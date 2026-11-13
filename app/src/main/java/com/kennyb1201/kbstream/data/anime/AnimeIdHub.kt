package com.kennyb1201.kbstream.data.anime

import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.mal.MalApiClient
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * The anime ID hub (spec 2026-11-13, F1).
 *
 * One title in, every anime id out: the AniList id, the MyAnimeList id and the
 * Kitsu id, plus the two facts AniList carries for free (the community score and
 * the next airing episode). Cached by normalized title for the life of the
 * process, because every reader of it - the Themes tab, the intro-skip chain's
 * anime leg, the AniList/Kitsu tracker legs - asks about the same handful of
 * shows over and over, and each miss costs a network round trip on a 30-a-minute
 * budget.
 *
 * The chain, first hit per id, never mixing services inside one id:
 *
 *  0. **MyAnimeList by title** (spec 2026-11-13, F4). MAL is the authoritative
 *     MAL-id source: it returns its own `id`, and its search costs the app's
 *     client id rather than an account, so it answers for every user before
 *     anyone connects anything. The same detail call carries the score and rank
 *     the Detail header shows (`mean`, `rank`), which is why this step is first
 *     and not merely another fallback.
 *  1. **AniList by title.** The single most valuable call here: one `Media`
 *     query returns BOTH the AniList id and `idMal`. Accepted only when the
 *     result's title matches - `romaji` or `english` (or `native`) after
 *     normalization.
 *  2. **Kitsu by title**, then its mapping graph. Kitsu is the cross-reference
 *     service: one id fans out to MAL, AniList, AniDB and TVDB, so its mappings
 *     fill whichever of the two ids step 1 could not.
 *  3. **Jikan by title**, unchanged, and only when the MAL id is still missing.
 *     Before this hub existed this was the only step, which is why it stays: it
 *     is the last resort, not the first.
 *
 * Every step fails on its own - a timeout, a 429, an unreadable body, no match -
 * and contributes only what it found. A title no service knows resolves to an
 * empty [AnimeIdentity] and the anime features simply have nothing to show; no
 * error, no toast, nothing on the rail.
 *
 * Read-only: no token, no key, no write. The User-Agent names the app and build.
 */
internal object AnimeIdHub {

    /** How long a title's answer is remembered. The process, in practice. */
    private val identities = ConcurrentHashMap<String, AnimeIdentity>()

    private const val JIKAN_TIMEOUT_MS = 8_000L

    private val jikanClient = BaseHttpClient.derived {
        callTimeout(JIKAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    internal const val USER_AGENT = "KBStream/" + BuildConfig.VERSION_NAME

    /**
     * Every anime id this title has, resolving once and remembering it after.
     *
     * [showTitle] is the TMDB title; a blank one resolves to nothing without a
     * request. A remembered answer is returned even when it is empty - "no
     * service knows this title" is an answer, and asking again costs three
     * requests to learn it again.
     */
    suspend fun resolve(showTitle: String?): AnimeIdentity {
        val normalized = normalizeAnimeTitle(showTitle)
        if (normalized.isEmpty()) return AnimeIdentity()

        identities[normalized]?.let { return it }

        val identity = runCatching { resolveFresh(showTitle.orEmpty()) }.getOrElse {
            // A failure is NOT remembered: the next reader may succeed, and a
            // transient outage must not become this session's verdict.
            return AnimeIdentity()
        }

        identities[normalized] = identity
        return identity
    }

    /** Test hook: forget every remembered identity. */
    internal fun clearCacheForTest() {
        identities.clear()
    }

    private suspend fun resolveFresh(showTitle: String): AnimeIdentity {
        var anilistId: Int? = null
        var malId: Int? = null
        var kitsuId: String? = null
        var averageScore: Int? = null
        var nextEpisode: Int? = null
        var nextAt: Long? = null
        var malScore: Double? = null
        var malRank: Int? = null

        // 0. MyAnimeList by title - the authoritative MAL id, plus the two facts
        // only MAL carries (F4). Public read: the app's client id, no account.
        MalApiClient.resolveTitle(showTitle)?.let { mal ->
            malId = mal.id
            malScore = mal.mean
            malRank = mal.rank
        }

        // 1. AniList by title - the one call that can answer both ids.
        AniListClient.mediaByTitle(showTitle)?.let { media ->
            val names = media.optJSONObject("title")
            val matches =
                animeTitleMatches(
                    showTitle = showTitle,
                    title = names?.optString("romaji"),
                    titleEnglish = names?.optString("english"),
                    titles = listOf(names?.optString("native"))
                )
            if (matches) {
                anilistId = media.optInt("id", 0).takeIf { it > 0 }
                malId = media.optInt("idMal", 0).takeIf { it > 0 }
                averageScore = media.optInt("averageScore", 0).takeIf { it > 0 }
                media.optJSONObject("nextAiringEpisode")?.let { next ->
                    nextEpisode = next.optInt("episode", 0).takeIf { it > 0 }
                    nextAt = next.optLong("airingAt", 0L).takeIf { it > 0L }
                }
            }
        }

        // 2. Kitsu by title, then its mappings for whatever is still missing.
        // It runs even when AniList answered: the Kitsu ID is one of the three
        // ids this hub exists to supply, and only Kitsu can name it.
        KitsuClient.searchAnime(showTitle)?.let { anime ->
            val attributes = anime.optJSONObject("attributes")
            val titles = attributes?.optJSONObject("titles")
            val matches =
                animeTitleMatches(
                    showTitle = showTitle,
                    title = attributes?.optString("canonicalTitle"),
                    titleEnglish = titles?.optString("en"),
                    titles = listOfNotNull(
                        titles?.optString("en_jp"),
                        titles?.optString("ja_jp")
                    )
                )
            if (matches) {
                kitsuId = anime.optString("id").takeIf { it.isNotBlank() }

                if (malId == null || anilistId == null) {
                    val mappings = KitsuClient.mappings(kitsuId.orEmpty())
                    if (malId == null) {
                        malId = mappings["myanimelist/anime"]?.toIntOrNull()?.takeIf { it > 0 }
                    }
                    if (anilistId == null) {
                        anilistId = mappings["anilist/anime"]?.toIntOrNull()?.takeIf { it > 0 }
                    }
                }
            }
        }

        // 3. Jikan by title - the existing resolution, last resort only.
        if (malId == null) {
            malId = resolveMalIdViaJikan(showTitle)
        }

        return AnimeIdentity(
            anilistId = anilistId,
            malId = malId,
            kitsuId = kitsuId,
            averageScore = averageScore,
            nextAiringEpisode = nextEpisode,
            nextAiringAtSeconds = nextAt,
            malScore = malScore,
            malRank = malRank
        )
    }

    /**
     * Jikan's MAL id for [showTitle], gated on a title match.
     *
     * Kept exactly as the intro-skip chain had it (that leg is what used to own
     * this): every name the result carries is compared - `title`,
     * `title_english` and each `titles[].title` - because an anime's romanized
     * and English names are different fields and a TMDB title may be either.
     */
    private suspend fun resolveMalIdViaJikan(showTitle: String): Int? =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(jikanAnimeSearchUrl(showTitle))
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()

            val body = runCatching {
                jikanClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    JSONObject(response.body?.string().orEmpty())
                }
            }.getOrNull() ?: return@withContext null

            readJikanMalId(body, showTitle)
        }

    /** The first Jikan result's MAL id, and only on a title match. */
    internal fun readJikanMalId(root: JSONObject, showTitle: String?): Int? {
        val first = root.optJSONArray("data")?.optJSONObject(0) ?: return null
        val malId = first.optInt("mal_id", 0).takeIf { it > 0 } ?: return null

        val titles = first.optJSONArray("titles")
        val names =
            if (titles == null) {
                emptyList()
            } else {
                (0 until titles.length()).mapNotNull { index ->
                    titles.optJSONObject(index)?.optString("title")
                }
            }

        val matches =
            animeTitleMatches(
                showTitle = showTitle,
                title = first.optString("title"),
                titleEnglish = first.optString("title_english"),
                titles = names
            )

        return if (matches) malId else null
    }
}
