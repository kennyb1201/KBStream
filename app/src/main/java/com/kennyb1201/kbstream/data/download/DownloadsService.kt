package com.kennyb1201.kbstream.data.download

import android.app.Notification
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import com.kennyb1201.kbstream.R

/**
 * The foreground service that does the downloading, and the only thing that
 * ever writes to [DownloadsManager]'s manager.
 *
 * Media3 owns this shape: the service is started by the `DownloadService.send*`
 * helpers the manager calls, it keeps the process alive while a transfer runs,
 * and it posts the progress notification through a
 * [DownloadNotificationHelper]. Its whole body is therefore plumbing - the
 * decisions live in the manager.
 *
 * ## Why a television never sees this notification
 *
 * A TV has no Download action anywhere, so nothing there ever calls
 * `sendAddDownload`, so this service is never started and no notification is
 * ever posted. The manager refuses [DownloadsManager.enqueue] on the TV form
 * factor as well, which is the second lock on the same door: an accidental call
 * site cannot produce the P0 regression the spec names (§8) even though it
 * would still have to get past `FormFactor` to do it.
 *
 * There is deliberately no [Scheduler]. A scheduler exists to re-arm the service
 * after a reboot through a JobScheduler/WorkManager job; this app already
 * resumes what is unfinished on every launch
 * ([DownloadsManager.start], called from MainApplication on a phone), and its
 * own background-work conventions live in `work/`. Adding a second scheduling
 * mechanism to re-post a notification after a reboot would be a new subsystem
 * for a case the launch path already covers.
 */
class DownloadsService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    // The default update interval: media3 throttles progress notifications to
    // this, so a fast transfer does not re-post once per byte.
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    NOTIFICATION_CHANNEL_ID,
    R.string.downloads_channel_name,
    R.string.downloads_channel_description
) {

    override fun getDownloadManager(): DownloadManager =
        DownloadsManager.ensure(this)
            ?: error("download manager unavailable: no usable download directory")

    /** No scheduler, see the class comment. */
    override fun getScheduler(): Scheduler? = null

    /**
     * The progress notification.
     *
     * `notMetRequirements` is what turns "Queued" into the honest reason why -
     * with the Wi-Fi-only rule in force, a download waiting for an unmetered
     * network says so instead of looking stalled.
     *
     * No content intent: a download's destination is the Downloads screen, which
     * is reached from Settings, and a notification that deep-links into a screen
     * this app cannot name from a service would be a second navigation path to
     * keep in step. Recorded here as the deliberate gap rather than as an
     * oversight.
     */
    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int
    ): Notification = DownloadNotificationHelper(this, NOTIFICATION_CHANNEL_ID)
        .buildProgressNotification(
            this,
            R.drawable.ic_notification,
            null,
            null,
            downloads,
            notMetRequirements
        )

    companion object {
        /**
         * The notification's own id. Above the app's other notification ids
         * (NotificationCenter's reminder ids are derived from rule hashes) so a
         * download's progress cannot collide with an alert and replace it.
         */
        const val FOREGROUND_NOTIFICATION_ID = 4300

        /** One channel for downloads, beside NotificationCenter's three. */
        const val NOTIFICATION_CHANNEL_ID = "downloads"
    }
}
