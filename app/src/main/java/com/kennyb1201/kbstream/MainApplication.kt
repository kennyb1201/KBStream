package com.kennyb1201.kbstream

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kennyb1201.kbstream.data.youtube.NewPipeDownloader
import com.kennyb1201.kbstream.work.SimklSyncWorker
import org.schabi.newpipe.extractor.NewPipe
import java.util.concurrent.TimeUnit

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        NewPipe.init(NewPipeDownloader())
        scheduleSimklPeriodicSync()
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
