package com.kennyb1201.kbstream.data.reporting

import android.os.SystemClock
import android.util.Log

/**
 * Lightweight in-memory timing trace for startup and network work.
 *
 * Why: "is there any way to speed everything up" is unanswerable from a
 * diagnostics dump that only lists counts. This records a bounded ring of
 * labelled durations (app start, home refreshes, per-service HTTP calls) and
 * renders a one-block summary into the same `adb logcat -s DIAGNOSTICS` line
 * set the rest of Diagnostics uses, so the slowest thing is visible without
 * a profiler attached to a TV box.
 *
 * Deliberately tiny: no disk I/O, no allocations on the hot path beyond a
 * short sample, and nothing leaves the device. Durations use
 * [SystemClock.elapsedRealtime] (monotonic, unaffected by clock changes).
 */
internal object PerfTrace {

    /** Samples kept before the oldest are dropped (≈a few screens of work). */
    private const val MAX_SAMPLES = 400

    /** Calls at or above this are worth naming in the summary. */
    private const val SLOW_MS = 1_200L

    private data class Sample(
        val label: String,
        val ms: Long,
        val atMs: Long,
        val ok: Boolean
    )

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()

    @Volatile
    private var appStartElapsedMs = 0L

    /** Called once from Application.onCreate; enables the startup figure. */
    fun markAppStart() {
        appStartElapsedMs = SystemClock.elapsedRealtime()
    }

    /** Milliseconds since [markAppStart], or -1 when startup was not marked. */
    fun sinceAppStartMs(): Long {
        val start = appStartElapsedMs
        if (start <= 0L) return -1L
        return SystemClock.elapsedRealtime() - start
    }

    /** Records one finished operation. Safe from any thread. */
    fun record(label: String, ms: Long, ok: Boolean = true) {
        val sample = Sample(label, ms, SystemClock.elapsedRealtime(), ok)
        synchronized(lock) {
            samples.addLast(sample)
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
        }
    }

    /** Times [block] under [label], recording even when it throws. */
    fun <T> timed(label: String, block: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            record(label, SystemClock.elapsedRealtime() - started, true)
        }
    }

    /** [timed] for suspend work (HTTP fetches). */
    suspend fun <T> timedSuspend(label: String, block: suspend () -> T): T {
        val started = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            record(label, SystemClock.elapsedRealtime() - started, true)
        }
    }

    /** Drops every sample (used by the "clear diagnostics" action). */
    fun reset() {
        synchronized(lock) { samples.clear() }
    }

    /**
     * The newest duration under each label starting with [prefix], in the order
     * those labels were first recorded.
     *
     * Exists so the launch phases can be printed as a fixed breakdown rather
     * than left to [summary]'s busiest-labels ranking. Each phase is recorded
     * about once, so a few hundred milliseconds never out-totals a session of
     * network traffic — the breakdown therefore drops out of the top six
     * exactly on the slow launch it was added to measure.
     */
    fun latestByPrefix(prefix: String): List<Pair<String, Long>> {
        val snapshot = synchronized(lock) { samples.toList() }
        // mutableMapOf is a LinkedHashMap: assigning an existing key keeps its
        // original position, which is what makes this first-seen order.
        val lastByLabel = mutableMapOf<String, Long>()
        snapshot.forEach { sample ->
            if (sample.label.startsWith(prefix)) {
                lastByLabel[sample.label] = sample.ms
            }
        }
        return lastByLabel.entries.map { it.key to it.value }
    }

    /**
     * Multi-line summary for the diagnostics dump. Empty string when nothing
     * was recorded, so a quiet session adds no noise.
     */
    fun summary(): String {
        val snapshot = synchronized(lock) { samples.toList() }
        if (snapshot.isEmpty()) return ""

        val byLabel = snapshot.groupBy { it.label }
        val lines = mutableListOf<String>()
        lines += "perf: samples=${snapshot.size} · startup=${startupLabel()}"

        // Busiest labels first — that is the order worth optimizing.
        val ranked = byLabel.entries
            .sortedByDescending { entry -> entry.value.sumOf { it.ms } }
            .take(6)
        ranked.forEach { (label, rows) ->
            val times = rows.map { it.ms }.sorted()
            val total = times.sum()
            lines += "perf· $label n=${times.size} total=${total}ms " +
                "avg=${total / times.size}ms p95=${percentile(times, 0.95)}ms " +
                "max=${times.last()}ms"
        }

        val slowest = snapshot
            .filter { it.ms >= SLOW_MS }
            .sortedByDescending { it.ms }
            .take(3)
        if (slowest.isNotEmpty()) {
            lines += "perf· slowest: " + slowest.joinToString(", ") { "${it.label}=${it.ms}ms" }
        }
        return lines.joinToString("\n")
    }

    /**
     * Logs the summary (and only that) so a long-running session can be
     * inspected live with `adb logcat -s DIAGNOSTICS`, matching how the rest
     * of the diagnostics output reaches the user.
     */
    fun logSummary(tag: String = "DIAGNOSTICS") {
        val text = summary()
        if (text.isEmpty()) return
        text.lineSequence().forEach { Log.i(tag, it) }
    }

    private fun startupLabel(): String {
        val since = sinceAppStartMs()
        return if (since < 0) "unmarked" else "${since}ms"
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0L
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
