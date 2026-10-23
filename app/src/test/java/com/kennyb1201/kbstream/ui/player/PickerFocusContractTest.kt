package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker keeps the D-pad, on both engines.
 *
 * Two halves of one report - "the stream picker in player and those pop up
 * screens sometimes lose focus to the overlay" - and both engines had their own
 * version of each:
 *
 *  - **The panel opened with the D-pad still on the chrome.** The rows are
 *    created in the adapter's first layout pass, which runs AFTER the posted
 *    focus request (a `post()` lands before the next traversal). The Exo
 *    engine's list is focusable itself, so it answered the request by taking
 *    focus - a panel with no highlighted row - and the MPV engine's is not, so
 *    its `requestFocus()` simply failed and left focus on the SOURCES / AUDIO /
 *    SUBTITLES button behind the panel. Either way the first D-pad press acted
 *    on the overlay, not on the picker. The fix is to focus the first row when
 *    it exists and otherwise wait for the layout that places it.
 *  - **A focus that left the panel stayed out.** The picker owns the remote
 *    while it is up, so a focus that escaped - the framework's hand-off when a
 *    row is recycled, or a press whose focus search walked out of the panel onto
 *    the scrim, the bar or the surface - has to come back to the row the viewer
 *    was on. The Exo engine's settings panel has had that guard for a while (the
 *    window's global focus-change listener); its picker and the MPV engine's
 *    picker now have it too.
 *
 * There is no device in CI and neither Activity can be driven from a JVM test,
 * so the structure that fixes them is read out of the source the way this
 * tree's other player contracts are.
 */
class PickerFocusContractTest {

    // ── opening: the first row gets the D-pad, once it exists ───────────

    @Test
    fun `the exo picker parks the D-pad on a row and waits for it to be placed`() {
        val park = functionBody(NATIVE, "private fun parkPickerFocus() {")
        assertTrue(
            "a row that is already laid out takes focus",
            park.contains("pickerList.getChildAt(0)?.requestFocus() == true")
        )
        assertTrue(
            "and while no row exists yet the request waits for the layout that " +
                "places one - an empty, focusable list answers a bare request " +
                "ITSELF, which is the panel with no row highlighted",
            park.contains("pickerList.viewTreeObserver.addOnGlobalLayoutListener(")
        )
        assertTrue(
            "the wait gives up when the panel closed underneath it, and leaves a " +
                "D-pad that is already inside the panel alone",
            normalized(NATIVE).contains(
                "if (!isPickerShowing || pickerList.findFocus() != null) return"
            )
        )
    }

    @Test
    fun `the mpv picker parks the D-pad on a row and waits for it to be placed`() {
        val park = functionBody(MPV, "private fun parkPickerFocus() {")
        assertTrue(
            "a row that is already laid out takes focus (the same first-focusable " +
                "walk the settings panel uses)",
            park.contains("if (focusFirstPill(container)) return")
        )
        assertTrue(
            "and while no row exists yet the request waits for the layout that " +
                "places one: this list is not focusable, so the request it " +
                "replaced failed outright and left the D-pad on the chrome",
            park.contains("container.viewTreeObserver.addOnGlobalLayoutListener(")
        )
        assertTrue(
            "the wait gives up when the panel closed underneath it, and leaves a " +
                "D-pad that is already inside the panel alone",
            normalized(MPV).contains(
                "if (container.findFocus() != null) { " +
                    "container.viewTreeObserver.removeOnGlobalLayoutListener(this) return }"
            )
        )
    }

    @Test
    fun `neither engine asks the list itself for focus any more`() {
        // The bare request is the bug: on ExoPlayer the list took it and left no
        // row highlighted, on MPV it did nothing at all.
        assertFalse(
            "the exo picker must not request focus on the list",
            code(NATIVE).contains("pickerList.requestFocus()")
        )
        assertFalse(
            "nor may mpv's fall back to the list when no row is placed",
            code(MPV).contains("else list.requestFocus()")
        )
    }

    @Test
    fun `both engines open the picker through that park`() {
        val exoOpen = functionBody(NATIVE, "private fun showPicker(mode: PickerMode) {")
        assertTrue(
            "the exo picker's posted focus is the park",
            exoOpen.contains("pickerList.post {") && exoOpen.contains("parkPickerFocus()")
        )
        val mpvOpen = functionBody(MPV, "private fun showPicker(mode: PickerMode) {")
        assertTrue(
            "and so is mpv's",
            mpvOpen.contains("if (!pickerOpen) return@post") &&
                mpvOpen.contains("parkPickerFocus()")
        )
    }

    // ── staying there: a focus that escapes comes back ──────────────────

    @Test
    fun `the exo picker takes focus back when it leaves the panel`() {
        val guard = code(NATIVE)
        assertTrue(
            "the window's focus-change listener is where an escape is caught, " +
                "synchronously, before a posted focus request from the chrome can " +
                "win the race",
            guard.contains(
                "} else if (isPickerShowing) { if (newFocus != null && " +
                    "isDescendantOf(newFocus, pickerContainer)) {"
            )
        )
        assertTrue(
            "and the escape itself hands the D-pad back to the picker",
            guard.contains("} else if (newFocus != null) { reassertPickerFocus() }")
        )
        assertTrue(
            "the hand-back targets the row the viewer was on, then the first row",
            functionBody(NATIVE, "private fun reassertPickerFocus() {")
                .contains("pickerLastFocus") &&
                functionBody(NATIVE, "private fun reassertPickerFocus() {")
                    .contains("pickerList.getChildAt(0)?.requestFocus()")
        )
    }

    @Test
    fun `the mpv picker takes focus back when it leaves the panel`() {
        val guard = code(MPV)
        assertTrue(
            "mpv gets the same window-level guard, gated on its own pickerOpen",
            guard.contains(
                "window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener " +
                    "{ _, newFocus -> if (!pickerOpen || newFocus == null) " +
                    "return@addOnGlobalFocusChangeListener"
            )
        )
        assertTrue(
            "an escape hands the D-pad back to the picker",
            guard.contains("} else { reassertPickerFocus() }")
        )
        assertTrue(
            "targeting the remembered row, then the first row",
            functionBody(MPV, "private fun reassertPickerFocus() {")
                .contains("pickerLastFocus") &&
                functionBody(MPV, "private fun reassertPickerFocus() {")
                    .contains("pickerContainer?.let { focusFirstPill(it) }")
        )
    }

    @Test
    fun `only a row is remembered, never the list or the container`() {
        // Remembering a holder that has nothing highlighted in it would send the
        // next press to a dead target - the state this whole contract is about.
        assertTrue(
            "the exo guard skips the list and the container",
            code(NATIVE).contains(
                "if (newFocus !== pickerList && newFocus !== pickerContainer) { " +
                    "pickerLastFocus = newFocus }"
            )
        )
        assertTrue(
            "and so does mpv's",
            code(MPV).contains(
                "if (newFocus !== container && newFocus !== pickerList) { " +
                    "pickerLastFocus = newFocus }"
            )
        )
    }

    @Test
    fun `the remembered row is dropped whenever the picker closes`() {
        // A recycled row must not be focused after the panel is gone, and a new
        // open starts from the top rather than from the last panel's row.
        assertTrue(
            "the exo picker opens from the top",
            functionBody(NATIVE, "private fun showPicker(mode: PickerMode) {")
                .contains("pickerLastFocus = null")
        )
        assertTrue(
            "and drops the reference as it dismisses",
            functionBody(NATIVE, "private fun dismissPicker() {")
                .contains("pickerLastFocus = null")
        )
        assertTrue(
            "mpv clears it at both ends too",
            functionBody(MPV, "private fun showPicker(mode: PickerMode) {")
                .contains("pickerLastFocus = null") &&
                functionBody(MPV, "private fun dismissPicker() {")
                    .contains("pickerLastFocus = null")
        )
        assertTrue(
            "and when its settings panel takes the screen instead",
            normalized(MPV).contains(
                "pickerOpen = false pickerLastFocus = null " +
                    "pickerContainer?.visibility = View.GONE"
            )
        )
    }

    @Test
    fun `an end-of-episode card clears the picker before it takes the D-pad`() {
        // The guard above is what made the card unreachable: it re-armed on every
        // focus change while the picker was open, so the card's own PLAY NEXT
        // request was answered by a picker row and the panel sat there unable to
        // be driven. The card is the decision once it is up, so the side panels
        // close first - on both engines, for both end panels.
        assertTrue(
            "the exo card dismisses the picker and the settings panel",
            functionBody(NATIVE, "private fun closePanelsForEndCard() {")
                .let { it.contains("dismissPicker()") && it.contains("dismissSettingsPanel()") }
        )
        assertTrue(
            "and it clears the guide and the INFO readout, the other two overlays " +
                "that own the remote",
            functionBody(NATIVE, "private fun closePanelsForEndCard() {")
                .let { it.contains("dismissChannelGuide()") && it.contains("hideInfoPanel()") }
        )
        assertTrue(
            "mpv clears pickerOpen itself: its guard is gated on that flag, so " +
                "hiding the container alone would leave the trap armed",
            functionBody(MPV, "private fun closePanelsForEndCard() {")
                .contains("pickerOpen = false")
        )
        assertTrue(
            "and it hides both side containers with it",
            functionBody(MPV, "private fun closePanelsForEndCard() {")
                .let {
                    it.contains("pickerContainer?.visibility = View.GONE") &&
                        it.contains("settingsContainer?.visibility = View.GONE")
                }
        )

        listOf(
            NATIVE to "private fun showNextUpPanel(targetSeason: Int, targetEpisode: Int) {",
            MPV to "private fun showNextUpPanel(targetSeason: Int, targetEpisode: Int) {",
            NATIVE to "private fun showBecauseYouWatchedPanel() {",
            MPV to "private fun showBecauseYouWatchedPanel() {"
        ).forEach { (path, signature) ->
            val body = functionBody(path, signature)
            val close = body.indexOf("closePanelsForEndCard()")
            assertTrue(
                "$path $signature must close the side panels before it is raised",
                close >= 0
            )
            val focus = body.indexOf("requestFocus()")
            assertTrue(
                "$path $signature must clear them BEFORE it parks the D-pad on the " +
                    "card, or the guard bounces that request straight back",
                focus < 0 || close < focus
            )        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun normalized(path: String): String = readSource(path)
        .lines()
        .joinToString(" ") { it.trim() }

    /**
     * The source with every comment removed and all whitespace collapsed, so an
     * assertion reads the code rather than the prose around it - the guards this
     * contract pins are written with a comment between them, and the KDoc that
     * explains the fix names the call it replaced.
     */
    private fun code(path: String): String = readSource(path)
        .lines()
        .joinToString(" ") { line ->
            val trimmed = line.trim()
            val marker = trimmed.indexOf("//")
            val head = if (marker < 0) trimmed else trimmed.substring(0, marker).trim()
            if (head.startsWith("*") || head.startsWith("/*")) "" else head
        }
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * The text of the member starting at [signature] up to the next member: a
     * line indented by exactly four spaces, which the body's deeper indents do
     * not match.
     */
    private fun functionBody(path: String, signature: String): String {
        val src = readSource(path)
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
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
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
