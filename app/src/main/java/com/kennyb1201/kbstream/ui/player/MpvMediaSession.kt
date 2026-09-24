package com.kennyb1201.kbstream.ui.player

import android.app.PendingIntent
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * The system's now-playing session for the MPV engine.
 *
 * The main player publishes one of these over its ExoPlayer, and it is worth
 * having for three things: the now-playing card the system and other apps can
 * read, transport buttons that work with this app in the background, and a tap
 * on that card that comes back to the player it was raised for. Libmpv is not a
 * media3 `Player`, so this is built on the platform session API that media3's
 * own session wraps — the viewer gets the same feature, from the same source of
 * truth (libmpv's playhead).
 *
 * Two deliberate differences from the main player's session, both forced by the
 * engine rather than chosen:
 *
 *  - **State is polled, not pushed.** ExoPlayer's listener tells its session the
 *    moment anything changes; here [now] is read on a one-second tick, which is
 *    the same trick the main player uses for its own position readout. A second
 *    of lag on a lock-screen scrubber is not something a viewer can see.
 *  - **Nothing is published unless it changed.** A session write notifies every
 *    registered controller, and re-sending identical metadata once a second for
 *    a two-hour film would keep the system's media stack awake for no reason, so
 *    every publish is compared against what was last sent.
 *
 * The session dies with its owner ([owner]): a released session whose activity
 * is gone would leave a now-playing card that cannot be reopened.
 */
internal class MpvMediaSession(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val now: () -> Now,
    private val pendingIntent: () -> PendingIntent,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onSkipNext: () -> Unit,
    private val onSkipPrevious: () -> Unit,
    private val onSeek: (Long) -> Unit
) {

    /** Everything the session publishes, read fresh on every tick. */
    internal data class Now(
        val title: String,
        val showName: String,
        val episodeLabel: String?,
        val posterUrl: String?,
        val durationMs: Long,
        val positionMs: Long,
        val buffering: Boolean,
        val playing: Boolean,
        val speed: Float
    )

    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    /** What was last sent, so an unchanged second publishes nothing. */
    private var lastMetadata: String? = null
    private var lastState: String? = null

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = release()
    }

    /**
     * Raises the session and starts the tick. Returns false when this device
     * refuses one, which is not a reason to fail playback: the session is a
     * convenience surface, and the picture must not depend on it.
     */
    fun start(): Boolean {
        val created = runCatching {
            MediaSession(context, "kbstream-mpv-" + System.nanoTime()).apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = this@MpvMediaSession.onPlay()
                    override fun onPause() = this@MpvMediaSession.onPause()
                    override fun onSkipToNext() = onSkipNext()
                    override fun onSkipToPrevious() = onSkipPrevious()
                    override fun onSeekTo(pos: Long) = this@MpvMediaSession.onSeek(pos)
                })
                runCatching { setSessionActivity(pendingIntent()) }
                isActive = true
            }
        }.getOrElse { error ->
            Log.w(TAG, "could not create the media session", error)
            return false
        }
        session = created
        owner.lifecycle.addObserver(lifecycleObserver)
        refresh()
        handler.postDelayed(tick, TICK_MS)
        return true
    }

    /** Publishes whatever changed since the last publish. */
    fun refresh() {
        val session = session ?: return
        val snapshot = runCatching { now() }.getOrNull() ?: return

        val state = when {
            snapshot.buffering -> PlaybackState.STATE_BUFFERING
            !snapshot.playing -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        // Position and duration move constantly and must not be suppressed -
        // they are what makes a lock-screen scrubber track the film - so they
        // are rounded to the second, which is the resolution these surfaces
        // show anyway.
        val stateKey = listOf(
            state,
            snapshot.positionMs / 1_000L,
            snapshot.durationMs / 1_000L,
            snapshot.speed
        ).joinToString(",")
        if (stateKey != lastState) {
            lastState = stateKey
            val builder = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, snapshot.positionMs.coerceAtLeast(0L), snapshot.speed)
            if (snapshot.durationMs > 0L) builder.setBufferedPosition(snapshot.durationMs)
            runCatching { session.setPlaybackState(builder.build()) }
        }

        val made = runCatching { mediaMetadata(snapshot) }.getOrNull() ?: return
        val metadataKey = listOf(
            snapshot.title,
            snapshot.showName,
            snapshot.episodeLabel.orEmpty(),
            snapshot.posterUrl.orEmpty(),
            snapshot.durationMs / 1_000L
        ).joinToString(",")
        if (metadataKey != lastMetadata) {
            lastMetadata = metadataKey
            runCatching { session.setMetadata(made) }
        }
    }

    /**
     * Title / show / episode, so the card reads the same here as it does when the
     * main player is the one playing: the episode title is the headline, the
     * show is the album, and "S1 E4" is the artist line these surfaces show in
     * place of a performing artist.
     */
    private fun mediaMetadata(snapshot: Now): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .putString(
                MediaMetadata.METADATA_KEY_TITLE,
                snapshot.title.ifBlank { snapshot.showName }
            )
            .putString(MediaMetadata.METADATA_KEY_ALBUM, snapshot.showName)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs)
        snapshot.episodeLabel?.takeIf { it.isNotBlank() }?.let { label ->
            builder.putString(MediaMetadata.METADATA_KEY_ARTIST, label)
        }
        // The poster, when the session was launched with one: these cards show
        // it instead of a placeholder.
        snapshot.posterUrl?.takeIf { it.isNotBlank() }?.let { art ->
            runCatching {
                builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, art)
                builder.putString(MediaMetadata.METADATA_KEY_ART_URI, art)
            }
        }
        return builder.build()
    }

    /** Drops the session and the tick. Safe to call more than once. */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        runCatching { owner.lifecycle.removeObserver(lifecycleObserver) }
        runCatching { session?.release() }
        session = null
        lastMetadata = null
        lastState = null
    }

    private companion object {
        const val TAG = "PLAYER_MPV"

        /**
         * One second: fast enough that a transport press from the system feels
         * immediate, slow enough that the tick is invisible in a battery trace.
         */
        const val TICK_MS = 1_000L
    }
}
