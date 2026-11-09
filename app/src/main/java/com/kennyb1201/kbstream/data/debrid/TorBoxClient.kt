package com.kennyb1201.kbstream.data.debrid

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.reporting.TorBoxTrace
import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request

/**
 * Asks TorBox which torrent hashes it already holds, so a source the viewer's
 * own debrid account can serve straight off its CDN can be marked in the
 * picker.
 *
 * This is the other half of the buffering problem the ranker attacks. The
 * ranker can only read what an add-on *claims* about a source; a cached hash
 * is a fact about the viewer's own account — the file is already on TorBox's
 * servers, so it starts at once with no peers to find and no torrent-side
 * stall. Stremio's own debrid add-ons work this way; this brings the same
 * signal to sources from any add-on.
 *
 * One batched request per fetch (the API takes comma-separated hashes, ~100 at
 * a time), and every answer is memoized for [CACHE_TTL_MS] — the endpoint
 * itself caches for an hour, so asking more often than that only burns rate
 * limit. Inert without an API key (see AppPreferences.getTorboxApiKey), the same
 * way the OpenSubtitles search is.
 *
 * Lives outside the ranker: it needs the network and a key, and the ranker is a
 * pure object. The badge it produces is attached after ranking (see
 * [TorBoxCachedBadges]).
 */
internal object TorBoxClient {

    private const val TAG = "TORBOX"
    private const val BASE = "https://api.torbox.app/v1/api/torrents/checkcached"

    /** The account's own TorBox library: everything in its cloud. */
    private const val MYLIST_URL = "https://api.torbox.app/v1/api/torrents/mylist"

    /** The API caps a query at ~100 hashes; larger lists are split into chunks. */
    internal const val BATCH_SIZE = 100

    /**
     * How long an answer is trusted. Matches TorBox's own one-hour cache, so the
     * second request would be answered from their side anyway.
     */
    internal const val CACHE_TTL_MS = 60 * 60_000L

    // Lazy so the pure parts of this object (notably [parseCached], which the
    // unit tests exercise) can be reached without building an HTTP client.
    private val client by lazy {
        BaseHttpClient.derived {
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(20, TimeUnit.SECONDS)
            // A wall-clock ceiling on the whole call (audit P2/P8 class): the
            // cached-hash answer is a cosmetic chip, and a hung connection used
            // to sit on the picker's path with no overall bound at all.
            callTimeout(15, TimeUnit.SECONDS)
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private class Entry(val cached: Boolean, val atMs: Long)

    /** Process-wide memo: hash -> (cached, when learned). */
    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Ceiling on the memo, so browsing many titles cannot grow it without
     * bound. Past this the expired entries go first; if that is not enough, the
     * oldest go too, and those hashes are simply re-queried once.
     */
    private const val MAX_CACHE_ENTRIES = 2000

    private fun pruneCache(nowMs: Long) {
        if (cache.size <= MAX_CACHE_ENTRIES) return
        cache.entries.removeIf { nowMs - it.value.atMs >= CACHE_TTL_MS }
        if (cache.size <= MAX_CACHE_ENTRIES) return
        cache.entries
            .sortedBy { it.value.atMs }
            .take(cache.size - MAX_CACHE_ENTRIES)
            .forEach { cache.remove(it.key) }
    }

    /**
     * The subset of [hashes] already known to be cached, with NO network access
     * and no suspension - the part of [checkCached] that costs nothing.
     *
     * Exists so the source list can be badged instantly on a repeat visit and
     * never wait for a debrid round-trip to reach the player (audit P8).
     */
    fun knownCached(hashes: Collection<String?>): Set<String> {
        if (cache.isEmpty()) return emptySet()
        val now = System.currentTimeMillis()
        return hashes
            .mapNotNull { it?.trim()?.lowercase() }
            .filter { hash ->
                val entry = cache[hash] ?: return@filter false
                entry.cached && now - entry.atMs < CACHE_TTL_MS
            }
            .toSet()
    }

    /**
     * What TorBox says about [hashes]: cached hashes when it ANSWERED, and
     * [TorBoxCachedAnswer.Unavailable] when nothing is known (no key, no hash it
     * could look up, or no batch answered).
     *
     * The distinction is load-bearing for the picker's cached-only filter and
     * invisible to everyone else: the chip pass draws no chip for either, and
     * the resolver plays only what it was told is cached. Carrying an empty set
     * for "could not ask" is what let one failed check look like "none of these
     * are cached yet" (see [TorBoxCachedAnswer]).
     */
    suspend fun checkCached(context: Context, hashes: Collection<String?>): TorBoxCachedAnswer =
        withContext(Dispatchers.IO) {
            val apiKey = AppPreferences.getTorboxApiKey(context)
            if (apiKey.isBlank()) {
                TorBoxTrace.note("${hashes.size} sources · no key, nothing checked")
                return@withContext TorBoxCachedAnswer.Unavailable
            }

            // The same normalization the cached-only filter applies to its rows,
            // so "which hashes did the check look at" and "which rows did it
            // hide" cannot answer differently (see TorBoxCachedOnly).
            val wanted = hashes
                .mapNotNull { TorBoxCachedOnly.lookupHash(it) }
                .toSet()
            if (wanted.isEmpty()) {
                // The answer to "I never see a Cached chip", said in one line:
                // a source with no infohash cannot be looked up at all, and a
                // debrid add-on that has already resolved the torrent hands over
                // a CDN link rather than a hash. A list of those is a chip-less
                // picker with nothing wrong with the key, and this is how a field
                // capture tells that case from a rejected key.
                TorBoxTrace.note("${hashes.size} sources · none carried an infohash")
                return@withContext TorBoxCachedAnswer.Unavailable
            }

            val now = System.currentTimeMillis()
            val toQuery = wanted.filter { hash ->
                cache[hash]?.let { now - it.atMs >= CACHE_TTL_MS } ?: true
            }

            // An empty [toQuery] is already an answer: every hash was fresh in
            // the memo. Otherwise only a batch that came back counts, so "every
            // request failed" stays [TorBoxCachedAnswer.Unavailable] and the
            // cached-only filter keeps its hands off the list.
            var answered = toQuery.isEmpty()
            toQuery.chunked(BATCH_SIZE).forEach { batch ->
                // A failed batch is left uncached so the next fetch can retry it,
                // rather than teaching the memo that everything is uncached.
                val result = runCatching { query(apiKey, batch) }
                    .onFailure { TorBoxTrace.note("checkcached failed: ${it.message}") }
                    .getOrNull() ?: return@forEach
                // One batch is enough: a partially failed check is ANSWERED for
                // the hashes the other batches resolved, and the failed batch's
                // hashes stay unmemoized so the next fetch asks again (spec §2).
                answered = true
                val stamp = System.currentTimeMillis()
                batch.forEach { hash -> cache[hash] = Entry(cached = hash in result, atMs = stamp) }
                pruneCache(stamp)
            }

            if (!answered) {
                // Its own line, because this is the case the cached-only filter
                // must NOT act on: nothing was judged, so nothing may be hidden.
                TorBoxTrace.note(
                    "${hashes.size} sources · ${wanted.size} with a hash · no batch answered"
                )
                return@withContext TorBoxCachedAnswer.Unavailable
            }

            val at = System.currentTimeMillis()
            val cachedNow = wanted.filter { hash ->
                val entry = cache[hash] ?: return@filter false
                entry.cached && at - entry.atMs < CACHE_TTL_MS
            }.toSet()
            TorBoxTrace.note(
                "${hashes.size} sources · ${wanted.size} with a hash · " +
                    "${cachedNow.size} cached"
            )
            TorBoxCachedAnswer.Answered(cachedNow)
        }

    private fun query(apiKey: String, hashes: List<String>): Set<String> {
        val url = "$BASE?hash=${hashes.joinToString(",")}&format=list"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .build()
        client.newCall(request).execute().use { response ->
            // A failed batch THROWS so the caller's runCatching leaves it
            // uncached, which is what the comment there intends. Returning
            // emptySet() instead made a 500 indistinguishable from "none of
            // these are cached", so every hash in the batch was stamped
            // cached=false for the full hour TTL and the picker lost its
            // TorBox badges for those titles.
            if (!response.isSuccessful) {
                Log.w(TAG, "checkcached HTTP ${response.code}")
                throw IllegalStateException("checkcached HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            return parseCachedOrNull(body)
                ?: throw IllegalStateException("checkcached body was not the expected shape")
        }
    }

    /**
     * Reads the cached hashes out of a `format=list` response.
     *
     * The documented shape is `{"data": [{"hash": "..."}, ...]}` — only the
     * cached torrents are listed, so presence *is* the answer. The object format
     * (`{"data": {"<hash>": {...}}}`) is handled too, because a caller that ever
     * switches `format` must not silently start reading nothing.
     */
    internal fun parseCached(body: String): Set<String> = parseCachedOrNull(body) ?: emptySet()

    /**
     * The same read, answering NULL for a body that is not the expected shape at
     * all - the answer [query] needs to tell a genuinely empty list from a
     * broken response. [parseCached] keeps its forgiving contract for callers
     * that only want the hashes.
     */
    private fun parseCachedOrNull(body: String): Set<String>? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        return when (val data = root["data"]) {
            is JsonArray -> data.mapNotNullTo(mutableSetOf()) { element ->
                ((element as? JsonObject)?.get("hash") as? JsonPrimitive)
                    ?.content?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            }

            is JsonObject -> data.keys.mapTo(mutableSetOf()) { it.trim().lowercase() }

            else -> null
        }
    }

    /** One file inside a [CloudTorrent], as `mylist` lists it. */
    data class CloudFile(val id: Int, val name: String, val size: Long = 0L)

    /**
     * One torrent already in the account's TorBox cloud.
     *
     * [id] and [name] are what the library sync has always needed; the rest was
     * added for the cloud-file picker (see TorBoxCloudPicker), which has to know
     * whether the torrent has finished downloading (a still-downloading file is
     * hidden, never greyed) and which files it holds (the file, not the torrent
     * name, is the authority on the episode).
     *
     * Both new halves are FAIL-SOFT: a response that omits them parses exactly
     * as it did before, with [complete] false and no files, so nothing that does
     * not opt into the feature can break on a shape it does not expect.
     */
    data class CloudTorrent(
        val id: Long,
        val name: String,
        /** True when TorBox reports the download as finished/present. */
        val complete: Boolean = false,
        /** 0..1 as the API reports it, when it says anything. */
        val progress: Double = 0.0,
        /** The torrent's files; empty when the response listed none. */
        val files: List<CloudFile> = emptyList()
    )

    /**
     * The account's TorBox torrents (its "My Library"), newest first as the API
     * returns them. Empty without a key, or when the request fails — the same
     * fail-soft contract as [checkCached]. Used by the optional "add TorBox
     * cloud files to Library" sync (see [TorBoxLibrarySync]).
     */
    suspend fun cloudTorrents(context: Context): List<CloudTorrent> =
        cloudTorrentsOrNull(context) ?: emptyList()

    /**
     * [cloudTorrents], answering NULL for "could not ask" - no key, or a failed
     * request - instead of the empty list a genuinely empty cloud answers.
     *
     * The distinction exists for [TorBoxCloudCache]: "you have no files" may be
     * cached for the TTL, "the request failed" may not, or one outage would
     * hide the viewer's own cloud files for a quarter hour. Callers that only
     * want the rows keep using [cloudTorrents].
     */
    suspend fun cloudTorrentsOrNull(context: Context): List<CloudTorrent>? =
        withContext(Dispatchers.IO) {
            val apiKey = AppPreferences.getTorboxApiKey(context)
            if (apiKey.isBlank()) return@withContext null
            runCatching { queryLibrary(apiKey) }
                .onFailure { Log.w(TAG, "mylist failed: ${it.message}") }
                .getOrNull()
        }

    /**
     * Probes the account's key against TorBox's own library route, for the
     * VERIFY action beside the key in Settings. Answers (what the account holds,
     * null) when the key works, or (null, why it failed) when it does not - the
     * same shape `MdbListClient.verifyKey` returns, so both settings panes can
     * render it with the code they already have.
     *
     * This exists because the key's two features both fail soft: a rejected key
     * means no Cached chip AND no library rows, with nothing on screen to say
     * so. A probe is the only thing that can tell the viewer which of the two
     * halves is wrong - and the torrent count it answers with doubles as a check
     * that the library side has something to sync at all.
     */
    suspend fun verifyKey(context: Context): Pair<String?, String?> {
        val apiKey = AppPreferences.getTorboxApiKey(context)
        if (apiKey.isBlank()) return null to "No key pasted yet"
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$MYLIST_URL?bypassCache=true")
                    .header("Authorization", "Bearer $apiKey")
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val count = parseCloudTorrents(response.body?.string().orEmpty()).size
                            val what = if (count == 1) "1 torrent" else "$count torrents"
                            "$what in your TorBox cloud" to null
                        }
                        response.code == 401 || response.code == 403 ->
                            null to "Key rejected — check it on torbox.app → Account → API"
                        else -> null to "TorBox error HTTP ${response.code}"
                    }
                }
            }.getOrElse { e ->
                // Never the key itself: it travels in the Authorization header,
                // not the URL, so an IO message cannot carry it.
                null to (e.message ?: "Network error reaching torbox.app")
            }
        }
    }

    private fun queryLibrary(apiKey: String): List<CloudTorrent> {
        // bypassCache so a torrent just added shows up immediately instead of
        // waiting out TorBox's own 600 s cache.
        val request = Request.Builder()
            .url("$MYLIST_URL?bypassCache=true")
            .header("Authorization", "Bearer $apiKey")
            .build()
        client.newCall(request).execute().use { response ->
            // Throws rather than answering empty: a failed request and an empty
            // cloud are different facts, and [cloudTorrentsOrNull] is what the
            // cloud-file cache reads to tell them apart.
            if (!response.isSuccessful) {
                Log.w(TAG, "mylist HTTP ${response.code}")
                throw IllegalStateException("mylist HTTP ${response.code}")
            }
            return parseCloudTorrents(response.body?.string().orEmpty())
        }
    }

    /**
     * Reads the account's torrents out of a `mylist` response. The documented
     * shape is `{"data": [{"id": 1, "name": "..."}]}`; a torrent with a blank
     * name cannot be looked up by title and is dropped here.
     *
     * The completion state and the file list are read beside them (see
     * [CloudTorrent]); both are optional, so a response that carries neither is
     * still a list of ids and names.
     */
    internal fun parseCloudTorrents(body: String): List<CloudTorrent> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            CloudTorrent(
                id = id,
                name = name,
                complete = isCloudComplete(obj),
                progress = (obj["progress"] as? JsonPrimitive)
                    ?.content?.trim()?.toDoubleOrNull() ?: 0.0,
                files = parseCloudFiles(obj["files"])
            )
        }
    }

    /**
     * Whether a `mylist` entry says its download has finished.
     *
     * Three spellings, because TorBox's own payloads have used each: the boolean
     * `download_finished`, a `download_state` word, and `download_present` (the
     * files are on their servers, which is what a play needs). The progress
     * fraction is the fallback. Silence is NOT completeness: a torrent this
     * cannot classify is hidden rather than offered (spec F0e), so a response
     * shape this build does not know degrades to "no cloud rows" instead of a
     * row that cannot play.
     */
    internal fun isCloudComplete(obj: JsonObject): Boolean {
        (obj["download_finished"] as? JsonPrimitive)
            ?.content?.trim()?.lowercase()
            ?.let { if (it == "true" || it == "false") return it == "true" }
        (obj["download_present"] as? JsonPrimitive)
            ?.content?.trim()?.lowercase()
            ?.let { if (it == "true") return true }
        val state = (obj["download_state"] as? JsonPrimitive)
            ?.content?.trim()?.lowercase().orEmpty()
        if (state in COMPLETE_STATES) return true
        if (state.isNotBlank() && state !in PENDING_STATES) return false
        val progress = (obj["progress"] as? JsonPrimitive)
            ?.content?.trim()?.toDoubleOrNull() ?: return false
        return progress >= 1.0
    }

    /** The file entries of a `mylist` row, in the API's own order. */
    private fun parseCloudFiles(element: JsonElement?): List<CloudFile> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { file ->
            val obj = file as? JsonObject ?: return@mapNotNull null
            // `id_` is the SDK's Python-side spelling of the same field (see
            // TorBoxResolve.parseFiles), so both are read.
            val id = (obj["id"] as? JsonPrimitive ?: obj["id_"] as? JsonPrimitive)
                ?.content?.trim()?.toIntOrNull()
                ?: return@mapNotNull null
            // `short_name` is the file's own name without the folder path; the
            // path is what `name` carries on some responses.
            val name = ((obj["short_name"] as? JsonPrimitive)?.content
                ?: (obj["name"] as? JsonPrimitive)?.content)
                ?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            CloudFile(
                id = id,
                name = name,
                size = (obj["size"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull() ?: 0L
            )
        }
    }

    /** `download_state` words that mean the files are ready to stream. */
    private val COMPLETE_STATES = setOf("completed", "complete", "cached", "finished", "uploaded")

    /** `download_state` words that mean it is still working, whatever it says. */
    private val PENDING_STATES = setOf(
        "downloading", "uploading", "queued", "pending", "stalled", "metaDL".lowercase(),
        "checking", "paused", "error"
    )

}
