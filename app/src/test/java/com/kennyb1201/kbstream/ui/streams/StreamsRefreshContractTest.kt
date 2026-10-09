package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's refresh control, and the cut it shares a row with.
 *
 * Two reported faults, pinned where they can be seen:
 *
 *  - **"the All chip is clipped a little on the left"** - the chip grows by
 *    `KBFocusChip` and draws a `KBFocusGlowSmall` glow, both OUTSIDE its own
 *    bounds, while the row's scroll container clips at its edge. So the chip
 *    row opens on lost its left border and glow to a flat cut. The room has to
 *    be INSIDE the clip, which is why padding the row from the outside never
 *    fixes it: a parent's padding sits outside the clip, and the chip still
 *    loses the glow there. (The theme carries the same fix for the discover and
 *    Library chip rows - see `KBFocusChipInset`.)
 *  - **"can we add a refresh button to the left of the All chip"** - the row
 *    was gated on `addonGroups.size > 1`, so the control would have been hidden
 *    in exactly the states a viewer reaches for it in: one add-on answering,
 *    and none at all.
 *
 * There is no TV in CI and this module has no Compose UI harness for a screen
 * that wants a ViewModel and a network round trip, so the structure that fixes
 * them is read out of the source the way this tree's other UI contracts are.
 */
class StreamsRefreshContractTest {

    private val screen: String by lazy { read(SCREEN) }

    private val viewModel: String by lazy { read(VIEW_MODEL) }

    @Test
    fun `the refresh chip comes first, so it sits to the left of the All chip`() {
        val tabs = body(screen, "private fun AddonTabs(")
        val refresh = tabs.indexOf("StreamRefreshChip(")
        val all = tabs.indexOf("StreamTabChip(")
        assertTrue("the row must carry the refresh chip", refresh >= 0)
        assertTrue("and the tabs must still be there", all >= 0)
        assertTrue(
            "the refresh control is the row's FIRST chip - the request was 'to the left of the All chip'",
            refresh < all
        )
    }

    @Test
    fun `the row is drawn whatever the add-ons did, so refresh is never hidden`() {
        val call = slice(screen, "AddonTabs(", "onSelect = { selectedAddonTab = it }")
        assertTrue(
            "the All tab and the provider tabs still appear only when there is more than one " +
                "add-on to choose between (a lone All tab does nothing)",
            call.contains("addonNames = if (addonGroups.size > 1)") && call.contains("emptyList()")
        )
        assertTrue(
            "but the ROW is no longer gated on that count: with one add-on answering there is " +
                "still a refresh control",
            call.contains("onRefresh = {")
        )
        assertTrue(
            "the chip only says REFRESHING for a refresh the viewer asked for - the first load " +
                "starts with isLoading true, and the control must not announce a press nobody made",
            call.contains("refreshing = refreshed && isLoading,")
        )
        assertTrue(
            "and the press re-asks for sources rather than only clearing a flag",
            call.contains("refreshed = true") && call.contains("viewModel.refresh()")
        )
    }

    @Test
    fun `the chip row's inset is inside the scroll, or the first chip is cut flat again`() {
        val tabs = body(screen, "private fun AddonTabs(")
        val offset = tabs.indexOf(".offset(x = -KBFocusChipInset)")
        val scroll = tabs.indexOf(".horizontalScroll(rememberScrollState())")
        val inset = tabs.indexOf(".padding(horizontal = KBFocusChipInset)")
        assertTrue("the cancel-outside offset must be on the row", offset >= 0)
        assertTrue("the row still scrolls", scroll >= 0)
        assertTrue("and the inset must be declared", inset >= 0)
        assertTrue(
            "the inset must sit AFTER horizontalScroll: that is what puts it inside the clip, " +
                "which is the only place it can hold the focused chip's growth and glow",
            inset > scroll
        )
        assertTrue(
            "and the negative offset must come BEFORE the scroll, so the row's left alignment " +
                "is exactly what it was while the room is available inside",
            offset < scroll
        )
    }

    @Test
    fun `the refresh chip is an action, not a tab`() {
        val chip = body(screen, "private fun StreamRefreshChip(")
        assertTrue(
            "it fires on the press",
            chip.contains("KBCard(onClick = onRefresh)")
        )
        assertFalse(
            "and NOT on focus - adopting on focus is what makes the tabs beside it a picker, " +
                "and a control that re-fetches the list as the D-pad crosses it is a bug",
            chip.contains("onFocusChanged")
        )
        assertTrue(
            "an icon in material3 needs an explicit tint, or it renders in its dark default",
            chip.contains("tint = KBAccent")
        )
        assertTrue(
            "the control is the refresh SYMBOL alone - the same mark the add-on screen's " +
                "refresh buttons carry - and no \"REFRESH\" word beside the tab chips",
            chip.contains("Icons.Filled.Refresh") && !chip.contains("Text(")
        )
        assertTrue(
            "with no label to read, the symbol alone has to carry its meaning",
            chip.contains("contentDescription = \"Refresh sources\"")
        )
        assertTrue(
            "and the mark turns only while a refresh the viewer asked for is running, " +
                "so the button still shows it is working now that the word is gone",
            chip.contains("if (refreshing) rememberRefreshSpin() else null") &&
                chip.contains("rotationZ = spin?.value ?: 0f")
        )
    }

    @Test
    fun `a manual refresh never launches the player`() {
        assertTrue(
            "the press suppresses auto-select for the rest of this picker's life",
            screen.contains("val autoSelectSuppressed = suppressAutoSelect || refreshed")
        )
        assertTrue(
            "and the auto-select effect reads that, not the raw route flag - a reload runs the " +
                "same isLoading/streams transition auto-play fires on",
            screen.contains("!autoSelectSuppressed && !manualPick")
        )
        assertFalse(
            "the route flag alone must no longer gate the effect",
            screen.contains("!suppressAutoSelect && !manualPick")
        )
    }

    @Test
    fun `refresh re-asks the ViewModel for the target it last loaded`() {
        assertTrue(
            "the request is remembered when it is made",
            viewModel.contains("lastRequest = StreamsRequest(contentType, streamId, runtimeMinutes)")
        )
        val refresh = body(viewModel, "fun refresh()")
        assertTrue(
            "and the button re-runs it rather than reading a cache",
            refresh.contains("val request = lastRequest ?: return") &&
                refresh.contains("load(request.contentType, request.streamId, request.runtimeMinutes)")
        )
        assertTrue(
            "a second load supersedes the first, so a repeated press cannot interleave two " +
                "fetches' sources into one list",
            viewModel.contains("loadJob?.cancel()")
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return source.substring(start, end)
    }

    /**
     * The body of the top-level function or member starting at [signature], up
     * to its own closing brace at column 0.
     */
    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = source.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = source.substring(brace + 1)
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
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/streams/StreamsViewModel.kt"
    }
}
