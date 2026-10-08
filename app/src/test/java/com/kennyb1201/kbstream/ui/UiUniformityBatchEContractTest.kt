package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch E of the UI uniformity sweep, the two focus-visibility bugs.
 *
 * The players are the one place in the app that is still classic Views, so
 * they draw focus with XML drawables rather than the Compose focus grammar
 * everything else speaks. Two of those drawables had gone missing from the
 * layouts: the control chrome's background had no `state_focused` at all, so
 * no button in either player ever lit up as the D-pad crossed it, and the
 * picker's row painted its background on a root that never saw the focus the
 * child TextView was holding. The fix for the first is the accent-stroke
 * selector that already existed (and was referenced by nothing), and for the
 * second an `addStatesFromChildren` root, which reports the child's focus
 * without moving focus off the text and reordering the D-pad.
 *
 * Source-level: no device, and both bugs are invisible to the compiler.
 */
class UiUniformityBatchEContractTest {

    private val mainDir: File by lazy { findMainDir() }

    private fun findMainDir(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "src/main not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun res(relative: String): String {
        val file = File(mainDir, "res/$relative")
        assertTrue("resource missing: $file", file.isFile)
        return squash(file.readText())
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun count(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    @Test
    fun `the player's control chrome shows focus`() {
        val selector = res("drawable/mpv_control_bg.xml")
        assertTrue(
            "the control background carries a focused state",
            selector.contains("<item android:state_focused=\"true\">")
        )
        assertTrue(
            "and it is the app's focus language: the raised plate with a 2dp " +
                "accent stroke",
            selector.contains("android:color=\"@color/kb_surface_raised\"") &&
                selector.contains("android:width=\"2dp\"") &&
                selector.contains("android:color=\"@color/kb_accent\"")
        )
        // The shared overlay lives in player_chrome.xml, which both players
        // include; the Exo-only views it cannot share live in their own slot
        // layouts and carry the same selector.
        listOf(
            "layout/player_chrome.xml",
            "layout/player_exo_channel_buttons.xml"
        )
            .forEach { layout ->
                val text = res(layout)
                assertEquals(
                    "$layout must draw every control button with that selector",
                    0,
                    count(text, "android:background=\"@drawable/control_button_bg\"")
                )
            }
        assertTrue(
            "the shared bar has no control button on the focus-less background",
            count(
                res("layout/player_chrome.xml"),
                "android:background=\"@drawable/mpv_control_bg\""
            ) >= 10
        )
        assertFalse(
            "and the focus-less background it replaced is gone rather than left " +
                "behind for the next screen to pick up",
            File(mainDir, "res/drawable/control_button_bg.xml").exists()
        )
    }

    @Test
    fun `a picker row shows focus`() {
        val row = res("layout/picker_item.xml")
        assertTrue(
            "the row's root reports the focus its child TextView holds, so the " +
                "background can react to it without moving focus off the text",
            row.contains("android:addStatesFromChildren=\"true\"")
        )
        val bg = res("drawable/picker_item_bg.xml")
        assertTrue(
            "and the row's plate has a focused state with the 2dp accent stroke",
            bg.contains("<item android:state_focused=\"true\">") &&
                bg.contains("android:width=\"2dp\"") &&
                bg.contains("android:color=\"@color/kb_accent\"")
        )
        assertTrue(
            "the press flash survives the selector",
            bg.contains("<ripple")
        )
    }

    @Test
    fun `every control bar button can get back to the scrub bar`() {
        // Up from the second control row has to land on the scrub bar, and the
        // bar is where a viewer who went down into a button expects to come
        // back to. Half the row declared it and half leaned on focus search's
        // nearest-neighbour guess, which changes with the bar's own width and
        // which buttons a title happens to show - so which one they landed on
        // depended on the stream. Now every button in both bars names it.
        val rows = mapOf(
            // The Exo engine's own channel/guide buttons lead the shared bar's
            // row, and name the shared bar as their Up target like every button
            // in it does.
            "layout/player_exo_channel_buttons.xml" to Pair(
                "@id/chrome_seekbar",
                listOf("btn_channel_up", "btn_channel_down", "btn_guide")
            ),
            "layout/player_chrome.xml" to Pair(
                "@id/chrome_seekbar",
                listOf(
                    "chrome_btn_play_pause", "chrome_btn_next", "chrome_btn_source",
                    "chrome_btn_audio", "chrome_btn_subtitle", "chrome_btn_speed",
                    "chrome_btn_aspect", "chrome_btn_player_switch",
                    "chrome_btn_player_external", "chrome_btn_info", "chrome_btn_settings"
                )
            )
        )
        rows.forEach { (layout, spec) ->
            val (target, ids) = spec
            val text = res(layout)
            assertEquals(
                "$layout: every control button declares $target as its Up target",
                ids.size,
                count(text, "android:nextFocusUp=\"$target\"")
            )
            ids.forEach { id ->
                val view = text.substring(text.indexOf("@+id/$id")).take(600)
                assertTrue(
                    "$layout: $id has no nextFocusUp",
                    view.contains("android:nextFocusUp=\"$target\"")
                )
            }
        }
    }

    @Test
    fun `every player label is drawn in the app's own font`() {
        // The players are XML, so a label with no fontFamily is not "the theme
        // font" - it is the platform face, and it only shows up on the three
        // buttons that were added to the control bar last and the MPV player's
        // own loading / hint lines. Scan the tag rather than list the ids: the
        // next button is written in whichever layout needs it.
        listOf(
            "layout/activity_player.xml",
            "layout/player_chrome.xml",
            "layout/player_exo_live_program.xml",
            "layout/player_exo_channel_buttons.xml",
            "layout/activity_mpv_player.xml",
            "layout/activity_external_player.xml"
        ).forEach { layout ->
            val text = res(layout)
            val leaks = text.split("<TextView")
                .drop(1)
                .mapNotNull { chunk ->
                    val block = chunk.substringBefore("/>")
                    // Only labels: a TextView that gets its text at runtime has
                    // no android:text, and the subtitle view is one of those
                    // on purpose (its face is the viewer's subtitle setting).
                    if (block.contains("android:text=") &&
                        !block.contains("fontFamily=\"@font/oswald")
                    ) {
                        block.trim().take(60)
                    } else {
                        null
                    }
                }
            assertEquals(
                "$layout has a label in the platform font: $leaks",
                emptyList<String>(),
                leaks
            )
        }
    }

    @Test
    fun `both engines paint the same subtitle backgrounds`() {
        val player = mainDir.resolve("java/com/kennyb1201/kbstream/ui/player")
        val mpv = squash(File(player, "MpvPlayerView.kt").readText())
        val native = squash(File(player, "NativePlayerActivity.kt").readText())
        // The main player already had the three: Semi 50%, Solid 90%, Text 70%.
        listOf("0x80000000", "0xE5000000", "0xB3000000").forEach { fill ->
            assertTrue(
                "the main player must still paint its $fill subtitle plate",
                native.contains(fill)
            )
        }
        listOf("#80000000", "#E5000000", "#B3000000").forEach { fill ->
            assertTrue(
                "the MPV player has to paint the SAME three plates, to the byte - " +
                    "a title handed between engines must not change its subtitles",
                mpv.contains("\"$fill\"")
            )
        }
        assertFalse(
            "Solid used to be fully opaque under MPV and 90% under the main " +
                "player",
            mpv.contains("\"#FF000000\"")
        )
        assertFalse(
            "and Text was an outlined-DVD look (`sub-border-size 2.4`) while the " +
                "main player drew the 70% plate",
            mpv.contains("\"2.4\"")
        )
    }

    @Test
    fun `the mpv error card leads with one accent action`() {
        val mpv = res("layout/activity_mpv_player.xml")
        assertTrue(
            "TRY NEXT SOURCE is the card's primary action - the same one the " +
                "native player's error card makes - so it is accent-filled with " +
                "void text, not another surface button beside SWITCH PLAYER",
            mpv.contains("android:id=\"@+id/mpv_error_next\"") &&
                mpv.substring(mpv.indexOf("@+id/mpv_error_next")).take(500)
                    .let {
                        it.contains("android:background=\"@drawable/button_accent_bg\"") &&
                            it.contains("android:textColor=\"@color/kb_void\"")
                    }
        )
        assertTrue(
            "and it keeps the surface plate the viewer's eye reads as secondary",
            mpv.substring(mpv.indexOf("@+id/mpv_error_switch")).take(500)
                .contains("android:background=\"@drawable/button_surface_bg\"")
        )
    }
}
