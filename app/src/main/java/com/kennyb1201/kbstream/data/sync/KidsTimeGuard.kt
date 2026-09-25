package com.kennyb1201.kbstream.data.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Kids Mode time guard: enforces the two per-profile time locks —
 *
 *  - dailyLimitMinutes: cumulative "screen on, app foreground" minutes per
 *    calendar day. 0 disables. Accumulation only advances while at least one
 *    Activity is started (see [registerForegroundCallbacks]), so a film
 *    playing in its own player Activity still burns the budget while time
 *    spent genuinely backgrounded does not; the day key rolls over at local
 *    midnight.
 *
 *  - bedtimeMinutes: minutes-after-midnight cutoff. The profile locks from
 *    bedtime until 4:00 AM next morning. 0 disables. A cutoff like 23:30
 *    also correctly locks across midnight.
 *
 * Both locks produce one [LockState]; a parent can lift either with the
 * profile's PIN ([overrideForSession]) — the override lasts until the app
 * process dies (deliberately not persisted: a force-stop resets it, which
 * is acceptable because the limit re-engages on the next launch).
 *
 * Usage: MainActivity starts the singleton, which then registers its own
 * application-level ActivityLifecycleCallbacks so accumulation follows the
 * PROCESS being foregrounded - playback included, because each player is a
 * separate Activity. A lock overlay composable collects [state], and every
 * screen that can hold the picture calls [enforceLock] so a lock that lands
 * mid-film ends that playback instead of only waiting behind it.
 */
object KidsTimeGuard {

    private const val TAG = "KidsTimeGuard"
    private const val PREFS = "kids_time_guard"
    private const val KEY_DAY = "day_"
    private const val KEY_MINUTES = "mins_"
    private const val BEDTIME_UNLOCK_MINUTES = 4 * 60 // 4:00 AM

    /** Snapshot consumed by the lock overlay. */
    data class LockState(
        val locked: Boolean,
        val reason: Reason = Reason.NONE,
        val profileId: String? = null,
        val profileName: String? = null,
        /** Remaining minutes today when limited by the daily budget. */
        val minutesLeftToday: Int = 0,
        val dailyLimitMinutes: Int = 0
    ) {
        enum class Reason { NONE, DAILY_LIMIT, BEDTIME }
    }

    private val _state = MutableStateFlow(LockState(locked = false))
    val state: StateFlow<LockState> = _state.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t ->
                android.util.Log.w(TAG, "kids guard task failed: ${t.message}")
                com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                    t, mapOf("source" to "kids_time_guard_scope")
                )
            }
    )
    private var tickerJob: kotlinx.coroutines.Job? = null

    // Per-profile persisted minute counters: { day: "yyyyMMdd", mins: Int }
    private var dayKey: String? = null
    private var todayMinutes: Int = 0
    private var profileIdForCounter: String? = null

    /** Set by MainActivity so the guard can persist without a Context leak. */
    @Volatile
    var appContext: Context? = null

    /** True while the app is foregrounded (drives accumulation). */
    @Volatile
    private var foregrounded = false

    /**
     * Number of started Activities, maintained by the application-level
     * callbacks [registerForegroundCallbacks] installs.
     *
     * Deliberately NOT MainActivity's own onStart/onStop, which is how this
     * used to work: every player is a SEPARATE Activity, so "MainActivity
     * stopped" is what happens when a title starts playing, not when the app
     * goes away. Reporting that as backgrounded froze the daily-limit clock
     * for the whole of playback - and because the Up Next chain keeps a
     * player on top, a child who just kept watching never spent a minute of
     * the budget, so the limit never arrived at all.
     *
     * Touched only from Activity lifecycle callbacks, which Android dispatches
     * on the main thread.
     */
    private var startedActivities = 0

    private var foregroundCallbacksRegistered = false

    /** Initialize and start the 30s evaluation loop. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (appContext == null) {
            appContext = app
        }
        registerForegroundCallbacks(app)
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                evaluateTicker(30)
                delay(TimeUnit.SECONDS.toMillis(30))
            }
        }
    }

    /**
     * Tracks the PROCESS being foregrounded rather than one Activity, so any
     * started Activity - the player included - keeps the clock running.
     */
    private fun registerForegroundCallbacks(app: Context) {
        if (foregroundCallbacksRegistered) return
        val application = app as? Application ?: return
        foregroundCallbacksRegistered = true
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    val previous = startedActivities
                    startedActivities = previous + 1
                    if (
                        foregroundTransition(previous, startedActivities) ==
                        ForegroundTransition.FOREGROUNDED
                    ) {
                        onAppStart()
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    val previous = startedActivities
                    startedActivities =
                        (previous - 1).coerceAtLeast(0)
                    if (
                        foregroundTransition(previous, startedActivities) ==
                        ForegroundTransition.BACKGROUNDED
                    ) {
                        onAppStop()
                    }
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    /** What a move in the started-Activity count means for accumulation. */
    internal enum class ForegroundTransition {
        NONE,
        FOREGROUNDED,
        BACKGROUNDED
    }

    /**
     * The clock runs only between the count leaving zero and returning to it:
     * 0 -> 1 foregrounds, 1 -> 0 backgrounds, and a move that stays on the
     * same side of zero (1 -> 2 as the player opens, 2 -> 1 when it closes)
     * changes nothing - which is exactly what makes playback count.
     */
    internal fun foregroundTransition(
        previous: Int,
        current: Int
    ): ForegroundTransition = when {
        previous <= 0 && current > 0 -> ForegroundTransition.FOREGROUNDED
        previous > 0 && current <= 0 -> ForegroundTransition.BACKGROUNDED
        else -> ForegroundTransition.NONE
    }

    /**
     * Ends [activity] the moment a lock engages, and straight away when one is
     * already engaged as it starts.
     *
     * The MainActivity overlay cannot do this job: a playing title holds a
     * different Activity, so a limit that arrived mid-film had no screen of
     * its own to act on and playback simply ran to the end. Enforcement
     * belongs to whichever Activity is holding the picture. Tied to that
     * Activity's own lifecycle, so there is nothing to release by hand, and
     * safe to call more than once.
     */
    fun enforceLock(activity: ComponentActivity) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.collect { lock ->
                    if (lock.locked) activity.finish()
                }
            }
        }
    }

    /** App foregrounded — begin counting against the daily budget. */
    fun onAppStart() {
        foregrounded = true
        evaluate()
    }

    /** App backgrounded — pause counting and flush the counter to disk. */
    fun onAppStop() {
        foregrounded = false
        flush()
        evaluate()
    }

    /**
     * Parent PIN override: unlock this session. Verifies against the
     * profile's PIN; succeeds only when the profile actually has one.
     */
    fun overrideForSession(profile: ProfileManager.Profile, pin: String): Boolean =
        ProfileManager.hasPin(profile) && ProfileManager.verifyPin(profile, pin) && run {
            overriddenProfileId = profile.id
            evaluate()
            true
        }

    @Volatile
    private var overriddenProfileId: String? = null

    /** Drop any session override (used on profile switch). */
    fun clearOverride() {
        overriddenProfileId = null
        evaluate()
    }

    // ── internals ───────────────────────────────────────────────────────

    private fun dayKeyNow(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun nowMinutesOfDay(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            cal.get(java.util.Calendar.MINUTE)
    }

    /**
     * Recompute the lock state from the active profile + counters. Safe to
     * call from any thread; writes are funneled through the ticker thread.
     */
    private fun evaluate() {
        try {
            val profile = ProfileManager.activeProfile.value
            if (profile == null || profile.kidsMaxAge == null || overriddenProfileId == profile.id) {
                _state.value = LockState(locked = false)
                return
            }

            // Load/roll the persisted counter for THIS profile.
            if (profileIdForCounter != profile.id || dayKey != dayKeyNow()) {
                loadCounter(profile.id)
            }

            val bedtime = profile.kidsBedtimeMinutes
            val limit = profile.kidsDailyLimitMinutes

            val bedtimeLock = bedtime > 0 && isBedtimeLocked(nowMinutesOfDay(), bedtime)
            val budgetExhausted = limit > 0 && todayMinutes >= limit

            _state.value = when {
                bedtimeLock -> LockState(
                    locked = true,
                    reason = LockState.Reason.BEDTIME,
                    profileId = profile.id,
                    profileName = profile.name
                )
                budgetExhausted -> LockState(
                    locked = true,
                    reason = LockState.Reason.DAILY_LIMIT,
                    profileId = profile.id,
                    profileName = profile.name,
                    minutesLeftToday = 0,
                    dailyLimitMinutes = limit
                )
                else -> LockState(
                    locked = false,
                    profileId = profile.id,
                    minutesLeftToday = if (limit > 0) (limit - todayMinutes).coerceAtLeast(0) else 0,
                    dailyLimitMinutes = limit
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "evaluate failed: ${t.message}")
        }
    }

    /** Bedtime window test: from cutoff until 4:00 AM next day. */
    internal fun isBedtimeLocked(nowMin: Int, bedtimeMin: Int): Boolean {
        if (bedtimeMin <= 0) return false
        return if (bedtimeMin >= BEDTIME_UNLOCK_MINUTES) {
            // Cutoff at/after 4 AM: lock from cutoff to midnight, and after
            // midnight only while still before 4 AM.
            nowMin >= bedtimeMin || nowMin < BEDTIME_UNLOCK_MINUTES
        } else {
            // Cutoff before 4 AM is nonsensical as a "bedtime"; treat the
            // whole window as locked (defensive).
            true
        }
    }

    private fun loadCounter(pid: String) {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val storedDay = prefs.getString(KEY_DAY + pid, null)
        val storedMins = prefs.getInt(KEY_MINUTES + pid, 0)
        val today = dayKeyNow()
        dayKey = today
        todayMinutes = if (storedDay == today) storedMins else 0
        profileIdForCounter = pid
    }

    /** Persist the current counter (day-keyed; stale days self-reset on load). */
    private fun flush() {
        val pid = profileIdForCounter ?: return
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString(KEY_DAY + pid, dayKey ?: dayKeyNow())
            .putInt(KEY_MINUTES + pid, todayMinutes)
            .apply()
    }

    /**
     * Ticker step: advance the active profile's counter while foregrounded
     * (only kids profiles count), then re-evaluate the lock.
     */
    private fun evaluateTicker(tickSeconds: Int) {
        val profile = ProfileManager.activeProfile.value
        if (profile == null || profile.kidsMaxAge == null) return
        if (!foregrounded) return
        if (profileIdForCounter != profile.id || dayKey != dayKeyNow()) {
            loadCounter(profile.id)
        }
        todayMinutes += (tickSeconds + 59) / 60 // round up partial minutes
        flush()
        evaluate()
    }
}
