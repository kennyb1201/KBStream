package com.kennyb1201.kbstream.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The `trailer.death` line as the field report reads it.
 *
 * The 2026-10-30 report ("dies at ~20 s on both attempts, same spot") arrived
 * with two COPY DIAGNOSTICS pastes and ZERO trailer lines in either of them: the
 * phone's Detail hero never wrote one. Fixing the surface is only half of it -
 * the line also has to carry the facts that settle the next report on its own,
 * so it now names the position, the chunk range the read died in, the format
 * that died (muxed vs adaptive, with its `itag`) and which retry it was.
 *
 * The other half of this file is about NOT inventing facts: a caller that knows
 * none of the new ones (the full player's trailer session, a surface that
 * predates this) writes exactly the line it always wrote, and a chunk death is
 * only ever quoted for its own video.
 */
class TrailerDeathRecordTest {

    private companion object {
        const val VIDEO_ID = "aF9tV2xC1zk"
        const val OTHER_ID = "zQ8mK1pR3sd"
        const val CHUNK_OFFSET = 2_097_152L
        const val STREAM_BYTES = 7_331_840L
    }

    @Before
    fun clearTrace() {
        PoTokenTrace.clear()
    }

    @Test
    fun `a retry's death names the position, chunk, format and retry`() {
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "android_vr",
            kind = "adaptive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "pending",
            itag = "137",
            attempt = 1,
            change = "class:tv",
            chunkOffsetBytes = CHUNK_OFFSET,
            chunkTotalBytes = STREAM_BYTES
        )

        assertEquals(
            "one line has to answer all of it without a second capture",
            "trailer.death: video=$VIDEO_ID client=android_vr kind=adaptive itag=137 " +
                "at=20400/140849 chunk=2097152/7331840 retry=1/class:tv resume=pending",
            PoTokenTrace.lines().last()
        )
    }

    @Test
    fun `the first play says it is the first play`() {
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "tv",
            kind = "progressive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "pending",
            itag = "18",
            attempt = 0,
            change = "first"
        )

        assertEquals(
            "trailer.death: video=$VIDEO_ID client=tv kind=progressive itag=18 " +
                "at=20400/140849 retry=0/first resume=pending",
            PoTokenTrace.lines().last()
        )
    }

    @Test
    fun `an identical replay says so rather than naming a class`() {
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "unknown",
            kind = "adaptive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "pending",
            attempt = 1,
            change = "same"
        )

        assertEquals(
            "trailer.death: video=$VIDEO_ID client=unknown kind=adaptive " +
                "at=20400/140849 retry=1/same resume=pending",
            PoTokenTrace.lines().last()
        )
    }

    @Test
    fun `a surface that knows none of the new facts writes the line it always wrote`() {
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = null,
            kind = null,
            positionMs = 0L,
            durationMs = -1L,
            resume = "cancelled"
        )

        assertEquals(
            "trailer.death: video=$VIDEO_ID client=unknown kind=unknown " +
                "at=0/? resume=cancelled",
            PoTokenTrace.lines().last()
        )
    }

    @Test
    fun `a spent ladder has a value of its own, and no replacement is invented`() {
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "android",
            kind = "adaptive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "none: retries spent",
            attempt = 2,
            change = "same"
        )

        assertEquals(
            "trailer.death: video=$VIDEO_ID client=android kind=adaptive " +
                "at=20400/140849 retry=2/same resume=none: retries spent",
            PoTokenTrace.lines().last()
        )
    }

    // ── the token state of the source that died ────────────────────────────

    /**
     * The 2026-11-05 capture this exists for: an `android_vr` progressive mount
     * died at 15.2 s and a replacement `client android` played. The resolve line
     * is the latest resolve BY DESIGN, so it showed the replacement's
     * `playerToken=false pot=true` - and the dead mount's state, the one fact
     * that tells the pot wall from the player-token wall, was gone.
     */
    @Test
    fun `a replacement's mount cannot overwrite the dead mount's tokens`() {
        PoTokenTrace.recordMount(VIDEO_ID, "android_vr", playerToken = false, pot = true)
        PoTokenTrace.recordResolve(
            VIDEO_ID,
            playerToken = false,
            pot = true,
            client = "android_vr",
            kind = "progressive"
        )
        PoTokenTrace.recordMount(VIDEO_ID, "android", playerToken = true, pot = true)
        PoTokenTrace.recordResolve(
            VIDEO_ID,
            playerToken = true,
            pot = true,
            client = "android",
            kind = "adaptive"
        )

        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "android_vr",
            kind = "progressive",
            positionMs = 15_240L,
            durationMs = 138_458L,
            resume = "client android ok"
        )

        assertEquals(
            "the death line is the per-attempt record: the DEAD mount's pair, " +
                "even though the resolve line has moved on",
            "trailer.death: video=$VIDEO_ID client=android_vr kind=progressive " +
                "playerToken=false pot=true at=15240/138458 resume=client android ok",
            PoTokenTrace.lines().last()
        )
        assertEquals(
            "and the resolve line stays the latest resolve - the disagreement is the point",
            "pot.resolve: video=$VIDEO_ID playerToken=true pot=true client=android kind=adaptive",
            PoTokenTrace.lines().single { it.startsWith("pot.resolve:") }
        )
    }

    @Test
    fun `a fallback death says it was the fallback, not a tokenless mount`() {
        PoTokenTrace.recordMount(VIDEO_ID, client = null, playerToken = false, pot = false)

        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = null,
            kind = null,
            positionMs = 4_100L,
            durationMs = 138_458L,
            resume = "none: no other client"
        )

        assertEquals(
            "the fallback names no client and carries no InnerTube tokens; without " +
                "the marker this reads exactly like a gate regression",
            "trailer.death: video=$VIDEO_ID client=unknown kind=unknown " +
                "playerToken=false pot=false fallback=true at=4100/138458 " +
                "resume=none: no other client",
            PoTokenTrace.lines().last()
        )
    }

    @Test
    fun `a death with no recorded mount invents no tokens`() {
        PoTokenTrace.recordResolve(VIDEO_ID, playerToken = true, pot = true, client = "android")

        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "tv",
            kind = "adaptive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "pending"
        )

        assertEquals(
            "a client that never mounted this video has no pair to quote, and the " +
                "resolve line's is another source's - an absence, never a guess",
            "trailer.death: video=$VIDEO_ID client=tv kind=adaptive " +
                "at=20400/140849 resume=pending",
            PoTokenTrace.lines().last()
        )
    }

    @Test
    fun `another video's mount is never quoted`() {
        PoTokenTrace.recordMount(OTHER_ID, "android", playerToken = true, pot = true)

        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "android",
            kind = "adaptive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "pending"
        )

        assertEquals(
            "trailer.death: video=$VIDEO_ID client=android kind=adaptive " +
                "at=20400/140849 resume=pending",
            PoTokenTrace.lines().last()
        )
    }

    @Test
    fun `a clear drops the mount snapshots with everything else`() {
        PoTokenTrace.recordMount(VIDEO_ID, "android", playerToken = true, pot = true)
        PoTokenTrace.clear()

        assertNull(
            "a cleared trace must not hand the next report a stale pair",
            PoTokenTrace.mountTokensFor(VIDEO_ID, "android")
        )
    }

    @Test
    fun `a chunk death is quoted only for its own video`() {
        PoTokenTrace.recordChunkDeath(
            videoId = OTHER_ID,
            offsetBytes = CHUNK_OFFSET,
            totalBytes = STREAM_BYTES,
            reason = "InvalidResponseCodeException:403"
        )

        assertNull(
            "another clip's chunk must never be quoted as this trailer's",
            PoTokenTrace.chunkDeathFor(VIDEO_ID)
        )
        val own = PoTokenTrace.chunkDeathFor(OTHER_ID)
        assertEquals(CHUNK_OFFSET, own?.offsetBytes)
        assertEquals(STREAM_BYTES, own?.totalBytes)
    }

    @Test
    fun `a chunk death with no id is never attributed to a video`() {
        PoTokenTrace.recordChunkDeath(
            videoId = null,
            offsetBytes = CHUNK_OFFSET,
            totalBytes = null,
            reason = "SocketTimeoutException"
        )

        assertNull(PoTokenTrace.chunkDeathFor(VIDEO_ID))
        assertNull("and nothing claims it a second time", PoTokenTrace.chunkDeathFor(null))
    }

    // ── the line the retry REWRITES ────────────────────────────────────────

    /**
     * The 2026-11-06 deep dive (§1.3): the first write carries the dying
     * mount's facts and the overwrite that follows a resolved retry replaced
     * them with the REPLACEMENT's - so a paste after a trailer death could no
     * longer say which mount died, what format it was, or whether its chunk read
     * was what failed. The four facts now ride on the pending record and are
     * passed back here, so the second write closes the same line with them.
     */
    @Test
    fun `an answer to the retry keeps the dying mount's kind, itag and chunk`() {
        PoTokenTrace.recordChunkDeath(
            videoId = VIDEO_ID,
            offsetBytes = CHUNK_OFFSET,
            totalBytes = STREAM_BYTES,
            reason = "InvalidResponseCodeException:403"
        )
        // The first write: the dying mount's own shape, format and range.
        val chunk = PoTokenTrace.chunkDeathFor(VIDEO_ID)
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "android_vr",
            kind = "progressive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "pending",
            itag = "18",
            attempt = 0,
            change = "first",
            chunkOffsetBytes = chunk?.offsetBytes,
            chunkTotalBytes = chunk?.totalBytes
        )
        // The overwrite, as the resolve makes it: the replacement is `android`
        // and its shape is `adaptive`, and NONE of that is passed - the record's
        // own dying facts are, plus the outcome.
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "android_vr",
            kind = "progressive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "client android ok",
            itag = "18",
            attempt = 1,
            change = "class:android_vr",
            chunkOffsetBytes = chunk?.offsetBytes,
            chunkTotalBytes = chunk?.totalBytes
        )

        val deaths = PoTokenTrace.lines().filter { it.startsWith("trailer.death:") }
        assertEquals(
            "one line, because a death and its answer are one event",
            1,
            deaths.size
        )
        assertEquals(
            "the dying mount's kind/itag/chunk survive the overwrite, and only " +
                "the outcome is the replacement's",
            "trailer.death: video=$VIDEO_ID client=android_vr kind=progressive " +
                "itag=18 at=20400/140849 chunk=2097152/7331840 " +
                "retry=1/class:android_vr resume=client android ok",
            deaths.single()
        )
    }

    @Test
    fun `a death with no chunk record writes no chunk and no itag on either write`() {
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "tv",
            kind = "adaptive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "pending",
            attempt = 0,
            change = "first"
        )
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "tv",
            kind = "adaptive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "client android ok",
            attempt = 1,
            change = "class:tv"
        )

        assertEquals(
            "a carried null stays null: absence keeps meaning 'no chunk record', " +
                "never an invented range",
            "trailer.death: video=$VIDEO_ID client=tv kind=adaptive at=20400/140849 " +
                "retry=1/class:tv resume=client android ok",
            PoTokenTrace.lines().single { it.startsWith("trailer.death:") }
        )
    }

    @Test
    fun `a recorded chunk death gets a standalone line that outlives the overwrite`() {
        PoTokenTrace.recordChunkDeath(
            videoId = VIDEO_ID,
            offsetBytes = CHUNK_OFFSET,
            totalBytes = STREAM_BYTES,
            reason = "InvalidResponseCodeException:403"
        )
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "tv",
            kind = "adaptive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "pending"
        )
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "tv",
            kind = "adaptive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "client android ok"
        )

        assertTrue(
            "the range is its own evidence, printed after the death line and " +
                "independent of which write is standing",
            PoTokenTrace.lines().contains(
                "trailer.chunk: video=$VIDEO_ID offset=2097152/7331840 " +
                    "reason=InvalidResponseCodeException:403"
            )
        )

        PoTokenTrace.clear()
        PoTokenTrace.recordTrailerDeath(
            videoId = VIDEO_ID,
            client = "tv",
            kind = "adaptive",
            positionMs = 20_400L,
            durationMs = 140_849L,
            resume = "pending"
        )
        assertFalse(
            "and with no chunk record the line is absent rather than empty",
            PoTokenTrace.lines().any { it.startsWith("trailer.chunk:") }
        )
    }

    @Test
    fun `a chunk death with no id or no length is printed as unknown, not guessed`() {
        PoTokenTrace.recordChunkDeath(
            videoId = null,
            offsetBytes = CHUNK_OFFSET,
            totalBytes = null,
            reason = "SocketTimeoutException"
        )

        assertTrue(
            "the capture gets the range it has and '?' for what it does not",
            PoTokenTrace.lines().contains(
                "trailer.chunk: video=? offset=2097152/? reason=SocketTimeoutException"
            )
        )
    }

    @Test
    fun `a clear drops the chunk death with everything else`() {
        PoTokenTrace.recordChunkDeath(
            videoId = VIDEO_ID,
            offsetBytes = CHUNK_OFFSET,
            totalBytes = STREAM_BYTES,
            reason = "InvalidResponseCodeException:403"
        )
        PoTokenTrace.clear()

        assertNull(
            "a cleared trace must not hand the next report a stale range",
            PoTokenTrace.chunkDeathFor(VIDEO_ID)
        )
    }
}
