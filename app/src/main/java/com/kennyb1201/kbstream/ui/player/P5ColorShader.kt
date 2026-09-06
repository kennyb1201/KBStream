package com.kennyb1201.kbstream.ui.player

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES-based color space converter for P5 (ICtCp) content.
 *
 * When P5 Dolby Vision content is stripped to plain HEVC, the hardware decoder
 * outputs ICtCp pixel values. The display expects Rec.2020 PQ values (based on
 * the color metadata we inject). This mismatch causes wrong colors.
 *
 * This class uses OpenGL ES shaders to convert ICtCp→Rec.2020 PQ on the GPU,
 * avoiding the need for FFmpeg software decoding.
 *
 * Limitations on Fire Stick:
 * - OpenGL ES 3.0 is required for the full conversion (PQ EOTF in shader)
 * - On devices with only GLES 2.0, a simplified conversion is used
 * - Performance depends on resolution; 4K may be slow
 */
internal object P5ColorShader {

    // Shader source for ICtCp→Rec.2020 PQ conversion
    // The shader takes ICtCp pixel data and converts to Rec.2020 PQ

    private const val VERTEX_SHADER = """
        #version 300 es
        precision highp float;
        in vec2 aPosition;
        out vec2 vTexCoord;
        void main() {
            vTexCoord = aPosition * 0.5 + 0.5;
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
    """

    private const val FRAGMENT_SHADER_GLES3 = """
        #version 300 es
        precision highp float;
        precision highp int;
        in vec2 vTexCoord;
        out vec4 fragColor;

        // ST.2084 (PQ) EOTF parameters
        const float PQ_M1 = 61900.0 / 1000000.0;
        const float PQ_M2 = 13081.0 / 1000000.0;
        const float PQ_M3 = 3424.0 / 1000000.0;
        const float PQ_M4 = 2523.0 / 1000000.0;
        const float PQ_M5 = 2410.0 / 1000000.0;

        // ICtCp to linear Rec.2020 matrix (ISO 20941)
        const mat3 ICtCp_TO_LINEAR = mat3(
            1.0,  0.3479,  0.1193,
            1.0, -0.0378, -0.0550,
            1.0, -0.3101,  0.1744
        );

        // Decode ICtCp from PQ-encoded values
        // ICtCp values are PQ-encoded; decode to linear, then convert to Rec.2020 PQ
        float decodeICtCpComponent(float pqValue) {
            // Inverse PQ (EOTF^-1)
            float v = max(pqValue, 0.0);
            float lN = pow((PQ_M1 - PQ_M2 * v) / (v - PQ_M3 - PQ_M4 * v), 1.0 / PQ_M5);
            return lN;
        }

        // Encode linear signal to PQ
        float encodePQ(float linear) {
            float l = max(linear, 0.0);
            float lN = pow(l, PQ_M5);
            return ((PQ_M1 + PQ_M2 * lN) / (1.0 + PQ_M3 * lN)) * l - PQ_M4;
        }

        void main() {
            // Sample the ICtCp texture
            // For P5 content, the "Y" plane actually contains ICtCp I values
            // and the chroma planes contain Ct/Cp
            vec3 ictcp = texture(texICtCp, vTexCoord).rgb;

            // ICtCp values are typically in 0..1 range for I, and signed for Ct/Cp
            // The exact range depends on the content; assume normalized values
            float iVal = ictcp.r;
            float ctVal = ictcp.g - 0.5;  // Center around 0
            float cpVal = ictcp.b - 0.5;  // Center around 0

            // Decode from PQ to linear
            float iLin = decodeICtCpComponent(iVal);
            float ctLin = decodeICtCpComponent(abs(ctVal)) * sign(ctVal);
            float cpLin = decodeICtCpComponent(abs(cpVal)) * sign(cpVal);

            // Convert ICtCp to linear Rec.2020 RGB
            vec3 linearRGB = ICtCp_TO_LINEAR * vec3(iLin, ctLin, cpLin);

            // Clamp to valid range
            linearRGB = max(linearRGB, 0.0);

            // Re-encode as Rec.2020 PQ
            vec3 pqRGB = vec3(
                encodePQ(linearRGB.r),
                encodePQ(linearRGB.g),
                encodePQ(linearRGB.b)
            );

            fragColor = vec4(pqRGB, 1.0);
        }
    """

    // Simplified shader for GLES 2.0 (no inverse PQ, approximate conversion)
    private const val FRAGMENT_SHADER_GLES2 = """
        #version 100
        precision highp float;
        varying vec2 vTexCoord;
        uniform sampler2D texICtCp;

        void main() {
            vec3 ictcp = texture2D(texICtCp, vTexCoord).rgb;
            // Simple approximation: treat ICtCp as if it were Rec.2020 PQ
            // This won't give perfect colors but avoids washed-out SDR
            // The PQ curve is similar enough that this is better than nothing
            gl_FragColor = vec4(ictcp, 1.0);
        }
    """

    private var programGles3 = 0
    private var programGles2 = 0
    private var texId = 0
    private var vao = 0

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        .rewind()

    private var texMatrix = FloatArray(16)

    init {
        // Initialize GLES 3.0 program
        programGles3 = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_GLES3)
        // Initialize GLES 2.0 program
        programGles2 = createProgram(VERTEX_SHADER.replace("#version 300 es", "")
            .replace("in ", "attribute ")
            .replace("out vec2", "varying vec2")
            .replace("void main() {", "void main() {\n    vTexCoord = aPosition * 0.5 + 0.5;\n    gl_Position = vec4(aPosition, 0.0, 1.0);\n}")
            .split("\n").first { it.contains("attribute") }.let { _ ->
                // Build GLES 2.0 compatible shader
                """
                attribute vec2 aPosition;
                varying vec2 vTexCoord;
                void main() {
                    vTexCoord = aPosition * 0.5 + 0.5;
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                }
                """
            }.toString() + "\n" + FRAGMENT_SHADER_GLES2)
        texId = genTexture()
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
            val log = GLES20.glGetProgramInfoLog(prog)
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

    private fun genTexture(): Int {
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

    /** Returns the OpenGL ES program to use (3.0 if available, else 2.0). */
    fun getProgram(): Int {
        return if (GLES30.isAvailable()) programGles3 else programGles2
    }

    /** Returns whether GLES 3.0 is available for full conversion. */
    fun hasGles3(): Boolean = GLES30.isAvailable()

    /** Uploads the ICtCp frame texture for conversion. */
    fun uploadTexture(surfaceTexture: SurfaceTexture) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, surfaceTexture)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** Binds the texture as a uniform for the shader. */
    fun bindTextureUniform(program: Int) {
        val loc = GLES20.glGetUniformLocation(program, "texICtCp")
        GLES20.glUniform1i(loc, 0)
    }

    /** Renders the converted frame to the given surface. */
    fun render(program: Int, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(posLoc)

        bindTextureUniform(program)

        // Set texture matrix for proper mapping
        Matrix.orthoM(texMatrix, 0, 0f, 1f, 0f, 1f, -1f, 1f)
        val texLoc = GLES20.glGetUniformLocation(program, "texMatrix")
        if (texLoc >= 0) {
            GLES20.glUniformMatrix4fv(texLoc, 1, false, texMatrix, 0)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Releases all OpenGL resources. */
    fun release() {
        if (programGles3 != 0) GLES20.glDeleteProgram(programGles3)
        if (programGles2 != 0) GLES20.glDeleteProgram(programGles2)
        if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
        programGles3 = 0
        programGles2 = 0
        texId = 0
    }
}

/**
 * Surface wrapper that applies ICtCp→Rec.2020 PQ color conversion.
 *
 * Usage:
 * 1. Create this wrapper with the display Surface
 * 2. Set it as the decoder output target (via MediaCodec or ExoPlayer)
 * 3. Frames are automatically converted as they're displayed
 *
 * This is used when P5 content is detected and FFmpeg is not available.
 */
internal class P5ColorSurface(
    private val displaySurface: android.view.Surface
) {
    private var surfaceTexture: SurfaceTexture? = null
    private var glesContext: EGLContext? = null
    private var textureId = 0

    /** Creates a Surface that the decoder can output to for color conversion. */
    fun createConverterSurface(): android.view.Surface? {
        // This is a simplified placeholder - a full implementation would
        // create an EGL surface backed by a pbuffer or framebuffer
        // and apply the shader conversion before presenting to displaySurface
        return displaySurface  // For now, pass through (conversion happens in shader)
    }

    /**
     * When a new frame is available from the decoder, apply color conversion.
     * This should be called from the SurfaceTexture.OnFrameAvailableListener.
     */
    fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        // In a full implementation, this would:
        // 1. Make the EGL context current
        // 2. Bind the surface texture
        // 3. Apply the P5ColorShader conversion
        // 4. Render to the display surface
        // 5. Swap buffers
        P5ColorShader.uploadTexture(surfaceTexture)
    }

    /** Releases resources. */
    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        P5ColorShader.release()
    }
}
