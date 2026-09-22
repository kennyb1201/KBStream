package com.kennyb1201.kbstream.data.library

/**
 * Normalizes any catalog or stream id into the (IMDB, TMDB) pair the library
 * add pipelines store.
 *
 * Every long-press "Add to Library" / "Add to list…" entry point used to keep
 * only the numeric TMDB form of an item's id
 * (`id.removePrefix("tmdb:").toIntOrNull()`) and throw the rest away. Add-on
 * catalog rows and IMDB-keyed rows ("tt12345") — and Continue Watching rows,
 * which are keyed by IMDB id — therefore handed the add pipeline a null id,
 * and [LibraryMirror] dropped the whole add: no local row, no tracker mirror,
 * and no feedback on screen. Splitting the id here means the id a screen
 * actually has is the id the item is saved with.
 *
 * Stream keys are accepted too ("tt12345:2:5", "tmdb:123:2:5"): only the head
 * segment names the title.
 */
object LibraryIds {

    /** The id pair an add is saved with; at least one side is set when known. */
    data class Ids(
        val imdbId: String?,
        val tmdbId: Int?
    ) {
        /** True when the pair can identify a title (so an add is possible). */
        val hasAny: Boolean
            get() = imdbId != null || tmdbId != null
    }

    /**
     * Splits one raw id. An id flavor the app does not know (an add-on's
     * "kitsu:42", say) is kept as the IMDB side rather than dropped, matching
     * how the Simkl playback parser passes unknown flavors through — the
     * title still lands locally instead of the press doing nothing.
     */
    fun split(rawId: String?): Ids {
        val raw = rawId?.trim().orEmpty()
        if (raw.isBlank()) return Ids(null, null)
        val head = raw.substringBefore(':').trim()
        return when {
            raw.startsWith("imdb:", ignoreCase = true) -> {
                val id = raw.substringAfter(':').substringBefore(':').trim()
                Ids(id.takeIf { it.isNotBlank() }, null)
            }

            head.startsWith("tt") -> Ids(head, null)

            raw.startsWith("tmdb:", ignoreCase = true) -> Ids(
                null,
                raw.substringAfter(':').substringBefore(':').trim()
                    .toIntOrNull()?.takeIf { it > 0 }
            )

            head.all(Char::isDigit) -> Ids(null, head.toIntOrNull()?.takeIf { it > 0 })

            else -> {
                // "<scheme>:<id>" flavors (kitsu:42, anilist:123). Keep the
                // scheme and its id, dropping a trailing stream key
                // ("kitsu:42:2:5" -> "kitsu:42").
                val parts = raw.split(':')
                val id = if (parts.size >= 2) parts.take(2).joinToString(":") else raw
                Ids(id.takeIf { it.isNotBlank() }, null)
            }
        }
    }

    /**
     * First id pair that identifies something: the preferred id (a Continue
     * Watching item's parent show) then the fallback (the row's own key).
     */
    fun splitFirst(vararg rawIds: String?): Ids {
        rawIds.forEach { raw ->
            val ids = split(raw)
            if (ids.hasAny) return ids
        }
        return Ids(null, null)
    }
}
