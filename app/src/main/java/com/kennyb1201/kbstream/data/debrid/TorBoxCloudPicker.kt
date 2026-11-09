package com.kennyb1201.kbstream.data.debrid

import android.content.Context
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchTitleResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TorBox cloud FILES in the stream picker.
 *
 * The picker has always known one way to reach the viewer's TorBox account: an
 * add-on hands over a torrent HASH, and the account is asked whether it holds
 * that hash (`TorBoxClient.checkCached` / `TorBoxResolve`). A file the viewer
 * downloaded straight into TorBox has no add-on row to match, so it is
 * invisible to every picker - which is the field report this exists for
 * (Landman S1E10, downloaded on 2026-11-08, no TorBox row anywhere).
 *
 * So this is the other direction: read the account's own torrent list, and turn
 * the files in it into picker rows for the title the viewer has open.
 *
 * Two rules keep it honest, both borrowed from the library sync
 * ([TorBoxCloudRules]):
 *
 *  - a torrent whose release name does not confidently name the picker's title
 *    is skipped, never guessed at;
 *  - the FILE is the authority on the episode. A season pack's torrent name
 *    says only "S01"; only a file inside it that names an episode earns a row,
 *    and only for that episode.
 *
 * Everything decidable without the network is a pure function here ([refOf],
 * [rowStream], [matches], [insertIndex]) so it is unit tested; [TorBoxCloudCache]
 * is the thin part that talks to the API.
 */
internal object TorBoxCloudPicker {

    /** The row's label: distinct from an add-on-matched TorBox row (F1b). */
    const val ROW_LABEL = "TorBox Cloud"

    /** What the one discreet offer row says when the feature is switched off. */
    const val OFFER_LABEL = "Show TorBox cloud file"

    /**
     * The scheme a cloud row's URL carries instead of a link.
     *
     * A row needs to name (torrent id, file id) and nothing else; the link is
     * only minted on the tap, through the same `requestdl` path an
     * add-on-matched row uses. Encoding the pair in the URL keeps the row a
     * plain [Stream] - the list, the ranker, the tabs and the player's source
     * hand-off all keep working without knowing what it is - and makes the
     * "is this one of ours" test a parse rather than a side table.
     */
    private const val SCHEME = "kbstream-torbox-cloud"

    /** One cloud file, as a play needs it: a torrent id and a file id. */
    data class CloudRef(val torrentId: Long, val fileId: Int)

    /** A file in the account that belongs to the title a picker is showing. */
    data class CloudMatch(
        val torrentId: Long,
        val fileId: Int,
        val torrentName: String,
        val fileName: String,
        val size: Long
    )

    /** The URL that carries [torrentId]/[fileId] on a cloud row. */
    fun refUrl(torrentId: Long, fileId: Int): String = "$SCHEME://$torrentId/$fileId"

    /**
     * The (torrent id, file id) [url] carries, or null for any other URL -
     * including a real link, so nothing can mistake a playable address for a
     * cloud reference.
     */
    fun refOf(url: String?): CloudRef? {
        val value = url?.trim() ?: return null
        val prefix = "$SCHEME://"
        if (!value.startsWith(prefix)) return null
        val parts = value.removePrefix(prefix).trim('/').split('/')
        if (parts.size != 2) return null
        val torrentId = parts[0].toLongOrNull() ?: return null
        val fileId = parts[1].toIntOrNull() ?: return null
        if (torrentId <= 0 || fileId < 0) return null
        return CloudRef(torrentId, fileId)
    }

    /** True when [stream] is a cloud row drawn by this feature. */
    fun isCloudRow(stream: Stream): Boolean = refOf(stream.url) != null

    /**
     * The picker row for [match]: labeled "TorBox Cloud", with the file's own
     * name as its subtitle so the viewer can confirm it is their file (F1b).
     *
     * The torrent's name is kept as the description only when it says something
     * the file name does not - a season pack, where "the release" is the pack
     * and the file is one episode of it.
     */
    fun rowStream(match: CloudMatch): Stream = Stream(
        name = ROW_LABEL,
        title = match.fileName,
        description = match.torrentName
            .takeIf { it.isNotBlank() && !it.equals(match.fileName, ignoreCase = true) },
        url = refUrl(match.torrentId, match.fileId)
    )

    /**
     * The files in [torrents] that belong to the title a picker is showing.
     *
     * [mediaType] is the picker's own (`series` / `movie`), [title] its heading
     * (see [TorBoxCloudRules.showTitleOf]) and [year] the title's year when the
     * caller knows it. Series matching is keyed on season+episode from the
     * episode's canonical identity, never on a displayed number, so the
     * Landman stored-scheme quirk cannot misroute a file.
     *
     * A torrent still downloading is skipped outright (F0e): the feature must
     * not add clutter, and a percent-complete row is clutter that cannot play.
     */
    fun matches(
        torrents: List<TorBoxClient.CloudTorrent>,
        mediaType: String,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?
    ): List<CloudMatch> {
        val wantedTitle = TorBoxCloudRules.showTitleOf(title)
        if (wantedTitle.isBlank()) return emptyList()
        if (mediaType == "series") {
            if (season == null || episode == null) return emptyList()
            return matchesEpisode(torrents, wantedTitle, year, season, episode)
        }
        return matchesMovie(torrents, wantedTitle, year)
    }

    /** Every cloud FILE that holds [episode] of [season] of [wantedTitle]. */
    private fun matchesEpisode(
        torrents: List<TorBoxClient.CloudTorrent>,
        wantedTitle: String,
        year: Int?,
        season: Int,
        episode: Int
    ): List<CloudMatch> {
        val found = mutableListOf<CloudMatch>()
        for (torrent in torrents) {
            if (!torrent.complete) continue
            val parsed = TorBoxCloudRules.parseName(torrent.name) ?: continue
            if (!TorBoxCloudRules.confidentMatch(parsed, wantedTitle, year)) continue
            // The shared rule, with the picker's own fallback kept: a picker has
            // to offer something to play for the open title even when a torrent
            // holds nothing the rule recognises.
            val files = torrent.files.filter { TorBoxCloudRules.isVideo(it.name) }
                .ifEmpty { torrent.files }
            for (file in files) {
                val span = TorBoxCloudRules.fileSpan(torrent.name, file.name)
                if (!TorBoxCloudRules.spanCovers(span, season, episode)) continue
                found += CloudMatch(
                    torrentId = torrent.id,
                    fileId = file.id,
                    torrentName = torrent.name,
                    fileName = file.name,
                    size = file.size
                )
            }
        }
        return found
    }

    /** The file a cloud torrent of [wantedTitle] holds, for a film's picker. */
    private fun matchesMovie(
        torrents: List<TorBoxClient.CloudTorrent>,
        wantedTitle: String,
        year: Int?
    ): List<CloudMatch> {
        val found = mutableListOf<CloudMatch>()
        for (torrent in torrents) {
            if (!torrent.complete) continue
            val parsed = TorBoxCloudRules.parseName(torrent.name) ?: continue
            if (!TorBoxCloudRules.confidentMatch(parsed, wantedTitle, year)) continue
            // A series release is not a film of the same name, however the
            // title alone reads.
            if (TorBoxCloudRules.looksLikeSeries(torrent.name)) continue
            val file = torrent.files
                .filter { TorBoxCloudRules.isVideo(it.name) }
                .maxByOrNull { it.size }
                ?: torrent.files.maxByOrNull { it.size }
                ?: continue
            found += CloudMatch(
                torrentId = torrent.id,
                fileId = file.id,
                torrentName = torrent.name,
                fileName = file.name,
                size = file.size
            )
        }
        return found
    }

    /**
     * True when [stream] is a row served by the viewer's TorBox account: the
     * add-on matched one (it carries the Cached chip, or names the service), a
     * cloud row, or a link that already points at it.
     *
     * Cloud rows are inserted directly after the last of these, so a filled
     * row sorts in the debrid tier but never outranks a row the add-on ranker
     * actually placed (F1c).
     */
    fun isTorBoxRow(stream: Stream): Boolean {
        if (isCloudRow(stream)) return true
        if (stream.badges.any { it.name.equals(TorBoxCachedBadges.BADGE_NAME, ignoreCase = true) }) {
            return true
        }
        return listOfNotNull(stream.name, stream.title, stream.description, stream.url)
            .any { it.contains("torbox", ignoreCase = true) }
    }

    /**
     * Where [rows] should take a cloud row: after the last TorBox row the
     * ranking produced, or at the head when there is none.
     *
     * Head-of-list is the honest answer for "no TorBox row today": the cloud
     * file is a completed file on the viewer's own account, which is the same
     * tier the ranker puts debrid-served links in, above every HTTP link. It
     * still cannot outrank a matched row, because then the index is that row's
     * own + 1.
     */
    fun insertIndex(rows: List<Stream>): Int {
        if (rows.isEmpty()) return 0
        val last = rows.indexOfLast { isTorBoxRow(it) }
        return if (last >= 0) last + 1 else 0
    }

    /** True when [rows] already hold a cloud row: a merge must not double it. */
    fun alreadyMerged(rows: List<Stream>): Boolean = rows.any { isCloudRow(it) }

    /** One cloud file's title, as TMDB knows it (the cloud screen's tap). */
    data class ResolvedTitle(val mediaType: String, val tmdbId: Int, val title: String)

    /**
     * The TMDB title a cloud release name names, or null when nothing is a
     * confident match (the sync's own bar - see [TorBoxCloudRules.bestMatch]).
     *
     * This is the cloud list's tap: a file that names a title and an episode
     * opens that episode's picker, and one that names only a title opens the
     * title. null is "could not tell" - TMDB being unreachable and a name no
     * title matches are the same answer here, because both mean the tap must not
     * guess.
     */
    suspend fun resolveTitle(context: Context, torrentName: String): ResolvedTitle? {
        val parsed = TorBoxCloudRules.parseName(torrentName) ?: return null
        val repository = TmdbRepository.getInstance(context)
        val candidates = if (TorBoxCloudRules.looksLikeSeries(torrentName)) {
            val shows = repository.searchTvOrNull(parsed.title) ?: return null
            shows.map { it.toCandidate("series") }
        } else {
            // A release name alone cannot tell a film from a show, so both are
            // searched - the same question the sync asks.
            val movies = repository.searchMoviesOrNull(parsed.title) ?: return null
            val shows = repository.searchTvOrNull(parsed.title) ?: return null
            movies.map { it.toCandidate("movie") } + shows.map { it.toCandidate("series") }
        }
        val match = TorBoxCloudRules.bestMatch(parsed, candidates) ?: return null
        return ResolvedTitle(match.mediaType, match.tmdbId, match.title)
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

    /**
     * [rows] as the PLAYER may receive them.
     *
     * A cloud row's URL is a reference, not a link: pressing it mints the link
     * first, and a list handed to the player still holding an un-pressed cloud
     * row's reference would give the player's own "next source" walk an address
     * it cannot open. Clearing the URL leaves the row in its place (so the
     * parallel add-on array stays aligned) and out of every player path that
     * filters for playable rows - the same treatment a hash row gets.
     */
    fun forPlayer(rows: List<Stream>): List<Stream> =
        rows.map { if (isCloudRow(it)) it.copy(url = null) else it }
}

/**
 * The account's cloud torrents, kept for [TTL_MS] so opening pickers does not
 * ask TorBox the same question once per episode.
 *
 * One `mylist` call per stale open AT MOST (F1d): [fresh] holds the lock across
 * the request and re-checks the timestamp once it has it, so two pickers opened
 * together share one call rather than racing two. A failed request is NOT
 * cached - an outage must not teach the app that the cloud is empty for the
 * next quarter hour - which is why the client's nullable read is used here.
 *
 * The library sync refreshes this on its launch + 12 h round
 * ([TorBoxLibrarySync]), because it has just read the same list.
 */
internal object TorBoxCloudCache {

    /** How long an answer is trusted (spec F1d). */
    const val TTL_MS = 15 * 60_000L

    private val lock = Mutex()
    private var entries: List<TorBoxClient.CloudTorrent>? = null
    private var atMs = 0L

    /** What is held and still fresh, with no network - for a synchronous merge. */
    @Synchronized
    fun snapshot(now: Long = System.currentTimeMillis()): List<TorBoxClient.CloudTorrent>? {
        val held = entries ?: return null
        return if (now - atMs < TTL_MS) held else null
    }

    /**
     * The account's torrents, from the cache when it is fresh or from one
     * `mylist` call otherwise. [force] is for the cloud screen's own refresh,
     * where the viewer asked to see the list as it stands.
     *
     * Answers the last held list (possibly empty) when the request fails, so a
     * caller that had rows keeps them rather than blanking the picker.
     */
    suspend fun fresh(
        context: Context,
        force: Boolean = false
    ): List<TorBoxClient.CloudTorrent> = lock.withLock {
        val now = System.currentTimeMillis()
        val held = entries
        if (!force && held != null && now - atMs < TTL_MS) return@withLock held
        val answer = TorBoxClient.cloudTorrentsOrNull(context)
        if (answer == null) return@withLock held.orEmpty()
        entries = answer
        atMs = System.currentTimeMillis()
        answer
    }

    /**
     * Files [torrents] as the held answer, already read by someone else.
     *
     * The library sync reads the same `mylist` on every launch and every 12 h
     * round, so its answer is handed over here instead of being asked for again
     * by the next picker (spec F1d).
     */
    @Synchronized
    fun seed(torrents: List<TorBoxClient.CloudTorrent>) {
        entries = torrents
        atMs = System.currentTimeMillis()
    }

    /** Forgets the held list, so the next open reads the account again. */
    @Synchronized
    fun invalidate() {
        entries = null
        atMs = 0L
    }
}
