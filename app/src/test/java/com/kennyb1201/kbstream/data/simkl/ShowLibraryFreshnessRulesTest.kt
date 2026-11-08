package com.kennyb1201.kbstream.data.simkl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the cached Simkl show library may decide that a show is caught up.
 *
 * The reported miss: MobLand's last aired episode was finished (watched 17 of
 * 18 aired), the cached all-shows blob still said 17, and the caught-up leg
 * filtered the show out of Upcoming entirely - no log line, no card, nothing on
 * a relaunch because the blob lives on disk for twelve hours. One episode of
 * staleness was the whole bug, so the rule that refuses a blob older than the
 * device's last watched write is the whole fix, and this is its arithmetic.
 *
 * The settle window is the second half: the fetch that follows a watched write
 * can land before Simkl's own read API has applied it, and that blob is NEWER
 * than the write while still carrying the old tally. It is not evidence yet.
 */
class ShowLibraryFreshnessRulesTest {

    private val writeAt = 1_000_000L

    @Test
    fun `a blob minted before the last watched write is not trusted`() {
        // The reported case, one episode of staleness: the blob is five minutes
        // older than the finish that made the show caught up.
        assertFalse(
            "a blob older than the last watched write still says the show is an " +
                "episode short, which is what filtered MobLand out",
            isShowLibraryBlobTrustworthy(
                blobUpdatedAt = writeAt - 5 * 60_000L,
                lastWatchedWriteAt = writeAt
            )
        )
        assertFalse(
            "and one millisecond older is just as stale",
            isShowLibraryBlobTrustworthy(
                blobUpdatedAt = writeAt - 1L,
                lastWatchedWriteAt = writeAt
            )
        )
    }

    @Test
    fun `a blob minted inside the settle window is not trusted either`() {
        // The write succeeded and dropped the cache; this blob is the fetch that
        // followed, and if Simkl had not applied the write yet it carries the
        // pre-write tally with a fresh timestamp. Refreshing again is the only
        // way to converge.
        assertFalse(
            "minted at the instant of the write",
            isShowLibraryBlobTrustworthy(
                blobUpdatedAt = writeAt,
                lastWatchedWriteAt = writeAt
            )
        )
        assertFalse(
            "minted half a window later",
            isShowLibraryBlobTrustworthy(
                blobUpdatedAt = writeAt + SHOW_LIBRARY_WRITE_SETTLE_MS / 2,
                lastWatchedWriteAt = writeAt
            )
        )
        assertTrue(
            "and trusted the moment the window has passed",
            isShowLibraryBlobTrustworthy(
                blobUpdatedAt = writeAt + SHOW_LIBRARY_WRITE_SETTLE_MS,
                lastWatchedWriteAt = writeAt
            )
        )
    }

    @Test
    fun `a blob minted well after the last write is served from cache`() {
        // T2: the cache still works. A Home load with no watched change behind
        // it must not refetch the library - that is what the 15 minute memory
        // TTL and the 12 hour disk TTL are for.
        assertTrue(
            "ten minutes after the write the blob has the write in it",
            isShowLibraryBlobTrustworthy(
                blobUpdatedAt = writeAt + 10 * 60_000L,
                lastWatchedWriteAt = writeAt
            )
        )
    }

    @Test
    fun `a profile with no watched write trusts whatever blob it has`() {
        // Cold start, or a profile that has never marked anything: there is no
        // write the blob could have missed, and the cache is the point.
        assertTrue(
            "no write, no staleness to be had",
            isShowLibraryBlobTrustworthy(
                blobUpdatedAt = writeAt,
                lastWatchedWriteAt = 0L
            )
        )
    }

    @Test
    fun `no blob at all is not trusted, and costs nothing`() {
        // "Untrusted" makes the caller pass forceRefresh = true, which is exactly
        // what a read with no blob does anyway (the TTL checks fall through to
        // the network). The flag only matters when there is something to skip.
        assertFalse(
            "nothing to serve means nothing to trust",
            isShowLibraryBlobTrustworthy(
                blobUpdatedAt = null,
                lastWatchedWriteAt = writeAt
            )
        )
        assertFalse(
            "and it is not trusted before any write either",
            isShowLibraryBlobTrustworthy(
                blobUpdatedAt = null,
                lastWatchedWriteAt = 0L
            )
        )
    }

    @Test
    fun `the window is the caller's to set`() {
        // The parameter exists so a caller (or a test) can pin the boundary
        // rather than reach into the constant.
        assertTrue(
            "no window: anything after the write is evidence",
            isShowLibraryBlobTrustworthy(
                blobUpdatedAt = writeAt + 1L,
                lastWatchedWriteAt = writeAt,
                settleMs = 0L
            )
        )
        assertFalse(
            "a long window refuses a blob minted a minute after the write",
            isShowLibraryBlobTrustworthy(
                blobUpdatedAt = writeAt + 60_000L,
                lastWatchedWriteAt = writeAt,
                settleMs = 5 * 60_000L
            )
        )
    }
}
