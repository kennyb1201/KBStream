package com.kennyb1201.kbstream.data.mal

import java.util.Locale
import kotlin.random.Random

/**
 * MAL's OAuth rules, pure (spec 2026-11-13, F1).
 *
 * MAL has NO device flow, so the connect is Authorization Code + PKCE in a
 * browser - which is a different shape from Trakt's and BingeCat's and cannot
 * reuse their code. Everything that decides the shape is here, with no Android
 * and no sockets, so the flow can be checked on the JVM: the verifier, the
 * challenge, the authorize URL, the redirect parse (including the state check),
 * the token form, the token parse, expiry and the 429 window.
 *
 * One MAL quirk is encoded here and must not be "fixed": only
 * `code_challenge_method=plain` is supported. S256 is rejected by MAL, so the
 * challenge IS the verifier.
 */

/**
 * The two URLs and the redirect the whole flow is built on.
 *
 * Here rather than in [MalConfig] because these are the flow's own shape - and
 * [MalConfig] is the only file in this package that touches the build, which is
 * what keeps everything else JVM-testable.
 */
internal const val MAL_AUTHORIZE_URL = "https://myanimelist.net/v1/oauth2/authorize"
internal const val MAL_REDIRECT_URI = "kbstream://oauth/mal"

/**
 * What happened when this device tried to OPEN MAL's authorize page
 * (spec 2026-11-14, F2a).
 *
 * The QR start link exists so a phone can run the round trip a TV cannot, so
 * every way it can end in "the app opened and nothing else happened" has a name
 * here. It used to be one Boolean: `openAuthorize` returned false for a build
 * with no app id AND for a device with no browser, and neither said anything -
 * which is the field report exactly. The scan offers KBStream, KBStream opens,
 * the browser never appears, and no sentence anywhere says why.
 */
internal enum class MalStartOutcome {
    Opened,

    /** This build has no MAL client id, so there is no authorize URL to open. */
    NotConfigured,

    /** A configured build, on a device with nothing that handles https. */
    NoBrowser
}

/**
 * The sentence a start link that could not open the browser owes the viewer, or
 * null when it opened.
 *
 * The not-configured case is a BUILD problem (see `MalConfig`), so it says that
 * instead of inviting a retry that cannot work: the only fix is a build carrying
 * the app id.
 */
internal fun malStartFailureMessage(outcome: MalStartOutcome): String? = when (outcome) {
    MalStartOutcome.Opened -> null
    MalStartOutcome.NotConfigured ->
        "MyAnimeList is not configured in this build: the code opened KBStream, but " +
            "there is no MAL app id to authorize. The phone needs KBStream built with " +
            "the MAL app id."
    MalStartOutcome.NoBrowser ->
        "This device has no browser to open MyAnimeList's authorize page."
}

/**
 * The one sentence a failed MAL connect shows and records (spec 2026-11-14, F1/F2).
 *
 * It exists as a function rather than a literal at the call site so the screen and
 * the diagnostics paste cannot say different things - and so a contract test can
 * assert that a failing LINK produces a message with nothing from the link in it:
 * [reason] is always one of this file's or [MalAuth]'s fixed sentences, never the
 * code, the state or the verifier the link carried.
 */
internal fun malFailureMessage(reason: String): String = "MAL didn't connect: $reason"

/** A parsed token response. Refresh tokens live a month, access tokens an hour. */
internal data class MalToken(
    val accessToken: String,
    val refreshToken: String?,
    /** Epoch ms after which [accessToken] is dead. */
    val expiresAtMs: Long
)

/**
 * The characters MAL allows in a verifier (RFC 3986 unreserved), and a length
 * inside its 43-128 window.
 */
internal const val MAL_VERIFIER_LENGTH = 64

private const val VERIFIER_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

/** A fresh code verifier: [MAL_VERIFIER_LENGTH] unreserved characters. */
internal fun malCodeVerifier(random: Random = Random.Default): String {
    val out = StringBuilder(MAL_VERIFIER_LENGTH)
    repeat(MAL_VERIFIER_LENGTH) {
        out.append(VERIFIER_ALPHABET[random.nextInt(VERIFIER_ALPHABET.length)])
    }
    return out.toString()
}

/**
 * The PKCE challenge for [verifier].
 *
 * `plain` on purpose: MAL's authorization endpoint rejects S256, so the challenge
 * is the verifier itself. A hash here would be a flow that never completes.
 */
internal fun malCodeChallenge(verifier: String): String = verifier

/** A random `state`, which MAL echoes back and this app must compare itself. */
internal fun malState(random: Random = Random.Default): String {
    val alphabet = "0123456789abcdef"
    val out = StringBuilder(32)
    repeat(32) { out.append(alphabet[random.nextInt(alphabet.length)]) }
    return out.toString()
}

/**
 * The authorize URL the browser opens.
 *
 * `redirect_uri` is sent even though MAL allows omitting it when the app has one
 * registered: registering a second URI later would silently change which one a
 * flow uses, and the value must match the manifest filter anyway.
 */
internal fun malAuthorizeUrl(
    clientId: String,
    challenge: String,
    state: String,
    redirectUri: String = MAL_REDIRECT_URI
): String =
    MAL_AUTHORIZE_URL +
        "?response_type=code" +
        "&client_id=" + malFormEncode(clientId) +
        "&redirect_uri=" + malFormEncode(redirectUri) +
        "&code_challenge=" + malFormEncode(challenge) +
        "&code_challenge_method=plain" +
        "&state=" + malFormEncode(state)

/** What a redirect delivered, once its state has been checked. */
internal sealed interface MalRedirect {
    /** A code to exchange; [state] already matched. */
    data class Code(val code: String) : MalRedirect

    /**
     * MAL refused on its own page (`?error=access_denied...`) - the viewer
     * cancelled, or the app's registration is wrong. Nothing to exchange.
     */
    data class Refused(val error: String) : MalRedirect

    /**
     * Not a redirect this app can act on: a missing code, a missing/mismatched
     * state, or a link that is not the MAL callback at all. A state mismatch is
     * the one that matters - it is how a forged callback is refused.
     */
    data class Ignored(val reason: String) : MalRedirect
}

/**
 * Parses a `kbstream://oauth/mal?...` redirect against the state this device
 * generated.
 *
 * The state check is not optional and is not MAL's job: the redirect arrives on
 * the phone's intent channel, where any app could send the same scheme. A link
 * carrying a different state is ignored rather than exchanged.
 */
internal fun malRedirectOutcome(rawUri: String?, expectedState: String?): MalRedirect {
    if (rawUri.isNullOrBlank()) return MalRedirect.Ignored("no link")

    val queryStart = rawUri.indexOf('?')
    val base = if (queryStart >= 0) rawUri.substring(0, queryStart) else rawUri
    if (!base.trimEnd('/').equals(MAL_REDIRECT_URI, ignoreCase = true)) {
        return MalRedirect.Ignored("not the MAL callback: $base")
    }
    if (queryStart < 0) return MalRedirect.Ignored("no query")

    val params = HashMap<String, String>()
    rawUri.substring(queryStart + 1).split('&').forEach { pair ->
        if (pair.isEmpty()) return@forEach
        val separator = pair.indexOf('=')
        val key = if (separator >= 0) pair.substring(0, separator) else pair
        val value = if (separator >= 0) pair.substring(separator + 1) else ""
        params[malPercentDecode(key)] = malPercentDecode(value)
    }

    val error = params["error"]
    if (!error.isNullOrBlank()) {
        return MalRedirect.Refused(error)
    }

    val state = params["state"]
    if (expectedState.isNullOrBlank()) return MalRedirect.Ignored("no state was generated")
    if (state.isNullOrBlank()) return MalRedirect.Ignored("the redirect carried no state")
    if (state != expectedState) return MalRedirect.Ignored("state mismatch")

    val code = params["code"]
    if (code.isNullOrBlank()) return MalRedirect.Ignored("no code")

    return MalRedirect.Code(code)
}

/**
 * The form body the code exchange POSTs.
 *
 * `client_id` in the body and NO secret: MAL's android app type has none, and the
 * documented alternative (HTTP Basic with id + secret) does not apply. [verifier]
 * is the same value the challenge was made from, which is what `plain` means.
 */
internal fun malCodeExchangeForm(
    clientId: String,
    code: String,
    verifier: String,
    redirectUri: String = MAL_REDIRECT_URI
): String =
    listOf(
        "grant_type" to "authorization_code",
        "code" to code,
        "code_verifier" to verifier,
        "client_id" to clientId,
        "redirect_uri" to redirectUri
    ).joinToString("&") { (key, value) -> "$key=" + malFormEncode(value) }

/** The refresh grant: the 401 path, and nothing else uses it. */
internal fun malRefreshForm(clientId: String, refreshToken: String): String =
    listOf(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "client_id" to clientId
    ).joinToString("&") { (key, value) -> "$key=" + malFormEncode(value) }

/**
 * Reads a token endpoint body into a [MalToken].
 *
 * `expires_in` is seconds and is turned into an absolute deadline here, because
 * every later reader asks "is this dead yet", never "how long was it good for".
 * A body with no refresh token keeps the previous one (MAL omits it on some
 * responses), which is what [previousRefresh] is for.
 */
internal fun malParseToken(
    body: String,
    nowMs: Long,
    previousRefresh: String? = null
): MalToken? {
    val access = malJsonString(body, "access_token")?.trim().orEmpty()
    if (access.isEmpty()) return null

    val expiresIn = malJsonLong(body, "expires_in")?.takeIf { it > 0L } ?: 3600L
    val refresh = malJsonString(body, "refresh_token")?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: previousRefresh

    return MalToken(
        accessToken = access,
        refreshToken = refresh,
        // A minute of slack: a token that expires while a request is in flight
        // is a 401 the caller has to handle anyway.
        expiresAtMs = nowMs + (expiresIn - 60L).coerceAtLeast(0L) * 1_000L
    )
}

/** A 401 means "refresh and retry once"; anything else is the caller's business. */
internal fun malNeedsRefresh(status: Int): Boolean = status == 401

/**
 * How long a 429 asks this client to wait, in ms.
 *
 * `Retry-After` is seconds on this API; absent or unreadable falls back to one
 * minute (the window the rate limit is reported against). Retrying INSIDE the
 * window is what extends it, so the caller parks for this long and no less.
 */
internal fun malRetryAfterMs(
    retryAfterHeader: String?,
    defaultMs: Long = 60_000L
): Long {
    val seconds = retryAfterHeader?.trim()?.toLongOrNull() ?: return defaultMs
    if (seconds <= 0L) return defaultMs
    return seconds * 1_000L
}

/** The user-facing state of the MAL connection. Honest: no state is "empty list". */
internal enum class MalConnectionState {
    /** This build was never given an app client id: nothing can connect. */
    NotConfigured,

    /** Configured, no session on this profile. */
    Disconnected,

    /** A session is stored and its access token is usable (or refreshable). */
    Connected,

    /**
     * MAL answered 401 and the refresh failed too. The session is kept (the
     * viewer may reconnect to the same account) but nothing calls MAL until
     * they do, and no loop is started.
     */
    NeedsReconnect
}

internal fun malConnectionState(
    configured: Boolean,
    hasSession: Boolean,
    needsReconnect: Boolean
): MalConnectionState = when {
    !configured -> MalConnectionState.NotConfigured
    needsReconnect -> MalConnectionState.NeedsReconnect
    hasSession -> MalConnectionState.Connected
    else -> MalConnectionState.Disconnected
}

/** Form/query encoding for the values this flow carries. */
internal fun malFormEncode(value: String): String {
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

/** Percent-decoding for the redirect's query values. */
internal fun malPercentDecode(value: String): String {
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
    // The values that matter here are ASCII (codes, states, ids).
    return out.toString()
}

/**
 * One string field out of a flat JSON body.
 *
 * The token endpoint answers a flat object of strings and numbers, and this file
 * is deliberately free of `org.json` (Android's) so the flow stays testable on the
 * JVM - so the two reads it needs are done here, and an unreadable body simply
 * yields null.
 */
internal fun malJsonString(body: String, key: String): String? {
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

/** One numeric field out of a flat JSON body; null when absent or unreadable. */
internal fun malJsonLong(body: String, key: String): Long? {
    val marker = "\"" + key + "\""
    val at = body.indexOf(marker)
    if (at < 0) return null

    val colon = body.indexOf(':', at + marker.length)
    if (colon < 0) return null

    var index = colon + 1
    while (index < body.length && body[index].isWhitespace()) index++

    val start = index
    if (index < body.length && (body[index] == '-' || body[index] == '+')) index++
    while (index < body.length && body[index].isDigit()) index++
    if (index == start) return null
    return body.substring(start, index).toLongOrNull()
}

private const val HEX = "0123456789ABCDEF"

/** Lowercased tag used by the diagnostics and the Settings row. */
internal fun MalConnectionState.label(): String = when (this) {
    MalConnectionState.NotConfigured -> "not configured"
    MalConnectionState.Disconnected -> "not connected"
    MalConnectionState.Connected -> "connected"
    MalConnectionState.NeedsReconnect -> "needs reconnect"
}

/** Normalizes a MAL media type for logging/filters without a Locale default. */
internal fun malMediaTypeLabel(raw: String?): String =
    raw.orEmpty().trim().lowercase(Locale.ROOT)
