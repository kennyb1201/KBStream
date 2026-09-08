package com.kennyb1201.kbstream.ui.player

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES shader utilities for P5 (ICtCp) → display color conversion,
 * sampling **raw planar YUV textures** uploaded from [P5PlaneDecoder] output
 * buffers (MediaCodec buffer mode — no Surface, no automatic dataspace
 * conversion in between).
 *
 * Pipeline per pixel:
 *   1. Sample planar Y (I channel) / U (Ct) / V (Cp), cropping stride
 *      padding via scale uniforms.
 *   2. Expand limited (video) range → full range.
 *   3. PQ (ST 2084) decode each ICtCp component to linear IPT.
 *   4. ICtCp → linear Rec.2020 RGB (ST 2084 inverse + IPT→RGB matrix).
 *   5. Rec.2020 → Rec.709 gamut conversion, ACES tone-map to SDR range,
 *      gamma 2.2 encode. (A GLSurfaceView composites as an SDR surface, so
 *      re-emitting PQ would be misinterpreted by the display — tone-mapped
 *      SDR is the correct output for this view. True HDR10 output stays with
 *      the P5 → 8.1 bitstream-rewrite path.)
 *
 * The previous OES/SurfaceTexture variant of this shader sampled a hardware
 * dataspace-converted texture and could never receive real ICtCp samples —
 * that is why P5 rendered green/washed-out through the GLES path.
 */
internal object P5ColorShader {

    private const val TAG = "P5_SHADER"

    private const val VERTEX_SHADER = """
        attribute vec2 aPosition;
        varying vec2 vTexCoord;
        // Aspect-fit scale: letterboxes the quad to the video's display
        // aspect (see applyQuadScale) — without it a 2:1 or 2.39:1 stream
        // would be stretched to the full viewport.
        uniform vec2 uQuadScale;
        void main() {
            vTexCoord = aPosition * 0.5 + 0.5;
            gl_Position = vec4(aPosition * uQuadScale, 0.0, 1.0);
        }
    """

    private const val FRAGMENT_SHADER = """
        precision highp float;
        varying vec2 vTexCoord;
        uniform sampler2D texY;
        uniform sampler2D texU;
        uniform sampler2D texV;
        // Stride cropping: valid region of each plane as a fraction of the
        // uploaded texture (x), height fraction (y).
        uniform vec2 uYScale;
        uniform vec2 uUVScale;
        // Limited-range expansion: (offset, span) applied to the normalized
        // sample, per luma/chroma.
        uniform vec2 uLumaRange;
        uniform vec2 uChromaRange;

        // SMPTE ST 2084 (PQ) transfer function constants (ITU-R BT.2100):
        //   EOTF:  L = (max(V^(1/m2) - c1, 0) / (c2 - c3 * V^(1/m2)))^(1/m1)
        //   OETF:  V = ((c1 + c2 * L^m1) / (1 + c3 * L^m1))^m2
        const float PQ_M1 = 0.1593017578125;   // 2610 / 16384
        const float PQ_M2 = 78.84375;           // 2523 / 32
        const float PQ_C1 = 0.8359375;          // 3424 / 4096
        const float PQ_C2 = 18.8515625;         // 2413 / 128
        const float PQ_C3 = 18.6875;            // 2392 / 128

        // ICtCp → linear RGB, IPT primary matrix (BT.2100 / ST 2084 coding).
        const mat3 ICtCp_TO_LINEAR = mat3(
            1.0,  0.3479,  0.1193,
            1.0, -0.0378, -0.0550,
            1.0, -0.3101,  0.1744
        );

        // Linear Rec.2020 → Rec.709 (column-major for GLSL).
        const mat3 REC2020_TO_REC709 = mat3(
             1.6605, -0.1246, -0.0182,
            -0.5876,  1.1329, -0.1006,
            -0.0728, -0.0083,  1.1187
        );

        // PQ EOTF: decode a PQ-encoded sample back to linear light.
        float decodePQ(float pqValue) {
            float v = clamp(pqValue, 0.0, 1.0);
            float vp = pow(v, 1.0 / PQ_M2);
            float num = max(vp - PQ_C1, 0.0);
            float den = max(PQ_C2 - PQ_C3 * vp, 1e-6);
            return pow(num / den, 1.0 / PQ_M1);
        }

        // ACES (Narkowicz) filmic tone-map; input scaled so SDR reference
        // white (80 nits) maps to ~0.8 display-linear.
        vec3 toneMapACES(vec3 x) {
            return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
        }

        void main() {
            float y = texture2D(texY, vTexCoord * uYScale).r;
            float u = texture2D(texU, vTexCoord * uUVScale).r;
            float v = texture2D(texV, vTexCoord * uUVScale).r;

            // Limited → full range, then Ct/Cp centered on zero.
            float iVal = clamp((y - uLumaRange.x) / uLumaRange.y, 0.0, 1.0);
            float ctVal = clamp((u - uChromaRange.x) / uChromaRange.y, 0.0, 1.0) - 0.5;
            float cpVal = clamp((v - uChromaRange.x) / uChromaRange.y, 0.0, 1.0) - 0.5;

            float iLin = decodePQ(iVal);
            float ctLin = decodePQ(abs(ctVal)) * sign(ctVal);
            float cpLin = decodePQ(abs(cpVal)) * sign(cpVal);

            vec3 linear2020 = ICtCp_TO_LINEAR * vec3(iLin, ctLin, cpLin);
            linear2020 = max(linear2020, 0.0);

            vec3 linear709 = REC2020_TO_REC709 * linear2020;
            linear709 = max(linear709, 0.0);

            // 80-nit SDR reference white; highlights roll off to 1.0.
            vec3 sdr = toneMapACES(linear709 / 80.0);

            gl_FragColor = vec4(pow(sdr, vec3(1.0 / 2.2)), 1.0);
        }
    """

    private var program = 0
    private val texIds = IntArray(3)
    private var initialized = false

    private var locYScale = -1
    private var locUVScale = -1
    private var locLumaRange = -1
    private var locChromaRange = -1
    private var locQuadScale = -1

    // Viewport the quad is drawn into (set by P5VideoGlesView on surface
    // changes); used with the frame size to letterbox the quad.
    private var viewportWidth = 0
    private var viewportHeight = 0

    // Dimensions of the currently uploaded textures, to avoid redundant
    // re-uploads only when the frame changes anyway (uploads happen per frame).
    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    /**
     * Creates the shader program and the three planar textures on the calling
     * (GL) thread. Called lazily so first-touch from a non-GL thread
     * (e.g. [hasGles3]) never issues GL calls without a context.
     */
    private fun ensureInitialized() {
        if (initialized) return
        initialized = true
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        createTextures()
        if (program != 0) {
            locYScale = GLES20.glGetUniformLocation(program, "uYScale")
            locUVScale = GLES20.glGetUniformLocation(program, "uUVScale")
            locLumaRange = GLES20.glGetUniformLocation(program, "uLumaRange")
            locChromaRange = GLES20.glGetUniformLocation(program, "uChromaRange")
            locQuadScale = GLES20.glGetUniformLocation(program, "uQuadScale")
            for (i in 0 until 3) {
                GLES20.glUseProgram(program)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, TEXTURE_UNIFORMS[i]), i)
            }
            GLES20.glUseProgram(0)
        }
    }

    private val TEXTURE_UNIFORMS = arrayOf("texY", "texU", "texV")

    private fun createTextures() {
        GLES20.glGenTextures(3, texIds, 0)
        for (i in 0 until 3) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vShader == 0 || fShader == 0) {
            Log.e(TAG, "Shader compile failed: vertex=$vShader fragment=$fShader")
            return 0
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vShader)
        GLES20.glAttachShader(prog, fShader)
        GLES20.glBindAttribLocation(prog, 0, "aPosition")
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)
        return prog
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Shader compile failed (type=$type): ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /**
     * Records the GL viewport size (GL thread, from onSurfaceChanged). Used
     * with the decoded frame size to letterbox the quad to the video aspect.
     */
    fun setViewportSize(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    /**
     * Returns whether the GLES 3.0 API surface is available. The 10-bit
     * upload path (GL_R16) requires GLES 3.0; the conversion shader itself
     * only needs GLES 2.0.
     */
    fun hasGles3(): Boolean {
        return try {
            Class.forName("android.opengl.GLES30")
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** Returns the shader program (initializing on the GL thread if needed). */
    fun getProgram(): Int {
        ensureInitialized()
        return program
    }

    /**
     * Uploads an output buffer's raw planes into the three textures and sets
     * the per-frame uniforms (stride crop + limited-range expansion + aspect
     * letterbox). GL thread only, and the program MUST be bound first (call
     * [bind]) — glUniform* operates on the currently bound program.
     */
    fun uploadOutputBuffer(buffer: P5OutputBuffer) {
        ensureInitialized()
        val planes = buffer.yuvPlanes ?: return
        val strides = buffer.yuvStrides ?: return
        val width = buffer.width
        val height = buffer.height
        if (width <= 0 || height <= 0) return
        val chromaHeight = (height + 1) / 2
        val tenBit = buffer.tenBit

        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        // Luma
        uploadPlane(0, planes[0], strides[0], height, tenBit)
        // Chroma
        uploadPlane(1, planes[1], strides[1], chromaHeight, tenBit)
        uploadPlane(2, planes[2], strides[2], chromaHeight, tenBit)

        if (program != 0) {
            val ySamples = if (tenBit) strides[0] / 2 else strides[0]
            val uvSamples = if (tenBit) strides[1] / 2 else strides[1]
            applyQuadScale(width, height)
            if (locYScale >= 0) {
                GLES20.glUniform2f(locYScale, width.toFloat() / ySamples.toFloat(), 1f)
            }
            if (locUVScale >= 0) {
                val cw = (width + 1) / 2
                GLES20.glUniform2f(locUVScale, cw.toFloat() / uvSamples.toFloat(), 1f)
            }
            // Limited-range constants per ITU-R BT.709 / BT.2020 quantization.
            if (locLumaRange >= 0 && locChromaRange >= 0) {
                if (tenBit) {
                    if (buffer.colorRangeLimited) {
                        GLES20.glUniform2f(locLumaRange, 64f / 1023f, 876f / 1023f)
                        GLES20.glUniform2f(locChromaRange, 64f / 1023f, 896f / 1023f)
                    } else {
                        GLES20.glUniform2f(locLumaRange, 0f, 1f)
                        GLES20.glUniform2f(locChromaRange, 0f, 1f)
                    }
                } else {
                    if (buffer.colorRangeLimited) {
                        GLES20.glUniform2f(locLumaRange, 16f / 255f, 219f / 255f)
                        GLES20.glUniform2f(locChromaRange, 16f / 255f, 224f / 255f)
                    } else {
                        GLES20.glUniform2f(locLumaRange, 0f, 1f)
                        GLES20.glUniform2f(locChromaRange, 0f, 1f)
                    }
                }
            }
        }
    }

    /**
     * Scales the fullscreen quad to letterbox the video inside the viewport
     * (aspect-fit, matching PlayerView's RESIZE_MODE_FIT behavior). The area
     * outside the quad is cleared black by the view before rendering.
     */
    private fun applyQuadScale(width: Int, height: Int) {
        if (locQuadScale < 0 || viewportWidth <= 0 || viewportHeight <= 0 ||
            width <= 0 || height <= 0
        ) {
            return
        }
        val videoAspect = width.toFloat() / height.toFloat()
        val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (videoAspect > viewAspect) {
            scaleX = 1f
            scaleY = viewAspect / videoAspect
        } else {
            scaleX = videoAspect / viewAspect
            scaleY = 1f
        }
        GLES20.glUniform2f(locQuadScale, scaleX, scaleY)
    }

    /**
     * Uploads one plane. 8-bit → GL_LUMINANCE texture `stride` wide;
     * 10-bit → GLES30 GL_R16 texture `stride / 2` samples wide (the plane
     * holds little-endian 16-bit words with the 10-bit value in the high
     * bits, so the normalized 16-bit read equals the PQ sample directly).
     */
    private fun uploadPlane(index: Int, plane: ByteBuffer, stride: Int, height: Int, tenBit: Boolean) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[index])
        plane.position(0)
        if (tenBit) {
            GLES30.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0,                 GLES31.GL_R16,
                stride / 2, height, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_SHORT, plane
            )
        } else {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                stride, height, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, plane
            )
        }
    }

    /** Binds the three planar textures to texture units 0..2. */
    fun bindTextures() {
        for (i in 0 until 3) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[i])
        }
    }

    /** Binds the shader program (uniforms are set during upload). */
    fun bind() {
        ensureInitialized()
        GLES20.glUseProgram(program)
    }

    /** Unbind shader. */
    fun unbind() {
        GLES20.glUseProgram(0)
    }

    /** Renders a full-screen quad. */
    fun renderFullscreenQuad() {
        ensureInitialized()
        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
    }

    /**
     * Drops the compiled program / texture handles without issuing GL calls
     * (safe from any thread). Call when the GLSurfaceView's EGL context is
     * (re)created: ids from a dead context are invalid in the new one. The
     * next [ensureInitialized] then rebuilds everything in the fresh context.
     */
    fun reset() {
        program = 0
        texIds[0] = 0
        texIds[1] = 0
        texIds[2] = 0
        initialized = false
        locYScale = -1
        locUVScale = -1
        locLumaRange = -1
        locChromaRange = -1
        locQuadScale = -1
    }
}
