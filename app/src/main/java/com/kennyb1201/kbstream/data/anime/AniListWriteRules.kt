package com.kennyb1201.kbstream.data.anime

import org.json.JSONObject

/**
 * One profile's AniList session (spec 2026-11-13 phase 2, F1).
 *
 * AniList issues no refresh token - an access token is a JWT good for about a
 * year - so this is the whole of a session: the token, the deadline taken from
 * the JWT's own `exp` claim (or a year, when the claim cannot be read) and the
 * viewer's AniList id/name, which are stored only so the settings row can say
 * who is connected.
 */
internal data class AniListToken(
    val accessToken: String,
    val expiresAtMs: Long,
    val viewerId: Int? = null,
    val viewerName: String? = null
) {
    fun isUsable(nowMs: Long): Boolean = accessToken.isNotBlank() && expiresAtMs > nowMs
}

/**
 * What one AniList write did, in the shapes the caller acts on.
 *
 * There is no [AniListWriteOutcome] refresh state on purpose: AniList has no
 * refresh grant, so a 401 is final until the viewer pastes a new token. That is
 * the one structural difference from Kitsu's write outcome.
 */
internal sealed interface AniListWriteOutcome {
    data object Success : AniListWriteOutcome

    /** 401/403, or no usable session: stop until the viewer reconnects. */
    data object NeedsReconnect : AniListWriteOutcome

    /** The cached list entry no longer exists; re-resolve before the next delete. */
    data object EntryGone : AniListWriteOutcome

    data class RateLimited(val parkedUntilMs: Long) : AniListWriteOutcome

    /** A transport error, a 5xx or a GraphQL `errors` envelope. */
    data class Failed(val reason: String) : AniListWriteOutcome
}

/**
 * The four answers the AniList row can carry, with their copy (F1).
 *
 * [NeedsReconnect] is the honest one: a token that has passed its `exp` (or that
 * a 401 declared dead) still LOOKS like a connected session, and calling that
 * "Connected" while every write silently fails is the bug BingeCat's row was
 * fixed for. The copy lives here so the row and the dialog cannot drift.
 */
internal enum class AniListConnectionState(val label: String) {
    NotConfigured("Not available in this build"),
    Disconnected("Not connected"),
    Connected("Connected"),
    NeedsReconnect("Reconnect needed")
}

/**
 * The pure half of AniList's write path (spec 2026-11-13 phase 2, F3).
 *
 * Everything here is decided without a socket, because the three things that are
 * easy to get subtly wrong are all decisions rather than plumbing:
 *
 *  - **the score**: AniList's `score` field is in the viewer's chosen format
 *    (POINT_10, POINT_100, POINT_5, POINT_3, ...), so sending 8 there is 8 out of
 *    whatever they picked. `scoreRaw` is the format-independent 0-100 value, so a
 *    KBStream 8/10 is `scoreRaw: 80` and needs no knowledge of their setting;
 *  - **the status**: KBStream's five states map onto AniList's enum, and an
 *    unknown one sends nothing rather than a guess;
 *  - **create versus update**: `SaveMediaListEntry(mediaId: ...)` creates the
 *    entry when it does not exist, so no lookup is needed - but a CREATE is the
 *    only moment a start date may be sent, because sending one on every update
 *    would overwrite the date the viewer actually started on.
 */
internal object AniListWriteRules {

    /** How long a 429 parks the leg when `Retry-After` is missing or unreadable. */
    const val DEFAULT_PARK_MS = 60_000L

    /**
     * The authorize URL, now the AUTHORIZATION CODE grant (spec 2026-11-14).
     *
     * `response_type=code` and the app's own redirect - not the auth-pin page and
     * not `response_type=token`. The implicit grant this used to build is gone at
     * AniList's end (the endpoint answers `unsupported_grant_type` for it), which
     * is why the connect now needs the bundled secret; [AniListConnectRules] owns
     * the flow's shape and this stays here as the one entry point the rest of the
     * write path already knows.
     *
     * A blank client id answers null: there is no usable URL, and a connect row
     * that offered one would be a dead end.
     */
    fun authorizeUrl(clientId: String, state: String? = null): String? =
        AniListConnectRules.authorizeUrl(clientId, state)

    /** KBStream's title status → AniList's `MediaListStatus`. */
    fun statusFor(appStatus: String?): String? = when (appStatus?.trim()?.lowercase()) {
        "watching", "current" -> "CURRENT"
        "completed" -> "COMPLETED"
        "on_hold", "onhold", "paused" -> "PAUSED"
        "dropped" -> "DROPPED"
        "plan_to_watch", "planned", "planning" -> "PLANNING"
        "rewatching", "repeating" -> "REPEATING"
        else -> null
    }

    /**
     * KBStream's 1-10 score → AniList's format-independent `scoreRaw` (0-100).
     *
     * A score outside 1-10 is not a rating this app has, so it answers null
     * rather than clamping into a value the viewer never chose.
     */
    fun scoreRaw(score: Int?): Int? {
        val value = score ?: return null
        if (value < 1 || value > 10) return null
        return value * 10
    }

    /**
     * The status a progress write should send.
     *
     * Reaching the episode total completes the title; anything short of it keeps
     * the entry `CURRENT`. A show whose episode total is unknown (a continuing
     * series, a title with no metadata) is never completed because its count was
     * missing.
     */
    fun progressStatus(progress: Int, totalEpisodes: Int?): String {
        val total = totalEpisodes ?: return "CURRENT"
        if (total <= 0) return "CURRENT"
        return if (progress >= total) "COMPLETED" else "CURRENT"
    }

    /** True when a progress write is the one that finishes the title. */
    fun completes(progress: Int, totalEpisodes: Int?): Boolean =
        progressStatus(progress, totalEpisodes) == "COMPLETED"

    /**
     * The mutation. It is `SaveMediaListEntry`, which creates the entry when the
     * viewer has none - so a first episode needs no separate "does it exist".
     *
     * The return selection is deliberately the three fields the caller uses and
     * nothing else (the phase-1 `fields` discipline): the entry id (cached for a
     * later delete), the status and the progress.
     */
    const val SAVE_MUTATION =
        "mutation (\$mediaId: Int, \$status: MediaListStatus, \$progress: Int, " +
            "\$scoreRaw: Int, \$startedAt: FuzzyDateInput, \$completedAt: FuzzyDateInput) " +
            "{ SaveMediaListEntry(mediaId: \$mediaId, status: \$status, progress: \$progress, " +
            "scoreRaw: \$scoreRaw, startedAt: \$startedAt, completedAt: \$completedAt) " +
            "{ id status progress score } }"

    /** Removal takes the ENTRY id, never the media id. */
    const val DELETE_MUTATION =
        "mutation (\$id: Int) { DeleteMediaListEntry(id: \$id) { deleted } }"

    /** The entry id for a media id, for the delete that has no cached one. */
    const val ENTRY_ID_QUERY =
        "query (\$mediaId: Int) { Media(id: \$mediaId, type: ANIME) " +
            "{ mediaListEntry { id } } }"

    /** Who the token belongs to. The connect validates itself with this. */
    const val VIEWER_QUERY = "query { Viewer { id name } }"

    /**
     * A `FuzzyDateInput` for [nowMs] as AniList's own calendar day (UTC).
     *
     * AniList's dates are dates, not instants, so a local day that has already
     * rolled over would be recorded a day early in some zones if the device's
     * zone were used. The app sends the UTC day, which is what an episode
     * finished "today" means on a service with no timezone to speak of.
     */
    fun fuzzyDateToday(nowMs: Long): JSONObject {
        val day = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
        return JSONObject()
            .put("year", day.year)
            .put("month", day.monthValue)
            .put("day", day.dayOfMonth)
    }

    /**
     * The variables of one save.
     *
     * Nulls are omitted rather than sent: AniList treats an absent field as "leave
     * it alone", and a `null` in a non-null schema field is an error. [creating]
     * is the one thing that decides whether a start date travels - a title with no
     * cached entry is being created, and that is the only moment `startedAt` may
     * be written.
     */
    fun saveVariables(
        mediaId: Int,
        status: String?,
        progress: Int?,
        scoreRaw: Int?,
        creating: Boolean,
        nowMs: Long
    ): JSONObject {
        val variables = JSONObject().put("mediaId", mediaId)

        val effectiveStatus = when {
            status != null -> status
            creating -> "CURRENT"
            else -> null
        }
        effectiveStatus?.let { variables.put("status", it) }
        progress?.takeIf { it > 0 }?.let { variables.put("progress", it) }
        scoreRaw?.let { variables.put("scoreRaw", it) }

        if (creating) variables.put("startedAt", fuzzyDateToday(nowMs))
        if (effectiveStatus == "COMPLETED") {
            variables.put("completedAt", fuzzyDateToday(nowMs))
        }
        return variables
    }

    /**
     * The deadline of a JWT access token, from its own `exp` claim.
     *
     * The token is a JWT and the docs promise about a year, but the claim is the
     * authority: a token issued with a different lifetime, or one already close
     * to expiry, must not be treated as fresh. Anything unreadable falls back to
     * a year rather than to "expired", because refusing a token the service would
     * accept is the worse failure.
     */
    fun expiryFromToken(token: String, nowMs: Long): Long {
        val fallback = nowMs + ONE_YEAR_MS
        val parts = token.trim().split('.')
        if (parts.size < 2) return fallback

        val payload = runCatching {
            val decoded = java.util.Base64.getUrlDecoder().decode(parts[1])
            JSONObject(String(decoded, Charsets.UTF_8))
        }.getOrNull() ?: return fallback

        val expSeconds = payload.optLong("exp", 0L)
        if (expSeconds <= 0L) return fallback
        val expMs = expSeconds * 1_000L
        // A claim that is already past, or further out than a year, is not
        // trusted into a session: the first answer is a 401 and a reconnect.
        if (expMs <= nowMs) return nowMs
        return expMs.coerceAtMost(nowMs + ONE_YEAR_MS)
    }

    /**
     * How long a 429 parks the leg: `Retry-After` seconds, else a minute, clamped
     * to something a session can wait out.
     */
    fun parkMs(retryAfterHeader: String?, nowMs: Long): Long {
        val seconds = retryAfterHeader?.trim()?.toLongOrNull()
        val wait = seconds?.takeIf { it > 0L }?.let { it * 1_000L } ?: DEFAULT_PARK_MS
        return nowMs + wait.coerceIn(1_000L, 60 * 60 * 1_000L)
    }

    /** Percent-encoding for the authorize URL, done here so it stays JVM-testable. */
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

    private const val HEX = "0123456789ABCDEF"

    private const val ONE_YEAR_MS = 365L * 24L * 60L * 60L * 1_000L
}
