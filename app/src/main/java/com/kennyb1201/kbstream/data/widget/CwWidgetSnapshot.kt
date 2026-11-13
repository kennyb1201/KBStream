package com.kennyb1201.kbstream.data.widget

/**
 * One Continue Watching row as the widget draws it (spec 2026-11-13, F1/F3).
 *
 * Deliberately a RENDERED row, not a reference: the widget may read this long
 * after the app was last open (S5), so everything it needs to draw and to resume
 * is in the snapshot - no database, no tracker, no network. The ids are the ones
 * the app's own resume path takes, so a tap is the same request a Continue
 * Watching card press is.
 */
internal data class CwWidgetRow(
    /** The app's media type: `series` or `movie`. */
    val parentType: String,
    /** The parent (show/film) id the detail destination is keyed by. */
    val parentId: String,
    /** The stream id the player resumes, when the card had one. */
    val streamId: String? = null,
    val showTitle: String,
    val episodeTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    /** Where the playhead is, which is what a tap resumes at. */
    val positionMs: Long = 0L,
    /** The rail's own progress fraction, when it had one; null falls back to position/duration. */
    val progressPercent: Float? = null,
    /** The rail's own "N min left", when it had one. */
    val remainingMinutes: Int? = null,
    val durationMs: Long = 0L,
    val updatedAtMs: Long = 0L
) {
    /** Clamped bar fraction: the rail's value when it had one, else from the row's own numbers. */
    val progress: Float
        get() = progressPercent?.coerceIn(0f, 1f)
            ?: CwWidgetRules.progress(positionMs, durationMs)

    val minutesLeft: Int?
        get() = remainingMinutes ?: CwWidgetRules.remainingMinutes(positionMs, durationMs)

    val subtitle: String get() = CwWidgetRules.subtitle(season, episode, episodeTitle)

    val timeLeft: String get() = CwWidgetRules.timeLeftLabel(minutesLeft)
}

/**
 * What the app last knew about Continue Watching, for one profile.
 *
 * [profileId] is stored, not assumed: a widget must never draw another profile's
 * rows, and the app's profile can change while the widget is on screen.
 */
internal data class CwWidgetSnapshot(
    val profileId: String?,
    val writtenAtMs: Long,
    val rows: List<CwWidgetRow>
)

/**
 * The widget's current view of itself: the snapshot, plus whether a refresh
 * actually reached the disk.
 *
 * [stale] is what the header draws when the last write is old or the file could
 * not be read: the widget keeps the last good list, and says so rather than
 * pretending it is current or going blank.
 */
internal data class CwWidgetState(
    val snapshot: CwWidgetSnapshot?,
    val stale: Boolean,
    val profileId: String?
) {
    val rows: List<CwWidgetRow>
        get() = snapshot?.rows.orEmpty()
}
