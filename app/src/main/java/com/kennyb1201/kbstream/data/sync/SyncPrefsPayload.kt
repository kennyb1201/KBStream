package com.kennyb1201.kbstream.data.sync

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Prefs accessor resolved against the ACTIVE profile's namespace — the sync
 * layer must read/write the same store the app reads/writes, otherwise cloud
 * pushes and pulls would cross profiles (the "kb leak"). With no profiles
 * this resolves to the legacy un-namespaced store, unchanged behavior.
 */
private fun scopedPrefs(context: Context, baseName: String) =
    context.getSharedPreferences(
        com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(context, baseName),
        Context.MODE_PRIVATE
    )

/**
 * Base name of a prefs store saved by builds before the N-U-V-I-O rename.
 * Assembled from fragments so the retired product name never appears
 * verbatim in source, while the one-time migrations below can still read
 * (and drain) the old stores on upgrading devices.
 */
private fun legacyStoreName(tail: String) = "kbstream_" + "nu" + "vio" + "_$tail"

// Prefs store + key holding the profile's Simkl session (owned by
// SimklRepository; spelled out here so the blob and the repository agree).
private const val SIMKL_AUTH_STORE = "simkl_auth"
private const val SIMKL_ACCESS_TOKEN_KEY = "access_token"

// Display-prefs store, plus the LOCAL bookkeeping that gives the display blob
// its per-key edit timestamps. These shadow keys are never published —
// SYNCED_PREF_KEYS is what actually leaves the device.
private const val DISPLAY_PREFS_STORE = "kbstream_player_prefs"
private const val DISPLAY_SNAPSHOT_KEY = "__sync_snapshot__"
private const val DISPLAY_TS_PREFIX = "__sync_ts__"
private const val DISPLAY_SYNCED_AT_KEY = "display_prefs_synced_at"

/**
 * Canonical string for a stored pref value. Matches the JSON primitive the
 * applier writes back (booleans as "true"/"false", numbers via toString), so
 * a value that round-trips through the cloud compares equal to itself.
 */
private fun prefsValueString(raw: Any?): String? =
    when (raw) {
        null -> null
        is Boolean, is Int, is Long, is Float, is String -> raw.toString()
        else -> null
    }

/** Values of the synced keys as of the last build on this device. */
private fun readDisplaySnapshot(
    prefs: android.content.SharedPreferences
): Map<String, String> {
    val raw = prefs.getString(DISPLAY_SNAPSHOT_KEY, null) ?: return emptyMap()
    return runCatching {
        val obj =
            kotlinx.serialization.json.Json.parseToJsonElement(raw) as?
                JsonObject ?: return emptyMap()
        obj.mapNotNull { (key, value) ->
            ((value as? kotlinx.serialization.json.JsonPrimitive)?.content)?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())
}

/** When each synced key last changed on this device. */
private fun readDisplayTimestamps(
    prefs: android.content.SharedPreferences
): Map<String, Long> {
    val out = mutableMapOf<String, Long>()
    prefs.all.forEach { (key, value) ->
        if (key.startsWith(DISPLAY_TS_PREFIX)) {
            (value as? Number)?.let { out[key.removePrefix(DISPLAY_TS_PREFIX)] = it.toLong() }
        }
    }
    return out
}

/**
 * Builds and applies the keyed JSON blobs stored in sync_prefs.
 *
 * Sync scope (user request):
 *  - SYNCED: display prefs, addon install JSON, Simkl auth token,
 *    IPTV playlist/EPG config, watched-override set, and the player's
 *    subtitle appearance + preferred audio/subtitle language. The language
 *    setters already called syncDisplayPrefsBlob, but their keys were not in
 *    the synced set — so the push was a silent no-op ("language didn't
 *    sync") and the same was true for the subtitle keys.
 *  - EXCLUDED: decoder/buffer/tunneling/Dolby Vision compat/aspect
 *    ratio/PiP — genuinely per-device, and they differ between e.g. a Fire
 *    TV Stick and a projector.
 *
 * Each blob has its own stable pref_key, so devices only merge what the
 * key covers and playback keys simply never exist in the cloud table.
 */
object PrefsPayloadBuilder {

    // Display/UI prefs that DO sync (must match AppPreferences key names).
    // Internal rather than private so the set itself is unit tested — a key
    // missing here is a silent no-op push ("it didn't sync").
    internal val SYNCED_PREF_KEYS = setOf(
        "auto_play_next",                    // convenience behavior, not device-specific
        "use_stream_ranker",
        "hero_trailer_autoplay",
        "hero_trailer_muted",
        "use_24h_clock",
        "home_rail_show_catalog_type",
        "home_rail_show_addon_name",
        "search_rail_show_catalog_type",
        "search_rail_show_addon_name",
        "poster_size",                       // Small/Medium/Large poster tiles
        "poster_caption_title",
        "poster_caption_year",
        "poster_caption_rating",
        "home_rail_hide_upcoming",
        "home_landscape_cards",
        "poster_partial_watch_badge",
        "binge_group_prefer",                // binge continuity, not device-specific
        "binge_group_reuse",
        "binge_group_fallback",
        "still_there_prompt",                // binge watchdog, not device-specific
        "still_there_episodes",
        "new_episode_notifications",         // new-episode alerts: behavior, not device-specific
        "live_reminder_notifications",       // live TV reminder alerts: same reasoning
        "amoled_black",                      // AMOLED theme toggle (pure display pref)
        "pure_black_surface",                // Pure black cards/panels/containers toggle
        "mdblist_api_key",
        "opensubtitles_api_key",             // player → search subtitles online
        "default_subtitle_size",             // subtitle appearance: same on every device
        "default_subtitle_bg",
        "preferred_audio_language",          // preferred track languages: same on every device
        "preferred_subtitle_language"
    )

    // Decoder/playback prefs that NEVER sync (documented for clarity).
    private val EXCLUDED_PREF_KEYS = setOf(
        "default_buffer_mode",
        "auto_select_stream",
        "force_software_decoder",
        "enable_tunneling",
        "enable_pip",
        "decoder_mode",
        "decoder_priority",
        "video_decoder",
        "audio_decoder",
        "dv_compat_mode",
        "strip_hdr10_plus",
        "dv_convert_p7_to_81",
        "dv_convert_p5_to_81",
        "dv_p5_gles_correction",
        "default_aspect_ratio"
    )

    const val KEY_DISPLAY_PREFS = "display_prefs"
    const val KEY_ADDONS = "addons"
    const val KEY_SIMKL_AUTH = "simkl_auth"
    const val KEY_IPTV = "iptv_config"
    const val KEY_WATCHED_OVERRIDES = "watched_overrides"
    const val KEY_HOME_ORDER = "kb_home_order"
    const val KEY_COLLECTIONS = "kb_collections"
    const val KEY_BADGE_PACK = "badge_pack"
    const val KEY_LIBRARY = "library"
    const val KEY_DISMISSALS = "dismissals"
    const val KEY_PROFILES = "profiles"

    fun buildAll(context: Context): List<Pair<String, JsonObject>> = listOf(
        KEY_DISPLAY_PREFS to buildDisplayPrefs(context),
        KEY_ADDONS to buildAddons(context),
        // The bulk push skips this blob when it carries nothing (see
        // SupabaseSync.pushPrefsBlobs) — an empty Simkl blob published by a
        // device that never connected erases the account's session elsewhere.
        KEY_SIMKL_AUTH to buildSimklAuth(context),
        KEY_IPTV to buildIptv(context),
        KEY_WATCHED_OVERRIDES to buildWatchedOverrides(context),
        KEY_HOME_ORDER to buildHomeOrder(context),
        KEY_COLLECTIONS to buildCollections(context),
        KEY_BADGE_PACK to buildBadgePack(context),
        KEY_LIBRARY to buildLibrary(context),
        KEY_DISMISSALS to buildDismissals(context),
        KEY_PROFILES to com.kennyb1201.kbstream.data.sync.ProfileManager.profilesSyncBlob(context)
    )

    /**
     * True when this device has something to say about the Simkl session: a
     * token to share, or a deliberate disconnect to propagate. Everything else
     * (the common "never connected Simkl here" case) must stay out of the
     * cloud — see [SimklAuthRules].
     */
    fun hasSimklAuthToPublish(context: Context): Boolean {
        val prefs = scopedPrefs(context, SIMKL_AUTH_STORE)
        val token = prefs.getString(SIMKL_ACCESS_TOKEN_KEY, null)
        val signedOut = token.isNullOrBlank() &&
            prefs.getBoolean(SimklAuthRules.SIGNED_OUT_FIELD, false)
        return SimklAuthRules.shouldPublish(token, signedOut)
    }

    fun buildBadgePack(context: Context): JsonObject {
        val prefs = scopedPrefs(context, "kbstream_stream_badges")
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put("badge_pack_url", prefs.getString("badge_pack_url", null).orEmpty())
            put("badge_pack_json", prefs.getString("badge_pack_json", null).orEmpty())
        }
    }

    fun buildHomeOrder(context: Context): JsonObject {
        val prefs = scopedPrefs(context, "kbstream_kb_home_order")
        // One-time legacy migration: orders saved under the pre-rename
        // store name carry into the new key so a user's home arrangement
        // survives the rename.
        val legacy = scopedPrefs(context, legacyStoreName("home_order"))
        if (prefs.all.isEmpty() && legacy.all.isNotEmpty()) {
            legacy.all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> prefs.edit().putBoolean(k, v).apply()
                    is Float -> prefs.edit().putFloat(k, v).apply()
                    is Int -> prefs.edit().putInt(k, v).apply()
                    is Long -> prefs.edit().putLong(k, v).apply()
                    is String -> prefs.edit().putString(k, v).apply()
                    is Set<*> -> @Suppress("UNCHECKED_CAST")
                    prefs.edit().putStringSet(k, v as Set<String>).apply()
                }
            }
            legacy.edit().clear().apply()
        }
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(
                "home_order_json",
                prefs.getString("home_order_json", null).orEmpty()
            )
        }
    }

    fun buildCollections(context: Context): JsonObject {
        val prefs = scopedPrefs(context, "kbstream_kb_collections")
        // One-time legacy migration from the pre-rename collections
        // store (same shape, old name).
        val legacy = scopedPrefs(context, legacyStoreName("collections"))
        if (prefs.all.isEmpty() && legacy.all.isNotEmpty()) {
            legacy.all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> prefs.edit().putBoolean(k, v).apply()
                    is Float -> prefs.edit().putFloat(k, v).apply()
                    is Int -> prefs.edit().putInt(k, v).apply()
                    is Long -> prefs.edit().putLong(k, v).apply()
                    is String -> prefs.edit().putString(k, v).apply()
                    is Set<*> -> @Suppress("UNCHECKED_CAST")
                    prefs.edit().putStringSet(k, v as Set<String>).apply()
                }
            }
            legacy.edit().clear().apply()
        }
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            putJsonArray("profile_urls") {
                prefs.getString("profile_urls", null).orEmpty()
                    .split('\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { add(it) }
            }
        }
    }

    /**
     * The display blob carries the time every key last CHANGED on this device
     * (not the time it was pushed — see [DisplayPrefsRules]), so a push that
     * happens to be newer than another device's edit can no longer revert
     * that edit for every key at once.
     */
    fun buildDisplayPrefs(context: Context): JsonObject {
        val prefs = scopedPrefs(context, DISPLAY_PREFS_STORE)
        val all = prefs.all

        val current =
            LinkedHashMap<String, String>()
        SYNCED_PREF_KEYS.forEach { key ->
            prefsValueString(all[key])?.let { current[key] = it }
        }

        val now =
            System.currentTimeMillis()
        val timestamps =
            DisplayPrefsRules.stampChanged(
                previous = readDisplaySnapshot(prefs),
                current = current,
                timestamps = readDisplayTimestamps(prefs),
                now = now,
                hadSnapshot = prefs.contains(DISPLAY_SNAPSHOT_KEY)
            )

        // Remember what was just observed: the next build diffs against this,
        // so a key only counts as a local edit when its VALUE moved.
        val editor =
            prefs.edit()
                .putString(
                    DISPLAY_SNAPSHOT_KEY,
                    buildJsonObject {
                        current.forEach { (key, value) -> put(key, value) }
                    }.toString()
                )
        timestamps.forEach { (key, ts) -> editor.putLong(DISPLAY_TS_PREFIX + key, ts) }
        editor.apply()

        return buildJsonObject {
            put(
                DisplayPrefsRules.UPDATED_AT_FIELD,
                DisplayPrefsRules.blobUpdatedAt(timestamps, now)
            )
            SYNCED_PREF_KEYS.forEach { key ->
                val value = all[key] ?: return@forEach
                when (value) {
                    is Boolean -> put(key, value)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Float -> put(key, value.toDouble())
                    is String -> put(key, value)
                }
            }
            putJsonObject(DisplayPrefsRules.TIMESTAMPS_FIELD) {
                current.keys.forEach { key ->
                    timestamps[key]?.let { put(key, it) }
                }
            }
        }
    }

    fun buildAddons(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(
                "installed_addons_json",
                scopedPrefs(context, "kbstream_addons")
                    .getString("installed_addons_json", null).orEmpty()
            )
        }

    fun buildSimklAuth(context: Context): JsonObject {
        val prefs = scopedPrefs(context, SIMKL_AUTH_STORE)
        val token = prefs.getString(SIMKL_ACCESS_TOKEN_KEY, null)
        // Tombstone: a blank token is a real sign-out ONLY when this profile
        // deliberately disconnected. It also has to be blank — a stale marker
        // left over from an earlier disconnect must not tag a live token.
        val signedOut = token.isNullOrBlank() &&
            prefs.getBoolean(SimklAuthRules.SIGNED_OUT_FIELD, false)
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(SIMKL_ACCESS_TOKEN_KEY, token.orEmpty())
            put(SimklAuthRules.SIGNED_OUT_FIELD, signedOut)
        }
    }

    fun buildIptv(context: Context): JsonObject {
        val prefs = scopedPrefs(context, "iptv_prefs")
        // Guide-level sets live in their own scoped store and must ride the
        // same payload row — hidden channels/groups that don't cross devices
        // make one TV's guide diverge from the other's.
        val guidePrefs = scopedPrefs(context, "iptv_guide_preferences")
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put("playlist_url", prefs.getString("playlist_url", null).orEmpty())
            put("playlist_name", prefs.getString("playlist_name", null).orEmpty())
            put("epg_url", prefs.getString("epg_url", null).orEmpty())
            putJsonArray("extra_epg_urls") {
                prefs.getString("extra_epg_urls", "").orEmpty()
                    .split('\n', ';')
                    .mapNotNull { it.trim().takeIf(String::isNotBlank) }
                    .forEach { add(it) }
            }
            putJsonArray("extra_playlist_urls") {
                prefs.getString("extra_playlist_urls", "").orEmpty()
                    .split('\n', ';')
                    .mapNotNull { it.trim().takeIf(String::isNotBlank) }
                    .forEach { add(it) }
            }
            putJsonArray("hidden_channel_ids") {
                prefs.getStringSet("hidden_channel_ids", emptySet()).orEmpty().forEach { add(it) }
            }
            putJsonArray("hidden_groups") {
                guidePrefs.getStringSet("hidden_groups", emptySet()).orEmpty().forEach { add(it) }
            }
            putJsonArray("favorites") {
                guidePrefs.getStringSet("favorites", emptySet()).orEmpty().forEach { add(it) }
            }
        }
    }

    fun buildWatchedOverrides(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            putJsonArray("overrides") {
                scopedPrefs(context, "kbstream_watched_overrides")
                    .getStringSet("watched_overrides", emptySet()).orEmpty().forEach { add(it) }
            }
        }

    /**
     * Local library (My List + personal lists) as two raw JSON strings —
     * the payloads mirror LocalLibraryStore's on-disk encoding exactly, so
     * the applier can hand them straight back without re-encoding. Full
     * replace on apply: the latest writer wins, matching how the store
     * itself writes.
     */
    fun buildLibrary(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            val prefs = scopedPrefs(context, "kbstream_library")
            put("my_list", prefs.getString("my_list", null) ?: "")
            put("personal_lists", prefs.getString("personal_lists", null) ?: "")
        }

    /**
     * Continue-watching/upcoming dismissals as one raw JSON object string
     * (key -> dismissedAtMillis). Built/reparsed opaquely — the writer owns
     * the encoding.
     */
    fun buildDismissals(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            val prefs = scopedPrefs(context, "continue_watching_dismissals")
            put(
                "dismissals_json",
                prefs.getString("continue_watching_dismissals", null) ?: ""
            )
        }
}

/**
 * Applies a pulled prefs blob locally. Everything is defensive: a malformed
 * payload logs and returns instead of corrupting local state.
 */
object PrefsPayloadApplier {

    private const val TAG = "SUPABASE_SYNC"

    // Suspend: the addon applier serializes through AddonManager's apply
    // mutex (shared with auto-update), which can suspend. Every caller is
    // already inside SupabaseSync's coroutine scope.
    suspend fun apply(context: Context, prefKey: String, payload: JsonObject) {
        when (prefKey) {
            PrefsPayloadBuilder.KEY_DISPLAY_PREFS -> applyDisplayPrefs(context, payload)
            PrefsPayloadBuilder.KEY_ADDONS -> applyAddons(context, payload)
            PrefsPayloadBuilder.KEY_SIMKL_AUTH -> applySimklAuth(context, payload)
            PrefsPayloadBuilder.KEY_IPTV -> applyIptv(context, payload)
            PrefsPayloadBuilder.KEY_WATCHED_OVERRIDES -> applyWatchedOverrides(context, payload)
            PrefsPayloadBuilder.KEY_HOME_ORDER -> applyHomeOrder(context, payload)
            PrefsPayloadBuilder.KEY_COLLECTIONS -> applyCollections(context, payload)
            PrefsPayloadBuilder.KEY_BADGE_PACK -> applyBadgePack(context, payload)
            PrefsPayloadBuilder.KEY_LIBRARY -> applyLibrary(context, payload)
            PrefsPayloadBuilder.KEY_DISMISSALS -> applyDismissals(context, payload)
            PrefsPayloadBuilder.KEY_PROFILES ->
                com.kennyb1201.kbstream.data.sync.ProfileManager.applyProfilesPayload(
                    context, payload
                )
        }
    }

    private fun applyBadgePack(context: Context, payload: JsonObject) {
        fun str(key: String): String =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

        val url = str("badge_pack_url")
        val packJson = str("badge_pack_json")
        if (packJson.isBlank()) return

        val prefs = scopedPrefs(context, "kbstream_stream_badges")
        if (packJson == prefs.getString("badge_pack_json", null).orEmpty()) return

        prefs.edit()
            .putString("badge_pack_url", url)
            .putString("badge_pack_json", packJson)
            .apply()
    }

    /**
     * Library apply: full replace with the remote blobs, but ONLY when the
     * remote write is newer than the last local one — My List edits from
     * either device must survive, so the loser of a timestamp race keeps
     * its state and re-pushes on its next edit.
     */
    private fun applyLibrary(context: Context, payload: JsonObject) {
        val remoteUpdated = payloadUpdatedAt(payload)
        val prefs = scopedPrefs(context, "kbstream_library")
        if (remoteUpdated != null &&
            remoteUpdated < prefs.getLong("library_synced_at", 0L)
        ) return

        fun raw(key: String): String? {
            val v = (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            return v?.takeIf { it.isNotBlank() }
        }

        val editor = prefs.edit()
        raw("my_list")?.let { editor.putString("my_list", it) }
        raw("personal_lists")?.let { editor.putString("personal_lists", it) }
        editor.putLong("library_synced_at", remoteUpdated ?: System.currentTimeMillis())
        editor.apply()
    }

    /**
     * Dismissals apply: same timestamp-guarded pattern as the library —
     * a remote blob older than the newest local dismissal write is
     * rejected so offline dismissals survive.
     */
    private fun applyDismissals(context: Context, payload: JsonObject) {
        val remoteUpdated = payloadUpdatedAt(payload)
        val prefs = scopedPrefs(context, "continue_watching_dismissals")
        if (remoteUpdated != null &&
            remoteUpdated < prefs.getLong("dismissals_synced_at", 0L)
        ) return

        val json = (payload["dismissals_json"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() } ?: return

        prefs.edit()
            .putString("continue_watching_dismissals", json)
            .putLong("dismissals_synced_at", remoteUpdated ?: System.currentTimeMillis())
            .apply()
    }

    private fun applyHomeOrder(context: Context, payload: JsonObject) {
        val blob = (payload["home_order_json"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        val prefs = scopedPrefs(context, "kbstream_kb_home_order")
        val remoteUpdated = payloadUpdatedAt(payload)
        if (remoteUpdated != null && remoteUpdated < prefs.getLong("home_order_synced_at", 0L)) return

        val local = prefs.getString("home_order_json", null).orEmpty()
        if (blob == local) return

        prefs.edit()
            .putString("home_order_json", blob)
            .putLong("home_order_synced_at", remoteUpdated ?: System.currentTimeMillis())
            .apply()
    }

    private fun applyCollections(context: Context, payload: JsonObject) {
        val arr = payload["profile_urls"] as? kotlinx.serialization.json.JsonArray ?: return
        val urls = arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val prefs = scopedPrefs(context, "kbstream_kb_collections")
        val joined = urls.joinToString("\n")
        if (joined == prefs.getString("profile_urls", null).orEmpty()) return

        prefs.edit().putString("profile_urls", joined).apply()
    }

    private fun applyDisplayPrefs(context: Context, payload: JsonObject) {
        val prefs = scopedPrefs(context, DISPLAY_PREFS_STORE)
        val remoteUpdated = payloadUpdatedAt(payload)
        val remoteTimestamps =
            (payload[DisplayPrefsRules.TIMESTAMPS_FIELD] as? JsonObject)
                ?.mapNotNull { (key, value) ->
                    ((value as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content?.toLongOrNull())?.let { key to it }
                }
                ?.toMap()
                .orEmpty()

        // Blobs from builds that predate per-key timestamps carry no map: they
        // keep the old whole-blob rule (an older blob must not revert a newer
        // local edit). Per-key blobs are merged key by key below instead.
        if (remoteTimestamps.isEmpty() && remoteUpdated != null &&
            remoteUpdated < prefs.getLong(DISPLAY_SYNCED_AT_KEY, 0L)
        ) {
            return
        }

        val localTimestamps =
            readDisplayTimestamps(prefs)
        val fallbackTs =
            remoteUpdated ?: System.currentTimeMillis()
        val snapshot =
            readDisplaySnapshot(prefs).toMutableMap()
        // Keys this pull actually ADOPTED: canonical value + the timestamp that
        // won, so both can be mirrored into the local bookkeeping below.
        val applied =
            mutableMapOf<String, String>()
        val appliedAt =
            mutableMapOf<String, Long>()
        val editor =
            prefs.edit()

        payload.forEach { (key, value) ->
            if (key == DisplayPrefsRules.UPDATED_AT_FIELD ||
                key == DisplayPrefsRules.TIMESTAMPS_FIELD
            ) {
                return@forEach
            }
            val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return@forEach
            val content = primitive.content

            // Merge per key: a key this device edited MORE RECENTLY keeps its
            // local value, which the next push publishes back.
            val editsAt =
                remoteTimestamps[key] ?: fallbackTs
            if (!DisplayPrefsRules.remoteWins(editsAt, localTimestamps[key] ?: 0L)) {
                return@forEach
            }

            val appliedIt =
                when {
                    primitive.isString -> {
                        editor.putString(key, content)
                        true
                    }
                    content == "true" || content == "false" -> {
                        editor.putBoolean(key, content == "true")
                        true
                    }
                    else -> content.toLongOrNull()?.let {
                        editor.putLong(key, it)
                        true
                    } ?: false
                }
            if (appliedIt) {
                applied[key] = content
                appliedAt[key] = editsAt
                snapshot[key] = content
            }
        }

        // Adopting a remote value must not look like a fresh local edit on the
        // NEXT build: mirror both the value and its timestamp.
        appliedAt.forEach { (key, ts) ->
            editor.putLong(DISPLAY_TS_PREFIX + key, ts)
        }
        editor.putString(
            DISPLAY_SNAPSHOT_KEY,
            buildJsonObject {
                snapshot.forEach { (key, value) -> put(key, value) }
            }.toString()
        )
        editor.putLong(DISPLAY_SYNCED_AT_KEY, remoteUpdated ?: System.currentTimeMillis())
        editor.apply()

        // The AMOLED toggle backs onto a live theme state, not just the prefs
        // file - a remote blob applied here must mirror into it so a synced
        // device repaints immediately (setContent only seeds it at launch).
        // Only when this key was actually ADOPTED — a newer local edit keeps
        // its value and must keep its matching live state too.
        applied["amoled_black"]?.let { raw ->
            com.kennyb1201.kbstream.ui.theme.kbAmoledBlackState.value = raw == "true"
        }

        // Pure black surface: same live-mirror treatment — a synced device
        // repaints immediately instead of waiting for the next relaunch.
        applied["pure_black_surface"]?.let { raw ->
            com.kennyb1201.kbstream.ui.theme.kbPureBlackSurfaceState.value = raw == "true"
        }
    }

    private suspend fun applyAddons(context: Context, payload: JsonObject) {
        val addonsJson = (payload["installed_addons_json"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        if (addonsJson.isBlank()) return

        val prefs = scopedPrefs(context, "kbstream_addons")
        val remoteUpdated = payloadUpdatedAt(payload)
        val localAddons = prefs.getString("installed_addons_json", null).orEmpty()
        if (addonsJson == localAddons) return

        // Serialize with auto-update applies through the manager's mutex:
        // both paths replace/merge the full addon list, and unserialized
        // writers would clobber each other's updates.
        try {
            com.kennyb1201.kbstream.data.addon.AddonManager.getInstance(context)
                .applySyncedAddons(addonsJson)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "addon reload after sync failed: ${e.message}")
        }
    }

    private fun applySimklAuth(context: Context, payload: JsonObject) {
        val token = (payload[SIMKL_ACCESS_TOKEN_KEY] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content.orEmpty()
        val signedOut =
            (payload[SimklAuthRules.SIGNED_OUT_FIELD] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content == "true"

        val prefs = scopedPrefs(context, SIMKL_AUTH_STORE)

        // Blank token = the profile SIGNED OUT on some device. Honor it — but
        // only when the blob says so explicitly. Every device used to publish
        // this blob, so the blanks coming from devices that had never
        // connected Simkl erased a live session everywhere; a blank without
        // the tombstone is now ignored (see [SimklAuthRules]).
        if (token.isBlank()) {
            if (!SimklAuthRules.clearsSession(token, signedOut)) return
            if (!prefs.contains(SIMKL_ACCESS_TOKEN_KEY)) return
            prefs.edit().remove(SIMKL_ACCESS_TOKEN_KEY).apply()
            resetSimklStateAfterTokenChange(context)
            return
        }

        if (prefs.getString(SIMKL_ACCESS_TOKEN_KEY, null) == token) {
            // Same account: just retire a lingering sign-out tombstone so it
            // cannot be republished over the live token on the next push.
            if (prefs.getBoolean(SimklAuthRules.SIGNED_OUT_FIELD, false)) {
                prefs.edit().putBoolean(SimklAuthRules.SIGNED_OUT_FIELD, false).apply()
            }
            return
        }

        prefs.edit()
            .putString(SIMKL_ACCESS_TOKEN_KEY, token)
            .putBoolean(SimklAuthRules.SIGNED_OUT_FIELD, false)
            .apply()
        resetSimklStateAfterTokenChange(context)
    }

    /**
     * The Simkl session just changed (adopted a synced token, or dropped one)
     * so everything derived from the PREVIOUS account has to go: the
     * in-memory lists (keyed by profile, not by account) and the
     * watched-activity checkpoint, which otherwise tells the next poll that
     * nothing changed and serves the old account's Continue Watching. The
     * token itself needs no cache handling — SimklRepository reads it live.
     */
    private fun resetSimklStateAfterTokenChange(context: Context) {
        runCatching {
            com.kennyb1201.kbstream.data.simkl.SimklRepository.clearTransientCaches()
            com.kennyb1201.kbstream.data.simkl.SimklRepository.getInstance(context)
                .forceClearWatchedActivitySync()
        }.onFailure {
            android.util.Log.w(TAG, "simkl reset after synced token change failed: ${it.message}")
        }
    }

    private fun applyIptv(context: Context, payload: JsonObject) {
        val prefs = scopedPrefs(context, "iptv_prefs")
        val remoteUpdated = payloadUpdatedAt(payload)
        if (remoteUpdated != null && remoteUpdated < prefs.getLong("iptv_synced_at", 0L)) return

        fun str(key: String): String =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

        val playlistUrl = str("playlist_url")
        val playlistName = str("playlist_name")
        val epgUrl = str("epg_url")
        val extraEpgUrls = (payload["extra_epg_urls"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .orEmpty()
            .filter { it.isNotBlank() }
        val extraPlaylistUrls = (payload["extra_playlist_urls"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .orEmpty()
            .filter { it.isNotBlank() }

        val editor = prefs.edit()
        if (playlistUrl.isNotBlank()) editor.putString("playlist_url", playlistUrl)
        if (playlistName.isNotBlank()) editor.putString("playlist_name", playlistName)
        if (epgUrl.isNotBlank()) editor.putString("epg_url", epgUrl)
        // Extra sources: the array replaces the local list wholesale (a
        // device that removed a source should propagate the removal), but
        // only when the key is present — older payloads keep local edits.
        if (payload.containsKey("extra_playlist_urls")) {
            editor.putString("extra_playlist_urls", extraPlaylistUrls.joinToString("\n"))
        }
        if (payload.containsKey("extra_epg_urls")) {
            editor.putString("extra_epg_urls", extraEpgUrls.joinToString("\n"))
        }
        (payload["hidden_channel_ids"] as? kotlinx.serialization.json.JsonArray)?.let { arr ->
            val set = arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()
            editor.putStringSet("hidden_channel_ids", set)
        }
        // Guide-level sets: full replace when present (a device that un-hid
        // a group should propagate the un-hide), guarded by the same
        // iptv_synced_at staleness check above.
        val guidePrefs = scopedPrefs(context, "iptv_guide_preferences")
        val guideEditor = guidePrefs.edit()
        var guideChanged = false
        (payload["hidden_groups"] as? kotlinx.serialization.json.JsonArray)?.let { arr ->
            guideEditor.putStringSet(
                "hidden_groups",
                arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()
            )
            guideChanged = true
        }
        (payload["favorites"] as? kotlinx.serialization.json.JsonArray)?.let { arr ->
            guideEditor.putStringSet(
                "favorites",
                arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()
            )
            guideChanged = true
        }
        if (guideChanged) guideEditor.apply()
        editor.putLong("iptv_synced_at", remoteUpdated ?: System.currentTimeMillis())
        editor.apply()

        // Trigger IPTV reload if the source actually changed.
        if (playlistUrl.isNotBlank() && playlistUrl != prefs.getString("playlist_applied_url", null)) {
            prefs.edit().putString("playlist_applied_url", playlistUrl).apply()
            runCatching {
                com.kennyb1201.kbstream.data.iptv.EpgRefreshScheduler.schedule(context)
            }
        }
    }

    private fun applyWatchedOverrides(context: Context, payload: JsonObject) {
        val arr = payload["overrides"] as? kotlinx.serialization.json.JsonArray ?: return
        val set = arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()

        val prefs = scopedPrefs(context, "kbstream_watched_overrides")
        prefs.edit().putStringSet("watched_overrides", set).apply()
    }

    private fun payloadUpdatedAt(payload: JsonObject): Long? =
        (payload["updatedAt"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
}
