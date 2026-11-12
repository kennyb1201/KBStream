package com.kennyb1201.kbstream.data.reporting

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
    private val BILLBOARD_ORDER = listOf("gate", "feed", "pages", "resolve", "clear")
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

    /** The feed the screen asked for, and what it merged from (F2, F4). */
    fun billboardFeed(keys: List<String>, trendingDaySize: Int, merged: Int) {
        val selected = if (keys.isEmpty()) "(none)" else keys.joinToString(",")
        val trending =
            if (trendingDaySize == 0) "trendingDay=empty" else "trendingDay=size=$trendingDaySize"
        putBillboard("feed", "feed selected=$selected $trending merged=$merged")
    }

    /** A fresh deal: the tally restarts, and the deal itself is a transition. */
    fun billboardDealt(pages: Int, pool: Int, epoch: Int) {
        synchronized(lock) {
            dealt = pages
            settled = 0
            resolved = 0
            nulls = 0
            failure = null
        }
        putBillboard("pages", pagesLine())
        note(BILLBOARD + "deal pages=$pages pool=$pool epoch=$epoch")
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
    fun spotlightClaim(taken: Boolean) {
        synchronized(lock) { lastClaim = taken }
        putSpotlight("dwell", dwellLine())
        note(SPOTLIGHT + "claim ${if (taken) "taken" else "not-taken"}")
    }

    /** The panel expanded for the held card. */
    fun spotlightExpanded(title: String?) {
        synchronized(lock) { expandedAtMs = clock() }
        val card: String
        synchronized(lock) {
            lastExpanded = true
            card = label(lastKey, title ?: lastTitle)
        }
        putSpotlight("tracker", trackerLine())
        putSpotlight("panel", "panel expanded=yes last=expanded $card")
        note(SPOTLIGHT + "expanded $card")
    }

    /** The dwell guard collapsed the panel, and which guard fired (F3/F5). */
    fun spotlightCollapsed(guard: String) {
        putSpotlight("panel", "panel expanded=no last=collapsed guard=$guard")
        note(SPOTLIGHT + "collapse guard=$guard")
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
            focusToken = 0
            held = false
            lastKey = null
            lastTitle = null
            lastExpanded = false
            lastClaim = null
            dwellDelayMs = 0L
            dwellMenuOpen = false
            dwellClaimEntryFocus = false
            dwellToken = 0
            dwellCard = null
            dwellCancel = null
            expandedAtMs = 0L
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

    private const val KEY_LIMIT = 48
    private const val TITLE_LIMIT = 48
    private const val MESSAGE_LIMIT = 120
}
