package com.kennyb1201.kbstream.ui.browse

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * The title a browse screen's Detail page was opened from.
 *
 * The browse pages (Genre/Keyword, Studio, Collection, Decade) are rebuilt from
 * scratch when their Detail page is backed out of: the list's scroll, the focus
 * and every card's requester go with the composition. Without a record of what
 * was pressed the page reopens at its first rail with its first card focused,
 * which loses the viewer's place in a list they may have scrolled a long way
 * down - the reported "Back returns to the top of the list".
 *
 * [screenKey] is what keeps the record honest: a browse page is one page per
 * route (this genre, this studio, this collection, this decade), and a record
 * left by one of them must not be read by another. See [tagBrowseScreenKey] and
 * its siblings, which are the only place the keys are spelled out.
 *
 * The screens are RAILS, not grids, which is why the record carries the rail as
 * well as the card: landing on position 20 means the list has to be put back on
 * the rail that holds it, and that rail has to be put back on position 20.
 */
data class BrowseReturnTarget(
    /** Which browse page this was pressed on (see the key builders below). */
    val screenKey: String,
    /** The pressed card, keyed the way its own rail keys it. */
    val itemId: String,
    /** Where the card sits in its rail - the position the page comes back to. */
    val position: Int,
    /**
     * The list key of the rail that holds the card, or null for a page with no
     * rail list to scroll (the collection's single rail is one row, and its
     * first card is already on screen).
     */
    val railKey: String? = null,
    /** Where that rail sat in the list, so it can be put back. */
    val railIndex: Int = 0
)

/**
 * Handoff from a browse page to itself, across the Detail page it opens. The
 * page records the card on the way out and reads the record back the next time
 * that same page composes.
 *
 * Held outside the composition because that is exactly what does not survive
 * the trip: leaving the browse screen disposes it. Read once, by the page it was
 * raised for and no other, and aged out on top of that in case the Back it was
 * waiting for never comes.
 *
 * ONE SLOT PER PAGE, not one slot in total. A single slot made a chained
 * drill-down lose the first page's record: Tag A -> Detail X -> Studio B ->
 * Detail Y recorded the studio's card over the tag's, so backing all the way out
 * reopened Tag A at the top instead of on the card that was pressed. The
 * `screenKey` only protected READING another page's record, never WRITING over
 * it. Keyed records make each page's record independent, and the garbage is
 * bounded by age ([VALID_FOR_MS]) and by [MAX_PENDING].
 *
 * The same shape as the detail page's own DetailReturnFocus, which does this for
 * its drill-downs; the two are deliberately separate records, because a browse
 * page and a detail page can both be waiting for a Back at the same time.
 */
object BrowseReturnFocus {

    private class Recorded(val target: BrowseReturnTarget, val recordedAtMs: Long)

    /** screenKey -> that page's record. Insertion-ordered, for the size cap. */
    private val pending = LinkedHashMap<String, Recorded>()

    /**
     * Long enough for a Detail page to be browsed before coming back, short
     * enough that a session-old record cannot answer for a fresh open of the
     * same genre.
     */
    private const val VALID_FOR_MS = 30 * 60 * 1000L

    /**
     * How many pages may be waiting at once. A drill-down chain is a handful;
     * this only stops a pathological session accumulating records nobody will
     * read.
     */
    private const val MAX_PENDING = 8

    /** Records the card a Detail page is being opened from, for that page. */
    @Synchronized
    fun record(target: BrowseReturnTarget) {
        val now = System.currentTimeMillis()
        prune(now)
        // Re-insert so this page's record is the newest for the size cap.
        pending.remove(target.screenKey)
        pending[target.screenKey] = Recorded(target, now)
        while (pending.size > MAX_PENDING) {
            pending.remove(pending.keys.first())
        }
    }

    /**
     * Reads the record for [screenKey], if there is one, and clears it once.
     *
     * Records for other pages stay where they are: a title opened from one
     * browse page can open another (a "More Like This" poster, a studio link),
     * and that page must not swallow the record the first one is still waiting
     * for. [BrowseReturnTarget.screenKey] is what keeps them apart - which is
     * also what makes opening a DIFFERENT genre start at the top.
     */
    @Synchronized
    fun consume(screenKey: String): BrowseReturnTarget? {
        prune(System.currentTimeMillis())
        return pending.remove(screenKey)?.target
    }

    /** Drops every aged-out record: stale for the page that raised it too. */
    private fun prune(now: Long) {
        pending.entries.removeAll { now - it.value.recordedAtMs > VALID_FOR_MS }
    }
}

/**
 * How many attempts a browse page makes at landing the focus on the card it
 * came back for, and how long it waits between them.
 *
 * The card only has a focus node once the rail has been composed AND scrolled
 * to it, which is a frame or more after the scroll that reveals the rail (see
 * [revealBrowseReturnRail]) - and a request against a card that is not composed
 * yet is a silent miss (`requestFocus()` returns false). The budget is the
 * detail page's own for the same race, long enough for two scrolls to settle.
 *
 * It counts LANDING TRIES - the recorded rail is on screen, a request was made,
 * and it missed - and never the WAITING for that rail to arrive at all. That
 * wait is bounded by [BROWSE_RETURN_WAIT_MS], and spending landing tries on it
 * is how a genre page whose pressed rail built slower than its other rails ran
 * the budget out on unrelated arrivals and fell through to its own first card
 * (see [browseReturnDecision]).
 */
internal const val BROWSE_RETURN_FOCUS_STEPS = 12

/**
 * How long a browse page holds its Detail-return record waiting for the rail
 * that card sits in, before it gives up and takes its own first card.
 *
 * The rails stream in one at a time and the pressed rail is not always early,
 * so the wait has to be long enough to cover a rail that builds behind several
 * others - and it is bounded by TIME rather than by the landing budget, because
 * no landing can be tried until the rail exists at all. Ten seconds is several
 * orders of magnitude past the frame or two a rail really takes, and short
 * enough that a rail whose load FAILED hands the D-pad to the first card
 * instead of leaving it nowhere.
 */
internal const val BROWSE_RETURN_WAIT_MS = 10_000L

internal enum class BrowseReturnDecision {
    /** Recorded rail not on screen yet: hold the record, spend nothing. */
    WAIT_FOR_RAIL,

    /** Rail here (or no rail to wait for): spend one attempt on the landing. */
    TRY_LANDING,

    /** Ordinary open, exhausted budget, or rail never arrived: first card. */
    FIRST_CARD
}

/**
 * What a browse page should do about its held Detail-return record on this
 * composition. Pure so the table below is pinnable without a composition.
 *
 * [arrivedRailKeys] is the key space the page's records use: `section.title`
 * for Tag/Studio/Decade, `"rail:${sourceId}"` for KB folders (their records
 * are keyed that way; see `KBFolderLayouts`). A null `railKey` (the folder
 * grid, which has no rail) counts as arrived.
 *
 * The two budgets are separate on purpose, and that is the whole point of the
 * rule. The landing budget ([BROWSE_RETURN_FOCUS_STEPS]) is spent by tries,
 * because a try means a request was made against a card that has a focus node;
 * the wait is spent by TIME, because the arrival of a rail is not the page's
 * doing - charging a landing attempt for it abandoned the card the page had
 * been handed as soon as the page's OTHER rails arrived first.
 *
 * The wait is bounded so a rail whose load failed falls back to the first card
 * instead of stranding the D-pad; the 30-minute [BrowseReturnFocus] age-out
 * still bounds cross-session staleness.
 */
internal fun browseReturnDecision(
    recorded: BrowseReturnTarget?,
    arrivedRailKeys: Set<String>,
    attemptsSpent: Int,
    waitStartMs: Long,
    nowMs: Long
): BrowseReturnDecision {
    if (recorded == null) return BrowseReturnDecision.FIRST_CARD
    val railKey = recorded.railKey
    if (railKey != null && railKey !in arrivedRailKeys) {
        return if (waitStartMs == 0L || nowMs - waitStartMs < BROWSE_RETURN_WAIT_MS) {
            BrowseReturnDecision.WAIT_FOR_RAIL
        } else {
            BrowseReturnDecision.FIRST_CARD
        }
    }
    return if (attemptsSpent < BROWSE_RETURN_FOCUS_STEPS) {
        BrowseReturnDecision.TRY_LANDING
    } else {
        BrowseReturnDecision.FIRST_CARD
    }
}
internal const val BROWSE_RETURN_FOCUS_DELAY_MS = 40L

/**
 * Puts the focus on [requester] once the node it names is attached.
 *
 * The app's one rule for a hand-back, and it reads the request's own answer for
 * the reason [KBStatusMessage] documents: `requestFocus()` returns FALSE for a
 * node that is not in the tree yet rather than throwing, so `runCatching`
 * reports success for every call ever made and a missed landing leaves no trace.
 * The node a hand-back names is usually one that is being (re)composed in the
 * same frame - a menu's scrim is leaving, a lazy row is putting a card back, a
 * row is un-dimming - so the ask is retried across frames like the browse
 * return record's own landing.
 *
 * Answers whether it landed. A caller that only wants the D-pad somewhere (the
 * menu dismiss paths) can ignore the answer; one that is choosing between two
 * targets can read it and fall back.
 */
internal suspend fun landFocusOn(requester: FocusRequester?): Boolean {
    if (requester == null) return false
    var landed = false
    var attempts = 0
    while (!landed && attempts < BROWSE_RETURN_FOCUS_STEPS) {
        delay(BROWSE_RETURN_FOCUS_DELAY_MS)
        landed = runCatching { requester.requestFocus() }.getOrDefault(false)
        attempts++
    }
    return landed
}

/**
 * The unique key of each browse page. Every route the page is opened with that
 * leads to a DIFFERENT list is part of the key.
 *
 * Ids alone are not enough for the genre page: a genre and a keyword are two
 * different TMDB namespaces that share one numbering, so genre 18 and keyword 18
 * would otherwise be the same key, and the same genre opened as a movie list
 * and as a series list is two different lists. Same argument for the studio
 * page, where a network id and a company id are different namespaces.
 */
internal fun tagBrowseScreenKey(id: Int, isKeyword: Boolean, type: String): String =
    "tag:${if (isKeyword) "keyword" else "genre"}:$id:${type.lowercase()}"

internal fun studioBrowseScreenKey(id: Int, isNetwork: Boolean, providerId: Int?): String =
    "studio:${if (isNetwork) "network" else "company"}:$id:service:${providerId ?: -1}"

internal fun collectionBrowseScreenKey(collectionId: Int): String =
    "collection:$collectionId"

internal fun decadeBrowseScreenKey(decadeStart: Int): String =
    "decade:$decadeStart"

/**
 * The imported KB folder page: one key per folder, so a title opened from one
 * folder cannot answer for a return into another.
 *
 * Keyed on the folder id rather than its route alone: two profiles can import
 * the same manifest, and the folders they resolve to are different lists.
 */
internal fun kbFolderBrowseScreenKey(folderId: String): String =
    "kbfolder:$folderId"

/**
 * Scrolls the rail keyed [railKey] back into view, or answers false if it cannot
 * be found.
 *
 * A list composes the rails near the viewport and nothing else, so a rail the
 * scroll left behind has no card to focus at all - which reads exactly like a
 * rail that is not on the page, and is what left the page reopening at the top.
 * [railIndex] is where the rail stood when the card was pressed, and the walk
 * around it covers the rails that moved while the viewer was away (a hidden
 * title, a new release, a paging load).
 *
 * The same reveal the detail page uses for its own rails, at the browse pages'
 * scale: the hint first, then a step each way until the rail's own key is among
 * the composed items.
 */
internal suspend fun LazyListState.revealBrowseReturnRail(
    railKey: String,
    railIndex: Int
): Boolean {
    var waited = 0
    while (layoutInfo.totalItemsCount == 0 && waited < BROWSE_RETURN_FOCUS_STEPS) {
        delay(BROWSE_RETURN_FOCUS_DELAY_MS)
        waited++
    }

    val hint = railIndex.coerceAtLeast(0)
    val candidates =
        buildList {
            add(hint)
            for (delta in 1..8) {
                if (hint - delta >= 0) add(hint - delta)
                add(hint + delta)
            }
        }

    for (index in candidates) {
        runCatching { scrollToItem(index) }
        delay(BROWSE_RETURN_FOCUS_DELAY_MS)
        if (layoutInfo.visibleItemsInfo.any { it.key == railKey }) return true
    }

    return false
}
