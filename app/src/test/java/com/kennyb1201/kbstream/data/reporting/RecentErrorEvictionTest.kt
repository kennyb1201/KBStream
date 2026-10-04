package com.kennyb1201.kbstream.data.reporting

import com.kennyb1201.kbstream.data.reporting.CrashReporter.RecentError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which entry the diagnostics ring gives up when it is full.
 *
 * This is not a nicety. The ring holds ten entries, and the periodic
 * `player.stats` breadcrumb is filed on a few-minute cadence per playback
 * session, so under the old oldest-first policy a report exported while the
 * viewer was looking at a failure came back as ten copies of the same benign
 * stats line with none of the failures in it — the section answering "what
 * went wrong?" with "played 0s" ten times.
 *
 * The failure is silent and only shows up in a report the user has already
 * sent, which is why the policy is pinned here rather than left to
 * inspection.
 */
class RecentErrorEvictionTest {

    private fun failure(id: Int) = RecentError(
        atMs = id.toLong(),
        source = "playback",
        summary = "failure $id"
    )

    private fun breadcrumb(id: Int) = RecentError(
        atMs = id.toLong(),
        source = "player.stats",
        summary = "played 0s, rebuffers 0 (0s), dropped 0"
    )

    @Test
    fun `a ring of failures still gives up the oldest`() {
        // The pre-existing behavior, unchanged for everything that is not a
        // breadcrumb.
        assertEquals(
            0,
            CrashReporter.evictionIndex(listOf(failure(1), failure(2), failure(3)))
        )
    }

    @Test
    fun `an existing breadcrumb gives way before a failure`() {
        // The newcomer is a failure, so the breadcrumb sitting at index 1 is
        // the cheapest thing to lose.
        assertEquals(
            1,
            CrashReporter.evictionIndex(
                listOf(failure(1), breadcrumb(2), failure(3), failure(4))
            )
        )
    }

    @Test
    fun `the oldest breadcrumb goes first`() {
        assertEquals(
            0,
            CrashReporter.evictionIndex(
                listOf(breadcrumb(1), failure(2), breadcrumb(3), failure(4))
            )
        )
    }

    @Test
    fun `an incoming breadcrumb is dropped rather than a failure`() {
        // The reported case: a ring full of failures does not become a ring
        // full of stats lines because the stats line happened to arrive last.
        val retained = listOf(
            failure(1),
            failure(2),
            failure(3),
            breadcrumb(4)
        )

        assertEquals(3, CrashReporter.evictionIndex(retained))
    }

    @Test
    fun `a full ring of breadcrumbs simply rotates`() {
        assertEquals(
            0,
            CrashReporter.evictionIndex(
                listOf(breadcrumb(1), breadcrumb(2), breadcrumb(3))
            )
        )
    }

    @Test
    fun `an empty ring has nothing to drop`() {
        assertEquals(0, CrashReporter.evictionIndex(emptyList()))
    }

    @Test
    fun `only the stats breadcrumb counts as routine`() {
        // A source that names a real failure must never be treated as
        // evictable filler just because its name looks similar.
        assertEquals(false, CrashReporter.isRoutineBreadcrumb("playback"))
        assertEquals(false, CrashReporter.isRoutineBreadcrumb("player.stats.x"))
        assertEquals(true, CrashReporter.isRoutineBreadcrumb("player.stats"))
    }
}
