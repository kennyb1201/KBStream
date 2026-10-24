package com.kennyb1201.kbstream.data.debrid

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.history.TitleProfileOwnership
import com.kennyb1201.kbstream.data.history.titleIdentityName
import com.kennyb1201.kbstream.data.history.titleOwnedByAnotherProfile
import com.kennyb1201.kbstream.data.history.titleProfileKey
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibraryMirror
import com.kennyb1201.kbstream.data.library.LibraryOrigin
import com.kennyb1201.kbstream.data.library.LibraryTombstones
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.sync.ProfileSession
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
 *
 * Nothing here is silent any more. Every exit — including the gates — files a
 * [Report] with the store and writes one log line naming the outcome, what the
 * round saw and how much of it was already handled, because "nothing appeared in
 * the Library" used to be the same absence whether the round was off, keyless,
 * empty, all-handled, or simply not matching anything.
 */
internal object TorBoxLibrarySync {

    private const val TAG = "TORBOX_LIBRARY"

    /**
     * How a round ended.
     *
     * The gates are outcomes too: a device with the switch off and a device with
     * no key both ran a round that could not do anything, and that is the fact
     * the Settings line has to be able to state instead of showing the previous
     * round's counts as if they were current.
     */
    enum class Outcome {
        DISABLED,
        NO_KEY,

        /**
         * The round ran with no profile LIVE in a session (see
         * [ProfileSession]): it may scan and report, but it writes nothing to
         * anyone's Library and marks nothing.
         */
        DEFERRED,
        CLOUD_EMPTY,
        COMPLETED
    }

    /**
     * What one round did: how many torrents were seen, how many were added, how
     * many were skipped because an earlier round had already handled them, how
     * many leftover rows the one-time cleanup took out of this profile's list,
     * and how it ended.
     *
     * [removed] is carried because it was the half of a round nobody could see:
     * "the cleanup ran" and "the cleanup removed nothing" were the same two
     * numbers on the Settings line, which is how a cleanup that attributed
     * nothing looked like one that had nothing to do.
     */
    data class Report(
        val scanned: Int,
        val added: Int,
        val handledSkips: Int = 0,
        /**
         * Titles this round would have added but for a My List removal record —
         * a title removed on purpose here or on another device. Counted so the
         * round's own log says why a cloud full of torrents added nothing.
         */
        val tombstoneSkips: Int = 0,
        val removed: Int = 0,
        val outcome: Outcome = Outcome.COMPLETED
    )

    /**
     * One sync round. Returns the report it filed — the gate exits included, and
     * without any network work when the toggle is off or no key is set, which is
     * the common case.
     */
    suspend fun sync(context: Context): Report {
        val store = TorBoxCloudStore(context)

        if (!AppPreferences.getTorboxLibrarySync(context)) {
            return file(store, Report(0, 0, outcome = Outcome.DISABLED))
        }
        if (AppPreferences.getTorboxApiKey(context).isBlank()) {
            return file(store, Report(0, 0, outcome = Outcome.NO_KEY))
        }

        // No adds without a LIVE profile. A round runs at launch, on a 12 h
        // schedule and on toggle/key changes - most of them with no session and
        // no chosen profile - and the library store resolves its partition at
        // WRITE time from whatever is ambient. That is how one shared household
        // TorBox account ended up mirrored into the profile that happened to be
        // active on a cold launch, kids' titles and all. A round with no live
        // profile is therefore a scan-only round: nothing written, nothing
        // marked, and the torrents are picked up by the first round that runs
        // inside a live session.
        val liveProfileId = ProfileSession.liveProfileId(context)
        if (liveProfileId == null) {
            Log.i(
                TAG,
                "TorBox library sync deferred: no profile is live in a session; " +
                    "nothing written, nothing marked"
            )
            return file(store, Report(0, 0, outcome = Outcome.DEFERRED))
        }

        // Marks written before the count fix are indistinguishable from good
        // ones, and one bad pre-fix round could have marked a whole cloud as
        // handled with nothing in the app able to revisit it. Cleared once per
        // profile per device, before anything is read, so nothing has to be
        // found by hand to recover from a bug that is already fixed.
        if (store.forgetPreCountingFixMarksOnce()) {
            Log.i(TAG, "one-time mark reset for this build: every cloud torrent is re-checked once")
        }

        val torrents = TorBoxClient.cloudTorrents(context)
        if (torrents.isEmpty()) {
            return file(store, Report(0, 0, outcome = Outcome.CLOUD_EMPTY))
        }

        // The one-time cleanup of an older build's cross-profile adds, before
        // this round adds anything of its own. Its removals are part of the
        // round's report, so the Settings line can say they happened.
        val removed = remediateOwnership(context, store, liveProfileId, torrents)

        // Read AFTER the cleanup, on purpose: the rows it just removed are
        // tombstoned as it removes them, and this round's add pass must not put
        // them straight back. That is the field's "removed 81, offered 81
        // again", and it is also how a Resync (which resets every processed
        // mark) can re-walk the whole cloud without re-imposing what another
        // device decided to remove.
        val tombstones = LocalLibraryStore.tombstones(context)

        val handled = store.processed()
        // One snapshot for the whole round: the ownership map only grows when
        // this device watches something, and a round is a second of work.
        val owners = TitleProfileOwnership.snapshot(context)
        val repository = TmdbRepository.getInstance(context)
        var added = 0
        var skips = 0
        var tombstoneSkips = 0

        for (torrent in torrents) {
            // Already dealt with in an earlier round: skip before spending a
            // TMDB search on it. Counted, because "129 already handled" is the
            // difference between a caught-up library and a broken round.
            if (torrent.id in handled) {
                skips++
                continue
            }

            val parsed = TorBoxCloudRules.parseName(torrent.name)
            if (parsed == null) {
                // Marked on SIGHT, not after retries, and deliberately: the
                // input is immutable and the parser deterministic, so the next
                // round would decide exactly the same thing. Its revisit path is
                // the Settings "Resync TorBox cloud" row, which is what makes
                // this asymmetry a choice - if the parser ever learns a shape it
                // refused here, a Resync looks at the name again.
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
                // Searched fine, matched nothing confidently: the same
                // uncertainty as an empty result, so the same budget. One round
                // used to be enough to lose a title TMDB simply had not indexed
                // (or spelled differently) that day.
                val attempts = store.noteNoMatch(torrent.id)
                if (!TorBoxCloudRules.retriesNoMatch(attempts)) {
                    Log.i(
                        TAG,
                        "giving up on '${torrent.name}' after $attempts rounds with no " +
                            "confident match"
                    )
                    store.markProcessed(torrent.id)
                }
                continue
            }
            // It matched a real title, so every retry streak is over.
            store.clearNoMatch(torrent.id)

            // The one shared household account holds every profile's titles,
            // and this round may only write the ones that are this profile's.
            // Same evidence Continue Watching filters tracker cards by
            // ([titleOwnedByAnotherProfile]): a title POSITIVELY owned by a
            // sibling is not added here. Owned by this profile, or owned by
            // nobody (a cloud title no profile has watched), adds as before.
            if (titleOwnedByAnotherProfile(owners, match.mediaType, match.title, liveProfileId)) {
                Log.i(
                    TAG,
                    "not adding '${torrent.name}': '${match.title}' belongs to another profile"
                )
                // Marked handled rather than retried: the decision was made on
                // evidence that does not change per round, and re-deciding it
                // would cost a TMDB search every time. A manual Resync clears
                // this mark with every other one, so a viewer who disagrees can
                // make the sync look again.
                store.markProcessed(torrent.id)
                continue
            }

            val imdbId = runCatchingCancellable {
                repository.resolveImdbId(match.tmdbId, match.mediaType)
            }.getOrNull()

            // Removed on purpose, here or on another device: a sync round never
            // puts it back, whatever the processed marks say. The only way back
            // is a MANUAL re-add, which clears the record (see
            // LocalLibraryStore.addToMyList). Not marked handled: the record can
            // be cleared later, and a mark would hide the title until a Resync.
            if (LibraryTombstones.isRemoved(tombstones, match.mediaType, imdbId, match.tmdbId)) {
                tombstoneSkips++
                Log.i(
                    TAG,
                    "not adding '${torrent.name}': '${match.title}' was removed from My List on purpose"
                )
                continue
            }

            val wasAdded = runCatchingCancellable {
                LibraryMirror.addToLibrary(
                    context = context,
                    mediaType = match.mediaType,
                    imdbId = imdbId,
                    tmdbId = match.tmdbId,
                    title = match.title,
                    year = match.year,
                    posterUrl = match.posterPath?.let { TmdbRepository.POSTER_BASE + it },
                    // The marker that lets the untoggle question count this row
                    // and the cleanup below name it (see LibraryOrigin).
                    origin = LibraryOrigin.TORBOX_SYNC
                )
            }.onFailure { Log.w(TAG, "add failed for '${torrent.name}': ${it.message}") }
                .getOrDefault(false)

            if (wasAdded) added++
            store.markProcessed(torrent.id)
        }

        return file(
            store,
            Report(
                scanned = torrents.size,
                added = added,
                handledSkips = skips,
                tombstoneSkips = tombstoneSkips,
                removed = removed,
                outcome = Outcome.COMPLETED
            )
        )
    }

    /**
     * The one-time cleanup of the contamination this sync caused before it
     * stepped outside the account it was reading.
     *
     * Runs on the first round that has a live profile, once per profile per
     * device (its own version flag in [TorBoxCloudStore]), and only ever removes
     * rows from THE CURRENT PROFILE's list. [torboxRowLeakedFromAnotherProfile]
     * is the rule; this method only assembles its evidence and does the one
     * write.
     *
     * Everything the rule does not name is untouched, whatever it is: manual
     * rows for titles the cloud does not hold, this profile's own cloud titles,
     * titles no profile owns and no sibling lists, and any title this profile has
     * watched stay exactly where they are.
     *
     * Returns how many rows it removed, for the round's report.
     */
    private fun remediateOwnership(
        context: Context,
        store: TorBoxCloudStore,
        liveProfileId: String,
        torrents: List<TorBoxClient.CloudTorrent>
    ): Int {
        if (!store.ownershipRemediationPending()) return 0

        val owners = TitleProfileOwnership.snapshot(context)
        val cloudTitles = torrents
            .mapNotNull { TorBoxCloudRules.parseName(it.name)?.title }
            .mapNotNull { titleIdentityName(it) }
            .toSet()
        val siblingTitles = siblingLibraryTitles(context, liveProfileId)

        val doomed = LocalLibraryStore.myList(context).filter { entry ->
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = owners,
                entry = entry,
                cloudTitles = cloudTitles,
                siblingTitles = siblingTitles,
                liveProfileId = liveProfileId
            )
        }

        val removed = LocalLibraryStore.removeMyListEntries(context, doomed)
        store.markOwnershipRemediated()
        Log.i(
            TAG,
            "one-time ownership cleanup: removed $removed title(s) this profile does not own"
        )
        return removed
    }

    /**
     * Every title identity in some OTHER profile's My List on this device.
     *
     * The evidence arm B needs, and the reason the cleanup can attribute a row
     * no profile has ever WATCHED: the pre-fix sync wrote the shared cloud into
     * every profile it ran under, so a leaked title has a copy in the sibling's
     * list, while a title only this profile ever listed has none. Read by title
     * identity, not by id, because the copied row's ids are whatever that
     * profile's TMDB search produced.
     */
    private fun siblingLibraryTitles(context: Context, liveProfileId: String): Set<String> =
        ProfileManager.profileIds(context)
            .asSequence()
            .filter { it.isNotBlank() && it != liveProfileId }
            .flatMap { sibling ->
                LocalLibraryStore.myListForProfile(context, sibling)
                    .asSequence()
                    .mapNotNull { titleIdentityName(it.title) }
            }
            .toSet()

    /**
     * Files [report] as this device's last round and writes the line the field
     * can read back. The single exit of every path in [sync], so no outcome can
     * be reported to the caller and lost on the way to the store.
     */
    private fun file(store: TorBoxCloudStore, report: Report): Report {
        store.recordRound(report)
        Log.i(
            TAG,
            "TorBox library sync: outcome=${report.outcome}, ${report.scanned} seen, " +
                "${report.added} added, ${report.handledSkips} already handled, " +
                "${report.tombstoneSkips} removed on purpose elsewhere, " +
                "${report.removed} removed as another profile's"
        )
        return report
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

/**
 * Whether one row of the LIVE profile's My List is a leftover of the pre-fix
 * cross-profile sync and may be removed.
 *
 * The cleanup used to ask a watch-shaped question - "is this title owned by
 * another profile?" - of a cloud-shaped leak. [TitleProfileOwnership] learns an
 * owner only when a profile WATCHES a title on this device, and the leaked rows
 * came from a shared TorBox cloud: a kids' title that was downloaded but never
 * played here has no recorded owner, so the filter called it unattributed and
 * kept it. That is most of the field's contamination, and the reason the first
 * cleanup pass removed nothing.
 *
 * Two arms, one veto:
 *
 *  - VETO: the ownership map names THIS profile. Whatever else is true, it is
 *    this profile's, and it stays.
 *  - ARM A (positive attribution, unchanged): owned by another profile AND
 *    (marked [LibraryOrigin.TORBOX_SYNC], or its title is in the shared cloud).
 *  - ARM B (cloud-shaped evidence): UNMARKED, title in the shared cloud, AND
 *    the title also sits in at least one sibling profile's My List. That last
 *    clause is what stands in for attribution: the pre-fix sync mirrored the
 *    cloud into every profile it ran under, so a leaked title has a copy there,
 *    while a title only this profile ever listed does not.
 *
 * The residual ambiguity is real and stated rather than hidden: a title the
 * viewer hand-added here, that ALSO sits in a sibling's list and in the cloud, is
 * indistinguishable from a leak in the stored data. Arm B treats it as leakage;
 * the harm is bounded to a My List row that is re-addable in one tap, and the
 * round now reports its removals by count instead of doing it silently.
 *
 * A row owned by NOBODY, in no sibling list, is kept whatever it is - including
 * this profile's own cloud titles, which is the state the add-time filter leaves
 * every new row in.
 */
internal fun torboxRowLeakedFromAnotherProfile(
    ownerByTitleKey: Map<String, String>,
    entry: LibraryItem,
    cloudTitles: Set<String>,
    siblingTitles: Set<String>,
    liveProfileId: String
): Boolean {
    val identity = titleIdentityName(entry.title) ?: return false
    val owner = titleProfileKey(entry.mediaType, entry.title)?.let { ownerByTitleKey[it] }

    // The veto: this profile's own title, by the one evidence that is positive.
    if (owner != null && owner == liveProfileId) return false

    val inCloud = identity in cloudTitles
    val marked = entry.origin == LibraryOrigin.TORBOX_SYNC

    if (owner != null && owner != liveProfileId) {
        return marked || inCloud
    }

    return !marked && inCloud && identity in siblingTitles
}
