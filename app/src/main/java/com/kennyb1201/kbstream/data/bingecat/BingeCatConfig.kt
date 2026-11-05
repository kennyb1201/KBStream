package com.kennyb1201.kbstream.data.bingecat

import com.kennyb1201.kbstream.BuildConfig

/**
 * Where BingeCat is and what identifies this app to it.
 *
 * Two facts decide this file's shape, both read from the live contract
 * (`https://bingecat.com/openapi.json`, v1.0.0) on 2026-11-04:
 *
 *  1. **`bingecat-api-key` is the application's `client_id`, not a secret.**
 *     The contract describes the header as "Application client_id. Must match
 *     the JSON client_id where a body contains one", and the client id is
 *     public by construction - it travels in every device-code request. So the
 *     header ships with the app exactly the way `trakt-api-key` does, and the
 *     spec's open question Q1 ("what is `bingecat-api-key`?") is answered: it
 *     is [clientId].
 *  2. **The device flow is a public-client flow.** The discovery document
 *     advertises `token_endpoint_auth_methods_supported: ["client_secret_post",
 *     "none"]`, and a real token poll with no secret answers
 *     `400 authorization_pending` rather than `invalid_client`. There is
 *     therefore **no client secret here, and one must not be added** - the same
 *     rule the Simkl and Trakt configs state, enforced for those by
 *     SimklClientConfigTest.
 */
object BingeCatConfig {

    const val BASE_URL = "https://bingecat.com/"

    /** The version header every request carries (`bingecat-api-version`). */
    const val API_VERSION = "1"

    /**
     * Where the viewer enters the code.
     *
     * The device-code response carries its own `verification_url` and that is
     * what the screen shows; this is only the fallback for a response that
     * omitted it (the contract marks it required, so this should never be
     * displayed - it exists so no screen has to print a blank address).
     */
    const val ACTIVATE_URL = "https://bingecat.com/activate"

    /**
     * Every scope the roadmap asks for: read the profile list (to file the
     * mapping), read lists (watchlist, saved list, user lists), read history,
     * write history, and now write lists.
     *
     * `history:write` is what turns the connection into a tracker (completion
     * scrobbles and the mark-as-watched/unwatched mirror); `lists:write` is what
     * lets the Library button save and unsave a title on the profile's watchlist
     * and user lists. `search:read` is what BingeCat guards its public-list
     * discovery routes with (categories, a category's lists, list search), so
     * browsing public lists cannot be done without it; `settings:read` stays
     * out, since nothing here reads account settings.
     *
     * Widening this string does NOT widen an existing grant: OAuth never adds a
     * scope silently, so a viewer who connected under step 1 keeps a read-only
     * token, and one who connected under step 2 keeps a token that cannot save -
     * until they reconnect (see [BingeCatWriteRules.hasWriteScope] and
     * [BingeCatWriteRules.hasListsWriteScope], and the connect screen's
     * re-connect prompt).
     */
    const val SCOPES =
        "profiles:read lists:read history:read history:write lists:write search:read"

    /** The write scope the tracker's gates check for on the stored grant. */
    const val WRITE_SCOPE = "history:write"

    /**
     * The scope saving needs: the watchlist and user-list writes (step 3).
     *
     * A separate name from [WRITE_SCOPE] because the two legs fail
     * independently: a step-2 grant can scrobble and cannot save, and a gate
     * that asked for "the write scope" would be wrong for one of them.
     */
    const val LISTS_WRITE_SCOPE = "lists:write"

    /**
     * The scope BingeCat guards public-list discovery with (step 6).
     *
     * Its own constant because it gates a different surface from either write
     * leg: a step-3 grant can import (it holds `lists:write`) and cannot browse
     * to find the list, and a read-only grant cannot do either. The browse
     * screen asks this question and turns the answer into the same re-connect
     * prompt the writes use, instead of earning a bare 403.
     */
    const val PUBLIC_LISTS_SCOPE = "search:read"

    /**
     * The scope whose absence from the developer app breaks the most of v1,
     * named so a rejection can say what to fix instead of printing
     * `invalid_scope` (see [com.kennyb1201.kbstream.data.bingecat.BingeCatConnectFailure]).
     */
    const val REQUIRED_LISTS_SCOPE = "lists:read"

    const val USER_AGENT = "KBStream/1.0"

    /**
     * KBStream's own BingeCat application, as the public client id.
     *
     * Public by construction, exactly as Trakt's is: an OAuth device flow has
     * no way to keep a client id off the wire - it IS the `bingecat-api-key`
     * header and the `client_id` body field - and `token_endpoint_auth_methods`
     * lists `none`, so there is no secret to pair it with. It is a fallback
     * rather than the only source so a build can override it
     * (`BINGECAT_CLIENT_ID` in local.properties or the environment,
     * `BuildConfig.BINGECAT_CLIENT_ID`), and so this integration works in a
     * build that was handed no secret - which is the release APK's situation
     * while the repository has no such secret configured.
     *
     * Not a credential: it grants nothing on its own. Every read it identifies
     * still needs a token the viewer approved in their own BingeCat account.
     */
    private const val PUBLIC_CLIENT_ID = "bca_S76tq8yirVGISZcdrvcfbEIfGeRERZlL"

    /** The client id: the build's own when it has one, else this app's. */
    val clientId: String
        get() = BuildConfig.BINGECAT_CLIENT_ID.trim().ifBlank { PUBLIC_CLIENT_ID }

    /** BingeCat can be used as soon as an id is present. */
    val isConfigured: Boolean get() = clientId.isNotBlank()
}
