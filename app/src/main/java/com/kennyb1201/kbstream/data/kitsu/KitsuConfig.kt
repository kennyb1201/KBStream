package com.kennyb1201.kbstream.data.kitsu

import com.kennyb1201.kbstream.BuildConfig

/**
 * Where Kitsu's write side lives and what identifies this app to it
 * (spec 2026-11-13 phase 2, F2).
 *
 * Two hosts, and they are not the same one: the API is `kitsu.app/api/edge` (the
 * reads in [KitsuClient] already use it) while the OAuth token endpoint sits
 * beside it at `kitsu.app/api/oauth/token` - Kitsu runs OAuth through Doorkeeper,
 * which mounts on the app, not on the JSON:API. `kitsu.io` still answers the old
 * host, but the API moved, so a stale literal would be a silent outage.
 *
 * The client id is the APP's - one value for every user, profile and device -
 * baked into the build exactly like Trakt's and MAL's: local.properties first,
 * then the environment, then blank. There is deliberately **no client secret**
 * field: Kitsu's documented password grant needs none (a token issued with a
 * secret needs that secret to refresh, and this app's registration does not use
 * one), and a secret compiled into an APK is recoverable from every published
 * build - the rule `SimklClientConfigTest` enforces for every tracker.
 *
 * A blank client id is not fatal here, unlike MAL: Kitsu's password grant is the
 * documented flow for native clients and its own docs show the request without
 * app credentials. The id is sent when the build has one (a registered app gets
 * per-app limits and a revocable identity) and omitted when it does not.
 */
internal object KitsuConfig {

    /**
     * The JSON:API base the writes go to - the same one the reads already use.
     *
     * The literal lives HERE, not beside the read client, so the write rules can
     * be compiled and tested on the JVM: [KitsuWriteRules] is the part with the
     * JSON:API bodies that a socket test could not check anyway, and a dependency
     * on the read client would drag OkHttp and Android's JSON into every test of
     * it. `KitsuClient.BASE_URL` points back here, so there is still one literal.
     */
    const val API_BASE = "https://kitsu.app/api/edge"

    /** Doorkeeper's token endpoint. Both grants POST here. */
    const val TOKEN_URL = "https://kitsu.app/api/oauth/token"

    /** What every Kitsu request calls itself, so a report pins the release. */
    const val USER_AGENT = "KBStream/" + BuildConfig.VERSION_NAME

    const val JSON_API = "application/vnd.api+json"

    /** How long a single call may take. A write is fire-and-forget; this bounds it. */
    const val CALL_TIMEOUT_MS = 10_000L

    /**
     * A token is treated as dead this long before it actually expires, so the
     * refresh happens on this side of the cliff rather than in the middle of a
     * request that then has to be retried.
     */
    const val EXPIRY_SKEW_MS = 120_000L

    /**
     * Kitsu's documented spacing. No rate headers come back and no ceiling is
     * published, so this is the conservative one-request-per-second the read
     * client also uses.
     */
    const val MIN_SPACING_MS = 1_000L

    /** The app's client id, or blank in a build that was never given one. */
    val clientId: String get() = BuildConfig.KITSU_CLIENT_ID

    /**
     * Whether a connect attempt can be presented at all.
     *
     * TRUE without a client id on purpose: the password grant does not need one.
     * A build given an id sends it and a build without one does not - the
     * connect row is available either way.
     */
    val isConfigured: Boolean get() = true
}
