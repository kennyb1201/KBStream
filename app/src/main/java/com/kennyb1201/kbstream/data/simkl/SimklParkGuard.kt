package com.kennyb1201.kbstream.data.simkl

/**
 * Where one Simkl leg's 429 park lives across process death (spec 2026-11-19,
 * Simkl quota F1).
 *
 * The park used to be an in-memory field on a process-wide singleton, which is
 * correct for as long as the process lives and wrong for exactly the case that
 * matters: a TV that updates, force-stops or reboots during a 16-hour park comes
 * back with no memory of it, and the first trigger (app start, Home load) fires
 * a drain that spends a call re-learning the same 429. Against a daily quota,
 * every wasted call is quota - and `drainAgain` could spend a second one.
 *
 * One `Long` per guard, and nothing else: the spacing floor is deliberately NOT
 * persisted (a burst floor, not a quota record - see [SimklRatingGuard]).
 *
 * An interface so the guard stays Android-free and its arithmetic stays pinned by
 * a plain JVM test that injects a fake; the SharedPreferences-backed one is
 * installed at app start (see `installSimklParkStores`).
 */
internal interface SimklParkStore {

    /** The persisted park expiry, or 0 when no park is stored. */
    fun readParkUntilMs(): Long

    /** Replaces the stored park expiry with [untilMs]. */
    fun writeParkUntilMs(untilMs: Long)
}

/** The store a guard has before anything is installed: no park, nowhere to write. */
internal object NoSimklParkStore : SimklParkStore {
    override fun readParkUntilMs(): Long = 0L

    override fun writeParkUntilMs(untilMs: Long) = Unit
}

/**
 * Simkl's politeness and 429 guard, local to one leg.
 *
 * Trakt has a budget object because its authenticated limit was known; no such
 * documented limit was verified for Simkl, so this is deliberately NOT a budget
 * claim - it is a floor that keeps a burst of thumbs from becoming a burst of
 * requests (one call per [minSpacingMs] at most), plus the same
 * `Retry-After` rule Trakt gets, defaulting to a minute when the header is
 * absent.
 *
 * Two things outlive the process and one does not:
 *
 *  - the 429 park ([blockedUntilMs]) is persisted through [parkStore], because a
 *    restart that forgets it spends a quota call re-learning it (F1);
 *  - the class of the park in force ([parkLimitLabel]) is kept in memory only -
 *    it describes THIS 429, and a paste read after a restart without one says
 *    `(429)` rather than inventing a class;
 *  - the spacing floor ([lastCallAtMs]) is in memory, because a burst floor that
 *    survives a restart holds back a leg that is no longer bursting.
 *
 * No Android types and a replaceable clock, so its arithmetic is pinned by a
 * plain JVM test - the same shape as TraktRateGuard.
 */
internal class SimklRatingGuard(
    private val minSpacingMs: Long = 2_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private var parkStore: SimklParkStore = NoSimklParkStore
) {

    private var lastCallAtMs = 0L

    @Volatile
    private var blockedUntilMs = parkStore.readParkUntilMs()

    /** The quota class of the park in force, as the server names it. */
    @Volatile
    private var parkLabel: String? = null

    /**
     * Points a running guard at the persisted park.
     *
     * Separate from the constructor because the guards are process-wide values
     * created before an Android `Context` exists; a store installed with a park in
     * the future is honoured, one with an expired park is simply no park (the
     * `coerceAtLeast(0L)` arithmetic already treats the past as free).
     */
    @Synchronized
    fun attachParkStore(store: SimklParkStore) {
        parkStore = store
        blockedUntilMs = maxOf(blockedUntilMs, store.readParkUntilMs())
    }

    /** True when a call may be made now. A refusal spends nothing. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        if (now < blockedUntilMs) return false
        if (lastCallAtMs != 0L && now - lastCallAtMs < minSpacingMs) return false
        lastCallAtMs = now
        return true
    }

    /**
     * Honor a 429's `Retry-After` (seconds), or [DEFAULT_RETRY_AFTER_SECONDS],
     * and persist the park.
     *
     * [limitLabel] is the quota class the 429 named (F2). The longer park is the
     * one that holds the leg, so a shorter one arriving while a longer is in
     * force keeps the longer park AND its class - the paste has to describe the
     * limit the leg is actually waiting on.
     *
     * The write is once per 429, never per call: this is not a hot path, and a
     * store write from `tryAcquire` would be one per thumb on a loaded device.
     */
    @Synchronized
    fun noteRetryAfter(seconds: Long?, limitLabel: String? = null) {
        val waitMs = (seconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceAtLeast(1L) * 1_000L
        val until = clock() + waitMs
        if (until > blockedUntilMs) {
            blockedUntilMs = until
            parkLabel = limitLabel
        }
        parkStore.writeParkUntilMs(blockedUntilMs)
    }

    /** True while the leg is parked (a 429) or inside its spacing window. */
    @Synchronized
    fun isBlocked(): Boolean = blockedForMs() > 0L

    /**
     * Just the 429 park, without the spacing window.
     *
     * The playback/history legs use this one: a scrobble or a completion is an
     * event, not a burst, so suppressing it inside the ratings leg's two-second
     * spacing would drop events the viewer actually produced - while a 429's
     * park is exactly the instruction to stop sending.
     */
    @Synchronized
    fun parkedForMs(): Long = (blockedUntilMs - clock()).coerceAtLeast(0L)

    /**
     * How long the leg is held back for: the longer of a 429's park and the
     * spacing window, in milliseconds, 0 when a call may go now.
     *
     * The caller uses this to tell a pause worth waiting out (a second or two of
     * spacing) from one that is not (a 429's Retry-After), which is the whole
     * difference between a prompt retry and a coroutine idling for an hour.
     */
    @Synchronized
    fun blockedForMs(): Long {
        val now = clock()
        val parked = (blockedUntilMs - now).coerceAtLeast(0L)
        val spacing = if (lastCallAtMs == 0L) 0L
        else (lastCallAtMs + minSpacingMs - now).coerceAtLeast(0L)
        return maxOf(parked, spacing)
    }

    /**
     * The quota class of the park in force, or null when the leg is not parked or
     * the park predates this process.
     *
     * The diagnostics line's word (F2): `user_limit_exceeded` means this account's
     * traffic is the lever, `app_limit_exceeded` means the app's quota is spent
     * for every KBStream user and waiting is the only fix.
     */
    @Synchronized
    fun parkLimitLabel(): String? = if (blockedUntilMs > clock()) parkLabel else null

    companion object {
        /** What a 429 without a usable `Retry-After` means (the Trakt default). */
        const val DEFAULT_RETRY_AFTER_SECONDS = 60L
    }
}
