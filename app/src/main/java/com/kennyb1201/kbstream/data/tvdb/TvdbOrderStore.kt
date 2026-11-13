package com.kennyb1201.kbstream.data.tvdb

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * A show's chosen episode order, and the cached answers TVDB gave about it
 * (spec 2026-11-13, F1/F2).
 *
 * ## The choice is per profile and device-local
 *
 * Which numbering a viewer reads by is a viewing preference, not account state -
 * the same reasoning the device-local poster prefs use. It is stored under the
 * active profile's own prefs file (`<profileId>.kbstream_episode_order`), so one
 * profile picking DVD order cannot renumber another profile's screens, and it
 * does not ride the sync blob: an order picked on the TV must not silently
 * rewrite the numbering on a phone where the files are numbered the aired way.
 *
 * ## What a choice stores
 *
 * Two things, both keyed by the same `stableShowId` the scheme store uses
 * (`tt...`, else `tmdb:<n>`):
 *
 *  - the chosen season type - the zero-state is "no entry", so a show that never
 *    had an order chosen reads exactly like every show did before this feature;
 *  - the built [TvdbCatalogue] for that type: the order's episodes joined to
 *    their TMDB identities. That join is what the read paths resolve through, and
 *    it is built once per choice (and per refresh) rather than per screen, so no
 *    Detail open, Continue Watching scan or add-on request ever waits on TVDB.
 *
 * ## TTLs
 *
 * The spec's: 24h in memory, 7d on disk for both the series' `seasonTypes` and
 * the chosen order's list, and a 1h failure entry so a dead TVDB is asked once an
 * hour rather than on every Detail open. A FAILED fetch never overwrites a good
 * cache - the previous catalogue stands until a newer one replaces it.
 */
internal object TvdbOrderStore {

    private const val TAG = "TvdbOrderStore"

    const val PREFS = "kbstream_episode_order"

    private const val KEY_ORDER_PREFIX = "order_"
    private const val KEY_CATALOGUE_PREFIX = "catalogue_"
    private const val KEY_TYPES_PREFIX = "types_"
    private const val KEY_FAILURE_PREFIX = "failure_"

    /** 24h in memory. */
    const val MEMORY_TTL_MS = 24L * 60L * 60L * 1000L

    /** 7d on disk. */
    const val DISK_TTL_MS = 7L * 24L * 60L * 60L * 1000L

    /** A failed series read is not retried for an hour. */
    const val FAILURE_TTL_MS = 60L * 60L * 1000L

    private val memoryCatalogue = ConcurrentHashMap<String, Pair<Long, TvdbCatalogue>>()
    private val memoryTypes = ConcurrentHashMap<String, Pair<Long, List<TvdbSeasonType>>>()

    // ── The choice ────────────────────────────────────────────────────────

    /** The chosen season type for [stableShowId], or null for the aired order. */
    fun chosen(context: Context?, stableShowId: String?): String? {
        val key = keyFor(stableShowId) ?: return null
        val raw = runCatching { prefs(context)?.getString(KEY_ORDER_PREFIX + key, null) }
            .getOrNull()
            ?.trim()
        return raw?.takeIf { it.isNotEmpty() && !TvdbOrders.isOfficial(it) }
    }

    /**
     * Stores the choice, replacing any catalogue built for the previous one.
     *
     * Clearing (the aired order) removes the entry entirely rather than writing
     * "official": "no entry" is how this store spells the zero-state, the same
     * way EpisodeSchemeStore spells 1:1.
     */
    fun setChosen(context: Context?, stableShowId: String?, seasonType: String?) {
        val key = keyFor(stableShowId) ?: return
        val normalised = TvdbOrders.normalise(seasonType)
        val store = prefs(context) ?: return
        runCatching {
            if (normalised == null) {
                store.edit()
                    .remove(KEY_ORDER_PREFIX + key)
                    .remove(KEY_CATALOGUE_PREFIX + key)
                    .apply()
            } else {
                store.edit().putString(KEY_ORDER_PREFIX + key, normalised).apply()
            }
        }.onFailure { Log.w(TAG, "could not store order: ${it.javaClass.simpleName}") }
        if (normalised == null) memoryCatalogue.remove(key)
    }

    // ── The built catalogue ───────────────────────────────────────────────

    /** The cached catalogue for [stableShowId], memory first then disk, both inside [DISK_TTL_MS]. */
    fun catalogue(
        context: Context?,
        stableShowId: String?,
        nowMs: Long = System.currentTimeMillis()
    ): TvdbCatalogue? {
        val key = keyFor(stableShowId) ?: return null
        memoryCatalogue[key]?.let { (at, catalogue) ->
            if (nowMs - at < MEMORY_TTL_MS) return catalogue
            memoryCatalogue.remove(key)
        }

        val raw = runCatching { prefs(context)?.getString(KEY_CATALOGUE_PREFIX + key, null) }
            .getOrNull()
            ?: return null
        val decoded = decodeCatalogue(raw) ?: return null
        if (nowMs - decoded.builtAtMs >= DISK_TTL_MS) return null
        // Only a catalogue that actually joins something is a cache entry: one
        // with no TMDB side would renumber nothing and must not shadow a later
        // successful build for a week.
        if (!decoded.mapsAnything) return null
        // Stamped with the BUILD time, not this read: the 24h memory TTL has to
        // be 24h from when the join was built, or a process that re-reads it
        // daily would keep serving a week-old catalogue without ever expiring.
        memoryCatalogue[key] = decoded.builtAtMs to decoded
        return decoded
    }

    fun putCatalogue(context: Context?, stableShowId: String?, catalogue: TvdbCatalogue?) {
        val key = keyFor(stableShowId) ?: return
        val store = prefs(context) ?: return
        val value = catalogue ?: return
        memoryCatalogue[key] = catalogue.builtAtMs to value
        runCatching {
            store.edit().putString(KEY_CATALOGUE_PREFIX + key, value.encode()).apply()
        }.onFailure { Log.w(TAG, "could not store catalogue: ${it.javaClass.simpleName}") }
    }

    /** Drops the built catalogue (the order changed, or its numbers must be re-derived). */
    fun dropCatalogue(context: Context?, stableShowId: String?) {
        val key = keyFor(stableShowId) ?: return
        memoryCatalogue.remove(key)
        runCatching {
            prefs(context)?.edit()?.remove(KEY_CATALOGUE_PREFIX + key)?.apply()
        }
    }

    // ── The series' own orders ────────────────────────────────────────────

    /** The series' `seasonTypes` as last read, or null when none is cached. */
    fun seasonTypes(
        context: Context?,
        stableShowId: String?,
        nowMs: Long = System.currentTimeMillis()
    ): List<TvdbSeasonType>? {
        val key = keyFor(stableShowId) ?: return null
        memoryTypes[key]?.let { (at, types) ->
            if (nowMs - at < MEMORY_TTL_MS) return types
            memoryTypes.remove(key)
        }

        val raw = runCatching { prefs(context)?.getString(KEY_TYPES_PREFIX + key, null) }
            .getOrNull()
            ?: return null
        val decoded = decodeTypes(raw) ?: return null
        val builtAt = runCatching { JSONObject(raw).optLong("at", 0L) }.getOrDefault(0L)
        if (nowMs - builtAt >= DISK_TTL_MS) return null
        memoryTypes[key] = nowMs to decoded
        return decoded
    }

    fun putSeasonTypes(
        context: Context?,
        stableShowId: String?,
        types: List<TvdbSeasonType>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val key = keyFor(stableShowId) ?: return
        val store = prefs(context) ?: return
        memoryTypes[key] = nowMs to types
        runCatching {
            val rows = JSONArray()
            types.forEach { type ->
                rows.put(
                    JSONObject()
                        .put("t", type.type)
                        .put("n", type.name)
                        .put("a", type.alternateName ?: "")
                )
            }
            store.edit()
                .putString(
                    KEY_TYPES_PREFIX + key,
                    JSONObject().put("at", nowMs).put("types", rows).toString()
                )
                .apply()
        }.onFailure { Log.w(TAG, "could not store season types: ${it.javaClass.simpleName}") }
    }

    // ── Failure park ──────────────────────────────────────────────────────

    /** True while a failed series read is still inside its hour. */
    fun recentlyFailed(
        context: Context?,
        stableShowId: String?,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val key = keyFor(stableShowId) ?: return false
        val at = runCatching { prefs(context)?.getLong(KEY_FAILURE_PREFIX + key, 0L) }
            .getOrDefault(0L)
        return at > 0L && nowMs - at < FAILURE_TTL_MS
    }

    fun markFailed(
        context: Context?,
        stableShowId: String?,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val key = keyFor(stableShowId) ?: return
        runCatching {
            prefs(context)?.edit()?.putLong(KEY_FAILURE_PREFIX + key, nowMs)?.apply()
        }
    }

    fun clearFailed(context: Context?, stableShowId: String?) {
        val key = keyFor(stableShowId) ?: return
        runCatching {
            prefs(context)?.edit()?.remove(KEY_FAILURE_PREFIX + key)?.apply()
        }
    }

    /** Test hook: forgets the process's memory copies. */
    internal fun resetMemoryForTest() {
        memoryCatalogue.clear()
        memoryTypes.clear()
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private fun keyFor(stableShowId: String?): String? =
        stableShowId?.trim()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context?) = context?.let { ctx ->
        val app = ctx.applicationContext
        app.getSharedPreferences(ProfileStorage.prefsName(app, PREFS), Context.MODE_PRIVATE)
    }

    private fun decodeTypes(raw: String): List<TvdbSeasonType>? = runCatching {
        val rows = JSONObject(raw).optJSONArray("types") ?: return null
        val out = ArrayList<TvdbSeasonType>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val type = row.optString("t").trim()
            if (type.isEmpty()) continue
            out += TvdbSeasonType(
                name = row.optString("n").trim().ifEmpty { type },
                type = type,
                alternateName = row.optString("a").trim().takeIf { it.isNotEmpty() }
            )
        }
        out
    }.getOrNull()
}
