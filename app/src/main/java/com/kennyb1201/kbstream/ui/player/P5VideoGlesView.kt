package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView-based video renderer for P5 (ICtCp) content, consuming
 * **raw decoded planes** instead of a decoder Surface.
 *
 * Architecture:
 *
 *   P5PlaneVideoRenderer (MediaCodec buffer mode, no Surface)
 *        → VideoDecoderOutputBuffer (raw Y/U/V plane ByteBuffers)
 *        → [VideoDecoderOutputBufferRenderer.setOutputBuffer] (this view)
 *        → GL upload (planar textures) → P5ColorShader (ICtCp → display)
 *        → Screen
 *
 * This is the same delivery contract media3's libdav1d / libgav1 extensions
 * use: the activity attaches this view with `player.setVideoSurfaceView`,
 * and because the view implements [VideoDecoderOutputBufferRenderer] the
 * player passes the view itself (not a Surface) to the video renderer. The
 * decoder-side raw planes are exactly what the Surface/SurfaceTexture path
 * could never provide — the display stack converts decoder-Surface output
 * before GLES can sample it, destroying the ICtCp samples.
 *
 * Threading: [setOutputBuffer] is called on the playback thread; the buffer
 * is parked in an atomic slot and released by the GL thread after its planes
 * have been uploaded (ownership returns to the decoder's pool, which is
 * concurrent). GL thread only ever touches `renderedBuffer`.
 */
@UnstableApi
class P5VideoGlesView(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer, VideoDecoderOutputBufferRenderer {

    /** Latest buffer handed over by the renderer — playback thread writer. */
    private val pendingBuffer = AtomicReference<VideoDecoderOutputBuffer?>()

    /** Buffer currently uploaded/rendered — GL thread only. */
    private var renderedBuffer: VideoDecoderOutputBuffer? = null

    init {
        setPreserveEGLContextOnPause(true)
        // GLES 3 context for the 10-bit (GL_R16) upload path; the shader is
        // GLSL ES 1.00 and runs in either context version.
        setEGLContextClientVersion(if (P5ColorShader.hasGles3()) 3 else 2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /**
     * Called by the video renderer (playback thread) with the next decoded
     * frame. The buffer stays valid until the GL thread has uploaded it and
     * a newer frame replaces it — release happens on the GL thread, returning
     * ownership to the decoder's buffer pool.
     */
    override fun setOutputBuffer(outputBuffer: VideoDecoderOutputBuffer) {
        pendingBuffer.getAndSet(outputBuffer)?.release()
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // The EGL context is fresh here — drop stale program/texture handles
        // so they are rebuilt in this context (ids from a dead context render
        // garbage with no GL error).
        P5ColorShader.reset()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        P5ColorShader.setViewportSize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val pending = pendingBuffer.getAndSet(null)
        if (pending != null) {
            renderedBuffer?.release()
            renderedBuffer = pending
        }
        val buffer = renderedBuffer
        if (buffer == null) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        if (P5ColorShader.getProgram() == 0) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        try {
            // Bind the program BEFORE the upload: uploadOutputBuffer sets
            // per-frame uniforms, and glUniform* only affects the currently
            // bound program — setting them with no program bound is
            // GL_INVALID_OPERATION and silently leaves uYScale/uLumaRange at
            // zero (constant-color output).
            P5ColorShader.bind()
            P5ColorShader.uploadOutputBuffer(buffer as P5OutputBuffer)
            P5ColorShader.bindTextures()
            // Clear first so the letterbox bars around an aspect-scaled quad
            // are black instead of stale framebuffer content.
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            P5ColorShader.renderFullscreenQuad()
            P5ColorShader.unbind()
        } catch (e: Exception) {
            // A frame that fails to upload must never wedge the GL loop;
            // release it and clear so the next frame can proceed.
            android.util.Log.e("P5_GLES", "Frame render failed", e)
            renderedBuffer?.release()
            renderedBuffer = null
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
    }

    /**
     * Detaches this view from any pending frames. The activity calls this
     * when the P5 GLES path deactivates or the activity is destroyed; the
     * player lifecycle itself stays with the activity.
     */
    fun release() {
        pendingBuffer.getAndSet(null)?.release()
        queueEvent {
            renderedBuffer?.release()
            renderedBuffer = null
        }
    }
}
