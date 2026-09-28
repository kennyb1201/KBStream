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
     * The season/episode a Stremio episode id names, or null when the id
     * carries none.
     *
     * A series video id is "<show id>:<season>:<episode>" — the show part is
     * whatever the addon calls the show ("tt...", "kitsu:..." — anything), so
     * the pair is the last two colon-separated segments, and only when both of
     * them are numbers. A movie id, a show-level id, or anything else answers
     * null, which is why the caller must treat null as "the id does not say"
     * rather than "the id says nothing is there".
     *
     * This exists because the id and the session's own `season`/`episode` are
     * two independent statements of the same fact and the app never compared
     * them: the id is what the stream was RESOLVED for, while the fields are
     * what the session RECORDS itself under — the watch-history row, the
     * watched marker, the Simkl scrobble, and the arithmetic next episode all
     * read the fields. When the two disagree, a session plays one episode and
     * files it as another, which looks like "the binge kept offering an episode
     * I had already watched, and the ones it played were never marked".
     */
    fun episodeFromId(id: String?): Pair<Int, Int>? {
        val parts = id?.trim()?.split(':') ?: return null
        if (parts.size < 3) return null
        val episode = parts[parts.size - 1].toIntOrNull() ?: return null
        val season = parts[parts.size - 2].toIntOrNull() ?: return null
        return season to episode
    }

    /**
     * One diagnostics line naming a session's identity, and whether the id its
     * stream was resolved for agrees with the fields it will file itself under.
     *
     * Deliberately a report and not a repair: rewriting one from the other would
     * be guessing which is wrong, and the whole question is which one is.
     */
    fun playbackSessionLine(
        season: Int?,
        episode: Int?,
        episodeStreamId: String?,
        historyId: String
    ): String {
        val base = "s=${season ?: "-"} e=${episode ?: "-"} row=$historyId"
        val fromId = episodeFromId(episodeStreamId) ?: return "$base id=$episodeStreamId"
        val agrees = fromId.first == season && fromId.second == episode
        return if (agrees) {
            "$base id agrees (s=${fromId.first} e=${fromId.second})"
        } else {
            "$base id says s=${fromId.first} e=${fromId.second} - MISMATCH"
        }
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
