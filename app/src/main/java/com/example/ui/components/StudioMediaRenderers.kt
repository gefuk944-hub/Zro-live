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
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.studio.ChromaKeyProcessor
import com.example.studio.LayerChromaKeySettings
import com.example.studio.LayerScaleMode
import com.example.studio.SceneLayer
import com.example.studio.StudioMediaStore
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
    fun normalizeAndValidate(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") ||
            lower.startsWith("file:") ||
            lower.startsWith("content:") ||
            lower.startsWith("data:") ||
            lower.startsWith("intent:")
        ) {
            return null
        }
        val withScheme = if (lower.startsWith("https://") || lower.startsWith("http://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val parsed = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (parsed.host.isNullOrBlank()) return null
        return withScheme
    }
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
            if (tv != null && tv.isAvailable && tv.width > 0 && tv.height > 0) {
                val sampleW = tv.width.coerceIn(160, 480)
                val sampleH = tv.height.coerceIn(90, 360)
                val frame = runCatching { tv.getBitmap(sampleW, sampleH) }.getOrNull()
                if (frame != null) {
                    val keyed = withContext(Dispatchers.Default) {
                        val out = ChromaKeyProcessor.applyChromaKey(frame, chroma)
                        frame.recycle()
                        out
                    }
                    keyedVideoFrame = keyed
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
 * Real OBS-style Browser / Website source rendered cleanly inside the Studio canvas:
 * - Validates HTTPS/HTTP schemes and rejects dangerous schemes & SSL errors
 * - Fits webpage cleanly into canvas bounds with useWideViewPort + loadWithOverviewMode
 * - Live loading progress bar, clear error state, reload trigger via reloadNonce
 * - Non-blocking touch handling when editing in Studio canvas so drag/resize stay smooth
 * - Full WebView teardown on dispose
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun StudioWebSourceView(
    url: String,
    reloadNonce: Int = 0,
    interactiveEditing: Boolean = true,
    allowPageInteraction: Boolean = false,
    modifier: Modifier = Modifier
) {
    val validatedUrl = remember(url) { BrowserUrlValidator.normalizeAndValidate(url) }
    var loadProgress by remember(validatedUrl, reloadNonce) { mutableIntStateOf(0) }
    var isLoading by remember(validatedUrl, reloadNonce) { mutableStateOf(validatedUrl != null) }
    var errorText by remember(validatedUrl, reloadNonce) { mutableStateOf<String?>(null) }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    var lastLoadedUrl by remember { mutableStateOf<String?>(null) }
    var lastReloadNonce by remember { mutableIntStateOf(-1) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewHolder[0]?.stopLoading()
                webViewHolder[0]?.loadUrl("about:blank")
                webViewHolder[0]?.clearHistory()
                webViewHolder[0]?.removeAllViews()
                webViewHolder[0]?.destroy()
                webViewHolder[0] = null
            }
        }
    }

    if (validatedUrl == null) {
        Box(
            modifier = modifier
                .background(Color(0xCC0D1017))
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

    Box(modifier = modifier.testTag("studio_web_source_container")) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isClickable = allowPageInteraction
                    isFocusable = allowPageInteraction
                    isFocusableInTouchMode = allowPageInteraction
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    isVerticalScrollBarEnabled = allowPageInteraction
                    isHorizontalScrollBarEnabled = allowPageInteraction
                    runCatching {
                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    }

                    // In Interact Mode: let user click, scroll, and interact directly inside the webpage
                    // (preventing parent LazyColumn from stealing vertical scroll).
                    // In Layout Edit Mode: never consume touch events so Compose drag/resize/crop handles work.
                    setOnTouchListener { v, event ->
                        if (allowPageInteraction) {
                            if (event.action == android.view.MotionEvent.ACTION_DOWN ||
                                event.action == android.view.MotionEvent.ACTION_MOVE
                            ) {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            false
                        } else {
                            false
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadProgress = newProgress
                            isLoading = newProgress in 1..99
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val scheme = request?.url?.scheme?.lowercase()
                            return scheme != "https" && scheme != "http"
                        }

                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            isLoading = false
                            loadProgress = 100
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                isLoading = false
                                val desc = error?.description?.toString().orEmpty()
                                errorText = when {
                                    desc.contains("CLEARTEXT", ignoreCase = true) ->
                                        "Insecure HTTP blocked. Change URL to https://"
                                    desc.isNotBlank() -> desc
                                    else -> "Unable to load website"
                                }
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            // Security: never bypass SSL certificate errors
                            handler?.cancel()
                            isLoading = false
                            errorText = "Blocked insecure SSL certificate"
                        }
                    }

                    webViewHolder[0] = this
                    lastLoadedUrl = validatedUrl
                    lastReloadNonce = reloadNonce
                    loadUrl(validatedUrl)
                }
            },
            update = { webView ->
                webView.isClickable = allowPageInteraction
                webView.isFocusable = allowPageInteraction
                webView.isFocusableInTouchMode = allowPageInteraction
                webView.isVerticalScrollBarEnabled = allowPageInteraction
                webView.isHorizontalScrollBarEnabled = allowPageInteraction
                webView.setOnTouchListener { v, event ->
                    if (allowPageInteraction) {
                        if (event.action == android.view.MotionEvent.ACTION_DOWN ||
                            event.action == android.view.MotionEvent.ACTION_MOVE
                        ) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        false
                    } else {
                        false
                    }
                }
                if (lastLoadedUrl != validatedUrl) {
                    errorText = null
                    isLoading = true
                    loadProgress = 10
                    lastLoadedUrl = validatedUrl
                    lastReloadNonce = reloadNonce
                    webView.loadUrl(validatedUrl)
                } else if (lastReloadNonce != reloadNonce) {
                    errorText = null
                    isLoading = true
                    loadProgress = 10
                    lastReloadNonce = reloadNonce
                    webView.reload()
                }
            }
        )

        // In Layout Edit Mode, cover the AndroidView WebView with a transparent Compose surface
        // so taps and drags propagate cleanly to the parent layer's selection & drag modifiers.
        if (!allowPageInteraction && interactiveEditing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("studio_web_source_edit_shield")
            )
        }

        if (isLoading) {
            LinearProgressIndicator(
                progress = { (loadProgress / 100f).coerceIn(0.08f, 1f) },
                color = ZroCobalt,
                trackColor = Color(0x66000000),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .align(Alignment.TopCenter)
            )
        }

        if (errorText != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xD90B0E14))
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
                        text = errorText!!,
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
