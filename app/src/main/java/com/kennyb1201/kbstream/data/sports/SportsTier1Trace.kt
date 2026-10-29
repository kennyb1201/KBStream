package com.kennyb1201.kbstream.data.sports

import android.util.Log

/**
 * The tier-1 ledger: what the sports hub's guide lookup actually saw, for the
 * LAST matching pass only.
 *
 * Why it exists (Kenny, 2026-10-29): Lightning vs Jets, and the hub listed
 * three ESPN+ feeds while the working TSN feed - a guide row titled "NHL Hockey
 * - Winnipeg Jets at Tampa Bay Lightning", which grades STRONG - was not in the
 * list at all. Every gate between the search and the card drops such a row
 * SILENTLY: a row whose channel is not in the guide index vanishes, a row whose
 * channel is not in the playlist map vanishes, and a row that names only one
 * side is filtered by the grade. Nothing recorded which gate fired, so the
 * field case could not be narrowed from code - only guessed at, and the first
 * guess (a weak-name theory) was refuted by the guide row itself.
 *
 * So this is a TRACE, not a rule: it is built during a pass, holds the last
 * pass only, and never changes a match. A capture during the next miss names
 * the gate (search-empty, GUIDE_INDEX, CHANNEL_BY_ID, or NONE_GRADE) instead of
 * costing another round of theories.
 *
 * Bounded on purpose: a pass over a sixteen-league slate asks hundreds of
 * questions and matches a hundred cards, and a diagnostics block has to stay
 * something a person can read. The counters are exact; the per-line detail is
 * capped ([MAX_LINES] matchups and games, newest first, [MAX_DROPS] drops each).
 */
internal class SportsTier1Ledger {

    private val matchups = ArrayList<Tier1Matchup>(MAX_LINES * 2)
    private val games = ArrayList<Tier1Game>(MAX_LINES * 2)

    /** One matchup was asked. [rows] is what the search answered, [kept] what survived the guide gate. */
    fun matchup(
        label: String,
        terms: List<String>,
        rows: Int,
        kept: Int,
        fallback: Boolean,
        drops: List<Tier1Drop>,
    ) {
        val entry = Tier1Matchup(label, terms, rows, kept, fallback, drops)
        matchups += entry
        drops.forEach(::logDrop)
    }

    /** One card was matched. [trace] is the matcher's own read - see [SportsChannelMatcher.traceTier1]. */
    fun game(label: String, trace: Tier1GameTrace) {
        val entry = Tier1Game(
            label = label,
            strong = trace.strong,
            weak = trace.weak,
            near = trace.near,
            listed = trace.listed,
            drops = trace.drops,
        )
        games += entry
        trace.drops.forEach(::logDrop)
    }

    /**
     * The `sports tier1:` block for COPY DIAGNOSTICS.
     *
     * The header line is exact totals for the whole pass; the detail lines are
     * the LAST [MAX_LINES] entries, newest first, so the tail of a big pass -
     * where the game the viewer reported usually sits - is what survives the cap.
     */
    fun render(): String = buildString {
        append("sports tier1: matchups=").append(matchups.size)
        append(" games=").append(games.size)
        append(" rows=").append(matchups.sumOf { it.rows })
        append(" kept=").append(matchups.sumOf { it.kept })
        append(" dropped=").append(matchups.sumOf { it.drops.size } + games.sumOf { it.drops.size })
        append(" fallback=").append(matchups.count { it.fallback })
        matchups.takeLast(MAX_LINES).reversed().forEach { matchup ->
            appendLine()
            append("  · matchup ").append(matchup.label)
            append(" terms=").append(matchup.terms.joinToString("|"))
            append(" rows=").append(matchup.rows)
            append(" kept=").append(matchup.kept)
            append(" fallback=").append(if (matchup.fallback) "yes" else "no")
            matchup.drops.take(MAX_DROPS).forEach { drop ->
                appendLine()
                append("    drop ").append(drop.gate)
                append(" ch=").append(drop.channelId)
                append(" \"").append(drop.title).append("\"")
            }
        }
        games.takeLast(MAX_LINES).reversed().forEach { game ->
            appendLine()
            append("  · game ").append(game.label)
            append(" strong=").append(game.strong)
            append(" weak=").append(game.weak)
            append(" near=").append(game.near)
            if (game.listed.isEmpty()) append(" listed=none")
            game.listed.forEach { (name, tier) ->
                append(" ").append(name).append("=").append(tier)
            }
            game.drops.take(MAX_DROPS).forEach { drop ->
                appendLine()
                append("    drop ").append(drop.gate)
                append(" ch=").append(drop.channelId)
                append(" \"").append(drop.title).append("\"")
            }
        }
    }

    /**
     * One logcat line per dropped row, naming the gate. The report above only
     * carries the capped tail; logcat carries every one, which is what a live
     * `adb logcat` during a field capture reads.
     */
    private fun logDrop(drop: Tier1Drop) {
        Log.d(TAG, "sports tier1 ${drop.gate}: channel=${drop.channelId} title=\"${drop.title}\"")
    }

    companion object {
        const val TAG = "SPORTS_TIER1"

        /** Detail lines kept per list, newest first. */
        const val MAX_LINES = 10

        /** Drops named under one line, before the rest is left to logcat. */
        const val MAX_DROPS = 3
    }
}

/**
 * One guide row tier 1 could not use, and the gate that dropped it.
 *
 * [gate] is one of [TIER1_GATE_GUIDE_INDEX], [TIER1_GATE_CHANNEL_BY_ID] or
 * [TIER1_GATE_NONE_GRADE]; [channelId] is the row's channel as the GUIDE named
 * it (not the playlist's id), because "the guide has it and we cannot resolve
 * it" and "we resolved it elsewhere" are different answers.
 */
internal data class Tier1Drop(
    val gate: String,
    val channelId: String,
    val title: String,
)

/** One matchup the pass asked the guide about. */
internal data class Tier1Matchup(
    val label: String,
    val terms: List<String>,
    val rows: Int,
    val kept: Int,
    val fallback: Boolean,
    val drops: List<Tier1Drop>,
)

/** One card's tier attribution, as recorded in the ledger. */
internal data class Tier1Game(
    val label: String,
    val strong: Int,
    val weak: Int,
    /** Rows that named ONE side: the near misses, not every unrelated row. */
    val near: Int,
    /** Each listed feed's name and the tier that produced it, strongest first. */
    val listed: List<Pair<String, String>>,
    val drops: List<Tier1Drop>,
)

/**
 * The matcher's own read of one game, returned by
 * [SportsChannelMatcher.traceTier1]. Diagnostics-only.
 *
 * [strong] / [weak] count the in-window rows that named both sides; [near]
 * counts the ones that named exactly one (the near misses). [drops] are the
 * in-window rows that could not be used,
 * each with its own gate ([TIER1_GATE_CHANNEL_BY_ID] when the playlist map has
 * no channel for it, [TIER1_GATE_NONE_GRADE] when it names only one side);
 * [listed] is each feed the card actually shows, with the tier it came from.
 */
internal data class Tier1GameTrace(
    val strong: Int,
    val weak: Int,
    val near: Int,
    val drops: List<Tier1Drop>,
    val listed: List<Pair<String, String>>,
)

/** The row's channel is not in the hub's guide index (see `guideChannelIndex`). */
internal const val TIER1_GATE_GUIDE_INDEX = "GUIDE_INDEX"

/** The row's channel is not in the playlist map the matcher resolves against. */
internal const val TIER1_GATE_CHANNEL_BY_ID = "CHANNEL_BY_ID"

/** The row named one side or none, so the grade filtered it out. */
internal const val TIER1_GATE_NONE_GRADE = "NONE_GRADE"

/**
 * The last pass's block, held for [com.kennyb1201.kbstream.data.reporting.Diagnostics].
 *
 * A process-wide holder rather than a UI state: the report is assembled from
 * static sources already (PerfTrace and the other traces), the hub screen has
 * no reason to recompose when a pass ends, and one slot is exactly the "last
 * pass only" the ledger promises.
 */
internal object SportsTier1Trace {

    @Volatile
    private var lastBlock: String? = null

    fun record(block: String) {
        lastBlock = block
    }

    fun block(): String? = lastBlock

    /** Test hook: a fresh process has no block, and a test needs the same. */
    fun clear() {
        lastBlock = null
    }
}
