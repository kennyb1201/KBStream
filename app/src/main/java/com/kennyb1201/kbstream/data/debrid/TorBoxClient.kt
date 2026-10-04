package com.kennyb1201.kbstream.data.debrid

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

    /** A hash shorter than this cannot be a torrent infohash; skip the query. */
    private const val MIN_HASH_LENGTH = 32

    // Lazy so the pure parts of this object (notably [parseCached], which the
    // unit tests exercise) can be reached without building an HTTP client.
    private val client by lazy {
        BaseHttpClient.derived {
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(20, TimeUnit.SECONDS)
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
     * The subset of [hashes] TorBox reports as cached. Empty when no key is set,
     * the list carries no usable hashes, or every request failed — in which case
     * nothing is badged and the picker is exactly as it was before this existed.
     */
    suspend fun checkCached(context: Context, hashes: Collection<String?>): Set<String> =
        withContext(Dispatchers.IO) {
            val apiKey = AppPreferences.getTorboxApiKey(context)
            if (apiKey.isBlank()) return@withContext emptySet()

            val wanted = hashes
                .mapNotNull { it?.trim()?.lowercase() }
                .filter { it.length >= MIN_HASH_LENGTH }
                .toSet()
            if (wanted.isEmpty()) return@withContext emptySet()

            val now = System.currentTimeMillis()
            val toQuery = wanted.filter { hash ->
                cache[hash]?.let { now - it.atMs >= CACHE_TTL_MS } ?: true
            }

            toQuery.chunked(BATCH_SIZE).forEach { batch ->
                // A failed batch is left uncached so the next fetch can retry it,
                // rather than teaching the memo that everything is uncached.
                val result = runCatching { query(apiKey, batch) }.getOrNull() ?: return@forEach
                val stamp = System.currentTimeMillis()
                batch.forEach { hash -> cache[hash] = Entry(cached = hash in result, atMs = stamp) }
            }

            val at = System.currentTimeMillis()
            wanted.filter { hash ->
                val entry = cache[hash] ?: return@filter false
                entry.cached && at - entry.atMs < CACHE_TTL_MS
            }.toSet()
        }

    private fun query(apiKey: String, hashes: List<String>): Set<String> {
        val url = "$BASE?hash=${hashes.joinToString(",")}&format=list"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "checkcached HTTP ${response.code}")
                return emptySet()
            }
            return parseCached(body = response.body?.string().orEmpty())
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
    internal fun parseCached(body: String): Set<String> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return emptySet()
        return when (val data = root["data"]) {
            is JsonArray -> data.mapNotNullTo(mutableSetOf()) { element ->
                ((element as? JsonObject)?.get("hash") as? JsonPrimitive)
                    ?.content?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            }

            is JsonObject -> data.keys.mapTo(mutableSetOf()) { it.trim().lowercase() }

            else -> emptySet()
        }
    }

    /** One torrent already in the account's TorBox cloud. */
    data class CloudTorrent(
        val id: Long,
        val name: String
    )

    /**
     * The account's TorBox torrents (its "My Library"), newest first as the API
     * returns them. Empty without a key, or when the request fails — the same
     * fail-soft contract as [checkCached]. Used by the optional "add TorBox
     * cloud files to Library" sync (see [TorBoxLibrarySync]).
     */
    suspend fun cloudTorrents(context: Context): List<CloudTorrent> =
        withContext(Dispatchers.IO) {
            val apiKey = AppPreferences.getTorboxApiKey(context)
            if (apiKey.isBlank()) return@withContext emptyList()
            runCatching { queryLibrary(apiKey) }
                .onFailure { Log.w(TAG, "mylist failed: ${it.message}") }
                .getOrDefault(emptyList())
        }

    private fun queryLibrary(apiKey: String): List<CloudTorrent> {
        // bypassCache so a torrent just added shows up immediately instead of
        // waiting out TorBox's own 600 s cache.
        val request = Request.Builder()
            .url("$MYLIST_URL?bypassCache=true")
            .header("Authorization", "Bearer $apiKey")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "mylist HTTP ${response.code}")
                return emptyList()
            }
            return parseCloudTorrents(response.body?.string().orEmpty())
        }
    }

    /**
     * Reads the account's torrents out of a `mylist` response. The documented
     * shape is `{"data": [{"id": 1, "name": "..."}]}`; a torrent with a blank
     * name cannot be looked up by title and is dropped here.
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
            CloudTorrent(id = id, name = name)
        }
    }

    /** Test seam: drop the memo so a case starts from a known-empty state. */
    internal fun clearCache() = cache.clear()
}
