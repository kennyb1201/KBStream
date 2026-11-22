package com.kennyb1201.kbstream.ui.home

/**
 * Coalesces Home's streaming rail publishes onto a cadence (perf spec
 * 2026-11-22, item 3).
 *
 * Progressive publication is deliberate: a catalog row appears as soon as it
 * resolves instead of waiting for the slowest addon. But a cold load resolves
 * 60-90 rows, and each publish replaced the whole rail list - so every
 * derivation downstream re-ran per rail (the hidden-titles pass, the
 * hide-watched pass and every `remember(rails)` on the screen), which is the
 * during-load frame cost the audit measured against a steady walk that was
 * already at p50 16 ms.
 *
 * The gate publishes the FIRST rail immediately - the screen's first row must
 * not wait on a timer - and then at most once per [cadenceMs], carrying every
 * rail offered since the last publish. Nothing is dropped: a rail the cadence
 * held back rides the next publish, and the build's own final publish replaces
 * the list when the fan-out ends.
 *
 * Thread-safe, because the fan-out resolves rails on several threads. The
 * [publish] callback runs OUTSIDE the lock, so a publish that touches the UI
 * state cannot serialize the fan-out's other arrivals. The clock is injected,
 * so the cadence is testable on the JVM.
 */
internal class RailStreamGate<R>(
    private val cadenceMs: Long,
    private val now: () -> Long
) {

    private val lock = Any()
    private val pending = ArrayList<R>(8)
    private var lastPublishedAt = Long.MIN_VALUE

    /**
     * Offers [rail] and, when the cadence allows it, calls [publish] with every
     * rail owed so far. Returns whether this offer published.
     */
    fun offer(rail: R, publish: (List<R>) -> Unit): Boolean {
        val batch =
            synchronized(lock) {
                pending += rail
                val at = now()
                if (
                    lastPublishedAt != Long.MIN_VALUE &&
                    at - lastPublishedAt < cadenceMs
                ) {
                    null
                } else {
                    lastPublishedAt = at
                    pending.toList().also { pending.clear() }
                }
            } ?: return false
        publish(batch)
        return true
    }
}
