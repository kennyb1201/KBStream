package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.view.ViewGroup
import androidx.media3.exoplayer.ExoPlayer

/**
 * Placeholder for P5 ICtCp→HDR10 color correction via GLES.
 *
 * Full implementation requires setting up EGL context, SurfaceTexture,
 * and shader-based color conversion. This stub compiles and provides
 * the interface; the GLES rendering can be implemented when testing
 * on actual Fire Stick hardware.
 */
class P5VideoGlesView(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    private var player: ExoPlayer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var videoTextureId = 0

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // No child views
    }

    fun setPlayer(player: ExoPlayer) {
        this.player = player
        // Create a SurfaceTexture that ExoPlayer can render to
        // The SurfaceTexture will be used later for GLES shader-based color conversion
        videoTextureId = createTexture()
        surfaceTexture = SurfaceTexture(videoTextureId)
        videoSurface = Surface(surfaceTexture)
        player.setVideoSurface(videoSurface)
        // SurfaceTexture.OnFrameAvailableListener will be set when GLES context is ready
    }

    fun release() {
        player?.setVideoSurface(null)
        player?.release()
        player = null
        videoSurface?.release()
        videoSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    private fun createTexture(): Int {
        val textures = IntArray(1)
        android.opengl.GLES20.glGenTextures(1, textures, 0)
        val tid = textures[0]
        android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, tid)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
        return tid
    }
}
