package com.kennyb1201.kbstream.data.reporting

/**
 * Best-effort credential redaction for the two places KBStream text leaves the
 * app in a form nobody curates before it is sent or shared: Sentry events and
 * the diagnostics export the user is asked to paste into a bug report.
 *
 * Both carry third-party messages verbatim, and two sources actually put
 * secrets in them:
 *
 *  - supabase-kt request exceptions embed the full request URL and headers,
 *    bearer token included. [SupabaseSync] already trims that to its first line
 *    for the on-screen error, but the raw Throwable is what reaches Sentry and
 *    the retained-error list in [Diagnostics].
 *  - IPTV playlist and EPG URLs. Xtream-style providers put the account
 *    username and password straight into the query string
 *    (`.../get.php?username=…&password=…`), and those URLs are logged.
 *
 * [text] removes the shapes a credential actually takes in those messages —
 * Authorization headers, sensitive query/header/JSON fields, URL user-info, and
 * bare JWTs — while leaving the rest of the message readable, so a report stays
 * diagnosable. It is a scrubber, not a guarantee: it can only mask values that
 * follow a recognizable key or that look like a token.
 */
object Redaction {

    const val MASK = "***"

    /**
     * Field names whose value is a credential, matched case-insensitively as a
     * whole word before `=` or `:` (and quoted forms, e.g. JSON).
     */
    private const val FIELDS =
        "username|user|usr|login|password|passwd|pwd|pass|pin|" +
            "token|access_token|refresh_token|id_token|apikey|api_key|api-key|" +
            "key|secret|client_secret|auth|authorization|session|sid"

    /** `Authorization: Bearer <token>`, and any bare `Bearer <token>`. */
    private val BEARER = Regex("(?i)\\b(bearer\\s+)[A-Za-z0-9\\-._~+/=]+")

    /** `scheme://user:pass@host` — credentials embedded in a URL authority. */
    private val USERINFO = Regex("([A-Za-z][A-Za-z0-9+.\\-]*://)[^/@\\s]+@")

    /**
     * A sensitive field and its value, covering query strings (`?username=x&…`),
     * headers (`apikey: x`) and JSON (`"access_token":"x"`). The value stops at
     * the first delimiter so the surrounding structure survives.
     */
    private val FIELD_VALUE = Regex(
        "(?i)([\"']?)\\b($FIELDS)\\b([\"']?\\s*[:=]\\s*[\"']?)([^&\\s\"',;}\\)\\]]+)"
    )

    /** A bare JWT: header.payload.signature. Supabase tokens are JWTs. */
    private val JWT = Regex("eyJ[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]*")

    fun text(input: String?): String {
        if (input.isNullOrEmpty()) return input.orEmpty()
        var out = input
        out = BEARER.replace(out) { "${it.groupValues[1]}$MASK" }
        out = USERINFO.replace(out) { "${it.groupValues[1]}$MASK@" }
        out = FIELD_VALUE.replace(out) { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] + MASK }
        out = JWT.replace(out, MASK)
        return out
    }

    /** Redacts a URL for logging/display. Same rules, named for intent. */
    fun url(input: String?): String = text(input)

    /**
     * Masks an email to its first character plus the domain — enough to tell
     * two accounts apart without naming one: `alice@example.com` →
     * `a***@example.com`. A value that is not an email is returned unchanged.
     */
    fun email(input: String?): String {
        val value = text(input).trim()
        val at = value.indexOf('@')
        if (at <= 0 || at == value.length - 1) return value
        return "${value.first()}$MASK${value.substring(at)}"
    }

    /**
     * A copy of [t] with its message scrubbed, for `Log.e(tag, msg, t)` sites
     * where the throwable's own message carries the URL. The stack trace is
     * preserved and no `cause` is attached, so the original (unredacted) message
     * does not reappear in a "Caused by:" line. Returns [t] unchanged when its
     * message is already clean, so untouched sites keep the real class.
     */
    fun throwable(t: Throwable): Throwable {
        val message = t.message
        val clean = text(message)
        if (clean == message) return t
        return RedactedThrowable("${t.javaClass.name}: $clean", t)
    }

    private class RedactedThrowable(
        message: String,
        original: Throwable
    ) : Exception(message) {
        init {
            stackTrace = original.stackTrace
        }
    }
}
