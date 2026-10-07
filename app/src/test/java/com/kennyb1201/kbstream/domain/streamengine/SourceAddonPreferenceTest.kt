package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order sources come back in once a title's addon outcomes are known.
 *
 * Reported problem: a show where every AIOStreams link was dead played from
 * another addon, and the next episode still offered the same unplayable links
 * first - in the picker's All tab as well as to auto-play.
 */
class SourceAddonPreferenceTest {

    private fun stream(addon: String, url: String) = Stream(
        name = addon,
        title = "Show S2E5 1080p",
        url = url
    )

    private val flix = stream("FlixStreams", "https://flix.example/e5.mp4")
    private val aio = stream("AIOStreams", "https://aio.example/e5.mp4")
    private val other = stream("Torrentio", "https://tor.example/e5.mp4")

    private fun ordered(
        streams: List<Stream>,
        worked: Set<String> = emptySet(),
        failed: Set<String> = emptySet(),
        slow: Set<String> = emptySet()
    ): List<Stream> = SourceAddonPreference.ordered(
        streams = streams,
        addonOf = { it.name },
        outcomes = SourceAddonPreference.Outcomes(
            worked = worked.map { SourceAddonPreference.normalize(it) }.toSet(),
            failed = failed.map { SourceAddonPreference.normalize(it) }.toSet(),
            slow = slow.map { SourceAddonPreference.normalize(it) }.toSet()
        )
    )

    @Test
    fun `the addon that worked leads and the one that would not open goes last`() {
        // The reported shape: AIOStreams ranked first and unplayable,
        // FlixStreams further down and fine.
        val result = ordered(
            streams = listOf(aio, flix, other),
            worked = setOf("FlixStreams"),
            failed = setOf("AIOStreams")
        )

        assertEquals(
            listOf("FlixStreams", "Torrentio", "AIOStreams"),
            result.map { it.name }
        )
    }

    @Test
    fun `an addon nothing is known about is left where the ranker put it`() {
        // Only the failure is known: the working order between everything else
        // must not be disturbed, and the unseen addon must not be punished.
        val result = ordered(
            streams = listOf(aio, other, flix),
            failed = setOf("AIOStreams")
        )

        assertEquals(listOf("Torrentio", "FlixStreams", "AIOStreams"), result.map { it.name })
    }

    @Test
    fun `the ranker's order survives inside a tier`() {
        // Two working addons keep their relative order, as do the rest.
        val workedA = stream("Provider A", "https://a.example/1.mp4")
        val workedB = stream("Provider B", "https://b.example/1.mp4")

        val result = ordered(
            streams = listOf(aio, workedA, other, workedB),
            worked = setOf("Provider A", "Provider B"),
            failed = setOf("AIOStreams")
        )

        assertEquals(
            listOf("Provider A", "Provider B", "Torrentio", "AIOStreams"),
            result.map { it.name }
        )
    }

    @Test
    fun `addon identity ignores case and punctuation`() {
        val result = ordered(
            streams = listOf(aio, flix),
            worked = setOf("flix streams"),
            failed = setOf("aio streams")
        )

        assertEquals(listOf("FlixStreams", "AIOStreams"), result.map { it.name })
    }

    @Test
    fun `a failure wins when an addon is somehow in both records`() {
        // The store keeps only the last outcome, so this is the defensive
        // reading: an addon that may not open must never lead.
        val result = ordered(
            streams = listOf(aio, flix),
            worked = setOf("AIOStreams"),
            failed = setOf("AIOStreams")
        )

        assertEquals(listOf("FlixStreams", "AIOStreams"), result.map { it.name })
    }

    @Test
    fun `nothing known means the list is untouched`() {
        val streams = listOf(aio, flix, other)

        val result = ordered(streams)

        assertTrue("the very list must come back", streams === result)
    }

    @Test
    fun `a single source is never reordered`() {
        val streams = listOf(aio)

        assertTrue(streams === ordered(streams, worked = setOf("FlixStreams")))
    }

    @Test
    fun `a stalling addon sits under an unknown one and over a dead one`() {
        // Reported problem (the other half of it): a source that opens and
        // cannot keep up was demoted for the session and forgotten, so the next
        // episode started from it again. It belongs behind everything the app
        // has nothing against - and still ahead of an addon that will not open
        // at all, because it does play.
        val slow = stream("SlowStreams", "https://slow.example/e5.mp4")

        val result = ordered(
            streams = listOf(aio, slow, other),
            failed = setOf("AIOStreams"),
            slow = setOf("SlowStreams")
        )

        assertEquals(
            listOf("Torrentio", "SlowStreams", "AIOStreams"),
            result.map { it.name }
        )
    }

    @Test
    fun `an addon that worked still leads a stalling one`() {
        // The two only meet across a half-written store (one good session
        // clears the count), and when they do the working one leads: a source
        // that plays cleanly here beats one that plays and stutters.
        val slow = stream("SlowStreams", "https://slow.example/e5.mp4")

        val result = ordered(
            streams = listOf(slow, flix),
            worked = setOf("FlixStreams"),
            slow = setOf("SlowStreams")
        )

        assertEquals(listOf("FlixStreams", "SlowStreams"), result.map { it.name })
    }

    @Test
    fun `the ranker's order survives inside the stalling tier`() {
        val slowA = stream("Slow A", "https://sa.example/1.mp4")
        val slowB = stream("Slow B", "https://sb.example/1.mp4")

        val result = ordered(
            streams = listOf(aio, slowA, slowB),
            slow = setOf("Slow A", "Slow B")
        )

        // The two stalling addons keep their relative order - and the unseen one
        // keeps its place ABOVE both, because a demotion for stalling must never
        // drag an addon under one nobody has a reason to doubt.
        assertEquals(listOf("AIOStreams", "Slow A", "Slow B"), result.map { it.name })
    }

    @Test
    fun `an addon the stream cannot name is never demoted`() {
        val unnamed = Stream(name = null, url = "https://x.example/1.mp4")

        val result = ordered(
            streams = listOf(unnamed, flix),
            worked = setOf("FlixStreams")
        )

        assertEquals("FlixStreams", result.first().name)
        assertEquals(unnamed, result.last())
    }

    @Test
    fun `a show key is its id without the episode pair`() {
        assertEquals("tt1234", SourceAddonPreference.showKeyOf("tt1234:4:11"))
        assertEquals("tt1234", SourceAddonPreference.showKeyOf("tt1234"))
        assertEquals("tmdb:99", SourceAddonPreference.showKeyOf("tmdb:99:1:2"))
        // Not a pair: a two-part id is the show's own id, whatever it holds.
        assertEquals("kitsu:42", SourceAddonPreference.showKeyOf("kitsu:42"))
        assertEquals("tt1234:special", SourceAddonPreference.showKeyOf("tt1234:special"))
        assertEquals("", SourceAddonPreference.showKeyOf(null))
    }
}
