package com.kennyb1201.kbstream.data.notifications

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.iptv.IptvReminderStore
import com.kennyb1201.kbstream.ui.settings.AppPreferences

/**
 * Delivers live-TV programme reminders as system notifications.
 *
 * Reminders used to be delivered only by the guide's own in-screen poller, so
 * "REMIND ME" silently did nothing unless the guide happened to be open at the
 * moment the programme started. This is the out-of-screen half: it reads the
 * same per-profile reminder store the guide writes and announces anything that
 * has started, once.
 *
 * Deliberately does NOT remove a reminder after announcing it — the guide's
 * banner is the "WATCH NOW" affordance for a running programme, and killing
 * the reminder on announce would take that away. Reminders are dropped by the
 * same staleness rule the guide uses.
 */
internal class ReminderNotifier(private val context: Context) {

    suspend fun run() {
        if (!AppPreferences.getLiveReminderNotifications(context)) return

        val prefs = IptvReminderStore.prefsFor(context)
        val reminders = IptvReminderStore.load(prefs)
        if (reminders.isEmpty()) {
            IptvReminderStore.pruneNotified(prefs, emptySet())
            return
        }

        val notified = IptvReminderStore.notifiedKeys(prefs)
        val now = System.currentTimeMillis()
        var posted = 0

        reminders.forEach { reminder ->
            val due = ReminderRules.isDue(
                startUtcMillis = reminder.startUtcMillis,
                nowMs = now,
                alreadyNotified = reminder.key in notified
            )
            if (due) {
                val delivered = NotificationCenter.programmeReminder(
                    context = context,
                    reminderKey = reminder.key,
                    channelId = reminder.channelId,
                    channelName = reminder.channelName,
                    programmeTitle = reminder.programmeTitle
                )
                // Only record the announcement if the system actually took it:
                // a blocked notification must not burn the one shot.
                if (delivered) {
                    IptvReminderStore.markNotified(prefs, reminder.key)
                    posted++
                }
            }
            if (ReminderRules.isStale(reminder.endUtcMillis, now)) {
                IptvReminderStore.remove(prefs, reminder.channelId, reminder.startUtcMillis)
            }
        }

        // Announcement keys are only meaningful while their reminder lives.
        IptvReminderStore.pruneNotified(
            prefs,
            IptvReminderStore.load(prefs).map { it.key }.toSet()
        )
        if (posted > 0) Log.i(TAG, "announced $posted live reminder(s)")
    }

    private companion object {
        const val TAG = "REMINDERS"
    }
}
