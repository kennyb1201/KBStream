package com.kennyb1201.kbstream.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.kennyb1201.kbstream.FormFactor
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.debrid.TorBoxResolve
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.player.StreamUserAgent
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * The app's one download manager (spec §3): the Media3 `DownloadManager`, the
 * cache its bytes live in, and the records this device keeps about them.
 *
 * ## Why Media3 and not a byte copier
 *
 * A progressive file is a copy, but an HLS row is a playlist of segments:
 * copying "the file" is not a thing you can do to it. `DownloadManager` already
 * downloads segments, resumes a half-finished transfer, tracks per-download
 * progress and holds a download for a network reason - and every one of those is
 * behaviour this feature needs rather than behaviour it would otherwise write
 * twice. The manager is created once, at application start, and the
 * `DownloadsService` is the only thing that writes.
 *
 * ## Where the bytes are (and what that means for playback)
 *
 * Media3's downloads are stored in a `SimpleCache`, keyed by their source URI
 * (or by a custom cache key), NOT as a standalone file on disk - and Media3
 * provides no export-to-file API. So playback of a download is not "open the
 * local file": it is the player reading the same URI through a cache data source
 * that is pointed at this cache ([playbackDataSourceFactory]). See that method
 * for why the cache is also the reason playback can never silently fall back to
 * the network, which is the property §6 actually asks for.
 *
 * ## What this object is, and is not, on a television
 *
 * Nothing here can produce a download on a TV: [enqueue] refuses outright on that
 * form factor, and no TV screen calls it. [ensure] still builds the manager on
 * any form factor, because the `DownloadsService` must be able to answer
 * `getDownloadManager()` if the platform ever starts it - a null there is a
 * crash, and a crash is a worse answer than an idle manager (spec §8's P0 case).
 */
object DownloadsManager {

    private const val TAG = "DOWNLOADS"

    /**
     * The stop reason this app sets when the VIEWER paused one download.
     *
     * Media3 has no pause-one-download call - `sendPauseDownloads` stops every
     * transfer - so a row's own pause is a stop reason on that row alone, and
     * resuming it is the same call with [Download.STOP_REASON_NONE]. The value
     * is the app's to choose; anything but zero means "do not run this one".
     */
    private const val STOP_REASON_PAUSED = 1

    /**
     * The user agent every download fetch uses.
     *
     * Resolved from an empty header map, which is [StreamUserAgent]'s own
     * documented default: a download is not one source's request, it is many
     * (a playlist and its segments, a redirect chain), and the per-record
     * headers are injected per URL by [recordHeaders] instead.
     */
    private val downloadUserAgent: String by lazy { StreamUserAgent.resolve(emptyMap()) }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kbstream-downloads")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var manager: DownloadManager? = null

    @Volatile
    private var cache: SimpleCache? = null

    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())

    /**
     * The active profile's downloads, as the app should draw them right now.
     *
     * Held in memory and refreshed from the downloader: a progress tick is not
     * worth a disk write, and the durable half of a record only changes when its
     * state does (see [refresh]).
     */
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    /**
     * Builds the manager on first use. Safe to call from anywhere; a television
     * gets an idle manager it never enqueues on.
     *
     * The `SimpleCache` holds an exclusive lock on its directory, so this is the
     * ONLY cache instance for downloads in the process and there is exactly one
     * per device - which is also why the per-profile split lives in the records
     * and the cache keys, not in the directory (see [DownloadsFiles]).
     */
    @Synchronized
    fun ensure(context: Context): DownloadManager? {
        manager?.let { return it }
        val app = context.applicationContext
        val dir = DownloadsFiles.directory(app)
        if (dir == null) {
            Log.w(TAG, "no usable download directory; downloads are unavailable here")
            return null
        }
        return runCatching {
            val database = StandaloneDatabaseProvider(app)
            val downloadCache = SimpleCache(dir, NoOpCacheEvictor(), database)
            val downloadManager = DownloadManager(
                app,
                database,
                downloadCache,
                OkHttpDataSource.Factory(downloadClient(app)).setUserAgent(downloadUserAgent),
                executor
            )
            downloadManager.addListener(object : DownloadManager.Listener {
                override fun onInitialized(downloadManager: DownloadManager) {
                    refresh(app, persist = false)
                }

                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?
                ) {
                    finalException?.let {
                        Log.w(TAG, "download ${download.request.id} reported ${it.javaClass.simpleName}")
                    }
                    // Progress is in-memory; a terminal state is worth a write,
                    // because it is what has to survive the process.
                    val terminal = download.state == Download.STATE_COMPLETED ||
                        download.state == Download.STATE_FAILED
                    refresh(app, persist = terminal)
                }

                override fun onDownloadRemoved(
                    downloadManager: DownloadManager,
                    download: Download
                ) {
                    refresh(app, persist = true)
                }
            })
            cache = downloadCache
            manager = downloadManager
            downloadManager
        }.onFailure {
            Log.w(TAG, "download manager could not start: ${it.message}")
            // A cache that cannot open must not take the app with it: the
            // feature degrades to "no downloads", the way every other
            // startup-time subsystem here degrades.
        }.getOrNull()
    }

    /**
     * Application start on a phone: build the manager, apply the Wi-Fi rule, and
     * pick up anything a previous process left unfinished.
     *
     * The resume is §7's "the download resumes on next launch": the download
     * index on disk knows what was in flight, but nothing is running until the
     * service is told to look. Deliberately not called on a TV - there is nothing
     * to resume, and starting the service there is the one way this feature
     * could produce a notification on a television.
     */
    fun start(context: Context) {
        if (FormFactor.isTv) return
        val app = context.applicationContext
        val downloadManager = ensure(app) ?: return
        runCatching { applyNetworkRequirement(app, downloadManager) }
        runCatching { DownloadService.start(app, DownloadsService::class.java) }
        refresh(app, persist = false)
    }

    /**
     * Re-reads the records and merges them with the downloader's own state.
     *
     * @param persist whether the durable store should take the merged result.
     *   False for a progress tick or a first read; true whenever a state could
     *   have changed in a way that must outlive the process (enqueue, pause,
     *   resume, delete, a terminal state).
     */
    fun refresh(context: Context, persist: Boolean = true) {
        val app = context.applicationContext
        val stored = DownloadsStore.records(app)
        val downloadManager = manager
        val live = readLive(downloadManager)
        val lowOnSpace = isLowOnSpace(app)
        val now = System.currentTimeMillis()

        val merged = stored.mapNotNull { record ->
            // `live.readOk` rather than "the map is not empty": an empty map is
            // also what a device with no downloads in flight looks like, and a
            // failed read must never be read as "the downloader forgot every
            // download" - it would fail every row on a device that hiccuped.
            reconcile(record, live.byId[record.id], live.readOk, lowOnSpace, now)
        }

        // A download that failed because the device ran out of room must not
        // leave its partial bytes behind (spec §7): the row stays, with the
        // reason, and the corpse goes. The removal frees the cache entry, which
        // is what makes the header total and the device's free space agree
        // again.
        if (live.readOk) {
            merged.filter { it.reason == DownloadSpace.OUT_OF_SPACE_REASON }
                .forEach { failed ->
                    downloadManager?.let { removeFromDownloader(app, it, failed.id) }
                }
        }

        _records.value = merged
        if (persist) {
            // The store ends up as what the app is showing: a record the
            // downloader has dropped is gone from the store as well, so the
            // header's total and the empty state are written from live rows and
            // never from a tombstone.
            runCatching { DownloadsStore.putAll(app, merged) }
                .onFailure { Log.w(TAG, "could not persist download records: ${it.message}") }
        }
    }

    /** The active profile's records, without touching prefs. */
    fun currentRecords(): List<DownloadRecord> = _records.value

    /**
     * Starts a download for one picked row (spec §2.3: the row IS the choice -
     * nothing here re-picks or re-ranks).
     */
    suspend fun enqueue(
        context: Context,
        stream: Stream,
        target: DownloadTarget
    ): DownloadEnqueueResult {
        val app = context.applicationContext
        if (FormFactor.isTv) return DownloadEnqueueResult.Refused(TV_REFUSED)
        val downloadManager = ensure(app) ?: return DownloadEnqueueResult.Refused(NO_STORAGE)

        val gate = DownloadGates.evaluate(
            stream = stream,
            isLiveChannel = target.parentType == "channel",
            online = isOnline(app)
        )
        when (gate) {
            DownloadGate.Hidden -> return DownloadEnqueueResult.Refused(LIVE_REFUSED)
            is DownloadGate.Disabled -> return DownloadEnqueueResult.Refused(gate.reason)
            DownloadGate.Offline -> return DownloadEnqueueResult.Refused(DownloadGates.CONNECT_TO_DOWNLOAD)
            is DownloadGate.Allowed -> Unit
        }

        // A hash-only row resolves first, through the very path a stream press
        // uses - and a resolve that fails is this download's failure, with the
        // resolver's own words (spec §5).
        val resolved = if (gate.needsResolve) {
            runCatching { TorBoxResolve.playableStream(app, stream) }.getOrNull()
                ?: return DownloadEnqueueResult.Refused(DownloadFailureReasons.RESOLVE_FAILED)
        } else {
            stream
        }
        val url = resolved.url?.trim().orEmpty()
        if (url.isBlank()) {
            return DownloadEnqueueResult.Refused(DownloadFailureReasons.NO_SOURCE)
        }
        val profileId = com.kennyb1201.kbstream.data.sync.ProfileStorage.activeProfileId(app)
        val mediaKey = DownloadIdentity.mediaKey(target.parentId, target.parentId)
        val rowKey = DownloadIdentity.rowKey(stream)
        val key = DownloadIdentity.key(profileId, mediaKey, target.season, target.episode, rowKey)
        val id = DownloadIdentity.downloadId(key)

        // Identity is the dedup: the same row asked for twice is one download
        // (spec §3), and the second ask is told so rather than starting a second
        // transfer of the same bytes.
        DownloadsStore.records(app).firstOrNull { it.key == key }?.let { existing ->
            return when {
                existing.state.isComplete -> DownloadEnqueueResult.Refused(ALREADY_DOWNLOADED)
                else -> DownloadEnqueueResult.Started(existing.id)
            }
        }

        // Known length is refused BEFORE a byte is written; unknown length
        // starts and lets the downloader's own failure speak (spec §3).
        val mimeType = resolvedMimeType(url)
        val length = probeLength(url, resolved.requestHeaders, mimeType)
        val free = DownloadsFiles.directory(app)?.usableSpace ?: 0L
        if (!DownloadSpace.hasRoomFor(free, length)) {
            return DownloadEnqueueResult.Refused(DownloadSpace.NOT_ENOUGH_SPACE)
        }

        val customCacheKey = if (isAdaptive(mimeType, url)) null else id
        val request = DownloadRequest.Builder(id, Uri.parse(url))
            .apply {
                mimeType?.let { setMimeType(it) }
                customCacheKey?.let { setCustomCacheKey(it) }
            }
            .build()

        val now = System.currentTimeMillis()
        val record = DownloadRecord(
            id = id,
            key = key,
            profileId = profileId,
            mediaKey = mediaKey,
            season = target.season,
            episode = target.episode,
            title = target.title,
            episodeLabel = target.episodeLabel,
            qualityLabel = target.qualityLabel,
            posterUrl = target.posterUrl,
            parentType = target.parentType,
            parentId = target.parentId,
            episodeStreamId = target.episodeStreamId,
            sourceUrl = url,
            customCacheKey = customCacheKey,
            mimeType = mimeType,
            sourceHeaders = resolved.requestHeaders,
            sourceName = stream.name,
            sourceTitle = stream.title,
            sourceDescription = stream.description,
            infoHash = stream.infoHash,
            fileIdx = stream.fileIdx,
            state = DownloadState.QUEUED,
            totalBytes = length ?: DownloadSpace.UNKNOWN_LENGTH,
            addedAt = now,
            updatedAt = now
        )
        DownloadsStore.put(app, record)
        runCatching { applyNetworkRequirement(app, downloadManager) }
        DownloadService.sendAddDownload(app, DownloadsService::class.java, request, false)
        refresh(app, persist = true)
        Log.i(TAG, "enqueued ${record.id} (${record.qualityLabel})")
        return DownloadEnqueueResult.Started(id)
    }

    fun pause(context: Context, id: String) {
        val app = context.applicationContext
        runCatching {
            DownloadService.sendSetStopReason(
                app,
                DownloadsService::class.java,
                id,
                STOP_REASON_PAUSED,
                false
            )
        }.onFailure { Log.w(TAG, "pause $id failed: ${it.message}") }
        markLocally(app, id) { it.copy(state = DownloadState.PAUSED, percent = null) }
        // Persisted even though the downloader records the stop itself: a paused
        // row is what the list shows between now and the next merge, and a
        // process that dies in that window should come back to the same picture.
        refresh(app, persist = true)
    }

    fun resume(context: Context, id: String) {
        val app = context.applicationContext
        runCatching {
            DownloadService.sendSetStopReason(
                app,
                DownloadsService::class.java,
                id,
                Download.STOP_REASON_NONE,
                false
            )
        }.onFailure { Log.w(TAG, "resume $id failed: ${it.message}") }
        markLocally(app, id) { it.copy(state = DownloadState.QUEUED, reason = null) }
        refresh(app, persist = true)
    }

    /**
     * Deletes one download: its bytes go with its record.
     *
     * `sendRemoveDownload` is what frees the cache entry, so the space a viewer
     * reclaims is exactly that download's bytes and the header total drops by
     * exactly its size (spec §9).
     */
    fun delete(context: Context, id: String) {
        val app = context.applicationContext
        manager?.let { removeFromDownloader(app, it, id) }
        DownloadsStore.remove(app, id)
        refresh(app, persist = true)
    }

    /**
     * Retry, which is a RE-RESOLVE and not a retry of a dead URL (spec §7).
     *
     * The old record and its partial bytes go first, then the row is enqueued
     * again from what it was - so a fresh debrid link is what the new attempt
     * fetches, and a row that no longer resolves anywhere stays Failed with the
     * resolver's reason and no bytes.
     */
    suspend fun retry(context: Context, id: String): DownloadEnqueueResult {
        val app = context.applicationContext
        val record = DownloadsStore.records(app).firstOrNull { it.id == id }
            ?: return DownloadEnqueueResult.Refused(NOT_FOUND)
        manager?.let { removeFromDownloader(app, it, id) }
        DownloadsStore.remove(app, id)
        refresh(app, persist = true)
        return enqueue(app, record.retrySource(), record.downloadTarget())
    }

    /**
     * Re-applies the Wi-Fi rule. Called when either toggle is flipped, so the
     * change takes effect on downloads already queued rather than only on the
     * next one.
     */
    fun onTogglesChanged(context: Context) {
        val app = context.applicationContext
        val downloadManager = ensure(app) ?: return
        runCatching { applyNetworkRequirement(app, downloadManager) }
        refresh(app, persist = false)
    }

    /** Bytes the completed downloads on this phone hold. Partials never count (spec §7). */
    fun totalBytes(context: Context): Long =
        DownloadsStore.records(context.applicationContext)
            .filter { it.state.isComplete }
            .sumOf { it.totalBytes.coerceAtLeast(0L) }

    /**
     * Whether a download exists for one of [mediaKeys] at the given episode - the
     * Downloaded badge's question.
     *
     * A set of keys rather than one because a title id has flavours (an IMDb id,
     * `tmdb:<n>`, the catalog's own id) and the screen asking the question does
     * not always know which one the download was filed under. Season/episode are
     * part of the match so a downloaded S01E02 does not badge the whole show.
     */
    fun isDownloaded(
        context: Context,
        mediaKeys: Collection<String>,
        season: Int?,
        episode: Int?
    ): Boolean {
        val keys = mediaKeys.filter { it.isNotBlank() }.toSet()
        if (keys.isEmpty()) return false
        return DownloadsStore.records(context.applicationContext).any { record ->
            record.state.isComplete &&
                record.mediaKey in keys &&
                record.season == season &&
                record.episode == episode
        }
    }

    /**
     * The "delete after finishing" half of the settings toggle (spec §4.5).
     *
     * Called once a completion has been FILED - not during playback - so the
     * viewer finishes the episode and the download goes afterwards. Off by
     * default, and a no-op when it is off or when nothing matches.
     */
    fun onTitleCompleted(context: Context, mediaKeys: Collection<String>) {
        val app = context.applicationContext
        if (!DownloadsStore.getDeleteAfterFinish(app)) return
        val keys = mediaKeys.filter { it.isNotBlank() }.toSet()
        if (keys.isEmpty()) return
        DownloadsStore.records(app)
            .filter { it.state.isComplete && it.mediaKey in keys }
            .forEach { delete(app, it.id) }
    }

    /**
     * Profile deletion: that profile's records and its bytes go, and no other
     * profile's are touched (spec §7).
     *
     * The records live in a preference namespace the profile wipe already clears,
     * so this exists for the BYTES: each download has to leave the downloader's
     * index, which is what frees its cache entry. Called before the wipe, while
     * the records can still be read.
     */
    fun deleteAllForProfile(context: Context, profileId: String) {
        if (profileId.isBlank()) return
        val app = context.applicationContext
        // Read THAT profile's own store, not the active one: a profile deleted
        // while another is on screen still has downloads to remove, and the
        // active profile's records are not its own.
        val mine = DownloadsStore.recordsFor(app, profileId)
        if (mine.isEmpty()) return
        scope.launch {
            val downloadManager = ensure(app) ?: return@launch
            mine.forEach { removeFromDownloader(app, downloadManager, it.id) }
            refresh(app, persist = true)
        }
    }

    // ── Playback of a download ────────────────────────────────────────────────

    /** The record a playback launch names, or null when this phone no longer has it. */
    fun playbackRecord(context: Context, downloadId: String?): DownloadRecord? =
        downloadId?.takeIf { it.isNotBlank() }
            ?.let { id -> DownloadsStore.records(context.applicationContext).firstOrNull { it.id == id } }

    /**
     * Whether the bytes are here to play.
     *
     * Both halves are required: the record must say Downloaded, and the
     * downloader must still hold it as completed. A record that survived but
     * whose cache entry did not (storage cleared behind the app, a partial
     * delete) answers false, which is what turns into "This download is no
     * longer on this phone" instead of a player that opens and then fails
     * halfway in (spec §6).
     */
    fun isPlayable(context: Context, downloadId: String?): Boolean {
        val app = context.applicationContext
        val record = playbackRecord(app, downloadId) ?: return false
        val downloadManager = ensure(app) ?: return false
        val live = readLive(downloadManager)
        // A downloader that could not be asked is not a downloader that has
        // lost the bytes: the record's own answer stands, because refusing a
        // play the viewer can see in the list would turn a hiccup into "this
        // download is no longer on this phone".
        if (!live.readOk) return record.state.isComplete
        return isPlayable(record, live.byId[record.id])
    }

    /** Marks a record whose bytes have gone, so the list offers a Retry rather than a Play. */
    fun markMissing(context: Context, downloadId: String) {
        val app = context.applicationContext
        markLocally(app, downloadId) {
            it.copy(
                state = DownloadState.FAILED,
                reason = DownloadFailureReasons.MISSING,
                percent = null
            )
        }
        refresh(app, persist = true)
    }

    /**
     * The data source a downloaded session reads through: this cache, and no
     * network at all.
     *
     * That second half is the whole point. Media3 stores a download in its cache
     * rather than exporting a file, so "play the local file" is "read this URI
     * from this cache" - and the one thing that must never happen is a cache miss
     * quietly becoming a network fetch of the source URL, which would look like
     * it worked on the sofa and fail on the plane. So the upstream factory here
     * is an OkHttp client whose only interceptor refuses every request: a cache
     * miss fails the session with the same words the launch-time check uses,
     * instead of streaming (spec §6's last bullet).
     */
    fun playbackDataSourceFactory(context: Context, record: DownloadRecord): DataSource.Factory? {
        val downloadCache = cache ?: return null
        return CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(offlineClient()).setUserAgent(downloadUserAgent)
            )
            // Read-only: a playback must not write cache entries of its own, or
            // the download's byte accounting stops matching what is on disk.
            .setCacheWriteDataSinkFactory(null)
    }

    /** The MediaItem cache key a downloaded session must carry, or null for adaptive rows. */
    fun playbackCacheKey(record: DownloadRecord): String? = record.customCacheKey

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * The downloader's view of every download it knows, and whether reading it
     * actually worked - which is the difference between "this download is gone"
     * and "this device could not be asked".
     */
    private data class LiveSnapshot(
        val byId: Map<String, LiveDownload>,
        val readOk: Boolean
    )

    /** The downloader's view of every download it knows, by id. */
    private fun readLive(downloadManager: DownloadManager?): LiveSnapshot {
        val manager = downloadManager ?: return LiveSnapshot(emptyMap(), false)
        return runCatching {
            // Media3 has no per-download "waiting for the network" flag: a
            // download held back by an unmet requirement is simply left QUEUED
            // while the MANAGER reports the requirement. The manager's own
            // unmet mask is therefore the honest signal, read once per pass.
            val networkUnmet = (manager.notMetRequirements and
                (Requirements.NETWORK or Requirements.NETWORK_UNMETERED)) != 0
            val out = LinkedHashMap<String, LiveDownload>()
            val cursor: DownloadCursor = manager.downloadIndex.getDownloads()
            try {
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    out[download.request.id] = LiveDownload(
                        state = liveStateOf(download.state),
                        percent = download.percentDownloaded.takeIf { it >= 0f },
                        bytesDownloaded = download.bytesDownloaded,
                        totalBytes = download.contentLength,
                        waitingForNetwork = networkUnmet &&
                            download.state == Download.STATE_QUEUED
                    )
                }
            } finally {
                cursor.close()
            }
            LiveSnapshot(out, readOk = true)
        }.onFailure {
            Log.w(TAG, "download index read failed: ${it.message}")
        }.getOrDefault(LiveSnapshot(emptyMap(), readOk = false))
    }

    /** Media3's state ints, in this module's vocabulary. */
    private fun liveStateOf(state: Int): LiveDownloadState = when (state) {
        Download.STATE_QUEUED -> LiveDownloadState.QUEUED
        Download.STATE_DOWNLOADING -> LiveDownloadState.DOWNLOADING
        Download.STATE_STOPPED -> LiveDownloadState.STOPPED
        Download.STATE_COMPLETED -> LiveDownloadState.COMPLETED
        Download.STATE_FAILED -> LiveDownloadState.FAILED
        else -> LiveDownloadState.REMOVED
    }

    private fun removeFromDownloader(context: Context, manager: DownloadManager, id: String) {
        runCatching {
            DownloadService.sendRemoveDownload(context, DownloadsService::class.java, id, false)
        }.onFailure { Log.w(TAG, "remove $id failed: ${it.message}") }
        // Belt and braces for the in-memory view: the service's own removal is
        // asynchronous, and the row must stop being listed the moment it is
        // deleted even if the service is slow to answer.
        val tracked = runCatching { manager.downloadIndex.getDownload(id) }.getOrNull()
        if (tracked != null) {
            runCatching { manager.removeDownload(id) }
                .onFailure { Log.w(TAG, "index removal of $id failed: ${it.message}") }
        }
    }

    /** Writes one record's change through to the store, when it is there to change. */
    private fun markLocally(context: Context, id: String, change: (DownloadRecord) -> DownloadRecord) {
        val current = DownloadsStore.records(context)
        val record = current.firstOrNull { it.id == id } ?: return
        DownloadsStore.put(context, change(record).copy(updatedAt = System.currentTimeMillis()))
        refresh(context, persist = false)
    }

    /**
     * The Wi-Fi-only rule, as the DOWNLOADER's own network requirement.
     *
     * Not a check at enqueue time: a requirement is enforced continuously, so a
     * download started on Wi-Fi and continued on cellular stops when the phone
     * leaves the network the viewer allowed, and a download enqueued on cellular
     * with the toggle off waits - held QUEUED while the manager reports the
     * unmet network requirement, which is what the row's "Waiting for Wi-Fi"
     * comes from. A one-shot check could not do either.
     */
    private fun applyNetworkRequirement(context: Context, downloadManager: DownloadManager) {
        val networkType = if (DownloadsStore.getDownloadOverCellular(context)) {
            Requirements.NETWORK
        } else {
            Requirements.NETWORK_UNMETERED
        }
        downloadManager.setRequirements(Requirements(networkType))
    }

    /**
     * The client every download fetch uses: the app's shared client, plus an
     * interceptor that adds each download's own headers to the requests for it.
     *
     * Media3's `DownloadRequest` has no header slot, and a gated host (Referer,
     * Origin, Cookie) answers a header-less request with a throttled or
     * short-lived variant - the same failure the player's own header plumbing
     * exists for. Matching is by exact URL first and by directory prefix after,
     * because an HLS download's segments are fetched as their own URLs: they
     * share the playlist's directory, and that is where the headers have to
     * follow them.
     */
    private fun downloadClient(context: Context) =
        BaseHttpClient.derived {
            addInterceptor(RecordHeaderInterceptor { url -> recordHeaders(context, url) })
        }

    /**
     * The headers this app stored for the download the given URL belongs to.
     *
     * Read from the store per request rather than cached, because the headers
     * belong to a record that can be deleted mid-transfer and a stale map is how
     * a deleted download keeps fetching.
     */
    private fun recordHeaders(context: Context, url: String): Map<String, String> {
        val records = DownloadsStore.records(context)
        records.firstOrNull { it.sourceUrl == url }?.let { return it.sourceHeaders }
        // An HLS segment: the playlist's own directory is the shared signal.
        val prefixMatch = records
            .filter { it.sourceHeaders.isNotEmpty() }
            .filter { record -> url.startsWith(record.sourceUrl.substringBeforeLast('/') + "/") }
            .maxByOrNull { it.sourceUrl.length }
        return prefixMatch?.sourceHeaders.orEmpty()
    }

    /** A client that cannot fetch anything, for a session that must read only the cache. */
    private fun offlineClient() = BaseHttpClient.derived {
        addInterceptor { chain: Interceptor.Chain ->
            throw IOException(DownloadFailureReasons.MISSING)
        }
    }

    /**
     * Whether the device has a usable connection right now - the enqueue gate's
     * own question, public because the surfaces ask it to decide what to offer
     * BEFORE a press rather than after one.
     *
     * Answers true when the platform cannot be asked at all: the gate's purpose
     * is to catch a phone that obviously has no network, not to become a second
     * connectivity oracle that blocks downloads on a device whose service
     * happens to be missing.
     */
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return true
        return runCatching {
            val network = manager.activeNetwork ?: return@runCatching false
            val caps = manager.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    /** True when this device is close enough to full that a failure is worth calling space. */
    private fun isLowOnSpace(context: Context): Boolean {
        val free = DownloadsFiles.directory(context)?.usableSpace ?: return false
        return free < DownloadSpace.MARGIN_BYTES
    }

    /**
     * The row's length before enqueue, when the response states one.
     *
     * A HEAD for a progressive URL is the only pre-enqueue length that can be
     * had for free, and it is what the spec's space rule wants. An adaptive row
     * (HLS/DASH/SmoothStreaming) has no single length to read, and Media3's own
     * estimate needs a Looper-bound `DownloadHelper.prepare` - deliberately not
     * done here: the spec's own fallback for an unknown length is to enqueue and
     * surface the out-of-space failure in the same words, which
     * [DownloadSpace.OUT_OF_SPACE_REASON] does.
     */
    private suspend fun probeLength(
        url: String,
        headers: Map<String, String>,
        mimeType: String?
    ): Long? {
        if (isAdaptive(mimeType, url)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url).head()
                headers.forEach { (key, value) -> builder.header(key, value) }
                BaseHttpClient.get().newCall(builder.build()).execute().use { response ->
                    response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                }
            }.getOrNull()
        }
    }

    /**
     * Whether this row is a segmented stream rather than one file.
     *
     * Two signals, because a row often has neither: the mime type when the
     * addon bothered to state one ([resolvedMimeType] reads it off the URL when
     * there is no extension to key on), and the URL's own shape - an `.m3u8` is
     * HLS whatever its content type says, and that is the format downloads of
     * this app's sources mostly are.
     */
    private fun isAdaptive(mimeType: String?, url: String): Boolean {
        val lower = url.lowercase()
        if (mimeType?.startsWith("application/vnd.apple.mpegurl") == true) return true
        if (mimeType?.startsWith("application/x-mpegURL") == true) return true
        if (mimeType?.startsWith("application/dash+xml") == true) return true
        if (mimeType?.startsWith("application/vnd.ms-sstr+xml") == true) return true
        return ADAPTIVE_EXTENSIONS.any { lower.contains(it) }
    }

    /** The mime type a row's URL announces, when it announces one. */
    private fun resolvedMimeType(url: String): String? {
        val lower = url.lowercase().substringBefore('?')
        return when {
            lower.endsWith(".m3u8") -> "application/x-mpegURL"
            lower.endsWith(".mpd") -> "application/dash+xml"
            lower.endsWith(".ism") || lower.contains(".ism/manifest") ->
                "application/vnd.ms-sstr+xml"

            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".ts") -> "video/mp2t"
            else -> null
        }
    }

    /**
     * The launch extra that tells the player it is opening a download.
     *
     * Public because two modules have to agree on it: the mobile UI writes it,
     * NativePlayerActivity reads it. An extra rather than a field on
     * Screen.Player, deliberately - the player already takes a downloaded
     * session's url as its `stream_url`, so a download is a normal playback with
     * one extra fact, not a second kind of screen.
     */
    const val EXTRA_DOWNLOAD_ID = "download_id"

    private const val TV_REFUSED = "Downloads aren't available on this device"
    private const val LIVE_REFUSED = "Live channels can't be downloaded"
    private const val NO_STORAGE = "This device has no room for downloads"
    private const val ALREADY_DOWNLOADED = "Already downloaded"
    private const val NOT_FOUND = "This download is no longer listed"

    private val ADAPTIVE_EXTENSIONS = listOf(".m3u8", ".mpd", ".ism/manifest", ".isml")
}

/** What one Download action is for: the row's context plus the title it belongs to. */
data class DownloadTarget(
    val parentType: String,
    val parentId: String,
    /** The title's display name, or the episode's own line for a series. */
    val title: String,
    val episodeLabel: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    /** The picked row's quality label - shown on the download row as the choice made. */
    val qualityLabel: String,
    /** The episode's stream id, so a downloaded play files the same history row. */
    val episodeStreamId: String? = null
)

/** The outcome of asking for a download, so the caller can say the right thing. */
sealed interface DownloadEnqueueResult {
    data class Started(val id: String) : DownloadEnqueueResult
    data class Refused(val reason: String) : DownloadEnqueueResult
}

/** One record as the target a Retry re-enqueues under. */
internal fun DownloadRecord.downloadTarget(): DownloadTarget = DownloadTarget(
    parentType = parentType,
    parentId = parentId,
    title = title,
    episodeLabel = episodeLabel,
    season = season,
    episode = episode,
    posterUrl = posterUrl,
    qualityLabel = qualityLabel,
    episodeStreamId = episodeStreamId
)

/** The one interceptor the download fetches need: per-URL headers from the records. */
private class RecordHeaderInterceptor(
    private val headersFor: (String) -> Map<String, String>
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val headers = headersFor(request.url.toString())
        if (headers.isEmpty()) return chain.proceed(request)
        val builder: Request.Builder = request.newBuilder()
        headers.forEach { (name, value) -> if (value.isNotBlank()) builder.header(name, value) }
        return chain.proceed(builder.build())
    }
}
