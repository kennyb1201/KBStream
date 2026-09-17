package com.kennyb1201.kbstream.data.mdblist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Critic/audience ratings pulled from MDBList for one title. All fields are
 * preformatted display strings so the UI can render them verbatim
 * ("8.1", "95%", "78/100").
 *
 * Replaces the old OMDb row with the same shape but adds more sources
 * (Trakt / Letterboxd / MyAnimeList) that OMDb did not carry. Fails soft:
 * any network/parse problem or a blank key yields null so the detail screen
 * simply omits the ratings row.
 */
data class MdbListRatings(
    val imdb: String? = null,
    val tmdb: String? = null,
    val rottenTomatoes: String? = null,
    val metacritic: String? = null,
    val trakt: String? = null,
    val letterboxd: String? = null,
    val myAnimeList: String? = null
) {
    val hasAny: Boolean
        get() = imdb != null || tmdb != null || rottenTomatoes != null ||
            metacritic != null || trakt != null || letterboxd != null ||
            myAnimeList != null
}

/**
 * Minimal MDBList client (plain OkHttp + org.json, matching the OMDb helper
 * pattern it replaces). API docs: https://docs.mdblist.com — the ratings
 * endpoints are GET https://api.mdblist.com/{movie|show}/{imdbId}/ratings
 * with the key as the `apikey` query parameter.
 */
object MdbListClient {
    private val client = OkHttpClient.Builder()
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    private const val BASE = "https://api.mdblist.com"

    private fun newEmptyObject(): JSONObject = JSONObject()

    /** MDBList ratings for a movie (type="movie") or a show (type="show"). */
    suspend fun fetchRatings(
        imdbId: String,
        mediaType: String,
        apiKey: String
    ): MdbListRatings? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !imdbId.startsWith("tt")) return@withContext null
        val type = if (mediaType.lowercase() == "movie") "movie" else "show"
        val url = "$BASE/$type/$imdbId/ratings?apikey=$apiKey"
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = JSONObject(response.body?.string().orEmpty())

                // MDBList responses vary by endpoint version: the values can
                // sit flat on the root object or nested under a "ratings"
                // object. Read BOTH and prefer whichever carries data.
                val ratingsNode =
                    root.optJSONObject("ratings") ?: newEmptyObject()

                fun value(name: String): Double {
                    val v = ratingsNode.optDouble(name, Double.NaN)
                    return if (v.isNaN()) root.optDouble(name, Double.NaN) else v
                }

                fun has(name: String): Boolean = !value(name).isNaN()

                // x/10 sources render with one decimal ("8.5").
                fun score(name: String): String? =
                    if (!has(name)) null
                    else String.format(java.util.Locale.US, "%.1f", value(name))

                // 0-100 sources render as percent ("95%").
                fun percent(name: String): String? =
                    if (!has(name)) null
                    else "${value(name).toInt()}%"

                // Metacritic is conventionally shown as "78/100".
                fun metacritic(): String? =
                    if (!has("metacritic")) null
                    else "${value("metacritic").toInt()}/100"

                // MyAnimeList scores live on a 1-10 scale at the source, but
                // MDBList may normalize to 0-100; adapt to whichever arrives.
                fun myAnimeList(): String? =
                    if (!has("mal")) null
                    else if (value("mal") > 10.0) "${value("mal").toInt()}%"
                    else String.format(java.util.Locale.US, "%.1f", value("mal"))

                val ratings = MdbListRatings(
                    imdb = score("imdb"),
                    tmdb = score("tmdb"),
                    rottenTomatoes = percent("tomatoes"),
                    metacritic = metacritic(),
                    trakt = percent("trakt"),
                    letterboxd = percent("letterboxd"),
                    myAnimeList = myAnimeList()
                )

                if (ratings.hasAny) ratings else null
            }
        }.getOrNull()
    }
}
