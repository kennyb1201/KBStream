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
import coil3.bitmapFactoryMaxParallelism
import coil3.disk.directory
import coil3.svg.SvgDecoder
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.memory.ImageCacheTrim
import com.kennyb1201.kbstream.data.memory.MemoryPressure
import com.kennyb1201.kbstream.data.memory.releaseImageMemoryCache
import com.kennyb1201.kbstream.data.memory.trimImageMemoryCache
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
import com.kennyb1201.kbstream.data.reporting.ImageLoadTrace
import com.kennyb1201.kbstream.data.reporting.CrashReporter
import com.kennyb1201.kbstream.data.reporting.PerfTrace
import com.kennyb1201.kbstream.data.reporting.PlaybackDeathMarker
import com.kennyb1201.kbstream.data.reporting.ProcessExitRecord
import com.kennyb1201.kbstream.data.reporting.Redaction
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.youtube.KBStreamDownloader
import com.kennyb1201.kbstream.data.youtube.NewPipeManager
import com.kennyb1201.kbstream.work.AddonManifestRefreshWorker
import com.kennyb1201.kbstream.work.CacheMaintenanceWorker
import com.kennyb1201.kbstream.work.NewEpisodeWorker
import com.kennyb1201.kbstream.work.OutboxFlushWorker
import com.kennyb1201.kbstream.work.ReminderWorker
import com.kennyb1201.kbstream.work.SimklSyncWorker
import com.kennyb1201.kbstream.work.SportsNotificationWorker
import com.kennyb1201.kbstream.work.TorBoxLibraryWorker
import dagger.hilt.android.HiltAndroidApp
import io.sentry.android.core.SentryAndroid
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.kennyb1201.kbstream.data.runCatchingCancellable

@HiltAndroidApp
class MainApplication : Application(), SingletonImageLoader.Factory {

    // ── The graph's singletons ───────────────────────────────────────────────
    // Spec 2026-11-22, item 3. The application hosts the graph and is an
    // injection point of its own, so the startup steps below replay against the
    // same BingeCat queue and addon list every screen uses. Hilt fills these in
    // before onCreate's body runs.
    @Inject lateinit var addonManager: AddonManager
    @Inject lateinit var bingecatRepository: BingeCatRepository
    @Inject lateinit var kbStreamDownloader: KBStreamDownloader

    override fun onCreate() {
        super.onCreate()
        // Start the clock the diagnostics perf block measures startup against.
        // Must stay first: everything below is part of the launch cost.
        com.kennyb1201.kbstream.data.reporting.PerfTrace.markAppStart()
        // Form factor, resolved before any activity exists. One artifact ships
        // to both TVs and phones (the self-updater downloads this same APK),
        // so every mobile branch reads this rather than a build variant. It
        // only drives orientation today; the TV composables are untouched.
        FormFactor.init(this)
        // The player preference stores, decoded in the background while the
        // rest of startup runs (spec 2026-11-22, DataStore). Every prefs read in
        // the app now goes through one shim whose first touch is a disk read;
        // this pays for the two stores the launch path is about to read.
        com.kennyb1201.kbstream.data.settings.AppPreferences.warm(this)
        // The two Simkl 429 parks, read back before anything can trigger a drain
        // (spec 2026-11-19, Simkl quota F1): a TV that updates or reboots during
        // a 16-hour park must not spend a quota call re-learning it.
        com.kennyb1201.kbstream.data.simkl.installSimklParkStores(this)
        // The same for every other rate-limited leg (spec 2026-11-20): BingeCat's
        // reads and writes, Kitsu's reads and writes, AniList, MAL and Trakt.
        // A restart must not re-learn a 429 by firing the fan-out that earned
        // it - which is exactly what the 0.9.407 paste showed happening.
        com.kennyb1201.kbstream.data.network.installRateLimitParkStores(this)
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
        // NewPipe's transport, installed with the graph's downloader instead of
        // one the extractor builds for itself on the first trailer resolve
        // (spec 2026-11-22, item 3). NewPipe.init only assigns a static, so this
        // is a couple of field writes, not work.
        runCatching { NewPipeManager.install(kbStreamDownloader) }.onFailure {
            // Warning: a trailer resolve would then throw where it used to
            // silently build a second OkHttp stack, and a field capture has to
            // be able to name that before it becomes a mystery crash.
            Log.w("APP_START", "NewPipe transport not installed", it)
        }
        // YouTube PO-token minting for trailers, installed once with the app
        // context. Nothing is created here: the WebView is built only when a
        // trailer resolve asks for a token, and the youtube_po_token_enabled
        // preference (default on) is read per call. Without this line the
        // extractor's provider is the null one and resolves exactly as before.
        runCatching {
            com.kennyb1201.kbstream.data.youtube.PoTokens.install(
                com.kennyb1201.kbstream.data.youtube.WebViewPoTokenProvider.from(
                    applicationContext
                )
            )
        }.onFailure {
            // Warning, not debug: without an install the extractor silently
            // resolves tokenless, which is exactly the kind of degradation a
            // field capture has to be able to name (Fire OS hides debug logs).
            Log.w("APP_START", "YouTube PO token provider not installed", it)
        }
        // A taste queue parked by an earlier session gets its retry here (spec
        // audit 2026-11-18, Data H1): a grant adopted from account sync or a
        // token refreshed in the background never goes through the connect
        // screen, so nothing ever drained what the profile had queued. Off the
        // launch path - the work is a background job.
        runCatching {
            com.kennyb1201.kbstream.data.library.TasteRatingSubmission
                .requestDrain(applicationContext)
        }
        // And the same door for Simkl's COMPLETIONS (spec 2026-11-20, F2): a
        // completion queued during a playback park outlives the session that
        // watched the episode, and app start is the one place the app can see
        // that a park recorded before the last exit may now be over. Silent when
        // nothing is queued.
        runCatching {
            com.kennyb1201.kbstream.data.simkl.SimklCompletionSubmission
                .requestDrain(applicationContext)
        }
        // The same door for BingeCat's completions (spec audit 2026-11-18, Data
        // M2): a play that failed offline / during a 429 in the last session is
        // replayed here instead of dying with that player. Silent when the queue
        // is empty, when the profile is unmapped, or while the leg is parked.
        runCatching {
            bingecatRepository
                .schedulePendingHistoryFlush()
        }
        // Startup isolation: an exception thrown from Application.onCreate
        // kills the process before any Activity exists — on a TV that is a
        // black screen for ~2s, then the launcher. Each step is independent
        // (sync, workers, addon refresh, update check); losing any one of
        // them degrades a feature, but must never take down the launch.
        // runCatching catches Throwable, covering Error subclasses that the
        // inner try/catch(Exception) blocks cannot see.
        // Phone-as-a-remote (spec 2026-11-13). The TV advertises itself over NSD
        // and serves its own LAN address; a phone never starts a server, it only
        // discovers. Both sides install the key injector, which is inert until an
        // activity is in front. Off the launch path: enumerating interfaces and
        // binding a socket is I/O, and nothing on the first frame needs it.
        runCatching {
            com.kennyb1201.kbstream.data.remote.RemoteKeyInjector.install(this)
            if (FormFactor.isTv) {
                startupScope.launch {
                    com.kennyb1201.kbstream.data.remote.RemoteControlRuntime
                        .start(this@MainApplication)
                }
            } else {
                // The other end of that channel (spec 2026-11-16, F1): a phone that
                // has already paired resumes that TV on launch and keeps the socket
                // up, so its Detail page's PLAY ON TV button reflects something real
                // instead of only working while the Remote screen happened to be
                // open. An unpaired phone starts nothing - no socket, no scan - and
                // discovery stays the Remote screen's and the reconnect's business.
                startupScope.launch {
                    com.kennyb1201.kbstream.data.remote.RemoteClientProvider
                        .get(this@MainApplication)
                        .keepConnected()
                }
            }
        }.onFailure {
            com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                it, mapOf("source" to "app_create_remote_start")
            )
        }
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
            // The launch after a death. A process killed under playback - an OOM
            // kill at the player's memory peak, a vendor codec abort, a native
            // crash whose handler never ran - leaves nothing behind: the viewer
            // is back on the launcher and Sentry has no event, because no code
            // of ours got to run. [PlaybackDeathMarker] is the one record that
            // survives that, so a marker still on disk here IS the previous
            // session's death. Reported first: it is the only step in this chain
            // whose subject cannot be reconstructed later.
            startupStep("startup.playbackDeath", "app_create_playback_death") {
                // The OS's account of how the previous process ended, read
                // ONCE and unconditionally.
                //
                // It used to be read only inside the playback-death branch
                // below, so a process the system ended WITHOUT a playback
                // session left no trace anywhere: no marker (nothing was
                // playing), nothing in Sentry (a system kill is not an
                // exception, an ANR or a signal), and therefore nothing in the
                // diagnostics export either. The field report that made this
                // concrete was the Trakt public-lists page on a 1.7 GB TCL,
                // which killed the app on open and vanished without a record.
                // The platform's exit history is the one place that death is
                // written down, so it is read here for every launch.
                val exit = ProcessExitRecord.previous(applicationContext)
                if (exit != null && !exit.deliberate) {
                    // Deliberate exits - force-stop, an update, a permission
                    // change, the app switching itself off - are not deaths and
                    // would be pure noise in the export. Everything else is
                    // recorded, including the crash / native-signal / ANR
                    // reasons Sentry already has a stack for: one line here
                    // names the cause beside it, and nothing is sent to the
                    // crash feed.
                    CrashReporter.recordEvent(
                        "process_exit",
                        "previous process: ${exit.label}"
                    )
                }
                PlaybackDeathMarker.consume(applicationContext)?.let { death ->
                    // The marker proves the session never finished; the exit
                    // read above says why (see ProcessExitRecord), and that
                    // answer decides whether this is a death at all: an app the
                    // installer replaced mid-session leaves the same marker a
                    // native abort does.
                    when {
                        exit?.deliberate == true -> CrashReporter.recordEvent(
                            // Force-stopped, updated, or switched off on purpose:
                            // worth a line in the diagnostics export (it explains
                            // a session that stopped mid-episode) and nothing in
                            // the crash feed.
                            "playback_process_death_deliberate",
                            death.sentence(exit)
                        )
                        exit?.alreadyReported == true -> CrashReporter.recordEvent(
                            // A Java crash, a native signal or an ANR is a death
                            // the crash reporter already sent, with a stack; the
                            // marker only owes the diagnostics export the facts
                            // that stack cannot carry (which session, how far in).
                            // Reporting it as a non-fatal too is how one death
                            // became two issues (Sentry ANDROID-1J, whose entire
                            // content was the signal recorded against ANDROID-1K),
                            // so the sentence is retained here and not sent.
                            "playback_process_death_reported",
                            death.sentence(exit)
                        )
                        death.wasOnScreen -> CrashReporter.recordNonFatal(
                            PlaybackDeathMarker.DeathReport(death.sentence(exit)),
                            mapOf(
                                "source" to "playback_process_death",
                                "engine" to death.engine,
                                "stage" to death.stage,
                                "process_exit" to (exit?.label ?: "unknown")
                            )
                        )
                        else -> CrashReporter.recordEvent(
                            // Backgrounded: the system reclaiming a paused player
                            // is ordinary - the external engine is background by
                            // design for the whole film - so it belongs in the
                            // diagnostics export, not in the crash feed.
                            "playback_process_death_background",
                            death.sentence(exit)
                        )
                    }
                }
            }
            startupStep("startup.simklWorker", "app_create_simkl_worker") {
                scheduleSimklPeriodicSync()
            }
            // Durable sync outbox: retry anything stranded by a previous
            // process death (an offline write that never reached the cloud).
            // The worker is network-gated and survives the app being closed.
            startupStep("startup.outboxFlush", "app_create_outbox_flush_worker") {
                OutboxFlushWorker.enqueue(applicationContext)
            }
            // Offline downloads (mobile only). Builds the download manager and
            // resumes whatever a previous process left in flight, and does
            // nothing at all on a television: nothing there can enqueue a
            // download, so there is nothing to resume and no reason to start the
            // service (spec §8). Runs before the periodic workers because a
            // download's own service is what keeps the transfer moving.
            startupStep("startup.downloads", "app_create_downloads") {
                com.kennyb1201.kbstream.data.download.DownloadsManager
                    .start(this@MainApplication)
            }
            startupStep("startup.newEpisodeWorker", "app_create_new_episode_worker") {
                scheduleNewEpisodeChecks()
            }
            startupStep("startup.reminderWorker", "app_create_reminder_worker") {
                scheduleReminderAlerts()
            }
            startupStep("startup.torboxLibraryWorker", "app_create_torbox_library_worker") {
                scheduleTorBoxLibrarySync()
            }
            // Followed-team game reminders. Armed only when the toggle is on
            // and at least one team is followed (following is the opt-in), so
            // a viewer who never used the hub's FAVORITES tab never wakes for
            // it. Also what re-arms the round across a process restart.
            startupStep("startup.sportsReminderWorker", "app_create_sports_reminder_worker") {
                SportsNotificationWorker.syncScheduleForPrefs(applicationContext)
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
            // ...and the third guide path (spec 2026-11-16): when a title ends
            // and the player closes, a stale guide gets a one-time refresh then,
            // instead of waiting for a 6h tick that a playing television keeps
            // deferring. Arming it here rather than in a player is what makes it
            // fire on BOTH engines and from the hero's trailer player too - the
            // listener lives on the write gate, not on an activity.
            startupStep("startup.epgCatchUp", "app_create_epg_catch_up") {
                com.kennyb1201.kbstream.data.iptv.EpgRefreshCatchUp
                    .install(applicationContext)
            }
            // Launch-time auto-update: picks up addon manifest changes on the
            // first launch after any restart. Throttled internally (shared with
            // the periodic worker) so frequent app relaunches don't spam every
            // manifest URL.
            //
            // This is only HALF the automatic path. It fires on PROCESS
            // creation, and a TV box keeps the process alive for weeks once it
            // is warm, so a long-lived install would rely on WorkManager alone
            // - which is exactly what does not run on time on Fire OS. The
            // other half is the same call from the foreground transition (see
            // MainActivity.onStart), which costs one throttled prefs read on
            // every return to the app.
            startupStep("startup.addonRefresh", "app_create_addon_launch_refresh") {
                addonManager
                    .refreshInstalledAddonsSoon()
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
                        addonManager
                            .warmHomeCatalogCache()
                    }
                }.onFailure { error ->
                    CrashReporter.recordNonFatal(
                        error,
                        mapOf("source" to "app_create_catalog_warm")
                    )
                }
            }
            // Pinned rail warm: the catalog warm above covers the add-on
            // catalogs, but Home's PINNED rows are the ones a cold start really
            // waits on - 35.5s of a 37.7s rail build in the 0.9.329 report. This
            // asks for their first pages through the same cached fetches the rail
            // build reads (AddonRepository.getCatalog for the Top Today feed,
            // TmdbRepository.discoverKB for the viewer's own catalogs), bounded to
            // page 1, TMDB and the feed only. Nested launch for the same reason as
            // the catalog warm: the steps after it are disk work that must not
            // queue behind a fetch, and the paint itself never waits for this - the
            // cached publish uses the cache as it stands.
            startupScope.launch {
                runCatchingCancellable {
                    PerfTrace.timedSuspend("startup.pinnedWarm") {
                        com.kennyb1201.kbstream.data.home.HomePinnedWarm
                            .warm(this@MainApplication)
                    }
                }.onFailure { error ->
                    CrashReporter.recordNonFatal(
                        error,
                        mapOf("source" to "app_create_pinned_warm")
                    )
                }
            }
            // Open the stream disk cache once here, off the first playback's
            // path. SimpleCache construction reads its SQLite metadata; doing it
            // from createPlayer() put that on the first play's TTFF frame (perf
            // audit, players #10). The cache stays process-wide after this, so
            // playback only takes the existing singleton.
            startupScope.launch {
                runCatchingCancellable {
                    com.kennyb1201.kbstream.data.player.StreamDiskCache
                        .get(this@MainApplication)
                }
            }
            // Home rails pinned to a Library list: check that the list still
            // exists at the service that holds it. A list deleted on a website
            // has no event behind it - the absence from a read is the only
            // signal - so without this a dead pin waited for the viewer to open
            // the Library. Its own launch, and the launcher does nothing at all
            // when no remote list is pinned (see LibraryHomeRailReconcile), so
            // the launch path never pays for a network read it does not need.
            startupScope.launch {
                runCatchingCancellable {
                    PerfTrace.timedSuspend("startup.homeRailReconcile") {
                        com.kennyb1201.kbstream.data.library.LibraryHomeRailReconcile
                            .runOnce(this@MainApplication)
                    }
                }.onFailure { error ->
                    CrashReporter.recordNonFatal(
                        error,
                        mapOf("source" to "app_create_home_rail_reconcile")
                    )
                }
            }
            // The stored continue-watching rows of older builds name their
            // shows by the id the CATALOG handed out rather than TMDB's (see
            // HistoryRootMigration). Every read re-roots them, so playback is
            // already correct; this is the one-shot repair of the ROWS, so the
            // sync layer stops handing those stale ids to other devices. Its
            // own launch: it resolves each show's verified root, and the steps
            // after it are disk work that must not queue behind that.
            startupScope.launch {
                runCatchingCancellable {
                    PerfTrace.timedSuspend("startup.historyRootMigration") {
                        com.kennyb1201.kbstream.data.history.HistoryRootMigration
                            .runOnce(this@MainApplication)
                    }
                }.onFailure { error ->
                    CrashReporter.recordNonFatal(
                        error,
                        mapOf("source" to "app_create_history_root_migration")
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
            // Play build only: adopt an update Play downloaded while this
            // process was not running. A flexible download outlives the app,
            // so without this the file would sit downloaded until someone
            // opened Settings; `begin` hands it to Play, which installs it on
            // the restart the viewer confirms. A no-op on every other build,
            // on a debug install, and on a television (see PlayUpdater).
            startupStep("startup.playUpdate", "app_create_play_update") {
                com.kennyb1201.kbstream.data.update.PlayUpdater
                    .begin(this@MainApplication)
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

            // Performance tracing, at a deliberately low rate. Until now only
            // CRASHES left the device, so a slow rail, a stuttering player or a
            // sync that quietly takes 40 s had no signal at all - the app-side
            // PerfTrace ring buffer only exists in this process. Tracing turns
            // those into spans keyed by the same release/dist above, which is
            // what makes "only slow on this one TV" answerable.
            //
            // 10%: these are 1.7 GB TV boxes, and full sampling would put a
            // measurable SDK cost on the exact devices being diagnosed. 10% of
            // many thousands of sessions is plenty to read a percentile from,
            // and it keeps the transaction quota bounded.
            options.tracesSampleRate = 0.1
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
                // m38: the message and the exception value were the only text
                // scrubbed. The extras and the tag values travel with the same
                // event and can carry a URL, a header or a token just as easily -
                // structural today (no leaking call site was found), which is
                // exactly the time to close it rather than after the first leak.
                // The stack trace stays untouched. `extras` (not `extra`) is the
                // map accessor on SentryBaseEvent; `getExtra(key)` is the single
                // -key getter and `extra` is a private field.
                event.extras?.let { extras ->
                    extras.keys.toList().forEach { key ->
                        val value = extras[key]
                        if (value is String) extras[key] = Redaction.text(value)
                    }
                }
                event.tags?.let { tags ->
                    tags.keys.toList().forEach { key ->
                        val value = tags[key]
                        if (value != null) tags[key] = Redaction.text(value)
                    }
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
            // Off by default, and on purpose. The two places this loader is
            // asked for art are a rail or a grid - hundreds of tiles a session
            // on a 1.7 GB box - and a fade on every one of them reads as
            // flicker on a 10-foot UI while it costs a per-tile animation the
            // user is about to scroll past. The few surfaces where the fade is
            // the intended look (the hero backdrop, the Detail hero, the
            // pre-playback splash) opt back in on their own ImageRequest.
            // Coil applies a request's own crossfade over this default, so
            // opting in is a one-liner at the call site.
            .crossfade(false)
            // F1 (2026-11-20): RGB_565 for every image that has no alpha.
            //
            // The walk-median diagnosis put the TCL's remaining jank on decode
            // VOLUME against a 48 MB cache that turns over mid-scroll: the same
            // posters were decoded, evicted and decoded again. 565 halves the
            // bytes per bitmap, so the same cache holds roughly twice the tiles
            // (fewer evictions, fewer re-decodes) and each decode copies half
            // as much memory on a weak SoC.
            //
            // The hero-class surfaces opt back OUT per request (see the
            // crossfade note above: they are exactly the requests that ask for
            // a fade). Banding is what 565 costs on a smooth gradient, and a
            // full-bleed backdrop is the one place it would be visible at ten
            // feet. Posters and tiles have neither the gradient nor the size.
            .allowRgb565(true)
            // F3 (2026-11-20): cap parallel decodes at four. Unbounded
            // parallelism lets a rail's worth of decodes compete with the main
            // thread on this class of hardware; four keeps a fill moving while
            // leaving the frame alone.
            .bitmapFactoryMaxParallelism(4)
            // Software bitmaps by DEFAULT, with hardware as a per-request opt-in
            // (2026-11-21).
            //
            // This was `.allowHardware(!getSoftwareImageBitmaps(context))` - a
            // global switch - until the TCL A/B showed what a global one costs:
            // hardware decoding was faster per image (worst decode 2647ms ->
            // 1258ms) but took back the app's ability to reclaim memory, because
            // an evicted hardware bitmap's pixels are native and no GC pressure
            // follows its eviction. 53MB of evicted-but-alive art sat outside the
            // 48MB budget and the system trimmed the rail mid-scroll.
            //
            // The rails are the volume, so they are what stays software; the
            // full-bleed backdrops opt in through
            // `ImageRequest.allowBackdropHardware(context)` (see BackdropArt),
            // which is where the decode actually shows and where churn is a few
            // images per screen.
            .allowHardware(false)
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
            .components {
                add(SvgDecoder.Factory())
                // F4 (2026-11-20): art gets its own HTTP client - wide
                // dispatcher, IPv4-first DNS, TCP_NODELAY, 4/5/8s timeouts and
                // one tighter retry - instead of the shared default one sized
                // for API calls. See ImageHttp for why each part is there.
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = {
                            com.kennyb1201.kbstream.data.image.imageHttpClient
                        }
                    )
                )
            }
            // F1 of the 2026-11-14 scrolling spec: one count per completed
            // request, by where its pixels came from and how big the bitmap
            // actually was. A session's paste can then tell a warm scroll (hits
            // high, decodes few) from one that re-decoded every card, and can
            // say which sizes the surfaces really asked for - which is the
            // evidence the full-resolution hero bug had no line for (see
            // [ImageLoadTrace]). One branch and one increment per image, not
            // per frame.
            .eventListener(
                object : coil3.EventListener() {
                    // One map entry per in-flight request, kept by request
                    // IDENTITY: Coil hands the same instance to `onStart`,
                    // `resolveSizeEnd` and the result that reaches `onSuccess`.
                    // Removed on every terminal callback.
                    private val inFlight =
                        java.util.IdentityHashMap<
                            coil3.request.ImageRequest,
                            CoilRequestTiming
                            >()

                    override fun onStart(request: coil3.request.ImageRequest) {
                        synchronized(inFlight) {
                            inFlight[request] = CoilRequestTiming(System.nanoTime())
                        }
                    }

                    override fun resolveSizeEnd(
                        request: coil3.request.ImageRequest,
                        size: coil3.size.Size
                    ) {
                        synchronized(inFlight) {
                            inFlight[request]?.let { start ->
                                start.askedWidth = size.width.pixelsOrZero()
                                start.askedHeight = size.height.pixelsOrZero()
                            }
                        }
                    }

                    // The DECODER's own window (0.9.399): Coil brackets the
                    // decoder, not the whole request, so this is the only place
                    // that can say how long decoding an image costs on this box.
                    // `onStart`..bitmap was doing that job and could not: it
                    // includes the disk read and the network, which is why the
                    // field's `decode:` line read `ge16 == ge33 == n` on every
                    // build.
                    override fun decodeStart(
                        request: coil3.request.ImageRequest,
                        decoder: coil3.decode.Decoder,
                        options: coil3.request.Options
                    ) {
                        synchronized(inFlight) {
                            inFlight[request]?.let { start ->
                                start.decodeStartNs = System.nanoTime()
                            }
                        }
                    }

                    override fun decodeEnd(
                        request: coil3.request.ImageRequest,
                        decoder: coil3.decode.Decoder,
                        options: coil3.request.Options,
                        result: coil3.decode.DecodeResult?
                    ) {
                        synchronized(inFlight) {
                            inFlight[request]?.let { start ->
                                if (start.decodeStartNs > 0L) {
                                    start.decodeMs =
                                        ((System.nanoTime() - start.decodeStartNs) /
                                            1_000_000L).toInt()
                                }
                            }
                        }
                    }

                    override fun onCancel(request: coil3.request.ImageRequest) {
                        synchronized(inFlight) { inFlight.remove(request) }
                    }

                    override fun onError(
                        request: coil3.request.ImageRequest,
                        result: coil3.request.ErrorResult
                    ) {
                        synchronized(inFlight) { inFlight.remove(request) }
                    }

                    override fun onSuccess(
                        request: coil3.request.ImageRequest,
                        result: coil3.request.SuccessResult
                    ) {
                        val start = synchronized(inFlight) { inFlight.remove(request) }
                        val durationMs = start?.let {
                            ((System.nanoTime() - it.atNs) / 1_000_000L).toInt()
                        } ?: 0
                        // F1 of the 2026-11-17 dynamic-colour fix: the tint
                        // samples the DISK cache, so a load that reached the
                        // disk is the moment its answer became readable. The
                        // listener is the app's only place that knows this, which
                        // is why the arrival signal is fed from here rather than
                        // polled for by every tinted surface. Memory hits are
                        // skipped: the file was already there, and the sampler's
                        // own window has covered that case.
                        if (result.dataSource != coil3.decode.DataSource.MEMORY_CACHE) {
                            com.kennyb1201.kbstream.ui.theme.ArtworkTintArrivals.noteLoaded(
                                request.data as? String
                            )
                        }
                        ImageLoadTrace.noteImage(
                            source = when (result.dataSource) {
                                coil3.decode.DataSource.MEMORY_CACHE ->
                                    ImageLoadTrace.Source.MEMORY

                                coil3.decode.DataSource.DISK -> ImageLoadTrace.Source.DISK
                                coil3.decode.DataSource.NETWORK -> ImageLoadTrace.Source.NETWORK
                                else -> ImageLoadTrace.Source.OTHER
                            },
                            width = result.image.width,
                            height = result.image.height,
                            durationMs = durationMs,
                            decodeMs = start?.decodeMs ?: 0,
                            askedWidth = start?.askedWidth ?: 0,
                            askedHeight = start?.askedHeight ?: 0,
                            duringFrame =
                                com.kennyb1201.kbstream.data.reporting.UiFrameTrace
                                    .isRunning(),
                            // Wider than duringFrame on purpose: the art a step
                            // asks for is decoded after the frame session closes
                            // (600 ms dwell, 350 ms settle), so the walk's own
                            // counts have to outlive the gesture. See
                            // ImageLoadTrace.noteImage.
                            walkActive =
                                com.kennyb1201.kbstream.data.reporting.UiFrameTrace
                                    .inWalkWindow()
                        )
                    }
                }
            )
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
        // 25%, not 15% (spec 2026-11-14, F2): the cache has to hold a rail's
        // worth of tiles on the boxes this is for, and 15% of a 256 MB class is
        // 38 MB - a Home screen plus a Detail page is more than that, so the
        // cache turned over during a scroll and every tile that came back was a
        // decode. The 64 MB cap is unchanged and still what makes it
        // survivable; a smaller class now reaches the cap instead of stopping
        // short of it.
        return (heapBytes * 25 / 100).coerceIn(24L * 1024 * 1024, 64L * 1024 * 1024)
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
    // FIVE levels stopped being DELIVERED in API 34 - RUNNING_MODERATE (5),
    // RUNNING_LOW (10), RUNNING_CRITICAL (15), MODERATE (60) and COMPLETE (80) -
    // and onLowMemory() is never called there either (all deprecated in API 35;
    // see the AOSP ComponentCallbacks2 javadoc). Only UI_HIDDEN (20) and
    // BACKGROUND (40) still arrive on API 34+, and both are >= the threshold
    // below, so on those boxes this branch is a BACKGROUNDING response only: a
    // resume cost, never a mid-scroll one. On API 33 and older (the TCL field
    // box) the RUNNING_* levels still arrive and a foreground CRITICAL can fire
    // mid-scroll, which is the case the graded trim exists for.
    // Do not reason about MODERATE/COMPLETE arriving on an API 34+ box: they
    // never do.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching {
            // F1 of the 2026-11-14 scrolling spec: every delivery is recorded
            // with its own name, because the report could not tell "the cache
            // was wiped mid-scroll" from "the cache was wiped after the viewer
            // left" and the two need opposite fixes (see [ImageLoadTrace]).
            ImageLoadTrace.noteTrim(trimLevelName(level))
            // F2: the bitmap cache no longer goes at RUNNING_LOW. On the boxes
            // this is for that level fires during ordinary browsing - it is a
            // hint, not an emergency - and dropping ~40 MB of decoded art there
            // bought nothing back that the browse session was not about to
            // need again: scroll, wipe, re-decode the same tiles from disk,
            // scroll, wipe. It now goes at RUNNING_CRITICAL and on
            // onLowMemory(), which are the two levels that mean the process is
            // actually in trouble; the 64 MB cap is what keeps the cache from
            // being the trouble in the first place.
            //
            // F4 of the 2026-11-15 scrolling round-two spec: CRITICAL now gives
            // back HALF the bitmap cache instead of all of it. The `clear()` that
            // used to run here is the mid-scroll wipe the paste kept showing - the
            // rail on screen then re-decoded as the viewer kept moving. A full
            // clear is still what onLowMemory() does, and that is the level that
            // means the process itself is about to die.
            // TWO STAGES (dig four, 2026-11-16, §3b/§3e rank 2). One branch used
            // to serve "the process is under pressure mid-scroll" and "the viewer
            // left" alike, which is why backgrounding got the scrolling
            // treatment while a foreground CRITICAL got the same.
            //
            // BACKGROUND (40) and above means the process has left the screen and
            // is on the system's LRU list; near-death levels (MODERATE 60 /
            // COMPLETE 80) arrive only on API 33 and older, and mean it more. With
            // nothing on screen a full clear is free, gives the system the most
            // headroom and makes the box less likely to kill us; the return pays a
            // disk re-decode, which is the honest cost of having been away.
            //
            // RUNNING_CRITICAL (15) is the only mid-scroll case, and only API 33
            // and older deliver it: there the graded LRU trim runs, keeping the
            // rail the viewer is actually looking at (see [trimImageMemoryCache]).
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                releaseImageMemoryCache(this)
            } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                // The smaller bite (round four F3c): this path runs with the
                // rail on screen, so the art being given back is the art being
                // looked at. The deeper reclaim belongs to the backgrounding
                // clear above, which runs with nothing visible.
                trimImageMemoryCache(this, ImageCacheTrim.SCROLL_FRACTION)
            }
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                MemoryPressure.releaseBrowsingCaches()
                // The trailer pre-warm's WebView is the reclaimable half of the
                // PO-token layer: a cached token is a string, a live page is a
                // renderer. Dropping the page here and re-warming on the next
                // trigger is the whole resource rule (see PoTokens.releaseWarm).
                runCatching {
                    com.kennyb1201.kbstream.data.youtube.PoTokens.releaseWarm()
                }
            }
        }
    }

    /**
     * The platform's trim level, spelled for the diagnostics paste.
     *
     * The RUNNING_* halves and MODERATE/COMPLETE are deprecated in API 35 (the
     * platform stopped delivering all five in API 34); they are still named
     * because an API 33 or older box - the TCL - does deliver them, and a paste
     * from one must not read as LEVEL_15.
     */
    @Suppress("DEPRECATION")
    private fun trimLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> "LEVEL_$level"
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
        // Keyed on the toggle OR a flagged air reminder (see the worker).
        NewEpisodeWorker.syncScheduleForPrefs(this)
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

    /**
     * TorBox cloud → Library rounds. Armed only when the toggle is on and a
     * TorBox key is set (see the worker), so an unrelated device never wakes
     * for it.
     *
     * A round is queued here as well as the periodic one, because
     * [com.kennyb1201.kbstream.data.debrid.TorBoxCloudStore]'s own record says
     * the sync runs "on a schedule and on every app launch" and only the
     * schedule was ever armed: a viewer who turned the switch on and reopened
     * the app still waited out a window before anything reached the Library.
     * The round is cheap once caught up (only torrents new since the last one
     * cost a TMDB lookup), and the worker's own gate skips it entirely when the
     * switch is off or the key is blank.
     */
    private fun scheduleTorBoxLibrarySync() {
        TorBoxLibraryWorker.syncScheduleForPrefs(this, runImmediate = true)
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

/**
 * When one image request started, and what size the layout resolved it to.
 *
 * The 2026-11-18 diagnosis (§1, §7) had counts and sizes but no durations:
 * nothing could tie a decode to the 33 ms frame the walk measured.
 *
 * A top-level class, not a nested one: Kotlin forbids a class declaration
 * inside the anonymous `EventListener` that owns the map.
 */
private class CoilRequestTiming(val atNs: Long) {
    var askedWidth = 0
    var askedHeight = 0

    /** `System.nanoTime()` at Coil's `decodeStart`; 0 if it never fired. */
    var decodeStartNs = 0L

    /** The decoder's own cost, 0 until `decodeEnd` has run. */
    var decodeMs = 0
}

/**
 * A resolved Coil dimension in pixels, or 0 when Coil left it undefined
 * (`Size.ORIGINAL`: no size was requested, so there is nothing to record).
 */
private fun coil3.size.Dimension.pixelsOrZero(): Int =
    if (this is coil3.size.Dimension.Pixels) px else 0
