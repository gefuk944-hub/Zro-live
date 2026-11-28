package com.example.studio

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.example.streaming.GlChromaShaderSpec
import com.example.telemetry.CrashRecoveryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
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
 * Persistent visible (`View.VISIBLE`, `alpha = 0.01f`) Activity-window host container that keeps
 * all pooled [WebView] instances permanently attached and measured at their exact fixed virtual
 * resolution (e.g., `1920x1080`) so Chromium renders frames, animations, and WebSockets continuously
 * without ever intercepting touches or appearing visually on top of the app UI.
 */
class FixedViewportWebHostContainer(context: Context) : FrameLayout(context) {

    init {
        visibility = View.VISIBLE
        alpha = 0.01f
        clipChildren = false
        clipToPadding = false
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultW = WebViewportPreset.P1080.widthPx
        val defaultH = WebViewportPreset.P1080.heightPx
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams
            val targetW = if (lp != null && lp.width > 0) lp.width else defaultW
            val targetH = if (lp != null && lp.height > 0) lp.height else defaultH
            child.measure(
                MeasureSpec.makeMeasureSpec(targetW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY)
            )
        }
        val selfW = resolveSize(1, widthMeasureSpec).coerceAtLeast(1)
        val selfH = resolveSize(1, heightMeasureSpec).coerceAtLeast(1)
        setMeasuredDimension(selfW, selfH)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val w = child.measuredWidth.coerceAtLeast(320)
            val h = child.measuredHeight.coerceAtLeast(320)
            child.layout(0, 0, w, h)
        }
    }
}

/**
 * Process-wide singleton manager for persistent, fixed-viewport Website (`BROWSER_WEB`) layers.
 *
 * Architectural guarantees:
 * 1. **Zero-Reload Persistence**: Active `WebView` instances are retained in memory across tab
 *    switches, settings navigation, UI refreshes, app minimize, and background streaming in
 *    `StreamingForegroundService`. `loadUrl()` only executes when the URL actually changes, and
 *    `reload()` only executes when the user explicitly increments `webReloadNonce`.
 * 2. **Attached Visible Host Container (`View.VISIBLE`, `alpha = 0.01f`)**: Active `WebView`
 *    instances stay attached inside [FixedViewportWebHostContainer] measured at their exact fixed
 *    virtual resolution (`1920x1080`, `1280x720`, or `800x800`) — never `View.GONE` or detached —
 *    so Chromium renders frames continuously without reflow on crop.
 * 3. **Full Stream Widget Compatibility**: Auto-prepends `https://` when scheme is missing,
 *    ignores sub-resource errors in `onReceivedError`/`onReceivedHttpError`, keeps redirects inside
 *    the WebView, enables JS/DOM/database/mixed-content/third-party cookies, and strips `; wv` from
 *    the User-Agent string.
 * 4. **Crash Isolation**: Overrides `WebViewClient.onRenderProcessGone()` to safely destroy and
 *    recreate ONLY the single dead WebView instance and reload its saved URL without crashing the app.
 */
object WebLayerManager {

    private const val TAG = "WebLayerManager"

    // Offscreen capture scale factor keeps GPU upload & H.264 compositing crisp while staying fast (<2ms/frame)
    private const val CAPTURE_MAX_WIDTH_STANDARD = 960
    private const val CAPTURE_MAX_WIDTH_LOW_RAM = 640

    private val WV_USER_AGENT_REGEX = Regex(";\\s*wv\\b", RegexOption.IGNORE_CASE)

    @Volatile
    private var cachedLowEndDevice: Boolean? = null

    /**
     * Detects budget / low-RAM Android devices (ActivityManager.isLowRamDevice or <= 3 GB total RAM)
     * so capture texture sizes and background polling intervals scale down cleanly for smooth live streaming.
     */
    fun isLowEndDevice(context: Context): Boolean {
        cachedLowEndDevice?.let { return it }
        val result = runCatching {
            val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching false
            if (am.isLowRamDevice) return@runCatching true
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            memInfo.totalMem in 1L..(3L * 1024L * 1024L * 1024L)
        }.getOrDefault(false)
        cachedLowEndDevice = result
        return result
    }

    /**
     * Pure coordinate mapper converting a touch `(touchX, touchY)` inside the cropped visual box
     * (`viewWidth x viewHeight`) into the exact pixel coordinate inside the fixed virtual viewport
     * (`viewport.widthPx x viewport.heightPx`), preserving crop bounds with zero WebView resizing.
     */
    fun mapTouchToFixedViewport(
        touchX: Float,
        touchY: Float,
        viewWidth: Float,
        viewHeight: Float,
        viewport: WebViewportPreset,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float
    ): Pair<Float, Float> {
        val safeW = viewWidth.coerceAtLeast(1f)
        val safeH = viewHeight.coerceAtLeast(1f)
        val normX = (touchX / safeW).coerceIn(0f, 1f)
        val normY = (touchY / safeH).coerceIn(0f, 1f)
        val uv = GlChromaShaderSpec.computeCropUvBounds(cropLeft, cropTop, cropRight, cropBottom)
        val targetX = (uv.uMin + normX * uv.visibleWidthFraction) * viewport.widthPx.toFloat()
        val targetY = (uv.vMin + normY * uv.visibleHeightFraction) * viewport.heightPx.toFloat()
        return targetX.coerceIn(0f, viewport.widthPx.toFloat()) to targetY.coerceIn(0f, viewport.heightPx.toFloat())
    }

    /**
     * Forwards a touch gesture from the unified stage canvas directly to the single fixed-viewport
     * pooled [WebView] without detaching, resizing, or changing its 1920x1080 layout.
     */
    fun dispatchMappedTouchEvent(
        layerId: String,
        event: MotionEvent,
        viewWidth: Float,
        viewHeight: Float,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float
    ): Boolean {
        val entry = entries[layerId] ?: return false
        val wv = entry.webView ?: return false
        return runCatching {
            val (mappedX, mappedY) = mapTouchToFixedViewport(
                touchX = event.x,
                touchY = event.y,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                viewport = entry.viewport,
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropRight = cropRight,
                cropBottom = cropBottom
            )
            val cloned = MotionEvent.obtain(event)
            try {
                cloned.setLocation(mappedX, mappedY)
                wv.dispatchTouchEvent(cloned)
            } finally {
                cloned.recycle()
            }
        }.getOrDefault(false)
    }

    fun getFrameRevision(layerId: String): Long = entries[layerId]?.stateFlow?.value?.frameRevision ?: 0L

    private class PooledWebEntry(
        val layerId: String,
        var webView: WebView?,
        var loadedUrl: String = "",
        var loadedNonce: Int = -1,
        var viewport: WebViewportPreset = WebViewportPreset.P1080,
        var interactiveAttached: Boolean = false,
        var captureBitmap: Bitmap? = null,
        var captureCanvas: Canvas? = null,
        val stateFlow: MutableStateFlow<WebLayerRuntimeState> = MutableStateFlow(WebLayerRuntimeState(layerId))
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val entries = ConcurrentHashMap<String, PooledWebEntry>()

    @Volatile
    private var hostContainerRef: WeakReference<FixedViewportWebHostContainer>? = null

    /**
     * Trims the user-entered URL and auto-prepends `https://` when `http://` or `https://` is missing.
     * Rejects unsafe schemes (`javascript:`, `file:`, `content:`, `data:`, `intent:`).
     */
    fun normalizeUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
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

    /**
     * Strips the Android WebView `; wv` token from [rawUserAgent] so modern stream widgets,
     * YouTube/Twitch/StreamElements/Streamlabs overlays, and OAuth/widget scripts load cleanly.
     */
    fun sanitizeUserAgent(rawUserAgent: String?): String {
        val base = rawUserAgent?.trim().orEmpty()
        if (base.isEmpty()) {
            return "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }
        return base
            .replace(WV_USER_AGENT_REGEX, "")
            .replace("Version/4.0 ", "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    /**
     * Registers the Activity-level visible host container (`View.VISIBLE`, `alpha = 0.01f`) and
     * immediately attaches all non-interactive pooled WebViews at their fixed virtual resolutions.
     */
    fun attachHostContainer(container: FixedViewportWebHostContainer) {
        hostContainerRef = WeakReference(container)
        val attachAll = {
            container.visibility = View.VISIBLE
            container.alpha = 0.01f
            entries.values.forEach { entry ->
                if (!entry.interactiveAttached) {
                    entry.webView?.let { wv ->
                        ensureAttachedToHostContainer(wv, entry.viewport)
                    }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attachAll()
        } else {
            mainHandler.post(attachAll)
        }
    }

    /**
     * Detaches pooled WebViews from an old Activity container without destroying or reloading them
     * (e.g., during configuration change).
     */
    fun detachHostContainer(container: FixedViewportWebHostContainer) {
        if (hostContainerRef?.get() === container) {
            hostContainerRef = null
        }
        runCatching {
            container.removeAllViews()
        }
    }

    fun hasAttachedHostContainer(): Boolean = hostContainerRef?.get() != null

    /**
     * Marks a pooled WebView as temporarily attached to an interactive user container and detaches
     * it from the background host container without destroying or reloading it.
     */
    fun prepareForInteractiveAttachment(layerId: String): WebView? {
        val entry = entries[layerId] ?: return null
        val wv = entry.webView ?: return null
        entry.interactiveAttached = true
        detachFromParentSafely(wv)
        wv.visibility = View.VISIBLE
        wv.alpha = 1f
        return wv
    }

    /**
     * Returns a pooled WebView from interactive editing back to the persistent visible host
     * container (`View.VISIBLE`, `alpha = 0.01f`) measured at its fixed virtual resolution so
     * Chromium never pauses rendering.
     */
    fun reattachToHostContainer(layerId: String) {
        val entry = entries[layerId] ?: return
        entry.interactiveAttached = false
        val wv = entry.webView ?: return
        val reattach = {
            wv.isClickable = false
            wv.isFocusable = false
            wv.isVerticalScrollBarEnabled = false
            wv.isHorizontalScrollBarEnabled = false
            ensureAttachedToHostContainer(wv, entry.viewport)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            reattach()
        } else {
            mainHandler.post(reattach)
        }
    }

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
        val normalized = normalizeUrl(url) ?: url.trim()
        val entry = entries.computeIfAbsent(layerId) { PooledWebEntry(layerId = layerId, webView = null) }
        val urlChanged = shouldLoadUrl(entry.loadedUrl, normalized)
        val manualReload = !urlChanged && shouldReload(entry.loadedNonce, reloadNonce)
        val viewportChanged = entry.viewport != viewport

        entry.viewport = viewport
        if (urlChanged) {
            entry.loadedUrl = normalized
            entry.loadedNonce = reloadNonce
        } else if (manualReload) {
            entry.loadedNonce = reloadNonce
        }

        val current = entry.stateFlow.value
        val next = current.copy(
            url = normalized,
            viewportPreset = viewport,
            reloadNonce = reloadNonce,
            isLoading = if (urlChanged || manualReload) normalized.isNotBlank() else current.isLoading,
            errorText = if (urlChanged || manualReload) null else current.errorText
        )
        entry.stateFlow.value = next

        if (viewportChanged) {
            entry.webView?.let { wv ->
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    if (!entry.interactiveAttached) {
                        ensureAttachedToHostContainer(wv, viewport)
                    } else {
                        applyFixedViewportLayout(wv, viewport)
                    }
                } else {
                    mainHandler.post {
                        if (!entry.interactiveAttached) {
                            ensureAttachedToHostContainer(wv, viewport)
                        } else {
                            applyFixedViewportLayout(wv, viewport)
                        }
                    }
                }
            }
        }
        return next
    }

    /**
     * Acquires (or reuses without reloading!) the pooled fixed-viewport [WebView] for [layerId].
     * Automatically trims & prepends `https://` if missing and ensures the WebView stays attached
     * to the persistent visible host container (`View.VISIBLE`, `alpha = 0.01f`).
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
        val normalizedUrl = normalizeUrl(validatedUrl) ?: return null
        val appCtx = context.applicationContext
        val entry = entries.computeIfAbsent(layerId) { PooledWebEntry(layerId = layerId, webView = null) }

        val existing = entry.webView
        val webView = existing ?: createConfiguredWebView(appCtx, entry).also { created ->
            entry.webView = created
            entry.viewport = viewport
            if (created != null) {
                if (!entry.interactiveAttached) {
                    ensureAttachedToHostContainer(created, viewport)
                } else {
                    applyFixedViewportLayout(created, viewport)
                }
            }
        } ?: return null

        if (entry.viewport != viewport) {
            entry.viewport = viewport
            if (!entry.interactiveAttached) {
                ensureAttachedToHostContainer(webView, viewport)
            } else {
                applyFixedViewportLayout(webView, viewport)
            }
            // Recreate capture bitmap cleanly only when user explicitly changes virtual viewport preset
            synchronized(entry) {
                entry.captureBitmap?.takeIf { !it.isRecycled }?.recycle()
                entry.captureBitmap = null
                entry.captureCanvas = null
            }
        } else if (!entry.interactiveAttached) {
            // Ensure WebView stays attached inside the visible host container (never detached or View.GONE)
            ensureAttachedToHostContainer(webView, viewport)
        }

        if (shouldLoadUrl(entry.loadedUrl, normalizedUrl)) {
            entry.loadedUrl = normalizedUrl
            entry.loadedNonce = reloadNonce
            entry.stateFlow.value = entry.stateFlow.value.copy(
                url = normalizedUrl,
                viewportPreset = viewport,
                reloadNonce = reloadNonce,
                isLoading = true,
                loadProgress = 10,
                errorText = null
            )
            runCatching {
                webView.onResume()
                webView.resumeTimers()
                webView.loadUrl(normalizedUrl)
            }.onFailure { Log.w(TAG, "Failed to load URL for $layerId: ${it.message}") }
        } else if (shouldReload(entry.loadedNonce, reloadNonce)) {
            entry.loadedNonce = reloadNonce
            entry.stateFlow.value = entry.stateFlow.value.copy(
                reloadNonce = reloadNonce,
                isLoading = true,
                loadProgress = 10,
                errorText = null
            )
            runCatching {
                webView.onResume()
                webView.resumeTimers()
                webView.reload()
            }.onFailure { Log.w(TAG, "Failed to reload URL for $layerId: ${it.message}") }
        } else {
            // Already loaded! Keep DOM, WebSockets, scroll position, and animations 100% intact.
            entry.stateFlow.value = entry.stateFlow.value.copy(
                url = normalizedUrl,
                viewportPreset = viewport,
                reloadNonce = reloadNonce
            )
        }

        return webView
    }

    /**
     * Detaches the pooled [WebView] from any previous parent without destroying or reloading it,
     * ready to be re-parented cleanly.
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
            if (!entry.interactiveAttached) {
                ensureAttachedToHostContainer(wv, viewport)
            }
            // Ensure WebView stays permanently laid out at its exact fixed virtual viewport
            if (wv.width != viewport.widthPx || wv.height != viewport.heightPx) {
                applyFixedViewportLayout(wv, viewport)
            }
            if (wv.width <= 0 || wv.height <= 0) return null

            val maxWidth = if (isLowEndDevice(wv.context)) CAPTURE_MAX_WIDTH_LOW_RAM else CAPTURE_MAX_WIDTH_STANDARD
            val scale = (maxWidth.toFloat() / viewport.widthPx.toFloat()).coerceAtMost(1.0f)
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
        val normalizedUrl = normalizeUrl(layer.webUrl) ?: return
        val appCtx = context.applicationContext
        mainHandler.post {
            runCatching {
                val wv = obtainWebView(
                    context = appCtx,
                    layerId = layer.id,
                    validatedUrl = normalizedUrl,
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
        hostContainerRef = null
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(appCtx: Context, entry: PooledWebEntry): WebView? {
        return runCatching {
            val viewCtx = hostContainerRef?.get()?.context ?: appCtx
            WebView(viewCtx).apply {
                visibility = View.VISIBLE
                setBackgroundColor(Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    allowFileAccess = false
                    allowContentAccess = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    // Strip `; wv` so stream widgets, overlays, and embedded players load cleanly
                    userAgentString = sanitizeUserAgent(userAgentString)
                }
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                runCatching {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                }

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
                        // Keep all redirects inside the WebView
                        return false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        entry.stateFlow.value = entry.stateFlow.value.copy(
                            isLoading = true,
                            errorText = null
                        )
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
                        if (request?.isForMainFrame != true) return
                        val desc = error?.description?.toString().orEmpty()
                        entry.stateFlow.value = entry.stateFlow.value.copy(
                            isLoading = false,
                            errorText = when {
                                desc.isNotBlank() -> desc
                                else -> "Unable to load website"
                            }
                        )
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        if (request?.isForMainFrame != true) return
                        val statusCode = errorResponse?.statusCode ?: 0
                        Log.w(TAG, "Main-frame HTTP status=$statusCode for layer=${entry.layerId} url=${request.url}")
                        // Do not block the rendered page for HTTP error pages that still deliver content
                        // (e.g., custom 404/auth widget redirects); only record hard gateway/server failures if load stops.
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
     * Attaches [webView] inside the persistent visible [FixedViewportWebHostContainer] (`View.VISIBLE`,
     * `alpha = 0.01f`) measured at [viewport] (`1920x1080` default) so Chromium continuously renders
     * frames even while the layer is displayed via the OpenGL ES UV-crop / Chroma Key shader.
     */
    private fun ensureAttachedToHostContainer(webView: WebView, viewport: WebViewportPreset) {
        val w = viewport.widthPx.coerceAtLeast(320)
        val h = viewport.heightPx.coerceAtLeast(320)
        webView.visibility = View.VISIBLE
        webView.alpha = 1f
        val host = hostContainerRef?.get()
        if (host != null) {
            runCatching {
                host.visibility = View.VISIBLE
                host.alpha = 0.01f
                if (webView.parent !== host) {
                    detachFromParentSafely(webView)
                    host.addView(webView, FrameLayout.LayoutParams(w, h))
                } else {
                    val lp = webView.layoutParams
                    if (lp == null || lp.width != w || lp.height != h) {
                        webView.layoutParams = FrameLayout.LayoutParams(w, h)
                    }
                }
            }
        }
        applyFixedViewportLayout(webView, viewport)
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
