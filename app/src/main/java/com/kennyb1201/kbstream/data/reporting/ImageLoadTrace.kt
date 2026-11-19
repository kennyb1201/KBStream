package com.kennyb1201.kbstream.data.reporting

/**
 * What the image loader actually did this session, in the words the diagnostics
 * report prints.
 *
 * The 2026-11-14 scrolling spec (F1) asks one question before any fix: is the
 * scroll jank a decode problem (a cache wiped mid-browse, so every card is
 * re-decoded from disk) or a fill-rate problem (a 4K framebuffer painting four
 * times the pixels)? Nothing in the report could answer it. MemoryPressure
 * counted cache CLEARS, which proves a wipe happened but says nothing about
 * whether the images then had to be decoded again - and a session that decodes
 * 400 bitmaps behind a cache that never cleared reads identically to one that
 * decodes 400 because it did.
 *
 * So every completed request is counted by where its pixels came from, and every
 * trimmed memory level is recorded with its name. The line is then read as a
 * sentence: hits high and decodes low is a warm session; `disk=` growing after a
 * `RUNNING_LOW` tag with the image cache kept says the trim no longer costs a
 * re-decode; `RUNNING_CRITICAL` beside a decode burst is the clear that did.
 *
 * The decode SIZES are here for F2's other half: the report proves what the
 * surfaces actually decoded (a rail tile is a few hundred pixels wide, a 4K hero
 * is 3840) rather than what the source was shaped like, which is what the
 * full-resolution hero bug was.
 *
 * The DECODE TIMES are here for the 2026-11-18 diagnosis (§1, §7). Every other
 * count on this object says what the loader did, not what it cost: a session
 * that decoded 400 bitmaps reads the same whether each took 3 ms or 40. The
 * frame meter cannot attribute a slow frame to anything either, so the two
 * lines together are the only way a paste can answer "did the decode land on
 * the frame". `ge16`/`ge33` are a decode that cost one or two 60 Hz budgets;
 * `duringFrame` says how many of them finished while a metered gesture was
 * running, which is the correlation itself.
 *
 * The ASKED sizes are the other half of the warm question: `decoded at:` shows
 * what came out of the decoder, which a warm also writes. `asked at:` shows what
 * the surfaces asked the loader FOR - including memory hits, where a warm that
 * matches shows up as the same size. Two different lists for one asset is a
 * warm that writes beside the entry the card reads.
 *
 * Counts and pixel sizes only - never a URL, a title or an id, the same rule
 * [DetailTrace] follows. Kept free of Android and Coil types on purpose: the
 * naming and the arithmetic are the parts worth testing, and they are testable
 * only if they need neither.
 */
internal object ImageLoadTrace {

    /** Where a decoded bitmap came from. */
    enum class Source {
        /** Served from the memory cache: no decode, no I/O. */
        MEMORY,

        /** Decoded from the disk cache (the network was not touched). */
        DISK,

        /** Fetched and decoded over the network. */
        NETWORK,

        /** Decoded without either cache - a policy-disabled request. */
        OTHER
    }

    /** Distinct decode sizes kept. Six names the surfaces; more is a log. */
    private const val MAX_SIZES = 6

    /**
     * Where "hero-size" starts, in DECODED pixels (spec 2026-11-15, scrolling
     * round two F1).
     *
     * The hero asks for 1280x720 ([HeroBackdropArt]); a rail tile is a few hundred
     * wide. 960 is above every tile size the app requests and below the hero's, so
     * a decode at or above it is a hero draw - the count the dig could not get from
     * `decoded at:`, which keeps only six sizes and so says nothing about how often
     * one of them repeated.
     */
    const val HERO_DECODE_MIN_WIDTH = 960

    /** Trim levels kept, newest last. Levels repeat, so this counts. */
    private const val MAX_TRIMS = 4

    /** One 60 Hz frame budget in ms: a decode at or over it lost a frame. */
    private const val FRAME_BUDGET_MS = 16

    /** Two budgets: the median frame the 2026-11-18 tap walk measured. */
    private const val TWO_FRAME_BUDGET_MS = 33

    private val lock = Any()

    private var memoryHits = 0
    private var diskDecodes = 0
    private var networkDecodes = 0
    private var otherDecodes = 0

    // F1 of the scrolling round-two spec: two counts the size list could not
    // carry. A hero decode is the 1280x720 re-decode a rail walk pays per card;
    // a rail disk decode is a newly-visible card's tile coming off the disk
    // because the memory cache no longer had it.
    //
    // WALK-SCOPED (2026-11-19): both count only decodes that landed inside a
    // metered gesture or its short tail (see [noteImage]'s walkActive and
    // UiFrameTrace.inWalkWindow). They used to be app-wide, which made a whole
    // session's Detail-page decodes print as "the scroll's" - the build-387
    // paste's `cardDiskDecodes=306` was a 30-minute total that included the 137
    // tile warms themselves and could not be read as the walk's cost.
    private var heroDecodes = 0
    private var railDiskDecodes = 0

    // Dig four (2026-11-16, §1c): the counts the width threshold cannot
    // separate. `heroDecodes` is every non-memory decode >= 960 px at one
    // app-wide funnel - the hero's own draw, Detail's backdrop, Actor's, and the
    // lookahead WARMS (which are never displayed). Counting the draw where it is
    // drawn and the warm where it is warmed is the only way to tell them apart;
    // the tile warms get the same treatment so a post-walk paste can subtract
    // "decoded because we warmed it" from "decoded because the viewer arrived".
    private var heroDraws = 0
    private var heroWarms = 0
    private var tileWarms = 0

    // F5 (spec 2026-11-17, lookahead warm-ahead): the hit RATE is the proof.
    // `heroWarms` counts decodes the lookahead paid; it cannot say whether the
    // walk then LANDED on them. Every focus arrival is counted, and every hero
    // draw is counted by whether its bitmap was already in the memory cache -
    // `hits / (hits + misses)` is the number that has to rise, and a warm the
    // walk never reached shows up as neither.
    private var heroFocusArrivals = 0
    private var heroDrawHits = 0
    private var heroDrawMisses = 0

    // The cost side (diagnosis 2026-11-18, §1/§7). Counts, not samples: a paste
    // needs "how many decodes were slow", never the list itself.
    private var timedDecodes = 0
    private var decodesOverBudget = 0
    private var decodesOverTwoBudgets = 0
    private var slowestDecodeMs = 0
    private var decodesDuringFrame = 0

    /** "WxH" -> count, first-seen order kept (LinkedHashMap). */
    private val sizes = LinkedHashMap<String, Int>()

    /** "WxH" -> count of what the surfaces ASKED for, hits included. */
    private val askedSizes = LinkedHashMap<String, Int>()

    /** "LEVEL xN" per distinct level, oldest first. */
    private val trims = LinkedHashMap<String, Int>()

    /**
     * One completed request. [width]/[height] are the DECODED pixels.
     *
     * [durationMs] is the request's end-to-end latency (start to bitmap, so it
     * includes the disk read), 0 when the caller did not time it. [askedWidth]/
     * [askedHeight] are the size the layout resolved the request to, 0 when Coil
     * left a dimension undefined. [duringFrame] says a frame-metered gesture was
     * running as this completed - the number that ties a decode to a frame.
     *
     * [walkActive] says a metered gesture was open **or had just closed** (see
     * UiFrameTrace.inWalkWindow), and it is what scopes the two `scroll:` counts.
     * It is deliberately wider than [duringFrame]: the art a step asks for is
     * decoded after the frame session closes - the hero's dwell alone is 600 ms
     * against a 350 ms settle - so a strict `duringFrame` gate would drop exactly
     * the warms those counts exist to subtract.
     */
    fun noteImage(
        source: Source,
        width: Int,
        height: Int,
        durationMs: Int = 0,
        askedWidth: Int = 0,
        askedHeight: Int = 0,
        duringFrame: Boolean = false,
        walkActive: Boolean = false
    ) {
        synchronized(lock) {
            when (source) {
                Source.MEMORY -> memoryHits++
                Source.DISK -> diskDecodes++
                Source.NETWORK -> networkDecodes++
                Source.OTHER -> otherDecodes++
            }
            // The ask, hits included: a memory hit whose asked size equals a
            // warm's size is the proof the warm entry was the one the card
            // reads, and a hit at a size no warm wrote is the proof it was not.
            if (askedWidth > 0 && askedHeight > 0) {
                val asked = "${askedWidth}x$askedHeight"
                askedSizes[asked] = (askedSizes[asked] ?: 0) + 1
                while (askedSizes.size > MAX_SIZES) {
                    askedSizes.remove(askedSizes.keys.first())
                }
            }
            // Timing is decode-only: a memory hit has no decode to time, and
            // counting its latency would mix the frame's own work into the
            // number a fix is supposed to bring down.
            if (source != Source.MEMORY && durationMs > 0) {
                timedDecodes++
                if (durationMs >= FRAME_BUDGET_MS) decodesOverBudget++
                if (durationMs >= TWO_FRAME_BUDGET_MS) decodesOverTwoBudgets++
                if (durationMs > slowestDecodeMs) slowestDecodeMs = durationMs
                if (duringFrame) decodesDuringFrame++
            }
            // A cache hit is not a decode: counting its size would drown the
            // six slots in one tile's dimensions and hide the surfaces that
            // really did decode.
            if (source == Source.MEMORY || width <= 0 || height <= 0) return
            // The two counts the size list cannot carry (spec 2026-11-15, F1).
            // Classified by DECODED width, not by URL or call site, so it costs
            // no call-site plumbing and counts what was actually decoded.
            //
            // Only inside a walk window (2026-11-19): the line says "scroll", and
            // these were the session's totals instead. `asked at`/`decoded at`
            // below stay session-wide on purpose - they are the warm's size audit,
            // not a claim about what the walk cost.
            if (walkActive) {
                if (width >= HERO_DECODE_MIN_WIDTH) {
                    heroDecodes++
                } else if (source == Source.DISK) {
                    railDiskDecodes++
                }
            }
            val key = "${width}x$height"
            sizes[key] = (sizes[key] ?: 0) + 1
            while (sizes.size > MAX_SIZES) {
                val oldest = sizes.keys.first()
                sizes.remove(oldest)
            }
        }
    }

    /**
     * The hero drew a backdrop (memory hit or decode - it was on screen).
     *
     * [memoryCached] is Coil's own answer for THIS draw: the bitmap came from
     * the memory cache, so the walk arrived on art the warm had already decoded.
     * That is F5's other half - the hit, not the warm.
     */
    fun noteHeroDraw(memoryCached: Boolean) {
        synchronized(lock) {
            heroDraws++
            if (memoryCached) heroDrawHits++ else heroDrawMisses++
        }
    }

    /**
     * Focus landed on a card: the lookahead was asked to warm its near field
     * (spec 2026-11-17, F1/F5). Counted per arrival, not per card warmed, so
     * the rate is read against the walk the viewer actually did.
     */
    fun noteHeroFocusArrival() {
        synchronized(lock) { heroFocusArrivals++ }
    }

    /**
     * The lookahead decoded a hero-size backdrop ahead of focus. A warm that the
     * hero then draws from memory is not a decode twice, but it IS a decode the
     * session paid; `heroDecodes - heroWarms` is the draws that cost a decode.
     */
    fun noteHeroWarm() {
        synchronized(lock) { heroWarms++ }
    }

    /**
     * The rail-tile warm decoded a card's tile ahead of the walk - the same
     * decode the card would otherwise have paid on the frame it appeared, which
     * is why `cardDiskDecodes` does not fall when this works: it counts both.
     * `cardDiskDecodes - tileWarms` is the first-sight decodes that remain.
     */
    fun noteRailTileWarm() {
        synchronized(lock) { tileWarms++ }
    }

    /**
     * One `onTrimMemory` delivery, by name.
     *
     * The names are the platform's own levels, spelled so a paste can be read
     * without the constants table: RUNNING_MODERATE, RUNNING_LOW,
     * RUNNING_CRITICAL, BACKGROUND, UI_HIDDEN, MODERATE, COMPLETE.
     */
    fun noteTrim(levelName: String) {
        val trimmed = levelName.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) {
            trims[trimmed] = (trims[trimmed] ?: 0) + 1
            while (trims.size > MAX_TRIMS) {
                val oldest = trims.keys.first()
                trims.remove(oldest)
            }
        }
    }

    /**
     * The report's lines, or empty before an image has loaded or a trim has
     * fired - a session that has not scrolled keeps the length it had.
     */
    fun lines(): List<String> = synchronized(lock) {
        val loads = memoryHits + diskDecodes + networkDecodes + otherDecodes
        if (loads == 0 && trims.isEmpty() && heroDraws == 0 &&
            heroWarms == 0 && tileWarms == 0 && heroFocusArrivals == 0 &&
            timedDecodes == 0 && askedSizes.isEmpty()
        ) {
            return emptyList()
        }
        val out = mutableListOf<String>()
        // A trim can arrive before the first image: pressure evidence on its
        // own, with no load line to explain it.
        if (loads > 0) {
            out += "loads: memory=$memoryHits disk=$diskDecodes " +
                "network=$networkDecodes other=$otherDecodes"
        }
        // The scroll session's two counts (spec 2026-11-15, F1): how many hero
        // decodes the walk paid, and how many rail cards had to be decoded off
        // the disk. Both are read as "the fix must bring this down", which is
        // why they are counts rather than sizes - and why they are scoped to the
        // walk window: an app-wide count answered a different question (see the
        // field's own note above).
        if (heroDecodes > 0 || railDiskDecodes > 0) {
            out += "scroll: heroDecodes=$heroDecodes cardDiskDecodes=$railDiskDecodes"
        }
        // What the decodes COST (diagnosis 2026-11-18, §1/§7): the counts above
        // cannot tell a 3 ms tile from a 40 ms one, and the frame meter has no
        // per-frame attribution. `duringFrame` is the correlation: decodes that
        // finished while a metered gesture was open.
        if (timedDecodes > 0) {
            out += "decode: n=$timedDecodes ge16=$decodesOverBudget " +
                "ge33=$decodesOverTwoBudgets max=${slowestDecodeMs}ms " +
                "duringFrame=$decodesDuringFrame"
        }
        // Where the hero-size decodes came from (dig four §1c). Printed beside
        // the counts above so a paste can be read as "warms are working" rather
        // than "the walk is still slow".
        if (heroDraws > 0 || heroWarms > 0 || tileWarms > 0) {
            out += "art: heroDraws=$heroDraws heroWarms=$heroWarms " +
                "tileWarms=$tileWarms"
        }
        // The lookahead's own line (spec 2026-11-17, F5): focus arrivals, and
        // of the hero draws that followed, how many were already decoded. The
        // warms above can be healthy while the walk still lands cold; this is
        // the one line that answers it.
        if (heroFocusArrivals > 0 || heroDrawHits > 0 || heroDrawMisses > 0) {
            out += heroLookaheadLine()
        }
        if (sizes.isNotEmpty()) {
            out += "decoded at: " +
                sizes.entries.joinToString(", ") { "${it.key} x${it.value}" }
        }
        // What the surfaces asked FOR, hits included - the warm's own audit.
        if (askedSizes.isNotEmpty()) {
            out += "asked at: " +
                askedSizes.entries.joinToString(", ") { "${it.key} x${it.value}" }
        }
        if (trims.isNotEmpty()) {
            out += "trim: " +
                trims.entries.joinToString(", ") { "${it.key} x${it.value}" }
        }
        out
    }

    /**
     * The lookahead's one line: focus arrivals, hero-draw hits and misses, and
     * the integer hit rate. No formatting or locale: `rate=80%` is the same
     * string on every device, and an arrival with no draw yet reads 0%.
     */
    private fun heroLookaheadLine(): String {
        val draws = heroDrawHits + heroDrawMisses
        val rate = if (draws == 0) 0 else heroDrawHits * 100 / draws
        return "hero: lookahead arrivals=$heroFocusArrivals " +
            "hits=$heroDrawHits misses=$heroDrawMisses rate=$rate%"
    }

    /** Clears the counters (tests). */
    fun reset() {
        synchronized(lock) {
            memoryHits = 0
            diskDecodes = 0
            networkDecodes = 0
            otherDecodes = 0
            heroDecodes = 0
            railDiskDecodes = 0
            heroDraws = 0
            heroWarms = 0
            tileWarms = 0
            heroFocusArrivals = 0
            heroDrawHits = 0
            heroDrawMisses = 0
            timedDecodes = 0
            decodesOverBudget = 0
            decodesOverTwoBudgets = 0
            slowestDecodeMs = 0
            decodesDuringFrame = 0
            sizes.clear()
            askedSizes.clear()
            trims.clear()
        }
    }
}
