package com.kennyb1201.kbstream.data.iptv

/**
 * Why the TV guide refresh ran and never landed (spec 2026-11-14, F1c/F3).
 *
 * The field paste had every flag reading clean - `failing=no`, `stall=none`,
 * `emptyRuns=0` - while no import had landed in 22 hours across four attempts.
 * The reason is structural: the worker records the outcome of a pass only when
 * the pass reaches its own empty-run branch. A run that declines at the
 * freshness gate, defers for playback, times out on a source, is stopped by
 * WorkManager mid-import, or throws leaves NOTHING behind, so a capture cannot
 * tell a healthy quiet pass from a permanently failing one.
 *
 * This object is the vocabulary and the arithmetic of that record: one token per
 * outcome, one durable verdict line per pass, and the rule for turning a run of
 * no-imports into the `failing` state Playlist Setup shows. Pure, so the
 * distinctions the spec asks for are unit tested rather than only observed on a
 * Stick that cannot be attached to a debugger.
 *
 * SHAPE ONLY: source names are the paste's `source N` (never a URL - the guide
 * line already redacts them), and a failure is its exception's class.
 */
internal object EpgRefreshDiagnostics {

    /** Durable: the last pass's verdict, for the paste and the setup screen. */
    const val KEY_LAST_OUTCOME = "epg_refresh_outcome"

    /** When that verdict was produced. */
    const val KEY_LAST_OUTCOME_AT = "epg_refresh_outcome_at"

    /**
     * Consecutive passes that imported nothing, however they declined. Separate
     * from `epg_empty_runs`, which counts only all-sources-answered-but-empty
     * runs - the streak that includes timeouts and stops is the one that proves
     * a refresh has stopped landing.
     */
    const val KEY_ABORT_STREAK = "epg_refresh_aborts"

    /** How many consecutive no-import passes make the refresh "failing". */
    const val FAILING_STREAK = 3

    /** Why a pass did not import: the pre-flight gate, then the sources. */
    enum class Decision {
        /** The marker is younger than the interval: a re-import would store the same window. */
        FRESH,

        /** A player is active; the pass deferred rather than compete with it. */
        DEFER,

        /** No guide source is configured. */
        NO_SOURCES,

        /** The pass ran and asked each source to import. */
        IMPORT
    }

    /** What one source did. */
    enum class SourceOutcome {
        /** The importer stored programs (the freshness marker moved). */
        LANDED,

        /** The source answered but had nothing to store (a 304, a refused empty feed). */
        NOT_LANDED,

        /** The per-source budget (8 min) expired; the import was abandoned mid-parse. */
        TIMEOUT,

        /** The import threw; [SourceResult.errorClass] names the exception. */
        FAILED,

        /** WorkManager stopped the job while this source was importing. */
        STOPPED
    }

    data class SourceResult(
        /** 1-based, in the order the pass asked - never the URL. */
        val index: Int,
        val outcome: SourceOutcome,
        val errorClass: String? = null
    )

    /** One pass, as recorded. */
    data class PassVerdict(
        val decision: Decision,
        val sources: List<SourceResult> = emptyList(),
        val imported: Int = 0,
        val stopped: Boolean = false
    )

    /** The token for a source, in the paste's short form: `1:landed`, `2:timeout`. */
    fun sourceToken(result: SourceResult): String {
        val what = when (result.outcome) {
            SourceOutcome.LANDED -> "landed"
            SourceOutcome.NOT_LANDED -> "no-new"
            SourceOutcome.TIMEOUT -> "timeout"
            SourceOutcome.STOPPED -> "stopped"
            SourceOutcome.FAILED -> "threw " + (result.errorClass ?: "unknown")
        }
        return "${result.index}:$what"
    }

    /**
     * The durable verdict line - what the next paste and the panel read.
     *
     * `refresh: import 1:landed` is the good case; `refresh: defer (player)` is
     * the one that explains a 22-hour gap with clean flags; `refresh: import
     * 1:timeout` names the candidate the spec's F1(a) asks about; `refresh:
     * stopped mid-import` names a pass WorkManager killed, which used to leave
     * no trace at all.
     */
    fun verdictLine(verdict: PassVerdict): String {
        if (verdict.stopped) {
            val partial = verdict.sources.takeIf { it.isNotEmpty() }?.joinToString(",") { sourceToken(it) }
            return if (partial.isNullOrEmpty()) "stopped mid-import" else "stopped mid-import after $partial"
        }
        return when (verdict.decision) {
            Decision.FRESH -> "fresh — skipped"
            Decision.DEFER -> "defer (player)"
            Decision.NO_SOURCES -> "no sources"
            Decision.IMPORT -> {
                val sources = verdict.sources.joinToString(",") { sourceToken(it) }
                "import $sources, landed=${verdict.imported}"
            }
        }
    }

    /**
     * The `failing` flag after a pass: a streak of passes that imported nothing,
     * whatever the reason. The old rule counted only empty-source passes, which
     * is why a run of timeouts or stops read as healthy forever.
     */
    fun failingAfter(verdict: PassVerdict, previousStreak: Int): Pair<Int, Boolean> {
        val importedNothing = !verdict.stopped &&
            verdict.decision == Decision.IMPORT &&
            verdict.imported == 0
        if (importedNothing) {
            val streak = previousStreak + 1
            return streak to (streak >= FAILING_STREAK)
        }
        // A stop is not a clean pass either: count it, but a fresh/defer pass
        // that was never asked to import must not - and neither should a stop
        // that had already landed a source (the guide did gain programs).
        if (verdict.stopped) {
            if (verdict.imported > 0) return 0 to false
            val streak = previousStreak + 1
            return streak to (streak >= FAILING_STREAK)
        }
        if (verdict.decision == Decision.IMPORT && verdict.imported > 0) return 0 to false
        return previousStreak to (previousStreak >= FAILING_STREAK)
    }

    /**
     * A future stamp as a countdown, and `none` when there is no meaningful one.
     *
     * WorkManager reports `Long.MAX_VALUE` for "no next run is scheduled" (and
     * while a run is in flight, before the next one is computed). Formatting it
     * as a delta produced the field paste's `next=in 2562047289488h 51m`, which
     * reads as a scheduling bug and is not one. Anything more than a year out is
     * no schedule this app made, so it prints as `none` too.
     */
    fun countdown(nowMs: Long, atMs: Long): String {
        if (atMs <= 0L || atMs == Long.MAX_VALUE) return "none"
        val delta = atMs - nowMs
        if (delta <= 0L) return "due"
        if (delta > NO_SCHEDULE_HORIZON_MS) return "none"
        val minutes = delta / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            days > 0L -> "in ${days}d ${hours % 24L}h"
            hours > 0L -> "in ${hours}h ${minutes % 60L}m"
            else -> "in ${minutes}m"
        }
    }

    /** A year: longer than any interval this app schedules. */
    private const val NO_SCHEDULE_HORIZON_MS = 365L * 24L * 60L * 60L * 1000L
}
