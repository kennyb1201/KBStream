package com.kennyb1201.kbstream.ui.detail

/**
 * The detail page's tabs, in the order the strip paints them.
 *
 * The page paints exactly the same items it always did; a tab is only the
 * NAME of the group they live in. That is what makes the page navigable: the
 * vertical chain used to be eleven rows long (see [DetailRow]), so DOWN from
 * the genre chips had to walk past rows that were still loading to reach the
 * episodes, and a press that could not land spent its time in geometric search.
 * With the sections behind tabs the chain is HEADER -> GENRE -> (OVERVIEW) ->
 * TABS -> the selected tab's own rows, which is at most four presses to any of
 * them. The overview is bracketed because it is only on the chain when its text
 * overflows its four collapsed lines.
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
 * [DetailTab.DETAILS] has no field because it cannot be absent: the facts, the
 * ratings and the title's own rows are its content, and a title with no
 * keywords and no networks still has those. The OVERVIEW is not here at all -
 * it lives in the fixed header, above the strip, on every tab (2026-11-21).
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

    /** The tabs on this page, in strip order (the content-presence rule). */
    fun tabs(presence: DetailTabPresence): List<DetailTab> =
        DetailTab.entries.filter { exists(it, presence) }

    /**
     * The tabs the strip PAINTS, decided from the title alone (spec
     * 2026-11-21, rule 3: the strip is stable from first frame).
     *
     * [tabs] answers what the loaded payload happens to contain, and that was
     * the trap: every section lands at a different moment, so the strip grew a
     * pill per arrival, recomposed, reset the focused pill's node and parked
     * focus at the top of the page. Eight identical `HEADER -> GENRE -> TABS`
     * cycles in one paste were eight Down presses restarting on a strip that had
     * been rebuilt under them.
     *
     * So the strip no longer asks the payload. A series is the one shape whose
     * tab set differs from every other title - it has Episodes - and the route
     * already says whether this is a series (`type` is what chose the screen),
     * so the answer is known before the first frame. Everything else can have
     * cast, recommendations, reviews and media, so those pills are painted from
     * the start and each shows its own loading or empty state until (or unless)
     * its content lands. A pill that never disappears cannot lose the focus.
     *
     * This is deliberately NOT [tabs]: presence is still the right question for
     * which ROW a press may land on ([firstRow]), because landing on a rail that
     * has not composed is still a miss. It is the wrong question for which pills
     * exist, because the pill is the stable surface the viewer holds while the
     * rows below it arrive.
     */
    fun stableTabs(isSeries: Boolean): List<DetailTab> =
        DetailTab.entries.filter { tab -> tab != DetailTab.EPISODES || isSeries }

    /**
     * The tab a page opens on, before any payload has arrived.
     *
     * The same rule as [defaultTab], but asked only of the title: a series
     * opens on Episodes and everything else on Details. Known on the first
     * frame, so the page never has to move the viewer's tab (and with it their
     * focus) when the seasons land.
     */
    fun stableDefaultTab(isSeries: Boolean): DetailTab =
        if (isSeries) DetailTab.EPISODES else DetailTab.DETAILS

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
    fun step(from: DetailTab, delta: Int, presence: DetailTabPresence): DetailTab =
        step(from, delta, tabs(presence))

    /**
     * [step] along the strip the page actually paints ([stableTabs]).
     *
     * The screen reads this overload, so LEFT/RIGHT walk the KEYED pills that
     * are on screen - never a presence list that could name a different set for
     * a frame while a section lands.
     */
    fun step(from: DetailTab, delta: Int, visible: List<DetailTab>): DetailTab {
        if (visible.isEmpty()) return DetailTab.DETAILS
        val index = visible.indexOf(from).let { if (it < 0) 0 else it }
        return visible[(index + delta).coerceIn(0, visible.size - 1)]
    }

    /**
     * What a tab with no content says (spec 2026-11-21, rule 3).
     *
     * A stable strip means a tab can be opened before its section has arrived - 
     * and, for a title that genuinely has none, opened when it never will. The
     * pill itself never changes; the tab says which of the two this is. The
     * wording is deliberately the title's, never an internal id.
     *
     * Details has no message: its facts and ratings are leaf surfaces, and an
     * empty Details tab is the case the content sink exists for (acceptance 6).
     */
    fun placeholder(tab: DetailTab, loading: Boolean): String? = when (tab) {
        DetailTab.DETAILS -> null
        DetailTab.EPISODES -> if (loading) "Loading episodes…" else "No episodes listed for this title."
        DetailTab.CAST -> if (loading) "Loading cast…" else "No cast listed for this title."
        DetailTab.MORE_LIKE_THIS ->
            if (loading) "Loading more titles…" else "No more titles like this one."
        DetailTab.REVIEWS -> if (loading) "Loading reviews…" else "No reviews for this title."
        DetailTab.MEDIA ->
            if (loading) "Loading media…" else "No videos, backdrops or posters for this title."
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

    /**
     * Every list row absent, the two rows painted in the fixed header kept:
     * the genre chips and the overview toggle are above the strip on every tab,
     * so a tab's own presence must never take them away.
     */
    private fun bare(rows: DetailRowPresence): DetailRowPresence = rows.copy(
        hasOverviewRow = rows.hasOverviewRow,
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
        DetailRow.HEADER, DetailRow.GENRE, DetailRow.OVERVIEW, DetailRow.TABS -> bare(rows)
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
