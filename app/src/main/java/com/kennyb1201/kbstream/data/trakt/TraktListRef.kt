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
    val isMovie: Boolean,
    /**
     * The item's position in the list, exactly as Trakt sent it. Carried so the
     * two halves can be merged back into ONE order: the read is per media type,
     * so merging movies then shows without it rendered every curated mixed list
     * as "all films, then all shows", whatever order its owner arranged.
     */
    val rank: Int? = null
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
        // Trakt's OWN canonical public-list URL: `trakt.tv/lists/12345`. It
        // names no owner - that is the whole shape of it - and the catch-all
        // below would read the literal segment "lists" as the owner's username
        // and ask for `users/lists/12345`, which Trakt answers with a 404 ("no
        // record found"). An ownerless ref needs no owner when it is read
        // either: the halves fall back to the id-only endpoint (see
        // TraktRepository.readListHalf), so this is the whole fix for the
        // public-list 404 - the parser, not a new read path.
        if (listsAt == 0 && segments.size == 2) {
            return TraktListRef(user = null, listId = segments[1])
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

/**
 * The reads a PINNED Trakt list needs, in the order they should be tried.
 *
 * A Home rail pin (data.library's `LibraryHomeRailPin`) stores the id its
 * Library row had - the row's remoteId - and for a Trakt list that id has two
 * shapes whose reads are different endpoints:
 *
 *  - a **numeric** id is a PUBLIC list's own Trakt id - the number Trakt's
 *    canonical `trakt.tv/lists/12345` address carries, and what a liked
 *    public-list row's ref holds. The endpoint that answers it takes no owner
 *    (`GET /lists/{id}/items/{type}`), so it is read ownerless;
 *  - everything else is an own list's remoteId, a slug, which is only readable
 *    under the account: `GET users/me/lists/{slug}/items/{type}`.
 *
 * Reading a public pin as `users/me/lists/{numeric}` is a 404, not an error the
 * callers can see: [TraktRepository.listItems] answers an empty list, so the
 * rail loaded nothing and Home drew no rail at all.
 *
 * The second entry exists for the pin whose shape points the wrong way - an
 * older numeric pin for a PRIVATE own list, which Trakt only serves under the
 * account. It is tried only when the first read came back empty, so the two
 * common cases still cost one call, and a list is one rail whichever read found
 * it.
 */
fun traktListReadsForPinnedId(listId: String): List<TraktListRef> {
    val id = listId.trim()
    if (id.isEmpty()) return emptyList()
    val ownerless = TraktListRef(user = null, listId = id)
    val own = TraktListRef(user = "me", listId = id)
    return if (id.all { it in '0'..'9' }) listOf(ownerless, own) else listOf(own)
}
