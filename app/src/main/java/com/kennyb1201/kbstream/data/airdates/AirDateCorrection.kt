package com.kennyb1201.kbstream.data.airdates

import java.time.LocalDate

/**
 * A show's air dates from the second source, in the shape the UI needs.
 *
 * [episodeDates] is keyed `"season:episode"` (see [AirDateCorrection.episodeKey])
 * and holds `yyyy-MM-dd` strings, matching how TMDB dates travel through
 * [com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode.airDate].
 */
data class AirDateCorrections(
    val episodeDates: Map<String, String> = emptyMap(),
    val seasonPremieres: Map<Int, LocalDate> = emptyMap()
) {

    val isEmpty: Boolean
        get() = episodeDates.isEmpty() && seasonPremieres.isEmpty()

    companion object {

        val NONE = AirDateCorrections()
    }
}

/**
 * Reconciles TMDB's air dates with a second metadata source.
 *
 * Why this exists: TMDB is volunteer-edited and its season/episode dates lag
 * reality often enough to be visible. American Horror Story "13" is the case
 * that prompted this - TMDB lists the season and its first three episodes as
 * premiering 2026-10-01 while the season had actually started on 2026-09-24.
 * Every date the Detail screen shows comes from TMDB, so a date that is
 * *wrong in the future* is not a cosmetic slip: the season chip dims, the
 * episode list is replaced by "airs soon", and each episode that does render
 * is badged UNAVAILABLE - all for a season the user can already watch.
 *
 * The rule is deliberately one-directional and conservative: the second source
 * only overrides TMDB when TMDB still claims a date **hasn't arrived yet** and
 * the other source says it already has. That is the only case where TMDB is
 * provably stale about something the user can see. A date that differs in the
 * future (a premiere pushed back, a schedule shift) is left to TMDB, which is
 * the primary catalog and knows about its own changes - so a genuinely
 * unreleased season such as Silo S4 keeps its "coming soon" state.
 *
 * Everything here is pure so the rule can be tested without a repository.
 */
object AirDateCorrection {

    /** Key for [AirDateCorrections.episodeDates]. */
    fun episodeKey(season: Int, episode: Int): String = "$season:$episode"

    /** Parses a `yyyy-MM-dd` date, returning null for blank or malformed input. */
    fun parse(raw: String?): LocalDate? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching { LocalDate.parse(trimmed) }.getOrNull()
    }

    /**
     * TMDB's date for one episode, corrected by [secondary] when the rule
     * above applies. Returns [primary] unchanged in every other case, so a
     * missing or unusable secondary value can never change what the UI shows.
     */
    fun correctAirDate(
        primary: String?,
        secondary: String?,
        today: LocalDate
    ): String? {
        val secondaryText = secondary?.trim().orEmpty()
        val secondaryDate = parse(secondaryText) ?: return primary

        val primaryText = primary?.trim().orEmpty()
        if (primaryText.isEmpty()) return secondaryText

        val primaryDate = parse(primaryText) ?: return secondaryText

        return if (primaryDate.isAfter(today) && !secondaryDate.isAfter(today)) {
            secondaryText
        } else {
            primary
        }
    }

    /**
     * The same rule applied to whole seasons: a season TMDB dates in the future
     * whose other source's earliest episode has already aired takes that earlier
     * date.
     *
     * Seasons only the second source knows about are ignored - this source must
     * not invent a season chip (or a premiere date) for a show TMDB reports
     * differently.
     */
    fun correctPremieres(
        primary: Map<Int, LocalDate>,
        secondary: Map<Int, LocalDate>,
        today: LocalDate
    ): Map<Int, LocalDate> {
        if (secondary.isEmpty()) return primary

        val merged = LinkedHashMap(primary)
        var changed = false
        for ((season, secondaryDate) in secondary) {
            val primaryDate = primary[season] ?: continue
            if (!primaryDate.isAfter(today) || secondaryDate.isAfter(today)) continue
            merged[season] = secondaryDate
            changed = true
        }
        return if (changed) merged else primary
    }

    /** One episode still to air, as resolved by [nextAiring]. */
    data class Airing(
        val season: Int,
        val episode: Int,
        /** The source's `yyyy-MM-dd` text, ready to drive the UI's date line. */
        val airDate: String
    )

    /**
     * The earliest episode on/after [today] in [episodeDates] - a show's real
     * "next episode to air" once TMDB's own pointer has gone stale (TMDB can
     * still be naming an episode that has already aired).
     *
     * Several episodes can share one date (a same-night premiere block), so
     * ties go to the lowest season/episode. Null when nothing is left to air,
     * which is the caller's cue that the show has no upcoming card at all.
     */
    fun nextAiring(
        episodeDates: Map<String, String>,
        today: LocalDate
    ): Airing? {
        var best: Airing? = null
        var bestDate: LocalDate? = null

        for ((key, raw) in episodeDates) {
            val date = parse(raw) ?: continue
            if (date.isBefore(today)) continue
            val season = key.substringBefore(':').toIntOrNull() ?: continue
            val episode = key.substringAfter(':').toIntOrNull() ?: continue

            val current = best
            val currentDate = bestDate
            if (current != null && currentDate != null) {
                val laterDay = date.isAfter(currentDate)
                val sameDayLaterEpisode =
                    date == currentDate &&
                        (season > current.season ||
                            (season == current.season &&
                                episode >= current.episode))
                if (laterDay || sameDayLaterEpisode) continue
            }

            best = Airing(season, episode, raw)
            bestDate = date
        }

        return best
    }

    /**
     * Each season's earliest known air date, folded out of [episodeDates].
     * A premiere is by definition the first episode that airs, so the minimum
     * is the right summary - and it comes for free with the episode list.
     */
    fun seasonPremieresFrom(episodeDates: Map<String, String>): Map<Int, LocalDate> {
        if (episodeDates.isEmpty()) return emptyMap()

        val premieres = HashMap<Int, LocalDate>()
        for ((key, raw) in episodeDates) {
            val season = key.substringBefore(':').toIntOrNull() ?: continue
            val date = parse(raw) ?: continue
            val existing = premieres[season]
            if (existing == null || date.isBefore(existing)) premieres[season] = date
        }
        return premieres
    }
}
