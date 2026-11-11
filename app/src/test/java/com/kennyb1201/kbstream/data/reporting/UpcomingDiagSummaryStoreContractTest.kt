package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The leg summaries must survive a paste the per-show detail would otherwise
 * overflow (spec: "Upcoming diagnostics: keep the leg summaries out of the
 * dropped-lines ring").
 *
 * The field report: Universal Basic Guys was missing from Upcoming on
 * 2026-11-11, and the COPY DIAGNOSTICS paste proved it never reached the
 * builder - `tt23469464` was in neither dropped list and no `fallback air
 * TVmaze` line fired - while the lines that would say WHY (the Simkl leg's
 * candidate list, the enriched line's `no-dated-tmdb-episode` bucket, the
 * local leg's candidate list) were gone. One ring held sixty-four lines and
 * the two builds' dropped lists plus the kept-off line filled exactly
 * sixty-four, so the summaries - emitted FIRST in each build, therefore the
 * oldest lines - were the ones evicted.
 *
 * What is pinned here:
 *
 *  - a summary goes to [UpcomingDiagTrace.summaries] and NOT to the ring, and
 *    every other line still goes to the ring only (T1, T4),
 *  - the store holds one line per kind, newest wins, and can never grow past
 *    the kinds themselves (T2),
 *  - the routing prefixes are the exact strings the emitters start their lines
 *    with, so a reworded summary cannot silently fall back into the ring (T3),
 *  - the report prints the summaries above the ring with the same `upcoming: `
 *    prefix (T5), and [UpcomingDiagTrace.reset] clears both (T6).
 *
 * Every behavioural test here FAILS against the 0.9.223 source: there is one
 * ring, so a summary noted there shows up in `lines()`.
 */
class UpcomingDiagSummaryStoreContractTest {

    private val trace by lazy { source(TRACE) }
    private val diagnostics by lazy { source(DIAGNOSTICS) }
    private val home by lazy { source(HOME_VIEW_MODEL) }
    private val reads by lazy { source(SIMKL_READS) }

    @After
    fun tearDown() {
        UpcomingDiagTrace.reset()
    }

    // ── T1: the summaries file in the store, not the ring ─────────────────

    @Test
    fun `a leg summary lands in the summary store and never in the ring`() {
        SUMMARIES.forEach(UpcomingDiagTrace::note)

        assertEquals(
            "the per-show ring must not hold a single summary: on a two-build " +
                "refresh its dropped lines fill all sixty-four and evict them, " +
                "which is the eviction this spec exists to stop",
            emptyList<String>(),
            UpcomingDiagTrace.lines()
        )
        assertEquals(
            "and all eight are kept, because nothing evicts the store",
            SUMMARIES,
            UpcomingDiagTrace.summaries()
        )
    }

    @Test
    fun `per-show lines still land in the ring and still evict oldest first`() {
        (1..65).forEach { UpcomingDiagTrace.note("dropped: no next air for 'Show $it' S1E$it") }

        assertEquals(
            "the ring's budget is unchanged: sixty-four, oldest first - the " +
                "dropped list is the evidence that a show never arrived, so it " +
                "is not capped, only bounded",
            64,
            UpcomingDiagTrace.lines().size
        )
        assertEquals(
            "oldest first, so the tail is the newest build",
            "dropped: no next air for 'Show 2' S1E2",
            UpcomingDiagTrace.lines().first()
        )
        assertEquals(
            "dropped: no next air for 'Show 65' S1E65",
            UpcomingDiagTrace.lines().last()
        )
        assertEquals(
            "and no per-show line leaked into the store",
            emptyList<String>(),
            UpcomingDiagTrace.summaries()
        )
    }

    // ── T2: one line per kind, never more ────────────────────────────────

    @Test
    fun `a second note of the same kind replaces the first`() {
        UpcomingDiagTrace.note(
            "caught-up candidates=3 'severance (tt11280740)', 'the bear (tt14452776)', " +
                "'ubg (tt23469464)'"
        )
        UpcomingDiagTrace.note("caught-up candidates=2 'severance (tt11280740)', 'ubg (tt23469464)'")

        assertEquals(
            "one entry per kind: a second build a refresh replaces the first " +
                "leg's line instead of stacking a near-identical copy",
            listOf("caught-up candidates=2 'severance (tt11280740)', 'ubg (tt23469464)'"),
            UpcomingDiagTrace.summaries()
        )
        assertEquals(
            "and the replaced line is not in the ring either",
            emptyList<String>(),
            UpcomingDiagTrace.lines()
        )
    }

    @Test
    fun `the store is bounded by its kinds, however many notes arrive`() {
        // Five hundred refreshes' worth of the same two legs, plus the rest.
        repeat(500) { round ->
            SUMMARIES.forEach { line -> UpcomingDiagTrace.note("$line round=$round") }
        }

        assertEquals(
            "bounded by construction: the key set is the prefix list, so the " +
                "store cannot grow with the session - a paste taken hours in is " +
                "the same eight lines it was after the first refresh",
            8,
            UpcomingDiagTrace.summaries().size
        )
        assertTrue(
            "and the newest note of each kind is the one kept",
            UpcomingDiagTrace.summaries().all { it.endsWith("round=499") }
        )
        assertEquals("the ring took none of it", emptyList<String>(), UpcomingDiagTrace.lines())
    }

    @Test
    fun `the fixed order reads the same whatever order the legs spoke in`() {
        // The local leg's emitter runs after the Simkl leg's, but a build that
        // serves the leg cache notes neither - so the arrival order varies.
        val reversed = SUMMARIES.reversed()
        reversed.forEach(UpcomingDiagTrace::note)

        assertEquals(
            "blob, Simkl candidates, enriched, Simkl cards, local candidates, " +
                "local cards, kept-off, failed - a fixed order, so two pastes of " +
                "the same state can be compared line by line",
            SUMMARIES,
            UpcomingDiagTrace.summaries()
        )
    }

    // ── T3: the routing list is the emitters' own prefixes ───────────────

    @Test
    fun `the prefixes match the strings the emitters start their lines with`() {
        val emitters: List<Pair<String, String>> =
            listOf(
                "caught-up blob " to "caught-up blob source=",
                "caught-up candidates=" to "caught-up candidates=\${candidates.size} ",
                "caught-up enriched=" to "caught-up enriched=\${built.size} ",
                "caught-up cards=" to "caught-up cards=\${upcomingCards.size} ",
                "caught-up candidates (local" to "caught-up candidates (local+mdb+trakt+bingecat)=",
                "caught-up cards (local" to "caught-up cards (local+mdb)=",
                "kept off Upcoming (not caught up): " to "kept off Upcoming (not caught up): ",
                "upcoming schedule build failed" to "upcoming schedule build failed"
            )

        emitters.forEach { (prefix, emitted) ->
            assertTrue(
                "the trace routes `$prefix` to the store",
                trace.contains("\"$prefix\"")
            )
            assertTrue(
                "and an emitter still starts its line with `$emitted` - rewording " +
                    "a summary without moving the prefix would silently drop it " +
                    "back into the ring, where the dropped list evicts it",
                home.contains("\"$emitted\"") || reads.contains("\"$emitted\"")
            )
        }
        assertEquals(
            "eight prefixes, four per source: the six leg summaries, the kept-off " +
                "line and the build-failure line",
            8,
            trace.split("\n").count { line -> line.trimStart().startsWith("\"caught-up") } +
                trace.split("\n").count { line -> line.trim().startsWith("\"kept off") } +
                trace.split("\n").count { line ->
                    line.trim().removeSuffix(",") == "\"upcoming schedule build failed\""
                }
        )
    }

    @Test
    fun `a line that only looks like a summary stays in the ring`() {
        // The leg (c) failure line shares the Simkl leg's words but not its
        // shape: it is not one of the eight summaries, so it keeps travelling
        // in the ring exactly as it did before this spec.
        UpcomingDiagTrace.note("caught-up candidates failed: timeout")
        UpcomingDiagTrace.note("cw skip title='The Bear' watched=12 aired=13 reason=caught-up")

        assertEquals(
            "the near-miss is ring detail - the store is keyed by exact prefix",
            emptyList<String>(),
            UpcomingDiagTrace.summaries()
        )
        assertEquals(
            listOf(
                "caught-up candidates failed: timeout",
                "cw skip title='The Bear' watched=12 aired=13 reason=caught-up"
            ),
            UpcomingDiagTrace.lines()
        )
    }

    // ── T4: logcat is untouched ──────────────────────────────────────────

    @Test
    fun `the store is an extra sink, not a second log line`() {
        assertTrue(
            "the trace itself logs nothing - every line still reaches logcat " +
                "from its own site, with its own tag, unchanged",
            !trace.contains("import android.util.Log")
        )
        assertEquals(
            "and the emitter sites are unchanged in number: ten UPCOMING_DIAG " +
                "sites in Home, one in the Simkl repository",
            10,
            home.count("UPCOMING_DIAG\"")
        )
    }

    // ── T5: the report prints summaries first, then the ring ─────────────

    @Test
    fun `the report prints the summaries above the ring with the same prefix`() {
        val summaries =
            diagnostics.indexOf(
                "UpcomingDiagTrace.summaries().forEach { report.appendLine(\"upcoming: \$it\") }"
            )
        val ring =
            diagnostics.indexOf(
                "UpcomingDiagTrace.lines().forEach { report.appendLine(\"upcoming: \$it\") }"
            )

        assertTrue("the summaries are printed at all", summaries >= 0)
        assertTrue("and the ring still is", ring >= 0)
        assertTrue(
            "summaries before the ring: a reader sees what the legs saw before " +
                "the per-show detail starts, and no ring length can move them",
            summaries < ring
        )
        assertTrue(
            "one prefix for both halves, so the block still greps as one section " +
                "and every existing reader keeps matching",
            diagnostics.contains("report.appendLine(\"upcoming: \$it\")")
        )
    }

    // ── T6: reset clears both ────────────────────────────────────────────

    @Test
    fun `reset clears the summaries as well as the ring`() {
        UpcomingDiagTrace.note("caught-up enriched=4 future-dated=2 no-dated-tmdb-episode='x'")
        UpcomingDiagTrace.note("dropped: no next air for 'The Bear' S3E4")
        UpcomingDiagTrace.reset()

        assertEquals("the ring", emptyList<String>(), UpcomingDiagTrace.lines())
        assertEquals("and the store", emptyList<String>(), UpcomingDiagTrace.summaries())
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.count(needle: String): Int {
        var total = 0
        var index = indexOf(needle)
        while (index >= 0) {
            total++
            index = indexOf(needle, index + needle.length)
        }
        return total
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val TRACE = "com/kennyb1201/kbstream/data/reporting/UpcomingDiagTrace.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
        const val HOME_VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val SIMKL_READS = "com/kennyb1201/kbstream/data/simkl/SimklReads.kt"

        /** The eight summaries, exactly as a real refresh emits them. */
        val SUMMARIES: List<String> =
            listOf(
                "caught-up blob source=simkl ageMs=4200 lastWriteAgeMs=90000 refreshed=true " +
                    "candidates=12 'tt11280740', 'tt23469464'",
                "caught-up candidates=12 'severance (tt11280740)', 'ubg (tt23469464)'",
                "caught-up enriched=12 future-dated=3 no-dated-tmdb-episode='ubg (tt23469464)'",
                "caught-up cards=9 (after kids mode + dismissals)",
                "caught-up candidates (local+mdb+trakt+bingecat)=5 'trakt:11280740'@trakt",
                "caught-up cards (local+mdb)=4 'Severance'",
                "kept off Upcoming (not caught up): 'The Bear'",
                "upcoming schedule build failed"
            )
    }
}
