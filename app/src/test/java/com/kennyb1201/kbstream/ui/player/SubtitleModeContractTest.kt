package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subtitle mode setting, asserted at the seams a pure test cannot reach.
 *
 * The decision - which track a mode lands on, and that an unknown stored value
 * degrades to On - is pure and covered by [SubtitleModeRulesTest] and
 * [SubtitleTrackRulesTest]. The rest is wiring: the preference has to exist and
 * sync, both engines have to read it, the explicit Off has to disable the text
 * track rather than leave the choice to media3, and the settings pane has to
 * offer it. Each absent line fails silently - the app compiles, the control
 * does nothing - so they are pinned here.
 */
class SubtitleModeContractTest {

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    @Test
    fun `the preference exists, defaults to on and syncs`() {
        val prefs = readSource(PREFS)
        assertTrue(prefs.contains("fun getSubtitleMode("))
        assertTrue(prefs.contains("fun setSubtitleMode("))
        assertTrue(
            "an absent preference must fall back to On (2)",
            prefs.contains("readIntPref(context, KEY_SUBTITLE_MODE, 2)")
        )
        assertTrue(
            "the mode is a viewing preference and belongs in the sync allow-list",
            readSource(SYNC).contains("\"subtitle_mode\"")
        )
    }

    @Test
    fun `the native engine honours the mode and a hard off disables the text track`() {
        val player = readSource(NATIVE)
        assertTrue(
            "Off must disable the text track outright, not leave it to media3",
            player.contains("if (!isLiveChannel && subtitleMode == SubtitleModeRules.OFF) {")
        )
        assertTrue(
            "the mode must be read from the preference",
            player.contains("AppPreferences.getSubtitleMode(this)")
        )
        assertTrue(
            "the track rules must be consulted with the mode",
            player.contains("SubtitleTrackRules.choose(") &&
                player.contains("subtitleMode")
        )
    }

    @Test
    fun `auto-fetch only runs in the language mode`() {
        // Forced wants foreign-dialogue cues, not a full translation; Off wants
        // nothing. Neither may pull a full subtitle from OpenSubtitles.
        val gate =
            "SubtitleModeRules.normalized(AppPreferences.getSubtitleMode(this)) !="
        assertTrue(
            "the native fetch must be gated on the On mode",
            readSource(NATIVE).contains(gate)
        )
        assertTrue(
            "the MPV fetch must be gated on the On mode",
            readSource(MPV_PLAYER).contains(gate)
        )
    }

    @Test
    fun `the mpv engine applies the mode at open time`() {
        assertTrue(
            "the mode must be set before initialize()",
            readSource(MPV_PLAYER)
                .contains("view.setSubtitleMode(AppPreferences.getSubtitleMode(this))")
        )
        val view = readSource(MPV_VIEW)
        assertTrue(
            "the view must hold the mode",
            view.contains("private var subtitleMode = SubtitleModeRules.DEFAULT")
        )
        assertTrue(
            "off must deselect subtitles in mpv",
            view.contains("SubtitleModeRules.OFF -> mpv.setOptionString(\"sid\", \"no\")")
        )
        assertTrue(
            "forced must ask mpv for forced cues only",
            view.contains("mpv.setOptionString(\"sub-forced-only\", \"yes\")")
        )
    }

    @Test
    fun `the runtime branch restores subtitle selection after off`() {
        // The open-time options only cover the FIRST file; a mode changed while
        // playing goes through setSubtitleMode's runtime branch, and OFF leaves
        // mpv's sid at "no". Restoring only the forced-only flag (what it used to
        // do) meant a runtime OFF -> ON stayed silent - the same mode, two
        // behaviours, depending on whether it was set before or after open.
        val view = readSource(MPV_VIEW)
        val runtime = view
            .substringAfter("fun setSubtitleMode(mode: Int)")
            .substringBefore("fun setSpeed(")
        val restored = "mpv.setPropertyString(\"sid\", \"auto\")"

        assertTrue(
            "off must still deselect at runtime",
            runtime.contains("mpv.setPropertyString(\"sid\", \"no\")")
        )
        assertTrue(
            "forced needs a selected track for its forced cues to appear",
            runtime.contains(restored)
        )
        assertTrue(
            "and ON must put subtitles back, not just drop the forced flag",
            runtime.substringAfter("sub-forced-only\", \"no\")").contains(restored)
        )
    }

    @Test
    fun `the settings pane offers the mode`() {
        val settings = readSource(SETTINGS)
        assertTrue(
            "the pane must render the mode options",
            settings.contains("SubtitleModeRules.OPTIONS.forEach")
        )
        assertTrue(
            "picking a mode must persist it",
            settings.contains("AppPreferences.setSubtitleMode(context, value)")
        )
        assertTrue(
            "the pane must read the stored mode",
            settings.contains("AppPreferences.getSubtitleMode(context)")
        )
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV_PLAYER =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        private const val MPV_VIEW =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
        private const val PREFS =
            "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        private const val SYNC =
            "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        private const val SETTINGS =
            "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
    }
}
