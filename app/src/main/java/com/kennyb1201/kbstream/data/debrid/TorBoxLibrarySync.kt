package com.kennyb1201.kbstream.data.debrid

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.library.LibraryMirror
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchTitleResult

/**
 * Mirrors the viewer's TorBox cloud into their KBStream Library.
 *
 * With a TorBox API key entered and the "Add TorBox Cloud to Library" toggle
 * on, every torrent in the account's TorBox library is resolved against TMDB
 * and added to My List — the same place "Add to Library" from any other screen
 * puts a title, so it also mirrors to any connected tracker. Off (the default)
 * and without a key this does nothing at all.
 *
 * It is best-effort by design. A release name is not metadata, so a torrent
 * whose name cannot be parsed, or that names no title TMDB matches confidently,
 * is skipped rather than guessed; [TorBoxCloudStore] records it so the next
 * round does not look it up again. A title already in My List is a no-op (the
 * store's own de-dupe), so re-running is safe.
 */
internal object TorBoxLibrarySync {

    private const val TAG = "TORBOX_LIBRARY"

    /** What one round did: how many torrents were seen, how many were added. */
    data class Report(val scanned: Int, val added: Int)

    /**
     * One sync round. Returns a zero report — and does no network work — when
     * the toggle is off or no key is set, which is the common case.
     */
    suspend fun sync(context: Context): Report {
        if (!AppPreferences.getTorboxLibrarySync(context)) return Report(0, 0)
        if (AppPreferences.getTorboxApiKey(context).isBlank()) return Report(0, 0)

        val torrents = TorBoxClient.cloudTorrents(context)
        if (torrents.isEmpty()) return Report(0, 0)

        val store = TorBoxCloudStore(context)
        val handled = store.processed()
        val repository = TmdbRepository.getInstance(context)
        var added = 0

        for (torrent in torrents) {
            // Already dealt with in an earlier round: skip before spending a
            // TMDB search on it.
            if (torrent.id in handled) continue

            val parsed = TorBoxCloudRules.parseName(torrent.name)
            if (parsed == null) {
                store.markProcessed(torrent.id)
                continue
            }

            val candidates = searchCandidates(repository, torrent.name, parsed)
            if (candidates == null) {
                // A FAILED search, not an empty one. TMDB being unreachable (an
                // outage, an offline box) says nothing about whether this title
                // exists, so it must not touch the give-up budget: that budget
                // is IRREVERSIBLE - `markProcessed` never expires and
                // `processed()` is consulted before searching - so three bad
                // rounds would have dropped every unmatched torrent for good,
                // even after TMDB came back. The torrent simply stays unrecorded
                // and is looked at again next round.
                Log.w(TAG, "TMDB search failed for '${torrent.name}'; leaving it for the next round")
                continue
            }
            if (candidates.isEmpty()) {
                // A genuine no-match, which is a statement about the title. It is
                // retried for [TorBoxCloudRules.EMPTY_SEARCH_ATTEMPTS] rounds and
                // then recorded as handled: leaving it unrecorded spent a TMDB
                // search on it on every launch and every schedule tick, forever.
                val attempts = store.noteEmptySearch(torrent.id)
                if (!TorBoxCloudRules.retriesEmptySearch(attempts)) {
                    Log.i(
                        TAG,
                        "giving up on '${torrent.name}' after $attempts empty searches"
                    )
                    store.markProcessed(torrent.id)
                }
                continue
            }
            // The name resolves, so any earlier empty-search streak is over.
            store.clearEmptySearch(torrent.id)

            val match = TorBoxCloudRules.bestMatch(parsed, candidates)
            if (match == null) {
                store.markProcessed(torrent.id)
                continue
            }

            val imdbId = runCatchingCancellable {
                repository.resolveImdbId(match.tmdbId, match.mediaType)
            }.getOrNull()

            val wasAdded = runCatchingCancellable {
                LibraryMirror.addToLibrary(
                    context = context,
                    mediaType = match.mediaType,
                    imdbId = imdbId,
                    tmdbId = match.tmdbId,
                    title = match.title,
                    year = match.year,
                    posterUrl = match.posterPath?.let { TmdbRepository.POSTER_BASE + it }
                )
            }.onFailure { Log.w(TAG, "add failed for '${torrent.name}': ${it.message}") }
                .getOrDefault(false)

            if (wasAdded) added++
            store.markProcessed(torrent.id)
        }

        Log.i(TAG, "TorBox library sync: ${torrents.size} seen, $added added")
        return Report(scanned = torrents.size, added = added)
    }

    /**
     * TMDB search results for [parsed], mapped into [TorBoxCloudRules.Candidate],
     * or NULL when a search could not be made at all.
     *
     * The null is the whole point: the repository's search methods answer
     * `emptyList()` for "nothing matched" and for "the request failed" alike
     * (they collapse every failure through `getOrDefault`), and the two need
     * opposite treatment here - a no-match may be given up on, a failure may
     * not. Catching at the call site is what keeps that distinction without
     * changing a search API the rest of the app reads as a plain list.
     *
     * A name with a season/episode marker is a series, so only the TV search
     * runs; anything else is searched as both a movie and a show, because a
     * release name alone cannot tell the two apart.
     */
    private suspend fun searchCandidates(
        repository: TmdbRepository,
        rawName: String,
        parsed: TorBoxCloudRules.ParsedName
    ): List<TorBoxCloudRules.Candidate>? {
        val query = parsed.title

        if (TorBoxCloudRules.looksLikeSeries(rawName)) {
            val shows = runCatchingCancellable { repository.searchTv(query) }.getOrNull()
                ?: return null
            return shows.map { it.toCandidate("series") }
        }

        val movies = runCatchingCancellable { repository.searchMovies(query) }.getOrNull()
            ?: return null
        val shows = runCatchingCancellable { repository.searchTv(query) }.getOrNull()
            ?: return null
        return movies.map { it.toCandidate("movie") } + shows.map { it.toCandidate("series") }
    }

    private fun TmdbSearchTitleResult.toCandidate(mediaType: String): TorBoxCloudRules.Candidate =
        TorBoxCloudRules.Candidate(
            mediaType = mediaType,
            tmdbId = id,
            title = title ?: name ?: "",
            year = (releaseDate ?: firstAirDate)?.substringBefore('-')?.toIntOrNull(),
            posterPath = posterPath,
            voteAverage = voteAverage
        )
}
