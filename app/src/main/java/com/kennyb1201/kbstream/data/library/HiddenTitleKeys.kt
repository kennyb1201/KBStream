package com.kennyb1201.kbstream.data.library

/**
 * The identity half of [HiddenTitles]: how one title's hide is keyed, and how a
 * surface's identity is matched against the stored keys.
 *
 * Split out because it is the only part of hiding that is pure - no prefs, no
 * profile, no Android - and it is the part a match MISS lives in. The field
 * case (2026-11-05, PAW Patrol hidden everywhere except Continue Watching) was
 * not a broken filter and not a broken sync: the same key set dropped the title
 * from the rails and kept its CW card, so the defect was in the identity the CW
 * filter handed this matcher. A rule that decides what a viewer sees has to be
 * exercisable without a device, which is why the key math lives here and
 * [HiddenTitles] delegates to it (see `CwHiddenMatchTest`).
 *
 * Nothing here reads or writes the store; [HiddenTitles] still owns the entries,
 * the profile scope and the sync merge.
 */
internal object HiddenTitleKeys {

    /** Prefix of a derived name key (never stored, only matched). */
    const val TITLE_KEY_PREFIX = "title::"

    /** Stands in for a year the hiding surface did not know. */
    const val ANY_YEAR = "*"

    /**
     * The two media types the app filters on. Everything a catalog calls a
     * series — "tv", "show", "anime" — is one type here, so a show hidden from
     * an anime rail is hidden from the TMDB series rails too.
     */
    fun normalizedType(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "tv", "series", "show", "anime", "anime.series" -> "series"
        else -> "movie"
    }

    /**
     * One canonical spelling of an id: "tmdb:603" for anything numeric (the
     * app writes TMDB ids as bare ints, "tmdb:603" and "tmdb_603"), and the id
     * itself otherwise — an add-on's "tt0133093" is already canonical.
     */
    fun normalizeId(raw: String?): String? {
        val id = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val bare = id.removePrefix("tmdb:").removePrefix("tmdb_")
        return if (bare.isNotEmpty() && bare.all { it.isDigit() }) "tmdb:$bare" else id
    }

    /**
     * The identity key for one id on one surface.
     *
     * A TMDB id is a NUMBER that movies and shows share (movie 1399 and series
     * 1399 are different titles), so it keeps its media type. Everything else
     * — an add-on's "tt0133093", a KB collection id — is already unique, so it
     * is keyed WITHOUT the type: the same film arrives as "movie" from one
     * catalog and "series" from a badly-typed one, and hiding it must not
     * depend on which spelling the catalog happened to use.
     */
    fun keyFor(mediaType: String?, id: String?): String? {
        val normalized = normalizeId(id) ?: return null
        return if (normalized.startsWith("tmdb:")) {
            "${normalizedType(mediaType)}::$normalized"
        } else {
            "id::$normalized"
        }
    }

    /** Every identity spelling for one title, given the ids a surface knows. */
    fun keysFor(mediaType: String?, ids: List<String?>): Set<String> =
        ids.mapNotNull { keyFor(mediaType, it) }.toCollection(LinkedHashSet())

    /**
     * True when this title is hidden - by an id spelling it carries, or by its
     * name.
     *
     * The id check is the precise one and covers the common case. The name
     * check exists because the two id namespaces are not bridged offline: a
     * title hidden from a TMDB rail ("movie::tmdb:603") and the same film in an
     * add-on rail ("id::tt0133093") cannot be matched through ids without a
     * TMDB lookup, which is exactly how a hidden title came back in add-on
     * search. [title] and [year] close that gap; [year] keeps the match from
     * catching a different title that shares the name (it is only skipped when
     * a surface truly has no year to give).
     */
    fun hides(
        hidden: Set<String>,
        mediaType: String?,
        title: String?,
        year: Int?,
        vararg ids: String?
    ): Boolean {
        if (hidden.isEmpty()) return false

        if (ids.any { keyFor(mediaType, it)?.let { key -> key in hidden } == true }) {
            return true
        }

        val name = normalizeTitle(title) ?: return false
        val prefix = "$TITLE_KEY_PREFIX${normalizedType(mediaType)}::$name::"
        return when {
            // A year on the surface: match that year, or a hidden entry that
            // never recorded one (hidden from a surface that had no year).
            year != null ->
                ("$prefix$year") in hidden || ("$prefix$ANY_YEAR") in hidden

            // No year to compare: any entry with this name and type matches.
            // Permissive on purpose - the user explicitly removed the title.
            else -> hidden.any { it.startsWith(prefix) }
        }
    }

    /**
     * The name key one entry is stored and matched under. Alphanumerics only,
     * lowercased, so "The  Office" and "The Office" agree. The year is "*"
     * when the hiding surface had none.
     */
    fun titleKey(mediaType: String?, title: String?, year: Int?): String? {
        val name = normalizeTitle(title) ?: return null
        return "$TITLE_KEY_PREFIX${normalizedType(mediaType)}::$name::${year ?: ANY_YEAR}"
    }

    /** Lowercased, alphanumerics only; null when nothing usable is left. */
    fun normalizeTitle(raw: String?): String? =
        raw?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }
}
