package com.kennyb1201.kbstream.data.library

/**
 * The library's copy of a poster URL, with any burned-in promo badge taken off.
 *
 * The pinned "Top Today" rails ask their add-on for RANKED, TAGGED artwork, and
 * the add-on bakes both into the image rather than laying them over it:
 *
 * ```
 * .../poster/1101383.png?type=movie&tag=just_added&rank=5&lang=en&logos=0
 * ```
 *
 * That is exactly right for a rail, where the position and the "just added"
 * strip are the point of the row. It is wrong the moment the title is saved:
 * the library shows the poster wherever the user put it, so a burned-in
 * `rank=5` is wrong the next day, and a `just_added` strip is wrong forever
 * after. Both are query parameters, and the add-on serves the plain poster for
 * the asking — its own untagged entries are `tag=none&rank=none` — so the
 * library simply asks for that instead.
 *
 * Only a URL carrying `rank=` is touched. That parameter is the marker of this
 * family of promo posters; anything else (a TMDB image, an add-on using `tag`
 * for something of its own) is returned exactly as it arrived.
 */
internal fun cleanLibraryPoster(posterUrl: String?): String? {
    val url = posterUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    // The fragment is excluded from the query rewrite and put back afterwards,
    // so a URL that carries one cannot lose it.
    val fragment = url.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
    val head = url.substringBefore('#')
    val query = head.substringAfter('?', "")
    if (!hasRankParam(query)) return url

    val rewritten = query.split('&').joinToString("&") { param ->
        val name = param.substringBefore('=')
        when {
            name.equals(RANK_PARAM, ignoreCase = true) -> "$name=$NONE"
            name.equals(TAG_PARAM, ignoreCase = true) -> "$name=$NONE"
            else -> param
        }
    }
    return "${head.substringBefore('?')}?$rewritten$fragment"
}

private const val RANK_PARAM = "rank"
private const val TAG_PARAM = "tag"
private const val NONE = "none"

private val RANK_IN_QUERY = Regex("""(^|&)rank=""", RegexOption.IGNORE_CASE)

/** True when the query string carries a real `rank=` parameter. */
private fun hasRankParam(query: String): Boolean =
    query.isNotEmpty() && RANK_IN_QUERY.containsMatchIn(query)
