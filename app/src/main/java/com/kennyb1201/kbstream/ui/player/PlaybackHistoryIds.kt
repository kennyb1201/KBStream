package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.player.EpisodeScheme
import com.kennyb1201.kbstream.data.player.SchemeKind
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.domain.streamengine.EpisodeMatch
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
            runCatchingCancellable {
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
     *
     * The parse itself is [EpisodeMatch.requestedFrom], which is also what the
     * source picker and the ranker read: whether a file is the episode asked
     * for is answered from this same pair, so there is one reading of "what
     * does this id name" rather than two that could drift apart.
     */
    fun episodeFromId(id: String?): Pair<Int, Int>? = EpisodeMatch.requestedFrom(id)

    /**
     * The session's season/episode corrected from the FILE the id names, or null
     * when the fields already name an episode that file holds.
     *
     * This is the repair the watchdog line ([playbackSessionLine]) deliberately
     * is not, and the reason it is safe is that the two statements are not
     * equals. The session's `season`/`episode` are a PREDICTION: they are carried
     * from the launch intent, which the previous session's arithmetic computed.
     * The stream id is what was RESOLVED and is now PLAYING, and the detected
     * scheme turns that file number into its exact TMDB episode(s)
     * ([EpisodeScheme.tmdbEpisodesOfFile]) - a mapping, not a guess. On a season
     * that mixes 1:1 and sp2 files (Paw Patrol S06) the prediction drifts while
     * the id stays right, so the wrong episode gets the watched marker, the
     * scrobble and the resume point. When they disagree, the prediction lost to
     * reality and the file wins.
     *
     * Only a genuine MISMATCH is repaired: when [EpisodeScheme.fileHolds] is
     * true - a consistent 1:1 show, the second half of a split episode, any file
     * of an fe group - this returns null and the session is byte-identical to
     * its old self. A null id or a null episode is likewise left alone. The
     * returned pair carries the id's season too, because the id is the same
     * statement of the season the episode came from.
     *
     * Called once at session start, before the history id and the
     * [playbackSessionLine] note are built, so the row, the line and everything
     * downstream (filing, scrobbling, the next-episode handoff) see the file's
     * episode. After it runs the line reads "id agrees", which is the check.
     *
     * Logged with the scheme, so a report shows not just that a correction
     * happened but which mapping was read to make it.
     */
    fun correctedSessionEpisode(
        season: Int?,
        episode: Int?,
        episodeStreamId: String?,
        scheme: EpisodeScheme = EpisodeScheme.ONE_TO_ONE
    ): Pair<Int, Int>? {
        val fromId = episodeFromId(episodeStreamId) ?: return null
        val sessionEpisode = episode ?: return null
        val (idSeason, idFileEp) = fromId
        if (scheme.fileHolds(idFileEp, sessionEpisode)) return null
        val corrected = scheme.tmdbEpisodesOfFile(idFileEp).firstOrNull() ?: return null
        if (corrected == sessionEpisode && idSeason == season) return null
        if (corrected != sessionEpisode) {
            val schemeLabel = scheme.encode() ?: "1:1"
            Log.w(
                TAG,
                "session s=$season e=$sessionEpisode corrected to e=$corrected " +
                    "from file $idFileEp (scheme $schemeLabel)"
            )
        }
        return idSeason to corrected
    }

    /**
     * One diagnostics line naming a session's identity, and whether the id its
     * stream was resolved for agrees with the fields it will file itself under.
     *
     * The comparison is SCHEME-AWARE, and it has to be: a session's fields name
     * a TMDB episode while its stream id names the FILE that holds it, which on
     * a segmented show (Paw Patrol: two TMDB episodes per file) are different
     * numbers on purpose. Asking the id's number against the raw episode called
     * every correctly-mapped session of such a show a MISMATCH — a watchdog
     * that cries wolf on the design it exists to protect. [scheme] is the
     * session's own detected mapping, so the question asked here is the one that
     * matters: does the file the id names actually HOLD this episode
     * ([EpisodeScheme.fileHolds])? Only when it does not is something wrong.
     *
     * Under [SchemeKind.ONE_TO_ONE] - no scheme, the state every session starts
     * in - this reduces to the strict equality it always was, so nothing about
     * the verdict changes for a show with no mapping.
     *
     * Still a report and not itself a repair - this reads whatever the session
     * now holds. The repair is [correctedSessionEpisode], run once at session
     * start from the file the id names; this line is then its verification, so
     * an 'id agrees' verdict after a correction is exactly the intended outcome.
     */
    fun playbackSessionLine(
        season: Int?,
        episode: Int?,
        episodeStreamId: String?,
        historyId: String,
        scheme: EpisodeScheme = EpisodeScheme.ONE_TO_ONE
    ): String {
        val base = "s=${season ?: "-"} e=${episode ?: "-"} row=$historyId"
        val fromId = episodeFromId(episodeStreamId) ?: return "$base id=$episodeStreamId"
        val agrees = fromId.first == season &&
            (episode == null || scheme.fileHolds(fromId.second, episode))
        return if (!agrees) {
            "$base id says s=${fromId.first} e=${fromId.second} - MISMATCH"
        } else if (scheme.kind == SchemeKind.ONE_TO_ONE) {
            "$base id agrees (s=${fromId.first} e=${fromId.second})"
        } else {
            // Name the mapping, so a correct split episode is not read as an
            // off-by-one: "file 16 holds e=8" is the answer to the question
            // the raw numbers cannot answer (sp = a file holding several,
            // fe = several files holding one - see EpisodeScheme.encode).
            "$base id agrees (file ${fromId.second} holds e=$episode, scheme ${scheme.encode()})"
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
            runCatchingCancellable {
                val repository = TmdbRepository.getInstance(context)
                val resolved = repository
                    .fetchEnrichedMetaCached(parentId, parentType)
                    ?.id
                // Leave the IMDB<->TMDB pair in the resolution cache. This is
                // how a session that played the title under its "tt..." id
                // makes the title's "tmdb:<n>" twin resolvable later - offline,
                // from that disk table - which is what lets a route under the
                // other flavor find the history row it wrote (see
                // PlaybackResume.savedPositionMs). The enriched-meta cache this
                // reads does not record the pair itself, so without this a
                // tt-first playback left the "tmdb:<n>" direction to a network
                // resolve that a device with no TMDB key can never complete.
                if (resolved != null && recordsResolution(parentId, resolved)) {
                    repository.recordResolution(parentId, resolved, parentType)
                }
                resolved
            }.getOrNull()
        }
    }
}

/**
 * Whether a resolved pair belongs in the TMDB resolution cache: a real IMDB
 * parent ("tt...") matched to a positive TMDB id. Anything else - an addon id
 * TMDB could not match, a synthetic -1 sentinel - is nothing to remember, and
 * recording it would forge an "tt..."->-1 mapping every later lookup trusts.
 */
internal fun recordsResolution(imdbId: String, tmdbId: Int?): Boolean =
    tmdbId != null && tmdbId > 0 && imdbId.trim().startsWith("tt")

