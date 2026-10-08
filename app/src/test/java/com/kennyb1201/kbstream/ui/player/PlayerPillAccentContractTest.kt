package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pill's selected / focused fill follows the global accent.
 *
 * Reported: "the up next popup still has the amber on one of the buttons even
 * though I'm on a different theme". The pill states were set from fixed XML
 * drawables - @drawable/pill_chip_selected_bg and @drawable/pill_chip_focused_bg
 * hard-code @color/kb_accent (brass, #FFE8A33D), and the two focused variants
 * are LAYER-LISTS, so the players' accent re-tint walk (which matches a
 * GradientDrawable's own fill) could not rebuild them either. The Up Next card
 * focuses its PLAY NEXT pill the moment it appears, so that one button always
 * hit the fixed, un-rebuildable variant and stayed amber under every other
 * accent.
 *
 * The fix builds every pill state at runtime from [pillChipBackground], which
 * reads themeAccentColor() and the AMOLED-aware panel surface. Drop it in any
 * one of the four pill sites and that surface silently reverts to brass, which
 * is why this reads the sources the way SplashClearLogoContractTest does.
 */
class PlayerPillAccentContractTest {

    private companion object {
        const val PILL_PANEL_UI =
            "com/kennyb1201/kbstream/ui/player/PillPanelUi.kt"
        const val NATIVE_PLAYER =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV_PLAYER =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL_PLAYER =
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        const val BECAUSE_YOU_WATCHED =
            "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"

        const val HELPER = "pillChipBackground"

        /**
         * The fixed, brass-hard-coded pill drawables the fix replaced - and
         * which are now DELETED rather than left dead: every state is built at
         * runtime, so the next pill would otherwise be pointed at the one
         * drawable that cannot follow the accent. Same call as the sweep's
         * other dead assets (control_button_bg, kb_overlay_scrim).
         */
        val FIXED_PILL_DRAWABLES = listOf(
            "pill_chip_selected_focused_bg",
            "pill_chip_selected_bg",
            "pill_chip_focused_bg",
            "button_accent_bg_focused"
        )
    }

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

    @Test
    fun `the shared helper resolves the pill fill from the current accent`() {
        val shared = source(PILL_PANEL_UI)
        assertTrue(
            "the pill background must be built from a context-aware helper",
            shared.contains("internal fun $HELPER(")
        )
        assertTrue(
            "a selected or focused pill must resolve the chosen accent, not a " +
                "fixed resource",
            shared.contains("themeAccentColor(context)")
        )
        assertTrue(
            "the neutral pill must follow the AMOLED-aware panel surface",
            shared.contains("playerPanelSurfaceColor(context)")
        )
    }

    @Test
    fun `every pill site uses the theme-resolved helper`() {
        // One look, one implementation: each activity's applyPillBackground is
        // now a call into the shared applyPillLook, which is the only place
        // $HELPER is called. Four copies used to exist, and they had already
        // diverged - the main player's set only the fill, the other two set the
        // fill and the label color.
        assertTrue(
            "$PILL_PANEL_UI must own the one pill look",
            source(PILL_PANEL_UI).contains("internal fun applyPillLook(")
        )
        listOf(NATIVE_PLAYER, MPV_PLAYER, EXTERNAL_PLAYER)
            .forEach { path ->
                assertTrue(
                    "$path must set its pill background through the shared look",
                    source(path).contains("applyPillLook(this, ")
                )
            }
        // And no second copy of the body is left behind anywhere.
        listOf(NATIVE_PLAYER, MPV_PLAYER, EXTERNAL_PLAYER).forEach { path ->
            val file = source(path)
            assertFalse(
                "$path paints a pill background of its own instead of calling " +
                    "the shared look",
                file.contains("view.background = pillChipBackground(")
            )
        }
    }

    @Test
    fun `focus is not selection on a pill`() {
        // A focused pill used to be accent-filled as well, so crossing the panel
        // with the D-pad repainted every pill it touched and the panel's actual
        // setting disappeared while it was being read. Only the chosen pill
        // fills; a focused neutral one takes the app's focus look
        // (raised panel fill + 2dp accent stroke).
        val shared = source(PILL_PANEL_UI)
        assertTrue(
            "the accent fill must hang off `selected` alone",
            shared.contains("selected -> themeAccentColor(context)")
        )
        assertFalse(
            "and never off selected-or-focused",
            shared.contains("if (selected || focused)")
        )
        assertTrue(
            "a focused but unselected pill must take the raised panel fill",
            shared.contains("focused -> playerPanelRaisedColor(context)")
        )
        assertTrue(
            "and the accent stroke - the app's focused-surface outline",
            shared.contains("focused -> themeAccentColor(context)")
        )
        assertFalse(
            "the hand-written white ring is gone: a focus ring comes from a " +
                "theme colour, not a literal",
            shared.contains("0xFFFFFFFF.toInt()")
        )
        assertTrue(
            "while focus on an already-filled pill still rings it, the way the " +
                "accent-filled SKIP INTRO control is ringed",
            shared.contains("selected && focused ->")
        )
    }

    @Test
    fun `the because-you-watched pills draw through the players' themed helper`() {
        val byw = source(BECAUSE_YOU_WATCHED)
        assertTrue(
            "the BYW panel must take an applyPill lambda so its PLAY/DETAILS " +
                "pills follow whichever engine's theme-resolved background",
            byw.contains("applyPill: (TextView, Boolean, Boolean) -> Unit")
        )
        listOf(NATIVE_PLAYER, MPV_PLAYER, EXTERNAL_PLAYER).forEach { path ->
            assertTrue(
                "$path must wire the BYW pills to its themed applyPillBackground",
                source(path).contains("applyPill = { pill, selected, focused ->")
            )
        }
    }

    @Test
    fun `the native accent button is themed, not the fixed focused layer-list`() {
        val native = source(NATIVE_PLAYER)
        assertTrue(
            "the SKIP INTRO control must build its focused state from the theme",
            native.contains("accentButtonBackground(this, focused)")
        )
        assertFalse(
            "R.drawable.button_accent_bg_focused is a layer-list the accent " +
                "walk cannot rebuild, so a focused accent button stayed brass",
            native.contains("R.drawable.button_accent_bg_focused")
        )
    }

    @Test
    fun `the fixed brass pill drawables are gone`() {
        FIXED_PILL_DRAWABLES.forEach { drawable ->
            assertFalse(
                "$drawable hard-codes @color/kb_accent and (for the focused " +
                    "variants) is a layer-list the accent walk cannot rebuild, so " +
                    "it is deleted rather than left for the next pill to pick up",
                File(sourceRoot, "../res/drawable/$drawable.xml").normalize().exists()
            )
            listOf(NATIVE_PLAYER, MPV_PLAYER, EXTERNAL_PLAYER, PILL_PANEL_UI)
                .forEach { path ->
                    assertFalse(
                        "$path still references $drawable",
                        source(path).contains(drawable)
                    )
                }
        }
    }

    @Test
    fun `no layout still points a pill at a fixed brass drawable`() {
        // The layouts are the other place a pill gets a background: the XML
        // default a pill wears before the runtime pass touches it. A brass one
        // there is what the MPV player's SKIP INTRO pill was still wearing.
        listOf(
            "../res/layout/activity_player.xml",
            "../res/layout/player_chrome.xml",
            "../res/layout/activity_mpv_player.xml",
            "../res/layout/activity_external_player.xml"
        ).forEach { layout ->
            val text = File(sourceRoot, layout).normalize().readText()
            FIXED_PILL_DRAWABLES.forEach { drawable ->
                assertFalse(
                    "$layout still defaults a control to @drawable/$drawable",
                    text.contains("@drawable/$drawable\"")
                )
            }
        }
    }
}
