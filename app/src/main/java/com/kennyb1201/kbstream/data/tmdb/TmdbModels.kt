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
data class TmdbAuthorDetails(
    val rating: Double? = null
)

@JsonClass(generateAdapter = true)
data class TmdbReview(
    val id: String,
    val author: String,
    @Json(name = "author_details") val authorDetails: TmdbAuthorDetails? = null,
    val content: String,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbReviews(
    val results: List<TmdbReview> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbGenre(
    val id: Int,
    val name: String
)

@JsonClass(generateAdapter = true)
data class TmdbKeyword(
    val id: Int,
    val name: String
)

@JsonClass(generateAdapter = true)
data class TmdbKeywords(
    val keywords: List<TmdbKeyword> = emptyList(),
    val results: List<TmdbKeyword> = emptyList()
)

fun TmdbKeywords?.list(): List<TmdbKeyword> =
    this?.keywords?.takeIf { it.isNotEmpty() } ?: this?.results.orEmpty()

@JsonClass(generateAdapter = true)
data class TmdbEpisodeAirInfo(
    @Json(name = "season_number") val seasonNumber: Int? = null,
    @Json(name = "episode_number") val episodeNumber: Int? = null,
    @Json(name = "air_date") val airDate: String? = null
)

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
    val reviews: TmdbReviews? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val keywords: TmdbKeywords? = null,
    @Json(name = "belongs_to_collection") val belongsToCollection: TmdbCollectionRef? = null,
    @Json(name = "next_episode_to_air") val nextEpisodeToAir: TmdbEpisodeAirInfo? = null,
    @Json(name = "last_episode_to_air") val lastEpisodeToAir: TmdbEpisodeAirInfo? = null
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
    val character: String? = null,
    val job: String? = null,
    val department: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    val popularity: Double? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCombinedCredits(
    val cast: List<TmdbPersonCredit> = emptyList(),
    val crew: List<TmdbPersonCredit> = emptyList()
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

fun TmdbCredits?.director(): TmdbCrewMember? =
    this?.crew?.firstOrNull { it.job == "Director" }

fun TmdbCredits?.writers(): List<TmdbCrewMember> =
    this?.crew?.filter { it.department == "Writing" || it.job == "Writer" || it.job == "Screenplay" } ?: emptyList()

fun TmdbDetail.certification(isMovie: Boolean): String? = null