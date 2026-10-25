package com.kennyb1201.kbstream.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The caret rule behind the multiline field's D-pad.
 *
 * Report (2026-10-25, Kenny): Playlist Setup's "one per line" fields could not
 * take a second line - their label promised something the single-line field
 * could never do, because Enter was the IME's Done. The fix makes both list
 * fields multiline; on TV that immediately raises the second half of the
 * problem, which is what this test covers: with Enter able to add a line, the
 * D-pad has to be able to walk ONTO that line. If Down always means "next
 * control", a viewer can type a second URL and never reach it.
 *
 * [moveCaretVertically] is the whole decision, and it is pure - so it is run
 * here rather than read out of a composable this module has no UI harness for
 * (the same reason [KBSecretFieldMaskingTest] runs the masking rule). The null
 * answer is the important one: it is what tells the field to hand the press back
 * to focus navigation, so an "always moves" bug would trap the D-pad in the
 * field and an "always null" bug would be the original complaint.
 */
class KBMultilineFieldCaretTest {

    @Test
    fun `down moves the caret onto the next line at the same column`() {
        val field = TextFieldValue("http://a\nhttp://b\nhttp://c", TextRange(3))

        val walked = moveCaretVertically(field, +1)!!

        // Line 1 starts after "http://a\n" (9 characters); column 3 rides along.
        assertEquals(12, walked.selection.start)
        assertEquals(12, walked.selection.end)
        assertEquals("moving the caret cannot change the text", field.text, walked.text)
    }

    @Test
    fun `up moves the caret onto the previous line at the same column`() {
        val field = TextFieldValue(
            "http://a\nhttp://b\nhttp://c",
            // Column 2 of line 2 ("http://c"): 9 + 9 + 2.
            TextRange(20)
        )

        // ...which is column 2 of line 1: 9 for line 0 plus 2.
        assertEquals(11, moveCaretVertically(field, -1)!!.selection.start)
    }

    @Test
    fun `a caret on the first line cannot go up`() {
        // Null, not a clamped move: this is the press the field must pass on so
        // the D-pad can leave the field at its top edge.
        assertNull(moveCaretVertically(TextFieldValue("http://a\nhttp://b", TextRange(4)), -1))
    }

    @Test
    fun `a caret on the last line cannot go down`() {
        val field = TextFieldValue("http://a\nhttp://b", TextRange(10))

        assertNull(moveCaretVertically(field, +1))
    }

    @Test
    fun `a single-line value has nowhere to walk`() {
        // The single-line fields keep the old behaviour exactly: Down is focus
        // navigation, never a caret move.
        val field = TextFieldValue("http://a", TextRange(0))

        assertNull(moveCaretVertically(field, +1))
        assertNull(moveCaretVertically(field, -1))
        assertNull(moveCaretVertically(TextFieldValue("", TextRange(0)), +1))
    }

    @Test
    fun `a shorter target line puts the caret at its end`() {
        // No padding: a caret parked past the end of a short line clamps to that
        // line's end instead of jumping into the next one.
        val field = TextFieldValue("http://very-long-url.example\nb", TextRange(20))

        // Line 1 is one character long, and starts at 29, so its end is 30.
        assertEquals(30, moveCaretVertically(field, +1)!!.selection.start)
    }

    @Test
    fun `a caret on a trailing empty line walks back up to the line above`() {
        // The state right after Enter at the end of a list: the caret sits on a
        // brand-new empty last line, and Up has to leave it rather than focus.
        val field = TextFieldValue("http://a\n", TextRange(9))

        assertEquals(0, moveCaretVertically(field, -1)!!.selection.start)
    }

    @Test
    fun `a caret at the very end of the text still walks up`() {
        val field = TextFieldValue("http://a\nhttp://b", TextRange(18))

        // A caret past the end clamps to the last line's end (17), which is
        // column 8 - the end of "http://a" on the line above.
        assertEquals(8, moveCaretVertically(field, -1)!!.selection.start)
    }
}
