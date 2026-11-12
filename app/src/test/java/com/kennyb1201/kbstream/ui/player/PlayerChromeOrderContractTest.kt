package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player control bar's button order (spec 2026-11-12).
 *
 * The bar is ONE horizontal LinearLayout in the shared overlay layout, which
 * both activities `<include>` and both engines bind by id - so the order lives
 * entirely in the XML, exactly as the 0.9.214 vertical reorder said. Two moves:
 * Episodes ahead of Source (the episode family leads the right-hand group;
 * picking an episode mid-play is the common action, Source is the "this stream
 * is wrong" one), and Subtitles ahead of Audio (the more-touched track picker
 * here).
 *
 * Layout-reading rather than inflating: the order, the spacer and the GONE
 * rules are all in the XML, and what this pins is that the file says what the
 * spec says - the D-pad walk follows the child order, so a moved child is a
 * moved focus stop.
 */
class PlayerChromeOrderContractTest {

    private val chrome: String by lazy { source(PLAYER_CHROME) }

    /** The control bar itself: from its comment to its own closing tag. */
    private fun bar(): String = chrome.substringAfter("<!-- Control bar:")
        .substringBefore("</LinearLayout>")

    private fun idsIn(block: String): List<String> =
        Regex("android:id=\"@\\+id/(chrome_[a-z_]+)\"")
            .findAll(block)
            .map { it.groupValues[1] }
            .toList()

    // ── (a) the sequence, exactly ─────────────────────────────────────────

    @Test
    fun `the bar's children are the approved order, left to right`() {
        assertEquals(
            "the control bar's children, in order: the engine-only slot leads, " +
                "then the episode family, then the stream actions, then the track " +
                "pair, then the session settings",
            listOf(
                "chrome_extra_controls",
                "chrome_btn_play_pause",
                "chrome_btn_next",
                "chrome_btn_episodes",
                "chrome_btn_source",
                "chrome_btn_subtitle",
                "chrome_btn_audio",
                "chrome_btn_speed",
                "chrome_btn_aspect",
                "chrome_btn_player_switch",
                "chrome_btn_player_external",
                "chrome_btn_info",
                "chrome_btn_settings"
            ),
            idsIn(bar())
        )
    }

    // ── (b) the split is where it was ─────────────────────────────────────

    @Test
    fun `the spacer still splits play controls from configuration, and the extra slot leads`() {
        val bar = bar()
        val spacer = bar.indexOf("android:layout_weight=\"1\"")
        assertTrue("the spacer exists", spacer >= 0)
        val next = bar.indexOf("@+id/chrome_btn_next\"")
        val episodes = bar.indexOf("@+id/chrome_btn_episodes\"")
        assertTrue("it sits after Next episode", spacer > next)
        assertTrue("and before Episodes, so the split is unchanged", spacer < episodes)
        assertTrue(
            "the engine-only slot still leads the bar",
            bar.indexOf("@+id/chrome_extra_controls\"") < bar.indexOf("@+id/chrome_btn_play_pause\"")
        )
        assertTrue(
            "and it is still GONE when empty, leaving no gap",
            chrome.substringAfter("@+id/chrome_extra_controls\"")
                .substringBefore("/>")
                .contains("android:visibility=\"gone\"")
        )
    }

    // ── (c) hidden buttons close ranks ────────────────────────────────────

    @Test
    fun `a hidden Next or Episodes button leaves no gap in the bar`() {
        val bar = bar()
        listOf("chrome_btn_next", "chrome_btn_episodes").forEach { id ->
            assertTrue(
                "$id is hidden with GONE, the only visibility that takes it out of the " +
                    "layout - INVISIBLE would leave its 40dp hole in the row",
                bar.substringAfter("@+id/$id\"").substringBefore("/>")
                    .contains("android:visibility=\"gone\"")
            )
        }
        assertFalse(
            "no control in the bar reserves invisible space",
            bar.contains("android:visibility=\"invisible\"")
        )
        // With both hidden, the visible walk is the F1 sequence minus those two,
        // in the same relative order - nothing else moves to fill their place.
        val full = idsIn(bar)
        val visible = full - setOf("chrome_btn_next", "chrome_btn_episodes")
        assertEquals(
            listOf(
                "chrome_extra_controls",
                "chrome_btn_play_pause",
                "chrome_btn_source",
                "chrome_btn_subtitle",
                "chrome_btn_audio",
                "chrome_btn_speed",
                "chrome_btn_aspect",
                "chrome_btn_player_switch",
                "chrome_btn_player_external",
                "chrome_btn_info",
                "chrome_btn_settings"
            ),
            visible
        )
    }

    // ── (d) the stale count in the comment ───────────────────────────────

    @Test
    fun `the bar's comment counts the buttons the bar actually has`() {
        val buttons = idsIn(bar()).filter { it.startsWith("chrome_btn_") }
        assertEquals(
            "twelve shared buttons: the eleven plus Episodes, which arrived with the " +
                "episode picker and never updated this comment",
            12,
            buttons.size
        )
        assertTrue(
            "and the comment says so",
            chrome.contains("<!-- Control bar: the 12 shared buttons")
        )
        assertFalse(
            "the stale eleven is gone",
            chrome.contains("the 11 shared buttons")
        )
    }

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/res")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("resource missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "res root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val PLAYER_CHROME = "layout/player_chrome.xml"
    }
}
