package com.kennyb1201.kbstream.ui.player

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
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
        precision highp float;
        varying vec2 vTexCoord;
        uniform sampler2D texICtCp;
        uniform mat4 texMatrix;

        const float PQ_M1 = 61900.0 / 1000000.0;
        const float PQ_M2 = 13081.0 / 1000000.0;
        const float PQ_M3 = 3424.0 / 1000000.0;
        const float PQ_M4 = 2523.0 / 1000000.0;
        const float PQ_M5 = 2410.0 / 1000000.0;

        const mat3 ICtCp_TO_LINEAR = mat3(
            1.0,  0.3479,  0.1193,
            1.0, -0.0378, -0.0550,
            1.0, -0.3101,  0.1744
        );

        float decodeICtCpComponent(float pqValue) {
            float v = max(pqValue, 0.0);
            float lN = pow((PQ_M1 - PQ_M2 * v) / (v - PQ_M3 - PQ_M4 * v), 1.0 / PQ_M5);
            return lN;
        }

        float encodePQ(float linear) {
            float l = max(linear, 0.0);
            float lN = pow(l, PQ_M5);
            return ((PQ_M1 + PQ_M2 * lN) / (1.0 + PQ_M3 * lN)) * l - PQ_M4;
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

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        .position(0)

    private val texMatrix = FloatArray(16)

    init {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        texId = createTexture()
        Matrix.setIdentityM(texMatrix, 0)
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vShader)
        GLES20.glAttachShader(prog, fShader)
        GLES20.glBindAttribLocation(prog, 0, "aPosition")
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] != GLES20.GL_TRUE) {
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
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
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

    /** Returns whether GLES 3.0 is available. */
    fun hasGles3(): Boolean {
        return try {
            GLES30::class.java.getDeclaredMethod("glClientWaitSync", Long::class.java, Int::class.java, Int::class.java)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Returns the shader program. */
    fun getProgram(): Int = program

    /** Uploads the SurfaceTexture as a GLES texture. */
    fun uploadTexture(surfaceTexture: SurfaceTexture) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        surfaceTexture.updateTexImage()
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
        GLES20.glUseProgram(program)
        bindTextureUniform(program)
    }

    /** Unbind shader. */
    fun unbind() {
        GLES20.glUseProgram(0)
    }

    /** Renders a full-screen quad. */
    fun renderFullscreenQuad() {
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
    }
}
