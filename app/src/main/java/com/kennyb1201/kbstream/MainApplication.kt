package com.kennyb1201.kbstream

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
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
import com.kennyb1201.kbstream.data.memory.MemoryPressure
import com.kennyb1201.kbstream.data.memory.releaseImageMemoryCache
import com.kennyb1201.kbstream.data.reporting.CrashReporter
import com.kennyb1201.kbstream.data.reporting.PerfTrace
import com.kennyb1201.kbstream.data.reporting.Redaction
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.work.AddonManifestRefreshWorker
import com.kennyb1201.kbstream.work.CacheMaintenanceWorker
import com.kennyb1201.kbstream.work.NewEpisodeWorker
import com.kennyb1201.kbstream.work.OutboxFlushWorker
import com.kennyb1201.kbstream.work.ReminderWorker
import com.kennyb1201.kbstream.work.SimklSyncWorker
import io.sentry.android.core.SentryAndroid
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.kennyb1201.kbstream.data.runCatchingCancellable

class MainApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // Start the clock the diagnostics perf block measures startup against.
        // Must stay first: everything below is part of the launch cost.
        com.kennyb1201.kbstream.data.reporting.PerfTrace.markAppStart()
        // Build fingerprint, logged before anything that can fail. The question
        // every device test turns on is "which build is this?": a logcat full of
        // behavior from a stale APK wastes the whole session, and a suppression
        // record written by an older build reads as a live bug. Log.i survives
        // release minification — -assumenosideeffects strips only Log.v/Log.d.
        Log.i(
            "APP_BUILD",
            "KBStream ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})" +
                " sha=${BuildConfig.GIT_SHA}"
        )
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
        // Notification channels must exist before anything posts; created here
        // (not in the worker) so a tap target is never missing on first alert.
        runCatching {
            com.kennyb1201.kbstream.data.notifications.NotificationCenter
                .ensureChannels(applicationContext)
        }
        // Everything below this point is scheduling and bookkeeping:
        // WorkManager enqueues (each a write into WorkManager's own database),
        // four SharedPreferences files read for their throttles, and two
        // throttled checks. None of it can change what the first frame shows,
        // and as main-thread I/O it sat directly in front of that first frame
        // on every cold start. One hop to the IO pool takes the whole chain off
        // the launch path; the steps keep their individual isolation, and each
        // is timed into PerfTrace so the cost stays visible (see Diagnostics)
        // rather than assumed.
        startupScope.launch {
            startupStep("startup.simklWorker", "app_create_simkl_worker") {
                scheduleSimklPeriodicSync()
            }
            // Durable sync outbox: retry anything stranded by a previous
            // process death (an offline write that never reached the cloud).
            // The worker is network-gated and survives the app being closed.
            startupStep("startup.outboxFlush", "app_create_outbox_flush_worker") {
                OutboxFlushWorker.enqueue(applicationContext)
            }
            startupStep("startup.newEpisodeWorker", "app_create_new_episode_worker") {
                scheduleNewEpisodeChecks()
            }
            startupStep("startup.reminderWorker", "app_create_reminder_worker") {
                scheduleReminderAlerts()
            }
            startupStep("startup.addonWorker", "app_create_addon_worker") {
                scheduleAddonManifestRefresh()
            }
            // Storage: the TMDB JSON cache is the app's only unbounded disk
            // store, and deleting rows from it never gave the file's space
            // back. Both halves are handled here - the budget on the write
            // path, and the VACUUM that only a background pass can take the
            // lock for - with a one-off run so an install that already grew
            // gets reclaimed now rather than at the next daily tick.
            startupStep("startup.cacheMaintenance", "app_create_cache_maintenance") {
                CacheMaintenanceWorker.schedule(applicationContext)
            }
            // IPTV guide: enqueue the periodic EPG refresh. The only caller
            // used to be the cloud-sync prefs applier, so a guide configured in
            // the app itself never got a background refresh and went stale
            // between manual ones. No-op when no guide is configured (the
            // worker returns success on a blank epg_url), and WorkManager
            // persists the request across process death, so this covers the app
            // being closed.
            startupStep("startup.epgWorker", "app_create_epg_worker") {
                com.kennyb1201.kbstream.data.iptv.EpgRefreshScheduler
                    .schedule(applicationContext)
            }
            // Launch-time auto-update: picks up addon manifest changes on the
            // first launch after any restart. Throttled internally so frequent
            // app relaunches don't spam every manifest URL. The daily worker
            // covers long-running installs that stay alive for days.
            startupStep("startup.addonRefresh", "app_create_addon_launch_refresh") {
                com.kennyb1201.kbstream.data.addon.AddonManager
                    .getInstance(this@MainApplication)
                    .maybeRefreshOnLaunch(this@MainApplication)
            }
            // Home catalog warm: start the Home catalogs' first pages fetching
            // while the launcher is still putting Home together, so the rail
            // build inherits the head start instead of issuing every request
            // itself (see AddonManager.warmHomeCatalogCache). A nested launch of
            // its own rather than one more step in the chain: it lasts as long
            // as the add-on hosts take, and the steps after it are disk work
            // that must not queue up behind a catalog fetch.
            startupScope.launch {
                runCatchingCancellable {
                    PerfTrace.timedSuspend("startup.catalogWarm") {
                        com.kennyb1201.kbstream.data.addon.AddonManager
                            .getInstance(this@MainApplication)
                            .warmHomeCatalogCache()
                    }
                }.onFailure { error ->
                    CrashReporter.recordNonFatal(
                        error,
                        mapOf("source" to "app_create_catalog_warm")
                    )
                }
            }
            // Self-update: if an install was handed to the system installer
            // before this process existed, say whether it landed. Runs first
            // so the confirmation outranks the 12h check below (which is a
            // no-op while that confirmation is the current update state).
            startupStep("startup.updateConfirm", "app_create_update_confirm") {
                com.kennyb1201.kbstream.data.update.AppUpdater
                    .confirmInstallOnLaunch(this@MainApplication)
            }
            // Self-update: quiet GitHub-release check at most every 12h; only
            // downloads when the user accepts the prompt in Settings.
            startupStep("startup.updateCheck", "app_create_update_check") {
                com.kennyb1201.kbstream.data.update.AppUpdater
                    .maybeAutoCheck(this@MainApplication)
            }
        }
    }

    /**
     * Launch-time work that does not have to finish before the first frame.
     *
     * Dispatched to IO because every step is disk or database work (WorkManager
     * enqueues and prefs reads), and because nothing here is read by the UI
     * until long after the first frame. The Application outlives every screen,
     * so the scope is never canceled; SupervisorJob keeps one failing step
     * from taking the rest of the chain down with it.
     */
    private val startupScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One launch step: isolated, and timed into PerfTrace.
     *
     * An exception here must degrade a single feature and never the launch
     * (Application.onCreate throwing kills the process before any Activity
     * exists -- on a TV that is a black screen, then the launcher), so this
     * keeps the runCatching that every call site used to spell out.
     */
    private fun startupStep(label: String, source: String, block: () -> Unit) {
        runCatching { PerfTrace.timed(label, block) }
            .onFailure { CrashReporter.recordNonFatal(it, mapOf("source" to source)) }
    }

    /**
     * Crash reporting via Sentry. Only initializes when a DSN was baked into
     * the build (SENTRY_DSN in local.properties or the CI environment) AND
     * this process is not a JVM unit test (see CrashReporter.isJvmUnitTest);
     * without either, the app behaves exactly as before and a Robolectric run
     * cannot report into the production project.
     *
     * Native (NDK) crashes are covered by this same call, and deliberately so:
     * `io.sentry:sentry-android` depends on `io.sentry:sentry-android-ndk`,
     * which bundles sentry-native as libsentry.so for all four ABIs, and
     * SentryAndroid.init installs io.sentry.android.core.NdkIntegration as
     * soon as it finds io.sentry.android.ndk.SentryNdk on the classpath. That
     * is the half that matters on the devices this ships to — libmpv, the
     * software FFmpeg decoder, the MediaCodec shim and the Dolby Vision
     * compat layer all run native code, and a native abort (SIGSEGV/SIGABRT)
     * never becomes a Java exception for CrashReporter to see.
     *
     * Two edits would silently take it away again, so they are written down
     * here: an `exclude group: "io.sentry"` (the kind added to drop a
     * transitive module such as sentry-android-replay) or a switch to
     * `sentry-android-core` removes the native half with no build or runtime
     * signal, and the manifest meta-data `io.sentry.ndk.enable = false`
     * disables it outright. Native frames come back symbolicated only when the
     * Sentry Gradle plugin uploads the native debug symbols (uploadNativeSymbols
     * in the sentry block of app/build.gradle.kts, off by default) — without
     * them the crash is still reported, with addresses instead of function
     * names.
     */
    private fun initCrashReporting() {
        // Refuses for a build with no DSN baked in, and for any JVM unit test
        // even when one is - see CrashReporter.shouldInitCrashReporting.
        if (!CrashReporter.shouldInitCrashReporting(BuildConfig.SENTRY_DSN)) return
        SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            // Build identity on every event: crash reports aggregate per
            // release in the dashboard and each one pins the exact build
            // number + commit, so a report maps to the APK that produced it.
            // CI stamps VERSION_CODE/VERSION_NAME/GIT_SHA (build.gradle.kts).
            options.release = "kbstream@${BuildConfig.VERSION_NAME}"
            options.dist = BuildConfig.VERSION_CODE.toString()
            options.setTag("git_sha", BuildConfig.GIT_SHA.take(10))
            // Scrub the TEXT of everything that leaves the device. supabase-kt
            // request exceptions embed the full request URL and headers --
            // bearer token included -- in the exception message (see the note
            // in SupabaseSync.recordSyncError), and the raw Throwable is what
            // gets captured, so the event is kept and only its message text is
            // rewritten. The stack trace -- the reason the report exists -- is
            // untouched. See Redaction for what is masked.
            options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                event.message?.let { message -> message.message = Redaction.text(message.message) }
                event.exceptions?.forEach { exception ->
                    exception.value = Redaction.text(exception.value)
                }
                event
            }
            // Breadcrumbs carry text too, and a future logcat/HTTP integration
            // would route Log.* and request URLs through here without any other
            // code change, so they get the same scrub.
            options.beforeBreadcrumb = io.sentry.SentryOptions.BeforeBreadcrumbCallback { crumb, _ ->
                crumb.message = Redaction.text(crumb.message)
                crumb.data?.let { data ->
                    data.keys.toList().forEach { key ->
                        val value = data[key]
                        if (value is String) crumb.setData(key, Redaction.text(value))
                    }
                }
                crumb
            }
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
                    .maxSizeBytes(imageMemoryCacheBytes(context))
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

    /**
     * Coil image memory-cache budget: 15% of the reported heap class, floored
     * at 24 MB and capped at 64 MB.
     *
     * A plain percentage is not safe here. `android:largeHeap="true"` raises
     * the memory class the system reports (512 MB on the field TV), and the
     * previous 30% of that was ~150 MB of decoded bitmaps on a device whose
     * Java heap growth limit is 192 MB — the same heap the player's buffers
     * have to fit inside. The cap is what makes the budget survivable; the
     * floor keeps rails from re-decoding on every scroll on small boxes.
     */
    private fun imageMemoryCacheBytes(context: android.content.Context): Long {
        val activityManager =
            context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as? ActivityManager
                ?: return 48L * 1024 * 1024
        val heapBytes = activityManager.memoryClass.toLong() * 1024 * 1024
        return (heapBytes * 15 / 100).coerceIn(24L * 1024 * 1024, 64L * 1024 * 1024)
    }

    /**
     * The system is out of memory right now: hand back everything that can be
     * rebuilt. Called on the main thread and may be called at any time, so it
     * must not block, allocate, or throw.
     */
    override fun onLowMemory() {
        super.onLowMemory()
        runCatching {
            releaseImageMemoryCache(this)
            MemoryPressure.releaseBrowsingCaches()
        }
    }

    /**
     * Memory-pressure response, ordered by what it costs to rebuild.
     *
     * Bitmaps are the biggest reclaimable block and cost only a decode from
     * Coil's disk cache to get back, so they go first. The guide caches cost a
     * database read to rebuild, so they wait for real pressure or for the app
     * going away — by then the user is not looking at the guide anyway.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                releaseImageMemoryCache(this)
            }
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                MemoryPressure.releaseBrowsingCaches()
            }
        }
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

    /**
     * Twice-daily "has a new episode aired?" round. Same cadence and network
     * gate as the Simkl refresh, but a separate job so new-episode alerts
     * don't depend on a connected tracker account.
     *
     * Honors the settings toggle: switching notifications off cancels the
     * work instead of leaving a job that wakes up only to bail out.
     */
    private fun scheduleNewEpisodeChecks() {
        NewEpisodeWorker.syncSchedule(
            this,
            AppPreferences.getNewEpisodeNotifications(this)
        )
    }

    /**
     * Live-TV program reminder alerts. Each reminder gets its own delayed
     * job armed at the program's start time; this is what re-arms the
     * reminders stored on a previous run, and what honors the toggle.
     */
    private fun scheduleReminderAlerts() {
        ReminderWorker.syncSchedule(
            this,
            AppPreferences.getLiveReminderNotifications(this)
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
