package com.kennyb1201.kbstream

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.directory
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
        // Crash reporting FIRST so every step below is observable.
        runCatching { initCrashReporting() }
        // Startup isolation: an exception thrown from Application.onCreate
        // kills the process before any Activity exists — on a TV that is a
        // black screen for ~2s, then the launcher. Each step is independent
        // (sync, workers, addon refresh, update check); losing any one of
        // them degrades a feature, but must never take down the launch.
        // runCatching catches Throwable, covering Error subclasses that the
        // inner try/catch(Exception) blocks cannot see.
        runCatching { com.kennyb1201.kbstream.data.sync.SupabaseSync.init(this) }
            .onFailure {
                com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                    it, mapOf("source" to "app_create_supabase_init")
                )
            }
        runCatching { scheduleSimklPeriodicSync() }
            .onFailure {
                com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                    it, mapOf("source" to "app_create_simkl_worker")
                )
            }
        runCatching { scheduleAddonManifestRefresh() }
            .onFailure {
                com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                    it, mapOf("source" to "app_create_addon_worker")
                )
            }
        // Launch-time auto-update: picks up addon manifest changes on the
        // first launch after any restart. Throttled internally so frequent
        // app relaunches don't spam every manifest URL; runs on a background
        // scope, so startup is never blocked. The daily worker covers
        // long-running installs that stay alive for days.
        runCatching {
            com.kennyb1201.kbstream.data.addon.AddonManager.getInstance(this)
                .maybeRefreshOnLaunch(this)
        }.onFailure {
            com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                it, mapOf("source" to "app_create_addon_launch_refresh")
            )
        }
        // Self-update: quiet GitHub-release check at most every 12h; only
        // downloads when the user accepts the prompt in Settings.
        runCatching { com.kennyb1201.kbstream.data.update.AppUpdater.maybeAutoCheck(this) }
            .onFailure {
                com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                    it, mapOf("source" to "app_create_update_check")
                )
            }
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
            // Build identity on every event: crash reports aggregate per
            // release in the dashboard and each one pins the exact build
            // number + commit, so a report maps to the APK that produced it.
            // CI stamps VERSION_CODE/VERSION_NAME/GIT_SHA (build.gradle.kts).
            options.release = "kbstream@${BuildConfig.VERSION_NAME}"
            options.dist = BuildConfig.VERSION_CODE.toString()
            options.setTag("git_sha", BuildConfig.GIT_SHA.take(10))
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
            // Coil 3 ships NO default disk cache — without this every app
            // restart re-downloads every poster/backdrop over the network,
            // which on a TV means slow, half-populated rails after relaunch.
            // Sized from usable storage: ~2% with a 32 MB floor and a
            // 256 MB ceiling so small-stick devices stay reasonable.
            .diskCache(
                coil3.disk.DiskCache.Builder()
                    .directory(
                        java.io.File(
                            context.applicationContext.cacheDir,
                            "image_cache"
                        )
                    )
                    .maxSizeBytes(imageDiskCacheBytes(context))
                    .build()
            )
            // KB badge packs commonly ship .svg chip art; without this
            // decoder those badges silently fail to render (blank chips).
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

    /**
     * Coil image disk-cache budget: 2% of usable storage, clamped to
     * [32 MB, 256 MB]. Pure disk math — no Context retained.
     */
    private fun imageDiskCacheBytes(context: android.content.Context): Long {
        val usable = context.cacheDir.usableSpace
        val twoPercent = usable * 2 / 100
        return twoPercent.coerceIn(32L * 1024 * 1024, 256L * 1024 * 1024)
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
