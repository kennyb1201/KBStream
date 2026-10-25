package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playlist Setup's "one per line" fields, on both surfaces.
 *
 * Report (2026-10-25, Kenny): the Extra EPG Sources field says "one per line",
 * and pressing Enter at the end of the URL reloaded nothing and started no
 * second line. The label was right about the intent and the field could not
 * deliver it: both `KBTextField` variants were single-line with Done as the IME
 * action, so Enter could only ever finish the session.
 *
 * The fix is a `multiline` variant on each component, taken by those two fields
 * and no others. That is wiring, and the module has no Compose UI harness - so
 * this reads it out of the source the way the rest of the app's behavioural
 * contracts do (see FireTvSearchContractTest, MobileTouchTwinContractTest), and
 * the RULE those keys drive is run for real in [KBMultilineFieldCaretTest].
 *
 * The assertions worth having are the ones a reviewer would otherwise have to
 * re-derive on both platforms by hand: that Enter cannot end a multiline
 * session, that the D-pad moves the caret before it moves focus, that the
 * single-value fields did NOT become lists, and that a `;`-separated list still
 * parses (nothing about the import changed, so an old value must keep working).
 */
class KBMultilineFieldWiringContractTest {

    private val tv by lazy { read("com/kennyb1201/kbstream/ui/components/KBTextField.kt") }

    private val touch by lazy {
        read("com/kennyb1201/kbstream/ui/components/touch/TouchTextField.kt")
    }

    private val guide by lazy { read("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt") }

    private val guideMobile by lazy {
        read("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt")
    }

    private val guideFiles by lazy {
        read("com/kennyb1201/kbstream/data/iptv/GuideFiles.kt")
    }

    private val viewModel by lazy {
        read("com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt")
    }

    // ---- TV ---------------------------------------------------------------

    @Test
    fun `the TV field only goes multiline when the caller asks`() {
        assertTrue(
            "the variant has to default off, or every field in the app becomes a list",
            tv.contains("multiline: Boolean = false")
        )
        assertTrue("multiline is not single-line", tv.contains("singleLine = false,"))
        assertTrue(
            "and the single-line branch is still there, unchanged",
            tv.contains("singleLine = true,")
        )
        assertTrue(
            "a list field must not advertise Done as its IME action",
            tv.contains("imeAction = ImeAction.Default,")
        )
        assertTrue(
            "a real Done from the IME still ends the session, as the field promises",
            tv.contains("keyboardActions = KeyboardActions(onDone = { finishEditing() }),")
        )
        assertTrue(
            "the plate grows to a bound and then scrolls inside itself",
            tv.contains("Modifier.heightIn(max = KBMultilineFieldMaxHeight)")
        )
    }

    @Test
    fun `Enter on a multiline field inserts a newline instead of finishing`() {
        val enter = tv.substring(
            tv.indexOf("Key.Enter, Key.NumPadEnter -> {"),
            tv.indexOf("Key.Back -> {")
        )

        assertTrue(
            "the multiline arm must exist and come before the finishing one",
            enter.contains("} else if (multiline) {")
        )

        val multilineArm = enter.substringAfter("} else if (multiline) {").substringBefore("} else {")
        assertFalse(
            "ending the session on Enter is exactly the bug being fixed",
            multilineArm.contains("finishEditing()")
        )
        assertTrue(
            "the press is handed to the field's own text input, which inserts it",
            multilineArm.contains("false")
        )
        assertFalse(
            "and the field must not consume the newline it is asking for",
            multilineArm.contains("true")
        )
    }

    @Test
    fun `the D-pad walks the caret before it walks focus`() {
        val down = tv.substring(tv.indexOf("Key.DirectionDown -> {"), tv.indexOf("Key.DirectionUp -> {"))
        val up = tv.substring(tv.indexOf("Key.DirectionUp -> {"), tv.indexOf("Key.DirectionCenter -> {"))

        listOf(down to 1, up to -1).forEach { (arm, delta) ->
            assertTrue(
                "a list's caret has to move before focus does, or the second line is unreachable",
                arm.contains("moveCaretVertically(caret, $delta)")
            )
            assertTrue(
                "only a caret already at the edge may hand the press to focus",
                arm.indexOf("moveCaretVertically") < arm.indexOf("focusManager.moveFocus")
            )
            assertTrue(
                "and this is the editing session's press, not the parked field's",
                arm.contains("if (editing && multiline) {")
            )
        }
    }

    @Test
    fun `the caret follows writes that did not come from typing`() {
        assertTrue(
            "the field owns a caret so the D-pad can move inside the text",
            tv.contains("TextFieldValue(value, TextRange(value.length))")
        )
        assertTrue(
            "a paste chip or restored pref must move the value, not be dropped",
            tv.contains("if (caret.text != value) caret = TextFieldValue(value, TextRange(value.length))")
        )
    }

    // ---- phone ------------------------------------------------------------

    @Test
    fun `the phone field has the same variant`() {
        assertTrue(touch.contains("multiline: Boolean = false"))
        assertTrue("the twin's single line is a negation, not a literal", touch.contains("singleLine = !multiline,"))
        assertTrue(
            "Done would make Enter end the session on the phone too",
            touch.contains("imeAction = if (multiline) ImeAction.Default else ImeAction.Done,")
        )
        assertTrue(
            "bounded growth, same rule and same constant as the TV field",
            touch.contains("maxLines = if (multiline) MULTILINE_FIELD_MAX_LINES else 1,")
        )
    }

    // ---- the call sites ---------------------------------------------------

    @Test
    fun `both list fields on the TV form are multiline`() {
        assertTrue(
            "Extra EPG Sources is the field the report was about",
            fieldBlock(guide, "value = extraEpgUrls,").contains("multiline = true")
        )
        assertTrue(
            "Extra playlists sits directly below it and has the same label",
            fieldBlock(guide, "value = extraPlaylistUrls,").contains("multiline = true")
        )
    }

    @Test
    fun `the single-value fields stay single line`() {
        // Playlist URL and EPG URL take exactly one value: a newline there would
        // be a typo, and NativeUrlField is their only field component.
        val native = guide.substring(
            guide.indexOf("private fun NativeUrlField("),
            guide.indexOf("@Composable", guide.indexOf("private fun NativeUrlField("))
        )
        assertFalse("the one-URL fields must not become lists", native.contains("multiline"))

        assertFalse(
            "the EPG URL is a list only in its extras field",
            fieldBlock(guide, "value = epgUrl,").contains("multiline")
        )
        assertFalse(
            "a playlist is one URL",
            fieldBlock(guide, "value = playlistUrl,").contains("multiline")
        )
    }

    @Test
    fun `both list fields on the phone form are multiline`() {
        assertTrue(
            "the phone form has to promise the same thing as the TV form",
            fieldBlock(guideMobile, "value = extraEpgUrls,").contains("multiline = true")
        )
        assertTrue(
            fieldBlock(guideMobile, "value = extraPlaylistUrls,").contains("multiline = true")
        )
        assertFalse(
            fieldBlock(guideMobile, "value = epgUrl,").contains("multiline")
        )
        assertFalse(
            fieldBlock(guideMobile, "value = playlistName,").contains("multiline")
        )
        assertTrue(
            "SetupField forwards the flag to the one field component",
            guideMobile.contains("multiline = multiline,")
        )
    }

    // ---- the parser must be untouched ------------------------------------

    @Test
    fun `both separators still parse, so an older value keeps working`() {
        // The workaround given while the field could not take a newline was to
        // separate URLs with ';'. Nothing about the import changed with this
        // fix, so a list typed that way has to keep being read the same way.
        assertEquals(
            "the playlist parse is the one spelling both the import and a guide's identity use",
            2,
            count(guideFiles, ".split('\\n', ';')")
        )
        assertTrue(
            "and the extras EPG field is parsed the same way",
            viewModel.contains(".split('\\n', ';')")
        )
    }

    // ---- source access ----------------------------------------------------

    /**
     * The argument list of the field whose `value = <name>` line starts at
     * [anchor] - from that line to the call's closing paren.
     */
    private fun fieldBlock(source: String, anchor: String): String {
        val start = source.indexOf(anchor)
        assertTrue("source missing field: $anchor", start >= 0)
        val end = source.indexOf(")", start)
        return source.substring(start, if (end >= 0) end else source.length)
    }

    private fun count(source: String, needle: String): Int =
        source.windowed(needle.length, 1).count { it == needle }

    private fun read(path: String): String {
        val file = File(findSourceRoot(), path)
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
        throw AssertionError("main source root not found")
    }
}
