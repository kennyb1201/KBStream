package com.kennyb1201.kbstream.data.library

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListEntry
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Shared "Add to Library" pipeline behind every long-press entry point
 * (Home rails, Search, Continue Watching, Detail, genre/keyword/network/
 * actor screens, and the Library tab's own picker). The local write
 * always happens first; adds then mirror to the Simkl watchlist (Plan to
 * Watch) and/or the MDBList watchlist when those accounts are connected.
 * Remote mirrors are best-effort — a tracker outage never blocks or
 * undoes the local save.
 *
 * Every remote call runs on [mirrorScope], never on the caller's scope: a
 * library action is started from a menu that closes as it fires, so the
 * caller's scope is the one thing the mirrors cannot use. See the scope's
 * own doc for what that cost when they did.
 */
object LibraryMirror {

    private const val TAG = "LIBRARY_MIRROR"

    /**
     * The scope the tracker mirrors run on.
     *
     * Deliberately not the caller's. An add is started by a long-press menu,
     * and that menu (and the dialog behind it) is gone — and its composition
     * scope canceled — the instant the row is pressed. A mirror launched on
     * the caller's scope is therefore racing a cancellation it usually loses:
     * the local write lands (it is the next statement) while the Simkl and
     * MDBList adds it was supposed to fire never leave the device, and the
     * menu has already closed, so nothing says so. That is what "I added it to
     * my watchlist and nothing showed up" looked like from the sofa.
     *
     * This scope is process-lifetime and its job is a [SupervisorJob], so one
     * tracker failing never takes the other with it — the same shape
     * MdbListClient's session scope and the startup scope use.
     */
    private val mirrorScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Simkl is signed in, so adds will mirror to its watchlist too. */
    fun simklConnected(context: Context): Boolean {
        val simkl = SimklRepository.getInstance(context)
        return simkl.isConfigured() && simkl.hasToken()
    }

    /** MDBList API key is set, so adds will mirror to its watchlist too. */
    fun mdbListConnected(context: Context): Boolean =
        MdbListClient.isConfigured(context)

    /** Trakt is connected, so adds will mirror to its watchlist too. */
    fun traktConnected(context: Context): Boolean {
        val trakt = TraktRepository.getInstance(context)
        return trakt.isConfigured() && trakt.hasToken()
    }

    /**
     * BingeCat can save: connected, mapped to a profile, and granted
     * `lists:write` (step 3).
     *
     * Deliberately [BingeCatRepository.canSave] and not "has a token": a
     * connected-but-unmapped BingeCat has nowhere to put the title, and a
     * step-1/2 grant would earn a 403. Neither is a mirror this app should
     * promise in the add row or fire after a save.
     */
    fun bingeCatConnected(context: Context): Boolean =
        BingeCatRepository.getInstance(context).canSave()

    /** The id triple shared by every entry point. */
    data class TitleRef(
        val mediaType: String,
        val imdbId: String?,
        val tmdbId: Int?,
        val title: String,
        val year: Int? = null,
        val posterUrl: String? = null
    ) {
        /** Simkl/MDBList want normalized types. */
        val normalizedType: String
            get() = when (mediaType.lowercase()) {
                "tv", "series" -> "series"
                else -> "movie"
            }

        /** MDBList only accepts tt-prefixed IMDB ids. */
        val mdbImdbId: String?
            get() = imdbId?.takeIf { it.startsWith("tt") }
    }

    private fun ref(
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        title: String,
        year: Int?,
        posterUrl: String?
    ): TitleRef? {
        val normalized = TitleRef(
            mediaType = mediaType,
            imdbId = imdbId,
            tmdbId = tmdbId,
            title = title,
            year = year,
            posterUrl = posterUrl
        )
        if (normalized.imdbId == null && normalized.tmdbId == null) return null
        if (normalized.title.isBlank()) return null
        return normalized
    }

    /**
     * Saves the title to the local My List, then fires the remote mirrors.
     * Mirrors fire even when the local list already had the title, so a
     * previously partial sync (title on one tracker but not the other)
     * heals on a repeat add. Returns true when the title was newly added
     * locally.
     *
     * A [LibraryOrigin.TORBOX_SYNC] add never mirrors. A cloud round is a
     * download sync into My List, not a watchlist add: the row already shows in
     * the Library, so mirroring it is redundant for the view and is exactly
     * what seeded a profile's Simkl/MDBList watchlists with titles nobody there
     * chose - every unowned kids title in the shared TorBox cloud landed in
     * Profile 1's trackers this way. A deliberate add (MANUAL, or any other
     * origin) keeps its mirrors, including on a repeat, so the partial-sync heal
     * still works for a title the viewer actually asked for.
     */
    fun addToLibrary(
        context: Context,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        title: String,
        year: Int? = null,
        posterUrl: String? = null,
        /**
         * Who is adding it. MANUAL for every menu entry point in the app; the
         * TorBox cloud→Library sync passes [LibraryOrigin.TORBOX_SYNC] so the
         * row can be named - and, at the viewer's request, taken back - by the
         * surfaces that own that sync (see LocalLibraryStore).
         */
        origin: LibraryOrigin = LibraryOrigin.MANUAL
    ): Boolean {
        val r = ref(mediaType, imdbId, tmdbId, title, year, posterUrl) ?: return false

        val added = LocalLibraryStore.addToMyList(
            context,
            mediaType = r.normalizedType,
            imdbId = r.imdbId,
            tmdbId = r.tmdbId,
            title = r.title,
            year = r.year,
            posterUrl = r.posterUrl,
            origin = origin
        )

        // See this function's doc: the unattended cloud sync writes My List
        // only. The stamp is still applied by LocalLibraryStore above.
        if (origin != LibraryOrigin.TORBOX_SYNC) launchMirrors(context, r)
        return added
    }

    /**
     * Saves the title to a local personal list, then mirrors the same add
     * to the matching MDBList list when one exists with that id and a key
     * is configured (a local list sharing an id with an MDBList list is
     * not possible — local ids are negative — so this only fires for real
     * MDBList ids chosen from the picker).
     */
    suspend fun addToList(
        context: Context,
        listId: Int,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        title: String,
        year: Int? = null,
        posterUrl: String? = null
    ): Boolean {
        val r = ref(mediaType, imdbId, tmdbId, title, year, posterUrl) ?: return false

        if (listId > 0) {
            // MDBList personal list: remote only, no local copy. The caller
            // waits for the POST because this is the case that used to report
            // success unconditionally: the row ticked "ADDED ✓" and the add
            // was fired off on the dialog's scope, so a rejection by MDBList
            // (or the dialog closing) looked exactly like a save.
            if (!mdbListConnected(context)) return false
            return runCatchingCancellable {
                MdbListClient.addToList(
                    context,
                    listId,
                    listOf(
                        MdbListEntry(
                            title = r.title,
                            mediaType = r.normalizedType,
                            year = r.year,
                            poster = r.posterUrl,
                            imdbId = r.mdbImdbId,
                            tmdbId = r.tmdbId
                        )
                    )
                )
            }.onFailure { e ->
                Log.e(TAG, "mdblist addToList failed: ${e.message}", e)
            }.getOrDefault(false)
        }

        return LocalLibraryStore.addToLocalList(
            context,
            listId = listId,
            mediaType = r.normalizedType,
            imdbId = r.imdbId,
            tmdbId = r.tmdbId,
            title = r.title,
            year = r.year,
            posterUrl = r.posterUrl
        )
    }

    /**
     * Removes from the local My List AND mirrors the removal to the
     * watchlists of every connected tracker (best-effort). Used by the
     * Library tab's long-press remove.
     */
    fun removeFromLibrary(
        context: Context,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?
    ) {
        LocalLibraryStore.removeFromMyList(context, mediaType, imdbId, tmdbId)
        removeFromTrackers(context, mediaType, imdbId, tmdbId)
    }

    /**
     * The tracker half of [removeFromLibrary], without touching the local list.
     *
     * Split out for the review queue: its confirmation already tombstoned the
     * local rows in one write, and a tracker copy left behind would resurface
     * the title in the Library merge. MDBList, Trakt, BingeCat and Simkl are
     * asked to drop it, best-effort and independent of each other; [title] only
     * names the row in the log, so a surviving remote copy is visible in
     * diagnostics instead of silent.
     */
    fun removeFromTrackers(
        context: Context,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        title: String? = null
    ) {
        val label = title?.takeIf { it.isNotBlank() } ?: "$mediaType $imdbId/$tmdbId"
        val type = when (mediaType.lowercase()) {
            "tv", "series" -> "series"
            else -> "movie"
        }

        if (mdbListConnected(context)) {
            val entry = MdbListEntry(
                title = null,
                mediaType = type,
                year = null,
                poster = null,
                imdbId = imdbId?.takeIf { it.startsWith("tt") },
                tmdbId = tmdbId
            )
            if (entry.imdbId != null || entry.tmdbId != null) {
                mirrorScope.launch {
                    runCatchingCancellable {
                        MdbListClient.removeFromWatchlist(context, listOf(entry))
                    }.onFailure { e ->
                        Log.e(
                            TAG,
                            "mdblist removeFromWatchlist failed for $label: ${e.message}",
                            e
                        )
                    }
                }
            }
        }

        if (traktConnected(context)) {
            val isMovie = type == "movie"
            if (imdbId != null || tmdbId != null) {
                mirrorScope.launch {
                    runCatchingCancellable {
                        TraktRepository.getInstance(context).removeFromWatchlist(
                            isMovie = isMovie,
                            imdbId = imdbId,
                            tmdbId = tmdbId
                        )
                    }.onFailure { e ->
                        Log.e(
                            TAG,
                            "trakt removeFromWatchlist failed for $label: ${e.message}",
                            e
                        )
                    }
                }
            }
        }

        // BingeCat's watchlist copy, on the same load-bearing rule: a copy left
        // behind is a title the Library merge would bring back a moment later
        // (see this function's doc). Skipped entirely when this device cannot
        // save - an unmapped profile or a grant without `lists:write` must not
        // turn a local remove into a 403.
        if (bingeCatConnected(context)) {
            val isMovie = type == "movie"
            if (imdbId != null || tmdbId != null) {
                mirrorScope.launch {
                    runCatchingCancellable {
                        BingeCatRepository.getInstance(context).removeFromWatchlist(
                            isMovie = isMovie,
                            imdbId = imdbId,
                            tmdbId = tmdbId,
                            title = label
                        )
                    }.onFailure { e ->
                        Log.e(
                            TAG,
                            "bingecat removeFromWatchlist failed for $label: ${e.message}",
                            e
                        )
                    }
                }
            }
        }

        // Simkl's own copy, on the same load-bearing rule: the merge reads
        // Simkl's list back, so a copy left behind puts the title on the rail
        // again a moment after the viewer removed it. Simkl has no dedicated
        // remove route - its documented path is a history delete, which would
        // also wipe watched history and the rating - so the list-only inverse
        // is used (see SimklRepository.removeFromWatchlist).
        if (simklConnected(context)) {
            if (imdbId != null || tmdbId != null) {
                mirrorScope.launch {
                    runCatchingCancellable {
                        SimklRepository.getInstance(context).removeFromWatchlist(
                            mediaType = type,
                            imdbId = imdbId,
                            tmdbId = tmdbId,
                            simklId = null
                        )
                    }.onFailure { e ->
                        Log.e(
                            TAG,
                            "simkl removeFromWatchlist failed for $label: ${e.message}",
                            e
                        )
                    }
                }
            }
        }
    }

    /** Removes from one MDBList personal list (ids only; best-effort). */
    fun removeFromMdbList(
        context: Context,
        listId: Int,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?
    ) {
        if (!mdbListConnected(context)) return
        val type = when (mediaType.lowercase()) {
            "tv", "series" -> "series"
            else -> "movie"
        }
        val entry = MdbListEntry(
            title = null,
            mediaType = type,
            year = null,
            poster = null,
            imdbId = imdbId?.takeIf { it.startsWith("tt") },
            tmdbId = tmdbId
        )
        if (entry.imdbId == null && entry.tmdbId == null) return
        mirrorScope.launch {
            runCatchingCancellable {
                MdbListClient.removeFromList(context, listId, listOf(entry))
            }.onFailure { e ->
                Log.e(TAG, "mdblist removeFromList failed: ${e.message}", e)
            }
        }
    }

    /** Removes from one local personal list. */
    fun removeFromLocalList(
        context: Context,
        listId: Int,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?
    ) {
        LocalLibraryStore.removeFromLocalList(context, listId, mediaType, imdbId, tmdbId)
    }

    /** Watchlist mirrors for one add (Simkl plan-to-watch + MDBList). */
    private fun launchMirrors(context: Context, r: TitleRef) {
        mirrorScope.launch {
            if (simklConnected(context)) {
                runCatchingCancellable {
                    SimklRepository.getInstance(context).addToWatchlist(
                        mediaType = r.normalizedType,
                        imdbId = r.imdbId,
                        tmdbId = r.tmdbId,
                        simklId = null,
                        title = r.title,
                        year = r.year
                    )
                }.onFailure { e ->
                    Log.e(TAG, "simkl mirror failed: ${e.message}", e)
                }
            }

            if (mdbListConnected(context)) {
                runCatchingCancellable {
                    MdbListClient.addToWatchlist(
                        context,
                        listOf(
                            MdbListEntry(
                                title = r.title,
                                mediaType = r.normalizedType,
                                year = r.year,
                                poster = r.posterUrl,
                                imdbId = r.mdbImdbId,
                                tmdbId = r.tmdbId
                            )
                        )
                    )
                }.onFailure { e ->
                    Log.e(TAG, "mdblist mirror failed: ${e.message}", e)
                }
            }

            if (traktConnected(context)) {
                runCatchingCancellable {
                    TraktRepository.getInstance(context).addToWatchlist(
                        isMovie = r.normalizedType == "movie",
                        imdbId = r.imdbId,
                        tmdbId = r.tmdbId,
                        title = r.title,
                        year = r.year
                    )
                }.onFailure { e ->
                    Log.e(TAG, "trakt mirror failed: ${e.message}", e)
                }
            }

            // BingeCat's watchlist, on the same fire-and-forget rule: it is a
            // mirror, so a rejected or parked write must not undo or block the
            // local save that already happened above.
            if (bingeCatConnected(context)) {
                runCatchingCancellable {
                    BingeCatRepository.getInstance(context).addToWatchlist(
                        isMovie = r.normalizedType == "movie",
                        imdbId = r.imdbId,
                        tmdbId = r.tmdbId,
                        title = r.title
                    )
                }.onFailure { e ->
                    Log.e(TAG, "bingecat mirror failed: ${e.message}", e)
                }
            }
        }
    }
}
