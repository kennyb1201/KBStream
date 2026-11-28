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
        STOPPED,

        /**
         * A stop-retry found this source already landed in the stopped pass, so
         * it was not downloaded again (see `EpgRefreshScheduler.INPUT_STOP_RETRY`).
         *
         * Neither a success nor a failure of THIS pass: the source's own stamp
         * is recent, and re-downloading a multi-minute guide to store what is
         * already stored is exactly the cost the per-source stamps exist to
         * avoid.
         */
        SKIPPED
    }

    data class SourceResult(
        /** 1-based, in the order the pass asked - never the URL. */
        val index: Int,
        val outcome: SourceOutcome,
        val errorClass: String? = null,
        /**
         * Where a cut landed, when the outcome is a cut: the importer's own
         * phase and counters at the moment the budget expired
         * (`saving,103622 rows,48MB`).
         *
         * Recorded because "1:timeout" cannot be acted on: the same token covers
         * a download that is a minute short and a guide that needs a window no
         * worker has. Null for every other outcome, so the line's grammar is
         * unchanged where nothing was cut.
         */
        val detail: String? = null
    )

    /** One pass, as recorded. */
    data class PassVerdict(
        val decision: Decision,
        val sources: List<SourceResult> = emptyList(),
        val imported: Int = 0,
        val stopped: Boolean = false,
        /**
         * True when this pass was the playback-end catch-up rather than a 6h
         * tick (spec 2026-11-16, F4). Prefixed onto the line, because "the
         * refresh ran while I never opened the Guide" and "the tick finally got
         * through" are different answers to the same field question.
         */
        val catchUp: Boolean = false,

        /**
         * True when this run exists because WorkManager stopped an earlier pass
         * (`EpgRefreshScheduler.INPUT_STOP_RETRY`). Prefixed onto the line so a
         * paste can tell it from the 6h tick and from a playback-end catch-up.
         */
        val stopRetry: Boolean = false,

        /**
         * True when at least one source continued a stopped import instead of
         * starting it over (its staged rows were kept). Prefixed onto the line:
         * the field test's question is whether the retry picked the import up
         * or began it again.
         */
        val resumed: Boolean = false
    )

    /** The token for a source, in the paste's short form: `1:landed`, `2:timeout`. */
    fun sourceToken(result: SourceResult): String {
        val what = when (result.outcome) {
            SourceOutcome.LANDED -> "landed"
            SourceOutcome.NOT_LANDED -> "no-new"
            SourceOutcome.TIMEOUT -> "timeout"
            SourceOutcome.STOPPED -> "stopped"
            SourceOutcome.SKIPPED -> "fresh"
            SourceOutcome.FAILED -> "threw " + (result.errorClass ?: "unknown")
        }
        val detail = result.detail?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "${result.index}:$what$detail"
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
        val body = when {
            verdict.stopped -> {
                val partial = verdict.sources.takeIf { it.isNotEmpty() }
                    ?.joinToString(",") { sourceToken(it) }
                if (partial.isNullOrEmpty()) {
                    "stopped mid-import"
                } else {
                    "stopped mid-import after $partial"
                }
            }
            else -> bodyLine(verdict)
        }
        // The markers are PREFIXES, so the rest of the grammar is untouched:
        // `refresh: catch-up import 1:landed` reads as the line it always did,
        // with the one fact this spec adds in front of it. A stop-retry that
        // resumed reads `stop-retry resumed import 1:landed, landed=1` - the
        // trigger, then whether the import continued or started over.
        val prefixes = buildList {
            if (verdict.catchUp) add("catch-up")
            if (verdict.stopRetry) add("stop-retry")
            if (verdict.resumed) add("resumed")
        }
        return (prefixes + body).joinToString(" ")
    }

    private fun bodyLine(verdict: PassVerdict): String = when (verdict.decision) {
        Decision.FRESH -> "fresh — skipped"
        Decision.DEFER -> "defer (player)"
        Decision.NO_SOURCES -> "no sources"
        Decision.IMPORT -> {
            val sources = verdict.sources.joinToString(",") { sourceToken(it) }
            "import $sources, landed=${verdict.imported}"
        }
    }

    /**
     * The `failing` flag after a pass: a streak of passes that imported nothing,
     * whatever the reason. The old rule counted only empty-source passes, which
     * is why a run of timeouts or stops read as healthy forever.
     */
    fun failingAfter(verdict: PassVerdict, previousStreak: Int): Pair<Int, Boolean> {
        // A stop-retry that only met sources it had already landed did not fail
        // at anything: every source it was given was fresh. Counting it would
        // make the switch to `failing` - which stops the retries - depend on how
        // many times the pass was continued rather than on the guide.
        if (verdict.sources.isNotEmpty() && verdict.sources.all { it.outcome == SourceOutcome.SKIPPED }) {
            return previousStreak to (previousStreak >= FAILING_STREAK)
        }
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
