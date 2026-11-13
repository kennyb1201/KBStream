package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
 * Four sources, in a fixed order, merged per segment KIND (F5, kinds combine):
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
 *  4. AniSkip - anime TV episodes only, and last: narrowest (anime only) and the
 *     most expensive (a Jikan title lookup before the skip-times call).
 *
 * Every leg fails on its own: a timeout, a 429 or an unreadable body contributes
 * nothing and the chain moves on. Nothing here ever blocks playback, and nothing
 * here writes to any service.
 *
 * [durationMs] and [showTitle] are optional so an older caller keeps compiling,
 * but all three players pass both: the duration is what makes SkipDB shift its
 * timestamps for a stream whose logo/scene length differs, and the title is the
 * only way to find an anime's MyAnimeList id. A call with no title simply has no
 * AniSkip leg.
 */
internal suspend fun fetchIntroDbStamps(
    parentId: String,
    season: Int?,
    episode: Int?,
    durationMs: Long? = null,
    showTitle: String? = null
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
 * The MyAnimeList id for [showTitle], remembered for the life of the process.
 *
 * Without the cache every episode of one anime would cost a Jikan call; with it,
 * one call per show per session. A successful search that names no matching
 * title is cached too - "this show is not that anime" is an answer, and asking
 * again for the next episode would only repeat it. A FAILED call is not cached:
 * a 429 or an outage must not become a permanent verdict for the session.
 */
internal suspend fun resolveMalId(showTitle: String?): Int? {
    val normalized = normalizeAnimeTitle(showTitle)
    if (normalized.isEmpty()) return null

    malResolutions[normalized]?.let { remembered -> return remembered.malId }

    val body = fetchIntroDbJson(jikanSearchUrl(showTitle ?: "")) ?: return null
    val malId = readJikanMalId(body, showTitle)
    malResolutions[normalized] = MalResolution(malId)
    return malId
}

/**
 * The first Jikan result's MAL id, and only when its title matches [showTitle].
 *
 * The title gate is the whole safety of this leg: Jikan answers a fuzzy search,
 * so asking about a live-action show can return an anime with a similar name, and
 * accepting it would give that live-action episode an anime's OP/ED times.
 */
internal fun readJikanMalId(root: JSONObject, showTitle: String?): Int? {
    val first = root.optJSONArray("data")?.optJSONObject(0) ?: return null
    val malId = first.optInt("mal_id", 0).takeIf { it > 0 } ?: return null

    val titles = first.optJSONArray("titles")
    val names = if (titles == null) {
        emptyList()
    } else {
        (0 until titles.length()).mapNotNull { index ->
            titles.optJSONObject(index)?.optString("title")
        }
    }

    val matches = animeTitleMatches(
        showTitle = showTitle,
        title = first.optString("title"),
        titleEnglish = first.optString("title_english"),
        titles = names
    )

    return if (matches) malId else null
}

/** A remembered Jikan answer: an id, or a "not this anime" verdict. */
private class MalResolution(val malId: Int?)

/** Keyed by [normalizeAnimeTitle], so spacing and case cannot split the cache. */
private val malResolutions = ConcurrentHashMap<String, MalResolution>()

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
