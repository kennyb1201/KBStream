package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide refresh's verdicts (spec 2026-11-14, F1c/F3).
 *
 * The field paste read `failing=no stall=none emptyRuns=0` through a 22-hour gap
 * with no import, because a pass only recorded anything when it reached its own
 * empty-source branch. Every other outcome - declined as fresh, deferred for
 * playback, timed out, threw, stopped by WorkManager - was silent. These tests
 * pin a different word for each, and the streak that turns them into `failing`.
 */
class EpgRefreshDiagnosticsTest {

    private fun source(
        index: Int,
        outcome: EpgRefreshDiagnostics.SourceOutcome,
        errorClass: String? = null,
        detail: String? = null
    ) = EpgRefreshDiagnostics.SourceResult(index, outcome, errorClass, detail)

    @Test
    fun `each outcome has its own word`() {
        assertEquals("1:landed", EpgRefreshDiagnostics.sourceToken(source(1, EpgRefreshDiagnostics.SourceOutcome.LANDED)))
        assertEquals("1:no-new", EpgRefreshDiagnostics.sourceToken(source(1, EpgRefreshDiagnostics.SourceOutcome.NOT_LANDED)))
        assertEquals("2:timeout", EpgRefreshDiagnostics.sourceToken(source(2, EpgRefreshDiagnostics.SourceOutcome.TIMEOUT)))
        assertEquals(
            "1:threw SocketTimeoutException",
            EpgRefreshDiagnostics.sourceToken(
                source(1, EpgRefreshDiagnostics.SourceOutcome.FAILED, "SocketTimeoutException")
            )
        )
    }

    @Test
    fun `a cut says where it landed, and the rest keep their exact token`() {
        assertEquals(
            "a timeout carries the importer's own phase and counters, because " +
                "\"1:timeout\" alone cannot say whether the pass was a minute short " +
                "or needs a window no worker has",
            "1:timeout (saving,103622 rows,48MB)",
            EpgRefreshDiagnostics.sourceToken(
                source(
                    1,
                    EpgRefreshDiagnostics.SourceOutcome.TIMEOUT,
                    detail = "saving,103622 rows,48MB"
                )
            )
        )
        assertEquals(
            "and an outcome with nothing to add renders exactly as before",
            "1:timeout",
            EpgRefreshDiagnostics.sourceToken(
                source(1, EpgRefreshDiagnostics.SourceOutcome.TIMEOUT)
            )
        )
    }

    @Test
    fun `a pass that landed says so, and one that did not names the branch`() {
        assertEquals(
            "import 1:landed, landed=1",
            EpgRefreshDiagnostics.verdictLine(
                EpgRefreshDiagnostics.PassVerdict(
                    EpgRefreshDiagnostics.Decision.IMPORT,
                    listOf(source(1, EpgRefreshDiagnostics.SourceOutcome.LANDED)),
                    imported = 1
                )
            )
        )
        assertEquals(
            "import 1:timeout, landed=0",
            EpgRefreshDiagnostics.verdictLine(
                EpgRefreshDiagnostics.PassVerdict(
                    EpgRefreshDiagnostics.Decision.IMPORT,
                    listOf(source(1, EpgRefreshDiagnostics.SourceOutcome.TIMEOUT)),
                    imported = 0
                )
            )
        )
        assertEquals(
            "fresh — skipped",
            EpgRefreshDiagnostics.verdictLine(EpgRefreshDiagnostics.PassVerdict(EpgRefreshDiagnostics.Decision.FRESH))
        )
        assertEquals(
            "defer (player)",
            EpgRefreshDiagnostics.verdictLine(EpgRefreshDiagnostics.PassVerdict(EpgRefreshDiagnostics.Decision.DEFER))
        )
        assertEquals(
            "no sources",
            EpgRefreshDiagnostics.verdictLine(EpgRefreshDiagnostics.PassVerdict(EpgRefreshDiagnostics.Decision.NO_SOURCES))
        )
    }

    @Test
    fun `a stopped pass is named, with how far it got`() {
        assertEquals(
            "stopped mid-import",
            EpgRefreshDiagnostics.verdictLine(
                EpgRefreshDiagnostics.PassVerdict(
                    EpgRefreshDiagnostics.Decision.IMPORT,
                    stopped = true
                )
            )
        )
        assertEquals(
            "stopped mid-import after 1:landed",
            EpgRefreshDiagnostics.verdictLine(
                EpgRefreshDiagnostics.PassVerdict(
                    EpgRefreshDiagnostics.Decision.IMPORT,
                    listOf(source(1, EpgRefreshDiagnostics.SourceOutcome.LANDED)),
                    imported = 1,
                    stopped = true
                )
            )
        )
    }

    @Test
    fun `a landed pass resets the streak and clears failing`() {
        val (streak, failing) = EpgRefreshDiagnostics.failingAfter(
            EpgRefreshDiagnostics.PassVerdict(
                EpgRefreshDiagnostics.Decision.IMPORT,
                listOf(source(1, EpgRefreshDiagnostics.SourceOutcome.LANDED)),
                imported = 1
            ),
            previousStreak = 4
        )
        assertEquals(0, streak)
        assertFalse(failing)
    }

    @Test
    fun `three no-import passes in a row make the refresh failing`() {
        var streak = 0
        var failing = false
        repeat(2) {
            val next = EpgRefreshDiagnostics.failingAfter(
                EpgRefreshDiagnostics.PassVerdict(
                    EpgRefreshDiagnostics.Decision.IMPORT,
                    listOf(source(1, EpgRefreshDiagnostics.SourceOutcome.TIMEOUT)),
                    imported = 0
                ),
                streak
            )
            streak = next.first
            failing = next.second
            assertFalse("not failing yet after $streak", failing)
        }
        val third = EpgRefreshDiagnostics.failingAfter(
            EpgRefreshDiagnostics.PassVerdict(
                EpgRefreshDiagnostics.Decision.IMPORT,
                listOf(source(1, EpgRefreshDiagnostics.SourceOutcome.TIMEOUT)),
                imported = 0
            ),
            streak
        )
        assertEquals(3, third.first)
        assertTrue(third.second)
    }

    @Test
    fun `a stop counts toward the streak, a fresh or deferred pass does not`() {
        val stopped = EpgRefreshDiagnostics.failingAfter(
            EpgRefreshDiagnostics.PassVerdict(
                EpgRefreshDiagnostics.Decision.IMPORT,
                listOf(source(1, EpgRefreshDiagnostics.SourceOutcome.STOPPED)),
                stopped = true
            ),
            previousStreak = 2
        )
        assertEquals(3, stopped.first)
        assertTrue(stopped.second)

        // A stop AFTER a source landed is not a failed pass: the guide gained
        // programs, so the streak resets rather than counting a success as a miss.
        val stoppedAfterLanded = EpgRefreshDiagnostics.failingAfter(
            EpgRefreshDiagnostics.PassVerdict(
                EpgRefreshDiagnostics.Decision.IMPORT,
                listOf(source(1, EpgRefreshDiagnostics.SourceOutcome.LANDED)),
                imported = 1,
                stopped = true
            ),
            previousStreak = 2
        )
        assertEquals(0, stoppedAfterLanded.first)
        assertFalse(stoppedAfterLanded.second)

        listOf(
            EpgRefreshDiagnostics.Decision.FRESH,
            EpgRefreshDiagnostics.Decision.DEFER,
            EpgRefreshDiagnostics.Decision.NO_SOURCES
        ).forEach { decision ->
            val untouched = EpgRefreshDiagnostics.failingAfter(
                EpgRefreshDiagnostics.PassVerdict(decision),
                previousStreak = 2
            )
            assertEquals(
                "$decision must not count as a failed import",
                2,
                untouched.first
            )
        }
    }

    @Test
    fun `the trillion-hour sentinel prints none, not a countdown`() {
        val now = 1_700_000_000_000L
        assertEquals("none", EpgRefreshDiagnostics.countdown(now, Long.MAX_VALUE))
        assertEquals("none", EpgRefreshDiagnostics.countdown(now, 0L))
        // Any horizon past a year is not a schedule this app made.
        assertEquals("none", EpgRefreshDiagnostics.countdown(now, now + 2 * 365L * 86_400_000L))
        assertEquals("due", EpgRefreshDiagnostics.countdown(now, now - 1L))
        assertEquals("in 12m", EpgRefreshDiagnostics.countdown(now, now + 12 * 60_000L))
        assertEquals("in 5h 20m", EpgRefreshDiagnostics.countdown(now, now + (5 * 60L + 20L) * 60_000L))
        assertEquals("in 2d 3h", EpgRefreshDiagnostics.countdown(now, now + (2 * 24L + 3L) * 3_600_000L))
    }

    @Test
    fun `a verdict names no URL and stays inside recordEvent's cap`() {
        val verdict = EpgRefreshDiagnostics.PassVerdict(
            EpgRefreshDiagnostics.Decision.IMPORT,
            listOf(
                source(1, EpgRefreshDiagnostics.SourceOutcome.TIMEOUT),
                source(2, EpgRefreshDiagnostics.SourceOutcome.FAILED, "IllegalStateException")
            ),
            imported = 0
        )
        val line = EpgRefreshDiagnostics.verdictLine(verdict)
        assertTrue("${line.length}: $line", line.length <= 160)
        assertFalse("sources are numbered, never named by URL", line.contains("http"))
        assertFalse(line.contains("/"))
    }
}
