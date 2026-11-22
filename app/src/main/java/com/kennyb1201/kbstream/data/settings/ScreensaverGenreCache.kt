package com.kennyb1201.kbstream.data.settings

/**
 * The screensaver's resolved-genre cache, as pure encoding rules.
 *
 * The cache is a map of `"<id>:<type>" -> "Action · Drama"`, per profile,
 * written whenever a title's detail resolves its genres. It exists because the
 * map used to live only in memory: a television that had been off for weeks
 * started every screensaver with no labels at all, and the idle wait could only
 * warm so many titles per evening. A genre resolved once is now known on every
 * later launch of the device.
 *
 * Kept here rather than in the screensaver's UI so the file format can be
 * round-tripped by tests without a SharedPreferences or an Android runtime.
 */

/**
 * How many titles one profile's cache keeps.
 *
 * A cap, not a target: this store is written by the idle wait and the hero's
 * resolve, so without one a device left running for seasons would grow it
 * forever. When it is full the oldest entry leaves - the cache's job is to make
 * the shows a viewer is currently cycling warm, not to be a library index. 400
 * is many evenings of slides and a few tens of kilobytes on disk.
 */
internal const val SCREENSAVER_GENRE_CACHE_LIMIT = 400

/** One entry's genre line, as the overlay would draw it (already joined). */
internal fun decodeScreensaverGenres(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val genres = LinkedHashMap<String, String>()
    raw.lineSequence().forEach { line ->
        val separator = line.indexOf('\t')
        if (separator <= 0) return@forEach
        val key = unescapeScreensaverGenrePart(line.substring(0, separator)).trim()
        val genre = unescapeScreensaverGenrePart(line.substring(separator + 1)).trim()
        if (key.isNotEmpty() && genre.isNotEmpty()) genres[key] = genre
    }
    return genres
}

/** The on-disk text for [genres], in insertion order. */
internal fun encodeScreensaverGenres(genres: Map<String, String>): String =
    genres.entries.joinToString("\n") { (key, genre) ->
        escapeScreensaverGenrePart(key) + "\t" + escapeScreensaverGenrePart(genre)
    }

/**
 * [genres] with one title's line written, bounded by [limit].
 *
 * Re-inserting a key makes it the newest rather than leaving it in its old
 * position, so the cap evicts what has gone longest without being resolved -
 * which is the behaviour a cache wants. A blank key or genre is ignored rather
 * than stored: an empty label is not a label.
 */
internal fun putScreensaverGenre(
    genres: Map<String, String>,
    key: String,
    genre: String,
    limit: Int = SCREENSAVER_GENRE_CACHE_LIMIT
): Map<String, String> {
    val cleanKey = key.trim()
    val cleanGenre = genre.trim()
    if (cleanKey.isEmpty() || cleanGenre.isEmpty()) return genres
    val out = LinkedHashMap<String, String>(genres.size + 1)
    genres.forEach { (storedKey, storedGenre) ->
        if (storedKey != cleanKey) out[storedKey] = storedGenre
    }
    out[cleanKey] = cleanGenre
    while (out.size > limit && out.isNotEmpty()) {
        out.remove(out.keys.first())
    }
    return out
}

// The separators are a record and a field; a title or genre that somehow holds
// one must not be able to split itself into entries on the next read.
private fun escapeScreensaverGenrePart(value: String): String {
    if (value.none { it == '\\' || it == '\n' || it == '\r' || it == '\t' }) {
        return value
    }
    return buildString(value.length + 8) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

private fun unescapeScreensaverGenrePart(value: String): String {
    if (!value.contains('\\')) return value
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '\\' || index + 1 >= value.length) {
            out.append(char)
            index++
            continue
        }
        when (val next = value[index + 1]) {
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            '\\' -> out.append('\\')
            // An escape this build does not know: keep both characters rather
            // than losing the genre to a malformed older record.
            else -> {
                out.append(char)
                out.append(next)
            }
        }
        index += 2
    }
    return out.toString()
}
