package com.kennyb1201.kbstream.data.cast

/**
 * What the phone may send to a Cast device, and what it must refuse to - the
 * rules half of the cast sender, free of Android and of the SDK so the verdicts
 * are unit-testable (the transport is [CastSender]).
 *
 * The receiver is Play's DEFAULT media receiver (CC1AD845), and that single
 * decision is what most of these verdicts are about: it takes a URL and fetches
 * it itself, from the television's own connection. It cannot carry the app's
 * HTTP headers, it cannot answer a licence challenge, and it cannot mint a
 * token that was signed for this phone's address. A source that needs any of
 * those would fail on the TV with a black screen and no explanation, which is
 * exactly the state the spec asks to be shown honestly instead.
 *
 * Verdicts, in the order they are decided:
 *
 *  - [CastRefusal.NOT_A_PHONE] - the sender is phone-only. A television that
 *    somehow ran this build does nothing at all here.
 *  - [CastRefusal.KIDS_PROFILE] - the same lock the External engine is under
 *    (audit C8): both time limits are enforced from the app's own player
 *    activities, so a session playing on the television would silently escape
 *    bedtime and the daily budget. See PlayerEngine.externalAllowedForProfile.
 *  - [CastRefusal.NO_DIRECT_URL] - a torrent/infoHash stream, or a link that
 *    never resolved. There is nothing for a receiver to fetch.
 *  - [CastRefusal.DRM_PROTECTED] - the licence is ours to request; the default
 *    receiver has no way to.
 *  - [CastRefusal.UNSUPPORTED_SCHEME] - not http(s).
 *  - [CastRefusal.HEADER_PROTECTED] - the host gates on a Referer / Cookie /
 *    User-Agent the receiver cannot send.
 *  - [CastRefusal.ADDRESS_BOUND] - the URL carries an address-bound token, so
 *    it will be refused by the host when a different device asks for it.
 */
internal enum class CastRefusal {
    NOT_A_PHONE,
    KIDS_PROFILE,
    NO_DIRECT_URL,
    DRM_PROTECTED,
    UNSUPPORTED_SCHEME,
    HEADER_PROTECTED,
    ADDRESS_BOUND
}

/** Everything the sender needs to load one title onto the default receiver. */
internal data class CastPlan(
    val url: String,
    val contentType: String,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    /** Where the television should start, from the phone's resume point. */
    val startPositionMs: Long
)

internal sealed interface CastVerdict {
    data class Castable(val plan: CastPlan) : CastVerdict
    data class Refused(val reason: CastRefusal) : CastVerdict
}

/**
 * The verdict for one launch.
 *
 * Deliberately takes plain values rather than a [com.kennyb1201.kbstream.data.addon.Stream]
 * and a profile: the decision is the thing worth pinning, and a test that has
 * to build an addon's stream to ask it would be testing the JSON model too.
 */
internal fun castVerdict(
    url: String?,
    headers: Map<String, String>?,
    drmProtected: Boolean,
    torrentOnly: Boolean,
    title: String,
    subtitle: String?,
    posterUrl: String?,
    startPositionMs: Long,
    phone: Boolean,
    kidsProfile: Boolean
): CastVerdict {
    if (!phone) return CastVerdict.Refused(CastRefusal.NOT_A_PHONE)
    if (kidsProfile) return CastVerdict.Refused(CastRefusal.KIDS_PROFILE)
    if (torrentOnly) return CastVerdict.Refused(CastRefusal.NO_DIRECT_URL)
    if (drmProtected) return CastVerdict.Refused(CastRefusal.DRM_PROTECTED)
    val link = url?.trim().orEmpty()
    if (link.isEmpty()) return CastVerdict.Refused(CastRefusal.NO_DIRECT_URL)
    if (!link.lowercase().startsWith("http://") && !link.lowercase().startsWith("https://")) {
        return CastVerdict.Refused(CastRefusal.UNSUPPORTED_SCHEME)
    }
    if (!headers.isNullOrEmpty()) return CastVerdict.Refused(CastRefusal.HEADER_PROTECTED)
    if (addressBound(link)) return CastVerdict.Refused(CastRefusal.ADDRESS_BOUND)
    return CastVerdict.Castable(
        CastPlan(
            url = link,
            contentType = castContentType(link),
            title = title,
            subtitle = subtitle,
            posterUrl = posterUrl,
            startPositionMs = startPositionMs.coerceAtLeast(0L)
        )
    )
}

/**
 * Whether the sender exists for this box at all - the phone-only gate every
 * entry point asks before it offers a device picker.
 *
 * Kept beside the verdicts so "cast is a phone feature" is one rule with one
 * expression, the way PlayerEngine.externalAllowedForProfile is for the
 * external engine. A kids profile is refused by [castVerdict] as well: this is
 * the OFFER, that is the LAUNCH.
 */
internal fun castSenderAllowed(phone: Boolean, kidsProfile: Boolean): Boolean =
    phone && !kidsProfile

/**
 * The media type the receiver should use, from the link itself.
 *
 * The default receiver picks its player from this string, so an HLS link
 * announced as progressive MP4 plays for a few seconds and stops. The query is
 * ignored: providers hang tokens and filenames off it, and `.m3u8?token=...`
 * is an HLS link.
 */
internal fun castContentType(url: String): String {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".m3u8") || path.endsWith(".m3u") -> "application/x-mpegURL"
        path.endsWith(".mpd") -> "application/dash+xml"
        path.endsWith(".webm") -> "video/webm"
        path.endsWith(".mkv") -> "video/x-matroska"
        else -> "video/mp4"
    }
}

/**
 * Query keys providers use to bind a signed URL to the address that asked for
 * it. A link carrying one of these was signed for THIS phone, and the receiver
 * - a different device on a different connection - is exactly what the
 * signature is there to refuse.
 *
 * A marker list rather than a guess at the signature's shape: the names are the
 * ones that say what they are, and an unfamiliar signature is left to fail on
 * the television rather than refused here for the wrong reason.
 */
private val ADDRESS_BOUND_KEYS = setOf(
    "ip",
    "ipaddr",
    "ip_address",
    "client_ip",
    "clientip",
    "remote_ip",
    "remoteip",
    "x_forwarded_for",
    "user_ip",
    "userip"
)

/** Whether [url]'s query names the caller's address see [ADDRESS_BOUND_KEYS]. */
internal fun addressBound(url: String): Boolean {
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return false
    return query.split('&').any { pair ->
        val key = pair.substringBefore('=').trim().lowercase()
        key in ADDRESS_BOUND_KEYS
    }
}
