package com.kennyb1201.kbstream.data.mal

import com.kennyb1201.kbstream.BuildConfig

/**
 * Where MyAnimeList is and what identifies this app to it (spec 2026-11-13, F1).
 *
 * The client id is the APP's, one value for every user, profile and device,
 * baked into the build exactly like Trakt's (`TRAKT_CLIENT_ID`): local.properties
 * first, then the environment, then blank. It is public by construction - it
 * rides the authorize URL and the `X-MAL-CLIENT-ID` header on every public read -
 * and each profile still connects its OWN account through OAuth.
 *
 * There is deliberately **no client secret**, and one must never be added: MAL
 * issues a secret only to web apps, this app is registered as an android client,
 * its flow is PKCE with a plain challenge, and a secret compiled into an APK is
 * recoverable from every published build (the same rule SimklClientConfigTest
 * enforces for every tracker: no `*SECRET*` BuildConfig field).
 */
object MalConfig {

    const val API_BASE = "https://api.myanimelist.net/v2"

    /** MalAuthRules owns these two; the config only re-exports the flow's URLs. */
    const val AUTHORIZE_URL = MAL_AUTHORIZE_URL

    const val TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"

    /**
     * The redirect registered on the app at `myanimelist.net/apiconfig` (App Type
     * android, 2026-11-13). MAL rejects an authorize request whose
     * `redirect_uri` is not one of the app's own, so this string and the
     * manifest's intent filter have to stay identical.
     */
    const val REDIRECT_URI = MAL_REDIRECT_URI

    /**
     * The only scope this integration asks for. It covers reading AND writing
     * the user's anime list, which is the whole of v1 - there is no separate
     * read scope to request.
     */
    const val SCOPE = "write:users"

    /** What every MAL request calls itself, so a report pins the release. */
    const val USER_AGENT = "KBStream/" + BuildConfig.VERSION_NAME

    /** The client id; blank in a build that was never given one. */
    val clientId: String get() = BuildConfig.MAL_CLIENT_ID

    /**
     * Whether this build can talk to MAL at all.
     *
     * A blank id is a BUILD problem, not a user setting: the MAL row says the app
     * is not configured and its Connect button is disabled, and nothing was ever
     * meant to be typed into Settings to fix it.
     */
    val isConfigured: Boolean get() = clientId.isNotBlank()

    /**
     * The fields this app asks for, per screen. MAL returns a minimal object
     * without them (`fields` is not optional in practice), and the spec forbids
     * requesting more than is used.
     */
    const val ID_FIELDS = "id,title,alternative_titles"

    /** The facts the anime Detail header shows, resolvable with the id call. */
    const val DETAIL_FIELDS = "id,title,alternative_titles,mean,rank,popularity,num_episodes,media_type"

    /** One list entry: what the legs read. */
    const val LIST_FIELDS =
        "list_status{status,score,num_watched_episodes,is_rewatching,updated_at}," +
            "num_episodes,alternative_titles,media_type"
}
