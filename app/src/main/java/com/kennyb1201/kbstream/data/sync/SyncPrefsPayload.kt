package com.kennyb1201.kbstream.data.sync

import android.content.Context
import com.kennyb1201.kbstream.data.anime.AniListToken
import com.kennyb1201.kbstream.data.anime.AniListTokenStore
import com.kennyb1201.kbstream.data.badges.BadgePackSyncRules
import com.kennyb1201.kbstream.data.badges.StreamBadgeEngine
import com.kennyb1201.kbstream.data.bingecat.BingeCatToken
import com.kennyb1201.kbstream.data.bingecat.BingeCatTokenStore
import com.kennyb1201.kbstream.data.kitsu.KitsuToken
import com.kennyb1201.kbstream.data.kitsu.KitsuTokenStore
import com.kennyb1201.kbstream.data.library.LibraryTombstones
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.mal.MalToken
import com.kennyb1201.kbstream.data.mal.MalTokenStore
import com.kennyb1201.kbstream.data.trakt.TraktToken
import com.kennyb1201.kbstream.data.trakt.TraktTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/**
 * The BingeCat session store, addressed the same way: the ACTIVE profile by
 * default, or [profileId] when the applier is writing a sibling's copy.
 */
private fun bingecatAuthStore(context: Context, profileId: String? = null) =
    BingeCatTokenStore(context, profileId)

/**
 * The Kitsu session store, addressed the same way as [traktAuthStore]: the active
 * profile by default, or [profileId] when the applier writes a sibling's copy.
 */
private fun kitsuAuthStore(context: Context, profileId: String? = null) =
    KitsuTokenStore(context, profileId)

/** The MAL session store, on the same rule as [kitsuAuthStore]. */
private fun malAuthStore(context: Context, profileId: String? = null) =
    MalTokenStore(context, profileId)

/** The AniList session store, on the same rule as [kitsuAuthStore]. */
private fun anilistAuthStore(context: Context, profileId: String? = null) =
    AniListTokenStore(context, profileId)

// The mapping row's LOCAL bookkeeping, next to the mapping itself but not inside
// it: `BingeCatProfileMap`'s prefs name and keys are its own contract. These two
// keys are never published - they are how the row is built (`written_at` is the
// row's `updatedAt`) and how a blank is told from a deliberate clear.
private const val BINGECAT_MAP_SYNC_STORE = "bingecat_map_sync"
private const val BINGECAT_MAP_WRITTEN_AT = "written_at"
private const val BINGECAT_MAP_CLEARED = "cleared"

/** The mapping bookkeeping store for the active profile, or for one named row. */
private fun bingecatMapSyncPrefs(context: Context, profileId: String? = null) =
    profilePrefs(context, BINGECAT_MAP_SYNC_STORE, profileId)

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
        // deliberately NOT here (decision, 2026-10-20, unchanged): the useful
        // tile size and card shape depend on the screen — a 65" TV and a 6.7"
        // phone want different answers — so they are screen geometry, not
        // identity. That family is now the ONLY local layout family; see
        // [TV_SCOPED_SYNC_KEYS] below for the six keys that left it.
        "home_layout",                     // see [TV_SCOPED_SYNC_KEYS]: TV to TV,
        "home_billboard_enabled",           //   device-level, never to a phone
        "home_billboard_feeds",
        "home_billboard_cycle_ms",
        "home_billboard_trailer_mode",
        "home_card_expand_ms",
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
        "torbox_cached_only",                // should the picker list only what that account already
                                             //   holds? The same account-level question, so the
                                             //   household's TVs agree instead of each hiding
                                             //   different rows
        "torbox_cloud_rows",                 // should the picker offer the account's own cloud FILES
                                             //   (a release no add-on returns)? Off by default;
                                             //   a viewing preference, so it follows the profile
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
        "sports_favorite_teams",
        // The hub's reminder master switch (settings reorg Q4). A per-profile
        // viewing choice like the three above - following a team IS the opt-in -
        // and not a device capability, so turning reminders off on the TV means
        // the phone stops buzzing about the same game. A device that never
        // touched it publishes nothing for it (no default is pushed on upgrade),
        // and the receiving box picks the value up at its next reminder round
        // rather than by rearming WorkManager from a sync apply.
        "sports_game_reminders"
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
        // The LEARNED bitrate ceiling (see AutoplayBitrate) is the same kind of
        // fact one step further: it is what this box's line has proven it can
        // hold, learned from its own stalls. A phone on mobile data must not
        // cap the TV on fibre, and a TV's stall must not narrow the phone's
        // auto-play. Device-local, decided 2026-11-03.
        "learned_sustainable_mbps",
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
        // The TV screensaver's switch and wait are this PANEL's: how long the
        // television in this room sits idle is not a fact about the account,
        // and the phone has no Home that idles. Device-local (see
        // AppPreferences), pinned here so a later "this looks synced" habit
        // cannot put them on the wire.
        "screensaver_enabled",
        "screensaver_timeout",
        // `force_software_decoder` used to sit here with no accessor anywhere in
        // the app - a key nothing could read or write. Removed with the other
        // dead entries (settings reorg U4).
        "enable_tunneling",
        "enable_pip",
        "decoder_mode",
        "decoder_priority",
        "video_decoder",
        "audio_decoder",
        "dv_compat_mode",
        "strip_hdr10_plus",
        // The DV persistence converters are real settings rows, so their keys
        // stay: a blob from another TV must never arm a rewrite nobody there is
        // running. (Two more entries used to sit above these - `dv_diag_drop_rpu`
        // and `dv_diag_no_color_info` - claiming rows that never existed; removed
        // with settings reorg U4.)
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
        // Which of two equally exact rates this panel presents properly: the
        // question the match above only asks arithmetically (audit AFR S1). A
        // synced answer would have one TV asking for the rate the other one was
        // tried on, which is worse than no answer - so it stays on the box it was
        // learned on.
        "prefer_whole_frame_rate",
        // Whether the trailer pipeline can mint YouTube PO tokens at all is a
        // property of this box (some Fire OS images ship no usable WebView), so
        // a TV's answer must never switch the phone's minter off.
        "youtube_po_token_enabled",
        // Whether a launch opens on Who's Watching. How a device is shared is a
        // property of that device: a living-room TV wants the picker every
        // time, a phone that only ever uses one profile does not - so picking
        // one answer on the TV must not remove the picker from the phone.
        "show_profile_picker",
    )

    /**
     * The six keys that sync TV to TV, and only TV to TV (spec 2026-11-12).
     *
     * The Home layout selection, the card dwell that goes with it, and the
     * billboard settings that go with it (the on/off switch, the catalog
     * selection, the hold, the trailer mode). One television's arrangement is
     * the other television's - and NOT a phone's, in either direction:
     *
     *  - only a TV PUBLISHES them ([buildDisplayPrefs] reads them from the
     *    device store on a TV and omits them everywhere else), so a phone's
     *    billboard settings can never travel up and rearrange a TV;
     *  - only a TV ADOPTS them ([applyDisplayPrefs] drops them on a phone), so
     *    a TV's arrangement never lands on a phone at all;
     *  - on a TV they are applied DEVICE-WIDE, to the device store (see
     *    AppPreferences.DEVICE_SCOPED_KEYS), never to a profile's file - they
     *    are the same on every profile of that box.
     *
     * The screen-geometry family (`poster_size`, `home_landscape_cards`,
     * `landscape_posters`) stays device-local and never appears here.
     */
    internal val TV_SCOPED_SYNC_KEYS = setOf(
        "home_layout",
        "home_billboard_enabled",
        "home_billboard_feeds",
        "home_billboard_cycle_ms",
        "home_billboard_trailer_mode",
        "home_card_expand_ms"
    )

    const val KEY_DISPLAY_PREFS = "display_prefs"

    /**
     * The Library lists pinned to Home (see
     * com.kennyb1201.kbstream.data.library.LibraryHomeRails).
     *
     * A Home row the viewer built, so it travels like the browse chips and the
     * rail arrangement do - with the same "nothing pinned here publishes
     * nothing" rule, plus one half those two do not need: removing the last pin
     * is a deliberate answer that DOES publish, or the sibling's next push would
     * put the rail back.
     */
    const val KEY_LIBRARY_HOME_RAILS = "library_home_rails"
    const val KEY_ADDONS = "addons"
    const val KEY_SIMKL_AUTH = "simkl_auth"

    /**
     * The Trakt session, the same shape (and the same "never publish a blank"
     * rule) as [KEY_SIMKL_AUTH] - see [TraktAuthRules]. It carries the refresh
     * token and expiry as well, because Trakt's access tokens expire.
     */
    const val KEY_TRAKT_AUTH = "trakt_auth"

    /**
     * The BingeCat session, on exactly the Trakt rule (see
     * [BingeCatAuthRules]): a profile that never connected publishes nothing,
     * only a deliberate disconnect publishes a tombstone, and the whole pair
     * travels because the refresh token can rotate.
     */
    const val KEY_BINGECAT_AUTH = "bingecat_auth"

    /**
     * The Kitsu session (spec 2026-11-13, tracker auth sessions
     * device-to-device), on the Trakt rule (see [KitsuAuthRules]): a profile
     * that never connected publishes nothing, only a deliberate disconnect
     * publishes a tombstone, and the whole pair travels because a refresh
     * rotates it. No Kitsu password is anywhere in it - that was used once and
     * dropped.
     */
    const val KEY_KITSU_AUTH = "kitsu_auth"

    /**
     * The MyAnimeList session, on the Kitsu rule (see [MalAuthRules]). The
     * in-flight PKCE values (`pending_verifier` / `pending_state`) are
     * per-device flow state and NEVER travel: the builder does not read them.
     */
    const val KEY_MAL_AUTH = "mal_auth"

    /**
     * The AniList session, on the Kitsu rule minus the refresh half (see
     * [AniListAuthRules]): AniList issues no refresh token, so the blob carries
     * the access token and its `exp`-derived deadline, and nothing else.
     */
    const val KEY_ANILIST_AUTH = "anilist_auth"

    /**
     * WHICH BingeCat profile this KBStream profile reads, beside the session
     * above (see [BingeCatProfileMapRules]).
     *
     * It was device-local on purpose until the session started travelling; a
     * second device then came up connected but serving nothing, because
     * `isReady()` needs a mapping and nothing said the profile step was still
     * owed (the 2026-11-05 "have to re-select the profile on the second device"
     * report). Per KBStream profile like every row here, which is what keeps the
     * kids profile's (empty) mapping its own: a row keyed by ACCOUNT instead
     * would hand Profile 1's BingeCat profile to the kids profile on every
     * device, and that invariant is out.
     */
    const val KEY_BINGECAT_PROFILE_MAP = "bingecat_profile_map"
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

    /**
     * The profile's OWN personal lists (create, rename, add, remove, delete).
     *
     * Its own row rather than a field of [KEY_LIBRARY]: the library blob is
     * whole-blob last-write-wins against a stamp every My List write also moves,
     * so a list made on one device was dropped by a sibling whose My List edit
     * was newer - and that sibling's next push republished its older copy over
     * the account's. A list is its own unit of edit, so the row carries the
     * lists and their removal record and merges per list (see
     * [com.kennyb1201.kbstream.data.sync.LocalListSyncRules]).
     */
    const val KEY_LIBRARY_LISTS = "library_lists"
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

    /**
     * The profile's hidden titles and their removal records (see
     * `HiddenTitles`). Its own key for the taste reason - it is a per-TITLE
     * merge, not a last-write-wins scalar blob - plus one thing taste has no
     * half for: `removed`, the unhides, without which a sibling's older hide
     * would put a title back on the device that just unhid it.
     */
    const val KEY_HIDDEN_TITLES = "hidden_titles"
    const val KEY_PROFILES = "profiles"

    fun buildAll(context: Context): List<Pair<String, JsonObject>> = listOf(
        KEY_DISPLAY_PREFS to buildDisplayPrefs(context),
        KEY_ADDONS to buildAddons(context),
        // Skipped by the push when this profile never pinned a list (see
        // hasLibraryHomeRailsToPublish): an untouched device must not publish
        // its empty row over the account's rails.
        KEY_LIBRARY_HOME_RAILS to
            com.kennyb1201.kbstream.data.library.LibraryHomeRails.buildBlob(context),
        // The bulk push skips this blob when it carries nothing (see
        // SupabaseSync.pushPrefsBlobs) — an empty Simkl blob published by a
        // device that never connected erases the account's session elsewhere.
        KEY_SIMKL_AUTH to buildSimklAuth(context),
        // The Trakt session, on exactly the Simkl rule: a profile that never
        // connected Trakt publishes nothing, and only a deliberate disconnect
        // publishes a tombstone (see [TraktAuthRules]).
        KEY_TRAKT_AUTH to buildTraktAuth(context),
        // And the BingeCat session, which used to be device-local by spec: the
        // same never-publish-a-blank rule applies (see [BingeCatAuthRules]).
        KEY_BINGECAT_AUTH to buildBingeCatAuth(context),
        // The three later trackers, each on the same rule and each skipped by
        // the push when this profile has nothing to say (see SupabaseSync's
        // guards and KitsuAuthRules / MalAuthRules / AniListAuthRules).
        KEY_KITSU_AUTH to buildKitsuAuth(context),
        KEY_MAL_AUTH to buildMalAuth(context),
        KEY_ANILIST_AUTH to buildAnilistAuth(context),
        // And the profile that session reads. Skipped by the push when this
        // profile never mapped anything and never deliberately unmapped (see
        // hasBingeCatProfileMapToPublish): an unmapped device publishing its
        // blank is exactly what would erase the account's mapping.
        KEY_BINGECAT_PROFILE_MAP to buildBingeCatProfileMap(context),
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
        // Skipped when this profile has neither a list nor a delete to publish
        // (see hasLibraryListsToPublish): an untouched device's empty row would
        // erase the account's lists for every sibling.
        KEY_LIBRARY_LISTS to buildLibraryLists(context),
        KEY_DISMISSALS to buildDismissals(context),
        // Skipped when this profile has no signals at all (see
        // SupabaseSync.pushPrefsBlobs): a device that never thumbed anything
        // must not publish an empty set over an account that has some.
        KEY_TASTE_SIGNALS to buildTasteSignals(context),
        // Skipped by the push unless this profile has hides OR removals (see
        // SupabaseSync.pushPrefsBlobs): a fresh device publishing its empty set
        // would erase the account's hidden titles on every sibling.
        KEY_HIDDEN_TITLES to buildHiddenTitles(context),
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

    /**
     * The profile's BingeCat session, as one blob.
     *
     * All of it travels - access token, refresh token, expiry, scope - because
     * a refresh can rotate the pair and an access token alone would be a
     * session that works until it expires and then strands the sibling.
     */
    fun buildBingeCatAuth(context: Context): JsonObject {
        val store = bingecatAuthStore(context)
        val session = store.read()
        // Tombstone only for a DELIBERATE disconnect: an empty store with no
        // marker is "this profile never connected BingeCat", which must not
        // erase a live session anywhere (see [BingeCatAuthRules]).
        val signedOut = session == null && store.isSignedOut()
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(BingeCatTokenStore.KEY_ACCESS_TOKEN, session?.accessToken.orEmpty())
            put(BingeCatTokenStore.KEY_REFRESH_TOKEN, session?.refreshToken.orEmpty())
            put(BingeCatTokenStore.KEY_EXPIRES_AT, session?.expiresAtEpochMillis ?: 0L)
            put(BingeCatTokenStore.KEY_SCOPE, session?.scope.orEmpty())
            put(BingeCatAuthRules.SIGNED_OUT_FIELD, signedOut)
        }
    }

    /**
     * True when this profile has something to say about the Library lists on
     * Home: it has edited the row at least once, including "removed them all".
     * A device that never pinned anything publishes nothing - see
     * [com.kennyb1201.kbstream.data.library.LibraryHomeRails.shouldPublish].
     */
    fun hasLibraryHomeRailsToPublish(context: Context): Boolean =
        com.kennyb1201.kbstream.data.library.LibraryHomeRails.shouldPublish(context)

    /**
     * True when this profile has something to say about its BingeCat session: a
     * token to share, or a deliberate disconnect to propagate. A profile that
     * never connected publishes nothing - see [BingeCatAuthRules].
     */
    fun hasBingeCatAuthToPublish(context: Context): Boolean {
        val store = bingecatAuthStore(context)
        return BingeCatAuthRules.shouldPublish(store.accessToken(), store.isSignedOut())
    }

    /**
     * True when this profile has something to say about its Kitsu session: a
     * token to share, or a deliberate disconnect to propagate. A profile that
     * never connected Kitsu publishes nothing - see [KitsuAuthRules].
     */
    fun hasKitsuAuthToPublish(context: Context): Boolean {
        val store = kitsuAuthStore(context)
        return KitsuAuthRules.shouldPublish(store.read()?.accessToken, store.signedOut())
    }

    /** The MAL session's half of the same gate - see [MalAuthRules]. */
    fun hasMalAuthToPublish(context: Context): Boolean {
        val store = malAuthStore(context)
        return MalAuthRules.shouldPublish(store.accessToken(), store.isSignedOut())
    }

    /** The AniList session's half of the same gate - see [AniListAuthRules]. */
    fun hasAnilistAuthToPublish(context: Context): Boolean {
        val store = anilistAuthStore(context)
        return AniListAuthRules.shouldPublish(store.read()?.accessToken, store.signedOut())
    }

    /**
     * The profile's Kitsu session, as one blob.
     *
     * Everything the session needs travels - access token, refresh token,
     * expiry, viewer id and name - because a refresh rotates the pair and an
     * access token alone would strand the sibling at its next expiry. The
     * password is not here and never was: the grant used it once, in memory.
     */
    fun buildKitsuAuth(context: Context): JsonObject {
        val store = kitsuAuthStore(context)
        val session = store.read()
        // Tombstone only for a DELIBERATE disconnect: an empty store with no
        // marker is "this profile never connected Kitsu", which must not erase
        // a live session anywhere (see [KitsuAuthRules]).
        val signedOut = session == null && store.signedOut()
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(KitsuTokenStore.KEY_ACCESS_TOKEN, session?.accessToken.orEmpty())
            put(KitsuTokenStore.KEY_REFRESH_TOKEN, session?.refreshToken.orEmpty())
            put(KitsuTokenStore.KEY_EXPIRES_AT, session?.expiresAtMs ?: 0L)
            put(KitsuTokenStore.KEY_VIEWER_ID, session?.viewerId.orEmpty())
            put(KitsuTokenStore.KEY_VIEWER_NAME, session?.viewerName.orEmpty())
            put(KitsuAuthRules.SIGNED_OUT_FIELD, signedOut)
        }
    }

    /**
     * The profile's MAL session, on exactly the Kitsu shape ([buildKitsuAuth]).
     *
     * `pending_verifier` and `pending_state` are deliberately absent: they are
     * this device's in-flight browser flow, not the session, and the store's own
     * `markSignedOut()` already removes them.
     */
    fun buildMalAuth(context: Context): JsonObject {
        val store = malAuthStore(context)
        val session = store.read()
        val signedOut = session == null && store.isSignedOut()
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(MalTokenStore.KEY_ACCESS_TOKEN, session?.accessToken.orEmpty())
            put(MalTokenStore.KEY_REFRESH_TOKEN, session?.refreshToken.orEmpty())
            put(MalTokenStore.KEY_EXPIRES_AT, session?.expiresAtMs ?: 0L)
            put(MalAuthRules.SIGNED_OUT_FIELD, signedOut)
        }
    }

    /**
     * The profile's AniList session, with NO refresh field because AniList
     * issues no refresh token (see [AniListAuthRules]).
     */
    fun buildAnilistAuth(context: Context): JsonObject {
        val store = anilistAuthStore(context)
        val session = store.read()
        val signedOut = session == null && store.signedOut()
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(AniListTokenStore.KEY_ACCESS_TOKEN, session?.accessToken.orEmpty())
            put(AniListTokenStore.KEY_EXPIRES_AT, session?.expiresAtMs ?: 0L)
            put(AniListTokenStore.KEY_VIEWER_ID, session?.viewerId ?: 0)
            put(AniListTokenStore.KEY_VIEWER_NAME, session?.viewerName.orEmpty())
            put(AniListAuthRules.SIGNED_OUT_FIELD, signedOut)
        }
    }

    /**
     * The profile's BingeCat mapping, as one blob: the id, its display-only name,
     * and the moment the mapping last CHANGED here.
     *
     * `updatedAt` is that change, not this build's clock (the collections bug: a
     * device that had merely opened the app re-stamped its older copy and
     * reverted a sibling's newer one). It is what decides a conflict against a
     * different local mapping - see [BingeCatProfileMapRules.shouldApply].
     * A profile that never mapped and never deliberately unmapped still builds a
     * row; the push gate is what keeps it out of the cloud.
     */
    fun buildBingeCatProfileMap(context: Context): JsonObject {
        val mapping = com.kennyb1201.kbstream.data.bingecat.BingeCatProfileMap(context)
        val mappedId = mapping.mappedProfileId()
        val sync = bingecatMapSyncPrefs(context)
        // Tombstone only for a DELIBERATE clear: an empty store with no marker is
        // "this profile never mapped", which must not erase a sibling's mapping.
        val cleared = BingeCatProfileMapRules.clearsMapping(
            mappedId,
            sync.getBoolean(BINGECAT_MAP_CLEARED, false)
        )
        return buildJsonObject {
            put(
                BingeCatProfileMapRules.UPDATED_AT_FIELD,
                sync.getLong(BINGECAT_MAP_WRITTEN_AT, 0L)
            )
            put(BingeCatProfileMapRules.PROFILE_ID_FIELD, mappedId ?: 0L)
            put(BingeCatProfileMapRules.PROFILE_NAME_FIELD, mapping.mappedProfileName().orEmpty())
            put(BingeCatProfileMapRules.CLEARED_FIELD, cleared)
        }
    }

    /**
     * True when this profile has something to say about the mapping: a profile it
     * reads, or a deliberate unmap to propagate. A profile that was never mapped
     * publishes nothing - see [BingeCatProfileMapRules].
     */
    fun hasBingeCatProfileMapToPublish(context: Context): Boolean {
        val mapping = com.kennyb1201.kbstream.data.bingecat.BingeCatProfileMap(context)
        val cleared = mapping.mappedProfileId() == null &&
            bingecatMapSyncPrefs(context).getBoolean(BINGECAT_MAP_CLEARED, false)
        return BingeCatProfileMapRules.shouldPublish(mapping.mappedProfileId(), cleared)
    }

    /**
     * Notes that this profile's BingeCat mapping just CHANGED on this device -
     * selected, cleared, or (with [cleared]) deliberately unmapped.
     *
     * Called by `BingeCatRepository` at the two places a mapping can change, so
     * the row it publishes carries the moment of the change and the marker that
     * makes a blank row a deliberate clear. Kept here rather than in
     * `BingeCatProfileMap` because the local store's name and keys are its own
     * contract and sync bookkeeping is not part of it.
     */
    fun recordBingeCatMappingChanged(context: Context?, cleared: Boolean) {
        val appContext = context?.applicationContext ?: return
        runCatching {
            bingecatMapSyncPrefs(appContext).edit()
                .putLong(BINGECAT_MAP_WRITTEN_AT, System.currentTimeMillis())
                .putBoolean(BINGECAT_MAP_CLEARED, cleared)
                .apply()
        }
    }

    fun buildBadgePack(context: Context): JsonObject {
        val prefs = scopedPrefs(context, StreamBadgeEngine.PREFS_NAME)
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(
                StreamBadgeEngine.KEY_PACK_URL,
                prefs.getString(StreamBadgeEngine.KEY_PACK_URL, null).orEmpty()
            )
            put(
                StreamBadgeEngine.KEY_PACK_JSON,
                prefs.getString(StreamBadgeEngine.KEY_PACK_JSON, null).orEmpty()
            )
            // The removal stamp, so a sibling can tell a deliberate removal from
            // a device that never configured a pack (see BadgePackSyncRules).
            put(
                StreamBadgeEngine.KEY_CLEARED_AT,
                prefs.getLong(StreamBadgeEngine.KEY_CLEARED_AT, 0L)
            )
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

        // The six TV-scoped keys are DEVICE-level and a phone never publishes
        // them (spec 2026-11-12) - so they are read from their own store, and
        // only on a television. On a phone the map is empty and the six are
        // simply absent from the blob, which is what makes its billboard
        // settings unable to travel.
        val deviceValues: Map<String, Any> =
            if (com.kennyb1201.kbstream.FormFactor.isTv) {
                TV_SCOPED_SYNC_KEYS.mapNotNull { key ->
                    com.kennyb1201.kbstream.data.settings.AppPreferences
                        .deviceScopedValue(context, key)
                        ?.let { value -> key to value }
                }.toMap()
            } else {
                emptyMap()
            }
        // A TV-scoped key is read from the DEVICE store even if this profile's
        // file still holds a pre-migration copy: the device value is the one
        // every reader uses.
        fun stored(key: String): Any? =
            if (key in TV_SCOPED_SYNC_KEYS) deviceValues[key] else all[key]

        val current =
            LinkedHashMap<String, String>()
        SYNCED_PREF_KEYS.forEach { key ->
            blobValue(key, stored(key))?.let { current[key] = it }
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
                val value = stored(key) ?: return@forEach
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
            // The personal lists are NOT here any more: they travel on their
            // own per-list row (see [KEY_LIBRARY_LISTS]), because a whole-blob
            // write gated on this stamp lost them whenever the other device had
            // also touched My List.
            put(
                LibraryTombstones.PAYLOAD_KEY,
                prefs.getString(LibraryTombstones.PREFS_KEY, null) ?: ""
            )
        }

    /**
     * The profile's personal lists and their removal record, as the store's own
     * encoded text.
     *
     * Both halves are carried because neither answers alone: the lists say what
     * this device HAS, and the record says what it deliberately removed - a list
     * that is merely absent is "never here", which must not delete a sibling's
     * copy (see [com.kennyb1201.kbstream.data.sync.LocalListSyncRules]).
     */
    fun buildLibraryLists(context: Context): JsonObject {
        val prefs = scopedPrefs(context, "kbstream_library")
        return buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put("lists", prefs.getString("personal_lists", null) ?: "")
            put("deleted", prefs.getString("personal_lists_deleted", null) ?: "")
        }
    }

    /**
     * True when this profile has a list or a delete to say: the gate the bulk
     * push uses so a device that never made a list cannot erase the account's.
     */
    fun hasLibraryListsToPublish(context: Context): Boolean =
        com.kennyb1201.kbstream.data.library.LocalLibraryStore.hasListsToPublish(context)

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

    /**
     * True when this profile has something to say about its hidden titles: a
     * hide, or a removal record from an unhide (see
     * [com.kennyb1201.kbstream.data.library.HiddenTitles.hasHiddenTitlesToPublish]).
     */
    fun hasHiddenTitlesToPublish(context: Context): Boolean =
        com.kennyb1201.kbstream.data.library.HiddenTitles.hasHiddenTitlesToPublish(context)

    /**
     * The profile's hidden titles, as the store's own encoded text (see
     * `HiddenTitles.encodedForSync`) plus its removal records - and the moment
     * this blob was built.
     *
     * `updatedAt` is the blob's age, NOT a conflict winner: the merge resolves
     * per title from each entry's or record's `at`, the way the taste blob's
     * `updatedAt` is not its winner either. Blank `entries` and blank `removed`
     * mean this profile has nothing hidden and has not unhidden anything, which
     * the push path skips entirely.
     */
    fun buildHiddenTitles(context: Context): JsonObject =
        buildJsonObject {
            put("updatedAt", System.currentTimeMillis())
            put(
                "entries",
                com.kennyb1201.kbstream.data.library.HiddenTitles
                    .encodedForSync(context)
                    .orEmpty()
            )
            put(
                "removed",
                com.kennyb1201.kbstream.data.library.HiddenTitles
                    .removedEncodedForSync(context)
                    .orEmpty()
            )
        }

    /**
     * The cloud pref keys this device can PROVE [profileId] holds local state
     * for - everything a signed-out delete can retire.
     *
     * Signed out there is no session to enumerate the account's rows with, so
     * the deleted profile's own stores are the only record of what was
     * published (the same reasoning as SupabaseSync's signed-out history/watched
     * staging). The probes below are the builders' own reads, addressed to
     * [profileId] instead of the active profile:
     *
     *  - the six sessions go through the same "has something to say" rules the
     *    push uses, so a profile that never connected a tracker retires nothing;
     *  - taste signals and hidden titles expose a profile-addressed read of their
     *    stored blob;
     *  - every other key is one prefs file per profile ([STORE_KEYS]), and a file
     *    with any content is the row's local copy.
     *
     * Deliberately NOT enumerated: the API-keys row. Its store is behind
     * [com.kennyb1201.kbstream.data.security.SecureTokenStore], which has no
     * profile-addressed accessor outside AppPreferences, and reading it as a
     * plain prefs file would silently prove nothing rather than the truth. An
     * auth key pasted for a deleted profile is therefore left to the signed-in
     * purge, which enumerates the cloud rows and needs no local probe.
     */
    fun localPrefKeysForProfile(context: Context, profileId: String): List<String> {
        if (profileId.isBlank()) return emptyList()
        val keys = mutableListOf<String>()

        val simkl = simklAuthPrefs(context, profileId)
        val simklToken = simkl.getString(SIMKL_ACCESS_TOKEN_KEY, null)
        val simklSignedOut = simklToken.isNullOrBlank() &&
            simkl.getBoolean(SimklAuthRules.SIGNED_OUT_FIELD, false)
        if (SimklAuthRules.shouldPublish(simklToken, simklSignedOut)) keys += KEY_SIMKL_AUTH

        val trakt = traktAuthStore(context, profileId)
        if (TraktAuthRules.shouldPublish(trakt.accessToken(), trakt.isSignedOut())) {
            keys += KEY_TRAKT_AUTH
        }

        val bingecat = bingecatAuthStore(context, profileId)
        val bingecatSession = bingecat.read()
        if (BingeCatAuthRules.shouldPublish(
                bingecatSession?.accessToken,
                bingecatSession == null && bingecat.isSignedOut()
            )
        ) {
            keys += KEY_BINGECAT_AUTH
        }

        // The three later trackers, behind the same "has something to say"
        // gates the push uses: a profile that never connected one retires
        // nothing. A key missing here would leave a deleted profile's session in
        // the cloud.
        val kitsu = kitsuAuthStore(context, profileId)
        if (KitsuAuthRules.shouldPublish(kitsu.read()?.accessToken, kitsu.signedOut())) {
            keys += KEY_KITSU_AUTH
        }

        val mal = malAuthStore(context, profileId)
        if (MalAuthRules.shouldPublish(mal.accessToken(), mal.isSignedOut())) {
            keys += KEY_MAL_AUTH
        }

        val anilist = anilistAuthStore(context, profileId)
        if (AniListAuthRules.shouldPublish(anilist.read()?.accessToken, anilist.signedOut())) {
            keys += KEY_ANILIST_AUTH
        }

        if (com.kennyb1201.kbstream.data.library.TasteSignals
                .encodedForSync(context, profileId) != null
        ) {
            keys += KEY_TASTE_SIGNALS
        }
        if (com.kennyb1201.kbstream.data.library.HiddenTitles
                .encodedForSync(context, profileId) != null
        ) {
            keys += KEY_HIDDEN_TITLES
        }

        STORE_KEYS.forEach { (key, stores) ->
            if (stores.any { store -> hasLocalPrefs(context, profileId, store) }) keys += key
        }
        return keys.distinct()
    }

    /** True when this profile's prefs file for [baseName] holds anything. */
    private fun hasLocalPrefs(context: Context, profileId: String, baseName: String): Boolean =
        context.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(profileId, baseName),
            Context.MODE_PRIVATE
        ).all.isNotEmpty()

    /**
     * One cloud pref key per set of prefs files it is built from.
     *
     * Read from the builders above, not guessed: each entry is the exact store
     * [scopedPrefs] hands its [buildAll] builder. Only the SIGNED-OUT delete purge
     * reads this (see [localPrefKeysForProfile]).
     */
    private val STORE_KEYS: List<Pair<String, List<String>>> = listOf(
        KEY_DISPLAY_PREFS to listOf(DISPLAY_PREFS_STORE),
        KEY_ADDONS to listOf("kbstream_addons"),
        KEY_LIBRARY_HOME_RAILS to
            listOf(com.kennyb1201.kbstream.data.library.LibraryHomeRails.SYNC_STORE),
        KEY_IPTV to listOf("iptv_prefs", "iptv_guide_preferences"),
        KEY_WATCHED_OVERRIDES to listOf(WatchedOverridesRules.STORE),
        KEY_HOME_ORDER to listOf("kbstream_kb_home_order"),
        KEY_BROWSE_SHORTCUTS to
            listOf(com.kennyb1201.kbstream.data.kb.BrowseHomeShortcuts.SYNC_STORE),
        KEY_CUSTOM_CATALOGS to
            listOf(com.kennyb1201.kbstream.data.catalogs.CustomCatalogStore.SYNC_STORE),
        KEY_COLLECTIONS to listOf("kbstream_kb_collections"),
        KEY_BADGE_PACK to listOf(StreamBadgeEngine.PREFS_NAME),
        KEY_LIBRARY to listOf("kbstream_library"),
        KEY_LIBRARY_LISTS to listOf("kbstream_library"),
        KEY_DISMISSALS to listOf("continue_watching_dismissals"),
        KEY_BINGECAT_PROFILE_MAP to listOf(BINGECAT_MAP_SYNC_STORE)
    )
}

/**
 * The payload that RETIRES one deleted profile's cloud pref row.
 *
 * m25's unfinished half: deleting a profile purged its history and watched rows
 * but left every `p:<profileId>:*` row in `sync_prefs` behind - including the
 * live Simkl, Trakt, BingeCat, Kitsu, MAL and AniList sessions, which are the
 * credentials the finding names. A delete is a newer WRITE here, like every
 * other delete in this file
 * (see [HistoryTombstoneRules]): the row is replaced, never cloud-deleted, so a
 * sibling that still holds its copy pulls the replacement and drops it too.
 *
 * Two shapes, both the tree's own:
 *
 *  - the six auth rows take their builder's deliberate-disconnect blob (blank
 *    tokens plus the signed-out marker), so [PrefsPayloadApplier]'s existing
 *    handlers clear the tokens on every device rather than storing a blank
 *    session that looks connected;
 *  - every other row takes [RETIRED_FIELD], which means "this profile is gone".
 *    Its local copy IS the deleted profile's stores, and those are wiped by the
 *    profiles blob's delete path (see
 *    [com.kennyb1201.kbstream.data.sync.ProfileManager.applyProfilesPayload]), so
 *    the marker only has to stop the row coming back.
 *
 * The account-wide profiles blob never gets one: it is not profile-scoped, and
 * removing the deleted id from it is the delete flow's own job.
 */
internal object PrefsRowTombstones {

    /** Top-level marker on a retired row: "do not read this as a value". */
    const val RETIRED_FIELD = "retired"

    fun isRetired(payload: JsonObject): Boolean =
        (payload[RETIRED_FIELD] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true

    /** What replaces [prefKeyName]'s row for a deleted profile, stamped [now]. */
    fun payloadFor(prefKeyName: String, now: Long): JsonObject = when (prefKeyName) {
        PrefsPayloadBuilder.KEY_SIMKL_AUTH -> buildJsonObject {
            put("updatedAt", now)
            put(SIMKL_ACCESS_TOKEN_KEY, "")
            put(SimklAuthRules.SIGNED_OUT_FIELD, true)
        }
        PrefsPayloadBuilder.KEY_TRAKT_AUTH -> buildJsonObject {
            put("updatedAt", now)
            put(TraktTokenStore.KEY_ACCESS_TOKEN, "")
            put(TraktTokenStore.KEY_REFRESH_TOKEN, "")
            put(TraktTokenStore.KEY_EXPIRES_AT, 0L)
            put(TraktTokenStore.KEY_ACCOUNT_SLUG, "")
            put(TraktAuthRules.SIGNED_OUT_FIELD, true)
        }
        PrefsPayloadBuilder.KEY_BINGECAT_AUTH -> buildJsonObject {
            put("updatedAt", now)
            put(BingeCatTokenStore.KEY_ACCESS_TOKEN, "")
            put(BingeCatTokenStore.KEY_REFRESH_TOKEN, "")
            put(BingeCatTokenStore.KEY_EXPIRES_AT, 0L)
            put(BingeCatTokenStore.KEY_SCOPE, "")
            put(BingeCatAuthRules.SIGNED_OUT_FIELD, true)
        }
        PrefsPayloadBuilder.KEY_KITSU_AUTH -> buildJsonObject {
            put("updatedAt", now)
            put(KitsuTokenStore.KEY_ACCESS_TOKEN, "")
            put(KitsuTokenStore.KEY_REFRESH_TOKEN, "")
            put(KitsuTokenStore.KEY_EXPIRES_AT, 0L)
            put(KitsuTokenStore.KEY_VIEWER_ID, "")
            put(KitsuTokenStore.KEY_VIEWER_NAME, "")
            put(KitsuAuthRules.SIGNED_OUT_FIELD, true)
        }
        PrefsPayloadBuilder.KEY_MAL_AUTH -> buildJsonObject {
            put("updatedAt", now)
            put(MalTokenStore.KEY_ACCESS_TOKEN, "")
            put(MalTokenStore.KEY_REFRESH_TOKEN, "")
            put(MalTokenStore.KEY_EXPIRES_AT, 0L)
            put(MalAuthRules.SIGNED_OUT_FIELD, true)
        }
        PrefsPayloadBuilder.KEY_ANILIST_AUTH -> buildJsonObject {
            put("updatedAt", now)
            put(AniListTokenStore.KEY_ACCESS_TOKEN, "")
            put(AniListTokenStore.KEY_EXPIRES_AT, 0L)
            put(AniListTokenStore.KEY_VIEWER_ID, 0)
            put(AniListTokenStore.KEY_VIEWER_NAME, "")
            put(AniListAuthRules.SIGNED_OUT_FIELD, true)
        }
        else -> buildJsonObject {
            put("updatedAt", now)
            put(RETIRED_FIELD, true)
        }
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

        // A retired row is a deleted profile's tombstone, not a value (see
        // [PrefsRowTombstones]): applying it as data would write blanks into
        // stores that are already being wiped with the profile. The six auth
        // keys are the exception and they are NOT retired - they carry their
        // builders' disconnect blobs, which the handlers below already clear
        // with, so a sibling's tokens go with them. (Six, not three, since
        // Kitsu, MAL and AniList were enrolled - the reason is unchanged.)
        if (PrefsRowTombstones.isRetired(payload)) return

        when (prefKey) {
            PrefsPayloadBuilder.KEY_DISPLAY_PREFS ->
                applyDisplayPrefs(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_LIBRARY_HOME_RAILS ->
                applyLibraryHomeRails(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_ADDONS -> applyAddons(context, payload, profileId)
            PrefsPayloadBuilder.KEY_SIMKL_AUTH ->
                applySimklAuth(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_TRAKT_AUTH ->
                applyTraktAuth(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_BINGECAT_AUTH ->
                applyBingeCatAuth(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_KITSU_AUTH ->
                applyKitsuAuth(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_MAL_AUTH ->
                applyMalAuth(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_ANILIST_AUTH ->
                applyAnilistAuth(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_BINGECAT_PROFILE_MAP ->
                applyBingeCatProfileMap(context, payload, profileId, live)
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
            PrefsPayloadBuilder.KEY_LIBRARY_LISTS ->
                applyLibraryLists(context, payload, profileId, live)
            PrefsPayloadBuilder.KEY_DISMISSALS -> applyDismissals(context, payload, profileId)
            PrefsPayloadBuilder.KEY_TASTE_SIGNALS ->
                applyTasteSignals(context, payload, profileId)
            PrefsPayloadBuilder.KEY_HIDDEN_TITLES ->
                applyHiddenTitles(context, payload, profileId)
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

    /**
     * Pinned Library lists, behind the same last-edit guard the home order and
     * the browse chips use - an older sibling copy must not revert a pin the
     * viewer just made here - and with the adoption MERGED when the payload
     * carries no stamp (see [unionLibraryHomeRailPins]).
     *
     * The guard still decides whether the remote is adopted at all; only what is
     * WRITTEN changed. A stamped remote replaces the pins (its omission is a
     * deliberate unpin, the only signal one leaves); an unstamped one proves
     * nothing about deletions, so its pins are unioned onto the local set rather
     * than replacing it. That is what stops a stale copy taking the Trakt and
     * MDBList pins off both devices (Kenny, 2026-11-09).
     *
     * An unreadable (or absent) rails array is "nothing to apply", never "clear
     * them": the applier cannot tell an older payload's missing field from an
     * intentional empty row, and the empty row has its own valid shape
     * (`"rails": []`).
     */
    private fun applyLibraryHomeRails(
        context: Context,
        payload: JsonObject,
        profileId: String?,
        live: Boolean
    ) {
        val rails = com.kennyb1201.kbstream.data.library.LibraryHomeRails
            .parseBlob(payload) ?: return
        val prefs = profilePrefs(
            context,
            com.kennyb1201.kbstream.data.library.LibraryHomeRails.SYNC_STORE,
            profileId
        )
        val remoteUpdated = payloadUpdatedAt(payload)
        val localSyncedAt = prefs.getLong(
            com.kennyb1201.kbstream.data.library.LibraryHomeRails.SYNCED_AT_KEY,
            0L
        )
        if (!HomeListBlobRules.shouldApply(remoteUpdated, localSyncedAt)) return
        // An UNSTAMPED payload proves nothing about deletions: it comes from a
        // build that predates the stamp, which is also a build that may simply
        // not know a pin. So it is adopted by UNION - a remote pin replaces the
        // local one with the same key (its title may be newer), a local pin the
        // remote omits survives, a pin only the remote has arrives. A payload
        // with a real stamp is the sibling's state AFTER the local pins, so it
        // is adopted whole: its omissions include a deliberate unpin, which
        // leaves no other trace (tombstones record removed titles, not pin
        // events).
        val merged = if (remoteUpdated == null) {
            val localPins = com.kennyb1201.kbstream.data.library.LibraryHomeRails
                .storedPins(
                    prefs.getString(
                        com.kennyb1201.kbstream.data.library.LibraryHomeRails
                            .SYNC_BLOB_KEY,
                        null
                    )
                ).orEmpty()
            com.kennyb1201.kbstream.data.library.unionLibraryHomeRailPins(
                localPins,
                rails
            )
        } else {
            rails
        }
        // A pin whose local list this device already deleted is not adopted: the
        // sibling may not have seen the tombstone yet, and taking its copy would
        // put a rail back for a list that does not exist here.
        val adopted = com.kennyb1201.kbstream.data.library.withoutDeletedLocalPins(
            merged,
            com.kennyb1201.kbstream.data.library.LocalLibraryStore
                .deletedListIds(context, profileId)
        )
        // Never `now`, for the same reason as the home order: an adoption is not
        // an edit this device made. The stamp only moves forward, so a re-pull of
        // the same remote re-merges idempotently (`shouldApply` adopts on `>`=
        // and the applier rejects only a strictly older remote) instead of
        // stalling, and a merged copy never claims to be newer than the sibling's
        // write it came from.
        val stamp = maxOf(localSyncedAt, remoteUpdated ?: localSyncedAt)
        prefs.edit()
            .putString(
                com.kennyb1201.kbstream.data.library.LibraryHomeRails.SYNC_BLOB_KEY,
                com.kennyb1201.kbstream.data.library.LibraryHomeRails.encode(adopted)
            )
            .putLong(
                com.kennyb1201.kbstream.data.library.LibraryHomeRails.SYNCED_AT_KEY,
                stamp
            )
            .apply()
        // Home reads the pins while it composes, so a rail adopted from the
        // account appears without waiting for some other state change.
        if (live) _revision.value += 1
    }

    /**
     * The pack half of the account, including its REMOVAL.
     *
     * A removal is adopted on the same rule as the pins and the home order -
     * newer than this device's own last pack state (see [BadgePackSyncRules]) -
     * and then the open Settings field is told, so it cannot go on showing the
     * URL of a pack that is gone.
     */
    private fun applyBadgePack(context: Context, payload: JsonObject, profileId: String?) {
        fun str(key: String): String =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        fun stamp(key: String): Long =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toLongOrNull() ?: 0L

        val prefs = profilePrefs(context, StreamBadgeEngine.PREFS_NAME, profileId)
        val localStamp = prefs.getLong(StreamBadgeEngine.KEY_SYNCED_AT, 0L)
        val clearedAt = stamp(StreamBadgeEngine.KEY_CLEARED_AT)

        when (
            BadgePackSyncRules.decide(
                remoteClearedAt = clearedAt,
                remotePackJson = str(StreamBadgeEngine.KEY_PACK_JSON),
                localPackJson = prefs.getString(StreamBadgeEngine.KEY_PACK_JSON, null)
                    .orEmpty(),
                localStamp = localStamp
            )
        ) {
            BadgePackSyncRules.Decision.Ignore -> Unit

            BadgePackSyncRules.Decision.AdoptRemoval -> {
                prefs.edit()
                    .remove(StreamBadgeEngine.KEY_PACK_URL)
                    .remove(StreamBadgeEngine.KEY_PACK_JSON)
                    // The sibling's stamp, not `now`: this device did not make
                    // the removal, and claiming it did would let it beat a newer
                    // pack on the third device.
                    .putLong(StreamBadgeEngine.KEY_CLEARED_AT, clearedAt)
                    .putLong(StreamBadgeEngine.KEY_SYNCED_AT, clearedAt)
                    .apply()
                StreamBadgeEngine.notifyPackChanged()
            }

            BadgePackSyncRules.Decision.AdoptPack -> {
                prefs.edit()
                    .putString(StreamBadgeEngine.KEY_PACK_URL, str(StreamBadgeEngine.KEY_PACK_URL))
                    .putString(
                        StreamBadgeEngine.KEY_PACK_JSON,
                        str(StreamBadgeEngine.KEY_PACK_JSON)
                    )
                    // A pack exists again here too, so the local removal record
                    // is spent. The stamp only moves forward.
                    .putLong(StreamBadgeEngine.KEY_CLEARED_AT, 0L)
                    .putLong(
                        StreamBadgeEngine.KEY_SYNCED_AT,
                        maxOf(localStamp, stamp("updatedAt"))
                    )
                    .apply()
                StreamBadgeEngine.notifyPackChanged()
            }
        }
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
            // The personal lists used to be adopted here too, whole-blob. They
            // have their own per-list row now ([KEY_LIBRARY_LISTS]) - leaving
            // the field here would let an older sibling's push replace the
            // merged lists with its copy.
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
     * The pulled personal lists: merged per list, never adopted whole.
     *
     * The store does the merge (it owns the encoding), and this is where the
     * screen is told: a list that arrived from the account repaints the Library
     * rail and Home's pinned rails through the shared revision, because both
     * read the store while they compose.
     */
    private fun applyLibraryLists(
        context: Context,
        payload: JsonObject,
        profileId: String?,
        live: Boolean
    ) {
        fun text(key: String): String? =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content
                ?.takeIf { it.isNotBlank() }
        val listsRaw = text("lists")
        val deletedRaw = text("deleted")
        val changed = runCatching {
            com.kennyb1201.kbstream.data.library.LocalLibraryStore.mergePulledLists(
                context = context,
                profileId = profileId,
                incomingListsRaw = listsRaw,
                incomingDeletedRaw = deletedRaw
            )
        }.onFailure {
            android.util.Log.w(TAG, "personal lists merge failed: ${it.message}")
        }.getOrDefault(false)
        // A list deleted on the other device takes its Home pin with it, even
        // though the sibling's pin row still names it (see
        // withoutDeletedLocalPins): the tombstone is the newer statement, and a
        // rail pointing at a list that no longer exists is a heading that
        // cannot fill.
        val pinsChanged = runCatching {
            com.kennyb1201.kbstream.data.library.LibraryHomeRails.dropLocalPinsAdopted(
                context = context,
                profileId = profileId,
                deletedListIds = com.kennyb1201.kbstream.data.library.LocalLibraryStore
                    .deletedListIdsFromRaw(deletedRaw)
            )
        }.onFailure {
            android.util.Log.w(TAG, "personal list pin drop failed: ${it.message}")
        }.getOrDefault(false)
        if ((changed || pinsChanged) && live) _revision.value += 1
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

    /**
     * Hidden titles apply: a per-title merge, never a replace (see
     * `HiddenTitles.mergeFromSync`), on the taste-signals rule.
     *
     * Both halves blank is "this account has nothing", never "clear yours" -
     * the same rule the taste blob follows and the reason the push side skips a
     * profile with nothing to say. `updatedAt` is deliberately not consulted:
     * precedence is each row's own `at`, so there is no blob stamp to race.
     */
    private fun applyHiddenTitles(context: Context, payload: JsonObject, profileId: String?) {
        fun str(key: String): String =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val entries = str("entries")
        val removed = str("removed")
        if (entries.isBlank() && removed.isBlank()) return
        val changed = com.kennyb1201.kbstream.data.library.HiddenTitles
            .mergeFromSync(context, entries, removed, profileId)
        if (changed > 0) {
            android.util.Log.i(TAG, "hidden titles: merged $changed row(s) from the account")
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
        if (remoteUpdated != null && remoteUpdated < prefs.getLong("home_order_synced_at", 0L)) {
            // The ORDER belongs to the newer copy and is dropped whole - but a
            // rail's NAME is one label on one row, and losing it because the
            // order lost a race is the "I renamed it and the other device never
            // saw the name" report (see KBHomeOrderPrefs.mergeRenames). Only for
            // the active profile: that helper writes the store the ACTIVE profile
            // resolves to, and a sibling profile's row is applied when it is next
            // switched to.
            if (profileId == ProfileStorage.activeProfileId(context)) {
                com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                    .mergeRenames(context, blob)
            }
            return
        }

        // ADOPT, then MERGE - not replace. The decision to adopt is the guard
        // above (an older stamped copy was rejected whole, a blank blob never
        // gets here); what the adoption WRITES is the remote's arrangement with
        // every rail only this device knows kept where it sat. A pulled blob
        // that never knew a rail cannot delete its placement, which is the
        // arranged BingeCat rail that moved to the end of Home (see
        // mergeHomeOrderAdopted).
        val local = prefs.getString("home_order_json", null).orEmpty()
        val remoteOrder = com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
            .parseBlob(blob) ?: return
        val localOrder = com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
            .parseBlob(local)
        val merged = if (localOrder == null) {
            // Nothing readable here (first sign-in, or a blob this build cannot
            // parse): the remote IS the arrangement, as it always was.
            remoteOrder
        } else {
            com.kennyb1201.kbstream.data.kb.mergeHomeOrderAdopted(localOrder, remoteOrder)
        }
        val encoded = com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.encodeBlob(merged)
        val localSyncedAt = prefs.getLong("home_order_synced_at", 0L)
        // Never `now`: a merge is not an edit this device made. Publishing a
        // fresh stamp here would let the adoption win a race it never entered
        // and republish the merged copy over a sibling's real edit - the
        // `remapAddonKeys` lesson. Equal stamps still converge: shouldApply
        // adopts on `>=`, so the same remote re-merges idempotently.
        val stamp = maxOf(localSyncedAt, remoteUpdated ?: localSyncedAt)
        if (encoded == local && stamp == localSyncedAt) return

        prefs.edit()
            .putString("home_order_json", encoded)
            .putLong("home_order_synced_at", stamp)
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

            // The six TV-scoped keys (spec 2026-11-12) are adopted differently
            // from every other key, for two reasons that cannot be folded into
            // the profile write below: they belong to the DEVICE, not to the
            // profile this blob is for, and a PHONE never adopts them at all. A
            // phone must come out of a TV's sync with its own layout and
            // billboard exactly as they were, so the gate is here - on the
            // receiving device, since the payload carries no device class.
            if (key in PrefsPayloadBuilder.TV_SCOPED_SYNC_KEYS) {
                if (!com.kennyb1201.kbstream.FormFactor.isTv) return@forEach
                val adopted: Any? =
                    when {
                        primitive.isString -> content
                        content == "true" || content == "false" -> content == "true"
                        else -> content.toLongOrNull()
                    }
                if (adopted != null) {
                    com.kennyb1201.kbstream.data.settings.AppPreferences
                        .adoptDeviceScopedValue(context, key, adopted)
                    applied[key] = content
                    appliedAt[key] = editsAt
                    snapshot[key] = content
                }
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
     * One pulled BingeCat session.
     *
     * Three shapes, the same three Trakt has: a tombstoned blank signs this
     * device out, a live session is adopted when it may replace the local one
     * ([BingeCatAuthRules.shouldAdopt]), and an unchanged session only retires
     * a stale marker. What runs after an adoption is smaller than Trakt's
     * reset, because BingeCat keeps no derived watch state of its own: only the
     * repository's cached snapshot and the mapping - and the mapping's own row
     * carries it now, so a session adopted here leaves this profile's choice of
     * BingeCat profile exactly as it was (see [BingeCatProfileMapRules]).
     */
    private fun applyBingeCatAuth(
        context: Context,
        payload: JsonObject,
        profileId: String?,
        live: Boolean
    ) {
        fun text(key: String): String =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

        val access = text(BingeCatTokenStore.KEY_ACCESS_TOKEN)
        val refresh = text(BingeCatTokenStore.KEY_REFRESH_TOKEN)
        val expiresAt = text(BingeCatTokenStore.KEY_EXPIRES_AT).toLongOrNull() ?: 0L
        val scope = text(BingeCatTokenStore.KEY_SCOPE)
        val signedOut = text(BingeCatAuthRules.SIGNED_OUT_FIELD) == "true"

        val store = bingecatAuthStore(context, profileId)
        val local = store.read()

        if (access.isBlank()) {
            if (!BingeCatAuthRules.clearsSession(access, signedOut)) return
            // Nothing to sign out of: a profile that never connected must not
            // record a tombstone it would then republish forever.
            if (local == null) return
            runCatching { store.markSignedOut() }
            if (live) resetBingeCatStateAfterSessionChange(context)
            return
        }

        val adopt = BingeCatAuthRules.shouldAdopt(
            incomingAccess = access,
            incomingExpiresAt = expiresAt,
            localAccess = local?.accessToken,
            localExpiresAt = local?.expiresAtEpochMillis ?: 0L,
            nowMs = System.currentTimeMillis()
        )
        if (!adopt) return

        // A rotated pair and a stale echo can share an access token, so which
        // refresh token to keep is a decision, not a coalesce: see
        // [BingeCatAuthRules.adoptedRefreshToken] for why taking the stale one
        // would strand this device with `no-token` forever.
        val nextRefresh = BingeCatAuthRules.adoptedRefreshToken(
            incomingAccess = access,
            incomingRefresh = refresh,
            incomingExpiresAt = expiresAt,
            localAccess = local?.accessToken,
            localRefresh = local?.refreshToken,
            localExpiresAt = local?.expiresAtEpochMillis ?: 0L
        )
        val nextScope = scope.takeIf { it.isNotBlank() } ?: local?.scope
        val unchanged = local?.accessToken == access &&
            local.refreshToken == nextRefresh &&
            local.expiresAtEpochMillis == expiresAt &&
            local.scope == nextScope
        if (unchanged) {
            // Same session: just retire a lingering disconnect marker so it
            // cannot be republished over the live token on the next push.
            store.clearSignedOutMarker()
            return
        }

        runCatching {
            store.save(
                BingeCatToken(
                    accessToken = access,
                    refreshToken = nextRefresh,
                    expiresAtEpochMillis = expiresAt,
                    scope = nextScope
                )
            )
        }
        if (live) resetBingeCatStateAfterSessionChange(context)
    }

    /**
     * One pulled Kitsu session (spec 2026-11-13, F2).
     *
     * Three shapes, the same three Trakt has: a tombstoned blank signs this
     * profile out, a live session is adopted when it may replace the local one
     * ([KitsuAuthRules.shouldAdopt]), and an unchanged session only retires a
     * stale marker. The full pair is written because a refresh on the other
     * device rotated it; adopting the access token alone would strand this
     * device at its next expiry.
     */
    private fun applyKitsuAuth(
        context: Context,
        payload: JsonObject,
        profileId: String?,
        live: Boolean
    ) {
        fun text(key: String): String =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

        val access = text(KitsuTokenStore.KEY_ACCESS_TOKEN)
        val refresh = text(KitsuTokenStore.KEY_REFRESH_TOKEN)
        val expiresAt = text(KitsuTokenStore.KEY_EXPIRES_AT).toLongOrNull() ?: 0L
        val viewerId = text(KitsuTokenStore.KEY_VIEWER_ID).takeIf { it.isNotBlank() }
        val viewerName = text(KitsuTokenStore.KEY_VIEWER_NAME).takeIf { it.isNotBlank() }
        val signedOut = text(KitsuAuthRules.SIGNED_OUT_FIELD) == "true"

        val store = kitsuAuthStore(context, profileId)
        val local = store.read()

        if (access.isBlank()) {
            if (!KitsuAuthRules.clearsSession(access, signedOut)) return
            // Nothing to sign out of: a profile that never connected must not
            // record a tombstone it would then republish forever.
            if (local == null && !store.signedOut()) return
            store.markSignedOut()
            AuthSyncTrace.record(PrefsPayloadBuilder.KEY_KITSU_AUTH, profileId, AuthSyncTrace.TOMBSTONED)
            if (live) resetKitsuStateAfterSessionChange()
            return
        }

        val adopt = KitsuAuthRules.shouldAdopt(
            incomingAccess = access,
            incomingExpiresAt = expiresAt,
            incomingAccountId = viewerId,
            localAccess = local?.accessToken,
            localExpiresAt = local?.expiresAtMs ?: 0L,
            localAccountId = local?.viewerId,
            nowMs = System.currentTimeMillis()
        )
        if (!adopt) {
            AuthSyncTrace.record(
                PrefsPayloadBuilder.KEY_KITSU_AUTH,
                profileId,
                AuthSyncTrace.REFUSED_STALE
            )
            return
        }

        val nextRefresh = KitsuAuthRules.adoptedRefreshToken(
            incomingAccess = access,
            incomingRefresh = refresh,
            incomingExpiresAt = expiresAt,
            localAccess = local?.accessToken,
            localRefresh = local?.refreshToken,
            localExpiresAt = local?.expiresAtMs ?: 0L
        )
        val nextViewerId = viewerId ?: local?.viewerId
        val nextViewerName = viewerName ?: local?.viewerName
        val unchanged = local?.accessToken == access &&
            local.refreshToken == nextRefresh &&
            local.expiresAtMs == expiresAt &&
            local.viewerId == nextViewerId &&
            local.viewerName == nextViewerName
        if (unchanged) {
            // Same session: just retire a lingering disconnect marker so it
            // cannot be republished over the live token on the next push.
            store.clearSignedOutMarker()
            return
        }

        store.save(
            KitsuToken(
                accessToken = access,
                refreshToken = nextRefresh,
                expiresAtMs = expiresAt,
                viewerId = nextViewerId,
                viewerName = nextViewerName
            )
        )
        AuthSyncTrace.record(PrefsPayloadBuilder.KEY_KITSU_AUTH, profileId, AuthSyncTrace.ADOPTED)
        if (live) resetKitsuStateAfterSessionChange()
    }

    /**
     * One pulled MAL session, on the Kitsu shape ([applyKitsuAuth]).
     *
     * No reset hook follows an adoption, and the absence is the answer rather
     * than an omission: MAL's write path addresses `my_list_status` by TITLE, so
     * it keeps no `animeId -> entry id` map that could belong to the previous
     * account (unlike the Kitsu and AniList libraries). Nothing derived is
     * cached for a session change to invalidate - state `none`.
     */
    private fun applyMalAuth(
        context: Context,
        payload: JsonObject,
        profileId: String?,
        live: Boolean
    ) {
        fun text(key: String): String =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

        val access = text(MalTokenStore.KEY_ACCESS_TOKEN)
        val refresh = text(MalTokenStore.KEY_REFRESH_TOKEN)
        val expiresAt = text(MalTokenStore.KEY_EXPIRES_AT).toLongOrNull() ?: 0L
        val signedOut = text(MalAuthRules.SIGNED_OUT_FIELD) == "true"

        val store = malAuthStore(context, profileId)
        val local = store.read()

        if (access.isBlank()) {
            if (!MalAuthRules.clearsSession(access, signedOut)) return
            if (local == null && !store.isSignedOut()) return
            store.markSignedOut()
            AuthSyncTrace.record(PrefsPayloadBuilder.KEY_MAL_AUTH, profileId, AuthSyncTrace.TOMBSTONED)
            return
        }

        val adopt = MalAuthRules.shouldAdopt(
            incomingAccess = access,
            incomingExpiresAt = expiresAt,
            localAccess = local?.accessToken,
            localExpiresAt = local?.expiresAtMs ?: 0L,
            nowMs = System.currentTimeMillis()
        )
        if (!adopt) {
            AuthSyncTrace.record(
                PrefsPayloadBuilder.KEY_MAL_AUTH,
                profileId,
                AuthSyncTrace.REFUSED_STALE
            )
            return
        }

        val nextRefresh = MalAuthRules.adoptedRefreshToken(
            incomingAccess = access,
            incomingRefresh = refresh,
            incomingExpiresAt = expiresAt,
            localAccess = local?.accessToken,
            localRefresh = local?.refreshToken,
            localExpiresAt = local?.expiresAtMs ?: 0L
        )
        val unchanged = local?.accessToken == access &&
            local.refreshToken == nextRefresh &&
            local.expiresAtMs == expiresAt
        if (unchanged) {
            store.clearSignedOutMarker()
            return
        }

        // A different session on this profile may be a different MAL account, so
        // the cached display name must not answer for it: the Settings row's
        // "connected as" is re-read from /users/@me under the adopted token.
        if (local?.accessToken != access) store.clearViewerName()

        store.save(
            MalToken(
                accessToken = access,
                refreshToken = nextRefresh,
                expiresAtMs = expiresAt
            )
        )
        AuthSyncTrace.record(PrefsPayloadBuilder.KEY_MAL_AUTH, profileId, AuthSyncTrace.ADOPTED)
    }

    /**
     * One pulled AniList session, on the Kitsu shape (see [applyKitsuAuth]).
     *
     * There is no refresh half to preserve: AniList issues no refresh token, so
     * the blob is the token, its deadline and the identity. An expired incoming
     * token with nothing local is refused before it lands (see
     * [AniListAuthRules.shouldAdopt]) - a session that reports "connected" while
     * every call fails is worse than no session.
     */
    private fun applyAnilistAuth(
        context: Context,
        payload: JsonObject,
        profileId: String?,
        live: Boolean
    ) {
        fun text(key: String): String =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

        val access = text(AniListTokenStore.KEY_ACCESS_TOKEN)
        val expiresAt = text(AniListTokenStore.KEY_EXPIRES_AT).toLongOrNull() ?: 0L
        val viewerId = text(AniListTokenStore.KEY_VIEWER_ID).toIntOrNull()?.takeIf { it > 0 }
        val viewerName = text(AniListTokenStore.KEY_VIEWER_NAME).takeIf { it.isNotBlank() }
        val signedOut = text(AniListAuthRules.SIGNED_OUT_FIELD) == "true"

        val store = anilistAuthStore(context, profileId)
        val local = store.read()

        if (access.isBlank()) {
            if (!AniListAuthRules.clearsSession(access, signedOut)) return
            if (local == null && !store.signedOut()) return
            store.markSignedOut()
            AuthSyncTrace.record(
                PrefsPayloadBuilder.KEY_ANILIST_AUTH,
                profileId,
                AuthSyncTrace.TOMBSTONED
            )
            if (live) resetAniListStateAfterSessionChange()
            return
        }

        val adopt = AniListAuthRules.shouldAdopt(
            incomingAccess = access,
            incomingExpiresAt = expiresAt,
            incomingAccountId = viewerId?.toString(),
            localAccess = local?.accessToken,
            localExpiresAt = local?.expiresAtMs ?: 0L,
            localAccountId = local?.viewerId?.toString(),
            nowMs = System.currentTimeMillis()
        )
        if (!adopt) {
            AuthSyncTrace.record(
                PrefsPayloadBuilder.KEY_ANILIST_AUTH,
                profileId,
                AuthSyncTrace.REFUSED_STALE
            )
            return
        }

        val nextViewerId = viewerId ?: local?.viewerId
        val nextViewerName = viewerName ?: local?.viewerName
        val unchanged = local?.accessToken == access &&
            local.expiresAtMs == expiresAt &&
            local.viewerId == nextViewerId &&
            local.viewerName == nextViewerName
        if (unchanged) {
            store.clearSignedOutMarker()
            return
        }

        store.save(
            AniListToken(
                accessToken = access,
                expiresAtMs = expiresAt,
                viewerId = nextViewerId,
                viewerName = nextViewerName
            )
        )
        AuthSyncTrace.record(
            PrefsPayloadBuilder.KEY_ANILIST_AUTH,
            profileId,
            AuthSyncTrace.ADOPTED
        )
        if (live) resetAniListStateAfterSessionChange()
    }

    /**
     * One pulled BingeCat mapping: which BingeCat profile this KBStream profile
     * reads, from the account (see [BingeCatProfileMapRules]).
     *
     * Three shapes, like the session's: a tombstoned blank unmaps this device, a
     * live mapping is adopted when it may replace the local one, and an identical
     * mapping is a no-op. The local store stays the store of record on the device
     * - this writes the same id and name `BingeCatProfileMap.set` would - and the
     * stamp it records is the pulled one, so the adopted choice is never
     * re-published as newer than it is.
     *
     * We never publish a pulled id here: applying is a receive, and a device that
     * merely received must not answer.
     */
    private fun applyBingeCatProfileMap(
        context: Context,
        payload: JsonObject,
        profileId: String?,
        live: Boolean
    ) {
        fun text(key: String): String =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

        val incomingId = text(BingeCatProfileMapRules.PROFILE_ID_FIELD)
            .toLongOrNull()
            ?.takeIf { it > 0L }
        val incomingName = text(BingeCatProfileMapRules.PROFILE_NAME_FIELD)
        val cleared = text(BingeCatProfileMapRules.CLEARED_FIELD) == "true"
        val incomingUpdatedAt = payloadUpdatedAt(payload) ?: 0L

        // The mapping's own store for THIS row's profile (a realtime row can name
        // a sibling), addressed with the same name and keys the local class uses.
        val mapPrefs = profilePrefs(
            context,
            com.kennyb1201.kbstream.data.bingecat.BingeCatProfileMap.PREFS_NAME,
            profileId
        )
        val localId = mapPrefs
            .getLong(com.kennyb1201.kbstream.data.bingecat.BingeCatProfileMap.KEY_PROFILE_ID, 0L)
            .takeIf { it > 0L }
        val syncPrefs = bingecatMapSyncPrefs(context, profileId)
        val localWrittenAt = syncPrefs.getLong(BINGECAT_MAP_WRITTEN_AT, 0L)

        if (!BingeCatProfileMapRules.shouldApply(
                incomingProfileId = incomingId,
                incomingCleared = cleared,
                localProfileId = localId,
                incomingUpdatedAt = incomingUpdatedAt,
                localWrittenAt = localWrittenAt
            )
        ) {
            return
        }

        if (incomingId == null) {
            mapPrefs.edit()
                .remove(com.kennyb1201.kbstream.data.bingecat.BingeCatProfileMap.KEY_PROFILE_ID)
                .remove(com.kennyb1201.kbstream.data.bingecat.BingeCatProfileMap.KEY_PROFILE_NAME)
                .apply()
            syncPrefs.edit()
                .putLong(BINGECAT_MAP_WRITTEN_AT, incomingUpdatedAt)
                .putBoolean(BINGECAT_MAP_CLEARED, true)
                .apply()
        } else {
            mapPrefs.edit()
                .putLong(
                    com.kennyb1201.kbstream.data.bingecat.BingeCatProfileMap.KEY_PROFILE_ID,
                    incomingId
                )
                .putString(
                    com.kennyb1201.kbstream.data.bingecat.BingeCatProfileMap.KEY_PROFILE_NAME,
                    incomingName.takeIf { it.isNotBlank() }
                )
                .apply()
            syncPrefs.edit()
                .putLong(BINGECAT_MAP_WRITTEN_AT, incomingUpdatedAt)
                .putBoolean(BINGECAT_MAP_CLEARED, false)
                .apply()
        }
        if (live) resetBingeCatStateAfterMappingChange(context)
    }

    /**
     * The mapping just changed on the on-screen profile.
     *
     * Only the repository's cached snapshot goes: it was read for the PREVIOUS
     * BingeCat profile, and serving it after a mapping change would show another
     * profile's lists until the next read (the same trap the cache's own
     * `mappedId` guard closes).
     */
    private fun resetBingeCatStateAfterMappingChange(context: Context) {
        runCatching {
            com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
                .getInstance(context)
                .onSyncedMappingChanged()
        }.onFailure {
            android.util.Log.w(TAG, "bingecat reset after synced mapping change failed: ${it.message}")
        }
    }

    /**
     * The BingeCat session just changed on the on-screen profile.
     *
     * The cached snapshot goes, because it was read with the previous session's
     * token; the mapping stays, because it is this profile's choice about which
     * BingeCat profile to read and the account behind it is the same one the
     * viewer just connected on the other device. Nothing in the local library,
     * watch history or resume rows is touched - the same line
     * [com.kennyb1201.kbstream.data.bingecat.BingeCatRepository.disconnect]
     * draws.
     */
    private fun resetBingeCatStateAfterSessionChange(context: Context) {
        runCatching {
            com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
                .getInstance(context)
                .onSyncedSessionChanged()
        }.onFailure {
            android.util.Log.w(TAG, "bingecat reset after synced session change failed: ${it.message}")
        }
    }

    /**
     * The Kitsu session just changed on the on-screen profile (F5).
     *
     * The library's `animeId -> entry id` cache belongs to the PREVIOUS account,
     * so a leftover id would PATCH the other account's entry - or, after a
     * tombstone, write under a dead grant. Local history, resume rows and watched
     * markers are the viewer's and stay untouched.
     */
    private fun resetKitsuStateAfterSessionChange() {
        runCatching {
            com.kennyb1201.kbstream.data.kitsu.KitsuLibrary.onSyncedSessionChanged()
        }.onFailure {
            android.util.Log.w(TAG, "kitsu reset after synced session change failed: ${it.message}")
        }
    }

    /**
     * The AniList session just changed on the on-screen profile (F5).
     *
     * Same hazard as Kitsu's, with no re-create backstop: a cached
     * `mediaId -> list-entry id` from the previous account can make a
     * `DeleteMediaListEntry` delete the wrong account's entry outright.
     */
    private fun resetAniListStateAfterSessionChange() {
        runCatching {
            com.kennyb1201.kbstream.data.anime.AniListLibrary.onSyncedSessionChanged()
        }.onFailure {
            android.util.Log.w(TAG, "anilist reset after synced session change failed: ${it.message}")
        }
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
