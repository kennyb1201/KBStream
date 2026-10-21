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
import com.kennyb1201.kbstream.data.format.DateFormats
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
 * The second credential is the app-level PARENT PIN ([setParentPin]), for the
 * profile that has a bedtime but no PIN of its own: the veil used to have no
 * exit at all in that case - Back is swallowed and everything is covered - so
 * the child was locked out of the app until the window passed. One PIN the
 * parent sets once, asked only at the veil, keeps daily profile switching
 * PIN-free while giving every lock a working exit. Stored device-local and
 * hashed with [ProfileManager.hashPin] - the same function a profile PIN uses
 * - never synced, never logged, and never readable back.
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
    /** The counter as SECONDS (m4): the minutes key rounded every 30 s tick up. */
    private const val KEY_SECONDS = "secs_"
    private const val KEY_PARENT_PIN_HASH = "parent_pin_hash"
    /** Wall + monotonic clock anchors, and the day a clock change was seen (M3). */
    private const val KEY_CLOCK_WALL = "clock_wall"
    private const val KEY_CLOCK_ELAPSED = "clock_elapsed"
    private const val KEY_CLOCK_TAMPER_DAY = "clock_tamper_day"
    private const val CLOCK_TOLERANCE_MS = 2 * 60 * 1000L
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
        enum class Reason {
            NONE,
            DAILY_LIMIT,
            BEDTIME,
            /** The device clock moved without time passing (see [clockConsistent]). */
            CLOCK,
            /** The guard could not work out the lock at all: fail CLOSED (m13). */
            UNKNOWN
        }
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

    // Per-profile persisted minute counters: { day: "yyyyMMdd", mins: Int }.
    //
    // @Volatile: the accumulation ticker runs on Dispatchers.Default and these
    // are read on the main thread (the lock snapshot [state] and [enforceLock]).
    // Without it a main-thread read can observe a stale day/counter while the
    // ticker has already rolled over, so the overlay shows yesterday's minutes
    // or a lock that never lifts. The ticker is one coroutine, so its own
    // writes are ordered; only cross-thread visibility is what these need.
    @Volatile
    private var dayKey: String? = null

    /**
     * The active profile's usage today, in SECONDS.
     *
     * Seconds, not minutes, because the ticker runs every 30 s and the old
     * `todayMinutes += (30 + 59) / 60` rounded each tick UP to a whole minute -
     * so a 60-minute limit was really about 30 minutes of watching (m4). The
     * minutes the lock reports are derived from this.
     */
    @Volatile
    private var todaySeconds: Int = 0

    @Volatile
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

    /**
     * Re-read the lock for the CURRENT active profile, now.
     *
     * The guard otherwise only evaluates on its 30 s tick, on app
     * start/stop and on a parent override — so anything that changes who the
     * active profile IS has to poke it, or the veil answers for the profile
     * that was active a beat ago (up to half a minute of the wrong answer in
     * both directions). [ProfileManager.setActive] is that caller.
     */
    fun refresh() = evaluate()

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
     * Parent PIN override: unlock this session.
     *
     * Two credentials are accepted, and a viewer is never told which one
     * answered (the veil's field says "Parent PIN to unlock" either way): the
     * locked profile's OWN PIN first, then the app-level [verifyParentPin]. The
     * second is what makes a lock on a PIN-less profile escapable at all - the
     * profile-PIN-only version left that veil with no exit, which is the brick
     * the parent PIN exists to close.
     *
     * Neither set still fails, exactly as before: a locked profile with no
     * credential of any kind is unchanged behaviour, and the editor refuses to
     * create that state any more (see ProfileEditScreen's save gate).
     */
    fun overrideForSession(profile: ProfileManager.Profile, pin: String): Boolean {
        val byProfilePin = ProfileManager.hasPin(profile) && ProfileManager.verifyPin(profile, pin)
        val byParentPin = verifyParentPin(pin)
        if (!byProfilePin && !byParentPin) return false
        overriddenProfileId = profile.id
        evaluate()
        return true
    }

    // ── the app-level parent PIN ────────────────────────────────────────

    /** The stored parent-PIN hash, or null when no parent PIN has been set. */
    private fun parentPinHash(): String? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_PARENT_PIN_HASH, null)
            ?.takeIf { it.isNotBlank() }

    /**
     * Whether a parent PIN exists on this device.
     *
     * Read straight from prefs rather than cached: the veil is the only reader
     * that runs while a lock is engaged, and the writers (the editor's prompt
     * and its Parent PIN row) only ever run with the veil down.
     */
    fun hasParentPin(): Boolean = parentPinHash() != null

    /**
     * Sets (4 digits) or clears (null/blank) the app-level parent PIN.
     *
     * Device-local by design: this is a credential for THIS TV, so it is not
     * part of the synced profile blob, and only the hash is ever stored. Returns
     * false on malformed input, the same 4-digit rule a profile PIN follows.
     */
    fun setParentPin(pin: String?): Boolean {
        val clean = pin?.trim().orEmpty()
        if (clean.isNotEmpty() && !ProfileManager.isValidPin(clean)) return false
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return false
        prefs.edit()
            .putString(
                KEY_PARENT_PIN_HASH,
                clean.takeIf { it.isNotEmpty() }?.let { ProfileManager.hashPin(it) }
            )
            .apply()
        return true
    }

    /**
     * True only when a parent PIN exists AND [pin] matches it.
     *
     * The one shape the profile-PIN check already has (see
     * [ProfileManager.verifyPin]): false for "there is nothing to check", so it
     * can never answer yes for a device with no parent PIN.
     */
    fun verifyParentPin(pin: String): Boolean {
        val stored = parentPinHash() ?: return false
        if (!ProfileManager.verifyHash(stored, pin)) return false
        // Same upgrade rule a profile PIN follows: the plain PIN exists only
        // here, so a legacy unsalted hash is rewritten on first success.
        if (ProfileManager.pinHashNeedsUpgrade(stored)) {
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(KEY_PARENT_PIN_HASH, ProfileManager.hashPin(pin))
                ?.apply()
        }
        return true
    }

    @Volatile
    private var overriddenProfileId: String? = null

    // ── internals ───────────────────────────────────────────────────────

    private fun dayKeyNow(): String =
        DateFormats.now(DateFormats.COMPACT_DAY)

    /** The guard's own prefs file, or null before MainActivity hands it a Context. */
    private fun prefs(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether the wall clock and the monotonic clock advanced together (M3).
     *
     * Pure and separate, because this is the whole defence against the easiest
     * escape there is: change the device clock and the daily budget is a fresh
     * day's, or bedtime is hours away. `elapsedRealtime` counts real time since
     * boot and cannot be set by the viewer, so the two deltas agreeing is what
     * "the clock has not been touched" means. A REBOOT resets the monotonic
     * clock and has nothing to compare ([elapsedNow] < [elapsedAnchor]), and a
     * first run has no anchor at all: both are trusted, because neither is
     * evidence of anything.
     */
    internal fun clockConsistent(
        wallNow: Long,
        elapsedNow: Long,
        wallAnchor: Long,
        elapsedAnchor: Long
    ): Boolean {
        if (wallAnchor == 0L || elapsedAnchor == 0L) return true
        if (elapsedNow < elapsedAnchor) return true
        val wallDelta = wallNow - wallAnchor
        val elapsedDelta = elapsedNow - elapsedAnchor
        return kotlin.math.abs(wallDelta - elapsedDelta) <= CLOCK_TOLERANCE_MS
    }

    /**
     * Checks the clock pair and re-anchors it when it agrees.
     *
     * On a mismatch the DAY is recorded, not just the moment: the lock then holds
     * for the rest of that day - through relaunches, and even if the clock is put
     * back where it started - and lifts when the day rolls over. That is what
     * makes "put the clock back" useless rather than a one-tick hiccup.
     */
    private fun clockTrustedNow(): Boolean {
        val stored = prefs() ?: return true
        val wall = System.currentTimeMillis()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val anchorWall = stored.getLong(KEY_CLOCK_WALL, 0L)
        val anchorElapsed = stored.getLong(KEY_CLOCK_ELAPSED, 0L)
        val consistent = clockConsistent(wall, elapsed, anchorWall, anchorElapsed)
        // Named editor + a plain apply() call: `editor.apply { ... }` would bind
        // to Kotlin's scope function instead of committing the edit.
        val editor = stored.edit()
            .putLong(KEY_CLOCK_WALL, wall)
            .putLong(KEY_CLOCK_ELAPSED, elapsed)
        if (!consistent) editor.putString(KEY_CLOCK_TAMPER_DAY, dayKeyNow())
        editor.apply()
        return consistent
    }

    /** Whether a clock change was already seen TODAY (see [clockTrustedNow]). */
    private fun clockTamperedToday(): Boolean =
        prefs()?.getString(KEY_CLOCK_TAMPER_DAY, null) == dayKeyNow()

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

            // M3: the clock is checked BEFORE the counters, because a clock that
            // has been moved is what makes them lie. A change seen today keeps
            // the profile locked for the rest of the day.
            val clockOk = clockTrustedNow() && !clockTamperedToday()

            // Load/roll the persisted counter for THIS profile.
            if (profileIdForCounter != profile.id || dayKey != dayKeyNow()) {
                loadCounter(profile.id)
            }

            val bedtime = profile.kidsBedtimeMinutes
            val limit = profile.kidsDailyLimitMinutes

            val bedtimeLock = bedtime > 0 && isBedtimeLocked(nowMinutesOfDay(), bedtime)
            val budgetExhausted = limit > 0 && todaySeconds >= limit * 60
            val minutesLeft = if (limit > 0) {
                ((limit * 60 - todaySeconds) + 59) / 60
            } else 0

            _state.value = when {
                !clockOk -> LockState(
                    locked = true,
                    reason = LockState.Reason.CLOCK,
                    profileId = profile.id,
                    profileName = profile.name
                )
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
                    minutesLeftToday = minutesLeft,
                    dailyLimitMinutes = limit
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "evaluate failed: ${t.message}")
            // m13: fail CLOSED. The old catch kept whatever the last state was,
            // so a throw at the moment a lock should engage left a kids profile
            // wide open - the one direction a guard must never round. A locked
            // state with no known reason is recoverable (the tick re-evaluates
            // in 30 s); an unlocked veil over a locked profile is not.
            val profile = runCatching { ProfileManager.activeProfile.value }.getOrNull()
            _state.value = if (profile?.kidsMaxAge != null) {
                LockState(
                    locked = true,
                    reason = LockState.Reason.UNKNOWN,
                    profileId = profile.id,
                    profileName = profile.name
                )
            } else {
                LockState(locked = false)
            }
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
        val prefs = prefs() ?: return
        val storedDay = prefs.getString(KEY_DAY + pid, null)
        // Seconds first, with the minutes key as the migration source: a counter
        // written by an older build is a whole number of minutes, and reading it
        // as seconds would hand the profile a 60x longer budget.
        val storedSeconds = prefs.getInt(KEY_SECONDS + pid, -1)
        val storedMins = prefs.getInt(KEY_MINUTES + pid, 0)
        val today = dayKeyNow()
        dayKey = today
        todaySeconds = when {
            storedDay != today -> 0
            storedSeconds >= 0 -> storedSeconds
            else -> storedMins * 60
        }
        profileIdForCounter = pid
    }

    /** Persist the current counter (day-keyed; stale days self-reset on load). */
    private fun flush() {
        val pid = profileIdForCounter ?: return
        val prefs = prefs() ?: return
        prefs.edit()
            .putString(KEY_DAY + pid, dayKey ?: dayKeyNow())
            .putInt(KEY_SECONDS + pid, todaySeconds)
            .putInt(KEY_MINUTES + pid, todaySeconds / 60)
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
        // Seconds counted exactly (m4): a 30 s tick is 30 s of budget, not the
        // whole minute the old rounding charged it.
        todaySeconds += tickSeconds
        flush()
        evaluate()
    }
}
