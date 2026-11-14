package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connect dialogs' username row, wired to a measurement (spec 2026-11-14,
 * amendment 3b).
 *
 * This test used to pin the opposite: SAVE USERNAME weighted, CLEAR unweighted,
 * every label single-line. That shipped and clipped the last E of SAVE USERNAME
 * mid-glyph, because a weighted button is only as wide as the slack the row has
 * left and the row at Kenny's font scale had none. The row is now one shared
 * composable that measures its two labels at the device's scale and stacks when
 * both do not fit, so the pins move with it: the dialogs must not build the row
 * by hand again, and the shared row must keep the measurement, the stack, the
 * tighter phone padding and the no-wrap guards.
 *
 * Pinned by reading the sources: a Compose layout cannot be measured from this
 * module's JVM tests, and the arithmetic itself is covered by
 * [UsernameActionLayoutTest].
 */
class AnimeConnectDialogButtonWrapContractTest {

    private val aniList by lazy { source(ANILIST) }
    private val kitsu by lazy { source(KITSU) }
    private val row by lazy { source(USERNAME_ROW) }
    private val layout by lazy { source(USERNAME_LAYOUT) }

    @Test
    fun `both dialogs hand the username row to the measured layout`() {
        listOf(aniList to "AniList", kitsu to "Kitsu").forEach { (dialog, name) ->
            assertTrue(
                "$name: the username row must be the shared one - a hand-built row " +
                    "is how it was starved twice",
                dialog.contains("AnimeUsernameActions(\n                phone = phone,")
            )
            assertFalse(
                "$name: no label of its own in that row any more",
                dialog.contains("text = \"SAVE USERNAME\"") || dialog.contains("text = \"CLEAR\"")
            )
            // CLEAR's behaviour is unchanged: clear the field, clear the saved name.
            assertTrue(
                "$name: CLEAR still clears the field and the saved username",
                dialog.contains("onClear = {\n                    keyboard?.hide()\n                    username = \"\"")
            )
        }
    }

    @Test
    fun `the shared row measures its labels before it lays them out`() {
        assertTrue("the labels are measured at the device's font scale", row.contains("rememberTextMeasurer()"))
        assertTrue("against the width the dialog actually has", row.contains("BoxWithConstraints("))
        assertTrue(
            "and the fit is the tested arithmetic, not a guess about font sizes",
            row.contains("UsernameActionLayout.fitsSideBySide(")
        )
        assertTrue(
            "labels the width in dp, so the measurement and the padding are one unit",
            row.contains("labelWidthDp(label")
        )
    }

    @Test
    fun `a row that cannot hold both labels stacks instead of clipping`() {
        val sideBySide = row.indexOf("if (sideBySide)")
        assertTrue("the row branches on the measurement", sideBySide >= 0)
        val fitsBranch = row.indexOf("Row(", sideBySide)
        val stackBranch = row.indexOf("Column(", sideBySide)
        assertTrue("both labels fit: the row, as before", fitsBranch in (sideBySide + 1) until stackBranch)
        assertTrue(
            "do not fit: SAVE USERNAME full width above CLEAR full width, which " +
                "cannot clip at any font scale (amendment 3b(c))",
            stackBranch > fitsBranch
        )
        val stacked = row.substring(stackBranch)
        assertTrue(
            "stacked: SAVE USERNAME takes the whole width",
            stacked.contains("onClick = onSave,\n                    modifier = Modifier.fillMaxWidth()")
        )
        assertTrue(
            "stacked: and so does CLEAR, below it",
            stacked.contains("onClick = onClear,\n                    modifier = Modifier.fillMaxWidth()")
        )
    }

    @Test
    fun `no label in the row can wrap or be cut mid-glyph`() {
        assertTrue(row.contains("maxLines = 1"))
        assertTrue(row.contains("softWrap = false"))
        assertTrue(
            "a label that outgrows its button ellipsises; the measurement means " +
                "this is a backstop, never the drawn state (amendment 3b(b))",
            row.contains("overflow = TextOverflow.Ellipsis")
        )
        assertTrue(
            "and a decided-width button constrains its label to the button's own width",
            row.contains("if (fillsButton) Modifier.fillMaxWidth() else Modifier")
        )
    }

    @Test
    fun `the tight row is the phone's and the TV keeps the padding it shipped`() {
        assertTrue(
            "12.dp on the phone is the room the clip needed",
            layout.contains("const val PHONE_PADDING_DP = 12f")
        )
        assertTrue(
            "TV is verified unchanged (S3), so its row keeps 18.dp",
            layout.contains("const val TV_PADDING_DP = 18f")
        )
        assertTrue(
            "and the row asks the layout which surface it is on",
            row.contains("UsernameActionLayout.paddingDp(phone)")
        )
        assertTrue(
            "TV keeps its start-aligned label as well as its padding - the " +
                "constraint is only for the phone's share of the row (S3)",
            row.contains("fillsButton = phone")
        )
    }

    @Test
    fun `every other button label in both dialogs is still guarded against wrapping`() {
        // Three labels each now - DISCONNECT, CONNECT/CONNECTING, CANCEL - because
        // the username row's two moved into the shared composable.
        assertEquals(3, countOf(aniList, "softWrap = false"))
        assertEquals(3, countOf(kitsu, "softWrap = false"))
        // The row's own labels are guarded once, where they are written.
        assertEquals(1, countOf(row, "softWrap = false"))
    }

    @Test
    fun `the fix is not a surface fork - TV draws the same shared row`() {
        assertFalse(
            "the row must not branch on the surface: TV has room, the phone gets " +
                "the measured fit, and both draw the same buttons",
            row.contains("if (phone)")
        )
        assertTrue(
            "and every button is still the shared one, raised with phone = phone",
            row.contains("AnimeDialogButton(\n                    phone = phone,")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun source(fileName: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/ui/settings/$fileName")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $fileName")
    }

    private fun countOf(text: String, token: String): Int {
        var found = 0
        var from = text.indexOf(token)
        while (from >= 0) {
            found++
            from = text.indexOf(token, from + token.length)
        }
        return found
    }

    private companion object {
        const val ANILIST = "AniListConnectDialog.kt"
        const val KITSU = "KitsuConnectDialog.kt"
        const val USERNAME_ROW = "AnimeUsernameActions.kt"
        const val USERNAME_LAYOUT = "UsernameActionLayout.kt"
    }
}
