package com.kennyb1201.kbstream.data.network

/**
 * Where a service keeps its 429 park so it outlives the process (R3).
 *
 * Only the deadline is stored - a `Long` of epoch millis - because that is the
 * whole park. The rule that decides the deadline ([retryAfterMs]) stays in the
 * service's own guard: a 429 on one bucket says nothing about another, so the
 * guards are never merged, and neither are their stores' keys.
 *
 * Implemented by a SharedPreferences-backed store at app start (see
 * `RateLimitParkInstall.kt`) and by [NoParkUntilStore] in plain JVM tests.
 */
internal interface ParkUntilStore {

    /** The persisted deadline, or 0 when nothing was ever written. */
    fun readParkUntilMs(): Long

    /** Persist a deadline. Called once per 429, never per request. */
    fun writeParkUntilMs(untilMs: Long)
}

/** The no-op store: a guard with no installed store simply does not persist. */
internal object NoParkUntilStore : ParkUntilStore {
    override fun readParkUntilMs(): Long = 0L

    override fun writeParkUntilMs(untilMs: Long) = Unit
}

/** The floor under every 429 park, whatever the header says (R1). */
internal const val RATE_LIMIT_MIN_PARK_MS = 5_000L

/** What a 429 without a usable `Retry-After` means (R1). */
internal const val RATE_LIMIT_DEFAULT_PARK_MS = 60_000L

/**
 * `Retry-After` (seconds) as a park in milliseconds, or null when the header is
 * absent or not a number.
 *
 * R1: never clamped below the header - a daily quota answers with an hours-long
 * value and clamping it is what turned one Simkl 429 into a polling loop. The
 * [RATE_LIMIT_MIN_PARK_MS] floor only lifts a header that is absurdly short.
 */
internal fun retryAfterParkMs(
    header: String?,
    defaultMs: Long = RATE_LIMIT_DEFAULT_PARK_MS
): Long {
    val seconds = header?.trim()?.toLongOrNull()
    val fromHeader = seconds?.takeIf { it > 0L }?.times(1_000L)
    return fromHeader?.coerceAtLeast(RATE_LIMIT_MIN_PARK_MS) ?: defaultMs
}
