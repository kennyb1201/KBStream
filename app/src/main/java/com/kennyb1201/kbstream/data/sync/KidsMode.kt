package com.kennyb1201.kbstream.data.sync

/**
 * Kids Mode: a per-profile content ceiling. The user picks one of the three
 * ratings offered in the profile builder — "PG-13 or lower", "PG or lower",
 * "G or lower" — and every surface that loads content for the profile
 * (TMDB discover rails, search results, collections, addon rails, detail)
 * drops titles rated above the ceiling.
 *
 * Ratings are mapped onto a small age scale so "or lower" comparisons are
 * a single integer check. US ratings only (the app is US-region oriented:
 * watch region, certifications, providers all assume US).
 *
 *  null  -> kids mode OFF (no filtering, the default for all profiles)
 *  14    -> PG-13 or lower
 *  13    -> PG or lower
 *  8     -> G or lower
 *
 * Known-unknown rule: a title whose certification cannot be resolved is
 * KEPT when the ceiling is PG-13 and DROPPED when it is PG/G — TMDB leaves
 * certifications blank on a long tail of obscure entries, and on the
 * strict ceilings it is safer to hide unknowns than to let an unrated
 * hard-R film through. TV is stricter than movies at the same age anyway
 * (TV-14 ≈ PG-13-ish), so the TV table shifts each rating one step up.
 */
object KidsMode {

    /** Max "age" for each ceiling, matching [Profile.kidsMaxAge]. */
    const val MAX_AGE_PG13 = 14
    const val MAX_AGE_PG = 13
    const val MAX_AGE_G = 8

    /** True when the profile's kids mode restricts anything. */
    fun isRestricted(kidsMaxAge: Int?): Boolean = kidsMaxAge != null

    /**
     * US rating -> internal age rank. Unrecognized / null returns null so
     * callers can apply the known-unknown rule. TV ratings map one step
     * stricter than their movie cousins at the same ceiling (TV-14 hides
     * under a PG-13 ceiling; TV-G stays under PG).
     */
    fun ratingToAge(rating: String?): Int? {
        val clean = rating?.trim()?.uppercase()?.replace(" ", "") ?: return null
        return when (clean) {
            // Movies
            "G" -> 0
            "PG" -> 3
            "PG-13" -> 6
            "R" -> 8
            "NC-17" -> 10
            // TV (US content ratings)
            "TV-Y" -> 1
            "TV-Y7" -> 2
            "TV-Y7-FV" -> 3   // fantasy violence — still preschool-adjacent
            "TV-G" -> 4
            "TV-PG" -> 5
            "TV-14" -> 7
            "TV-MA" -> 9
            else -> null
        }
    }

    /**
     * May this profile watch a title certified [rating]? A null ceiling
     * (kids mode off) always allows; a null rating follows the
     * known-unknown rule from the class doc.
     */
    fun allowed(kidsMaxAge: Int?, rating: String?): Boolean {
        val ceiling = kidsMaxAge ?: return true
        val titleAge = ratingToAge(rating)
            ?: return ceiling == MAX_AGE_PG13 // unknown cert: keep only on the loosest ceiling
        return titleAge <= ceiling
    }
}
