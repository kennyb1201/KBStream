package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the guide's search has to do on Fire TV, where it behaved differently to
 * Google TV.
 *
 * Two faults, one platform, and neither is arithmetic - so a Compose test
 * harness (which this module does not carry) would not help. Both are pinned at
 * the source instead.
 *
 *  - **Voice search did nothing.** Fire OS ships no speech recognizer; Amazon's
 *    own docs say ACTION_RECOGNIZE_SPEECH "produces an error" there because
 *    "Fire TV does not support this speech recognizer". The chip was drawn
 *    anyway, so a press could only ever do nothing. It is now offered only where
 *    the platform resolves a recognizer.
 *  - **Enter or Back took the results with the keyboard.** Fire OS closes its
 *    keyboard and hands the app the Back as well, so the press arrived as
 *    "close the overlay" and one press lost both the keyboard and the list the
 *    viewer had just searched for. A Back now belongs to the keyboard while its
 *    session is live or just ended, and to the overlay only after that.
 */
class FireTvSearchContractTest {

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
        const val PACKAGE = "com/kennyb1201/kbstream/"
        const val GUIDE = PACKAGE + "ui/iptv/GuideScreen.kt"
        const val SEARCH = PACKAGE + "ui/search/SearchScreenPart2.kt"
        const val FIELD = PACKAGE + "ui/components/KBTextField.kt"
        const val VOICE = PACKAGE + "ui/components/VoiceSearch.kt"
    }

    @Test
    fun `the voice chip is only drawn where a recognizer resolves`() {
        // The capability check itself asks the platform, not the vendor.
        val voice = source(VOICE)
        assertTrue(
            "voiceSearchAvailable must resolve the recognizer intent",
            voice.contains("fun voiceSearchAvailable(context: Context): Boolean")
        )
        assertTrue(
            "and answer from what the package manager actually resolves",
            voice.contains("queryIntentActivities(voiceSearchIntent(context), 0)")
        )
        // Both search boxes guard the chip on it.
        listOf(GUIDE, SEARCH).forEach { path ->
            val scene = source(path)
            assertTrue("$path must ask whether voice search exists", scene.contains("voiceSearchAvailable(voiceContext)"))
            assertTrue("$path must draw the chip only when it does", scene.contains("if (voiceSearchHere) {"))
        }
    }

    @Test
    fun `a Back while the keyboard is up ends the keyboard, not the overlay`() {
        val guide = source(GUIDE)
        assertTrue(
            "the overlay's Back must go through the shared decision",
            guide.contains("val handleSearchBack: () -> Unit = {")
        )
        assertTrue(
            "which asks the pure rule whose Back the press is: the editing echo first, then " +
                "the window's refocus (a Fire TV keyboard is a window the app cannot see)",
            guide.contains("searchBackBelongsToKeyboard(") &&
                guide.contains("(!searchWindowFocused && searchWindowHeld)")
        )
        // The focus it consults is the OVERLAY's, not this activity's: the search
        // overlay is a Dialog (its own window), so the activity's window is
        // unfocused for the whole session and made every Back look like the
        // keyboard's - the overlay could not be closed at all (2026-10-30).
        assertTrue(
            "the focus must be the search overlay window's own, reported up from the dialog",
            guide.contains("var searchWindowFocused by remember(showSearch)") &&
                guide.contains("onWindowFocusChanged = { focused ->")
        )
        assertTrue(
            "and the dialog reports it from inside its own window",
            guide.contains("val dialogWindowFocused = LocalWindowInfo.current.isWindowFocused") &&
                guide.contains("onWindowFocusChanged(dialogWindowFocused)")
        )
        assertTrue(
            "and ends the field's session rather than dismissing",
            guide.contains("searchEndEditingSignal += 1")
        )
        // Both arrival points use it: the guide's own root handler (which sees
        // the press when Fire OS does not give it to the keyboard) and the
        // dialog's dismiss callback.
        assertTrue(
            "the guide's Back handler must route the search case through it",
            guide.contains("showSearch -> {\n                handleSearchBack()")
        )
        assertTrue("the dialog's dismiss must be the same decision", guide.contains("onDismiss = handleSearchBack"))
        assertFalse(
            "the search case must no longer dismiss outright",
            guide.contains("showSearch -> {\n                dismissSearch()")
        )
    }

    /**
     * CLOSE is an explicit press on an explicit button, so it closes.
     *
     * It used to share the Back decision, which meant a session the field no
     * longer had (or a window-focus trace it kept) could read the button press
     * as the keyboard's and leave the overlay on screen with every exit dead
     * (reported 2026-10-30: "the close button doesnt work and back button doesnt
     * work so u get stuck on that screen").
     */
    @Test
    fun `CLOSE closes, whatever the keyboard says`() {
        val guide = source(GUIDE)
        val dialog = guide.substringAfter("private fun ChannelSearchDialog(")
            .substringBefore("private enum class HiddenItemsTab")
        assertTrue(
            "the CLOSE card takes its own callback",
            dialog.contains("onClick = onClose,")
        )
        assertFalse(
            "and no longer the Back decision that can swallow it",
            dialog.contains("onClick = onDismiss,")
        )
        assertTrue(
            "which the screen wires straight to the dismissal, not to the keyboard test",
            guide.contains("onClose = dismissSearch,") &&
                guide.contains("val dismissSearch = {") &&
                guide.contains("showSearch = false")
        )
    }

    /**
     * The search overlay must stay closable, however the window focus reports it.
     *
     * Report (2026-10-30, TV): after backing out of the keyboard in guide
     * search, CLOSE did nothing and Back did nothing - the overlay could not be
     * closed. The decision read THIS ACTIVITY's window focus, and the overlay is
     * a Dialog (its own window), so this activity was unfocused for the whole
     * session and every press was read as the keyboard's.
     */
    @Test
    fun `the overlay cannot be wedged by the window focus it reads`() {
        val guide = source(GUIDE)
        assertTrue(
            "the keyboard arm requires the overlay's window to have held focus first, " +
                "so a focus that never reports cannot pin the overlay open",
            guide.contains("(!searchWindowFocused && searchWindowHeld)")
        )
        assertTrue(
            "and that history is only set by the overlay's own window reporting focus",
            guide.contains("if (searchWindowFocused) searchWindowHeld = true")
        )
        assertFalse(
            "the bare activity-window test that caused it is gone",
            guide.contains("|| !windowFocused")
        )
    }

    /**
     * The keyboard's own window must not end the editing session.
     *
     * Field report (2026-10-23): on Fire TV, opening the guide's search and
     * typing produced a blank box until ENTER was pressed on the keyboard, after
     * which typing worked. The field's session ended on its first blur - and on
     * Fire OS the leanback keyboard is a separate window, so opening it blurs
     * the field. Read-only with a live IME connection swallows every letter;
     * ENTER re-armed the session through the field's key handler, which is why
     * the second attempt worked.
     */
    @Test
    fun `a blur caused by the keyboard window does not end the session`() {
        val field = source(FIELD)
        assertTrue(
            "the field must know whether this app's window is the focused one",
            field.contains("val windowInfo = LocalWindowInfo.current")
        )
        assertTrue(
            "and refuse to end the session on a blur that arrived while another " +
                "window (the keyboard) held the focus",
            field.contains(
                "if (!it.isFocused && !editingWhenLeft && windowInfo.isWindowFocused) {"
            )
        )
        assertFalse(
            "the old unconditional blur rule is what shipped the blank field",
            field.contains("if (!it.isFocused && !editingWhenLeft) editing = false")
        )
        // The Escape hatch stays: an explicit OK/ENTER on a read-only field still
        // re-arms it, which is the behaviour the report proves was reachable.
        assertTrue(
            "and ENTER on a not-yet-editing field still starts the session",
            field.contains("Key.Enter, Key.NumPadEnter -> {") &&
                field.contains("if (!editing) {")
        )
    }

    @Test
    fun `the field reports its session and lets the screen end it`() {
        val field = source(FIELD)
        assertTrue(
            "the field must report its editing session",
            field.contains("LaunchedEffect(editing) { onEditingChanged?.invoke(editing) }")
        )
        assertTrue(
            "and honour a caller asking it to end",
            field.contains("if (endEditingSignal > 0) {")
        )
        assertTrue(
            "a Back that reaches the field must end the sharing field's session too",
            field.contains("if (editing && (!openKeyboardOnFocus || closeKeyboardOnBlur))")
        )
        // And the guide wires all three through the dialog.
        val guide = source(GUIDE)
        assertTrue(guide.contains("onInputEditingChanged: (Boolean) -> Unit,"))
        assertTrue(guide.contains("endEditingSignal: Int,"))
        assertTrue(guide.contains("onEditingChanged = onInputEditingChanged,"))
        assertTrue(guide.contains("endEditingSignal = endEditingSignal"))
    }
}
