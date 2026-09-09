package com.kennyb1201.kbstream.data.omdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Critic/audience ratings pulled from OMDb for one title. All fields are
 * preformatted display strings exactly as OMDb returns them
 * ("7.9", "93%", "78/100") so the UI can render them verbatim.
 */
data class OmdbRatings(
    val imdb: String? = null,
    val rottenTomatoes: String? = null,
    val metacritic: String? = null
) {
    val hasAny: Boolean
        get() = imdb != null || rottenTomatoes != null || metacritic != null
}

/**
 * Minimal OMDb client (plain OkHttp + org.json, matching the IntroDb helper
 * pattern). Fails soft: any network/parse problem or a blank key yields null
 * so the detail screen simply omits the ratings row.
 */
object OmdbClient {
    private val client = OkHttpClient.Builder()
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    suspend fun fetchRatings(imdbId: String, apiKey: String): OmdbRatings? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || !imdbId.startsWith("tt")) return@withContext null
            runCatching {
                val url = "https://www.omdbapi.com/?i=$imdbId&apikey=$apiKey"
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val root = JSONObject(response.body?.string().orEmpty())
                    if (root.optString("Response") != "True") return@use null

                    var imdb = root.optString("imdbRating")
                        .takeIf { it.isNotBlank() && it != "N/A" }
                    var rt: String? = null
                    var mc: String? = null
                    val arr = root.optJSONArray("Ratings")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val entry = arr.optJSONObject(i) ?: continue
                            when (entry.optString("Source")) {
                                "Rotten Tomatoes" ->
                                    rt = entry.optString("Value").takeIf { it.isNotBlank() && it != "N/A" }
                                "Metacritic" ->
                                    mc = entry.optString("Value").takeIf { it.isNotBlank() && it != "N/A" }
                            }
                        }
                    }
                    if (imdb == null && rt == null && mc == null) null
                    else OmdbRatings(imdb, rt, mc)
                }
            }.getOrNull()
        }
}
