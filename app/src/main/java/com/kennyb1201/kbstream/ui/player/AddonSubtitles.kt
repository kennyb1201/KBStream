package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.net.Uri
import com.kennyb1201.kbstream.data.cache.DiskSweep
import android.util.AttributeSet
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.SubtitleEntry
import com.kennyb1201.kbstream.data.network.BaseHttpClient
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
import java.io.File
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
            // A specific audio track chosen for this show has to be re-applied
            // to every player instance (the activity's own setup only knows
            // languages); the bridge tracks that per title.
            PlayerTrackBridge.onPlayerAttached(player)
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
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Downloaded subtitle files: offer url -> cached file URI. */
    private val downloadCache = mutableMapOf<String, Uri>()

    private var fetchJob: Job? = null
    private var offers: List<SubtitleEntry> = emptyList()
    private var fetchedForVideoId: String? = null
    private var pendingAttachTo: Player? = null

    /** Player the sidecar tracks were last attached to (idempotency). */
    private var attachedPlayer: Player? = null

    /**
     * Addon ASS offers by "language|label", for the fallback when media3 does
     * not carry a config id through to the track's Format (see [assSourceFor]).
     */
    @Volatile
    private var assOffersByKey: Map<String, String> = emptyMap()

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
            val merged = AddonSubtitleSource.discover(appContext, videoId, contentType)
            withContext(Dispatchers.Main) {
                offers = merged
                pendingAttachTo?.let { player ->
                    attachTracks(player)
                }
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
            val assKeys = mutableMapOf<String, String>()
            val configs = offers.mapNotNull { offer ->
                val uri = downloadCache[offer.url] ?: download(offer.url) ?: return@mapNotNull null
                val label = offer.label ?: offer.lang?.uppercase()
                val mime = resolveAddonSubtitleMime(offer.url, uri)
                val builder = MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(mime)
                    .setLanguage(offer.lang)
                    .setLabel(label)
                    .setSelectionFlags(0)
                // Tag ASS offers so the player can route the selected track to
                // the libass overlay instead of rendering them flattened; a
                // non-ASS offer is left exactly as before.
                AddonAssTracks.configIdFor(mime, offer.url)?.let { id ->
                    builder.setId(id)
                    assKeys[AddonAssTracks.languageKey(offer.lang, label)] = offer.url
                }
                builder.build()
            }
            if (configs.isEmpty()) return@launch
            assOffersByKey = assKeys

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

    /**
     * The addon ASS source URL [format] names, or null when it is not an addon
     * ASS track. Tries the config id media3 should have carried through first
     * (see [AddonAssTracks]), then falls back to language + label.
     */
    fun assSourceFor(format: Format): String? =
        AddonAssTracks.urlFromConfigId(format.id)
            ?: assOffersByKey[AddonAssTracks.languageKey(format.language, format.label)]

    /**
     * Downloads [url] into the shared subtitle cache, or returns the copy
     * already there.
     *
     * The file name is derived from the URL (see DiskSweep) rather than from
     * the clock, which is what makes that second part possible: the previous
     * `addon_sub_${System.nanoTime()}` name could never collide with an earlier
     * download of the same track, so every use re-downloaded it AND left
     * another file behind, forever.
     */
    private fun download(url: String): Uri? =
        downloadCache[url]
            ?: AddonSubtitleSource.download(appContext, url)?.also { downloadCache[url] = it }
}

/**
 * Engine-agnostic half of addon subtitle support: the offers the installed
 * addons publish for one video id, and download of one offer into the shared
 * subtitle cache.
 *
 * [AddonSubtitleController] merges those into an ExoPlayer as sidecar tracks;
 * the MPV picker downloads one on tap and hands the file to libmpv. Both sit
 * on this, so the two engines offer the same subtitles and the two caches
 * cannot drift.
 */
internal object AddonSubtitleSource {

    private const val TAG = "AddonSubs"
    private const val FETCH_TIMEOUT_MS = 12_000L
    private const val MAX_TRACKS = 20

    private val repository = AddonRepository.getInstance()

    /**
     * Derived from the process-wide base client. A private builder per session
     * meant a fresh connection pool, dispatcher and thread pool for every
     * title opened - for subtitle downloads, on the one screen where the
     * decoder wants the headroom.
     */
    private val downloadClient by lazy {
        BaseHttpClient.derived {
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(20, TimeUnit.SECONDS)
        }
    }

    /**
     * Subtitle offers for [videoId] ("tt…" for movies, "tt…:S:E" for series
     * episodes), from every installed addon that declares the Stremio
     * "subtitles" resource. A dead addon contributes nothing rather than
     * sinking the rest. Empty when no addon offers them.
     */
    suspend fun discover(
        context: Context,
        videoId: String,
        contentType: String
    ): List<SubtitleEntry> {
        val addons = AddonManager.getInstance(context.applicationContext)
            .getEnabledAddons()
            .filter { it.resources.contains("subtitles") }
        if (addons.isEmpty()) {
            Log.i(TAG, "No installed addon offers the 'subtitles' resource")
            return emptyList()
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
                        Log.w(TAG, "Subtitle lookup failed for ${addon.displayName}", e)
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        val merged = collected.distinctBy { it.url }.take(MAX_TRACKS)
        Log.i(TAG, "Addon subtitles found: ${merged.size}")
        return merged
    }

    /**
     * Downloads [url] into the shared subtitle cache, or returns the copy
     * already there.
     *
     * The file name is derived from the URL (see DiskSweep) rather than from
     * the clock, so a second use of the same track reuses one file instead of
     * downloading it again and leaving another behind.
     */
    fun download(context: Context, url: String): Uri? {
        val extension = url
            .substringBefore('?')
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .let { if (it in DiskSweep.SUBTITLE_EXTENSIONS) it else "srt" }

        return try {
            DiskSweep.existingSubtitleFile(context.applicationContext, url, extension)?.let {
                return Uri.fromFile(it)
            }

            val request = okhttp3.Request.Builder().url(url).build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val file = DiskSweep.targetSubtitleFile(context.applicationContext, url, extension)
                file.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                }
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Subtitle download failed: $url", e)
            null
        }
    }
}

/**
 * Maps a downloaded addon subtitle to the Media3 MIME type it actually holds.
 *
 * The URL's extension is the first answer and the cheap one - but it is not
 * the only one, and it used to be the ONLY one. A Stremio subtitle addon
 * routinely serves a numeric file id with no extension at all, so an ASS
 * script fell through to the SubRip default, the SubRip parser found no cues,
 * and the track the viewer picked looked empty. When the extension does not
 * settle it, sniff the downloaded file - the same content probe the external
 * file path already uses (see [AssSubtitleSource.isAssContent]).
 */
private fun resolveAddonSubtitleMime(url: String, local: Uri?): String =
    resolveSubtitleMimeFrom(url, local?.let { readSubtitleProbe(it) } ?: "")

/**
 * The decision behind [resolveAddonSubtitleMime], split out so it can be unit
 * tested without a Uri or a file: the URL's extension first, then the content
 * probe (empty when there is nothing to sniff). See the call site's KDoc for
 * why the probe exists.
 */
internal fun resolveSubtitleMimeFrom(url: String, contentProbe: String): String {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    when {
        path.endsWith(".vtt") -> return MimeTypes.TEXT_VTT
        path.endsWith(".ssa") || path.endsWith(".ass") -> return MimeTypes.TEXT_SSA
    }
    if (AssSubtitleSource.isAssContent(contentProbe)) return MimeTypes.TEXT_SSA
    return MimeTypes.APPLICATION_SUBRIP
}

/**
 * The first few KB of a downloaded subtitle, as text, for a content sniff.
 *
 * [AssSubtitleSource.isAssContent] only reads the opening lines, so a small
 * probe is enough and avoids pulling a whole multi-megabyte script into
 * memory. Best-effort: an unreadable file probes as empty and keeps the
 * SubRip default rather than failing the attach.
 */
private fun readSubtitleProbe(uri: Uri): String = runCatching {
    val path = uri.path ?: return@runCatching ""
    File(path).inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(4096)
        val read = reader.read(buffer)
        if (read <= 0) "" else String(buffer, 0, read)
    }
}.getOrDefault("")
