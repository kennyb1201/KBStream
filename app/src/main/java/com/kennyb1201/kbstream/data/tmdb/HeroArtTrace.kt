package com.kennyb1201.kbstream.data.tmdb

/**
 * The last few Home-hero artwork resolves, for the in-app Diagnostics report.
 *
 * The hero's resolve line (`HOME_HERO: logo=… tmdbId=…`) lives in logcat only,
 * and a TV has no logcat within reach from the couch - so a "this show's logo
 * never shows" report could not say which id the resolver was handed or whether
 * the answer came from a cache that had already settled the question (Kenny's
 * 2026-10-23 capture, and the MobLand case behind it).
 *
 * Four facts per resolve, and nothing else: the TMDB id, the media type, whether
 * a logo was found, and which layer answered. That is exactly enough to tell a
 * wrong id from a stale cache from a genuine "TMDB holds no English wordmark" -
 * the three explanations that otherwise look identical on screen.
 */
object HeroArtTrace {

    /** Which layer produced the answer. [NONE] means the resolve never asked. */
    enum class Layer(val label: String) {
        MEMORY("memory"),
        DISK("disk"),
        NETWORK("network"),
        NONE("none")
    }

    /** The tail of one session's resolves; a report is not a log file. */
    private const val MAX_ENTRIES = 4

    private val entries = ArrayDeque<String>(MAX_ENTRIES)

    /**
     * Records one resolve. [tmdbId] is null when the requested item carried no
     * id at all - the case that leaves a title permanently logo-less without
     * ever making a request, which is precisely what a report has to be able to
     * say.
     */
    @Synchronized
    fun record(
        tmdbId: Int?,
        mediaType: String,
        logo: Boolean,
        layer: Layer
    ) {
        val line = buildString {
            append("tmdb=").append(tmdbId?.toString() ?: "none")
            append(" type=").append(mediaType)
            append(" logo=").append(if (logo) "true" else "false")
            append(" layer=").append(layer.label)
        }
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        entries.addLast(line)
    }

    /** Oldest first, so the last line is the resolve the viewer is looking at. */
    @Synchronized
    fun lines(): List<String> = entries.toList()
}
