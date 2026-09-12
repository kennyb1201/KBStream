package com.kennyb1201.kbstream.data.badges

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.addon.Stream
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Stream badge packs, Nuvio-compatible: a pack is a JSON document with a
 * `filters` array; each filter pairs a regex [StreamBadgeFilter.pattern]
 * with the badge art/colors to show when the pattern matches a stream's
 * text (name, title, description, filename...). Packs are imported from a
 * URL in Settings, cached on disk, and applied to every fetched stream list.
 */
@JsonClass(generateAdapter = true)
data class StreamBadgePack(
    val filters: List<StreamBadgeFilter> = emptyList(),
    val groups: List<StreamBadgeGroup> = emptyList()
)

@JsonClass(generateAdapter = true)
data class StreamBadgeFilter(
    val id: String? = null,
    val name: String? = null,
    val pattern: String? = null,
    val imageURL: String? = null,
    val isEnabled: Boolean? = null,
    val tagColor: String? = null,
    val tagStyle: String? = null,
    val textColor: String? = null,
    val borderColor: String? = null
)

@JsonClass(generateAdapter = true)
data class StreamBadgeGroup(
    val id: String? = null,
    val name: String? = null,
    val color: String? = null
)

/** A badge resolved onto a stream (a matched filter's display payload). */
data class StreamBadge(
    val name: String,
    val imageURL: String = "",
    val tagColor: String = "",
    val tagStyle: String = "",
    val textColor: String = "",
    val borderColor: String = ""
)

/** One compiled filter: the parsed regex plus its badge display payload. */
private data class CompiledBadgeFilter(
    val regex: Regex,
    val badge: StreamBadge
)

object StreamBadgeEngine {

    private const val TAG = "StreamBadges"
    private const val PREFS_NAME = "kbstream_stream_badges"
    private const val KEY_PACK_JSON = "badge_pack_json"
    private const val KEY_PACK_URL = "badge_pack_url"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val packAdapter = moshi.adapter(StreamBadgePack::class.java)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------
    // Persistence + import
    // ------------------------------------------------------------------

    fun getPackUrl(context: Context): String =
        prefs(context).getString(KEY_PACK_URL, null).orEmpty()

    fun hasPack(context: Context): Boolean =
        prefs(context).getString(KEY_PACK_JSON, null)?.isNotBlank() == true

    /** Number of active (enabled, valid) filters in the loaded pack. */
    fun filterCount(context: Context): Int = loadFilters(context).size

    /** Store a fetched pack; returns false when parsing failed. */
    fun savePack(context: Context, url: String, json: String): Boolean {
        val normalized = normalizePack(json) ?: return false
        prefs(context).edit()
            .putString(KEY_PACK_URL, url.trim())
            .putString(KEY_PACK_JSON, normalized)
            .apply()
        return true
    }

    /** Import from a URL: download, parse, persist. Returns an error message or null on success. */
    suspend fun importFromUrl(context: Context, rawUrl: String): String? =
        withContext(Dispatchers.IO) {
            val url = rawUrl.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext "Enter an http(s) URL"
            }
            try {
                val request = Request.Builder().url(url).build()
                val body = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext "Download failed (HTTP ${response.code})"
                    }
                    response.body?.string()
                } ?: return@withContext "Empty response"
                if (!savePack(context, url, body)) {
                    return@withContext "Not a valid badge pack (no usable filters)"
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "badge import failed: ${e.message}")
                e.message ?: "Import failed"
            }
        }

    fun clearPack(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Parse + normalize a pack JSON: drops filters without name/pattern/valid regex. */
    fun normalizePack(json: String): String? {
        val pack = runCatching { packAdapter.fromJson(json) }.getOrNull() ?: return null
        val usable = pack.filters.filter { filter ->
            val name = filter.name?.trim().orEmpty()
            val pattern = filter.pattern?.trim().orEmpty()
            name.isNotBlank() && pattern.isNotBlank() &&
                runCatching { Regex(pattern) }.isSuccess
        }
        if (usable.isEmpty()) return null
        return runCatching { packAdapter.toJson(pack.copy(filters = usable)) }.getOrNull()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    private fun loadFilters(context: Context): List<CompiledBadgeFilter> {
        val json = prefs(context).getString(KEY_PACK_JSON, null) ?: return emptyList()
        val pack = runCatching { packAdapter.fromJson(json) }.getOrNull() ?: return emptyList()
        return pack.filters.mapNotNull { filter ->
            val pattern = filter.pattern?.trim().orEmpty()
            if (filter.isEnabled == false) return@mapNotNull null
            val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                ?: return@mapNotNull null
            CompiledBadgeFilter(
                regex = regex,
                badge = StreamBadge(
                    name = filter.name?.trim().orEmpty(),
                    imageURL = filter.imageURL.orEmpty(),
                    tagColor = filter.tagColor.orEmpty(),
                    tagStyle = filter.tagStyle.orEmpty(),
                    textColor = filter.textColor.orEmpty(),
                    borderColor = filter.borderColor.orEmpty()
                )
            )
        }
    }

    /**
     * Text fields a badge pattern may match against: Nuvio's candidates
     * (name / title / description) plus the URL. The URL is beyond Nuvio's
     * set — direct-link addons often keep quality/host info only in the
     * link, and packs with host or release-name patterns need it to match.
     */
    private fun matchCandidates(stream: Stream): List<String> = listOfNotNull(
        stream.name,
        stream.title,
        stream.description,
        stream.url
    ).filter { it.isNotBlank() }

    /**
     * Resolve badges for one stream: every enabled filter whose pattern
     * matches any of the stream's text fields, de-duplicated by badge key,
     * in pack order.
     */
    private fun resolveBadges(stream: Stream, filters: List<CompiledBadgeFilter>): List<StreamBadge> {
        if (filters.isEmpty()) return emptyList()
        val candidates = matchCandidates(stream)
        if (candidates.isEmpty()) return emptyList()

        val matched = LinkedHashMap<String, StreamBadge>()
        for (filter in filters) {
            if (candidates.any { filter.regex.containsMatchIn(it) }) {
                val key = filter.badge.imageURL.ifBlank { filter.badge.name }.lowercase()
                if (key !in matched) matched[key] = filter.badge
            }
        }
        return matched.values.toList()
    }

    /** Attach badges to every stream in a list (used after fetching sources). */
    fun apply(streams: List<Stream>, context: Context): List<Stream> {
        val filters = loadFilters(context)
        if (filters.isEmpty() || streams.isEmpty()) return streams
        return streams.map { stream ->
            val badges = resolveBadges(stream, filters)
            if (badges.isEmpty()) stream else stream.copy(badges = badges)
        }
    }
}
