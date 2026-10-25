package com.kennyb1201.kbstream.data.iptv

import com.kennyb1201.kbstream.data.format.DateFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Playlist Setup says about guide freshness.
 *
 * §3.6's requirement is that a guide going stale is visible where the viewer
 * goes to press IMPORT GUIDE - not in logcat. That makes two strings
 * load-bearing: which source last landed (and when), and whether the background
 * refresh has given up. Both are formatted here so the TV form and the phone
 * form cannot word them differently.
 */
class GuideRefreshStatusTest {

    private val importedAt = 1_700_000_000_000L

    @Test
    fun `a profile with no guide sources says nothing`() {
        assertEquals("", GuideRefreshStatus().lastGoodLabel())
        assertEquals(
            "even when a stale stamp exists from a source that was removed",
            "",
            GuideRefreshStatus(lastGoodSource = "https://host/gone.xml", lastGoodAtMs = importedAt)
                .lastGoodLabel()
        )
    }

    @Test
    fun `a device that never imported says so`() {
        assertEquals(
            "Guide not imported on this device yet",
            GuideRefreshStatus(sourceCount = 2).lastGoodLabel()
        )
        assertEquals(
            "a zero stamp is 'never', not the epoch",
            "Guide not imported on this device yet",
            GuideRefreshStatus(sourceCount = 1, lastGoodSource = "https://host/g.xml", lastGoodAtMs = 0L)
                .lastGoodLabel()
        )
    }

    @Test
    fun `the last good line names the source and the time`() {
        val label = GuideRefreshStatus(
            sourceCount = 2,
            lastGoodSource = "https://epg.kanya.io/us/guide.xml.gz",
            lastGoodAtMs = importedAt
        ).lastGoodLabel()

        assertTrue(
            "the source it came from, without the scheme: $label",
            label.contains("epg.kanya.io/us/guide.xml.gz")
        )
        assertTrue(
            "and when: $label",
            label.contains(
                DateFormats.time(importedAt, DateFormats.MONTH_DAY_CLOCK_12H)
            )
        )
        assertTrue("and what the line is about: $label", label.startsWith("Guide last imported from"))
    }

    @Test
    fun `a source label drops the scheme and the query`() {
        // Xtream-style providers put the account's credentials in the query -
        // a status line must not put them on screen.
        assertEquals(
            "host/guide.xml.gz",
            GuideRefreshStatus.sourceLabel("https://host/guide.xml.gz?username=a&password=b")
        )
        assertEquals(
            "host/g.xml",
            GuideRefreshStatus.sourceLabel("http://user:pass@host/g.xml")
        )
        assertEquals(
            "a label is capped so the date cannot be pushed off the row",
            GuideRefreshStatus.SOURCE_LABEL_MAX_CHARS,
            GuideRefreshStatus.sourceLabel("https://" + "a".repeat(200) + "/g.xml").length
        )
    }

    @Test
    fun `the failing line points at the button that fixes it`() {
        assertTrue(
            "the recovery is the button on this screen, so the line has to name it",
            GuideRefreshStatus.FAILING_LABEL.contains("IMPORT GUIDE")
        )
        assertTrue(
            "and it has to say what is wrong",
            GuideRefreshStatus.FAILING_LABEL.contains("failing")
        )
    }
}
