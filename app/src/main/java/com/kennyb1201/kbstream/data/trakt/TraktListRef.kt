package com.kennyb1201.kbstream.data.trakt

/**
 * A Trakt user list, as a KB export names it.
 *
 * KB exports a Trakt list source as a URL (`https://trakt.tv/users/alice/lists/
 * my-list`) or as the path after the host; some exports carry only the list's
 * numeric id. Trakt's read endpoints are keyed on (user slug, list id/slug) or
 * on the list id alone, so both shapes are kept rather than normalized away.
 */
data class TraktListRef(
    /** The owner's slug; null for an export that named only the list id. */
    val user: String?,
    /** The Trakt list id or slug. */
    val listId: String
)

/**
 * One resolved list item, with the media type its list column carried.
 *
 * Trakt reads a list one type at a time (movies, then shows), so the type is
 * known at read time and would otherwise be lost when the two halves merge.
 */
data class TraktListEntry(
    val media: TraktMediaEntry,
    val isMovie: Boolean
)

/**
 * Reads a Trakt list reference out of whatever a KB export put in the source.
 *
 * Deliberately tolerant: there is no single KB export version to pin, so a full
 * URL, a path, or a bare id all resolve. Anything that does not look like a
 * list is null, and the caller degrades to an empty rail exactly as it did when
 * the provider was unmapped.
 */
object TraktListRefParser {

    /**
     * @param candidates the source's own addressing fields, most specific first
     *   (`catalogId`, `id`, `addonId`, a numeric `tmdbId`)
     */
    fun fromSource(candidates: List<String?>): TraktListRef? {
        for (candidate in candidates) {
            parse(candidate)?.let { return it }
        }
        return null
    }

    fun parse(raw: String?): TraktListRef? {
        val value = raw
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.substringBefore('#')
            ?.substringBefore('?')
            ?.trimEnd('/')
            ?: return null

        // A bare id (or slug) is the last segment here and needs no owner.
        val segments = value
            .removePrefix("https://")
            .removePrefix("http://")
            .substringAfter("trakt.tv/", value)
            .removePrefix("trakt.tv/")
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
            .map { segment -> segment.removePrefix("@") }

        if (segments.isEmpty()) return null

        val usersAt = segments.indexOfFirst { it.equals("users", ignoreCase = true) }
        val listsAt = segments.indexOfFirst { it.equals("lists", ignoreCase = true) }
        if (usersAt >= 0 && listsAt == usersAt + 2 && segments.size > listsAt + 1) {
            return TraktListRef(
                user = segments[usersAt + 1],
                listId = segments[listsAt + 1]
            )
        }
        // "alice/lists/my-list" without the "users" prefix.
        if (listsAt == 1 && segments.size > 2) {
            return TraktListRef(
                user = segments[0],
                listId = segments[listsAt + 1]
            )
        }
        if (segments.size >= 2) {
            return TraktListRef(
                user = segments[segments.size - 2],
                listId = segments.last()
            )
        }
        return TraktListRef(user = null, listId = segments.first())
    }
}
