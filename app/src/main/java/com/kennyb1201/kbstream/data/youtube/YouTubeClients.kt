package com.kennyb1201.kbstream.data.youtube

import org.json.JSONObject

/**
 * The embed page for one video.
 *
 * It is two things at once, which is why it lives here rather than in either
 * caller: the page the watch config is minted from (its ytcfg carries the
 * INNERTUBE_API_KEY and the VISITOR_DATA of the *embedded* client context),
 * and the `context.thirdParty.embedUrl` the WEB_EMBEDDED_PLAYER client has to
 * declare on its player requests.
 */
internal fun youtubeEmbedUrl(videoId: String): String = "https://www.youtube.com/embed/$videoId"

/**
 * What a client's User-Agent is: its own app identity, or a platform the app
 * runs on. Only an app UA carries the InnerTube client version (that is how
 * YouTube's own apps present themselves); a TV browser or a web page carries
 * its platform's UA instead.
 */
internal enum class ClientKind {
    /** A native app (android, ios, android_vr): the UA is the app's identity. */
    APP,

    /** A set-top platform (TVHTML5 on Cobalt): a fixed TV-browser UA. */
    TV,

    /**
     * A web player embedded in a page: the desktop UA, plus the per-video
     * `context.thirdParty.embedUrl` such a player declares.
     */
    WEB_EMBEDDED
}

/**
 * One InnerTube client identity: the id/version pair the player API is asked
 * with, the client context sent with it, and the User-Agent every request for
 * that client's formats has to carry.
 *
 * These are one unit on purpose. googlevideo signs a stream URL for the client
 * that requested it, so the `x-youtube-client-name`/`-version` headers, the
 * `context.client` object and the UA used by the reachability probe and by
 * playback are three views of the SAME client. Mixing them - playing an
 * iOS-signed progressive URL under the Android UA - is a false 403, and that is
 * exactly what the format parser used to do by attributing every client's
 * formats to the first table entry.
 *
 * The UA is passed in already built from [version] (see [YouTubeClients]), and
 * [init] rejects a [ClientKind.APP] entry whose UA does not carry the version
 * it claims, so the two cannot drift apart. The TV and embedded-web clients are
 * the deliberate exceptions: their UAs are fixed platform strings (Cobalt, a
 * desktop Chrome) that carry no InnerTube version in reality, so the only thing
 * to guarantee there is that the string has one definition - this table.
 */
internal data class YouTubeClient(
    val key: String,
    val id: String,
    val version: String,
    val userAgent: String,
    val context: JSONObject,
    val priority: Int,
    val kind: ClientKind = ClientKind.APP
) {
    init {
        require(kind != ClientKind.APP || userAgent.contains(version)) {
            "YouTube client '$key' presents version '$version' but its UA " +
                "does not carry it: $userAgent"
        }
    }

    /**
     * The `context` object for one player request: the client, plus - for the
     * embedded client - the `thirdParty.embedUrl` pair the web player sends
     * beside it. A fresh object per call, so a caller can never mutate the
     * table.
     */
    fun contextFor(videoId: String): JSONObject {
        val out = JSONObject().put("client", JSONObject(context.toString()))
        if (kind == ClientKind.WEB_EMBEDDED) {
            out.put("thirdParty", JSONObject().put("embedUrl", youtubeEmbedUrl(videoId)))
        }
        return out
    }
}

/**
 * THE table of YouTube client identities, in the order they are tried.
 *
 * Why a table and not inline literals: the client UA used to exist twice - once
 * here, once hand-copied in [YoutubeChunkedDataSourceFactory]'s UA ladder - and
 * the client versions sat frozen at Feb-Mar 2025 while YouTube bot-walled them
 * at rising rates (android_vr had already started answering LOGIN_REQUIRED).
 * Both copies now derive from this one list.
 *
 * Order is the extractor's fallback chain. `android` is still the preferred
 * client for adaptive picks, `tv` (TVHTML5) is the client YouTube's own TV apps
 * use and, like `ios`, still serves token-free formats, `ios` and `web_embedded`
 * widen the pool, and `android_vr` stays LAST because of its LOGIN_REQUIRED
 * history.
 *
 * Versions are the ones the current shipping clients present (checked against
 * yt-dlp's client table, Oct 2026). They are the identity YouTube is asked
 * with, so a bump is a bot-wall question, not a cosmetic change: android and
 * ios are on the 21.x trains, TVHTML5 carries a date-stamped build, and
 * android_vr is pinned at 1.65.10 - later VR versions are documented to return
 * SABR-only streams.
 */
internal object YouTubeClients {

    /**
     * Desktop Chrome UA. It is the UA for every config fetch - the embed page
     * that mints the visitor in the embedded context - and the identity of the
     * `web_embedded` client, which is a web player seen through a desktop
     * browser.
     */
    const val DEFAULT_USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

    // Current shipping versions (see the class comment). android_vr stays at
    // 1.65.10 on purpose: a VR clientVersion above 1.65 has been observed
    // returning SABR-only streams, which this extractor cannot play.
    private const val ANDROID_VERSION = "21.26.364"
    private const val TV_VERSION = "7.20260707.07.00"
    private const val IOS_VERSION = "21.26.4"
    private const val WEB_EMBEDDED_VERSION = "1.20260708.01.00"
    private const val ANDROID_VR_VERSION = "1.65.10"

    /**
     * The most reliable anonymous client, and the preferred one for adaptive
     * video/audio picks (see InnerTubeExtractor.PREFERRED_SEPARATE_CLIENT).
     */
    val android: YouTubeClient = YouTubeClient(
        key = "android",
        id = "3",
        version = ANDROID_VERSION,
        userAgent = "com.google.android.youtube/$ANDROID_VERSION " +
            "(Linux; U; Android 14; en_US) gzip",
        context = clientContext(
            name = "ANDROID",
            version = ANDROID_VERSION,
            "osName" to "Android",
            "osVersion" to "14",
            "platform" to "MOBILE",
            "androidSdkVersion" to 34
        ),
        priority = 0
    )

    /**
     * The set-top client YouTube's own TV apps use (TVHTML5). It still serves
     * token-free formats, which the web client no longer does. Its UA is the
     * Cobalt platform string - the browser the TV app actually runs on - never
     * the desktop Chrome UA.
     */
    val tv: YouTubeClient = YouTubeClient(
        key = "tv",
        id = "7",
        version = TV_VERSION,
        userAgent = "Mozilla/5.0 (ChromiumStylePlatform) " +
            "Cobalt/25.lts.30.1034943-gold (unlike Gecko), " +
            "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
        context = clientContext(
            name = "TVHTML5",
            version = TV_VERSION,
            "platform" to "TV"
        ),
        priority = 1,
        kind = ClientKind.TV
    )

    /**
     * The iPhone client. Its device fields stay on the era of the version it
     * claims (an iPhone16,2 on iOS 18.3.2): a version/device mismatch is one of
     * the tells a bot-wall checks.
     */
    val ios: YouTubeClient = YouTubeClient(
        key = "ios",
        id = "5",
        version = IOS_VERSION,
        userAgent = "com.google.ios.youtube/$IOS_VERSION " +
            "(iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        context = clientContext(
            name = "IOS",
            version = IOS_VERSION,
            "deviceMake" to "Apple",
            "deviceModel" to "iPhone16,2",
            "osName" to "iPhone",
            "osVersion" to "18.3.2.22D82",
            "platform" to "MOBILE"
        ),
        priority = 2
    )

    /**
     * The player as it runs inside an embedded web page. Its requests declare
     * `context.thirdParty.embedUrl` (see [YouTubeClient.contextFor]) - the
     * embed page the watch config is minted from - and it presents the desktop
     * Chrome UA like any browser-embedded player.
     */
    val webEmbedded: YouTubeClient = YouTubeClient(
        key = "web_embedded",
        id = "56",
        version = WEB_EMBEDDED_VERSION,
        userAgent = DEFAULT_USER_AGENT,
        context = clientContext(
            name = "WEB_EMBEDDED_PLAYER",
            version = WEB_EMBEDDED_VERSION,
            "platform" to "DESKTOP"
        ),
        priority = 3,
        kind = ClientKind.WEB_EMBEDDED
    )

    /**
     * The Quest client. LAST in the chain: its sessions have returned
     * LOGIN_REQUIRED at rising rates, and its URLs only play under the exact VR
     * UA. It is also the legacy default UA of the chunked data source, because
     * hint-less sources come from the NewPipe/Piped path and are expected to
     * keep working the way they always have.
     */
    val androidVr: YouTubeClient = YouTubeClient(
        key = "android_vr",
        id = "28",
        version = ANDROID_VR_VERSION,
        userAgent = "com.google.android.apps.youtube.vr.oculus/$ANDROID_VR_VERSION " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        context = clientContext(
            name = "ANDROID_VR",
            version = ANDROID_VR_VERSION,
            "deviceMake" to "Oculus",
            "deviceModel" to "Quest 3",
            "osName" to "Android",
            "osVersion" to "12L",
            "platform" to "MOBILE",
            "androidSdkVersion" to 32
        ),
        priority = 4
    )

    /** The fallback chain, highest priority first. Every client is queried. */
    val all: List<YouTubeClient> = listOf(android, tv, ios, webEmbedded, androidVr)

    /**
     * The clients' UAs, in chain order. [YoutubeChunkedDataSourceFactory]'s UA
     * ladder is exactly this list - not a hand-copied second copy of it.
     */
    val userAgents: List<String> = all.map { it.userAgent }

    private val byKey: Map<String, YouTubeClient> = all.associateBy { it.key }

    /** The client with this key, or null for a key that is not in the table. */
    fun clientFor(key: String): YouTubeClient? = byKey[key]

    /** UA of the client a signed URL belongs to, or null for an unknown key. */
    fun userAgentFor(key: String): String? = byKey[key]?.userAgent

    private fun clientContext(
        name: String,
        version: String,
        vararg extra: Pair<String, Any>
    ): JSONObject = JSONObject().apply {
        put("clientName", name)
        put("clientVersion", version)
        extra.forEach { (key, value) -> put(key, value) }
        put("hl", "en")
        put("gl", "US")
    }
}
