package com.kennyb1201.kbstream.data.widget

import com.kennyb1201.kbstream.ui.home.UpNextHiddenDecision
import com.kennyb1201.kbstream.ui.home.upNextHiddenDecision

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
    /**
     * True when this row is a card the viewer has STARTED, from the rail's own
     * rule ([com.kennyb1201.kbstream.ui.home.upNextIsResumeCard], spec
     * 2026-11-16, F3).
     *
     * Rendered here for the same reason the chip is: the widget must not decide
     * which of the cards is a resume and which is an offer to start one, or it
     * would be a second copy of that rule and the first place the two surfaces
     * drift. A non-resume row draws no bar and no time-left, and its tap opens
     * Detail instead of resuming a playhead it does not have.
     *
     * Defaulted true so a snapshot an older build wrote (resume-only in practice)
     * keeps drawing its rows as it always did until the next publish rewrites it.
     */
    val isResume: Boolean = true,
    /**
     * The card's own id on the rail ("nextup:tt…", "simkl:…"), for the
     * carry-forward rules. Empty only in a snapshot an older build wrote.
     */
    val cardId: String = "",
    /** The TMDB show id the card knew, one of the spellings a hide is keyed by. */
    val tmdbId: Int? = null,
    /** The watch-history row behind the card, when it had one (a hide spelling). */
    val historyRowId: String? = null,
    /**
     * The air date of the episode THIS row names, as an epoch day, when the row
     * proves it is a tracker card pointing at its own unplayed, dated episode
     * ([com.kennyb1201.kbstream.ui.home.upNextOwnEpisodeAirEpochDay]).
     *
     * Carried so a rebuild can re-ask "has it aired yet?" (spec 2026-11-16, F2)
     * and drop the row exactly as the rail would, without holding the card's
     * `UpNextItem` or inventing a schedule from disk. Null when the row proves
     * nothing, which means the rule never fires on it.
     */
    val ownEpisodeAirEpochDay: Long? = null,
    /**
     * The card's chip text ("RESUME", "NEXT UP", "SEASON FINALE" ...), RENDERED
     * by the rail's own rule ([com.kennyb1201.kbstream.ui.home.upNextChipLabel])
     * at publish time (spec 2026-11-16, F1).
     *
     * The rendered string, not the badge enum plus the two finale flags: the
     * widget is RemoteViews, it only prints this, and a launcher that decided
     * which of six strings a badge and two flags add up to would be a second
     * copy of the rule - the one thing F3 forbids. Null means "draw no chip".
     */
    val chip: String? = null,
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
    /**
     * Clamped bar fraction: the rail's value when it had one, else from the row's
     * own numbers. Always 0 for a non-resume row, which draws no bar at all.
     */
    val progress: Float
        get() = if (!isResume) {
            0f
        } else {
            progressPercent?.coerceIn(0f, 1f)
                ?: CwWidgetRules.progress(positionMs, durationMs)
        }

    /**
     * Minutes left, or null when there is none to claim.
     *
     * A non-resume row has nobody's playhead: a next-up card sits at position 0,
     * so the position/duration fallback would print its FULL runtime as
     * "44m left" - the claim the rail's own card already refuses (spec
     * 2026-11-16, F3).
     */
    val minutesLeft: Int?
        get() = if (!isResume) {
            null
        } else {
            remainingMinutes ?: CwWidgetRules.remainingMinutes(positionMs, durationMs)
        }

    val subtitle: String get() = CwWidgetRules.subtitle(season, episode, episodeTitle)

    val timeLeft: String get() = CwWidgetRules.timeLeftLabel(minutesLeft)

    /** The show this row belongs to: what a rebuild de-duplicates carried rows by. */
    val parentKey: String get() = "$parentType::$parentId"

    /**
     * This row's pass through the rail's hidden-titles rule, decided with the
     * fields the publish rendered (spec 2026-11-16, F2).
     *
     * The matcher is Home's own ([upNextHiddenDecision]), handed the same
     * spellings Home hands it: parent id, the card's own id, its TMDB id and its
     * history row. A rebuild runs this again on the rows it carries forward, so a
     * title hidden after the snapshot was written disappears from the widget
     * without waiting for the next publish.
     */
    fun hiddenDecision(
        hiddenKeys: Set<String>,
        showNamesFromHistory: Map<String, String> = emptyMap()
    ): UpNextHiddenDecision =
        upNextHiddenDecision(
            hiddenKeys = hiddenKeys,
            parentType = parentType,
            // The row prints one title, and it is the SHOW's (`showTitle` is the
            // rail's own show name, or the film's title). An episode title is
            // deliberately not offered as the name: it normalizes to nothing a
            // hide is stored under.
            title = showTitle,
            showTitle = showTitle,
            episodeTitle = episodeTitle,
            season = season,
            episode = episode,
            parentId = parentId,
            id = cardId.takeIf { it.isNotBlank() } ?: parentId,
            tmdbId = tmdbId,
            historyRowId = historyRowId,
            showNameFromHistory = showNamesFromHistory[parentId]
        )

    /**
     * The same read-site verdict a rebuild needs: hidden, or an episode this row
     * proves has not aired yet (spec 2026-11-16, F2).
     */
    fun droppedFromRail(
        hiddenKeys: Set<String>,
        showNamesFromHistory: Map<String, String> = emptyMap(),
        todayEpochDay: Long
    ): Boolean {
        if (hiddenDecision(hiddenKeys, showNamesFromHistory).hidden) return true
        val airDay = ownEpisodeAirEpochDay ?: return false
        return airDay >= todayEpochDay
    }
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
