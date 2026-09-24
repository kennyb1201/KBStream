package com.kennyb1201.kbstream.data.player

import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.list

/**
 * Is this title anime? The question [PlayerEngine]'s "Play anime in MPV"
 * setting asks before a launch.
 *
 * Deliberately metadata-only and cheap: a catalog type the app already
 * carries, and the TMDB detail it already has cached for anything that
 * reaches playback. No extra endpoint, no genre guessing off the title.
 *
 * The signals, in the order [isAnime] checks them:
 *
 *  1. The catalog type. Stremio-style anime catalogs emit `anime` (and the
 *     `anime.series` / `anime.movie` variants), and the app already treats
 *     those as series/movie for every other purpose - a title that came out
 *     of one is anime by definition, whatever TMDB thinks of it.
 *  2. TMDB's own `anime` keyword. Release groups and TMDB users tag with it,
 *     and unlike genre+language it also covers a title whose original
 *     language TMDB records as something else.
 *  3. Animation (TMDB genre 16) with a Japanese original language. This is
 *     the one that catches the ordinary case: a series opened from a normal
 *     rail or from Continue Watching, whose catalog type is plain `series`.
 *
 * Animation in any other original language (US, French, Korean, Chinese
 * cartoons) is NOT anime here: those are encoded and subtitled like any other
 * Western release, which is the whole reason the MPV rule exists. A Chinese
 * donghua or Korean animation does qualify when TMDB tags it with the `anime`
 * keyword (signal 2).
 */
object AnimeDetect {

    /** TMDB's Animation genre. */
    const val ANIMATION_GENRE_ID = 16

    /** Original languages whose animation is anime by signal 3. */
    private val ANIME_LANGUAGES = setOf("ja")

    /** The keyword that marks anime on TMDB (and on anime addon catalogs). */
    private const val ANIME_KEYWORD = "anime"

    /**
     * A play request's episode suffix: ids arrive as "id:season:episode"
     * (see the episode streamIds every screen builds), and the detail cache is
     * keyed by the bare show id.
     */
    private val EPISODE_SUFFIX = Regex(":\\d+:\\d+$")

    /**
     * The show/movie id behind a play request id: "tt123:4:2" -> "tt123",
     * "tmdb:1234:4:2" -> "tmdb:1234", a movie id unchanged.
     */
    fun titleIdOf(streamId: String): String =
        streamId.trim().replace(EPISODE_SUFFIX, "")

    /** True when a catalog/addon type is one of the anime flavors. */
    fun isAnimeType(parentType: String?): Boolean {
        val type = parentType?.trim()?.lowercase() ?: return false
        return type == "anime" || type.startsWith("anime.")
    }

    /**
     * The rule itself: true when the title is anime. [keywordNames] defaults to
     * empty so a caller that only has genre and language (a MetaPreview, a
     * catalog entry) can still ask.
     */
    fun isAnime(
        parentType: String?,
        genreIds: Collection<Int>,
        originalLanguage: String?,
        keywordNames: Collection<String> = emptyList()
    ): Boolean {
        if (isAnimeType(parentType)) return true

        if (
            keywordNames.any { keyword ->
                keyword.trim().equals(ANIME_KEYWORD, ignoreCase = true)
            }
        ) {
            return true
        }

        val language = originalLanguage?.trim()?.lowercase()
        return ANIMATION_GENRE_ID in genreIds &&
            language != null &&
            language in ANIME_LANGUAGES
    }

    /**
     * [isAnime] fed from the TMDB detail of the title about to play.
     *
     * The lookup is [TmdbRepository.fetchEnrichedMetaCached], i.e. the same
     * cached detail every other screen already resolves: a hit in memory (12h)
     * or on disk (30 days) in the normal case, one request only for a launch
     * that never resolved the title's metadata anywhere else. Callers gate this
     * behind the setting, so a profile that does not want the MPV rule never
     * pays the lookup at all.
     *
     * Fails safe: an unresolvable or unreachable detail is NOT anime, which
     * leaves the launch on the engine the user picked.
     */
    suspend fun isAnimeForLaunch(
        repository: TmdbRepository,
        parentId: String,
        parentType: String
    ): Boolean {
        if (isAnimeType(parentType)) return true

        val detail = try {
            repository.fetchEnrichedMetaCached(
                imdbId = parentId,
                type = parentType
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return false

        return isAnime(
            parentType = parentType,
            genreIds = detail.genres.map { genre -> genre.id },
            originalLanguage = detail.originalLanguage,
            keywordNames = detail.keywords.list().map { keyword -> keyword.name }
        )
    }
}
