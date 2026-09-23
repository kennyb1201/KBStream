package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The identifiers one playback session records itself under.
 *
 * Both engines write the same rows — a title started in ExoPlayer and continued
 * in MPV after a decoder failure must land on ONE Continue Watching card, with
 * one playhead. That only works if the history row's id and the row's
 * `parentId` column are derived the same way on both paths, so this logic lives
 * in one place instead of once per player.
 */
object PlaybackHistoryIds {

    private const val TAG = "PLAYER_IDS"

    /**
     * Row id for the current session. An episode's own stream id wins (it names
     * the exact episode), then the show + season/episode pair, then the bare
     * title for a movie.
     */
    fun historyId(
        parentId: String,
        season: Int?,
        episode: Int?,
        episodeStreamId: String?
    ): String = when {
        !episodeStreamId.isNullOrBlank() -> episodeStreamId
        season != null && episode != null -> "$parentId:$season:$episode"
        else -> parentId
    }

    /**
     * The parent id the playback-history row should be stored under.
     *
     * A title is reachable as "tt..." (add-on catalogs, Continue Watching) and
     * as "tmdb:<n>" (TMDB search rows, the kids rails), and a history row was
     * written under whichever flavor started playback. Continue Watching groups
     * rows by parentId in SQL, so the SAME title ended up as TWO cards — one per
     * flavor — with progress on only one of them. Rows are canonicalized to the
     * IMDB id when it can be resolved; anything unresolvable (no TMDB key, a
     * timeout) stays as the route's own id.
     *
     * Bounded on purpose: this runs on the player's exit path (and, for the MPV
     * engine, in front of the fallback write), so a slow resolve must never hold
     * a history write hostage.
     */
    suspend fun canonicalParentId(
        context: Context,
        rawParentId: String,
        parentType: String,
        resolvedTmdbId: Int? = null
    ): String {
        val raw = rawParentId.trim()
        if (raw.isBlank() || raw.startsWith("tt")) return raw

        val tmdbId = resolvedTmdbId?.takeIf { it > 0 }
            ?: when {
                raw.startsWith("tmdb:") || raw.all(Char::isDigit) ->
                    raw.removePrefix("tmdb:").toIntOrNull()

                else -> resolveTmdbId(context, raw, parentType)
            }
        if (tmdbId == null || tmdbId <= 0) return raw

        val imdb = withTimeoutOrNull(2500L) {
            runCatching {
                TmdbRepository.getInstance(context)
                    .resolveImdbId(tmdbId, parentType)
                    ?.trim()
                    ?.takeIf { it.startsWith("tt") }
            }.getOrNull()
        }
        if (imdb == null) {
            Log.w(TAG, "could not canonicalize $raw to an IMDB id; keeping the route's own id")
        }
        return imdb ?: raw
    }

    /**
     * TMDB id for a parent regardless of the raw id flavor (imdb / tmdb: /
     * tvdb: / bare numeric). Simkl and MDBList can only match shows/movies by
     * imdb or tmdb id, so TVDB-sourced titles scrobble via this resolved id
     * instead of being silently dropped.
     */
    suspend fun resolveTmdbId(
        context: Context,
        parentId: String,
        parentType: String
    ): Int? {
        if (parentId.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                TmdbRepository.getInstance(context)
                    .fetchEnrichedMetaCached(parentId, parentType)
                    ?.id
            }.getOrNull()
        }
    }
}
