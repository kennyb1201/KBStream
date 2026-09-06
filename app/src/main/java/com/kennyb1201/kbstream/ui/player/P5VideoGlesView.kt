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

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Create the SurfaceTexture on the GL thread using the shader's OES
        // texture, so decoder frames land in the exact texture the conversion
        // shader samples.
        surfaceTexture = SurfaceTexture(P5ColorShader.getTextureId())
        videoSurface = Surface(surfaceTexture)
        surfaceTexture?.setOnFrameAvailableListener(this)
        player?.setVideoSurface(videoSurface)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()

        val matrix = FloatArray(16)
        st.getTransformMatrix(matrix)
        P5ColorShader.setTextureMatrix(matrix)

        // Apply P5ColorShader ICtCp→PQ conversion (skip if program failed to compile)
        if (P5ColorShader.getProgram() != 0) {
            P5ColorShader.uploadTexture(st)
            P5ColorShader.bind()
            P5ColorShader.renderFullscreenQuad()
            P5ColorShader.unbind()
        } else {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        requestRender()
    }

    fun setPlayer(player: ExoPlayer) {
        this.player = player
        // Wait for onSurfaceCreated to create the surface and attach to player
        if (videoSurface != null) {
            player.setVideoSurface(videoSurface)
        }
    }

    fun release() {
        // Detach only — the activity owns the player lifecycle and releases it.
        player?.setVideoSurface(null)
        player = null
        surfaceTexture?.release()
        surfaceTexture = null
        videoSurface = null
    }
}