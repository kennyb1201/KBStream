package com.kennyb1201.kbstream.data.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt's wiring, read off the sources (spec 2026-11-25, F1).
 *
 * The companion to [PlayUpdatePromptContractTest]: that one runs the state
 * machine, this one reads the two files that decide who is allowed to restart
 * the app. They exist apart because only the state machine needs Play's own
 * classes, and a pin that a build cannot run is a pin nobody checks between CI
 * runs.
 *
 * The bug was one line - `completeUpdate()` called from the `DOWNLOADED`
 * callback - so the pins are about call sites: exactly one hand-off to Play in
 * the updater (the declaration and the API call inside it), exactly one call in
 * the shell (the prompt's Open), and none in the Settings row or either
 * callback branch.
 */
class PlayUpdatePromptWiringContractTest {

    private fun projectFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("not found: $relative")
    }

    private fun source(relative: String): String =
        File(projectFile("app/build.gradle.kts").parentFile, relative).readText()

    private val playUpdater: String
        get() = source("src/main/java/com/kennyb1201/kbstream/data/update/PlayUpdater.kt")

    private val mainActivity: String
        get() = source("src/main/java/com/kennyb1201/kbstream/MainActivity.kt")

    private val settingsMobile: String
        get() = source("src/main/java/com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    /** The source of one private function, from its declaration to the next. */
    private fun function(name: String): String {
        val start = playUpdater.indexOf("private fun $name(")
        if (start < 0) throw AssertionError("no private fun $name in PlayUpdater.kt")
        val end = playUpdater.indexOf("private fun ", start + 1)
        return if (end < 0) playUpdater.substring(start) else playUpdater.substring(start, end)
    }

    @Test
    fun `no callback branch hands the update to Play`() {
        val listener = playUpdater
            .substringAfter("private val installListener")
            .substringBefore("internal fun stateForInstallStatus")
        assertFalse(
            "the download finishing must never restart the app on its own",
            listener.contains("completeUpdate()")
        )
        assertFalse(
            "a downloaded update found at launch is offered, not installed",
            function("adopt").contains("completeUpdate()")
        )
        assertEquals(
            "completeUpdate() exists as the declaration and Play's own call inside " +
                "it, and nowhere else in the updater",
            2,
            occurrences(playUpdater, "completeUpdate()")
        )
    }

    @Test
    fun `the phone prompt is the only caller of completeUpdate`() {
        assertEquals(
            "Open in the shell's prompt is THE path: exactly one call site",
            1,
            occurrences(mainActivity, "PlayUpdater.completeUpdate()")
        )
        assertTrue(
            "and the prompt is drawn by the shell, not by a Settings row",
            mainActivity.contains("private fun PlayUpdatePrompt()")
        )
        assertFalse(
            "the About row is a door to the prompt now, never a direct restart",
            settingsMobile.contains("PlayUpdater.completeUpdate()")
        )
        assertTrue(
            "tapping it opens the same prompt",
            settingsMobile.contains("PlayUpdater.requestPrompt()")
        )
    }
}
