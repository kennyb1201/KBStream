package com.kennyb1201.kbstream.data.trakt

import com.kennyb1201.kbstream.BuildConfig

/**
 * Where Trakt is and what identifies this app to it.
 *
 * The client id is public by construction - it travels in every authorize URL -
 * and is read from the build the same way Simkl's is (local.properties, then the
 * environment, then nothing).
 *
 * There is deliberately **no client secret here**, and one must not be added.
 * Trakt's device flow is a public-client flow, its current authentication guide
 * deprecates the secret for user sign-in, and anything compiled into this app is
 * recoverable from every published APK (see SimklClientConfigTest, which fails
 * the build on any `*SECRET*` BuildConfig field). If Trakt ever rejects a
 * secret-less device-token request, the exchange has to move behind a server -
 * baking the secret in is not an option.
 */
object TraktConfig {

    const val BASE_URL = "https://api.trakt.tv/"

    /** The version header every request carries (`trakt-api-version`). */
    const val API_VERSION = "2"

    /** What the viewer is told to open, and what Trakt's own code page shows. */
    const val ACTIVATE_URL = "https://trakt.tv/activate"

    /**
     * OAuth's own "no redirect" marker. The device flow never redirects, but
     * the refresh grant still lists this value, exactly as Trakt's docs do.
     */
    const val REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"

    const val USER_AGENT = "KBStream/1.0"

    /** The client id; blank in a build that was never given one. */
    val clientId: String get() = BuildConfig.TRAKT_CLIENT_ID

    /** Trakt can be used as soon as an id is present - the same test Simkl uses. */
    val isConfigured: Boolean get() = clientId.isNotBlank()
}
