package com.example.studio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.telemetry.CrashRecoveryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Live status for a pooled fixed-viewport WebView layer managed by [WebLayerManager].
 */
data class WebLayerRuntimeState(
    val layerId: String,
    val url: String = "",
    val viewportPreset: WebViewportPreset = WebViewportPreset.P1080,
    val reloadNonce: Int = 0,
    val loadProgress: Int = 0,
    val isLoading: Boolean = false,
    val errorText: String? = null,
    val renderProcessRecoveryCount: Int = 0,
    val frameRevision: Long = 0L
)

/**
 * Process-wide singleton manager for persistent, fixed-viewport Website (`BROWSER_WEB`) layers.
 *
 * Architectural guarantees:
 * 1. **Zero-Reload Persistence**: Active `WebView` instances are retained in memory across tab
 *    switches, settings navigation, UI refreshes, app minimize, and background streaming in
 *    `StreamingForegroundService`. `loadUrl()` only executes when the URL actually changes, and
 *    `reload()` only executes when the user explicitly increments `webReloadNonce`.
 * 2. **Fixed Virtual Viewport (Zero Vertical Reflow on Crop)**: Every pooled `WebView` is laid out
 *    at its fixed virtual overlay preset (`1920x1080`, `1280x720`, or `800x800`) and is NEVER
 *    resized during crop or canvas drag gestures. Cropping is applied non-destructively via GPU
 *    UV coordinates (`uCropRect`) / `Canvas.clipRect`.
 * 3. **Crash Isolation**: Overrides `WebViewClient.onRenderProcessGone()` to safely destroy and
 *    recreate ONLY the single dead WebView instance and reload its saved URL without crashing the app.
 * 4. **Zero-Allocation Frame Capture**: Reuses a single fixed-resolution offscreen `Bitmap` per
 *    layer for both OpenGL ES preview texture upload and `StudioLiveCompositor` RTMP broadcast frames.
 */
object WebLayerManager {

    private const val TAG = "WebLayerManager"

    // Offscreen capture scale factor keeps GPU upload & H.264 compositing crisp while staying fast (<2ms/frame)
    private const val CAPTURE_MAX_WIDTH = 960

    private class PooledWebEntry(
        val layerId: String,
        var webView: WebView?,
        var loadedUrl: String = "",
        var loadedNonce: Int = -1,
        var viewport: WebViewportPreset = WebViewportPreset.P1080,
        var captureBitmap: Bitmap? = null,
        var captureCanvas: Canvas? = null,
        val stateFlow: MutableStateFlow<WebLayerRuntimeState> = MutableStateFlow(WebLayerRuntimeState(layerId))
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val entries = ConcurrentHashMap<String, PooledWebEntry>()

    /**
     * Pure logic helper: returns true only when a new valid URL needs to be loaded.
     * Prevents accidental page reloads on recomposition, tab switches, or crop/resize drags.
     */
    fun shouldLoadUrl(previousLoadedUrl: String?, nextUrl: String?): Boolean {
        if (nextUrl.isNullOrBlank()) return false
        return previousLoadedUrl != nextUrl
    }

    /**
     * Pure logic helper: returns true only when the user explicitly tapped "Reload Webpage".
     */
    fun shouldReload(previousNonce: Int, nextNonce: Int): Boolean {
        return previousNonce >= 0 && nextNonce != previousNonce
    }

    /**
     * Registers or updates logical metadata for a web layer (safe to call on any thread or in unit tests).
     * Never reloads an already-loaded URL unless URL changed or `reloadNonce` incremented.
     */
    fun registerOrUpdateLayerState(
        layerId: String,
        url: String,
        viewport: WebViewportPreset,
        reloadNonce: Int
    ): WebLayerRuntimeState {
        val entry = entries.computeIfAbsent(layerId) { PooledWebEntry(layerId = layerId, webView = null) }
        val urlChanged = shouldLoadUrl(entry.loadedUrl, url)
        val manualReload = !urlChanged && shouldReload(entry.loadedNonce, reloadNonce)
        val viewportChanged = entry.viewport != viewport

        entry.viewport = viewport
        if (urlChanged) {
            entry.loadedUrl = url
            entry.loadedNonce = reloadNonce
        } else if (manualReload) {
            entry.loadedNonce = reloadNonce
        }

        val current = entry.stateFlow.value
        val next = current.copy(
            url = url,
            viewportPreset = viewport,
            reloadNonce = reloadNonce,
            isLoading = if (urlChanged || manualReload) url.isNotBlank() else current.isLoading,
            errorText = if (urlChanged || manualReload) null else current.errorText
        )
        entry.stateFlow.value = next

        if (viewportChanged) {
            entry.webView?.let { wv ->
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    applyFixedViewportLayout(wv, viewport)
                } else {
                    mainHandler.post { applyFixedViewportLayout(wv, viewport) }
                }
            }
        }
        return next
    }

    /**
     * Acquires (or reuses without reloading!) the pooled fixed-viewport [WebView] for [layerId].
     * MUST be called on the Main thread.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun obtainWebView(
        context: Context,
        layerId: String,
        validatedUrl: String,
        viewport: WebViewportPreset,
        reloadNonce: Int
    ): WebView? {
        val appCtx = context.applicationContext
        val entry = entries.computeIfAbsent(layerId) { PooledWebEntry(layerId = layerId, webView = null) }

        val existing = entry.webView
        val webView = existing ?: createConfiguredWebView(appCtx, entry).also { created ->
            entry.webView = created
            entry.viewport = viewport
            if (created != null) {
                applyFixedViewportLayout(created, viewport)
            }
        } ?: return null

        if (entry.viewport != viewport) {
            entry.viewport = viewport
            applyFixedViewportLayout(webView, viewport)
            // Recreate capture bitmap cleanly only when user explicitly changes virtual viewport preset
            synchronized(entry) {
                entry.captureBitmap?.takeIf { !it.isRecycled }?.recycle()
                entry.captureBitmap = null
                entry.captureCanvas = null
            }
        }

        if (shouldLoadUrl(entry.loadedUrl, validatedUrl)) {
            entry.loadedUrl = validatedUrl
            entry.loadedNonce = reloadNonce
            entry.stateFlow.value = entry.stateFlow.value.copy(
                url = validatedUrl,
                viewportPreset = viewport,
                reloadNonce = reloadNonce,
                isLoading = true,
                loadProgress = 10,
                errorText = null
            )
            runCatching { webView.loadUrl(validatedUrl) }
                .onFailure { Log.w(TAG, "Failed to load URL for $layerId: ${it.message}") }
        } else if (shouldReload(entry.loadedNonce, reloadNonce)) {
            entry.loadedNonce = reloadNonce
            entry.stateFlow.value = entry.stateFlow.value.copy(
                reloadNonce = reloadNonce,
                isLoading = true,
                loadProgress = 10,
                errorText = null
            )
            runCatching { webView.reload() }
                .onFailure { Log.w(TAG, "Failed to reload URL for $layerId: ${it.message}") }
        } else {
            // Already loaded! Keep DOM, WebSockets, scroll position, and animations 100% intact.
            entry.stateFlow.value = entry.stateFlow.value.copy(
                url = validatedUrl,
                viewportPreset = viewport,
                reloadNonce = reloadNonce
            )
        }

        return webView
    }

    /**
     * Detaches the pooled [WebView] from any previous parent without destroying or reloading it,
     * ready to be attached in interactive edit mode.
     */
    fun detachFromParentSafely(webView: WebView?) {
        if (webView == null) return
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
        }
    }

    fun observeLayerState(layerId: String): StateFlow<WebLayerRuntimeState> {
        val entry = entries.computeIfAbsent(layerId) { PooledWebEntry(layerId = layerId, webView = null) }
        return entry.stateFlow.asStateFlow()
    }

    fun getViewportForLayer(layerId: String): WebViewportPreset {
        return entries[layerId]?.viewport ?: WebViewportPreset.P1080
    }

    fun pooledLayerCount(): Int = entries.size

    fun hasPooledLayer(layerId: String): Boolean = entries.containsKey(layerId)

    /**
     * Renders the pooled fixed-viewport [WebView] into its reusable offscreen [Bitmap] on the Main thread.
     * Zero Bitmap allocations during drag or crop! Safe against detached/zero-size/crashed WebViews.
     */
    fun captureLayerFrameOnMainThread(layerId: String): Bitmap? {
        val entry = entries[layerId] ?: return null
        val wv = entry.webView ?: return null
        val viewport = entry.viewport
        if (viewport.widthPx <= 0 || viewport.heightPx <= 0) return null

        return runCatching {
            // Ensure WebView stays permanently laid out at its exact fixed virtual viewport
            if (wv.width != viewport.widthPx || wv.height != viewport.heightPx) {
                applyFixedViewportLayout(wv, viewport)
            }
            if (wv.width <= 0 || wv.height <= 0) return null

            val scale = (CAPTURE_MAX_WIDTH.toFloat() / viewport.widthPx.toFloat()).coerceAtMost(1.0f)
            val bmpW = (viewport.widthPx * scale).toInt().coerceAtLeast(64)
            val bmpH = (viewport.heightPx * scale).toInt().coerceAtLeast(64)

            synchronized(entry) {
                var bmp = entry.captureBitmap
                if (bmp == null || bmp.isRecycled || bmp.width != bmpW || bmp.height != bmpH) {
                    bmp?.takeIf { !it.isRecycled }?.recycle()
                    bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888).apply {
                        setHasAlpha(true)
                    }
                    entry.captureBitmap = bmp
                    entry.captureCanvas = Canvas(bmp)
                }
                val canvas = entry.captureCanvas ?: Canvas(bmp).also { entry.captureCanvas = it }
                val save = canvas.save()
                try {
                    bmp.eraseColor(Color.TRANSPARENT)
                    canvas.scale(scale, scale)
                    wv.draw(canvas)
                } finally {
                    canvas.restoreToCount(save)
                }
                entry.stateFlow.value = entry.stateFlow.value.copy(
                    frameRevision = entry.stateFlow.value.frameRevision + 1L
                )
                bmp
            }
        }.onFailure { e ->
            Log.w(TAG, "Safe WebView frame capture skipped for $layerId: ${e.message}")
        }.getOrNull()
    }

    /**
     * Returns the latest captured fixed-viewport [Bitmap] for [layerId] without blocking or allocating.
     * Used directly by `StudioLiveCompositor` during live RTMP encoding and by Chroma Key previews.
     */
    fun getLatestFrame(layerId: String): Bitmap? {
        val entry = entries[layerId] ?: return null
        synchronized(entry) {
            val bmp = entry.captureBitmap
            return if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) bmp else null
        }
    }

    /**
     * Requests an asynchronous frame refresh on the Main thread (safe to call from the RTMP encoder
     * thread inside `StreamingForegroundService` so web overlays keep updating live in background!).
     */
    fun requestBackgroundFrameRefresh(context: Context, layer: SceneLayer) {
        val url = layer.webUrl.trim()
        if (url.isBlank()) return
        val appCtx = context.applicationContext
        mainHandler.post {
            runCatching {
                val wv = obtainWebView(
                    context = appCtx,
                    layerId = layer.id,
                    validatedUrl = url,
                    viewport = layer.webViewportPreset,
                    reloadNonce = layer.webReloadNonce
                )
                if (wv != null) {
                    captureLayerFrameOnMainThread(layer.id)
                }
            }
        }
    }

    /**
     * Records a WebView render-process crash recovery event (also invoked directly from `onRenderProcessGone`
     * and verifiable in Robolectric tests).
     */
    fun recordRenderProcessRecovery(
        context: Context?,
        layerId: String,
        didCrash: Boolean
    ): WebLayerRuntimeState {
        val entry = entries.computeIfAbsent(layerId) { PooledWebEntry(layerId = layerId, webView = null) }
        val prev = entry.stateFlow.value
        val next = prev.copy(
            renderProcessRecoveryCount = prev.renderProcessRecoveryCount + 1,
            isLoading = prev.url.isNotBlank(),
            errorText = null
        )
        entry.stateFlow.value = next
        if (context != null) {
            CrashRecoveryStore.recordEvent(
                context = context.applicationContext,
                category = "RECOVERY",
                summary = "Recovered WebView layer '$layerId' (didCrash=$didCrash)",
                detail = "Isolated renderer crash recovery #${next.renderProcessRecoveryCount}; app process kept alive."
            )
        }
        return next
    }

    /**
     * Explicitly destroys and releases a single WebView layer ONLY when the user deletes that layer.
     */
    fun releaseLayer(layerId: String) {
        val removed = entries.remove(layerId) ?: return
        val cleanup = {
            runCatching {
                removed.webView?.let { wv ->
                    detachFromParentSafely(wv)
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    wv.removeAllViews()
                    wv.destroy()
                }
                removed.webView = null
            }
            synchronized(removed) {
                removed.captureBitmap?.takeIf { !it.isRecycled }?.recycle()
                removed.captureBitmap = null
                removed.captureCanvas = null
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup()
        } else {
            mainHandler.post(cleanup)
        }
    }

    /**
     * Clears pool metadata in unit tests without leaking state across test cases.
     */
    fun clearForTesting() {
        val ids = entries.keys.toList()
        ids.forEach { releaseLayer(it) }
        entries.clear()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(appCtx: Context, entry: PooledWebEntry): WebView? {
        return runCatching {
            WebView(appCtx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                // Software-compatible layer allows reliable offscreen Canvas draw() even when not attached to a Window
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    allowFileAccess = false
                    allowContentAccess = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                }
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                runCatching { CookieManager.getInstance().setAcceptCookie(true) }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        entry.stateFlow.value = entry.stateFlow.value.copy(
                            loadProgress = newProgress,
                            isLoading = newProgress in 1..99
                        )
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val scheme = request?.url?.scheme?.lowercase()
                        return scheme != "https" && scheme != "http"
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        entry.stateFlow.value = entry.stateFlow.value.copy(
                            isLoading = false,
                            loadProgress = 100
                        )
                        captureLayerFrameOnMainThread(entry.layerId)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            val desc = error?.description?.toString().orEmpty()
                            entry.stateFlow.value = entry.stateFlow.value.copy(
                                isLoading = false,
                                errorText = when {
                                    desc.contains("CLEARTEXT", ignoreCase = true) ->
                                        "Insecure HTTP blocked. Use https://"
                                    desc.isNotBlank() -> desc
                                    else -> "Unable to load website"
                                }
                            )
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        handler?.cancel()
                        entry.stateFlow.value = entry.stateFlow.value.copy(
                            isLoading = false,
                            errorText = "Blocked insecure SSL certificate"
                        )
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            detail?.didCrash() == true
                        } else {
                            true
                        }
                        Log.w(TAG, "WebView render process gone for layer=${entry.layerId} didCrash=$didCrash — recovering isolated layer")
                        val savedUrl = entry.loadedUrl
                        val savedViewport = entry.viewport
                        val savedNonce = entry.loadedNonce

                        // Tear down ONLY the crashed WebView instance
                        runCatching {
                            detachFromParentSafely(view)
                            view?.destroy()
                        }
                        if (entry.webView === view) {
                            entry.webView = null
                        }
                        entry.loadedUrl = ""
                        recordRenderProcessRecovery(appCtx, entry.layerId, didCrash)

                        // Re-initialize a fresh WebView for just this layer on the next main looper tick
                        mainHandler.post {
                            if (entries.containsKey(entry.layerId) && savedUrl.isNotBlank()) {
                                obtainWebView(
                                    context = appCtx,
                                    layerId = entry.layerId,
                                    validatedUrl = savedUrl,
                                    viewport = savedViewport,
                                    reloadNonce = savedNonce
                                )
                            }
                        }
                        // Return true so Android does NOT kill the entire Zro Live application process
                        return true
                    }
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Unable to create WebView for layer ${entry.layerId}: ${e.message}")
        }.getOrNull()
    }

    /**
     * Permanently lays out the WebView at its fixed virtual canvas dimensions (e.g., 1920x1080)
     * so cropping the visual bounding box NEVER triggers responsive CSS reflow or vertical squishing.
     */
    private fun applyFixedViewportLayout(webView: WebView, viewport: WebViewportPreset) {
        val w = viewport.widthPx.coerceAtLeast(320)
        val h = viewport.heightPx.coerceAtLeast(320)
        runCatching {
            val wSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            webView.measure(wSpec, hSpec)
            webView.layout(0, 0, w, h)
        }
    }
}
