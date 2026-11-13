package com.kennyb1201.kbstream.data.remote

import android.content.Context
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the TV does with a "Play on TV" id, once it has looked the title up. */
internal data class RemotePlayPlan(
    val id: String,
    val mediaType: String,
    /** The resume row, when the title has progress to continue from. */
    val resume: RemoteResume? = null
)

internal data class RemoteResume(
    val streamId: String?,
    val title: String?,
    val episodeTitle: String?,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val poster: String?
)

/**
 * The one-shot "play this on the TV" hand-off (spec 2026-11-13, F3).
 *
 * The server resolves the title (it has the database and the media type), then
 * offers a plan here; the running MainActivity collects it and routes through
 * the SAME Screen.Detail + pendingTarget path a Continue Watching card press
 * uses - so the TV resolves add-ons, picks the source and resumes from the
 * stored playhead exactly as it does on its own. The phone sends an id and the
 * TV does the rest, which is the whole contract.
 *
 * A StateFlow rather than a callback because the activity may not be composed
 * when the offer arrives (the app is in the background, or the process was kept
 * alive by this very server): the value waits for the collector instead of being
 * lost.
 */
internal object RemotePlayBus {

    private val state = MutableStateFlow<RemotePlayPlan?>(null)

    val plans: StateFlow<RemotePlayPlan?> = state.asStateFlow()

    fun offer(plan: RemotePlayPlan) {
        state.value = plan
    }

    fun current(): RemotePlayPlan? = state.value

    /** The activity has routed this plan (or a newer one replaced it). */
    fun consume(plan: RemotePlayPlan? = null) {
        val current = state.value ?: return
        if (plan == null || current === plan || current.id == plan.id) state.value = null
    }

    /**
     * Looks a title up for "Play on TV".
     *
     * Only the resume row is resolved here. Everything else - which add-on holds
     * the file, which source is picked, the actual playback - stays on the TV's
     * own path, which is why a title the phone knows by id needs nothing else.
     */
    suspend fun resolve(
        context: Context,
        id: String,
        mediaType: String?
    ): RemotePlayPlan? {
        val parentId = id.trim().takeIf { it.isNotEmpty() } ?: return null
        val dao = WatchHistoryDatabase.getInstanceScoped(context).watchHistoryDao()
        val row = runCatching { dao.getResumeForParents(listOf(parentId)) }.getOrNull()
        // The phone names the type; when it does not, the row it is resuming
        // knows, and only then does the id decide.
        val type = when {
            mediaType?.trim()?.lowercase() == "movie" -> "movie"
            mediaType?.trim()?.lowercase() in setOf("series", "tv", "show") -> "series"
            row?.type?.equals("movie", ignoreCase = true) == true -> "movie"
            else -> "series"
        }
        val resume = row?.let {
            RemoteResume(
                streamId = it.episodeStreamId,
                title = it.name,
                episodeTitle = it.episodeTitle,
                season = it.season,
                episode = it.episode,
                positionMs = it.positionMs,
                poster = it.poster
            )
        }
        return RemotePlayPlan(id = parentId, mediaType = type, resume = resume)
    }
}
