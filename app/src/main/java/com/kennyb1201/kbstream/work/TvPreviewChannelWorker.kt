package com.kennyb1201.kbstream.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.tv.TvPreviewChannelPublisher
import kotlinx.coroutines.CancellationException

/**
 * The launcher preview channel's periodic round (spec 2026-11-16, F4).
 *
 * Fifteen minutes, armed by [TvPreviewChannelPublisher] once the channel exists
 * and persisted across reboots by WorkManager - the app's one scheduling layer.
 * It does the same reconcile the foreground publish does, from the same list: the
 * snapshot the Continue Watching widget last wrote. It reads nothing else, so a
 * round can never put a card in the channel that the in-app rail has not
 * published.
 *
 * A round on a phone (or with a provider that refused the writes) is a no-op:
 * [TvPreviewChannelPublisher.refresh] guards on both.
 */
class TvPreviewChannelWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        TvPreviewChannelPublisher.refresh(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A failed reconcile keeps whatever the channel already draws; the next
        // round and the next Home publish cover it. Never a retry storm.
        Result.success()
    }
}
