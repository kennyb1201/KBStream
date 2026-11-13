package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.anime.AnimeIdHub
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

// The stamp model and its source marker live in IntroDbModel.kt: they carry no
// Android dependency, so the pure chain rules beside them can be tested on the
// JVM without the app's BuildConfig and Uri.

internal val introDbHttpClient = BaseHttpClient.derived {
    callTimeout(5, TimeUnit.SECONDS)
}

/**
 * TheIntroDB's read surface, pinned to v4.
 *
 * v1 is gone and v2/v3 are frozen - they still answer `GET /media`, but from a
 * copy that gets no fixes and no new fields, and they are being removed. v4 is
 * the only surface under development, so a client built on v2/v3 is on borrowed
 * time.
 *
 * The bump is nearly transparent: v4 serves the same `GET /media` query and the
 * same segment arrays. The one identity change is that the body is keyed by
 * `tvdb_id` and no longer carries `tmdb_id` - and this reader never read
 * `tmdb_id`, so the version in this URL was the whole migration. `tmdb_id` and
 * `imdb_id` are still valid REQUEST ids on v4 (resolved server-side, in
 * `tvdb_id` -> `tmdb_id` -> `imdb_id` order), which is why the id shapes
 * [fetchIntroDbStamps] already builds keep working.
 *
 * `mapping` is deliberately not sent. It converts season/episode numbers that
 * come from a media server's display order; the numbers here come from TMDB /
 * add-on metadata, so `default` (the stored TVDB order) is what they already
 * mean. Sending an order TVDB does not carry for a series is a 400 that would
 * take the whole lookup down, so a guess would be strictly worse than silence.
 */
internal const val INTRO_DB_MEDIA_URL = "https://api.theintrodb.org/v4/media"

/**
 * What every TheIntroDB request calls itself.
 *
 * The integration had no `User-Agent`, so OkHttp's own (`okhttp/5.x`) was all
 * the API ever saw and the developer could not tell KBStream's traffic from any
 * other client's - their ask in issue #79. `KBStream/<version>` is the form they
 * named; the version comes from the build so a report from a user pins the
 * release that made the request.
 *
 * Sent to the legacy `/segments` host too: it is the same courtesy, and a
 * header costs nothing on a request already being made.
 */
internal const val INTRO_DB_USER_AGENT = "KBStream/" + BuildConfig.VERSION_NAME

/**
 * Millisecond field. Accepts a JSON number (the API's normal shape), a numeric
 * string, or a clock string.
 */
internal fun readMillisField(obj: JSONObject, key: String): Long? {
    if (!obj.has(key) || obj.isNull(key)) return null
    return when (val raw = obj.opt(key)) {
        is Number -> raw.toLong()
        is String -> {
            val text = raw.trim()
            text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong() ?: parseClockToMillis(text)
        }
        else -> null
    }
}

/**
 * Seconds field. The API returns numbers today, but its documented input format
 * accepts clock strings ("00:00:02", "1:59:00") — so a client that only reads
 * numbers would silently drop a segment that comes back in that shape instead
 * of skipping it.
 */
internal fun readSecondsField(obj: JSONObject, key: String): Long? {
    if (!obj.has(key) || obj.isNull(key)) return null
    return when (val raw = obj.opt(key)) {
        is Number -> (raw.toDouble() * 1_000.0).toLong()
        is String -> {
            val text = raw.trim()
            text.toDoubleOrNull()?.let { (it * 1_000.0).toLong() } ?: parseClockToMillis(text)
        }
        else -> null
    }
}

/** "45" / "mm:ss" / "hh:mm:ss" → millis. Null when it is not a clock string. */
internal fun parseClockToMillis(text: String): Long? {
    val parts = text.split(':')
    if (parts.isEmpty() || parts.size > 3) return null
    var totalSeconds = 0L
    for (part in parts) {
        val value = part.trim().toLongOrNull() ?: return null
        if (value < 0L) return null
        totalSeconds = totalSeconds * 60L + value
    }
    return totalSeconds * 1_000L
}

private fun JSONObject.readConfidence(): Double? = when (val raw = opt("confidence")) {
    is Number -> raw.toDouble()
    is String -> raw.toDoubleOrNull()
    else -> null
}

/**
 * Reads one segment row.
 *
 * `end_ms` may be absent. A credits or outro row without one is
 * `{"start_ms": 8300000, "end_ms": null}`: the credits start there and run to
 * the end of the file, which is what a null [IntroDbStamp.endMs] records. The
 * same row on any other segment type is not something this app can act on -
 * an intro with no end is a skip with no target - so it is dropped, exactly as
 * a row with no start already was.
 */
internal fun JSONObject.readIntroDbStamp(
    type: IntroDbMarkerType,
    source: IntroDbSource = IntroDbSource.IntroDbApp
): IntroDbStamp? {
    val startMs = readMillisField(this, "start_ms")
        ?: readSecondsField(this, "start_sec")
        ?: return null
    if (startMs < 0L) return null
    val endMs = readMillisField(this, "end_ms")
        ?: readSecondsField(this, "end_sec")
    if (endMs == null) {
        val openEnded = type == IntroDbMarkerType.Credits || type == IntroDbMarkerType.Outro
        return if (openEnded) {
            IntroDbStamp(startMs, null, type, readConfidence(), source)
        } else {
            null
        }
    }
    if (endMs <= startMs) return null
    return IntroDbStamp(startMs, endMs, type, readConfidence(), source)
}

/**
 * The one request every IntroDB read sends.
 *
 * Extracted so the `User-Agent` is part of the request's own definition rather
 * than an argument at one call site, and so a JVM test can assert it without a
 * socket: both hosts, one builder.
 */
internal fun introDbRequest(url: String): Request =
    Request.Builder()
        .url(url)
        .get()
        .header("User-Agent", INTRO_DB_USER_AGENT)
        .build()

internal fun fetchIntroDbJson(url: String): JSONObject? = runCatching {
    introDbHttpClient.newCall(introDbRequest(url)).execute().use { response ->
        if (!response.isSuccessful) null else JSONObject(response.body?.string().orEmpty())
    }
}.getOrElse { null }

/**
 * Reads one `/segments` response: `intro`, `recap`, `outro`, `post_credits`
 * (the API's newer movie/scene segments). A key arrives as a single object
 * today; the array shape is accepted too so a future/bulk response cannot
 * silently blind the skip button.
 */
private fun readIntroDbSegments(root: JSONObject): List<IntroDbStamp> {
    fun one(key: String, type: IntroDbMarkerType): IntroDbStamp? =
        root.optJSONObject(key)?.readIntroDbStamp(type, IntroDbSource.IntroDbApp)
            ?: root.optJSONArray(key)?.let { arr ->
                (0 until arr.length())
                    .mapNotNull {
                        arr.optJSONObject(it)?.readIntroDbStamp(type, IntroDbSource.IntroDbApp)
                    }
                    .minByOrNull { it.startMs }
            }

    return listOfNotNull(
        one("intro", IntroDbMarkerType.Intro),
        one("recap", IntroDbMarkerType.Recap),
        one("outro", IntroDbMarkerType.Outro),
        one("credits", IntroDbMarkerType.Credits),
        one("post_credits", IntroDbMarkerType.PostCredits),
        one("post-credits", IntroDbMarkerType.PostCredits),
        one("preview", IntroDbMarkerType.Preview)
    )
}

/**
 * Segments for one episode (season + episode present) or one movie (both null —
 * how the player represents a movie).
 *
 * Five sources, in a fixed order, merged per segment KIND (F5, kinds combine):
 * for each kind the first source in the chain that provides it wins and later
 * sources' rows for that kind are ignored, because two services' timings for the
 * same kind can come from different releases. The chain keeps going while any
 * core kind is still missing, so a source that answers with only an intro no
 * longer ends the lookup - that used to mean its credits were never asked for -
 * and it stops as soon as every core kind is covered.
 *
 *  1. IntroDB.app - unchanged, first, IMDb ids only.
 *  2. TheIntroDB v4 - unchanged, second: human-validated and the larger library,
 *     so it outranks SkipDB.
 *  3. SkipDB - general movies and TV, duration-aware, IMDb ids only.
 *  4. AniSkip - anime TV episodes only.
 *  5. Anime Skip - anime TV episodes only, and last of all: it is the only leg
 *     that needs a credential this app does not ship (the viewer's own client
 *     id), so it is asked once every keyless source has had its chance.
 *
 * Every leg fails on its own: a timeout, a 429 or an unreadable body contributes
 * nothing and the chain moves on. Nothing here ever blocks playback, and nothing
 * here writes to any service.
 *
 * [durationMs], [showTitle] and [animeSkipClientId] are optional so an older
 * caller keeps compiling, but all three players pass all three: the duration is
 * what makes SkipDB shift its timestamps for a stream whose logo/scene length
 * differs, the title is the only way to find an anime's MyAnimeList/AniList id,
 * and the client id is the Anime Skip credential without which that leg cannot
 * ask anything. A call with no title simply has no anime legs, and a call with
 * no client id stops after AniSkip.
 */
internal suspend fun fetchIntroDbStamps(
    parentId: String,
    season: Int?,
    episode: Int?,
    durationMs: Long? = null,
    showTitle: String? = null,
    animeSkipClientId: String? = null
): List<IntroDbStamp> = withContext(Dispatchers.IO) {
    val normalizedId = parentId.trim()
    val isImdbId = isSkipDbImdbId(normalizedId)
    val hasEpisode = season != null && episode != null
    val episodeNumber = if (hasEpisode) episode else null
    val encodedId = Uri.encode(normalizedId)

    var merged: List<IntroDbStamp> = emptyList()

    // 1. IntroDB.app — IMDb ids only, and a movie needs the explicit flag.
    if (isImdbId) {
        val url = if (hasEpisode) {
            "https://api.introdb.app/segments?imdb_id=$encodedId&season=$season&episode=$episode"
        } else {
            "https://api.introdb.app/segments?imdb_id=$encodedId&is_movie=true"
        }
        val markers = fetchIntroDbJson(url)?.let { readIntroDbSegments(it) }.orEmpty()
        merged = mergeIntroDbStamps(merged, markers)
        if (!introDbChainStillOpen(merged)) return@withContext merged
    }

    // 2. TheIntroDB v4 — the id flavors it accepts, which are the ones this app
    // has: an IMDb id, or a numeric TMDB id.
    val idQuery = when {
        isImdbId -> "imdb_id=$encodedId"
        normalizedId.toLongOrNull() != null -> "tmdb_id=$normalizedId"
        else -> null
    }
    if (idQuery != null) {
        val episodeQuery = if (hasEpisode) "&season=$season&episode=$episode" else ""
        fetchIntroDbJson("$INTRO_DB_MEDIA_URL?$idQuery$episodeQuery")?.let { media ->
            merged = mergeIntroDbStamps(merged, readIntroDbMedia(media))
        }
        if (!introDbChainStillOpen(merged)) return@withContext merged
    }

    // 3. SkipDB — IMDb ids only; it has no TMDB lookup.
    if (isImdbId) {
        merged = mergeIntroDbStamps(
            merged,
            fetchSkipDbStamps(normalizedId, season, episode, durationMs)
        )
        if (!introDbChainStillOpen(merged)) return@withContext merged
    }

    // 4. AniSkip — episode-addressed, so a movie never reaches it. The Jikan
    // title gate runs first and decides whether this title is anime at all.
    if (hasEpisode && episodeNumber != null) {
        merged = mergeIntroDbStamps(
            merged,
            fetchAniSkipStamps(showTitle, episodeNumber, durationMs)
        )

        // 5. Anime Skip — same gate, and last: it is the only leg with a
        // credential, so it only runs while a kind is still open. It fills the
        // kinds the four keyless sources missed and never overrides one of
        // theirs (the merge is unchanged, which is the invariant).
        if (introDbChainStillOpen(merged)) {
            merged = mergeIntroDbStamps(
                merged,
                fetchAnimeSkipStamps(showTitle, season, episodeNumber, durationMs, animeSkipClientId)
            )
        }
    }

    merged
}

/**
 * SkipDB's segments for one episode or movie.
 *
 * Duration-aware on purpose: with the stream's length in hand SkipDB matches the
 * row to the viewer's release and shifts it EARLIER (never later, see
 * [skipDbSegmentsUrl]) when the two differ by a logo. Its response reports the
 * shift, the confidence and whether the row is `exact`, `shifted`, `agnostic` or
 * `out-of-range`; the confidence is the only one of those this app acts on,
 * through the existing [AutoSkipRules.MIN_CONFIDENCE] gate, so an `out-of-range`
 * row still gets a button but is never skipped on its own.
 *
 * Read-only and keyless. Any failure is no data.
 */
internal suspend fun fetchSkipDbStamps(
    parentId: String,
    season: Int?,
    episode: Int?,
    durationMs: Long?
): List<IntroDbStamp> {
    if (!isSkipDbImdbId(parentId)) return emptyList()
    val body = fetchIntroDbJson(skipDbSegmentsUrl(parentId, season, episode, durationMs))
        ?: return emptyList()
    return readSkipDbSegments(body)
}

/**
 * Reads a SkipDB `/api/segments` body.
 *
 * `segments` always carries all four keys and a key is `null` when SkipDB has no
 * approved row for that type, so an absent segment is the normal answer, not an
 * error. SkipDB's `outro` IS the end-credits block - "SKIP CREDITS" is the label
 * the viewer expects there, and `Credits` and `Outro` are gated by the same
 * skip-credits pref, so mapping it to `Credits` changes a label and nothing
 * else.
 *
 * The rows are the same `start_ms` / `end_ms` / `confidence` shape the other two
 * readers already parse, so they go through [readIntroDbStamp]: a credits row
 * with no end stays open-ended and the player resolves it against the file's
 * duration, exactly as a TheIntroDB credits row does.
 */
internal fun readSkipDbSegments(root: JSONObject): List<IntroDbStamp> {
    val segments = root.optJSONObject("segments") ?: return emptyList()

    fun one(key: String, type: IntroDbMarkerType): IntroDbStamp? =
        segments.optJSONObject(key)?.readIntroDbStamp(type, IntroDbSource.SkipDb)

    return listOfNotNull(
        one("intro", IntroDbMarkerType.Intro),
        one("recap", IntroDbMarkerType.Recap),
        one("outro", IntroDbMarkerType.Credits),
        one("preview", IntroDbMarkerType.Preview)
    )
}

/**
 * AniSkip's skip times for one anime episode, after the MAL id is resolved.
 *
 * Anime TV episodes only: the endpoint is episode-addressed, so calling it with
 * `episode=1` for an anime FILM would be a wrong lookup rather than a fallback,
 * and a null episode means there is nothing to ask.
 *
 * The stamps it returns are marked [IntroDbSource.AniSkip], which is what keeps
 * them button-only after the merge (see [AutoSkipRules.shouldAutoSkip]).
 */
internal suspend fun fetchAniSkipStamps(
    showTitle: String?,
    episode: Int?,
    durationMs: Long?
): List<IntroDbStamp> {
    val episodeNumber = episode ?: return emptyList()
    val malId = resolveMalId(showTitle) ?: return emptyList()
    val body = fetchIntroDbJson(aniSkipTimesUrl(malId, episodeNumber, durationMs))
        ?: return emptyList()
    return readAniSkipResults(body)
}

/**
 * Reads an AniSkip `/v2/skip-times` body.
 *
 * SECONDS, not milliseconds: AniSkip's `interval.startTime` / `endTime` are float
 * seconds, and they are the one place in this chain where the unit differs. The
 * conversion happens in [readSecondsField], which multiplies by 1000 - a reader
 * that treated these as millis would put every anime skip 1000x too early, and a
 * reader that divided instead would put it 1000x too late.
 *
 * `mixed-op` / `mixed-ed` are mapped alongside `op` / `ed` on the API docs'
 * description of the mixed segments; if the service never returns them under the
 * `types[]` this app asks for, those two rows simply never fire. Every other
 * `skipType` (including `mixed`) is ignored: there is no kind the viewer could be
 * offered for it.
 *
 * AniSkip reports no confidence, so these rows carry `null` ("not stated"). That
 * is exactly why the source marker matters: the confidence gate cannot protect a
 * row that has no confidence, so the button-only rule does it instead.
 */
internal fun readAniSkipResults(root: JSONObject): List<IntroDbStamp> {
    val results = root.optJSONArray("results") ?: return emptyList()

    return (0 until results.length()).mapNotNull { index ->
        readAniSkipResult(results.optJSONObject(index))
    }
}

private fun readAniSkipResult(row: JSONObject?): IntroDbStamp? {
    val result = row ?: return null

    val type = when (result.optString("skipType").trim().lowercase(Locale.ROOT)) {
        "op", "mixed-op" -> IntroDbMarkerType.Intro
        "ed", "mixed-ed" -> IntroDbMarkerType.Credits
        "recap" -> IntroDbMarkerType.Recap
        else -> return null
    }

    val interval = result.optJSONObject("interval") ?: return null

    // Seconds in, milliseconds out - the chain's one unit change.
    val startMs = readSecondsField(interval, "startTime") ?: return null
    val endMs = readSecondsField(interval, "endTime") ?: return null

    if (startMs < 0L || endMs <= startMs) return null

    return IntroDbStamp(
        startMs = startMs,
        endMs = endMs,
        type = type,
        confidence = null,
        source = IntroDbSource.AniSkip
    )
}

/**
 * The MyAnimeList id for [showTitle], from the anime ID hub (spec 2026-11-13).
 *
 * This is the intro-skip chain's anime leg, and the hub is what it asks now: the
 * hub tries AniList first (one call returns the MAL id AND the AniList id), then
 * Kitsu's mapping graph, and only then Jikan - which is where this resolution
 * used to start and end. The title gate and the per-title memo live in the hub,
 * so an anime Detail open and a playback lookup share one answer.
 */
internal suspend fun resolveMalId(showTitle: String?): Int? =
    AnimeIdHub.resolve(showTitle).malId

// ── Anime Skip (the fifth leg, spec 2026-11-13) ───────────────────────────

/** The header Anime Skip requires on every request: the viewer's own client id. */
internal const val ANIME_SKIP_CLIENT_ID_HEADER = "X-Client-ID"

/**
 * A 429 parks the leg for this long. One lookup per episode is the design, but
 * zapping through a season is still bursty, and the chain must not retry inside
 * a window the service has just closed.
 */
private const val ANIME_SKIP_RATE_LIMIT_PARK_MS = 60_000L

@Volatile
private var animeSkipParkedUntilMs = 0L

/** Test hook: forget a parked window. */
internal fun resetAnimeSkipRateLimitForTest() {
    animeSkipParkedUntilMs = 0L
}

/**
 * The one request this leg sends.
 *
 * A body-carrying request (GraphQL has no GET equivalent), with the client id
 * in [ANIME_SKIP_CLIENT_ID_HEADER] and the app's own User-Agent. The header is the
 * only place the credential travels: it is never a query parameter, never a
 * log line and never part of the request body, so nothing that records a URL or
 * a payload can capture it.
 */
internal fun animeSkipRequest(clientId: String, body: String): Request =
    Request.Builder()
        .url(ANIME_SKIP_GRAPHQL_URL)
        .post(body.toRequestBody("application/json".toMediaType()))
        .header("Content-Type", "application/json")
        .header(ANIME_SKIP_CLIENT_ID_HEADER, clientId)
        .header("User-Agent", INTRO_DB_USER_AGENT)
        .build()

internal fun fetchAnimeSkipJson(clientId: String, body: String): JSONObject? {
    if (System.currentTimeMillis() < animeSkipParkedUntilMs) return null
    return runCatching {
        introDbHttpClient.newCall(animeSkipRequest(clientId, body)).execute().use { response ->
            when {
                response.code == 429 -> {
                    animeSkipParkedUntilMs = System.currentTimeMillis() + ANIME_SKIP_RATE_LIMIT_PARK_MS
                    null
                }
                !response.isSuccessful -> null
                else -> JSONObject(response.body?.string().orEmpty())
            }
        }
    }.getOrElse { null }
}

/** The `data` member, or null when the body is an error envelope. */
private fun JSONObject.animeSkipData(): JSONObject? {
    val errors = optJSONArray("errors")
    if (errors != null && errors.length() > 0) return null
    return optJSONObject("data")
}

/**
 * Anime Skip's segments for one anime episode, or none.
 *
 * Anime TV episodes only: everything here is episode-addressed, so a movie (a
 * null episode) never reaches it, and the season is only ever used to pick
 * BETWEEN matches. Read-only - no login, no token, no mutation - and the client
 * id is the only credential, which is why a blank one ends the leg before a
 * request is made.
 *
 * Show resolution, in order:
 *
 *  1. **By AniList id**, the authoritative path: the live schema HAS
 *     `findShowsByExternalId`, its only service is `ANILIST`, and the anime ID
 *     hub already resolved that id for this title (the same resolution the
 *     AniSkip leg makes - one answer, not two).
 *  2. **By title**, only when the hub has no AniList id: `searchShows`, and the
 *     top result is accepted only when its name matches after normalization.
 *
 * Episode match, then the point pairing: [pairAnimeSkipTimestamps] turns the
 * timestamps into segments and keeps the skippable ones, all of them marked
 * [IntroDbSource.AnimeSkip] so the button-only rule applies per stamp.
 *
 * Any failure - no client id, no show, no episode, no timestamps, timeout, 429,
 * an error envelope - is no stamps from this leg and nothing else. The earlier
 * sources are already merged and unaffected.
 */
internal suspend fun fetchAnimeSkipStamps(
    showTitle: String?,
    season: Int?,
    episode: Int?,
    durationMs: Long?,
    clientId: String?
): List<IntroDbStamp> {
    val episodeNumber = episode ?: return emptyList()
    val client = clientId?.trim().orEmpty()
    if (client.isEmpty()) return emptyList()

    val anilistId = AnimeIdHub.resolve(showTitle).anilistId
    val showId = when {
        anilistId != null -> fetchAnimeSkipJson(client, animeSkipShowsByAniListIdQuery(anilistId))
            ?.let { readAnimeSkipShowByAniListId(it, anilistId) }
        else -> null
    } ?: showTitle
        ?.takeIf { it.isNotBlank() }
        ?.let { title ->
            fetchAnimeSkipJson(client, animeSkipShowSearchQuery(title))
                ?.let { readAnimeSkipShowByTitle(it, title) }
        }
        ?: return emptyList()

    val episodesBody = fetchAnimeSkipJson(client, animeSkipEpisodesQuery(showId)) ?: return emptyList()
    val episodes = readAnimeSkipEpisodes(episodesBody)
    val episodeId = episodes.getOrNull(selectAnimeSkipEpisode(episodes, season, episodeNumber) ?: -1)
        ?.id
        ?.takeIf { it.isNotBlank() }
        ?: return emptyList()

    val timestampsBody = fetchAnimeSkipJson(client, animeSkipTimestampsQuery(episodeId))
        ?: return emptyList()

    return pairAnimeSkipTimestamps(readAnimeSkipTimestamps(timestampsBody), durationMs)
}

/**
 * The show whose external AniList link is exactly [anilistId].
 *
 * The filter matters: `serviceId` is matched loosely server-side - a one-digit
 * id comes back with hundreds of shows - so a result is accepted only when it
 * carries a link naming this exact id under the AniList service.
 */
internal fun readAnimeSkipShowByAniListId(root: JSONObject, anilistId: Int): String? {
    val shows = root.animeSkipData()?.optJSONArray("findShowsByExternalId") ?: return null
    val wanted = anilistId.toString()

    for (index in 0 until shows.length()) {
        val show = shows.optJSONObject(index) ?: continue
        val links = show.optJSONArray("externalLinks") ?: continue
        for (linkIndex in 0 until links.length()) {
            val link = links.optJSONObject(linkIndex) ?: continue
            val exact = link.optString("serviceId").trim() == wanted &&
                link.optString("service").trim().equals("anilist.co", ignoreCase = true)
            if (exact) return show.optString("id").takeIf { it.isNotBlank() }
        }
    }
    return null
}

/**
 * The first `searchShows` result whose name is the title we asked about.
 *
 * Same gate as the Jikan title match - normalized, exact, no prefix matching -
 * because this is the path taken when no id is known, and a wrong show's
 * timestamps must never become a skip button.
 */
internal fun readAnimeSkipShowByTitle(root: JSONObject, showTitle: String): String? {
    val shows = root.animeSkipData()?.optJSONArray("searchShows") ?: return null

    for (index in 0 until shows.length()) {
        val show = shows.optJSONObject(index) ?: continue
        val matches = animeTitleMatches(
            showTitle = showTitle,
            title = show.optString("name"),
            titleEnglish = show.optString("originalName"),
            titles = emptyList()
        )
        if (matches) return show.optString("id").takeIf { it.isNotBlank() }
    }
    return null
}

/** A `findEpisodesByShowId` body. Episode numbers are STRINGS in this API. */
internal fun readAnimeSkipEpisodes(root: JSONObject): List<AnimeSkipEpisode> {
    val episodes = root.animeSkipData()?.optJSONArray("findEpisodesByShowId") ?: return emptyList()

    return (0 until episodes.length()).mapNotNull { index ->
        val episode = episodes.optJSONObject(index) ?: return@mapNotNull null
        AnimeSkipEpisode(
            id = episode.optString("id"),
            season = episode.optStringOrNull("season"),
            number = episode.optStringOrNull("number"),
            absoluteNumber = episode.optStringOrNull("absoluteNumber")
        )
    }
}

/**
 * A `findTimestampsByEpisodeId` body, as points.
 *
 * `at` is a FLOAT in seconds - `1365.0833333333333` is a real value - so it is
 * read as a double and converted once, in [pairAnimeSkipTimestamps]. No
 * confidence and no vote field exists on this type, which is why these rows
 * carry none and why the source is button-only.
 */
internal fun readAnimeSkipTimestamps(root: JSONObject): List<AnimeSkipPoint> {
    val timestamps = root.animeSkipData()?.optJSONArray("findTimestampsByEpisodeId")
        ?: return emptyList()

    return (0 until timestamps.length()).mapNotNull { index ->
        val timestamp = timestamps.optJSONObject(index) ?: return@mapNotNull null
        val at = when (val raw = timestamp.opt("at")) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        } ?: return@mapNotNull null
        AnimeSkipPoint(atSeconds = at, typeName = timestamp.optJSONObject("type")?.optString("name"))
    }
}

/** A JSON string field, or null when absent/null/empty (GraphQL sends nulls). */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/**
 * Reads one `/media` body into stamps.
 *
 * v4 answers the grouped timestamps as top-level arrays per segment type
 * (`intro`, `recap`, `credits`, `outro`, `post_credits`, `preview`), exactly as
 * v3 did - the arrays are the documented shape, and a key with no submissions
 * for this media is simply absent. The body's media keys (`tvdb_id`, `type`,
 * `season`, `episode`) are metadata; nothing here needs them, because the
 * caller already knows which episode it asked about.
 *
 * A body that carries no segment arrays reads as no stamps at all, which is
 * what the two alternative v4 bodies (`list_versions`, and the episode-count
 * fallback) are: they must not produce markers.
 */
internal fun readIntroDbMedia(media: JSONObject): List<IntroDbStamp> {
    fun readArray(key: String, type: IntroDbMarkerType): List<IntroDbStamp> {
        val arr = media.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            arr.optJSONObject(it)?.readIntroDbStamp(type, IntroDbSource.TheIntroDb)
        }
    }

    return readArray("intro", IntroDbMarkerType.Intro) +
        readArray("recap", IntroDbMarkerType.Recap) +
        readArray("credits", IntroDbMarkerType.Credits) +
        readArray("outro", IntroDbMarkerType.Outro) +
        readArray("post_credits", IntroDbMarkerType.PostCredits) +
        readArray("post-credits", IntroDbMarkerType.PostCredits) +
        readArray("preview", IntroDbMarkerType.Preview)
}
