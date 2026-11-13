package com.kennyb1201.kbstream.data.anime

import com.kennyb1201.kbstream.BuildConfig

/**
 * What identifies this app to AniList, and where its connect lives
 * (spec 2026-11-13 phase 2, F1).
 *
 * A client ID AND NOTHING ELSE. AniList issues every registered app a client
 * secret, and the Authorization Code grant needs it in the token exchange - but a
 * secret compiled into an APK is recoverable from every published build, which is
 * exactly what `SimklClientConfigTest` fails the build for. AniList documents the
 * answer itself: "If your client is in an environment where your client
 * credentials and secret cannot be securely stored, you should use the Implicit
 * Grant. These types of applications are typically websites or mobile apps."
 *
 * So the connect is the implicit grant against the auth-pin redirect: the viewer
 * approves on a phone, AniList shows them an access token, and the token is
 * pasted into the app. No secret ships, no token exchange exists, and the same
 * paste works on a TV with no browser - which is the surface the flow was
 * designed for.
 *
 * A blank client id is a build that was never given one: the row says so and no
 * authorize URL is offered, rather than sending the viewer to a page that will
 * refuse them.
 */
internal object AniListConfig {

    /** The app's client id, or blank in a build that was never given one. */
    val clientId: String get() = BuildConfig.ANILIST_CLIENT_ID

    val isConfigured: Boolean get() = clientId.isNotBlank()

    /** The authorize URL whose PIN page hands out an access token, or null. */
    fun authorizeUrl(): String? = AniListWriteRules.authorizeUrl(clientId)
}
