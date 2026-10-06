package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the guide does when the row the viewer is on disappears because they
 * just hid it.
 *
 * Reported from the field: hiding a channel from its row menu threw the viewer
 * back to the top of the list, with D-pad focus landing up on the group chips
 * row instead of on a channel. Two independent resets caused it - the
 * selection was re-seated on the list's FIRST channel (which scrolls the list
 * to the top) and Compose's focus followed the removed row out of the
 * composition - so this pins both halves of the answer:
 *
 *  - the removed row hands its place to the row that inherits it, IN the same
 *    group, without touching the viewport (see [inheritedChannelRowIndex]);
 *  - the settle effect keeps a selection that survived the change, and only
 *    scrolls when the list genuinely has to move (a group switch, a restore, a
 *    typed channel number).
 *
 * A Compose test harness is not something this module carries, so the wiring is
 * pinned by reading the source - the same approach the guide's other contracts
 * take.
 */
class GuideHideChannelFocusContractTest {

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
        const val SCREEN = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
    }

    /** The removal effect: the one keyed on the channel list, after its books. */
    private fun removalEffect(screen: String): String {
        val start = screen.indexOf("LaunchedEffect(groupedChannels) {", screen.indexOf("previousGroupedChannels"))
        assertTrue("the removal effect must exist", start >= 0)
        // Ends at the next declaration (the chip-scroll helper and its note),
        // not at the next effect: the helper in between scrolls the CHIPS row
        // and has nothing to do with this effect's viewport.
        val end = screen.indexOf("// Scroll the chips row so", start)
        assertTrue("the removal effect must end", end > start)
        return screen.substring(start, end)
    }

    /** The settle effect: the one keyed on the channel membership. */
    private fun settleEffect(screen: String): String {
        val start = screen.indexOf("LaunchedEffect(groupedChannelMembership, membershipBump) {")
        assertTrue("the settle effect must exist", start >= 0)
        val end = screen.indexOf("LaunchedEffect(", start + 10)
        assertTrue("the settle effect must end", end > start)
        return screen.substring(start, end)
    }

    @Test
    fun `a removed row hands its place to the row that inherits it`() {
        val removal = removalEffect(source(SCREEN))
        assertTrue(
            "the removed row's index in the list that was on screen must drive the pick",
            removal.contains("inheritedChannelRowIndex(")
        )
        assertTrue(
            "the list as it was must be kept for that lookup",
            removal.contains("previousGroupedChannels")
        )
    }

    @Test
    fun `the viewport is left alone when a row is hidden`() {
        val removal = removalEffect(source(SCREEN))
        assertTrue("an empty list must not wedge the effect", removal.contains("?: return@LaunchedEffect"))
        assertFalse(
            "the inherited row is already where the removed one was - scrolling to it IS the jump",
            removal.contains("scrollToItem(")
        )
    }

    @Test
    fun `focus is handed to the inherited row instead of escaping to the chips`() {
        val removal = removalEffect(source(SCREEN))
        assertTrue(
            "the inherited row's own requester must be used",
            removal.contains("channelRowFocusRequesters[key]")
        )
        assertTrue("focus must actually be requested", removal.contains("requestFocus()"))
    }

    @Test
    fun `a group change is not a removal`() {
        val screen = source(SCREEN)
        assertTrue(
            "a different group's list must be left to the group switch (and a chip focus walk)",
            removalEffect(screen).contains("if (lastSettledGroup != selectedGroup) return@LaunchedEffect")
        )
        assertFalse(
            "the old unconditional reset to the first channel must be gone",
            screen.contains("val currentStillExists = groupedChannels.any")
        )
    }

    @Test
    fun `a surviving selection is kept, and the list only scrolls when it has to`() {
        val settle = settleEffect(source(SCREEN))
        assertTrue(
            "a selection that survived the change must be kept",
            settle.contains("?: keptId")
        )
        assertTrue(
            "the scroll must be conditional on the list actually moving",
            settle.contains("if (keptId == null) {")
        )
        assertTrue(
            "a group switch must still settle on the first row",
            settle.contains("?: groupedChannels.firstOrNull()?.channel?.id")
        )
        assertTrue(
            "a group switch must still scroll to it",
            settle.contains("channelListState.scrollToItem(")
        )
        assertTrue(
            "a pending jump (restore, typed number) must still win",
            settle.contains("val target = pending?.let { key ->")
        )
    }
}
