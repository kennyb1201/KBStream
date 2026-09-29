package com.kennyb1201.kbstream.data.library

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListEntry
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.simkl.SimklRepository
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
     */
    fun addToLibrary(
        context: Context,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        title: String,
        year: Int? = null,
        posterUrl: String? = null
    ): Boolean {
        val r = ref(mediaType, imdbId, tmdbId, title, year, posterUrl) ?: return false

        val added = LocalLibraryStore.addToMyList(
            context,
            mediaType = r.normalizedType,
            imdbId = r.imdbId,
            tmdbId = r.tmdbId,
            title = r.title,
            year = r.year,
            posterUrl = r.posterUrl
        )

        launchMirrors(context, r)
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
                        Log.e(TAG, "mdblist removeFromWatchlist failed: ${e.message}", e)
                    }
                }
            }
        }

        // Simkl has no remove-from-watchlist endpoint (only add-to-list
        // moves between statuses), so Simkl rows are add-only.
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
        }
    }
}
