package com.kennyb1201.kbstream.ui.player

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** OpenGL ES shader utilities for P5 (ICtCp) → Rec.2020 PQ color conversion. */
internal object P5ColorShader {

    private const val VERTEX_SHADER = """
        attribute vec2 aPosition;
        varying vec2 vTexCoord;
        void main() {
            vTexCoord = aPosition * 0.5 + 0.5;
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
    """

    private const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES texICtCp;
        uniform mat4 texMatrix;

        // SMPTE ST 2084 (PQ) transfer function constants (ITU-R BT.2100):
        //   EOTF:  L = (max(V^(1/m2) - c1, 0) / (c2 - c3 * V^(1/m2)))^(1/m1)
        //   OETF:  V = ((c1 + c2 * L^m1) / (1 + c3 * L^m1))^m2
        const float PQ_M1 = 0.1593017578125;   // 2610 / 16384
        const float PQ_M2 = 78.84375;           // 2523 / 32
        const float PQ_C1 = 0.8359375;          // 3424 / 4096
        const float PQ_C2 = 18.8515625;         // 2413 / 128
        const float PQ_C3 = 18.6875;            // 2392 / 128

        const mat3 ICtCp_TO_LINEAR = mat3(
            1.0,  0.3479,  0.1193,
            1.0, -0.0378, -0.0550,
            1.0, -0.3101,  0.1744
        );

        // PQ EOTF: decode a PQ-encoded sample back to linear light.
        float decodeICtCpComponent(float pqValue) {
            float v = max(pqValue, 0.0);
            float vp = pow(v, 1.0 / PQ_M2);
            float num = max(vp - PQ_C1, 0.0);
            float den = max(PQ_C2 - PQ_C3 * vp, 1e-6);
            return pow(num / den, 1.0 / PQ_M1);
        }

        // PQ OETF: re-encode linear light as a PQ sample.
        float encodePQ(float linear) {
            float l = max(linear, 0.0);
            float lp = pow(l, PQ_M1);
            return pow((PQ_C1 + PQ_C2 * lp) / (1.0 + PQ_C3 * lp), PQ_M2);
        }

        void main() {
            vec3 ictcp = texture2D(texICtCp, (texMatrix * vec4(vTexCoord, 0.0, 1.0)).xy).rgb;
            float iVal = ictcp.r;
            float ctVal = ictcp.g - 0.5;
            float cpVal = ictcp.b - 0.5;

            float iLin = decodeICtCpComponent(iVal);
            float ctLin = decodeICtCpComponent(abs(ctVal)) * sign(ctVal);
            float cpLin = decodeICtCpComponent(abs(cpVal)) * sign(cpVal);

            vec3 linearRGB = ICtCp_TO_LINEAR * vec3(iLin, ctLin, cpLin);
            linearRGB = max(linearRGB, 0.0);

            vec3 pqRGB = vec3(
                encodePQ(linearRGB.r),
                encodePQ(linearRGB.g),
                encodePQ(linearRGB.b)
            );

            gl_FragColor = vec4(pqRGB, 1.0);
        }
    """

    private var program = 0
    private var texId = 0
    private var initialized = false

    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    private val texMatrix = FloatArray(16)

    /**
     * Creates the shader program and OES texture on the calling (GL) thread.
     * Called lazily so first-touch from a non-GL thread (e.g. hasGles3())
     * never issues GL calls without a context.
     */
    private fun ensureInitialized() {
        if (initialized) return
        initialized = true
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        texId = createTexture()
        Matrix.setIdentityM(texMatrix, 0)
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vShader == 0 || fShader == 0) {
            Log.e("P5_SHADER", "Shader compile failed: vertex=$vShader fragment=$fShader")
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
            Log.e("P5_SHADER", "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
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
            Log.e("P5_SHADER", "Shader compile failed (type=$type): ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun createTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val tid = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tid)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tid
    }

    /**
     * Returns whether the GLES 3.0 API surface is available.
     *
     * The conversion shader only needs GLES 2.0 (samplerExternalOES), so this
     * is deliberately a loose availability check: the previous reflection probe
     * passed the wrong parameter types for glClientWaitSync and always returned
     * false, silently disabling the GLES path.
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

    /** Returns the OES texture used for the SurfaceTexture (initializing on the GL thread). */
    fun getTextureId(): Int {
        ensureInitialized()
        return texId
    }

    /** Applies the SurfaceTexture crop/transform matrix. */
    fun setTextureMatrix(matrix: FloatArray) {
        System.arraycopy(matrix, 0, texMatrix, 0, 16)
    }

    /** Binds the OES texture for sampling (the caller already updated the frame). */
    fun bindTexture() {
        ensureInitialized()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
    }

    /** Binds the texture uniform. */
    fun bindTextureUniform(prog: Int) {
        val loc = GLES20.glGetUniformLocation(prog, "texICtCp")
        if (loc >= 0) GLES20.glUniform1i(loc, 0)
        val matLoc = GLES20.glGetUniformLocation(prog, "texMatrix")
        if (matLoc >= 0) GLES20.glUniformMatrix4fv(matLoc, 1, false, texMatrix, 0)
    }

    /** Bind shader and set common state. */
    fun bind() {
        ensureInitialized()
        GLES20.glUseProgram(program)
        bindTextureUniform(program)
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

    /** Releases resources. */
    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        if (texId != 0) {
            val textures = intArrayOf(texId)
            GLES20.glDeleteTextures(1, textures, 0)
        }
        program = 0
        texId = 0
        initialized = false
    }

    /**
     * Drops the compiled program / texture handles without issuing GL calls
     * (safe from any thread). Call when the GLSurfaceView's EGL context is
     * (re)created: ids from a dead context are invalid in the new one, so
     * reusing them renders garbage (green/black) with no GL error. The next
     * [ensureInitialized] then rebuilds everything in the fresh context.
     */
    fun reset() {
        program = 0
        texId = 0
        initialized = false
    }
}