package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.BackgroundWork
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The guide refresh that runs when a title ends (spec 2026-11-16).
 *
 * The background refresh never runs while something is playing - the guide
 * rebuild under a starting player was 750 ms GC pauses and a 6.9 s rebuffer - so
 * a television that plays most of the time it is on starves the 6h tick. The
 * field paste showed twelve `outcome=defer (player)` attempts and a guide 1d 1h
 * old, and the only path left that healed it was opening the Guide: the entry
 * policy imports when the data is stale, and the Guide screen has to be opened
 * for that to run.
 *
 * This closes that gap with the same machinery, not a second import path:
 *
 *  - the trigger is [EpgWriteGate]'s active → idle edge, i.e. the player closing
 *    on either engine. Playback is never interrupted, because the trigger only
 *    exists after it has stopped;
 *  - the staleness test is [GuideRefreshPolicy.urgency] - the SAME function the
 *    Guide screen's entry policy and the diagnostics read, against the same
 *    marker and the same window end;
 *  - the work is [EpgRefreshScheduler.enqueueCatchUp], a one-time run of the same
 *    [EpgRefreshWorker]: its own freshness and playback gates still apply, so a
 *    guide that turned fresh in the meantime is a FRESH no-op and playback that
 *    restarted before it ran defers it exactly like the periodic run.
 *
 * What it cannot do: run on a television that is off or asleep. WorkManager is
 * Doze-deferred there and no app-side trigger can change that - this closes the
 * playback-starvation gap, not the powered-off one.
 */
internal object EpgRefreshCatchUp {

    private const val TAG = "EpgRefreshCatchUp"

    /** `iptv_prefs`, the profile-scoped store both other guide paths read. */
    private const val PREFS_NAME = "iptv_prefs"

    private val installed = AtomicBoolean(false)

    /**
     * Arms the trigger, once per process.
     *
     * Called from [com.kennyb1201.kbstream.MainApplication] beside the periodic
     * schedule: the listener is process-wide, and registering it per activity
     * would leave a dead player's registration behind on every screen change.
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        EpgWriteGate.addIdleListener { onPlaybackEnded(appContext) }
    }

    /**
     * A title just ended. The decision runs OFF the player's teardown path: it
     * reads the guide's own state (a database query for where the window ends),
     * and none of that belongs between the stop button and the launcher.
     */
    private fun onPlaybackEnded(context: Context) {
        BackgroundWork.launch {
            runCatchingCancellable { catchUpIfStale(context) }
                .onFailure {
                    Log.w(TAG, "guide catch-up check failed: ${it.javaClass.simpleName}")
                }
        }
    }

    /**
     * Checks the guide the way the entry policy does and enqueues the one-time
     * refresh when it is due. Returns whether it enqueued, which is what the
     * field test reads.
     */
    internal suspend fun catchUpIfStale(context: Context): Boolean {
        // No sources configured: the worker would answer NO_SOURCES, and there is
        // nothing to compare the window against.
        if (GuideFiles.guideUrls(context).isEmpty()) return false

        val prefs = context.getSharedPreferences(
            ProfileStorage.prefsName(context, PREFS_NAME),
            Context.MODE_PRIVATE
        )
        val now = System.currentTimeMillis()
        val lastImport = prefs.getLong(EpgRefreshWorker.KEY_EPG_UPDATED_AT, 0L)
        val lastAttempt = prefs.getLong(EpgRefreshWorker.KEY_EPG_ATTEMPT_AT, 0L)

        // Where the stored window actually ENDS, which is the signal the entry
        // policy decides on. A guide that holds nothing answers null, and that is
        // the blank-guide branch of the same rule.
        val endsAt = runCatchingCancellable {
            IptvRepository.shared(context).newestGuideProgramEndMs()
        }.getOrNull()

        val urgency = GuideRefreshPolicy.urgency(
            endsAtMs = endsAt,
            nowMs = now,
            ageStale = GuideRefreshPolicy.ageStale(now, lastImport),
            lastAttemptMs = lastAttempt
        )
        if (!EpgCatchUpRules.shouldRun(urgency, now, lastAttempt)) return false

        EpgRefreshScheduler.enqueueCatchUp(context)
        Log.i(TAG, "guide catch-up enqueued: urgency=$urgency age=${(now - lastImport) / 60_000}min")
        return true
    }
}
