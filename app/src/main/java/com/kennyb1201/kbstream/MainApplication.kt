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
import com.kennyb1201.kbstream.work.AddonManifestRefreshWorker
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
        scheduleAddonManifestRefresh()
        // Launch-time auto-update: picks up addon manifest changes on the
        // first launch after any restart. Throttled internally so frequent
        // app relaunches don't spam every manifest URL; runs on a background
        // scope, so startup is never blocked. The daily worker covers
        // long-running installs that stay alive for days.
        com.kennyb1201.kbstream.data.addon.AddonManager.getInstance(this)
            .maybeRefreshOnLaunch(this)
        // Self-update: quiet GitHub-release check at most every 12h; only
        // downloads when the user accepts the prompt in Settings.
        com.kennyb1201.kbstream.data.update.AppUpdater.maybeAutoCheck(this)
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
            // KB badge packs commonly ship .svg chip art; without this
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

    private fun scheduleAddonManifestRefresh() {
        val request = PeriodicWorkRequestBuilder<AddonManifestRefreshWorker>(
            24, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ADDON_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        const val SIMKL_SYNC_WORK_NAME = "simkl_periodic_sync"
        const val ADDON_REFRESH_WORK_NAME = "addon_manifest_refresh"
    }
}
