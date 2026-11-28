package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a stopped import may be continued, and exactly which rows a continued
 * parse must not insert again.
 *
 * Both halves are the resume's safety, and neither is arithmetic a Robolectric
 * import would make obvious: a checkpoint accepted for the wrong feed stages
 * rows from a document that no longer lines up, and a skip that is off by one
 * leaves the guide one row short of what the swap re-keys. Pure, so both are
 * pinned on the JVM (the importer's own end-to-end resume is in
 * [GuideImportRecoveryTest]).
 */
class GuideImportCheckpointTest {

    private val feed = GuideImportCheckpoint(
        createdAtMs = 1_000_000L,
        windowStartMs = 900_000L,
        windowEndMs = 2_000_000L,
        stagedChannels = 500,
        stagedPrograms = 12_000,
        bytesRead = 48L * 1024L * 1024L,
        etag = "\"feed-1\"",
        lastModified = "Wed, 21 Oct 2026 07:28:00 GMT",
        matchKeysFingerprint = "abc123"
    )

    // ── when it may be used ────────────────────────────────────────────────

    @Test
    fun `a checkpoint from the same feed, filter and attempt is resumable`() {
        assertSame(
            feed,
            resumeCheckpoint(
                checkpoint = feed,
                nowMs = 1_000_000L + 4L * 60_000L,
                etag = feed.etag,
                lastModified = feed.lastModified,
                matchKeysFingerprint = feed.matchKeysFingerprint
            )
        )
    }

    @Test
    fun `nothing staged is nothing to resume`() {
        assertNull(
            resumeCheckpoint(
                checkpoint = null,
                nowMs = 2_000_000L,
                etag = null,
                lastModified = null,
                matchKeysFingerprint = "abc123"
            )
        )
        assertNull(
            "an empty staging table would make the skip a no-op, but the sweep it " +
                "skips still has to happen",
            resumeCheckpoint(
                checkpoint = feed.copy(stagedChannels = 0, stagedPrograms = 0),
                nowMs = 1_000_000L,
                etag = feed.etag,
                lastModified = feed.lastModified,
                matchKeysFingerprint = feed.matchKeysFingerprint
            )
        )
    }

    @Test
    fun `a regenerated feed is not resumed`() {
        // The provider keeps the URL and changes the file: row N of the new
        // document is not row N of the staged one.
        assertNull(
            resumeCheckpoint(
                checkpoint = feed,
                nowMs = 1_100_000L,
                etag = "\"feed-2\"",
                lastModified = feed.lastModified,
                matchKeysFingerprint = feed.matchKeysFingerprint
            )
        )
        assertNull(
            resumeCheckpoint(
                checkpoint = feed,
                nowMs = 1_100_000L,
                etag = feed.etag,
                lastModified = "Thu, 22 Oct 2026 07:28:00 GMT",
                matchKeysFingerprint = feed.matchKeysFingerprint
            )
        )
        // Validators that appear where the staged attempt had none are a
        // different answer from the server too, and the safe read is "changed".
        assertNull(
            resumeCheckpoint(
                checkpoint = feed.copy(etag = null, lastModified = null),
                nowMs = 1_100_000L,
                etag = "\"feed-1\"",
                lastModified = null,
                matchKeysFingerprint = feed.matchKeysFingerprint
            )
        )
    }

    @Test
    fun `a playlist edit is not resumed`() {
        assertNull(
            "the match keys decide which programmes are kept, so a playlist edit " +
                "moves what row N is",
            resumeCheckpoint(
                checkpoint = feed,
                nowMs = 1_100_000L,
                etag = feed.etag,
                lastModified = feed.lastModified,
                matchKeysFingerprint = "def456"
            )
        )
    }

    @Test
    fun `an attempt older than the cadence starts over`() {
        val maxAge = GUIDE_IMPORT_RESUME_MAX_AGE_MS
        assertEquals(
            "the last moment it may still be used",
            feed,
            resumeCheckpoint(
                checkpoint = feed,
                nowMs = feed.createdAtMs + maxAge - 1,
                etag = feed.etag,
                lastModified = feed.lastModified,
                matchKeysFingerprint = feed.matchKeysFingerprint
            )
        )
        assertNull(
            resumeCheckpoint(
                checkpoint = feed,
                nowMs = feed.createdAtMs + maxAge,
                etag = feed.etag,
                lastModified = feed.lastModified,
                matchKeysFingerprint = feed.matchKeysFingerprint
            )
        )
        // A clock that moved backwards is not a young checkpoint either.
        assertNull(
            resumeCheckpoint(
                checkpoint = feed,
                nowMs = feed.createdAtMs - 1,
                etag = feed.etag,
                lastModified = feed.lastModified,
                matchKeysFingerprint = feed.matchKeysFingerprint
            )
        )
    }

    // ── which rows the continued parse inserts ─────────────────────────────

    @Test
    fun `a fresh import skips nothing`() {
        val rows = GuideImportResumeRows(null)
        assertFalse(rows.resumed)
        repeat(3) {
            assertFalse(rows.channelAlreadyStaged())
            assertFalse(rows.programAlreadyStaged())
        }
    }

    @Test
    fun `a resumed parse drops exactly the rows the staging table holds`() {
        val rows = GuideImportResumeRows(feed.copy(stagedChannels = 2, stagedPrograms = 3))
        assertTrue(rows.resumed)

        // The document's first two channels are already staged; the third is not.
        assertTrue(rows.channelAlreadyStaged())
        assertTrue(rows.channelAlreadyStaged())
        assertFalse(rows.channelAlreadyStaged())
        assertFalse(rows.channelAlreadyStaged())

        // Programmes have their own count: the channel block does not move it.
        assertTrue(rows.programAlreadyStaged())
        assertTrue(rows.programAlreadyStaged())
        assertTrue(rows.programAlreadyStaged())
        assertFalse(rows.programAlreadyStaged())
    }

    @Test
    fun `one kind of row staged does not skip the other`() {
        val channelsOnly = GuideImportResumeRows(
            feed.copy(stagedChannels = 4, stagedPrograms = 0)
        )
        repeat(5) { assertTrue(channelsOnly.programAlreadyStaged().not()) }
        repeat(4) { assertTrue(channelsOnly.channelAlreadyStaged()) }
        assertFalse(channelsOnly.channelAlreadyStaged())
    }

    // ── the durable record ─────────────────────────────────────────────────

    @Test
    fun `a checkpoint survives the round trip, nulls included`() {
        assertEquals(
            feed,
            GuideImportCheckpointCodec.decode(
                GuideImportCheckpointCodec.encode(feed)
            )
        )
        val noValidators = feed.copy(etag = null, lastModified = null)
        assertEquals(
            noValidators,
            GuideImportCheckpointCodec.decode(
                GuideImportCheckpointCodec.encode(noValidators)
            )
        )
    }

    @Test
    fun `a record this build cannot read is not resumed`() {
        assertNull(GuideImportCheckpointCodec.decode(""))
        assertNull(GuideImportCheckpointCodec.decode("1\u001f2\u001f3"))
        assertNull(GuideImportCheckpointCodec.decode("x\u001f2\u001f3\u001f4\u001f5\u001f6\u001f\u001f\u001f7"))
    }

    @Test
    fun `the match-key fingerprint is order-free and changes with the keys`() {
        val a = GuideImportCheckpointCodec.fingerprint(setOf("bbc1", "itv1", "ch4"))
        val b = GuideImportCheckpointCodec.fingerprint(linkedSetOf("ch4", "bbc1", "itv1"))
        assertEquals("a set has no order, so neither may its fingerprint", a, b)
        assertFalse(
            "one edited key must invalidate the checkpoint",
            a == GuideImportCheckpointCodec.fingerprint(setOf("bbc1", "itv1"))
        )
        assertEquals(
            "no playlist known is still a stable fingerprint",
            GuideImportCheckpointCodec.fingerprint(emptySet()),
            GuideImportCheckpointCodec.fingerprint(emptySet())
        )
    }
}
