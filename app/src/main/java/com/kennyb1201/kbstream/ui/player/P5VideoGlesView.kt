package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.AttributeSet
import android.view.Surface
import android.view.ViewGroup
import androidx.media3.exoplayer.ExoPlayer

/**
 * GLSurface-based video renderer for P5 (ICtCp) content.
 *
 * Provides a Surface for ExoPlayer to render to, intercepts frames via
 * SurfaceTexture.OnFrameAvailableListener, and renders through the
 * ICtCp→Rec.2020 PQ conversion shader.
 *
 * Architecture:
 *   ExoPlayer → Surface (from SurfaceTexture) → SurfaceTexture → GLES texture
 *                                                           ↓
 *                               GLS rendering with P5ColorShader conversion
 *                                                           ↓
 *                                                       Screen (EGL surface)
 */
class P5VideoGlesView(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs), SurfaceTexture.OnFrameAvailableListener {

    private var player: ExoPlayer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var videoTextureId = 0
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null
    private var gles3Available = false

    init {
        gles3Available = checkGles3()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // No child views to layout
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        renderFrame()
    }

    fun setPlayer(player: ExoPlayer) {
        this.player = player
        videoTextureId = createTexture()
        surfaceTexture = SurfaceTexture(videoTextureId)
        surfaceTexture?.setOnFrameAvailableListener(this)
        videoSurface = Surface(surfaceTexture)
        player.setVideoSurface(videoSurface)
        initEgl()
    }

    fun release() {
        player?.setVideoSurface(null)
        player?.release()
        player = null
        videoSurface?.release()
        videoSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        releaseEgl()
    }

    private fun checkGles3(): Boolean {
        return try {
            EGL14::class.java.getDeclaredMethod("eglGetCurrentContext")
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == null) return

        EGL14.eglInitialize(eglDisplay, null)

        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, configs, configs.size, numConfigs)

        if (configs[0] == null) return
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.eglGetCurrentContext(), ctxAttribs)
        if (eglContext == null) return

        val surfAttribs = intArrayOf(
            EGL14.EGL_WIDTH, this.width,
            EGL14.EGL_HEIGHT, this.height,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfAttribs)
    }

    private fun makeCurrent(): Boolean {
        if (eglDisplay == null || eglContext == null || eglSurface == null) return false
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun swapBuffers() {
        if (eglDisplay != null && eglSurface != null) {
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    private fun releaseEgl() {
        if (eglDisplay != null && eglSurface != null) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        if (eglDisplay != null && eglContext != null) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
        }
        eglSurface = null
        eglContext = null
        eglDisplay = null
        eglConfig = null
    }

    private fun renderFrame() {
        val st = surfaceTexture ?: return
        val ply = player ?: return

        val vw = ply.videoSize.width
        val vh = ply.videoSize.height
        if (vw <= 0 || vh <= 0) return

        if (!makeCurrent()) return

        st.updateTexImage()

        // TODO: Apply P5ColorShader conversion here
        // For now, just clear the screen (no shader applied)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        swapBuffers()
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
