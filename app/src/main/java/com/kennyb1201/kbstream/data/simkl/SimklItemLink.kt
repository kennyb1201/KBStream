package com.kennyb1201.kbstream.data.simkl

/** Simkl's own site, for the one credit line that has no single item to point at. */
internal const val SIMKL_HOME_URL = "https://simkl.com"

/**
 * The Simkl credit line Simkl's API rules name as an accepted placement: "a
 * one-time Movie, TV and anime data from Simkl line" (their footer/About
 * example). It is deliberately a sentence about the DATA rather than about the
 * account, because the neighbouring rows of that pane are about the app.
 */
internal const val SIMKL_DATA_CREDIT = "Movie, TV and anime data from Simkl"

/** A Simkl slug is lower-case words joined by single dashes ("breaking-bad-2008"). */
private val SIMKL_SLUG = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

/**
 * The Simkl page for one title, or null when there is no Simkl id to point at.
 *
 * Simkl's API rules are explicit that attribution is a PER-ITEM link: "Wherever
 * Simkl data appears in your app, link back to the Simkl page for that specific
 * item - not just a generic homepage link", and a link to simkl.com with no
 * item on it "does not count as attribution". The shapes they publish are
 * `/movies/:simkl_id/:slug`, `/tv/:simkl_id/:slug` and `/anime/:simkl_id/:slug`,
 * and they ask for the slug "when you have it" - a bare numeric URL still
 * resolves, so a missing or odd-looking slug drops the tail rather than
 * inventing one.
 *
 * [mediaType] is the app's own type ("movie" / "series"), which is all the
 * metadata layer carries: the app has no anime flag, so a series always points
 * at the `/tv/` shape. The id is what makes the link correct - it belongs to one
 * Simkl record - and the slug only helps the URL read.
 */
internal fun simklItemUrl(mediaType: String?, simklId: Int?, slug: String? = null): String? {
    val id = simklId?.takeIf { it > 0 } ?: return null
    val section = when (mediaType?.trim()?.lowercase()) {
        "movie", "movies", "film" -> "movies"
        else -> "tv"
    }
    val cleanSlug = slug?.trim()?.lowercase()?.takeIf { SIMKL_SLUG.matches(it) }
    return buildString {
        append(SIMKL_HOME_URL).append('/').append(section).append('/').append(id)
        if (cleanSlug != null) append('/').append(cleanSlug)
    }
}
