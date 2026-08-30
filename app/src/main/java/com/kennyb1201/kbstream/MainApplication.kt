package com.kennyb1201.kbstream

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.kennyb1201.kbstream.work.SimklSyncWorker
import java.util.concurrent.TimeUnit

class MainApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        scheduleSimklPeriodicSync()
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(true)
            .components {
                add(OkHttpNetworkFetcherFactory())
            }
            .build()
    }

    private fun scheduleSimklPeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SimklSyncWorker>(
            12, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SIMKL_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        const val SIMKL_SYNC_WORK_NAME = "simkl_periodic_sync"
    }
}
