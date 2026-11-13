package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LEFT/RIGHT on a link switches the add-on tab (spec 2026-11-12).
 *
 * Before this, LEFT/RIGHT on a source was a dead key: the tab row is a sibling
 * ABOVE the list, so the only way to change source was to walk up to the chips -
 * and the chips adopt on focus, which made that walk a filter change the viewer
 * had not asked for yet.
 *
 * Pinned here: the key is intercepted on the COLUMN (which holds links and
 * nothing else focusable, so it only fires with focus on a link - the tab row
 * and REFRESH keep their own keys), it switches through the SAME name-based
 * selection a chip press uses, the ring is [cycleAddonTab]'s, focus lands on the
 * new tab's first link by the entry focus's retry rule, the new chip keeps
 * itself visible without taking focus, and a one-add-on picker stays dead.
 */
class StreamsTabCycleOnLinkContractTest {

    @Test
    fun `the links column answers LEFT and RIGHT, and only while a link has focus`() {
        val screen = read()
        val column = screen.substringAfter("LazyColumn(\n                state = streamListState,")
            .substringBefore("itemsIndexed(")
        assertTrue(
            "the column intercepts the direction keys on the preview pass",
            column.contains(".onPreviewKeyEvent { event ->") &&
                column.contains("event.type != KeyEventType.KeyDown") &&
                column.contains("Key.DirectionRight -> cycleTab(1)") &&
                column.contains("Key.DirectionLeft -> cycleTab(-1)")
        )
        assertFalse(
            "it must not touch UP: that is the first row's own answer (see " +
                "StreamsUpReturnsToActiveTabContractTest)",
            column.contains("Key.DirectionUp")
        )
        assertFalse(
            "and it is not on the rows, where a direction key would also have to " +
                "mean the ordinary list walk",
            screen.substringAfter("StreamCard(").substringBefore("badgesAbove =")
                .contains("Key.DirectionRight")
        )
    }

    @Test
    fun `a switch sets the same name a chip press sets, so it survives a reorder`() {
        val screen = read()
        val cycle = screen.substringAfter("val cycleTab: (Int) -> Boolean = cycle@{ direction ->")
            .substringBefore("// The one case where auto-play deliberately starts nothing")
        assertTrue(
            "the ring decides, not an ad-hoc +1/-1",
            cycle.contains("val next = cycleAddonTab(selectedAddonTab, direction, addonGroups.size)")
        )
        assertTrue(
            "All is null and an add-on is its NAME - the same values onSelect writes",
            cycle.contains("if (next == ALL_ADDONS_TAB) null") &&
                cycle.contains("else addonGroups.getOrNull(next)?.addonName")
        )
        assertTrue(
            "and it is written through the same state, so the list swaps by the " +
                "same derivation the chips use",
            cycle.contains("selectedAddonName =") &&
                screen.contains("var selectedAddonName by remember(loadedKey) { mutableStateOf<String?>(null) }")
        )
    }

    @Test
    fun `focus lands on the new tab's first link, not on a row that just left`() {
        val screen = read()
        val cycle = screen.substringAfter("val cycleTab: (Int) -> Boolean = cycle@{ direction ->")
            .substringBefore("// The one case where auto-play deliberately starts nothing")
        assertTrue(
            "the claim is the entry focus's requester - the one attached to the first row",
            cycle.contains("firstStreamFocusRequester.requestFocus()")
        )
        assertTrue(
            "retried across frames until it lands, by the same rule as the entry focus",
            cycle.contains("while (!landed && attempts < 8)") &&
                cycle.contains("awaitFrame()") &&
                cycle.contains("runCatching {")
        )
        assertTrue(
            "the request runs from the frame AFTER the selection, so the new row exists",
            cycle.contains("cycleScope.launch {")
        )
    }

    @Test
    fun `the newly selected chip keeps itself visible without taking focus`() {
        val chip = body("private fun StreamTabChip(")
        assertTrue(
            "the selection, not just focus, brings the chip into view - the hand " +
                "stays in the list on the new tab's first link",
            chip.contains("LaunchedEffect(selected) {") &&
                chip.contains("if (selected) runCatching { bringIntoView.bringIntoView() }")
        )
    }

    @Test
    fun `with one add-on the keys stay dead, and refresh is never a stop`() {
        val screen = read()
        val cycle = screen.substringAfter("val cycleTab: (Int) -> Boolean = cycle@{ direction ->")
            .substringBefore("// The one case where auto-play deliberately starts nothing")
        assertTrue(
            "no ring means no switch - cycleAddonTab answers the selection unchanged",
            cycle.contains("if (next == selectedAddonTab) return@cycle false")
        )
        assertTrue(
            "and the key is only consumed when a switch happened, so a single-add-on " +
                "picker keeps its own direction keys",
            cycle.contains("return@cycle false")
        )
        assertTrue(
            "REFRESH is not on the ring: the cycle never names the refresh control",
            !cycle.contains("refreshFocusRequester") &&
                !cycle.contains("onRefresh")
        )
    }

    private fun read(): String {
        val file = File(findSourceRoot(), SCREEN)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The body of the top-level function starting at [signature]. */
    private fun body(signature: String): String {
        val src = read()
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = rest.indexOf("\n}\n")
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
