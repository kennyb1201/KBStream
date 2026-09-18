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
 * (Home rails, Search, Continue Watching, Detail). The local profile My
 * List write always happens first; the add then mirrors to the Simkl
 * watchlist (Plan to Watch) and/or the MDBList watchlist when those
 * accounts are connected. Remote mirrors are best-effort — a tracker
 * outage never blocks or undoes the local save.
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
        val normalizedType = mediaType.lowercase()
        if (imdbId == null && tmdbId == null) return false

        val added = LocalLibraryStore.addToMyList(
            context,
            mediaType = normalizedType,
            imdbId = imdbId,
            tmdbId = tmdbId,
            title = title,
            year = year,
            posterUrl = posterUrl
        )

        scope.launch {
            if (simklConnected(context)) {
                runCatching {
                    SimklRepository.getInstance(context).addToWatchlist(
                        mediaType = normalizedType,
                        imdbId = imdbId,
                        tmdbId = tmdbId,
                        simklId = null,
                        title = title,
                        year = year
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
                                title = title,
                                mediaType = normalizedType,
                                year = year,
                                poster = posterUrl,
                                imdbId = imdbId?.takeIf { it.startsWith("tt") },
                                tmdbId = tmdbId
                            )
                        )
                    )
                }.onFailure { e ->
                    Log.e(TAG, "mdblist mirror failed: ${e.message}", e)
                }
            }
        }

        return added
    }
}
