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
 * Deliberately a leaf: this file knows nothing about the guide or the player,
 * so the callers stay `onTrimMemory` (system pressure) and the fullscreen
 * player opening (self-inflicted pressure).
 */
object MemoryPressure {

    /** An owner of rebuildable caches that can drop them on demand. */
    interface Releasable {
        fun releaseCaches()
    }

    // Weak by design. Holding these strongly would mean the registry keeps a
    // released ViewModel's entire cache alive — the opposite of the point.
    private val releasables = CopyOnWriteArrayList<WeakReference<Releasable>>()

    /** Called by a cache owner as it is constructed. */
    fun register(releasable: Releasable) {
        releasables.add(WeakReference(releasable))
    }

    /**
     * Drops every registered cache. Safe to call at any time from any thread,
     * including when nothing has registered: the caches it clears are all
     * rebuilt on next use rather than assumed to be present.
     */
    fun releaseBrowsingCaches() {
        // Iterating a CopyOnWriteArrayList walks a snapshot, so removing dead
        // references mid-loop is safe.
        releasables.forEach { ref ->
            val releasable = ref.get()
            if (releasable == null) {
                releasables.remove(ref)
            } else {
                // One bad actor must not leave the rest of the caches resident.
                runCatching { releasable.releaseCaches() }
            }
        }
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
