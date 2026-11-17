package com.kennyb1201.kbstream.ui.theme

/**
 * Which URLs have had their art land where the tint can read it - and which ones
 * are being probed by the tint itself (spec 2026-11-17, F1/F2 of the dynamic
 * colour fix).
 *
 * The tint samples the image DISK cache, and it used to ask six times inside a
 * fixed 1.5 s window and then give up. A backdrop that took longer than that to
 * download was therefore never sampled again for the visit: the TV held the
 * previous card's colour and the phone Detail drew no wash at all. The trigger
 * was the bug, not the sampler - so the loader's own success path (the moment the
 * art actually lands) is now what tells the tint to look again, and this is the
 * bookkeeping behind it.
 *
 * Two facts are kept per URL, both bounded:
 *
 *  - **landed**: a load that reached the disk completed for this URL. The tint can
 *    read the file afterwards, so a sample taken now is an answer rather than a
 *    timing miss (F2). Last-wins, oldest out.
 *  - **probing**: a sample of this URL is in flight. The sampler's own request
 *    goes through the same image loader as every surface's, so without this the
 *    arrival signal would feed itself - sample succeeds, arrival fires, sample
 *    again, forever - and a title with no usable colour would never settle.
 *
 * Pure on purpose: no Android or Coil type reaches this file, so the two rules a
 * contract test can check without a device live here (see
 * ArtworkTintArrivalLedgerTest). The flow that wakes the surfaces, and the miss
 * invalidation that F2 asks for, live next to the loader hook and the cache.
 */
internal object ArtworkTintArrivalLedger {

    /** How many URLs are remembered. A session's tinted art is dozens, not thousands. */
    private const val MAX_ENTRIES = 96

    private val landed = object : LinkedHashMap<String, Long>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_ENTRIES
    }

    /** url -> number of samples in flight; a count, not a flag, so nested probes are safe. */
    private val probes = HashMap<String, Int>()

    /**
     * Records that [url]'s art landed, and says whether the caller should treat it
     * as news.
     *
     * False means one of two things: the URL is blank, or the load was the tint's
     * own probe of that URL - which must not count as an arrival, or the tint
     * would answer its own question forever.
     */
    @Synchronized
    fun noteLanded(url: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = url.trim()
        if (key.isEmpty()) return false
        if ((probes[key] ?: 0) > 0) return false
        landed[key] = nowMs
        return true
    }

    /** Whether [url]'s art has landed since this process started. */
    @Synchronized
    fun hasLanded(url: String): Boolean = landed.containsKey(url.trim())

    /** Marks a sample of [url] as in flight; pair with [endProbe] in a `finally`. */
    @Synchronized
    fun beginProbe(url: String) {
        val key = url.trim()
        probes[key] = (probes[key] ?: 0) + 1
    }

    @Synchronized
    fun endProbe(url: String) {
        val key = url.trim()
        val count = probes[key] ?: return
        if (count <= 1) probes.remove(key) else probes[key] = count - 1
    }

    /** For the diagnostics and the tests: is a sample of [url] running right now. */
    @Synchronized
    fun isProbing(url: String): Boolean = (probes[url.trim()] ?: 0) > 0

    /** Drops every fact. Used by tests; no production caller needs it. */
    @Synchronized
    fun clear() {
        landed.clear()
        probes.clear()
    }
}
