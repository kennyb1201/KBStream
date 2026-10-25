package com.kennyb1201.kbstream.data.iptv

import com.kennyb1201.kbstream.data.format.DateFormats

/**
 * What Playlist Setup says about the guide's freshness, in the one screen that
 * already owns IMPORT GUIDE.
 *
 * The field complaint behind it is not "the guide went stale" but "nothing said
 * so": a background pass that imported nothing, or one source that kept failing
 * while its siblings kept the window ahead, only ever appeared in logcat. The
 * viewer found out by opening the guide and seeing nothing. So the two facts a
 * person can act on are published here — which source last succeeded and when,
 * and whether the background refresh has given up — next to the button that
 * fixes both.
 *
 * No push notification and no Home banner: the recovery is a button on this
 * screen, so this is where the state belongs.
 */
internal data class GuideRefreshStatus(
    /** Newest program end the live guide holds; null when it holds none. */
    val endsAtMs: Long? = null,
    /** The source that imported most recently, by URL, or null for none. */
    val lastGoodSource: String? = null,
    /** When [lastGoodSource] imported, epoch millis; null for never. */
    val lastGoodAtMs: Long? = null,
    /** Whether the background refresh has hit its run of empty passes. */
    val failing: Boolean = false,
    /** How many guide sources this profile has configured. */
    val sourceCount: Int = 0,
) {

    /**
     * The per-source last-good line, e.g.
     *
     *     Guide last imported from epg.kanya.io/us/guide.xml.gz Oct 25, 12:04 PM
     *
     * Empty when the profile has no guide sources (there is nothing to say) and
     * when no import has ever landed here ("not imported on this device yet" on
     * a profile that HAS sources — the difference between a profile nobody
     * configured and a device that never fetched).
     */
    fun lastGoodLabel(): String = when {
        sourceCount == 0 -> ""
        lastGoodSource.isNullOrBlank() || lastGoodAtMs == null || lastGoodAtMs <= 0L ->
            "Guide not imported on this device yet"
        else ->
            "Guide last imported from ${sourceLabel(lastGoodSource)} " +
                DateFormats.time(lastGoodAtMs, DateFormats.MONTH_DAY_CLOCK_12H)
    }

    /** The [sourceLabel] of the last good source, or blank when there is none. */
    fun sourceLabel(): String = lastGoodSource?.let(::sourceLabel) ?: ""

    companion object {
        /**
         * The "refresh is failing" state, shown in the danger colour beside the
         * IMPORT GUIDE button.
         *
         * "IMPORT GUIDE retries now" is the actionable half on purpose: the
         * button forces a fresh fetch and skips the stall cooldown, so it is
         * genuinely the recovery rather than a repeat of what just failed.
         */
        const val FAILING_LABEL = "Guide refresh failing — IMPORT GUIDE retries now"

        /**
         * The URL as much of it as a setup row can show.
         *
         * Scheme, credentials and query are dropped: Xtream-style providers put
         * the account's username and password in the query, and a status line is
         * the last place those belong. The length is capped so a long path cannot
         * push the date off the row.
         */
        fun sourceLabel(url: String): String {
            val bare = url.trim().substringAfter("://").substringBefore('?')
            val slash = bare.indexOf('/')
            val authority = if (slash >= 0) bare.substring(0, slash) else bare
            val path = if (slash >= 0) bare.substring(slash) else ""
            // `user:pass@host` - the credentials are not part of the host.
            val label = authority.substringAfterLast('@') + path
            return label.ifEmpty { url.trim() }.take(SOURCE_LABEL_MAX_CHARS)
        }

        /** Capped length of a [sourceLabel]; visible for the tests that pin it. */
        internal const val SOURCE_LABEL_MAX_CHARS = 56
    }
}
