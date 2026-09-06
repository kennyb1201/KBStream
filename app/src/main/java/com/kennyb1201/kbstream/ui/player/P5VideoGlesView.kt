package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView-based video renderer for P5 (ICtCp) content.
 *
 * Provides a Surface for ExoPlayer to render to, intercepts frames via
 * SurfaceTexture.OnFrameAvailableListener, and renders through the
 * ICtCp→Rec.2020 PQ conversion shader.
 *
 * Architecture:
 *   ExoPlayer → Surface (from SurfaceTexture) → SurfaceTexture → GLES OES texture
 *                                                           ↓
 *                               GLSurfaceView.Renderer with P5ColorShader
 *                                                           ↓
 *                                                       Screen
 *
 * Threading: the SurfaceTexture is created on the GL thread, but ExoPlayer
 * requires every player call on the thread the player was created on (main).
 * All setVideoSurface/prepare calls are therefore funneled through
 * [mainHandler]; the attach/prepare flags make delivery order-independent
 * between [setPlayer] (main) and [onSurfaceCreated] (GL thread).
 */
class P5VideoGlesView(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private var player: ExoPlayer? = null
    // Written on the GL thread; read on main only to seed attach() after
    // setPlayer(), where the GL-thread post already ran.
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // Main-thread only: which player/surface have been delivered, and whether
    // prepare() has been delivered for the current player.
    private var attachedPlayer: ExoPlayer? = null
    private var attachedSurface: Surface? = null
    private var prepareDelivered = false

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // The EGL context is fresh here — the previous one was destroyed with
        // the old surface. Drop stale program/texture handles so they are
        // rebuilt in this context; reusing ids from a dead context renders
        // garbage (green/black) with no GL error.
        P5ColorShader.reset()
        // Create the SurfaceTexture on the GL thread using the shader's OES
        // texture, so decoder frames land in the exact texture the conversion
        // shader samples.
        val st = SurfaceTexture(P5ColorShader.getTextureId())
        surfaceTexture = st
        val surface = Surface(st)
        videoSurface = surface
        st.setOnFrameAvailableListener(this)
        // New EGL surface → the player must be re-pointed at it. Hand the
        // player calls off to the main thread (post establishes a happens-
        // before edge, so the player/prepare state read inside is safe).
        mainHandler.post { attach(surface) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        // Exactly one updateTexImage() per frame: a second call for the same
        // buffer re-queues the previous frame and causes flicker / stale
        // output, and can throw when no new frame is pending.
        st.updateTexImage()

        val matrix = FloatArray(16)
        st.getTransformMatrix(matrix)
        P5ColorShader.setTextureMatrix(matrix)

        // Apply P5ColorShader ICtCp→PQ conversion (skip if program failed to compile)
        if (P5ColorShader.getProgram() != 0) {
            P5ColorShader.bindTexture()
            P5ColorShader.bind()
            P5ColorShader.renderFullscreenQuad()
            P5ColorShader.unbind()
        } else {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        requestRender()
    }

    fun setPlayer(player: ExoPlayer) {
        this.player = player
        prepareDelivered = false
        // If the GL surface already exists, attach and start immediately;
        // otherwise onSurfaceCreated (GL thread) creates it and posts the same
        // attach() here on main. prepare() is only called from the view
        // because the activity's non-GLES branch owns its own prepare.
        videoSurface?.let { attach(it) }
    }

    fun release() {
        // Detach only — the activity owns the player lifecycle and releases it.
        player?.setVideoSurface(null)
        player = null
        attachedPlayer = null
        attachedSurface = null
        prepareDelivered = false
        surfaceTexture?.release()
        surfaceTexture = null
        videoSurface = null
    }

    /** Main-thread only: deliver the surface and one prepare() to the player. */
    private fun attach(surface: Surface) {
        val p = player ?: return
        if (attachedPlayer !== p || attachedSurface !== surface) {
            attachedPlayer = p
            attachedSurface = surface
            p.setVideoSurface(surface)
        }
        if (!prepareDelivered) {
            prepareDelivered = true
            p.prepare()
        }
    }
}