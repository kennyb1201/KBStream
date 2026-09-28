package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The player's read-ahead cache: video bytes already fetched from a stream,
 * kept so a seek, an error recovery or a re-opened episode does not have to
 * pull them off the network again.
 *
 * It was a fixed 256 MB, which is the one number in the app's storage budget
 * that could not answer the device it was running on. Every other store is
 * sized from the room available (Coil's posters at a share of the free space,
 * the guides pruned to a window, the TMDB JSON cache at 64 MB); this one
 * claimed a quarter of a gigabyte of a nearly-full stick regardless, and the
 * LRU evictor only ever evicts while something NEW is being written to it. See
 * [streamCacheBudgetBytes] for the shape it has now.
 */
internal object StreamDiskCache {

    private const val TAG = "STREAM_DISK_CACHE"

    /** The cache directory, as a child of `cacheDir`. */
    const val FOLDER = "media_cache"

    /** Read-ahead floor: ~30 s of an 8 Mbps stream, which is the point of it. */
    const val MIN_BYTES = 32L * 1024 * 1024

    /** Ceiling: ~2 minutes of the same stream, past which it caches the film. */
    const val MAX_BYTES = 128L * 1024 * 1024

    /** Share of the free space the cache may hold, before the clamps above. */
    const val PERCENT_OF_USABLE = 5L

    private var singleton: SimpleCache? = null
    private val cacheLock = Any()

    /**
     * The process-wide cache, created on first use.
     *
     * SimpleCache must be a singleton per directory -- a second instance opened
     * on the same folder throws -- and the player Activity is recreated while
     * playback continues, so the instance is held here rather than per-Activity.
     * Once it exists the folder stays locked by the platform for the rest of the
     * process, which is what [sweepIfOversized] stands down for.
     */
    fun get(context: Context): SimpleCache {
        synchronized(cacheLock) {
            singleton?.let { return it }
            val appContext = context.applicationContext
            val cache = SimpleCache(
                directory(appContext),
                LeastRecentlyUsedCacheEvictor(
                    streamCacheBudgetBytes(appContext.cacheDir.usableSpace)
                ),
                StandaloneDatabaseProvider(appContext)
            )
            singleton = cache
            return cache
        }
    }

    /** Where the cache lives; also what the platform's folder lock is keyed on. */
    fun directory(context: Context): File = File(context.cacheDir, FOLDER)

    /**
     * Reclaims the cache when the budget has moved out from under it, returning
     * the bytes freed.
     *
     * The LRU evictor only evicts while a stream is being written into the
     * cache, so an install carrying the old fixed 256 MB one would keep every
     * byte of it until the next long playback -- on a device whose complaint was
     * that there was no room left to raise a new small one. This runs from
     * [com.kennyb1201.kbstream.work.CacheMaintenanceWorker], which is a
     * launch-time pass, and stands down entirely while the player holds the
     * folder: `SimpleCache` locks it for the rest of the process once it has
     * opened one, and deleting underneath a live cache is not a space fix. The
     * next launch takes it instead.
     */
    suspend fun sweepIfOversized(context: Context): Long = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dir = directory(appContext)
        if (!dir.isDirectory) return@withContext 0L

        val before = cacheBytes(dir)
        val budget = streamCacheBudgetBytes(appContext.cacheDir.usableSpace)
        if (before <= budget) return@withContext 0L

        if (SimpleCache.isCacheFolderLocked(dir)) {
            Log.i(
                TAG,
                "in use this session; leaving ${before / 1_048_576} MB " +
                    "(budget ${budget / 1_048_576} MB) for the next launch"
            )
            return@withContext 0L
        }

        // The platform's own delete, not a directory wipe: it also drops the
        // index rows this cache keeps in its own database, which a raw
        // recursive delete would leave behind to be re-read as cache entries.
        val deleted = runCatching {
            SimpleCache.delete(dir, StandaloneDatabaseProvider(appContext))
        }
        if (deleted.isFailure) {
            Log.w(TAG, "sweep failed: ${deleted.exceptionOrNull()?.message}")
            return@withContext 0L
        }

        val freed = before - cacheBytes(dir)
        Log.i(TAG, "swept ${before / 1_048_576} MB -> ${freed / 1_048_576} MB freed")
        freed
    }

    /** Everything the cache directory holds, sidecars and index files included. */
    private fun cacheBytes(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/**
 * How much disk the player's read-ahead may hold, from the free space on the
 * volume it lives on.
 *
 * Three parts, each doing a job a single number could not:
 *
 *  - the SHARE is what keeps a nearly-full device from spending its last
 *    megabytes on cached video;
 *  - the FLOOR keeps enough read-ahead that a slow host still has headroom at
 *    the head of a file, which is the stall this cache exists to prevent;
 *  - the CEILING stops a roomy device from caching much more than that head.
 *    The old fixed 256 MB was spent on whole films' worth of bytes on a device
 *    that was reporting itself out of storage.
 */
internal fun streamCacheBudgetBytes(usableBytes: Long): Long =
    (usableBytes.coerceAtLeast(0L) * StreamDiskCache.PERCENT_OF_USABLE / 100)
        .coerceIn(StreamDiskCache.MIN_BYTES, StreamDiskCache.MAX_BYTES)
