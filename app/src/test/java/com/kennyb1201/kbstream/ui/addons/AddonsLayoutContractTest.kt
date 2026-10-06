package com.kennyb1201.kbstream.ui.addons

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Add-ons screen's two clipping bugs, and the crash that lived beside them.
 *
 * Reported from the field, on a set that reports 960dp: the page title rendered
 * as "ADD-O…" and the buttons at the end of the header row ("COPY FROM PROFILE",
 * "BACK") were off the right edge, unreachable; and the copy dialog's heading and
 * its COPY SELECTED / CANCEL row were clipped, because a Dialog clips content
 * taller than the screen instead of scrolling it.
 *
 * The three rules that keep those from coming back:
 *
 *  - the header TITLE keeps its own width - it was the weighted cell that
 *    absorbed whatever the six action buttons left, which on a narrow set is
 *    nothing;
 *  - the action buttons WRAP (FlowRow) rather than being laid out past the end
 *    of the row. There is no sideways scroll on a TV remote, so nothing may be
 *    laid out off the end of one;
 *  - the copy dialog is bounded to the screen and its LIST is the part that
 *    flexes, so every control stays whole;
 *  - the add-on list is keyed by manifest URL over [distinctAddonRows] - the one
 *    identity that is unique per install - not by the manifest id.
 *
 * A Compose test harness is not something this module carries, so the wiring is
 * pinned by reading the source, the same approach the guide's contracts take.
 */
class AddonsLayoutContractTest {

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
        const val SCREEN = "com/kennyb1201/kbstream/ui/addons/AddonsScreen.kt"
        const val COPY = "com/kennyb1201/kbstream/ui/addons/CopyAddonsDialog.kt"
    }

    /** The block between two anchors, both of which must exist. */
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = source.indexOf(end, from)
        assertTrue("missing anchor: $end", to > from)
        return source.substring(from, to)
    }

    private fun header(): String = between(
        source(SCREEN),
        "Row(\n                verticalAlignment = Alignment.CenterVertically,",
        "Spacer(modifier = Modifier.height(14.dp))"
    )

    @Test
    fun `the page title keeps its own width`() {
        val header = header()
        assertTrue(
            "the title column must be laid out at its own width",
            header.contains("Column(modifier = Modifier.padding(end = 16.dp))")
        )
        assertFalse(
            "the title must not be the cell that absorbs the buttons' leftover width",
            header.contains("Column(modifier = Modifier.weight(")
        )
    }

    @Test
    fun `the header buttons wrap instead of running off the row`() {
        val screen = source(SCREEN)
        val header = header()
        assertTrue(
            "the action buttons must be laid out in a wrapping row",
            header.contains("FlowRow(")
        )
        // Every button is inside the FlowRow: the last one closes it.
        val flowStart = header.indexOf("FlowRow(")
        val backButton = header.indexOf("ActionButton(label = \"BACK\", onClick = onBack)")
        assertTrue("BACK must exist", backButton > flowStart)
        assertTrue(
            "the wrapping row must close after BACK",
            header.indexOf('}', backButton) < header.length
        )
        assertTrue(
            "the wrapping row is why ExperimentalLayoutApi is opted into",
            screen.contains("@OptIn(ExperimentalLayoutApi::class)")
        )
        assertTrue(
            "the buttons stay right-aligned while they fit",
            header.contains("Spacer(modifier = Modifier.weight(1f))")
        )
    }

    @Test
    fun `the copy dialog is bounded to the screen and its list flexes`() {
        val copy = source(COPY)
        assertTrue(
            "the dialog must be bounded to the screen height or a Dialog clips it",
            copy.contains(".heightIn(max = maxDialogHeight)")
        )
        assertTrue(
            "the bound must come from the screen",
            copy.contains("LocalConfiguration.current.screenHeightDp.dp - 40.dp")
        )
        assertTrue(
            "the list is the part that gives up space",
            copy.contains(".weight(1f, fill = false)")
        )
        assertTrue(
            "and it is still capped so a tall screen keeps the dialog compact",
            copy.contains(".heightIn(max = 320.dp)")
        )
        assertTrue(
            "the profile chips wrap rather than squeezing",
            copy.contains("FlowRow(")
        )
    }

    @Test
    fun `the add-on list is keyed by the manifest url, not the manifest id`() {
        val screen = source(SCREEN)
        assertTrue(
            "rows must come from the deduplicating rule",
            screen.contains("distinctAddonRows(matching)")
        )
        assertTrue(
            "and be keyed by the identity that is unique per install",
            screen.contains("key = { addon -> addon.manifestUrl }")
        )
        assertFalse(
            "the manifest id is NOT unique: two installs can report the same one",
            screen.contains("key = { addon -> addon.id }")
        )
    }
}
