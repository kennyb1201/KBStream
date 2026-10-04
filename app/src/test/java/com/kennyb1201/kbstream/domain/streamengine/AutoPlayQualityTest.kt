package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The viewer's ceiling on what auto-play starts.
 *
 * The ranker already weighs this device's decode headroom; this is the missing
 * input the ranker cannot see — tonight's network — and these tests pin both
 * directions of it: that a cap moves auto-play *down* to a copy it can serve,
 * and that it never invents a source the episode rules would not have allowed
 * in the first place (a wrong episode, a season pack) or refuses to play
 * anything at all when every copy is taller than the cap.
 */
class AutoPlayQualityTest {

    private fun stream(
        title: String? = null,
        url: String = "https://cdn.example.com/f.mp4",
        name: String? = null
    ) = Stream(name = name, title = title, url = url)

    private fun s(tag: String, url: String = "https://cdn.example.com/f.mp4") =
        stream(title = tag, url = url)

    // ── resolution reading ───────────────────────────────────────────────────

    @Test
    fun `resolution tiers are read from the release name`() {
        assertEquals(5, StreamRanker.resolutionRank(s("Movie 2160p WEB-DL")))
        assertEquals(5, StreamRanker.resolutionRank(s("Movie 4K HDR")))
        assertEquals(4, StreamRanker.resolutionRank(s("Movie 1440p")))
        assertEquals(3, StreamRanker.resolutionRank(s("Movie 1080p")))
        assertEquals(2, StreamRanker.resolutionRank(s("Movie 720p")))
        assertEquals(1, StreamRanker.resolutionRank(s("Movie 480p")))
        assertEquals(0, StreamRanker.resolutionRank(s("Movie WEB-DL")))
    }

    @Test
    fun `cap ranks admit their own tier and below`() {
        assertEquals(5, AutoPlayQuality.capRank(AutoPlayQuality.CAP_2160))
        assertEquals(3, AutoPlayQuality.capRank(AutoPlayQuality.CAP_1080))
        assertEquals(2, AutoPlayQuality.capRank(AutoPlayQuality.CAP_720))
        assertEquals(Int.MAX_VALUE, AutoPlayQuality.capRank(AutoPlayQuality.CAP_AUTO))
    }

    // ── picking under a cap ──────────────────────────────────────────────────

    private val ranked = listOf(
        s("Movie 2160p REMUX"),
        s("Movie 1080p WEB-DL"),
        s("Movie 720p WEBRip")
    )

    @Test
    fun `auto cap returns the ranker's pick untouched`() {
        val pick = ranked.first()
        assertSame(
            pick,
            AutoPlayQuality.cappedPick(pick, ranked, null, null, null, AutoPlayQuality.CAP_AUTO)
        )
    }

    @Test
    fun `a 1080p cap skips a 4K head for the best copy under the ceiling`() {
        assertEquals(
            ranked[1],
            AutoPlayQuality.cappedPick(ranked[0], ranked, null, null, null, AutoPlayQuality.CAP_1080)
        )
    }

    @Test
    fun `a 720p cap skips both taller copies`() {
        assertEquals(
            ranked[2],
            AutoPlayQuality.cappedPick(ranked[0], ranked, null, null, null, AutoPlayQuality.CAP_720)
        )
    }

    @Test
    fun `a pick already under the ceiling is left alone`() {
        val pick = ranked[2]
        assertSame(
            pick,
            AutoPlayQuality.cappedPick(pick, ranked, null, null, null, AutoPlayQuality.CAP_1080)
        )
    }

    @Test
    fun `a source that declares no resolution counts as under the ceiling`() {
        val unlabeled = s("Movie WEB-DL")
        val candidates = listOf(s("Movie 2160p"), unlabeled, s("Movie 1080p"))
        assertEquals(
            unlabeled,
            AutoPlayQuality.cappedPick(candidates[0], candidates, null, null, null, AutoPlayQuality.CAP_1080)
        )
    }

    @Test
    fun `when every copy is taller than the cap the pick is kept rather than refusing to play`() {
        val allUhd = listOf(s("Movie 2160p A"), s("Movie 4K B"))
        assertEquals(
            allUhd[0],
            AutoPlayQuality.cappedPick(allUhd[0], allUhd, null, null, null, AutoPlayQuality.CAP_1080)
        )
    }

    @Test
    fun `a null pick stays null`() {
        assertNull(
            AutoPlayQuality.cappedPick(null, ranked, null, null, null, AutoPlayQuality.CAP_1080)
        )
    }

    @Test
    fun `an unplayable source is never chosen`() {
        val dead = s("Movie 1080p", url = "")
        val candidates = listOf(s("Movie 2160p"), dead, s("Movie 720p"))
        assertEquals(
            candidates[2],
            AutoPlayQuality.cappedPick(candidates[0], candidates, null, null, null, AutoPlayQuality.CAP_1080)
        )
    }

    // ── the episode rules still hold under a cap ─────────────────────────────

    @Test
    fun `a lower copy that declares another episode is skipped for the real episode`() {
        // The wrong episode is *under* the cap, so only the episode rule can
        // keep it out: if auto-play took it the ceiling would be the excuse.
        val wrongEpisode = s("Show S01E04 720p")
        val right1080 = s("Show S01E05 1080p")
        val candidates = listOf(s("Show S01E05 2160p"), wrongEpisode, right1080)
        assertEquals(
            right1080,
            AutoPlayQuality.cappedPick(candidates[0], candidates, 1, 5, null, AutoPlayQuality.CAP_1080)
        )
    }

    @Test
    fun `a season pack is skipped for a real under-cap episode`() {
        val pack = s("Show S01 COMPLETE 720p")
        val right1080 = s("Show S01E05 1080p")
        val candidates = listOf(s("Show S01E05 2160p"), pack, right1080)
        assertEquals(
            right1080,
            AutoPlayQuality.cappedPick(candidates[0], candidates, 1, 5, null, AutoPlayQuality.CAP_1080)
        )
    }

    @Test
    fun `with no episode in the request any under-cap playable source qualifies`() {
        assertEquals(
            ranked[1],
            AutoPlayQuality.cappedPick(ranked[0], ranked, null, null, null, AutoPlayQuality.CAP_1080)
        )
    }
}
