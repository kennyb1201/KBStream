package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.StreamBehaviorHints
import com.kennyb1201.kbstream.data.player.BingeGroupMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one auto-play pick every surface now shares (spec C19).
 *
 * The defect was never "the pick is wrong" - it was that three surfaces each
 * built their own. The pickers had no binge tie-break at all, so a provider the
 * binge had been continuing with for ten episodes lost the next episode to
 * whatever row the resolver happened to put first, and the identity was lost
 * entirely on a cold start because nothing was handed over.
 *
 * Only the pure half is exercised here ([AutoplayPick.binge],
 * [AutoplayPick.preferBinge] and the tier scan they feed); [AutoplayPick.pick]
 * itself reads the viewer's ceiling through AppPreferences and needs a real
 * Context, which the picker contract tests cover.
 */
class AutoplayPickTest {

    private fun stream(title: String, bingeGroup: String? = null) = Stream(
        name = "addon",
        title = title,
        url = "https://example.invalid/${title.hashCode()}",
        behaviorHints = bingeGroup?.let { StreamBehaviorHints(bingeGroup = it) }
    )

    private fun pickAmong(
        streams: List<Stream>,
        binge: AutoplayPick.Binge,
        addonOf: (Stream) -> String? = { null }
    ) = EpisodeMatch.autoplayPick(
        streams = streams,
        season = 1,
        episode = 2,
        runtimeMinutes = 45,
        preferBinge = AutoplayPick.preferBinge(binge, addonOf)
    )

    @Test
    fun `the tagged group breaks a tie inside the episode tier`() {
        val other = stream("Show S01E02 1080p", bingeGroup = "other.group")
        val continued = stream("Show S01E02 1080p", bingeGroup = "binge.group")

        assertSame(
            "the provider the binge has been continuing with wins the tier",
            continued,
            pickAmong(listOf(other, continued), AutoplayPick.Binge(group = "binge.group"))
        )
    }

    @Test
    fun `the add-on half breaks the tie when no row is tagged`() {
        // A provider that stopped tagging consecutive files: the group says
        // nothing, so the add-on it came from is the only identity left.
        val first = stream("Show S01E02 1080p")
        val remembered = stream("Show S01E02 1080p")
        val servedBy = mapOf(first to "AddonA", remembered to "AddonB")

        assertSame(
            "the add-on that served the previous episode continues",
            remembered,
            pickAmong(
                listOf(first, remembered),
                AutoplayPick.Binge(addonName = "AddonB"),
                addonOf = { servedBy[it] }
            )
        )
    }

    @Test
    fun `a blank identity leaves the order alone`() {
        val first = stream("Show S01E02 1080p")
        val tagged = stream("Show S01E02 1080p", bingeGroup = "binge.group")

        assertSame(
            "no identity, no reordering: the first declared match stays the pick",
            first,
            pickAmong(listOf(first, tagged), AutoplayPick.Binge())
        )
    }

    @Test
    fun `continuity never crosses an episode tier`() {
        // A binge-tagged row that declares another episode, and a silent row that
        // declares nothing: the declared-other row must not be lifted over a
        // usable one just because it carries the identity.
        val otherEpisode = stream("Show S01E09 1080p", bingeGroup = "binge.group")
        val silent = stream("Show 1080p")

        assertSame(
            "a row naming another episode is never started for continuity",
            silent,
            pickAmong(listOf(otherEpisode, silent), AutoplayPick.Binge(group = "binge.group"))
        )
    }

    @Test
    fun `the identity prefers a silent row over another provider's silent row`() {
        val other = stream("Show 1080p")
        val continued = stream("Show 1080p", bingeGroup = "binge.group")

        assertSame(
            "between two undeclared rows the identity decides",
            continued,
            pickAmong(listOf(other, continued), AutoplayPick.Binge(group = "binge.group"))
        )
    }

    @Test
    fun `the handed-over identity wins over the remembered one`() {
        val memory = BingeGroupMemory.Identity(bingeGroup = "old.group", addonName = "AddonOld")

        val handed = AutoplayPick.binge(memory, handedGroup = "new.group", handedAddon = null)
        assertEquals("the handoff is the newer fact", "new.group", handed.group)
        assertNull("a stale add-on is not carried beside a fresh group", handed.addonName)

        val coldStart = AutoplayPick.binge(memory, handedGroup = null, handedAddon = "  ")
        assertEquals("a cold start recalls the show's identity", "old.group", coldStart.group)
        assertEquals("And the add-on with it", "AddonOld", coldStart.addonName)

        assertTrue(
            "nothing anywhere is an honestly blank identity",
            AutoplayPick.binge(null, null, null).isBlank
        )
        assertFalse(
            "and either half is enough to continue a binge",
            AutoplayPick.binge(null, null, "AddonB").isBlank
        )
    }

    @Test
    fun `a movie request takes the first candidate and never the identity`() {
        val first = stream("Movie 1080p")
        val tagged = stream("Movie 1080p", bingeGroup = "binge.group")

        assertSame(
            "with no episode there is nothing to tie-break",
            first,
            EpisodeMatch.autoplayPick(
                streams = listOf(first, tagged),
                season = null,
                episode = null,
                preferBinge = AutoplayPick.preferBinge(
                    AutoplayPick.Binge(group = "binge.group"),
                    { null }
                )
            )
        )
    }
}
