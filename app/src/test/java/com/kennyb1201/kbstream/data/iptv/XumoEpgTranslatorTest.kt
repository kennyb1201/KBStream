package com.kennyb1201.kbstream.data.iptv

import com.kennyb1201.kbstream.data.reporting.Redaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Xumo translator, pinned against the shapes the LIVE API actually returns
 * (verified 2026-10-25) rather than the shapes the community generator's code
 * implies.
 *
 * The most consequential difference is the fetch shape: the URL's trailing
 * number is a six-hour BLOCK, not a day page - block 0 is 00:00-06:00 and block
 * 4 answers HTTP 400 - so a day is four block walks, and pagination happens
 * inside each of them. A fixture that assumed one page per day would pass while
 * the shipped adapter fetched six hours of schedule.
 *
 * These are pure and network-free: the fetch is a lambda, so no HTTP stack and
 * no clock is involved in the translation itself.
 */
class XumoEpgTranslatorTest {

    // ── Program metadata mapping ─────────────────────────────────────────

    @Test
    fun `episode title becomes the sub-title when it differs from the title`() {
        val xml = translateOne(
            assetJson = """
                {"title":"American Ninja Warrior",
                 "episodeTitle":"Qualifiers Night 2",
                 "descriptions":{"medium":"Obstacles."}}
            """.trimIndent()
        )
        assertContains(xml, "<sub-title>Qualifiers Night 2</sub-title>")
    }

    @Test
    fun `an episode title that repeats the title is not emitted`() {
        // The generator emits it unconditionally, which renders the same line
        // twice in the guide; the importer would store a pointless sub-title.
        val xml = translateOne(
            assetJson = """{"title":"Ninja Kidz TV","episodeTitle":"Ninja Kidz TV"}"""
        )
        assertFalse(xml.contains("<sub-title>"))
    }

    @Test
    fun `a missing episode title is the normal case and emits nothing`() {
        // Sampled live assets carry `title` and `descriptions` only.
        val xml = translateOne(assetJson = """{"title":"Power Players"}""")
        assertFalse(xml.contains("<sub-title>"))
    }

    @Test
    fun `description falls back large then medium then small then tiny`() {
        assertEquals(
            "L",
            xumoDescription(mapOf("large" to "L", "medium" to "M", "small" to "S", "tiny" to "T"))
        )
        assertEquals(
            "M",
            xumoDescription(mapOf("medium" to "M", "small" to "S", "tiny" to "T"))
        )
        assertEquals("S", xumoDescription(mapOf("small" to "S", "tiny" to "T")))
        assertEquals("T", xumoDescription(mapOf("tiny" to "T")))
    }

    @Test
    fun `no description is empty, not the string null`() {
        assertNull(xumoDescription(emptyMap()))
        assertNull(xumoDescription(null))
        // A present-but-blank value must not win the chain.
        assertEquals("M", xumoDescription(mapOf("large" to "  ", "medium" to "M")))
    }

    @Test
    fun `EP assets are program ids and everything else is a series id`() {
        assertEquals("dd_progid" to "EP012100200456", episodeNum("EP012100200456"))
        assertEquals("dd_seriesid" to "SH012100200456", episodeNum("SH012100200456"))
    }

    // ── Time conversion ──────────────────────────────────────────────────

    @Test
    fun `the API's compact offset spelling becomes an XMLTV timestamp`() {
        // Live value: "2026-10-25T00:00:00+0000". Pinned against the guide
        // parser on purpose: parseXmltvDateMillis deliberately does NOT accept
        // this spelling, and the first live run of the adapter emitted 455
        // channels with ZERO programmes because it was used here.
        assertNull(
            "the XMLTV parser must not be relied on for the API's spelling",
            parseXmltvDateMillis("2026-10-25T00:00:00+0000")
        )
        val epoch = parseXumoInstant("2026-10-25T00:00:00+0000")
        assertNotNull(epoch)
        assertEquals("20261025000000 +0000", xmltvTimestamp(epoch!!))
    }

    @Test
    fun `a program that starts the night before keeps its real hour`() {
        // The first ANW program on 2026-10-25 starts 2026-10-24T22:24:00+0000 -
        // programs cross the day boundary, which is why the fixtures pin the
        // converted value rather than a date bucket.
        val epoch = parseXumoInstant("2026-10-24T22:24:00+0000")!!
        assertEquals("20261024222400 +0000", xmltvTimestamp(epoch))
    }

    @Test
    fun `the other offset spellings parse too`() {
        // Defensive: the endpoint is unofficial and its offset spelling could
        // change without warning. All three reach the same instant.
        val expected = parseXumoInstant("2026-10-25T00:00:00+0000")
        assertEquals(expected, parseXumoInstant("2026-10-25T00:00:00Z"))
        assertEquals(expected, parseXumoInstant("2026-10-25T02:00:00+02:00"))
    }

    @Test
    fun `days span window start through window end inclusive`() {
        val start = parseXmltvDateMillis("2026-10-25T02:00:00Z")!!
        val end = parseXmltvDateMillis("2026-10-26T22:00:00Z")!!
        assertEquals(listOf("20261025", "20261026"), daysFor(start, end))
    }

    @Test
    fun `a window inside one day fetches one day`() {
        val start = parseXmltvDateMillis("2026-10-25T02:00:00Z")!!
        val end = parseXmltvDateMillis("2026-10-25T22:00:00Z")!!
        assertEquals(listOf("20261025"), daysFor(start, end))
    }

    // ── The document ─────────────────────────────────────────────────────

    @Test
    fun `the American Ninja Warrior fixture translates with its real channel id`() {
        // channel 99951318 is the id the playlist carries as
        // `ads.xumo_channelId`, and the lineup's own title is what NAME matching
        // needs to see.
        val xml = translateOne(
            channelId = 99951318L,
            channelTitle = "American Ninja Warrior",
            assetJson = """
                {"title":"American Ninja Warrior",
                 "descriptions":{"medium":"Athletes take on an obstacle course."}}
            """.trimIndent()
        )
        assertContains(xml, "<channel id=\"xumo-99951318\">")
        assertContains(xml, "<display-name>American Ninja Warrior</display-name>")
        assertContains(xml, "channel=\"xumo-99951318\"")
        assertContains(xml, "start=\"20261025000000 +0000\"")
        assertContains(xml, "stop=\"20261025010000 +0000\"")
    }

    @Test
    fun `the channel tile is emitted because the importer keeps channel icons`() {
        val xml = translateOne(assetJson = """{"title":"Power Players"}""")
        assertContains(
            xml,
            "<icon src=\"https://image.xumo.com/v1/channels/channel/xumo-99951318/600x337.png" +
                "?type=channelTile\"/>"
        )
    }

    @Test
    fun `a channel the lineup does not name is not emitted`() {
        // Titles exist ONLY in the channel list; an EPG id with no title could
        // only be emitted under a numeric display-name, which matches nothing
        // and would cost an import slot to prove it.
        val fetcher = fetcher(
            channelList = mapOf(99951318L to "American Ninja Warrior"),
            days = mapOf("20261025" to dayOf(99951318L to oneProgram(), 12345L to oneProgram()))
        )
        val xml = runBlocking {
            XumoEpgTranslator(fetcher).translate(DAY_START, DAY_START + 6 * 3_600_000L)
        }
        assertContains(xml, "xumo-99951318")
        assertFalse(xml.contains("xumo-12345"))
    }

    @Test
    fun `titles and descriptions are escaped so a stray ampersand cannot truncate`() {
        assertContains(xmlEscape("Law & Order <SVU>"), "Law &amp; Order &lt;SVU&gt;")
        val xml = translateOne(assetJson = """{"title":"Law & Order"}""")
        assertContains(xml, "<title>Law &amp; Order</title>")
    }

    // ── The fetch shape ──────────────────────────────────────────────────

    @Test
    fun `one day is four block walks, not one day page`() {
        val requested = mutableListOf<String>()
        val fetcher = fetcher(
            channelList = mapOf(99951318L to "American Ninja Warrior"),
            days = mapOf("20261025" to dayOf(99951318L to oneProgram())),
            record = requested
        )
        runBlocking {
            XumoEpgTranslator(fetcher).translate(DAY_START, DAY_START + 6 * 3_600_000L)
        }
        // Block 3 is still walked even though block 0 already reached the window:
        // the rule is a fixed block count per fetched day, which is what makes a
        // 20 h window's two days a bounded 44-request walk.
        (0 until XumoGuide.BLOCKS_PER_DAY).forEach { block ->
            assertTrue(
                "block $block was not fetched: $requested",
                requested.any { it.contains("/20261025/$block.json") }
            )
        }
        assertFalse(
            "block 4 does not exist and must never be requested",
            requested.any { it.contains("/20261025/4.json") }
        )
    }

    @Test
    fun `pagination walks offset pages until a short page ends the block`() {
        val requested = mutableListOf<String>()
        // A full page is exactly PAGE_SIZE channels; the walk may only stop on
        // the short one that follows.
        val fullPage = (0 until XumoGuide.PAGE_SIZE).associate { index ->
            (1_000_000L + index) to oneProgram()
        }
        val fetcher: suspend (String) -> String = { url ->
            requested += url
            when {
                url == XumoGuide.CHANNEL_LIST_URL -> channelListJson(
                    fullPage.keys.associateWith { "Channel $it" }
                )
                url.contains("offset=0") -> epgJson(fullPage)
                url.contains("offset=${XumoGuide.PAGE_SIZE}") -> epgJson(
                    mapOf(2_000_000L to oneProgram())
                )
                else -> epgJson(emptyMap())
            }
        }
        runBlocking {
            XumoEpgTranslator(fetcher).translate(DAY_START, DAY_START + 6 * 3_600_000L)
        }
        val blockZero = requested.filter { it.contains("/20261025/0.json") }
        assertEquals(
            "expected offset 0 then offset 50 for block 0, got $blockZero",
            listOf(0, XumoGuide.PAGE_SIZE),
            blockZero.map { it.substringAfter("offset=").substringBefore('&').toInt() }
        )
    }

    @Test
    fun `a window the fetched days do not reach fetches one more day`() {
        val requested = mutableListOf<String>()
        val fetcher = fetcher(
            channelList = mapOf(99951318L to "American Ninja Warrior"),
            days = mapOf(
                "20261025" to dayOf(99951318L to oneProgram()),
                "20261026" to dayOf(99951318L to oneProgram())
            ),
            record = requested
        )
        // A two-day window whose programs exist only on day one: the coverage
        // loop must extend rather than silently hand the importer a guide that
        // stops halfway through the window.
        runBlocking {
            XumoEpgTranslator(fetcher).translate(DAY_START, DAY_START + 30 * 3_600_000L)
        }
        assertTrue(
            "day two was never fetched: $requested",
            requested.any { it.contains("/20261026/") }
        )
    }

    // ── Failure semantics ───────────────────────────────────────────────

    @Test
    fun `a failed page throws rather than importing a partial day`() {
        // §6: a partial walk is a failed SOURCE. Swallowing it would promote a
        // guide that is silently missing the rest of the day.
        val fetcher: suspend (String) -> String = { url ->
            when {
                url == XumoGuide.CHANNEL_LIST_URL ->
                    channelListJson(mapOf(99951318L to "American Ninja Warrior"))
                url.contains("/0.json") -> epgJson(mapOf(99951318L to oneProgram()))
                else -> error("HTTP 500 Internal Server Error")
            }
        }
        var thrown: Throwable? = null
        runBlocking {
            runCatching {
                XumoEpgTranslator(fetcher).translate(DAY_START, DAY_START + 6 * 3_600_000L)
            }.onFailure { thrown = it }
        }
        assertNotNull("a failed block must fail the source", thrown)
    }

    @Test
    fun `a failed channel list fails before any schedule is fetched`() {
        // Without titles nothing could match, so fetching 44 pages first would
        // be pure waste.
        val requested = mutableListOf<String>()
        val fetcher: suspend (String) -> String = { url ->
            requested += url
            error("HTTP 503 Service Unavailable")
        }
        var thrown: Throwable? = null
        runBlocking {
            runCatching {
                XumoEpgTranslator(fetcher).translate(DAY_START, DAY_START + 6 * 3_600_000L)
            }.onFailure { thrown = it }
        }
        assertNotNull(thrown)
        assertEquals(1, requested.size)
    }

    @Test
    fun `the source key is URL-shaped for every consumer that assumes a URL`() {
        // The audit §5 demands: Redaction, the stamp lines and the diagnostics
        // all treat a source key as a URL. A scheme is what keeps them honest.
        assertTrue(XumoGuide.SOURCE_KEY.contains("://"))
        assertEquals(XumoGuide.SOURCE_KEY, Redaction.url(XumoGuide.SOURCE_KEY))
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private fun translateOne(
        channelId: Long = 99951318L,
        channelTitle: String = "American Ninja Warrior",
        assetJson: String
    ): String {
        val fetcher = fetcher(
            channelList = mapOf(channelId to channelTitle),
            days = mapOf("20261025" to dayOf(channelId to oneProgram())),
            assetJson = assetJson
        )
        return runBlocking {
            XumoEpgTranslator(fetcher).translate(DAY_START, DAY_START + 6 * 3_600_000L)
        }
    }

    private fun fetcher(
        channelList: Map<Long, String>,
        days: Map<String, Map<Long, List<Pair<String, String>>>>,
        assetJson: String = """{"title":"American Ninja Warrior"}""",
        record: MutableList<String>? = null
    ): suspend (String) -> String = { url ->
        record?.add(url)
        when {
            url == XumoGuide.CHANNEL_LIST_URL -> channelListJson(channelList)
            else -> {
                val day = DAY_IN_URL.find(url)?.groupValues?.get(1) ?: ""
                val channels = days[day].orEmpty()
                epgJson(channels, assetJson)
            }
        }
    }

    private fun channelListJson(titles: Map<Long, String>): String {
        val items = titles.entries.joinToString(",") { (id, title) ->
            """{"title":"$title","guid":{"value":"$id"},"number":1313}"""
        }
        return """{"channel":{"item":[$items]}}"""
    }

    private fun epgJson(
        channels: Map<Long, List<Pair<String, String>>>,
        assetJson: String = """{"title":"American Ninja Warrior"}"""
    ): String {
        val channelJson = channels.entries.joinToString(",") { (id, programs) ->
            val schedule = programs.joinToString(",") { (start, end) ->
                """{"assetId":"EP012100200456","start":"$start","end":"$end"}"""
            }
            """{"channelId":$id,"number":1,"schedule":[$schedule]}"""
        }
        return """{"totalChannels":1,"channels":[$channelJson],
            "assets":{"EP012100200456":$assetJson}}""".trimIndent()
    }

    private fun dayOf(vararg channels: Pair<Long, List<Pair<String, String>>>) =
        channels.toMap()

    private fun oneProgram(): List<Pair<String, String>> =
        listOf("2026-10-25T00:00:00+0000" to "2026-10-25T01:00:00+0000")

    private fun assertContains(haystack: String, needle: String) {
        assertTrue("expected to find <$needle> in:\n$haystack", haystack.contains(needle))
    }

    private companion object {
        /** 2026-10-25T00:00:00Z, the day every fixture is built on. */
        val DAY_START: Long = parseXmltvDateMillis("2026-10-25T00:00:00Z")!!
        val DAY_IN_URL = Regex("""/epg/\d+/(\d{8})/""")
    }
}
