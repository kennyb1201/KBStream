package com.kennyb1201.kbstream.ui.home

import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How many landings between incremental publishes (spec 2026-11-26, CW load F5).
 *
 * The FIRST landing publishes on its own: the rail's enriched half is worth
 * showing the moment one row has answered, and the seed cards of the rows still
 * to come are already on screen through [CwLocalProgress]. After that the
 * publishes come in waves, so a 108-row pass is ~19 publishes rather than 108 -
 * each one re-merges, re-sorts and re-prefetches, and a publish per card would
 * cost more than the enrichment it is publishing.
 */
internal const val CW_LOCAL_PUBLISH_BATCH = 6

/**
 * Whether the [landed]th row to answer publishes the running local slice.
 *
 * Pure, so the shape is testable without a ViewModel: first card, then every
 * [batch]. Against the old behaviour - one publish for the whole pass, after
 * `awaitAll` - nothing publishes until every row has answered.
 */
internal fun cwLocalPublishDue(landed: Int, batch: Int = CW_LOCAL_PUBLISH_BATCH): Boolean =
    landed > 0 && (landed == 1 || batch <= 0 || landed % batch == 0)

/** Where one row of the local pass ended up (spec 2026-11-26, CW load F5b). */
internal enum class CwRowOutcome {
    IN_FLIGHT,
    ANSWERED,
    TIMED_OUT,
    DROPPED
}

/**
 * The stages one row walks through inside `enrich` (spec 2026-11-26, CW load F5c).
 *
 * F5b's summary could say the rows were slow but not WHERE, because a row cut
 * off before landing recorded no lookup time at all. These are the stages the
 * enrich lambda already runs through, named so the summary can point at one:
 * the row's TMDB detail lookup, the season/episode resolution, the watched-state
 * reads and the next-up card computation.
 *
 * The labels are the summary's `slowStep=<label>` spelling.
 */
internal enum class CwRowStep(val label: String) {
    DETAIL("detail"),
    SEASONS("seasons"),
    WATCHED("watched"),
    NEXTUP("nextup")
}

/**
 * One row's timeline inside one pass (spec 2026-11-26, CW load F5b).
 *
 * Every field is an offset from the build's start - the clock every `cwload:`
 * line already uses - so `firstLand` can be compared with the `publish` lines
 * directly. The point of the record is the F5b question: the first enriched
 * publish is still slow, and the paste could not say whether the first ROW was
 * slow, the row waited for a permit, or the publish gating fired late.
 */
internal class CwRowTiming(
    /** The history row id this row answers for. */
    val key: String,
    /** When the row's coroutine began, in ms since the build started. */
    val startedAtMs: Long
) {

    /** When the row got its six-permit semaphore permit, or -1 if it never did. */
    var permitAcquiredAtMs: Long = -1L

    /** `"mem"`, `"disk"`, `"shared"` or `"net"` - the detail lookup's own answer. */
    var lookupSource: String? = null

    var outcome: CwRowOutcome = CwRowOutcome.IN_FLIGHT

    /** When the row's card landed in the slice, or -1. */
    var landedAtMs: Long = -1L

    /** When the row ended, landed or not. */
    var closedAtMs: Long = -1L

    /** How long the row stood in the permit queue, or -1 if it never got one. */
    val permitWaitMs: Long
        get() =
            if (permitAcquiredAtMs < 0L) -1L
            else (permitAcquiredAtMs - startedAtMs).coerceAtLeast(0L)

    /**
     * How long the row held its permit before its card landed, or -1.
     *
     * This is the number that separates "the lookups are slow" from "the rows
     * are queued": a row that spent its time waiting has a long [permitWaitMs]
     * and no lookup time at all.
     */
    val lookupMs: Long
        get() =
            if (permitAcquiredAtMs < 0L || landedAtMs < 0L) -1L
            else (landedAtMs - permitAcquiredAtMs).coerceAtLeast(0L)

    /** The row's whole life, ended or cut off by the pass deadline, or -1. */
    val totalMs: Long
        get() = if (closedAtMs < 0L) -1L else (closedAtMs - startedAtMs).coerceAtLeast(0L)

    /**
     * The stage the row is inside right now (F5c), or null before the first
     * one. [stepStartedAtMs] is when it entered it, in ms since the build
     * started.
     */
    var step: CwRowStep? = null
        internal set

    private var stepStartedAtMs: Long = -1L

    /**
     * How long the row spent in each stage, summed per stage (F5c).
     *
     * A stage is closed by the next one starting, or by the row ending - which
     * is what makes a cut-off row's time visible: the F3 timeout or the pass
     * deadline closes the stage the row was standing in, so the summary can say
     * "the rows were in `seasons`", not just "the rows were slow".
     */
    val stepDurationsMs: MutableMap<CwRowStep, Long> = LinkedHashMap()

    /** The stage the row was in when its own timeout or the pass deadline ended it. */
    var cutOffStep: CwRowStep? = null
        internal set

    /** Enters [next], closing the stage the row was in at [atMs]. */
    internal fun enterStep(next: CwRowStep, atMs: Long) {
        closeStepAt(atMs)
        step = next
        stepStartedAtMs = atMs
    }

    /** Adds the stage the row is standing in, up to [atMs], to [stepDurationsMs]. */
    internal fun closeStepAt(atMs: Long) {
        val open = step ?: return
        if (stepStartedAtMs < 0L) return
        stepDurationsMs[open] =
            (stepDurationsMs[open] ?: 0L) + (atMs - stepStartedAtMs).coerceAtLeast(0L)
        stepStartedAtMs = -1L
    }
}

/**
 * The local half of Continue Watching as the pass is building it.
 *
 * Seeded with the instant cards of every history row (the F2 seed's own cards,
 * in the history's order), then upgraded IN PLACE as each row's enrichment
 * lands. That is what keeps the rail from collapsing to the first answered card
 * and growing back: the seed card is replaced by its enriched twin, at the
 * position the seed already had. A row the pass drops (the zombie card) is
 * removed so it does not sit there un-upgraded.
 *
 * It also holds this PASS's per-row timeline (F5b). The timings live and die
 * with the progress object, exactly like the trace: nothing survives the build,
 * and no line is emitted per row.
 *
 * Thread-safe: the rows of one pass run six at a time and each lands on its own
 * coroutine.
 */
internal class CwLocalProgress(
    seed: List<Pair<String, UpNextItem>>
) {

    /** Insertion-ordered so the rail keeps the seed's order as upgrades land. */
    private val placed = LinkedHashMap<String, UpNextItem>()

    private var landed = 0

    private val timings = ArrayList<CwRowTiming>()

    private var deadlineFired = false

    private var landedWhenDeadlineFired = 0

    private val lock = Any()

    init {
        seed.forEach { (key, card) -> placed[key] = card }
    }

    /**
     * Starts timing a row at [atMs] (ms since the build started).
     *
     * The caller gets the record back because only it knows when the row got a
     * permit and where its detail lookup was answered from.
     */
    fun beginRow(key: String, atMs: Long): CwRowTiming = synchronized(lock) {
        val timing = CwRowTiming(key = key, startedAtMs = atMs)
        timings.add(timing)
        timing
    }

    /** The row got its semaphore permit at [atMs]. */
    fun notePermit(timing: CwRowTiming, atMs: Long) {
        synchronized(lock) { timing.permitAcquiredAtMs = atMs }
    }

    /** The row's detail lookup answered from [source] ("mem", "disk", "shared", "net"). */
    fun noteLookupSource(timing: CwRowTiming, source: String) {
        synchronized(lock) { timing.lookupSource = source }
    }

    /**
     * The row's enrichment did not answer in the F3 window at [atMs], so it
     * keeps its seed card: it still lands, and the summary counts it as a
     * timeout. The stage it timed out in is closed here (F5c) - the row's own
     * timeout is the only boundary the row has.
     */
    fun noteTimedOut(timing: CwRowTiming, atMs: Long) {
        synchronized(lock) {
            if (timing.outcome == CwRowOutcome.IN_FLIGHT) {
                timing.outcome = CwRowOutcome.TIMED_OUT
                timing.cutOffStep = timing.step
                timing.closeStepAt(atMs)
            }
        }
    }

    /**
     * The row entered [step] at [atMs] (F5c). The stage it left is closed, so
     * the summary's `slowStep` is the stage's own time rather than the row's.
     */
    fun noteStep(timing: CwRowTiming, step: CwRowStep, atMs: Long) {
        synchronized(lock) { timing.enterStep(step, atMs) }
    }

    /**
     * The row [key] answered with [card] at [atMs]. Answers whether this
     * landing is a publish point ([cwLocalPublishDue]).
     */
    fun place(key: String, card: UpNextItem, atMs: Long): Boolean = synchronized(lock) {
        placed[key] = card
        timingFor(key)?.let { timing ->
            // A row that kept its seed card after the F3 timeout still lands -
            // its card is real - so it keeps the outcome the timeout gave it.
            if (timing.outcome == CwRowOutcome.IN_FLIGHT) {
                timing.outcome = CwRowOutcome.ANSWERED
            }
            timing.landedAtMs = atMs
            timing.closedAtMs = atMs
            timing.closeStepAt(atMs)
        }
        landed += 1
        cwLocalPublishDue(landed)
    }

    /** The row [key] was dropped by this pass (the zombie card) at [atMs]. */
    fun remove(key: String, atMs: Long) {
        synchronized(lock) {
            placed.remove(key)
            timingFor(key)?.let { timing ->
                timing.outcome = CwRowOutcome.DROPPED
                timing.closedAtMs = atMs
                timing.closeStepAt(atMs)
            }
        }
    }

    /**
     * The pass ended at its deadline with rows still out (F5's pass deadline,
     * now row timeout + margin from the enrichment start - F5c). They are timed
     * out - cut off by the pass, which is what the summary reports - and
     * [landedAtDeadline] is how much of the rail had answered when it did.
     *
     * With the deadline ordered after the row timeout this should only cut rows
     * that never reached their own timeout (a row whose tail does not return).
     * A row that the pass cut off is never silent about the stage it stood in:
     * [CwRowTiming.cutOffStep] names it for `slowStep`.
     */
    fun noteDeadlineFired(atMs: Long) {
        synchronized(lock) {
            deadlineFired = true
            landedWhenDeadlineFired = landed
            timings.forEach { timing ->
                if (timing.outcome == CwRowOutcome.IN_FLIGHT) {
                    timing.outcome = CwRowOutcome.TIMED_OUT
                    timing.closedAtMs = atMs
                    timing.cutOffStep = timing.step
                    timing.closeStepAt(atMs)
                }
            }
        }
    }

    /** What to publish: every seed card, with the rows that answered upgraded. */
    fun cards(): List<UpNextItem> = synchronized(lock) { placed.values.toList() }

    /** Rows that have answered so far (drops not counted). */
    fun landedCount(): Int = synchronized(lock) { landed }

    /**
     * The ONE `cwload:` line this pass adds (spec 2026-11-26, CW load F5b).
     *
     * Its whole job is to say why the first enriched publish is late, from
     * `firstLand` (when the first row actually landed - the time the first
     * publish should have followed) and the latency distribution around it:
     *
     *  - `firstLand` ~ the publish's own offset, `net` high: the lookups are
     *    round trips and the cache is cold;
     *  - `permitWaitP95` high with low `p50`: the rows are queued behind the
     *    repository's other callers, not slow themselves;
     *  - `p50` low with `p95`/`max` high and the same `slowest` titles:
     *    a few titles, not the pass;
     *  - `firstLand` early but the publish late: the gating, not the lookups;
     *  - `deadlineFired=yes landedAtDeadline=0`: the pass ended with nothing
     *    answered.
     *
     * F5c adds `slowStep`: the stage ([CwRowStep]) with the highest p50 across
     * the rows, and `cutOff` - how many rows were still inside that stage when
     * their own timeout or the pass deadline ended them. F5b could only say
     * "the rows were slow"; this says which stage they were slow in, including
     * for rows that never landed.
     *
     * `p50`/`p95`/`max` are row totals (start to landed, or to the deadline for
     * a row the deadline cut off), `slowest` is by lookup time per the spec, and
     * the percentiles are nearest-rank over what the pass actually recorded -
     * no sample, no invented zero.
     */
    fun summaryLine(rows: Int): String {
        val snapshot = synchronized(lock) { timings.toList() }
        val landedNow = synchronized(lock) { landed }
        val fired = synchronized(lock) { deadlineFired }
        val landedThen = synchronized(lock) { landedWhenDeadlineFired }

        val firstLand = snapshot
            .map { it.landedAtMs }
            .filter { it >= 0L }
            .minOrNull()
        val totals = snapshot.mapNotNull { it.totalMs.takeIf { ms -> ms >= 0L } }
        val waits = snapshot.mapNotNull { it.permitWaitMs.takeIf { ms -> ms >= 0L } }
        val slowest = snapshot
            .filter { it.lookupMs >= 0L }
            .sortedByDescending { it.lookupMs }
            .take(3)
            .joinToString(",") { timing -> "${timing.key}:${timing.lookupMs}ms" }
        val cacheHits = snapshot.count { timing ->
            timing.lookupSource == "mem" ||
                timing.lookupSource == "disk" ||
                timing.lookupSource == "shared"
        }
        val net = snapshot.count { it.lookupSource == "net" }
        val timeouts = snapshot.count { it.outcome == CwRowOutcome.TIMED_OUT }
        val slowStep = slowestStep(snapshot)

        return "rows landed=$landedNow/$rows" +
            " firstLand=" + (firstLand?.let { "+${it}ms" } ?: "-") +
            " p50=${percentile(totals, 50)}ms" +
            " p95=${percentile(totals, 95)}ms" +
            " max=${totals.maxOrNull() ?: 0L}ms" +
            " timeouts=$timeouts" +
            " cacheHits=$cacheHits net=$net" +
            " permitWaitP95=${percentile(waits, 95)}ms" +
            " slowest=" + slowest.ifEmpty { "-" } +
            " slowStep=" + slowStep +
            " deadlineFired=" + (if (fired) "yes" else "no") +
            " landedAtDeadline=$landedThen"
    }

    /**
     * The stage with the highest p50 across the rows, and how many rows were
     * still inside it when they were cut off (F5c): `detail:6675ms cutOff=0`,
     * or `- cutOff=0` when no row recorded a stage.
     */
    private fun slowestStep(snapshot: List<CwRowTiming>): String {
        val perStep =
            CwRowStep.entries.mapNotNull { step ->
                val durations =
                    snapshot.mapNotNull { timing ->
                        timing.stepDurationsMs[step]?.takeIf { ms -> ms >= 0L }
                    }
                if (durations.isEmpty()) null else step to percentile(durations, 50)
            }
        val worst = perStep.maxByOrNull { (_, p50) -> p50 }
            ?: return "- cutOff=0"
        val cutOff = snapshot.count { timing -> timing.cutOffStep == worst.first }
        return "${worst.first.label}:${worst.second}ms cutOff=$cutOff"
    }

    private fun timingFor(key: String): CwRowTiming? =
        timings.lastOrNull { timing -> timing.key == key }

    private companion object {

        /** Nearest-rank over what was recorded; 0 only when nothing was. */
        fun percentile(values: List<Long>, percent: Int): Long {
            if (values.isEmpty()) return 0L
            val sorted = values.sorted()
            val index = ((sorted.size - 1) * percent / 100).coerceIn(0, sorted.size - 1)
            return sorted[index]
        }
    }
}

/**
 * Builds one pass's local cards INCREMENTALLY (spec 2026-11-26, CW load F5).
 *
 * The old shape awaited every row before the rail's first enriched publish
 * (`history.map { async { ... } }.awaitAll()`), which is why the 6390 paste's
 * enriched build published at +153s: each row is capped at 8s, but the pass is
 * not, so a slow repository made the pass's total the per-row timeout times the
 * number of waves (18 waves x 8s). Two bounds replace it:
 *
 *  - a landing publishes the running slice through [publish] as it happens
 *    (throttled by [CwLocalProgress]'s batch rule), so the first answer is on
 *    the rail in the first row's latency;
 *  - the whole pass is capped at [deadlineAtMs] - a deadline the caller takes
 *    from the ENRICHMENT start (spec 2026-11-26, CW load F5c), after the
 *    history read and the seed, at the row timeout plus a margin. Measured
 *    from `beginCwBuild` instead, as F5 did, the pass deadline at 8s arrived
 *    before a row's own 8s timeout (rows start ~1.3s in), cancelled the whole
 *    scope, and the F3 fallback never ran: the 6400 paste's `landed=0/7
 *    deadlineFired=yes landedAtDeadline=0`. Ordered after the row timeout, the
 *    row's timeout fires first, its seed card lands through the F3 fallback
 *    and publishes the running slice, and this deadline is the hard stop for
 *    the tail (a row whose fallback never returns) - with [progress]'s
 *    `landedAtDeadline` saying what had landed when it did.
 *
 *    Honest limit: a row that times out lands as the seed card it already was
 *    on the rail, so that landing does not make an enriched card appear - it
 *    makes the row count, runs the F3 fallback, and lets the first publish
 *    follow the first row's timeout instead of a later pass. The enriched
 *    cards stay as slow as their stage until `slowStep` names it.
 *
 * [enrich] answers null for a row the pass DROPS, which removes it from the
 * slice rather than leaving its seed card behind. Every row still carries its
 * own 8s timeout (F3) inside [enrich]; this bound is the pass's.
 *
 * [enrich] also receives the row's [CwRowTiming] (F5b): the row is the only
 * place that knows when its permit arrived and which cache answered its lookup.
 * The helper itself records the start, the landing and the deadline, and the
 * row's stages reach the summary through [CwLocalProgress.noteStep] (F5c).
 *
 * [nowMs] is the ONE clock the whole pass is timed on (spec 2026-11-26, CW load
 * F5d): the helper stamps [deadlineAtMs], every row's start/landing/removal and
 * the deadline with it, so the caller's step, permit and timeout stamps have to
 * come from the same function. F5c did not: the helper got raw
 * `elapsedRealtime()` while the call site passed build-relative offsets, so
 * `firstLand` read as device uptime, `permitWaitMs` went negative and coerced
 * to 0, and NEXTUP - the stage the helper closes - always won `slowest` at
 * ~1.13e9 ms. The pass now defines that clock once and hands it in.
 *
 * Every row runs concurrently and the semaphore inside [enrich] is what limits
 * the repository load - unchanged, because it is what protects TMDB.
 */
internal suspend fun <R> cwLocalItemsIncrementally(
    rows: List<R>,
    progress: CwLocalProgress,
    deadlineAtMs: Long,
    nowMs: () -> Long,
    keyOf: (R) -> String,
    publish: suspend (List<UpNextItem>) -> Unit,
    enrich: suspend (R, CwRowTiming) -> UpNextItem?
): List<UpNextItem> {
    if (rows.isEmpty()) return emptyList()

    // What actually LANDED, as opposed to what progress holds: an unlanded
    // row's seed card must not be reported as enriched, or the series
    // extension below would run on cards the pass never answered for.
    val landed = Collections.synchronizedList(ArrayList<UpNextItem>())

    val remainingMs = (deadlineAtMs - nowMs()).coerceAtLeast(1L)
    val completed = withTimeoutOrNull(remainingMs) {
        coroutineScope {
            rows.map { row ->
                async {
                    val key = keyOf(row)
                    val timing = progress.beginRow(key, nowMs())
                    val card = enrich(row, timing)
                    if (card == null) {
                        progress.remove(key, nowMs())
                        null
                    } else {
                        landed.add(card)
                        if (progress.place(key, card, nowMs())) {
                            publish(progress.cards())
                        }
                        card
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
    if (completed == null) {
        progress.noteDeadlineFired(nowMs())
        return landed.toList()
    }
    return completed
}
