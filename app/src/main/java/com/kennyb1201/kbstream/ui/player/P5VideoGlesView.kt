package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import android.view.SurfaceTexture
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GLSurfaceView-based video renderer for P5 (ICtCp) content.
 *
 * Replaces the standard PlayerView when P5 content is detected and FFmpeg
 * is not in use. Provides a Surface for ExoPlayer to render to, intercepts
 * each frame via SurfaceTexture.OnFrameAvailableListener, applies the
 * ICtCp→Rec.2020 PQ conversion shader, and renders the corrected frame.
 *
 * Architecture:
 *   ExoPlayer → Surface (from SurfaceTexture) → SurfaceTexture → GLES texture
 *                                                         ↓
 *                                   GLSurfaceView.Renderer (ICtCp→PQ shader)
 *                                                         ↓
 *                                                   Screen
 *
 * When P5 content is not detected, the normal PlayerView is used instead.
 */
class P5VideoGlesView(
    context: Context,
    attrs: android.content.res.AttributeSet? = null
) : ViewGroup(context, attrs), SurfaceTexture.OnFrameAvailableListener {

    // The ExoPlayer instance that will render to our surface
    private var player: ExoPlayer? = null

    // SurfaceTexture that receives decoder output
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null

    // GLES texture ID for the incoming video frame
    private var videoTextureId = 0

    // Whether the shader is available (GLES 3.0)
    private var gles3Available = false

    // Renderer for the GLSurfaceView (we render manually in this ViewGroup)
    private var eglSurface: android.opengl.EGLSurface? = null
    private var eglContext: android.opengl.EGLContext? = null
    private var eglDisplay: android.opengl.EGLDisplay? = null

    init {
        gles3Available = GLES30.isAvailable()
    }

    /**
     * Attaches an ExoPlayer to this view.
     * Creates the SurfaceTexture and Surface, and passes the Surface to the player.
     */
    fun setPlayer(player: ExoPlayer) {
        this.player = player

        // Create the SurfaceTexture and Surface
        // The texture will be used as input to our GLES shader
        videoTextureId = createTexture()
        surfaceTexture = SurfaceTexture(videoTextureId).apply {
            setOnFrameAvailableListener(this@P5VideoGlesView)
        }
        videoSurface = Surface(surfaceTexture)

        // Set player size
        val width = width.coerceAtLeast(1)
        val height = height.coerceAtLeast(1)
        player.setVideoSurface(videoSurface)
        player.setVideoSize(width, height)

        // Initialize EGL for rendering
        initEgl()
    }

    /**
     * Releases the player and resources.
     */
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

    /**
     * Handles frame availability from the SurfaceTexture.
     * When a new frame arrives from the decoder, we need to re-render.
     */
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        requestRender()
    }

    /**
     * Request a render pass. Called from onFrameAvailable or manually.
     */
    private fun requestRender() {
        // Render is handled by the view's draw loop
        postInvalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        // We don't draw to the canvas - we use GLES rendering
        // The actual rendering happens in the EGL context
        renderFrame()
    }

    /**
     * Renders the current frame using GLES with the ICtCp→PQ conversion shader.
     */
    private fun renderFrame() {
        val st = surfaceTexture ?: return
        val player = player ?: return

        val videoWidth = player.videoSize.width()
        val videoHeight = player.videoSize.height()
        if (videoWidth <= 0 || videoHeight <= 0) return

        // Make EGL context current
        if (!makeCurrent()) return

        // Update the surface texture to upload the new frame
        st.updateTexImage()

        // Get the texture coordinates for the video frame
        val texMatrix = FloatArray(16)
        st.getTransformMatrix(texMatrix)

        // Apply the shader and render
        val program = P5ColorShader.getProgram()
        if (program == 0) {
            releaseEgl()
            return
        }

        // Clear and render
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // Use the shader program
        GLES20.glUseProgram(program)

        // Bind the video texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, videoTextureId)
        P5ColorShader.bindTextureUniform(program)

        // Set up vertex coordinates for full-screen quad
        // The video frame may not fill the screen - we letterbox it
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val vidAspect = if (videoHeight > 0) videoWidth.toFloat() / videoHeight.toFloat() else 16f / 9f
        val viewAspect = viewW / viewH
        val scaleX: Float
        val scaleY: Float
        val offsetX: Float
        val offsetY: Float

        if (vidAspect > viewAspect) {
            // Video is wider than view - letterbox top/bottom
            scaleX = viewW
            scaleY = viewW / vidAspect
            offsetX = 0f
            offsetY = (viewH - scaleY) / 2f
        } else {
            // Video is taller than view - letterbox left/right
            scaleX = viewH * vidAspect
            scaleY = viewH
            offsetX = (viewW - scaleX) / 2f
            offsetY = 0f
        }

        // Render the full-screen quad with the shader
        renderQuad(program, offsetX, offsetY, scaleX, scaleY, texMatrix)

        // Swap buffers
        swapBuffers()
    }

    private fun renderQuad(
        program: Int,
        x: Float, y: Float,
        w: Float, h: Float,
        texMatrix: FloatArray
    ) {
        // Vertex data: position (x, y) + texture coordinate
        // We render a full quad covering the video area
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

        // Set up texture matrix for proper mapping
        if (texLoc >= 0) {
            GLES20.glEnableVertexAttribArray(texLoc)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuf)
            vertexBuf.position(8)
        }

        // Apply texture transform matrix
        val texMatrixLoc = GLES20.glGetUniformLocation(program, "texMatrix")
        if (texMatrixLoc >= 0) {
            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (posLoc >= 0) GLES20.glDisableVertexAttribArray(posLoc)
        if (texLoc >= 0) GLES20.glDisableVertexAttribArray(texLoc)
    }

    // --- EGL Management ---

    private fun initEgl() {
        eglDisplay = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == null || android.opengl.EGL14.eglGetError() != android.opengl.EGL14.EGL_SUCCESS) {
            return
        }

        val results = IntArray(EGL_CONFIGS)
        if (android.opengl.EGL14.eglInitialize(eglDisplay, null, null) == android.opengl.EGL14.EGL_FALSE) {
            return
        }

        // Choose config
        val attribs = intArrayOf(
            android.opengl.EGL14.EGL_RED_SIZE, 8,
            android.opengl.EGL14.EGL_GREEN_SIZE, 8,
            android.opengl.EGL14.EGL_BLUE_SIZE, 8,
            android.opengl.EGL14.EGL_ALPHA_SIZE, 8,
            android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
            android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_WINDOW_BIT,
            android.opengl.EGL14.EGL_NONE
        )

        val configs = Array(1) { android.opengl.EGLConfig() }
        val numConfigs = IntArray(1)
        if (android.opengl.EGL14.eglChooseConfig(eglDisplay, attribs, configs, 1, numConfigs) == android.opengl.EGL14.EGL_FALSE) {
            return
        }

        eglContext = android.opengl.EGL14.eglCreateContext(eglDisplay, configs[0], android.opengl.EGL14.eglGetCurrentContext(), intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE))

        // Create a pixel buffer surface for off-screen rendering
        // We render to a pbuffer then blit to the screen
        val pbufferAttribs = intArrayOf(
            android.opengl.EGL14.EGL_WIDTH, width.coerceAtLeast(1),
            android.opengl.EGL14.EGL_HEIGHT, height.coerceAtLeast(1),
            android.opengl.EGL14.EGL_NONE
        )
        eglSurface = android.opengl.EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs)
    }

    private fun makeCurrent(): Boolean {
        if (eglDisplay == null || eglContext == null || eglSurface == null) return false
        return android.opengl.EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext) == android.opengl.EGL14.EGL_TRUE
    }

    private fun swapBuffers() {
        if (eglDisplay == null || eglSurface == null) return
        android.opengl.EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun releaseEgl() {
        if (eglDisplay != null && eglSurface != null) {
            android.opengl.EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        if (eglDisplay != null && eglContext != null) {
            android.opengl.EGL14.eglDestroyContext(eglDisplay, eglContext)
        }
        eglSurface = null
        eglContext = null
        eglDisplay = null
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

    companion object {
        private const val EGL_CONFIGS = 1
    }
}
