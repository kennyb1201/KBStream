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
 * pushes and pulls would cross profiles (the "nuvio leak"). With no profiles
 * this resolves to the legacy un-namespaced store, unchanged behavior.
 */
private fun scopedPrefs(context: Context, baseName: String) =
    context.getSharedPreferences(
        com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(context, baseName),
        Context.MODE_PRIVATE
    )

/**
 * Builds and applies the keyed JSON blobs stored in sync_prefs.
 *
 * Sync scope (user request):
 *  - SYNCED: display prefs, addon install JSON, Simkl auth token,
 *    IPTV playlist/EPG config, watched-override set.
 *  - EXCLUDED: every playback/decoder setting (buffer, subtitle render,
 *    decoder priority, tunneling, Dolby Vision compat, aspect ratio,
 *    audio/subtitle language defaults, PiP) — those are per-device by
 *    nature and differ between e.g. a Fire TV Stick and a projector.
 *
 * Each blob has its own stable pref_key, so devices only merge what the
 * key covers and playback keys simply never exist in the cloud table.
 */
object PrefsPayloadBuilder {

    // Display/UI prefs that DO sync (must match AppPreferences key names).
    private val SYNCED_PREF_KEYS = setOf(
        "auto_play_next",                    // convenience behavior, not device-specific
        "use_stream_ranker",
        "hero_trailer_autoplay",
        "hero_trailer_muted",
        "use_24h_clock",
        "home_rail_show_catalog_type",
        "home_rail_show_addon_name",
        "search_rail_show_catalog_type",
        "search_rail_show_addon_name",
        "poster_caption_title",
        "poster_caption_year",
        "poster_caption_rating",
        "home_rail_hide_upcoming",
        "home_landscape_cards",
        "poster_partial_watch_badge",
        "amoled_black",                      // AMOLED theme toggle (pure display pref)
        "pure_black_surface",                // Pure black cards/panels/containers toggle
        "omdb_api_key",
        "opensubtitles_api_key"              // player → search subtitles online
    )

    // Playback/decoder prefs that NEVER sync (documented for clarity).
    private val EXCLUDED_PREF_KEYS = setOf(
        "default_buffer_mode",
        "default_subtitle_size",
        "default_subtitle_bg",
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
        "default_aspect_ratio",
        "preferred_audio_language",
        "preferred_subtitle_language"
    )

    const val KEY_DISPLAY_PREFS = "display_prefs"
    const val KEY_ADDONS = "addons"
    const val KEY_SIMKL_AUTH = "simkl_auth"
    const val KEY_IPTV = "iptv_config"
    const val KEY_WATCHED_OVERRIDES = "watched_overrides"
    const val KEY_HOME_ORDER = "nuvio_home_order"
    const val KEY_COLLECTIONS = "nuvio_collections"
    const val KEY_BADGE_PACK = "badge_pack"
    const val KEY_PROFILES = "profiles"

    fun buildAll(context: Context): List<Pair<String, JsonObject>> = listOf(
        KEY_DISPLAY_PREFS to buildDisplayPrefs(context),
        KEY_ADDONS to buildAddons(context),
        KEY_SIMKL_AUTH to buildSimklAuth(context),
        KEY_IPTV to buildIptv(context),
        KEY_WATCHED_OVERRIDES to buildWatchedOverrides(context),
        KEY_HOME_ORDER to buildHomeOrder(context),
        KEY_COLLECTIONS to buildCollections(context),
        KEY_BADGE_PACK to buildBadgePack(context),
        KEY_PROFILES to com.kennyb1201.kbstream.data.sync.ProfileManager.profilesSyncBlob(context)
    )

    fun buildBadgePack(context: Context): JsonObject {
        val prefs = scopedPrefs(context, "kbstream_stream_badges")
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put("badge_pack_url", prefs.getString("badge_pack_url", null).orEmpty())
            put("badge_pack_json", prefs.getString("badge_pack_json", null).orEmpty())
        }
    }

    fun buildHomeOrder(context: Context): JsonObject {
        val prefs = scopedPrefs(context, "kbstream_nuvio_home_order")
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(
                "home_order_json",
                prefs.getString("home_order_json", null).orEmpty()
            )
        }
    }

    fun buildCollections(context: Context): JsonObject {
        val prefs = scopedPrefs(context, "kbstream_nuvio_collections")
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

    fun buildDisplayPrefs(context: Context): JsonObject {
        val prefs = scopedPrefs(context, "kbstream_player_prefs")
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            val all = prefs.all
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

    fun buildSimklAuth(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(
                "access_token",
                scopedPrefs(context, "simkl_auth")
                    .getString("access_token", null).orEmpty()
            )
        }

    fun buildIptv(context: Context): JsonObject {
        val prefs = scopedPrefs(context, "iptv_prefs")
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

    private fun applyHomeOrder(context: Context, payload: JsonObject) {
        val blob = (payload["home_order_json"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        val prefs = scopedPrefs(context, "kbstream_nuvio_home_order")
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
        val prefs = scopedPrefs(context, "kbstream_nuvio_collections")
        val joined = urls.joinToString("\n")
        if (joined == prefs.getString("profile_urls", null).orEmpty()) return

        prefs.edit().putString("profile_urls", joined).apply()
    }

    private fun applyDisplayPrefs(context: Context, payload: JsonObject) {
        // Last-write-wins: skip applying an older blob over a newer local edit.
        val prefs = scopedPrefs(context, "kbstream_player_prefs")
        val remoteUpdated = payloadUpdatedAt(payload)
        if (remoteUpdated != null && remoteUpdated < prefs.getLong("display_prefs_synced_at", 0L)) {
            return
        }

        val editor = prefs.edit()
        payload.forEach { (key, value) ->
            if (key == "updatedAt") return@forEach
            val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return@forEach
            val content = primitive.content
            when {
                primitive.isString -> editor.putString(key, content)
                content == "true" || content == "false" -> editor.putBoolean(key, content == "true")
                else -> content.toLongOrNull()?.let { editor.putLong(key, it) }
            }
        }
        editor.putLong("display_prefs_synced_at", remoteUpdated ?: System.currentTimeMillis())
        editor.apply()

        // The AMOLED toggle backs onto a live theme state, not just the prefs
        // file - a remote blob applied here must mirror into it so a synced
        // device repaints immediately (setContent only seeds it at launch).
        if (payload.containsKey("amoled_black")) {
            val raw = (payload["amoled_black"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (raw == "true" || raw == "false") {
                com.kennyb1201.kbstream.ui.theme.kbAmoledBlackState.value = raw == "true"
            }
        }

        // Pure black surface: same live-mirror treatment — a synced device
        // repaints immediately instead of waiting for the next relaunch.
        if (payload.containsKey("pure_black_surface")) {
            val raw = (payload["pure_black_surface"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (raw == "true" || raw == "false") {
                com.kennyb1201.kbstream.ui.theme.kbPureBlackSurfaceState.value = raw == "true"
            }
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
        val token = (payload["access_token"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        if (token.isBlank()) return

        val prefs = scopedPrefs(context, "simkl_auth")
        if (prefs.getString("access_token", null) == token) return

        prefs.edit().putString("access_token", token).apply()
        // No explicit cache clear needed: SimklRepository reads the token on
        // every request, so the swapped-in session takes effect immediately.

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
