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

        /** The fixed, brass-hard-coded pill drawables the fix replaces. */
        val FIXED_PILL_DRAWABLES = listOf(
            "R.drawable.pill_chip_selected_focused_bg",
            "R.drawable.pill_chip_selected_bg",
            "R.drawable.pill_chip_focused_bg"
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
        listOf(NATIVE_PLAYER, MPV_PLAYER, EXTERNAL_PLAYER, PILL_PANEL_UI)
            .forEach { path ->
                val file = source(path)
                assertTrue(
                    "$path must set its pill background through $HELPER",
                    file.contains("$HELPER(")
                )
            }
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
    fun `no pill site still paints the fixed brass drawables`() {
        listOf(NATIVE_PLAYER, MPV_PLAYER, EXTERNAL_PLAYER, PILL_PANEL_UI)
            .forEach { path ->
                val file = source(path)
                FIXED_PILL_DRAWABLES.forEach { drawable ->
                    assertFalse(
                        "$path still references $drawable, which hard-codes " +
                            "@color/kb_accent and (for the focused variants) is a " +
                            "layer-list the accent walk cannot rebuild",
                        file.contains(drawable)
                    )
                }
            }
    }
}
