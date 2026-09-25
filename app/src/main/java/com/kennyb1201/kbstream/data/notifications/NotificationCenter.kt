package com.kennyb1201.kbstream.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kennyb1201.kbstream.MainActivity
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher

/**
 * The app's only notification surface.
 *
 * Everything that posts goes through here so the channel, the permission
 * check, and the tap target stay in one place: a notification that doesn't
 * navigate where it says it will is worse than no notification.
 */
internal object NotificationCenter {

    private const val TAG = "NOTIFICATIONS"

    const val CHANNEL_NEW_EPISODES = "new_episodes"
    const val CHANNEL_LIVE_REMINDERS = "live_reminders"

    /**
     * Extras on the guide deep link: which channel a reminder tap should tune
     * to. MainActivity reads them, the guide resolves the channel against its
     * loaded lineup.
     */
    const val EXTRA_REMINDER_CHANNEL_ID = "kbstream_reminder_channel_id"

    /** Two channels, so a user can silence one kind of alert without the other. */
    private data class ChannelSpec(
        val id: String,
        val name: String,
        val description: String
    )

    private val CHANNELS = listOf(
        ChannelSpec(
            CHANNEL_NEW_EPISODES,
            "New episodes",
            "Alerts when a new episode of a show you watch has aired."
        ),
        ChannelSpec(
            CHANNEL_LIVE_REMINDERS,
            "Live TV reminders",
            "Alerts when a live programme you asked to be reminded about starts."
        )
    )

    /**
     * Registers the channels once per process start. Safe to call repeatedly:
     * creating an existing channel is a no-op, and a channel the user already
     * tuned (importance, sound) is never clobbered.
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager =
            runCatching {
                context.getSystemService(NotificationManager::class.java)
            }.getOrNull() ?: return
        CHANNELS.forEach { spec ->
            if (manager.getNotificationChannel(spec.id) != null) return@forEach
            val channel = NotificationChannel(
                spec.id,
                spec.name,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = spec.description
                setShowBadge(true)
            }
            runCatching { manager.createNotificationChannel(channel) }
                .onFailure { Log.w(TAG, "channel registration failed: ${it.message}") }
        }
    }

    /**
     * Posts the "your programme is starting" alert for a live-TV reminder and
     * deep-links its tap into the guide.
     *
     * The tap deliberately does NOT tune straight into the player: a reminder
     * stores the channel identity but no stream URL (the guide resolves that
     * from the live playlist), and the guide is where a dead channel can still
     * fall back to something sensible. MainActivity hands the channel id over
     * and the guide plays it as soon as its lineup is loaded.
     */
    fun programmeReminder(
        context: Context,
        reminderKey: String,
        channelId: String,
        channelName: String,
        programmeTitle: String
    ): Boolean {
        ensureChannels(context)
        if (!canPost(context)) {
            Log.i(TAG, "reminder for $channelId skipped: notifications disabled")
            return false
        }

        val body = buildString {
            append(programmeTitle.ifBlank { "Your programme" })
            append(" is starting now")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_LIVE_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(channelName.ifBlank { "Live TV" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(reminderIntent(context, reminderKey, channelId))
            .build()

        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(ReminderRules.notificationId(reminderKey), notification)
            true
        }.getOrElse {
            Log.w(TAG, "could not post reminder: ${it.message}")
            false
        }
    }

    /**
     * Tap target: the guide, carrying the channel to play. Same activity flags
     * as the new-episode deep link and the TV Watch Next cards — CLEAR_TOP
     * without SINGLE_TOP deliberately recreates the activity so the
     * launch-intent handler (which reads extras once, at startup) sees them.
     */
    private fun reminderIntent(
        context: Context,
        reminderKey: String,
        channelId: String
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_REMINDER_CHANNEL_ID, channelId)
        }
        return PendingIntent.getActivity(
            context,
            ReminderRules.notificationId(reminderKey),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Whether the OS will actually show anything. False when the user turned
     * the app's notifications off in system settings (Android 13+ also needs
     * the runtime POST_NOTIFICATIONS grant).
     */
    fun canPost(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(false)

    /**
     * Posts (or replaces) the new-episode alert for one show and deep-links
     * its tap to that show's detail screen. Returns true when the system took
     * the notification.
     */
    fun newEpisode(
        context: Context,
        showId: String,
        showTitle: String,
        episodeKey: String,
        episodeName: String?
    ): Boolean {
        ensureChannels(context)
        if (!canPost(context)) {
            Log.i(TAG, "new episode for $episodeKey skipped: notifications disabled")
            return false
        }

        val body = buildString {
            append(episodeKey)
            episodeName?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
            append(" is ready to watch")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_EPISODES)
            // A plain vector, not a launcher icon: small icons are alpha-masked
            // (adaptive XML icons render as a solid blob), and at ~18dp a
            // bezelled button loses its ring -- ic_notification is the bare
            // play glyph, scaled up to fill the frame.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(showTitle)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(detailIntent(context, showId))
            .build()

        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(NewEpisodeRules.notificationId(showId), notification)
            true
        }.getOrElse {
            // Most likely a missing POST_NOTIFICATIONS grant on Android 13+;
            // never let a failed alert break the worker that produced it.
            Log.w(TAG, "could not post new episode: ${it.message}")
            false
        }
    }

    /**
     * Tap target: the show's detail screen. Mirrors the TV Watch Next deep
     * link exactly (same extras, same flags) — MainActivity already routes
     * `EXTRA_TYPE`/`EXTRA_ID` into `Screen.Detail`, so both paths land on the
     * same screen and a "tv" type is normalized to "series" there.
     *
     * CLEAR_TOP without SINGLE_TOP deliberately recreates the activity so the
     * launch-intent handler sees these extras; that handler only reads them
     * once, at startup.
     */
    private fun detailIntent(context: Context, showId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TvLauncherPublisher.EXTRA_TYPE, "tv")
            putExtra(TvLauncherPublisher.EXTRA_ID, showId)
        }
        return PendingIntent.getActivity(
            context,
            NewEpisodeRules.notificationId(showId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
