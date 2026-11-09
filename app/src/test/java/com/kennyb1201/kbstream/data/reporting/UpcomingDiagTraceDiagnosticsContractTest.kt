package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CW / Upcoming evaluation evidence in COPY DIAGNOSTICS (spec Slice D).
 *
 * The field report: three shows aired on Nov 8, sat in Upcoming, and were
 * missing from Continue Watching on both devices - and the diagnostics paste
 * could not say why. The only lines that answer it (`cw skip …`,
 * `caught-up blob …`, the leg (c) summaries) were `Log.d("UPCOMING_DIAG", …)`:
 * logcat-only, on a release APK that carries no logcat and TVs that cannot read
 * one. The paste's `cw.filter:` block answers a different question (hidden-title
 * verdicts), so it cannot stand in.
 *
 * What is pinned here:
 *
 *  - the ring is [GuideTrace]'s shape with a capacity sized to ONE feed build
 *    (sixty-four, not sixteen - a build emits a line per evaluated show), oldest
 *    first, process-wide, and never cleared by navigation (T1, T2),
 *  - every `UPCOMING_DIAG` log site has a twin with the SAME string, so log
 *    wording and log volume are unchanged (T3),
 *  - the positive half exists at all: the CW leg records `cw kept …` as well as
 *    `cw skip …`, and the skip names its reason (T3),
 *  - and the block rides in the report after the `cw.filter:` block with one
 *    print-time prefix, appearing only once something has been evaluated
 *    (T4).
 */
class UpcomingDiagTraceDiagnosticsContractTest {

    private val trace by lazy { source(TRACE) }
    private val diagnostics by lazy { source(DIAGNOSTICS) }
    private val repository by lazy { source(SIMKL_REPOSITORY) }
    private val reads by lazy { source(SIMKL_READS) }
    private val home by lazy { source(HOME_VIEW_MODEL) }

    @After
    fun tearDown() {
        UpcomingDiagTrace.reset()
    }

    // ── T1: the ring is bounded and ordered ───────────────────────────────

    @Test
    fun `sixty-five notes keep the last sixty-four, oldest first`() {
        (1..65).forEach { UpcomingDiagTrace.note("line $it") }

        assertEquals(
            "sixty-four, not GuideTrace's sixteen: one Simkl feed build emits a " +
                "skip or kept line PER EVALUATED SHOW, so a smaller ring would evict " +
                "the caught-up blob line - the staleness evidence the paste exists for " +
                "- during the very build it is meant to explain",
            64,
            UpcomingDiagTrace.lines().size
        )
        assertEquals("line 2", UpcomingDiagTrace.lines().first())
        assertEquals("line 65", UpcomingDiagTrace.lines().last())
    }

    // ── T2: the ring is GuideTrace's shape ────────────────────────────────

    @Test
    fun `the ring is GuideTrace's shape and cannot be cleared by navigation`() {
        assertTrue(
            "the same bounded ring as the other traces: sixty-four, oldest first",
            trace.contains("private const val MAX_LINES = 64") &&
                trace.contains(
                    "synchronized(lock) { lines = (lines + trimmed).takeLast(MAX_LINES) }"
                )
        )
        assertTrue(
            "and the same three entries: note(), lines() and reset()",
            trace.contains("fun note(line: String)") &&
                trace.contains("fun lines(): List<String>") &&
                trace.contains("fun reset()")
        )
        assertEquals(
            "only reset() empties it - the paste is taken AFTER the repro, from " +
                "Settings, so leaving Home, a rail rebuild or a profile switch must " +
                "not wipe the evidence",
            0,
            repository.count("UpcomingDiagTrace.reset(") +
                reads.count("UpcomingDiagTrace.reset(") +
                home.count("UpcomingDiagTrace.reset(") +
                diagnostics.count("UpcomingDiagTrace.reset(")
        )
    }

    @Test
    fun `a trim and a blank note cannot waste a line`() {
        UpcomingDiagTrace.note("  cw skip title='The Simpsons' watched=781 aired=782 reason=caught-up  ")
        UpcomingDiagTrace.note("   ")

        assertEquals(
            "the stored line is the log string, trimmed - not an indented copy",
            listOf("cw skip title='The Simpsons' watched=781 aired=782 reason=caught-up"),
            UpcomingDiagTrace.lines()
        )
    }

    // ── T3: every log site has its twin, and both verdicts are recorded ────

    @Test
    fun `the CW leg records the skip and the kept verdict, and names the reason`() {
        assertTrue(
            "the skip line is hoisted, logged and noted - one string, so the log and " +
                "the paste cannot drift apart",
            repository.contains("val cwLine =") &&
                repository.contains("Log.d(\"UPCOMING_DIAG\", cwLine)") &&
                repository.contains("UpcomingDiagTrace.note(cwLine)")
        )
        assertEquals(
            "one log site, unchanged in tag and volume",
            1,
            repository.count("UPCOMING_DIAG\"")
        )
        assertTrue(
            "X4: the KEPT verdict is a line too - without it a paste with only skips " +
                "cannot tell 'every show was kept' from 'the leg never ran', which is " +
                "exactly the Nov 8 fork",
            repository.contains("\"cw kept title='\${item.show?.title}' \"") &&
                repository.contains("if (candidate) {")
        )
        assertTrue(
            "X3: the skip names WHICH branch of the rule refused it, instead of making " +
                "the reader re-run the rule on the tallies",
            repository.contains("\"reason=\${cwSkipReason(item, watched, aired)}\"") &&
                repository.contains("\"dropped\"") &&
                repository.contains("\"not-started\"") &&
                repository.contains("\"caught-up\"") &&
                repository.contains("\"finished\"")
        )
        assertTrue(
            "and both verdicts sit behind the one flag, so UPCOMING_DIAGNOSTICS stays " +
                "the single kill-switch for logcat and the paste",
            repository.contains("if (UPCOMING_DIAGNOSTICS) {") &&
                repository.indexOf("if (UPCOMING_DIAGNOSTICS) {") <
                repository.indexOf("val cwLine =")
        )
    }

    @Test
    fun `the caught-up blob line is recorded, ages and all`() {
        assertTrue(
            "the blob's age vs the last watched write is the staleness evidence - it is " +
                "worth nothing if it only exists in logcat",
            reads.contains("val blobLine =") &&
                reads.contains("Log.d(\"UPCOMING_DIAG\", blobLine)") &&
                reads.contains("UpcomingDiagTrace.note(blobLine)") &&
                reads.contains("\" lastWriteAgeMs=\"") &&
                reads.contains("\" refreshed=\"")
        )
    }

    @Test
    fun `every Home evaluation line has a twin note with the same string`() {
        val named =
            listOf(
                "notCaughtUpLine",
                "candidatesLine",
                "enrichedLine",
                "cardsLine",
                "localCandidatesLine",
                "localCardsLine",
                "droppedLine"
            )

        named.forEach { name ->
            assertTrue(
                "$name is built once, logged once and noted once - the twin is an " +
                    "additional sink, not a second line",
                home.contains("val $name =") &&
                    home.contains("Log.d(\"UPCOMING_DIAG\", $name)") &&
                    home.contains("UpcomingDiagTrace.note($name)")
            )
        }
        assertEquals(
            "one twin per UPCOMING_DIAG site in Home - nine of them, the same nine " +
                "as before the spec",
            9,
            home.count("UPCOMING_DIAG\"")
        )
        assertEquals(
            "and nine notes, so no site was left logcat-only",
            9,
            home.count("UpcomingDiagTrace.note(")
        )
    }

    @Test
    fun `the two whole-rail failure lines are recorded too`() {
        assertEquals(
            "the build failure and the leg (c) fallback each discriminate a whole-rail " +
                "miss, so both messages reach the paste",
            2,
            home.count("UpcomingDiagTrace.note(failedLine)")
        )
        assertEquals(
            "and both are still logged with their message and throwable, unchanged",
            2,
            home.count("failedLine,")
        )
        assertTrue(home.contains("Log.e(") && home.contains("Log.w("))
    }

    // ── T4: the report ───────────────────────────────────────────────────

    @Test
    fun `the block follows the cw filter block and carries one prefix`() {
        val filter =
            diagnostics.indexOf("CwFilterTrace.lines().forEach { report.appendLine(it) }")
        val upcoming =
            diagnostics.indexOf(
                "UpcomingDiagTrace.lines().forEach { report.appendLine(\"upcoming: \$it\") }"
            )

        assertTrue("the cw.filter block exists", filter >= 0)
        assertTrue(
            "the evaluation block sits immediately after the other CW evidence, so a " +
                "reader compares the filter verdict with the evaluation verdict",
            upcoming > filter
        )
        assertTrue(
            "one print-time prefix keeps the two subjects (cw skip/kept and caught-up …) " +
                "greppable as one block",
            diagnostics.contains("report.appendLine(\"upcoming: \$it\")")
        )
    }

    @Test
    fun `a session that never evaluates a rail prints no evaluation lines`() {
        assertEquals("the ring starts empty", emptyList<String>(), UpcomingDiagTrace.lines())
        assertTrue(
            "the report appends per line, so an empty ring adds nothing - not even a " +
                "header that would read as 'the rail never ran'",
            diagnostics.contains(
                "UpcomingDiagTrace.lines().forEach { report.appendLine(\"upcoming: \$it\") }"
            )
        )
        assertFalse(
            "there is no unconditional evaluation heading",
            diagnostics.contains("upcoming focus:") ||
                diagnostics.contains("report.appendLine(\"upcoming: \")")
        )
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
        const val SIMKL_REPOSITORY = "com/kennyb1201/kbstream/data/simkl/SimklRepository.kt"
        const val SIMKL_READS = "com/kennyb1201/kbstream/data/simkl/SimklReads.kt"
        const val HOME_VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
