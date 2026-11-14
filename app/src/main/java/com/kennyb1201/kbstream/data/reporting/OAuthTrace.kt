package com.kennyb1201.kbstream.data.reporting

/**
 * The OAuth arrival trace: what arrived, in what shape, and never with what
 * values (spec 2026-11-14, F1/F2).
 *
 * WHY THIS EXISTS. On build 0.9.310 Kenny approved KBStream on both MAL and
 * AniList, came back to the app, and neither connected - and the paste said
 * "recent errors: none this session". The failure alone would have been
 * diagnosable; the ABSENCE of any line was not. A link that never arrives leaves
 * no trace anywhere: the routers only log once they run, and logcat is not a
 * surface he can reach. So the app now records the arrival and the router's
 * verdict into the same recent-events feed COPY DIAGNOSTICS prints.
 *
 * THE HARD RULE. A record carries the link's SHAPE only - scheme, host, path and
 * the NAMES of its query parameters. Never a code, a state, a verifier, a token,
 * a client id, a secret, and never the full URL. That is why every string here is
 * built by this object rather than concatenated at a call site: a call site that
 * pasted `link` into a record would leak a one-time code into a file the viewer
 * emails. [linkShape] drops the query entirely and refuses to echo an opaque
 * link; [paramNames] keeps only the keys.
 *
 * Pure and Android-free on purpose: the contract test feeds synthetic links full
 * of credentials through the same functions the app uses.
 */
internal object OAuthTrace {

    /** True for a `kbstream://oauth/...` link - the only arrivals worth recording. */
    fun isOAuthLink(rawLink: String?): Boolean {
        val parsed = parse(rawLink) ?: return false
        return parsed.scheme == "kbstream" && parsed.host == "oauth"
    }

    /** True when [rawLink] is an OAuth link for [provider] (`mal`, `anilist`). */
    fun isFor(rawLink: String?, provider: String): Boolean {
        val parsed = parse(rawLink) ?: return false
        return parsed.scheme == "kbstream" &&
            parsed.host == "oauth" &&
            parsed.path.trim('/').equals(provider, ignoreCase = true)
    }

    /**
     * `oauth arrival warm: kbstream://oauth/mal params=code,state`, or the
     * no-data forms, which are the most informative lines of all: they separate
     * "the redirect never left the browser" from "it arrived in a shape the
     * router rejects".
     */
    fun arrivalLine(warm: Boolean, rawLink: String?): String {
        val whenPart = if (warm) "warm" else "cold"
        val prefix = "oauth arrival $whenPart: "
        if (rawLink.isNullOrBlank()) {
            return prefix + if (warm) "no data" else "launcher intent, no data"
        }
        return prefix + linkShape(rawLink) + " params=" + paramNames(rawLink)
    }

    /** `oauth route mal: duplicate callback ignored` - a fixed verdict + provider. */
    fun routeLine(provider: String, verdict: String): String = "oauth route $provider: $verdict"

    /** The verdict text for a link this router does not recognise. */
    fun notARouteVerdict(rawLink: String?): String =
        "not a callback (params=" + paramNames(rawLink) + ")"

    /**
     * The link's shape, and nothing that could be a value.
     *
     * The query and fragment are dropped before anything is read. A non-kbstream
     * link is named by its scheme alone (`<https link>`), and something that does
     * not parse as scheme://host/path is `<opaque link>` - never the raw string.
     */
    fun linkShape(rawLink: String?): String {
        val parsed = parse(rawLink) ?: return "<opaque link>"
        if (parsed.scheme != "kbstream") return "<${parsed.scheme} link>"
        return "kbstream://" + parsed.host + parsed.path
    }

    /** The query's parameter NAMES, in link order: `code,state`, or `none`. */
    fun paramNames(rawLink: String?): String {
        val query = rawLink?.substringAfter('?', "")?.substringBefore('#').orEmpty()
        if (query.isEmpty()) return "none"
        val names = query.split('&')
            .map { pair -> pair.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
        return if (names.isEmpty()) "none" else names.joinToString(",")
    }

    /** One `scheme://host/path`, with the query/fragment dropped and no userinfo. */
    private data class Shape(val scheme: String, val host: String, val path: String)

    private fun parse(rawLink: String?): Shape? {
        val value = rawLink?.trim().orEmpty()
        if (value.isEmpty()) return null
        val match = SHAPE.find(value.substringBefore('?').substringBefore('#')) ?: return null
        val scheme = match.groupValues[1].lowercase()
        // `user:pass@` never survives, and a port is not part of the shape.
        val host = match.groupValues[2]
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
        val path = match.groupValues[3].lowercase()
        if (scheme.isEmpty() || host.isEmpty()) return null
        return Shape(scheme, host, path)
    }

    private val SHAPE = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([^?#]*)")
}
