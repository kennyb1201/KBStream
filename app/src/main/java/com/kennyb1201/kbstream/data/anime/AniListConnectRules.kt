package com.kennyb1201.kbstream.data.anime

import kotlin.random.Random

/**
 * AniList's Authorization Code grant, pure (spec 2026-11-14).
 *
 * THE FLOW THIS REPLACES IS DEAD AT ANILIST'S END. Until 2026-11-14 the connect
 * was the IMPLICIT grant: `response_type=token`, the redirect registered as
 * AniList's own auth-pin page, and a token the viewer copied into the app. That
 * kept `ANILIST_CLIENT_SECRET` out of the APK - the rule every other tracker
 * follows - but AniList has since removed the implicit grant: its authorize
 * endpoint now answers `{"error":"unsupported_grant_type"}` for
 * `response_type=token` (verified live by two independent apps in 2026-06). The
 * pin page and the paste-a-token flow were built entirely on that grant, so no
 * client id or redirect fix could save them.
 *
 * Kenny decided the reversal on 2026-11-14 ("Bundle the secret - spec the code
 * grant switch"), and it is AniList-only. The exchange needs the secret, KBStream
 * has no backend to hold it, so it ships in the APK - recoverable by anyone who
 * unpacks a build, which is the accepted cost of the decision, not a leak to
 * chase.
 *
 * WHAT DOES NOT EXIST HERE, ON PURPOSE:
 *  - **no PKCE**: AniList's code grant does not accept `code_challenge` /
 *    `code_challenge_method`, so adding them would be a flow that never
 *    completes;
 *  - **no refresh token**: AniList issues none, so a 401 still means
 *    needs-reconnect;
 *  - **no secret in a URL**: the secret is one field of the exchange POST body
 *    and nothing else in this file touches it.
 *
 * Everything is pure so the JVM can check the shape: the authorize URL, the
 * redirect parse (including the state check), the exchange form, the pasted
 * value, the token parse and the failure copy.
 */

/** Where AniList's two endpoints live. Public endpoints: no secret. */
internal const val ANILIST_AUTHORIZE_URL = "https://anilist.co/api/v2/oauth/authorize"
internal const val ANILIST_TOKEN_URL = "https://anilist.co/api/v2/oauth/token"

/**
 * The redirect registered on the AniList app page, 2026-11-14.
 *
 * The app's own scheme, so an approval returns straight here and the code is
 * captured automatically - the shape MAL's `kbstream://oauth/mal` links already
 * use, and the reason nothing is pasted in the ordinary path. AniList rejects an
 * authorize request whose `redirect_uri` is not the registered one, so this
 * string, the manifest's intent filter and the app page must stay identical.
 */
internal const val ANILIST_REDIRECT_URI = "kbstream://oauth/anilist"

/** What a redirect delivered, once its state has been checked. */
internal sealed interface AniListRedirect {
    /** A code to exchange; [state] already matched (or there was none to match). */
    data class Code(val code: String) : AniListRedirect

    /**
     * AniList refused on its own page (`?error=access_denied...`): the viewer
     * cancelled, or the app's registration is wrong. Nothing to exchange.
     */
    data class Refused(val error: String) : AniListRedirect

    /**
     * Not a redirect this app can act on: a wrong base, no code, or a state this
     * device never generated. A state mismatch is the one that matters - it is
     * how a forged callback is refused.
     */
    data class Ignored(val reason: String) : AniListRedirect
}

/** What the connect field was given: the code, or something else. */
internal sealed interface AniListPastedValue {
    /** A bare code, ready for the exchange. */
    data class Code(val code: String) : AniListPastedValue

    /** The whole redirect URL (or its query): parsed exactly like a callback. */
    data class Redirect(val url: String) : AniListPastedValue

    /**
     * What the old flow asked for and this one must never exchange: an access
     * token. Sending it as a code would fail at AniList and read as an outage.
     */
    data object Token : AniListPastedValue

    /** Nothing usable was typed. */
    data object Missing : AniListPastedValue
}

internal object AniListConnectRules {

    /** How the field's sentence names each mistake. Never echoes what was typed. */
    const val TOKEN_PASTED_MESSAGE =
        "That is an AniList token, not the code. Open the link and approve KBStream, " +
            "then paste the code AniList sends back."

    const val MISSING_CODE_MESSAGE =
        "Paste the code AniList sent back, or open the link above and approve there."

    /**
     * The authorize URL the browser opens.
     *
     * `state` is optional and is only carried by a flow THIS device started (see
     * [redirectOutcome]). The link a TV shows - and the QR that encodes it - is
     * approved on a PHONE, whose app never generated this device's state, so the
     * advertised URL carries none: `state` here is a CSRF token for a flow this
     * device will finish, not decoration on a URL another device completes.
     *
     * A blank client id answers null: there is no usable URL, and a connect row
     * that offered one would be a dead end.
     */
    fun authorizeUrl(clientId: String, state: String? = null): String? {
        val id = clientId.trim()
        if (id.isEmpty()) return null
        val fields = ArrayList<String>(4)
        fields += "response_type=code"
        fields += "client_id=" + formEncode(id)
        fields += "redirect_uri=" + formEncode(ANILIST_REDIRECT_URI)
        state?.takeIf { it.isNotBlank() }?.let { fields += "state=" + formEncode(it) }
        return ANILIST_AUTHORIZE_URL + "?" + fields.joinToString("&")
    }

    /**
     * The form body of the one request that carries the secret.
     *
     * `client_secret` travels here and nowhere else: not in a URL, not in a log
     * line, not in a diagnostic paste, not in a sync payload.
     */
    fun codeExchangeForm(
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String = ANILIST_REDIRECT_URI
    ): String =
        listOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "redirect_uri" to redirectUri,
            "code" to code
        ).joinToString("&") { (key, value) -> "$key=" + formEncode(value) }

    /** A random `state`, echoed back and compared here. Hex, like MAL's. */
    fun state(random: Random = Random.Default): String {
        val alphabet = "0123456789abcdef"
        val out = StringBuilder(32)
        repeat(32) { out.append(alphabet[random.nextInt(alphabet.length)]) }
        return out.toString()
    }

    /**
     * Parses a `kbstream://oauth/anilist?...` redirect against the state this
     * device generated.
     *
     * THE NO-STATE ARRIVAL IS THE CROSS-DEVICE ONE, and it is deliberately
     * accepted: a TV shows the URL, a phone approves it, and AniList sends the
     * code to the PHONE - which never generated the TV's state, so requiring a
     * match would make the TV's QR fail every time. The guard is instead
     * symmetrical: a device WITH a pending flow demands the state match (a forged
     * callback is refused), and a device with none refuses a callback that
     * presents a state it never issued. Both halves are pinned in the tests.
     */
    fun redirectOutcome(rawUri: String?, expectedState: String?): AniListRedirect {
        if (rawUri.isNullOrBlank()) return AniListRedirect.Ignored("no link")

        val queryStart = rawUri.indexOf('?')
        val base = if (queryStart >= 0) rawUri.substring(0, queryStart) else rawUri
        if (!base.trimEnd('/').equals(ANILIST_REDIRECT_URI, ignoreCase = true)) {
            return AniListRedirect.Ignored("not the AniList callback: $base")
        }
        if (queryStart < 0) return AniListRedirect.Ignored("no query")

        val params = queryParams(rawUri.substring(queryStart + 1))

        val error = params["error"]
        if (!error.isNullOrBlank()) {
            return AniListRedirect.Refused(error)
        }

        val state = params["state"]?.takeIf { it.isNotBlank() }
        val expected = expectedState?.takeIf { it.isNotBlank() }
        when {
            expected != null && state == null ->
                return AniListRedirect.Ignored("the redirect carried no state")
            expected != null && state != expected ->
                return AniListRedirect.Ignored("state mismatch")
            expected == null && state != null ->
                return AniListRedirect.Ignored("state mismatch: no flow was started here")
        }

        val code = params["code"]
        if (code.isNullOrBlank()) return AniListRedirect.Ignored("no code")

        return AniListRedirect.Code(code)
    }

    /**
     * Reads the connect field.
     *
     * A viewer can paste the code, or the whole redirect URL the browser leaves
     * in the address bar (which is the same value with its state attached), so
     * both are accepted. A TOKEN is not: the old flow handed one out, and sending
     * it to the exchange would fail at AniList with a message about a bad code
     * rather than about a stale instruction.
     */
    fun pastedValue(pasted: String?): AniListPastedValue {
        val value = pasted?.trim().orEmpty()
        if (value.isEmpty()) return AniListPastedValue.Missing

        if (value.contains("code=") || value.startsWith(ANILIST_REDIRECT_URI, ignoreCase = true)) {
            return AniListPastedValue.Redirect(value)
        }
        // AniList access tokens are JWTs: three dot-separated parts.
        if (value.count { it == '.' } == 2 && !value.contains(' ')) {
            return AniListPastedValue.Token
        }
        if (value.any { it.isWhitespace() }) return AniListPastedValue.Missing
        return AniListPastedValue.Code(value)
    }

    /** The `access_token` of a token-endpoint body, or null. */
    fun tokenFromExchange(body: String): String? =
        jsonString(body, "access_token")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * What to say when the exchange failed.
     *
     * AniList answers a refused exchange with `error`, `message` and `hint` - the
     * sentences that tell a viewer a wrong secret from an expired code - and the
     * dialog must show them rather than a generic failure. Nothing here can
     * contain the request body, so the secret cannot leak through this path.
     */
    fun exchangeFailure(body: String, status: Int): String {
        val error = jsonString(body, "error")?.trim().orEmpty()
        val message = jsonString(body, "message")?.trim().orEmpty()
        val hint = jsonString(body, "hint")?.trim().orEmpty()
        val detail = listOf(error, message, hint)
            .filter { it.isNotEmpty() && it != "null" }
            .distinct()
            .joinToString(": ")
        return if (detail.isNotEmpty()) {
            "AniList refused the connect ($detail)."
        } else {
            "AniList answered HTTP $status and issued no token."
        }
    }

    /** Form/query encoding for the values this flow carries. */
    fun formEncode(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt().toChar()
            if (
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~'
            ) {
                out.append(ch)
            } else {
                out.append('%')
                out.append(HEX[(byte.toInt() shr 4) and 0xF])
                out.append(HEX[byte.toInt() and 0xF])
            }
        }
        return out.toString()
    }

    /** Percent-decoding for the redirect's query values (ASCII in practice). */
    fun percentDecode(value: String): String {
        if (!value.contains('%') && !value.contains('+')) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            when (val ch = value[index]) {
                '+' -> {
                    out.append(' ')
                    index++
                }
                '%' -> {
                    val hex = value.substring(index + 1, (index + 3).coerceAtMost(value.length))
                    val byte = hex.toIntOrNull(16)
                    if (byte == null || hex.length < 2) {
                        out.append(ch)
                        index++
                    } else {
                        out.append(byte.toChar())
                        index += 3
                    }
                }
                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun queryParams(query: String): Map<String, String> {
        val params = HashMap<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val separator = pair.indexOf('=')
            val key = if (separator >= 0) pair.substring(0, separator) else pair
            val value = if (separator >= 0) pair.substring(separator + 1) else ""
            params[percentDecode(key)] = percentDecode(value)
        }
        return params
    }

    /**
     * One string field out of a flat JSON body.
     *
     * The two bodies this flow reads (the token, and AniList's error) are flat
     * objects of strings and numbers, and this file stays free of `org.json` so
     * the whole flow is JVM-testable. An unreadable body simply yields null.
     */
    fun jsonString(body: String, key: String): String? {
        val marker = "\"" + key + "\""
        val at = body.indexOf(marker)
        if (at < 0) return null

        val colon = body.indexOf(':', at + marker.length)
        if (colon < 0) return null

        var index = colon + 1
        while (index < body.length && body[index].isWhitespace()) index++
        if (index >= body.length || body[index] != '"') return null

        val out = StringBuilder()
        index++
        while (index < body.length) {
            val ch = body[index]
            when {
                ch == '"' -> return out.toString()
                ch == '\\' && index + 1 < body.length -> {
                    out.append(body[index + 1])
                    index += 2
                }
                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        return null
    }

    private const val HEX = "0123456789ABCDEF"
}
