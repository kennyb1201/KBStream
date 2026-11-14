package com.kennyb1201.kbstream.data.anime

import com.kennyb1201.kbstream.BuildConfig

/**
 * What identifies this app to AniList, and where its connect lives
 * (spec 2026-11-13 phase 2, F1; rewritten by spec 2026-11-14, F1).
 *
 * A CLIENT ID AND A CLIENT SECRET, and the secret is a deliberate exception to
 * the rule every other tracker follows. Until 2026-11-14 this was the id alone:
 * the connect was the implicit grant, whose auth-pin page handed the viewer a
 * token to paste, precisely so no secret had to ship. AniList has removed that
 * grant - its authorize endpoint answers `unsupported_grant_type` for
 * `response_type=token` - so the connect is now the Authorization Code grant,
 * and that grant needs the secret in the token exchange.
 *
 * Kenny's decision, 2026-11-14: bundle it. KBStream has no backend to hold the
 * secret, so the alternatives were no AniList writes at all or a secret in the
 * APK. What that costs is recorded plainly rather than explained away: the
 * secret IS recoverable from any published build by anyone who unpacks it. It is
 * used in exactly one place (the exchange POST body in [AniListAuth]), it never
 * travels in a URL, a log line, a diagnostic paste or a sync payload, and it is
 * the ONLY `*SECRET*` field `SimklClientConfigTest` allows - the exception is
 * AniList-only and encoded in that test.
 *
 * A blank id or secret is a build that was never given one: the row says so and
 * no authorize URL is offered, rather than sending the viewer to a page that will
 * refuse them.
 */
internal object AniListConfig {

    /** The app's client id, or blank in a build that was never given one. */
    val clientId: String get() = BuildConfig.ANILIST_CLIENT_ID

    /**
     * The app's client secret, or blank.
     *
     * Only [AniListAuth]'s exchange reads this. Nothing else in the app may
     * carry it, log it or publish it.
     */
    val clientSecret: String get() = BuildConfig.ANILIST_CLIENT_SECRET

    /**
     * Whether the app is registered at all. The IDENTITY half: a build with an id
     * but no secret can still hold a synced session and write with it, so this
     * stays true on the id alone.
     */
    val isConfigured: Boolean get() = clientId.isNotBlank()

    /** True when this build can complete an exchange - id AND secret. */
    val canConnect: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /** True for the inert-row case: an app id with no secret beside it. */
    val missingClientSecret: Boolean get() = clientId.isNotBlank() && clientSecret.isBlank()

    /**
     * The authorize URL a flow THIS device will finish, or null when this build
     * cannot connect.
     */
    fun authorizeUrl(state: String? = null): String? {
        if (!canConnect) return null
        return AniListConnectRules.authorizeUrl(clientId, state)
    }

    /**
     * The authorize URL to SHOW - the link a TV prints and the QR encodes - with
     * no state, because the phone that approves it is the device that receives
     * the code (see [AniListConnectRules.redirectOutcome]).
     */
    fun advertisedAuthorizeUrl(): String? = authorizeUrl(state = null)
}
