package com.example.streaming

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.example.studio.ChromaKeyPreset
import com.example.studio.LayerChromaKeySettings
import com.example.studio.StudioWorkspaceReducer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Pure normalized UV rectangle derived from non-destructive layer crop bounds.
 */
data class CropUvBounds(
    val uMin: Float,
    val vMin: Float,
    val uMax: Float,
    val vMax: Float
) {
    val visibleWidthFraction: Float get() = (uMax - uMin).coerceAtLeast(0f)
    val visibleHeightFraction: Float get() = (vMax - vMin).coerceAtLeast(0f)
}

/**
 * Unified OpenGL ES 2.0/3.0 GLSL Chroma Key + Non-Destructive UV Crop Shader Specification.
 *
 * Supports both:
 * - Standard `sampler2D` textures (Images, GIFs, Website fixed-viewport frames, Overlays)
 * - External `samplerExternalOES` textures (`SurfaceTexture` for WebView / Video / CameraX)
 *
 * Zero bitmap cropping or View relayout is ever needed: `uCropRect` (`cropLeft, cropTop, cropRight, cropBottom`)
 * remaps texture UVs directly in the GPU fragment shader at 60-120fps alongside real-time YCbCr Chroma Key
 * and Black/Luma keying with Similarity, Smoothness, and Spill Reduction uniforms.
 */
object GlChromaShaderSpec {

    const val UNIFORM_CROP_RECT = "uCropRect"
    const val UNIFORM_OPACITY = "uOpacity"
    const val UNIFORM_CHROMA_ENABLED = "uChromaEnabled"
    const val UNIFORM_KEY_COLOR = "uKeyColor"
    const val UNIFORM_SIMILARITY = "uSimilarity"
    const val UNIFORM_SMOOTHNESS = "uSmoothness"
    const val UNIFORM_SPILL_REDUCTION = "uSpillReduction"
    const val UNIFORM_IS_LUMA_KEY = "uIsLumaKey"

    const val VERTEX_SHADER_SOURCE = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    private fun buildFragmentShader(useExternalOes: Boolean): String {
        val header = if (useExternalOes) {
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nuniform samplerExternalOES uTexture;"
        } else {
            "precision mediump float;\nuniform sampler2D uTexture;"
        }
        return """
            $header
            varying vec2 vTexCoord;
            uniform vec4 uCropRect;        // (cropLeft, cropTop, cropRight, cropBottom)
            uniform float uOpacity;
            uniform int uChromaEnabled;
            uniform vec3 uKeyColor;        // normalized RGB 0..1
            uniform float uSimilarity;     // 0..1
            uniform float uSmoothness;     // 0..1
            uniform float uSpillReduction; // 0..1
            uniform int uIsLumaKey;        // 1 for Black/Luma key, 0 for YCbCr chroma key

            vec2 rgbToCbCr(vec3 c) {
                float cb = -0.168736 * c.r - 0.331264 * c.g + 0.500000 * c.b;
                float cr =  0.500000 * c.r - 0.418688 * c.g - 0.081312 * c.b;
                return vec2(cb, cr);
            }

            void main() {
                float visW = max(0.05, 1.0 - uCropRect.x - uCropRect.z);
                float visH = max(0.05, 1.0 - uCropRect.y - uCropRect.w);
                vec2 croppedUv = vec2(
                    uCropRect.x + vTexCoord.x * visW,
                    uCropRect.y + vTexCoord.y * visH
                );
                if (croppedUv.x < 0.0 || croppedUv.x > 1.0 || croppedUv.y < 0.0 || croppedUv.y > 1.0) {
                    discard;
                }

                vec4 texColor = texture2D(uTexture, croppedUv);
                vec3 rgb = texColor.rgb;
                float alpha = texColor.a;

                if (uChromaEnabled == 1 && alpha > 0.002) {
                    float dist;
                    float simRadius;
                    float smoothBand = max(0.004, uSmoothness * 0.45);
                    if (uIsLumaKey == 1) {
                        float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
                        float keyLuma = dot(uKeyColor, vec3(0.299, 0.587, 0.114));
                        dist = abs(luma - keyLuma);
                        simRadius = uSimilarity * 0.65;
                    } else {
                        vec2 chroma = rgbToCbCr(rgb);
                        vec2 keyChroma = rgbToCbCr(uKeyColor);
                        dist = distance(chroma, keyChroma);
                        simRadius = uSimilarity * 0.52;
                    }

                    float mask = smoothstep(simRadius, simRadius + smoothBand, dist);
                    alpha *= mask;

                    if (uSpillReduction > 0.01 && uIsLumaKey == 0) {
                        float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
                        float spillVal = clamp(1.0 - dist / max(0.05, simRadius + smoothBand + 0.18), 0.0, 1.0);
                        rgb = mix(rgb, vec3(luma), spillVal * uSpillReduction);
                    }
                }

                gl_FragColor = vec4(rgb, alpha * uOpacity);
            }
        """.trimIndent()
    }

    val FRAGMENT_SHADER_2D: String = buildFragmentShader(useExternalOes = false)
    val FRAGMENT_SHADER_EXTERNAL_OES: String = buildFragmentShader(useExternalOes = true)

    /**
     * Pure-Kotlin helper computing safe UV crop bounds with the strict 5% minimum visible guarantee.
     */
    fun computeCropUvBounds(
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float
    ): CropUvBounds {
        val cl = if (cropLeft.isNaN() || cropLeft.isInfinite()) 0f else cropLeft.coerceIn(0f, StudioWorkspaceReducer.MAX_CROP_COMBINED)
        val cr = if (cropRight.isNaN() || cropRight.isInfinite()) 0f else cropRight.coerceIn(0f, (StudioWorkspaceReducer.MAX_CROP_COMBINED - cl).coerceAtLeast(0f))
        val ct = if (cropTop.isNaN() || cropTop.isInfinite()) 0f else cropTop.coerceIn(0f, StudioWorkspaceReducer.MAX_CROP_COMBINED)
        val cb = if (cropBottom.isNaN() || cropBottom.isInfinite()) 0f else cropBottom.coerceIn(0f, (StudioWorkspaceReducer.MAX_CROP_COMBINED - ct).coerceAtLeast(0f))
        val uMax = (1f - cr).coerceAtLeast(cl + StudioWorkspaceReducer.MIN_CROP_VISIBLE).coerceAtMost(1f)
        val vMax = (1f - cb).coerceAtLeast(ct + StudioWorkspaceReducer.MIN_CROP_VISIBLE).coerceAtMost(1f)
        return CropUvBounds(uMin = cl, vMin = ct, uMax = uMax, vMax = vMax)
    }

    /**
     * Extracts normalized RGB [0..1] triple from a packed ARGB Long hex color.
     */
    fun extractKeyRgb(colorHex: Long): FloatArray {
        val rgb = colorHex.toInt()
        return floatArrayOf(
            ((rgb shr 16) and 0xFF) / 255f,
            ((rgb shr 8) and 0xFF) / 255f,
            (rgb and 0xFF) / 255f
        )
    }

    fun isBlackLumaKey(settings: LayerChromaKeySettings): Boolean {
        if (settings.preset == ChromaKeyPreset.BLACK_LUMA) return true
        val rgb = extractKeyRgb(settings.keyColorHex)
        return rgb[0] < 0.08f && rgb[1] < 0.08f && rgb[2] < 0.08f
    }
}

/**
 * Lightweight, crash-guarded OpenGL ES 2.0 `TextureView` renderer executing [GlChromaShaderSpec]
 * for 60fps real-time UV cropping and Chroma Keying on Website, Image, GIF, and Overlay layers.
 */
class GlTextureSurfaceRenderer {

    companion object {
        private const val TAG = "GlTextureSurfaceRenderer"
        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
        // Flip V so Android Bitmap top-left (0,0) maps cleanly to screen top-left
        private val QUAD_TEX_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var programId: Int = 0
    private var textureId: Int = 0
    private var initialized = false

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(QUAD_VERTICES)
        .apply { position(0) }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(QUAD_TEX_COORDS)
        .apply { position(0) }

    /**
     * Initializes an EGL14 window surface bound to [surfaceTexture].
     * Returns false gracefully on software-only or headless test environments so caller can fall back to Canvas.
     */
    fun initialize(surfaceTexture: SurfaceTexture): Boolean {
        if (initialized) return true
        return runCatching {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
                release()
                return false
            }
            val config = configs[0] ?: run { release(); return false }

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                release()
                return false
            }

            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surfaceTexture, surfAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                release()
                return false
            }

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                release()
                return false
            }

            programId = createProgram(GlChromaShaderSpec.VERTEX_SHADER_SOURCE, GlChromaShaderSpec.FRAGMENT_SHADER_2D)
            if (programId == 0) {
                release()
                return false
            }

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            textureId = texIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            initialized = true
            true
        }.onFailure { e ->
            Log.w(TAG, "EGL/GLES20 init unavailable, falling back safely: ${e.message}")
            release()
        }.getOrDefault(false)
    }

    /**
     * Uploads [bitmap] (if provided) and draws one frame with real-time `uCropRect` + Chroma Key uniforms.
     */
    fun renderBitmapFrame(
        bitmap: Bitmap?,
        viewportWidth: Int,
        viewportHeight: Int,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        opacity: Float,
        chroma: LayerChromaKeySettings
    ): Boolean {
        if (!initialized || viewportWidth <= 0 || viewportHeight <= 0) return false
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false

        return runCatching {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            GLES20.glUseProgram(programId)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            val safeCrop = GlChromaShaderSpec.computeCropUvBounds(cropLeft, cropTop, cropRight, cropBottom)
            val clampedChroma = chroma.clamped()
            val keyRgb = GlChromaShaderSpec.extractKeyRgb(clampedChroma.keyColorHex)
            val isLuma = if (GlChromaShaderSpec.isBlackLumaKey(clampedChroma)) 1 else 0

            GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uTexture"), 0)
            GLES20.glUniform4f(
                GLES20.glGetUniformLocation(programId, GlChromaShaderSpec.UNIFORM_CROP_RECT),
                safeCrop.uMin,
                safeCrop.vMin,
                1f - safeCrop.uMax,
                1f - safeCrop.vMax
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(programId, GlChromaShaderSpec.UNIFORM_OPACITY),
                (opacity * clampedChroma.opacity).coerceIn(0f, 1f)
            )
            GLES20.glUniform1i(
                GLES20.glGetUniformLocation(programId, GlChromaShaderSpec.UNIFORM_CHROMA_ENABLED),
                if (clampedChroma.enabled) 1 else 0
            )
            GLES20.glUniform3f(
                GLES20.glGetUniformLocation(programId, GlChromaShaderSpec.UNIFORM_KEY_COLOR),
                keyRgb[0],
                keyRgb[1],
                keyRgb[2]
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(programId, GlChromaShaderSpec.UNIFORM_SIMILARITY),
                clampedChroma.similarity
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(programId, GlChromaShaderSpec.UNIFORM_SMOOTHNESS),
                clampedChroma.smoothness
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(programId, GlChromaShaderSpec.UNIFORM_SPILL_REDUCTION),
                clampedChroma.spillReduction
            )
            GLES20.glUniform1i(
                GLES20.glGetUniformLocation(programId, GlChromaShaderSpec.UNIFORM_IS_LUMA_KEY),
                isLuma
            )

            val posLoc = GLES20.glGetAttribLocation(programId, "aPosition")
            val texLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(texLoc)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(texLoc)

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }.onFailure { e ->
            Log.w(TAG, "GL render skipped safely: ${e.message}")
        }.getOrDefault(false)
    }

    fun release() {
        initialized = false
        runCatching {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            if (programId != 0) {
                GLES20.glDeleteProgram(programId)
                programId = 0
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        }
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linkStatus[0] == 0) {
            Log.w(TAG, "GL program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG, "GL shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
