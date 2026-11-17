package com.kennyb1201.kbstream.ui.mobile

/**
 * What a billboard page DRAWS, held still (spec 2026-11-17, F1).
 *
 * The first entry to the app settles the pool in a burst, and every settle
 * rebuilds the pages state - so the LIVE fallback (`billboardHeroArt`) can move
 * under a page that is already on screen. Each move was a new URL, a new image
 * request and a flash; the page's own art then swapped in on top of that, which
 * is the rapid flicker of the first 1-2 seconds.
 *
 * This rule makes the first art a page showed STICKY until its own lands:
 *
 *  - [ownArt] is the page's own art (`billboardPageArt`) and wins the moment it
 *    exists. That is the one swap a page is allowed: borrowed -> own.
 *  - [heldArt] is the art the page showed first, recorded by the billboard per
 *    page per deal. It keeps the frame still however many other pages settle.
 *  - [liveFallback] is only the FIRST choice - what the page would borrow right
 *    now - used once before anything is held.
 *
 * A page whose [ownArt] was already there never swaps at all: [ownArt] wins on
 * every pass, so [heldArt] is never even consulted.
 *
 * A pure function rather than a rule inside the composable, so the precedence
 * is stated - and testable - in one place; the billboard only remembers what it
 * has shown and asks this question.
 */
internal fun billboardStickyHeroArt(
    ownArt: String?,
    heldArt: String?,
    liveFallback: String?
): String? = ownArt ?: heldArt ?: liveFallback
