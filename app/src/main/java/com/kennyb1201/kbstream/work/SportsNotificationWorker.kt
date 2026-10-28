package com.kennyb1201.kbstream.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.notifications.NotificationCenter
import com.kennyb1201.kbstream.data.notifications.SportsGameReminderRules
import com.kennyb1201.kbstream.data.notifications.SportsReminderStore
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sports.EspnSportsRepository
import com.kennyb1201.kbstream.data.sports.SportsLeagues
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * "Yankees @ Red Sox starts in 15 minutes" for the teams the profile follows.
 *
 * A periodic round rather than a job armed per game: a game's start time is
 * known the moment its league is fetched, but follows change, games get
 * postponed and scoreboards arrive late, so the cheap, self-healing thing is to
 * re-ask every half hour which followed games are imminent. Half an hour is
 * also the lead window - a reminder is a heads-up, not a live ticker - and the
 * rules ignore anything further out than that.
 *
 * The round deliberately touches no player and no playlist: it posts a
 * notification whose tap opens the sports hub, where the channel is matched and
 * the game is played by the hub's own existing path. The only state it writes
 * is the set of games it has already announced ([SportsReminderStore]).
 */
class SportsNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A failed round is not worth a retry storm (the next periodic run
        // covers it), but it is worth a log line.
        Log.e(TAG, "game reminder round failed: ${e.message}", e)
        Result.success()
    }

    private suspend fun run() {
        // The hub's master switch, read HERE as well as by the scheduler
        // (settings reorg Q4). The switch follows the profile now, and a value
        // that arrives from the account cannot rearm THIS box's WorkManager: the
        // scheduler was armed (or cancelled) on the device the viewer toggled.
        // Without this read, turning reminders off on the TV would leave the
        // phone's already-armed round posting until someone touched the switch
        // on the phone itself - which is the opposite of what syncing it is for.
        if (!AppPreferences.getSportsGameReminders(applicationContext)) return
        val favorites = AppPreferences.getSportsFavoriteTeams(applicationContext)
        // Following a team OR asking for one game is the opt-in (audit: sports
        // F3). This is the belt to the scheduler's braces: a stale request that
        // outlived an unfollow must do nothing rather than buzz about teams
        // nobody follows.
        val prefs = SportsReminderStore.prefsFor(applicationContext)
        val requested = SportsReminderStore.loadRequested(prefs)
        if (!SportsGameReminderRules.shouldSchedule(favorites, requested.keys)) return

        val leagues = SportsLeagues.enabled(
            AppPreferences.getSportsEnabledLeagues(applicationContext)
        )
        if (leagues.isEmpty()) return

        // The shared repository, so a round that lands while the hub is open
        // reads the same cache the hub is reading.
        val repository = EspnSportsRepository.shared()
        // Every enabled league AT ONCE. With the whole catalog on by default a
        // sequential flatMap would put sixteen scoreboard hops end to end inside
        // a half-hourly job; the fetches share nothing, and the shared
        // repository answers a repeat one from its cache.
        val games = coroutineScope {
            leagues.map { league ->
                async {
                    runCatchingCancellable { repository.scoreboard(league.path) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }.flatten()
        if (games.isEmpty()) return

        val now = System.currentTimeMillis()
        val due = SportsGameReminderRules.dueGames(games, favorites, now, requested.keys)
        if (due.isEmpty()) {
            // Even with nothing due, a request for a game that has started is
            // past its use: drop it here so the store keeps describing games
            // that are still ahead.
            SportsReminderStore.pruneRequested(prefs, now)
            return
        }

        val notified = SportsReminderStore.load(prefs).toMutableMap()
        due.forEach { game ->
            if (!SportsGameReminderRules.shouldNotify(game.id, notified[game.id], now)) {
                return@forEach
            }
            if (NotificationCenter.gameReminder(applicationContext, game)) {
                SportsReminderStore.markNotified(prefs, game.id, now)
                notified[game.id] = now
                Log.i(TAG, "SPORTS REMINDER posted game=${game.id}")
            }
        }
        // Expired records are dropped here, so the store tracks the games it
        // describes instead of growing for the life of the install. Individual
        // requests are pruned by their kick-off, not by when they were armed.
        SportsReminderStore.prune(prefs, now)
        SportsReminderStore.pruneRequested(prefs, now)
    }

    companion object {
        private const val TAG = "SPORTS_REMINDER_WORKER"

        /** Unique periodic work name, owned here so the toggle and startup agree. */
        const val PERIODIC_WORK_NAME = "sports_game_reminders"

        /**
         * The cadence. Half an hour, not faster: this is a reminder, not a live
         * ticker, and it matches [SportsGameReminderRules.LEAD_WINDOW_MS] so a
         * game is announced on exactly one tick before it starts.
         */
        private const val INTERVAL_MINUTES = 30L

        /**
         * Arms the round from the current prefs: the toggle is on AND there is
         * something to remind about - a followed team, or a game the viewer
         * asked for by name (audit: sports F3). Unfollowing the last team with
         * no individual request cancels the schedule that was there.
         */
        fun syncScheduleForPrefs(context: Context) {
            val requested = SportsReminderStore.loadRequested(
                SportsReminderStore.prefsFor(context)
            )
            syncSchedule(
                context,
                enabled = AppPreferences.getSportsGameReminders(context) &&
                    SportsGameReminderRules.shouldSchedule(
                        AppPreferences.getSportsFavoriteTeams(context),
                        requested.keys
                    )
            )
        }

        /**
         * Runs one round now, as a one-shot.
         *
         * Used when a viewer arms an INDIVIDUAL game reminder: the periodic
         * round may be up to half an hour away, and a game asked for ten minutes
         * out would otherwise be announced after it started - or not at all.
         * The round reads the same store the toggle writes, so this cannot
         * announce anything the periodic run would not have.
         */
        fun enqueueImmediate(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${PERIODIC_WORK_NAME}_now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SportsNotificationWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .addTag(TAG)
                    .build()
            )
        }

        fun syncSchedule(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelAllWorkByTag(TAG)
                return
            }
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                // UPDATE, not KEEP: the interval below has to be able to change
                // on an install that already holds a request.
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SportsNotificationWorker>(
                    INTERVAL_MINUTES,
                    TimeUnit.MINUTES
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .addTag(TAG)
                    .build()
            )
        }
    }
}
