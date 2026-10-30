package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.settings.BillboardTrailerMode
import com.kennyb1201.kbstream.data.tmdb.TmdbDiscoverItem
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository

/*
 * The phone Home billboard's rules, as pure functions.
 *
 * The billboard is a cycling hero: up to BILLBOARD_MAX_PAGES titles, each fed
 * from a Home rail (or from TMDB's trending-of-the-day pair), each resolved into
 * its own backdrop/clearlogo/copy. Everything that decides WHICH titles those
 * are, and in what order, lives here rather than inside the screen or the view
 * model, so the merge order, the de-dupe and the cap are testable without a
 * composition or a network.
 */

/**
 * The cap from the spec, and a real bound rather than a taste: every page is a
 * full-resolution backdrop plus a clearlogo, and a pager holding more than ten
 * of them keeps more art resident than the hero can ever show.
 */
internal const val BILLBOARD_MAX_PAGES = 10

/**
 * One billboard page's identity: the title's id AND its type.
 *
 * Not the id alone. TMDB numbers movies and shows in separate spaces, so
 * "movie 1399" and "series 1399" are two different titles that would collapse
 * into one page if the id were the whole key - and a chosen catalog's items
 * are de-duped against the NEXT catalog's, where an id collision is exactly the
 * case that would silently drop a title.
 */
internal fun billboardPageKey(item: MetaPreview): String =
    item.type.trim().lowercase() + ":" + item.id.trim()

/**
 * The billboard's pages from several rail sources: concatenated in the order
 * the viewer picked the catalogs, de-duplicated by identity, capped.
 *
 * An empty source contributes nothing, which is the rule that makes one broken
 * catalog a missing page rather than a broken billboard. The caller decides
 * what an all-empty result means (fall back to the default feed, then hide).
 */
internal fun billboardMergeFeeds(
    sources: List<List<MetaPreview>>,
    cap: Int = BILLBOARD_MAX_PAGES
): List<MetaPreview> {
    val seen = HashSet<String>(cap * 2)
    val pages = ArrayList<MetaPreview>(cap)
    for (source in sources) {
        for (item in source) {
            if (pages.size >= cap) return pages
            if (item.id.isBlank()) continue
            if (seen.add(billboardPageKey(item))) pages.add(item)
        }
    }
    return pages
}

/**
 * The default feed: movie #1, TV #1, movie #2, TV #2, ...
 *
 * Interleaved by RANK rather than concatenated, so the first page of a fresh
 * install is not "nine films and then a show". Once one list runs out the other
 * continues in its own order, and the de-dupe/cap rules are
 * [billboardMergeFeeds]'s.
 */
internal fun billboardInterleaveFeeds(
    movies: List<MetaPreview>,
    shows: List<MetaPreview>,
    cap: Int = BILLBOARD_MAX_PAGES
): List<MetaPreview> {
    val merged = ArrayList<MetaPreview>(cap)
    var index = 0
    while (merged.size < cap && (index < movies.size || index < shows.size)) {
        if (index < movies.size) merged.add(movies[index])
        if (merged.size < cap && index < shows.size) merged.add(shows[index])
        index += 1
    }
    return billboardMergeFeeds(listOf(merged), cap)
}

/**
 * One TMDB discover/trending row as a billboard preview.
 *
 * [railType] is "movie" or "series" - the app's rail spelling rather than
 * TMDB's "movie"/"tv", because every downstream reader (Detail's route, the
 * history rows, the artwork resolver) keys on the rail spelling.
 */
internal fun TmdbDiscoverItem.toBillboardPreview(railType: String): MetaPreview =
    MetaPreview(
        id = "tmdb:" + id,
        type = railType,
        name = name ?: title.orEmpty(),
        poster = posterPath
            ?.takeIf { it.isNotBlank() }
            ?.let { TmdbRepository.POSTER_BASE + it },
        background = backdropPath
            ?.takeIf { it.isNotBlank() }
            ?.let { TmdbRepository.BACKDROP_BASE + it },
        releaseInfo = (firstAirDate ?: releaseDate)
            ?.takeIf { it.length >= 4 }
            ?.take(4),
        tmdbRating = voteAverage?.takeIf { it > 0.0 }
    )

/**
 * The billboard's items for the viewer's feed selection, or null when the
 * selection produced nothing at all.
 *
 * [sources] is every rail the billboard may be fed from, keyed the way the Home
 * manager keys a rail (so the picker's chips and this lookup cannot disagree).
 * The trending-day sentinel is a SOURCE too, in the position it was picked, so
 * "trending today" and a catalog can be merged in either order rather than the
 * default being a mode that replaces a selection.
 *
 * Null means "nothing selected, or everything selected was empty": the caller
 * falls back to the default feed, and hides the slot when that is empty too.
 * An EMPTY selection is the default selection, never an empty feed.
 */
internal fun billboardChosenFeed(
    keys: List<String>,
    sources: Map<String, List<MetaPreview>>,
    defaultFeed: List<MetaPreview>,
    cap: Int = BILLBOARD_MAX_PAGES
): List<MetaPreview>? {
    val order = keys.ifEmpty { AppPreferences.DEFAULT_BILLBOARD_FEEDS }
    val merged = billboardMergeFeeds(
        sources = order.map { key ->
            if (isBillboardDefaultFeed(key)) defaultFeed else sources[key].orEmpty()
        },
        cap = cap
    )
    return merged.ifEmpty { null }
}

// ── The Ken Burns sweep (spec 2.6) ──────────────────────────────────
//
// Motion is specified as numbers, so it is decided here as numbers: the pager
// only interpolates these fields with one 0..1 value. That value is also the
// progress bar's fill, which is what makes "the sweep and the fill pause
// together and resume from the held fraction" true by construction rather than
// by two timers agreeing.

/** The furthest a sweep zooms, and the cap the spec names. */
internal const val BILLBOARD_KEN_BURNS_MAX_SCALE = 1.10f

/** Pan, as a fraction of the backdrop's width ("up to 2%"). */
internal const val BILLBOARD_KEN_BURNS_PAN_FRACTION = 0.02f

/**
 * How long the sweep runs when the billboard cannot advance by itself.
 *
 * "Manual only" has no dwell to fill, but a backdrop still has to move
 * somewhere and stop: ten seconds, then it holds at its end state.
 */
internal const val BILLBOARD_KEN_BURNS_MANUAL_MS = 10_000L

/** The content block's entrance: a fade plus this much rise, once per page. */
internal const val BILLBOARD_CONTENT_REVEAL_MS = 450

/** The rise the content entrance travels, in dp. */
internal const val BILLBOARD_CONTENT_RISE_DP = 8

/** The progress bar's track, in dp. */
internal const val BILLBOARD_SEGMENT_TRACK_DP = 3

/**
 * One page's backdrop sweep: where it starts, where it ends, how long it takes.
 *
 * A data class rather than a lambda so the screen can hand it to the image
 * layer once and interpolate it per frame without allocating.
 */
internal data class BillboardSweep(
    val startScale: Float,
    val endScale: Float,
    /** Horizontal offset at the start, as a fraction of the image's width. */
    val startPanFraction: Float,
    /** ...and at the end. */
    val endPanFraction: Float,
    val durationMs: Long
)

/**
 * The sweep for the page at [index], over the billboard's current dwell.
 *
 * Direction alternates by page PARITY, per the spec: an even page zooms in
 * while panning one way, an odd page zooms out while panning the other, so two
 * consecutive pages never repeat the same move. The duration is the dwell
 * itself, so the move lands exactly as the page would advance; [cycleMs] of 0
 * ("Manual only") has no dwell, and uses [BILLBOARD_KEN_BURNS_MANUAL_MS].
 */
internal fun billboardSweep(index: Int, cycleMs: Long): BillboardSweep {
    val durationMs = if (cycleMs > 0L) cycleMs else BILLBOARD_KEN_BURNS_MANUAL_MS
    return if (index % 2 == 0) {
        BillboardSweep(
            startScale = 1f,
            endScale = BILLBOARD_KEN_BURNS_MAX_SCALE,
            startPanFraction = 0f,
            endPanFraction = -BILLBOARD_KEN_BURNS_PAN_FRACTION,
            durationMs = durationMs
        )
    } else {
        BillboardSweep(
            startScale = BILLBOARD_KEN_BURNS_MAX_SCALE,
            endScale = 1f,
            startPanFraction = 0f,
            endPanFraction = BILLBOARD_KEN_BURNS_PAN_FRACTION,
            durationMs = durationMs
        )
    }
}

/**
 * How full one progress segment is: 0..1.
 *
 * Completed pages stay full and upcoming ones stay empty, which is what makes
 * the bar read as position; only the CURRENT segment animates, and only when
 * there is an animation to show ([animated] is false for "Manual only" and for
 * system animations off, where the current segment is simply full - position
 * without motion).
 */
internal fun billboardSegmentFill(
    segment: Int,
    current: Int,
    progress: Float,
    animated: Boolean
): Float = when {
    segment < current -> 1f
    segment > current -> 0f
    animated -> progress.coerceIn(0f, 1f)
    else -> 1f
}

/** A cycle option as its chip reads: "Manual only", "6s", ... */
internal fun billboardCycleLabel(ms: Long): String =
    if (ms <= 0L) "Manual only" else "${ms / 1_000L}s"

/** A trailer mode as its chip reads. */
internal fun billboardTrailerModeLabel(mode: BillboardTrailerMode): String = when (mode) {
    BillboardTrailerMode.OFF -> "Off"
    BillboardTrailerMode.AUTO -> "Auto-play"
    BillboardTrailerMode.MANUAL -> "Manual"
}

/** The feed chip's label for the default entry. */
internal val BILLBOARD_DEFAULT_FEED_LABEL = "Trending today"

/**
 * Whether a selected feed key is the trending-day default.
 *
 * One spelling of the sentinel, so the picker, the screen and the view model
 * cannot disagree about which key means "the default feed" - and so a stored
 * list that somehow carries both keys is still just the default.
 */
internal fun isBillboardDefaultFeed(key: String): Boolean =
    key == AppPreferences.BILLBOARD_TRENDING_DAY_FEED

/**
 * One-shot handoff from the billboard's Play button to the Detail page.
 *
 * Play is spec'd to behave EXACTLY like pressing Play on that title's Detail
 * page, and the only way to guarantee that is to let that page perform the
 * press: this request carries the title's id through the navigation, and
 * DetailScreenMobile consumes it and calls its own `playCurrent()` - the same
 * function its Play button calls. Nothing here resolves a stream, chooses an
 * episode or knows what auto-select does.
 *
 * The id is part of the request because a route can land on a DIFFERENT title
 * than the one pressed (a stale back-stack entry, a re-keyed Detail view model).
 * [consume] answers true only when the id is the page's own, so a request can
 * never start playback on the wrong title - and it is read once, so it cannot
 * turn a later ordinary Play press into an autoplay.
 */
object BillboardPlaySelection {

    private var requestedId: String? = null
    private var requestedAtMs: Long = 0L

    /** Long enough for a navigation to happen, short enough that a stray
     *  request cannot outlive the press that made it (see
     *  [com.kennyb1201.kbstream.ui.components.ManualSourceSelection]). */
    private const val VALID_FOR_MS = 12_000L

    fun request(id: String) {
        requestedAtMs = System.currentTimeMillis()
        requestedId = id.trim()
    }

    /**
     * Reads the request once and clears it, whether or not it was still fresh.
     * True only when it named [id] - the id of the page doing the asking.
     */
    fun consume(id: String?): Boolean {
        val wanted = requestedId?.let { requested ->
            requested.isNotBlank() &&
                id != null &&
                requested == id.trim() &&
                System.currentTimeMillis() - requestedAtMs <= VALID_FOR_MS
        } ?: false
        requestedId = null
        requestedAtMs = 0L
        return wanted
    }
}
