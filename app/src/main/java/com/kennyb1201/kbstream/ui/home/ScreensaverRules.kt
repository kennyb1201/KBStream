package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview

/*
 * The TV Home screensaver's rules, as pure functions.
 *
 * The overlay is ambient: it cycles Home's own loaded titles as fullscreen
 * backdrop + clearlogo, and nothing on it is clickable. Everything that decides
 * WHICH titles qualify and HOW LONG each one holds lives here rather than in the
 * screen, so the art gate and the cadence are testable without a composition.
 */

/**
 * How long one slide holds the screen.
 *
 * Deliberately longer than the billboard's 6 s default: the billboard is being
 * browsed, so a page that overstays its welcome is in the way, while this is
 * what the room looks at when nobody is watching. The sweep's duration IS this
 * value, so each move lands exactly as the next slide arrives.
 */
internal const val SCREENSAVER_DWELL_MS = 10_000L

/** How long two slides take to dissolve into each other. */
internal const val SCREENSAVER_CROSSFADE_MS = 700

/**
 * How often the idle timer re-reads the last key press.
 *
 * Polling rather than sleeping the whole timeout: a press has to push the
 * deadline out without restarting anything, and a check inside the last second
 * is what stops a key at 4:59 of a 5-minute wait from being rounded away.
 */
internal const val SCREENSAVER_IDLE_POLL_MS = 1_000L

/** The stored wait, in milliseconds, for [minutes] (a value from the rows' chips). */
internal fun screensaverIdleTimeoutMs(minutes: Int): Long =
    minutes.coerceIn(1, 60) * 60_000L

/**
 * Whether [this] title can be a slide: it has its own clearlogo.
 *
 * The logo is the one piece of art Home already owns, and the one the corner
 * draws unchanged. The BACKDROP is not a gate any more: a rail item's
 * `background` is whatever single image the catalog shipped - for the built-in
 * TMDB rails that is the title's default backdrop, routinely the distributor's
 * promo art with "NEW SEASON" and a service bug burned into it. The screensaver
 * therefore takes its backdrop from the resolved clean artwork instead (see
 * [screensaverCleanSlides]), so requiring a RAW backdrop here would only drop
 * titles whose clean art exists. A title with no logo is still skipped: a bare
 * frame is worse than not showing the title at all. A pool whose items all lack
 * one simply never arms the overlay, which is why Home can be the whole page
 * with no empty state to explain.
 */
internal fun MetaPreview.hasScreensaverArt(): Boolean =
    !logo.isNullOrBlank()

/**
 * The candidate pool for the rails currently on Home, in rail order.
 *
 * Rail order, not focus order and not a ranking: the overlay is a slow tour of
 * the page the viewer left, so it walks the rows top to bottom the way they are
 * drawn. The pool is snapshotted by the caller when the overlay arms - rails
 * reloading underneath it must not reshuffle a show that is already running.
 * These are candidates, not slides: a candidate only becomes one once its clean
 * backdrop has resolved (see [screensaverCleanSlides]).
 */
internal fun screensaverSlides(items: List<MetaPreview>): List<MetaPreview> =
    items.filter { it.hasScreensaverArt() }

/**
 * How many pool titles the idle wait resolves before the show arms.
 *
 * Thirty slides at ten seconds is five minutes of unique show before the tour
 * cycles - longer than any idle wait it can serve - and it caps both the artwork
 * fetches and the TMDB detail calls a page can cost while nobody is watching.
 */
internal const val SCREENSAVER_PRERESOLVE_LIMIT = 30

/**
 * The items the pre-resolve pass walks: the pool's first
 * [SCREENSAVER_PRERESOLVE_LIMIT], in slide order.
 *
 * A prefix, not a sample: the tour starts at the top of Home, so the titles the
 * viewer is most likely to see first are the ones resolved first. The rest of
 * the pool keeps its order and simply has no clean art yet - and an unresolved
 * title is never shown with its raw art.
 */
internal fun screensaverPreresolveItems(items: List<MetaPreview>): List<MetaPreview> =
    items.take(SCREENSAVER_PRERESOLVE_LIMIT)

/**
 * The slides the overlay shows: [items] whose CLEAN backdrop has resolved, in
 * the same order, with that backdrop in place of the rail item's raw one.
 *
 * Clean or nothing. Falling back to `background` here would put the promo art
 * the fix exists to remove back on screen, so a title whose artwork did not
 * resolve is dropped for this show rather than drawn branded. The clearlogo is
 * untouched: it is the rail item's own, and it is drawn over the backdrop
 * exactly as the hero draws it.
 */
internal fun screensaverCleanSlides(
    items: List<MetaPreview>,
    cleanBackdrop: (MetaPreview) -> String?
): List<MetaPreview> =
    items.mapNotNull { item ->
        val backdrop = cleanBackdrop(item)?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        item.copy(background = backdrop)
    }

/**
 * Between the corner label's genres. A middle dot with a hair of space: the
 * line is tracked capitals, so a plain hyphen would read as a dash between two
 * words rather than as a separator.
 */
internal const val SCREENSAVER_GENRE_SEPARATOR = " · "

/**
 * The genre line the corner shows, or null to show the logo alone.
 *
 * The first TWO genres the title resolved, joined - "Action · Drama" - and only
 * genres that are already known: rail items carry none of their own, so the
 * label comes from a resolved title - either the hero the focus pass resolved,
 * or the idle wait's own pass over the pool. A slide whose title has not
 * resolved yet is drawn without a genre rather than waiting for one - no slide
 * delays the show for a label.
 *
 * Two, not the whole list: the label sits under the wordmark at the wordmark's
 * width, where a title's full genre set is a paragraph rather than a caption.
 * A one-genre title still shows just that one, and a title with no genres still
 * shows no line at all. This is the screensaver's line only - the hero draws
 * its own genre from its detail state and keeps showing one.
 */
internal fun screensaverGenre(genres: List<String>?): String? =
    genres
        ?.asSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.take(2)
        ?.joinToString(SCREENSAVER_GENRE_SEPARATOR)
        ?.takeIf { it.isNotEmpty() }
