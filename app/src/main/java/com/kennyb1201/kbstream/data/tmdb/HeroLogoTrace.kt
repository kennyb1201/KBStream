package com.kennyb1201.kbstream.data.tmdb

/**
 * The last few Home-hero clear-logo DRAWS, for the in-app Diagnostics report.
 *
 * [HeroArtTrace] records the RESOLVE - which logo URL the repository picked, and
 * from which layer - and that is all it records: four entries, no URL, no
 * bitmap, no tint. A resolve that says `logo=true` only means a URL was handed
 * over; the draw is a separate Coil request that can decode the file at the
 * wrong depth or not paint at all.
 *
 * The field case behind this (Chappelle's Show, 2026-11-24) shows exactly why
 * that gap matters: the hero draws a solid white slab where the wordmark should
 * be, Detail draws the same title's logo correctly, and the paste carries no
 * entry for the title at all - the resolve trace holds four entries and the walk
 * from the hero to Settings resolves more than that on the way, so the one line
 * that would have named it is evicted before COPY DIAGNOSTICS is pressed.
 *
 * So the draw gets its own trace, with its own small cap, turning over only on
 * hero paints: `config=` settles file-vs-decode on its own (RGB_565 means the
 * Nov 21 op-out is not reaching this draw; ARGB_8888/HARDWARE means the decode
 * is fine and the file is the suspect), `shape=` names the file, `size=` says
 * what was decoded, and `tint=` carries the dark-mark verdict so a later tint
 * question is answerable from the same paste.
 *
 * Never the URL itself: a paste is public, so the file is named the way the
 * player's `LOGO: start` line names it - host plus bounded path, no query.
 */
object HeroLogoTrace {

    /**
     * A report is not a log file. Eight covers the focus steps between the hero
     * being open and the copy being taken with margin, and the trace only turns
     * over on hero paints, so it survives the walk to Settings.
     */
    private const val MAX_ENTRIES = 8

    private val entries = ArrayDeque<String>(MAX_ENTRIES)

    /**
     * Records one logo that PAINTED.
     *
     * [layer] is Coil's own answer for this draw (memory, disk, network); [width]
     * and [height] are the decoded bitmap's, and [config] its config by name -
     * read from the bitmap this paint already materializes, never a second
     * readback. [tint] is the dark-mark verdict that decided the SrcIn tint.
     */
    @Synchronized
    fun recordDraw(
        tmdbId: Int?,
        shape: String,
        layer: HeroArtTrace.Layer,
        width: Int,
        height: Int,
        config: String,
        tint: Boolean
    ) {
        add(
            buildString {
                append("tmdb=").append(tmdbId?.toString() ?: "none")
                append(" shape=").append(shape)
                append(" layer=").append(layer.label)
                append(" size=")
                .append(if (width > 0 && height > 0) "${width}x$height" else "none")
                append(" config=").append(config)
                append(" tint=").append(if (tint) "yes" else "no")
            }
        )
    }

    /**
     * Records one logo that FAILED to load.
     *
     * The hero keeps the name standing in when the art fails - by design - so
     * without this a failed logo read exactly like a title TMDB holds no
     * wordmark for. [reason] is the failure's own class name
     * (`logoLoadFailureReason`), never its message.
     */
    @Synchronized
    fun recordFailure(tmdbId: Int?, shape: String, reason: String) {
        add(
            buildString {
                append("tmdb=").append(tmdbId?.toString() ?: "none")
                append(" shape=").append(shape)
                append(" reason=").append(reason)
            }
        )
    }

    /** Oldest first, so the last line is the logo the viewer is looking at. */
    @Synchronized
    fun lines(): List<String> = entries.toList()

    /** Callers hold the lock. */
    private fun add(line: String) {
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        entries.addLast(line)
    }
}
