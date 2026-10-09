package com.kennyb1201.kbstream.ui.components

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The masking rule behind the API-key fields.
 *
 * Report (fifth audit, P1): the MDBList / OpenSubtitles / TorBox key fields
 * drew the key in the clear, so anyone in the room could read it off the TV.
 * The fix is [KBSecretField], which draws through [secretFieldTransformation] -
 * and that function is what this test pins, because the module has no Compose
 * UI harness (see the settings contracts: the wiring is read from source, but a
 * TRANSFORMATION is a pure function and can be run).
 *
 * The two things worth testing here are the ones a source read cannot show: a
 * masked field really does draw dots instead of the key, and masking really is
 * display-only - the offset mapping stays 1:1 across the mask, which is what
 * lets the field keep editing the real string while dots are on screen (a
 * masked field that remapped offsets would corrupt a blind paste).
 */
class KBSecretFieldMaskingTest {

    private val key = "a1b2c3d4e5f6"

    @Test
    fun `an unrevealed key field draws dots, never the key`() {
        val drawn = secretFieldTransformation(revealed = false)
            .filter(AnnotatedString(key))
            .text
            .text

        assertFalse("the key must not be drawn in the clear", drawn.contains(key))
        assertEquals(
            "one password-style dot per character, so the length still reads",
            "\u2022".repeat(key.length),
            drawn
        )
    }

    @Test
    fun `revealing draws the stored key exactly, with nothing added`() {
        val shown = secretFieldTransformation(revealed = true)
            .filter(AnnotatedString(key))
            .text
            .text

        assertEquals(key, shown)
    }

    @Test
    fun `masking keeps the offsets one to one, so the field edits the real key`() {
        val mapping = secretFieldTransformation(revealed = false)
            .filter(AnnotatedString(key))
            .offsetMapping

        for (index in 0..key.length) {
            assertEquals(
                "a cursor at $index sits at $index inside the real key",
                index,
                mapping.originalToTransformed(index)
            )
            assertEquals(
                index,
                mapping.transformedToOriginal(index)
            )
        }
    }

    @Test
    fun `a revealed key goes back to dots once focus leaves the field and its chip`() {
        assertTrue(
            "moving on to the paste chip, another row or Back re-masks it",
            shouldRemaskKey(fieldFocused = false, toggleFocused = false)
        )
        assertFalse(
            "typing in the field keeps the reveal up",
            shouldRemaskKey(fieldFocused = true, toggleFocused = false)
        )
        assertFalse(
            "the chip is a focusable node, so pressing it cannot cancel its own reveal",
            shouldRemaskKey(fieldFocused = false, toggleFocused = true)
        )
        assertFalse(
            shouldRemaskKey(fieldFocused = true, toggleFocused = true)
        )
    }
}
