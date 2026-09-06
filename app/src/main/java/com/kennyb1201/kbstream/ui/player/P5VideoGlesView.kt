package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
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
 */
class P5VideoGlesView(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private var player: ExoPlayer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    // Set when setPlayer() ran before the GL surface existed; onSurfaceCreated
    // then calls prepare() once the decoder has a Surface to output into.
    private var pendingPrepare = false

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
        surfaceTexture = SurfaceTexture(P5ColorShader.getTextureId())
        videoSurface = Surface(surfaceTexture)
        surfaceTexture?.setOnFrameAvailableListener(this)
        player?.setVideoSurface(videoSurface)
        if (pendingPrepare) {
            pendingPrepare = false
            player?.prepare()
        }
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
        // If the GL surface already exists, attach and start immediately;
        // otherwise onSurfaceCreated (GL thread) attaches and calls prepare
        // once the SurfaceTexture is live. prepare() is only called from the
        // view because the activity's non-GLES branch owns its own prepare.
        if (videoSurface != null) {
            player.setVideoSurface(videoSurface)
            player.prepare()
        } else {
            pendingPrepare = true
        }
    }

    fun release() {
        // Detach only — the activity owns the player lifecycle and releases it.
        player?.setVideoSurface(null)
        player = null
        pendingPrepare = false
        surfaceTexture?.release()
        surfaceTexture = null
        videoSurface = null
    }
}