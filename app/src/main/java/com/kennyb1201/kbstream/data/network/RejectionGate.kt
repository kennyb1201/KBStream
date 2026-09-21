package com.kennyb1201.kbstream.data.network

import java.util.concurrent.atomic.AtomicLong

/**
 * Remembers that an optional source answered "no" in a way that will not
 * change on the next request, so the app stops asking for a while.
 *
 * The detail screen asks two supplementary review sources on every title the
 * user opens. Both can reject the whole app rather than the individual
 * request — Trakt's Cloudflare rules 403 its public comments API and Reddit
 * 403s the search JSON — and that answer is identical for the next title, and
 * the one after it. Without this, opening ten titles sent ten rejected
 * requests and logged ten warnings for data that was never going to arrive.
 *
 * Blocking is deliberately short-lived and in-process only: a datacenter IP
 * that is blocked now is often fine later, and the next app start tries once
 * more rather than persisting a "broken forever" flag on the device.
 *
 * Thread-safe: the two sources are queried from parallel coroutines.
 */
internal class RejectionGate(
    private val blockMs: Long,
    private val now: () -> Long = System::currentTimeMillis
) {

    init {
        require(blockMs > 0) { "blockMs must be positive" }
    }

    private val blockedUntilMs = AtomicLong(0L)

    /** True while a previous rejection is still in force. */
    fun isBlocked(): Boolean = now() < blockedUntilMs.get()

    /**
     * Records a rejection. Returns true when this is the first one of the
     * current block, which is the caller's cue to log it — later rejections in
     * the same window are the expected consequence and stay silent.
     */
    fun recordRejection(): Boolean {
        val until = now() + blockMs
        while (true) {
            val current = blockedUntilMs.get()
            if (now() < current) return false
            if (blockedUntilMs.compareAndSet(current, until)) return true
        }
    }

    /** Clears the block; used when a request succeeds again (and by tests). */
    fun reset() {
        blockedUntilMs.set(0L)
    }
}
