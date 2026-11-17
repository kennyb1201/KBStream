package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Enqueues the background guide refresh.
 *
 * This used to be called from exactly one place - the cloud-sync prefs
 * applier, and only when a synced playlist URL had changed. A guide
 * configured in the app itself therefore never got an [EpgRefreshWorker] at
 * all: the only refreshes were the manual one and the staleness check that
 * runs while the Guide screen is open. With the app closed (or on a TV box
 * that is left on the launcher) the guide simply aged, and because an import
 * only stores a window of programs, it eventually aged into "No program
 * data" rather than merely being old. [MainApplication] now schedules it on
 * every launch.
 *
 * `UPDATE` rather than `KEEP` on purpose: KEEP would leave an existing
 * request's old interval in place forever, so the cadence below could never
 * change on an install that already had one (and it is the cadence that keeps
 * the stored window ahead of the clock).
 */
object EpgRefreshScheduler {

    /**
     * Public because diagnostics name the same job: "is the periodic refresh
     * even scheduled on this box" is one of the three causes of a blank guide,
     * and it used to be answerable only by guessing.
     */
    const val WORK_NAME = "iptv_epg_refresh"

    /**
     * How often the guide is re-imported in the background. WorkManager's
     * minimum for periodic work is 15 minutes and a large XMLTV import is a
     * multi-minute download plus parse, so this is a freshness/cost trade
     * rather than a fine-grained poll.
     *
     * The value is [EpgWindow]'s: the stored window is sized from it (they are
     * the two halves of one contract — a guide refreshed this often has to
     * reach the end of what the UI renders, with slack for a run that Doze or
     * the write gate defers), so changing one without the other is the drift
     * that made the guide databases the app's largest store.
     */
    private const val REFRESH_INTERVAL_HOURS = EpgWindow.REFRESH_INTERVAL_HOURS

    /**
     * The ONE-TIME catch-up, under its own unique name (spec 2026-11-16).
     *
     * Separate from [WORK_NAME] on purpose: enqueueing a one-time request under
     * the periodic job's name would replace the 6h cadence, and the catch-up
     * exists precisely because that cadence is starving - it must not cost the
     * schedule it is compensating for.
     *
     * `KEEP`: one pending catch-up is enough. A second one enqueued while the
     * first is still waiting to run has nothing to add, and the run reads the
     * guide at the moment it starts anyway.
     */
    const val CATCH_UP_WORK_NAME = "iptv_epg_refresh_catch_up"

    /**
     * The input flag that tells [EpgRefreshWorker] this run was a catch-up.
     *
     * It changes nothing about what the pass DOES - same gates, same budgets,
     * same stamping - only what the recorded verdict says it was, so a paste can
     * tell a catch-up from a 6h tick and the field test can prove the trigger
     * fired.
     */
    const val INPUT_CATCH_UP = "catch_up"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(
            REFRESH_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(guideConstraints())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Enqueues the playback-end catch-up ([EpgRefreshCatchUp] decides whether it
     * is worth one; this only asks for the work).
     *
     * Same constraints as the periodic run, because it is the same multi-minute
     * download and parse and the same reasons apply.
     */
    fun enqueueCatchUp(context: Context) {
        val request = OneTimeWorkRequestBuilder<EpgRefreshWorker>()
            .setConstraints(guideConstraints())
            .setInputData(workDataOf(INPUT_CATCH_UP to true))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            CATCH_UP_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * What a multi-minute download + parse needs before it may start: the network,
     * and not a nearly-flat battery or a storage that is about to fill, either of
     * which is how an import fails halfway and leaves a windowed guide short.
     *
     * Both gates DEFER the run (WorkManager retries) rather than failing it - see
     * the worker's own note on why failure is never the answer here.
     */
    private fun guideConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    /** What WorkManager currently says about the scheduled refresh. */
    data class ScheduledWork(
        /** `ENQUEUED`, `RUNNING`, `BLOCKED`, `CANCELLED`, `none` or `unavailable`. */
        val state: String,
        /** When the next periodic run is due, or null when it is not scheduled. */
        val nextRunAtMs: Long?,
        /** How many attempts WorkManager has made at the current run. */
        val attempts: Int
    )

    /**
     * Reads the scheduled job's own state, for the diagnostics line.
     *
     * Blocking (a `ListenableFuture` with a short timeout) and therefore called
     * from a background dispatcher only. `none` is a real answer: it means the
     * job is not in WorkManager at all, which is the difference between "Fire
     * OS never let it run" and "it ran and its own gate declined".
     */
    fun scheduledWork(context: Context): ScheduledWork = runCatching {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WORK_NAME)
            .get(2, TimeUnit.SECONDS)
        val info = infos.firstOrNull()
            ?: return@runCatching ScheduledWork("none", null, 0)
        ScheduledWork(
            state = info.state.name,
            nextRunAtMs = info.nextScheduleTimeMillis.takeIf { it > 0L },
            attempts = info.runAttemptCount
        )
    }.getOrElse { ScheduledWork("unavailable", null, 0) }
}
