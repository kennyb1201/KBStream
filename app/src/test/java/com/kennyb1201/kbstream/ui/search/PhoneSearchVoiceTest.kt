package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice search on the phone's search screen.
 *
 * The TV hero and the guide's overlay both draw the shared [VoiceSearchChip];
 * the phone hero drew none, so a phone user's only route to a spoken query was
 * the keyboard's own mic or a system voice deep link. The chip is the same
 * component on purpose - it knows how to report "no recognizer here" and it
 * already carries the app's focus/press treatment - so this pins the wiring
 * rather than a second implementation.
 *
 * @see com.kennyb1201.kbstream.ui.components.VoiceSearchChip
 */
class PhoneSearchVoiceTest {

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

    private fun phone(): String {
        val file = File(sourceRoot, MOBILE)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("start marker missing: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        return text.substring(from, if (to > from) to else text.length)
    }

    /** The screen's search-bar block: the field and everything under it. */
    private fun searchBar() = slice(phone(), "item(key = \"searchbar\")", "val statusText = when {")

    @Test
    fun `the phone hero draws the shared voice chip`() {
        assertTrue(
            "the chip must be the shared component, not a phone-only copy",
            phone().contains("import com.kennyb1201.kbstream.ui.components.VoiceSearchChip")
        )
        assertTrue(
            "and it must sit in the search bar, under the field",
            searchBar().contains("VoiceSearchChip(")
        )
    }

    @Test
    fun `the chip is hidden where no recognizer exists`() {
        assertTrue(
            "whether a recognizer can answer is asked of the platform, not assumed",
            phone().contains(
                "val voiceSearchHere = remember(context) { voiceSearchAvailable(context) }"
            )
        )
        assertTrue(
            "and the chip is drawn only when it can do something",
            searchBar().contains("if (voiceSearchHere) {")
        )
    }

    @Test
    fun `a transcript is submitted exactly like a typed query`() {
        val bar = searchBar()
        assertTrue(
            "the recognized text goes into the field's own path",
            bar.contains("viewModel.onQueryChanged(spoken)")
        )
        assertTrue(
            "and is committed to the recents the way Enter does",
            bar.contains("viewModel.commitSearch()")
        )
        assertTrue(
            "so the results are already loading when the keyboard closes",
            bar.contains("focusManager.clearFocus()")
        )
    }

    @Test
    fun `the phone keeps its own field plumbing`() {
        // The voice chip is an addition, not a replacement: the typed path and
        // the clear button must still be there.
        val bar = searchBar()
        assertTrue(bar.contains("onValueChange = viewModel::onQueryChanged"))
        assertTrue(bar.contains("onClear = {"))
        assertFalse(
            "the chip must not replace the keyboard: there is no recognizer-only branch",
            bar.contains("if (!voiceSearchHere) { KBTextField")
        )
    }

    private companion object {
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/SearchScreenMobile.kt"
    }
}
