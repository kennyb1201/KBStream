package com.kennyb1201.kbstream.ui.home

/**
 * Continue Watching's order: what just arrived leads the rail, and the shows
 * being watched follow it.
 *
 * The rail's job is twofold - hand back the title the viewer paused, and point
 * at a season or episode that has just landed - and it used to serve only the
 * first: every card carrying a "New Season" or "New Episode" chip was pushed
 * behind every show being watched, however big the arrival and however stale
 * the resume. A tracker follows a few dozen shows, so a handful of paused
 * titles was enough to send the news that actually brought the viewer to the
 * app to the very bottom of the rail.
 *
 * The chips are the whole point of the demoted cards, so they are what leads.
 * Freshness is not the only thing that matters - the viewer's own most recent
 * touch still orders the cards within a tier - but a premiere or a new episode
 * outranks a title the viewer merely has a position in.
 *
 * Extracted from HomeViewModel so the rule can be pinned by a test: the rail
 * itself is built by a private method that needs a database, a tracker session
 * and a network, while the ORDER is pure.
 */

/**
 * The tier a card sorts into: `0` for the ones with news (a season or episode
 * that recently aired), `1` for the shows being watched (a paused episode, or
 * the next one waiting).
 *
 * Both news badges deliberately share a tier, so which of "a whole season
 * landed" and "one more episode landed" leads is decided by recency itself
 * rather than by the badge. The watching pair share a tier for the same
 * reason: which of "resume this" and "the next episode is ready" leads is
 * likewise left to recency.
 */
internal fun upNextRailTier(badge: UpNextBadge): Int =
    when (badge) {
        UpNextBadge.NEW_SEASON,
        UpNextBadge.NEW_EPISODE -> 0

        UpNextBadge.CONTINUE_WATCHING,
        UpNextBadge.NEXT_UP -> 1
    }

/**
 * The rail's order: tier, then most recently watched, then title.
 *
 * [UpNextItem.recencyTimestamp] is the viewer's own last touch of that show for
 * every source that builds a card - a watch-history row's update time, or the
 * tracker's own `lastWatchedAt` - which is what lets it order cards from a
 * local write against cards that came from Simkl. A card carrying no timestamp
 * (0) sorts last inside its tier rather than claiming the top.
 */
internal val upNextRailComparator: Comparator<UpNextItem> =
    compareBy<UpNextItem> { upNextRailTier(it.badge) }
        .thenByDescending { it.recencyTimestamp }
        .thenBy { it.title.lowercase() }
