package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.settings.ResumeAuthority
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continue Watching's rail publishes as each tracker feed lands.
 *
 * The rail already put the local cards up first, but the enriched half waited
 * for ONE merged publish after every tracker had answered - so a slow tracker
 * held the whole enriched rail while its own cards were ready, and a tracker
 * that never answered held it for as long as the timeouts took.
 *
 * What these cases pin is the shape that replaces it: the local cards, then
 * each feed's cards the moment that feed answers, with the merged publish's
 * contents at the end whatever order they arrived in, no twins along the way,
 * the previous cards of a feed that has not answered still on screen, and the
 * dismissals plus the locally-finished filter applied to EVERY publish - not
 * only to the last one, where a dismissed card could flash back in.
 */
class ContinueWatchingArrivalsTest {

    private val local =
        localCard(parentId = "tt1", title = "Paused Show")

    private val simklCard =
        trackerCard(ResumeSource.SIMKL, parentId = "tt2", title = "Simkl Show")

    private val mdblistCard =
        trackerCard(ResumeSource.MDBLIST, parentId = "tt3", title = "Mdblist Show")

    private val traktCard =
        trackerCard(ResumeSource.TRAKT, parentId = "tt4", title = "Trakt Show")

    // ── T2: incremental publishes, merged contents ─────────────────────────

    @Test
    fun `the rail grows feed by feed and lands on the merged publish's contents`() =
        runBlocking {

            val published = mutableListOf<List<UpNextItem>>()

            val arrivals =
                arrivals(published = published, localCards = listOf(local))

            arrivals.publishLocal()

            assertEquals(
                "the local cards are first on screen, on their own",
                listOf("Paused Show"),
                titles(published.single())
            )

            arrivals.arrive(TrackerFeed.SIMKL, listOf(simklCard))

            assertEquals(
                "Simkl's cards land beside the local ones, not instead of them",
                dedupeAndSortUpNext(
                    listOf(local, simklCard),
                    ResumeAuthority.DEFAULT
                ),
                published.last()
            )
            assertTrue(
                "and the local card that was already up is still there",
                published.last().contains(local)
            )

            // Trakt answers before MDBList here on purpose: the merge order is
            // the feeds' own, so arrival order must not decide the contents.
            arrivals.arrive(TrackerFeed.TRAKT, listOf(traktCard))
            arrivals.arrive(TrackerFeed.MDBLIST, listOf(mdblistCard))

            assertEquals(
                "the last publish is the merged publish's contents",
                dedupeAndSortUpNext(
                    listOf(local, simklCard, mdblistCard, traktCard),
                    ResumeAuthority.DEFAULT
                ),
                published.last()
            )

            assertEquals(
                "one publish per arrival, plus the local one",
                4,
                published.size
            )

            published.forEachIndexed { step, items ->
                assertEquals(
                    "no show may appear twice on publish #${step + 1}",
                    items.size,
                    items.map { showDedupeKey(it) }.distinct().size
                )
            }
        }

    @Test
    fun `a show two feeds both carry is one card, whichever landed first`() =
        runBlocking {

            // The commonest twin there is: the viewer has a resume row, and a
            // tracker also reports the show. The rail must never grow a second
            // card for it, in either arrival order.
            val fromSimkl = trackerCard(ResumeSource.SIMKL, "tt1", "Paused Show")
            val fromTrakt = trackerCard(ResumeSource.TRAKT, "tt1", "Paused Show")

            val published = mutableListOf<List<UpNextItem>>()

            val arrivals =
                arrivals(published = published, localCards = listOf(local))

            arrivals.publishLocal()
            arrivals.arrive(TrackerFeed.TRAKT, listOf(fromTrakt))
            arrivals.arrive(TrackerFeed.SIMKL, listOf(fromSimkl))

            assertEquals(
                "the local card wins the title it already occupies",
                listOf("Paused Show"),
                titles(published.last())
            )

            published.forEachIndexed { step, items ->
                assertEquals(
                    "one card per show on publish #${step + 1}",
                    items.size,
                    items.map { showDedupeKey(it) }.distinct().size
                )
            }
        }

    // ── T3: a feed that has not answered keeps its cards ───────────────────

    @Test
    fun `a tracker that has not answered keeps its cards, and a fresh answer replaces them`() =
        runBlocking {

            // What was on the rail before this pass: two cards from Simkl, one
            // of which the fresh answer will still carry.
            val unseen = trackerCard(ResumeSource.SIMKL, "tt8", "Still Unseen")
            val refreshed = trackerCard(ResumeSource.SIMKL, "tt9", "Just Refreshed")

            val published = mutableListOf<List<UpNextItem>>()

            val arrivals =
                arrivals(
                    published = published,
                    localCards = listOf(local),
                    previousTrackerCards = listOf(unseen, refreshed)
                )

            arrivals.publishLocal()

            assertTrue(
                "the cards already on the rail survive the local-first publish",
                titles(published.last()).containsAll(listOf("Still Unseen", "Just Refreshed"))
            )

            arrivals.arrive(TrackerFeed.TRAKT, listOf(traktCard))

            assertTrue(
                "and they survive another feed landing while Simkl has not " +
                    "answered yet - an unanswered feed is never emptied",
                titles(published.last())
                    .containsAll(listOf("Still Unseen", "Just Refreshed", "Trakt Show"))
            )

            val fresh =
                trackerCard(ResumeSource.SIMKL, "tt9", "Just Refreshed", episode = 6)

            arrivals.arrive(TrackerFeed.SIMKL, listOf(fresh))

            assertFalse(
                "a fresh answer replaces the feed's cards, so a show it no longer " +
                    "carries is gone",
                titles(published.last()).contains("Still Unseen")
            )
            assertEquals(
                "and the show it still carries appears ONCE, as the fresh card",
                1,
                published.last().count { showDedupeKey(it) == showDedupeKey(fresh) }
            )
            assertEquals(
                "the fresh card is the one on screen",
                fresh,
                published.last().single { showDedupeKey(it) == showDedupeKey(fresh) }
            )
        }

    // ── T4: the filters run on every publish ───────────────────────────────

    @Test
    fun `dismissals and the locally-finished filter run on every publish`() =
        runBlocking {

            val dismissed =
                trackerCard(ResumeSource.SIMKL, "tt5", "Dismissed Show")
            val finished =
                trackerCard(ResumeSource.MDBLIST, "tt6", "Finished Show")
            val kept =
                trackerCard(ResumeSource.SIMKL, "tt7", "Kept Show")

            val published = mutableListOf<List<UpNextItem>>()

            var dismissalsRun = 0
            var finishedFiltersRun = 0

            val arrivals =
                ContinueWatchingArrivals(
                    localCards = listOf(local),
                    resumeAuthority = ResumeAuthority.DEFAULT,
                    previousTrackerCards = emptyList(),
                    locallyFinished = { items ->
                        finishedFiltersRun += 1
                        items.filterNot { it.title == "Finished Show" }
                    },
                    dismiss = { items ->
                        dismissalsRun += 1
                        items.filterNot { it.title == "Dismissed Show" }
                    },
                    publish = { items -> published += items }
                )

            arrivals.publishLocal()
            arrivals.arrive(TrackerFeed.SIMKL, listOf(dismissed, finished, kept))
            arrivals.arrive(TrackerFeed.TRAKT, listOf(traktCard))

            assertEquals(
                "both filters ran once per publish, the local one included",
                published.size,
                dismissalsRun
            )
            assertEquals(
                published.size,
                finishedFiltersRun
            )

            published.forEachIndexed { step, items ->
                assertFalse(
                    "a dismissed title must never appear, not even briefly " +
                        "(publish #${step + 1})",
                    titles(items).contains("Dismissed Show")
                )
                assertFalse(
                    "nor a tracker card for a locally finished show " +
                        "(publish #${step + 1})",
                    titles(items).contains("Finished Show")
                )
            }

            assertTrue(
                "the rest of the tracker's cards still landed",
                titles(published.last()).contains("Kept Show")
            )
        }

    // ── T5: the BingeCat slot and per-feed seeding (Slice A, X5b) ─────────

    @Test
    fun `a feed is seeded with its own previous cards, not another feed's slot`() =
        runBlocking {

            // What is on screen from the last pass, one card per tracker.
            val previousSimkl = trackerCard(ResumeSource.SIMKL, "tt20", "Previous Simkl")
            val previousTrakt = trackerCard(ResumeSource.TRAKT, "tt21", "Previous Trakt")
            val previousBingeCat =
                trackerCard(ResumeSource.BINGECAT, "tt22", "Previous BingeCat")

            val published = mutableListOf<List<UpNextItem>>()

            val arrivals =
                arrivals(
                    published = published,
                    localCards = listOf(local),
                    previousTrackerCards =
                        listOf(previousSimkl, previousTrakt, previousBingeCat)
                )

            arrivals.publishLocal()

            assertTrue(
                "every feed's cards survive the local-first publish, including " +
                    "the newest feed's - a slot seeded with nothing flashes its " +
                    "cards out until that feed answers",
                titles(published.last()).containsAll(
                    listOf("Previous Simkl", "Previous Trakt", "Previous BingeCat")
                )
            )

            // One feed answers with a fresh card. Nothing else may move: an
            // unanswered feed keeps its own cards exactly where they were.
            arrivals.arrive(
                TrackerFeed.SIMKL,
                listOf(trackerCard(ResumeSource.SIMKL, "tt23", "Fresh Simkl"))
            )

            val afterSimkl = titles(published.last())
            assertTrue("the fresh Simkl answer replaced Simkl's cards", afterSimkl.contains("Fresh Simkl"))
            assertFalse("including the one it no longer carries", afterSimkl.contains("Previous Simkl"))
            assertTrue(
                "and the other feeds' previous cards are untouched",
                afterSimkl.containsAll(listOf("Previous Trakt", "Previous BingeCat"))
            )
        }

    @Test
    fun `BingeCat merges last, so the older feeds keep their precedence`() =
        runBlocking {

            val published = mutableListOf<List<UpNextItem>>()
            val arrivals = arrivals(published = published, localCards = emptyList())

            // The same show from two trackers, BingeCat answering FIRST.
            val bingeCatTwin =
                trackerCard(ResumeSource.BINGECAT, "tt30", "Shared Show", episode = 4)
            val simklTwin =
                trackerCard(ResumeSource.SIMKL, "tt30", "Shared Show", episode = 4)

            arrivals.arrive(TrackerFeed.BINGECAT, listOf(bingeCatTwin))
            arrivals.arrive(TrackerFeed.SIMKL, listOf(simklTwin))

            assertEquals(
                "one card for the show",
                1,
                published.last().count { showDedupeKey(it) == showDedupeKey(simklTwin) }
            )
            assertEquals(
                "and it is the Simkl one: the merge order, not the arrival order, " +
                    "keeps the precedence the feeds had before BingeCat existed",
                simklTwin,
                published.last().single { showDedupeKey(it) == showDedupeKey(simklTwin) }
            )
        }

    @Test
    fun `Trakt's two halves arrive in one slot, and one answer replaces both`() =
        runBlocking {

            val published = mutableListOf<List<UpNextItem>>()
            val arrivals = arrivals(published = published, localCards = emptyList())

            val paused =
                trackerCard(ResumeSource.TRAKT, "tt40", "Paused On Trakt", episode = 2)
            val next =
                trackerCard(ResumeSource.TRAKT, "tt41", "Next On Trakt", episode = 7)

            // What the merge does for a Success: ONE arrive carrying both.
            arrivals.arrive(TrackerFeed.TRAKT, listOf(paused, next))

            assertEquals(
                "both halves are on the rail from a single Trakt arrival",
                listOf("Paused On Trakt", "Next On Trakt").sorted(),
                titles(published.last()).sorted()
            )

            // The next pass answers with the next-episode half only: the whole
            // slot is replaced, so the paused card the session feed dropped is
            // gone rather than lingering beside the fresh answer.
            val refreshed =
                trackerCard(ResumeSource.TRAKT, "tt41", "Next On Trakt", episode = 8)
            arrivals.arrive(TrackerFeed.TRAKT, listOf(refreshed))

            assertEquals(
                "one Trakt slot: the fresh answer is all of it",
                listOf("Next On Trakt"),
                titles(published.last())
            )
            assertEquals(
                "and it is the refreshed card",
                8,
                published.last().single().episode
            )
        }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun arrivals(
        published: MutableList<List<UpNextItem>>,
        localCards: List<UpNextItem>,
        previousTrackerCards: List<UpNextItem> = emptyList()
    ) = ContinueWatchingArrivals(
        localCards = localCards,
        resumeAuthority = ResumeAuthority.DEFAULT,
        previousTrackerCards = previousTrackerCards,
        locallyFinished = { items -> items },
        dismiss = { items -> items },
        publish = { items -> published += items }
    )

    private fun titles(items: List<UpNextItem>): List<String> =
        items.map { it.title }

    private fun localCard(
        parentId: String,
        title: String
    ): UpNextItem = UpNextItem(
        id = "history:$parentId:1:1",
        title = title,
        poster = null,
        badge = UpNextBadge.CONTINUE_WATCHING,
        parentId = parentId,
        parentType = "series",
        season = 1,
        episode = 1,
        historyRowId = "$parentId:1:1",
        recencyTimestamp = 1_000L
    )

    private fun trackerCard(
        source: ResumeSource,
        parentId: String,
        title: String,
        episode: Int = 1
    ): UpNextItem = UpNextItem(
        id = "${source.name.lowercase()}:$parentId:$episode",
        title = title,
        poster = null,
        badge = UpNextBadge.NEXT_UP,
        subtitle = "Up Next - S1E$episode",
        parentId = parentId,
        parentType = "series",
        season = 1,
        episode = episode,
        resumeSource = source,
        recencyTimestamp = 2_000L
    )
}
