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
        @Query("append_to_response") append: String = "credits,videos,recommendations,release_dates,alternative_titles"
    ): TmdbDetail

    @GET("tv/{id}")
    suspend fun getTv(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") append: String = "credits,videos,recommendations,content_ratings,alternative_titles"
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

    @GET("discover/movie")
    suspend fun discoverMovieByCompany(
        @Query("with_companies") companyId: Int,
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("page") page: Int = 1
    ): TmdbDiscoverResponse

    @GET("discover/tv")
    suspend fun discoverTvByCompany(
        @Query("with_companies") companyId: Int,
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("page") page: Int = 1
    ): TmdbDiscoverResponse

    @GET("discover/tv")
    suspend fun discoverByNetwork(
        @Query("with_networks") networkId: Int,
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("page") page: Int = 1
    ): TmdbDiscoverResponse

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("api_key") apiKey: String,
        @Query("with_genres") genreId: Int? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("primary_release_year") year: Int? = null,
        @Query("page") page: Int = 1
    ): TmdbDiscoverResponse

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("api_key") apiKey: String,
        @Query("with_genres") genreId: Int? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("first_air_date_year") year: Int? = null,
        @Query("page") page: Int = 1
    ): TmdbDiscoverResponse

    @GET("tv/{id}/season/{season_number}")
    suspend fun getSeasonDetail(
        @Path("id") id: Int,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String
    ): TmdbSeasonDetail
}
