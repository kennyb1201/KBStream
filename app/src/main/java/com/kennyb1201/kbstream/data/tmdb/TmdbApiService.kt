package com.kennyb1201.kbstream.data.tmdb

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApiService {
    @GET("find/{externalId}")
    suspend fun find(
        @Path("externalId") externalId: String,
        @Query("api_key") apiKey: String,
        @Query("external_source") externalSource: String = "imdb_id"
    ): TmdbFindResponse

    @GET("movie/{id}")
    suspend fun getMovie(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") append: String = "credits,videos,recommendations,reviews"
    ): TmdbDetail

    @GET("tv/{id}")
    suspend fun getTv(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") append: String = "credits,videos,recommendations,reviews"
    ): TmdbDetail

    @GET("movie/{id}/external_ids")
    suspend fun getMovieExternalIds(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String
    ): TmdbExternalIds

    @GET("tv/{id}/external_ids")
    suspend fun getTvExternalIds(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String
    ): TmdbExternalIds

    @GET("person/{id}")
    suspend fun getPerson(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") append: String = "combined_credits"
    ): TmdbPersonDetail

    @GET("collection/{id}")
    suspend fun getCollection(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String
    ): TmdbCollectionDetail

    @GET("trending/all/day")
    suspend fun getTrending(
        @Query("api_key") apiKey: String
    ): TmdbDiscoverResponse

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("api_key") apiKey: String
    ): TmdbDiscoverResponse

    @GET("tv/popular")
    suspend fun getPopularTv(
        @Query("api_key") apiKey: String
    ): TmdbDiscoverResponse

    @GET("movie/top_rated")
    suspend fun getTopRatedMovies(
        @Query("api_key") apiKey: String
    ): TmdbDiscoverResponse

    @GET("tv/top_rated")
    suspend fun getTopRatedTv(
        @Query("api_key") apiKey: String
    ): TmdbDiscoverResponse

    @GET("discover/movie")
    suspend fun discoverMovieByCompany(
        @Query("with_companies") companyId: Int,
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("primary_release_date.lte") releaseDateLte: String? = null,
        @Query("page") page: Int = 1
    ): TmdbDiscoverResponse

    @GET("discover/tv")
    suspend fun discoverTvByCompany(
        @Query("with_companies") companyId: Int,
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("first_air_date.lte") firstAirDateLte: String? = null,
        @Query("page") page: Int = 1
    ): TmdbDiscoverResponse

    @GET("discover/movie")
    suspend fun discoverMovieByGenre(
        @Query("with_genres") genreId: Int,
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("primary_release_date.lte") releaseDateLte: String? = null,
        @Query("page") page: Int = 1
    ): TmdbDiscoverResponse

    @GET("discover/tv")
    suspend fun discoverTvByGenre(
        @Query("with_genres") genreId: Int,
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("first_air_date.lte") firstAirDateLte: String? = null,
        @Query("page") page: Int = 1
    ): TmdbDiscoverResponse

    @GET("discover/movie")
    suspend fun discoverMovieByKeyword(
        @Query("with_keywords") keywordId: Int,
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("primary_release_date.lte") releaseDateLte: String? = null,
        @Query("page") page: Int = 1
    ): TmdbDiscoverResponse

    @GET("discover/tv")
    suspend fun discoverTvByKeyword(
        @Query("with_keywords") keywordId: Int,
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("first_air_date.lte") firstAirDateLte: String? = null,
        @Query("page") page: Int = 1
    ): TmdbDiscoverResponse

    @GET("discover/tv")
    suspend fun discoverByNetwork(
        @Query("with_networks") networkId: Int,
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("first_air_date.lte") firstAirDateLte: String? = null,
        @Query("page") page: Int = 1
    ): TmdbDiscoverResponse

    @GET("tv/{id}/season/{season_number}")
    suspend fun getSeasonDetail(
        @Path("id") id: Int,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String
    ): TmdbSeasonDetail
}
