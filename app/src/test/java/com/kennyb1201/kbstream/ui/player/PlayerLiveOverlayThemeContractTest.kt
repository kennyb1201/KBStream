package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live TV overlay follows the theme (field report 2026-11-12, build 262).
 *
 * The player's chrome is View-based, and an XML drawable resolves
 * `@color/kb_surface`, `@color/kb_surface_raised` and `@color/kb_accent` ONCE at
 * inflation - so the chrome walk that fixes that (`retintPlayerChrome`) has to
 * run over a view AFTER it exists. The live block and the CH / GUIDE buttons are
 * inflated by `bindLiveViews`, which runs after the walk in `bindViews`, so the
 * one part of the player a live session actually looks at was the one part the
 * walk never saw: a gold status line under a green accent, and blue-grey plates
 * under AMOLED.
 *
 * The colour resolution itself is tested where it lives
 * ([com.kennyb1201.kbstream.ui.theme.ThemeAccentTest] and the AMOLED token
 * tests); this pins the wiring that the live views go through it at all.
 */
class PlayerLiveOverlayThemeContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        val to = text.indexOf(end, from + start.length)
        if (to < 0) return ""
        return text.substring(from, to)
    }

    // ── F1: the live block's accent comes from the theme ──────────────────

    @Test
    fun `the live block is themed as soon as it is inflated`() {
        val activity = source(ACTIVITY)
        val bindLive = between(activity, "private fun bindLiveViews(", "private fun themeLiveChrome(")

        assertTrue("the program block is inflated into the chrome slot", bindLive.contains("player_exo_live_program"))
        assertTrue("and the channel bar too", bindLive.contains("player_exo_channel_buttons"))
        assertTrue(
            "the pass runs AFTER both, because the activity's chrome walk ran " +
                "in bindViews, before either existed",
            bindLive.contains("btnGuide = channelBar.findViewById(R.id.btn_guide)") &&
                bindLive.contains("themeLiveChrome()")
        )
    }

    @Test
    fun `the status line and the program bar take the chosen accent`() {
        val activity = source(ACTIVITY)
        val themeLive = between(activity, "private fun themeLiveChrome(", "private fun bindViews(")

        assertTrue(
            "the \"LIVE • group\" status line - the gold one in the report",
            themeLive.contains("liveProgramStatus?.setTextColor(accent)")
        )
        assertTrue(
            "and the programme progress bar",
            themeLive.contains("liveProgramProgress?.progressTintList = ColorStateList.valueOf(accent)")
        )
        assertTrue(
            "both read the ONE accent the rest of the app paints",
            themeLive.contains("val accent = themeAccentColor(this)")
        )
    }

    @Test
    fun `the shared seek bar is pinned to the accent as well`() {
        val activity = source(ACTIVITY)
        val themeLive = between(activity, "private fun themeLiveChrome(", "private fun bindViews(")

        assertTrue(themeLive.contains("seekbar.progressTintList = ColorStateList.valueOf(accent)"))
        assertTrue(
            "the thumb is the part the report saw as 'light'",
            themeLive.contains("seekbar.thumbTintList = ColorStateList.valueOf(accent)")
        )
    }

    // ── F2: the CH / GUIDE plates follow AMOLED, ring follows the accent ──

    @Test
    fun `the live buttons are rebuilt from the AMOLED-aware fills and the accent`() {
        val activity = source(ACTIVITY)
        val themeLive = between(activity, "private fun themeLiveChrome(", "private fun bindViews(")

        assertTrue(
            "all three live buttons",
            themeLive.contains("listOfNotNull(btnChannelUp, btnChannelDown, btnGuide)")
        )
        assertTrue(
            "with a background built from the current theme, not the XML selector",
            themeLive.contains("button.background = themedControlBackground(this)")
        )
    }

    @Test
    fun `the built plate mirrors mpv_control_bg with themed colours`() {
        val helpers = source(HELPERS)
        val builder = between(
            helpers,
            "internal fun themedControlBackground(",
            "/**\n * The accent button's background"
        )
        assertTrue("the builder must be found", builder.isNotBlank())

        assertTrue(
            "the focused plate is the RAISED surface - pure black when AMOLED " +
                "and pure black surface are both on",
            builder.contains("plate(playerPanelRaisedColor(context), 2f, themeAccentColor(context))")
        )
        assertTrue(
            "the unfocused plate is the ordinary surface...",
            builder.contains("playerPanelSurfaceColor(context)")
        )
        assertTrue(
            "...with the XML's hairline border kept",
            builder.contains("R.color.kb_overlay_gradient_mid")
        )
        assertTrue(
            "2dp ring on focus, 1dp hairline off it, 10dp corners - the " +
                "D-pad look mpv_control_bg draws (S4)",
            builder.contains("2f, themeAccentColor(context)") &&
                builder.contains("1f,") &&
                builder.contains("cornerRadius = 10f * density")
        )
        assertFalse(
            "and no static resource stands in for the theme",
            builder.contains("R.color.kb_surface_raised") ||
                builder.contains("R.color.kb_surface\"") ||
                builder.contains("R.color.kb_accent")
        )
    }

    // ── F3: one copy of the live chrome, and it is themed ─────────────────

    @Test
    fun `the MPV engine has no copy of the live block to fall behind`() {
        val mpv = source(MPV)

        assertFalse(
            "the Exo live block is not inflated by the backup engine",
            mpv.contains("player_exo_live_program")
        )
        assertFalse(
            "nor its channel bar",
            mpv.contains("player_exo_channel_buttons")
        )
        assertFalse(
            "nor the live buttons by id",
            mpv.contains("R.id.btn_channel_up") || mpv.contains("R.id.btn_guide")
        )
        assertTrue(
            "it does theme the shared chrome it does have",
            mpv.contains("retintPlayerChrome")
        )
    }

    // ── S3 / S4: runtime re-read, and the default look untouched ──────────

    @Test
    fun `showing the overlay re-reads the theme`() {
        val activity = source(ACTIVITY)
        val controls = between(activity, "private fun updateControlsInfo(", "private fun applyPillBackground(")

        assertTrue(
            "the live chrome is repainted every time the overlay is shown, so " +
                "a theme changed while the player is up is picked up (S3)",
            controls.contains("themeLiveChrome()")
        )
    }

    @Test
    fun `the default palette is a no-op, not a repaint`() {
        val activity = source(ACTIVITY)
        val themeLive = between(activity, "private fun themeLiveChrome(", "private fun bindViews(")

        assertTrue(
            "with the gold accent and AMOLED off the XML already IS the theme (S4)",
            themeLive.contains("if (!moved && !liveChromeThemed) return")
        )
        assertTrue(
            "but once this pass has moved the views, it keeps re-applying so a " +
                "toggle turned back off is undone",
            themeLive.contains("liveChromeThemed = true")
        )
    }

    private companion object {
        const val ACTIVITY = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val HELPERS = "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"
    }
}
