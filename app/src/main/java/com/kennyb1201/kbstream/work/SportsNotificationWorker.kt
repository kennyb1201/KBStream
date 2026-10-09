package com.kennyb1201.kbstream.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
        val favorites = AppPreferences.getSportsFavoriteTeams(applicationContext)
        // Following is the opt-in. This is the belt to the scheduler's braces: a
        // stale request that outlived an unfollow must do nothing rather than
        // buzz about teams nobody follows.
        if (!SportsGameReminderRules.shouldSchedule(favorites)) return

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
        val due = SportsGameReminderRules.dueGames(games, favorites, now)
        if (due.isEmpty()) return

        val prefs = SportsReminderStore.prefsFor(applicationContext)
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
        // describes instead of growing for the life of the install.
        SportsReminderStore.prune(prefs, now)
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
         * Arms the round from the current prefs: the toggle is on AND at least
         * one team is followed. A viewer who follows nobody never gets a
         * schedule, and unfollowing the last team cancels the one that was
         * there.
         */
        fun syncScheduleForPrefs(context: Context) {
            syncSchedule(
                context,
                enabled = AppPreferences.getSportsGameReminders(context) &&
                    SportsGameReminderRules.shouldSchedule(
                        AppPreferences.getSportsFavoriteTeams(context)
                    )
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
