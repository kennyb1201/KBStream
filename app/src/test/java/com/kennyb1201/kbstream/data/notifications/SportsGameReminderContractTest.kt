package com.kennyb1201.kbstream.data.notifications

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The followed-team reminder, end to end through the wiring: the periodic round,
 * its schedule, the toggle, the channel, and the deep link.
 *
 * The rules themselves are pinned in `SportsGameReminderRulesTest`; what is left
 * is the plumbing, which is exactly what a source contract sees and a unit test
 * cannot - WorkManager schedules, a notification channel and a launch-intent
 * extra are all outside a pure function.
 */
class SportsGameReminderContractTest {

    private fun flat(relative: String): String =
        source(relative).replace(Regex("\\s+"), " ")

    private val worker: String by lazy { flat(WORKER) }
    private val hub: String by lazy { flat(HUB) }
    private val viewModel: String by lazy { flat(VIEW_MODEL) }
    private val notifications: String by lazy { flat(NOTIFICATION_CENTER) }
    private val prefs: String by lazy { flat(APP_PREFERENCES) }
    private val app: String by lazy { flat(MAIN_APPLICATION) }
    private val activity: String by lazy { flat(MAIN_ACTIVITY) }

    @Test
    fun `the round runs every half hour and is cancelled when it should not run`() {
        assertTrue(
            "the interval is half an hour",
            worker.contains("private const val INTERVAL_MINUTES = 30L")
        )
        assertTrue(
            "armed as periodic work with UPDATE, so the interval can change on an older request",
            worker.contains("PeriodicWorkRequestBuilder<SportsNotificationWorker>( INTERVAL_MINUTES, TimeUnit.MINUTES )") &&
                worker.contains("ExistingPeriodicWorkPolicy.UPDATE")
        )
        assertTrue(
            "and cancelled by tag when it should not run",
            worker.contains("workManager.cancelAllWorkByTag(TAG)")
        )
    }

    @Test
    fun `it is never scheduled with zero followed teams`() {
        assertTrue(
            "the schedule gate is the toggle AND at least one follow",
            worker.contains(
                "enabled = AppPreferences.getSportsGameReminders(context) && SportsGameReminderRules.shouldSchedule( AppPreferences.getSportsFavoriteTeams(context) )"
            )
        )
        assertTrue(
            "and the round itself refuses to run with nobody followed, in case a stale request outlives an unfollow",
            worker.contains("if (!SportsGameReminderRules.shouldSchedule(favorites)) return")
        )
    }

    @Test
    fun `unfollowing from the hub re-arms the schedule, not just the panel switch`() {
        assertTrue(
            "every follow/unfollow toggle re-syncs the round",
            viewModel.contains("SportsNotificationWorker.syncScheduleForPrefs(getApplication())")
        )
        assertTrue(
            "and the panel switch writes the pref and re-syncs",
            model().contains("fun setGameReminders(enabled: Boolean)") &&
                model().contains("SportsNotificationWorker.syncScheduleForPrefs(getApplication())")
        )
    }

    @Test
    fun `the round does no playback and touches no player`() {
        assertFalse("no player is involved", worker.contains("Player"))
        assertFalse("and no channel is resolved", worker.contains("IptvChannel"))
        assertTrue(
            "it only fetches scoreboards and posts a reminder",
            worker.contains("EspnSportsRepository.shared()") &&
                worker.contains("NotificationCenter.gameReminder(applicationContext, game)")
        )
    }

    @Test
    fun `a game is announced once by recording its id`() {
        assertTrue(
            "the round checks the store before posting",
            worker.contains("SportsGameReminderRules.shouldNotify(game.id, notified[game.id], now)")
        )
        assertTrue(
            "and records the id after a successful post",
            worker.contains("SportsReminderStore.markNotified(prefs, game.id, now)")
        )
        assertTrue(
            "with the expired records pruned each run",
            worker.contains("SportsReminderStore.prune(prefs, now)")
        )
    }

    @Test
    fun `the reminder is a silent channel of its own with a sports deep link`() {
        assertTrue(
            "the channel is the spec's \"Game reminders\"",
            notifications.contains("CHANNEL_GAME_REMINDERS = \"game_reminders\"") &&
                notifications.contains("\"Game reminders\"")
        )
        assertTrue(
            "and it is created at LOW importance, so it is silent by default and DND still governs it",
            notifications.contains("importance = NotificationManager.IMPORTANCE_LOW")
        )
        assertTrue(
            "the tap carries the sports deep-link extra",
            notifications.contains("putExtra(EXTRA_OPEN_SPORTS, true)")
        )
        assertTrue(
            "and MainActivity routes it to the hub - the existing entry point",
            activity.contains("intent?.getBooleanExtra(NotificationCenter.EXTRA_OPEN_SPORTS, false) == true") &&
                activity.contains("screen = Screen.Sports")
        )
    }

    @Test
    fun `the pref defaults on, because following is the opt-in`() {
        assertTrue(
            "the default is true",
            prefs.contains("prefs(context).getBoolean(KEY_SPORTS_GAME_REMINDERS, true)")
        )
        assertTrue(
            "and the hub's panel carries the switch",
            hub.contains("ReminderToggleRow(checked = reminders, onToggle = onToggleReminders)") &&
                hub.contains("\"Notify me when followed teams play\"")
        )
        assertTrue(
            "the switch sits in the leagues panel",
            hub.contains("reminders = gameReminders")
        )
    }

    @Test
    fun `the round is re-armed on every launch`() {
        assertTrue(
            "startup arms it from the current prefs",
            app.contains("SportsNotificationWorker.syncScheduleForPrefs(applicationContext)")
        )
    }

    private fun model(): String = viewModel

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    private companion object {
        const val WORKER = "com/kennyb1201/kbstream/work/SportsNotificationWorker.kt"
        const val HUB = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
        const val NOTIFICATION_CENTER = "com/kennyb1201/kbstream/data/notifications/NotificationCenter.kt"
        const val APP_PREFERENCES = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val MAIN_APPLICATION = "com/kennyb1201/kbstream/MainApplication.kt"
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
    }
}
