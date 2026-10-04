package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-player channel guide follows the global accent.
 *
 * Reported: "the in-player guide outline wasn't following the theme". The row's
 * focus outline is a stroke inside @drawable/channel_guide_item_bg, and the
 * shared tint walk ([retintAccentView] / [retintAccentChrome]) only retints
 * colours and tint lists - it never touches a background drawable, and a
 * GradientDrawable's stroke colour cannot be read back to patch it in place. So
 * the outline (and the GUIDE title) stayed on the fixed brass they inflated
 * with whenever a non-default accent was chosen.
 *
 * The fix rebuilds the row background from the current accent and re-runs the
 * tint walk over the overlay when it opens. Drop either half and the outline
 * silently reverts to brass, which is why this reads the sources the way
 * SplashClearLogoContractTest does.
 */
class GuideOutlineThemeContractTest {

    private companion object {
        const val BECAUSE_YOU_WATCHED =
            "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"
        const val CHANNEL_GUIDE_ADAPTER =
            "com/kennyb1201/kbstream/ui/player/ChannelGuideAdapter.kt"
        const val NATIVE_PLAYER =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"

        const val REBUILD = "themedGuideRowBackground"
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
    fun `the guide row background is rebuilt from the current accent`() {
        val shared = source(BECAUSE_YOU_WATCHED)
        assertTrue(
            "the accent-stroked row background must be buildable from an accent int",
            shared.contains("fun $REBUILD(context: Context, accent: Int)")
        )
        assertTrue(
            "the focused state must keep the accent stroke (the guide outline)",
            shared.contains("state_focused") && shared.contains("row(raised, 2, accent)")
        )
        assertTrue(
            "the selected state marks the playing channel with a hairline accent",
            shared.contains("state_selected") && shared.contains("row(raised, 1, accent)")
        )
        assertTrue(
            "the default state must keep the neutral hairline, not the accent",
            shared.contains("kb_overlay_gradient_mid")
        )
    }

    @Test
    fun `the adapter paints each row with the themed background`() {
        val adapter = source(CHANNEL_GUIDE_ADAPTER)
        assertTrue(
            "the adapter must set the row background from the chosen accent, or " +
                "the outline stays brass",
            adapter.contains("view.background = $REBUILD(") &&
                adapter.contains("themeAccentColor(")
        )
        assertTrue(
            "the accent text and progress tint still go through the shared walk",
            adapter.contains("retintAccentChrome(view, parent.context)")
        )
    }

    @Test
    fun `the GUIDE title is retinted when the overlay opens`() {
        val player = source(NATIVE_PLAYER)
        assertTrue(
            "the overlay's own accent chrome (the GUIDE title) must be retinted " +
                "each time it opens",
            player.contains("channelGuideContainer?.let { retintAccentChrome(it, this) }")
        )
    }
}
