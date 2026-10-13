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
 * One-slot handoff from a browse page to itself, across the Detail page it
 * opens. The page records the card on the way out and reads the record back the
 * next time that same page composes.
 *
 * Held outside the composition because that is exactly what does not survive
 * the trip: leaving the browse screen disposes it. Read once, by the page it was
 * raised for and no other, and aged out on top of that in case the Back it was
 * waiting for never comes.
 *
 * The same shape as the detail page's own DetailReturnFocus, which does this for
 * its drill-downs; the two are deliberately separate records, because a browse
 * page and a detail page can both be waiting for a Back at the same time.
 */
object BrowseReturnFocus {

    private var pending: BrowseReturnTarget? = null
    private var recordedAtMs: Long = 0L

    /**
     * Long enough for a Detail page to be browsed before coming back, short
     * enough that a session-old record cannot answer for a fresh open of the
     * same genre.
     */
    private const val VALID_FOR_MS = 30 * 60 * 1000L

    /** Records the card a Detail page is being opened from. */
    fun record(target: BrowseReturnTarget) {
        pending = target
        recordedAtMs = System.currentTimeMillis()
    }

    /**
     * Reads the record, if it is for [screenKey], and clears it once.
     *
     * A record for another page is left exactly where it is: a title opened
     * from one browse page can open another (a "More Like This" poster, a
     * studio link), and that page must not swallow the record this one is still
     * waiting for. [BrowseReturnTarget.screenKey] is what keeps the two apart -
     * which is also what makes opening a DIFFERENT genre start at the top.
     */
    fun consume(screenKey: String): BrowseReturnTarget? {
        val recorded = pending ?: return null
        // An aged-out record is dropped whichever page asks next - it is stale
        // for the page that raised it too.
        if (System.currentTimeMillis() - recordedAtMs > VALID_FOR_MS) {
            pending = null
            recordedAtMs = 0L
            return null
        }
        if (recorded.screenKey != screenKey) return null
        pending = null
        recordedAtMs = 0L
        return recorded
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
 */
internal const val BROWSE_RETURN_FOCUS_STEPS = 12
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
