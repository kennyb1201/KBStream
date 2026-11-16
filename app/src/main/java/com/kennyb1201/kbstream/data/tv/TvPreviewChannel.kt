package com.kennyb1201.kbstream.data.tv

import com.kennyb1201.kbstream.data.widget.CwWidgetRow

/**
 * The launcher preview channel's rows, with no Android in them (spec
 * 2026-11-16).
 *
 * The channel is a launcher surface, not a second Continue Watching: it shows
 * the same rows, in the same order, that the in-app rail published - resume
 * cards plus the next-up / new-episode / new-season / tracker cards - so the work
 * here is mapping one rendered row onto one program. Everything a program SAYS
 * is decided in this file, so it can be pinned without a TV provider, and the
 * Android half ([TvPreviewChannelPublisher]) only writes those values into
 * `TvContract.PreviewPrograms`.
 *
 * No fake progress, ever: a card nobody has started carries NO playback
 * position. `CwWidgetRow` deliberately refuses to compute a "progress" for such
 * a card (see its F3), and the program inherits that refusal rather than
 * inventing a bar from the runtime.
 */

/** The one channel this app publishes. The launcher's name for the row. */
internal const val PREVIEW_CHANNEL_NAME = "KBStream Continue Watching"

/** What kind of program one row becomes. Mapped to `TvContract` constants by the writer. */
internal enum class PreviewProgramKind {
    EPISODE,
    MOVIE
}

/**
 * One preview program, rendered: the fields the provider table takes, already
 * decided.
 *
 * [rowKey] is the card's identity for reconciliation - the same card keeps the
 * same key across syncs, so a sync knows which provider rows are stale even
 * though the rows themselves are rewritten (DELETE + INSERT, spec F3).
 */
internal data class PreviewProgramRow(
    val rowKey: String,
    val parentType: String,
    val parentId: String,
    val streamId: String?,
    val showTitle: String,
    val episodeTitle: String?,
    val season: Int?,
    val episode: Int?,
    val posterUrl: String?,
    val chip: String?,
    val isResume: Boolean,
    val positionMs: Long,
    val durationMs: Long
) {
    /**
     * Episode or film. A declared `series` is an episode; so is a row that
     * carries an episode number at all, which is how a tracker card with no
     * declared type still lands on the right program type.
     */
    val kind: PreviewProgramKind
        get() = if (parentType == "series" || episode != null || season != null) {
            PreviewProgramKind.EPISODE
        } else {
            PreviewProgramKind.MOVIE
        }

    /** The program's headline: the episode's own name when it has one. */
    val title: String
        get() = episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: showTitle

    /**
     * The line under the title: the show, its numbering, and the rail's chip.
     *
     * The chip is the one the rail drew (`RESUME`, `NEXT UP`, `NEW EPISODE`,
     * `NEW SEASON`, `SEASON FINALE`), so the launcher says what the card says.
     */
    val description: String
        get() = buildString {
            append(showTitle)
            val numbering = numbering()
            if (numbering.isNotEmpty()) {
                append(" · ")
                append(numbering)
            }
            chip?.trim()?.takeIf { it.isNotBlank() }?.let {
                append(" · ")
                append(it)
            }
        }

    /**
     * Where playback left off, or null when nothing has been played.
     *
     * The one rule this file exists for (spec F2): a resume row carries its real
     * position - the provider draws that as the card's progress bar - and a
     * next-up / new-episode card carries NONE, because a card nobody started has
     * no progress to draw. A resume row sitting at 0 has no position either: the
     * bar would be a claim about a session that never got going.
     */
    val lastPlaybackPositionMs: Long?
        get() = if (!isResume) null else positionMs.takeIf { it > 0L }

    /** The runtime the provider may print, when the row knew one. */
    val durationMillis: Long?
        get() = durationMs.takeIf { it > 0L }

    /** `S4E2`, or empty when the row names no episode pair. */
    fun numbering(): String {
        val season = season?.takeIf { it >= 0 } ?: return ""
        val episode = episode?.takeIf { it > 0 } ?: return ""
        return "S${season}E$episode"
    }
}

/** Turns the rail's rendered rows into the channel's programs. */
internal object TvPreviewChannelRules {

    /**
     * One program per row, in the rail's own order, with a stable key each.
     *
     * Nothing is filtered here: the rows handed in have already been through the
     * rail's hidden and unaired rules (the widget's list, spec: "never a second
     * Continue Watching"), and re-deciding them here would be a second copy of
     * those rules and the first place they drift.
     *
     * A row with no parent id is dropped: its program could not be opened, and a
     * tile that does nothing is worse than a missing tile.
     */
    fun programsFrom(rows: List<CwWidgetRow>): List<PreviewProgramRow> =
        rows.mapNotNull { row ->
            val parentId = row.parentId.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val showTitle = row.showTitle.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PreviewProgramRow(
                rowKey = keyOf(row, parentId),
                parentType = row.parentType.trim().takeIf { it.isNotBlank() } ?: "series",
                parentId = parentId,
                streamId = row.streamId?.trim()?.takeIf { it.isNotBlank() },
                showTitle = showTitle,
                episodeTitle = row.episodeTitle?.trim()?.takeIf { it.isNotBlank() },
                season = row.season,
                episode = row.episode,
                posterUrl = row.posterUrl?.trim()?.takeIf { it.isNotBlank() },
                chip = row.chip,
                isResume = row.isResume,
                positionMs = row.positionMs.coerceAtLeast(0L),
                durationMs = row.durationMs.coerceAtLeast(0L)
            )
        }

    /**
     * The card's identity across syncs: its parent, and the episode it names.
     *
     * Season/episode are part of it because one show can hold a resume card and a
     * next-up card for different episodes at once, and they are different
     * programs.
     */
    fun keyOf(row: CwWidgetRow, parentId: String = row.parentId): String =
        buildString {
            append(row.parentType.trim().ifBlank { "series" })
            append("::")
            append(parentId.trim())
            append("::")
            append(row.season ?: -1)
            append(":")
            append(row.episode ?: -1)
        }
}
