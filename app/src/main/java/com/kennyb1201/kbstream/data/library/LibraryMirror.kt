package com.kennyb1201.kbstream.data.library

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListEntry
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared "Add to Library" pipeline behind every long-press entry point
 * (Home rails, Search, Continue Watching, Detail, genre/keyword/network/
 * actor screens, and the Library tab's own picker). The local write
 * always happens first; adds then mirror to the Simkl watchlist (Plan to
 * Watch) and/or the MDBList watchlist when those accounts are connected.
 * Remote mirrors are best-effort — a tracker outage never blocks or
 * undoes the local save.
 */
object LibraryMirror {

    private const val TAG = "LIBRARY_MIRROR"

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
     * Saves the title to the local My List, then fires the remote mirrors
     * in [scope]. Mirrors fire even when the local list already had the
     * title, so a previously partial sync (title on one tracker but not
     * the other) heals on a repeat add. Returns true when the title was
     * newly added locally.
     */
    fun addToLibrary(
        context: Context,
        scope: CoroutineScope,
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

        launchMirrors(context, scope, r)
        return added
    }

    /**
     * Saves the title to a local personal list, then mirrors the same add
     * to the matching MDBList list when one exists with that id and a key
     * is configured (a local list sharing an id with an MDBList list is
     * not possible — local ids are negative — so this only fires for real
     * MDBList ids chosen from the picker).
     */
    fun addToList(
        context: Context,
        scope: CoroutineScope,
        list: LibraryList,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        title: String,
        year: Int? = null,
        posterUrl: String? = null
    ): Boolean {
        val r = ref(mediaType, imdbId, tmdbId, title, year, posterUrl) ?: return false

        val added = if (list.id > 0) {
            // MDBList personal list: mirror remotely, no local copy.
            if (mdbListConnected(context)) {
                scope.launch {
                    runCatching {
                        MdbListClient.addToList(
                            context,
                            list.id,
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
                    }
                }
            }
            mdbListConnected(context)
        } else {
            LocalLibraryStore.addToLocalList(
                context,
                listId = list.id,
                mediaType = r.normalizedType,
                imdbId = r.imdbId,
                tmdbId = r.tmdbId,
                title = r.title,
                year = r.year,
                posterUrl = r.posterUrl
            )
        }
        return added
    }

    /**
     * Removes from the local My List AND mirrors the removal to the
     * watchlists of every connected tracker (best-effort). Used by the
     * Library tab's long-press remove.
     */
    fun removeFromLibrary(
        context: Context,
        scope: CoroutineScope,
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
                scope.launch {
                    runCatching {
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
        scope: CoroutineScope,
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
        scope.launch {
            runCatching {
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
    private fun launchMirrors(context: Context, scope: CoroutineScope, r: TitleRef) {
        scope.launch {
            if (simklConnected(context)) {
                runCatching {
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
                runCatching {
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
