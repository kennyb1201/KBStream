package com.kennyb1201.kbstream.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
