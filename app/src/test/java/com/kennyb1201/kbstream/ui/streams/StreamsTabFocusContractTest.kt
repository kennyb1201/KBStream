package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing a provider by moving onto its chip.
 *
 * The chip row filters ONE stream list, so a viewer who D-pads across the chips
 * and watches the list below stay put reads the chip as broken - and the only
 * way to see what a provider offers was to press Select on a chip already under
 * the focus. Two halves of the fix are pinned here: the chip adopts itself on
 * FOCUS (not only on the press), and the list it filters returns to its own
 * head, so the new provider's best-ranked source is what the viewer is looking
 * at instead of a stale scroll offset into a different list.
 */
class StreamsTabFocusContractTest {

    @Test
    fun `the chip adopts its tab on focus, not only on the press`() {
        val chip = body("private fun StreamTabChip(")
        assertTrue(
            "focus on a chip must select the tab the chip stands for",
            chip.contains("onFocusChanged { if (it.isFocused) onSelect() }")
        )
        assertTrue(
            "and a press must still select it",
            chip.contains("onClick = onSelect")
        )
    }

    @Test
    fun `every chip hands its own tab to that one adopt path`() {
        val tabs = body("private fun AddonTabs(")
        assertTrue(
            "\"All\" must adopt the all-add-ons tab",
            tabs.contains("onSelect = { onSelect(ALL_ADDONS_TAB) }")
        )
        assertTrue(
            "and each provider chip its own index",
            tabs.contains("onSelect = { onSelect(index) }")
        )
        assertFalse(
            "no chip may keep the old click-only wiring",
            tabs.contains("onClick =")
        )
    }

    @Test
    fun `switching provider returns the list to its own head`() {
        val screen = read()
        assertTrue(
            "the list's scroll position has to be owned to be resettable",
            screen.contains("val streamListState = rememberLazyListState()")
        )
        assertTrue(
            "and reset on a provider switch - and on a reload, which is a new list too",
            screen.contains("LaunchedEffect(selectedAddonTab, loadedKey)")
        )
        assertTrue(
            "the state must actually drive the list",
            screen.contains("state = streamListState,")
        )
    }

    private fun read(): String {
        val file = File(findSourceRoot(), SCREEN)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /**
     * The body of the top-level function starting at [signature], up to its own
     * closing brace. Delimited on a brace in column 0 because these are
     * top-level private composables, not members of an object.
     */
    private fun body(signature: String): String {
        val src = read()
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = rest.indexOf("\n}")
        return if (end < 0) rest else rest.substring(0, end)
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
        const val SCREEN = "com/kennyb1201/kbstream/ui/streams/StreamsScreen.kt"
    }
}
