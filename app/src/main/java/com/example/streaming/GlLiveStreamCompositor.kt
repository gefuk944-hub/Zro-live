package com.example.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.example.R
import com.example.studio.LayerScaleMode
import com.example.studio.LayerSourceType
import com.example.studio.SceneLayer
import com.example.studio.StudioWorkspaceState
import com.example.studio.WebLayerManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure GPU Zero-Copy OpenGL ES 2.0 Live Broadcast Compositor.
 *
 * Pipeline:
 *   `VirtualDisplay` -> `SurfaceTexture` (`GL_TEXTURE_EXTERNAL_OES`) -> OpenGL ES Compositor -> `MediaCodec.createInputSurface()`
 *
 * Key optimizations for BGMI & low-end Android devices:
 * 1. **Zero-Copy Gameplay Capture**: Gameplay frames flow directly through GPU external OES
 *    textures with zero CPU `ImageReader` readback and zero `Bitmap.compress()`.
 * 2. **Strict 16:9 Landscape Aspect Lock**: Uses pre-allocated aspect-fit / aspect-fill
 *    matrices so 20:9 ultrawide phones never stretch or rotate sideways on 1280x720 / 1920x1080 output.
 * 3. **Zero-Allocation `onDrawFrame()`**: All vertex/texCoord buffers, 4x4 matrices, shader handles,
 *    and texture slots are pre-allocated at initialization.
 * 4. **Overlay Upload Throttling**:
 *    - Static PNG/JPG/WebP image overlays upload to GPU ONLY when their URI/revision changes.
 *    - WebView overlays throttle texture uploads to 10–15 FPS (`WEB_OVERLAY_UPLOAD_INTERVAL_MS = 80ms`).
 *    - GPU Chroma Key & normalized UV crop run in fragment shader via [GlChromaShaderSpec].
 * 5. **Monotonic Presentation Timestamps**: Calls `EGLExt.eglPresentationTimeANDROID()` using the
 *    exact monotonic `System.nanoTime()` clock shared with `ZroAudioEngine` for drift-free A/V sync.
 */
class GlLiveStreamCompositor(
    private val context: Context,
    val outputWidth: Int,
    val outputHeight: Int,
    private val targetFps: Int = DEFAULT_FPS,
    private val lockLandscape16x9: Boolean = true
) {
    companion object {
        private const val TAG = "GlLiveStreamCompositor"
        const val DEFAULT_FPS = 30
        const val WEB_OVERLAY_UPLOAD_INTERVAL_MS = 80L // ~12.5 FPS (within 10–15 FPS budget)
        private const val MAX_OVERLAY_SLOTS = 12
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val OES_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform vec4 uCropRect;
            uniform float uAlpha;
            varying vec2 vTexCoord;
            void main() {
                vec2 uv = clamp(vTexCoord, vec2(0.0), vec2(1.0));
                float visW = max(0.05, 1.0 - uCropRect.x - uCropRect.z);
                float visH = max(0.05, 1.0 - uCropRect.y - uCropRect.w);
                vec2 croppedUv = vec2(
                    uCropRect.x + uv.x * visW,
                    uCropRect.y + uv.y * visH
                );
                vec4 c = texture2D(uTexture, croppedUv);
                gl_FragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """

        private const val OVERLAY_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMvpMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // Fullscreen quad: X, Y
        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )

        // OpenGL texture coordinates (bottom-left origin)
        private val QUAD_TEX_COORDS_OES = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        // Bitmap/Android 2D texture coordinates (top-left origin flipped Y for GLUtils.texImage2D)
        private val QUAD_TEX_COORDS_2D = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )

        /**
         * Computes a pure 4x4 scale matrix that maps a source frame ([srcWidth] x [srcHeight])
         * cleanly into the locked output canvas ([dstWidth] x [dstHeight]) without stretching
         * or rotating sideways (e.g., 20:9 ultrawide gameplay into locked 16:9 landscape output).
         *
         * Writes directly into [outMatrix] (length >= 16) with zero allocations.
         */
        fun computeAspectLockMatrix(
            srcWidth: Int,
            srcHeight: Int,
            dstWidth: Int,
            dstHeight: Int,
            fillMode: Boolean,
            outMatrix: FloatArray
        ) {
            require(outMatrix.size >= 16) { "outMatrix must have at least 16 elements" }
            setIdentity(outMatrix)
            if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) return

            val srcAspect = srcWidth.toFloat() / srcHeight.toFloat()
            val dstAspect = dstWidth.toFloat() / dstHeight.toFloat()
            if (kotlin.math.abs(srcAspect - dstAspect) < 0.002f) return

            val scaleX: Float
            val scaleY: Float
            if (fillMode) {
                // Aspect-Fill (center crop): no letterboxing, no distortion
                if (srcAspect > dstAspect) {
                    scaleX = srcAspect / dstAspect
                    scaleY = 1f
                } else {
                    scaleX = 1f
                    scaleY = dstAspect / srcAspect
                }
            } else {
                // Aspect-Fit (letterbox/pillarbox): preserve full HUD, zero stretching
                if (srcAspect > dstAspect) {
                    scaleX = 1f
                    scaleY = dstAspect / srcAspect
                } else {
                    scaleX = srcAspect / dstAspect
                    scaleY = 1f
                }
            }
            outMatrix[0] = scaleX
            outMatrix[5] = scaleY
        }

        /**
         * Builds a layer placement MVP matrix in OpenGL NDC space `[-1, 1]` from normalized
         * top-left Studio coordinates `(normX, normY, normW, normH)` with zero allocations.
         */
        fun computeLayerNdcMatrix(
            normX: Float,
            normY: Float,
            normW: Float,
            normH: Float,
            outMatrix: FloatArray
        ) {
            require(outMatrix.size >= 16) { "outMatrix must have at least 16 elements" }
            setIdentity(outMatrix)
            val safeW = normW.coerceIn(0.01f, 2f)
            val safeH = normH.coerceIn(0.01f, 2f)
            val centerNdcX = (normX + safeW * 0.5f) * 2f - 1f
            val centerNdcY = 1f - (normY + safeH * 0.5f) * 2f
            outMatrix[0] = safeW
            outMatrix[5] = safeH
            outMatrix[12] = centerNdcX
            outMatrix[13] = centerNdcY
        }

        private fun setIdentity(m: FloatArray) {
            for (i in 0 until 16) m[i] = 0f
            m[0] = 1f
            m[5] = 1f
            m[10] = 1f
            m[15] = 1f
        }
    }

    /** Pre-allocated texture slot for static PNG overlays or throttled WebView overlays. */
    private class OverlayTextureSlot(val textureId: Int) {
        var layerId: String = ""
        var contentKey: String = ""
        var lastUploadElapsedMs: Long = 0L
        var lastWebRevision: Long = -1L
        var hasValidTexture: Boolean = false
        var lastUsedFrame: Long = 0L
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesProgram = 0
    private var oesPosLoc = -1
    private var oesTexLoc = -1
    private var oesMvpLoc = -1
    private var oesTexMatrixLoc = -1
    private var oesCropLoc = -1
    private var oesAlphaLoc = -1
    private var oesSamplerLoc = -1

    private var overlayProgram = 0
    private var overlayPosLoc = -1
    private var overlayTexLoc = -1
    private var overlayMvpLoc = -1
    private var overlaySamplerLoc = -1
    private var overlayCropLoc = -1
    private var overlayKeyColorLoc = -1
    private var overlayChromaEnabledLoc = -1
    private var overlaySimLoc = -1
    private var overlaySmoothLoc = -1
    private var overlaySpillLoc = -1
    private var overlayIsLumaLoc = -1
    private var overlayAlphaLoc = -1

    private var oesTextureId = 0
    private var statusCardTextureId = 0
    private var statusCardUploaded = false

    private var surfaceTexture: SurfaceTexture? = null
    private var inputVirtualSurface: Surface? = null

    val virtualDisplaySurface: Surface?
        get() = inputVirtualSurface

    private val frameAvailable = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val sourceWidth = AtomicInteger(outputWidth)
    private val sourceHeight = AtomicInteger(outputHeight)

    // Pre-allocated zero-GC buffers & matrices used inside onDrawFrame()
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_VERTICES); position(0) }

    private val texCoordOesBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_TEX_COORDS_OES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_TEX_COORDS_OES); position(0) }

    private val texCoord2DBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_TEX_COORDS_2D.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_TEX_COORDS_2D); position(0) }

    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val layerMvpMatrix = FloatArray(16)
    private val tempCombinedMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).also { setIdentity(it) }
    private val overlaySlots = ArrayList<OverlayTextureSlot>(MAX_OVERLAY_SLOTS)
    private val failedImageKeys = HashSet<String>()
    private var frameCounter = 0L

    /** Strict frame interval in nanoseconds (defaults to 33.33ms for 30 FPS). */
    val frameIntervalNs: Long = 1_000_000_000L / targetFps.coerceIn(15, 60)

    /**
     * Initializes EGL14 on the caller's dedicated GL thread and binds to [encoderInputSurface]
     * (from `MediaCodec.createInputSurface()`). Returns `false` cleanly if EGL is unavailable.
     */
    fun initialize(encoderInputSurface: Surface, callbackHandler: Handler): Boolean {
        if (initialized.get()) return true
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
            eglDisplay = display

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] <= 0 || configs[0] == null
            ) {
                release()
                return false
            }
            val config = configs[0]!!

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
                release()
                return false
            }
            eglContext = ctx

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            val surf = EGL14.eglCreateWindowSurface(display, config, encoderInputSurface, surfaceAttribs, 0)
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) {
                release()
                return false
            }
            eglSurface = surf

            if (!EGL14.eglMakeCurrent(display, surf, surf, ctx)) {
                release()
                return false
            }

            // Compile OES gameplay shader + 2D overlay Chroma/Crop shader
            oesProgram = buildProgram(OES_VERTEX_SHADER, OES_FRAGMENT_SHADER)
            overlayProgram = buildProgram(
                OVERLAY_VERTEX_SHADER,
                GlChromaShaderSpec.FRAGMENT_SHADER_2D
            )
            if (oesProgram == 0 || overlayProgram == 0) {
                release()
                return false
            }
            cacheUniformLocations()

            // Allocate external OES texture for VirtualDisplay zero-copy input
            val texIds = IntArray(1 + 1 + MAX_OVERLAY_SLOTS)
            GLES20.glGenTextures(texIds.size, texIds, 0)
            oesTextureId = texIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Pre-allocate 2D texture for Privacy Shield / BRB card
            statusCardTextureId = texIds[1]
            configure2DTexture(statusCardTextureId)

            // Pre-allocate overlay texture slot pool (zero allocations inside onDrawFrame)
            overlaySlots.clear()
            for (i in 0 until MAX_OVERLAY_SLOTS) {
                val id = texIds[2 + i]
                configure2DTexture(id)
                overlaySlots.add(OverlayTextureSlot(id))
            }

            // Create SurfaceTexture + Surface for VirtualDisplay
            val st = SurfaceTexture(oesTextureId).apply {
                setDefaultBufferSize(outputWidth, outputHeight)
                setOnFrameAvailableListener({
                    frameAvailable.set(true)
                }, callbackHandler)
            }
            surfaceTexture = st
            inputVirtualSurface = Surface(st)
            setIdentity(stMatrix)

            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            initialized.set(true)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "GPU OpenGL compositor unavailable (${t.message}); falling back safely")
            release()
            false
        }
    }

    fun updateSourceDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            sourceWidth.set(width)
            sourceHeight.set(height)
            runCatching { surfaceTexture?.setDefaultBufferSize(width, height) }
        }
    }

    /**
     * Zero-allocation per-frame GPU composite + monotonic presentation timestamp submit.
     * Must be invoked on the same GL thread that called [initialize].
     */
    fun onDrawFrame(
        presentationTimeNs: Long,
        workspace: StudioWorkspaceState,
        privacyShieldActive: Boolean,
        remoteGameplayBitmap: Bitmap? = null
    ): Boolean {
        if (!initialized.get() || eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        frameCounter++

        if (frameAvailable.getAndSet(false)) {
            runCatching {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(stMatrix)
            }
        }

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0.039f, 0.055f, 0.086f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 1. Privacy Shield or BRB card override (zero-alloc cached texture)
        if (privacyShieldActive || workspace.brbActive) {
            ensureStatusCardUploaded()
            if (statusCardUploaded) {
                draw2DQuad(
                    textureId = statusCardTextureId,
                    mvp = identityMatrix,
                    cropLeft = 0f,
                    cropTop = 0f,
                    cropRight = 0f,
                    cropBottom = 0f,
                    alpha = 1f,
                    chromaEnabled = false,
                    isLumaKey = false,
                    keyR = 0f,
                    keyG = 1f,
                    keyB = 0f,
                    similarity = 0.35f,
                    smoothness = 0.1f,
                    spill = 0.2f
                )
            }
            return submitFrame(presentationTimeNs)
        }

        val activeScene = workspace.activeScene
        val layers = activeScene.layers
        var drewGameplay = false

        // 2. Render Gameplay base layer (pure zero-copy OES or 2nd-phone mirrored frame)
        for (i in 0 until layers.size) {
            val layer = layers[i]
            if (!layer.visible || layer.type != LayerSourceType.GAMEPLAY_SCREEN) continue
            drewGameplay = true
            drawGameplayLayer(layer, remoteGameplayBitmap)
        }
        if (!drewGameplay) {
            // Even if user hid gameplay layer in a custom scene, keep aspect-locked gameplay if no static BG replaces it
            val hasFullBg = hasVisibleFullBackground(layers)
            if (!hasFullBg) {
                computeAspectLockMatrix(
                    srcWidth = sourceWidth.get(),
                    srcHeight = sourceHeight.get(),
                    dstWidth = outputWidth,
                    dstHeight = outputHeight,
                    fillMode = lockLandscape16x9,
                    outMatrix = mvpMatrix
                )
                drawOesQuad(
                    mvp = mvpMatrix,
                    texMat = stMatrix,
                    cropLeft = 0f,
                    cropTop = 0f,
                    cropRight = 0f,
                    cropBottom = 0f,
                    alpha = 1f
                )
            }
        }

        // 3. Render Studio Overlays (static PNG/image uploaded on change; WebView throttled to 10–15 FPS)
        val nowElapsedMs = SystemClock.elapsedRealtime()
        for (i in 0 until layers.size) {
            val layer = layers[i]
            if (!layer.visible) continue
            if (layer.type == LayerSourceType.GAMEPLAY_SCREEN) continue
            drawOverlayLayer(layer, nowElapsedMs)
        }

        return submitFrame(presentationTimeNs)
    }

    private fun hasVisibleFullBackground(layers: List<SceneLayer>): Boolean {
        for (i in 0 until layers.size) {
            val l = layers[i]
            if (l.visible && (l.type == LayerSourceType.BACKGROUND || l.type == LayerSourceType.IMAGE) &&
                l.width >= 0.95f && l.height >= 0.95f && !l.mediaUri.isNullOrBlank()
            ) {
                return true
            }
        }
        return false
    }

    private fun drawGameplayLayer(layer: SceneLayer, remoteGameplayBitmap: Bitmap?) {
        val fillMode = layer.scaleMode != LayerScaleMode.FIT
        val cropL = layer.cropLeft.coerceIn(0f, 0.9f)
        val cropT = layer.cropTop.coerceIn(0f, 0.9f)
        val cropR = layer.cropRight.coerceIn(0f, 0.9f)
        val cropB = layer.cropBottom.coerceIn(0f, 0.9f)

        computeLayerNdcMatrix(layer.x, layer.y, layer.width, layer.height, layerMvpMatrix)
        if (remoteGameplayBitmap != null && !remoteGameplayBitmap.isRecycled) {
            val slot = acquireOverlaySlot("__remote_gameplay__")
            uploadBitmapToTexture(slot.textureId, remoteGameplayBitmap)
            slot.hasValidTexture = true
            slot.lastUsedFrame = frameCounter
            computeAspectLockMatrix(
                srcWidth = remoteGameplayBitmap.width,
                srcHeight = remoteGameplayBitmap.height,
                dstWidth = outputWidth,
                dstHeight = outputHeight,
                fillMode = fillMode,
                outMatrix = mvpMatrix
            )
            Matrix.multiplyMM(tempCombinedMatrix, 0, layerMvpMatrix, 0, mvpMatrix, 0)
            draw2DQuad(
                textureId = slot.textureId,
                mvp = tempCombinedMatrix,
                cropLeft = cropL,
                cropTop = cropT,
                cropRight = cropR,
                cropBottom = cropB,
                alpha = layer.opacity.coerceIn(0f, 1f),
                chromaEnabled = false,
                isLumaKey = false,
                keyR = 0f,
                keyG = 1f,
                keyB = 0f,
                similarity = 0.35f,
                smoothness = 0.1f,
                spill = 0.2f
            )
            return
        }

        computeAspectLockMatrix(
            srcWidth = sourceWidth.get(),
            srcHeight = sourceHeight.get(),
            dstWidth = outputWidth,
            dstHeight = outputHeight,
            fillMode = fillMode,
            outMatrix = mvpMatrix
        )
        Matrix.multiplyMM(tempCombinedMatrix, 0, layerMvpMatrix, 0, mvpMatrix, 0)
        drawOesQuad(
            mvp = tempCombinedMatrix,
            texMat = stMatrix,
            cropLeft = cropL,
            cropTop = cropT,
            cropRight = cropR,
            cropBottom = cropB,
            alpha = layer.opacity.coerceIn(0f, 1f)
        )
    }

    private fun drawOverlayLayer(layer: SceneLayer, nowElapsedMs: Long) {
        val slot: OverlayTextureSlot
        when (layer.type) {
            LayerSourceType.BROWSER_WEB -> {
                slot = acquireOverlaySlot(layer.id)
                slot.lastUsedFrame = frameCounter
                // Throttle WebView texture uploads to 10–15 FPS (~80ms) to keep GPU cool for BGMI
                if (!slot.hasValidTexture || (nowElapsedMs - slot.lastUploadElapsedMs) >= WEB_OVERLAY_UPLOAD_INTERVAL_MS) {
                    val webBmp = WebLayerManager.getLatestFrame(layer.id)
                    if (webBmp != null && !webBmp.isRecycled) {
                        val genId = webBmp.generationId.toLong()
                        if (!slot.hasValidTexture || genId != slot.lastWebRevision) {
                            uploadBitmapToTexture(slot.textureId, webBmp)
                            slot.lastWebRevision = genId
                            slot.hasValidTexture = true
                        }
                    }
                    slot.lastUploadElapsedMs = nowElapsedMs
                }
            }
            LayerSourceType.IMAGE, LayerSourceType.LOGO, LayerSourceType.BACKGROUND -> {
                val uriStr = layer.mediaUri
                if (uriStr.isNullOrBlank()) return
                slot = acquireOverlaySlot(layer.id)
                slot.lastUsedFrame = frameCounter
                // Upload static PNG/image overlay ONLY when URI/source changes
                if (!slot.hasValidTexture || slot.contentKey != uriStr) {
                    if (!failedImageKeys.contains(uriStr)) {
                        val bmp = decodeStaticOverlayBitmap(uriStr)
                        if (bmp != null) {
                            uploadBitmapToTexture(slot.textureId, bmp)
                            bmp.recycle()
                            slot.contentKey = uriStr
                            slot.hasValidTexture = true
                        } else {
                            failedImageKeys.add(uriStr)
                        }
                    }
                }
            }
            else -> return
        }

        if (!slot.hasValidTexture) return

        val cropL = layer.cropLeft.coerceIn(0f, 0.9f)
        val cropT = layer.cropTop.coerceIn(0f, 0.9f)
        val cropR = layer.cropRight.coerceIn(0f, 0.9f)
        val cropB = layer.cropBottom.coerceIn(0f, 0.9f)

        computeLayerNdcMatrix(layer.x, layer.y, layer.width, layer.height, layerMvpMatrix)

        val chroma = layer.graphicAdjustment.chromaKey
        val keyColor = chroma.keyColorHex.toInt()
        val keyR = Color.red(keyColor) / 255f
        val keyG = Color.green(keyColor) / 255f
        val keyB = Color.blue(keyColor) / 255f
        val isLuma = chroma.preset == com.example.studio.ChromaKeyPreset.BLACK_LUMA

        draw2DQuad(
            textureId = slot.textureId,
            mvp = layerMvpMatrix,
            cropLeft = cropL,
            cropTop = cropT,
            cropRight = cropR,
            cropBottom = cropB,
            alpha = layer.opacity.coerceIn(0f, 1f),
            chromaEnabled = chroma.enabled,
            isLumaKey = isLuma,
            keyR = keyR,
            keyG = keyG,
            keyB = keyB,
            similarity = chroma.similarity,
            smoothness = chroma.smoothness,
            spill = chroma.spillReduction
        )
    }

    private fun acquireOverlaySlot(layerId: String): OverlayTextureSlot {
        var freeSlot: OverlayTextureSlot? = null
        var oldestSlot = overlaySlots[0]
        for (i in 0 until overlaySlots.size) {
            val s = overlaySlots[i]
            if (s.layerId == layerId) return s
            if (s.layerId.isEmpty() && freeSlot == null) freeSlot = s
            if (s.lastUsedFrame < oldestSlot.lastUsedFrame) oldestSlot = s
        }
        val target = freeSlot ?: oldestSlot
        target.layerId = layerId
        target.contentKey = ""
        target.lastWebRevision = -1L
        target.hasValidTexture = false
        target.lastUploadElapsedMs = 0L
        return target
    }

    private fun ensureStatusCardUploaded() {
        if (statusCardUploaded) return
        val bmp = runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.img_scene_brb_gaming_1795732558862)
        }.getOrNull() ?: return
        uploadBitmapToTexture(statusCardTextureId, bmp)
        bmp.recycle()
        statusCardUploaded = true
    }

    private fun decodeStaticOverlayBitmap(uriStr: String): Bitmap? {
        return runCatching {
            if (uriStr.startsWith("drawable://")) {
                val resName = uriStr.removePrefix("drawable://")
                val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)
                if (resId != 0) BitmapFactory.decodeResource(context.resources, resId) else null
            } else {
                val uri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }
        }.getOrNull()
    }

    private fun uploadBitmapToTexture(textureId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun drawOesQuad(
        mvp: FloatArray,
        texMat: FloatArray,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        alpha: Float
    ) {
        GLES20.glUseProgram(oesProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(oesSamplerLoc, 0)
        GLES20.glUniformMatrix4fv(oesMvpLoc, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(oesTexMatrixLoc, 1, false, texMat, 0)
        GLES20.glUniform4f(oesCropLoc, cropLeft, cropTop, cropRight, cropBottom)
        GLES20.glUniform1f(oesAlphaLoc, alpha)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(oesPosLoc)
        GLES20.glVertexAttribPointer(oesPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordOesBuffer.position(0)
        GLES20.glEnableVertexAttribArray(oesTexLoc)
        GLES20.glVertexAttribPointer(oesTexLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordOesBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(oesPosLoc)
        GLES20.glDisableVertexAttribArray(oesTexLoc)
    }

    private fun draw2DQuad(
        textureId: Int,
        mvp: FloatArray,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        alpha: Float,
        chromaEnabled: Boolean,
        isLumaKey: Boolean,
        keyR: Float,
        keyG: Float,
        keyB: Float,
        similarity: Float,
        smoothness: Float,
        spill: Float
    ) {
        GLES20.glUseProgram(overlayProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(overlaySamplerLoc, 0)
        GLES20.glUniformMatrix4fv(overlayMvpLoc, 1, false, mvp, 0)
        GLES20.glUniform4f(overlayCropLoc, cropLeft, cropTop, cropRight, cropBottom)
        GLES20.glUniform3f(overlayKeyColorLoc, keyR, keyG, keyB)
        GLES20.glUniform1i(overlayChromaEnabledLoc, if (chromaEnabled) 1 else 0)
        GLES20.glUniform1i(overlayIsLumaLoc, if (isLumaKey) 1 else 0)
        GLES20.glUniform1f(overlaySimLoc, similarity)
        GLES20.glUniform1f(overlaySmoothLoc, smoothness)
        GLES20.glUniform1f(overlaySpillLoc, spill)
        GLES20.glUniform1f(overlayAlphaLoc, alpha)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(overlayPosLoc)
        GLES20.glVertexAttribPointer(overlayPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoord2DBuffer.position(0)
        GLES20.glEnableVertexAttribArray(overlayTexLoc)
        GLES20.glVertexAttribPointer(overlayTexLoc, 2, GLES20.GL_FLOAT, false, 0, texCoord2DBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(overlayPosLoc)
        GLES20.glDisableVertexAttribArray(overlayTexLoc)
    }

    private fun submitFrame(presentationTimeNs: Long): Boolean {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun configure2DTexture(textureId: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun cacheUniformLocations() {
        oesPosLoc = GLES20.glGetAttribLocation(oesProgram, "aPosition")
        oesTexLoc = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
        oesMvpLoc = GLES20.glGetUniformLocation(oesProgram, "uMvpMatrix")
        oesTexMatrixLoc = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix")
        oesCropLoc = GLES20.glGetUniformLocation(oesProgram, "uCropRect")
        oesAlphaLoc = GLES20.glGetUniformLocation(oesProgram, "uAlpha")
        oesSamplerLoc = GLES20.glGetUniformLocation(oesProgram, "uTexture")

        overlayPosLoc = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        overlayTexLoc = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        overlayMvpLoc = GLES20.glGetUniformLocation(overlayProgram, "uMvpMatrix")
        overlaySamplerLoc = GLES20.glGetUniformLocation(overlayProgram, "uTexture")
        overlayCropLoc = GLES20.glGetUniformLocation(overlayProgram, GlChromaShaderSpec.UNIFORM_CROP_RECT)
        overlayKeyColorLoc = GLES20.glGetUniformLocation(overlayProgram, GlChromaShaderSpec.UNIFORM_KEY_COLOR)
        overlayChromaEnabledLoc = GLES20.glGetUniformLocation(overlayProgram, GlChromaShaderSpec.UNIFORM_CHROMA_ENABLED)
        overlayIsLumaLoc = GLES20.glGetUniformLocation(overlayProgram, GlChromaShaderSpec.UNIFORM_IS_LUMA_KEY)
        overlaySimLoc = GLES20.glGetUniformLocation(overlayProgram, GlChromaShaderSpec.UNIFORM_SIMILARITY)
        overlaySmoothLoc = GLES20.glGetUniformLocation(overlayProgram, GlChromaShaderSpec.UNIFORM_SMOOTHNESS)
        overlaySpillLoc = GLES20.glGetUniformLocation(overlayProgram, GlChromaShaderSpec.UNIFORM_SPILL_REDUCTION)
        overlayAlphaLoc = GLES20.glGetUniformLocation(overlayProgram, GlChromaShaderSpec.UNIFORM_OPACITY)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "GL program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "GL shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /**
     * Clears non-essential failed-key caches under `onTrimMemory()` while keeping the active
     * EGL context, OES gameplay texture, and encoder pipeline intact.
     */
    fun trimNonEssentialCaches() {
        failedImageKeys.clear()
    }

    fun release() {
        initialized.set(false)
        runCatching { inputVirtualSurface?.release() }
        inputVirtualSurface = null
        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (oesProgram != 0) {
                runCatching { GLES20.glDeleteProgram(oesProgram) }
                oesProgram = 0
            }
            if (overlayProgram != 0) {
                runCatching { GLES20.glDeleteProgram(overlayProgram) }
                overlayProgram = 0
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            runCatching { EGL14.eglTerminate(eglDisplay) }
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        overlaySlots.clear()
        failedImageKeys.clear()
        statusCardUploaded = false
    }
}
