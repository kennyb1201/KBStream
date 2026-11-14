package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic push's verdicts (spec 2026-11-14).
 *
 * The field symptom was an ABSENCE - `lastPush=never pending=0` for three builds
 * - and the fix had to make three different breaks say different things:
 * nothing due, nothing queued, nothing running. These tests are that requirement
 * in arithmetic, since the device under test is a phone nobody can attach a
 * debugger to.
 */
class SyncPushDiagnosticsTest {

    private fun key(
        name: String,
        localSession: Boolean = true,
        gateOpen: Boolean = true,
        marker: SyncPushDiagnostics.Marker = SyncPushDiagnostics.Marker.ABSENT,
        due: Boolean = false,
        queued: Boolean = false
    ) = SyncPushDiagnostics.KeyVerdict(name, localSession, gateOpen, marker, due, queued)

    private fun verdict(
        keys: List<SyncPushDiagnostics.KeyVerdict>,
        signedIn: Boolean = true,
        pullRan: Boolean = true,
        scopeActive: Boolean = true,
        loopRunning: Boolean = true,
        failureClass: String? = null
    ) = SyncPushDiagnostics.BackfillVerdict(
        signedIn = signedIn,
        pullRan = pullRan,
        scopeActive = scopeActive,
        loopRunning = loopRunning,
        failureClass = failureClass,
        keys = keys
    )

    @Test
    fun `a locally-held session this device never published is due and enqueued`() {
        val run = verdict(listOf(key("kitsu", due = true, queued = true)))
        assertEquals("kitsu due, enqueued", SyncPushDiagnostics.backfillSummary(run))
        assertEquals(
            "bf: in=1 pull=1 scope=1 loop=1 | kitsu ses=1 gate=1 due=1 q=1 mk=absent",
            SyncPushDiagnostics.backfillLine(run)
        )
    }

    @Test
    fun `no local session is named as such, not as a broken backfill`() {
        val run = verdict(listOf(key("kitsu", localSession = false, gateOpen = false)))
        assertEquals("nothing due — kitsu no session", SyncPushDiagnostics.backfillSummary(run))
        assertTrue(SyncPushDiagnostics.backfillLine(run).contains("kitsu ses=0 gate=0 due=0"))
    }

    @Test
    fun `a pulled session with the gate shut is a correct nothing-due`() {
        // Connected on the TV, ingested by this phone's pull: the phone HAS a
        // copy, but nothing it should publish (spec §4 - the distinction that
        // stops "nothing due" reading as "the backfill is broken").
        val run = verdict(listOf(key("kitsu", localSession = true, gateOpen = false)))
        assertEquals("nothing due — kitsu nothing to publish", SyncPushDiagnostics.backfillSummary(run))
    }

    @Test
    fun `an already-published session is not due, and the marker says which half`() {
        val run = verdict(
            listOf(
                key("kitsu", marker = SyncPushDiagnostics.Marker.MATCH),
                key("mal", localSession = false, gateOpen = false)
            )
        )
        assertEquals(
            "nothing due — kitsu already published, mal no session",
            SyncPushDiagnostics.backfillSummary(run)
        )
        val line = SyncPushDiagnostics.backfillLine(run)
        assertTrue(line.contains("kitsu ses=1 gate=1 due=0 q=0 mk=match"))
        assertTrue("a never-published key's marker is absent", line.contains("mal ses=0 gate=0 due=0 q=0 mk=absent"))
    }

    @Test
    fun `the three skip verdicts are three different lines`() {
        assertEquals("bf skipped — pull did not run", SyncPushDiagnostics.backfillLine(verdict(emptyList(), pullRan = false)))
        assertEquals("bf skipped — not signed in", SyncPushDiagnostics.backfillLine(verdict(emptyList(), signedIn = false)))
        assertEquals(
            "bf skipped — profile changed mid-run",
            SyncPushDiagnostics.backfillLine(verdict(emptyList(), scopeActive = false))
        )
        assertEquals("skipped — pull did not run", SyncPushDiagnostics.backfillSummary(verdict(emptyList(), pullRan = false)))
    }

    @Test
    fun `a throw is its class name, never its message`() {
        // The message of a thrown sync call can carry a URL, a response body or a
        // token. The class cannot.
        val run = verdict(emptyList(), failureClass = "IllegalStateException")
        assertEquals("bf threw — IllegalStateException", SyncPushDiagnostics.backfillLine(run))
        assertEquals("threw IllegalStateException", SyncPushDiagnostics.backfillSummary(run))
    }

    @Test
    fun `the paste line fits recordEvent's 160-character cap for all three keys`() {
        val worst = verdict(
            listOf(
                key("kitsu", marker = SyncPushDiagnostics.Marker.MISMATCH, due = true, queued = true),
                key("mal", marker = SyncPushDiagnostics.Marker.MISMATCH, due = true, queued = true),
                key("anilist", marker = SyncPushDiagnostics.Marker.MISMATCH, due = true, queued = true)
            )
        )
        val line = SyncPushDiagnostics.backfillLine(worst)
        assertTrue("${line.length} chars: $line", line.length <= 160)
        assertTrue("and still names every key", listOf("kitsu", "mal", "anilist").all { line.contains(it) })
    }

    @Test
    fun `the verdict carries no free text that could hold a secret`() {
        // Every character in a paste line comes from this vocabulary: the three
        // key names, fixed tokens, and digits.
        val run = verdict(
            listOf(
                key("kitsu", marker = SyncPushDiagnostics.Marker.MISMATCH, due = true, queued = true),
                key("mal", localSession = false, gateOpen = false),
                key("anilist", marker = SyncPushDiagnostics.Marker.MATCH)
            )
        )
        val line = SyncPushDiagnostics.backfillLine(run)
        assertTrue(
            "unexpected characters in: $line",
            line.matches(Regex("^[a-z0-9 :|=—_-]+$"))
        )
        assertFalse("no URLs", line.contains("http"))
        assertFalse("no bearer material", line.contains("Bearer") || line.contains("token"))
    }

    @Test
    fun `the loop's state distinguishes four ways of not running`() {
        assertEquals("alive", SyncPushDiagnostics.loopStateToken(alive = true, storedState = null))
        assertEquals(
            "not running (signed out)",
            SyncPushDiagnostics.loopStateToken(alive = false, storedState = SyncPushDiagnostics.LOOP_SIGNED_OUT)
        )
        assertEquals(
            "not running (cancelled)",
            SyncPushDiagnostics.loopStateToken(alive = false, storedState = SyncPushDiagnostics.LOOP_CANCELLED)
        )
        assertEquals(
            "not running (process ended)",
            SyncPushDiagnostics.loopStateToken(alive = false, storedState = SyncPushDiagnostics.LOOP_PROCESS_END)
        )
        assertEquals("not running (never started)", SyncPushDiagnostics.loopStateToken(alive = false, storedState = null))
    }

    @Test
    fun `an alive loop shows its last flush and rows, a stopped one when it was last seen`() {
        val now = 1_000_000_000L
        assertEquals(
            "alive, last flush 12m ago, 3 rows",
            SyncPushDiagnostics.loopLine(
                stateToken = "alive",
                lastAliveAtMs = now - 60_000L,
                lastFlushAtMs = now - 12 * 60_000L,
                lastUploaded = 3,
                lastAttempted = 3,
                nowMs = now
            )
        )
        assertEquals(
            "alive, no flush yet",
            SyncPushDiagnostics.loopLine("alive", 0L, 0L, 0, 0, now)
        )
        assertEquals(
            "not running (signed out), last seen 40m ago",
            SyncPushDiagnostics.loopLine("not running (signed out)", now - 40 * 60_000L, 0L, 0, 0, now)
        )
    }

    @Test
    fun `a flush that uploaded nothing says it made no progress`() {
        assertEquals(
            "alive, last flush 1m ago, 0 rows of 2 (no progress)",
            SyncPushDiagnostics.loopLine("alive", 0L, 1_000_000_000L - 60_000L, 0, 2, 1_000_000_000L)
        )
        assertEquals("flush: 4 attempted, 3 uploaded", SyncPushDiagnostics.flushOutcomeLine(4, 3))
        assertEquals("flush: 2 attempted, 0 uploaded (no progress)", SyncPushDiagnostics.flushOutcomeLine(2, 0))
    }

    @Test
    fun `the pull-end line is the one that names a dead loop`() {
        assertEquals("flush loop: not running at pull end", SyncPushDiagnostics.pullEndLoopLine(false))
        assertEquals("flush loop: running at pull end", SyncPushDiagnostics.pullEndLoopLine(true))
    }

    @Test
    fun `durations read as minutes, hours and days, and never as zero`() {
        val now = 10_000_000_000L
        assertEquals("never", SyncPushDiagnostics.ago(0L, now))
        assertEquals("just now", SyncPushDiagnostics.ago(now - 20_000L, now))
        assertEquals("12m ago", SyncPushDiagnostics.ago(now - 12 * 60_000L, now))
        assertEquals("3h ago", SyncPushDiagnostics.ago(now - 3 * 3_600_000L, now))
        assertEquals("2d ago", SyncPushDiagnostics.ago(now - 2 * 86_400_000L, now))
    }

    @Test
    fun `the loop exit carries words a paste can read`() {
        assertEquals("signed out", SyncPushDiagnostics.loopExitLabel(SyncPushDiagnostics.LOOP_SIGNED_OUT))
        assertEquals("cancelled", SyncPushDiagnostics.loopExitLabel(SyncPushDiagnostics.LOOP_CANCELLED))
        assertEquals("process end", SyncPushDiagnostics.loopExitLabel(SyncPushDiagnostics.LOOP_PROCESS_END))
    }
}
