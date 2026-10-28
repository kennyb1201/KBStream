package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-press menu's focus-ring clearance.
 *
 * The focused row's ring is drawn outside the row's own layout box - it scales
 * to [com.kennyb1201.kbstream.ui.theme.KBFocusRow] and adds a 2dp accent border
 * on top - while the menu's rows live in a scrolling Column that clips at its
 * own edge. The panel's 22dp padding therefore was not enough: on the field
 * report the bottom of the focused "Hide" row's ring was cut off flush with the
 * menu's rounded bottom edge.
 *
 * The fix is the same trailing spacer the sports hub's list uses, and it is
 * invisible unless the last row is focused - which is exactly the kind of
 * one-line wiring a later layout change deletes without noticing, so its
 * PLACEMENT is what is pinned here: last child of the menu's Column, after the
 * rows, with nothing composed after it.
 */
class PosterContextMenuFocusInsetContractTest {

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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

    @Test
    fun `the menu gives the last row's focus ring room below it`() {
        val menu = readSource(MENU)

        assertEquals(
            "the clearance must be composed once, below the action rows",
            1,
            menu.split(SPACER).size - 1
        )

        val spacerAt = menu.indexOf(SPACER)
        val rowsAt = menu.indexOf("rows.forEachIndexed { index, action ->")
        assertTrue("the action rows must still be composed", rowsAt > 0)
        assertTrue(
            "the clearance must sit AFTER the last row, not above the list",
            spacerAt > rowsAt
        )

        // And it must be the last child of the menu's Column: from the end of
        // the clearance up to the Column's own closing brace there is nothing
        // but the rest of that line, so no later row can be composed below the
        // room that was made for one.
        //
        // The boundary is the Column's close - the first line that is a lone
        // "}" - rather than the next sibling widget: the menu hosts the list
        // picker and the rating dialog BEHIND the Column, and an anchor on either
        // of those would let a row composed inside the Column go unnoticed.
        val tail = menu.substring(spacerAt + SPACER.length)
            .lines()
            .drop(1)
            .takeWhile { it.trim() != "}" }
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
            .trim()
        assertTrue(
            "nothing may be composed after the clearance: found '${tail.take(60)}'",
            tail.isEmpty()
        )
    }

    @Test
    fun `the spacer is the shared focus-row inset, not a local number`() {
        val menu = readSource(MENU)
        assertTrue(
            "KBFocusRowInset must be imported from the theme",
            menu.contains("import com.kennyb1201.kbstream.ui.theme.KBFocusRowInset")
        )
        assertTrue(
            "the inset's own standard is 16dp - the 12dp glow plus the growth of KBFocusRow",
            readSource(THEME).contains("val KBFocusRowInset = 16.dp")
        )
    }

    @Test
    fun `the row layout the clearance exists for is unchanged`() {
        val menu = readSource(MENU)
        // The ring itself: rows still scale to KBFocusRow with the 2dp accent
        // border that overflows their bounds.
        assertTrue(menu.contains("focusedScale = KBFocusRow"))
        assertTrue(menu.contains("BorderStroke(2.dp, KBAccent)"))
        // The list's own spacing and the panel's visual inset, untouched: the
        // spacer adds overflow room rather than changing either.
        assertTrue(menu.contains("Spacer(modifier = Modifier.height(8.dp))"))
        assertTrue(menu.contains(".padding(22.dp)"))
    }

    private companion object {
        const val MENU = "com/kennyb1201/kbstream/ui/components/PosterContextMenu.kt"
        const val THEME = "com/kennyb1201/kbstream/ui/theme/Theme.kt"
        const val SPACER = "Spacer(modifier = Modifier.height(KBFocusRowInset))"
    }
}
