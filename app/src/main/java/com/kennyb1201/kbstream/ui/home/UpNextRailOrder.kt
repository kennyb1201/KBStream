package com.kennyb1201.kbstream.ui.home

/**
 * Continue Watching's order: the shows being watched, most recently watched
 * first, and only then the ones that merely have news.
 *
 * This used to be the other way round. A card whose next episode aired inside
 * the 7-day new-release window led the rail — season first, then episode — and
 * the show the viewer was actually in the middle of sat below every one of
 * them. A tracker feeds a few dozen followed shows, so a show watched minutes
 * ago, with its next episode waiting, landed tens of cards deep: in the one
 * rail whose whole job is to put that card first.
 *
 * Freshness has not been dropped, only demoted. It is still the chip on the
 * card, and "what aired" is what the Upcoming rail is for
 * (`HomeViewModel.upcomingEpisodes`). What it no longer does is outrank the
 * title the viewer was just watching.
 *
 * Extracted from HomeViewModel so the rule can be pinned by a test: the rail
 * itself is built by a private method that needs a database, a tracker session
 * and a network, while the ORDER is pure.
 */

/**
 * The tier a card sorts into: `0` for the shows being watched (a paused
 * episode, or the next one waiting), `1` for the ones with news (a season or
 * episode that recently aired).
 *
 * Both watching badges deliberately share a tier, so which of "resume this"
 * and "the next episode is ready" leads is decided by recency itself rather
 * than by the badge.
 */
internal fun upNextRailTier(badge: UpNextBadge): Int =
    when (badge) {
        UpNextBadge.CONTINUE_WATCHING,
        UpNextBadge.NEXT_UP -> 0

        UpNextBadge.NEW_SEASON,
        UpNextBadge.NEW_EPISODE -> 1
    }

/**
 * The rail's order: tier, then most recently watched, then title.
 *
 * [UpNextItem.recencyTimestamp] is the viewer's own last touch of that show for
 * every source that builds a card — a watch-history row's update time, or the
 * tracker's own `lastWatchedAt` — which is what lets it order cards from a
 * local write against cards that came from Simkl. A card carrying no timestamp
 * (0) sorts last inside its tier rather than claiming the top.
 */
internal val upNextRailComparator: Comparator<UpNextItem> =
    compareBy<UpNextItem> { upNextRailTier(it.badge) }
        .thenByDescending { it.recencyTimestamp }
        .thenBy { it.title.lowercase() }
