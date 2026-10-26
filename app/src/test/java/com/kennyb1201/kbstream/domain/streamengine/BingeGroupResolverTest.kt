package com.kennyb1201.kbstream.domain.streamengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.StreamBehaviorHints
import com.kennyb1201.kbstream.data.settings.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How the next episode's sources are ordered for a show mid-binge.
 *
 * Reported problem: every AIOStreams link for one show was dead while the same
 * episodes played from another addon - and the next episode came back with the
 * dead addon's links first. The addon that played is carried across the
 * handoff, but the reuse tier that matches on it was gated behind a non-blank
 * bingeGroup, and a provider that stopped tagging consecutive files (exactly
 * what that tier is for) has none - so the tier could never run for the case it
 * was written for.
 */
@RunWith(AndroidJUnit4::class)
class BingeGroupResolverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun defaultToggles() {
        AppPreferences.setBingeGroupPrefer(context, true)
        AppPreferences.setBingeGroupReuse(context, true)
        AppPreferences.setBingeGroupFallback(context, true)
    }

    private fun stream(addon: String, url: String, group: String? = null) = Stream(
        name = "$addon\n1080p",
        title = "Show S2E5 1080p",
        url = url,
        behaviorHints = group?.let { StreamBehaviorHints(bingeGroup = it) }
    )

    private val aio = stream("AIOStreams", "https://aio.example/e5.mp4")
    private val flix = stream("FlixStreams", "https://flix.example/e5.mp4")

    @Test
    fun `an addon alone is context enough to reuse it`() {
        // The reported case: nothing is tagged, so there is no group - but
        // FlixStreams is the addon that just played.
        val ordered = BingeGroupResolver.orderedForNextEpisode(
            context = context,
            streams = listOf(aio, flix),
            previousBingeGroup = null,
            previousAddonName = "FlixStreams"
        )

        assertEquals(
            listOf("FlixStreams", "AIOStreams"),
            ordered.map { it.name?.substringBefore('\n') }
        )
    }

    @Test
    fun `a tagged group still wins over the addon`() {
        val taggedFlix = flix.copy(
            behaviorHints = StreamBehaviorHints(bingeGroup = "prov|1080p")
        )

        val ordered = BingeGroupResolver.orderedForNextEpisode(
            context = context,
            streams = listOf(aio, taggedFlix),
            previousBingeGroup = "prov|1080p",
            previousAddonName = "AIOStreams"
        )

        assertEquals("prov|1080p", ordered.first().bingeGroup)
    }

    @Test
    fun `no group and no addon leaves the ranker's order alone`() {
        val streams = listOf(aio, flix)

        val ordered = BingeGroupResolver.orderedForNextEpisode(
            context = context,
            streams = streams,
            previousBingeGroup = "   ",
            previousAddonName = null
        )

        assertEquals(streams, ordered)
    }

    @Test
    fun `a blank group with an addon that is not in the list still plays something`() {
        // Fallback on: autoplay must not dead-end just because the previous
        // addon answered nothing for this episode.
        val streams = listOf(aio)

        val ordered = BingeGroupResolver.orderedForNextEpisode(
            context = context,
            streams = streams,
            previousBingeGroup = null,
            previousAddonName = "FlixStreams"
        )

        assertEquals(streams, ordered)
    }

    // ── Promotion never re-lifts a sunk copy ──
    //
    // Reported: Russian-dubbed PAW Patrol copies kept starting. Promotion
    // wholesale outranks rank order (`groupMatch + rest`), so once the ranker
    // sank a row, a matching binge group or addon lifted it straight back over
    // the honest head for the next episode. Both tiers now only promote honest
    // matches; the sunk ones keep their ranked position in the remainder.

    private fun showRow(
        addon: String,
        url: String,
        title: String,
        group: String? = null
    ) = stream(addon, url, group).copy(title = title)

    @Test
    fun `a sunk group match does not ride the binge group to the head`() {
        val honestGroup = showRow(
            "AIOStreams",
            "https://aio.example/e6.en.mkv",
            "Show S2E6 1080p WEB-DL 4 GB",
            group = "prov|1080p"
        )
        val otherAddon = showRow(
            "FlixStreams",
            "https://flix.example/e6.mkv",
            "Show S2E6 1080p WEB-DL 4 GB"
        )
        val russian = showRow(
            "AIOStreams",
            "https://aio.example/e6.ru.mkv",
            "Show S2E6 1080p WEB-DL RUS 4 GB",
            group = "prov|1080p"
        )
        // Already ranked, as the caller hands it over: the sunk row last.
        val ranked = listOf(honestGroup, otherAddon, russian)

        val ordered = BingeGroupResolver.orderedForNextEpisode(
            context = context,
            streams = ranked,
            previousBingeGroup = "prov|1080p",
            previousAddonName = "FlixStreams"
        )

        assertEquals(honestGroup, ordered.first())
        assertTrue(ordered.indexOf(russian) > ordered.indexOf(honestGroup))
        assertTrue(ordered.indexOf(russian) > ordered.indexOf(otherAddon))
    }

    @Test
    fun `a sunk same-addon match does not head the next episode`() {
        val honest = showRow(
            "AIOStreams",
            "https://aio.example/e6.en.mkv",
            "Show S2E6 1080p WEB-DL 4 GB"
        )
        val other = showRow("FlixStreams", "https://flix.example/e6.mkv", "Show S2E6 1080p WEB-DL 4 GB")
        val russian = showRow(
            "AIOStreams",
            "https://aio.example/e6.ru.mkv",
            "Show S2E6 1080p WEB-DL RUS 4 GB"
        )
        val ranked = listOf(honest, other, russian)

        val ordered = BingeGroupResolver.orderedForNextEpisode(
            context = context,
            streams = ranked,
            previousBingeGroup = null,
            previousAddonName = "AIOStreams"
        )

        assertEquals(honest, ordered.first())
        assertEquals(russian, ordered.last())
    }

    @Test
    fun `when every group match is sunk the reuse tier still heads an honest row`() {
        val sunkGroup = showRow(
            "AIOStreams",
            "https://aio.example/e6.ru.mkv",
            "Show S2E6 1080p WEB-DL RUS 4 GB",
            group = "prov|1080p"
        )
        val honest = showRow(
            "AIOStreams",
            "https://aio.example/e6.en.mkv",
            "Show S2E6 1080p WEB-DL 4 GB"
        )
        val other = showRow("FlixStreams", "https://flix.example/e6.mkv", "Show S2E6 1080p WEB-DL 4 GB")
        val ranked = listOf(honest, other, sunkGroup)

        val ordered = BingeGroupResolver.orderedForNextEpisode(
            context = context,
            streams = ranked,
            previousBingeGroup = "prov|1080p",
            previousAddonName = "AIOStreams"
        )

        // The group has nothing honest to promote, so the addon tier answers -
        // and it must not re-lift the sunk copy either.
        assertEquals(honest, ordered.first())
        assertEquals(sunkGroup, ordered.last())
    }
}
