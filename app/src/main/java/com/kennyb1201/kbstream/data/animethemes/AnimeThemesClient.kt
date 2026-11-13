package com.kennyb1201.kbstream.data.animethemes

import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.ui.player.animeTitleMatches
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * AnimeThemes' read surface, over GraphQL.
 *
 * GraphQL only, on purpose: the JSON:API at `api.animethemes.moe` is deprecated
 * and will be removed, so a client built on it would be on borrowed time. The
 * field names below are the JSON:API ones carried across (AnimeThemes documents
 * both from one content model).
 *
 * No key and no account: the GraphQL surface is unauthenticated at 90 requests a
 * minute, and one Detail open is one request for a title TMDB calls animation.
 */
internal object AnimeThemesClient {

    /**
     * What every request calls itself. The API wants a User-Agent, and naming
     * the app (plus the build, so a report pins the release) is the same
     * courtesy the intro-skip sources get.
     */
    const val USER_AGENT = "KBStream/" + BuildConfig.VERSION_NAME

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private const val DEFAULT_TIMEOUT_MS = 8_000L

    private val httpClient = BaseHttpClient.derived {
        callTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * One anime's themes, keyed by MyAnimeList id.
     *
     * `filterAnimeByExternalSite` is the documented lookup for an external id;
     * `MAL` is the [ResourceSite] for a MyAnimeList id. A resource may link to
     * several anime (a remake, a spin-off sharing an id), so the answer is
     * filtered by [showTitle] with the same normalized title match the Jikan
     * resolution uses - a title that does not match has no tab, rather than
     * another anime's openings.
     *
     * Empty on any failure: a 429, a transport error, a GraphQL `errors` body
     * and a body that names no themes all mean "no Themes tab", and none of them
     * may take the Detail screen down with it.
     */
    suspend fun fetchThemes(malId: Int, showTitle: String?): List<AnimeTheme> =
        withContext(Dispatchers.IO) {
            if (malId <= 0) return@withContext emptyList()

            val body = runCatching {
                httpClient.newCall(request(malId)).execute().use { response ->
                    if (!response.isSuccessful) null else JSONObject(response.body?.string().orEmpty())
                }
            }.getOrNull() ?: return@withContext emptyList()

            readThemes(body, showTitle)
        }

    /** The POST every theme lookup sends. Extracted so a test can assert it. */
    fun request(malId: Int): Request =
        Request.Builder()
            .url(ANIME_THEMES_GRAPHQL_URL)
            .header("User-Agent", USER_AGENT)
            .post(requestBody(malId))
            .build()

    /** The GraphQL envelope: one query, one variable. */
    fun requestBody(malId: Int): RequestBody =
        JSONObject()
            .put("query", QUERY)
            .put("variables", JSONObject().put("id", JSONArray().put(malId)))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

    /**
     * The lookup, by external site.
     *
     * Every field the tab draws is asked for in one round trip: the theme's
     * type/sequence/slug and song, and each entry's version/episodes and video
     * nodes (link/resolution/source/nc). Nothing is fetched per theme.
     */
    private const val QUERY =
        "query (\$id: [Int!]) {" +
            " filterAnimeByExternalSite(site: MAL, id: \$id) {" +
            " title { romaji }" +
            " themes {" +
            " type sequence slug" +
            " song { title artists { name } }" +
            " entries { version episodes videos { nodes { link resolution source nc } } }" +
            " }" +
            " }" +
            " }"

    /**
     * Reads one GraphQL answer into the tab's themes.
     *
     * The first anime whose title matches [showTitle] wins; a body with `errors`,
     * no `data`, or no matching anime reads as no themes. Unknown fields and
     * unknown theme types are ignored rather than failing the read.
     */
    internal fun readThemes(root: JSONObject, showTitle: String?): List<AnimeTheme> {
        if (root.optJSONArray("errors")?.length() ?: 0 > 0) return emptyList()

        val animeList = root.optJSONObject("data")
            ?.optJSONArray("filterAnimeByExternalSite")
            ?: return emptyList()

        for (index in 0 until animeList.length()) {
            val anime = animeList.optJSONObject(index) ?: continue
            val title = anime.optJSONObject("title")?.optString("romaji")
            if (!animeTitleMatches(showTitle, title, null, emptyList())) continue

            val themes = anime.optJSONArray("themes") ?: return emptyList()
            return (0 until themes.length())
                .mapNotNull { position -> readTheme(themes.optJSONObject(position)) }
                .let { AnimeThemeRules.themesOf(it) }
        }

        return emptyList()
    }

    /**
     * One theme, or null for a row the tab cannot draw.
     *
     * A type other than OP/ED and a missing sequence are dropped: the badge is
     * the type and the sequence, and a card that cannot say which opening it is
     * has nothing to offer. A missing slug falls back to the pair, which is
     * unique within the anime (the lazy list's key has to be).
     */
    private fun readTheme(theme: JSONObject?): AnimeThemeRules.RawTheme? {
        val row = theme ?: return null

        val type = row.optString("type").trim().uppercase(Locale.ROOT)
        if (type != "OP" && type != "ED") return null

        val sequence = row.optInt("sequence", 0).takeIf { it > 0 } ?: return null

        val slug = row.optString("slug").trim()
            .takeIf { it.isNotEmpty() }
            ?: "theme:" + type + sequence

        val song = row.optJSONObject("song")
        val songTitle = song?.optString("title")?.trim()?.takeIf { it.isNotEmpty() }

        val artists = song?.optJSONArray("artists")
            ?.let { list ->
                (0 until list.length()).mapNotNull { index ->
                    list.optJSONObject(index)
                        ?.optString("name")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
            }
            .orEmpty()

        val entries = row.optJSONArray("entries")
            ?.let { list ->
                (0 until list.length()).mapNotNull { index ->
                    readEntry(list.optJSONObject(index))
                }
            }
            .orEmpty()

        return AnimeThemeRules.RawTheme(
            slug = slug,
            type = type,
            sequence = sequence,
            songTitle = songTitle,
            artists = artists,
            entries = entries
        )
    }

    /**
     * One version of a theme.
     *
     * `version` absent means the first one (AnimeThemes omits it there), which
     * is what [AnimeThemeRules.bestVersion] compares against. `episodes` is the
     * range the version covers, e.g. `1-13`.
     */
    private fun readEntry(entry: JSONObject?): AnimeThemeEntry? {
        val row = entry ?: return null

        // Present means the service is telling us; absent means the first one.
        val version = if (row.has("version")) {
            row.optInt("version", 0).takeIf { it > 0 }
        } else {
            1
        }

        val episodes = row.optString("episodes").trim().takeIf { it.isNotEmpty() }

        val videos = row.optJSONObject("videos")
            ?.optJSONArray("nodes")
            ?.let { nodes ->
                (0 until nodes.length()).mapNotNull { index ->
                    readVideo(nodes.optJSONObject(index))
                }
            }
            .orEmpty()

        return AnimeThemeEntry(
            version = version,
            episodes = episodes,
            videos = videos
        )
    }

    /**
     * One video file. A node with no `link` is dropped - there is nothing to
     * play - and every other field is optional, because the service is free to
     * omit what it does not know.
     */
    private fun readVideo(node: JSONObject?): AnimeThemeVideo? {
        val row = node ?: return null

        val link = row.optString("link").trim()
        if (link.isEmpty()) return null

        return AnimeThemeVideo(
            link = link,
            resolution = row.optInt("resolution", 0).takeIf { it > 0 },
            source = row.optString("source").trim().takeIf { it.isNotEmpty() },
            nc = row.optBoolean("nc", false)
        )
    }
}
