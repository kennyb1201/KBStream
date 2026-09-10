package com.kennyb1201.kbstream.data.nuvio

import android.content.Context
import android.util.Log
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Loads Nuvio collections profiles: JSON documents (a top-level array of
 * [NuvioCollectionProfile]) exported from the Nuvio TMDB catalog filter
 * builder and hosted by the user (usually a GitHub raw URL — the same
 * document also carries the hosted cover/hero image URLs).
 *
 * - The list of profile URLs lives in SharedPreferences (via
 *   [NuvioProfilePrefs]) so profiles can be added/removed from Settings.
 * - Downloaded JSON is cached in filesDir with a 12h TTL so returning to
 *   the Collections screen renders instantly from disk; refresh re-fetches.
 * - Parsing is lenient: unknown fields are ignored and a bad top-level
 *   shape yields an empty list instead of throwing.
 */
class NuvioRepository(private val context: Context) {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val profileListAdapter: JsonAdapter<List<NuvioCollectionProfile>> =
        moshi.adapter(
            Types.newParameterizedType(
                List::class.java,
                NuvioCollectionProfile::class.java
            )
        )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cacheMutex = Mutex()
    private val memoryCache = mutableMapOf<String, Pair<Long, List<NuvioCollectionProfile>>>()

    companion object {
        private const val TAG = "NUVIO_REPO"
        private const val CACHE_DIR = "nuvio_collections"
        private const val CACHE_TTL_MS = 12L * 60L * 60L * 1000L

        private fun cacheFileFor(url: String): String =
            // Stable, filesystem-safe key for the profile URL.
            Integer.toHexString(url.hashCode()) + ".json"

        /** True when the string looks like an http(s) profile URL. */
        fun isPlausibleUrl(raw: String): Boolean {
            val trimmed = raw.trim()
            return trimmed.startsWith("http://") || trimmed.startsWith("https://")
        }
    }

    /**
     * All folders across all configured profiles, flattened in profile order
     * (pinToTop collections first), for the single "Collections" screen.
     */
    suspend fun loadAllFolders(forceRefresh: Boolean = false): List<NuvioFolder> =
        loadProfiles(forceRefresh)
            .sortedByDescending { it.pinToTop }
            .flatMap { profile ->
                profile.folders.map { it.withProfileDefaults(profile) }
            }

    /**
     * One folder by id, searching every configured profile. Used by the
     * folder screen when it is reopened directly (process-death restore)
     * without carrying the whole folder object through the saver.
     */
    suspend fun findFolder(folderId: String): NuvioFolder? =
        loadProfiles()
            .asSequence()
            .flatMap { profile ->
                profile.folders.asSequence().map { it.withProfileDefaults(profile) }
            }
            .firstOrNull { it.id == folderId }

    /** All configured profiles, from cache or network. */
    suspend fun loadProfiles(forceRefresh: Boolean = false): List<NuvioCollectionProfile> {
        val urls = NuvioProfilePrefs.getProfileUrls(context)
        if (urls.isEmpty()) return emptyList()

        return urls.mapNotNull { url ->
            runCatching {
                loadProfile(url, forceRefresh)
            }.onFailure { e ->
                Log.e(TAG, "Profile load failed url=$url: ${e.message}")
            }.getOrNull()
        }.flatten()
    }

    /** One profile's collections, cached or fetched. */
    private suspend fun loadProfile(
        url: String,
        forceRefresh: Boolean
    ): List<NuvioCollectionProfile> {
        val now = System.currentTimeMillis()

        cacheMutex.withLock {
            val cached = memoryCache[url]
            if (!forceRefresh && cached != null && now - cached.first < CACHE_TTL_MS) {
                return cached.second
            }
        }

        // Disk cache first (12h TTL), network on miss/expiry.
        val disk = readDiskCache(url)
        if (!forceRefresh && disk != null && now - disk.first < CACHE_TTL_MS) {
            cacheMutex.withLock { memoryCache[url] = disk.first to disk.second }
            return disk.second
        }

        val fresh = fetchAndParse(url)
        cacheMutex.withLock { memoryCache[url] = (now to fresh) }
        writeDiskCache(url, fresh)
        return fresh
    }

    /** Re-fetch every configured profile, bypassing caches. */
    suspend fun refreshAll(): List<NuvioCollectionProfile> = loadProfiles(forceRefresh = true)

    /** Stamps a folder with its parent collection's layout settings. */
    private fun NuvioFolder.withProfileDefaults(
        profile: NuvioCollectionProfile
    ): NuvioFolder = copy(
        viewMode = profile.viewMode,
        showAllTab = profile.showAllTab
    )

    /**
     * Fetch + parse one URL without requiring it to be in the configured
     * list — used to validate an import before it is persisted.
     */
    suspend fun loadProfileForValidation(url: String): List<NuvioCollectionProfile> {
        val now = System.currentTimeMillis()
        val cached = memoryCache[url]
        if (cached != null && now - cached.first < CACHE_TTL_MS) {
            return cached.second
        }
        val fresh = fetchAndParse(url)
        cacheMutex.withLock { memoryCache[url] = now to fresh }
        writeDiskCache(url, fresh)
        return fresh
    }

    /** Remove a URL's caches after it is deleted in Settings. */
    fun evict(url: String) {
        cacheMutex.withLock { memoryCache.remove(url) }
        runCatching {
            File(context.filesDir, CACHE_DIR).apply { mkdirs() }
                .resolve(cacheFileFor(url))
                .delete()
        }
    }

    /** Validate + import a pasted JSON document; returns collection count. */
    suspend fun importFromJson(jsonText: String): Int = withContext(Dispatchers.IO) {
        val parsed = profileListAdapter.fromJson(jsonText)
            ?: throw IllegalArgumentException("Not a Nuvio collections profile")
        parsed.size
    }

    private suspend fun fetchAndParse(url: String): List<NuvioCollectionProfile> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code} fetching collections")
                }
                response.body?.string()
                    ?: throw IllegalStateException("Empty response fetching collections")
            }

            val parsed = profileListAdapter.fromJson(body)
                ?: throw IllegalStateException("Not a Nuvio collections profile")

            parsed.filter { it.folders.isNotEmpty() || it.title.isNotBlank() }
        }

    private data class DiskEntry(val first: Long, val second: List<NuvioCollectionProfile>)

    private suspend fun readDiskCache(url: String): DiskEntry? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
                    .resolve(cacheFileFor(url))
                if (!file.exists()) return@runCatching null
                val text = file.readText()
                // Line 1 is the cache timestamp; the profile JSON follows it.
                val newline = text.indexOf('\n')
                if (newline <= 0) return@runCatching null
                val ts = text.substring(0, newline).toLongOrNull() ?: return@runCatching null
                val parsed = profileListAdapter.fromJson(text.substring(newline + 1))
                    ?: return@runCatching null
                DiskEntry(ts, parsed)
            }.getOrNull()
        }

    private suspend fun writeDiskCache(
        url: String,
        profiles: List<NuvioCollectionProfile>
    ): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
            val tmp = dir.resolve(cacheFileFor(url) + ".tmp")
            val final = dir.resolve(cacheFileFor(url))
            tmp.writeText(
                System.currentTimeMillis().toString() + "\n" +
                    (profileListAdapter.toJson(profiles) ?: "[]")
            )
            if (!tmp.renameTo(final)) {
                final.writeText(
                    System.currentTimeMillis().toString() + "\n" +
                        (profileListAdapter.toJson(profiles) ?: "[]")
                )
                tmp.delete()
            }
        }
    }
}

/**
 * Profile URL list in SharedPreferences. Each entry is a hosted Nuvio
 * collections-profile JSON document; removing it also clears its cache.
 */
object NuvioProfilePrefs {

    private const val PREFS_NAME = "kbstream_nuvio_collections"
    private const val KEY_URLS = "profile_urls"
    private const val KEY_LAST_REFRESH = "last_refresh_ms"

    fun getProfileUrls(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_URLS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun addProfileUrl(context: Context, url: String): Boolean {
        val clean = url.trim()
        if (!NuvioRepository.isPlausibleUrl(clean)) return false
        val current = getProfileUrls(context)
        if (current.any { it.equals(clean, ignoreCase = true) }) return false
        prefs(context).edit().putString(KEY_URLS, (current + clean).joinToString("\n")).apply()
        return true
    }

    fun removeProfileUrl(context: Context, url: String) {
        val remaining = getProfileUrls(context).filterNot { it.equals(url, ignoreCase = true) }
        prefs(context).edit()
            .putString(KEY_URLS, remaining.joinToString("\n"))
            .apply()
    }

    fun getLastRefreshMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_REFRESH, 0L)

    fun setLastRefreshMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_LAST_REFRESH, ms).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
