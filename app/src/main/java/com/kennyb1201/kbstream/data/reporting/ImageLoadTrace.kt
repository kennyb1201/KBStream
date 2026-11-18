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

    private val lock = Any()

    private var memoryHits = 0
    private var diskDecodes = 0
    private var networkDecodes = 0
    private var otherDecodes = 0

    // F1 of the scrolling round-two spec: two counts the size list could not
    // carry. A hero decode is the 1280x720 re-decode a rail walk pays per card;
    // a rail disk decode is a newly-visible card's tile coming off the disk
    // because the memory cache no longer had it.
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

    /** "WxH" -> count, first-seen order kept (LinkedHashMap). */
    private val sizes = LinkedHashMap<String, Int>()

    /** "LEVEL xN" per distinct level, oldest first. */
    private val trims = LinkedHashMap<String, Int>()

    /** One completed request. [width]/[height] are the DECODED pixels. */
    fun noteImage(source: Source, width: Int, height: Int) {
        synchronized(lock) {
            when (source) {
                Source.MEMORY -> memoryHits++
                Source.DISK -> diskDecodes++
                Source.NETWORK -> networkDecodes++
                Source.OTHER -> otherDecodes++
            }
            // A cache hit is not a decode: counting its size would drown the
            // six slots in one tile's dimensions and hide the surfaces that
            // really did decode.
            if (source == Source.MEMORY || width <= 0 || height <= 0) return
            // The two counts the size list cannot carry (spec 2026-11-15, F1).
            // Classified by DECODED width, not by URL or call site, so it costs
            // no call-site plumbing and counts what was actually decoded.
            if (width >= HERO_DECODE_MIN_WIDTH) {
                heroDecodes++
            } else if (source == Source.DISK) {
                railDiskDecodes++
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
            heroWarms == 0 && tileWarms == 0 && heroFocusArrivals == 0
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
        // why they are counts rather than sizes.
        if (heroDecodes > 0 || railDiskDecodes > 0) {
            out += "scroll: heroDecodes=$heroDecodes cardDiskDecodes=$railDiskDecodes"
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
            sizes.clear()
            trims.clear()
        }
    }
}
