package com.kennyb1201.kbstream.data.omdb

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * OMDB title lookup by IMDb id — the only source on this app's list that
 * carries awards text (TMDB has no awards field at all, and neither does the
 * addon meta contract beyond what an addon chooses to send).
 *
 * Only [OmdbResponse] is modelled, and only two of its fields: OMDB answers
 * with twenty-odd keys this screen never reads, so an OMDB schema change to any
 * of them cannot reach the parser. `Awards` is the fact the Detail screen
 * shows; `Response` is OMDB's own success flag ("True"/"False"), which is what
 * a wrong or rate-limited key comes back as.
 */
interface OmdbApiService {
    @GET("/")
    suspend fun getByImdbId(
        @Query("i") imdbId: String,
        @Query("apikey") apiKey: String,
    ): OmdbResponse
}

/**
 * The service over [client], pointed at OMDB's read endpoint.
 *
 * A factory rather than inline Retrofit setup so the wire contract — the host,
 * the `i`/`apikey` query names, and OMDB's own `Awards`/`Response` JSON keys —
 * can be exercised against the real Retrofit + Moshi stack in a unit test
 * instead of being assumed (see OmdbApiServiceTest).
 *
 * The caller supplies the client: production passes the app's shared base
 * client (which installs no logging interceptor, so the `apikey` query
 * parameter never reaches a log).
 */
internal fun omdbRetrofitApi(client: OkHttpClient): OmdbApiService =
    Retrofit.Builder()
        .baseUrl(OMDB_BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(omdbMoshi))
        .build()
        .create(OmdbApiService::class.java)

internal const val OMDB_BASE_URL = "https://www.omdbapi.com/"

private val omdbMoshi: Moshi by lazy {
    Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
}

@JsonClass(generateAdapter = true)
data class OmdbResponse(
    @Json(name = "Awards") val awards: String? = null,
    /** "True" when the lookup succeeded, "False" for an unknown id or key. */
    @Json(name = "Response") val response: String? = null,
)

/**
 * The displayable awards text in [response], or null when there is none to
 * show.
 *
 * Null covers every "nothing to show" shape OMDB has, so callers can treat a
 * missing awards line as an ordinary fact rather than an error:
 *
 *  - a failed lookup (`Response = "False"` — an unknown id, a bad key, or the
 *    free tier's daily quota spent);
 *  - a title with no awards recorded at all (the field is absent or blank);
 *  - OMDB's literal `"N/A"` placeholder, which is the same non-answer.
 *
 * The returned text is the whole awards string, trimmed: "Won 2 Oscars. 159
 * wins & 220 nominations total" is shown in full (see the Detail fact row,
 * which wraps rather than truncates).
 */
internal fun omdbAwardsOrNull(response: OmdbResponse?): String? {
    if (response == null) return null
    if (response.response?.trim().equals("False", ignoreCase = true)) return null
    val awards = response.awards?.trim().orEmpty()
    return awards.takeIf { it.isNotEmpty() && !it.equals("N/A", ignoreCase = true) }
}
