package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic subtitle fetch, asserted at the seams a unit test cannot reach.
 *
 * The decision (which hit) is pure and covered by [AutoSubtitleRulesTest]. The
 * rest is wiring: it has to be triggered by the "no subtitle track" branch, be
 * gated on the toggle, an API key, and a preferred language, and actually
 * attach the track. Each absent line fails silently — the app compiles and no
 * subtitle ever appears — so they are pinned here.
 */
class AutoFetchSubtitlesContractTest {

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

    private companion object {
        const val PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV_PLAYER = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
    }

    @Test
    fun `the no-subtitle branch triggers the fetch`() {
        val player = source(PLAYER)
        assertTrue(
            "the Off branch must ask for an automatic subtitle",
            player.contains("maybeAutoFetchSubtitle()")
        )
        assertTrue(
            "the fetch must be attempted once, not per track callback",
            player.contains("autoSubtitleFetchTried")
        )
    }

    @Test
    fun `the fetch is gated on the toggle, the key and a title`() {
        val player = source(PLAYER)
        assertTrue(player.contains("AppPreferences.getAutoFetchSubtitles(this)"))
        assertTrue(player.contains("AppPreferences.getOpensubtitlesApiKey(this).isBlank()"))
        assertTrue(player.contains("AutoSubtitleRules.pick("))
        assertTrue(player.contains("attachExternalSubtitle(uri)"))
    }

    @Test
    fun `the mpv engine also auto-fetches`() {
        val player = source(MPV_PLAYER)
        assertTrue(
            "the MPV engine must attempt the fetch once the file is open",
            player.contains("maybeAutoFetchSubtitle()")
        )
        assertTrue(
            "the MPV fetch must be attempted once, not per file-loaded callback",
            player.contains("autoSubtitleFetchTried")
        )
        assertTrue(player.contains("AppPreferences.getAutoFetchSubtitles(this)"))
        assertTrue(player.contains("AutoSubtitleRules.pick("))
        assertTrue(player.contains("surface?.addExternalSubtitle("))
    }

    @Test
    fun `the setting exists and syncs`() {
        assertTrue(source(PREFS).contains("fun getAutoFetchSubtitles("))
        assertTrue(
            "a viewing preference belongs in the synced allow-list",
            source(SYNC).contains("\"auto_fetch_subtitles\"")
        )
        assertTrue(
            "the setting needs a control in Settings",
            source(SETTINGS).contains("Auto-fetch Subtitles")
        )
    }
}
