package com.kennyb1201.kbstream.data.animethemes

import java.util.Locale

/**
 * AnimeThemes (animethemes.moe): the community archive of anime openings and
 * endings, as the Detail screen's Themes tab uses it.
 *
 * Read-only, keyless, and looked up by MyAnimeList id (see
 * [com.kennyb1201.kbstream.ui.detail.DetailViewModel] and the resolver it
 * shares with the intro-skip chain). GraphQL only: the JSON:API on
 * `api.animethemes.moe` is deprecated and will be removed.
 */
internal const val ANIME_THEMES_GRAPHQL_URL = "https://rs.animethemes.moe/graphql"

/**
 * TMDB's Animation genre id.
 *
 * The whole feature is gated on it: AnimeThemes is keyed by MAL, and finding one
 * from a title costs a Jikan search before the theme query, so the tab is only
 * attempted for a title TMDB calls animation. A few anime without the genre miss
 * the tab - no tab is better than two network calls on every Detail open.
 */
internal const val TMDB_GENRE_ANIMATION = 16

/**
 * One theme of one anime: an OP or an ED, as the tab shows it.
 *
 * One per THEME, never one per version: a theme with three versions is one card
 * (see [AnimeThemeRules.bestVersion]), which is why [version] is the chosen
 * entry's and the rest are dropped here.
 */
internal data class AnimeTheme(
    /** AnimeThemes' own slug for the theme - unique within the anime. */
    val slug: String,
    /** `OP` or `ED`. */
    val type: String,
    /** The theme's own ordering within its type (OP1, OP2...). */
    val sequence: Int,
    /** The song, when AnimeThemes names one. */
    val songTitle: String?,
    /** Who performs it. */
    val artists: List<String>,
    /** The chosen entry's version, or null when AnimeThemes names none. */
    val version: Int?,
    /** The chosen entry's episode range, e.g. `1-13`. */
    val episodes: String?,
    /**
     * The video this card plays: a direct progressive WebM URL, or null when no
     * usable video could be picked (the card is drawn unplayable, exactly as an
     * unplayable trailer card is).
     */
    val videoUrl: String?
)

/** One version of a theme, as AnimeThemes lists it. */
internal data class AnimeThemeEntry(
    val version: Int?,
    val episodes: String?,
    val videos: List<AnimeThemeVideo>
)

/** One video file of one entry. */
internal data class AnimeThemeVideo(
    /** The direct stream URL - the field that is played. */
    val link: String,
    /** Frame height, when the source reports one. */
    val resolution: Int?,
    /** WEB / RAW / BD / DVD / VHS / LD, when the source reports one. */
    val source: String?,
    /** Creditless. */
    val nc: Boolean
)

/**
 * The Themes tab's rules: the label a card wears, the line under it, the order
 * the cards are drawn in, and which version and video a theme plays.
 *
 * Pure and separate from the screen for the same reason as [DetailMediaRow]:
 * what uses it is a card and a press, and neither can be asserted without a
 * device.
 */
internal object AnimeThemeRules {

    /** Whether [genreIds] says this title is animation (see [TMDB_GENRE_ANIMATION]). */
    fun isAnime(genreIds: Collection<Int>): Boolean = genreIds.contains(TMDB_GENRE_ANIMATION)

    /**
     * The card's badge: the theme's type and sequence (`OP1`, `ED2`), with the
     * version appended when the chosen entry is above the first (`ED1v2`).
     *
     * A version of 1 or no version at all reads as the plain label: "v1" on one
     * card and nothing on its neighbour would suggest a difference the viewer
     * is not being offered.
     */
    fun badge(type: String, sequence: Int, version: Int?): String {
        val base = type.trim().uppercase(Locale.US) + sequence
        val chosen = version ?: return base
        return if (chosen > 1) "$base" + "v" + chosen else base
    }

    /** The card's title: the song, or the word the viewer should see instead. */
    fun title(songTitle: String?): String =
        songTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "Theme"

    /**
     * The card's second line: who performs the theme, and which episodes it
     * covers.
     *
     * `"Artist · Eps 1–13"`, `"Artist"`, `"Eps 1–13"` or null when AnimeThemes
     * names neither. The episode range is printed with an en dash because it is
     * a range, not two numbers joined by a hyphen; a range that is not a range
     * (`1,3,5`) is left exactly as the source spells it.
     */
    fun subtitle(artists: List<String>, episodes: String?): String? {
        val artistLine = artists
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }
        val episodeLine = episodes
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { range -> "Eps " + range.replace('-', '\u2013') }

        return listOfNotNull(artistLine, episodeLine)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" \u00b7 ")
    }

    /**
     * The themes in the order the tab draws them: OPs first, then EDs, each by
     * sequence.
     *
     * The type order is AnimeThemes' own (OP then ED) and is deliberately not
     * alphabetical - ED would come first - because a viewer opening this tab is
     * looking for the opening first. A stable sort, so two themes that claim the
     * same sequence keep the order the source listed them in.
     */
    fun ordered(themes: List<AnimeTheme>): List<AnimeTheme> =
        themes.sortedWith(
            compareBy(
                { theme -> if (theme.type.trim().uppercase(Locale.US) == "OP") 0 else 1 },
                { theme -> theme.sequence }
            )
        )

    /**
     * The newest version of a theme: highest [AnimeThemeEntry.version].
     *
     * An entry with no version counts as 1 - AnimeThemes omits `version` on the
     * first one - so a `v2` always beats it and two versionless entries tie
     * (first wins).
     */
    fun bestVersion(entries: List<AnimeThemeEntry>): AnimeThemeEntry? =
        entries.maxByOrNull { entry -> entry.version ?: 1 }

    /**
     * The video a theme plays, from its chosen entry.
     *
     * In order:
     *  1. CREDITLESS first (`nc`). The clean version is the reason this archive
     *     is worth having; a credited copy is the fallback, not the point.
     *  2. Highest resolution, CAPPED at 1080. The app's trailer cap is 1080 (see
     *     `TrailerPick`), and a 4K theme buys nothing on a card-sized surface
     *     while costing several times the bandwidth - which is why above the cap
     *     the SMALLER file wins a tie, rather than the source's biggest one.
     *  3. Source: BD, then WEB, then DVD, then anything else - the order of
     *     fidelity, with an unknown source last.
     *
     * Null when the entry has no video with a link: the card then draws
     * unplayable rather than pretending to play.
     */
    fun bestVideo(videos: List<AnimeThemeVideo>): AnimeThemeVideo? {
        val usable = videos.filter { it.link.isNotBlank() }
        if (usable.isEmpty()) return null

        return usable.sortedWith(
            compareBy<AnimeThemeVideo> { video -> if (video.nc) 0 else 1 }
                .thenByDescending { video -> (video.resolution ?: 0).coerceAtMost(1080) }
                // At the cap the smaller actual file wins: a 2160 and a 1080
                // creditless BD file are the same picture on this strip, and one
                // of them is four times the bytes.
                .thenBy { video -> video.resolution ?: 0 }
                .thenBy { video -> sourceRank(video.source) }
        ).first()
    }

    /** BD > WEB > DVD > anything else. */
    private fun sourceRank(source: String?): Int = when (source?.trim()?.uppercase(Locale.US)) {
        "BD" -> 0
        "WEB" -> 1
        "DVD" -> 2
        else -> 3
    }

    /**
     * Builds the tab's themes from what one anime's entries say.
     *
     * One theme per (type, sequence): the versions are collapsed onto the newest
     * one (see [bestVersion]), and its best video becomes the theme's URL. Kept
     * here, rather than in the reader, so the choice can be asserted without a
     * JSON body.
     */
    fun themesOf(source: List<RawTheme>): List<AnimeTheme> =
        source.map { raw ->
            val entry = bestVersion(raw.entries)
            AnimeTheme(
                slug = raw.slug,
                type = raw.type,
                sequence = raw.sequence,
                songTitle = raw.songTitle,
                artists = raw.artists,
                version = entry?.version,
                episodes = entry?.episodes,
                videoUrl = entry?.let { chosen -> bestVideo(chosen.videos)?.link }
            )
        }.let { themes -> ordered(themes) }

    /** A theme exactly as the service listed it, before the rules pick. */
    internal data class RawTheme(
        val slug: String,
        val type: String,
        val sequence: Int,
        val songTitle: String?,
        val artists: List<String>,
        val entries: List<AnimeThemeEntry>
    )
}
