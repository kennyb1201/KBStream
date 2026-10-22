package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
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
import com.kennyb1201.kbstream.data.player.fileEpisodeStreamId
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

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

        /**
         * How many addon subtitle downloads run at once. Bounded, not
         * unbounded: this is a shared connection pool, and a batch of twenty
         * tracks against one host should not open twenty sockets. Four is
         * enough that a dead host's refused connections (which fail fast at
         * the kernel) overlap with the live ones rather than queueing in front
         * of them, and few enough to leave the decoder its headroom.
         */
        private const val BULK_DOWNLOAD_PARALLELISM = 4
    }

    private val appContext = context.applicationContext
    private var scope = newScope()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The controller's scope, recreated after [release] cancelled it.
     *
     * [release] is wired to the view's detach, but [bind] runs once per
     * activity - so a single mid-session detach used to kill addon-subtitle
     * discovery and track attachment for the rest of the session, silently:
     * [fetchIfNeeded] had already recorded the video id, so nothing retried.
     * Cancelling now only cancels the work in flight; the next use gets a fresh
     * scope.
     */
    private fun activeScope(): CoroutineScope {
        val job = scope.coroutineContext[Job]
        if (job == null || !job.isActive) scope = newScope()
        return scope
    }

    /**
     * Downloaded subtitle files: offer url -> cached file URI.
     *
     * Concurrent: [attachTracks] resolves the batch from up to
     * [BULK_DOWNLOAD_PARALLELISM] coroutines at once, so a plain map here was
     * a data race - and a corrupted one would hand a track the wrong file or
     * lose a completed download, which is a silent wrong-subtitles bug rather
     * than a crash.
     */
    private val downloadCache = ConcurrentHashMap<String, Uri>()

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
     * Optional bulk-status hook: how many offers resolved and how many did not,
     * called on the main thread once per attach attempt.
     *
     * A batch that fails completely has nothing to attach and used to be
     * entirely silent - only a Log.w - so the viewer saw a title with no
     * subtitles and no reason why. The caller decides what to say; a controller
     * with no listener behaves exactly as it did before.
     */
    var onBulkResult: ((succeeded: Int, failed: Int) -> Unit)? = null

    /**
     * Starts discovery for the video being played and re-attaches tracks to
     * every player attached to [view] from now on. Safe to call repeatedly; the
     * fetch happens once per video id.
     *
     * [episodeStreamId] is the session's own episode id, which is what the
     * stream was resolved with and therefore the video the add-ons know this
     * file by (see [addonSubtitleVideoId]).
     */
    fun bind(
        view: KBPlayerView,
        parentId: String,
        parentType: String,
        season: Int?,
        episode: Int?,
        episodeStreamId: String? = null
    ) {
        view.onPlayerAttached = { player ->
            onPlayerAttached(player)
        }
        view.onViewDetached = {
            release()
        }

        // A series that arrived without an episode id of its own has to name
        // the show the way the listing names it - the root TMDB verified, not
        // the flavor its route was opened with (see [addonSubtitleRootId]) - and
        // that resolve suspends, so it runs on this controller's own scope. A
        // session carrying its own id, and a film, answer with no lookup at all.
        if (parentType == "series" && episodeStreamId?.trim().isNullOrEmpty()) {
            activeScope().launch {
                val root = addonSubtitleRootId(appContext, parentId, parentType)
                addonSubtitleVideoId(
                    context = appContext,
                    parentId = parentId,
                    parentType = parentType,
                    sessionStreamId = episodeStreamId,
                    season = season,
                    episode = episode,
                    rootId = root
                )?.let { fetchIfNeeded(it, parentType) }
            }
            return
        }

        val videoId = addonSubtitleVideoId(
            context = appContext,
            parentId = parentId,
            parentType = parentType,
            sessionStreamId = episodeStreamId,
            season = season,
            episode = episode
        ) ?: return

        fetchIfNeeded(videoId, parentType)
    }

    /** Cancels discovery and any pending work. Safe to call twice. */
    fun release() {
        fetchJob?.cancel()
        fetchJob = null
        pendingAttachTo = null
        // Drop the per-video memo too: with the scope cancelled nothing could
        // have completed, and leaving it set is what made the next bind skip
        // discovery entirely. The next bind/fetch re-runs it once.
        fetchedForVideoId = null
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
        fetchJob = activeScope().launch(Dispatchers.IO) {
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

        activeScope().launch(Dispatchers.IO) {
            // The batch downloads CONCURRENTLY, under a bounded cap. Serially,
            // one dead host held every working track hostage: in the field log
            // six good tracks could not attach until all fourteen refused
            // attempts had drained, one blocking OkHttp call at a time.
            //
            // supervisorScope plus a per-offer catch: one track's unexpected
            // throw must not take its siblings down. The catch is load-bearing
            // rather than belt-and-suspenders - with a plain `async`, a throw
            // reaches awaitAll() and discards every other track's completed
            // download. (download() already returns null for its own failures;
            // runCatchingCancellable keeps a cancellation a cancellation.)
            val resolved = supervisorScope {
                val permits = Semaphore(BULK_DOWNLOAD_PARALLELISM)
                // ONE abort scope for this batch, and a new one for the next
                // attach (see [AddonSubtitleSource.BatchAbort]). It is what the
                // process-wide dead-host memo cannot be: the memo is written
                // when a failure LANDS, and by then this batch's other offers
                // to that host are already in flight, each holding one of the
                // four permits for its own full timeout while the batch's
                // working hosts queue behind them.
                val batch = AddonSubtitleSource.BatchAbort()
                offers.map { offer ->
                    async {
                        permits.withPermit {
                            runCatchingCancellable {
                                val uri = downloadCache[offer.url] ?: download(offer.url, batch)
                                uri?.let {
                                    ResolvedSubtitle(
                                        url = offer.url,
                                        language = offer.lang,
                                        label = addonSubtitleRowLabel(offer),
                                        uri = it,
                                        mime = resolveAddonSubtitleMime(offer.url, it)
                                    )
                                }
                            }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            // The ASS bookkeeping below stays SEQUENTIAL and in offer order.
            // awaitAll preserves that order, so the key map built here is the
            // one the serial version built and the ambiguity rule still sees
            // the same first-wins sequence.
            val assKeys = mutableMapOf<String, String>()
            val ambiguousAssKeys = mutableSetOf<String>()
            val configs = resolved.map { track ->
                val builder = MediaItem.SubtitleConfiguration.Builder(track.uri)
                    .setMimeType(track.mime)
                    .setLanguage(track.language)
                    .setLabel(track.label)
                    .setSelectionFlags(0)
                // Tag ASS offers so the player can route the selected track to
                // the libass overlay instead of rendering them flattened; a
                // non-ASS offer is left exactly as before.
                AddonAssTracks.configIdFor(track.mime, track.url)?.let { id ->
                    builder.setId(id)
                    val key = AddonAssTracks.languageKey(track.language, track.label)
                    val existing = assKeys[key]
                    if (existing == null) {
                        assKeys[key] = track.url
                    } else if (existing != track.url) {
                        // Two ASS tracks share language+label: the fallback key
                        // cannot name one of them, and guessing would paint the
                        // OTHER track's typesetting over the one selected. Drop
                        // the key - only the config id can route these, and
                        // flattened rendering is the safe default.
                        ambiguousAssKeys += key
                    }
                }
                builder.build()
            }

            // Report the batch BEFORE attaching, so the all-failed case - which
            // has nothing to attach and used to be silent apart from a Log.w -
            // still reaches the UI. On the main thread: the listener owns
            // views.
            withContext(Dispatchers.Main) {
                onBulkResult?.invoke(configs.size, offers.size - configs.size)
            }
            if (configs.isEmpty()) return@launch
            ambiguousAssKeys.forEach { assKeys.remove(it) }
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
    private fun download(url: String, batch: AddonSubtitleSource.BatchAbort?): Uri? =
        downloadCache[url]
            ?: AddonSubtitleSource.download(appContext, url, batch)?.also {
                downloadCache[url] = it
            }
}

/**
 * One offer whose download resolved, with the label and MIME decided at
 * download time.
 *
 * This exists so the concurrent half of [AddonSubtitleController.attachTracks]
 * can stay purely about downloading: the ASS key bookkeeping that follows is
 * sequential and in offer order, and needs the label and mime without
 * re-reading the offer or re-probing the file.
 */
private class ResolvedSubtitle(
    val url: String,
    val language: String?,
    val label: String?,
    val uri: Uri,
    val mime: String
)

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

    /**
     * Hosts whose last connection attempt failed, and when (elapsedRealtime
     * ms). A refused connection is usually a LAN/DNS problem that is not the
     * app's to fix, and one dead host answering a batch of offers would
     * otherwise be re-attempted once per track - twenty doomed connects, each
     * paying its own timeout, for one unreachable host.
     *
     * The memo EXPIRES, deliberately: this object is process-wide, so a host
     * that recovers (or a box that changes network) must get another chance
     * rather than staying blackholed for the life of the process.
     */
    private val deadHosts = ConcurrentHashMap<String, Long>()
    private const val DEAD_HOST_TTL_MS = 10 * 60 * 1000L

    /**
     * One fetch's abort scope: the hosts that have already failed while THIS
     * batch of offers was being fetched, and the calls that batch still has in
     * flight to each of them.
     *
     * The memo above is process-wide and time-based, and it cannot do this job.
     * It stops a dead host being re-attempted at all, but it is written when a
     * failure LANDS - and a batch starts its offers together, so by the time the
     * first one fails the batch's other offers to that host are already out,
     * each holding one of [BULK_DOWNLOAD_PARALLELISM]'s permits for its own
     * timeout. That is the field shape: six working tracks waiting while a host
     * that accepts the connection and never answers is asked once per track. A
     * batch-scoped scope can ABORT, because the first host-level failure cancels
     * the calls this batch still has to that host, and the queued offers behind
     * them are turned away before a request is even built.
     *
     * The identity is the BATCH, stated by construction: one instance per
     * attach, owned by that attach, garbage-collected with it. A new attach is a
     * new batch, so nothing carries over - the process-wide memo, with its TTL,
     * stays the only thing that outlives a fetch.
     */
    internal class BatchAbort {
        private val deadHosts = ConcurrentHashMap.newKeySet<String>()
        private val inFlight = ConcurrentHashMap<String, MutableSet<okhttp3.Call>>()

        /** Whether this batch has already seen [host] fail at connection level. */
        internal fun isDead(host: String): Boolean = host in deadHosts

        /** The calls this batch currently has out to [host], to register one in. */
        internal fun inFlightFor(host: String): MutableSet<okhttp3.Call> =
            inFlight.computeIfAbsent(host) { ConcurrentHashMap.newKeySet() }

        /**
         * [host] failed at connection level in this batch: nothing else this
         * batch has out to it can succeed, so every other call is cancelled and
         * the host is turned away for the rest of the batch. Cancelling an
         * already-finished call is a no-op, so the caller does not have to
         * exclude its own.
         */
        internal fun markDead(host: String) {
            deadHosts += host
            inFlightFor(host).forEach { call ->
                if (!call.isCanceled()) call.cancel()
            }
        }
    }

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
            // Backstop for the accept-then-stall hole: connect and read
            // timeouts are both per-read, so a server that accepts the
            // connection and then dribbles or wedges can hold a call (and now
            // one of the bulk download permits) indefinitely. A refused
            // connection already fails fast at TCP level - this bounds the
            // other case, it does not change the refused one.
            callTimeout(30, TimeUnit.SECONDS)
        }
    }

    /**
     * Subtitle offers for [videoId] ("tt…" for movies, "tt…:S:E" for series
     * episodes, in the same file numbering the stream was resolved with - see
     * [addonSubtitleVideoId]), from every installed addon that declares the
     * Stremio "subtitles" resource. A dead addon contributes nothing rather
     * than sinking the rest. Empty when no addon offers them.
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
    fun download(
        context: Context,
        url: String,
        /**
         * The abort scope of the batch this download belongs to, or null for a
         * one-off download (the pickers' "use this track" tap), where there is
         * no batch to abort and the process-wide memo is the only guard.
         */
        batch: BatchAbort? = null
    ): Uri? {
        val extension = url
            .substringBefore('?')
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .let { if (it in DiskSweep.SUBTITLE_EXTENSIONS) it else "srt" }

        // Held outside the try so the catch can name the host that just
        // failed. Null only when the URL builder refuses the url, which the
        // try reports the same way it always has.
        var host: String? = null

        return try {
            DiskSweep.existingSubtitleFile(context.applicationContext, url, extension)?.let {
                return Uri.fromFile(it)
            }

            val request = okhttp3.Request.Builder().url(url).build()
            val requestHost = request.url.host
            host = requestHost

            // This batch's own abort, before a connection is even opened: a host
            // that has already failed once while these offers were being
            // fetched is not worth a second one (see [BatchAbort]).
            if (batch?.isDead(requestHost) == true) {
                Log.d(TAG, "Aborted subtitle host for this batch: $requestHost")
                return null
            }

            // One refused host used to be re-attempted once per track in a
            // batch. Skip it instead, until the memo expires.
            deadHosts[requestHost]?.let { failedAt ->
                if (SystemClock.elapsedRealtime() - failedAt < DEAD_HOST_TTL_MS) {
                    Log.d(TAG, "Skipping recently-dead subtitle host: $requestHost")
                    return null
                }
                // Expired: the host gets another chance.
                deadHosts.remove(requestHost)
            }

            // Registered while it runs, so this batch can cancel it the moment
            // its host fails (see [BatchAbort]).
            val call = downloadClient.newCall(request)
            val inFlight = batch?.inFlightFor(requestHost)
            inFlight?.add(call)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) return null
                    val body = response.body ?: return null
                    val file =
                        DiskSweep.targetSubtitleFile(context.applicationContext, url, extension)
                    file.outputStream().use { out ->
                        body.byteStream().copyTo(out)
                    }
                    Uri.fromFile(file)
                }
            } finally {
                inFlight?.remove(call)
            }
        } catch (e: Exception) {
            // Only a HOST-level failure blackholes the host: one that refused,
            // does not resolve, stalls without ever answering, or never
            // finished its TLS handshake. An HTTP 500 or a corrupt body is
            // that track's problem, not the host's, and must not hide its
            // other subtitles.
            val failedHost = host
            if (failedHost != null && isConnectionFailure(e)) {
                deadHosts[failedHost] = SystemClock.elapsedRealtime()
                // And abort the rest of THIS batch's offers to it: the calls
                // already out give their permits back now, instead of one full
                // read timeout from now.
                batch?.markDead(failedHost)
            }
            Log.w(TAG, "Subtitle download failed: $url", e)
            null
        }
    }

    /**
     * True when [e] says something about the HOST rather than about the file
     * being fetched: it refused the connection, does not resolve, timed out
     * (connect or read - the accept-then-stall shape, where the connection is
     * up and nothing ever answers), or never finished its TLS handshake.
     *
     * [SocketTimeoutException] belongs here and used to be missing. It is the
     * accept-then-stall host's own exception: one such host was re-attempted
     * once per track of every picker open instead of being parked with the
     * other dead hosts, which is the whole reason the memo exists. A per-read
     * timeout cannot be told apart from a slow host at this level, and
     * [DEAD_HOST_TTL_MS] bounds the cost of that: the host gets another chance
     * ten minutes later. The download client's own call timeout - a server that
     * IS answering, just not fast enough to finish a subtitle inside 30 s -
     * throws its parent `InterruptedIOException` and is deliberately NOT in the
     * set: that host is slow, not dead.
     *
     * `internal` rather than private so the classification itself is unit
     * tested: the rule that an HTTP 500 must NOT blackhole a host is the whole
     * reason this is a named predicate instead of an inline `is` chain.
     */
    internal fun isConnectionFailure(e: Exception): Boolean =
        e is ConnectException || e is UnknownHostException ||
            e is SocketTimeoutException || e is SSLException
}

/**
 * What one add-on subtitle is CALLED, for a picker row.
 *
 * The add-on's own label first, and - when that label does not already name it -
 * the kind the offer's URL names. The URL is the one piece of evidence the
 * picker cannot recover later: media3 carries a label and a language on the
 * track, and no field for the URL, so an offer whose label says only "English"
 * would otherwise lose the ".sdh." in its own file name and read as a plain
 * track next to two others that read the same.
 *
 * Null only when the offer names itself nowhere - the shape this returned
 * before, and the one the picker's own fallback exists for.
 */
internal fun addonSubtitleRowLabel(offer: SubtitleEntry): String? {
    val base = offer.label ?: offer.lang?.uppercase()
    val urlKind = SubtitleKindRules.tagText(offer.url)
        ?.takeIf { base == null || SubtitleKindRules.tagText(base) == null }
    return listOfNotNull(base, urlKind)
        .joinToString(" \u00b7 ")
        .takeIf { it.isNotBlank() }
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

/**
 * The video id a subtitles add-on is asked for: the SAME id the stream was
 * resolved with.
 *
 * An add-on publishes its subtitles against the video it was asked to resolve,
 * and that id is FILE numbering (see EpisodeScheme): on a show whose files hold
 * two TMDB segments each, the TMDB episode's own number names a file that does
 * not hold it, so the lookup answers with a different episode's subtitles - or
 * with none at all.
 *
 * [sessionStreamId] is the session's own episode id, which is exactly that file
 * id, so it is used as it stands. A session without one - a deep link, an older
 * handoff - falls back to the TMDB episode mapped through the show's stored
 * scheme, and to the plain TMDB id when even that is unknown. A film answers
 * with [parentId], the id an add-on already keys it by.
 *
 * One function for both engines: the native player binds its controller with
 * it, the MPV engine builds its own offers with it (see MpvPlayerActivity),
 * and the two cannot drift into asking about different videos.
 */
internal fun addonSubtitleVideoId(
    context: Context,
    parentId: String,
    parentType: String,
    sessionStreamId: String?,
    season: Int?,
    episode: Int?,
    rootId: String? = null
): String? {
    if (parentType != "series" || season == null || episode == null) {
        return parentId.takeIf { it.isNotBlank() }
    }
    sessionStreamId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return fileEpisodeStreamId(
        context = context,
        // The show's VERIFIED root when the caller resolved one (see
        // [addonSubtitleRootId]). A route opened as "tmdb:<n>", or with a
        // catalog id that names no title TMDB knows, is not an id an add-on
        // publishes subtitles against - and the episode scheme this mapping
        // reads is filed under the verified root too, so keyed by the route's
        // flavor it answered 1:1 for a segmented show.
        rootId = rootId?.takeIf { it.isNotBlank() } ?: parentId,
        tmdbId = null,
        season = season,
        tmdbEpisode = episode
    )
}

/**
 * The show's verified stream root, for the one case [addonSubtitleVideoId] cannot
 * answer from the session alone: a series that arrived with no episode id (a
 * deep link, an older handoff).
 *
 * The root it returns is the id every episode id is built on - TMDB's own
 * external-ids answer when it has one, the route's id when it does not (see
 * TmdbRepository.streamRootIdFor) - so the fallback asks the add-on about the
 * same show the season listing would, whatever flavor the route was opened
 * with. A film, a live channel or a blank parent has nothing to resolve: null,
 * which leaves [addonSubtitleVideoId] on its previous behavior.
 *
 * Bounded and cached, because this runs on the window from bind to the first
 * fetch: the resolution is memory- and disk-cached, and a slow network leaves
 * the fallback id unrooted rather than holding the subtitle fetch up.
 */
internal suspend fun addonSubtitleRootId(
    context: Context,
    parentId: String,
    parentType: String,
    tmdbId: Int? = null
): String? {
    if (parentType != "series" || parentId.isBlank()) return null
    return withTimeoutOrNull(2_500L) {
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val repository = TmdbRepository.getInstance(context)
                val tmdb = tmdbId?.takeIf { it > 0 }
                    ?: PlaybackHistoryIds.resolveTmdbId(context, parentId, parentType)
                repository.streamRootIdFor(parentId, tmdb, "series")
            }.getOrNull()
        }
    }
}
