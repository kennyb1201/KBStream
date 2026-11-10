package com.kennyb1201.kbstream.data.badges

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Stream badge packs, KB-compatible: a pack is a JSON document with a
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

/**
 * A badge resolved onto a stream (a matched filter's display payload).
 *
 * Annotated like every other model in this file: it is nested inside the
 * generated `Stream` adapter, and being the only unannotated model here made it
 * the one adapted reflectively - the shape sweep 5 proved can throw under R8 on
 * its default-argument constructor. Add-on wire JSON never carries `badges`
 * today, so nothing exercises that path yet; this keeps the first Moshi stream
 * cache from inheriting the landmine.
 */
@JsonClass(generateAdapter = true)
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

    /** The pack's own store, profile-scoped like every per-profile setting. */
    internal const val PREFS_NAME = "kbstream_stream_badges"
    internal const val KEY_PACK_JSON = "badge_pack_json"
    internal const val KEY_PACK_URL = "badge_pack_url"

    /**
     * When this device's pack state last changed - a local import, a local
     * removal, or an adoption from a sibling.
     *
     * The same role `SYNCED_AT` plays for the home order and the pinned rails:
     * it is what lets a REMOVAL be compared against the pack it would undo. A
     * removal older than this device's own pack is the sibling not having seen
     * it yet, and must not win.
     */
    internal const val KEY_SYNCED_AT = "badge_pack_synced_at"

    /**
     * When the pack was removed, 0 while one is loaded.
     *
     * A removal used to be nothing but an empty store, which a payload cannot
     * tell apart from a device that never configured a pack - the blank guard's
     * whole reason for existing. This is the fact that separates the two.
     */
    internal const val KEY_CLEARED_AT = "badge_pack_cleared_at"

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val packAdapter = moshi.adapter(StreamBadgePack::class.java)

    private val client = BaseHttpClient.derived {
        connectTimeout(10, TimeUnit.SECONDS)
        readTimeout(20, TimeUnit.SECONDS)
    }

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
            // A pack exists again, so the removal record is spent: a stale
            // cleared_at left behind would let a sibling adopt a removal of a
            // pack that is back.
            .putLong(KEY_CLEARED_AT, 0L)
            .putLong(KEY_SYNCED_AT, System.currentTimeMillis())
            .apply()

        // Cross-device sync: share the imported badge pack.
        publish(context)
        notifyPackChanged()
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

    /**
     * Remove the pack - and record that this was a REMOVAL, not an empty store.
     *
     * The clear is unchanged (the pack URL and JSON go, nothing else in the
     * settings does). What is new is the stamp and the publish: without them the
     * removal only existed on this device until the next bulk push, which sent a
     * blank pack that the other side is right to ignore.
     */
    fun clearPack(context: Context) {
        val now = System.currentTimeMillis()
        prefs(context).edit().clear().apply()
        prefs(context).edit()
            .putLong(KEY_CLEARED_AT, now)
            .putLong(KEY_SYNCED_AT, now)
            .apply()
        publish(context)
        notifyPackChanged()
    }

    /**
     * True when the last pack state was a removal, so Settings can say so.
     *
     * Reads the stamp rather than remembering the press: the line then survives
     * leaving and re-entering Settings, and says the same thing when the removal
     * arrived from the other device.
     */
    fun wasRemoved(context: Context): Boolean =
        !hasPack(context) && prefs(context).getLong(KEY_CLEARED_AT, 0L) > 0L

    /**
     * Ticks whenever the pack itself changes: a local import, a local removal, or
     * a payload adopted from a sibling (see [PrefsPayloadApplier]).
     *
     * The Settings field is `remember`ed state seeded from the store, so it has
     * no other way to learn that the store moved underneath it - which is why a
     * REMOVE left the old URL on screen until the screen was left and entered
     * again.
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun notifyPackChanged() {
        _revision.value += 1
    }

    /** Hand the pack to sync immediately: a removal the viewer just made must land now. */
    private fun publish(context: Context) {
        val appContext = com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext
            ?: context.applicationContext
        com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
            appContext,
            com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_BADGE_PACK,
            com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildBadgePack(appContext)
        )
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
        context.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(context, PREFS_NAME),
            Context.MODE_PRIVATE
        )

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
     * Text fields a badge pattern may match against: KB's candidates
     * (name / title / description) plus the URL. The URL is beyond KB's
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
