package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings -> Sync's stacked-word bug.
 *
 * Reported from the field: under LOCAL DATA the word CLEANUP rendered one glyph
 * per line, stacked in a column that overlapped its own value. [SyncStatRow]
 * used to give the LABEL `Modifier.weight(1f)` and measure the VALUE with no
 * constraint. In a Row the un-weighted children are measured first with the
 * whole row available, so a value that has to wrap claims the entire width and
 * starves the weighted label down to a zero-width column - and the sweep detail
 * ("completed - history=0 overrides=0 - ...") is the only sync value long
 * enough to wrap.
 *
 * The rule that keeps it from coming back: the label keeps its own intrinsic
 * width on a single line, and the VALUE is the cell that flexes. A Compose test
 * harness is not something this module carries, so the wiring is pinned by
 * reading the source, the same approach the add-on and guide contracts take.
 */
class SyncStatRowLayoutContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SCREEN = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
    }

    /** The block between two anchors, both of which must exist. */
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = source.indexOf(end, from)
        assertTrue("missing anchor: $end", to > from)
        return source.substring(from, to)
    }

    /** The [SyncStatRow] composable, from its declaration to the next helper. */
    private fun syncStatRow(): String = between(
        source(SCREEN),
        "private fun SyncStatRow(label: String, value: String) {",
        "private fun syncRelativeTime("
    )

    @Test
    fun `the label keeps its own width and cannot stack into a column`() {
        val row = syncStatRow()
        val label = between(row, "text = label,", "text = value,")
        assertFalse(
            "the label must not be the cell that absorbs a wrapping value's leftover width",
            label.contains("weight(1f)")
        )
        assertTrue(
            "the label must be pinned to one line, not wrapped one glyph per line",
            label.contains("maxLines = 1")
        )
    }

    @Test
    fun `the value is the cell that flexes and stays right-aligned`() {
        val row = syncStatRow()
        val value = row.substring(row.indexOf("text = value,"))
        assertTrue(
            "the value must take the remaining width so a long one wraps in its own column",
            value.contains("Modifier.weight(1f)")
        )
        assertTrue(
            "a label and its value still need a gap between them",
            row.contains("Spacer(modifier = Modifier.width(")
        )
        assertTrue(
            "short values stay flush right, as they were before the fix",
            value.contains("textAlign = TextAlign.End")
        )
        assertTrue(
            "TextAlign needs its import or the screen does not compile",
            source(SCREEN).contains("import androidx.compose.ui.text.style.TextAlign")
        )
    }

    @Test
    fun `the sync panel still reports cleanup through the row`() {
        val screen = source(SCREEN)
        assertTrue(
            "the CLEANUP row is the value long enough to wrap, so it stays wired",
            screen.contains("SyncStatRow(\"CLEANUP\", sync.poisonSweepStatus(context))")
        )
        assertTrue(
            "and the LOCAL DATA row above it is unchanged",
            screen.contains("SyncStatRow(\"LOCAL DATA\", localRows ?: \"reading\u2026\")")
        )
    }
}
