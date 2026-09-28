package com.kennyb1201.kbstream.data.iptv

/**
 * How much of a provider's schedule is STORED, against how much of it the app
 * can actually show.
 *
 * These numbers used to live apart — the guide's read window in
 * [com.kennyb1201.kbstream.ui.iptv.IptvViewModel], the refresh cadence in
 * [EpgRefreshScheduler], and the import window in [XmltvImporter] — and they
 * had drifted by a factor of six: the guide renders **8 hours** ahead while an
 * import stored **48**, for every channel in the playlist, in every profile's
 * own database. Diagnostics on the field TV reported 1.34 GB of app data with
 * 982 MB of it in four `iptv_epg.db` files, and nothing in the app could ever
 * read the rows beyond the eighth hour.
 *
 * The relationship the three numbers have to keep is a real constraint, not a
 * preference, so it is written down here (and pinned by `EpgWindowTest`):
 *
 * ```
 * read  <=  stored - (one refresh interval)     i.e. a guide that has just
 *                                               been refreshed must still
 *                                               reach the end of what the UI
 *                                               renders, even if the next
 *                                               refresh is late
 * ```
 *
 * The past edge is different in kind: the guide leans back only
 * [READ_PAST_MS] (to fill the "on now" cell and the row of what just finished)
 * while catch-up TV lists programs that have already aired, so the import
 * keeps [PAST_MS] — the two are cheap next to the future, which is where the
 * rows and the hundreds of megabytes were.
 *
 * [FUTURE_MS] is deliberately NOT the read window itself: an install whose
 * refreshes are being deferred (Doze, or [EpgWriteGate] holding an import back
 * through a film) must not show a schedule that stops mid-evening. Two
 * intervals of slack is the trade that keeps the tail of the guide populated
 * while still storing a third of what it used to.
 */
internal object EpgWindow {

    /**
     * What the guide RENDERS behind the clock. The "on now" cell and the row
     * of just-finished programs; see `IptvViewModel.GUIDE_PAST_WINDOW_MS`.
     */
    const val READ_PAST_MS = 30L * 60L * 1000L

    /**
     * What the guide RENDERS ahead of the clock — the grid, the channel
     * detail's "coming up" row and the guide-wide search all read this
     * window; see `IptvViewModel.GUIDE_FUTURE_WINDOW_MS`.
     */
    const val READ_FUTURE_MS = 8L * 60L * 60L * 1000L

    /**
     * How often the active profile's guide is re-imported in the background;
     * see [EpgRefreshScheduler].
     */
    const val REFRESH_INTERVAL_HOURS = 6L
    const val REFRESH_INTERVAL_MS = REFRESH_INTERVAL_HOURS * 60L * 60L * 1000L

    /**
     * How much of the past an import keeps.
     *
     * Two hours, unchanged: it is what catch-up TV and the zap banner have
     * always had, and it is ~4% of the stored window.
     */
    const val PAST_MS = 2L * 60L * 60L * 1000L

    /**
     * How far ahead an import keeps programs.
     *
     * [READ_FUTURE_MS] plus two [REFRESH_INTERVAL_MS] of slack: enough that a
     * guide which was refreshed on time still reaches the end of the grid after
     * its next two refresh attempts are missed. The previous 48 hours stored
     * six times what could be displayed for every matched channel of a playlist
     * that can hold tens of thousands — the single largest store the app owned.
     */
    const val FUTURE_MS = READ_FUTURE_MS + 2 * REFRESH_INTERVAL_MS

    /**
     * The most of a description the widest screen can actually draw.
     *
     * A program's description reaches a screen in three places and nowhere
     * else, and every one of them clamps it: the guide's "on now" card at three
     * lines, the catch-up list at one, and the player's zap banner at two lines
     * of 12sp across the banner (see `activity_player.xml`). The widest of the
     * three shows under 250 characters on a 1080p panel. Nothing else reads a
     * description: the guide-wide search is an index over TITLES with a title
     * `LIKE` fallback (see [EpgSearchIndex]), so a description is never
     * searched, and no screen scrolls one.
     */
    const val DISPLAY_CLAMP_CHARS = 250

    /**
     * How much of a description is STORED.
     *
     * Providers ship far more than [DISPLAY_CLAMP_CHARS]: `<desc>` is routinely
     * a full synopsis with cast, director and a content warning, and
     * multi-kilobyte ones are common. Every byte of it sat in the guide file, in
     * EVERY profile's copy of it, for a window of [FUTURE_MS] across a playlist
     * that can hold thousands of channels - which is most of the ~130 MB per
     * profile measured on the field TV.
     *
     * Headroom over the widest clamp, so the cut lands beyond anything any
     * screen could have drawn; a clipped description ends in an ellipsis, so it
     * reads as clipped rather than as the provider's own text.
     */
    const val MAX_DESCRIPTION_CHARS = 400
}

/**
 * [raw] as it is STORED: trimmed, blank as null, and clipped to
 * [EpgWindow.MAX_DESCRIPTION_CHARS].
 *
 * One rule for both paths that write a description - the import
 * ([XmltvImporter]) and the maintenance pass that shortens what an earlier
 * build already stored (see [GuideStorage]) - so the two cannot disagree about
 * what a stored description looks like. The pass clips in SQL rather than
 * through here, because reading and rewriting hundreds of thousands of rows to
 * shorten them is far more I/O than one `UPDATE` is; the shape they produce is
 * the same, and `EpgWindowTest` pins it.
 *
 * With one honest exception: SQLite counts Unicode code points where Kotlin
 * counts UTF-16 units, so a description containing astral characters comes back
 * from the pass up to one character shorter than this function would have made
 * it. Both avoid cutting a surrogate pair, and neither is a byte contract with
 * the other - a stored description is bounded by this cap and drawable by every
 * screen, which is all it is for.
 */
internal fun epgDescriptionForStorage(raw: String?): String? {

    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.length <= EpgWindow.MAX_DESCRIPTION_CHARS) return trimmed

    var clipped = trimmed
        .take(EpgWindow.MAX_DESCRIPTION_CHARS - 1)
        .trimEnd()

    // A surrogate pair must not be cut in half: a lone surrogate cannot be
    // encoded, so it would reach SQLite as U+FFFD. (The maintenance pass clips
    // in SQL, where `substr`/`length` count characters rather than UTF-16
    // units - this guard is for Kotlin's `take`.)
    if (clipped.isNotEmpty() && clipped.last().isHighSurrogate()) {
        clipped = clipped.dropLast(1)
    }

    return clipped + "…"
}
