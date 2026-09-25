package com.kennyb1201.kbstream.data.iptv

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * How many times each guide source has been imported, process-wide.
 *
 * The guide caches live on an [IptvRepository] instance, and there is more
 * than one: the guide screen's ViewModel owns one for the session, and the
 * background refresh ([EpgRefreshWorker]) builds its own. A cached snapshot
 * therefore only ever learned about imports that went through *its own*
 * instance, so a background import updated the database while the running
 * screen kept serving the lineup it had built before it — a channel the
 * provider renamed or added in the guide file showed as "No program data"
 * until the app was restarted.
 *
 * This counter is the missing signal. Every successful import bumps the
 * revision of the source it imported, and a cache read compares the revision
 * its entry was built from against the current one: different means the entry
 * is a miss and gets rebuilt from the database. The background worker's
 * instance may not exist by the time the guide reads a snapshot again, but the
 * number it left behind does.
 *
 * Kept deliberately free of Android types (it is all counters) so the
 * invalidation rule can be unit tested.
 */
internal object GuideRevision {

    /** Revisions per guide source, keyed by the trimmed source URL. */
    private val perSource = ConcurrentHashMap<String, Long>()

    /** Bumped by every import, whatever the source: the cross-source caches. */
    private val everyImport = AtomicLong(0L)

    /** The revision of [sourceUrl]; 0 means "never imported in this process". */
    fun of(sourceUrl: String): Long = perSource[sourceUrl.trim()] ?: 0L

    /** The revision of the whole guide subsystem, for caches that span sources. */
    fun total(): Long = everyImport.get()

    /**
     * Records a completed import of [sourceUrl].
     *
     * Only a *successful* import may call this: a failed one leaves the
     * database exactly as it was, so invalidating every cache for it would
     * throw away a good snapshot and rebuild the same bytes.
     */
    fun bump(sourceUrl: String) {
        val normalized = sourceUrl.trim()
        if (normalized.isEmpty()) return
        everyImport.incrementAndGet()
        perSource.compute(normalized) { _, current -> (current ?: 0L) + 1L }
    }
}
