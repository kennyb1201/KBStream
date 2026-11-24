package com.kennyb1201.kbstream.data.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The cast sender's transport: the session, the device list, and the one call
 * that puts a title on the television.
 *
 * Scope, deliberately: this object knows how to CONNECT and how to LOAD. It does
 * not decide whether a launch may be cast ([CastRules.castVerdict] does), and it
 * does not write anything down ([CastWatchSession] does). That split keeps the
 * SDK out of the rules and the rules out of the SDK.
 *
 * The receiver is Play's default media receiver (see [CastOptions]), so a load
 * is exactly a URL plus its type, a title, and where to start. Nothing plays
 * here: the phone is a remote control, and every fact about the session -
 * position, play state, when it ends - is read back from the remote player.
 */
internal object CastSender {

    private const val TAG = "CAST_SENDER"

    /** What the phone is casting to, if anything, for the settings row. */
    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    /** True while a receiver is connected and can be loaded. */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _devices = MutableStateFlow<List<String>>(emptyList())

    /**
     * The Cast devices currently discovered, by display name. Read by the
     * phone's own chooser; empty until discovery has had a moment, which is why
     * the chooser says so rather than looking broken.
     */
    val devices: StateFlow<List<String>> = _devices.asStateFlow()

    private var sdk: CastContext? = null
    private var routerCallback: MediaRouter.Callback? = null
    private var sessionListener: SessionManagerListener<CastSession>? = null
    private var progressListener: RemoteMediaClient.ProgressListener? = null

    /**
     * The Cast context, or null when this device cannot cast at all (Play
     * services missing from the box, or the options provider never merged in).
     *
     * Every call site treats null as "no cast" rather than as an error: a
     * television and a phone without Play services both simply do not offer it.
     */
    private fun castOf(context: Context): CastContext? {
        sdk?.let { return it }
        return try {
            CastContext.getSharedInstance(context.applicationContext).also { sdk = it }
        } catch (e: Exception) {
            Log.w(TAG, "cast context unavailable", e)
            null
        }
    }

    private fun remoteClient(context: Context): RemoteMediaClient? =
        castOf(context)?.sessionManager?.currentCastSession?.remoteMediaClient

    /** Whether a receiver is connected right now. */
    fun isSessionLive(context: Context): Boolean =
        castOf(context)?.sessionManager?.currentCastSession?.isConnected == true

    /**
     * Starts discovery, keeps [deviceName]/[connected] current, and returns the
     * selector the chooser's routes are matched against - or null when there is
     * no Cast context on this device.
     */
    fun start(context: Context): MediaRouteSelector? {
        val cast = castOf(context) ?: return null
        val appContext = context.applicationContext
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(CastOptions.DEFAULT_RECEIVER_ID)
            )
            .build()

        if (sessionListener == null) {
            val listener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(session: CastSession, sessionId: String) =
                    noteSession(session)

                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) =
                    noteSession(session)

                override fun onSessionEnded(session: CastSession, error: Int) {
                    stopWatching(appContext)
                    noteSession(null)
                }

                override fun onSessionStartFailed(session: CastSession, error: Int) {
                    Log.w(TAG, "cast session start failed: $error")
                    noteSession(null)
                }

                override fun onSessionResumeFailed(session: CastSession, error: Int) {
                    Log.w(TAG, "cast session resume failed: $error")
                    noteSession(null)
                }

                override fun onSessionStarting(session: CastSession) = Unit
                override fun onSessionEnding(session: CastSession) = Unit
                override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
            }
            sessionListener = listener
            cast.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        }

        if (routerCallback == null) {
            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) =
                    publishRoutes(appContext, selector)

                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) =
                    publishRoutes(appContext, selector)

                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) =
                    publishRoutes(appContext, selector)
            }
            routerCallback = callback
            MediaRouter.getInstance(appContext).addCallback(
                selector,
                callback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
            )
        }
        publishRoutes(appContext, selector)
        noteSession(cast.sessionManager.currentCastSession)
        return selector
    }

    /** Stops discovery, for a settings row going away. The session is untouched. */
    fun stop(context: Context) {
        val appContext = context.applicationContext
        routerCallback?.let { callback ->
            runCatching { MediaRouter.getInstance(appContext).removeCallback(callback) }
        }
        routerCallback = null
    }

    /** Whether [name] is a discovered route, and selecting it worked. */
    fun selectRoute(context: Context, name: String): Boolean {
        val selector = start(context) ?: return false
        val route = MediaRouter.getInstance(context.applicationContext).routes.firstOrNull { route ->
            route.matchesSelector(selector) &&
                !route.isDefault &&
                route.name?.toString() == name
        } ?: return false
        route.select()
        return true
    }

    /**
     * Loads [plan] onto the connected receiver, at the phone's own position.
     *
     * Returns false when there is nothing to load ONTO (no session, no remote
     * client) or the SDK refused the request - the caller's cue to fall back to
     * a local player rather than leave the viewer staring at a phone that is
     * doing nothing.
     */
    fun load(context: Context, plan: CastPlan): Boolean {
        val client = remoteClient(context) ?: return false
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, plan.title)
            plan.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            plan.posterUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(plan.url)
            // Buffered, not LIVE: the default receiver buffers whatever it is
            // given, and a live stream type would take the scrubber away from
            // the resume point this load is trying to honour.
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(plan.contentType)
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(plan.startPositionMs)
            .build()
        return try {
            client.load(request)
            true
        } catch (e: Exception) {
            Log.w(TAG, "cast load failed", e)
            false
        }
    }

    /** Pauses the receiver, if it is playing. */
    fun pause(context: Context) {
        remoteClient(context)?.pause()
    }

    /** Resumes the receiver, if it is paused. */
    fun resume(context: Context) {
        remoteClient(context)?.play()
    }

    /** Ends the session: the television goes back to its own screen. */
    fun disconnect(context: Context) {
        runCatching { castOf(context)?.sessionManager?.endCurrentSession(true) }
    }

    /**
     * Reports the receiver's position every [tickMs] to [onPosition]: position
     * in milliseconds, whether it is playing, and the item's duration (0 while
     * the receiver has not reported one). Returns whether a listener was
     * attached.
     */
    fun watchPosition(
        context: Context,
        tickMs: Long,
        onPosition: (positionMs: Long, playing: Boolean, durationMs: Long) -> Unit
    ): Boolean {
        val client = remoteClient(context) ?: return false
        stopWatching(context)
        val listener = RemoteMediaClient.ProgressListener { progress, _ ->
            val status = client.mediaStatus
            onPosition(
                progress.coerceAtLeast(0L),
                status?.playerState == MediaStatus.PLAYER_STATE_PLAYING,
                status?.mediaInfo?.streamDuration ?: 0L
            )
        }
        progressListener = listener
        return runCatching {
            client.addProgressListener(listener, tickMs)
            true
        }.getOrDefault(false)
    }

    /** Stops the position reports (a new cast replaces the old watch). */
    fun stopWatching(context: Context) {
        val listener = progressListener ?: return
        progressListener = null
        runCatching { remoteClient(context)?.removeProgressListener(listener) }
    }

    private fun noteSession(session: CastSession?) {
        val live = session?.isConnected == true
        _connected.value = live
        _deviceName.value = if (live) {
            session?.castDevice?.friendlyName ?: "Cast device"
        } else {
            null
        }
    }

    private fun publishRoutes(context: Context, selector: MediaRouteSelector) {
        val names = runCatching {
            MediaRouter.getInstance(context).routes
                .filter { route -> route.matchesSelector(selector) && !route.isDefault }
                .mapNotNull { route -> route.name?.toString()?.takeIf { it.isNotBlank() } }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
        _devices.value = names
    }
}
