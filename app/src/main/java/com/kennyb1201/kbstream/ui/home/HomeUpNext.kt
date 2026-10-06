package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.namedEpisodeNumber
import com.kennyb1201.kbstream.data.tmdb.TmdbEpisodeAirInfo
import kotlinx.coroutines.flow.map

// The rail and up-next models, and the pure helpers that key, group and
// de-duplicate them. Split out of HomeViewModel.kt, whose bulk is one very
// large class: these declarations are top-level and take no part in the
// ViewModel's state, so keeping them in that file only made the models hard to
// find. HomeViewModel.kt still owns the loading, the caches and the IO; this
// file owns what a card IS.
internal const val PREFS_DISMISSED_UPNEXT =
    "continue_watching_dismissals"

/**
 * Ceiling on how many caught-up shows one Upcoming refresh looks up on TMDB.
 * The candidate list is already narrowed to followed shows Simkl knows have
 * unaired episodes; this only keeps a huge library from turning one refresh
 * into dozens of lookups.
 *
 * Raised from 25 when a finished show started counting as a candidate too
 * (a returning show reads as "completed" until its new season airs), which
 * doubled the eligible set: at 25 a library with plenty of returning shows
 * silently dropped the tail of them from the rail, which is the symptom the
 * cap must never cause. Every candidate is one cached TMDB detail lookup.
 */
internal const val MAX_CAUGHT_UP_UPCOMING_ITEMS =
    50

/**
 * How long a loaded set of caught-up Upcoming cards is reused. The Upcoming
 * schedule re-derives on every Continue Watching publish (two of those per
 * refresh, plus one per watched-state change), and the candidates behind it
 * are network work: within this window the previous answer stands.
 */
internal const val CAUGHT_UP_UPCOMING_TTL_MS =
    60_000L

/** Sync bookkeeping key inside the dismissals prefs store. */
internal const val DISMISSALS_SYNCED_AT = "dismissals_synced_at"

/**
 * Ceiling on how many locally-watched shows one Continue Watching refresh
 * resolves into "next up" cards. Each candidate is one cached TMDB detail +
 * season walk (see HomeViewModel's local next-up builder); the list is ordered
 * newest completion first, so the cap only trims the tail of a long history.
 */
internal const val MAX_LOCAL_NEXT_UP_ITEMS = 25

/**
 * Ceiling on the RETURNING-show pass that runs after the 25-cap above: shows
 * whose whole watched run is old enough to have been trimmed off the tail, but
 * which TMDB says now have aired episodes beyond what was watched (a new season
 * dropped). Kept small and separate so the [MAX_LOCAL_NEXT_UP_ITEMS] ordering
 * for active shows is never disturbed - these cards are appended after those.
 */
internal const val MAX_RETURNING_SHOW_ITEMS =
    10

/** Parallel TMDB resolutions while building the local next-up cards. */
internal const val LOCAL_NEXT_UP_CONCURRENCY =
    4

/**
 * Parallel TMDB resolutions while building the MDBList cards. They each resolve
 * the show's watched/total aired counts now, so the pass is no longer a cheap
 * per-session metadata read (see [upNextArrivalBadge] and the counts the card
 * carries).
 */
internal const val MDBLIST_UP_NEXT_CONCURRENCY =
    4

/**
 * How long after it airs an episode still counts as news: the window the
 * arrival badges read ("New Season" / "New Episode"), and the one the local
 * next-up card uses to decide a premiere is a season RETURN rather than one
 * more episode.
 *
 * Seven days is the same week a tracker's own "new" flag covers, so a card
 * stops claiming to be news at the point the next episode is usually out.
 */
internal const val NEW_RELEASE_WINDOW_DAYS =
    7

data class Rail(
    val addonName: String,
    val catalogName: String,
    val type: String,
    val items: List<MetaPreview>,
    val catalogId: String? = null,
    val baseUrl: String? = null,
    // Landscape-card artwork per item id ("movie:tmdb:603" style key):
    // resolved backdrop + clearlogo, filled when the landscape toggle is
    // on. Poster mode never reads these.
    val landscapeArt: Map<String, Pair<String?, String?>> = emptyMap(),
    // Draw each card's position as a rank number, the way a "Top 10" row
    // reads. Only the pinned "Top ... Today" rows (and their kids-profile
    // stand-ins) are rankings: every other rail arrives in an order the add-on
    // chose to be browsed, not one it claims is a standing.
    //
    // The number is the rail's own order and not a field of the item, because
    // the add-on sends its ranking as the order of its catalog and these rails
    // keep it. That same artwork is the reason the number is not simply shown
    // with it: the pinned backdrops carry a burned-in logo, which is what made
    // these rows TMDB-art rows in the first place (see
    // LandscapeArtRequest.tmdbOnly), and TMDB art has no number on it. So the
    // card draws it, or it is not shown at all.
    val ranked: Boolean = false
)

enum class UpNextBadge {
    CONTINUE_WATCHING,
    NEXT_UP,
    NEW_EPISODE,
    NEW_SEASON
}

data class UpNextItem(
    val id: String,
    val title: String,
    val poster: String?,
    val badge: UpNextBadge,

    // Display metadata
    val showTitle: String? = null,
    val episodeTitle: String? = null,
    val episodeDescription: String? = null,
    val tmdbRating: Double? = null,
    val imdbRating: Double? = null,
    val runtimeMinutes: Int? = null,
    val remainingMinutes: Int? = null,
    val episodesRemaining: Int? = null,
    val episodesWatched: Int? = null,    val episodesTotal: Int? = null,
    val episodeThumbnail: String? = null,
    val backdrop: String? = null,
    val clearLogo: String? = null,

    // Upcoming-rail items only: the relative air-date label ("Today",
    // "In 5 days") and the absolute calendar date ("Mon, Sep 15") so the
    // hero can say "Airs Tomorrow" with the real date underneath.
    val airDateLabel: String? = null,
    val airDateFull: String? = null,

    val subtitle: String? = null,
    val progressPercent: Float? = null,
    val streamUrl: String? = null,
    val parentId: String? = null,
    val parentType: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeStreamId: String? = null,
    val startPositionMs: Long = 0L,
    val recencyTimestamp: Long = 0L,

    /**
     * Raw watch_history row id backing this item ("history:..." without the
     * prefix). Non-history items (SIMKL) leave it null. Long-press Remove
     * uses it to fall back when parentId is unavailable.
     */
    val historyRowId: String? = null,

    /**
     * Simkl /sync/playback session id when this card came from a paused
     * Simkl playback session. Long-press Remove deletes the session so the
     * card stops reappearing from the remote Continue Watching feed.
     */
    val playbackId: Int? = null,

    /**
     * True when this card's episode is the last aired episode of its
     * season. Renders as the "SEASON FINALE" tag on the rail and hero.
     */
    val isSeasonFinale: Boolean = false,

    /**
     * True when this card's episode is the show's final aired episode
     * (the last episode of the last aired season). Takes precedence over
     * [isSeasonFinale] and renders as the "SERIES FINALE" tag.
     */
    val isSeriesFinale: Boolean = false,

    /**
     * TMDB "next episode to air" for this title (season, episode number
     * and air date), captured from the same detail response the Continue
     * Watching enrichment already fetches — so the Upcoming rail costs no
     * extra network calls. Null when unknown / not a returning series.
     */
    val nextEpisodeAir: TmdbEpisodeAirInfo? = null,

    /** TMDB show id when enrichment resolved one, for episode-title fallback. */
    val tmdbId: Int? = null
) {

    /**
     * The watched count the hero's "X of Y aired episodes watched" line shows -
     * reported as 0 rather than as nothing whenever the total is known.
     *
     * [episodesWatched] is null for a title with no COMPLETED episode yet: the
     * local-history builder stores the count with `takeIf { it > 0 }`, so a show
     * the viewer has just started arrives with a total and no count at all - and
     * the hero, which needs both halves to make a sentence, showed no episode
     * line for exactly the case the line is asked about (reported: started a
     * show, paused mid-episode 1, and the hero was the only card without a
     * count, since every other show had at least one episode finished).
     *
     * A known total with no count means zero watched. A movie, or a show whose
     * episode list never resolved, has no total - and those still get no line,
     * because there is nothing truthful to say. The count is clamped to the
     * total so a stale watched tally cannot read "13 of 12".
     */
    val episodesWatchedForDisplay: Int?
        get() = episodesTotal
            ?.takeIf { it > 0 }
            ?.let { total -> (episodesWatched ?: 0).coerceIn(0, total) }
}

/**
 * Media type as the Continue Watching dedupe rule sees it. Anything the app
 * cannot place (a rail type like "anime") collapses to "unknown" rather than
 * inventing a second bucket for the same show.
 */
internal fun upNextMediaType(type: String?): String =
    when (type?.trim()?.lowercase()) {
        "movie" -> "movie"
        "series", "show", "tv" -> "series"
        else -> "unknown"
    }

/**
 * Dedupe id form: "tt..." stays as it is, prefixed ids lose their prefix
 * ("tmdb:123" and "simkl:9" become "123" / "9").
 */
internal fun upNextIdentifier(rawId: String?): String? {
    if (rawId.isNullOrBlank()) return null

    val trimmed =
        rawId.trim().lowercase()

    return when {
        trimmed.startsWith("tt") -> trimmed
        trimmed.startsWith("tmdb:") -> trimmed.removePrefix("tmdb:")
        trimmed.startsWith("simkl:") -> trimmed.removePrefix("simkl:")
        else -> trimmed
    }
}

/** Id prefixes that are internal lookup keys, never part of a title. */
private val RAW_ID_PREFIXES = setOf("tmdb", "imdb", "simkl", "tvdb", "trakt")

/**
 * True when [value] is an internal media id rather than a name.
 *
 * Cards reach the rail titled with one of these when the source's own name
 * field was missing or its enrichment failed: "tmdb:12345", "tt0111161", or a
 * bare "12345". The tell is that the same failure is what leaves the card
 * with no artwork, so those two symptoms travel together - which is why a
 * bare number is only an id when the card has NO poster. Real titles are
 * numbers too ("1917", "2012"), and the prefixed forms are unambiguous.
 */
internal fun looksLikeRawMediaId(
    value: String,
    hasArtwork: Boolean = true
): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false

    // tt0111161
    if (
        trimmed.startsWith("tt", ignoreCase = true) &&
        trimmed.length > 2 &&
        trimmed.substring(2).all { it.isDigit() }
    ) {
        return true
    }

    // tmdb:12345 / imdb:tt0111161 / simkl:123 / tvdb:456, and multi-segment
    // ids like tmdb:12345:3:17 (each colon-segment must itself be numeric, so
    // the colons cannot slip past the check).
    val prefix = trimmed.substringBefore(":", missingDelimiterValue = "")
    if (prefix.lowercase() in RAW_ID_PREFIXES) {
        val rest = trimmed.substringAfter(":", missingDelimiterValue = "")
        if (
            rest.isNotEmpty() && rest.split(":").all { seg ->
                seg.isNotEmpty() && seg.substringAfter("tt").all { it.isDigit() }
            }
        ) {
            return true
        }
    }

    // Bare number: only an id when nothing identifies the card as a real
    // title, i.e. it has no artwork either.
    return !hasArtwork && trimmed.all { it.isDigit() }
}

/**
 * A card's display title, or null when there is no real name to show.
 *
 * Blank and id-like names both mean "this card has no title". Callers fall
 * back to a resolved name or skip the card; putting an internal id on screen
 * is what made the phantom duplicates so confusing - they could not even be
 * paired with the right card by name.
 */
internal fun upNextDisplayTitleOrNull(
    raw: String?,
    hasArtwork: Boolean = true
): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (looksLikeRawMediaId(trimmed, hasArtwork)) return null
    return trimmed
}

/** What the player falls back to when a card has no name to give it. */
internal const val UNKNOWN_SHOW_NAME = "Unknown Show"

/**
 * The name a Continue Watching / Up Next card hands the PLAYER, i.e. the
 * `StreamsTarget.displayName` that becomes `itemName`.
 *
 * This is the show name: it is what the Up Next panel prints as the title of
 * the episode coming next, and what the autoplay handoff copies into the next
 * session. [cardTitle] ([UpNextItem.title]) is usually exactly that - but when
 * the source's own name was missing and enrichment failed it is the internal id
 * instead ("tmdb:114666"). [upNextDisplayTitleOrNull] already keeps such a name
 * off the CARD; the play path used to copy the raw field straight into the
 * target, so the id reached the player anyway, was printed as the show title,
 * and rode the handoff into every episode after it.
 *
 * The chain never ends on an id: the card title, then [showTitle], then
 * [composedTitle] (the "S4 E41 • Name" field), then [UNKNOWN_SHOW_NAME].
 *
 * [composedTitle] gets one extra condition. It is built by appending the
 * season/episode marker to the CARD title, so an id-like card title makes it
 * "tmdb:114666 S4 E41" - which is NOT [looksLikeRawMediaId] (the marker stops it
 * being all-numeric) even though it still prints an internal id. So the composed
 * field is only trusted when there was no id-like card title for it to inherit
 * from; otherwise the literal is better than an id with a suffix.
 */
internal fun upNextPlayerDisplayName(
    cardTitle: String?,
    showTitle: String?,
    composedTitle: String?,
    hasArtwork: Boolean,
    fallback: String = UNKNOWN_SHOW_NAME
): String {
    upNextDisplayTitleOrNull(cardTitle, hasArtwork)?.let { return it }
    upNextDisplayTitleOrNull(showTitle, hasArtwork)?.let { return it }
    if (!looksLikeRawMediaId(cardTitle.orEmpty(), hasArtwork)) {
        upNextDisplayTitleOrNull(composedTitle, hasArtwork)?.let { return it }
    }
    return fallback
}

/**
 * [upNextDisplayTitleOrNull] for the handoff paths, which carry only the name a
 * running session already had and have no second field to fall back to.
 *
 * The autoplay and picker-restore paths copy the session's `itemName` into the
 * next target's `displayName`; a session that was launched before the name was
 * sanitized (or by a route that never sanitized it) would otherwise propagate an
 * internal id into every episode after it. The literal is the honest answer.
 */
internal fun upNextPlayerNameOrUnknown(name: String?, hasArtwork: Boolean): String =
    upNextDisplayTitleOrNull(name, hasArtwork) ?: UNKNOWN_SHOW_NAME

/**
 * The small season/episode label a Continue Watching card carries - "S02 · E08".
 *
 * Extracted from the card so the one thing that must never happen here can be
 * pinned by a test: printing an episode number the sources never really named.
 * A tracker and an add-on both spell "no episode" as 0 (see
 * [com.kennyb1201.kbstream.data.namedEpisodeNumber]), and formatting that
 * straight through is what put a rail of cards reading "S02 · E00" over blank
 * artwork in front of a viewer. The season half is still worth saying on its
 * own; when neither half is a real number the label goes away rather than
 * becoming an empty "S02 · E00" shape.
 */
internal fun upNextEpisodeLabel(
    season: Int?,
    episode: Int?
): String? {
    val named =
        namedEpisodeNumber(episode)

    return when {
        season != null && named != null ->
            "S%02d · E%02d".format(
                season,
                named
            )

        season != null ->
            "S%02d".format(season)

        named != null ->
            "E%02d".format(named)

        else ->
            null
    }
}

/**
 * The season/episode pair a Continue Watching card should print, given the
 * watch-history row's own pair and the episode the show resolved to continue
 * at.
 *
 * A resume row does not always name an episode. A row written as "season 2,
 * episode 0" (before the 0-is-not-an-episode rule) or one a hand-off wrote
 * with a season but no episode reaches the builder as season 2 with no
 * episode, and the card then printed the season alone - "S02" with no episode
 * beside it - even though the show's next episode was perfectly well known.
 * The pair is therefore completed from the resolution, and only from it:
 *
 *  - a row that already names a real episode keeps its own pair, untouched, so
 *    a genuine resume still points at the episode it was paused on;
 *  - a row with a season but no episode keeps that season and takes the
 *    resolved episode (the resolution walked that same season);
 *  - a row with neither takes both from the resolution;
 *  - a row with an episode but no season keeps its episode and invents no
 *    season, because the resolution cannot know which season that episode
 *    belonged to.
 */
internal fun upNextCardEpisodePair(
    rowSeason: Int?,
    rowEpisode: Int?,
    resolvedSeason: Int?,
    resolvedEpisode: Int?
): Pair<Int?, Int?> {

    val namedRowEpisode =
        namedEpisodeNumber(rowEpisode)

    if (namedRowEpisode != null) {
        return rowSeason to namedRowEpisode
    }

    return (rowSeason ?: resolvedSeason) to resolvedEpisode
}

/**
 * Local-history parent ids that describe a show: the id a Continue Watching
 * row is keyed by plus the twins the SAME show is stored under.
 *
 * The same title arrives under more than one id flavor - TMDB search results
 * and the kids rails carry "tmdb:<n>", add-on catalogs and Continue Watching
 * carry "tt..." - and playback history is written under whichever flavor
 * started it. Reading only the row's own flavor is what made Continue Watching
 * blind to watch state the detail screen could see: the show resolved no
 * watched episode, so its card fell back to "season 1 episode 1" while its
 * episode markers were ticked on Detail. Every local history read that feeds
 * the series resolution goes through this list (the detail screen's twin of
 * this rule lives in DetailViewModel.localHistoryParentIds).
 */
internal fun localHistoryParentIdsForShow(
    parentId: String,
    tmdbShowId: Int?,
    imdbId: String? = null
): List<String> {
    if (parentId.isBlank()) return emptyList()

    val ids = linkedSetOf(parentId)

    // A synthetic / unresolved id (-1) must never become a fake "tmdb:-1" key.
    val tmdbId =
        tmdbShowId?.takeIf { it > 0 }
    if (tmdbId != null) {
        ids += "tmdb:$tmdbId"
    }

    imdbId
        ?.trim()
        ?.takeIf { it.startsWith("tt") }
        ?.let { ids += it }

    return ids.toList()
}

/**
 * The Home hero's Continue Watching line: the card's own season/episode label
 * behind its action prefix ("Resume  •  S02 · E08").
 *
 * The hero sits directly above the rail, and it used to format the season and
 * episode itself. That meant the card's own rule (see [upNextEpisodeLabel])
 * only ever reached the rail: for the very card whose corner had been fixed,
 * the hero still read "Resume  •  S02 · E00", which is what made the fix look
 * like it had not taken. Both surfaces now spell the pair the same way, and a
 * prefix with nothing to attach to stays a bare prefix.
 */
internal fun upNextHeroEpisodeLabel(
    prefix: String,
    season: Int?,
    episode: Int?
): String =
    upNextEpisodeLabel(season, episode)
        ?.let { label -> "$prefix  •  $label" }
        ?: prefix

/** Title key: the fallback identity of a show when its ids disagree. */
internal fun upNextTitleKey(item: UpNextItem): String =
    "title:${upNextMediaType(item.parentType)}:${item.title.trim().lowercase()}"

/**
 * True when this card came from a TRACKER account (Simkl, MDBList) rather
 * than from this profile's own watch history.
 */
internal fun isTrackerSourcedCard(item: UpNextItem): Boolean =
    item.id.startsWith("simkl:", ignoreCase = true) ||
        item.id.startsWith("mdblist:", ignoreCase = true)

/**
 * The parent identity keys a show is known by, in the same vocabulary as
 * [upNextGroupingKeys]. Built from raw fields because a locally CAUGHT-UP show
 * has no card to read them off - it is precisely the show that must not appear
 * on the rail.
 */
internal fun upNextShowParentKeys(
    parentId: String?,
    parentType: String?,
    tmdbId: Int?
): Set<String> {
    val mediaType =
        upNextMediaType(parentType)

    return buildSet {
        upNextIdentifier(parentId)
            ?.let { add("parent:$mediaType:$it") }

        tmdbId
            ?.takeIf { it > 0 }
            ?.let { add("parent:$mediaType:$it") }
    }
}

/**
 * True when a tracker card (Simkl, MDBList) names a show this profile has
 * already finished locally.
 *
 * The local pass proves a show is caught up - every aired episode it counted
 * is watched and nothing is left to resume (see [hasNothingLeftToWatch]) -
 * while the tracker feed keeps listing the show until the local completion is
 * pushed AND the tracker's own feed catches up. That gap is what kept a
 * finished title on Continue Watching for as long as another server took to
 * agree.
 *
 * Making the local verdict authoritative here removes that dependency: the card
 * leaves the rail the moment the last watched episode is filed, whatever the
 * tracker still says. Matching is by the id vocabulary the duplicate collapse
 * already uses, so the card still pairs with the caught-up show across the
 * "tt..." / "tmdb:<n>" flavors.
 */
internal fun trackerCardLocallyFinished(
    item: UpNextItem,
    finishedShowKeys: Set<String>
): Boolean =
    isTrackerSourcedCard(item) &&
        finishedShowKeys.isNotEmpty() &&
        upNextGroupingKeys(item).any { it in finishedShowKeys }

/**
 * Whether a tracker card must stay off the active profile's rails because the
 * title belongs to a sibling profile.
 *
 * Local history is profile-scoped, so a local card is never in question here:
 * it exists only for a title the ACTIVE profile watched. The tracker feeds are
 * account-wide (Simkl and MDBList hold one library per account, not per
 * profile), which is how a kids show watched on profile 3 resurfaced on
 * profile 1 - a profile with no history for it at all. The owner a local card
 * recorded decides it.
 *
 * A title with no recorded owner is kept everywhere on purpose: a show watched
 * on another device, or before this TV had profiles, cannot be attributed, and
 * hiding it would break the cross-device Continue Watching the tracker feeds
 * exist for.
 */
internal fun trackerCardOwnedByAnotherProfile(
    item: UpNextItem,
    ownerByTitleKey: Map<String, String>,
    activeProfileId: String?
): Boolean {
    if (!isTrackerSourcedCard(item)) return false

    val active = activeProfileId?.takeIf { it.isNotBlank() } ?: return false
    val owner = ownerByTitleKey[upNextTitleKey(item)] ?: return false

    return owner != active
}

/**
 * A tracker card's subtitle: the action word and the episode pair, or the
 * tracker's own text when there is no pair to print.
 *
 * The pair used to be concatenated unconditionally, so a card whose resolution
 * came back with nothing at all read "Up Next - " - a dangling separator - or
 * worse, "Up Next - S1 E1" from the invented default pair. The tracker's own
 * line ("S2 · E5", "Paused 34%") is real information; it stands when the pair
 * is not.
 */
internal fun upNextTrackerSubtitle(
    prefix: String,
    season: Int?,
    episode: Int?,
    fallback: String?
): String? {
    val pair = formatSeasonEpisode(season, episode)
    return if (pair.isBlank()) {
        fallback?.takeIf { it.isNotBlank() }
    } else {
        "$prefix - $pair"
    }
}

/**
 * The badge a rail card carries for the episode it points at.
 *
 * One rule for all three sources of cards - this profile's history, MDBList and
 * Simkl - because they were deciding it separately and disagreeing: the Simkl
 * builder read the arrival window, the local builder handled only the season
 * PREMIERE case, and the MDBList builder hardcoded "continue watching". So a
 * viewer without a Simkl account never saw a "New Episode" chip at all, and one
 * whose tracker was MDBList saw no arrival chip anywhere, however recently the
 * episode had aired.
 *
 * A card with progress to resume is a resume, whatever aired: the viewer is
 * mid-episode, and the chip belongs on the card for the episode they have not
 * started. Otherwise a recently aired episode is news - a premiere is the show
 * RETURNING ("New Season"), anything else is one more episode of a show still
 * airing ("New Episode") - and anything older is simply what is up next.
 *
 * [episode] is the episode the CARD shows, so the season-premiere check
 * describes the same episode the viewer is being pointed at.
 */
internal fun upNextArrivalBadge(
    isResume: Boolean,
    airDate: String?,
    episode: Int?
): UpNextBadge {
    if (isResume) return UpNextBadge.CONTINUE_WATCHING

    val airedRecently =
        airDate?.let { isWithinDays(it, NEW_RELEASE_WINDOW_DAYS) } == true

    if (!airedRecently) return UpNextBadge.NEXT_UP

    return if (
        episode?.let { SeasonRules.isSeasonPremiere(it) } == true
    ) {
        UpNextBadge.NEW_SEASON
    } else {
        UpNextBadge.NEW_EPISODE
    }
}

/**
 * The action word a card's subtitle leads with, matching [upNextArrivalBadge]
 * so the chip and the line under the title never contradict each other (a
 * "NEW SEASON" badge over "Resume - S2E1" was the previous MDBList behavior).
 */
internal fun upNextBadgePrefix(
    badge: UpNextBadge
): String = when (badge) {
    UpNextBadge.CONTINUE_WATCHING -> "Resume"
    UpNextBadge.NEW_SEASON -> "New Season"
    UpNextBadge.NEW_EPISODE -> "New Episode"
    UpNextBadge.NEXT_UP -> "Up Next"
}

/** One card per show: keyed by parent id when the card has one, title otherwise. */
internal fun upNextShowKey(item: UpNextItem): String {
    val normalizedParentId =
        upNextIdentifier(item.parentId)

    if (normalizedParentId != null) {
        return "parent:${upNextMediaType(item.parentType)}:$normalizedParentId"
    }

    return upNextTitleKey(item)
}

/**
 * Every identity this card carries, for the duplicate-collapse pass.
 *
 * A show reaches the rail under more than one id at once: local history and
 * add-on catalogs write "tt..." while the tracker cards and TMDB enrichment
 * use "tmdb:<n>". [upNextShowKey] can only name the one flavor a card
 * happens to carry, so the same show produced two different keys and survived
 * the collapse.
 *
 * What made that visible was a card whose enrichment had failed: no poster,
 * and the raw navigation id where its title should have been - so the title
 * fallback could not pair it with its twin either. Both twins resolve the
 * numeric TMDB id (enrichment fills it in, and a "tmdb:<n>" id normalizes to
 * the same number), so emitting a key per form the card knows lets them meet.
 *
 * [upNextShowKey] itself is deliberately NOT widened: it is the persisted
 * dismissal key, and changing its shape would orphan every dismissal already
 * stored on the device and in the cloud.
 */
internal fun upNextIdentityKeys(item: UpNextItem): Set<String> =
    upNextStrongIdentityKeys(item) + upNextTitleKey(item)

/**
 * The id-based identity keys only - [upNextIdentityKeys] without the title
 * fallback. A card with one of these can be compared exactly; the title exists
 * only for a card that has none (see [upNextGroupingKeys] and the duplicate
 * collapse's refusal to hide a differently-identified namesake).
 */
internal fun upNextStrongIdentityKeys(item: UpNextItem): Set<String> {
    val mediaType =
        upNextMediaType(item.parentType)

    return buildSet {
        upNextIdentifier(item.parentId)?.let { add("parent:$mediaType:$it") }

        item.tmdbId
            ?.takeIf { it > 0 }
            ?.let { add("parent:$mediaType:$it") }
    }
}

/**
 * [upNextIdentityKeys] narrowed to one episode, so the same episode reached
 * from two id flavors pairs up - and a *different* episode of that show does
 * not, because it is a separate thing to continue.
 */
internal fun upNextEpisodeKeys(item: UpNextItem): Set<String> {
    val season =
        item.season

    val episode =
        item.episode

    if (season == null || episode == null) {
        return emptySet()
    }

    return upNextIdentityKeys(item)
        .mapTo(linkedSetOf()) { key -> "$key:$season:$episode" }
}

/**
 * Drops the redundant twin cards a show can pick up on the rail:
 *
 *  1. "up next" style cards (Next up / New episode / New season) for a show
 *     that already has an unfinished episode on the rail. A paused episode IS
 *     what "continue watching" means for that show, so the suggestion for the
 *     next one is only useful once the current episode is done.
 *  2. a remote card for the very same episode a local resume row covers,
 *     which can be keyed differently (imdb row vs tmdb/simkl card) and so
 *     survive the show-level dedupe. The local card wins: it is the one that
 *     can be resumed here with the exact stream it was paused on.
 *
 * Both cases are the same underlying bug - one show on the rail twice,
 * because the local row and the tracker card carry different id flavors
 * (which is how a show mid-S4E5 showed up alongside "New Episode S4E6").
 * Matching therefore falls back to the show title when the parent keys
 * disagree, and to the card's resolved TMDB id when even the titles cannot be
 * compared - a card whose enrichment failed carries the raw navigation id as
 * its title, which matches nothing (see [upNextIdentityKeys]).
 */
internal fun collapseDuplicateUpNextCards(
    items: List<UpNextItem>
): List<UpNextItem> {

    fun hasSomethingToResume(item: UpNextItem): Boolean =
        item.badge == UpNextBadge.CONTINUE_WATCHING ||
            item.startPositionMs > 0L ||
            (item.progressPercent ?: 0f) > 0f

    fun isLocal(item: UpNextItem): Boolean =
        item.historyRowId != null

    val resumeKeys =
        items
            .filter { item -> hasSomethingToResume(item) }
            .flatMap { item -> upNextIdentityKeys(item) }
            .toSet()

    val localEpisodeKeys =
        items
            .filter { item -> isLocal(item) }
            .flatMap { item -> upNextEpisodeKeys(item) }
            .toSet()

    if (resumeKeys.isEmpty() && localEpisodeKeys.isEmpty()) {
        return items
    }

    return items.filterNot { item ->
        val matchesAnInProgressShow =
            upNextIdentityKeys(item)
                .any { key -> key in resumeKeys }

        val redundantSuggestion =
            !hasSomethingToResume(item) &&
                matchesAnInProgressShow

        val remoteTwinOfALocalEpisode =
            !isLocal(item) &&
                upNextEpisodeKeys(item)
                    .any { key -> key in localEpisodeKeys }

        redundantSuggestion || remoteTwinOfALocalEpisode
    }
}

/**
 * One card per show for the instant Continue Watching seed.
 *
 * The seed is built from `getContinueWatchingParentsSnapshot`, whose SQL
 * groups by the RAW parent id, so a show whose resume rows were written under
 * two id flavors ("tt..." from the player's canonicalization, "tmdb:<n>"
 * from the TMDB rows and the kids rails) arrives as TWO rows. The enriched
 * pass pairs those through the resolved TMDB id - which is exactly what the
 * snapshot does not have, since resolving it is the enrichment - so until that
 * pass finished the rail drew the same show twice.
 *
 * The display name is the only identity the two rows share at this point, so
 * that is what collapses them. [upNextTitleKey] is deliberately the key here
 * even though [upNextGroupingKeys] refuses it: a card with a resolved id can
 * meet its twin on that id, and grouping on the name would hide a different
 * title that shares one. A snapshot row has no such id to offer. Two genuinely
 * different titles that share a name would therefore collapse here too, but
 * only for the moment before the enriched list replaces this one, and the
 * newest row wins - so the card that survives is the one the viewer touched
 * last, not an arbitrary one.
 */
internal fun collapseInstantSnapshotItems(
    items: List<UpNextItem>
): List<UpNextItem> =
    items
        .groupBy(::upNextTitleKey)
        .mapNotNull { (_, group) ->
            group.maxWithOrNull(
                compareBy<UpNextItem> { it.recencyTimestamp }
                    // Furthest progress wins a same-touch tie: the card that
                    // records more of the episode is the one worth resuming.
                    // `thenByDescending` under maxWith picked the SMALLER
                    // position, i.e. the barely-started flavor.
                    .thenBy { it.startPositionMs }
                    .thenBy { it.id }
            )
        }

/**
 * The identity keys that may GROUP two rail cards into one, as opposed to the
 * looser [upNextIdentityKeys] used to pair a suggestion with the episode it
 * duplicates.
 *
 * The difference is the title key, and it matters here: two DIFFERENT titles
 * legitimately share a name (the two "Ghostbusters", a remake and its
 * original, a US and a UK series), and grouping on the name would hide one of
 * them. A card with a usable id is therefore grouped by its ids alone - the
 * resolved TMDB id is what pairs an "tt..." card with its "tmdb:..." twin,
 * since both sources resolve it (see buildLocalNextUpItem /
 * buildSimklUpNextItem). Only a card with no id at all falls back to the name,
 * which is the case where two flavors have nothing else to meet on.
 */
internal fun upNextGroupingKeys(
    item: UpNextItem
): Set<String> {
    val idKeys = upNextStrongIdentityKeys(item)

    return idKeys.ifEmpty { setOf(upNextTitleKey(item)) }
}

/**
 * The second half of the rail's one-card-per-show rule: merges the strict
 * [upNextGroupingKeys] clusters that name the same show but could not meet on
 * an id.
 *
 * [upNextGroupingKeys] pairs a show through its resolved TMDB id, which is
 * exact - and that is the only id a card from another flavor is guaranteed to
 * share. When one side's TMDB resolution FAILED it carries just its own
 * flavor's parent id (an imdb one, typically) and the two clusters sit side by
 * side, which is the "a couple of shows stay doubled on Continue Watching"
 * report. The name is then the only common ground, so clusters are merged on
 * the title key - guarded, so the merge can never swallow a genuinely
 * different show: two clusters that each resolved a TMDB id are two different
 * shows however they are named (the two "Ghostbusters"), and only a pair whose
 * resolved ids do not contradict may merge. One unresolved twin therefore
 * joins its show, and two fully-resolved namesakes stay two cards.
 */
internal fun mergeTitleTwinClusters(
    groups: List<List<UpNextItem>>
): List<List<UpNextItem>> {
    if (groups.size < 2) return groups

    val parent = IntArray(groups.size) { it }

    fun root(index: Int): Int {
        var node = index
        while (parent[node] != node) node = parent[node]
        var cursor = index
        while (parent[cursor] != cursor) {
            val next = parent[cursor]
            parent[cursor] = node
            cursor = next
        }
        return node
    }

    // The TMDB ids each CLUSTER has resolved. Merging is only allowed while
    // the union of the two does not claim two different ids.
    val idsByRoot =
        groups
            .map { group ->
                group.mapNotNull { item -> item.tmdbId?.takeIf { it > 0 } }
                    .toMutableSet()
            }
            .toMutableList()

    fun union(a: Int, b: Int) {
        val rootA = root(a)
        val rootB = root(b)
        if (rootA == rootB) return
        val winner = if (rootA < rootB) rootA else rootB
        val loser = if (winner == rootA) rootB else rootA
        idsByRoot[winner] += idsByRoot[loser]
        idsByRoot[loser] = mutableSetOf()
        parent[loser] = winner
    }

    val firstByTitle = HashMap<String, Int>()

    groups.forEachIndexed { index, group ->
        for (key in group.mapTo(linkedSetOf()) { item -> upNextTitleKey(item) }) {
            val first = firstByTitle.putIfAbsent(key, index)
            if (first != null) {
                val unionIds = idsByRoot[root(first)] + idsByRoot[root(index)]
                if (unionIds.size <= 1) union(first, index)
            }
        }
    }

    val merged = LinkedHashMap<Int, MutableList<UpNextItem>>()
    groups.forEachIndexed { index, group ->
        merged.getOrPut(root(index)) { mutableListOf() }.addAll(group)
    }

    return merged.values.toList()
}

/**
 * Groups [items] into clusters that share at least one key, transitively:
 * A-B and B-C land in one cluster even when A and C have no key in common.
 *
 * Used wherever the rail must show one card per show. A show reaches it under
 * "tt...", "tmdb:<n>" and a bare numeric id at once, and each key alone only
 * names the flavor that card happens to carry - which is how the same show
 * survived as two cards after the show-level dedupe compared one hand-picked
 * key per card.
 *
 * Clusters come back in first-appearance order and keep input order inside a
 * cluster, so a caller that picks a winner and then sorts stays
 * deterministic.
 */
internal fun <T> clusterByIdentityKeys(
    items: List<T>,
    keysOf: (T) -> Set<String>
): List<List<T>> {
    if (items.isEmpty()) return emptyList()

    val parent = IntArray(items.size) { it }

    fun root(index: Int): Int {
        var node = index
        while (parent[node] != node) node = parent[node]

        // Path compression, so a card carrying many keys stays cheap to merge.
        var cursor = index
        while (parent[cursor] != cursor) {
            val next = parent[cursor]
            parent[cursor] = node
            cursor = next
        }
        return node
    }

    fun union(a: Int, b: Int) {
        val rootA = root(a)
        val rootB = root(b)
        if (rootA == rootB) return

        // Lower index wins, so the cluster's representative is its first
        // member and the output order above holds.
        if (rootA < rootB) parent[rootB] = rootA else parent[rootA] = rootB
    }

    val firstOwnerByKey = HashMap<String, Int>()

    items.forEachIndexed { index, item ->
        for (key in keysOf(item)) {
            val first = firstOwnerByKey.putIfAbsent(key, index)
            if (first != null) union(first, index)
        }
    }

    val clusters = LinkedHashMap<Int, MutableList<T>>()

    items.forEachIndexed { index, item ->
        clusters.getOrPut(root(index)) { mutableListOf() }.add(item)
    }

    return clusters.values.toList()
}

/**
 * Collapses the Upcoming rail's just-built rows to ONE per show.
 *
 * A show has exactly one next unaired episode, so a second row for the same
 * show is a duplicate. The rail merges two sources that name a show
 * differently - the local cards carry its canonical imdb id, the
 * Simkl-derived caught-up cards carry the tracker's flavor - and
 * [UpcomingEpisode] alone cannot tell those apart because it keeps no resolved
 * TMDB id. Pairing therefore happens on the SOURCE cards
 * ([upNextGroupingKeys]) before the winner is kept.
 *
 * The earliest air date wins: within one show the rows describe the same real
 * episode, so the earlier date is the one the second-source correction left in
 * place. Ties prefer the row that knows an episode title and then artwork, so
 * the surviving card is the informative one.
 */
internal fun selectUpcomingPerShow(
    rows: List<Pair<UpNextItem, UpcomingEpisode>>
): List<UpcomingEpisode> =
    clusterByIdentityKeys(
        rows
    ) { (item, _) -> upNextGroupingKeys(item) }
        .map { group ->
            group
                .minWith(
                    compareBy<Pair<UpNextItem, UpcomingEpisode>> { (_, episode) ->
                        episode.airDateEpochMs
                    }
                        .thenByDescending { (_, episode) ->
                            episode.episodeTitle != null
                        }
                        .thenByDescending { (_, episode) ->
                            !episode.poster.isNullOrBlank()
                        }
                        .thenBy { (item, _) -> item.title.lowercase() }
                )
                .second
        }

/**
 * True when a series resolution pass ended with nothing to continue to: it
 * counted the show's aired episodes and every one of them is watched, and the
 * caller supplied no next-episode hint.
 *
 * A pass that counted NOTHING ([totalAiredEpisodes] null / zero - every season
 * lookup failed) is not this case: an offline device cannot prove the viewer
 * is caught up, and hiding shows on a failed lookup is worse than showing one
 * card too many.
 *
 * The hint is excluded because it is the caller's own answer: a tracker can
 * queue an episode that TMDB has not aired or listed yet, and such a show is
 * deliberately kept on the rail (see
 * ShowCompletionRules.isContinueWatchingCandidate).
 *
 * Top level on purpose: the resolver that uses it is a private member of
 * HomeViewModel, and a rule declared next to it would be a member too - i.e.
 * unreachable from the unit tests that pin this behavior down.
 */
internal fun hasNothingLeftToWatch(
    simklSeason: Int?,
    simklEpisode: Int?,
    watchedAiredEpisodes: Int,
    totalAiredEpisodes: Int?
): Boolean =
    simklSeason == null &&
        simklEpisode == null &&
        totalAiredEpisodes != null &&
        totalAiredEpisodes > 0 &&
        watchedAiredEpisodes >= totalAiredEpisodes

/**
 * Upcoming's INCLUSION rule: only a show the viewer is CAUGHT UP on advertises
 * its next unaired episode.
 *
 * Reported: "stuff I'm not caught up to is showing in the Upcoming because it
 * has new aired episodes out - only shows I'm caught up to should be seeing the
 * new unaired episodes". The rail is fed from Continue Watching, which carries
 * every show with something left to watch, so a show three episodes behind and
 * still airing was announcing "the next one airs Friday" over the episodes the
 * viewer has not got to yet. Those already-aired episodes are what is waiting;
 * the episode after them is not news.
 *
 * "Every aired episode watched" is the same caught-up the rest of the app
 * already means by it - the detail page's caught-up state and the eye marker's
 * rule (see `LocalSeriesProgress.isCaughtUp`) - so the rail now agrees with
 * them instead of contradicting them.
 *
 * Continue Watching is deliberately NOT gated this way. The NEW SEASON and
 * NEW EPISODE badges are the alert that an episode has ARRIVED, and they are
 * exactly what tells the viewer there is something to catch up on; the user
 * asked for those to be kept.
 *
 * A show whose episode list could not be counted ([UpNextItem.episodesTotal]
 * null or zero - every season lookup failed) is kept: an offline device cannot
 * prove a backlog, and the same reasoning keeps such a show on Continue
 * Watching (see [hasNothingLeftToWatch]). A tracker's queued next episode is
 * unaffected for the same reason - a show with every aired episode watched is
 * caught up even when the tracker is pointing at an episode TMDB has not aired.
 *
 * Top level and pure so the rule can be pinned by a test: the rail itself is
 * built by a private method that needs a database, a tracker session and a
 * network.
 */
internal fun isCaughtUpForUpcoming(item: UpNextItem): Boolean {
    val airedEpisodes = item.episodesTotal?.takeIf { it > 0 } ?: return true
    return (item.episodesWatched ?: 0) >= airedEpisodes
}

/**
 * One row in the Home "Upcoming" rail: a show's next unaired episode,
 * derived for free from the Continue Watching enrichment (the TMDB detail
 * it already fetches carries next_episode_to_air). No extra network calls.
 */
data class UpcomingEpisode(
    val id: String,
    val parentId: String,
    val parentType: String,
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val season: Int,
    val episode: Int,
    val airDateEpochMs: Long,
    val airDateLabel: String,
    /** Episode title from TMDB, when available for the unaired episode. */
    val episodeTitle: String? = null,
    /** Absolute calendar date ("Mon, Sep 15") for the hero's date line. */
    val airDateFull: String = "",
    /** True when E01 — renders the card's badge as "NEW SEASON". */
    val isSeasonPremiere: Boolean = false
)

internal data class ResolvedHomeSeriesTarget(
    // Nullable on purpose. The resolution's last resort - a show whose season
    // walk resolved no episode at all - knows no pair, and an unknown episode
    // must stay unknown: reported as Continue Watching cards reading "season 1
    // episode 1" for shows the viewer had actually been watching, which is
    // exactly the pair the S1E1 default invented for a show with no episode
    // list. Callers print what they have (see [upNextEpisodeLabel] /
    // [upNextCardEpisodePair]); null prints nothing rather than a guess.
    val season: Int? = null,
    val episode: Int? = null,
    val streamId: String? = null,
    val startPositionMs: Long = 0L,
    val isResume: Boolean = false,
    val airDate: String? = null,
    val episodeTitle: String? = null,
    val episodeDescription: String? = null,
    val runtimeMinutes: Int? = null,
    val episodesWatched: Int? = null,
    val episodesTotal: Int? = null,
    val episodesRemaining: Int? = null,
    val episodeThumbnail: String? = null,
    val episodeRating: Double? = null,

    /** True when this target is the last aired episode of its season. */
    val isSeasonFinale: Boolean = false,

    /** True when this target is the show's final aired episode. */
    val isSeriesFinale: Boolean = false,
)

internal data class ShowEpisodeTotals(
    val watched: Int,
    val total: Int
)

internal sealed interface SimklUpNextResult {

    data class Success(
        val items: List<UpNextItem>
    ) : SimklUpNextResult

    data object NotConfigured : SimklUpNextResult

    data class Failed(
        val error: Throwable
    ) : SimklUpNextResult
}

