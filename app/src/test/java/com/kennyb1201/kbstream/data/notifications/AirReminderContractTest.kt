package com.kennyb1201.kbstream.data.notifications

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The air-reminder wiring, asserted at the seams that a unit test cannot reach.
 *
 * A flagged show is announced by exactly the machinery that announces a watched
 * one, so the feature lives in four connections rather than in new logic: the
 * flag has to reach the checker's follow set, the checker has to run when only a
 * flag is present, the schedule has to stay armed while a flag is present, and
 * the poster menu has to be able to set the flag. Each is one line whose absence
 * is silent — the app compiles, the menu looks right, and nothing is ever
 * announced — which is why they are pinned here.
 */
class AirReminderContractTest {

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
        const val CHECKER = "com/kennyb1201/kbstream/data/notifications/NewEpisodeChecker.kt"
        const val WORKER = "com/kennyb1201/kbstream/work/NewEpisodeWorker.kt"
        const val APP = "com/kennyb1201/kbstream/MainApplication.kt"
        const val MENU = "com/kennyb1201/kbstream/ui/components/PosterContextMenu.kt"
        const val STORE = "com/kennyb1201/kbstream/data/notifications/AirReminderStore.kt"
    }

    @Test
    fun `the checker follows flagged shows on top of the watched ones`() {
        val checker = source(CHECKER)
        assertTrue(
            "the checker must add the flagged set to its follow list",
            checker.contains("AirReminderStore(appContext).flagged()")
        )
    }

    @Test
    fun `the checker runs when only an air reminder is present`() {
        val checker = source(CHECKER)
        assertTrue(
            "the checker's gate must not return early on the toggle alone",
            checker.contains("flagged.isEmpty()")
        )
    }

    @Test
    fun `the schedule stays armed while either kind of follow exists`() {
        val worker = source(WORKER)
        assertTrue(
            "the worker must expose a prefs-driven schedule",
            worker.contains("fun syncScheduleForPrefs(")
        )
        assertTrue(
            "the prefs-driven schedule must OR the flag set in",
            worker.contains("AirReminderStore.hasAny(context)")
        )
        // Startup must arm from that same prefs-driven path, or a device that
        // only has flags never schedules anything until reboot.
        assertTrue(
            "startup must arm the round from prefs, not the toggle alone",
            source(APP).contains("syncScheduleForPrefs(this)")
        )
    }

    @Test
    fun `the poster menu can set and clear the reminder`() {
        val menu = source(MENU)
        assertTrue(
            "the menu must offer the reminder row",
            menu.contains("Remind me when it airs")
        )
        assertTrue(
            "toggling the row must re-arm the round",
            menu.contains("NewEpisodeWorker.syncScheduleForPrefs(")
        )
        assertTrue(
            "the flag must be read back so the row can label itself",
            menu.contains("AirReminderStore(context).isFlagged(")
        )
    }

    @Test
    fun `the flag store is profile scoped and exposes hasAny`() {
        val store = source(STORE)
        assertTrue(
            "flags are per profile, like the new-episode snapshot",
            store.contains("ProfileStorage.prefsName(")
        )
        assertTrue("the worker's gate reads hasAny", store.contains("fun hasAny(context: Context)"))
    }
}
