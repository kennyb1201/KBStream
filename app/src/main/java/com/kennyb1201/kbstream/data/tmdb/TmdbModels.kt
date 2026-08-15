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
data class TmdbImageAsset(
    @Json(name = "file_path") val filePath: String? = null,
    @Json(name = "iso_639_1") val iso6391: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    val width: Int? = null,
    val height: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbImagesResponse(
    val logos: List<TmdbImageAsset> = emptyList(),
    val posters: List<TmdbImageAsset> = emptyList(),
    val backdrops: List<TmdbImageAsset> = emptyList()
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
data class TmdbMovieReleaseDateEntry(
    val certification: String? = null,
    @Json(name = "iso_639_1") val iso6391: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    val type: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbMovieReleaseDatesResult(
    @Json(name = "iso_3166_1") val iso31661: String,
    @Json(name = "release_dates") val releaseDates: List<TmdbMovieReleaseDateEntry> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbMovieReleaseDates(
    val results: List<TmdbMovieReleaseDatesResult> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbTvContentRating(
    @Json(name = "iso_3166_1") val iso31661: String,
    val rating: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbTvContentRatings(
    val results: List<TmdbTvContentRating> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbDetail(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    val budget: Long? = null,
    val revenue: Long? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "production_companies") val productionCompanies: List<TmdbProductionCompany> = emptyList(),
    val networks: List<TmdbNetwork> = emptyList(),
    val seasons: List<TmdbSeasonSummary> = emptyList(),
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    val recommendations: TmdbRecommendations? = null,
    val reviews: TmdbReviews? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val keywords: TmdbKeywords? = null,
    val images: TmdbImagesResponse? = null,
    @Json(name = "belongs_to_collection") val belongsToCollection: TmdbCollectionRef? = null,
    @Json(name = "next_episode_to_air") val nextEpisodeToAir: TmdbEpisodeAirInfo? = null,
    @Json(name = "last_episode_to_air") val lastEpisodeToAir: TmdbEpisodeAirInfo? = null,
    @Json(name = "release_dates") val releaseDates: TmdbMovieReleaseDates? = null,
    @Json(name = "content_ratings") val contentRatings: TmdbTvContentRatings? = null,
    val status: String? = null
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
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null
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
    val birthday: String? = null,
    val deathday: String? = null,
    @Json(name = "place_of_birth") val placeOfBirth: String? = null,
    @Json(name = "known_for_department") val knownForDepartment: String? = null,
    @Json(name = "also_known_as") val alsoKnownAs: List<String> = emptyList(),
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
    val runtime: Int? = null,
    @Json(name = "air_date") val airDate: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null
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

// Search screen support

@JsonClass(generateAdapter = true)
data class TmdbKnownForItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "media_type") val mediaType: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbSearchPersonResult(
    val id: Int,
    val name: String,
    @Json(name = "profile_path") val profilePath: String? = null,
    @Json(name = "known_for_department") val knownForDepartment: String? = null,
    @Json(name = "known_for") val knownFor: List<TmdbKnownForItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbSearchPersonResponse(
    val results: List<TmdbSearchPersonResult> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbSearchStudioResult(
    val id: Int,
    val name: String,
    @Json(name = "logo_path") val logoPath: String? = null,
    @Json(name = "origin_country") val originCountry: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbSearchCompanyResponse(
    val results: List<TmdbSearchStudioResult> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbGenreListResponse(
    val genres: List<TmdbGenre> = emptyList()
)

data class TmdbGenreMatch(
    val id: Int,
    val name: String,
    val mediaType: String
)

fun TmdbCredits?.director(): TmdbCrewMember? =
    this?.crew?.firstOrNull { it.job == "Director" }

fun TmdbCredits?.writers(): List<TmdbCrewMember> =
    this?.crew?.filter {
        it.department == "Writing" ||
            it.job == "Writer" ||
            it.job == "Screenplay"
    } ?: emptyList()

fun TmdbDetail.releaseYear(): String? =
    bestReleaseDate()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

fun TmdbDetail.bestReleaseDate(): String? {
    val rawDate = releaseDates?.results
        ?.firstOrNull { it.iso31661 == "US" }
        ?.releaseDates
        ?.firstOrNull { !it.releaseDate.isNullOrBlank() }
        ?.releaseDate
        ?: releaseDates?.results
            ?.asSequence()
            ?.flatMap { it.releaseDates.asSequence() }
            ?.firstOrNull { !it.releaseDate.isNullOrBlank() }
            ?.releaseDate
        ?: releaseDate
        ?: firstAirDate

    return rawDate?.substringBefore("T")?.takeIf { it.isNotBlank() }
}

fun TmdbDetail.certification(isMovie: Boolean): String? {
    return if (isMovie) {
        releaseDates?.results
            ?.firstOrNull { it.iso31661 == "US" }
            ?.releaseDates
            ?.firstOrNull { !it.certification.isNullOrBlank() }
            ?.certification
            ?.takeIf { it.isNotBlank() }
            ?: releaseDates?.results
                ?.asSequence()
                ?.flatMap { it.releaseDates.asSequence() }
                ?.mapNotNull { it.certification?.takeIf(String::isNotBlank) }
                ?.firstOrNull()
    } else {
        contentRatings?.results
            ?.firstOrNull { it.iso31661 == "US" }
            ?.rating
            ?.takeIf { it.isNotBlank() }
            ?: contentRatings?.results
                ?.asSequence()
                ?.mapNotNull { it.rating?.takeIf(String::isNotBlank) }
                ?.firstOrNull()
    }
}

fun TmdbDetail.bestLogoPath(): String? =
    images?.logos
        ?.asSequence()
        ?.filter { !it.filePath.isNullOrBlank() }
        ?.sortedWith(
            compareByDescending<TmdbImageAsset> { it.iso6391 == "en" }
                .thenByDescending { it.iso6391 == null }
                .thenByDescending { it.voteAverage ?: 0.0 }
                .thenByDescending { it.width ?: 0 }
        )
        ?.mapNotNull { it.filePath }
        ?.firstOrNull()

fun tmdbImageOriginal(path: String?): String? =
    path?.takeIf { it.isNotBlank() }
        ?.let { "https://image.tmdb.org/t/p/original$it" }

private fun String.parsedLocalDate(): java.time.LocalDate? =
    takeIf { it.isNotBlank() }
        ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }

fun TmdbPersonDetail.formattedBirthday(): String? =
    birthday?.parsedLocalDate()
        ?.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
        ?: birthday?.takeIf { it.isNotBlank() }

fun TmdbPersonDetail.formattedDeathday(): String? =
    deathday?.parsedLocalDate()
        ?.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
        ?: deathday?.takeIf { it.isNotBlank() }

fun TmdbPersonDetail.age(): Int? {
    val born = birthday?.parsedLocalDate() ?: return null
    val end = deathday?.parsedLocalDate() ?: java.time.LocalDate.now()
    return java.time.Period.between(born, end).years
}

fun TmdbPersonDetail.metaLine(): String {
    val parts = mutableListOf<String>()

    knownForDepartment?.takeIf { it.isNotBlank() }?.let { parts += it }

    val born = birthday?.parsedLocalDate()
    if (born != null) {
        val died = deathday?.parsedLocalDate()
        parts += if (died != null) {
            "${formattedBirthday()} – ${formattedDeathday()} (age ${age()})"
        } else {
            "Born ${formattedBirthday()} (age ${age()})"
        }
    }

    placeOfBirth?.takeIf { it.isNotBlank() }?.let { parts += it }

    return parts.joinToString(" • ")
}
