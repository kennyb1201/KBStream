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
        @Query("append_to_response") append: String = "release_dates,credits,videos,recommendations,reviews,keywords,images,awards"
    ): TmdbDetail

    @GET("tv/{id}")
    suspend fun getTv(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") append: String = "content_ratings,credits,videos,recommendations,reviews,keywords,images,awards"
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

    // ---- NEW: Search screen support (actors, studios/networks, genres) ----

    @GET("search/person")
    suspend fun searchPerson(
        @Query("query") query: String,
        @Query("api_key") apiKey: String
    ): TmdbSearchPersonResponse

    @GET("search/company")
    suspend fun searchCompany(
        @Query("query") query: String,
        @Query("api_key") apiKey: String
    ): TmdbSearchCompanyResponse

    @GET("search/collection")
suspend fun searchCollection(
    @Query("query") query: String,
    @Query("api_key") apiKey: String
): TmdbSearchCollectionResponse

    @GET("search/movie")
    suspend fun searchMovie(
        @Query("query") query: String,
        @Query("api_key") apiKey: String
    ): TmdbSearchTitleResponse

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("api_key") apiKey: String
    ): TmdbSearchTitleResponse

    @GET("trending/movie/week")
    suspend fun getTrendingMovies(
        @Query("api_key") apiKey: String
    ): TmdbSearchTitleResponse

    @GET("trending/tv/week")
    suspend fun getTrendingTv(
        @Query("api_key") apiKey: String
    ): TmdbSearchTitleResponse

    @GET("genre/movie/list")
    suspend fun getMovieGenreList(
        @Query("api_key") apiKey: String
    ): TmdbGenreListResponse

    @GET("genre/tv/list")
    suspend fun getTvGenreList(
        @Query("api_key") apiKey: String
    ): TmdbGenreListResponse

    // ---- Studio / network screen support ----

    @GET("company/{id}/images")
    suspend fun getCompanyImages(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String
    ): TmdbCompanyImages

    @GET("company/{id}")
    suspend fun getCompanyDetail(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String
    ): TmdbCompanyDetail

    // Networks are a separate TMDB ID space from companies, so they need
    // their own endpoints (a network id is NOT a valid company id).
    @GET("network/{id}/images")
    suspend fun getNetworkImages(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String
    ): TmdbCompanyImages

    @GET("network/{id}")
    suspend fun getNetworkDetail(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String
    ): TmdbCompanyDetail
}
