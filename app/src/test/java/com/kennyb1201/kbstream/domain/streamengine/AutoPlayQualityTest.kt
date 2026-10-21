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
 * input the ranker cannot see — tonight's network — and these tests pin what it
 * means in practice: a cap moves auto-play *down* to a copy it can serve, it
 * never invents a source the episode rules would not have allowed (a wrong
 * episode, a season pack), it prefers a copy that declares a fitting
 * resolution over one that declares none, and when every copy plainly claims
 * more than the ceiling it starts nothing and leaves the picker — the setting's
 * own promise is "never above 1080p", and the report this answers is "set to
 * 1080p and it is autoplaying 4k".
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
    fun `every spelling of 4K counts as 4K, not as unlabeled`() {
        // The ceiling treats an unlabeled source as allowed, so a 4K copy the
        // reader cannot see is a 4K copy the ceiling cannot stop - which is the
        // "set to 1080p and it still started 4K" report. These are the tags
        // releases use when they never write "2160p".
        listOf(
            "Movie 2024 UHD BluRay REMUX",
            "Movie 2024 2160i HDTV",
            "Movie 2024 3840x2160 WEB-DL",
            "Movie 2024 UHD Bluray DTS"
        ).forEach { name ->
            assertEquals(name, 5, StreamRanker.resolutionRank(s(name)))
        }
    }

    @Test
    fun `an FHD tag is a 1080p copy`() {
        assertEquals(3, StreamRanker.resolutionRank(s("Movie 2024 FHD WEB-DL")))
    }

    @Test
    fun `a 4K ceiling still admits a 4K copy`() {
        assertEquals(
            ranked[0],
            AutoPlayQuality.cappedPick(ranked[0], ranked, null, null, null, AutoPlayQuality.CAP_2160)
        )
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
    fun `a declared copy under the ceiling beats an unlabeled one`() {
        // The old rule took the first unlabeled source, which is where a 4K
        // remux hides behind a size hint: the head was "unknown", so a 1080p
        // ceiling started it while a labeled 1080p sat below. A copy that SAYS
        // it fits now wins over one that says nothing.
        val unlabeled = s("Movie WEB-DL")
        val candidates = listOf(s("Movie 2160p"), unlabeled, s("Movie 1080p"))
        assertEquals(
            candidates[2],
            AutoPlayQuality.cappedPick(candidates[0], candidates, null, null, null, AutoPlayQuality.CAP_1080)
        )
    }

    @Test
    fun `an unlabeled copy is still the fallback when nothing declares a resolution`() {
        val unlabeled = s("Movie WEB-DL")
        val candidates = listOf(s("Movie 2160p"), unlabeled, s("Movie UHD"))
        assertEquals(
            unlabeled,
            AutoPlayQuality.cappedPick(candidates[0], candidates, null, null, null, AutoPlayQuality.CAP_1080)
        )
    }

    @Test
    fun `when every copy plainly claims more than the cap nothing is auto-started`() {
        // The setting's own copy says auto-play "never starts above 1080p", and
        // the picker still lists every source one tap away. Starting the 4K
        // anyway is the report this rule answers: "set to 1080p and it is
        // autoplaying 4k".
        val allUhd = listOf(s("Movie 2160p A"), s("Movie 4K B"), s("Movie UHD C"))
        assertNull(
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

    // ── what the picker says when the ceiling blocked everything ─────────────

    @Test
    fun `the ceiling is named the way the Settings pickers name it`() {
        assertEquals("Auto", AutoPlayQuality.name(AutoPlayQuality.CAP_AUTO))
        assertEquals("4K", AutoPlayQuality.name(AutoPlayQuality.CAP_2160))
        assertEquals("1080p", AutoPlayQuality.name(AutoPlayQuality.CAP_1080))
        assertEquals("720p", AutoPlayQuality.name(AutoPlayQuality.CAP_720))
    }

    @Test
    fun `the picker explains a ceiling that blocked every source`() {
        val allUhd = listOf(s("Movie 2160p A"), s("Movie UHD B"))
        assertEquals(
            "Every source for this title is above your 1080p auto-play ceiling, so " +
                "none was started. Pick one below, or raise Maximum auto-play " +
                "quality in Settings.",
            AutoPlayQuality.ceilingNotice(allUhd, null, null, null, AutoPlayQuality.CAP_1080)
        )
        // Not when the viewer asked for Auto (nothing was blocked) ...
        assertNull(AutoPlayQuality.ceilingNotice(allUhd, null, null, null, AutoPlayQuality.CAP_AUTO))
        // ... nor when a source would have been taken ...
        assertNull(AutoPlayQuality.ceilingNotice(ranked, null, null, null, AutoPlayQuality.CAP_1080))
        // ... nor on an empty list, which is the picker's other message.
        assertNull(AutoPlayQuality.ceilingNotice(emptyList(), null, null, null, AutoPlayQuality.CAP_1080))
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
