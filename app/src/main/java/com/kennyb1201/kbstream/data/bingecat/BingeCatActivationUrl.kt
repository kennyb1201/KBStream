package com.kennyb1201.kbstream.data.bingecat

/**
 * The URL the BingeCat connect screen's QR must carry: the activation page WITH
 * the code shown beside it.
 *
 * Trakt's and Simkl's activation pages are a page, and a code the viewer types
 * into it. BingeCat's is the same page with an optional `user_code` query
 * parameter (8 characters, `^[A-Z0-9]{8}$`), and that difference is the whole
 * report behind this: on the TV, typing the code at `bingecat.com/activate`
 * failed twice with `invalid_user_code`, while the direct
 * `?user_code=XXXXXXXX` link approved first time. A sparse QR of the bare page
 * throws away the code it is printed next to, so the scan has to carry it.
 *
 * Idempotent by design: a URL that already names a code (a server-provided
 * `verification_url`, now or after a BingeCat change) is passed through
 * untouched - two `user_code` parameters would be a different, wrong request.
 */
internal fun bingecatActivationUrlForQr(
    verificationUrl: String,
    userCode: String
): String {
    val url = verificationUrl.trim()
    val code = userCode.trim()
    if (url.isEmpty() || code.isEmpty()) return url
    if (carriesUserCode(url)) return url
    val separator = when {
        '?' !in url -> "?"
        // Already an empty/just-opened query string: the parameter starts it.
        url.endsWith("?") || url.endsWith("&") -> ""
        else -> "&"
    }
    return url + separator + "user_code=" + code
}

/** Whether [url]'s query already names `user_code`, whatever its value. */
private fun carriesUserCode(url: String): Boolean {
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return false
    return query.split('&').any { pair ->
        pair.substringBefore('=').trim() == "user_code"
    }
}
