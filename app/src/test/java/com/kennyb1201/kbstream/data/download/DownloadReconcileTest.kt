package com.kennyb1201.kbstream.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7's state rules, which are the ones a device pass cannot reproduce on demand:
 * killing the app mid-download, filling the storage mid-transfer, and a record
 * whose bytes were removed behind the app's back.
 *
 * The rule the whole list exists for is the first one: a partial file must never
 * read as Downloaded. Everything else here is the honest answer to what DID
 * happen, in the words the viewer will read.
 */
class DownloadReconcileTest {

    private val now = 1_000L

    private fun record(
        state: DownloadState = DownloadState.QUEUED,
        totalBytes: Long = DownloadSpace.UNKNOWN_LENGTH,
        bytesDownloaded: Long = 0L
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
        totalBytes = totalBytes,
        bytesDownloaded = bytesDownloaded,
        addedAt = 1L,
        updatedAt = 1L
    )

    private fun merged(
        stored: DownloadRecord,
        live: LiveDownload?,
        indexReady: Boolean = true,
        lowOnSpace: Boolean = false
    ) = reconcile(stored, live, indexReady, lowOnSpace, now)

    @Test
    fun `a downloading transfer keeps its percentage and its size`() {
        val out = merged(
            record(state = DownloadState.DOWNLOADING),
            LiveDownload(
                state = LiveDownloadState.DOWNLOADING,
                percent = 42.5f,
                bytesDownloaded = 425L,
                totalBytes = 1_000L
            )
        )
        assertEquals(DownloadState.DOWNLOADING, out?.state)
        assertEquals(42.5f, out?.percent)
        assertEquals(1_000L, out?.totalBytes)
        assertEquals(425L, out?.bytesDownloaded)
    }

    @Test
    fun `a partial file never reads as downloaded`() {
        // A stored record that was somehow marked complete, with the downloader
        // still transferring it, is the exact state §7 forbids showing: the
        // downloader wins.
        val out = merged(
            record(state = DownloadState.DOWNLOADED, totalBytes = 1_000L),
            LiveDownload(state = LiveDownloadState.DOWNLOADING, percent = 10f)
        )
        assertEquals(DownloadState.DOWNLOADING, out?.state)
        assertFalse(out!!.state.isComplete)
    }

    @Test
    fun `a completed transfer is whole, with its size and no reason`() {
        val out = merged(
            record(state = DownloadState.DOWNLOADING, totalBytes = 1_000L),
            LiveDownload(
                state = LiveDownloadState.COMPLETED,
                percent = 100f,
                bytesDownloaded = 1_000L,
                totalBytes = 1_000L
            )
        )
        assertEquals(DownloadState.DOWNLOADED, out?.state)
        assertNull(out?.reason)
        assertTrue(out!!.state.isComplete)
        assertEquals(1_000L, out.bytesDownloaded)
        assertEquals(100f, out.percent)
    }

    @Test
    fun `a queued download waiting on its network requirement says so and stays queued`() {
        val out = merged(
            record(state = DownloadState.QUEUED),
            LiveDownload(
                state = LiveDownloadState.QUEUED,
                waitingForNetwork = true
            )
        )
        assertEquals(DownloadState.QUEUED, out?.state)
        assertTrue(out!!.waitingForNetwork)
        assertEquals("Waiting for Wi-Fi", WAITING_FOR_WIFI)
    }

    @Test
    fun `a stopped transfer is paused and stays resumable`() {
        val out = merged(
            record(state = DownloadState.DOWNLOADING, totalBytes = 1_000L),
            LiveDownload(state = LiveDownloadState.STOPPED, bytesDownloaded = 300L)
        )
        assertEquals(DownloadState.PAUSED, out?.state)
        assertTrue(out!!.state.isPartial)
    }

    @Test
    fun `a failure with no room left is called out of space`() {
        val out = merged(
            record(state = DownloadState.DOWNLOADING),
            LiveDownload(state = LiveDownloadState.FAILED),
            lowOnSpace = true
        )
        assertEquals(DownloadState.FAILED, out?.state)
        assertEquals(DownloadSpace.OUT_OF_SPACE_REASON, out?.reason)
    }

    @Test
    fun `a failure with room left is reported without inventing a cause`() {
        val out = merged(
            record(state = DownloadState.DOWNLOADING),
            LiveDownload(state = LiveDownloadState.FAILED),
            lowOnSpace = false
        )
        assertEquals(DownloadState.FAILED, out?.state)
        assertEquals(DownloadFailureReasons.FAILED, out?.reason)
    }

    @Test
    fun `a completed download the downloader has forgotten is failed, not downloaded`() {
        // Storage cleared behind the app, or a partial delete. The row must not
        // offer Play, and its words are the same ones the player says when it
        // cannot find the bytes (spec §6).
        val out = merged(
            record(state = DownloadState.DOWNLOADED, totalBytes = 1_000L),
            live = null
        )
        assertEquals(DownloadState.FAILED, out?.state)
        assertEquals(DownloadFailureReasons.MISSING, out?.reason)
    }

    @Test
    fun `a partial the downloader has forgotten is failed as interrupted`() {
        val out = merged(record(state = DownloadState.DOWNLOADING), live = null)
        assertEquals(DownloadState.FAILED, out?.state)
        assertEquals(DownloadFailureReasons.INTERRUPTED, out?.reason)
    }

    @Test
    fun `an index that could not be read leaves the stored record alone`() {
        // The index read failing is not the downloader forgetting this download;
        // treating it as such would fail every row on a device that hiccuped.
        val stored = record(state = DownloadState.DOWNLOADED, totalBytes = 1_000L)
        assertEquals(stored, merged(stored, live = null, indexReady = false))
    }

    @Test
    fun `a download the downloader has removed is dropped entirely`() {
        assertNull(merged(record(state = DownloadState.DELETED), LiveDownload(LiveDownloadState.REMOVED)))
    }

    @Test
    fun `only a completed download the downloader still holds may be played`() {
        val stored = record(state = DownloadState.DOWNLOADED, totalBytes = 1_000L)
        assertTrue(
            isPlayable(stored, LiveDownload(state = LiveDownloadState.COMPLETED))
        )
        assertFalse(isPlayable(stored, LiveDownload(state = LiveDownloadState.DOWNLOADING)))
        assertFalse(isPlayable(stored, live = null))
        assertFalse(
            isPlayable(
                record(state = DownloadState.PAUSED),
                LiveDownload(state = LiveDownloadState.COMPLETED)
            )
        )
    }

    @Test
    fun `the state labels are the spec's own words`() {
        // Queued, Downloading (%), Paused, Downloaded, Failed (with reason),
        // Deleted - the closed list every surface draws from.
        assertEquals("Queued", DownloadState.QUEUED.label())
        assertEquals("Downloading (7%)", DownloadState.DOWNLOADING.label(7.4f))
        assertEquals("Downloading", DownloadState.DOWNLOADING.label(null))
        assertEquals("Paused", DownloadState.PAUSED.label())
        assertEquals("Downloaded", DownloadState.DOWNLOADED.label())
        assertEquals("Deleted", DownloadState.DELETED.label())
        val failed = record(state = DownloadState.FAILED).copy(reason = "Out of space")
        assertEquals("Failed (Out of space)", failed.stateLabel())
        assertEquals("Failed", record(state = DownloadState.FAILED).stateLabel())
    }

    @Test
    fun `a size reads in the units the system's own storage screen uses`() {
        assertEquals("999 B", formatDownloadBytes(999L))
        assertEquals("1.4 GB", formatDownloadBytes(1_400_000_000L))
        assertEquals("700 MB", formatDownloadBytes(700_000_000L))
        assertEquals("2 TB", formatDownloadBytes(2_000_000_000_000L))
    }
}
