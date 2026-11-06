package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.library.HiddenTitleKeys

/**
 * The identity one Continue Watching / Up Next card hands the hidden-titles
 * filter, and the line Diagnostics prints about that decision.
 *
 * Why this exists (spec: CW hidden-titles match fix, 2026-11-05). A hidden show
 * left Library and Browse and the rails but kept its CW card - on the island
 * where the key set is provably right and the filter provably runs, because the
 * rails on that same screen dropped the same title from the same
 * `hiddenTitleKeys`. The miss was therefore in the identity the CW call passed:
 * card-carried fields that name a show differently from the rail meta beside
 * it. Three ways that happens, all covered here:
 *
 *  - the call passed the *episode* title when `showTitle` was null, and an
 *    episode title normalizes to nothing any hide entry stores;
 *  - the call derived the type from `season` alone, so a series card with a
 *    null `parentType` and a null `season` was matched as a MOVIE - both its
 *    TMDB key (`movie::tmdb:n`) and its name key (`title::movie::…`) then miss
 *    a hide stored as series;
 *  - the call offered only `parentId` and `id`, and a series item's parent id
 *    is often an episode-flavoured or IMDb-flavoured spelling, while the hide
 *    was stored from a TMDB rail under the card's `tmdbId`.
 *
 * Kept free of the item class and of Android on purpose: the match matrix over
 * those three legs is a JVM test (`CwHiddenMatchTest`), and it has to run
 * against the real key math (`HiddenTitleKeys.hides`), not a reimplementation.
 */
internal data class UpNextHiddenIdentity(
    /** "series" or "movie", the type the matcher keys a TMDB id by. */
    val mediaType: String,
    /** The name the matcher compares: the SHOW's, never an episode's. */
    val title: String?,
    /** Every id spelling the card carries, in the order they are offered. */
    val ids: List<String?>
)

/** One card's pass through the hidden-titles filter, with its line. */
internal data class UpNextHiddenDecision(
    val identity: UpNextHiddenIdentity,
    val hidden: Boolean,
    val line: String
)

/**
 * The filter's answer for one card: the identity it was matched by, the verdict,
 * and the diagnostics line describing both.
 *
 * This is the whole decision, so a test can state it in the same terms the
 * screen does - and the pre-fix identity can be handed the same hidden set and
 * shown to miss (see `CwHiddenMatchTest`).
 */
internal fun upNextHiddenDecision(
    hiddenKeys: Set<String>,
    parentType: String?,
    title: String,
    showTitle: String?,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    parentId: String?,
    id: String,
    tmdbId: Int?,
    historyRowId: String?,
    showNameFromHistory: String? = null
): UpNextHiddenDecision {
    val identity = upNextHiddenIdentity(
        parentType = parentType,
        title = title,
        showTitle = showTitle,
        episodeTitle = episodeTitle,
        season = season,
        episode = episode,
        parentId = parentId,
        id = id,
        tmdbId = tmdbId,
        historyRowId = historyRowId,
        showNameFromHistory = showNameFromHistory
    )

    val hidden = HiddenTitleKeys.hides(
        hiddenKeys,
        identity.mediaType,
        identity.title,
        // A CW card has no year to compare, so the name leg stays permissive
        // - the viewer explicitly removed the title.
        null,
        *identity.ids.toTypedArray()
    )

    return UpNextHiddenDecision(
        identity = identity,
        hidden = hidden,
        line = upNextHiddenFilterLine(
            name = showTitle ?: title,
            parentId = parentId,
            id = id,
            tmdbId = tmdbId,
            mediaType = identity.mediaType,
            hidden = hidden
        )
    )
}

/**
 * The identity to filter one card by.
 *
 * @param showNameFromHistory the name of the show's own parent row, when the
 *   card arrived without one (see `HomeViewModel.continueWatchingShowNames`).
 *   Only ever used for a card that describes an episode - a movie card's own
 *   title is its title, and swapping it for a history name would be the same
 *   class of bug in the other direction.
 */
internal fun upNextHiddenIdentity(
    parentType: String?,
    title: String,
    showTitle: String?,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    parentId: String?,
    id: String,
    tmdbId: Int?,
    historyRowId: String?,
    showNameFromHistory: String? = null
): UpNextHiddenIdentity {
    val declared = declaredMediaType(parentType)

    // The declared type is authoritative; only an undeclared one is derived
    // from the card's shape. `season` alone is not shape: the reported series
    // case is a card whose season never resolved.
    //
    // One consequence, accepted on purpose: a hide made from such a card BEFORE
    // this fix was stored under the old `season != null` guess, so a
    // TMDB-flavoured one (`movie::tmdb:n`) no longer meets this card's
    // `series::tmdb:n` and the title can reappear until hidden once more. The id
    // spellings that are not TMDB keep their type-free key and still match, and
    // hides made from every other surface were already stored as series.
    val isSeries = when (declared) {
        "series" -> true
        "movie" -> false
        else -> hasSeriesSignal(id, showTitle, episodeTitle, season, episode, parentId)
    }

    val name = if (isSeries) {
        showTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: showNameFromHistory?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        title.trim().takeIf { it.isNotEmpty() }
    }

    return UpNextHiddenIdentity(
        mediaType = if (isSeries) "series" else "movie",
        title = name,
        // Every spelling the card knows, in the order the hide is most likely
        // to have been stored under: the parent first (the rails' own id), then
        // the card's own, then the TMDB show id (which is what a rail hide is
        // usually keyed by), then the history row. `normalizeId` inside the
        // matcher canonicalizes a bare number to "tmdb:<n>", so a raw row id is
        // offered as-is.
        ids = listOf(
            parentId,
            id,
            tmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" },
            historyRowId
        )
    )
}

/**
 * The type a card DECLARES, or "" when it declares none.
 *
 * The same vocabulary as [upNextMediaType] ("movie" / "series" / "show" /
 * "tv"), but an undeclared type is kept apart from a declared one here: the
 * difference between "this is a movie" and "this card said nothing" is exactly
 * what the old `season != null` guess threw away.
 */
private fun declaredMediaType(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "movie" -> "movie"
    "series", "show", "tv" -> "series"
    else -> ""
}

/**
 * True when the card's own shape says "episode of a show", with no declared
 * type to say it for them.
 *
 * Any one signal is enough: a show title beside a card title, an episode
 * title, an episode number, or an id shaped like one (`tt123:1:2`,
 * `tmdb:603:2:5` - the composite form `normalizeId` passes through unchanged,
 * and one the field note named as an id the matcher had never been handed at
 * show level).
 */
private fun hasSeriesSignal(
    id: String,
    showTitle: String?,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    parentId: String?
): Boolean =
    season != null ||
        episode != null ||
        !showTitle.isNullOrBlank() ||
        !episodeTitle.isNullOrBlank() ||
        // An ADVANCE card ("next up") only ever exists for a series.
        id.startsWith("nextup:", ignoreCase = true) ||
        hasEpisodeIdShape(id) ||
        hasEpisodeIdShape(parentId)

/**
 * True when [rawId] names an EPISODE rather than a title: its last two
 * colon-separated segments are numbers (optionally `s`/`e` prefixed), as in
 * "tt0133093:2:5" and "tmdb:603:s2:e5".
 */
private fun hasEpisodeIdShape(rawId: String?): Boolean {
    val segments = rawId?.trim()?.split(':') ?: return false
    if (segments.size < 3) return false
    fun numbered(segment: String): Boolean =
        segment.removePrefix("s").removePrefix("e").toIntOrNull() != null
    return numbered(segments[segments.size - 2]) && numbered(segments.last())
}

/**
 * One `cw.filter:` diagnostics line: the card's name, the identity it was
 * matched by, and the verdict.
 *
 * Printed for KEPT cards too, not only dropped ones: a kept card whose name
 * matches a hidden entry is the next investigation's starting point, and a
 * dropped card with no matching entry is a false positive's. Titles and ids
 * only, the same PII posture as the other session lines.
 */
internal fun upNextHiddenFilterLine(
    name: String?,
    parentId: String?,
    id: String?,
    tmdbId: Int?,
    mediaType: String,
    hidden: Boolean
): String = buildString {
    append("cw.filter: ")
    append(name?.takeIf { it.isNotBlank() } ?: "-")
    append(" parentId=").append(parentId?.takeIf { it.isNotBlank() } ?: "-")
    append(" id=").append(id?.takeIf { it.isNotBlank() } ?: "-")
    append(" tmdbId=").append(tmdbId?.toString() ?: "-")
    append(" type=").append(mediaType)
    append(" hidden=").append(if (hidden) "true" else "false")
}
