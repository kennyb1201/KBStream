package com.kennyb1201.kbstream.data.sync

import android.content.Context
import com.kennyb1201.kbstream.data.library.LibraryTombstones
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.trakt.TraktToken
import com.kennyb1201.kbstream.data.trakt.TraktTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
/**
 * The IPTV config the ACTIVE profile holds, as one comparable string.
 *
 * Everything the payload carries is in it, so a change to ANY of it (a new
 * playlist, a hidden group, a favorite) is an edit and stamps the config's
 * `iptv_config_edited_at`. Read in both directions: the builder compares it to
 * the last one it stored to detect an edit, and the applier stores what it just
 * adopted so an adoption is never mistaken for one. File-level because both
 * halves of the sync layer — the builder and the applier — need the SAME
 * normalisation, and two copies would drift. (See [IptvConfigRules].)
 */
private fun iptvSignature(context: Context, profileId: String? = null): String {
    fun normalised(raw: String?): String =
        raw.orEmpty().split('\n', ';')
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .joinToString(",")

    val prefs = profilePrefs(context, "iptv_prefs", profileId)
    val guidePrefs = profilePrefs(context, "iptv_guide_preferences", profileId)
    return listOf(
        normalised(prefs.getString("playlist_url", null)),
        prefs.getString("playlist_name", null).orEmpty().trim(),
        prefs.getString("epg_url", null).orEmpty().trim(),
        normalised(prefs.getString("extra_epg_urls", null)),
        normalised(prefs.getString("extra_playlist_urls", null)),
        prefs.getStringSet("hidden_channel_ids", emptySet()).orEmpty().sorted()
            .joinToString(","),
        guidePrefs.getStringSet("hidden_groups", emptySet()).orEmpty().sorted()
            .joinToString(","),
        guidePrefs.getStringSet("favorites", emptySet()).orEmpty().sorted()
            .joinToString(",")
    ).joinToString("\u0001")
}

private fun scopedPrefs(context: Context, baseName: String) =
    context.getSharedPreferences(
        com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(context, baseName),
        Context.MODE_PRIVATE
    )

/**
 * [scopedPrefs] for ONE NAMED profile: the store a synced row's OWN key names,
 * not the one the active profile happens to resolve to.
 *
 * This is what lets a realtime row for a profile that is not on screen be
 * applied to THAT profile's file (see [PrefsPayloadApplier.applyForProfile]) —
 * ready for the next switch, and never written into the active profile's store.
 * A null [profileId] is the legacy "no profile system" store, exactly
 * [scopedPrefs].
 */
private fun profilePrefs(context: Context, baseName: String, profileId: String?) =
    if (profileId == null) {
        scopedPrefs(context, baseName)
    } else {
        context.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(profileId, baseName),
            Context.MODE_PRIVATE
        )
    }

/**
 * The Simkl session store, which holds a live access token and so lives behind
 * [SecureTokenStore] rather than in plain XML. The name is resolved through
 * ProfileStorage exactly as [scopedPrefs] does, so per-profile scoping is
 * unchanged.
 */
private fun simklAuthPrefs(context: Context, profileId: String? = null) =
    com.kennyb1201.kbstream.data.security.SecureTokenStore.prefs(
        context,
        if (profileId == null) {
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(
                context,
                SIMKL_AUTH_STORE
            )
        } else {
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(
                profileId,
                SIMKL_AUTH_STORE
            )
        },
        legacyPlaintext = true
    )

/**
 * Base name of a prefs store saved by builds before the N-U-V-I-O rename.
 * Assembled from fragments so the retired product name never appears
 * verbatim in source, while the one-time migrations below can still read
 * (and drain) the old stores on upgrading devices.
 */
private fun legacyStoreName(tail: String) = "kbstream_" + "nu" + "vio" + "_$tail"

/**
 * The Trakt session store, addressed exactly as [TraktTokenStore] resolves it
 * for the ACTIVE profile (or for [profileId] when the cloud applier is writing
 * a sibling profile's copy).
 */
private fun traktAuthStore(context: Context, profileId: String? = null) =
    TraktTokenStore(context, profileId)

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

// IPTV config bookkeeping, all local to the profile's own "iptv_prefs" store.
// `IPTV_EDITED_AT_KEY` is when the config last CHANGED here (what the blob
// publishes, see IptvConfigRules), `IPTV_SNAPSHOT_KEY` is the config the build
// last saw (how an edit is told from a mere rebuild, and from an adoption), and
// `IPTV_SYNCED_AT_KEY` is the cloud copy's stamp this device last adopted.
private const val IPTV_EDITED_AT_KEY = "iptv_config_edited_at"
private const val IPTV_SNAPSHOT_KEY = "__iptv_config_snapshot__"
private const val IPTV_SYNCED_AT_KEY = "iptv_synced_at"

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
 *  - SYNCED: display prefs, addon install JSON, Simkl auth token, Trakt
 *    session (access + refresh token, expiry, account), IPTV playlist/EPG
 *    config, watched-override set, and the player's
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
        "hero_trailer_delay_ms",            // hero trailer dwell: a viewing preference
        // What "continue watching" means to this account: every device should
        // agree which tracker's resume point wins (all still scrobble).
        "resume_authority",
        "use_24h_clock",
        "home_rail_show_catalog_type",
        "home_rail_show_addon_name",
        "search_rail_show_catalog_type",
        "search_rail_show_addon_name",
        // `max_auto_play_quality` is deliberately absent too (decision,
        // 2026-10-20): a decode ceiling is a property of the box that has to
        // decode the source, so a stick's 1080p answer must not become the
        // phone's. It is device-local exactly like the three keys below.
        //
        // `poster_size`, `home_landscape_cards` and `landscape_posters` are
        // deliberately NOT here (decision, 2026-10-20): the useful tile size
        // and card shape depend on the screen — a 65" TV and a 6.7" phone want
        // different answers — so they are screen geometry, not identity. Each
        // device keeps the value it already has stored locally, and changing
        // one only affects that device. This set gates BOTH directions (the
        // builder only publishes listed keys, [applyDisplayPrefs] skips any
        // key not in it), so a blob from an older build that still carries
        // them applies nothing for them — no migration needed.
        "poster_caption_title",
        "poster_caption_year",
        "poster_caption_rating",
        "home_rail_hide_upcoming",
        "home_rail_hide_watched",            // hide finished titles on Home: a viewing preference
        "browse_english_only",               // catalog language filter, not device-specific
        "poster_partial_watch_badge",
        "poster_border_strength",           // poster edge strength: same look on every device
        "poster_edge",                       // poster corner shape: same look on every device
        "badges_above_file",                // badge chip placement: same layout preference on every device
        "binge_group_prefer",                // binge continuity, not device-specific
        "binge_group_reuse",
        "binge_group_fallback",
        "still_there_prompt",                // binge watchdog, not device-specific
        "still_there_episodes",
        "next_episode_popup",                // end-of-episode panels: how a title ends is a
        "next_episode_popup_percent",        //   viewing preference, not a device capability,
        "because_you_watched_popup",         //   so both the switches and the pop-up points
        "because_you_watched_popup_percent", //   follow the profile
        "new_episode_notifications",         // new-episode alerts: behavior, not device-specific
        "live_reminder_notifications",       // live TV reminder alerts: same reasoning
        "amoled_black",                      // AMOLED theme toggle (pure display pref)
        "pure_black_surface",                // Pure black cards/panels/containers toggle
        "accent_index",                      // global theme accent (index into the palette)
        "custom_accent_color",               // ...and the viewer's own colour for that accent
        // The MDBList / OpenSubtitles / TorBox API keys are deliberately NOT
        // here: each is a live bearer credential, so it stays in the encrypted
        // SecureTokenStore rather than in this plaintext display-pref file (see
        // AppPreferences.apiKeyPrefs). They sync as their own blob instead
        // ([KEY_API_KEYS]), the way the Simkl session does — same cloud trust,
        // encrypted at rest, and with per-key edit stamps so an empty-handed
        // device cannot blank the account's keys.
        "torbox_library_sync",               // add TorBox cloud files to the Library: a library preference
        "torbox_resolve_create",             // may pressing a cached hash source add the torrent to
                                             //   the viewer's TorBox account? A statement about the
                                             //   account, not the device, so it follows them
        "default_subtitle_size",             // subtitle appearance: same on every device
        "default_subtitle_bg",
        "clean_sdh_captions",                // ...and whether captions keep their sound descriptions
        "default_subtitle_position",
        "auto_skip_intro",                   // skipping behavior, not device-specific
        "auto_skip_credits",
        "preferred_audio_language",          // preferred track languages: same on every device
        "preferred_subtitle_language",
        "subtitle_mode",                     // off / forced only / on: a viewing preference
        "auto_fetch_subtitles",              // subtitle auto-fetch: a viewing preference
        "spoiler_free",                      // spoiler-free browsing: how much of a show's
                                             // own episode list the viewer wants to see
        // The sports hub's arrangement follows the viewer's account: which
        // leagues are on, the tab order they were dragged into, and who they
        // follow are all viewing choices, not device capabilities - so the
        // other TV comes up with the same hub. Each is stored as a
        // newline-separated String (see AppPreferences.readSportsSet) precisely
        // because a StringSet has no representation in this primitive-only
        // blob and so could never sync.
        "sports_enabled_leagues",
        "sports_league_order",
        "sports_favorite_teams"
    )

    /**
     * Decoder/playback prefs that must NEVER sync: they describe what THIS
     * device can decode — or, for `match_frame_rate`, what its panel can
     * display — so importing another device's choices would select a decoder
     * the viewer's TV may not have, or ask a panel for a mode it does not
     * report. Nothing here is consulted at runtime — [SYNCED_PREF_KEYS] is an
     * allow-list, so a key cannot leave the device without being on it — and
     * SyncScopeTest walks this list against that one, which is what gives the
     * exclusions teeth.
     */
    internal val EXCLUDED_PREF_KEYS = setOf(
        "default_buffer_mode",
        "auto_select_stream",
        // The auto-play ceiling can only be honoured by the box that decodes
        // the source: a stick capped at 1080p must not cap the phone on the
        // same profile. Device-local, decided 2026-10-20.
        "max_auto_play_quality",
        // How long auto-play waits for more add-ons is about THIS box's
        // add-ons and connection: the TV on fibre can afford twenty seconds,
        // the phone on a data plan wants four. Device-local, beside the ceiling
        // it trades against (audit: streams C1).
        "auto_play_wait_sec",
        // The cellular data saver is a statement about THIS device's
        // connection: the phone on a data plan needs it, the television on
        // fibre does not, and a synced value would either cap the TV for no
        // reason or spend the phone's plan. Device-local, like the ceiling it
        // narrows.
        "cellular_data_saver",
        "force_software_decoder",
        "enable_tunneling",
        "enable_pip",
        "decoder_mode",
        "decoder_priority",
        "video_decoder",
        "audio_decoder",
        "dv_compat_mode",
        "strip_hdr10_plus",
        // The temporary DV stall-bisect arms: what one decoder is doing right
        // now, so a blob from another TV must never arm a bisect nobody there
        // is running. They come out with the settings rows.
        "dv_diag_drop_rpu",
        "dv_diag_no_color_info",
        "dv_convert_p7_to_81",
        "dv_convert_p5_to_81",
        "dv_p5_gles_correction",
        "default_aspect_ratio",
        // The MPV video output: `vo` and its GPU settings are the field knob for
        // the 6.51 mpvlibAndroid black screens, and what a box's GPU can put a
        // frame on screen with is a property of that box - one TV's working
        // configuration must never be armed on another, which is exactly what a
        // synced value would do.
        "mpv_vo",
        "match_frame_rate",                // which refresh rates this panel can do
        // Whether the trailer pipeline can mint YouTube PO tokens at all is a
        // property of this box (some Fire OS images ship no usable WebView), so
        // a TV's answer must never switch the phone's minter off.
        "youtube_po_token_enabled",
        // Whether a launch opens on Who's Watching. How a device is shared is a
        // property of that device: a living-room TV wants the picker every
        // time, a phone that only ever uses one profile does not - so picking
        // one answer on the TV must not remove the picker from the phone.
        "show_profile_picker"
    )

    const val KEY_DISPLAY_PREFS = "display_prefs"
    const val KEY_ADDONS = "addons"
    const val KEY_SIMKL_AUTH = "simkl_auth"

    /**
     * The Trakt session, the same shape (and the same "never publish a blank"
     * rule) as [KEY_SIMKL_AUTH] - see [TraktAuthRules]. It carries the refresh
     * token and expiry as well, because Trakt's access tokens expire.
     */
    const val KEY_TRAKT_AUTH = "trakt_auth"
    const val KEY_IPTV = "iptv_config"

    /**
     * The addon service credentials: TorBox, OpenSubtitles, MDBList.
     *
     * A credential rather than a display pref, so it is its own row (the same
     * shape the Simkl session uses) with per-key edit times — see
     * [com.kennyb1201.kbstream.data.sync.ApiKeySyncRules] for the merge and
     * [com.kennyb1201.kbstream.data.settings.AppPreferences.SYNCED_API_KEYS] for
     * what travels. The values stay encrypted at rest on each device
     * ([com.kennyb1201.kbstream.data.security.SecureTokenStore]); the account
     * copy is the same trust level the Simkl access token already has.
     */
    const val KEY_API_KEYS = "api_keys"
    const val KEY_WATCHED_OVERRIDES = "watched_overrides"
    const val KEY_HOME_ORDER = "kb_home_order"
    const val KEY_BROWSE_SHORTCUTS = "kb_browse_shortcuts"
    const val KEY_CUSTOM_CATALOGS = "kb_custom_catalogs"
    const val KEY_COLLECTIONS = "kb_collections"
    const val KEY_BADGE_PACK = "badge_pack"
    const val KEY_LIBRARY = "library"
    const val KEY_DISMISSALS = "dismissals"
    /**
     * The profile's own thumbs and 1-10 ratings (see [TasteSignals]). Its own
     * payload key rather than a row in the display-prefs blob: those are
     * per-KEY last-write-wins scalars, while this is a per-TITLE merge (see
     * [TasteSignals.mergeFromSync]), and a blob whose whole point is "the
     * newer verdict wins per title" cannot be stored as one string with one
     * timestamp.
     */
    const val KEY_TASTE_SIGNALS = "taste_signals"
    const val KEY_PROFILES = "profiles"

    fun buildAll(context: Context): List<Pair<String, JsonObject>> = listOf(
        KEY_DISPLAY_PREFS to buildDisplayPrefs(context),
        KEY_ADDONS to buildAddons(context),
        // The bulk push skips this blob when it carries nothing (see
        // SupabaseSync.pushPrefsBlobs) — an empty Simkl blob published by a
        // device that never connected erases the account's session elsewhere.
        KEY_SIMKL_AUTH to buildSimklAuth(context),
        // The Trakt session, on exactly the Simkl rule: a profile that never
        // connected Trakt publishes nothing, and only a deliberate disconnect
        // publishes a tombstone (see [TraktAuthRules]).
        KEY_TRAKT_AUTH to buildTraktAuth(context),
        // Same rule as the Simkl blob: a device with no key of its own must not
        // publish its blanks (see SupabaseSync.pushPrefsBlobs).
        KEY_API_KEYS to buildApiKeys(context),
        KEY_IPTV to buildIptv(context),
        KEY_WATCHED_OVERRIDES to buildWatchedOverrides(context),
        KEY_HOME_ORDER to buildHomeOrder(context),
        KEY_BROWSE_SHORTCUTS to buildBrowseShortcuts(context),
        KEY_CUSTOM_CATALOGS to buildCustomCatalogs(context),
        KEY_COLLECTIONS to buildCollections(context),
        KEY_BADGE_PACK to buildBadgePack(context),
        KEY_LIBRARY to buildLibrary(context),
        KEY_DISMISSALS to buildDismissals(context),
        // Skipped when this profile has no signals at all (see
        // SupabaseSync.pushPrefsBlobs): a device that never thumbed anything
        // must not publish an empty set over an account that has some.
        KEY_TASTE_SIGNALS to buildTasteSignals(context),
        KEY_PROFILES to com.kennyb1201.kbstream.data.sync.ProfileManager.profilesSyncBlob(context)
    )

    /**
     * True when this device has something to say about the Simkl session: a
     * token to share, or a deliberate disconnect to propagate. Everything else
     * (the common "never connected Simkl here" case) must stay out of the
     * cloud — see [SimklAuthRules].
     */
    fun hasSimklAuthToPublish(context: Context): Boolean {
        val prefs = simklAuthPrefs(context)
        val token = prefs.getString(SIMKL_ACCESS_TOKEN_KEY, null)
        val signedOut = token.isNullOrBlank() &&
            prefs.getBoolean(SimklAuthRules.SIGNED_OUT_FIELD, false)
        return SimklAuthRules.shouldPublish(token, signedOut)
    }

    /**
     * True when this profile has something to say about its Trakt session: a
     * token to share, or a deliberate disconnect to propagate. A profile that
     * never connected Trakt publishes nothing - see [TraktAuthRules].
     */
    fun hasTraktAuthToPublish(context: Context): Boolean {
        val store = traktAuthStore(context)
        return TraktAuthRules.shouldPublish(store.accessToken(), store.isSignedOut())
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
            // Publish the arrangement's OWN last-change time, not the push's.
            // buildAll runs on every bulk push, so stamping `now` here let any
            // device that had merely opened the app win the last-write-wins
            // race against a sibling's fresh rail arrangement and revert it -
            // the exact "my home order didn't sync" failure. A device that has
            // not changed its order republishes its old stamp, and a device
            // that never arranged one publishes 0, which every arranged
            // sibling's guard rejects (see KBHomeOrderPrefs.SYNCED_AT_KEY).
            put(
                "updatedAt",
                HomeListBlobRules.publishStamp(
                    prefs.getLong(
                        com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                            .SYNCED_AT_KEY,
                        0L
                    ),
                    System.currentTimeMillis()
                )
            )
            put(
                "home_order_json",
                prefs.getString("home_order_json", null).orEmpty()
            )
        }
    }

    /**
     * Browse chips mirrored to Home, as the store's own raw JSON blob plus the
     * local edit stamp. Opaque, exactly like the home order: the writer owns
     * the encoding, so the applier hands the string straight back and this
     * layer never has to know what a chip is.
     *
     * Without this blob the rails added from Search's Browse browser were the
     * one piece of Home's arrangement that stayed on the TV it was made on.
     */
    fun buildBrowseShortcuts(context: Context): JsonObject {
        val prefs =
            scopedPrefs(
                context,
                com.kennyb1201.kbstream.data.kb.BrowseHomeShortcuts.SYNC_STORE
            )
        return buildJsonObject {
            // Same rule as the home order: publish the chips' own last-change
            // time, so an unchanged device's bulk push can never beat a
            // sibling's freshly added or removed chip (see
            // BrowseHomeShortcuts.SYNCED_AT_KEY).
            put(
                "updatedAt",
                HomeListBlobRules.publishStamp(
                    prefs.getLong(
                        com.kennyb1201.kbstream.data.kb.BrowseHomeShortcuts
                            .SYNCED_AT_KEY,
                        0L
                    ),
                    System.currentTimeMillis()
                )
            )
            put(
                "shortcuts_json",
                prefs.getString(
                    com.kennyb1201.kbstream.data.kb.BrowseHomeShortcuts.SYNC_BLOB_KEY,
                    null
                ).orEmpty()
            )
        }
    }

    /**
     * The user's built catalogs, as the store's own raw JSON blob plus the local
     * edit stamp — opaque, exactly like the browse chips: the writer owns the
     * encoding, so the applier hands the string straight back and this layer
     * never has to know what a rule set is.
     *
     * Publishing the content's OWN change time (not the push's) is what keeps a
     * device that merely opened the app from re-stamping its unchanged list and
     * beating a sibling's freshly built catalog. See
     * [CustomCatalogStore.SYNCED_AT_KEY].
     */
    fun buildCustomCatalogs(context: Context): JsonObject {
        val prefs =
            scopedPrefs(
                context,
                com.kennyb1201.kbstream.data.catalogs.CustomCatalogStore.SYNC_STORE
            )
        return buildJsonObject {
            put(
                "updatedAt",
                HomeListBlobRules.publishStamp(
                    prefs.getLong(
                        com.kennyb1201.kbstream.data.catalogs.CustomCatalogStore
                            .SYNCED_AT_KEY,
                        0L
                    ),
                    System.currentTimeMillis()
                )
            )
            put(
                "catalogs_json",
                prefs.getString(
                    com.kennyb1201.kbstream.data.catalogs.CustomCatalogStore
                        .SYNC_BLOB_KEY,
                    null
                ).orEmpty()
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
            // The store's own last-known stamp, NOT the push's `now`. buildAll
            // runs on every bulk push, so re-stamping here let any device that
            // had merely opened the app win the last-write-wins race against a
            // sibling's genuinely newer import and revert it - the same defect
            // the home order and the chips already had fixed, which is why
            // collections were the last blob still re-stamping (see
            // [applyCollections], whose guard compares these stamps).
            put("updatedAt", prefs.getLong("collections_synced_at", 0L))
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
     * The sports keys whose stored value may still be a legacy StringSet: the
     * pre-sync format, written by an older build and never re-saved since the
     * String change. Everything else here is already a JSON primitive.
     */
    private val SPORTS_SET_KEYS = setOf(
        "sports_enabled_leagues",
        "sports_league_order",
        "sports_favorite_teams"
    )

    /**
     * The string the blob carries for [key], or null when it carries nothing.
     *
     * The sports sets are normalized out of the legacy StringSet form first
     * (see [com.kennyb1201.kbstream.data.settings.AppPreferences.sportsSetForSync]):
     * a StringSet has no representation in this primitive-only payload, so
     * without it the key would silently drop out of the blob and the other TV
     * would come up with a default hub.
     *
     * Both the change snapshot and the payload go through this, so a legacy
     * value that has not changed locally does not read as a fresh edit on every
     * build — and a value that moves here moves once, together.
     */
    private fun blobValue(key: String, raw: Any?): String? =
        if (key in SPORTS_SET_KEYS) {
            com.kennyb1201.kbstream.data.settings.AppPreferences.sportsSetForSync(raw)
        } else {
            prefsValueString(raw)
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
            blobValue(key, all[key])?.let { current[key] = it }
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
                // The sports sets may still be stored as legacy StringSets
                // (written by an older build, never re-saved since). The blob
                // carries JSON primitives only, so normalize to the
                // newline-separated String form here — the same form the
                // setters write — rather than dropping the key.
                if (key in SPORTS_SET_KEYS) {
                    blobValue(key, value)?.let { put(key, it) }
                    return@forEach
                }
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

    fun buildAddons(context: Context): JsonObject {
        val json =
            scopedPrefs(context, "kbstream_addons")
                .getString("installed_addons_json", null).orEmpty()
        // The edit stamp (not the push time) decides which device's catalog
        // order/names/visibility win; see [AddonsConfigRules].
        val editedAt =
            runCatching {
                com.kennyb1201.kbstream.data.addon.AddonManager.getInstance(context)
                    .addonsConfigEditedAt()
            }.getOrDefault(0L)
        return buildAddons(json, editedAt)
    }

    /**
     * Payload for an addon set that has ALREADY been written, so the blob
     * carries exactly the JSON that went to disk for that profile plus the
     * configuration's edit time. Re-reading the active profile's store here
     * instead would publish whichever profile happened to be active at
     * enqueue time.
     */
    fun buildAddons(addonsJson: String, configEditedAt: Long = 0L): JsonObject =
        buildJsonObject {
            // Keep updatedAt meaningful for older builds: when this
            // configuration last changed, falling back to the push time only
            // for a device that holds nothing deliberate.
            put(
                "updatedAt",
                configEditedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            put(AddonsConfigRules.CONFIG_EDITED_AT_FIELD, configEditedAt)
            put("installed_addons_json", addonsJson)
        }

    fun buildSimklAuth(context: Context): JsonObject {
        val prefs = simklAuthPrefs(context)
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

    /**
     * The profile's Trakt session, as one blob.
     *
     * All four fields travel: the access token alone would be a session that
     * works until it expires and then strands the sibling, because Trakt's
     * refresh tokens are single-use and rotate on every refresh. [updatedAt]
     * stays for older builds; the session fields are the contract.
     */
    fun buildTraktAuth(context: Context): JsonObject {
        val store = traktAuthStore(context)
        val session = store.read()
        // Tombstone only for a DELIBERATE disconnect: a blank session with no
        // marker is "this profile never connected Trakt", which must not
        // erase a live session anywhere.
        val signedOut = session == null && store.isSignedOut()
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(TraktTokenStore.KEY_ACCESS_TOKEN, session?.accessToken.orEmpty())
            put(TraktTokenStore.KEY_REFRESH_TOKEN, session?.refreshToken.orEmpty())
            put(TraktTokenStore.KEY_EXPIRES_AT, session?.expiresAtEpochMillis ?: 0L)
            put(TraktTokenStore.KEY_ACCOUNT_SLUG, store.accountSlug().orEmpty())
            put(TraktAuthRules.SIGNED_OUT_FIELD, signedOut)
        }
    }

    /**
     * The service credentials this device holds an opinion about, each with the
     * time it was last edited here.
     *
     * Only keys with a local edit stamp are published. A key with a VALUE but no
     * stamp is stamped first — that is the device that pasted it before this
     * blob existed (or had it migrated out of the old plaintext pref), and it is
     * the device whose copy the others need. A key with neither is one this
     * device has never had, so it has nothing to say and is left out entirely.
     */
    fun buildApiKeys(context: Context): JsonObject {
        val now = System.currentTimeMillis()
        val stamps = mutableMapOf<String, Long>()
        val values = mutableMapOf<String, String>()
        com.kennyb1201.kbstream.data.settings.AppPreferences.SYNCED_API_KEYS.forEach { key ->
            val value = if (
                key == com.kennyb1201.kbstream.data.settings.AppPreferences.KEY_OMDB_API_KEY
            ) {
                // Never through storedApiKey/migrateApiKey: the plaintext
                // `omdb_api_key` slot is MDBList's legacy ratings key, and
                // migrating it here would race getMdbListApiKey's own migration.
                com.kennyb1201.kbstream.data.settings.AppPreferences.getOmdbApiKey(context)
            } else {
                com.kennyb1201.kbstream.data.settings.AppPreferences
                    .storedApiKey(context, key)
            }
            var stamp = com.kennyb1201.kbstream.data.settings.AppPreferences
                .apiKeyEditedAt(context, key)
            if (stamp == 0L && value.isNotBlank()) {
                com.kennyb1201.kbstream.data.settings.AppPreferences
                    .stampApiKeyEdit(context, key, now)
                stamp = now
            }
            if (stamp == 0L) return@forEach
            stamps[key] = stamp
            values[key] = value
        }
        return buildJsonObject {
            put("updatedAt", ApiKeySyncRules.latestStamp(stamps.values))
            putJsonObject(ApiKeySyncRules.KEYS_FIELD) {
                values.forEach { (key, value) -> put(key, value) }
            }
            putJsonObject(ApiKeySyncRules.TIMESTAMPS_FIELD) {
                stamps.forEach { (key, stamp) -> put(key, stamp) }
            }
        }
    }

    /**
     * The newest LOCAL key edit, or 0 when this device has never edited one.
     * The push gate compares it with the account copy this device adopted (see
     * [ApiKeySyncRules.shouldPublish]): a device with no credential of its own,
     * and a device that has just adopted the account's, both publish nothing —
     * which is what keeps an empty-handed device from erasing the account's keys
     * (the failure the Simkl session blob had) and what keeps an adoption from
     * being echoed straight back.
     */
    fun apiKeysEditedAt(context: Context): Long =
        ApiKeySyncRules.latestStamp(
            com.kennyb1201.kbstream.data.settings.AppPreferences.SYNCED_API_KEYS.map { key ->
                com.kennyb1201.kbstream.data.settings.AppPreferences.apiKeyEditedAt(context, key)
            }
        )

    fun buildIptv(context: Context): JsonObject {
        val prefs = scopedPrefs(context, "iptv_prefs")
        // Guide-level sets live in their own scoped store and must ride the
        // same payload row — hidden channels/groups that don't cross devices
        // make one TV's guide diverge from the other's.
        val guidePrefs = scopedPrefs(context, "iptv_guide_preferences")
        val playlistUrl = prefs.getString("playlist_url", null).orEmpty()
        val extraPlaylistUrls = prefs.getString("extra_playlist_urls", "").orEmpty()
            .split('\n', ';')
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
        // The published stamp is the config's OWN last change, never the push's
        // (see [IptvConfigRules]): this blob is FULL REPLACE, and stamping `now`
        // here let any device whose profile had no playlist configured re-stamp
        // its EMPTY config on every sign-in / Sync now and win the cloud race
        // against the sibling that had one — the "the guest profile's IPTV
        // didn't sync, every other profile does" report, since a guest profile
        // is exactly the one a second device has nothing set up for. The change
        // tracker below is what tells an edit from a mere rebuild.
        val signature = iptvSignature(context)
        val previous = prefs.getString(IPTV_SNAPSHOT_KEY, null)
        if (
            IptvConfigRules.shouldStampEdit(
                previous,
                signature,
                IptvConfigRules.looksConfigured(playlistUrl, extraPlaylistUrls)
            )
        ) {
            prefs.edit().putLong(IPTV_EDITED_AT_KEY, System.currentTimeMillis()).apply()
        }
        if (previous != signature) {
            prefs.edit().putString(IPTV_SNAPSHOT_KEY, signature).apply()
        }
        val editedAt = prefs.getLong(IPTV_EDITED_AT_KEY, 0L)
        return buildJsonObject {
            put("updatedAt", IptvConfigRules.publishStamp(editedAt))
            put("playlist_url", playlistUrl)
            put("playlist_name", prefs.getString("playlist_name", null).orEmpty())
            put("epg_url", prefs.getString("epg_url", null).orEmpty())
            putJsonArray("extra_epg_urls") {
                prefs.getString("extra_epg_urls", "").orEmpty()
                    .split('\n', ';')
                    .mapNotNull { it.trim().takeIf(String::isNotBlank) }
                    .forEach { add(it) }
            }
            putJsonArray("extra_playlist_urls") {
                extraPlaylistUrls.forEach { add(it) }
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

    /**
     * The IPTV config's own last-change time on this device, and the cloud
     * copy's stamp this device last adopted. The push gate compares the two
     * (see [IptvConfigRules.shouldPublish]).
     */
    fun iptvEditedAt(context: Context): Long =
        scopedPrefs(context, "iptv_prefs").getLong(IPTV_EDITED_AT_KEY, 0L)

    fun iptvCloudAt(context: Context): Long =
        scopedPrefs(context, "iptv_prefs").getLong(IPTV_SYNCED_AT_KEY, 0L)

    /**
     * The display blob's own last-change time on this device (the newest
     * per-key edit; 0 when there is no edit history yet), and the cloud copy's
     * `updatedAt` this device last adopted. The push gate compares the two
     * (see [DisplayPrefsRules.shouldPublish]).
     */
    fun displayEditedAt(context: Context): Long =
        readDisplayTimestamps(scopedPrefs(context, DISPLAY_PREFS_STORE)).values.maxOrNull() ?: 0L

    fun displayCloudAt(context: Context): Long =
        scopedPrefs(context, DISPLAY_PREFS_STORE).getLong(DISPLAY_SYNCED_AT_KEY, 0L)

    /**
     * The manual override set, and whether an empty one is MEANT.
     *
     * Every caller of this builder is a deliberate edit (a mark, an unmark, or
     * an unmark of the last marked title) — the bulk push skips the empty case
     * entirely (see [WatchedOverridesRules.shouldPublish] and
     * [SupabaseSync.pushPrefsBlobs]) — so `cleared` is exactly "this device
     * means to have no overrides", which is what the applier requires before it
     * will erase a sibling's set.
     */
    fun buildWatchedOverrides(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            val prefs = scopedPrefs(context, WatchedOverridesRules.STORE)
            val overrides = prefs.getStringSet(WatchedOverridesRules.SET_KEY, emptySet()).orEmpty()
            put(WatchedOverridesRules.CLEARED_FIELD, overrides.isEmpty())
            putJsonArray("overrides") {
                overrides.forEach { add(it) }
            }
            // Per key, so the title marked here is not erased by a sibling's
            // later mark of a DIFFERENT title, and so the title unmarked here
            // cannot be put back by a sibling's older mark (see
            // [WatchedOverridesRules.merge]).
            putJsonObject(WatchedOverridesRules.TIMESTAMPS_FIELD) {
                WatchedOverridesRules.decodeStamps(
                    prefs.getString(WatchedOverridesRules.KEY_STAMPS_KEY, null)
                ).forEach { (key, at) -> put(key, at) }
            }
            putJsonObject(WatchedOverridesRules.REMOVED_FIELD) {
                WatchedOverridesRules.decodeStamps(
                    prefs.getString(WatchedOverridesRules.REMOVED_KEY, null)
                ).forEach { (key, at) -> put(key, at) }
            }
        }

    /**
     * Local library (My List + personal lists + the My List removal record) as
     * raw JSON strings — the payloads mirror LocalLibraryStore's on-disk
     * encoding exactly, so the applier can hand them straight back without
     * re-encoding. The lists full-replace on apply (the latest writer wins,
     * matching how the store itself writes); the removal record is the one part
     * that MERGES instead (see [PrefsPayloadApplier.applyLibrary]), because a
     * removal that a newer list can overwrite is not a removal. That is the
     * cross-device half of the TorBox library leak: without it, one device's
     * round re-adds whatever the other device's cleanup removed.
     */
    fun buildLibrary(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            val prefs = scopedPrefs(context, "kbstream_library")
            put("my_list", prefs.getString("my_list", null) ?: "")
            put("personal_lists", prefs.getString("personal_lists", null) ?: "")
            put(
                LibraryTombstones.PAYLOAD_KEY,
                prefs.getString(LibraryTombstones.PREFS_KEY, null) ?: ""
            )
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

    /**
     * True when this profile has any signal at all: the guard the push path
     * uses so a device that never thumbed anything does not publish an empty
     * set over the account's real one.
     */
    fun hasTasteSignalsToPublish(context: Context): Boolean =
        !com.kennyb1201.kbstream.data.library.TasteSignals
            .encodedForSync(context)
            .isNullOrBlank()

    /**
     * The profile's taste signals, as the store's own encoded text (see
     * [TasteSignals.encodedForSync]) plus the moment this blob was built.
     *
     * The `updatedAt` is the blob's own age, NOT a conflict winner: the merge
     * resolves per title from each entry's timestamp (see
     * [TasteSignals.mergeFromSync]), so a device that has been offline for a
     * week still contributes the titles nobody else rated. Blank `entries` means
     * this profile has no signals, which the push path skips entirely rather
     * than publishing as "nothing is rated".
     */
    fun buildTasteSignals(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(
                "entries",
                com.kennyb1201.kbstream.data.library.TasteSignals
                    .encodedForSync(context)
                    .orEmpty()
            )
        }
}

/**
 * Applies a pulled prefs blob locally. Everything is defensive: a malformed
 * payload logs and returns instead of corrupting local state.
 */
object PrefsPayloadApplier {

    private const val TAG = "SUPABASE_SYNC"

    /**
     * The last payload applied for each "<profileScope>|<prefKey>".
     *
     * Per-profile, because the same key carries a different blob for each
     * profile: keying by prefKey alone would read a profile switch as a change
     * of every blob on the device. Bounded by the handful of pref keys the
     * cloud stores, and process-lifetime only - a restart simply re-fingerprints
     * on the first pull.
     */
    private val appliedFingerprints = mutableMapOf<String, String>()

    private val _revision = MutableStateFlow(0L)

    /**
     * Increments when a pulled blob actually DIFFERS from the last one applied
     * for the active profile.
     *
     * Why this exists: a blob pulled from a sibling device (a browse rail added
     * on the phone, a collection removed in the browser) only reached the
     * screen on the next Home entry. Reloading an open screen on every pull is
     * not the answer - [com.kennyb1201.kbstream.data.sync.SupabaseSync.lastPullAtMs]
     * ticks even when nothing changed, and a periodic/realtime pull would then
     * repaint Home for no reason. This fires only on a real difference.
     *
     * Deliberately a COUNTER, not a set of changed keys: a screen that renders
     * the arrangement, its browse chips and its collections together cannot do
     * anything useful with "which blob", only with "something landed".
     */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * Ticks the revision for a LOCAL edit of a synced pref.
     *
     * The revision used to move only when a pulled blob differed from the last
     * one applied, which made every `remember(syncedRevision) { read the pref }`
     * read a value only a sibling device could change. A viewer flipping a
     * switch in Settings got nothing: the switch itself moved (it is local
     * state), but every tile drawn from that pref kept the value it read at
     * first composition - the "changing it does nothing" report on the phone's
     * poster size and poster-title/year/rating toggles, where Settings sits one
     * screen away from the grid it changes.
     *
     * A local write is the same "something landed" the remote path reports, so
     * it lands on the same counter (see [AppPreferences.syncDisplayPrefsBlob],
     * which every display-pref setter calls). Readers cannot tell the two
     * apart, and do not need to.
     */
    fun notifyLocalPrefChange() {
        _revision.update { it + 1 }
    }

    // Suspend: the addon applier serializes through AddonManager's apply
    // mutex (shared with auto-update), which can suspend. Every caller is
    // already inside SupabaseSync's coroutine scope.
    //
    // The ACTIVE profile's stores. This is the pull path's entry point (a pulled
    // row was already filtered against the active profile); the realtime path
    // uses [applyForProfile], because a row that arrives live can belong to a
    // profile that is not the one on screen.
    suspend fun apply(context: Context, prefKey: String, payload: JsonObject) =
        applyForProfile(context, ProfileStorage.activeProfileId(context), prefKey, payload)

    /**
     * The owning-profile apply: [profileId] is the profile the ROW names (its
     * key's "p:<profileId>:" scope), and every store this touches is resolved
     * from it rather than from the active profile.
     *
     * Why it exists: a sibling TV's change to a profile that is not active here
     * used to be dropped outright (`storedKeyApplies`), so "the guest profile's
     * landscape posters" only appeared on the other TV after a restart — the
     * cold-start pull is what finally applied the row. Applying it to the
     * OWNING profile's store instead leaves the change already in place when
     * that profile is next switched to, and the store is still derived from the
     * key alone, so the cross-profile leak the old pin guarded against stays
     * impossible.
     *
     * Live side effects — the theme mirrors, the addon manager's in-memory list,
     * the Simkl caches, an EPG re-schedule — belong to the profile on screen, so
     * they run only when [profileId] IS the active one. The store writes run
     * either way: they are what the next switch reads.
     */
    suspend fun applyForProfile(
        context: Context,
        profileId: String?,
        prefKey: String,
        payload: JsonObject
    ) {
        // Fingerprint BEFORE the apply, but never to SKIP the apply: sync is
        // last-write-wins, so a local edit that diverged from an unchanged
        // server blob still has to be overwritten by the next pull. The apply
        // therefore runs exactly as it did; the fingerprint only answers "did
        // this pull change anything", which is what the revision above reports.
        // Keyed per profile, because the same key carries a different blob for
        // each one.
        val scope = profileId ?: "global"
        val fingerprintKey = "$scope|$prefKey"
        val encoded = payload.toString()
        val changed = synchronized(appliedFingerprints) {
            val previous = appliedFingerprints[fingerprintKey]
            appliedFingerprints[fingerprintKey] = encoded
            previous != encoded
        }

        val live = profileId == ProfileStorage.activeProfileId(context)

        when (prefKey) {
            PrefsPayloadBuilder.KEY_DISPLAY_PREFS ->
                applyDisplayPrefs(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_ADDONS -> applyAddons(context, payload, profileId)
            PrefsPayloadBuilder.KEY_SIMKL_AUTH ->
                applySimklAuth(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_TRAKT_AUTH ->
                applyTraktAuth(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_API_KEYS ->
                applyApiKeys(context, payload, profileId)
            PrefsPayloadBuilder.KEY_IPTV -> applyIptv(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_WATCHED_OVERRIDES ->
                applyWatchedOverrides(context, payload, profileId)
            PrefsPayloadBuilder.KEY_HOME_ORDER -> applyHomeOrder(context, payload, profileId)
            PrefsPayloadBuilder.KEY_BROWSE_SHORTCUTS ->
                applyBrowseShortcuts(context, payload, profileId)
            PrefsPayloadBuilder.KEY_CUSTOM_CATALOGS ->
                applyCustomCatalogs(context, payload, profileId)
            PrefsPayloadBuilder.KEY_COLLECTIONS ->
                applyCollections(context, payload, profileId)
            PrefsPayloadBuilder.KEY_BADGE_PACK -> applyBadgePack(context, payload, profileId)
            PrefsPayloadBuilder.KEY_LIBRARY -> applyLibrary(context, payload, profileId)
            PrefsPayloadBuilder.KEY_DISMISSALS -> applyDismissals(context, payload, profileId)
            PrefsPayloadBuilder.KEY_TASTE_SIGNALS ->
                applyTasteSignals(context, payload, profileId)
            PrefsPayloadBuilder.KEY_PROFILES ->
                com.kennyb1201.kbstream.data.sync.ProfileManager.applyProfilesPayload(
                    context, payload
                )
        }

        // After the apply, so anything observing the revision reads the
        // already-updated local stores. Only a change to the ACTIVE profile's
        // stores can repaint a screen that is on it: a sibling profile's row is
        // picked up by the next composition after that profile is switched to.
        if (changed && live) _revision.value += 1
    }

    private fun applyBadgePack(context: Context, payload: JsonObject, profileId: String?) {
        fun str(key: String): String =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

        val url = str("badge_pack_url")
        val packJson = str("badge_pack_json")
        if (packJson.isBlank()) return

        val prefs = profilePrefs(context, "kbstream_stream_badges", profileId)
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
     *
     * The one exception is the removal record, and it is what makes removals
     * converge: a tombstone is merged (newest timestamp per key, never a
     * whole-blob replacement) BEFORE the timestamp gate, so a stale device's
     * push can win the list but never resurrect a title. Whatever the gate then
     * decides, the list that ends up stored is filtered against the MERGED
     * record - the incoming one when the pull wins, the local one when it does
     * not - so the two devices converge on the same list without either needing
     * its own cleanup pass to have fired.
     */
    private fun applyLibrary(context: Context, payload: JsonObject, profileId: String?) {
        val remoteUpdated = payloadUpdatedAt(payload)
        val prefs = profilePrefs(context, "kbstream_library", profileId)
        val remoteIsStale = remoteUpdated != null &&
            remoteUpdated < prefs.getLong("library_synced_at", 0L)

        fun raw(key: String): String? {
            val v = (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            return v?.takeIf { it.isNotBlank() }
        }

        val tombstones = LocalLibraryStore.mergePulledTombstones(
            context = context,
            profileId = profileId,
            incoming = LibraryTombstones.decode(raw(LibraryTombstones.PAYLOAD_KEY))
        )

        val editor = prefs.edit()
        if (!remoteIsStale) {
            raw("personal_lists")?.let { editor.putString("personal_lists", it) }
            editor.putLong("library_synced_at", remoteUpdated ?: System.currentTimeMillis())
        }

        // The list this profile ends up with: the pulled blob when the pull wins,
        // otherwise the one already here - filtered either way, so a removal
        // learned from the other device is applied even to a list that lost the
        // race for being written.
        val source = (if (remoteIsStale) null else raw("my_list"))
            ?: LocalLibraryStore.myListBlob(context, profileId)
        if (source != null) {
            editor.putString(
                "my_list",
                LocalLibraryStore.withoutTombstonedRows(source, tombstones)
            )
        }

        editor.apply()
    }

    /**
     * Dismissals apply: same timestamp-guarded pattern as the library —
     * a remote blob older than the newest local dismissal write is
     * rejected so offline dismissals survive.
     */
    /**
     * Taste signals: MERGED per title rather than adopted whole.
     *
     * Every other blob here is last-write-wins because it describes one
     * coherent thing (an arrangement, a library, a session). A signals set does
     * not: two devices each rate different titles, and replacing one with the
     * other would lose thumbs that were never in conflict. The merge (see
     * [TasteSignals.mergeFromSync]) keeps every title from both sides and lets
     * the newer verdict win where the same title was rated twice.
     *
     * A blank `entries` is "this account has none", never "erase yours" - the
     * same rule the home order and library blobs follow, and the reason the push
     * side skips a device with nothing to say.
     */
    private fun applyTasteSignals(context: Context, payload: JsonObject, profileId: String?) {
        val raw = (payload["entries"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() } ?: return
        val changed = com.kennyb1201.kbstream.data.library.TasteSignals
            .mergeFromSync(context, raw, profileId)
        if (changed > 0) {
            android.util.Log.i(TAG, "taste signals: merged $changed entr(y/ies) from the account")
        }
    }

    private fun applyDismissals(context: Context, payload: JsonObject, profileId: String?) {
        val remoteUpdated = payloadUpdatedAt(payload)
        val prefs = profilePrefs(context, "continue_watching_dismissals", profileId)
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

    private fun applyHomeOrder(context: Context, payload: JsonObject, profileId: String?) {
        val blob = (payload["home_order_json"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        // A blank blob is "this device has no arrangement", never "erase
        // yours": adopting it would wipe a real rail order for everyone and
        // read exactly like "it doesn't sync" from the receiving end.
        if (blob.isBlank()) return
        val prefs = profilePrefs(context, "kbstream_kb_home_order", profileId)
        val remoteUpdated = payloadUpdatedAt(payload)
        if (remoteUpdated != null && remoteUpdated < prefs.getLong("home_order_synced_at", 0L)) return

        val local = prefs.getString("home_order_json", null).orEmpty()
        if (blob == local) return

        prefs.edit()
            .putString("home_order_json", blob)
            .putLong("home_order_synced_at", remoteUpdated ?: System.currentTimeMillis())
            .apply()
    }

    /**
     * Browse chips apply: the store's raw blob replaced wholesale, guarded by
     * the local edit stamp so an older sibling-device copy cannot revert a
     * chip the user just added (the same rule the home order follows).
     */
    private fun applyBrowseShortcuts(context: Context, payload: JsonObject, profileId: String?) {
        val blob =
            (payload["shortcuts_json"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content
                ?: return
        // A blank blob is "no chips mirrored here", not "remove them from
        // every device": adopting it would erase the account's rails.
        if (blob.isBlank()) return

        val prefs =
            profilePrefs(
                context,
                com.kennyb1201.kbstream.data.kb.BrowseHomeShortcuts.SYNC_STORE,
                profileId
            )
        val blobKey =
            com.kennyb1201.kbstream.data.kb.BrowseHomeShortcuts.SYNC_BLOB_KEY
        val syncedAtKey =
            com.kennyb1201.kbstream.data.kb.BrowseHomeShortcuts.SYNCED_AT_KEY

        val remoteUpdated = payloadUpdatedAt(payload)
        if (!HomeListBlobRules.shouldApply(remoteUpdated, prefs.getLong(syncedAtKey, 0L))) {
            return
        }

        if (blob == prefs.getString(blobKey, null).orEmpty()) return

        prefs.edit()
            .putString(blobKey, blob)
            .putLong(syncedAtKey, remoteUpdated ?: System.currentTimeMillis())
            .apply()
    }

    /**
     * Built-catalog apply: the store's raw blob replaced wholesale, guarded by
     * the local edit stamp so an older sibling-device copy cannot revert a
     * catalog the user just built (the same rule the chips follow).
     */
    private fun applyCustomCatalogs(context: Context, payload: JsonObject, profileId: String?) {
        val blob =
            (payload["catalogs_json"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content
                ?: return
        // A blank blob is "none built here", not "delete them everywhere":
        // adopting it would erase hand-composed rule sets from every device.
        if (blob.isBlank()) return

        val prefs =
            profilePrefs(
                context,
                com.kennyb1201.kbstream.data.catalogs.CustomCatalogStore.SYNC_STORE,
                profileId
            )
        val blobKey =
            com.kennyb1201.kbstream.data.catalogs.CustomCatalogStore.SYNC_BLOB_KEY
        val syncedAtKey =
            com.kennyb1201.kbstream.data.catalogs.CustomCatalogStore.SYNCED_AT_KEY

        val remoteUpdated = payloadUpdatedAt(payload)
        if (!HomeListBlobRules.shouldApply(remoteUpdated, prefs.getLong(syncedAtKey, 0L))) {
            return
        }

        if (blob == prefs.getString(blobKey, null).orEmpty()) return

        prefs.edit()
            .putString(blobKey, blob)
            .putLong(syncedAtKey, remoteUpdated ?: System.currentTimeMillis())
            .apply()
    }

    /**
     * Collections apply: full replace with the remote URL list, but only when
     * the remote write is newer than this device's last local edit or adoption
     * (see [KBProfilePrefs]) — an imported collection must survive a sibling's
     * older blob, and the device re-pushes its own list on its next import.
     */
    private fun applyCollections(context: Context, payload: JsonObject, profileId: String?) {
        val arr = payload["profile_urls"] as? kotlinx.serialization.json.JsonArray ?: return
        val urls = arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val prefs = profilePrefs(context, "kbstream_kb_collections", profileId)

        val remoteUpdated = payloadUpdatedAt(payload)
        if (!HomeListBlobRules.shouldApply(remoteUpdated, prefs.getLong("collections_synced_at", 0L))) {
            return
        }

        val joined = urls.joinToString("\n")
        if (joined == prefs.getString("profile_urls", null).orEmpty()) return

        prefs.edit()
            .putString("profile_urls", joined)
            .putLong("collections_synced_at", remoteUpdated ?: System.currentTimeMillis())
            .apply()
    }

    private fun applyDisplayPrefs(
        context: Context,
        payload: JsonObject,
        profileId: String?,
        live: Boolean
    ) {
        val prefs = profilePrefs(context, DISPLAY_PREFS_STORE, profileId)
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
            // Only keys we still SYNC may be adopted. A blob pushed by a build
            // that predates the secure-store move can still carry an API key;
            // without this filter the pull wrote it back into plaintext display
            // prefs (SI-P2-2). The builder only ever pushes SYNCED_PREF_KEYS,
            // so a key outside the set is exactly the stale-blob case.
            if (key !in PrefsPayloadBuilder.SYNCED_PREF_KEYS) return@forEach
            val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return@forEach
            val content = primitive.content

            // Merge per key. A per-key blob carries a stamp only for the keys
            // its device actually edited, so a key with NO stamp is one the
            // pusher has no opinion on and is left alone. The old
            // `?: fallbackTs` read every unstamped key as edited at push time,
            // which is what let a fresh profile's defaults win on each sibling;
            // a legacy whole-blob (no timestamps map at all) still falls back to
            // its own updatedAt.
            val editsAt =
                if (remoteTimestamps.isNotEmpty()) remoteTimestamps[key] else fallbackTs
            if (editsAt == null) return@forEach
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

        // The AMOLED / pure-black toggles back onto live theme state, not just
        // the prefs file - a remote blob applied here must mirror into it so a
        // synced device repaints immediately (setContent only seeds it at
        // launch). Re-reading through the theme's own mirror keeps the data
        // layer out of the UI state entirely: whichever values were actually
        // adopted above are now in prefs, and the rest read back unchanged.
        // Only for the profile on screen: the mirror is the LIVE theme of this
        // device, and re-reading it for a sibling profile's file would just
        // overwrite it with the active profile's own values.
        if (live) com.kennyb1201.kbstream.ui.theme.refreshThemeMirrors(context)
    }

    private suspend fun applyAddons(context: Context, payload: JsonObject, profileId: String?) {
        val addonsJson = (payload["installed_addons_json"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        if (addonsJson.isBlank()) return

        // The published configuration edit time is what decides whether this
        // blob may replace a newer local configuration (see
        // [AddonsConfigRules]). A blob from a build that predates the field
        // reads as edit time 0 — deliberately NOT its push time: an old
        // build stamped updatedAt at push time, so trusting it would let an
        // untouched old device's default order overwrite a configured one.
        // Edit time 0 can still be adopted by a device that has no local
        // configuration of its own.
        val remoteEditedAt =
            (payload[AddonsConfigRules.CONFIG_EDITED_AT_FIELD] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toLongOrNull()
                ?: 0L

        // Remember the cloud copy's edit stamp even when there is nothing to
        // adopt (identical content, or a local config that wins). This is what
        // lets the publish gate tell "already synced" from "never sent".
        com.kennyb1201.kbstream.data.addon.AddonManager.getInstance(context)
            .observeAddonsCloudEditedAt(remoteEditedAt, profileId)

        val prefs = profilePrefs(context, "kbstream_addons", profileId)
        val localAddons = prefs.getString("installed_addons_json", null).orEmpty()
        if (addonsJson == localAddons) return

        // Serialize with auto-update applies through the manager's mutex:
        // both paths replace/merge the full addon list, and unserialized
        // writers would clobber each other's updates.
        try {
            com.kennyb1201.kbstream.data.addon.AddonManager.getInstance(context)
                .applySyncedAddons(addonsJson, remoteEditedAt, profileId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "addon reload after sync failed: ${e.message}")
        }
    }

    private fun applySimklAuth(
        context: Context,
        payload: JsonObject,
        profileId: String?,
        live: Boolean
    ) {
        val token = (payload[SIMKL_ACCESS_TOKEN_KEY] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content.orEmpty()
        val signedOut =
            (payload[SimklAuthRules.SIGNED_OUT_FIELD] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content == "true"

        val prefs = simklAuthPrefs(context, profileId)

        // Blank token = the profile SIGNED OUT on some device. Honor it — but
        // only when the blob says so explicitly. Every device used to publish
        // this blob, so the blanks coming from devices that had never
        // connected Simkl erased a live session everywhere; a blank without
        // the tombstone is now ignored (see [SimklAuthRules]).
        if (token.isBlank()) {
            if (!SimklAuthRules.clearsSession(token, signedOut)) return
            if (!prefs.contains(SIMKL_ACCESS_TOKEN_KEY)) return
            prefs.edit().remove(SIMKL_ACCESS_TOKEN_KEY).apply()
            // The caches below are the ON-SCREEN profile's; a sibling
            // profile's sign-out must not clear them.
            if (live) resetSimklStateAfterTokenChange(context)
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
        if (live) resetSimklStateAfterTokenChange(context)
    }

    /**
     * The Simkl session just changed (adopted a synced token, or dropped one)
     * so everything derived from the PREVIOUS account has to go: the
     * in-memory lists (keyed by profile, not by account) and the
     * watched-activity checkpoint, which otherwise tells the next poll that
     * nothing changed and serves the old account's Continue Watching. The
     * token itself needs no cache handling — SimklRepository reads it live —
     * but the ON-DISK Continue Watching blob does: it is keyed by profile
     * while its contents are per-account, so it is what the read after this
     * one falls straight back to.
     */
    private fun resetSimklStateAfterTokenChange(context: Context) {
        runCatching {
            val simkl = com.kennyb1201.kbstream.data.simkl.SimklRepository
            simkl.clearTransientCaches()
            val repository = simkl.getInstance(context)
            repository.forceClearWatchedActivitySync()
            // The feed itself, on disk. Memory above is not enough: the next
            // read answers from the 6h blob first, and the Upcoming rail is
            // built from that feed.
            repository.clearContinueWatchingDiskBlob()
        }.onFailure {
            android.util.Log.w(TAG, "simkl reset after synced token change failed: ${it.message}")
        }
    }

    /**
     * Adopts the account's Trakt session.
     *
     * Full replace, like the Simkl blob, but with two extra guards: a blank
     * only clears when it carries the sign-out tombstone (see
     * [TraktAuthRules.clearsSession]), and a stale remote session never
     * replaces a live local one (see [TraktAuthRules.shouldAdopt]). The whole
     * session is written because a refresh on the other device rotated the
     * single-use refresh token; adopting only the access token would leave this
     * device unable to refresh once it expires.
     */
    private fun applyTraktAuth(
        context: Context,
        payload: JsonObject,
        profileId: String?,
        live: Boolean
    ) {
        fun text(key: String): String =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

        val access = text(TraktTokenStore.KEY_ACCESS_TOKEN)
        val refresh = text(TraktTokenStore.KEY_REFRESH_TOKEN)
        val expiresAt = text(TraktTokenStore.KEY_EXPIRES_AT).toLongOrNull() ?: 0L
        val slug = text(TraktTokenStore.KEY_ACCOUNT_SLUG).takeIf { it.isNotBlank() }
        val signedOut = text(TraktAuthRules.SIGNED_OUT_FIELD) == "true"

        val store = traktAuthStore(context, profileId)
        val local = store.read()

        if (access.isBlank()) {
            if (!TraktAuthRules.clearsSession(access, signedOut)) return
            if (local == null && !store.isSignedOut()) return
            store.markSignedOut()
            if (live) resetTraktStateAfterSessionChange(context)
            return
        }

        val adopt = TraktAuthRules.shouldAdopt(
            incomingAccess = access,
            incomingExpiresAt = expiresAt,
            incomingAccountSlug = slug,
            localAccess = local?.accessToken,
            localExpiresAt = local?.expiresAtEpochMillis ?: 0L,
            localAccountSlug = store.accountSlug(),
            nowMs = System.currentTimeMillis()
        )
        if (!adopt) return

        val nextRefresh = refresh.takeIf { it.isNotBlank() } ?: local?.refreshToken
        val unchanged = local?.accessToken == access &&
            local.refreshToken == nextRefresh &&
            local.expiresAtEpochMillis == expiresAt &&
            store.accountSlug() == slug
        if (unchanged) {
            // Same session: just retire a lingering disconnect marker so it
            // cannot be republished over the live token on the next push.
            store.clearSignedOutMarker()
            return
        }

        store.save(
            TraktToken(
                accessToken = access,
                refreshToken = nextRefresh,
                expiresAtEpochMillis = expiresAt
            )
        )
        store.setAccountSlug(slug)
        if (live) resetTraktStateAfterSessionChange(context)
    }

    /**
     * The Trakt session just changed on the on-screen profile, so everything
     * derived from the PREVIOUS account goes: the repository's watched snapshot
     * and every watched set the shared repository cached. Local history, resume
     * rows and watched markers are the viewer's and stay untouched - the same
     * line [TraktRepository.revokeAndClear] draws.
     */
    private fun resetTraktStateAfterSessionChange(context: Context) {
        runCatching {
            com.kennyb1201.kbstream.data.trakt.TraktRepository.getInstance(context)
                .onSyncedSessionChanged()
            com.kennyb1201.kbstream.data.watched.WatchedStatusRepository.invalidateAllCaches()
        }.onFailure {
            android.util.Log.w(TAG, "trakt reset after synced session change failed: ${it.message}")
        }
    }

    /**
     * Adopts the account's service credentials, one key at a time.
     *
     * Per key and by edit stamp, so a viewer who pasted the TorBox key on one TV
     * and the MDBList key on the other keeps both, and so a deliberate clear
     * (a blank with a newer stamp) still propagates. Only the keys the account
     * copy carries are touched: a device's own key is never blanked by a blob
     * that simply has no entry for it.
     */
    private fun applyApiKeys(context: Context, payload: JsonObject, profileId: String?) {
        val stamps = payload[ApiKeySyncRules.TIMESTAMPS_FIELD] as? JsonObject ?: return
        val keys = payload[ApiKeySyncRules.KEYS_FIELD] as? JsonObject
        var adopted = false
        stamps.forEach { (name, stampValue) ->
            // Allowlist, the same rule the display applier has: a blob written by
            // a build that knows a key this one does not must not have it written
            // into this device's store.
            if (name !in com.kennyb1201.kbstream.data.settings.AppPreferences.SYNCED_API_KEYS) {
                return@forEach
            }
            val remoteEditedAt = (stampValue as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toLongOrNull() ?: return@forEach
            val localEditedAt = com.kennyb1201.kbstream.data.settings.AppPreferences
                .apiKeyEditedAt(context, name, profileId)
            if (!ApiKeySyncRules.remoteKeyWins(remoteEditedAt, localEditedAt)) return@forEach
            val value = (keys?.get(name) as? kotlinx.serialization.json.JsonPrimitive)
                ?.content.orEmpty()
            com.kennyb1201.kbstream.data.settings.AppPreferences
                .adoptApiKeyFromSync(context, name, value, remoteEditedAt, profileId)
            adopted = true
        }
        if (adopted) {
            com.kennyb1201.kbstream.data.settings.AppPreferences.markApiKeysAdopted(
                context,
                payloadUpdatedAt(payload) ?: System.currentTimeMillis(),
                profileId
            )
        }
    }

    private fun applyIptv(
        context: Context,
        payload: JsonObject,
        profileId: String?,
        live: Boolean
    ) {
        val prefs = profilePrefs(context, "iptv_prefs", profileId)
        val remoteUpdated = payloadUpdatedAt(payload)
        // A remote config wins only when its edit is strictly NEWER than this
        // profile's own last edit here — not, as it used to be, when it beat
        // `iptv_synced_at`: that key was stamped even when the apply wrote
        // nothing, so an EMPTY blob from a device that had never configured
        // IPTV both changed nothing locally and blocked the real config from
        // ever landing on any device that pulled it (see [IptvConfigRules]).
        if (!IptvConfigRules.shouldApply(remoteUpdated, prefs.getLong(IPTV_EDITED_AT_KEY, 0L))) {
            return
        }

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
        val guidePrefs = profilePrefs(context, "iptv_guide_preferences", profileId)
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
        editor.putLong(IPTV_SYNCED_AT_KEY, remoteUpdated ?: System.currentTimeMillis())
        editor.apply()
        // The adopted config is not a LOCAL edit: record what this device now
        // holds, so the next build's change tracker does not read the adoption
        // as a change of ours and echo the same config back with a new stamp
        // (the same "adopt, do not echo" rule the addons blob follows).
        prefs.edit().putString(IPTV_SNAPSHOT_KEY, iptvSignature(context, profileId)).apply()

        // Trigger IPTV reload if the source actually changed. The re-schedule is
        // the on-screen profile's EPG: a sibling profile's config write waits
        // for that profile's own activation to schedule its guide.
        if (live &&
            playlistUrl.isNotBlank() &&
            playlistUrl != prefs.getString("playlist_applied_url", null)
        ) {
            prefs.edit().putString("playlist_applied_url", playlistUrl).apply()
            runCatching {
                com.kennyb1201.kbstream.data.iptv.EpgRefreshScheduler.schedule(context)
            }
        }
    }

    private fun applyWatchedOverrides(
        context: Context,
        payload: JsonObject,
        profileId: String?
    ) {
        val arr = payload["overrides"] as? kotlinx.serialization.json.JsonArray ?: return
        val set = arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()

        val prefs = profilePrefs(context, WatchedOverridesRules.STORE, profileId)
        val deliberateClear =
            (payload[WatchedOverridesRules.CLEARED_FIELD]
                as? kotlinx.serialization.json.JsonPrimitive)
                ?.content == "true"
        // Never a bare putStringSet: an empty blob from a device that never
        // hand-marked anything (or an older blob than this device's own last
        // mark) used to erase the local set, which is the wipe this guard
        // closes — see [WatchedOverridesRules].
        val localSyncedAt =
            maxOf(
                prefs.getLong(WatchedOverridesRules.EDITED_AT_KEY, 0L),
                prefs.getLong(WatchedOverridesRules.SYNCED_AT_KEY, 0L)
            )
        if (!WatchedOverridesRules.shouldApply(
                payloadUpdatedAt(payload),
                localSyncedAt,
                set,
                deliberateClear
            )
        ) {
            return
        }

        val appliedAt = payloadUpdatedAt(payload) ?: System.currentTimeMillis()
        val localKeys =
            prefs.getStringSet(WatchedOverridesRules.SET_KEY, emptySet()).orEmpty()
        val localKeyStamps = WatchedOverridesRules.decodeStamps(
            prefs.getString(WatchedOverridesRules.KEY_STAMPS_KEY, null)
        )
        val localRemoved = WatchedOverridesRules.decodeStamps(
            prefs.getString(WatchedOverridesRules.REMOVED_KEY, null)
        )

        if (set.isEmpty() && deliberateClear) {
            // The sender means to hold nothing, and that is an unmark of every
            // key it held: recording each one is what stops an older mark still
            // sitting on another device from putting the title back.
            val unmarked = LinkedHashMap(localRemoved)
            (localKeys + localKeyStamps.keys).forEach { key -> unmarked[key] = appliedAt }
            prefs.edit()
                .putStringSet(WatchedOverridesRules.SET_KEY, emptySet())
                .putString(
                    WatchedOverridesRules.KEY_STAMPS_KEY,
                    WatchedOverridesRules.encodeStamps(emptyMap())
                )
                .putString(
                    WatchedOverridesRules.REMOVED_KEY,
                    WatchedOverridesRules.encodeStamps(unmarked)
                )
                .putLong(WatchedOverridesRules.SYNCED_AT_KEY, appliedAt)
                .apply()
            return
        }

        // Per key, not wholesale: what this device marked and the sender never
        // heard about is KEPT, and the sender's own marks are adopted
        // individually by their stamps (see [WatchedOverridesRules.merge]).
        val merge = WatchedOverridesRules.merge(
            incoming = set,
            incomingStamps = stampsFrom(payload, WatchedOverridesRules.TIMESTAMPS_FIELD),
            incomingRemoved = stampsFrom(payload, WatchedOverridesRules.REMOVED_FIELD),
            localKeys = localKeys,
            localStamps = WatchedOverridesRules.opinions(localKeyStamps, localRemoved)
        )

        val nextKeys = (localKeys + merge.added) - merge.removed
        val nextKeyStamps = LinkedHashMap(localKeyStamps)
        val nextRemoved = LinkedHashMap(localRemoved)
        merge.stamps.forEach { (key, stamp) ->
            if (key in merge.removed) {
                nextKeyStamps.remove(key)
                nextRemoved[key] = stamp
            } else {
                nextRemoved.remove(key)
                nextKeyStamps[key] = stamp
            }
        }
        // The stamp map only ever names keys the set holds: a key that left with
        // no stamp of its own is the removed map's business, and a stale entry
        // here would read as an opinion this device does not hold.
        nextKeyStamps.keys.retainAll(nextKeys)

        prefs.edit()
            .putStringSet(WatchedOverridesRules.SET_KEY, nextKeys)
            .putString(
                WatchedOverridesRules.KEY_STAMPS_KEY,
                WatchedOverridesRules.encodeStamps(nextKeyStamps)
            )
            .putString(
                WatchedOverridesRules.REMOVED_KEY,
                WatchedOverridesRules.encodeStamps(nextRemoved)
            )
            .putLong(WatchedOverridesRules.SYNCED_AT_KEY, appliedAt)
            .apply()
    }

    /** The `{key: stamp}` field of a payload as a map; absent reads as empty. */
    private fun stampsFrom(payload: JsonObject, field: String): Map<String, Long> {
        val obj = payload[field] as? JsonObject ?: return emptyMap()
        return obj
            .mapNotNull { (key, value) ->
                (value as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content
                    ?.toLongOrNull()
                    ?.let { at -> key to at }
            }
            .toMap()
    }

    private fun payloadUpdatedAt(payload: JsonObject): Long? =
        (payload["updatedAt"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
}
