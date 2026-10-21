package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's clearlogo slots must fit the screen they are drawn on.
 *
 * The field report ("the logo's edges are cut off on a phone") was not a
 * `fitCenter` problem - `fitCenter` scaled the art correctly *inside its box*.
 * The box was the problem: both slots were fixed TV sizes (400dp on the
 * controls overlay, 420dp on the pre-playback splash), a phone header cannot
 * give 400dp, and the parent clipped the box with the art's edges in it.
 *
 * Pinned here because no JVM test can inflate a player layout: what is checked
 * is the measurement contract each slot carries - the box takes the container's
 * width and stops at the old TV size (so a TV, where that size is always
 * available, still draws exactly what it did), and the art is still `fitCenter`
 * with `adjustViewBounds`, at the same heights.
 */
class PlayerClearLogoSlotContractTest {

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

    /** Whitespace-collapsed layout source, so a wrapped tag still matches. */
    private fun layout(file: String): String {
        val layoutFile = File(File(sourceRoot.parentFile, "res/layout"), file)
        assertTrue("layout missing: $layoutFile", layoutFile.isFile)
        return layoutFile.readText().replace(Regex("\\s+"), " ")
    }

    /** One view tag: from its id to the end of the tag. */
    private fun slot(xml: String, id: String): String =
        xml.substringAfter("@+id/$id").substringBefore("/>")

    @Test
    fun `the controls overlay's slot takes the header's width, capped at 400dp`() {
        val chrome = layout("player_chrome.xml")
        val logo = slot(chrome, "chrome_clear_logo")

        assertTrue(
            "the slot must be measured by its container - a fixed 400dp box is " +
                "wider than a phone's header and gets clipped with the art in it",
            logo.contains("android:layout_width=\"0dp\"")
        )
        assertTrue(
            "and it must stop at the TV size it used to be, so a TV is unchanged",
            logo.contains("app:layout_constraintWidth_max=\"400dp\"")
        )
        assertTrue(
            "left-aligned as the old 400dp box was (bias 0), not centred in the header",
            logo.contains("app:layout_constraintHorizontal_bias=\"0\"")
        )
        assertTrue(
            "a ConstraintLayout is what measures that cap - the slot has to be one " +
                "of its children",
            chrome.contains("androidx.constraintlayout.widget.ConstraintLayout")
        )
        assertTrue(
            "the slot is anchored to both of its parent's edges",
            logo.contains("app:layout_constraintStart_toStartOf=\"parent\"") &&
                logo.contains("app:layout_constraintEnd_toEndOf=\"parent\"")
        )
        listOf(
            "android:layout_height=\"104dp\"",
            "android:scaleType=\"fitCenter\"",
            "android:adjustViewBounds=\"true\"",
            "android:visibility=\"gone\""
        ).forEach { attribute ->
            assertTrue(
                "only the WIDTH changed; $attribute must stay as it was",
                logo.contains(attribute)
            )
        }
        assertFalse(
            "the fixed box that was clipped must be gone",
            logo.contains("android:layout_width=\"400dp\"")
        )
    }

    @Test
    fun `both pre-playback splashes cap their slot at 420dp instead of fixing it`() {
        // activity_player.xml is the Exo engine's splash, activity_mpv_player.xml
        // the MPV engine's own copy of it: the same slot, so the same clipping on
        // a phone, and the same rule here.
        listOf(
            "activity_player.xml" to "splash_clear_logo",
            "activity_mpv_player.xml" to "mpv_loading_logo"
        ).forEach { (file, id) ->
            val logo = slot(layout(file), id)
            assertTrue(
                "$file: the slot must size to the art so it can fit a phone",
                logo.contains("android:layout_width=\"wrap_content\"")
            )
            assertTrue(
                "$file: and stop at the TV size it used to be",
                logo.contains("android:maxWidth=\"420dp\"")
            )
            listOf(
                "android:layout_height=\"140dp\"",
                "android:scaleType=\"fitCenter\"",
                "android:adjustViewBounds=\"true\"",
                "android:layout_gravity=\"center\""
            ).forEach { attribute ->
                assertTrue(
                    "$file: only the WIDTH changed; $attribute must stay as it was",
                    logo.contains(attribute)
                )
            }
            assertFalse(
                "$file: the fixed 420dp box that overhung a phone must be gone",
                logo.contains("android:layout_width=\"420dp\"")
            )
        }
    }
}
