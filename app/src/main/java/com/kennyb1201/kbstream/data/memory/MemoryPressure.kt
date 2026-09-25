package com.kennyb1201.kbstream.data.memory

import android.content.Context
import coil3.SingletonImageLoader
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the app holds onto while browsing, and how it gives it back.
 *
 * A field crash pinned the Java heap at its growth limit (192 MB) and died on
 * a 56-byte allocation the moment playback started on top of it: the browse
 * session was already carrying ~133 MB live before the player existed. The
 * player was the last straw, not the cause — so the caches that got the app
 * that close to the ceiling are the ones worth dropping.
 *
 * Everything releasable here is rebuildable: decoded artwork comes back from
 * Coil's disk cache, and the EPG snapshot comes back from the database. None
 * of it needs to survive a fullscreen viewing, and keeping it resident is
 * exactly what leaves the player without headroom.
 *
 * It also *reports*: Diagnostics reads the live owners' sizes back out of this
 * same registry, which is what makes a bounded cache verifiable on a device
 * instead of assumed. Nothing here is created to be measured — the registry
 * holds owners weakly and simply skips the ones that are gone.
 *
 * Deliberately a leaf: this file knows nothing about the guide or the player,
 * so the callers stay `onTrimMemory` (system pressure) and the fullscreen
 * player opening (self-inflicted pressure).
 */
object MemoryPressure {

    /**
     * Something that holds caches and can say how big they are.
     *
     * The report is a pull, not a push: a size is asked for when the diagnostics
     * dump is built rather than kept up to date on every write, so a cache pays
     * nothing for being observable.
     */
    interface CacheOwner {
        /** Entry counts of what this owner holds, one line for Diagnostics. */
        fun cacheStats(): String
    }

    /**
     * An owner of rebuildable caches that can drop them on demand.
     *
     * Every Releasable is also observed; not everything observed is releasable.
     * The trailer source cache is the counter-example worth knowing about: it is
     * bounded and worth watching, but rebuilding a dropped entry means
     * re-resolving over the network against YouTube's anonymous player API —
     * the exact churn its cache exists to prevent — so it is reported and never
     * released.
     */
    interface Releasable : CacheOwner {
        fun releaseCaches()
    }

    // Weak by design. Holding these strongly would mean the registry keeps a
    // released ViewModel's entire cache alive — the opposite of the point.
    private val tracked = CopyOnWriteArrayList<WeakReference<CacheOwner>>()

    /** Called by a cache owner as it is constructed. */
    fun register(owner: CacheOwner) {
        tracked.add(WeakReference(owner))
    }

    /**
     * Drops every registered releasable cache. Safe to call at any time from
     * any thread, including when nothing has registered: the caches it clears
     * are all rebuilt on next use rather than assumed to be present.
     */
    fun releaseBrowsingCaches() {
        // Iterating a CopyOnWriteArrayList walks a snapshot, so removing dead
        // references mid-loop is safe.
        tracked.forEach { ref ->
            val owner = ref.get()
            if (owner == null) {
                tracked.remove(ref)
            } else {
                // Owners that are only observed have nothing to give back. One
                // bad actor must not leave the rest of the caches resident.
                (owner as? Releasable)?.let { runCatching { it.releaseCaches() } }
            }
        }
    }

    /**
     * One line per live cache owner, for the diagnostics dump.
     *
     * Dead references are pruned here as well as in [releaseBrowsingCaches]: a
     * dump is usually taken on an idle session, which is exactly when a released
     * repository's entry is most likely to still be sitting in the list.
     */
    fun cacheStatsLines(): List<String> {
        val lines = mutableListOf<String>()
        tracked.forEach { ref ->
            val owner = ref.get()
            if (owner == null) {
                tracked.remove(ref)
            } else {
                // A cache that throws while sizing itself must not lose the rest
                // of the report — this runs inside the diagnostics dump.
                runCatching { lines += owner.cacheStats() }
            }
        }
        return lines
    }
}

/**
 * Drops Coil's decoded-bitmap cache.
 *
 * Bitmap pixels live in native memory on API 26+, which makes this usually the
 * largest single reclaimable block the app holds, and it competes for the same
 * device as the player's buffers. Only the in-memory cache goes: the disk
 * cache still holds every image, so a rail repaints from local data instead of
 * re-downloading, and images already on screen keep working because the
 * Compose painter holds its own reference.
 */
internal fun releaseImageMemoryCache(context: Context) {
    runCatching {
        SingletonImageLoader.get(context.applicationContext).memoryCache?.clear()
    }
}
