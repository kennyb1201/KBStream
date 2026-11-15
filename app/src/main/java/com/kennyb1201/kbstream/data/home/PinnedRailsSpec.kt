package com.kennyb1201.kbstream.data.home

/**
 * The pinned "Top ... Today" rails' feed, in one place (spec 2026-11-15, Home
 * cold start).
 *
 * These lived in HomeViewModel's companion, which was fine while the only reader
 * was the rail build. The launch warm reads them too, and the whole point of the
 * warm is that it asks for EXACTLY what the build will ask for - a second copy of
 * the URL, or of the catalog ids, would drift into a warm that fetches something
 * the build never reads. So they are stated once, here, and both sides read them.
 *
 * A top-level object rather than a companion, because the warm runs from the
 * Application before any ViewModel exists.
 */
object PinnedRailsSpec {

    /** The feed's manifest: one base URL for both Top Today catalogs. */
    const val MANIFEST_URL: String =
        "https://toptoday.llamayu.com/landscapeTags=true|landscapeLogos=true|landscapeRanked=false|portraitTags=true|portraitLogos=false|portraitRanked=true|posterLang=en|digitalOnly=true|listLang=en/manifest.json"

    /** The add-on name the pinned rows are published under. */
    const val ADDON_NAME: String = "TMDB Top Today"

    /** The feed's base URL (the manifest URL without its document). */
    val baseUrl: String
        get() = MANIFEST_URL.substringBefore("/manifest.json").removeSuffix("/")

    /** The two catalogs the rows are built from, in the order they are drawn. */
    val catalogs: List<Triple<String, String, String>> = listOf(
        Triple("top_movies_today", "movie", "Top Movies Today"),
        Triple("top_shows_today", "series", "Top Shows Today")
    )
}
