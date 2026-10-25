package com.kennyb1201.kbstream.data.iptv

import com.kennyb1201.kbstream.data.reporting.Redaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostics guide-match section.
 *
 * Field case it exists for (2026-10-25, phone, Free TV on the guest profile):
 * Midsomer Murders had program data from a hand-added Roku feed while American
 * Horrors stayed blank, and answering "what are we missing?" needed a
 * per-channel test the report could not provide. The section must therefore
 * attribute every channel to the FIRST source that resolves it - the lineup
 * query's own rule - and count the rest, and it must agree with [matchEpgChannel]
 * exactly, or the capture would accuse a channel the grid is happily showing.
 */
class GuideMatchReportTest {

    @Test
    fun `a channel counts under the first source that resolves it, once`() {
        val kanya = GuideMatchSource(
            url = "https://epg.kanya.io/us/guide.xml.gz",
            byId = emptyMap(),
            byName = mapOf("midsomermurders" to "MidsomerMurders.us@SD")
        )
        val roku = GuideMatchSource(
            url = "https://i.mjh.nz/Roku/all.xml.gz",
            byId = mapOf("midsomermurders.us.sd" to "MidsomerMurders.us@SD"),
            byName = mapOf(
                "midsomermurders" to "MidsomerMurders.us@SD",
                "abcnewslive" to "ABCNewsLive.us"
            )
        )
        val neverLanded = GuideMatchSource<String>(
            url = "https://i.mjh.nz/PlutoTV/us.xml.gz",
            byId = emptyMap(),
            byName = emptyMap()
        )

        val report = attributeGuideMatches(
            channels = listOf(
                channel(id = "midsomer", displayName = "Midsomer Murders"),
                channel(id = "abc3", displayName = "ABC News Live 3")
            ),
            sources = listOf(kanya, roku, neverLanded)
        )

        // Both sources carry Midsomer Murders; the earlier one owns it.
        assertEquals(1, report.matchedBy(kanya.url))
        assertEquals(1, report.matchedBy(roku.url))
        // A source with nothing to match is still printed, with 0 - that is how
        // a dead feed is visible instead of silently absent.
        assertEquals(0, report.matchedBy(neverLanded.url))
        // Counts reconcile by construction, and here on the data too.
        assertEquals(report.channelCount, report.matchedCount + report.unmatchedCount)
        assertEquals(2, report.channelCount)
        assertEquals(2, report.matchedCount)

        val lines = report.lines(Redaction::url)
        assertEquals("guide-match: channels=2 matched=2 unmatched=0", lines.first())
        assertEquals(
            "guide-match by source: ${kanya.url}=1, ${roku.url}=1, ${neverLanded.url}=0",
            lines[1]
        )
        // Nothing unmatched means no empty list header.
        assertTrue(lines.none { it == "guide-match unmatched:" })
    }

    @Test
    fun `every matcher pass is the matcher's verdict, not a second implementation`() {
        val guide = GuideMatchSource(
            url = "https://epg.example/guide.xml.gz",
            byId = mapOf(
                epgLookupKey("BBCNews.us") to "BBCNews.us",
                epgLookupKey("KMTV") to "KMTV"
            ),
            byName = mapOf(
                epgLookupKey("Sky Sports 1") to "SkySports1",
                // The guide spells it bare; only the SIMPLIFIED pass folds a
                // quality qualifier off the playlist's spelling.
                epgLookupKey("ESPN2") to "ESPN2"
            )
        )

        // One channel per pass: exact id, exact name, simplified name, callsign.
        val channels = listOf(
            channel(id = "id-hit", displayName = "Something Else", tvgId = "BBCNews.us"),
            channel(id = "name-hit", displayName = "Sky Sports 1"),
            channel(id = "simplified-hit", displayName = "ESPN2 HD"),
            channel(id = "callsign-hit", displayName = "CBS KMTV-TV", tvgId = "CBS.us@KMTVTV"),
            channel(id = "no-hit", displayName = "American Horrors")
        )

        val report = attributeGuideMatches(channels, listOf(guide))

        channels.forEachIndexed { index, channel ->
            // The expected verdict is asked of matchEpgChannel itself, with the
            // candidate lists spelled out here: a section that skipped a pass,
            // or normalized its candidates differently, disagrees here.
            val expected = matchEpgChannel(
                idCandidates = listOf(channel.tvgId, channel.providerChannelId),
                nameCandidates = listOf(channel.tvgName, channel.displayName, channel.name),
                byId = guide.byId,
                byName = guide.byName,
                callsignCandidates = playlistCallsignKeys(channel).toList()
            ) != null

            assertEquals(
                "verdict for ${channel.displayName} must be the matcher's",
                expected,
                report.rows[index].sourceUrl != null
            )
        }

        assertEquals(4, report.matchedCount)
        assertEquals(1, report.unmatchedCount)
        assertEquals("American Horrors", report.rows.last().displayName)
    }

    @Test
    fun `the unmatched list carries name, tvg-id and group, and never a stream url`() {
        val channel = channel(
            id = "horrors",
            displayName = "American Horrors",
            tvgId = "AmericanHorrors.us@SD",
            group = "Movies",
            // An Xtream provider spells the account in the stream path.
            streamUrl = "http://provider.example:8080/live/alice/hunter2/12345.ts"
        )
        // The guide carries a DIFFERENT channel, so this one is unmatched - the
        // row a capture is actually read for.
        val guide = GuideMatchSource(
            url = "http://guide.example/xmltv.php?username=alice&password=hunter2",
            byId = mapOf(epgLookupKey("MidsomerMurders.us@SD") to "MidsomerMurders.us@SD"),
            byName = emptyMap()
        )

        val text = attributeGuideMatches(listOf(channel), listOf(guide)).lines(Redaction::url).joinToString("\n")

        assertTrue(text.contains("guide-match: channels=1 matched=0 unmatched=1"))
        assertTrue(text.contains("- American Horrors | tvg-id=AmericanHorrors.us@SD | group=Movies"))
        // The source URL is redacted exactly as the guide line above redacts it.
        assertFalse("credentials leaked from a source url", text.contains("hunter2"))
        assertTrue(text.contains("${Redaction.url(guide.url)}=0"))
        // And nothing about the stream itself, path included.
        assertFalse("stream url leaked", text.contains("provider.example"))
        assertFalse("stream path leaked", text.contains("/live/"))
        assertFalse("account name leaked", text.contains("alice"))
    }

    @Test
    fun `an empty playlist field keeps the row's shape`() {
        val report = attributeGuideMatches(
            channels = listOf(channel(id = "x", displayName = "AMG TV")),
            sources = emptyList<GuideMatchSource<String>>()
        )

        val lines = report.lines(Redaction::url)
        assertEquals("guide-match: channels=1 matched=0 unmatched=1", lines[0])
        assertEquals("guide-match by source: none", lines[1])
        assertEquals("- AMG TV | tvg-id=- | group=-", lines[3])
        assertNull(report.rows.single().sourceUrl)
    }

    // ── report-only: guide-side `by <network>` suffixes ────────────────────

    @Test
    fun `a guide-side network suffix is counted with the row it would rescue`() {
        // The 2026-10-25 question, answered with a number instead of the one case
        // it came from: `Pluto TV Storage Wars` vs the guide's
        // `Storage Wars by A&E`.
        val pluto = GuideMatchSource(
            url = "https://i.mjh.nz/PlutoTV/us.xml.gz",
            byId = emptyMap(),
            byName = mapOf(
                epgLookupKey("Storage Wars by A&E") to "suffix-1",
                epgLookupKey("BET Classics") to "plain-1"
            ),
            identityOf = { id ->
                when (id) {
                    "suffix-1" -> GuideChannelIdentity("suffix-1", listOf("Storage Wars by A&E"))
                    else -> GuideChannelIdentity(id, listOf("BET Classics"))
                }
            }
        )

        val report = attributeGuideMatches(
            channels = listOf(
                channel(id = "storage", displayName = "Pluto TV Storage Wars"),
                channel(id = "betpluto", displayName = "BET Classics Pluto TV")
            ),
            sources = listOf(pluto)
        )

        // One suffix candidate, and it is the one the field named.
        assertEquals(1, report.networkSuffixCandidates.size)
        val candidate = report.networkSuffixCandidates.single()
        assertEquals("Storage Wars by A&E", candidate.guideName)
        assertEquals(
            "the affixed row is the one a strip would rescue",
            "Pluto TV Storage Wars",
            candidate.playlistName
        )
        assertEquals(1, report.networkSuffixRescues)

        val lines = report.lines(Redaction::url)
        assertTrue(
            "the count line is greppable",
            lines.contains(
                "guide-side \"by\" suffix (report only): " +
                    "unmatched-guide=1 would-rescue-playlist=1"
            )
        )
        assertTrue(
            lines.any { it == "- Storage Wars by A&E (${pluto.url}) | would match \"Pluto TV Storage Wars\"" }
        )
        // Report only: the affixed row is still unmatched, because nothing strips a
        // guide-side suffix.
        assertNull(report.rows.single { it.displayName == "Pluto TV Storage Wars" }.sourceUrl)
    }

    @Test
    fun `a suffix nothing would reach is still listed, with no row`() {
        val guide = GuideMatchSource(
            url = "https://guide.example/g.xml.gz",
            byId = emptyMap(),
            byName = mapOf(epgLookupKey("Some Show by A&E") to "only"),
            identityOf = { id -> GuideChannelIdentity(id, listOf("Some Show by A&E")) }
        )

        val report = attributeGuideMatches(
            channels = listOf(channel(id = "other", displayName = "Unrelated Channel")),
            sources = listOf(guide)
        )

        assertEquals(1, report.networkSuffixCandidates.size)
        assertNull("no row reaches it, and the section says so rather than omitting it", report.networkSuffixCandidates.single().playlistName)
        assertEquals(0, report.networkSuffixRescues)
        assertTrue(
            report.lines(Redaction::url).any { it == "- Some Show by A&E (${guide.url})" }
        )
    }

    @Test
    fun `a resolved guide channel is not a suffix candidate`() {
        // `BET Pluto TV` is a real guide name and matches exactly, so it must never
        // be reported as a by-suffix gap even though it loads into the same index.
        val guide = GuideMatchSource(
            url = "https://guide.example/g.xml.gz",
            byId = emptyMap(),
            byName = mapOf(
                epgLookupKey("BET Pluto TV") to "resolved",
                epgLookupKey("Storage Wars by A&E") to "suffix"
            ),
            identityOf = { id ->
                when (id) {
                    "resolved" -> GuideChannelIdentity("resolved", listOf("BET Pluto TV"))
                    else -> GuideChannelIdentity("suffix", listOf("Storage Wars by A&E"))
                }
            }
        )

        val report = attributeGuideMatches(
            channels = listOf(
                channel(id = "betpluto", displayName = "BET Pluto TV"),
                // One unmatched row, so the section is produced at all (it exists
                // to explain a blank row, not to inventory a healthy guide).
                channel(id = "other", displayName = "Unrelated Channel")
            ),
            sources = listOf(guide)
        )

        assertEquals(guide.url, report.rows.first().sourceUrl)
        assertEquals(
            "only the unresolved guide channel is counted",
            listOf("Storage Wars by A&E"),
            report.networkSuffixCandidates.map { it.guideName }
        )
    }

    @Test
    fun `a source that cannot describe its channels adds no suffix section`() {
        val guide = GuideMatchSource(
            url = "https://guide.example/g.xml.gz",
            byId = emptyMap(),
            byName = mapOf(epgLookupKey("Storage Wars by A&E") to "x")
        )

        val report = attributeGuideMatches(
            channels = listOf(channel(id = "c", displayName = "Nothing")),
            sources = listOf(guide)
        )

        assertTrue(report.networkSuffixCandidates.isEmpty())
        assertTrue(report.lines(Redaction::url).none { it.startsWith("guide-side") })
    }

    @Test
    fun `no lineup loaded is one line, never a silent omission`() {
        assertEquals(
            listOf("guide-match: no lineup loaded"),
            guideMatchSectionLines(report = null, redactSource = Redaction::url)
        )
    }

    private fun channel(
        id: String,
        displayName: String,
        tvgId: String? = null,
        group: String? = null,
        streamUrl: String = "http://stream.example/$id.ts"
    ) = IptvChannel(
        id = id,
        name = displayName,
        displayName = displayName,
        streamUrl = streamUrl,
        groupTitle = group,
        logoUrl = null,
        tvgId = tvgId,
        tvgName = null,
        tvgChno = null,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        providerChannelId = null
    )
}
