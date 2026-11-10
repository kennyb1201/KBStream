package com.kennyb1201.kbstream.ui.detail

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kennyb1201.kbstream.data.tmdb.TmdbVideo
import com.kennyb1201.kbstream.data.youtube.PlayableSource
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerLauncher
import com.kennyb1201.kbstream.ui.player.NativePlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Opens one of a title's TMDB videos in the native player, as a TRAILER
 * session.
 *
 * This is the one place the intent shape lives. The TV screen builds it inline
 * (its `trailerPlayerIntent`, which now delegates here) and the phone's Media
 * row calls [play] directly - if the two ever disagreed about the extras, a
 * trailer opened from the phone would be filed as ordinary playback, which is
 * exactly what the `trailer_video_id` extra exists to prevent (see
 * [TrailerPlayerLauncher.EXTRA_TRAILER_VIDEO_ID]).
 *
 * Only the player's own source contract is here: [video] must already be known
 * playable (see `DetailMediaRow.isPlayable`). The resolve itself goes through
 * [TrailerPlayerLauncher], so both form factors share its cache, its
 * coalescing mutex and its failure memory.
 */
object DetailTrailerLaunch {

    private const val TAG = "DetailTrailer"

    /**
     * The title's ids, carried into the player so a trailer that DIES there can
     * re-resolve with the same IMDb leg the first resolve had.
     *
     * Without them the player's one trailer retry (see
     * `NativePlayerActivity.refreshIdentityOnTrailerRefusal`) reaches only the
     * YouTube ladder - the exact hole the Head of State death fell into: the
     * retry after a YouTube death could never reach the IMDb MP4.
     */
    const val EXTRA_TRAILER_IMDB_ID = "trailer_imdb_id"

    const val EXTRA_TRAILER_TMDB_ID = "trailer_tmdb_id"

    const val EXTRA_TRAILER_TYPE = "trailer_type"

    /** The intent that hands [source] to the player for [videoId]. */
    fun intent(
        context: Context,
        source: PlayableSource,
        videoId: String,
        imdbId: String? = null,
        tmdbId: Int? = null,
        type: String? = null
    ): Intent =
        Intent(context, NativePlayerActivity::class.java).apply {
            val userAgent = when (source) {
                is PlayableSource.Muxed -> {
                    putExtra("stream_url", source.url)
                    source.userAgent
                }

                is PlayableSource.Adaptive -> {
                    putExtra("stream_url", source.videoUrl)
                    putExtra("audio_url", source.audioUrl)
                    source.userAgent
                }
            }

            userAgent?.let { ua ->
                putExtra("stream_headers", "User-Agent: $ua")
            }

            putExtra("parent_type", "movie")
            putExtra("item_name", "Trailer")
            // Identifies the session as a trailer so a player-visible 403 can
            // invalidate the cached YouTube watch config (see
            // TrailerPlayerLauncher.isYouTubeIdentityBlocked's callers).
            putExtra(TrailerPlayerLauncher.EXTRA_TRAILER_VIDEO_ID, videoId)
            // The ids the FIRST resolve had, for the one re-resolve this
            // session can make: a retry that has no IMDb id cannot try the MP4.
            imdbId?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_TRAILER_IMDB_ID, it) }
            tmdbId?.let { putExtra(EXTRA_TRAILER_TMDB_ID, it) }
            type?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_TRAILER_TYPE, it) }
        }

    /**
     * Resolves and plays [video], or does nothing when there is no key to
     * resolve. A resolve failure is logged and dropped: the press was a
     * request to watch a trailer, and a dead tap with no screen change is the
     * same outcome whether or not the log line exists.
     */
    fun play(
        context: Context,
        scope: CoroutineScope,
        video: TmdbVideo?,
        /**
         * The title's IMDb id, when the screen knows it.
         *
         * The resolve tries IMDb's own MP4 before the YouTube key it was handed
         * (see `TrailerPlayerLauncher.resolvePlayableUrl`), so the two are not
         * in competition: IMDb first, this video's YouTube key when IMDb has
         * nothing suitable.
         */
        imdbId: String? = null,
        /** The title on TMDB, when the screen has no `tt…` id to pass. */
        tmdbId: Int? = null,
        /** `movie` / `series`, for the TMDB-to-IMDb mapping. */
        type: String? = null
    ) {
        val key = video?.key?.takeIf { it.isNotBlank() } ?: return

        scope.launch {
            val source =
                TrailerPlayerLauncher.resolvePlayableUrl(
                    "https://www.youtube.com/watch?v=$key",
                    imdbId = imdbId,
                    tmdbId = tmdbId,
                    type = type
                ).getOrElse { error ->
                    Log.e(TAG, "Failed to resolve playable trailer URL", error)
                    return@launch
                }

            context.startActivity(
                intent(
                    context,
                    source,
                    key,
                    imdbId = imdbId,
                    tmdbId = tmdbId,
                    type = type
                )
            )
        }
    }
}
