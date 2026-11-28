package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.PublicOff
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.streaming.GlChromaShaderSpec
import com.example.streaming.GlTextureSurfaceRenderer
import com.example.studio.ChromaKeyProcessor
import com.example.studio.LayerChromaKeySettings
import com.example.studio.LayerScaleMode
import com.example.studio.SceneLayer
import com.example.studio.StudioMediaStore
import com.example.studio.WebLayerManager
import com.example.studio.WebViewportPreset
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Shared Coil ImageLoader configured with animated GIF + WebP decoding for Studio layers.
 */
object StudioImageLoaderProvider {
    @Volatile
    private var cachedLoader: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        val existing = cachedLoader
        if (existing != null) return existing
        return synchronized(this) {
            cachedLoader ?: ImageLoader.Builder(context.applicationContext)
                .components {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .crossfade(false)
                .build()
                .also { cachedLoader = it }
        }
    }
}

/**
 * Normalizes & validates a user-entered browser source URL.
 * Rejects unsafe schemes (javascript:, file:, content:, data:).
 */
object BrowserUrlValidator {
    fun normalizeAndValidate(raw: String): String? = WebLayerManager.normalizeUrl(raw)
}

/**
 * Renders PNG / JPG / WebP / GIF Studio layers with real decoding, preserved transparency,
 * optional per-layer Chroma Key removal, and clean loading/error states.
 */
@Composable
fun StudioImageLayerView(
    layer: SceneLayer,
    contentScale: ContentScale,
    bypassGraphicAdjustment: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mediaUri = layer.mediaUri.orEmpty()
    val chroma = layer.graphicAdjustment.chromaKey
    val applyChroma = !bypassGraphicAdjustment &&
        layer.graphicAdjustment.enabled &&
        chroma.enabled &&
        layer.type.supportsChromaKey() &&
        mediaUri.isNotBlank()

    if (applyChroma) {
        var keyedBitmap by remember(mediaUri) { mutableStateOf<Bitmap?>(null) }
        var decodeError by remember(mediaUri) { mutableStateOf(false) }

        LaunchedEffect(mediaUri, chroma) {
            decodeError = false
            val processed = withContext(Dispatchers.IO) {
                val src = StudioMediaStore.decodeScaledBitmap(
                    context = context,
                    uriString = mediaUri,
                    isVideo = false,
                    maxDimensionPx = 720
                ) ?: return@withContext null
                ChromaKeyProcessor.applyChromaKeyCached(
                    sourceKey = "${layer.id}_${mediaUri.hashCode()}",
                    source = src,
                    settings = chroma
                )
            }
            if (processed != null) {
                keyedBitmap = processed
            } else {
                decodeError = true
            }
        }

        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            val bmp = keyedBitmap
            when {
                bmp != null -> {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = layer.name,
                        contentScale = contentScale,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("studio_image_chroma_${layer.id}")
                    )
                }
                decodeError -> {
                    MediaErrorPlaceholder(label = "Image unavailable")
                }
                else -> {
                    CircularProgressIndicator(
                        color = ZroCobalt,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        return
    }

    val imageLoader = remember(context) { StudioImageLoaderProvider.get(context) }
    val model = remember(mediaUri) {
        if (mediaUri.startsWith("/")) File(mediaUri) else mediaUri
    }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(model)
            .allowHardware(false) // Keeps software compatibility with ColorMatrix & ClipRect
            .build(),
        imageLoader = imageLoader,
        contentDescription = layer.name,
        contentScale = contentScale,
        modifier = modifier.testTag("studio_image_layer_${layer.id}"),
        loading = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = ZroCobalt,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
            }
        },
        error = {
            MediaErrorPlaceholder(label = "Source missing")
        }
    )
}

/**
 * Real looping video layer rendered via TextureView + MediaPlayer inside the Studio canvas,
 * with live Chroma Key frame keying when enabled on the layer.
 */
@Composable
fun StudioVideoLayerView(
    layer: SceneLayer,
    bypassGraphicAdjustment: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mediaUri = layer.mediaUri.orEmpty()
    val chroma = layer.graphicAdjustment.chromaKey
    val applyChroma = !bypassGraphicAdjustment &&
        layer.graphicAdjustment.enabled &&
        chroma.enabled

    var playbackError by remember(mediaUri) { mutableStateOf<String?>(null) }
    var isPrepared by remember(mediaUri) { mutableStateOf(false) }
    var textureViewRef by remember(mediaUri) { mutableStateOf<TextureView?>(null) }
    var keyedVideoFrame by remember(mediaUri) { mutableStateOf<Bitmap?>(null) }
    val playerHolder = remember(mediaUri) { arrayOfNulls<MediaPlayer>(1) }

    // Real-time Chroma Key frame loop when enabled on video source
    LaunchedEffect(applyChroma, chroma, isPrepared, textureViewRef) {
        if (!applyChroma || !isPrepared) {
            keyedVideoFrame = null
            return@LaunchedEffect
        }
        while (isActive) {
            val tv = textureViewRef
            if (tv != null && tv.isAttachedToWindow && tv.isAvailable && tv.width > 0 && tv.height > 0) {
                val sampleW = tv.width.coerceIn(160, 480)
                val sampleH = tv.height.coerceIn(90, 360)
                val frame = runCatching { tv.getBitmap(sampleW, sampleH) }.getOrNull()
                if (frame != null) {
                    val keyed = withContext(Dispatchers.Default) {
                        val out = runCatching { ChromaKeyProcessor.applyChromaKey(frame, chroma) }.getOrNull()
                        if (!frame.isRecycled) frame.recycle()
                        out
                    }
                    if (keyed != null) {
                        keyedVideoFrame = keyed
                    }
                }
            }
            delay(66L) // ~15 FPS smooth preview without bogging down encoder
        }
    }

    DisposableEffect(mediaUri) {
        onDispose {
            runCatching {
                playerHolder[0]?.stop()
                playerHolder[0]?.reset()
                playerHolder[0]?.release()
                playerHolder[0] = null
            }
            keyedVideoFrame = null
        }
    }

    Box(
        modifier = modifier.testTag("studio_video_layer_${layer.id}"),
        contentAlignment = Alignment.Center
    ) {
        if (mediaUri.isBlank()) {
            MediaErrorPlaceholder(label = "Select Video File")
            return@Box
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            startVideoPlayback(
                                context = ctx,
                                mediaUri = mediaUri,
                                surfaceTexture = st,
                                playerHolder = playerHolder,
                                onPrepared = { isPrepared = true; playbackError = null },
                                onError = { err -> playbackError = err }
                            )
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            runCatching {
                                playerHolder[0]?.stop()
                                playerHolder[0]?.release()
                                playerHolder[0] = null
                            }
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                    textureViewRef = this
                }
            },
            update = { tv ->
                // Keep TextureView invisible underneath when keyed overlay is active so transparency reveals layers below
                tv.alpha = if (applyChroma && keyedVideoFrame != null) 0f else 1f
                if (layer.scaleMode == LayerScaleMode.FIT && tv.width > 0 && tv.height > 0) {
                    val mp = playerHolder[0]
                    val vw = runCatching { mp?.videoWidth ?: 0 }.getOrDefault(0)
                    val vh = runCatching { mp?.videoHeight ?: 0 }.getOrDefault(0)
                    if (vw > 0 && vh > 0) {
                        val viewRatio = tv.width.toFloat() / tv.height.toFloat()
                        val videoRatio = vw.toFloat() / vh.toFloat()
                        val m = Matrix()
                        if (videoRatio > viewRatio) {
                            val sy = viewRatio / videoRatio
                            m.setScale(1f, sy, tv.width / 2f, tv.height / 2f)
                        } else {
                            val sx = videoRatio / viewRatio
                            m.setScale(sx, 1f, tv.width / 2f, tv.height / 2f)
                        }
                        tv.setTransform(m)
                    }
                } else {
                    // Default Stretch (Fill Box): stretch cleanly to fill exact layer box
                    tv.setTransform(Matrix())
                }
            }
        )

        if (applyChroma && keyedVideoFrame != null) {
            Image(
                bitmap = keyedVideoFrame!!.asImageBitmap(),
                contentDescription = "${layer.name} keyed video",
                contentScale = if (layer.scaleMode == LayerScaleMode.FIT) ContentScale.Fit else ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("studio_video_chroma_${layer.id}")
            )
        }

        if (!isPrepared && playbackError == null) {
            CircularProgressIndicator(
                color = ZroCobalt,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        }

        if (playbackError != null) {
            MediaErrorPlaceholder(label = playbackError!!)
        }
    }
}

private fun startVideoPlayback(
    context: Context,
    mediaUri: String,
    surfaceTexture: SurfaceTexture,
    playerHolder: Array<MediaPlayer?>,
    onPrepared: () -> Unit,
    onError: (String) -> Unit
) {
    runCatching {
        playerHolder[0]?.release()
        val surface = Surface(surfaceTexture)
        val mp = MediaPlayer().apply {
            isLooping = true
            setVolume(0f, 0f) // Keep canvas preview muted so it doesn't feedback into mic
            setSurface(surface)
            val parsedUri = Uri.parse(mediaUri)
            if (parsedUri.scheme == "file" || mediaUri.startsWith("/")) {
                val path = parsedUri.path ?: mediaUri
                if (!File(path).exists()) {
                    onError("Video file missing")
                    return
                }
                setDataSource(path)
            } else {
                setDataSource(context, parsedUri)
            }
            setOnPreparedListener {
                onPrepared()
                it.start()
            }
            setOnErrorListener { _, _, _ ->
                onError("Video decode error")
                true
            }
            prepareAsync()
        }
        playerHolder[0] = mp
    }.onFailure {
        onError("Cannot play video")
    }
}

/**
 * Real CameraX live feed with real-time per-layer Chroma Key removal via ImageAnalysis RGBA_8888.
 */
@Composable
fun CameraXChromaFacecamView(
    useFront: Boolean,
    chromaSettings: LayerChromaKeySettings,
    scaleMode: LayerScaleMode = LayerScaleMode.STRETCH,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var keyedFrame by remember { mutableStateOf<Bitmap?>(null) }
    val latestChroma = remember { arrayOf(chromaSettings) }
    latestChroma[0] = chromaSettings
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner, useFront) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var boundProvider: ProcessCameraProvider? = null
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                boundProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor) { imageProxy ->
                    try {
                        val rawBmp = imageProxy.toBitmap()
                        val rotDeg = imageProxy.imageInfo.rotationDegrees.toFloat()
                        val oriented = if (rotDeg != 0f || useFront) {
                            val m = Matrix().apply {
                                if (rotDeg != 0f) postRotate(rotDeg)
                                if (useFront) postScale(-1f, 1f)
                            }
                            Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true).also {
                                if (it != rawBmp) rawBmp.recycle()
                            }
                        } else {
                            rawBmp
                        }
                        val keyed = ChromaKeyProcessor.applyChromaKey(oriented, latestChroma[0])
                        oriented.recycle()
                        keyedFrame = keyed
                    } catch (_: Exception) {
                        // Skip bad frame
                    } finally {
                        imageProxy.close()
                    }
                }
                val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, analysis)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { boundProvider?.unbindAll() }
            runCatching { executor.shutdown() }
            keyedFrame = null
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val bmp = keyedFrame
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Chroma Key Facecam",
                contentScale = if (scaleMode == LayerScaleMode.FIT) ContentScale.Fit else ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.VideocamOff,
                contentDescription = "Initializing camera",
                tint = ZroAmber,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Persistent, Fixed-Viewport OBS-Style Browser / Website Source with 60fps Non-Destructive
 * GPU UV Cropping (`uCropRect`) and Real-Time OpenGL ES Chroma Key Shader support.
 *
 * - Retains active WebView instances in [WebLayerManager] across tab switches, settings navigation,
 *   UI refreshes, app minimize, and background streaming — ZERO accidental reloads!
 * - Renders WebView at its fixed virtual canvas preset (`1920x1080`, `1280x720`, or `800x800`) and
 *   NEVER resizes internal WebView layout during crop drags, eliminating vertical page reflow.
 * - Drives [GlTextureSurfaceRenderer] (`uCropRect` + `uChromaEnabled` + `uKeyColor` + `uSimilarity`
 *   + `uSmoothness` + `uSpillReduction`) for live preview, with an automatic Compose Canvas fallback
 *   when hardware GL is unavailable.
 * - Seamlessly attaches the exact same pooled WebView when [allowPageInteraction] is toggled ON.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun StudioWebSourceView(
    url: String,
    reloadNonce: Int = 0,
    interactiveEditing: Boolean = true,
    allowPageInteraction: Boolean = false,
    layerId: String = "web_inline_${url.hashCode()}",
    viewportPreset: WebViewportPreset = WebViewportPreset.P1080,
    cropLeft: Float = 0f,
    cropTop: Float = 0f,
    cropRight: Float = 0f,
    cropBottom: Float = 0f,
    chromaKey: LayerChromaKeySettings = LayerChromaKeySettings.DEFAULT,
    opacity: Float = 1.0f,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val validatedUrl = remember(url) { BrowserUrlValidator.normalizeAndValidate(url) }
    val runtimeState by remember(layerId) {
        WebLayerManager.observeLayerState(layerId)
    }.collectAsStateWithLifecycle()

    var glAvailable by remember(layerId) { mutableStateOf(true) }
    var softwareFrame by remember(layerId) { mutableStateOf<Bitmap?>(null) }
    val glRendererHolder = remember(layerId) { arrayOfNulls<GlTextureSurfaceRenderer>(1) }
    val surfaceSizeHolder = remember(layerId) { intArrayOf(0, 0) }
    val glTextureViewHolder = remember(layerId) { arrayOfNulls<TextureView>(1) }

    // Keep latest crop/chroma parameters available inside frame refresh loop without restarting effects
    val latestCrop = remember { FloatArray(4) }
    latestCrop[0] = cropLeft
    latestCrop[1] = cropTop
    latestCrop[2] = cropRight
    latestCrop[3] = cropBottom
    val latestChroma = remember { arrayOf(chromaKey) }
    latestChroma[0] = chromaKey
    val latestOpacity = remember { floatArrayOf(opacity) }
    latestOpacity[0] = opacity

    if (validatedUrl == null) {
        Box(
            modifier = modifier
                .background(Color(0xCC0B0D10))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.PublicOff,
                    contentDescription = "Browser source URL required",
                    tint = ZroAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "BROWSER SOURCE\nEnter a valid https:// URL",
                    fontSize = 9.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = Color(0xFFD1D5DB),
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    // Acquire or update the pooled fixed-viewport WebView (never reloads unless URL or reloadNonce changed!)
    LaunchedEffect(layerId, validatedUrl, viewportPreset, reloadNonce) {
        WebLayerManager.obtainWebView(
            context = context,
            layerId = layerId,
            validatedUrl = validatedUrl,
            viewport = viewportPreset,
            reloadNonce = reloadNonce
        )
    }

    // Immediate GPU uniform redraw whenever crop bounds or Chroma Key sliders move (zero WebView relayout!)
    LaunchedEffect(cropLeft, cropTop, cropRight, cropBottom, chromaKey, opacity, allowPageInteraction) {
        if (allowPageInteraction) return@LaunchedEffect
        val frame = WebLayerManager.getLatestFrame(layerId) ?: WebLayerManager.captureLayerFrameOnMainThread(layerId)
        val tv = glTextureViewHolder[0]
        val renderer = glRendererHolder[0]
        if (glAvailable && renderer != null && tv != null && tv.isAttachedToWindow && tv.isAvailable &&
            surfaceSizeHolder[0] > 0 && surfaceSizeHolder[1] > 0
        ) {
            renderer.renderBitmapFrame(
                bitmap = frame,
                viewportWidth = surfaceSizeHolder[0],
                viewportHeight = surfaceSizeHolder[1],
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropRight = cropRight,
                cropBottom = cropBottom,
                opacity = opacity,
                chroma = chromaKey
            )
        } else if (frame != null && !frame.isRecycled) {
            softwareFrame = if (chromaKey.enabled) {
                withContext(Dispatchers.Default) {
                    runCatching { ChromaKeyProcessor.applyChromaKey(frame, chromaKey) }.getOrDefault(frame)
                }
            } else {
                frame
            }
        }
    }

    // Periodic frame refresh loop (~20 FPS) pumping animated webpage content into GPU shader / software preview
    LaunchedEffect(layerId, validatedUrl, allowPageInteraction) {
        if (allowPageInteraction) return@LaunchedEffect
        while (isActive) {
            val frame = WebLayerManager.captureLayerFrameOnMainThread(layerId)
            val tv = glTextureViewHolder[0]
            val renderer = glRendererHolder[0]
            val renderedGl = if (glAvailable && renderer != null && tv != null && tv.isAttachedToWindow &&
                tv.isAvailable && surfaceSizeHolder[0] > 0 && surfaceSizeHolder[1] > 0
            ) {
                renderer.renderBitmapFrame(
                    bitmap = frame,
                    viewportWidth = surfaceSizeHolder[0],
                    viewportHeight = surfaceSizeHolder[1],
                    cropLeft = latestCrop[0],
                    cropTop = latestCrop[1],
                    cropRight = latestCrop[2],
                    cropBottom = latestCrop[3],
                    opacity = latestOpacity[0],
                    chroma = latestChroma[0]
                )
            } else {
                false
            }
            if (!renderedGl && frame != null && !frame.isRecycled) {
                val ck = latestChroma[0]
                softwareFrame = if (ck.enabled) {
                    withContext(Dispatchers.Default) {
                        runCatching { ChromaKeyProcessor.applyChromaKey(frame, ck) }.getOrDefault(frame)
                    }
                } else {
                    frame
                }
            }
            delay(50L)
        }
    }

    // Release ONLY the GL preview TextureView surface on dispose — NEVER destroy the pooled WebView!
    DisposableEffect(layerId) {
        onDispose {
            runCatching {
                glRendererHolder[0]?.release()
                glRendererHolder[0] = null
                glTextureViewHolder[0] = null
            }
        }
    }

    Box(modifier = modifier.testTag("studio_web_source_container")) {
        if (allowPageInteraction) {
            // Interactive mode: attach the exact SAME pooled WebView (zero reload!) for scrolling/clicking
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val pooled = WebLayerManager.obtainWebView(
                        context = ctx,
                        layerId = layerId,
                        validatedUrl = validatedUrl,
                        viewport = viewportPreset,
                        reloadNonce = reloadNonce
                    )
                    if (pooled != null) {
                        WebLayerManager.prepareForInteractiveAttachment(layerId)
                    }
                    android.widget.FrameLayout(ctx).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        if (pooled != null) {
                            pooled.isClickable = true
                            pooled.isFocusable = true
                            pooled.isFocusableInTouchMode = true
                            pooled.isVerticalScrollBarEnabled = true
                            pooled.isHorizontalScrollBarEnabled = true
                            pooled.setOnTouchListener { v, event ->
                                if (event.action == android.view.MotionEvent.ACTION_DOWN ||
                                    event.action == android.view.MotionEvent.ACTION_MOVE
                                ) {
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                                false
                            }
                            addView(
                                pooled,
                                android.widget.FrameLayout.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                    }
                },
                onRelease = { container ->
                    // Return the exact same WebView back to the persistent visible host container (never detached or reloaded!)
                    runCatching {
                        container.removeAllViews()
                        WebLayerManager.reattachToHostContainer(layerId)
                    }
                }
            )
        } else {
            // Live OpenGL ES 2.0 GPU UV-Cropped + Chroma-Keyed TextureView surface (60fps, zero reflow!)
            if (glAvailable) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("studio_web_gl_surface_$layerId"),
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            isOpaque = false
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    surfaceSizeHolder[0] = w
                                    surfaceSizeHolder[1] = h
                                    val renderer = GlTextureSurfaceRenderer()
                                    if (renderer.initialize(st)) {
                                        glRendererHolder[0] = renderer
                                        val frame = WebLayerManager.getLatestFrame(layerId)
                                            ?: WebLayerManager.captureLayerFrameOnMainThread(layerId)
                                        renderer.renderBitmapFrame(
                                            bitmap = frame,
                                            viewportWidth = w,
                                            viewportHeight = h,
                                            cropLeft = latestCrop[0],
                                            cropTop = latestCrop[1],
                                            cropRight = latestCrop[2],
                                            cropBottom = latestCrop[3],
                                            opacity = latestOpacity[0],
                                            chroma = latestChroma[0]
                                        )
                                    } else {
                                        glAvailable = false
                                    }
                                }

                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                    surfaceSizeHolder[0] = w
                                    surfaceSizeHolder[1] = h
                                }

                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    runCatching {
                                        glRendererHolder[0]?.release()
                                        glRendererHolder[0] = null
                                    }
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                            }
                            glTextureViewHolder[0] = this
                        }
                    }
                )
            }

            // Software Compose Canvas fallback (zero bitmap cropping allocation; draws cropped srcRect directly)
            val fallbackBmp = softwareFrame
            if (!glAvailable && fallbackBmp != null && !fallbackBmp.isRecycled && fallbackBmp.width > 0 && fallbackBmp.height > 0) {
                val imgBmp = remember(fallbackBmp) { fallbackBmp.asImageBitmap() }
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("studio_web_canvas_fallback_$layerId")
                ) {
                    val uv = GlChromaShaderSpec.computeCropUvBounds(cropLeft, cropTop, cropRight, cropBottom)
                    val srcL = (uv.uMin * imgBmp.width).toInt().coerceIn(0, imgBmp.width - 1)
                    val srcT = (uv.vMin * imgBmp.height).toInt().coerceIn(0, imgBmp.height - 1)
                    val srcW = ((uv.uMax - uv.uMin) * imgBmp.width).toInt().coerceIn(1, imgBmp.width - srcL)
                    val srcH = ((uv.vMax - uv.vMin) * imgBmp.height).toInt().coerceIn(1, imgBmp.height - srcT)
                    drawImage(
                        image = imgBmp,
                        srcOffset = androidx.compose.ui.unit.IntOffset(srcL, srcT),
                        srcSize = androidx.compose.ui.unit.IntSize(srcW, srcH),
                        dstSize = androidx.compose.ui.unit.IntSize(
                            size.width.toInt().coerceAtLeast(1),
                            size.height.toInt().coerceAtLeast(1)
                        ),
                        alpha = opacity.coerceIn(0f, 1f)
                    )
                }
            }

            // Edit-mode touch shield so canvas drag/resize/crop handles receive every gesture cleanly
            if (interactiveEditing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("studio_web_source_edit_shield")
                )
            }
        }

        if (runtimeState.isLoading) {
            LinearProgressIndicator(
                progress = { (runtimeState.loadProgress / 100f).coerceIn(0.08f, 1f) },
                color = ZroCobalt,
                trackColor = Color(0x66000000),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .align(Alignment.TopCenter)
            )
        }

        val err = runtimeState.errorText
        if (err != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xD90B0D10))
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PublicOff,
                        contentDescription = "Website load error",
                        tint = ZroAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = err,
                        fontSize = 9.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = ZroAmber,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = validatedUrl,
                        fontSize = 8.sp,
                        color = Color(0xFF9CA3AF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Reusable checkerboard backdrop for visualizing true alpha transparency in Chroma Key previews.
 */
@Composable
fun TransparentCheckerboardBackground(
    modifier: Modifier = Modifier,
    cellDp: Float = 8f
) {
    Canvas(modifier = modifier) {
        val cellPx = cellDp.dp.toPx().coerceAtLeast(6f)
        val light = Color(0xFF3A3F4B)
        val dark = Color(0xFF232730)
        val cols = (size.width / cellPx).toInt() + 1
        val rows = (size.height / cellPx).toInt() + 1
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                drawRect(
                    color = if ((r + c) % 2 == 0) light else dark,
                    topLeft = Offset(c * cellPx, r * cellPx),
                    size = Size(cellPx, cellPx)
                )
            }
        }
    }
}

@Composable
private fun MediaErrorPlaceholder(label: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC0D1017))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.BrokenImage,
            contentDescription = label,
            tint = ZroAmber,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 8.sp,
            fontFamily = JetBrainsMonoFontFamily,
            color = Color(0xFFD1D5DB),
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}
