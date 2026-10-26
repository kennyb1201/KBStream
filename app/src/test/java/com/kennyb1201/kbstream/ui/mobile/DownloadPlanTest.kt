package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.download.DownloadGates
import com.kennyb1201.kbstream.data.download.DownloadRecord
import com.kennyb1201.kbstream.data.download.DownloadState
import com.kennyb1201.kbstream.data.download.WAITING_FOR_WIFI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's download state machine, as the streams long-press menu draws it.
 *
 * Pinned as behaviour rather than as source text because it is a pure function:
 * the menu is the only way to start a download now (the Detail button and the
 * inline row chip are gone), so "which row is offered in which state" is the
 * whole affordance, and a wrong press here means offering to play bytes that do
 * not exist or to cancel a transfer that already finished.
 */
class DownloadPlanTest {

    private fun record(
        state: DownloadState = DownloadState.QUEUED,
        percent: Float? = null,
        waitingForNetwork: Boolean = false
    ) = DownloadRecord(
        id = "kbdl_test",
        key = "profile|tt1|s1e2|row",
        mediaKey = "tt1",
        season = 1,
        episode = 2,
        title = "Show",
        qualityLabel = "1080p",
        sourceUrl = "https://host/file.mkv",
        state = state,
        percent = percent,
        waitingForNetwork = waitingForNetwork
    )

    private fun plan(
        record: DownloadRecord?,
        idleLabel: String = DownloadLabel,
        disabledReason: String? = null,
        inert: Boolean = false
    ) = downloadPlan(record, idleLabel, disabledReason, inert)

    @Test
    fun `no record offers one Download row`() {
        val rows = plan(record = null)
        assertEquals(1, rows.size)
        assertEquals(DownloadActionKind.DOWNLOAD, rows[0].kind)
        assertEquals("Download", rows[0].label)
        assertTrue(rows[0].enabled)
    }

    @Test
    fun `the DRM gate is shown, disabled, with its reason`() {
        // The one gate that must stay learnable rather than hidden (spec §5):
        // the row is there, it just cannot be pressed, and it says why.
        val rows = plan(record = null, disabledReason = DownloadGates.DRM_REASON)
        assertEquals(1, rows.size)
        assertEquals(DownloadActionKind.DOWNLOAD, rows[0].kind)
        assertFalse("a protected title must not offer a press", rows[0].enabled)
        assertEquals(DownloadGates.DRM_REASON, rows[0].reason)
    }

    @Test
    fun `queued and downloading offer the state word and Cancel download`() {
        val queued = plan(record = record(state = DownloadState.QUEUED))
        assertEquals(DownloadActionKind.STATE, queued[0].kind)
        assertEquals("Queued", queued[0].label)
        assertFalse("the state word is not a press", queued[0].enabled)
        assertEquals(DownloadActionKind.DELETE, queued[1].kind)
        assertEquals("Cancel download", queued[1].label)

        val downloading = plan(record = record(state = DownloadState.DOWNLOADING, percent = 42f))
        assertEquals("Downloading (42%)", downloading[0].label)
        assertEquals(DownloadActionKind.DELETE, downloading[1].kind)

        val unknown = plan(record = record(state = DownloadState.DOWNLOADING))
        assertEquals("Downloading", unknown[0].label)
    }

    @Test
    fun `waiting for Wi-Fi is carried on the queued word`() {
        val rows = plan(
            record = record(state = DownloadState.QUEUED, waitingForNetwork = true)
        )
        assertEquals("Queued · $WAITING_FOR_WIFI", rows[0].label)
    }

    @Test
    fun `offline shows the queued word without offering a cancel`() {
        // Inert: the transfer is waiting on a network the viewer does not have,
        // so the row says so and offers nothing.
        val rows = plan(record = record(state = DownloadState.QUEUED), inert = true)
        assertEquals(1, rows.size)
        assertEquals(DownloadActionKind.STATE, rows[0].kind)
    }

    @Test
    fun `paused offers Resume and Cancel download`() {
        val rows = plan(record = record(state = DownloadState.PAUSED))
        assertEquals(DownloadActionKind.RESUME, rows[0].kind)
        assertEquals("Resume", rows[0].label)
        assertEquals(DownloadActionKind.DELETE, rows[1].kind)
        assertEquals("Cancel download", rows[1].label)
    }

    @Test
    fun `failed offers Retry, never a Play`() {
        val rows = plan(record = record(state = DownloadState.FAILED))
        assertEquals(DownloadActionKind.DOWNLOAD, rows[0].kind)
        assertEquals("Retry", rows[0].label)
        assertEquals(DownloadActionKind.DELETE, rows[1].kind)
        assertEquals("Delete", rows[1].label)
        assertFalse(
            "a failed download has no bytes to play",
            rows.any { it.kind == DownloadActionKind.PLAY }
        )
    }

    @Test
    fun `downloaded offers Play downloaded and Delete`() {
        val rows = plan(record = record(state = DownloadState.DOWNLOADED))
        assertEquals(DownloadActionKind.PLAY, rows[0].kind)
        assertEquals("Play downloaded", rows[0].label)
        assertEquals(DownloadActionKind.DELETE, rows[1].kind)
        assertEquals("Delete", rows[1].label)
    }

    @Test
    fun `the offline idle label is the row's own words`() {
        val rows = plan(
            record = null,
            idleLabel = DownloadGates.CONNECT_TO_DOWNLOAD,
            inert = true
        )
        assertEquals(DownloadGates.CONNECT_TO_DOWNLOAD, rows[0].label)
    }

    @Test
    fun `a deleted record is back to Download`() {
        val rows = plan(record = record(state = DownloadState.DELETED))
        assertEquals(DownloadActionKind.DOWNLOAD, rows[0].kind)
        assertEquals("Download", rows[0].label)
        assertTrue(rows[0].enabled)
    }
}
