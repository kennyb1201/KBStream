package com.kennyb1201.kbstream.data.tvdb

import com.kennyb1201.kbstream.BuildConfig

/**
 * Where TheTVDB is, and what identifies this app to it (spec 2026-11-13, F1).
 *
 * The key is the APP's, one value for every user, profile and device - the same
 * wiring TMDB's, fanart's and MAL's use: `local.properties` first, then the
 * environment, then blank. It is public by construction (it is the `apikey` in
 * every `/v4/login` call, so it is in every published APK) and is deliberately
 * NOT named as a secret: SimklClientConfigTest fails the build on any
 * `*SECRET*` BuildConfig field, and TVDB has no client secret to have. The
 * subscriber PIN, which IS per user, never enters the APK (see
 * [TvdbTokenStore] and AppPreferences.getTvdbPin).
 *
 * A blank key is a BUILD fact, not a user setting: the episode-order row is
 * then not offered at all, and every surface behaves exactly as it did before
 * this feature existed (F4).
 */
object TvdbConfig {

    /**
     * v4's base, verified against `thetvdb.github.io/v4-api` (4.7.10) on
     * 2026-11-13. The trailing slash is absent on purpose: paths are appended
     * as `/series/...` and a double slash is a different resource to some
     * proxies.
     */
    const val API_BASE = "https://api4.thetvdb.com/v4"

    /** What every request calls itself, so a report pins the release. */
    const val USER_AGENT = "KBStream/" + BuildConfig.VERSION_NAME

    /**
     * Attribution is a licensing condition, not a courtesy: "Unless approved by
     * TheTVDB, attribution with a direct link to TheTVDB.com must be displayed
     * to end users viewing metadata from our API" (api-information page, read
     * 2026-11-13). The order picker is the one surface that shows TVDB-sourced
     * metadata, so it carries this line and the link.
     */
    const val ATTRIBUTION = "Metadata provided by TheTVDB"
    const val ATTRIBUTION_URL = "https://thetvdb.com"

    /** The project key; blank in a build that was never given one. */
    val apiKey: String get() = BuildConfig.TVDB_API_KEY

    /** Whether this build can talk to TVDB at all. */
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * The language an order's translated episode list is read in. TVDB's
     * episode records carry a name per language; `eng` is what the app's other
     * English-first sources use, and a missing translation falls back to the
     * record's base name on TVDB's side.
     */
    const val DEFAULT_LANGUAGE = "eng"

    /**
     * The season-type values that mean "the aired/default order", i.e. the
     * zero-state where nothing is renumbered.
     *
     * `default` is the series' own base order (the docs' default for the path
     * parameter) and `official` is the aired order; both are what TMDB's
     * numbering already follows, so either one stored is treated as "no
     * choice".
     */
    val OFFICIAL_TYPES = setOf("default", "official")
}
