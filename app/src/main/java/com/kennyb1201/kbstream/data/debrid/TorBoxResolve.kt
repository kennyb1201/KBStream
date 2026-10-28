package com.kennyb1201.kbstream.data.debrid

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.network.awaitResponse
import com.kennyb1201.kbstream.data.reporting.TorBoxTrace
import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MultipartBody
import okhttp3.Request

/**
 * Turns a cached infohash source into a link the player can open.
 *
 * A debrid-first add-on (or a plain Torrentio row) hands over a torrent hash and
 * expects the viewer's own account to serve it. This app has no torrent engine,
 * so such a row used to be both unplayable AND unbadged - it sank to the bottom
 * of the ranked list and the picker's player-side SOURCES list dropped it. The
 * Cached chip ([TorBoxCachedBadges]) marked it, which made the dead end worse:
 * the one row labelled "this will start instantly" was the one that did nothing
 * when pressed.
 *
 * This closes that gap for the rows TorBox already holds: creating (or reusing)
 * the torrent in the account and asking for its CDN link is the whole job, and
 * the resulting URL starts in well under a second - exactly the stall the ranker
 * and the chip were built around.
 *
 * Three deliberate limits:
 *
 *  - **Cached rows only.** The hash must already be in the account's cache,
 *    which is the same answer [TorBoxClient.checkCached] gives the badge pass,
 *    so pressing a chipped row costs one more request rather than a download
 *    that may never finish.
 *  - **One resolution per play.** Nothing is resolved while browsing: creating
 *    a torrent is a write to the viewer's cloud, and doing it for every row of
 *    every list would fill their account with files they never watched.
 *  - **Adding is opt-in.** A torrent that is not in the account yet is only
 *    added when the viewer switched that on ("Add missing torrents on play",
 *    off by default - see [AppPreferences.getTorboxResolveCreate]). Switched
 *    off, this plays only what the account already holds and every other hash
 *    row keeps today's behaviour, because an app must not put files in
 *    someone's paid account on its own initiative.
 *  - **Fail soft.** Any failure (no key, no hash, not cached, HTTP error, no
 *    file to pick) answers null and the picker behaves exactly as it did before
 *    this existed. The reason goes to [TorBoxTrace] so the diagnostics report
 *    can say which of those it was.
 *
 * Everything that can be decided without the network is a pure function here
 * ([isUnresolved], [normalizedHash], [pickFile], the parsers) so it is unit
 * tested; the calls themselves are the thin part.
 */
internal object TorBoxResolve {

    private const val TAG = "TORBOX"

    private const val MYLIST_URL = "https://api.torbox.app/v1/api/torrents/mylist"
    private const val CREATE_URL = "https://api.torbox.app/v1/api/torrents/createtorrent"
    private const val REQUEST_DL_URL = "https://api.torbox.app/v1/api/torrents/requestdl"

    /** A hash shorter than this cannot be a torrent infohash. */
    private const val MIN_HASH_LENGTH = 32

    /**
     * Attempts at the download link for a torrent that was just created.
     *
     * A cached torrent still has to be registered on TorBox's side before its
     * files can be linked, and the first `requestdl` can land before that has
     * happened. Two retries cover it; a genuinely broken request still gives up
     * in a couple of seconds rather than holding the tap for long.
     */
    private const val DL_ATTEMPTS = 3
    private const val DL_RETRY_MS = 1_200L

    /** Attempts at reading the file list of a torrent that was just created. */
    private const val FILE_ATTEMPTS = 3

    /**
     * How long a remembered torrent id is trusted. Long enough that re-watching
     * an episode an hour later skips the lookup, short enough that a torrent the
     * viewer deleted from their cloud is re-created rather than referenced.
     */
    private const val ID_TTL_MS = 60 * 60_000L

    /** Ceiling on the id memo, so browsing cannot grow it without bound. */
    private const val MAX_KNOWN_IDS = 500

    private val client by lazy {
        BaseHttpClient.derived {
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(20, TimeUnit.SECONDS)
            // A wall-clock ceiling on the whole call: this runs on the way to the
            // player, so a hung connection must not hold the tap open.
            callTimeout(25, TimeUnit.SECONDS)
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** One file inside a torrent, as `mylist` describes it. */
    data class TorrentFile(val id: Int, val name: String, val size: Long)

    /** A torrent in the account, with the files a play can choose from. */
    data class Torrent(val id: Long, val files: List<TorrentFile>)

    private class Known(val torrentId: Long, val atMs: Long)

    /** hash -> torrent id, so a repeat play skips the account lookup. */
    private val knownIds = ConcurrentHashMap<String, Known>()

    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "mpg", "mpeg", "flv"
    )

    /**
     * A link for [stream], or null when this source cannot be served from the
     * viewer's TorBox account.
     *
     * The stream is only ever a HASH row: a source that already carries a URL is
     * playable as it is and must never be routed through debrid, which would
     * spend an account write to replace a working link.
     */
    suspend fun playableStream(context: Context, stream: Stream): Stream? =
        withContext(Dispatchers.IO) {
            if (!isUnresolved(stream)) return@withContext null
            val hash = normalizedHash(stream.infoHash) ?: return@withContext null
            val apiKey = AppPreferences.getTorboxApiKey(context)
            if (apiKey.isBlank()) return@withContext null
            val allowCreate = AppPreferences.getTorboxResolveCreate(context)

            // Shares the badge pass's memo: when the chip is already on screen
            // this answers without a request, and when it is not (a fresh
            // process, a list whose badge pass has not finished) it asks once.
            val cached = TorBoxClient.checkCached(context, listOf(hash))
            if (hash !in cached) {
                TorBoxTrace.note("resolve · the source is not cached, left unplayable")
                return@withContext null
            }

            runCatching { resolve(apiKey, stream, hash, allowCreate) }
                .onFailure {
                    // Never the key or the hash, and never the exception message:
                    // both are user data, and a download URL carries the key.
                    Log.w(TAG, "resolve failed: ${it.javaClass.simpleName}")
                    TorBoxTrace.note("resolve failed: ${it.javaClass.simpleName}")
                }
                .getOrNull()
        }

    private suspend fun resolve(
        apiKey: String,
        stream: Stream,
        hash: String,
        allowCreate: Boolean
    ): Stream? {
        val torrent = knownTorrent(apiKey, hash, allowCreate)
        if (torrent == null) {
            TorBoxTrace.note("resolve · not in the cloud, adding is switched off")
            return null
        }
        val file = pickFile(
            files = torrent.files,
            fileIdx = stream.fileIdx,
            filename = stream.behaviorHints?.filename
        )
        if (file == null) {
            TorBoxTrace.note("resolve · torrent has no file to play")
            return null
        }
        val url = requestDownloadUrl(apiKey, torrent.id, file.id)
        TorBoxTrace.note("resolve · playing TorBox link (file ${file.id})")
        return playable(stream, url)
    }

    /**
     * [stream] as the player should receive it: the account's CDN link as its
     * address, and the torrent fields that described how to *find* that address
     * cleared, so nothing downstream mistakes the hash for a URL again.
     *
     * The badges are kept: the Cached chip on the row is still true.
     */
    fun playable(stream: Stream, url: String): Stream = stream.copy(
        url = url,
        infoHash = null,
        fileIdx = null,
        behaviorHints = stream.behaviorHints?.copy(filename = null)
    )

    /**
     * The account's torrent for [hash], added first when it is missing and
     * [allowCreate] permits it. Null when the account does not hold it and may
     * not be given it.
     */
    /**
     * The account's entry for [hash], registering a CACHED one that is not in
     * the account yet when [allowCreate] permits it (spec S2/D7).
     *
     * [allowCreate] no longer means "add an uncached torrent on play": TorBox
     * Instant serves only torrents its cache already holds, so a hash the walk
     * got here with is one [TorBoxClient.checkCached] said is cached, and the
     * only thing ever registered is that cached torrent, under the viewer's
     * account. An uncached hash never reaches this call - [playableStream] has
     * already answered null for it - so nothing is downloaded, created or
     * queued on the viewer's behalf.
     */
    private suspend fun knownTorrent(
        apiKey: String,
        hash: String,
        allowCreate: Boolean
    ): Torrent? {
        val now = System.currentTimeMillis()
        knownIds[hash]?.let { entry ->
            if (now - entry.atMs < ID_TTL_MS) {
                // Only a torrent that actually lists files is worth keeping: an
                // entry the viewer deleted from their cloud can still answer
                // with an empty shell, and that would fail the play instead of
                // falling through to a fresh add.
                fetchTorrent(apiKey, entry.torrentId)
                    ?.takeIf { it.files.isNotEmpty() }
                    ?.let { return it }
            }
            knownIds.remove(hash)
        }

        findInAccount(apiKey, hash)?.let { torrent ->
            remember(hash, torrent.id)
            return torrent
        }

        if (!allowCreate) return null

        val id = createTorrent(apiKey, hash)
        TorBoxTrace.note("resolve · added the missing torrent to the cloud")
        remember(hash, id)
        // A torrent that was just created is listed a beat after the call
        // returns; the first `mylist?id` can answer with no files at all.
        repeat(FILE_ATTEMPTS) { attempt ->
            val torrent = fetchTorrent(apiKey, id)
            if (torrent != null && torrent.files.isNotEmpty()) return torrent
            if (attempt < FILE_ATTEMPTS - 1) Thread.sleep(DL_RETRY_MS)
        }
        return Torrent(id, emptyList())
    }

    private fun remember(hash: String, torrentId: Long) {
        if (knownIds.size >= MAX_KNOWN_IDS) knownIds.clear()
        knownIds[hash] = Known(torrentId, System.currentTimeMillis())
    }

    /**
     * The account's own entry for [hash], or null when it does not hold it.
     *
     * `mylist` without an id is the documented way to list the account, and the
     * match is on the hash rather than the name because a name is whatever the
     * uploader called it.
     */
    private suspend fun findInAccount(apiKey: String, hash: String): Torrent? {
        val request = Request.Builder()
            .url(MYLIST_URL)
            .header("Authorization", "Bearer $apiKey")
            .build()
        // awaitResponse, not execute(): a cancelled walk must abort the exchange
        // rather than leave a write running behind the gone screen (spec S3).
        client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "mylist HTTP ${response.code}")
                return null
            }
            return parseTorrentList(response.body?.string().orEmpty())
                .firstOrNull { it.first.equals(hash, ignoreCase = true) }
                ?.let { Torrent(it.second, it.third) }
        }
    }

    /** The account entry for one torrent id, for its file list. */
    private suspend fun fetchTorrent(apiKey: String, torrentId: Long): Torrent? {
        // bypassCache so a torrent created a moment ago is visible instead of
        // waiting out TorBox's own cache on this route.
        val request = Request.Builder()
            .url("$MYLIST_URL?id=$torrentId&bypassCache=true")
            .header("Authorization", "Bearer $apiKey")
            .build()
        client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "mylist?id HTTP ${response.code}")
                return null
            }
            val body = response.body?.string().orEmpty()
            val torrent = parseTorrent(body) ?: return null
            return if (torrent.id > 0) torrent else torrent.copy(id = torrentId)
        }
    }

    /**
     * Adds [hash] to the account and answers its torrent id.
     *
     * The magnet is built from the hash alone: a hash row from an add-on names
     * no trackers, and a cached torrent needs no peers to be served, so there is
     * nothing for a tracker list to contribute. `seed=3` is TorBox's "do not
     * seed" - the file is already on their servers, and a viewer asking to play
     * an episode has not asked to upload it.
     */
    private suspend fun createTorrent(apiKey: String, hash: String): Long {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("magnet", "magnet:?xt=urn:btih:$hash")
            .addFormDataPart("seed", "3")
            .build()
        val request = Request.Builder()
            .url(CREATE_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        client.newCall(request).awaitResponse().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // DUPLICATE_ITEM lands here too: the account already holds what
                // we were about to add, which means the search above missed it
                // (a stale memo, a torrent added on another device). The play
                // then fails soft and the row behaves as it did before this
                // existed, rather than guessing at an id that was never
                // answered.
                Log.w(TAG, "createtorrent HTTP ${response.code}")
                throw IllegalStateException("createtorrent HTTP ${response.code}")
            }
            return parseCreatedTorrentId(text)
                ?: throw IllegalStateException("createtorrent body had no torrent id")
        }
    }

    /**
     * The download link for one file of [torrentId].
     *
     * Retried a couple of times: a torrent that was just created is registered
     * on TorBox's side asynchronously, and the first attempt can land before the
     * file is linkable. `token` is the documented auth for this route (it does
     * not take the bearer header), so the key travels in the query string of one
     * in-memory request - it is never logged, never recorded in the trace, and
     * never shown.
     */
    private suspend fun requestDownloadUrl(apiKey: String, torrentId: Long, fileId: Int): String {
        var lastFailure: Throwable? = null
        repeat(DL_ATTEMPTS) { attempt ->
            // delay, not Thread.sleep: this runs on a coroutine, and the wait
            // has to be cancellable like the calls around it (spec S3).
            if (attempt > 0) delay(DL_RETRY_MS)
            runCatching { requestDownloadOnce(apiKey, torrentId, fileId) }
                .onSuccess { return it }
                .onFailure { lastFailure = it }
        }
        throw lastFailure ?: IllegalStateException("requestdl failed")
    }

    private suspend fun requestDownloadOnce(apiKey: String, torrentId: Long, fileId: Int): String {
        val request = Request.Builder()
            .url("$REQUEST_DL_URL?token=$apiKey&torrent_id=$torrentId&file_id=$fileId&redirect=false")
            .build()
        client.newCall(request).awaitResponse().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("requestdl HTTP ${response.code}")
            }
            return parseDownloadUrl(text)
                ?: throw IllegalStateException("requestdl body had no link")
        }
    }

    // ── pure rules ───────────────────────────────────────────────────────────

    /**
     * Whether [stream] is a hash row that needs resolving: no URL of its own and
     * an infohash to resolve. A source with a URL is playable already.
     */
    fun isUnresolved(stream: Stream): Boolean =
        stream.url.isNullOrBlank() && !stream.infoHash.isNullOrBlank()

    /** The torrent infohash in the spelling the API expects, or null if absent. */
    fun normalizedHash(infoHash: String?): String? =
        infoHash?.trim()?.lowercase()?.takeIf { it.length >= MIN_HASH_LENGTH }

    /**
     * The file a play should request.
     *
     * Preference order:
     *
     *  1. the add-on's own `fileIdx`, when the torrent has a file with that id -
     *     the add-on chose that file for this episode, and it is right far more
     *     often than any guess about names;
     *  2. the hinted filename, compared on the full name and on the basename;
     *  3. the largest video file (a season pack's biggest entry is one of its
     *     episodes, which is wrong, but it is at least a playable video);
     *  4. the largest file at all.
     *
     * Answers null only when the torrent lists no files at all.
     */
    fun pickFile(files: List<TorrentFile>, fileIdx: Int?, filename: String?): TorrentFile? {
        if (files.isEmpty()) return null
        if (fileIdx != null) {
            files.firstOrNull { it.id == fileIdx }?.let { return it }
        }
        if (!filename.isNullOrBlank()) {
            val wanted = filename.trim()
            val base = wanted.substringAfterLast('/').substringAfterLast('\\')
            files.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return it }
            files.firstOrNull {
                val candidate = it.name.substringAfterLast('/').substringAfterLast('\\')
                candidate.equals(base, ignoreCase = true)
            }?.let { return it }
        }
        return files.filter { extensionOf(it.name) in VIDEO_EXTENSIONS }
            .maxByOrNull { it.size }
            ?: files.maxByOrNull { it.size }
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").trim().lowercase()

    // ── response readers ─────────────────────────────────────────────────────

    /**
     * Reads `{"data": {"torrent_id": 123, "hash": "..."}}`.
     *
     * The id arrives as a JSON number on the documented shape; a string is
     * accepted too because every TorBox id field has been seen in both spellings
     * across their SDKs.
     */
    fun parseCreatedTorrentId(body: String): Long? {
        val data = dataOf(body) as? JsonObject ?: return null
        return (data["torrent_id"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()
    }

    /**
     * Reads a `mylist` answer for ONE torrent: `data` is documented as an object
     * there, but the generated SDK models it as a list, so both are accepted.
     */
    fun parseTorrent(body: String): Torrent? = parseTorrentList(body).firstOrNull()
        ?.let { Torrent(it.second, it.third) }

    /** Every torrent in a `mylist` answer, as (hash, id, files). */
    fun parseTorrentList(body: String): List<Triple<String, Long, List<TorrentFile>>> {
        val data = dataOf(body) ?: return emptyList()
        val entries = when (data) {
            is JsonArray -> data
            is JsonObject -> JsonArray(listOf(data))
            else -> return emptyList()
        }
        return entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()
                ?: return@mapNotNull null
            val hash = (obj["hash"] as? JsonPrimitive)?.content?.trim().orEmpty()
            Triple(hash, id, parseFiles(obj["files"]))
        }
    }

    private fun parseFiles(element: JsonElement?): List<TorrentFile> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { file ->
            val obj = file as? JsonObject ?: return@mapNotNull null
            // The SDK's models rename `id` to `id_` (a Python-side spelling), so
            // both are read rather than trusting one generated name.
            val id = (obj["id"] as? JsonPrimitive ?: obj["id_"] as? JsonPrimitive)
                ?.content?.trim()?.toIntOrNull()
                ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val size = (obj["size"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull() ?: 0L
            TorrentFile(id = id, name = name, size = size)
        }
    }

    /** Reads `{"data": "https://..."}`, and the object spelling defensively. */
    fun parseDownloadUrl(body: String): String? {
        val data = dataOf(body) ?: return null
        val url = when (data) {
            is JsonPrimitive -> data.content
            is JsonObject -> (data["url"] as? JsonPrimitive)?.content
            else -> null
        }?.trim().orEmpty()
        return url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun dataOf(body: String): JsonElement? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        return root["data"]
    }
}
