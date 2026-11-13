package com.kennyb1201.kbstream.data.kitsu

/**
 * One profile's Kitsu session (spec 2026-11-13 phase 2, F2).
 *
 * Kept apart from the store and the network so the expiry question is answerable
 * on the JVM: [expiresAtMs] is already skewed backwards by
 * [KitsuConfig.EXPIRY_SKEW_MS], so "expired" here means "too close to call".
 *
 * [viewerId] is Kitsu's own user id, learned at connect. It is not a credential -
 * it is the `filter[userId]` every library write is addressed by - and it is
 * stored beside the tokens because there is no other place that survives a
 * restart and a sync.
 */
internal data class KitsuToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val viewerId: String? = null,
    val viewerName: String? = null
) {
    fun isUsable(nowMs: Long): Boolean = accessToken.isNotBlank() && expiresAtMs > nowMs
}

/**
 * What one Kitsu write did, in the only four shapes the caller acts on.
 *
 * [parkedUntilMs] is set only for [RateLimited]: Kitsu answers 429 with a
 * `Retry-After`, and every write until then is a wasted request that also keeps
 * the leg hot. [NotFound] exists separately because a PATCH against a deleted
 * entry is the ONE way a cached entry id goes stale - it means "forget the id",
 * not "the write failed for a reason worth retrying".
 */
internal sealed interface KitsuWriteOutcome {
    data object Success : KitsuWriteOutcome

    /** 401 after a refresh attempt, or a dead grant: stop until reconnect. */
    data object NeedsReconnect : KitsuWriteOutcome

    /** The cached library entry no longer exists; re-resolve on the next write. */
    data object EntryGone : KitsuWriteOutcome

    data class RateLimited(val parkedUntilMs: Long) : KitsuWriteOutcome

    /** A transport error, a 5xx or an unreadable body. Nothing to act on. */
    data class Failed(val reason: String) : KitsuWriteOutcome
}

/**
 * The pure half of the Kitsu write path: what to send, in what shape, and how to
 * read the answer.
 *
 * Every decision here is testable without a socket, which is the point: the
 * JSON:API bodies have three rules that are easy to get subtly wrong (a PATCH
 * must carry `type` AND `id`, the rating is a 0-20 scale, and status values are
 * Kitsu's own spellings, not KBStream's), and a wrong body is a 400 the viewer
 * never sees.
 */
internal object KitsuWriteRules {

    /**
     * KBStream's title status → Kitsu's.
     *
     * Kitsu spells "watching" as `current` and "plan to watch" as `planned`;
     * everything else is the same word. An unknown status answers null, which
     * means "send no status" - never a guess.
     */
    fun statusFor(appStatus: String?): String? = when (appStatus?.trim()?.lowercase()) {
        "watching", "current", "rewatching" -> "current"
        "completed" -> "completed"
        "on_hold", "onhold", "paused" -> "on_hold"
        "dropped" -> "dropped"
        "plan_to_watch", "planned", "planning" -> "planned"
        else -> null
    }

    /**
     * KBStream's 1-10 score → Kitsu's `ratingTwenty` (0-20, half-star steps).
     *
     * A score outside 1-10 is not a rating this app has, so it answers null
     * rather than clamping into a value the viewer never chose.
     */
    fun ratingTwenty(score: Int?): Int? {
        val value = score ?: return null
        if (value < 1 || value > 10) return null
        return value * 2
    }

    /**
     * The status a progress write should ALSO send, or null to leave it alone.
     *
     * Reaching the episode total completes the title; anything short of it keeps
     * the entry `current`. The total is often unknown (a continuing show, an
     * add-on with no metadata), and then nothing is completed - a show is never
     * "finished" because its count was missing.
     */
    fun progressStatus(progress: Int, totalEpisodes: Int?): String? {
        val total = totalEpisodes ?: return "current"
        if (total <= 0) return "current"
        return if (progress >= total) "completed" else "current"
    }

    /** The authenticated entry lookup: one title, the fields a write needs. */
    fun entryLookupUrl(userId: String, kitsuAnimeId: String): String =
        KitsuConfig.API_BASE +
            "/library-entries?filter[userId]=" + urlEncode(userId) +
            "&filter[kind]=anime" +
            "&filter[animeId]=" + urlEncode(kitsuAnimeId) +
            "&fields[libraryEntries]=status,progress,ratingTwenty"

    /**
     * A new library entry: the user and the anime are relationships, not
     * attributes, and Kitsu rejects the create body without both.
     */
    fun createBody(
        userId: String,
        kitsuAnimeId: String,
        status: String?,
        progress: Int?,
        ratingTwenty: Int?
    ): String = buildString {
        append("""{"data":{"type":"libraryEntries","attributes":{""")
        val attributes = ArrayList<String>(3)
        status?.let { attributes += """"status":${jsonString(it)}""" }
        progress?.let { attributes += """"progress":$it""" }
        ratingTwenty?.let { attributes += """"ratingTwenty":$it""" }
        append(attributes.joinToString(","))
        append("""},"relationships":{""")
        append(""""user":{"data":{"type":"users","id":${jsonString(userId)}}},""")
        append(""""media":{"data":{"type":"anime","id":${jsonString(kitsuAnimeId)}}}""")
        append("}}}")
    }

    /**
     * An update. The body MUST carry `type` and `id` - a PATCH without them is a
     * 400 - and only the attributes being changed.
     */
    fun patchBody(
        entryId: String,
        status: String?,
        progress: Int?,
        ratingTwenty: Int?
    ): String = buildString {
        append("""{"data":{"type":"libraryEntries","id":${jsonString(entryId)},"attributes":{""")
        val attributes = ArrayList<String>(3)
        status?.let { attributes += """"status":${jsonString(it)}""" }
        progress?.let { attributes += """"progress":$it""" }
        ratingTwenty?.let { attributes += """"ratingTwenty":$it""" }
        append(attributes.joinToString(","))
        append("}}}")
    }

    /**
     * The body that takes a rating back.
     *
     * JSON:API cannot express "no change" and "set to null" in the same field, so
     * a cleared rating needs a body of its own: an explicit `null` is what tells
     * Kitsu to remove the score rather than leave it.
     */
    fun clearRatingBody(entryId: String): String =
        """{"data":{"type":"libraryEntries","id":${jsonString(entryId)},""" +
            """"attributes":{"ratingTwenty":null}}}"""

    /**
     * The password grant's form.
     *
     * The password must be RFC3986-encoded: Kitsu's docs say so explicitly,
     * because a `+` or an `&` in a real password otherwise truncates the field.
     * [clientId] is added only when this build has one.
     */
    fun passwordForm(email: String, password: String, clientId: String): String {
        val fields = ArrayList<String>(4)
        fields += "grant_type=password"
        fields += "username=" + urlEncode(email.trim())
        fields += "password=" + urlEncode(password)
        if (clientId.isNotBlank()) fields += "client_id=" + urlEncode(clientId)
        return fields.joinToString("&")
    }

    /** The refresh grant's form; same client id rule as the password grant. */
    fun refreshForm(refreshToken: String, clientId: String): String {
        val fields = ArrayList<String>(3)
        fields += "grant_type=refresh_token"
        fields += "refresh_token=" + urlEncode(refreshToken)
        if (clientId.isNotBlank()) fields += "client_id=" + urlEncode(clientId)
        return fields.joinToString("&")
    }

    /**
     * How long a 429 parks the leg.
     *
     * `Retry-After` may be seconds or an HTTP date; seconds is what Kitsu sends.
     * An absent or unreadable header parks for a conservative minute - retrying
     * sooner is what turns a soft limit into a hard one.
     */
    fun parkMs(retryAfterHeader: String?, nowMs: Long): Long {
        val seconds = retryAfterHeader?.trim()?.toLongOrNull()
        val wait = seconds?.takeIf { it > 0L }?.let { it * 1_000L } ?: DEFAULT_PARK_MS
        return nowMs + wait.coerceIn(MIN_PARK_MS, MAX_PARK_MS)
    }

    const val DEFAULT_PARK_MS = 60_000L
    const val MIN_PARK_MS = 1_000L
    const val MAX_PARK_MS = 60 * 60 * 1_000L

    /**
     * Percent-encoding for query values and form fields, done here so the
     * builders stay JVM-testable (no `android.net.Uri`).
     */
    fun urlEncode(value: String): String {
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

    /** A JSON string literal, quoted and escaped. */
    fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        value.forEach { ch ->
            when (ch) {
                '"', '\\' -> {
                    out.append('\\')
                    out.append(ch)
                }
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') {
                    out.append("\\u")
                    out.append(String.format("%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}

/**
 * The four answers the Kitsu row can carry (spec 2026-11-13 phase 2, F2).
 *
 * [NeedsReconnect] is the honest one and the reason this is an enum rather than a
 * boolean: a profile whose refresh failed holds a session that LOOKS connected
 * and can write nothing, and saying "Connected" there is the bug BingeCat's
 * connect row was fixed for. The copy lives here so the phone row, the TV row and
 * the dialog cannot drift apart.
 */
internal enum class KitsuConnectionState(val label: String) {
    NotConfigured("Not available in this build"),
    Disconnected("Not connected"),
    Connected("Connected"),
    NeedsReconnect("Reconnect needed")
}
