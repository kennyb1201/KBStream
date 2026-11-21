package com.kennyb1201.kbstream.ui.detail

/**
 * The detail page's tabs, in the order the strip paints them.
 *
 * The page paints exactly the same items it always did; a tab is only the
 * NAME of the group they live in. That is what makes the page navigable: the
 * vertical chain used to be eleven rows long (see [DetailRow]), so DOWN from
 * the genre chips had to walk past rows that were still loading to reach the
 * episodes, and a press that could not land spent its time in geometric search.
 * With the sections behind tabs the chain is HEADER -> GENRE -> TABS -> the
 * selected tab's own rows, which is at most three presses to any of them.
 *
 * [EPISODES] is first because it is the default tab on a series and the strip
 * reads left to right from the thing most viewers open the page for.
 */
internal enum class DetailTab(val label: String) {
    EPISODES("Episodes"),
    DETAILS("Details"),
    CAST("Cast"),
    MORE_LIKE_THIS("More Like This"),
    REVIEWS("Reviews"),
    MEDIA("Media")
}

/**
 * Which tabs this page shows.
 *
 * Every field is the presence of the ROWS inside that tab (see
 * [DetailRowPresence]), so \"the tab exists\" and \"the tab has content\" are the
 * same answer: a series with no seasons has no Episodes tab, a title with no
 * reviews has no Reviews tab, and there is no empty tab to press into.
 *
 * [DetailTab.DETAILS] has no field because it cannot be absent: the overview,
 * the facts and the ratings are the page's own description of the title, and a
 * title with no keywords and no networks still has those.
 */
internal data class DetailTabPresence(
    val hasEpisodesTab: Boolean,
    val hasCastTab: Boolean,
    val hasMoreLikeThisTab: Boolean,
    val hasReviewsTab: Boolean,
    val hasMediaTab: Boolean
) {
    companion object {
        fun of(rows: DetailRowPresence): DetailTabPresence = DetailTabPresence(
            // The same expression the episode section renders behind
            // (`normalizedType == "series" && seasons.isNotEmpty()` is folded
            // into hasEpisodesRow where the presence is built).
            hasEpisodesTab = rows.hasEpisodesRow,
            hasCastTab = rows.hasPeopleRow,
            // Collection and recommendations are one tab: a collection WITHOUT
            // recommendations still has a rail there, and vice versa.
            hasMoreLikeThisTab = rows.hasCollectionRow || rows.hasMoreLikeThisRow,
            hasReviewsTab = rows.hasReviewsRow,
            hasMediaTab = rows.hasMediaRow
        )
    }
}

/**
 * The tab-level rules: which tabs are on the strip, which one a page opens on,
 * what LEFT/RIGHT does, and which [DetailRow]s belong to the selected tab.
 *
 * Pure and separate from the screen, in the same style as [DetailFocusChain],
 * because what uses it is a key handler and a composable, and no unit test can
 * press a key. The screen reads it; this file decides.
 */
internal object DetailTabChain {

    /** Whether [tab] is on this page at all. */
    fun exists(tab: DetailTab, presence: DetailTabPresence): Boolean = when (tab) {
        // The page's own description of the title: always there.
        DetailTab.DETAILS -> true
        DetailTab.EPISODES -> presence.hasEpisodesTab
        DetailTab.CAST -> presence.hasCastTab
        DetailTab.MORE_LIKE_THIS -> presence.hasMoreLikeThisTab
        DetailTab.REVIEWS -> presence.hasReviewsTab
        DetailTab.MEDIA -> presence.hasMediaTab
    }

    /** The tabs on this page, in strip order. */
    fun tabs(presence: DetailTabPresence): List<DetailTab> =
        DetailTab.entries.filter { exists(it, presence) }

    /**
     * The tab the page opens on: Episodes for a series that has them, Details
     * for everything else.
     *
     * A movie has no Episodes tab, and a series whose seasons have not resolved
     * yet answers Details - which is the same rule as \"the tab does not exist\",
     * so the default can never name a tab the strip does not paint.
     */
    fun defaultTab(isSeries: Boolean, presence: DetailTabPresence): DetailTab =
        if (isSeries && exists(DetailTab.EPISODES, presence)) DetailTab.EPISODES
        else DetailTab.DETAILS

    /**
     * The tab [delta] steps from [from] along the strip, clamped at both ends.
     *
     * Clamped rather than wrapped: LEFT on the first tab and RIGHT on the last
     * are the ends of a list the viewer can see, and jumping from Media back to
     * Episodes would be a move the strip's own geometry contradicts.
     */
    fun step(from: DetailTab, delta: Int, presence: DetailTabPresence): DetailTab {
        val visible = tabs(presence)
        if (visible.isEmpty()) return DetailTab.DETAILS
        val index = visible.indexOf(from).let { if (it < 0) 0 else it }
        return visible[(index + delta).coerceIn(0, visible.size - 1)]
    }

    /**
     * The rows of [tab], in the shape the existing vertical chain reads.
     *
     * Everything outside the tab is answered `false`, which is what keeps a
     * press inside a tab from walking into a row that is painted in another
     * one: the chain steps over absent rows, so the first row of a tab is its
     * own top and its last row its own bottom. The genre chips stay present
     * because they are painted in the fixed header above the strip, not inside
     * any tab.
     */
    fun rowPresence(tab: DetailTab, rows: DetailRowPresence): DetailRowPresence = when (tab) {
        DetailTab.EPISODES -> rows.copy(
            hasKeywordsRow = false,
            hasPeopleRow = false,
            hasNetworkRow = false,
            hasProductionRow = false,
            hasReviewsRow = false,
            hasCollectionRow = false,
            hasMoreLikeThisRow = false,
            hasMediaRow = false
        )
        DetailTab.DETAILS -> DetailTabPresenceScope.copyWithoutListSections(rows)
        DetailTab.CAST -> DetailTabPresenceScope.only(rows, DetailRow.PEOPLE)
        DetailTab.MORE_LIKE_THIS -> rows.copy(
            hasKeywordsRow = false,
            hasEpisodesRow = false,
            hasPeopleRow = false,
            hasNetworkRow = false,
            hasProductionRow = false,
            hasReviewsRow = false,
            hasMediaRow = false
        )
        DetailTab.REVIEWS -> DetailTabPresenceScope.only(rows, DetailRow.REVIEWS)
        DetailTab.MEDIA -> DetailTabPresenceScope.only(rows, DetailRow.MEDIA)
    }

    /**
     * The first row the chain should land on when the viewer presses DOWN out
     * of the strip into [tab]: the row directly under the strip in that tab.
     *
     * Read from the chain, not written down here, so the answer cannot drift
     * from the order the rows are painted in. Null only for a tab whose content
     * is entirely leaf surfaces (the Details tab of a title with no keywords,
     * networks or production companies); the screen then falls back to the
     * geometric search, which is what reaches the overview and the fact cards.
     */
    fun firstRow(tab: DetailTab, rows: DetailRowPresence): DetailRow? =
        DetailFocusChain.nextRow(
            DetailRow.TABS,
            DetailFocusDirection.DOWN,
            rowPresence(tab, rows)
        )
}

/**
 * The small arithmetic [DetailTabChain.rowPresence] needs, kept together so
 * the table above reads as a table.
 */
private object DetailTabPresenceScope {

    /** Every row absent except the genre chips in the fixed header. */
    private fun bare(rows: DetailRowPresence): DetailRowPresence = rows.copy(
        hasKeywordsRow = false,
        hasEpisodesRow = false,
        hasPeopleRow = false,
        hasNetworkRow = false,
        hasProductionRow = false,
        hasReviewsRow = false,
        hasCollectionRow = false,
        hasMoreLikeThisRow = false,
        hasMediaRow = false
    )

    /** Only [row] survives, alongside the header chips. */
    fun only(rows: DetailRowPresence, row: DetailRow): DetailRowPresence = when (row) {
        DetailRow.HEADER, DetailRow.GENRE, DetailRow.TABS -> bare(rows)
        DetailRow.KEYWORDS -> bare(rows).copy(hasKeywordsRow = rows.hasKeywordsRow)
        DetailRow.EPISODES -> bare(rows).copy(hasEpisodesRow = rows.hasEpisodesRow)
        DetailRow.PEOPLE -> bare(rows).copy(hasPeopleRow = rows.hasPeopleRow)
        DetailRow.NETWORK -> bare(rows).copy(hasNetworkRow = rows.hasNetworkRow)
        DetailRow.PRODUCTION -> bare(rows).copy(hasProductionRow = rows.hasProductionRow)
        DetailRow.COLLECTION -> bare(rows).copy(hasCollectionRow = rows.hasCollectionRow)
        DetailRow.MORE_LIKE_THIS ->
            bare(rows).copy(hasMoreLikeThisRow = rows.hasMoreLikeThisRow)
        DetailRow.REVIEWS -> bare(rows).copy(hasReviewsRow = rows.hasReviewsRow)
        DetailRow.MEDIA -> bare(rows).copy(hasMediaRow = rows.hasMediaRow)
    }

    /** The Details tab: its chips and its header rows, nothing from rails. */
    fun copyWithoutListSections(rows: DetailRowPresence): DetailRowPresence = bare(rows).copy(
        hasKeywordsRow = rows.hasKeywordsRow,
        hasNetworkRow = rows.hasNetworkRow,
        hasProductionRow = rows.hasProductionRow
    )
}

/**
 * The tab a viewer last had open on a title, held outside the composition.
 *
 * The page composes between the player and the drill-downs it opens, so a tab
 * held in `remember` would read as Episodes/Details again on the way back - the
 * spec asks for the tab AND the position inside it to survive the trip. Same
 * shape and lifetime rules as [DetailReturnFocus], for the same reason: one
 * record per title, aged out so a session-old record cannot answer for a fresh
 * open, capped so a pathological session cannot grow without bound.
 */
internal object DetailTabMemory {

    private class Recorded(val tab: DetailTab, val recordedAtMs: Long)

    private val pending = LinkedHashMap<String, Recorded>()

    private const val VALID_FOR_MS = 30 * 60 * 1000L

    private const val MAX_PENDING = 16

    /** The tab last shown for [detailKey], if the record is still fresh. */
    @Synchronized
    fun read(detailKey: String): DetailTab? {
        prune(System.currentTimeMillis())
        return pending[detailKey]?.tab
    }

    /** Records [tab] as the tab [detailKey] should reopen on. */
    @Synchronized
    fun record(detailKey: String, tab: DetailTab) {
        val now = System.currentTimeMillis()
        prune(now)
        pending.remove(detailKey)
        pending[detailKey] = Recorded(tab, now)
        while (pending.size > MAX_PENDING) {
            pending.remove(pending.keys.first())
        }
    }

    private fun prune(now: Long) {
        pending.entries.removeAll { now - it.value.recordedAtMs > VALID_FOR_MS }
    }
}
