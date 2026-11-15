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
        if (loads == 0 && trims.isEmpty()) {
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

    /** Clears the counters (tests). */
    fun reset() {
        synchronized(lock) {
            memoryHits = 0
            diskDecodes = 0
            networkDecodes = 0
            otherDecodes = 0
            heroDecodes = 0
            railDiskDecodes = 0
            sizes.clear()
            trims.clear()
        }
    }
}
