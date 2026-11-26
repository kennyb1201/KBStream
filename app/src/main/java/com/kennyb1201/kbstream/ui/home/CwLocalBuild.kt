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
 * Thread-safe: the rows of one pass run six at a time and each lands on its own
 * coroutine.
 */
internal class CwLocalProgress(
    seed: List<Pair<String, UpNextItem>>
) {

    /** Insertion-ordered so the rail keeps the seed's order as upgrades land. */
    private val placed = LinkedHashMap<String, UpNextItem>()

    private var landed = 0

    private val lock = Any()

    init {
        seed.forEach { (key, card) -> placed[key] = card }
    }

    /**
     * The row [key] answered with [card]. Answers whether this landing is a
     * publish point ([cwLocalPublishDue]).
     */
    fun place(key: String, card: UpNextItem): Boolean = synchronized(lock) {
        placed[key] = card
        landed += 1
        cwLocalPublishDue(landed)
    }

    /** The row [key] was dropped by this pass (the zombie card). */
    fun remove(key: String) {
        synchronized(lock) { placed.remove(key) }
    }

    /** What to publish: every seed card, with the rows that answered upgraded. */
    fun cards(): List<UpNextItem> = synchronized(lock) { placed.values.toList() }

    /** Rows that have answered so far (drops not counted). */
    fun landedCount(): Int = synchronized(lock) { landed }
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
 *  - the whole pass is capped at [deadlineAtMs] - a deadline taken from the
 *    pass's own clock, not from this call, because the history read and the
 *    seed before it are part of the pass. On expiry the remaining rows are
 *    cancelled and the call answers with what has landed; the rail keeps the
 *    last published slice, and the next pass asks again.
 *
 * [enrich] answers null for a row the pass DROPS, which removes it from the
 * slice rather than leaving its seed card behind. Every row still carries its
 * own 8s timeout (F3) inside [enrich]; this bound is the pass's.
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
    enrich: suspend (R) -> UpNextItem?
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
                    val card = enrich(row)
                    if (card == null) {
                        progress.remove(keyOf(row))
                        null
                    } else {
                        landed.add(card)
                        if (progress.place(keyOf(row), card)) {
                            publish(progress.cards())
                        }
                        card
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
    // The deadline took the rest: what answered is the pass's answer.
    return completed ?: landed.toList()
}
