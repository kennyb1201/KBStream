package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.StreamDrm
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Remembers the debrid link a title was last played from, so replaying it
 * within a few hours skips the addon "Finding sources" round-trip and starts
 * the same URL directly.
 *
 * Debrid download links live a few hours; a replay an afternoon later is
 * exactly the case that paid an unnecessary resolve. The window is deliberately
 * conservative — a link older than [TTL_MS] is treated as gone and resolved
 * fresh, because a stale link costs a failed launch while a fresh resolve costs
 * a couple of seconds.
 *
 * Keyed by the same `streamKey` the auto-play suppression uses, so it is one
 * entry per title/episode. Profile-scoped and never synced: the stored URL is a
 * short-lived host link that means nothing on another profile or device.
 *
 * The 3h window runs from the ORIGINAL play, never from a reuse — reading an
 * entry does not refresh its timestamp, or a title replayed every two hours
 * would pin one link forever and never re-resolve.
 *
 * A hit only ever SKIPS a resolve; a miss runs the exact path it did before.
 * Source order, ranking, auto-quality pick and binge-group logic are untouched.
 */
internal object PlayedLinkCache {

    internal const val TTL_MS = 3 * 60 * 60_000L

    private const val TAG = "PLAYED_LINK_CACHE"
    private const val PREFS_BASE = "kbstream_played_links"
    private const val KEY_ENTRIES = "played_links_v1"

    /** Bounded so a long-lived install cannot grow this file forever. */
    private const val MAX_ENTRIES = 30

    /**
     * Fallbacks worth carrying: the head of the ranked list is what
     * `tryNextSource` would actually walk, and ten is more than it reaches in
     * practice.
     */
    private const val MAX_SOURCES_PER_ENTRY = 10

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Only what the player needs to start a link — not the whole addon stream.
     * `behaviorHints` is deliberately dropped: the rebuilt stream carries a null
     * one, so its derived `bingeGroup` is null too, and binge continuity is
     * decided by the resolve path (which a hit never takes).
     */
    @Serializable
    private data class CachedStream(
        val url: String,
        val audioUrl: String? = null,
        val name: String? = null,
        val title: String? = null,
        val description: String? = null,
        val infoHash: String? = null,
        val fileIdx: Int? = null,
        val headers: Map<String, String> = emptyMap(),
        val drmLicenseUrl: String? = null,
        val drmHeaders: Map<String, String> = emptyMap()
    )

    @Serializable
    private data class Entry(
        val played: CachedStream,
        val sources: List<CachedStream> = emptyList(),
        val atMs: Long = 0L
    )

    @Serializable
    private data class Store(val entries: Map<String, Entry> = emptyMap())

    /** What the auto-play path needs to skip resolution. */
    data class CachedPlay(val played: Stream, val sources: List<Stream>)

    /**
     * Records [played] and the ordered [sources] it came from. A blank key or a
     * blank URL is a no-op: there would be nothing to replay.
     */
    fun remember(context: Context, key: String, played: Stream, sources: List<Stream>) {
        if (key.isBlank() || played.url.isNullOrBlank()) return
        runCatching {
            val entries = LinkedHashMap(read(context).entries)
            entries[key] = Entry(
                played = played.toCached(),
                sources = sources.take(MAX_SOURCES_PER_ENTRY).map { it.toCached() },
                atMs = System.currentTimeMillis()
            )

            // Evict the oldest entries once past the cap.
            if (entries.size > MAX_ENTRIES) {
                entries.entries
                    .sortedBy { it.value.atMs }
                    .take(entries.size - MAX_ENTRIES)
                    .forEach { entries.remove(it.key) }
            }

            write(context, Store(entries))
        }.onFailure {
            Log.w(TAG, "remember played link failed: ${it.message}")
        }
    }

    /**
     * The cached link for [key], or null when there is nothing to reuse: not
     * stored, older than [TTL_MS], or carrying no URL.
     */
    fun get(context: Context, key: String): CachedPlay? {
        if (key.isBlank()) return null
        val entry = runCatching { read(context).entries[key] }.getOrNull() ?: return null
        if (System.currentTimeMillis() - entry.atMs > TTL_MS) return null
        val played = entry.played.toStream() ?: return null
        if (played.url.isNullOrBlank()) return null
        return CachedPlay(played, entry.sources.mapNotNull { it.toStream() })
    }

    fun forget(context: Context, key: String) {
        if (key.isBlank()) return
        runCatching {
            val entries = LinkedHashMap(read(context).entries)
            if (entries.remove(key) != null) write(context, Store(entries))
        }
    }

    /**
     * [forget] for a session pinned to its LAUNCH profile.
     *
     * The active profile can change mid-playback (a switch), and plain [forget]
     * then resolves the NEW profile's store: the launching profile's dead entry
     * survives and a replay of it loops until the TTL (SD-2). A null
     * [profileId] - the legacy no-profiles device - falls back to the active one.
     */
    fun forgetForProfile(context: Context, key: String, profileId: String?) {
        if (key.isBlank()) return
        if (profileId == null) return forget(context, key)
        runCatching {
            val store = prefsFor(context, profileId)
            val entries = LinkedHashMap(readFrom(store).entries)
            if (entries.remove(key) != null) writeTo(store, Store(entries))
        }
    }

    private fun Stream.toCached(): CachedStream = CachedStream(
        url = url.orEmpty(),
        audioUrl = audioUrl,
        name = name,
        title = title,
        description = description,
        infoHash = infoHash,
        fileIdx = fileIdx,
        headers = requestHeaders,
        drmLicenseUrl = drm?.licenseUrl,
        drmHeaders = drm?.headers.orEmpty()
    )

    /** Null for a cached row with no URL — nothing to hand the player. */
    private fun CachedStream.toStream(): Stream? {
        if (url.isBlank()) return null
        return Stream(
            name = name,
            title = title,
            description = description,
            url = url,
            audioUrl = audioUrl,
            infoHash = infoHash,
            fileIdx = fileIdx,
            behaviorHints = null,
            drm = drmLicenseUrl?.let { StreamDrm(licenseUrl = it, headers = drmHeaders) },
            headers = headers.takeIf { it.isNotEmpty() },
            badges = emptyList()
        )
    }

    private fun read(context: Context): Store = readFrom(prefs(context))

    private fun readFrom(prefs: SharedPreferences): Store {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return Store()
        return runCatching { json.decodeFromString(Store.serializer(), raw) }
            .getOrElse {
                // A corrupt blob must never break playback: drop and restart.
                Log.w(TAG, "played-link blob unreadable; resetting (${it.message})")
                Store()
            }
    }

    private fun write(context: Context, store: Store) = writeTo(prefs(context), store)

    private fun writeTo(prefs: SharedPreferences, store: Store) {
        // apply(), never commit(): this runs on the UI thread as playback starts.
        prefs.edit()
            .putString(KEY_ENTRIES, json.encodeToString(Store.serializer(), store))
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences = prefsFor(context, null)

    /** The store of one profile, or the active profile's when [profileId] is null. */
    private fun prefsFor(context: Context, profileId: String?): SharedPreferences =
        context.applicationContext.getSharedPreferences(
            if (profileId == null) {
                ProfileStorage.prefsName(context.applicationContext, PREFS_BASE)
            } else {
                ProfileStorage.prefsName(profileId, PREFS_BASE)
            },
            Context.MODE_PRIVATE
        )
}
