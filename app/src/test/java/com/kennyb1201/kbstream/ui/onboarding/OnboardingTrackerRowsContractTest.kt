package com.kennyb1201.kbstream.ui.onboarding

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Onboarding L2: a first run can now reach every tracker that needs an account.
 *
 * Before this, the setup page offered Simkl and Trakt only. BingeCat - which
 * reads and writes history - and the three anime trackers (MAL, AniList, Kitsu)
 * were connectable from Settings and nowhere else, so a new install either found
 * them by accident or ran without them.
 *
 * The anime three are Settings' OWN rows and dialogs, hosted in one sheet here:
 * three more connect state machines on the welcome page would have been three
 * more chances to drift from the ones in Settings.
 */
class OnboardingTrackerRowsContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val tv = squash(source("com/kennyb1201/kbstream/ui/onboarding/OnboardingScreen.kt"))
    private val phone = squash(source("com/kennyb1201/kbstream/ui/mobile/OnboardingScreenMobile.kt"))
    private val activity = squash(source("com/kennyb1201/kbstream/MainActivity.kt"))

    @Test
    fun `both surfaces offer BingeCat and the anime trackers`() {
        listOf(tv to "OnboardingSourceRow(", phone to "OnboardingSourceRowMobile(").forEach { (screen, row) ->
            assertTrue("a BingeCat hand-off ($row)", screen.contains("title = \"Connect BingeCat\""))
            assertTrue("with its own page", screen.contains("onOpenBingeCat"))
            assertTrue("and one anime-tracker hand-off", screen.contains("title = \"Anime trackers\""))
            assertTrue(
                "the description names the three, so the row says what it opens",
                screen.contains("description = \"MyAnimeList, AniList or Kitsu\"")
            )
            assertTrue("the row is what opens the sheet", screen.contains("onClick = { showAnimeTrackers = true }"))
        }
    }

    @Test
    fun `the anime connects are Settings' own rows, not a second implementation`() {
        listOf(tv to "phone = false", phone to "phone = true").forEach { (screen, phoneFlag) ->
            assertTrue(
                "the shared rows composable, with the surface's own plate",
                screen.contains("com.kennyb1201.kbstream.ui.settings.AnimeTrackerRows(") &&
                    screen.contains(phoneFlag)
            )
            assertTrue(
                "hosted in a dialog that can be dismissed",
                screen.contains("onDismissRequest = { showAnimeTrackers = false }")
            )
        }
    }

    @Test
    fun `BingeCat lands on its own page, the way Settings opens it`() {
        assertTrue(
            "the first run routes to the real connect screen",
            activity.contains("onOpenBingeCat = { screen = Screen.BingeCat(returnTo = Screen.Home) }")
        )
        assertTrue(
            "and both surfaces pass it",
            activity.contains("OnboardingScreenMobile(") && activity.contains("OnboardingScreen(")
        )
    }

    @Test
    fun `the page does not grow a second copy of a connect flow`() {
        assertFalse(
            "no device-code or OAuth plumbing lives in the onboarding screen",
            tv.contains("deviceCode") || phone.contains("deviceCode") ||
                tv.contains("requestAudioFocus")
        )
        assertTrue(
            "the existing hand-offs are untouched",
            tv.contains("onOpenSimkl") && tv.contains("onOpenTrakt") &&
                phone.contains("onOpenSimkl") && phone.contains("onOpenTrakt")
        )
    }
}
