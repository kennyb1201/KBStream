package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.SubtitleEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * PlayerView that wires KBStream's addon subtitle support (Stremio
 * "subtitles" resource) into the native player with zero changes to
 * NativePlayerActivity's playback flow.
 *
 * [onPlayerAttached] fires on every [setPlayer] call — including the ones
 * caused by recreatePlayer() after a source switch or a settings change —
 * so addon subtitle tracks survive every player rebuild. The activity only
 * needs this view type in its layout; everything else lives in
 * [AddonSubtitleController].
 */
class KBPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : PlayerView(context, attrs, defStyleAttr) {

    /** Invoked each time a non-null player is attached to this view. */
    var onPlayerAttached: ((Player) -> Unit)? = null

    /** Invoked when the view leaves the window (activity teardown). */
    var onViewDetached: (() -> Unit)? = null

    override fun setPlayer(player: Player?) {
        super.setPlayer(player)
        if (player != null) {
            onPlayerAttached?.invoke(player)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        onViewDetached?.invoke()
    }
}

/**
 * Fetches subtitle offers from every installed addon that declares the
 * Stremio "subtitles" resource and merges them into the live player as
 * sidecar text tracks.
 *
 * Sidecar tracks (rather than a hand-rolled list in the subtitle picker)
 * mean addon subtitles:
 *  - appear in the existing SUBTITLES picker next to embedded tracks,
 *  - toggle OFF correctly with the rest,
 *  - and render through SubtitleCueHandler, so the size / background /
 *    offset controls from player + global settings apply to them too.
 *
 * Tracks carry no selection flags and text tracks stay enabled, so nothing
 * auto-selects: picking a subtitle stays an explicit user action, matching
 * Stremio's behavior. The video id follows the same convention stream
 * addons already get: "tt…" for movies, "tt…:S:E" for series episodes.
 */
class AddonSubtitleController(
    context: Context,
    /**
     * True when the live session merges a separate audio source. Such
     * sessions cannot be rebuilt via setMediaItem without losing the
     * merged audio, so addon tracks are skipped for them (embedded and
     * external-file subtitles still work).
     */
    private val isSeparateAudioSession: () -> Boolean
) {

    companion object {
        private const val TAG = "AddonSubs"
        private const val FETCH_TIMEOUT_MS = 12_000L
        private const val MAX_TRACKS = 20
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = AddonRepository()

    /** Downloaded subtitle files: offer url -> cached file URI. */
    private val downloadCache = mutableMapOf<String, Uri>()

    private val downloadClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private var fetchJob: Job? = null
    private var offers: List<SubtitleEntry> = emptyList()
    private var fetchedForVideoId: String? = null
    private var pendingAttachTo: Player? = null

    /** Player the sidecar tracks were last attached to (idempotency). */
    private var attachedPlayer: Player? = null

    /**
     * Starts discovery for [parentId] and re-attaches tracks to every
     * player attached to [view] from now on. Safe to call repeatedly; the
     * fetch happens once per video id.
     */
    fun bind(
        view: KBPlayerView,
        parentId: String,
        parentType: String,
        season: Int?,
        episode: Int?
    ) {
        view.onPlayerAttached = { player ->
            onPlayerAttached(player)
        }
        view.onViewDetached = {
            release()
        }

        val videoId = if (parentType == "series" && season != null && episode != null) {
            "$parentId:$season:$episode"
        } else {
            parentId.takeIf { it.isNotBlank() }
        } ?: return

        fetchIfNeeded(videoId, parentType)
    }

    /** Cancels discovery and any pending work. Safe to call twice. */
    fun release() {
        fetchJob?.cancel()
        fetchJob = null
        pendingAttachTo = null
        scope.cancel()
    }

    private fun onPlayerAttached(player: Player) {
        if (offers.isEmpty()) {
            // Fetch still in flight (or nothing found): remember the player
            // so tracks attach the moment results arrive.
            pendingAttachTo = player
            return
        }
        attachTracks(player)
    }

    private fun fetchIfNeeded(videoId: String, parentType: String) {
        if (fetchJob?.isActive == true) return
        if (fetchedForVideoId == videoId) return
        fetchedForVideoId = videoId

        // Live channels have no Stremio video id; skip discovery entirely.
        if (parentType == "channel") return

        val contentType = if (parentType == "series") "series" else "movie"
        fetchJob = scope.launch(Dispatchers.IO) {
            val addons = AddonManager.getInstance(appContext)
                .getInstalledAddons()
                .filter { it.resources.contains("subtitles") }
            if (addons.isEmpty()) {
                Log.i(TAG, "No installed addon offers the 'subtitles' resource")
                return@launch
            }
            val collected = supervisorScope {
                addons.map { addon ->
                    async {
                        try {
                            val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json")
                            withTimeout(FETCH_TIMEOUT_MS) {
                                repository.getSubtitles(baseUrl, contentType, videoId)
                            }.filter { !it.url.isNullOrBlank() }
                        } catch (e: Exception) {
                            // One dead addon must not sink the rest; the
                            // picker just omits its offers.
                            Log.w(TAG, "Subtitle lookup failed for ${addon.displayName}", e)
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }

            val merged = collected
                .distinctBy { it.url }
                .take(MAX_TRACKS)

            withContext(Dispatchers.Main) {
                offers = merged
                pendingAttachTo?.let { player ->
                    attachTracks(player)
                }
                Log.i(TAG, "Addon subtitles found: ${merged.size}")
            }
        }
    }

    /**
     * Merges the fetched offers into [player] as sidecar text tracks,
     * downloading any not yet cached. Position is preserved so the video
     * does not restart, and playback is unaffected until the user picks a
     * track in the subtitle picker (prepare() keeps playWhenReady).
     */
    private fun attachTracks(player: Player) {
        if (offers.isEmpty()) return
        if (isSeparateAudioSession()) {
            Log.i(TAG, "Separate-audio session: skipping addon subtitle attach")
            return
        }
        if (attachedPlayer === player) return
        val currentMediaItem = player.currentMediaItem
        if (currentMediaItem == null) {
            // Progressive HLS/DASH sources built via setMediaSource only
            // expose their media item after prepare; retry once ready.
            val readyListener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        player.removeListener(this)
                        attachTracks(player)
                    }
                }
            }
            player.addListener(readyListener)
            return
        }
        // Skip when this media item already carries sidecar configs: either
        // we attached a moment ago (idempotency across setPlayer calls) or
        // the user loaded an external subtitle file, which must not be
        // clobbered by addon tracks.
        if (currentMediaItem.localConfiguration?.subtitleConfigurations?.isNotEmpty() == true) {
            attachedPlayer = player
            return
        }

        scope.launch(Dispatchers.IO) {
            val configs = offers.mapNotNull { offer ->
                val uri = downloadCache[offer.url] ?: download(offer.url) ?: return@mapNotNull null
                MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(resolveAddonSubtitleMime(offer.url))
                    .setLanguage(offer.lang)
                    .setLabel(offer.label ?: offer.lang?.uppercase())
                    .setSelectionFlags(0)
                    .build()
            }
            if (configs.isEmpty()) return@launch

            withContext(Dispatchers.Main) {
                // Re-check on the main thread: the media item can change
                // while we download (source switch / external file pick).
                val liveItem = player.currentMediaItem ?: return@withContext
                if (liveItem.localConfiguration?.subtitleConfigurations?.isNotEmpty() == true) return@withContext
                player.setMediaItem(
                    liveItem.buildUpon()
                        .setSubtitleConfigurations(configs)
                        .build(),
                    player.currentPosition
                )
                player.prepare()
                attachedPlayer = player
                Log.i(TAG, "Attached ${configs.size} addon subtitle track(s)")
            }
        }
    }

    /** Downloads [url] to app cache and returns its file URI, or null on failure. */
    private fun download(url: String): Uri? {
        return try {
            val request = okhttp3.Request.Builder().url(url).build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val ext = url
                    .substringBefore('?')
                    .substringAfterLast('.', missingDelimiterValue = "")
                    .lowercase()
                val safeExt = if (ext in setOf("srt", "vtt", "ssa", "ass")) ext else "srt"
                val file = java.io.File(
                    appContext.cacheDir,
                    "addon_sub_${System.nanoTime()}.$safeExt"
                )
                file.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                }
                val uri = Uri.fromFile(file)
                downloadCache[url] = uri
                uri
            }
        } catch (e: Exception) {
            Log.w(TAG, "Subtitle download failed: $url", e)
            null
        }
    }
}

/** Maps a subtitle URL to the Media3 MIME type its extension implies. */
private fun resolveAddonSubtitleMime(url: String): String {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
        path.endsWith(".ssa") || path.endsWith(".ass") -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}
