package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.player.bundledEpisodeNumbers

/**
 * Whether an add-on's row is the KIND OF FILE this query asked for.
 *
 * Reported: South Park S29E4 — a wrong stored episode scheme made the picker ask
 * the add-ons for S29E13, and Flix-Streams answered with five copies of the 1999
 * film, `South.Park.Bigger.Longer.and.Uncut.1999…`, 17.4–20.6 GB. Autoplay
 * played one. Nothing in the picker asked whether a row was an episode at all:
 * [EpisodeMatch] asks whether a row names the requested EPISODE, and a film
 * names no episode, so it answered [EpisodeMatch.Verdict.UNKNOWN] — the
 * deliberately playable tier — and the movie went out as the best guess.
 *
 * So this is the second, independent question, asked where the rows arrive and
 * nowhere else:
 *
 *  - does a row that names no episode, pins no season, and is too big for one
 *    episode to be, get offered as an episode? No — it is dropped.
 *  - does a row that names a season and an episode get offered as a film? No —
 *    it is dropped.
 *
 * The three verdicts below are the whole vocabulary, and [Kind.UNKNOWN] is the
 * one that must stay inviolable: most add-on rows carry no explicit type (the
 * row model has no type field at all — see `Stream`), and an unlabeled episode
 * file is the commonest shape on every add-on. Treating the unprovable as wrong
 * would break autoplay for every show whose releases are simply unnamed, which
 * is exactly the conservatism [EpisodeMatch] states for its own verdicts.
 *
 * Every signal is read from the row's own text and size — the fields
 * [EpisodeMatch] already reads — plus the query. No network, no TMDB, no addon
 * metadata: none of those exists at these call sites, and the Detail screen's
 * own wrong-type guard (TMDB's `/find` answering the id under the other type)
 * is a different question about a different input (the title, not the row).
 */
object RowMediaType {

    /**
     * What a row IS — a property of the row read against the query, not a
     * verdict about keeping it. [guard] decides what to drop: a film is wrong
     * for an episode query, an episode file is wrong for a film query, and
     * [UNKNOWN] is right for both.
     */
    enum class Kind {
        /** Proved to be a film: no episode, no pack, too big for one episode. */
        MOVIE,

        /** Proved to hold an episode: it declares a season and an episode. */
        EPISODE,

        /** Nothing proved either way. Never dropped, for either query. */
        UNKNOWN
    }

    /**
     * A guarded hand: the rows that survived, and what the guard refused.
     *
     * The two counts are separate because the picker says which rule fired
     * ("did not match this title" is one sentence for both) while the
     * diagnostics line counts them apart, so a capture can tell a film-shaped
     * answer from an episode-shaped one.
     */
    data class Guarded(
        val kept: List<Stream>,
        val droppedMovie: Int,
        val droppedEpisode: Int
    ) {
        val dropped: Int get() = droppedMovie + droppedEpisode
    }

    /**
     * Whether this request asks for an episode: an episode-numbered id, or a
     * series query. The id is the authority — a series browse that carries no
     * episode number (a show-level listing) still answers episode-shaped rows.
     */
    fun isEpisodeQuery(contentType: String?, streamId: String?): Boolean =
        EpisodeMatch.requestedFrom(streamId) != null || contentType == "series"

    /**
     * Whether this request asks for a film — the mirror rule, and deliberately
     * narrow: a film is a `movie` query with no episode in its id.
     */
    fun isMovieQuery(contentType: String?, streamId: String?): Boolean =
        contentType == "movie" && EpisodeMatch.requestedFrom(streamId) == null

    /**
     * What [stream] is, read against the query. See the class comment for the
     * rule; the two verdicts are conjunctions and nothing else may drop a row.
     */
    fun classify(
        stream: Stream,
        contentType: String?,
        streamId: String?,
        runtimeMinutes: Int?
    ): Kind {
        val declaresEpisode = EpisodeMatch.declaresEpisode(stream)

        if (isMovieQuery(contentType, streamId)) {
            // A film legitimately released with an `S01E01` in its name is the
            // accepted cost of this arm; it is rarer than the episode-query
            // failure, and NO SIZE can prove anything here (a film's runtime
            // supports feature-sized files), so a film query never answers
            // MOVIE at all — everything is an episode declaration or unknown.
            return if (declaresEpisode) Kind.EPISODE else Kind.UNKNOWN
        }

        if (!isEpisodeQuery(contentType, streamId)) return Kind.UNKNOWN

        // A full declaration proves the row holds an episode, whatever its
        // size. A bare "E04" proves nothing and falls through.
        if (declaresEpisode) return Kind.EPISODE

        // The pack signal is the row NAMING one: a row that pins a season and
        // never names an episode of it holds the episode, so it cannot be a
        // film. EpisodeMatch.isSeasonPack is deliberately NOT consulted — with
        // a size and a runtime its answer IS the density test below, so folding
        // it in here would make the movie verdict unreachable (every row the
        // density catches would save itself).
        val packSignal = EpisodeMatch.hasSeasonMarker(stream)

        // The movie verdict needs all three: no episode declaration, no pack
        // signal, and a size that cannot be one episode of the requested
        // length. Drop one of them and the row is unknown, which is KEPT.
        val tooBigForOneEpisode = EpisodeMatch.exceedsEpisodeDensity(stream, runtimeMinutes)

        return if (packSignal || !tooBigForOneEpisode) Kind.UNKNOWN else Kind.MOVIE
    }

    /**
     * [streams] with every proved-wrong-typed row removed, and the refused
     * counts.
     *
     * The kept rows are what every consumer downstream must see — the merged
     * list, the per-add-on tabs, the ranker, and autoplay's own pick, which take
     * the head of this same hand. A guard the manual picker saw but autoplay did
     * not would not have stopped the field failure: the film was taken by the
     * resolve path, not by a press.
     *
     * A row that names an episode is KEPT on an episode query — it may be the
     * wrong episode, and that is [EpisodeMatch]'s and the ranker's question, not
     * this one. Only the provable mismatch is dropped, in either direction.
     */
    fun guard(
        streams: List<Stream>,
        contentType: String?,
        streamId: String?,
        runtimeMinutes: Int?
    ): Guarded {
        val movieQuery = isMovieQuery(contentType, streamId)
        var droppedMovie = 0
        var droppedEpisode = 0
        val kept = ArrayList<Stream>(streams.size)
        for (stream in streams) {
            when (classify(stream, contentType, streamId, runtimeMinutes)) {
                Kind.MOVIE -> if (movieQuery) kept += stream else droppedMovie++
                Kind.EPISODE -> if (movieQuery) droppedEpisode++ else kept += stream
                Kind.UNKNOWN -> kept += stream
            }
        }
        return Guarded(kept = kept, droppedMovie = droppedMovie, droppedEpisode = droppedEpisode)
    }

    /**
     * Why a file that just played must NOT be committed as its show's
     * numbering scheme, or null when it may.
     *
     * The scheme store is global and its last write wins, which is what healed
     * the field incident (one correct file re-detects and clears a poisoned
     * entry). It is also how the poison got in: detection commits whatever
     * [EpisodeScheme.detect] returns for whatever file happened to be playing.
     * This is the guard on that commit, and it is a SKIP — never a substitution,
     * because it knows nothing the detector does not.
     *
     * Two refusals, both from [Kind] and the bundle name:
     *
     *  - the playing row is movie-typed on an episode session. The field case's
     *    second-order harm: the 1999 film, 81 minutes against a 22-minute
     *    episode runtime, reads as a 4x scheme and would overwrite one wrong
     *    scheme with another.
     *  - the file name's bundle does not hold this session's episode: a
     *    statement about another file is not evidence about this one, and the
     *    name is trusted over the ratio exactly because it is a statement about
     *    the file. Deliberately CONTAINMENT rather than "starts at": a session
     *    entered at E2 of a `S01E01E02` bundle is a session on the right file
     *    (Paw Patrol's second segment, a split episode's second half), and
     *    refusing it would leave a genuinely segmented show unable to learn its
     *    scheme from the one file the viewer actually opened.
     *
     * [stream] is the launch's own row for what is playing; null (a bare
     * `stream_url` launch) skips the type refusal — there is no row to read, and
     * "no row" is not evidence.
     */
    fun schemeCommitRefusal(
        stream: Stream?,
        contentType: String?,
        streamId: String?,
        runtimeMinutes: Int?,
        sessionEpisode: Int?,
        fileName: String?
    ): String? {
        if (stream != null && classify(stream, contentType, streamId, runtimeMinutes) == Kind.MOVIE) {
            return "playing row is movie-typed"
        }
        val bundle = bundledEpisodeNumbers(fileName)
        if (bundle != null && sessionEpisode != null && sessionEpisode !in bundle) {
            return "bundle holds E${bundle.first()}-E${bundle.last()}, session is E$sessionEpisode"
        }
        return null
    }
}
