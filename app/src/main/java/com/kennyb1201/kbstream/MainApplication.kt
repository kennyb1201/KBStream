package com.kennyb1201.kbstream

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import com.kennyb1201.kbstream.work.SimklSyncWorker
import io.sentry.android.core.SentryAndroid
import java.util.concurrent.TimeUnit

class MainApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext =
            applicationContext
        com.kennyb1201.kbstream.data.sync.SupabaseSync.appContextRef =
            java.lang.ref.WeakReference(applicationContext)
        com.kennyb1201.kbstream.data.sync.SupabaseSync.init(this)
        initCrashReporting()
        scheduleSimklPeriodicSync()
    }

    /**
     * Crash reporting via Sentry. Only initializes when a DSN was baked into
     * the build (SENTRY_DSN in local.properties or the CI environment);
     * without one the app behaves exactly as before.
     */
    private fun initCrashReporting() {
        if (BuildConfig.SENTRY_DSN.isBlank()) return
        SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(true)
            // TV browsing shows hundreds of posters/backdrops; a generous
            // memory cache keeps tiles resident so re-scrolling a rail never
            // re-decodes (the default is a small fraction of free RAM).
            .memoryCache(
                coil3.memory.MemoryCache.Builder()
                    .maxSizePercent(context, 0.30)
                    .build()
            )
            // Nuvio badge packs commonly ship .svg chip art; without this
            // decoder those badges silently fail to render (blank chips).
            .components { add(SvgDecoder.Factory()) }
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
