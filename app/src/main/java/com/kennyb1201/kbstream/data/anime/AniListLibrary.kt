package com.kennyb1201.kbstream.data.anime

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * AniList's list writes (spec 2026-11-13 phase 2, F3).
 *
 * GraphQL, one endpoint, three operations:
 *
 *  - `SaveMediaListEntry(mediaId: …)` creates the entry when the viewer has none
 *    and updates it when they do,
 *  - `DeleteMediaListEntry(id: …)` removes it (by ENTRY id, never by media id),
 *  - `Media(id: …) { mediaListEntry { id } }` resolves which of the two a title
 *    needs, once per title per process.
 *
 * WHY THE LOOKUP COMES FIRST. The save alone could do the create-or-update, but
 * then it could not know which it is - and the one thing that must not be sent on
 * an update is a start date, because that would overwrite the day the viewer
 * actually started. The lookup also gives the entry id a delete needs, so the
 * first write of a title is a GET+save and every later one is a save.
 *
 * NO REFRESH. A 401 marks the connection needs-reconnect and stops: AniList has
 * no refresh grant, so there is nothing to retry with. 429 parks the shared
 * client (see [AniListClient]) and every write until then returns immediately,
 * because a write inside a 429 window only extends it.
 *
 * A failure takes down only AniList: every caller gets an outcome and no
 * exception leaves this file.
 */
internal object AniListLibrary {

    private const val TAG = "AniListLibrary"

    /** mediaId → list-entry id. The write that normally follows is an update. */
    private val entryIds = ConcurrentHashMap<Int, Int>()

    /** Test hook: forgets every cached entry id. */
    internal fun resetForTest() {
        entryIds.clear()
    }

    /**
     * The session this profile writes under just changed (the cloud applier
     * adopted a sibling's login, or a pulled tombstone signed it out).
     *
     * The cached list-entry ids belong to the PREVIOUS account, and there is no
     * re-create backstop on the delete path: a `DeleteMediaListEntry` carrying a
     * stale id can DELETE the wrong account's entry outright. Clearing here is
     * the fix; the next write resolves its own id again.
     */
    fun onSyncedSessionChanged() {
        entryIds.clear()
    }

    /** True when the shared client is parked after a 429. */
    fun parked(nowMs: Long = System.currentTimeMillis()): Boolean = AniListClient.parked(nowMs)

    /**
     * A watched episode: the authoritative count, not an increment.
     *
     * The status follows the count ([AniListWriteRules.progressStatus]): COMPLETED
     * at the episode total, CURRENT before it, CURRENT when the total is unknown.
     * `startedAt`/`completedAt` are added by the rules - a start date only on the
     * write that creates the entry, a completion date on the one that finishes it.
     */
    suspend fun pushProgress(
        context: Context,
        mediaId: Int,
        progress: Int,
        totalEpisodes: Int? = null
    ): AniListWriteOutcome = write(
        context = context,
        mediaId = mediaId,
        status = AniListWriteRules.progressStatus(progress, totalEpisodes),
        progress = progress,
        scoreRaw = null
    )

    /**
     * A title-level rating, as `scoreRaw` (0-100) so it never depends on the
     * viewer's AniList score format.
     */
    suspend fun pushRating(
        context: Context,
        mediaId: Int,
        score: Int
    ): AniListWriteOutcome {
        val raw = AniListWriteRules.scoreRaw(score) ?: return AniListWriteOutcome.Success
        return write(
            context = context,
            mediaId = mediaId,
            status = null,
            progress = null,
            scoreRaw = raw
        )
    }

    /**
     * Taking a rating back.
     *
     * AniList has no "null score" input, but `scoreRaw: 0` is its own
     * unscored value - so a cleared KBStream signal removes the score instead of
     * leaving the old one standing under a title the viewer just un-rated.
     */
    suspend fun clearRating(context: Context, mediaId: Int): AniListWriteOutcome = write(
        context = context,
        mediaId = mediaId,
        status = null,
        progress = null,
        scoreRaw = 0
    )

    /** A title status change, by KBStream's own spelling. */
    suspend fun pushStatus(
        context: Context,
        mediaId: Int,
        appStatus: String
    ): AniListWriteOutcome {
        val status = AniListWriteRules.statusFor(appStatus) ?: return AniListWriteOutcome.Success
        return write(
            context = context,
            mediaId = mediaId,
            status = status,
            progress = null,
            scoreRaw = null
        )
    }

    /**
     * Removing a title from the viewer's list.
     *
     * A DELETE, not a `DROPPED` status: dropping says the viewer gave up on the
     * title, which is a statement about the show, while removing says they never
     * wanted it on the list. A title with no entry has nothing to remove.
     */
    suspend fun remove(context: Context, mediaId: Int): AniListWriteOutcome {
        val token = AniListAuth.accessToken(context) ?: return AniListWriteOutcome.NeedsReconnect

        val entryId = entryIds[mediaId] ?: when (val lookup = lookupEntryId(context, mediaId, token)) {
            is Lookup.Found -> lookup.id
            is Lookup.Absent -> return AniListWriteOutcome.Success
            is Lookup.Unauthorized -> return AniListWriteOutcome.NeedsReconnect
            is Lookup.Parked -> return AniListWriteOutcome.RateLimited(lookup.untilMs)
            is Lookup.Failed -> return AniListWriteOutcome.Failed(lookup.reason)
        }

        val variables = JSONObject().put("id", entryId)
        val outcome = when (
            val answer = AniListClient.authed(
                query = AniListWriteRules.DELETE_MUTATION,
                variables = variables,
                accessToken = token
            )
        ) {
            is AniListAnswer.Ok -> AniListWriteOutcome.Success
            is AniListAnswer.Unauthorized -> AniListWriteOutcome.NeedsReconnect
            is AniListAnswer.Parked -> AniListWriteOutcome.RateLimited(answer.untilMs)
            is AniListAnswer.Failed -> AniListWriteOutcome.Failed(answer.reason)
        }

        when (outcome) {
            is AniListWriteOutcome.Success,
            is AniListWriteOutcome.EntryGone -> entryIds.remove(mediaId)
            else -> Unit
        }
        if (outcome is AniListWriteOutcome.NeedsReconnect) {
            AniListAuth.markNeedsReconnect(context, "delete refused")
        }
        return outcome
    }

    /**
     * One write: resolve whether the entry exists, then save.
     *
     * A lookup that FAILS is not a create. Creating on a failed read would put a
     * duplicate entry on the viewer's list the moment AniList had a bad minute,
     * so the write fails instead and the next attempt resolves it properly.
     */
    private suspend fun write(
        context: Context,
        mediaId: Int,
        status: String?,
        progress: Int?,
        scoreRaw: Int?
    ): AniListWriteOutcome {
        if (mediaId <= 0) return AniListWriteOutcome.Success
        if (parked()) return AniListWriteOutcome.Success

        val token = AniListAuth.accessToken(context) ?: return AniListWriteOutcome.NeedsReconnect

        var creating = false
        if (entryIds[mediaId] == null) {
            when (val lookup = lookupEntryId(context, mediaId, token)) {
                is Lookup.Found -> entryIds[mediaId] = lookup.id
                is Lookup.Absent -> creating = true
                is Lookup.Unauthorized -> return AniListWriteOutcome.NeedsReconnect
                is Lookup.Parked -> return AniListWriteOutcome.RateLimited(lookup.untilMs)
                is Lookup.Failed -> return AniListWriteOutcome.Failed(lookup.reason)
            }
        }

        val variables = AniListWriteRules.saveVariables(
            mediaId = mediaId,
            status = status,
            progress = progress,
            scoreRaw = scoreRaw,
            creating = creating,
            nowMs = System.currentTimeMillis()
        )

        return when (
            val answer = AniListClient.authed(
                query = AniListWriteRules.SAVE_MUTATION,
                variables = variables,
                accessToken = token
            )
        ) {
            is AniListAnswer.Ok -> {
                // The mutation answers the entry's id; keeping it is what makes
                // the next write (and a later delete) a single call.
                answer.json.optJSONObject("data")
                    ?.optJSONObject("SaveMediaListEntry")
                    ?.optInt("id", 0)
                    ?.takeIf { it > 0 }
                    ?.let { entryIds[mediaId] = it }
                AniListWriteOutcome.Success
            }
            is AniListAnswer.Unauthorized -> {
                AniListAuth.markNeedsReconnect(context, "write refused")
                AniListWriteOutcome.NeedsReconnect
            }
            is AniListAnswer.Parked -> AniListWriteOutcome.RateLimited(answer.untilMs)
            is AniListAnswer.Failed -> AniListWriteOutcome.Failed(answer.reason)
        }
    }

    /** The entry id a title has (or has not), cached. */
    private suspend fun lookupEntryId(
        context: Context,
        mediaId: Int,
        token: String
    ): Lookup {
        entryIds[mediaId]?.let { return Lookup.Found(it) }

        val variables = JSONObject().put("mediaId", mediaId)
        return when (
            val answer = AniListClient.authed(
                query = AniListWriteRules.ENTRY_ID_QUERY,
                variables = variables,
                accessToken = token
            )
        ) {
            is AniListAnswer.Ok -> {
                val media = answer.json.optJSONObject("data")?.optJSONObject("Media")
                if (media == null) {
                    Lookup.Failed("no Media answer")
                } else {
                    val id = media.optJSONObject("mediaListEntry")
                        ?.optInt("id", 0)
                        ?.takeIf { it > 0 }
                    if (id == null) {
                        Lookup.Absent
                    } else {
                        entryIds[mediaId] = id
                        Lookup.Found(id)
                    }
                }
            }
            is AniListAnswer.Unauthorized -> {
                AniListAuth.markNeedsReconnect(context, "entry lookup refused")
                Lookup.Unauthorized
            }
            is AniListAnswer.Parked -> Lookup.Parked(answer.untilMs)
            is AniListAnswer.Failed -> {
                Log.w(TAG, "AniList entry lookup failed for $mediaId: ${answer.reason}")
                Lookup.Failed(answer.reason)
            }
        }
    }

    /** What the entry lookup found. */
    private sealed interface Lookup {
        data class Found(val id: Int) : Lookup
        data object Absent : Lookup
        data object Unauthorized : Lookup
        data class Parked(val untilMs: Long) : Lookup
        data class Failed(val reason: String) : Lookup
    }
}
