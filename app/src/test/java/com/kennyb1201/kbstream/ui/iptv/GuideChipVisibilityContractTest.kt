package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selected group's chip must always be on screen, and Up from the channel
 * list must actually land on it.
 *
 * Two cooperating defects lived in `GuideScreen.kt`, both invisible to the JVM
 * suite because they are a key-handler race and a focus requester lookup, not a
 * decision the pure rules can express:
 *
 *  - the chip's `onFocus` was guarded by `if (!moveFocusToChannelList)`, so a
 *    Left/Right that landed during a move-to-list transit left the focused chip
 *    and `selectedGroup` diverged — the row sat scrolled to the focused chip
 *    while the selected group's chip was off-screen;
 *  - the Up-from-list effect never scrolled the chips row and relied on the
 *    keep-in-view effect having already run, so Up could focus a stale,
 *    detached requester and silently no-op.
 *
 * The fix shares one `ensureGroupChipVisible` between the keep-in-view effect
 * and the Up effect. The divergence half is pinned at the state level in
 * [GuideRulesTest] via [chipFocusState]; this class pins the wiring that state
 * helper cannot reach, reading the source the way the other screen contract
 * tests do (`HandoffFilingContractTest`).
 */
class GuideChipVisibilityContractTest {

    private fun source(): String {
        val file = File(findSourceRoot(), GUIDE)
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

    /** The LaunchedEffect block starting at [signature], up to its closing brace. */
    private fun effectBlock(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing effect: $signature", start >= 0)
        val body = src.substring(start + signature.length)
        val end = body.indexOf("\n}")
        return if (end >= 0) body.substring(0, end) else body
    }

    @Test
    fun `the visibility helper owns the unchanged clipping rules`() {
        val src = source()
        assertTrue(
            "the shared helper must exist",
            src.contains("suspend fun ensureGroupChipVisible(group: String) {")
        )
        assertTrue(
            "a start-clipped chip must snap flush with scrollToItem",
            src.contains("groupRowState.scrollToItem(chipIndex.coerceIn(0, groups.lastIndex))")
        )
        assertTrue(
            "an end-clipped chip must nudge in by exactly the overflow",
            src.contains("groupRowState.dispatchRawDelta(endOverflow.toFloat())")
        )
    }

    @Test
    fun `the keep-in-view effect calls the shared helper`() {
        val block = effectBlock(source(), "LaunchedEffect(selectedGroup, groups) {")
        assertTrue(
            "the group-change effect must scroll the selected chip into view",
            block.contains("ensureGroupChipVisible(selectedGroup)")
        )
    }

    @Test
    fun `Up scrolls the chip into view before focusing it`() {
        val block = effectBlock(source(), "LaunchedEffect(pendingGroupChipFocus) {")
        val flagGuard = block.indexOf("if (!pendingGroupChipFocus) return@LaunchedEffect")
        val ensure = block.indexOf("ensureGroupChipVisible(selectedGroup)")
        val missingOut = block.indexOf("if (groups.indexOf(selectedGroup) < 0)")
        val requestFocus = block.indexOf("requester.requestFocus()")
        assertTrue("the flag must short-circuit the effect", flagGuard >= 0)
        assertTrue(
            "Up must scroll before looking for the missing-group early-out",
            ensure >= 0 && ensure > flagGuard && missingOut > ensure
        )
        assertTrue(
            "Up must still request focus on the selected chip",
            requestFocus > ensure
        )
        assertTrue(
            "the retry budget must survive the rewrite",
            block.contains("while (!focused && attempts < 8)")
        )
    }

    @Test
    fun `chip focus routes through the pure selection rule`() {
        val src = source()
        assertTrue(
            "the chip onFocus must apply the focus-wins rule",
            src.contains("val next = chipFocusState(group, moveFocusToChannelList)")
        )
        assertTrue(
            "selectedGroup must follow the focused chip",
            src.contains("selectedGroup = next.selectedGroup")
        )
        assertTrue(
            "a chip focus must cancel any pending move-to-list transit",
            src.contains("moveFocusToChannelList = next.moveFocusToChannelList")
        )
        assertTrue(
            "the old swallow-the-update guard must be gone",
            !src.contains("onFocus = { if (!moveFocusToChannelList) selectedGroup = group }")
        )
    }

    @Test
    fun `a move-to-list transit re-anchors on the list requester, which outlives the swap`() {
        val src = source()
        assertTrue(
            "the channel list must carry its own requester",
            src.contains("val channelListFocusRequester = remember { FocusRequester() }")
        )
        assertTrue(
            "and the list must be the node that requester points at",
            src.contains(".focusRequester(channelListFocusRequester)")
        )
        val block = effectBlock(src, "LaunchedEffect(moveFocusToChannelList, groupedChannels) {")
        assertTrue(
            "the re-anchor must aim at the LIST first: a row's requester is " +
                "destroyed by the group change, so a request aimed at it can be " +
                "granted and cleared in the same frame, stranding focus on the chips",
            block.contains("channelListFocusRequester.requestFocus()")
        )
        assertTrue(
            "and it must still fall back to the first row",
            block.contains("firstChannelFocusRequester.requestFocus()")
        )
    }

    @Test
    fun `chips are unfocusable while a move-to-list transit is pending`() {
        val src = source()
        assertTrue(
            "a chip must refuse focus during the transit, so default focus " +
                "resolution cannot land on the first chip and adopt its group",
            src.contains("canFocus = chipRowAcceptsFocus(moveFocusToChannelList)")
        )
        assertTrue(
            "the gate must route through the pure rule",
            src.contains(".focusProperties {")
        )
    }

    @Test
    fun `a group change from the list cannot send focus up to the chips`() {
        val src = source()
        val start = src.indexOf("state = channelListState")
        assertTrue("the channel list must exist", start >= 0)
        val end = src.indexOf(") {itemsIndexed(", start)
        assertTrue("the channel list's item block must exist", end > start)
        val container = src.substring(start, end)
        assertTrue(
            "the list must be a focus group",
            container.contains(".focusGroup()")
        )
        assertTrue(
            "and must restore focus inside itself: a Left/Right group change " +
                "swaps every keyed row, and without a restorer the lost focus " +
                "escaped into the chips, whose onFocus then reset selectedGroup " +
                "back to the focused chip (the reported snap to All)",
            container.contains(".focusRestorer()")
        )
    }

    private companion object {
        const val GUIDE = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
    }
}
