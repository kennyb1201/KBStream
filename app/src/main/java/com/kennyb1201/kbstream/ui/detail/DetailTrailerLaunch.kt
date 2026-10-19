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

    /** The intent that hands [source] to the player for [videoId]. */
    fun intent(
        context: Context,
        source: PlayableSource,
        videoId: String
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
        video: TmdbVideo?
    ) {
        val key = video?.key?.takeIf { it.isNotBlank() } ?: return

        scope.launch {
            val source =
                TrailerPlayerLauncher.resolvePlayableUrl(
                    "https://www.youtube.com/watch?v=$key"
                ).getOrElse { error ->
                    Log.e(TAG, "Failed to resolve playable trailer URL", error)
                    return@launch
                }

            context.startActivity(intent(context, source, key))
        }
    }
}
