package com.kennyb1201.kbstream.data.tmdb

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TmdbBasic(
    val id: Int,
    val title: String? = null,
    val name: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbFindResponse(
    @Json(name = "movie_results") val movieResults: List<TmdbBasic> = emptyList(),
    @Json(name = "tv_results") val tvResults: List<TmdbBasic> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbCastMember(
    val id: Int,
    val name: String,
    val character: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCrewMember(
    val id: Int,
    val name: String,
    val job: String? = null,
    val department: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCredits(
    val cast: List<TmdbCastMember> = emptyList(),
    val crew: List<TmdbCrewMember> = emptyList()
)

fun TmdbCredits?.director(): TmdbCrewMember? = this?.crew?.firstOrNull { it.job == "Director" }

fun TmdbCredits?.writers(): List<TmdbCrewMember> =
    this?.crew.orEmpty().filter { it.department == "Writing" }

@JsonClass(generateAdapter = true)
data class TmdbVideo(
    val key: String,
    val site: String,
    val type: String,
    val name: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbVideos(
    val results: List<TmdbVideo> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbProductionCompany(
    val id: Int,
    val name: String,
    @Json(name = "logo_path") val logoPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbNetwork(
    val id: Int,
    val name: String,
    @Json(name = "logo_path") val logoPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbRecommendationItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbRecommendations(
    val results: List<TmdbRecommendationItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbSeasonSummary(
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String? = null,
    @Json(name = "episode_count") val episodeCount: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbAlternativeTitleItem(
    val title: String? = null,
    @Json(name = "iso_3166_1") val iso31661: String? = null
)

// Movie alternative_titles uses key "titles"; TV alternative_titles uses key "results".
// Both are modeled here so a single append_to_response call works for either media type.
@JsonClass(generateAdapter = true)
data class TmdbAlternativeTitles(
    val titles: List<TmdbAlternativeTitleItem> = emptyList(),
    val results: List<TmdbAlternativeTitleItem> = emptyList()
)

fun TmdbAlternativeTitles?.allTitles(): List<TmdbAlternativeTitleItem> =
    (this?.titles.orEmpty() + this?.results.orEmpty()).distinctBy { it.title }

@JsonClass(generateAdapter = true)
data class TmdbReleaseDateEntry(
    val certification: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbReleaseDatesCountry(
    @Json(name = "iso_3166_1") val iso31661: String,
    @Json(name = "release_dates") val releaseDates: List<TmdbReleaseDateEntry> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbReleaseDatesResponse(
    val results: List<TmdbReleaseDatesCountry> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbContentRatingEntry(
    @Json(name = "iso_3166_1") val iso31661: String,
    val rating: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbContentRatingsResponse(
    val results: List<TmdbContentRatingEntry> = emptyList()
)

// Resolves a US certification/content rating from either movie release_dates
// or TV content_ratings, falling back to the first non-blank value found
// for any country if US isn't present.
fun TmdbDetail.certification(isMovie: Boolean): String? {
    return if (isMovie) {
        val results = releaseDates?.results.orEmpty()
        val us = results.firstOrNull { it.iso31661 == "US" }
        val fromUs = us?.releaseDates?.firstOrNull { !it.certification.isNullOrBlank() }?.certification
        fromUs ?: results.flatMap { it.releaseDates }.firstOrNull { !it.certification.isNullOrBlank() }?.certification
    } else {
        val results = contentRatings?.results.orEmpty()
        val us = results.firstOrNull { it.iso31661 == "US" }?.rating
        us ?: results.firstOrNull { !it.rating.isNullOrBlank() }?.rating
    }
}

@JsonClass(generateAdapter = true)
data class TmdbDetail(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "production_companies") val productionCompanies: List<TmdbProductionCompany> = emptyList(),
    val networks: List<TmdbNetwork> = emptyList(),
    val seasons: List<TmdbSeasonSummary> = emptyList(),
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    val recommendations: TmdbRecommendations? = null,
    @Json(name = "belongs_to_collection") val belongsToCollection: TmdbCollectionRef? = null,
    @Json(name = "alternative_titles") val alternativeTitles: TmdbAlternativeTitles? = null,
    @Json(name = "release_dates") val releaseDates: TmdbReleaseDatesResponse? = null,
    @Json(name = "content_ratings") val contentRatings: TmdbContentRatingsResponse? = null
)

@JsonClass(generateAdapter = true)
data class TmdbExternalIds(
    @Json(name = "imdb_id") val imdbId: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbPersonCredit(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    val character: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCombinedCredits(
    val cast: List<TmdbPersonCredit> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbPersonDetail(
    val id: Int,
    val name: String,
    val biography: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null,
    @Json(name = "combined_credits") val combinedCredits: TmdbCombinedCredits? = null
)

@JsonClass(generateAdapter = true)
data class TmdbDiscoverItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbDiscoverResponse(
    val results: List<TmdbDiscoverItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbEpisode(
    @Json(name = "episode_number") val episodeNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @Json(name = "still_path") val stillPath: String? = null,
    val runtime: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbSeasonDetail(
    val episodes: List<TmdbEpisode> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbCollectionRef(
    val id: Int,
    val name: String,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCollectionPart(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCollectionDetail(
    val id: Int,
    val name: String,
    val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    val parts: List<TmdbCollectionPart> = emptyList()
)
