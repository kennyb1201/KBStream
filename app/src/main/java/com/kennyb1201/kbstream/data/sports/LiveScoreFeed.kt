package com.kennyb1201.kbstream.data.sports

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The live scores the guide grid draws (round-2 F9 / F3, F3m).
 *
 * It deliberately owns no data path of its own. The scoreboard is the SAME
 * [EspnSportsRepository] the sports hub polls - one shared instance with its
 * own 60s live cache, so a grid and a hub open at the same moment cost one
 * download, not two - and the leagues are the same enabled set the hub reads.
 * Only what the hub would poll is polled here: a league the viewer turned off
 * has no scores to draw on any screen.
 *
 * Attach/detach is ref-counted rather than tied to a screen, because the grid
 * is a view inside two different screens (the TV guide and the phone guide).
 * Opening the grid attaches; leaving it detaches; the loop exists only while
 * something is watching, so a viewer browsing the list never pays for a poll
 * they cannot see.
 *
 * "Never blanks" (F3c's stale-poll rule) is the repository's job - it answers
 * its cache on a failed fetch - plus one guard here: a poll that comes back
 * empty while the grid already holds games is treated as an answer about the
 * poll, not about the slate, and the last good scores stay on screen.
 */
internal object LiveScoreFeed {

    private const val TAG = "LIVE_SCORES"

    /**
     * The hub's own beat, kept in step with it by hand: a score does not move
     * between two 30-second ticks, and the two loops share the repository's
     * cache anyway (see the class note). Deliberately not a read of the hub's
     * constant - the data layer does not import from the UI layer.
     */
    private const val POLL_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _games = MutableStateFlow<List<SportsGame>>(emptyList())

    /**
     * Every game the grid may draw: the ones in play, and the finished ones the
     * cells may still be showing ("Final 3 - 2" until the program ends).
     * Upcoming games are dropped here rather than at the cell, so the match pass
     * never runs for a fixture that has no score.
     */
    val games: StateFlow<List<SportsGame>> = _games.asStateFlow()

    private var consumers = 0
    private var loop: Job? = null

    /** Starts the shared poll for the caller, or joins the one already running. */
    @Synchronized
    fun attach(context: Context) {
        consumers++
        if (loop != null) return
        val app = context.applicationContext
        loop = scope.launch {
            while (isActive) {
                pollOnce(app)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Releases the caller's interest; the last one out stops the poll. */
    @Synchronized
    fun detach() {
        consumers = (consumers - 1).coerceAtLeast(0)
        if (consumers > 0) return
        loop?.cancel()
        loop = null
    }

    /**
     * One pass over the hub-polled leagues.
     *
     * `scoreboard` is the hub's own call and answers its cache when a fetch
     * fails, so a dropped network keeps the scores that were already on screen.
     * A league that is not an ESPN head-to-head league (a golf round, a race)
     * has nothing a per-channel cell could show, so it is not asked at all.
     */
    private suspend fun pollOnce(context: Context) {
        val enabled = AppPreferences.getSportsEnabledLeagues(context)
        // ESPN head-to-head leagues only. A `tsdb:` league is head-to-head too,
        // but its free source has no livescore leg (spec 2026-11-13 §5), so asking
        // it here would spend its 30/minute budget on a grid cell it can never
        // fill - and asking ESPN for a `tsdb:` path would just 400.
        val leagues = SportsLeagues.enabled(enabled).filter {
            it.kind == SportsKind.HEAD_TO_HEAD && !it.isTheSportsDb
        }
        if (leagues.isEmpty()) return

        val fresh = ArrayList<SportsGame>()
        leagues.forEach { league ->
            runCatchingCancellable {
                EspnSportsRepository.shared().scoreboard(league.path)
            }.getOrDefault(emptyList()).forEach { game ->
                if (game.state != GameState.UPCOMING) fresh += game
            }
        }

        if (fresh.isEmpty()) {
            // Nothing cached anywhere and the fetch answered nothing: keep
            // drawing the last good scores instead of blanking them.
            if (_games.value.isNotEmpty()) {
                Log.i(TAG, "scores poll answered nothing; keeping ${_games.value.size} last-good")
            }
            return
        }
        _games.value = fresh
    }
}
