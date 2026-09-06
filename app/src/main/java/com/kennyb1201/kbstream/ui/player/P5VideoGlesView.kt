package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/**
 * ViewGroup-based video renderer for P5 (ICtCp) content.
 *
 * Provides a Surface for ExoPlayer to render to, intercepts frames via
 * SurfaceTexture.OnFrameAvailableListener, applies the ICtCp→PQ conversion
 * shader, and renders to screen.
 */
class P5VideoGlesView(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs), SurfaceTexture.OnFrameAvailableListener {

    private var player: ExoPlayer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var videoTextureId = 0
    private var eglSurface: EGLSurface? = null
    private var eglContext: EGLContext? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglConfig: EGLConfig? = null
    private var gles3Available = false
    private var renderRequested = false
    private var viewWidth = 0
    private var viewHeight = 0

    init {
        gles3Available = checkGles3()
    }

    private fun checkGles3(): Boolean {
        return try {
            val method = GLES30::class.java.getDeclaredMethod("glClientWaitSync", Long::class.java, Int::class.java, Int::class.java)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setPlayer(player: ExoPlayer) {
        this.player = player
        videoTextureId = createTexture()
        surfaceTexture = SurfaceTexture(videoTextureId).apply {
            setOnFrameAvailableListener(this@P5VideoGlesView)
        }
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

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        renderRequested = true
        postInvalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (renderRequested) {
            renderRequested = false
            renderFrame()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // No child views to layout
    }

    private fun renderFrame() {
        val st = surfaceTexture ?: return
        val ply = player ?: return

        val vw = ply.videoSize.width()
        val vh = ply.videoSize.height()
        if (vw <= 0 || vh <= 0) return

        if (!makeCurrent()) return

        st.updateTexImage()

        val texMatrix = FloatArray(16)
        st.getTransformMatrix(texMatrix)

        val prog = P5ColorShader.getProgram()
        if (prog == 0) {
            releaseEgl()
            return
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        GLES20.glUseProgram(prog)
        P5ColorShader.uploadTexture(st)
        P5ColorShader.bindTextureUniform(prog)

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val vidAspect = if (vh > 0) vw.toFloat() / vh.toFloat() else 16f / 9f
        val viewAspect = viewW / viewH
        val scaleX: Float
        val scaleY: Float
        val offsetX: Float
        val offsetY: Float

        if (vidAspect > viewAspect) {
            scaleX = viewW
            scaleY = viewW / vidAspect
            offsetX = 0f
            offsetY = (viewH - scaleY) / 2f
        } else {
            scaleX = viewH * vidAspect
            scaleY = viewH
            offsetX = (viewW - scaleX) / 2f
            offsetY = 0f
        }

        renderQuad(prog, offsetX, offsetY, scaleX, scaleY, texMatrix)

        swapBuffers()
    }

    private fun renderQuad(
        program: Int,
        x: Float, y: Float,
        w: Float, h: Float,
        texMatrix: FloatArray
    ) {
        val vertices = floatArrayOf(
            x, y, 0f, 0f,
            x + w, y, 1f, 0f,
            x, y + h, 0f, 1f,
            x + w, y + h, 1f, 1f
        )

        val vertexBuf = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .position(0)

        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")

        if (posLoc >= 0) {
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuf)
            vertexBuf.position(0)
        }

        if (texLoc >= 0) {
            GLES20.glEnableVertexAttribArray(texLoc)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuf)
            vertexBuf.position(8)
        }

        val texMatrixLoc = GLES20.glGetUniformLocation(program, "texMatrix")
        if (texMatrixLoc >= 0) {
            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (posLoc >= 0) GLES20.glDisableVertexAttribArray(posLoc)
        if (texLoc >= 0) GLES20.glDisableVertexAttribArray(texLoc)
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == null) return

        if (EGL14.eglInitialize(eglDisplay, null, null) == EGL14.EGL_FALSE) return

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        val configs = Array(1) { EGLConfig() }
        val numConfigs = IntArray(1)
        if (EGL14.eglChooseConfig(eglDisplay, attribs, configs, 1, numConfigs) == EGL14.EGL_FALSE) return

        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.eglGetCurrentContext(), contextAttribs)

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, this.width.coerceAtLeast(1),
            EGL14.EGL_HEIGHT, this.height.coerceAtLeast(1),
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs)
    }

    private fun makeCurrent(): Boolean {
        if (eglDisplay == null || eglContext == null || eglSurface == null) return false
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext) == EGL14.EGL_TRUE
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
