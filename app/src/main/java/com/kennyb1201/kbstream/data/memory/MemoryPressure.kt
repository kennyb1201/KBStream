package com.kennyb1201.kbstream.data.memory

import android.content.Context
import coil3.SingletonImageLoader
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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

    // How many times each half of the response has actually run this process.
    // Counted so a diagnostics dump can prove the hardening fired rather than
    // assume it: "the app got tight and nothing was given back" and "the app
    // got tight and 40 MB came back" look identical from the outside, and the
    // field device is the only place this can be seen (see Diagnostics).
    private val imageReleases = AtomicInteger(0)
    private val browsingReleases = AtomicInteger(0)

    // A graded trim and a full clear are different events with different costs
    // (spec 2026-11-15, scrolling round two F4): the paste has to be able to say
    // which one it saw, because a clear is the one that blanks the rail.
    private val imageTrims = AtomicInteger(0)
    private val imageClears = AtomicInteger(0)

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
        browsingReleases.incrementAndGet()
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

    /** Called by [releaseImageMemoryCache] so the bitmap half is counted too. */
    internal fun noteImageCacheReleased() {
        imageReleases.incrementAndGet()
        imageClears.incrementAndGet()
    }

    /** Called by [trimImageMemoryCache]: a graded trim, not a clear. */
    internal fun noteImageCacheTrimmed() {
        imageReleases.incrementAndGet()
        imageTrims.incrementAndGet()
    }

    /**
     * One line naming how often each release path has run, or null when neither
     * has — a report from a session that never came under pressure should stay
     * as short as it was.
     */
    fun releaseStatsLine(): String? {
        val images = imageReleases.get()
        val browsing = browsingReleases.get()
        if (images == 0 && browsing == 0) return null
        // trims vs clears (F4, spec 2026-11-15): `images=` stays the total, so
        // an older paste still reads the same way, and the split says which kind
        // of release it was. A session that only trimmed shows `clears=0`.
        return "releases: images=$images browsing=$browsing" +
            " trims=${imageTrims.get()} clears=${imageClears.get()}"
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
        MemoryPressure.noteImageCacheReleased()
    }
}

/**
 * Gives back HALF the decoded-bitmap cache, biggest bitmaps first (spec
 * 2026-11-15, scrolling round two F4).
 *
 * This is the RUNNING_CRITICAL response now. `clear()` is what made a pressure
 * event mid-scroll re-decode the rail the viewer was looking at, including cards
 * they had just scrolled past; a graded trim keeps those and drops the largest
 * entries - hero-size backdrops - which are the ones the hero re-decodes anyway
 * the next time focus moves.
 *
 * `trimToSize` is the backstop behind the chosen evictions: if the loader did not
 * report entry sizes (some decoders do not), the chosen list cannot reach the
 * target and the cache trims itself to the same half by its own order.
 */
internal fun trimImageMemoryCache(context: Context) {
    runCatching {
        val cache = SingletonImageLoader.get(context.applicationContext).memoryCache
            ?: return
        val resident = cache.size
        if (resident <= 0) return

        // The Keys, not their strings: `remove` matches the whole key (key +
        // extras), and a rebuilt Key with the same string would not match.
        val keys = cache.keys.toList()
        val entries = keys.map { key ->
            val image = cache.get(key)?.image
            ImageCacheTrim.Entry(
                width = image?.width ?: 0,
                height = image?.height ?: 0,
                sizeBytes = image?.size ?: 0L
            )
        }
        ImageCacheTrim.evictIndices(entries, resident).forEach { index ->
            runCatching { cache.remove(keys[index]) }
        }
        val half = resident / 2
        if (cache.size > half) {
            runCatching { cache.trimToSize(half) }
        }
        MemoryPressure.noteImageCacheTrimmed()
    }
}
