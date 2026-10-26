package com.kennyb1201.kbstream.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3's space rule: a download whose length is known is refused before it starts,
 * and one whose length is not is allowed to try.
 *
 * The alternative to this rule is the failure it exists to prevent - a phone
 * whose storage is gone and whose only explanation is a download that was happy
 * to take the last byte.
 */
class DownloadSpaceTest {

    private val margin = DownloadSpace.MARGIN_BYTES

    @Test
    fun `a file that fits with the margin to spare is allowed`() {
        val file = 2L * 1024L * 1024L * 1024L
        assertTrue(DownloadSpace.hasRoomFor(freeBytes = file + margin, lengthBytes = file))
    }

    @Test
    fun `a file that fits exactly, with no margin, is refused`() {
        // The margin is what keeps the phone running while the download writes:
        // the player's buffers, the history database and the image cache all
        // still need room, so "it would just fit" is not room enough.
        val file = 2L * 1024L * 1024L * 1024L
        assertFalse(DownloadSpace.hasRoomFor(freeBytes = file, lengthBytes = file))
        assertFalse(
            DownloadSpace.hasRoomFor(freeBytes = file + margin - 1, lengthBytes = file)
        )
    }

    @Test
    fun `an unknown length is allowed to start`() {
        // The spec's own fallback: enqueue, and surface the downloader's
        // out-of-space failure in the same plain words.
        assertTrue(DownloadSpace.hasRoomFor(freeBytes = 0L, lengthBytes = null))
        assertTrue(
            DownloadSpace.hasRoomFor(freeBytes = 0L, lengthBytes = DownloadSpace.UNKNOWN_LENGTH)
        )
    }

    @Test
    fun `a nonsense length does not become a refusal or an overflow`() {
        assertTrue(DownloadSpace.hasRoomFor(freeBytes = 0L, lengthBytes = 0L))
        assertTrue(DownloadSpace.hasRoomFor(freeBytes = 0L, lengthBytes = -5L))
        // A bogus Content-Length must not wrap below the margin and read as
        // "room enough" on a full device.
        assertFalse(
            DownloadSpace.hasRoomFor(
                freeBytes = Long.MAX_VALUE,
                lengthBytes = Long.MAX_VALUE - 1L
            )
        )
    }

    @Test
    fun `the two failure words are the same sentence everywhere`() {
        // The enqueue refusal and the downloader's own mid-transfer failure print
        // one string, so a viewer who sees one in each place reads one fact.
        assertTrue(DownloadSpace.NOT_ENOUGH_SPACE.contains("space"))
        assertTrue(DownloadSpace.outOfSpaceReason().contains("space"))
        assertFalse(DownloadSpace.NOT_ENOUGH_SPACE.isBlank())
    }
}
