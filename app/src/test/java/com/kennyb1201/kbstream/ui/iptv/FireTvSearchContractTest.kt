package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertEquals
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
        const val RULES = PACKAGE + "ui/iptv/GuideRules.kt"
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
            "which asks the pure rule whose Back the press is and what it must do",
            guide.contains("searchBackAction(") &&
                guide.contains("keyboardWindowUp = !searchWindowFocused && searchWindowHeld")
        )
        assertTrue(
            "the rule is the one that asks the echo windows first, then the window's refocus " +
                "(a Fire TV keyboard is a window the app cannot see)",
            source(RULES).contains("searchBackBelongsToKeyboard(")
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
            guide.contains("keyboardWindowUp = !searchWindowFocused && searchWindowHeld")
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

    /**
     * The Back that closes the Fire TV keyboard must not end the field's session.
     *
     * Field report (2026-11-27, Fire Stick): in guide search, Back with the
     * keyboard up left the search bar blank and typing dead while the results
     * were still on screen - so the query was never cleared, the FIELD was
     * broken. Fire OS closes its keyboard AND hands the app the press, and the
     * screen ended the session on that press, leaving a read-only field under a
     * live IME: the box reads blank and every letter lands nowhere. The session
     * now ends only once the close is observed (the overlay's window regaining
     * focus), when no IME is attached any more.
     */
    @Test
    fun `the Fire TV Back that closes the keyboard keeps the query, the field and the results`() {
        val search = SearchOverlayModel()
        assertTrue(search.fieldEditable)
        assertTrue(search.resultsShown)

        // The Back Fire OS delivers: it closes the keyboard AND reaches the app.
        search.back()

        assertEquals("the query was never the problem and must stay", "bbc news", search.query)
        assertTrue("so the results stay on screen", search.resultsShown)
        assertEquals("and the bar shows the query", "bbc news", search.fieldText)
        assertTrue(
            "and the field is still editable, not read-only behind a live IME",
            search.fieldEditable
        )
        assertEquals(
            "the session must not end while the IME is still connected",
            0,
            search.endEditingSignals
        )
        search.type("x")
        assertEquals(
            "letters from that keyboard must land in the field",
            "bbc newsx",
            search.query
        )

        // The close lands a beat later: the overlay's window gets focus back.
        search.now += 120L
        search.keyboardCloses()
        assertEquals("the session ends once the keyboard is really gone", 1, search.endEditingSignals)
        assertFalse(
            "so the field is read-only again, which is what closeKeyboardOnBlur promises",
            search.fieldEditable
        )
        assertEquals("but it still displays the query, never a blank bar", "bbc newsx", search.fieldText)
        assertTrue("with the results still up", search.resultsShown)

        // The viewer's second press, keyboard gone, closes the search.
        search.now += 2_000L
        search.back()
        assertTrue("the second Back closes the overlay", search.closed)
        assertEquals("and only now clears the query", "", search.query)
    }

    /**
     * The session's end is deferred to the observed keyboard close.
     *
     * Neither the echo windows nor the field's blur rule is the fix: the first
     * says a keyboard closed a moment ago (never that one is still open), and
     * the second is what keeps the field read-only once the keyboard is gone.
     */
    @Test
    fun `the keyboard's press only ends the session once the close is observed`() {
        val guide = source(GUIDE)
        assertTrue(
            "the keyboard-up press is a decision of its own, not a session end",
            guide.contains("SearchBackAction.KeepKeyboardSession -> Unit")
        )
        assertTrue(
            "and only a press with the keyboard gone ends the session",
            guide.contains("SearchBackAction.EndKeyboardSession -> {")
        )
        assertTrue(
            "which the overlay window regaining focus does, now that the IME is gone",
            guide.contains("if (searchInputEditing) {")
        )
        assertTrue(
            "and it is guarded on a loss that was actually observed",
            guide.contains("searchWindowFocused && windowLostAt != 0L ->")
        )
        // The timing guards and the read-only policy are unchanged by this fix.
        val rules = source(RULES)
        assertTrue(rules.contains("internal const val SEARCH_IME_ECHO_MS = 700L"))
        assertTrue(rules.contains("internal const val SEARCH_REFOCUS_ECHO_MS = 1000L"))
        assertTrue(
            "the field still goes read-only once focus leaves it after the keyboard is gone",
            source(FIELD).contains(
                "if (!it.isFocused && !editingWhenLeft && windowInfo.isWindowFocused) {"
            )
        )
        assertTrue(
            "and the guide's field still declares that policy",
            guide.contains("closeKeyboardOnBlur = true,")
        )
    }

    /**
     * The TCL / Google TV path is the regression control.
     *
     * There the leanback IME sits inside the app's own window and swallows
     * Back, so the press that closes it never reaches this screen. The first
     * press the app does see arrives with the keyboard gone and no session to
     * end - and it must still close the search, exactly as before.
     */
    @Test
    fun `the TCL path still closes the search on the one Back the app sees`() {
        val search = SearchOverlayModel().apply {
            windowFocused = true       // same window: no keyboard window over the overlay
            editing = false             // the IME's close ended the session on its way out
            changedAt = 1_000L
            now = 30_000L
        }
        search.back()
        assertTrue("one Back closes the overlay", search.closed)
        assertEquals("and clears the query", "", search.query)
        assertEquals("with no session left to end", 0, search.endEditingSignals)
    }

    /**
     * The other order Fire OS can deliver the two halves in: the session has
     * already ended and the press arrives at the overlay. It is still the
     * keyboard's, so the results must survive it.
     */
    @Test
    fun `a Back that trails the session's end still leaves the results up`() {
        val search = SearchOverlayModel().apply {
            editing = false
            changedAt = 9_800L
            now = 10_000L
            windowFocused = true
        }
        search.back()
        assertFalse("the overlay stays open", search.closed)
        assertEquals("with the query intact", "bbc news", search.query)
        assertEquals("and exactly the one session end it was", 1, search.endEditingSignals)
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

/**
 * The search overlay's state, reduced to what one Back press can change.
 *
 * It mirrors the state the screen keeps around `searchInputEditing`,
 * `searchWindowFocused` / `windowLostAt` / `windowRefocusedAt` and applies the
 * same production decision ([searchBackAction]), so the Fire TV sequence can be
 * played out on the JVM - this module carries no Compose test harness.
 */
private class SearchOverlayModel {
    var query = "bbc news"

    /** KBTextField's session. The single-line field is `readOnly = !editing`. */
    var editing = true

    /** The overlay's OWN window. False while the TV keyboard window is in front. */
    var windowFocused = false
    var windowHeld = true
    var windowLostAt = 1_000L
    var windowRefocusedAt = 0L

    /** When the field last reported its session changing (onEditingChanged). */
    var changedAt = 2_000L
    var now = 10_000L
    var endEditingSignals = 0
    var closed = false

    /**
     * What the viewer sees in the bar.
     *
     * A read-only field under a live IME is the reported blank bar (2026-11-27,
     * Fire Stick): it reads blank and letters land nowhere, while the results -
     * derived from the query, which was never cleared - stay on screen.
     */
    val fieldText: String get() = if (!editing && !windowFocused) "" else query
    val fieldEditable: Boolean get() = editing
    val resultsShown: Boolean get() = query.isNotBlank()

    /** One Back, decided exactly as the guide decides it. */
    fun back() {
        when (
            searchBackAction(
                editing = editing,
                sinceEditingChangeMs = now - changedAt,
                windowLostAt = windowLostAt,
                windowRefocusedAt = windowRefocusedAt,
                keyboardWindowUp = !windowFocused && windowHeld,
                nowMs = now
            )
        ) {
            SearchBackAction.KeepKeyboardSession -> Unit
            SearchBackAction.EndKeyboardSession -> endSession()
            SearchBackAction.CloseSearch -> {
                closed = true
                query = ""
            }
        }
    }

    /** Letters from the still-connected keyboard: they only land in a live session. */
    fun type(text: String) {
        if (editing) query += text
    }

    /** The keyboard window closing: the overlay's window gets its focus back. */
    fun keyboardCloses() {
        windowFocused = true
        windowRefocusedAt = now
        if (editing) endSession()
    }

    private fun endSession() {
        endEditingSignals += 1
        editing = false
        changedAt = now
        windowLostAt = 0L
        windowRefocusedAt = 0L
    }
}
