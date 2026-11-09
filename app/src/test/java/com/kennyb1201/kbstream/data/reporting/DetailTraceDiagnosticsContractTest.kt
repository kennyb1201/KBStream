package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detail page's focus evidence in COPY DIAGNOSTICS (audit §3, T8).
 *
 * The page's traps each fail by consuming a press and moving nothing, and the
 * only trace of that was logcat - which the release APK does not carry and a TV
 * cannot read. So the walk that ended in a trap now travels in the paste: one
 * line per chain move (E1), the top landing's exhaustion (E2), the toggle's own
 * outcomes (E3), the Media row's internal handoffs (E4), the hand-back verdict
 * (E5) and the entry landing (E6).
 *
 * Pinned here: the ring is sixteen lines and never cleared by navigation (T1,
 * T5), every choke point above notes its line (T2), the block sits between the
 * library-art and session sections and prints nothing until Detail is used
 * (T3), and no line can carry a title or an id (T4).
 */
class DetailTraceDiagnosticsContractTest {

    private val detail by lazy { normalized(stripComments(source(DETAIL))) }
    private val handBack by lazy { normalized(stripComments(source(HAND_BACK))) }
    private val diagnostics by lazy { source(DIAGNOSTICS) }
    private val trace by lazy { source(TRACE) }

    @After
    fun tearDown() {
        DetailTrace.reset()
    }

    // ── T1/T5: the ring ────────────────────────────────────────────────────

    @Test
    fun `seventeen notes keep the last sixteen, oldest first`() {
        (1..17).forEach { DetailTrace.note("line $it") }

        val lines = DetailTrace.lines()
        assertEquals(
            "sixteen, not eight: a full walk is ten rows down and ten back up, and " +
                "the trap needs the walk, not just its tail",
            16,
            lines.size
        )
        assertEquals("line 2", lines.first())
        assertEquals("line 17", lines.last())
    }

    @Test
    fun `the ring is TorBoxTrace's shape and navigation cannot clear it`() {
        assertTrue(
            "the same bounded ring as the other traces",
            trace.contains("private const val MAX_LINES = 16") &&
                trace.contains(
                    "synchronized(lock) { lines = (lines + trimmed).takeLast(MAX_LINES) }"
                ) &&
                trace.contains("fun note(line: String)") &&
                trace.contains("fun lines(): List<String>")
        )
        assertEquals(
            "only reset() empties it: the report is taken from Settings AFTER the " +
                "repro, so leaving Detail, changing title or switching profile must " +
                "not wipe the evidence",
            0,
            detail.count("DetailTrace.reset(") + handBack.count("DetailTrace.reset(") +
                diagnostics.count("DetailTrace.reset(")
        )
    }

    @Test
    fun `a trim and a blank note cannot waste a line`() {
        DetailTrace.note("  detail move GENRE UP -> HEADER landed=true via=chain  ")
        DetailTrace.note(" ")

        assertEquals(
            listOf("detail move GENRE UP -> HEADER landed=true via=chain"),
            DetailTrace.lines()
        )
    }

    // ── T2 E1: one line per chain move, whatever the outcome ───────────────

    @Test
    fun `every chain move notes its target, its landing and the path it took`() {
        assertTrue(
            "E1: the move, spelled the way a paste can be read",
            detail.contains("\"detail move \$from \$direction -> ") &&
                detail.contains("\"landed=\$tracedLanded via=\$tracedVia\"")
        )
        assertTrue(
            "noted from a finally, so every exit produces a line - including the " +
                "press startFocusMove cancels, which never lands at all",
            detail.contains("} finally {") &&
                detail.substringAfter("} finally {").contains("DetailTrace.note(")
        )
        val vias = listOf("\"toggle\"", "\"chain\"", "\"geometry\"", "\"edge\"")
        vias.forEach { via ->
            assertTrue("the via field names $via", detail.contains("tracedVia = $via"))
        }
        assertTrue(
            "the target is the chain row the press aimed at, and `none` when the " +
                "chain had no row that way - a dead press is a line, not an absence",
            detail.contains("tracedTarget = target") &&
                detail.contains("tracedTarget ?: \"none\"")
        )
    }

    // ── T2 E2-E6: the other choke points ──────────────────────────────────

    @Test
    fun `an exhausted top landing and the toggle both note it`() {
        assertTrue(
            "E2: the same site as the Log.w, so logcat and the paste agree",
            detail.contains("detail top landing failed after \$attempts attempts") &&
                detail.contains(
                    "\"focusTopOfPage: PLAY and details would not take the focus after \""
                )
        )
        assertTrue(
            "E3: both arms of the toggle, named by direction",
            detail.contains("detail toggle UP landed=\$landed") &&
                detail.contains("detail toggle DOWN landed=\$kept")
        )
    }

    @Test
    fun `both Media handoffs note their landing`() {
        assertTrue(
            "E4: card -> strip and strip -> cards",
            detail.contains("detail media UP landed=\$landed") &&
                detail.contains("detail media DOWN landed=\$landed")
        )
        assertTrue(
            "and each one falls through to the geometric search when its own " +
                "landing failed (F3)",
            detail.contains(
                "if (!landed) { focusByGeometry( DetailFocusDirection.UP ) }"
            ) && detail.contains(
                "if (!landed) { focusByGeometry( DetailFocusDirection.DOWN ) }"
            )
        )
    }

    @Test
    fun `the hand-back verdict and the entry landing are recorded`() {
        assertTrue(
            "E5: delivered and abandoned, with the surface label and the frames it took",
            handBack.contains(
                "detail hand-back \${handBack.label} delivered after \$frame frame(s)"
            ) && handBack.contains(
                "detail hand-back \${handBack.label} abandoned after \$frame frame(s)"
            )
        )
        assertTrue(
            "E6: the page's first focus - PLAY, or the rail card a drill-down returns to",
            detail.contains("detail entry focus=play landed=\$landed") &&
                detail.contains("detail entry focus=return:\${target.rowKey} landed=")
        )
    }

    // ── T3: the block, and the report that never opened Detail ─────────────

    @Test
    fun `the detail block sits between the library-art and session sections`() {
        val art = diagnostics.indexOf("LibraryRailArtTrace.lines().forEach { report.appendLine(it) }")
        val block = diagnostics.indexOf(
            "DetailTrace.lines().forEach { report.appendLine(\"detail: \$it\") }"
        )
        val session = diagnostics.indexOf(
            "PlaybackSessionTrace.lines().forEach { report.appendLine(\"session: \$it\") }"
        )
        assertTrue("the library-art block exists", art >= 0)
        assertTrue("T3: the detail block follows it", block > art)
        assertTrue("and precedes the session block", session > block)
    }

    @Test
    fun `a session that never opens Detail prints no detail lines`() {
        assertEquals("the ring starts empty", emptyList<String>(), DetailTrace.lines())
        assertTrue(
            "the report appends per line, so an empty ring adds nothing at all - not " +
                "even a heading that would read as \"Detail never opened\"",
            diagnostics.contains(
                "DetailTrace.lines().forEach { report.appendLine(\"detail: \$it\") }"
            )
        )
        assertFalse(
            "no unconditional detail heading",
            diagnostics.contains("report.appendLine(\"detail: \")")
        )
    }

    // ── T4: no PII in any line ────────────────────────────────────────────

    @Test
    fun `the lines carry rows, directions and counts only`() {
        // A report gets pasted into public threads, so this pins that every
        // noting site names only the page's own vocabulary: rows, directions,
        // counts, frame budgets and hand-back labels.
        val sites = detail
            .split("DetailTrace.note(")
            .drop(1)
            .map { it.substringBefore(")") }
        assertTrue("there are noting sites to check", sites.isNotEmpty())
        sites.forEach { site ->
            listOf(".title", "tmdbId", "imdbId", "email", "streamUrl", "token").forEach { leak ->
                assertFalse("a detail trace line must not carry $leak: $site", site.contains(leak))
            }
        }
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

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
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val HAND_BACK = "com/kennyb1201/kbstream/ui/detail/DetailHandBack.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
        const val TRACE = "com/kennyb1201/kbstream/data/reporting/DetailTrace.kt"
    }
}
