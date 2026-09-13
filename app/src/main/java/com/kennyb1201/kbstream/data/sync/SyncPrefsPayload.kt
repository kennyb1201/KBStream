package com.kennyb1201.kbstream.data.sync

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
        "omdb_api_key"
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

    fun buildAll(context: Context): List<Pair<String, JsonObject>> = listOf(
        KEY_DISPLAY_PREFS to buildDisplayPrefs(context),
        KEY_ADDONS to buildAddons(context),
        KEY_SIMKL_AUTH to buildSimklAuth(context),
        KEY_IPTV to buildIptv(context),
        KEY_WATCHED_OVERRIDES to buildWatchedOverrides(context)
    )

    fun buildDisplayPrefs(context: Context): JsonObject {
        val prefs = context.getSharedPreferences("kbstream_player_prefs", Context.MODE_PRIVATE)
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
                context.getSharedPreferences("kbstream_addons", Context.MODE_PRIVATE)
                    .getString("installed_addons_json", null).orEmpty()
            )
        }

    fun buildSimklAuth(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(
                "access_token",
                context.getSharedPreferences("simkl_auth", Context.MODE_PRIVATE)
                    .getString("access_token", null).orEmpty()
            )
        }

    fun buildIptv(context: Context): JsonObject {
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put("playlist_url", prefs.getString("playlist_url", null).orEmpty())
            put("playlist_name", prefs.getString("playlist_name", null).orEmpty())
            put("epg_url", prefs.getString("epg_url", null).orEmpty())
            putJsonArray("hidden_channel_ids") {
                prefs.getStringSet("hidden_channel_ids", emptySet()).orEmpty().forEach { add(it) }
            }
        }
    }

    fun buildWatchedOverrides(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            putJsonArray("overrides") {
                context.getSharedPreferences("kbstream_watched_overrides", Context.MODE_PRIVATE)
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

    fun apply(context: Context, prefKey: String, payload: JsonObject) {
        when (prefKey) {
            PrefsPayloadBuilder.KEY_DISPLAY_PREFS -> applyDisplayPrefs(context, payload)
            PrefsPayloadBuilder.KEY_ADDONS -> applyAddons(context, payload)
            PrefsPayloadBuilder.KEY_SIMKL_AUTH -> applySimklAuth(context, payload)
            PrefsPayloadBuilder.KEY_IPTV -> applyIptv(context, payload)
            PrefsPayloadBuilder.KEY_WATCHED_OVERRIDES -> applyWatchedOverrides(context, payload)
        }
    }

    private fun applyDisplayPrefs(context: Context, payload: JsonObject) {
        // Last-write-wins: skip applying an older blob over a newer local edit.
        val prefs = context.getSharedPreferences("kbstream_player_prefs", Context.MODE_PRIVATE)
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
    }

    private fun applyAddons(context: Context, payload: JsonObject) {
        val addonsJson = (payload["installed_addons_json"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        if (addonsJson.isBlank()) return

        val prefs = context.getSharedPreferences("kbstream_addons", Context.MODE_PRIVATE)
        val remoteUpdated = payloadUpdatedAt(payload)
        val localAddons = prefs.getString("installed_addons_json", null).orEmpty()
        if (addonsJson == localAddons) return

        prefs.edit().putString("installed_addons_json", addonsJson).apply()

        // Reload the manager so Home/Search rebuild with the synced catalog set.
        runCatching {
            com.kennyb1201.kbstream.data.addon.AddonManager.getInstance(context).refreshAddons()
        }.onFailure {
            android.util.Log.w(TAG, "addon reload after sync failed: ${it.message}")
        }
    }

    private fun applySimklAuth(context: Context, payload: JsonObject) {
        val token = (payload["access_token"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        if (token.isBlank()) return

        val prefs = context.getSharedPreferences("simkl_auth", Context.MODE_PRIVATE)
        if (prefs.getString("access_token", null) == token) return

        prefs.edit().putString("access_token", token).apply()
        // No explicit cache clear needed: SimklRepository reads the token on
        // every request, so the swapped-in session takes effect immediately.

    }

    private fun applyIptv(context: Context, payload: JsonObject) {
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val remoteUpdated = payloadUpdatedAt(payload)
        if (remoteUpdated != null && remoteUpdated < prefs.getLong("iptv_synced_at", 0L)) return

        fun str(key: String): String =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

        val playlistUrl = str("playlist_url")
        val playlistName = str("playlist_name")
        val epgUrl = str("epg_url")

        val editor = prefs.edit()
        if (playlistUrl.isNotBlank()) editor.putString("playlist_url", playlistUrl)
        if (playlistName.isNotBlank()) editor.putString("playlist_name", playlistName)
        if (epgUrl.isNotBlank()) editor.putString("epg_url", epgUrl)
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

        val prefs = context.getSharedPreferences("kbstream_watched_overrides", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("watched_overrides", set).apply()
    }

    private fun payloadUpdatedAt(payload: JsonObject): Long? =
        (payload["updatedAt"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
}
