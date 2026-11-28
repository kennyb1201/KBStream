package com.kennyb1201.kbstream.ui.detail

/**
 * The direction of a vertical D-pad press on the detail page.
 *
 * Named apart from Compose's own `FocusDirection` on purpose: that one is the
 * platform's, with four directions and movement as the answer, and this is the
 * page's own spelling of the two presses its rails answer.
 */
internal enum class DetailFocusDirection { UP, DOWN }

/**
 * Every row of the detail page that takes part in its vertical focus chain, in
 * the order they are painted top to bottom.
 *
 * This list IS the page's row order, and it is the only place that order is
 * written down: [DetailFocusChain.nextRow] walks it, so UP and DOWN from every
 * rail agree with each other by construction instead of each rail carrying its
 * own idea of what is above and below it. That is what the reported bug was -
 * a fix applied to one transition (network UP, then production UP) moved the
 * stranding to the next one, because there was no single answer to "what is
 * below this row".
 *
 * The order is the PAINTED order, which is not the order the rails are listed
 * in the source: under the fixed header's title/PLAY row the genre chips, then
 * the tab strip, then the selected tab's first row (the overview toggle in
 * Details), then the tag rail, the episode section, the people rail, the
 * network rail, the production companies, the collection rail, More Like This,
 * the review rail and finally the Media row (see the `genrerow`, `keywordsrow`,
 * `peoplerow`, `networkrow`, `productionrow`, `collectionrow`, `recsrow`,
 * `reviewsrow` and `mediarow` items). A chain that did not mirror the screen
 * would send UP into a row the viewer can see is below them.
 *
 * Rows that carry no vertical handler of their own are deliberately absent:
 * the ratings strip and the fact cards are leaf surfaces reached by the default
 * search, and naming them here would change behaviour the chain is not about.
 * The overview belongs here because its toggle is focusable; its prose does not
 * take a press. The genre rail, the review rail, the collection rail, More Like
 * This and the Media row ARE named - the genre chips because they live in the
 * fixed header and a press out of the tag rail used to skip them, the review
 * rail because a card reached by touch had no chain entry for where it was, and
 * the last three because a viewer who scrolled into them had no chain entry
 * either - all in the order they are painted.
 */
internal enum class DetailRow {
    /** The fixed title/logo and PLAY row above the list - always composed. */
    HEADER,

    /**
     * The genre chips, painted in the fixed header just under the PLAY row and
     * above the list.
     *
     * A named row rather than a header detail because a press out of the tag
     * rail below it must land on the genre chip in the same column, not jump
     * straight to the PLAY button over a rail the viewer can see in between.
     */
    GENRE,

    /**
     * The tab strip, pinned under the genre chips and above the list.
     *
     * It is the page's only way into the content: LEFT/RIGHT switch tabs, DOWN
     * enters the selected tab's first row, and UP is the genre chips above it.
     * Naming it in the chain is what replaced the eleven-row walk whose misses
     * were the reported "trapped on details pages": a press out of the strip
     * has one row to reach instead of a page's worth.
     *
     * It always exists, like [HEADER]: the strip is painted whether or not a
     * given section is loaded, which is the other half of the fix - a press can
     * always land somewhere real while the rows below are still arriving.
     */
    TABS,

    /**
     * The overview block's "View more" toggle: the first focusable row INSIDE
     * the Details tab, directly under the strip (2026-11-22).
     *
     * The overview itself is not focusable - it is prose - but its toggle is,
     * so the block is a real chain stop whenever the text overflows its four
     * collapsed lines. Presence, not a constant: an overview that fits has no
     * toggle, and a row that is not on screen must never be landed on (the
     * press would be consumed and the focus would not move, which is the trap
     * this page keeps re-learning).
     *
     * UP is the strip above it. DOWN is the next row in the Details tab, or
     * the platform's geometric search when the tab has only the leaf fact and
     * rating rows below.
     */
    OVERVIEW,

    /** The tag rail, the first focusable row inside the list. */
    KEYWORDS,

    /** The season chips and episode cards, which share one section. */
    EPISODES,

    /** The writer/director/cast rail. */
    PEOPLE,

    /** The network chips (series with networks only). */
    NETWORK,

    /** The production-company chips. */
    PRODUCTION,

    /** The collection rail: this title's other parts. */
    COLLECTION,

    /** The More Like This recommendations rail. */
    MORE_LIKE_THIS,

    /**
     * The review rail, painted after More Like This and above the Media row.
     *
     * It is a focusable rail of cards like any other, so the chain has to name
     * it: left out, a UP or DOWN press on a review card fell to the default
     * search and landed anywhere the geometry suggested, and the rail could not
     * be reached by D-pad at all.
     */
    REVIEWS,

    /**
     * The Media row - the title's videos, its other backdrops and its posters,
     * as one row with a tab strip on top of it - painted LAST, under More Like
     * This.
     *
     * The chain names it ONCE, because what it is for is the handoff between
     * rows: UP is the review rail above it (More Like This when the title has no
     * reviews) and DOWN is the bottom of the page, exactly as if the row held a
     * single rail, which is what makes a DOWN off it the END of the page - consumed, with the focus staying on the card
     * or the strip the viewer is holding, never thrown back to the top (see the
     * screen's handler). Which of its pages the viewer is looking at, and the
     * LEFT/RIGHT presses that swap them, never leave the row and are the row's
     * own business (see [DetailMediaRow]).
     */
    MEDIA
}

/**
 * Which rows this page actually has.
 *
 * Every field is the SAME expression that renders its row, so "the row exists"
 * and "the row is on screen" cannot drift apart - which is the second half of
 * the reported bug: a rail the scroll had disposed used to read as a rail that
 * was not there, and the press then skipped it.
 */
internal data class DetailRowPresence(
    val hasGenreRow: Boolean,
    /**
     * Whether the overview's "View more" toggle is on the page: true only when
     * the description actually overflows its collapsed lines.
     *
     * Defaulted so the presence literals written before the overview moved
     * keep compiling unchanged; the screen sets it explicitly from the block's
     * own overflow measurement, and [DetailTabChain.rowPresence] exposes it
     * only in the Details tab.
     */
    val hasOverviewRow: Boolean = false,
    val hasKeywordsRow: Boolean,
    val hasEpisodesRow: Boolean,
    val hasPeopleRow: Boolean,
    val hasNetworkRow: Boolean,
    val hasProductionRow: Boolean,
    val hasReviewsRow: Boolean,
    val hasCollectionRow: Boolean,
    val hasMoreLikeThisRow: Boolean,
    val hasMediaRow: Boolean
)

/**
 * The detail page's vertical focus chain: which row a press leaves for, decided
 * once, from one list.
 *
 * Kept pure and separate from the screen so the rule can be pinned without a
 * device: as with [DetailChipColumns], what uses it is a key handler, which no
 * unit test can press.
 */
internal object DetailFocusChain {

    /**
     * Whether [row] is on this page at all.
     *
     * [DetailRow.HEADER] is the one row that always exists: it lives in the
     * fixed header above the list, so it is composed whatever the payload, and
     * it is what makes the chain incapable of stranding a press - UP always has
     * somewhere to end.
     */
    fun exists(row: DetailRow, presence: DetailRowPresence): Boolean = when (row) {
        DetailRow.HEADER -> true
        // Painted whether or not any tab has loaded: this is the stop that makes
        // a press land while the page is still filling in.
        DetailRow.TABS -> true
        DetailRow.GENRE -> presence.hasGenreRow
        DetailRow.OVERVIEW -> presence.hasOverviewRow
        DetailRow.KEYWORDS -> presence.hasKeywordsRow
        DetailRow.EPISODES -> presence.hasEpisodesRow
        DetailRow.PEOPLE -> presence.hasPeopleRow
        DetailRow.NETWORK -> presence.hasNetworkRow
        DetailRow.PRODUCTION -> presence.hasProductionRow
        DetailRow.REVIEWS -> presence.hasReviewsRow
        DetailRow.COLLECTION -> presence.hasCollectionRow
        DetailRow.MEDIA -> presence.hasMediaRow
        DetailRow.MORE_LIKE_THIS -> presence.hasMoreLikeThisRow
    }

    /**
     * The nearest row to [from] in [direction] that this page actually has, or
     * null when there is none that way.
     *
     * Rows that do not exist are stepped over rather than landed on, which is
     * what makes the move honest about a page that is missing a rail: UP out of
     * a series' network row with no cast reaches the episode rail, not a row
     * that is not there.
     *
     * Null is only ever reached going DOWN out of the bottom row of the page -
     * [DetailRow.HEADER] always exists and sits at the top, so an upward walk
     * always ends on it. Null is the END OF THE PAGE and nothing else: whether
     * a press that reaches it is consumed is [isPageEdge]'s question, and the
     * screen decides it there (see the screen's handler).
     */
    fun nextRow(
        from: DetailRow,
        direction: DetailFocusDirection,
        presence: DetailRowPresence
    ): DetailRow? {
        val rows = DetailRow.entries
        val fromIndex = rows.indexOf(from)
        val step = if (direction == DetailFocusDirection.UP) -1 else 1

        var index = fromIndex + step
        while (index in rows.indices) {
            val candidate = rows[index]
            if (exists(candidate, presence)) {
                return candidate
            }
            index += step
        }

        return null
    }

    /**
     * Whether a press that could not move the focus is at a REAL EDGE of the
     * page, where staying put is the honest answer.
     *
     * The page's vertical handlers consume the key they own up front - the move
     * itself is asynchronous, because a rail the scroll disposed has to be put
     * back and then asked again a frame at a time - so a press that cannot land
     * anywhere used to be consumed with the focus unmoved: the reported dead
     * remote on a title whose rows in that direction were still loading (or
     * were thinner than the page the chain assumed). Mid-page there is ALWAYS
     * something the press can do - the default geometric search, which is what
     * any surface without a chain entry would have got - so the only presses
     * that may be swallowed are these, at the page's edges:
     *
     *  - UP out of [DetailRow.HEADER], the fixed action row above the list:
     *    there is nothing above the page to move to, and it has been consumed
     *    that way since the header existed.
     *  - UP out of [DetailRow.GENRE], the chips painted in that same fixed
     *    header just under the action row: the only thing above them is the row
     *    that just failed to take the focus, so a search from there can only
     *    leave the page - which reads as the focus jumping off the screen, the
     *    one thing an unlanded press must never do.
     *  - DOWN out of the last row this page actually has, [presence] being the
     *    loaded data's own answer: below that row is the end of the page.
     *
     * Verified against the loaded rows rather than against where the walk
     * happened to stop, because a walk can stop at the bottom row of the page
     * having started mid-page: the press came from a row with rows below it,
     * and the rows below just would not take the focus. That press is mid-page
     * and must not be swallowed.
     */
    fun isPageEdge(
        row: DetailRow,
        direction: DetailFocusDirection,
        presence: DetailRowPresence
    ): Boolean = when (direction) {
        DetailFocusDirection.UP -> row == DetailRow.HEADER || row == DetailRow.GENRE
        DetailFocusDirection.DOWN -> nextRow(row, DetailFocusDirection.DOWN, presence) == null
    }

    /**
     * Whether the overview's "View more" toggle may KEEP a DOWN press.
     *
     * The toggle now sits at the top of the Details tab, so its DOWN asks the
     * chain for the first row below it ([toggleDownRow]) and its UP is the
     * strip above the list. The reported trap - "DOWN does nothing after going
     * up", Dark Marvels - was that press
     * being kept unconditionally: the
     * toggle's handler was composed before the seasons landed, its captured
     * [presence] said the page had no rows below, the chain answered null, the
     * fall-through read HEADER+DOWN as a page edge, and the key had already been
     * consumed. Focus stayed on the toggle with the episodes one press away.
     *
     * So the press is kept only when the page has somewhere to put it: a row
     * below ([nextRow]), or rows still arriving - absence while loading is "not
     * yet", never an edge, and the move waits for them (see the screen's
     * `awaitRowsThenMove`). Otherwise the press is handed BACK to the platform's
     * default search, which is what [focusByGeometry] would have done with it and
     * what [isPageEdge] says a real edge may refuse. A press that moves nothing
     * must not be a press the page took.
     *
     * Note what this is not: it is not the page edge rule. A page whose only rows
     * are above the toggle keeps nothing either way; the difference is that the
     * edge rule swallows the press, and this hands it over.
     */
    fun toggleDownIsConsumed(
        presence: DetailRowPresence,
        rowsStillArriving: Boolean
    ): Boolean =
        rowsStillArriving ||
            nextRow(DetailRow.OVERVIEW, DetailFocusDirection.DOWN, presence) != null

    /**
     * Where a DOWN out of the overview's "View more" toggle goes: the first row
     * BELOW the toggle inside the Details tab - the tag rail on a title with
     * tags, the network or production rail on one without, and so on.
     *
     * Not the strip, and that is the point of moving the overview into the tab
     * (2026-11-22): the strip is painted ABOVE the toggle now, so sending DOWN
     * there would move the focus up the screen while asking to go down.
     *
     * Falls back to [DetailRow.TABS] only for the presence values that have no
     * row below at all; the screen then hands the press to the geometric search
     * for the Details tab's leaf fact and rating rows rather than swallowing it.
     */
    fun toggleDownRow(presence: DetailRowPresence): DetailRow =
        nextRow(DetailRow.OVERVIEW, DetailFocusDirection.DOWN, presence)
            ?: nextRow(DetailRow.TABS, DetailFocusDirection.DOWN, presence)
            ?: DetailRow.TABS
}

/**
 * What a new vertical press does to the move already in flight (spec 2026-11-15,
 * third round at the KEYWORDS -> EPISODES trap).
 *
 * The page keeps ONE move at a time, and it cancels the old one on every press -
 * which is right for a reversal and wrong for a repeat. The move into the episode
 * section takes frames: it has to ask the section's season chip while the lazy
 * row composes it. On build 329's paste that move was cancelled five times in a
 * row, each cancel a KEYWORDS DOWN from the viewer, each one starting the same
 * move again: `landed=false via=cancelled`, five times, focus never leaving the
 * keywords. The focus was not refusing to go anywhere - the press was killing its
 * own instruction every time it restated it.
 *
 * So the press's DIRECTION, not its target, decides:
 *
 *  - the same direction as the move in flight is the same intent restated. The
 *    move is already heading where the press wants to go, so it is left alone -
 *    no cancel, no second move, no possible snap-back (both presses named the
 *    same direction).
 *  - the opposite direction is a new instruction, and the cancel stays exactly as
 *    it was: a DOWN still retrying must never land after the viewer pressed UP.
 *  - no move at all is a new move, whether the last one landed a moment ago or
 *    this is the first press of the walk.
 *
 * Pure on purpose: the rule is the whole bug, and this way a JVM test can pin it
 * without a page, a key event or a device.
 */
internal object DetailFocusMoveRules {

    /** What [decide] says the press should do. */
    enum class Decision {
        /** Nothing in flight: start the move. */
        LAUNCH,

        /** A move the other way is in flight: cancel it, then start this one. */
        CANCEL_AND_LAUNCH,

        /** The same press again: keep the move that is already heading there. */
        COALESCE
    }

    /**
     * The decision for a [press] arriving while [inFlight] does (or does not)
     * hold a live move in [inFlightDirection].
     *
     * [inFlight] is the job's own liveness, not merely "a move was started": a
     * move that has landed leaves nothing to coalesce into, and the next press in
     * the same direction is the next row the viewer wants.
     */
    fun decide(
        inFlight: Boolean,
        inFlightDirection: DetailFocusDirection?,
        press: DetailFocusDirection
    ): Decision = when {
        !inFlight -> Decision.LAUNCH
        inFlightDirection == press -> Decision.COALESCE
        else -> Decision.CANCEL_AND_LAUNCH
    }

    /**
     * The row a gain of [gain] just REVOKED, or null when the gain is a landing
     * the page should accept.
     *
     * The page's one reclaim exists for the framework's restart-to-Play: a
     * HEADER gain nobody asked for, taking a row the viewer had chosen. Every
     * other HEADER gain is legitimate, and the distinction is whether a move is
     * in flight - a press of the viewer's, so the landing belongs to it.
     *
     * A rule rather than an inline condition because the strip's UP press used
     * to bypass it (spec 2026-11-27): that handler placed Play with its own raw
     * launch, no move was in flight, and so the page's own deliberate landing was
     * read as a theft and reclaimed - press UP, land on Play, be pulled back to
     * the pill, press UP again. The strip's UP/DOWN are chain moves now (see
     * [com.kennyb1201.kbstream.ui.detail.DetailScreen]'s `DetailTabStrip` call),
     * which is the same treatment every other surface already had; this is what
     * makes that treatment the reason the landing survives.
     *
     * @param gain the row that just gained the focus
     * @param owned the row the chain last placed (`lastOwnedRow` on the page)
     * @param chainMoveActive whether a D-pad move of the page's own is in flight
     */
    fun revokedRow(
        gain: DetailRow,
        owned: DetailRow?,
        chainMoveActive: Boolean
    ): DetailRow? = when {
        gain != DetailRow.HEADER -> null
        owned == null || owned == gain -> null
        chainMoveActive -> null
        else -> owned
    }
}
