package com.kennyb1201.kbstream.data.sync

/**
 * Kids Mode: a per-profile content ceiling. The user picks one of the three
 * ratings offered in the profile builder — "PG-13 or lower", "PG or lower",
 * "G or lower" — and every surface that loads content for the profile
 * (TMDB discover rails, search results, collections, addon rails, detail)
 * drops titles rated above the ceiling.
 *
 * Ratings map onto a small ordinal scale so "or lower" comparisons are a
 * single integer check. US ratings only (the app is US-region oriented:
 * watch region, certifications, providers all assume US).
 *
 *  null  -> kids mode OFF (no filtering, the default for all profiles)
 *  CEIL_PG13 -> PG-13 or lower
 *  CEIL_PG   -> PG or lower
 *  CEIL_G    -> G or lower
 *
 * The ceiling constants sit ON the movie-rank scale — CEIL_PG13 is literally
 * the rank of the PG-13 movie rating — so "or lower" is a plain
 * `titleRank <= ceiling` check and a rating rank can never outrank the
 * ceiling the way the old age-like values (PG-13 ceiling 14 vs R rank 8)
 * silently allowed adult content through.
 *
 * Known-unknown rule: a title whose certification cannot be resolved is
 * KEPT when the ceiling is PG-13 and DROPPED when it is PG/G — TMDB leaves
 * certifications blank on a long tail of obscure entries, and on the
 * strict ceilings it is safer to hide unknowns than to let an unrated
 * hard-R film through.
 */
object KidsMode {

    /** Ceilings expressed on the movie-rank scale (see class doc). */
    const val CEIL_PG13 = 6
    const val CEIL_PG = 3
    const val CEIL_G = 0

    /**
     * Legacy persisted values from earlier builds. They were documented as
     * "max age" (14/13/8) and synced inside the profiles blob, so devices
     * upgrading still carry them in sync_prefs. [normalize] maps them onto
     * the current ordinals; any other value degrades to the default ceiling.
     */
    private const val LEGACY_PG13 = 14
    private const val LEGACY_PG = 13
    private const val LEGACY_G = 8

    /** Coerce any stored kidsMaxAge onto a legal current ceiling. */
    fun normalize(stored: Int?): Int? = when (stored) {
        null -> null
        CEIL_PG13, LEGACY_PG13 -> CEIL_PG13
        CEIL_PG, LEGACY_PG -> CEIL_PG
        CEIL_G, LEGACY_G -> CEIL_G
        else -> CEIL_PG // unknown junk -> the builder's default ceiling
    }

    /** True when the profile's kids mode restricts anything. */
    fun isRestricted(kidsMaxAge: Int?): Boolean = kidsMaxAge != null

    /**
     * US rating -> rank. Unrecognized / null returns null so callers can
     * apply the known-unknown rule. Movie ratings define the scale (G=0,
     * PG=3, PG-13=6, R=7, NC-17=8); TV ratings interleave BELOW the movie
     * ranks they are age-equivalent to, so each ceiling admits the TV band
     * a parent would expect (G admits TV-Y; PG adds TV-Y7/TV-Y7-FV/TV-G/TV-PG;
     * PG-13 adds nothing TV-side — TV-14 ranks with R and is blocked).
     */
    fun ratingToAge(rating: String?): Int? {
        val clean = rating?.trim()?.uppercase()?.replace(" ", "") ?: return null
        return when (clean) {
            // Movies — G and PG-13 define the ceiling anchors
            "G" -> 0
            "PG" -> 3
            "PG-13" -> 6
            "R" -> 7
            "NC-17" -> 8
            // TV (US content ratings)
            "TV-Y" -> 0
            "TV-Y7" -> 1
            "TV-Y7-FV" -> 2   // fantasy violence — a notch stricter than TV-Y7
            "TV-G" -> 1
            "TV-PG" -> 2
            "TV-14" -> 7      // ≈ R-movie territory for kids purposes
            "TV-MA" -> 8
            else -> null
        }
    }

    /**
     * May this profile watch a title certified [rating]? A null ceiling
     * (kids mode off) always allows; a null rating follows the
     * known-unknown rule from the class doc.
     */
    fun allowed(kidsMaxAge: Int?, rating: String?): Boolean {
        val ceiling = normalize(kidsMaxAge) ?: return true
        val titleAge = ratingToAge(rating)
            ?: return ceiling == CEIL_PG13 // unknown cert: keep only on the loosest ceiling
        return titleAge <= ceiling
    }
}
