package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continue Watching never offers an episode this profile has already watched -
 * and that is a rule of the MERGE, not of a completion event.
 *
 * Reported: after finishing an episode, CW offered that same episode as Up
 * Next; and at the last-aired boundary the show stayed on the rail offering the
 * episode just finished instead of leaving until a new one airs. Both were
 * fixed by restarting the app, because the correction lived in a bus event: it
 * fired when the completion push landed, dropped the tracker cards for THAT
 * episode, and every later re-merge (a resume refresh, a lag retry, a TTL
 * expiry) could readmit a tracker card pointing at an episode the local
 * database already records as watched.
 *
 * The local database is this device's own viewing, so it is never staler than a
 * tracker feed about it. This predicate is applied on every publish, needs no
 * recency window, and never touches a local card (those are built from the same
 * rows). A tracker card for the genuinely NEXT episode survives, and a show with
 * no local rows is untouched.
 */
class LocallyWatchedTrackerTargetTest {

    private fun completedRow(
        parentId: String = "tt1",
        season: Int = 1,
        episode: Int,
        name: String = SHOW,
        completedAt: Long = 1_000L
    ): WatchHistoryEntity =
        WatchHistoryEntity(
            id = "$parentId:$season:$episode",
            parentId = parentId,
            type = "series",
            name = name,
            poster = null,
            streamUrl = null,
            season = season,
            episode = episode,
            positionMs = 0L,
            durationMs = 44L * 60L * 1000L,
            updatedAt = completedAt,
            isCompleted = true,
            completedAt = completedAt
        )

    private fun card(
        id: String,
        episode: Int,
        source: ResumeSource,
        season: Int = 1,
        parentId: String = "tmdb:123",
        title: String = SHOW,
        badge: UpNextBadge = UpNextBadge.NEXT_UP
    ): UpNextItem =
        UpNextItem(
            id = id,
            title = title,
            poster = null,
            badge = badge,
            parentId = parentId,
            parentType = "series",
            season = season,
            episode = episode,
            resumeSource = source
        )

    @Test
    fun `a mid-season tracker card for the episode just finished is dropped`() {
        val rows = (1..5).map { episode -> completedRow(episode = episode) }
        val tracker = card("simkl:1", episode = 5, source = ResumeSource.SIMKL)

        assertTrue(
            "the card points at what the local database records as watched, whatever " +
                "the tracker feed still says",
            dropTrackerCardsForCompletedTargets(listOf(tracker), rows).isEmpty()
        )
    }

    @Test
    fun `the drop does not depend on how long ago the episode was finished`() {
        // The old rule read completions inside a ten-minute window. A tracker
        // card for an episode watched last week is exactly as stale.
        val old = completedRow(episode = 5, completedAt = 1L)
        val tracker = card("simkl:1", episode = 5, source = ResumeSource.SIMKL)

        assertTrue(dropTrackerCardsForCompletedTargets(listOf(tracker), listOf(old)).isEmpty())
    }

    @Test
    fun `a tracker card for the genuinely next episode survives`() {
        val rows = (1..5).map { episode -> completedRow(episode = episode) }
        val next = card("simkl:1", episode = 6, source = ResumeSource.SIMKL)

        assertEquals(
            "the feed has moved on to what is next, which is the card the rail should show",
            listOf(next),
            dropTrackerCardsForCompletedTargets(listOf(next), rows)
        )
    }

    @Test
    fun `finishing the last aired episode leaves the show with no card`() {
        val rows = (1..6).map { episode -> completedRow(episode = episode) }
        val tracker = card("simkl:1", episode = 6, source = ResumeSource.SIMKL)

        assertTrue(
            "the show has nothing left to watch, so its tracker twin must leave the rail " +
                "instead of offering the episode just finished",
            dropTrackerCardsForCompletedTargets(listOf(tracker), rows).isEmpty()
        )
    }

    @Test
    fun `a tracker-only show is untouched`() {
        val tracker = card("simkl:9", episode = 3, source = ResumeSource.SIMKL)

        assertEquals(
            "no local rows for this show means no completed set, so the rule is a no-op",
            listOf(tracker),
            dropTrackerCardsForCompletedTargets(listOf(tracker), emptyList())
        )
        assertEquals(
            "and rows for ANOTHER show cannot condemn it",
            listOf(tracker),
            dropTrackerCardsForCompletedTargets(
                listOf(tracker),
                listOf(completedRow(parentId = "tt999", name = "Another Show", episode = 3))
            )
        )
    }

    @Test
    fun `a local card is never this rule's business`() {
        val local =
            card(
                "nextup:tt1",
                episode = 5,
                source = ResumeSource.LOCAL,
                parentId = "tt1"
            )
        val rows = (1..5).map { episode -> completedRow(episode = episode) }

        assertEquals(
            "local cards are built from the rows this rule reads",
            listOf(local),
            dropTrackerCardsForCompletedTargets(listOf(local), rows)
        )
    }

    @Test
    fun `a trakt card is dropped the same way`() {
        val rows = (1..4).map { episode -> completedRow(episode = episode) }
        val trakt = card("trakt:1", episode = 4, source = ResumeSource.TRAKT)

        assertTrue(
            "the account-wide feeds are one class of input here",
            dropTrackerCardsForCompletedTargets(listOf(trakt), rows).isEmpty()
        )
    }

    @Test
    fun `the id flavors meet on the show title`() {
        // The local row is filed under the imdb id, the tracker card carries
        // the tmdb one: the episode pair is what keeps a same-named show from
        // being caught by the name alone, and the name is what lets the two
        // flavors meet at all.
        val rows = listOf(completedRow(parentId = "tt1", episode = 2))
        val sameShow = card("simkl:1", episode = 2, source = ResumeSource.SIMKL)
        val namesake = card(
            "simkl:2",
            episode = 2,
            source = ResumeSource.SIMKL,
            title = "A Different Show"
        )

        assertTrue(
            dropTrackerCardsForCompletedTargets(listOf(sameShow), rows).isEmpty()
        )
        assertEquals(
            "a differently-named show is not this profile's history",
            listOf(namesake),
            dropTrackerCardsForCompletedTargets(listOf(namesake), rows)
        )
    }

    private companion object {
        const val SHOW = "The UnBelievable"
    }
}
