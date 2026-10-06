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
            "which asks the pure rule whose Back the press is",
            guide.contains("searchBackClosesKeyboard(")
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
