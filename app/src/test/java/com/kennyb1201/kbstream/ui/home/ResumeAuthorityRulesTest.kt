package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.settings.ResumeAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The toggle every resume source feeds, and only one of them wins.
 *
 * The rail merges local history, Simkl, MDBList and Trakt. Choosing a source
 * must not drop the others: they are the fallback for titles the chosen source
 * has never seen, or the rail would empty the moment a viewer picks a tracker
 * that is not connected. And a tracker card that is merely "next up" - the show
 * finished on that account - must never replace a real in-progress position just
 * because it came from the authoritative source.
 */
class ResumeAuthorityRulesTest {

    @Test
    fun `the chosen tracker wins a title every source knows`() {
        val candidates = listOf(
            card("local", ResumeSource.LOCAL, positionMs = 600_000),
            card("simkl", ResumeSource.SIMKL, positionMs = 900_000),
            card("mdblist", ResumeSource.MDBLIST, positionMs = 300_000)
        )

        assertEquals(
            "Simkl's resume point is what the viewer asked to believe",
            "simkl",
            resumeAuthorityWinner(candidates, ResumeAuthority.SIMKL)?.id
        )
        assertEquals(
            "and MDBList's is the answer once the toggle moves",
            "mdblist",
            resumeAuthorityWinner(candidates, ResumeAuthority.MDBLIST)?.id
        )
    }

    @Test
    fun `this device is a real authority too`() {
        val candidates = listOf(
            card("local", ResumeSource.LOCAL, positionMs = 120_000),
            card("simkl", ResumeSource.SIMKL, positionMs = 900_000)
        )

        assertEquals(
            "local history carries the exact millisecond position, so choosing " +
                "it must beat a tracker's",
            "local",
            resumeAuthorityWinner(candidates, ResumeAuthority.LOCAL)?.id
        )
    }

    @Test
    fun `a source that does not know the title leaves the best other card`() {
        val candidates = listOf(
            card("local", ResumeSource.LOCAL, positionMs = 120_000),
            card("mdblist", ResumeSource.MDBLIST, positionMs = 300_000)
        )

        val winner = resumeAuthorityWinner(candidates, ResumeAuthority.TRAKT)?.id
        assertTrue(
            "Trakt has nothing for this title, so the merge keeps one of the cards " +
                "it does have: $winner",
            winner in setOf("local", "mdblist")
        )
    }

    @Test
    fun `a next-up card does not count as the authoritative resume`() {
        val candidates = listOf(
            card("simkl-next", ResumeSource.SIMKL, positionMs = 0L, resume = false),
            card("local", ResumeSource.LOCAL, positionMs = 120_000)
        )

        assertEquals(
            "the show finished on Simkl; its next-episode card must not replace " +
                "the in-progress position this device knows",
            "local",
            resumeAuthorityWinner(candidates, ResumeAuthority.SIMKL)?.id
        )
    }

    @Test
    fun `the rail keeps the authoritative card even when it twins a local episode`() {
        val local = card("local", ResumeSource.LOCAL, positionMs = 100_000).copy(
            parentId = "tt1",
            parentType = "series",
            season = 1,
            episode = 2,
            historyRowId = "h1"
        )
        val trakt = card("trakt", ResumeSource.TRAKT, positionMs = 400_000).copy(
            parentId = "tt1",
            parentType = "series",
            season = 1,
            episode = 2
        )

        assertEquals(
            "the duplicate collapse must not drop the card the viewer chose as " +
                "authoritative before the winner rule ever sees it",
            "trakt",
            dedupeAndSortUpNext(listOf(local, trakt), ResumeAuthority.TRAKT).first().id
        )
        assertEquals(
            "and this device's card wins when it is the chosen source",
            "local",
            dedupeAndSortUpNext(listOf(local, trakt), ResumeAuthority.LOCAL).first().id
        )
    }

    @Test
    fun `the authoritative episode wins when the sources are on different ones`() {
        val local = card("local", ResumeSource.LOCAL, positionMs = 100_000).copy(
            parentId = "tt2",
            parentType = "series",
            season = 1,
            episode = 2
        )
        val simkl = card("simkl", ResumeSource.SIMKL, positionMs = 100_000).copy(
            parentId = "tt2",
            parentType = "series",
            season = 1,
            episode = 5
        )

        val merged = dedupeAndSortUpNext(listOf(local, simkl), ResumeAuthority.SIMKL)
        assertEquals("simkl", merged.first().id)
        assertEquals(5, merged.first().episode)
    }

    @Test
    fun `a local target beats a tracker target when the two disagree`() {
        // Both are advance cards - nothing is in progress - and the feeds
        // disagree about which episode comes next. Local rows are this
        // device's own writes; a tracker feed can lag them, never lead them.
        val local = card("local", ResumeSource.LOCAL, positionMs = 0L, resume = false).copy(
            parentId = "tt3",
            parentType = "series",
            season = 1,
            episode = 6
        )
        val simkl = card("simkl", ResumeSource.SIMKL, positionMs = 0L, resume = false).copy(
            parentId = "tt3",
            parentType = "series",
            season = 1,
            episode = 5
        )

        listOf(
            ResumeAuthority.LOCAL,
            ResumeAuthority.SIMKL,
            ResumeAuthority.MDBLIST
        ).forEach { authority ->
            val merged = dedupeAndSortUpNext(listOf(simkl, local), authority)
            assertEquals(
                "the local walk's target is the answer, whatever the toggle says",
                6,
                merged.first().episode
            )
        }
    }

    @Test
    fun `agreeing targets keep the ordinary order`() {
        // Same episode: nothing to arbitrate, so the copy with the better data
        // (here the one carrying the episode's stream id) still wins.
        val local = card("local", ResumeSource.LOCAL, positionMs = 0L, resume = false).copy(
            parentId = "tt4",
            parentType = "series",
            season = 1,
            episode = 6
        )
        val simkl = card("simkl", ResumeSource.SIMKL, positionMs = 0L, resume = false).copy(
            parentId = "tt4",
            parentType = "series",
            season = 1,
            episode = 6,
            episodeStreamId = "tt4:1:6"
        )

        assertEquals(
            "a target disagreement is the only thing the local card decides",
            "simkl",
            dedupeAndSortUpNext(listOf(local, simkl), ResumeAuthority.DEFAULT).first().id
        )
    }

    @Test
    fun `unknown stored values fall back to this device`() {
        assertEquals(
            ResumeAuthority.LOCAL,
            ResumeAuthority.fromStorage("from-a-newer-build")
        )
        assertEquals(ResumeAuthority.LOCAL, ResumeAuthority.fromStorage(null))
        assertEquals(ResumeAuthority.TRAKT, ResumeAuthority.fromStorage("trakt"))
        assertEquals(
            "the stored form is stable, because it syncs between builds",
            "mdblist",
            ResumeAuthority.MDBLIST.storageValue
        )
    }

    @Test
    fun `an empty cluster has no winner`() {
        assertEquals(null, resumeAuthorityWinner(emptyList(), ResumeAuthority.SIMKL))
    }

    @Test
    fun `every authority maps to its source`() {
        assertEquals(
            listOf(ResumeSource.LOCAL, ResumeSource.SIMKL, ResumeSource.MDBLIST, ResumeSource.TRAKT),
            ResumeAuthority.entries.map { it.toResumeSource() }
        )
        assertNotNull(resumeAuthorityWinner(listOf(card("t", ResumeSource.TRAKT, 1_000)), ResumeAuthority.TRAKT))
    }

    private fun card(
        id: String,
        source: ResumeSource,
        positionMs: Long,
        resume: Boolean = true
    ): UpNextItem = UpNextItem(
        id = id,
        title = "Title $id",
        poster = null,
        badge = if (resume) UpNextBadge.CONTINUE_WATCHING else UpNextBadge.NEXT_UP,
        startPositionMs = positionMs,
        resumeSource = source
    )
}
