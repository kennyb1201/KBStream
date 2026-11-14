package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No dialog button's label may wrap (spec 2026-11-14).
 *
 * Kenny's screenshot showed the AniList username row on his phone with CLEAR
 * drawn as "CLEA" over "R". Nothing about the row was misbehaving - a Row that
 * gives its children no weight measures the unweighted ones first, so SAVE
 * USERNAME's label plus its 18.dp padding ate the width and CLEAR's two-inch word
 * had nowhere to go. The same shape existed in Kitsu's dialog, because the two
 * rows were written from the same template.
 *
 * THE FIX IS AT THE CALL SITES, not in [AnimeDialogButton] (which already honours
 * a modifier on both surfaces): SAVE USERNAME takes the row's slack, CLEAR keeps
 * its own width, and every button label in these two dialogs is single-line. TV
 * is unchanged by construction - the weight only redistributes space a TV already
 * had - and the rows still raise the same button on both surfaces.
 *
 * Pinned by reading the sources: a Compose layout cannot be measured from this
 * module's JVM tests, and the failure is visual and easy to reintroduce by
 * copy-paste.
 */
class AnimeConnectDialogButtonWrapContractTest {

    private val aniList by lazy { source(ANILIST) }
    private val kitsu by lazy { source(KITSU) }

    @Test
    fun `the username row gives SAVE the slack and leaves CLEAR its own width`() {
        listOf(aniList to "AniList", kitsu to "Kitsu").forEach { (dialog, name) ->
            // Scoped to the username row's two buttons: the dialogs already use a
            // weight elsewhere (the AniList link text beside its QR), and that is
            // a different row.
            val saveButton = dialog.substringBefore("text = \"SAVE USERNAME\"")
                .substringAfterLast("AnimeDialogButton(")
            assertTrue(
                "$name: SAVE USERNAME must absorb the row's slack",
                saveButton.contains("modifier = Modifier.weight(1f)")
            )
            val clearButton = dialog.substringBefore("text = \"CLEAR\"")
                .substringAfterLast("AnimeDialogButton(")
            assertFalse(
                "$name: CLEAR must keep its own width - weighting it too would " +
                    "just split the starvation",
                clearButton.contains("Modifier.weight(")
            )
            assertTrue(
                "$name: the username row still fills the dialog width",
                dialog.substringBefore("text = \"SAVE USERNAME\"")
                    .substringAfterLast("Row(")
                    .contains("Modifier.fillMaxWidth()")
            )
        }
    }

    @Test
    fun `CLEAR is single-line in both dialogs`() {
        listOf(aniList to "AniList", kitsu to "Kitsu").forEach { (dialog, name) ->
            val clear = dialog.substringAfter("text = \"CLEAR\"")
            assertTrue("$name: CLEAR must not wrap", clear.contains("maxLines = 1"))
            assertTrue("$name: and must not soft-wrap either", clear.contains("softWrap = false"))
        }
    }

    @Test
    fun `every button label in both dialogs is guarded against wrapping`() {
        // Five labels each: DISCONNECT, CONNECT/CONNECTING, CANCEL, SAVE USERNAME,
        // CLEAR. The guard is cheap, and the CONNECT/CANCEL row has the same
        // starve-and-wrap hazard on a narrower display.
        assertEquals(5, countOf(aniList, "softWrap = false"))
        assertEquals(5, countOf(kitsu, "softWrap = false"))
    }

    @Test
    fun `TV keeps its own layout - the fix has no surface branch`() {
        listOf(aniList to "AniList", kitsu to "Kitsu").forEach { (dialog, name) ->
            val saveToClear = dialog.substringAfter("text = \"SAVE USERNAME\"")
                .substringBefore("text = \"CLEAR\"")
            assertFalse(
                "$name: the username row must not branch on the surface - both " +
                    "draw the same buttons, and the weight only redistributes slack",
                saveToClear.contains("if (phone)")
            )
            assertTrue(
                "$name: and the button is still the shared one, raised with phone = phone",
                dialog.contains("AnimeDialogButton(\n                    phone = phone,")
            )
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun source(fileName: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/ui/settings/$fileName")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $fileName")
    }

    private fun countOf(text: String, token: String): Int {
        var found = 0
        var from = text.indexOf(token)
        while (from >= 0) {
            found++
            from = text.indexOf(token, from + token.length)
        }
        return found
    }

    private companion object {
        const val ANILIST = "AniListConnectDialog.kt"
        const val KITSU = "KitsuConnectDialog.kt"
    }
}
