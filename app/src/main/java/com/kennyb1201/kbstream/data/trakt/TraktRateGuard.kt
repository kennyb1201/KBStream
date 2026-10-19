package com.kennyb1201.kbstream.data.trakt

/**
 * Trakt's request budget, from day one.
 *
 * MDBList's history is the reason this exists before the first Trakt request is
 * ever sent: a missing budget distinction turned one failed read into ~40
 * attempts in 30 seconds. The rule that came out of it is stated here rather
 * than learned again:
 *
 *  - **A budget, not an infinite retry.** Trakt's authenticated limit is 1,000
 *    calls per 5 minutes; this guard spends at most [maxRequestsInWindow] of
 *    them, leaving a margin for the other paths (and for clock drift between
 *    this device and Trakt's).
 *  - **A 429 is remembered, not re-tried.** Trakt sends `Retry-After` on a 429;
 *    [noteRetryAfter] parks every caller until it passes.
 *  - **Exhaustion is not empty.** A refused call reports "no answer", and the
 *    caller keeps whatever it already had - the `!result.isEmpty` shape - so a
 *    budget that runs out can never blank a badge or a rail.
 *
 * No Android types and a replaceable clock, so the window logic is pinned by a
 * plain JVM test.
 */
internal class TraktRateGuard(
    private val maxRequestsInWindow: Int = 900,
    private val windowMs: Long = 5L * 60L * 1_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val stamps = ArrayDeque<Long>()

    @Volatile
    private var blockedUntilMs: Long = 0L

    /** True when this request may be made right now. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        if (now < blockedUntilMs) return false
        while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) {
            stamps.removeFirst()
        }
        if (stamps.size >= maxRequestsInWindow) return false
        stamps.addLast(now)
        return true
    }

    /** Honor a 429's `Retry-After`, in seconds. */
    @Synchronized
    fun noteRetryAfter(seconds: Long) {
        val waitMs = seconds.coerceAtLeast(1L) * 1_000L
        blockedUntilMs = maxOf(blockedUntilMs, clock() + waitMs)
    }

    /** True while a server-requested pause is still running. */
    @Synchronized
    fun isBlocked(): Boolean = clock() < blockedUntilMs

    /** How long the current pause has left, in milliseconds. */
    @Synchronized
    fun blockedForMs(): Long = (blockedUntilMs - clock()).coerceAtLeast(0L)

    /** How many calls are currently inside the window (diagnostics/tests). */
    @Synchronized
    fun spentInWindow(): Int = stamps.size
}
