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
data class TmdbCredits(
    val cast: List<TmdbCastMember> = emptyList()
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
data class TmdbSimilar(
    val results: List<TmdbRecommendationItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbSeasonSummary(
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String? = null,
    @Json(name = "episode_count") val episodeCount: Int? = null
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

@JsonClass(generateAdapter = true)
data class TmdbBackdropImage(
    @Json(name = "file_path") val filePath: String,
    val width: Int? = null,
    val height: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbLogoImage(
    @Json(name = "file_path") val filePath: String,
    @Json(name = "iso_639_1") val language: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbImages(
    val backdrops: List<TmdbBackdropImage> = emptyList(),
    val posters: List<TmdbBackdropImage> = emptyList(),
    val logos: List<TmdbLogoImage> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbKeyword(val id: Int, val name: String)

// TMDB's movie keywords response uses "keywords" as the inner list key,
// while TV uses "results" -- this wrapper covers both shapes with one field.
@JsonClass(generateAdapter = true)
data class TmdbKeywordsWrapper(
    val keywords: List<TmdbKeyword>? = null,
    val results: List<TmdbKeyword>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbReleaseDate(
    val certification: String,
    @Json(name = "iso_639_1") val language: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbReleaseDatesResult(
    @Json(name = "iso_3166_1") val country: String,
    @Json(name = "release_dates") val releaseDates: List<TmdbReleaseDate> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbReleaseDates(val results: List<TmdbReleaseDatesResult> = emptyList())

@JsonClass(generateAdapter = true)
data class TmdbContentRating(
    @Json(name = "iso_3166_1") val country: String,
    val rating: String
)

@JsonClass(generateAdapter = true)
data class TmdbContentRatings(val results: List<TmdbContentRating> = emptyList())

@JsonClass(generateAdapter = true)
data class TmdbReview(
    val id: String,
    val author: String,
    val content: String,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbReviews(val results: List<TmdbReview> = emptyList())

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
    val images: TmdbImages? = null,
    val keywords: TmdbKeywordsWrapper? = null,
    val similar: TmdbSimilar? = null,
    @Json(name = "release_dates") val releaseDates: TmdbReleaseDates? = null,
    @Json(name = "content_ratings") val contentRatings: TmdbContentRatings? = null,
    val reviews: TmdbReviews? = null
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
