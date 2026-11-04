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
 * Whether [this] title can be a slide: a real backdrop AND a real clearlogo.
 *
 * Both are required and neither has a fallback. A poster stretched across a
 * television reads as a bug, and a bare text title on an empty frame is worse
 * than not showing the title at all - so an item missing either is skipped, and
 * a page whose items all fail this test simply never arms the overlay. That is
 * also why the pool can be empty on a viewer's Home without anything being
 * wrong: an add-on catalogs titles with no artwork, and there is nothing
 * ambient to make out of them.
 */
internal fun MetaPreview.hasScreensaverArt(): Boolean =
    !background.isNullOrBlank() && !logo.isNullOrBlank()

/**
 * The slides for the rails currently on Home, in rail order.
 *
 * Rail order, not focus order and not a ranking: the overlay is a slow tour of
 * the page the viewer left, so it walks the rows top to bottom the way they are
 * drawn. The pool is snapshotted by the caller when the overlay arms - rails
 * reloading underneath it must not reshuffle a show that is already running.
 */
internal fun screensaverSlides(items: List<MetaPreview>): List<MetaPreview> =
    items.filter { it.hasScreensaverArt() }

/**
 * The genre the corner shows, or null to show the logo alone.
 *
 * Only the first genre, and only one that is already known: rail items carry no
 * genre of their own, so the label comes from the hero meta the focus pass has
 * already resolved. A slide whose title was never focused is drawn without a
 * genre rather than waiting for one - no slide delays the show for a label.
 */
internal fun screensaverGenre(genres: List<String>?): String? =
    genres
        ?.asSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }
