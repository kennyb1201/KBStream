package com.kennyb1201.kbstream.data.reporting

import android.os.SystemClock

/**
 * Lightweight in-memory timing trace for startup and network work.
 *
 * Why: "is there any way to speed everything up" is unanswerable from a
 * diagnostics dump that only lists counts. This records a bounded ring of
 * labeled durations (app start, home refreshes, per-service HTTP calls) and
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

    /** Hosts the summary can name. See [hostsByLabel]. */
    private const val MAX_HOST_LABELS = 12

    /** Calls at or above this are worth naming in the summary. */
    private const val SLOW_MS = 1_200L

    /**
     * The phase labels that are pinned out of the ring (see [startupPinned]).
     * A trailing dot, so `startup.mainCreate` is one and `startupFoo` is not.
     */
    private const val STARTUP_PREFIX = "startup."

    /**
     * The Home-load legs, pinned for the same reason (spec 2026-11-15, Home load
     * F1/S5).
     *
     * `home.railsReady` is the headline number of that spec (8641ms on a cold
     * TCL, quoted in the code), and the split that decides what to do about it -
     * firstRail, pinned, catalogRails, railsReady, refreshAll - is exactly the
     * kind of evidence the 400-sample ring eats before the report is taken: the
     * same eviction that lost the startup phases, from the same 291 artwork
     * lookups Home generates after launch.
     */
    private const val HOME_PREFIX = "home."

    private data class Sample(
        val label: String,
        val ms: Long,
        val atMs: Long,
        val ok: Boolean,
        /**
         * The value is a COUNT, not a duration. See [recordCount]: it stays in
         * the ring so [latestByPrefix] can read it beside the stages, and is
         * kept out of the duration views ([summary]'s ranking and slow list) so
         * "340 programs" is never read as 340 milliseconds.
         */
        val count: Boolean = false
    )

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()

    /**
     * The launch phases, kept beside the ring so they survive it filling
     * (spec 2026-11-15).
     *
     * The ring holds [MAX_SAMPLES] samples, and a cold start fills it long before
     * the report can be taken: Home's artwork lookups alone ran to 291 in the
     * field paste that prompted this, and every one of them evicted a `startup.*`
     * phase the report was supposed to carry. The 323 cold-start paste has no
     * `startup:` line at all for exactly that reason - a diagnostics gap caused
     * by the diagnostics ring, not by the build.
     *
     * Bounded by construction rather than by a cap: the labels are the handful of
     * `startupStep` calls in MainApplication, so this holds a fixed few entries
     * however long the session runs. Per-PROCESS, deliberately: nothing here
     * outlives the process, so the line always describes THIS launch and never a
     * phase from a previous one. And [reset] (the "clear diagnostics" button)
     * does not empty it - the launch phases are facts about this process that
     * clearing the ring cannot make untrue, and dropping them would remove the
     * only cold-start evidence the report has.
     *
     * Nothing else changes for the ring itself: [add] still appends and still
     * evicts, and [record]'s slow-sample forward still fires once per slow
     * sample, so pinning adds no second Sentry event.
     */
    private val startupPinned = LinkedHashMap<String, Long>()

    /** [startupPinned] for the `home.*` legs - bounded for the same reason. */
    private val homePinned = LinkedHashMap<String, Long>()

    /**
     * Label to the most recent host seen under it, for labels whose name does
     * not say where the call went.
     *
     * `http.ip` is the reason this exists: the label is shared by every bare
     * address, so without the host beside it a report can say that 20 seconds
     * went somewhere unnamed but not which endpoint to fix. Bounded, and only
     * ever written for the labels the interceptor nominates.
     */
    private val hostsByLabel = LinkedHashMap<String, String>()

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

    /**
     * Labels already recorded through [recordLaunch]. Process-lifetime, and
     * deliberately NOT cleared by [reset]: the launch happens once per process,
     * while `reset` is the "clear diagnostics" button.
     */
    private val launchLabels = mutableSetOf<String>()

    /**
     * [record] for a ONE-OFF launch figure, returning whether it landed.
     *
     * A launch figure is elapsed-PROCESS time, so it only means what its label
     * says on the process's first create. Recorded again from a re-created
     * activity — a configuration change, a theme or profile `recreate()`, or
     * simply coming back to Home after an episode — the same label reports the
     * whole session as a "startup" cost. That is not hypothetical: a capture
     * with 7 minutes of playback on it put `startup.mainCreate=441900ms` at the
     * head of both [summary]'s slowest list and the diagnostics startup
     * breakdown, while the real cold start in that same capture was 118ms. It
     * buried the one genuine symptom in the report under an artifact, and it
     * reads exactly like a hung launch.
     */
    fun recordLaunch(label: String, ms: Long): Boolean {
        if (!synchronized(lock) { launchLabels.add(label) }) return false
        record(label, ms)
        return true
    }

    /** Records one finished operation. Safe from any thread. */
    fun record(label: String, ms: Long, ok: Boolean = true) {
        add(Sample(label, ms, SystemClock.elapsedRealtime(), ok))
        // The ring above is only readable on this device. The slow outliers
        // also leave as a Sentry distribution (a no-op without a DSN or in a
        // test), which is what makes "this rail is slow only on this model of
        // TV" answerable from the dashboard - the tracing MainApplication
        // enables has no other source of these app-specific labels. Only
        // samples at or above SLOW_MS are forwarded, so ordinary traffic costs
        // nothing.
        if (ms >= SLOW_MS) SentryPerf.slowSample(label, ms)
    }

    /**
     * Records a COUNT under [label] rather than a duration.
     *
     * The ring is one list of labeled numbers, and [latestByPrefix] reads a
     * count exactly as it reads a duration - which is what lets one report line
     * carry "8 matched, 340 programs" beside "channels 1200ms". It is separate
     * from [record] for one reason: [record] forwards anything at or above
     * [SLOW_MS] to Sentry as a slow sample, and a card count of four thousand is
     * not a four-second stall. Same storage, no slow-sample forward.
     */
    fun recordCount(label: String, value: Long) {
        add(Sample(label, value, SystemClock.elapsedRealtime(), true, count = true))
    }

    /** The ring's one insertion point; both recorders go through it. */
    private fun add(sample: Sample) {
        synchronized(lock) {
            // Pin the launch phases before anything can evict them. Only this
            // prefix: `playback.*` and the sports lines read the ring and keep
            // reading it, unchanged (spec 2026-11-15 says so explicitly).
            if (sample.label.startsWith(STARTUP_PREFIX)) {
                startupPinned[sample.label] = sample.ms
            }
            // The Home-load legs, for the same reason (spec 2026-11-15). Its
            // label set is fixed too - the handful of `home.*` records in
            // HomeViewModel - so this is bounded by construction as well.
            if (sample.label.startsWith(HOME_PREFIX)) {
                homePinned[sample.label] = sample.ms
            }
            samples.addLast(sample)
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
        }
    }

    /**
     * The launch phases recorded by this process, newest value per label, in
     * first-seen order - the same shape [latestByPrefix] returns, but read from
     * the pin so it survives the ring filling.
     *
     * Empty only when no `startup.*` label has been recorded yet (the first
     * moments of a launch), which is why the report falls back to the ring.
     */
    fun pinnedStartup(): List<Pair<String, Long>> = synchronized(lock) {
        startupPinned.entries.map { it.key to it.value }
    }

    /**
     * The Home-load legs recorded by this process, newest value per label, in
     * first-seen order - the split the Home load spec needs, read from the pin
     * so a long session cannot evict it.
     *
     * Empty only when nothing has recorded a `home.*` label yet (Home has not
     * started building), which is why the report falls back to the ring.
     */
    fun pinnedHome(): List<Pair<String, Long>> = synchronized(lock) {
        homePinned.entries.map { it.key to it.value }
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

    /**
     * Remembers the host behind [label], replacing any earlier one.
     *
     * Oldest entries fall out once the map is full: a session that talks to a
     * dozen unnamed endpoints is already past the point where one more line
     * helps the report.
     */
    fun recordHost(label: String, host: String) {
        synchronized(lock) {
            hostsByLabel[label] = host
            while (hostsByLabel.size > MAX_HOST_LABELS) {
                val oldest = hostsByLabel.keys.firstOrNull() ?: break
                hostsByLabel.remove(oldest)
            }
        }
    }

    /** The host most recently recorded for [label], or null when none was. */
    fun hostFor(label: String): String? = synchronized(lock) { hostsByLabel[label] }

    /**
     * Drops every sample (used by the "clear diagnostics" action).
     *
     * Leaves the [launchLabels] latch alone on purpose: with the ring empty, a
     * re-created activity would otherwise be free to record the whole session's
     * elapsed time as a launch figure all over again.
     */
    fun reset() {
        synchronized(lock) {
            samples.clear()
            hostsByLabel.clear()
        }
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
     * How many samples were recorded under [prefix].
     *
     * [latestByPrefix] answers "how long", which is the wrong question for an
     * event that REPEATS: one 2.6s stall and five of them read identically when
     * only the newest duration is printed, and the count is the part that says
     * whether a symptom is a one-off or a pattern.
     */
    fun count(prefix: String): Int = synchronized(lock) {
        samples.count { it.label.startsWith(prefix) }
    }

    /** The longest sample recorded under [prefix], or 0 when there is none. */
    fun maxMs(prefix: String): Long = synchronized(lock) {
        samples.filter { it.label.startsWith(prefix) }.maxOfOrNull { it.ms } ?: 0L
    }

    /**
     * The sum of every sample recorded under [prefix], or 0 when there is none.
     *
     * Unlike [latestByPrefix] (the newest duration) this adds a flag that is
     * recorded as a bare 0/1 per event, which is how a hit rate is read out of
     * a label like `live.prefetch_warm`.
     */
    fun sumMs(prefix: String): Long = synchronized(lock) {
        samples.filter { it.label.startsWith(prefix) }.sumOf { it.ms }
    }

    /**
     * Multi-line summary for the diagnostics dump. Empty string when nothing
     * was recorded, so a quiet session adds no noise.
     */
    fun summary(): String {
        val snapshot = synchronized(lock) { samples.toList() }
        if (snapshot.isEmpty()) return ""

        // Durations only: a count is not a duration, and a ranking of "where
        // the time went" that a big card count could win would send whoever
        // reads the report after the wrong thing (see [recordCount]).
        val durations = snapshot.filter { !it.count }
        val byLabel = durations.groupBy { it.label }
        val lines = mutableListOf<String>()
        // `uptime`, not `startup`. The value is how long the PROCESS has been
        // alive, and a report taken 82 minutes in printed "startup=4930806ms" -
        // which reads as a four-million-millisecond cold start and sends
        // whoever picks the report up chasing a launch-time bug that does not
        // exist, while the real failure sits further down the same page.
        lines += "perf: samples=${snapshot.size} · uptime=${uptimeLabel()}"

        // Busiest labels first — that is the order worth optimizing.
        val ranked = byLabel.entries
            .sortedByDescending { entry -> entry.value.sumOf { it.ms } }
            .take(6)
        ranked.forEach { (label, rows) ->
            val times = rows.map { it.ms }.sorted()
            val total = times.sum()
            // The host only ever exists for a label that does not name its own
            // destination, so this adds nothing to the self-describing lines.
            val host = hostFor(label)
            lines += "perf· $label n=${times.size} total=${total}ms " +
                "avg=${total / times.size}ms p95=${percentile(times, 0.95)}ms " +
                "max=${times.last()}ms" +
                (if (host == null) "" else " host=$host")
        }

        val slowest = durations
            .filter { it.ms >= SLOW_MS }
            .sortedByDescending { it.ms }
            .take(3)
        if (slowest.isNotEmpty()) {
            lines += "perf· slowest: " + slowest.joinToString(", ") { "${it.label}=${it.ms}ms" }
        }
        return lines.joinToString("\n")
    }

    /** How long this process has been alive, or "unmarked" before app start. */
    private fun uptimeLabel(): String {
        val since = sinceAppStartMs()
        return if (since < 0) "unmarked" else "${since}ms"
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0L
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
