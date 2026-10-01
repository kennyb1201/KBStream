package com.kennyb1201.kbstream.data.player

/**
 * The User-Agent the app presents to a stream host when the source does not
 * name one itself.
 *
 * This has to be one value shared by both engines, because it is not a nicety:
 * the hosts this app plays from - addon proxies, debrid gateways, usenet - gate
 * on it, and the two engines used to disagree. The ExoPlayer path fell back to
 * the browser User-Agent below, while the MPV path set no User-Agent at all and
 * therefore asked as `mpv/<version>`. A source that accepts the app in the main
 * player and refuses it in the backup engine is exactly the "Switch player"
 * failure, and an engine that cannot open a stream looks nothing like a User
 * Agent problem from the outside.
 *
 * A User-Agent that came with the source always wins: a URL signed for a
 * particular client (googlevideo, a proxy that names its own agent) is rejected
 * when it is asked for as something else.
 */
internal object StreamUserAgent {

    /** The browser identity the app plays as when the source names none. */
    const val DEFAULT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private const val HEADER = "User-Agent"

    /** The User-Agent [headers] name, else [DEFAULT]. */
    fun resolve(headers: Map<String, String>): String =
        headers.entries
            .firstOrNull { it.key.equals(HEADER, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT

    /**
     * [headers] without their User-Agent.
     *
     * The two engines both carry the agent separately from the rest of the
     * headers - Media3 through `setUserAgent`, mpv through its `user-agent`
     * option - and a User-Agent sent twice is a request the source can reject.
     */
    fun withoutUserAgent(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { !it.equals(HEADER, ignoreCase = true) }
}
