package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide's focus evidence in COPY DIAGNOSTICS (spec GuideTrace F1-F4, T1-T6).
 *
 * The field report: the stuck first-enter repro on the TCL produced a full paste
 * with NO guide focus lines in it at all, so "landed vs exhausted vs never
 * armed" could not be read from the very capture that was meant to answer it.
 * The guide's focus lines were `Log.i("GUIDE", ...)` - logcat only - and the
 * release APK carries no logcat while a TV has no way to read one.
 *
 * What is pinned here:
 *
 *  - the ring is [TorBoxTrace]'s shape - sixteen lines (GUIDE-LEFT F3 raised it
 *    from eight), oldest first, process-wide and never cleared by navigation
 *    (F1a/F1b, T5),
 *  - every existing GUIDE log site has a twin with the SAME string, so logcat
 *    volume and wording are unchanged (F2a/F2b, T6),
 *  - the three lines that did not exist - arming, stand-down, collapse - are
 *    recorded (F3a-F3c, T1-T4),
 *  - and the block rides in the report right after the guide data and match
 *    sections, printing nothing at all until the guide has been used
 *    (F4a/F4b, T6).
 */
class GuideTraceDiagnosticsContractTest {

    private val grid by lazy { source(GRID) }
    private val screen by lazy { source(GUIDE_SCREEN) }
    private val diagnostics by lazy { source(DIAGNOSTICS) }
    private val trace by lazy { source(GUIDE_TRACE) }

    @After
    fun tearDown() {
        GuideTrace.reset()
    }

    // ── T5: the ring is bounded and ordered ────────────────────────────────

    @Test
    fun `seventeen notes keep the last sixteen, oldest first`() {
        (1..17).forEach { GuideTrace.note("line $it") }

        assertEquals(
            "sixteen lines, not eight (GUIDE-LEFT F3): the 0.9.206 paste's ring was " +
                "full of five repeated LEFT attempts and the entry-focus line had " +
                "been pushed out, so the first-enter fork could not be read from " +
                "the capture meant to answer it",
            16,
            GuideTrace.lines().size
        )
        assertEquals("line 2", GuideTrace.lines().first())
        assertEquals("line 17", GuideTrace.lines().last())
    }

    @Test
    fun `the ring is TorBoxTrace's shape and cannot be cleared by navigation`() {
        assertTrue(
            "the same bounded ring as the other traces: sixteen, oldest first - " +
                "parity with DetailTrace (GUIDE-LEFT F3)",
            trace.contains("private const val MAX_LINES = 16") &&
                trace.contains("synchronized(lock) { lines = (lines + trimmed).takeLast(MAX_LINES) }")
        )
        assertTrue(
            "and the same two entries: note() and lines()",
            trace.contains("fun note(line: String)") && trace.contains("fun lines(): List<String>")
        )
        assertEquals(
            "only reset() empties it - the report is taken AFTER the repro, from " +
                "Settings, so leaving the guide, toggling the view or switching " +
                "profile must not wipe the evidence",
            0,
            grid.count("GuideTrace.reset(") + screen.count("GuideTrace.reset(") +
                diagnostics.count("GuideTrace.reset(")
        )
        assertTrue(
            "nothing on the guide's exit path clears it either",
            !screen.contains("GuideTrace.reset") && !grid.contains("GuideTrace.reset")
        )
    }

    @Test
    fun `a trim and a blank note cannot waste a line`() {
        GuideTrace.note("  grid entry-focus landed=sidebar group=All attempts=1  ")
        GuideTrace.note("   ")

        assertEquals(
            "the stored line is the log string, trimmed - not an indented copy",
            listOf("grid entry-focus landed=sidebar group=All attempts=1"),
            GuideTrace.lines()
        )
    }

    // ── T1: cold first enter shows the arming, then the grab ───────────────

    @Test
    fun `a cold first enter records the arming and then the grab`() {
        assertTrue(
            "G1/F3a: the arming decision is a line, so a grab that never armed is " +
                "not indistinguishable from one that armed and exhausted",
            arming.contains("GuideTrace.note(\"guide entry armed view=GRID\")")
        )
        assertTrue(
            "F2a: the grab's own line reaches the ring with the identical string",
            grab.contains("val guideLine = \"grid entry-focus landed=\$target attempts=\$attempts\"") &&
                grab.contains("GuideTrace.note(guideLine)")
        )
        assertTrue(
            "and so does the give-up arm - the fork the field report could not name",
            grab.contains(
                "val guideLine = \"grid entry-focus exhausted home=\$home attempts=\$attempts\""
            ) && grab.contains("GuideTrace.note(guideLine)")
        )
        assertTrue(
            "the armed line comes from the screen and the grab line from the grid, in " +
                "that order - which is what makes the paste read as a sequence",
            screen.contains("GuideTrace.note(\"guide entry armed view=GRID\")") &&
                grid.contains("\"grid entry-focus landed=\$target")
        )
    }

    // ── T2: a return from playback names its skip ──────────────────────────

    @Test
    fun `a return from playback records the skip and arms nothing`() {
        assertTrue(
            "G1/F3a: the playback return says why no grab followed",
            arming.contains("if (fromPlayback) {") &&
                arming.contains("GuideTrace.note(\"guide entry skipped fromPlayback=true\")")
        )
        assertTrue(
            "and it is still the same arming rule - the skip does not arm the grab",
            arming.contains(
                "if (guideView == GuideViewMode.GRID && !fromPlayback) gridEntryFocusArmed = true"
            )
        )
        assertTrue(
            "so an entry-focus line after it would be impossible, not merely absent",
            !arming.substringAfter("GuideTrace.note(\"guide entry skipped fromPlayback=true\")")
                .contains("grid entry-focus")
        )
    }

    // ── T3: the stand-down names the guard that fired ──────────────────────

    @Test
    fun `an entry while a handoff is in flight names the guard`() {
        assertTrue(
            "G2/F3b: the guard is resolved, not just tested - an early return with no " +
                "line looked exactly like a grab that never ran",
            grab.contains("val guard = when {") &&
                grab.contains("pendingFocus != null -> \"pendingFocus\"") &&
                grab.contains("pendingSidebarFocus -> \"pendingSidebarFocus\"") &&
                grab.contains("else -> \"returnFocusSignal=\$returnFocusSignal\"")
        )
        assertTrue(
            "and the line carries it, in the spec's shape",
            grab.contains("GuideTrace.note(\"grid entry-focus stood-down \$guard\")")
        )
        assertTrue(
            "the stand-down still consumes the armed flag, as it did",
            grab.indexOf("GuideTrace.note(\"grid entry-focus stood-down")
                .let { standDown ->
                    val consumed = grab.indexOf("onEntryFocusDone()", standDown)
                    standDown > 0 && consumed > standDown
                }
        )
    }

    // ── T4: the sidebar collapse records both halves ───────────────────────

    @Test
    fun `a sidebar collapse records whether the request landed and the hide ran`() {
        val enter = grid.slice("fun enterGrid(rowIndex: Int) {", "fun activateCell(")
        assertTrue(
            "G3/F3c: one line, both halves - the request's result and the hide that " +
                "rides with it",
            enter.contains(
                "GuideTrace.note(\n            \"grid collapse sidebar->channel " +
                    "row=\$rowIndex landed=\$direct sidebarHidden=true\"\n        )"
            )
        )
        assertTrue(
            "the hide is still the call it was, before the line rather than after it",
            enter.indexOf("onSidebarVisibleChange(false)") in 0 until
                enter.indexOf("grid collapse sidebar->channel")
        )
        assertTrue(
            "and the collapsed sidebar still hides through the same callback",
            enter.contains("onSidebarVisibleChange(false)")
        )
    }

    // ── T6: the report, and the logcat that must not change ────────────────

    @Test
    fun `the guide block sits after the guide data and match sections`() {
        val match = diagnostics.indexOf("guideMatchLines(app).forEach { report.appendLine(it) }")
        val guide = diagnostics.indexOf(
            "GuideTrace.lines().forEach { report.appendLine(\"guide: \$it\") }"
        )
        val addons = diagnostics.indexOf("report.appendLine(addonLine(app))")
        assertTrue("the guide match section exists", match >= 0)
        assertTrue("F4a: the focus block follows it", guide > match)
        assertTrue(
            "and precedes the unrelated sections, so data, match and focus read together",
            guide < addons
        )
        assertTrue(
            "the prefix is the plain `guide: ` the spec asks for, one line per note",
            diagnostics.contains("report.appendLine(\"guide: \$it\")")
        )
    }

    @Test
    fun `a session that never opens the guide prints no guide focus lines`() {
        assertEquals("the ring starts empty", emptyList<String>(), GuideTrace.lines())
        assertTrue(
            "and the report appends them per line, so an empty ring adds nothing - " +
                "not even a header that would read as \"the guide never ran\"",
            diagnostics.contains(
                "GuideTrace.lines().forEach { report.appendLine(\"guide: \$it\") }"
            )
        )
        assertFalse(
            "there is no unconditional guide-focus heading",
            diagnostics.contains("guide focus:") || diagnostics.contains("report.appendLine(\"guide: \")")
        )
    }

    @Test
    fun `every GUIDE log site keeps its string and gains the twin`() {
        val logs = grid.count("Log.i(")
        assertEquals(
            "eleven sites, exactly as many as before the spec - the twins are an " +
                "additional sink, not a new set of lines",
            11,
            grid.count("\"GUIDE\"")
        )
        assertEquals(
            "F2a: every one of them notes the same string it logs",
            logs,
            grid.count("GuideTrace.note(guideLine)")
        )
        assertEquals(
            "F2b: the string lives in one place per site, so logcat and the paste " +
                "cannot drift apart",
            logs,
            grid.count("val guideLine = ")
        )
        assertTrue(
            "and the three new lines are trace-only: no logcat volume was added (F5a)",
            !grid.contains("Log.i(\"GUIDE\", \"grid entry-focus stood-down") &&
                !grid.contains("Log.i(\"GUIDE\", \"grid collapse")
        )
        assertTrue(
            "the screen adds no logcat line either, only its trace notes - the two " +
                "arming decisions and, since m10, the list's entry grab (the grid's " +
                "landed/exhausted vocabulary, trace-only so logcat volume is unchanged)",
            !screen.contains("\"GUIDE\",") &&
                screen.count("GuideTrace.note(") == 3
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("missing start marker: $startMarker", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("missing end marker: $endMarker", end > start)
        return substring(start, end)
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

    /** The screen's arming effect and its decision. */
    private val arming: String by lazy {
        screen.slice(
            "var gridEntryFocusArmed by remember",
            "// F4a: nothing reads the grid's span any more"
        )
    }

    /** The grid's entry-focus effect, stand-down included. */
    private val grab: String by lazy {
        grid.slice("LaunchedEffect(entryFocusArmed, rows)", "/**\n     * F1a/F1b: delivers one in-grid move")
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
        const val GRID = "com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt"
        const val GUIDE_SCREEN = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
        const val GUIDE_TRACE = "com/kennyb1201/kbstream/data/reporting/GuideTrace.kt"
    }
}
