package com.kennyb1201.kbstream.data.reporting

import java.util.Locale

/**
 * What the Home billboard last dealt, and what the Spotlight tracker last held.
 *
 * Two 2026-11-12 TV reports cannot be answered from the paste as it stood, and
 * both answers live in runtime state that the release APK never writes anywhere
 * a viewer can send:
 *
 *  - the billboard shows one item, vanishes on its own and never comes back,
 *    even with trending-day only selected - and the paste carried NO billboard
 *    lines at all, so "the feed was empty" and "the pages never settled" look
 *    identical;
 *  - catalog cards do not expand on a two-second dwell, even though every link
 *    in the chain reports correct at the tip.
 *
 * Same shape as [UpcomingDiagTrace]: a process-wide singleton, no ViewModel
 * reference, read by [Diagnostics] right after the `upcoming:` block. Two
 * stores, for the same reason as there:
 *
 *  - [summaries] hold the LATEST state as one line per kind - the billboard's
 *    feed, its page tally, the last resolve and the last clear; the tracker's
 *    last snapshot, the dwell inputs and the panel. Fixed order, replaced when
 *    the same kind speaks again, never evicted. This is what the viewer's paste
 *    still says after the repro has moved on.
 *  - the ring holds recent TRANSITIONS only (a deal, a settle, a null resolve, a
 *    clear, a focus, a claim, an expand, a collapse), capped at [MAX_LINES].
 *    Never a per-frame or per-bounds write: [SpotlightCardFocus.updateBounds]
 *    copies the snapshot on every scroll and would fill any ring in a second.
 *
 * No token, no account identifier, no watch history - feed keys, page keys, a
 * card's id form and a title, exactly what the panel already draws. Nothing is
 * persisted: like [PlaybackSessionTrace] it is in-memory and a force-stop clears
 * it (F7), which is why the paste must be taken while the app is running.
 *
 * Every line is already prefixed (`billboard: ` / `spotlight: `) so the reader
 * can just append it; a trace that has never been written prints nothing at all,
 * so a phone that never opened Spotlight keeps its paste clean.
 */
internal object SpotlightDiagTrace {

    /**
     * Transition lines kept. Thirty-two is a day's worth of Home visits and no
     * more: a billboard deal is a handful of lines (a settle or a null per page,
     * plus the deal), and the alternative - unbounded - grows with every swipe.
     */
    private const val MAX_LINES = 32

    private const val BILLBOARD = "billboard: "
    private const val SPOTLIGHT = "spotlight: "

    /** Summary slots in print order, so two pastes of one state read alike. */
    private val BILLBOARD_ORDER =
        listOf("gate", "render", "art", "rest", "feed", "walk", "pages", "resolve", "clear")
    private val SPOTLIGHT_ORDER = listOf("tracker", "dwell", "panel")

    private val lock = Any()

    @Volatile
    private var ring: List<String> = emptyList()

    @Volatile
    private var billboard: Map<String, String> = emptyMap()

    @Volatile
    private var spotlight: Map<String, String> = emptyMap()

    // ── The current deal's tally ──────────────────────────────────────────
    // Counters, not derived from the page list: the ViewModel's pages are the
    // truth for what is on screen, but a page that returned null is exactly the
    // one that is NOT in any settled count, and it is the line the report needs.
    private var dealt = 0
    private var settled = 0
    private var resolved = 0
    private var nulls = 0
    private var failure: String? = null

    // ── The pager's rest offset (spec 2026-11-17, F4) ─────────────────────
    //
    // A strip of the next title's art at the right edge of a billboard that was
    // not moving ("its there sitting still"). The strip is the pager resting
    // off-centre, so the paste has to be able to say what the offset was - and
    // whether anything ever had to put it back. A count rather than a boolean:
    // one correction is a stray settle, a growing one is a pager being fought
    // by something, and the number is the difference.
    private var restCorrections = 0

    // ── The hero's art swaps (spec 2026-11-17, F4) ─────────────────────────
    //
    // The first entry to the app flashed: every settle moved the art under the
    // showing page, and each move was a new request over an empty frame. The
    // render line says what the art IS; these say how many times it CHANGED -
    // per page, and for the session - so a paste can prove the flash is gone.
    // A first entry has to read at most one swap per page (borrowed -> own).
    //
    // Keyed by the pager's page index, which is why a fresh deal clears them:
    // page 0 of the next deal is a different title, not the same page. The
    // session total does NOT reset on a deal - it is the whole session's count.
    private val artUrls = mutableMapOf<Int, String?>()
    private val artSources = mutableMapOf<Int, String>()
    private val artSwaps = mutableMapOf<Int, Int>()
    private var artSwapsSession = 0

    // ── The last trending-day walk (spec 2026-11-16, F2) ───────────────────
    //
    // The kids hero went black on the TCL and the paste could not say WHY the
    // default feed was empty: TMDB returning nothing and the strict PG ceiling
    // dropping all forty titles both read as "empty pool". The walk now records,
    // per page, what came back and what survived, and how it ended - so the next
    // paste answers it instead of the next guess.
    private val walkPages = mutableListOf<String>()
    private var walkPool = 0
    private var walkEnd: String? = null

    // ── The last snapshot, kept past its blur (F5) ────────────────────────
    // COPY DIAGNOSTICS is taken from Settings, so by paste time the card has
    // lost focus and the tracker's live snapshot is null. A live-only read
    // would always answer "no snapshot" to the question the report is asking.
    private var focusToken = 0
    private var held = false
    private var lastKey: String? = null
    private var lastTitle: String? = null
    private var lastExpanded = false
    private var lastClaim: Boolean? = null

    // The dwell effect's inputs, kept so a later claim can re-emit the line.
    private var dwellDelayMs = 0L
    private var dwellMenuOpen = false
    private var dwellClaimEntryFocus = false
    private var dwellToken = 0
    private var dwellCard: String? = null
    private var dwellCancel: String? = null

    /** The card the panel last expanded for, as `type:id (title)`. */
    private var expandedCard: String? = null

    /** The expanded card's trailer's state, as its own composable reported it (F4). */
    private var panelTrailer: String? = null

    /**
     * The picture the panel actually DREW - its rung and the host it came from
     * (F3, 2026-11-13).
     *
     * Twice now the field report was "the panel backdrop is blurry" and the
     * paste could not say whether the panel drew a backdrop, a poster, the rail
     * still, or nothing at all. The rung is the whole answer: `background@host`
     * is the title's own backdrop, `none` is the honest text tile while a title
     * without one is up, `loading` is the same tile before the resolve lands.
     */
    private var panelBackdrop: String? = null

    /** When the panel last expanded, so a following blur can be dated against it. */
    private var expandedAtMs = 0L

    /** The clock, in one place: the trace is time-agnostic everywhere else. */
    private fun clock(): Long = System.currentTimeMillis()

    /** True once the dwell effect has spoken, so a claim cannot print delay=0. */
    private var dwellSeen = false

    // ── Billboard ─────────────────────────────────────────────────────────

    /**
     * The RENDER GATE's inputs, ahead of the pool's own lines.
     *
     * This is the line that answers "the pool is settled, where is the hero":
     * the hero is drawn only under the Spotlight layout, inside a section that
     * also requires the billboard to be on. When either is false the pool can
     * still read settled from an earlier visit - it lives in the ViewModel - so
     * every other billboard line reads healthy while the screen has no hero at
     * all. Written from the Home, which is composed even when the hero is not.
     */
    fun billboardGate(enabled: Boolean, layout: String, spotlightActive: Boolean) {
        putBillboard(
            "gate",
            "gate billboardEnabled=${yesNo(enabled)} layout=$layout " +
                "spotlightActive=${yesNo(spotlightActive)}"
        )
    }

    // ── The render line's own fields (spec 2026-11-12, follow-up F1) ─────
    // Written from two places that cannot see each other: the Home's list (is
    // the item there, how tall did it measure, where does the list rest) and the
    // billboard's pager (is the section past its gate, which page is in front,
    // does that page have art). One line, because the paste has to answer "why
    // is there no hero" from ONE reading.
    private var renderHeroVisible: Boolean? = null
    private var renderHeroIndex: Int? = null
    private var renderHeroHeightDp: Int? = null
    private var renderViewportDp: Int? = null
    private var renderFirstIndex = 0
    private var renderFirstOffset = 0
    private var renderItemCount = 0
    private var renderComposed: Boolean? = null
    private var renderGate: String? = null
    private var renderPage: String? = null
    private var renderArt: String? = null
    private var renderSettled = 0
    private var renderPages = 0

    /**
     * The hero item in the rails list, measured.
     *
     * [heroVisible] false with [renderItemCount] above zero is branch B2/B5 -
     * the item is emitted but not in the viewport, which is a scroll, not a
     * list. [heroHeightDp] 0 next to a non-zero viewport is B5's zero height.
     */
    fun billboardRenderList(
        heroVisible: Boolean,
        heroIndex: Int?,
        heroHeightDp: Int?,
        viewportHeightDp: Int,
        firstIndex: Int,
        firstOffset: Int,
        itemCount: Int
    ) {
        synchronized(lock) {
            renderHeroVisible = heroVisible
            renderHeroIndex = heroIndex
            renderHeroHeightDp = heroHeightDp
            renderViewportDp = viewportHeightDp
            renderFirstIndex = firstIndex
            renderFirstOffset = firstOffset
            renderItemCount = itemCount
        }
        putBillboard("render", renderLine())
    }

    /**
     * The section and the page it is showing.
     *
     * [composed] false means the hero's own gate returned and names it; true
     * with [art] null means the section drew a page with no art at all - B4.
     */
    fun billboardRenderHero(
        composed: Boolean,
        gate: String?,
        pageKey: String?,
        art: String?,
        settled: Int,
        total: Int
    ) {
        synchronized(lock) {
            renderComposed = composed
            renderGate = gate
            renderPage = pageKey
            renderArt = art
            renderSettled = settled
            renderPages = total
        }
        putBillboard("render", renderLine())
    }

    /** One line: the list's half, then the hero's half, in a fixed order. */
    private fun renderLine(): String {
        val hero = when {
            renderHeroVisible == true -> {
                val at = renderHeroIndex?.let { "#$it" } ?: "#?"
                val height = renderHeroHeightDp?.let { "${it}dp" } ?: "?dp"
                val viewport = renderViewportDp?.let { "${it}dp" } ?: "?dp"
                "list=hero$at ${height}/$viewport"
            }
            renderHeroVisible == false -> "list=hero-not-visible"
            else -> "list=not-reported"
        }
        val rest = "first=#${renderFirstIndex}+${renderFirstOffset}px items=${renderItemCount}"
        val section = when {
            renderComposed == true ->
                "section=composed page=${renderPage ?: "(none)"} " +
                    "art=${renderArt ?: "none"} settled=${renderSettled}/${renderPages}"
            renderComposed == false ->
                "section=gated (${renderGate ?: "?"}) settled=${renderSettled}/${renderPages}"
            else -> "section=not-reported"
        }
        return "render $hero $rest $section"
    }

    /** The feed the screen asked for, and what it merged from (F2, F4). */
    fun billboardFeed(keys: List<String>, trendingDaySize: Int, merged: Int) {
        val selected = if (keys.isEmpty()) "(none)" else keys.joinToString(",")
        val trending =
            if (trendingDaySize == 0) "trendingDay=empty" else "trendingDay=size=$trendingDaySize"
        putBillboard("feed", "feed selected=$selected $trending merged=$merged")
    }

    /**
     * The billboard pager's rest state (spec 2026-11-17, F4).
     *
     * [offset] is the pager's current-page offset fraction for [page] AT REST - never mid-drag or mid-animation, because those are not
     * the state S1-S3 are about and they would fill the ring with noise.
     *
     * [corrected] says this call is the one that had to snap a non-zero rest
     * offset back to zero, and [offset] is then the offset that was corrected -
     * the field evidence for the bug. The summary slot always carries the LATEST
     * rest state plus the running correction count, so a paste taken long after
     * a correction still shows that one happened, and a transitions ring line
     * records the correction itself with the page and the offset it started at.
     */
    fun billboardRest(page: Int, offset: Float, corrected: Boolean) {
        val count = synchronized(lock) {
            if (corrected) restCorrections++
            restCorrections
        }
        putBillboard(
            "rest",
            "rest page=$page offset=${offsetText(offset)} corrections=$count"
        )
        if (corrected) {
            note(BILLBOARD + "rest corrected page=$page from=${offsetText(offset)}")
        }
    }

    /**
     * The art the hero is drawing (spec 2026-11-17, F4).
     *
     * [source] is the render line's own vocabulary (`backdrop`, `own-art`,
     * `borrowed`, `none`); [url] is the URL actually drawn, and it is only
     * COMPARED - it never travels in the paste (a signed hostname per page is
     * noise). A changed URL for a page is one HERO IMAGE SWAP, the number the
     * flash report is about: the old bug moved the borrowed art several times a
     * second, so a first entry now has to read at most one per page.
     *
     * The summary slot always carries the page in front, that page's own swaps,
     * the session total and an `index:source/swaps` row for every page that has
     * been in front this deal, so the paste answers "did page 0 settle without
     * flickering" directly. A transition line is written for the swap itself,
     * naming both sources - a `borrowed -> borrowed` row there is exactly the
     * old bug's signature.
     */
    fun billboardHeroArt(page: Int, source: String, url: String?) {
        var swapFrom: String? = null
        var swaps = 0
        val line = synchronized(lock) {
            val previous = artUrls[page]
            swaps = artSwaps[page] ?: 0
            if (previous != null && url != null && previous != url) {
                swaps++
                artSwaps[page] = swaps
                artSwapsSession++
                swapFrom = artSources[page]
            }
            artUrls[page] = url
            artSources[page] = source
            artLine(page, source, swaps, artSwapsSession)
        }
        putBillboard("art", line)
        swapFrom?.let { from ->
            note(BILLBOARD + "art swap page=$page from=$from to=$source swaps=$swaps")
        }
    }

    /** One art line: the page in front, its swaps, the session, and every page. */
    private fun artLine(page: Int, source: String, swaps: Int, session: Int): String {
        val perPage = artSources.entries
            .sortedBy { entry -> entry.key }
            .joinToString(" ") { entry ->
                "${entry.key}:${entry.value}/${artSwaps[entry.key] ?: 0}"
            }
        return "art page=$page source=$source swaps=$swaps session=$session " +
            "perPage=[$perPage]"
    }

    /** A fresh deal: the tally restarts, and the deal itself is a transition. */
    fun billboardDealt(pages: Int, pool: Int, epoch: Int) {
        synchronized(lock) {
            dealt = pages
            settled = 0
            resolved = 0
            nulls = 0
            failure = null
            // Page indexes belong to THIS deal (see artUrls).
            artUrls.clear()
            artSources.clear()
            artSwaps.clear()
        }
        putBillboard("pages", pagesLine())
        // F4 (2026-11-16): the RESOLVE slot is refreshed with the counters that
        // were just zeroed. It used to be left as the previous deal wrote it, so
        // a pool of 0 sat beside a `resolve resolved=16` from an earlier deal -
        // almost certainly another profile's - and the paste lied about what the
        // current pool had done.
        putBillboard("resolve", resolveLine())
        note(BILLBOARD + "deal pages=$pages pool=$pool epoch=$epoch")
    }

    /** A new walk of the default feed has started; the last walk's pages are dropped. */
    fun billboardWalkStart() {
        synchronized(lock) {
            walkPages.clear()
            walkPool = 0
            walkEnd = null
        }
        putBillboard("walk", walkLine())
        note(BILLBOARD + "walk start")
    }

    /**
     * One page of the walk: what TMDB returned and what the ceiling kept.
     *
     * [moviesFetched] and [showsFetched] are the raw row counts, [moviesKept] and
     * [showsKept] the survivors - the pair that tells "an empty day" from "the
     * ceiling dropped everything" (F2). A fetched count of -1 means that half of
     * the page did not answer at all, so a failed request never reads as an empty
     * one.
     */
    fun billboardWalkPage(
        page: Int,
        moviesFetched: Int,
        moviesKept: Int,
        showsFetched: Int,
        showsKept: Int,
        pool: Int
    ) {
        synchronized(lock) {
            walkPages += "p$page m$moviesFetched>$moviesKept s$showsFetched>$showsKept"
            walkPool = pool
        }
        putBillboard("walk", walkLine())
        note(
            BILLBOARD + "walk page=$page movies=$moviesFetched->$moviesKept " +
                "shows=$showsFetched->$showsKept pool=$pool"
        )
    }

    /** Why the walk stopped: [reason] is `null` page, `empty` pages or the `cap`. */
    fun billboardWalkEnded(reason: String, pages: Int, pool: Int) {
        synchronized(lock) {
            walkEnd = reason
            walkPool = pool
        }
        putBillboard("walk", walkLine())
        note(BILLBOARD + "walk end reason=$reason pages=$pages pool=$pool")
    }

    /** The walk's one summary line: every page, the pool it built, and its end. */
    private fun walkLine(): String {
        val pages =
            if (walkPages.isEmpty()) "pages=0" else "pages=${walkPages.size} [${walkPages.joinToString(" ")}]"
        return "walk $pages pool=$walkPool end=${walkEnd ?: "running"}"
    }

    /**
     * The same pool arriving again - the re-entry path where nothing is
     * re-resolved. Worth a line: a billboard that is gone while this keeps
     * saying "same pool, pages unsettled" is a page that never settled.
     */
    fun billboardDealSkipped(pages: Int, settledNow: Int) {
        putBillboard("pages", "pages dealt=$pages settled=$settledNow (no re-deal: same pool)")
        note(BILLBOARD + "deal skipped (same pool) pages=$pages settled=$settledNow")
    }

    /** One page came back with art. */
    fun billboardPageResolved(key: String, settledNow: Int, total: Int) {
        synchronized(lock) {
            resolved++
            settled = settledNow
        }
        putBillboard("pages", pagesLine())
        putBillboard("resolve", resolveLine())
        note(BILLBOARD + "settle ${key.take(KEY_LIMIT)} settled=$settledNow/$total")
    }

    /**
     * One page resolved to null - the interesting one: it leaves its page
     * unsettled forever (`?: return@launch` in the ViewModel), so a billboard
     * with a null page never shows that page and never finishes.
     */
    fun billboardPageNull(key: String) {
        synchronized(lock) { nulls++ }
        putBillboard("pages", pagesLine())
        putBillboard("resolve", resolveLine())
        note(BILLBOARD + "resolve null ${key.take(KEY_LIMIT)} (page stays unsettled)")
    }

    /** The resolve job threw: the message only, exactly what the warning logs. */
    fun billboardJobFailed(message: String?) {
        val text = message?.take(MESSAGE_LIMIT)?.ifBlank { null } ?: "unknown"
        synchronized(lock) { failure = text }
        putBillboard("resolve", resolveLine())
        note(BILLBOARD + "resolve job failed: $text")
    }

    /** `clearBillboard()` ran, and why (F2/F4): disabled vs an empty feed. */
    fun billboardCleared(reason: String, epoch: Int) {
        putBillboard("clear", "clear reason=$reason epoch=$epoch")
        note(BILLBOARD + "clear reason=$reason epoch=$epoch")
    }

    // ── Spotlight ─────────────────────────────────────────────────────────

    /** A card took focus: this becomes the last snapshot that will be kept. */
    fun spotlightFocused(token: Int, key: String?, title: String?) {
        synchronized(lock) {
            focusToken = token
            held = true
            lastKey = key
            lastTitle = title
            lastExpanded = false
        }
        putSpotlight("tracker", trackerLine())
        note(SPOTLIGHT + "focus card=${label(key, title)} token=$token")
    }

    /**
     * Focus left. The last snapshot is KEPT - see the field doc (F5).
     *
     * When a panel is up, the line also says how long after the expand the blur
     * came. That is the number the 2026-11-12 collapse report is about: a card
     * that blurs a few hundred milliseconds after its own panel opened, with no
     * card taking focus back, loses focus to nothing and never re-earns it -
     * which the paste can now show rather than infer.
     */
    fun spotlightBlurred() {
        val sinceExpand = synchronized(lock) {
            held = false
            if (expandedAtMs <= 0L) null else (clock() - expandedAtMs).coerceAtLeast(0L)
        }
        putSpotlight("tracker", trackerLine())
        note(
            SPOTLIGHT + "blur (last snapshot kept)" +
                (sinceExpand?.let { " +${it}ms after expand" } ?: "")
        )
    }

    /**
     * The dwell effect's inputs on its last run, and the card it armed on.
     *
     * [token] is recorded but is deliberately NOT one of the effect's keys (see
     * [spotlightDwellCancelled]): a re-entrant focus on the card already held
     * used to bump it and restart the two seconds.
     */
    fun spotlightDwell(expandDelayMs: Long, menuOpen: Boolean, token: Int, card: String?) {
        synchronized(lock) {
            dwellDelayMs = expandDelayMs
            dwellMenuOpen = menuOpen
            dwellToken = token
            dwellCard = card
            dwellSeen = true
        }
        putSpotlight("dwell", dwellLine())
    }

    /**
     * A dwell was abandoned before its delay completed, and by which input.
     *
     * The field report this answers (spec 2026-11-12): five cards each took
     * focus once, the delay was 2 s, no menu was open - and the panel never
     * opened, which means the effect was RESTARTED, and the paste could not say
     * by what. [from] -> [to] is the transition, so a flicker reads as
     * `hasSnapshot (series:tt…→none)` and a churn as `focusToken (5→6)`.
     */
    fun spotlightDwellCancelled(key: String, from: String, to: String) {
        synchronized(lock) { dwellCancel = "$key ($from\u2192$to)" }
        putSpotlight("dwell", dwellLine())
        note(SPOTLIGHT + "dwell cancelled key=$key ($from\u2192$to)")
    }

    /**
     * Whether this composition may claim the entry focus (F3).
     *
     * Written from a different scope than the dwell effect - the hero's own
     * claim effect - because that is where the latch lives, so it updates the
     * dwell line rather than inventing a second one.
     */
    fun spotlightClaimGate(claimEntryFocus: Boolean) {
        val emit = synchronized(lock) {
            dwellClaimEntryFocus = claimEntryFocus
            dwellSeen
        }
        if (emit) putSpotlight("dwell", dwellLine())
    }

    /**
     * The first-pill claim's outcome - the gate-hole case: a failed claim
     * leaves the entry focus live, so every billboard re-deal yanks focus.
     */
    fun spotlightClaim(taken: Boolean, frames: Int = 0) {
        synchronized(lock) { lastClaim = taken }
        putSpotlight("dwell", dwellLine())
        // The count is what separates "landed on the first try" from "needed the
        // retry" - and, on a failure, how much of the bounded window the attempt
        // actually spent (spec 2026-11-13, F4).
        note(
            SPOTLIGHT + "claim ${if (taken) "taken" else "not-taken"}" +
                (if (frames > 0) " frames=$frames" else "")
        )
    }

    /** The panel expanded for the held card. */
    fun spotlightExpanded(title: String?) {
        synchronized(lock) {
            expandedAtMs = clock()
            lastExpanded = true
            expandedCard = label(lastKey, title ?: lastTitle)
            panelTrailer = "not-reported"
            // A new panel draws a new picture; until its composable reports, the
            // last card's rung would be a lie about this one.
            panelBackdrop = null
        }
        putSpotlight("tracker", trackerLine())
        putSpotlight("panel", panelLine("expanded=yes last=expanded"))
        note(SPOTLIGHT + "expanded ${expandedCard}")
    }

    /**
     * The expanded card's trailer's own state (spec 2026-11-12, F4).
     *
     * "The trailer did not start" has four causes that look identical on screen:
     * no key for the card, the mode/autoplay gate, a resolve that failed, and a
     * player that never painted. This is the word from inside the panel's own
     * trailer composable, so the next paste names the one it is.
     */
    fun spotlightPanelTrailer(status: String) {
        synchronized(lock) { panelTrailer = status }
        putSpotlight("panel", panelLine(if (lastExpanded) "expanded=yes last=expanded" else "expanded=no"))
    }

    /**
     * The picture the expanded panel drew (F3, 2026-11-13).
     *
     * [rung] is `background`, `none` (the title has no backdrop) or `loading`
     * (the resolve for this title has not landed); [url] is the drawn URL, of
     * which only the host travels in the paste - the size class is implied by
     * the rung, and the full URL would be a wall of signed hostnames.
     */
    fun spotlightPanelBackdrop(rung: String, url: String?) {
        val host = url
            ?.substringAfter("://", "")
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            panelBackdrop = if (host != null) "$rung@$host" else rung
        }
        putSpotlight(
            "panel",
            panelLine(if (lastExpanded) "expanded=yes last=expanded" else "expanded=no")
        )
    }

    /** The dwell guard collapsed the panel, and which guard fired (F3/F5). */
    fun spotlightCollapsed(guard: String) {
        synchronized(lock) {
            panelTrailer = null
            panelBackdrop = null
        }
        putSpotlight("panel", panelLine("expanded=no last=collapsed guard=$guard"))
        note(SPOTLIGHT + "collapse guard=$guard")
    }

    /** One panel line: what the panel is doing, for which card, and its trailer. */
    private fun panelLine(state: String): String {
        val card = expandedCard?.let { " $it" } ?: ""
        val trailer = panelTrailer?.let { " trailer=$it" } ?: ""
        val picture = panelBackdrop?.let { " picture=$it" } ?: ""
        return "panel $state$card$trailer$picture"
    }

    // ── Reads ─────────────────────────────────────────────────────────────

    /** The latest state as prefixed `billboard: ` / `spotlight: ` lines. */
    fun summaries(): List<String> =
        BILLBOARD_ORDER.mapNotNull { billboard[it] } +
            SPOTLIGHT_ORDER.mapNotNull { spotlight[it] }

    /** Recent transitions, oldest first, already prefixed. Empty until Home runs. */
    fun lines(): List<String> = ring

    /** Clears every store (tests). */
    fun reset() {
        synchronized(lock) {
            ring = emptyList()
            billboard = emptyMap()
            spotlight = emptyMap()
            dealt = 0
            settled = 0
            resolved = 0
            nulls = 0
            failure = null
            walkPages.clear()
            walkPool = 0
            walkEnd = null
            focusToken = 0
            held = false
            lastKey = null
            lastTitle = null
            lastExpanded = false
            lastClaim = null
            dwellDelayMs = 0L
            dwellMenuOpen = false
            dwellClaimEntryFocus = false
            renderHeroVisible = null
            renderHeroIndex = null
            renderHeroHeightDp = null
            renderViewportDp = null
            renderFirstIndex = 0
            renderFirstOffset = 0
            renderItemCount = 0
            renderComposed = null
            renderGate = null
            renderPage = null
            renderArt = null
            renderSettled = 0
            renderPages = 0
            restCorrections = 0
            artUrls.clear()
            artSources.clear()
            artSwaps.clear()
            artSwapsSession = 0
            dwellToken = 0
            dwellCard = null
            dwellCancel = null
            expandedAtMs = 0L
            expandedCard = null
            panelTrailer = null
            dwellSeen = false
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun pagesLine(): String = "pages dealt=$dealt settled=$settled"

    private fun dwellLine(): String {
        val last = lastClaim?.let { if (it) "taken" else "not-taken" } ?: "none"
        val armed = dwellCard?.let { "card=$it" } ?: "card=none"
        return "dwell delay=${dwellDelayMs}ms menuOpen=${yesNo(dwellMenuOpen)} " +
            "claimEntryFocus=${yesNo(dwellClaimEntryFocus)} lastClaim=$last " +
            "token=$dwellToken $armed" +
            (dwellCancel?.let { " lastCancel=$it" } ?: "")
    }

    private fun resolveLine(): String {
        val failed = failure?.let { " failed=\"$it\"" } ?: ""
        return "resolve resolved=$resolved null=$nulls of $dealt$failed"
    }

    private fun trackerLine(): String {
        val last = if (lastKey == null && lastTitle == null) {
            "last=none"
        } else {
            "last=${label(lastKey, lastTitle)} expanded=${yesNo(lastExpanded)}"
        }
        return "tracker focusToken=$focusToken held=${yesNo(held)} $last"
    }

    private fun putBillboard(kind: String, line: String) {
        synchronized(lock) { billboard = billboard + (kind to (BILLBOARD + line)) }
    }

    private fun putSpotlight(kind: String, line: String) {
        synchronized(lock) { spotlight = spotlight + (kind to (SPOTLIGHT + line)) }
    }

    private fun note(line: String) {
        synchronized(lock) { ring = (ring + line).takeLast(MAX_LINES) }
    }

    /** `id` when a card has one, else its title - never both, never a URL. */
    private fun label(key: String?, title: String?): String {
        val id = key?.take(KEY_LIMIT)
        val name = title?.take(TITLE_LIMIT)
        return when {
            id != null && name != null -> "'${name.trim()}' ($id)"
            id != null -> "($id)"
            name != null -> "'${name.trim()}'"
            else -> "(no card)"
        }
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    /**
     * A fraction of a page as the paste reads it: `0.00`, `-0.03`.
     *
     * Two decimals and a forced dot: the strip was about 0.03 of a frame, and a
     * locale that wrote `0,03` would make the one number the report is about
     * unsearchable. The sign matters - the field photo's strip was the NEXT page
     * at the right edge (positive), but a negative offset shows the previous one
     * at the left, and both are rest offsets now.
     */
    private fun offsetText(offset: Float): String =
        String.format(Locale.US, "%.2f", offset)

    private const val KEY_LIMIT = 48
    private const val TITLE_LIMIT = 48
    private const val MESSAGE_LIMIT = 120
}
