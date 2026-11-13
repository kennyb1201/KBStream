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

    /**
     * The YouTube client that signed the handed-over trailer source, when one
     * did.
     *
     * The player decides its data source from it (see
     * `trailerDataSourceFactory`): a token-free pick plays through the stock
     * loader, a gated one through the chunker. It travels as its own extra
     * because this is the only place the source is flattened into an intent -
     * without it the fullscreen player could only guess from the URL's host,
     * and both URL classes are googlevideo.
     */
    const val EXTRA_TRAILER_CLIENT = "trailer_client"

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

            source.client?.let { putExtra(EXTRA_TRAILER_CLIENT, it) }

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
     * Plays [url] as a one-off video - a theme video, already resolved.
     *
     * The same session shape as [intent], with the resolve taken out: a theme
     * link is a direct progressive WebM file, so there is no YouTube key, no
     * IMDb leg and nothing to look up.
     *
     * Like a trailer, and for the same reason, this session carries NO
     * `parent_id`: every write the player makes - history, the resume point, the
     * Continue Watching merge, the tracker scrobbles - returns early on a blank
     * parent, so a theme is a viewing rather than a playback session. It is not
     * filed under the title, cannot mark an episode watched and cannot move a
     * card. [title] is what the player's own overlay calls it.
     */
    fun playUrl(context: Context, url: String?, title: String?) {
        val link = url?.trim()?.takeIf { it.isNotEmpty() } ?: return

        val intent = Intent(context, NativePlayerActivity::class.java).apply {
            putExtra("stream_url", link)
            putExtra("item_name", title?.trim()?.takeIf { it.isNotEmpty() } ?: "Theme")
        }

        context.startActivity(intent)
    }

    /**
     * Resolves and plays [video], or does nothing when there is no key to
     * resolve.
     *
     * A resolve failure is logged AND reported through [onFailure], because the
     * press asked to watch this trailer: a dead tap with no screen change reads
     * as a broken remote, not as a video that would not open. Each surface has
     * its own channel for that (TV's feedback banner, the phone's toast), so the
     * launcher only reports and the caller decides - a caller with nothing to
     * say leaves the log as the only trace, exactly as before.
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
        type: String? = null,
        /**
         * What the caller's surface says when the resolve fails - the TV's
         * feedback banner, the phone's toast. Default null so a caller with no
         * channel (or no wish to report) keeps the old silence.
         */
        onFailure: ((Throwable) -> Unit)? = null
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
                    onFailure?.invoke(error)
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
