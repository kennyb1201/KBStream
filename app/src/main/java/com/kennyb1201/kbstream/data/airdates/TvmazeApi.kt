package com.kennyb1201.kbstream.data.airdates

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The subset of TVmaze's show payload needed to reach its episode list: the
 * numeric id. Everything else the endpoint returns (image, rating, schedule)
 * is ignored by design - this source exists for one thing only, air dates.
 */
data class TvmazeShow(
    @Json(name = "id") val id: Int? = null
)

/**
 * One TVmaze episode. [airDate] is a plain calendar date (`yyyy-MM-dd`) in the
 * network's local timezone - the same shape TMDB uses, so the two are directly
 * comparable. [airStamp] (an absolute UTC instant) is deliberately unused: the
 * UI works in whole days, and mixing a UTC stamp with a local-date "today" is
 * how an evening premiere gets read as tomorrow.
 */
data class TvmazeEpisode(
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "airdate") val airDate: String? = null
)

/**
 * TVmaze's public read API. No key and no auth are required, which is why this
 * is the cross-check source: a wrong TMDB date is corrected without the user
 * having to register for anything.
 *
 * Two calls per show: `lookup/shows?imdb=tt...` (answers 404 when the show is
 * unknown) and `shows/{id}/episodes`, which returns the show's whole run -
 * every season - in one response, so a season's premiere can be derived
 * without a request per season.
 */
interface TvmazeApiService {

    @GET("lookup/shows")
    suspend fun lookupShow(@Query("imdb") imdbId: String): TvmazeShow

    @GET("shows/{id}/episodes")
    suspend fun episodes(@Path("id") showId: Int): List<TvmazeEpisode>
}
