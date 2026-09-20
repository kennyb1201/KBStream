package com.kennyb1201.kbstream.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.iptv.IptvReminderStore
import com.kennyb1201.kbstream.data.notifications.ReminderNotifier
import com.kennyb1201.kbstream.data.notifications.ReminderRules
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Delivers live-TV programme reminders while the app is in the background.
 *
 * One delayed job per reminder, armed at the programme's start time (see
 * [ReminderNotifier] for what the job does). A single periodic job was the
 * obvious alternative but WorkManager's floor for periodic work is 15
 * minutes, which would announce a programme that started a quarter of an hour
 * ago — not what "remind me when this starts" means. Delayed work survives
 * reboots and app kills, so nothing needs re-arming at boot.
 */
class ReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        ReminderNotifier(applicationContext).run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The next reminder's job covers whatever this pass missed; a retry
        // storm would only re-announce or thrash.
        Log.e(TAG, "reminder pass failed: ${e.message}", e)
        Result.success()
    }

    companion object {
        private const val TAG = "REMINDER_WORKER"
        private const val WORK_PREFIX = "iptv_reminder_"

        /**
         * Keeps armed work in step with the user's "Live TV Reminders" toggle
         * and with the reminders currently stored: switching the toggle off
         * cancels everything, switching it on arms every reminder that has not
         * started yet. Startup calls this too, which is what re-arms reminders
         * set on a previous run.
         */
        fun syncSchedule(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelAllWorkByTag(TAG)
                return
            }
            val now = System.currentTimeMillis()
            IptvReminderStore.load(IptvReminderStore.prefsFor(context)).forEach { reminder ->
                // Already-started programmes are the guide banner's job; arming
                // them would announce something the user is already late for.
                if (ReminderRules.isStale(reminder.endUtcMillis, now)) return@forEach
                if (reminder.startUtcMillis > now) arm(context, reminder.key, reminder.startUtcMillis)
            }
        }

        /** Arms (or re-arms) the job for one reminder at its start time. */
        fun arm(context: Context, reminderKey: String, startUtcMillis: Long) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    workName(reminderKey),
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ReminderWorker>()
                        .setInitialDelay(
                            ReminderRules.delayUntilStart(
                                startUtcMillis,
                                System.currentTimeMillis()
                            ),
                            TimeUnit.MILLISECONDS
                        )
                        .addTag(TAG)
                        .build()
                )
            }.onFailure { Log.w(TAG, "could not arm reminder: ${it.message}") }
        }

        /** Drops the job for a reminder the user just removed. */
        fun cancel(context: Context, reminderKey: String) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName(reminderKey)) }
        }

        /** Work names are opaque and hash-based: a channel id can be a URL. */
        private fun workName(reminderKey: String): String =
            WORK_PREFIX + ReminderRules.notificationId(reminderKey)
    }
}
