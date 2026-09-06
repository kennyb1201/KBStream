package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import javax.microedition.khronos.opengles.GL10
import javax.microedition.khronos.egl.EGLConfig

/**
 * GLSurfaceView-based video renderer for P5 (ICtCp) content.
 *
 * Provides a Surface for ExoPlayer to render to, intercepts frames via
 * SurfaceTexture.OnFrameAvailableListener, and renders through the
 * ICtCp→Rec.2020 PQ conversion shader.
 *
 * Architecture:
 *   ExoPlayer → Surface (from SurfaceTexture) → SurfaceTexture → GLES texture
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
    private var videoTextureId = 0

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        videoTextureId = createTexture()
        surfaceTexture = SurfaceTexture(videoTextureId)
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

    fun setPlayer(player: ExoPlayer) {
        this.player = player
        // Wait for onSurfaceCreated to create the surface and attach to player
        if (videoSurface != null) {
            player.setVideoSurface(videoSurface)
        }
    }

    fun release() {
        player?.setVideoSurface(null)
        player?.release()
        player = null
        surfaceTexture?.release()
        surfaceTexture = null
        videoSurface = null
    }

    private fun createTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val tid = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tid)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tid
    }
}
