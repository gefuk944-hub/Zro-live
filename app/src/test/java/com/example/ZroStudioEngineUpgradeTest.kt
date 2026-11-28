package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import com.example.streaming.GlChromaShaderSpec
import com.example.studio.ChromaKeyPreset
import com.example.studio.ChromaKeyProcessor
import com.example.studio.CropEdge
import com.example.studio.FixedViewportWebHostContainer
import com.example.studio.LayerChromaKeySettings
import com.example.studio.LayerGraphicAdjustment
import com.example.studio.LayerSourceType
import com.example.studio.ResizeCorner
import com.example.studio.SceneLayer
import com.example.studio.StudioPersistence
import com.example.studio.StudioWorkspaceReducer
import com.example.studio.StudioWorkspaceState
import com.example.studio.WebLayerManager
import com.example.studio.WebViewportPreset
import com.example.telemetry.CrashRecoveryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end verification suite for the Zro Live Studio Engine Upgrade:
 * 1. Smooth non-destructive crop clamping (5% min visible) & NaN/extreme-value crash immunity
 * 2. Fixed-viewport WebLayerManager persistence (zero reload on crop/recompose, isolated crash recovery)
 * 3. 8-handle resize (4 edges free stretch even with Aspect Lock ON; 4 corners honor Aspect Lock) + center snap guides
 * 4. Unified OpenGL ES Chroma Key + UV Crop shader spec (Green, Blue, Black/Luma, Magenta, Custom) & WebView chroma support
 * 5. StudioPersistence round-trip & CrashRecoveryStore emergency workspace backup
 * 6. Remote Control screen preservation + tactile buttons clickable
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36])
class ZroStudioEngineUpgradeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        WebLayerManager.clearForTesting()
    }

    @Test
    fun `non destructive crop strictly enforces 5 percent minimum visible bounds and survives NaN or extreme values`() {
        val base = StudioWorkspaceState()
        val id = "s1_gameplay"

        // Extreme combined horizontal/vertical crop clamps cleanly so visible area >= 0.05f
        val extreme = StudioWorkspaceReducer.setLayerCrop(
            state = base,
            layerId = id,
            cropLeft = 0.80f,
            cropRight = 0.80f,
            cropTop = 0.99f,
            cropBottom = 0.50f
        )
        val clamped = extreme.activeScene.layers.first { it.id == id }
        assertTrue(clamped.cropLeft + clamped.cropRight <= 0.9501f)
        assertTrue(clamped.cropTop + clamped.cropBottom <= 0.9501f)
        assertTrue(1f - clamped.cropLeft - clamped.cropRight >= 0.0499f)
        assertTrue(1f - clamped.cropTop - clamped.cropBottom >= 0.0499f)

        // NaN, Infinity, and negative values never crash or corrupt crop bounds
        val sanitized = StudioWorkspaceReducer.setLayerCrop(
            state = base,
            layerId = id,
            cropLeft = Float.NaN,
            cropRight = Float.POSITIVE_INFINITY,
            cropTop = -5f,
            cropBottom = Float.NEGATIVE_INFINITY
        )
        val safeLayer = sanitized.activeScene.layers.first { it.id == id }
        assertFalse(safeLayer.cropLeft.isNaN())
        assertFalse(safeLayer.cropRight.isNaN())
        assertFalse(safeLayer.cropTop.isNaN())
        assertFalse(safeLayer.cropBottom.isNaN())
        assertTrue(safeLayer.cropLeft in 0f..0.95f)
        assertTrue(safeLayer.cropRight in 0f..0.95f)
        assertTrue(safeLayer.cropTop in 0f..0.95f)
        assertTrue(safeLayer.cropBottom in 0f..0.95f)

        // Rapid edge crop drag increments never drop below 5% remaining width/height
        var state = base
        repeat(40) {
            state = StudioWorkspaceReducer.cropLayerEdge(state, id, CropEdge.LEFT, 0.05f)
            state = StudioWorkspaceReducer.cropLayerEdge(state, id, CropEdge.BOTTOM, 0.05f)
        }
        val finalLayer = state.activeScene.layers.first { it.id == id }
        assertTrue(finalLayer.cropLeft <= 0.9501f)
        assertTrue(finalLayer.cropBottom <= 0.9501f)
    }

    @Test
    fun `web layer manager preserves fixed virtual viewport zero reload gating and isolated render crash recovery`() {
        assertEquals(1920, WebViewportPreset.P1080.widthPx)
        assertEquals(1080, WebViewportPreset.P1080.heightPx)
        assertEquals(1280, WebViewportPreset.P720.widthPx)
        assertEquals(720, WebViewportPreset.P720.heightPx)
        assertEquals(800, WebViewportPreset.SQUARE_800.widthPx)
        assertEquals(800, WebViewportPreset.SQUARE_800.heightPx)

        // Zero-reload gating helpers
        assertTrue(WebLayerManager.shouldLoadUrl("", "https://overlay.example.com/alerts"))
        assertFalse(WebLayerManager.shouldLoadUrl("https://overlay.example.com/alerts", "https://overlay.example.com/alerts"))
        assertTrue(WebLayerManager.shouldLoadUrl("https://overlay.example.com/alerts", "https://overlay.example.com/chat"))
        assertFalse(WebLayerManager.shouldReload(1, 1))
        assertTrue(WebLayerManager.shouldReload(1, 2))

        // Register layer state -> repeated calls with same URL/nonce never re-trigger loading
        val first = WebLayerManager.registerOrUpdateLayerState(
            layerId = "web_overlay_1",
            url = "https://stream.example.com/widget",
            viewport = WebViewportPreset.P1080,
            reloadNonce = 0
        )
        assertTrue(first.isLoading)
        assertEquals(WebViewportPreset.P1080, WebLayerManager.getViewportForLayer("web_overlay_1"))
        assertTrue(WebLayerManager.hasPooledLayer("web_overlay_1"))

        // Changing viewport preset updates virtual resolution without clearing pool
        WebLayerManager.registerOrUpdateLayerState(
            layerId = "web_overlay_1",
            url = "https://stream.example.com/widget",
            viewport = WebViewportPreset.SQUARE_800,
            reloadNonce = 0
        )
        assertEquals(WebViewportPreset.SQUARE_800, WebLayerManager.getViewportForLayer("web_overlay_1"))

        // Isolated WebView renderer crash recovery increments counter without affecting other layers
        WebLayerManager.registerOrUpdateLayerState(
            layerId = "web_overlay_2",
            url = "https://stream.example.com/ticker",
            viewport = WebViewportPreset.P720,
            reloadNonce = 0
        )
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val recovered = WebLayerManager.recordRenderProcessRecovery(ctx, "web_overlay_1", didCrash = true)
        assertEquals(1, recovered.renderProcessRecoveryCount)
        assertEquals(0, WebLayerManager.observeLayerState("web_overlay_2").value.renderProcessRecoveryCount)

        // Explicit release removes only the deleted layer
        WebLayerManager.releaseLayer("web_overlay_1")
        assertFalse(WebLayerManager.hasPooledLayer("web_overlay_1"))
        assertTrue(WebLayerManager.hasPooledLayer("web_overlay_2"))
    }

    @Test
    fun `8 handle resize allows free edge stretch even with aspect lock while corners preserve ratio and snap guides detect center`() {
        val squareLayer = SceneLayer(
            id = "sq_logo",
            name = "1:1 Logo",
            type = LayerSourceType.LOGO,
            x = 0.25f,
            y = 0.25f,
            width = 0.40f,
            height = 0.40f,
            lockAspectRatio = true
        )
        val base = StudioWorkspaceState()
        val withLogo = base.copy(
            scenes = base.scenes + (base.activeSceneSlot to base.activeScene.copy(
                layers = base.activeScene.layers + squareLayer
            )),
            selectedLayerId = squareLayer.id
        )

        // Edge handle (CENTER_RIGHT) freely stretches width independently while keeping height unchanged even though lockAspectRatio=true
        val edgeStretched = StudioWorkspaceReducer.resizeLayerFromCorner(
            state = withLogo,
            layerId = squareLayer.id,
            corner = ResizeCorner.CENTER_RIGHT,
            dxNorm = 0.18f,
            dyNorm = 0f,
            proportional = false
        )
        val afterEdge = edgeStretched.activeScene.layers.first { it.id == squareLayer.id }
        assertTrue(afterEdge.width > 0.55f)
        assertEquals(0.40f, afterEdge.height, 0.001f)

        // Corner handle (BOTTOM_RIGHT) honors lockAspectRatio=true so both width and height scale together
        val cornerResized = StudioWorkspaceReducer.resizeLayerFromCorner(
            state = withLogo,
            layerId = squareLayer.id,
            corner = ResizeCorner.BOTTOM_RIGHT,
            dxNorm = 0.12f,
            dyNorm = 0.02f,
            proportional = false
        )
        val afterCorner = cornerResized.activeScene.layers.first { it.id == squareLayer.id }
        assertEquals(afterCorner.width / afterCorner.height, 1.0f, 0.05f)

        // Magnetic center snap guides illuminate when layer center approaches 0.5f
        val centeredLayer = squareLayer.copy(x = 0.305f, y = 0.305f, width = 0.40f, height = 0.40f)
        val guides = StudioWorkspaceReducer.computeCenterSnapGuides(centeredLayer)
        assertTrue(guides.snappedCenterX)
        assertTrue(guides.snappedCenterY)
    }

    @Test
    fun `gl chroma shader spec and processor support website layers black luma magenta and uv crop bounds`() {
        assertTrue(LayerSourceType.BROWSER_WEB.supportsChromaKey())
        assertTrue(LayerSourceType.GIF.supportsChromaKey())
        assertTrue(LayerSourceType.IMAGE.supportsChromaKey())
        assertTrue(LayerSourceType.VIDEO_OVERLAY.supportsChromaKey())

        // Both sampler2D and samplerExternalOES shaders contain all required uniforms
        for (shader in listOf(GlChromaShaderSpec.FRAGMENT_SHADER_2D, GlChromaShaderSpec.FRAGMENT_SHADER_EXTERNAL_OES)) {
            assertTrue(shader.contains(GlChromaShaderSpec.UNIFORM_CROP_RECT))
            assertTrue(shader.contains(GlChromaShaderSpec.UNIFORM_CHROMA_ENABLED))
            assertTrue(shader.contains(GlChromaShaderSpec.UNIFORM_KEY_COLOR))
            assertTrue(shader.contains(GlChromaShaderSpec.UNIFORM_SIMILARITY))
            assertTrue(shader.contains(GlChromaShaderSpec.UNIFORM_SMOOTHNESS))
            assertTrue(shader.contains(GlChromaShaderSpec.UNIFORM_SPILL_REDUCTION))
            assertTrue(shader.contains(GlChromaShaderSpec.UNIFORM_IS_LUMA_KEY))
        }

        // Pure UV crop calculation enforces minimum 5% visible width & height
        val uv = GlChromaShaderSpec.computeCropUvBounds(0.75f, 0.80f, 0.75f, 0.80f)
        assertTrue(uv.visibleWidthFraction >= 0.0499f)
        assertTrue(uv.visibleHeightFraction >= 0.0499f)

        // All 5 presets produce valid colors & luma detection
        assertEquals(0xFF00FF00L, LayerChromaKeySettings.forPreset(ChromaKeyPreset.GREEN_SCREEN).keyColorHex)
        assertEquals(0xFF0000FFL, LayerChromaKeySettings.forPreset(ChromaKeyPreset.BLUE_SCREEN).keyColorHex)
        assertEquals(0xFF000000L, LayerChromaKeySettings.forPreset(ChromaKeyPreset.BLACK_LUMA).keyColorHex)
        assertEquals(0xFFFF00FFL, LayerChromaKeySettings.forPreset(ChromaKeyPreset.MAGENTA_KEY).keyColorHex)
        assertTrue(GlChromaShaderSpec.isBlackLumaKey(LayerChromaKeySettings.forPreset(ChromaKeyPreset.BLACK_LUMA)))
        assertFalse(GlChromaShaderSpec.isBlackLumaKey(LayerChromaKeySettings.forPreset(ChromaKeyPreset.GREEN_SCREEN)))

        // Black / Luma key strips pure black pixels while keeping bright foreground pixels opaque
        val testBmp = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        testBmp.setPixel(0, 0, Color.BLACK)
        testBmp.setPixel(1, 0, Color.WHITE)
        val keyed = ChromaKeyProcessor.applyChromaKey(
            source = testBmp,
            rawSettings = LayerChromaKeySettings.forPreset(ChromaKeyPreset.BLACK_LUMA)
        )
        assertTrue(Color.alpha(keyed.getPixel(0, 0)) < 20)
        assertTrue(Color.alpha(keyed.getPixel(1, 0)) > 235)
        testBmp.recycle()
        keyed.recycle()
    }

    @Test
    fun `studio persistence and emergency crash backup preserve web viewport preset crop bounds and chroma key`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val webLayer = SceneLayer(
            id = "web_alerts_99",
            name = "Live Alerts Web",
            type = LayerSourceType.BROWSER_WEB,
            x = 0.05f,
            y = 0.10f,
            width = 0.70f,
            height = 0.55f,
            cropLeft = 0.12f,
            cropTop = 0.08f,
            cropRight = 0.15f,
            cropBottom = 0.10f,
            webUrl = "https://alerts.example.com/live",
            webViewportPreset = WebViewportPreset.P720,
            graphicAdjustment = LayerGraphicAdjustment(
                chromaKey = LayerChromaKeySettings.forPreset(ChromaKeyPreset.MAGENTA_KEY).copy(
                    similarity = 0.44f,
                    smoothness = 0.22f,
                    spillReduction = 0.55f
                )
            )
        )
        val initial = StudioWorkspaceState()
        val updated = initial.copy(
            scenes = initial.scenes + (initial.activeSceneSlot to initial.activeScene.copy(
                layers = initial.activeScene.layers + webLayer
            ))
        )

        val json = StudioPersistence.serializeWorkspace(updated)
        val restored = StudioPersistence.deserializeWorkspace(json)
        val restoredWeb = restored.activeScene.layers.first { it.id == "web_alerts_99" }
        assertEquals("https://alerts.example.com/live", restoredWeb.webUrl)
        assertEquals(WebViewportPreset.P720, restoredWeb.webViewportPreset)
        assertEquals(0.12f, restoredWeb.cropLeft, 0.001f)
        assertEquals(0.15f, restoredWeb.cropRight, 0.001f)
        assertTrue(restoredWeb.graphicAdjustment.chromaKey.enabled)
        assertEquals(ChromaKeyPreset.MAGENTA_KEY, restoredWeb.graphicAdjustment.chromaKey.preset)
        assertEquals(0.55f, restoredWeb.graphicAdjustment.chromaKey.spillReduction, 0.001f)

        // Emergency crash backup round-trip
        assertTrue(CrashRecoveryStore.saveEmergencyWorkspaceBackup(ctx, updated))
        val backup = CrashRecoveryStore.readEmergencyWorkspaceBackup(ctx)
        assertNotNull(backup)
        assertTrue(backup!!.activeScene.layers.any { it.id == "web_alerts_99" && it.webViewportPreset == WebViewportPreset.P720 })
        CrashRecoveryStore.clearEmergencyWorkspaceBackup(ctx)
    }

    @Test
    fun `remote control screen preserves existing layout and tactile buttons respond cleanly`() {
        composeRule.waitForIdle()
        // Navigate to Remote Control tab
        composeRule.onNodeWithTag("nav_dual").performClick()
        composeRule.waitForIdle()

        // Existing Remote Control controls & 3-column Broadcast Deck remain intact
        composeRule.onNodeWithTag("remote_live_studio_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("remote_layers_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("dual_studio_list").performScrollToNode(hasTestTag("remote_deck_grid"))
        composeRule.onNodeWithTag("remote_deck_grid").assertIsDisplayed()
        composeRule.onNodeWithTag("remote_tile_start_live").assertIsDisplayed()
        composeRule.onNodeWithTag("remote_tile_mute_mic").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `website layer auto prepends https strips wv user agent keeps visible fixed viewport host attached and ignores subresource errors`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // 1. Auto-HTTPS & trimming
        assertEquals(
            "https://streamelements.com/overlay/123",
            WebLayerManager.normalizeUrl("  streamelements.com/overlay/123  ")
        )
        assertEquals(
            "http://192.168.1.10:8080/overlay",
            WebLayerManager.normalizeUrl("http://192.168.1.10:8080/overlay")
        )
        assertEquals(
            "https://streamlabs.com/alert-box/v3/token",
            WebLayerManager.normalizeUrl("https://streamlabs.com/alert-box/v3/token")
        )

        // 2. User-Agent strips `; wv`
        val rawUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36"
        val cleanUa = WebLayerManager.sanitizeUserAgent(rawUa)
        assertFalse(cleanUa.contains("; wv"))
        assertTrue(cleanUa.contains("Chrome/126.0.0.0"))

        // 3. Persistent visible host container (View.VISIBLE, alpha = 0.01f) measured at 1920x1080
        val host = FixedViewportWebHostContainer(ctx)
        assertEquals(View.VISIBLE, host.visibility)
        assertEquals(0.01f, host.alpha, 0.0001f)
        assertFalse(host.dispatchTouchEvent(null))
        WebLayerManager.attachHostContainer(host)
        assertTrue(WebLayerManager.hasAttachedHostContainer())

        // 4. WebView creation configures full compatibility settings and attaches to visible host at 1920x1080
        val wv = WebLayerManager.obtainWebView(
            context = ctx,
            layerId = "web_widget_compat",
            validatedUrl = "streamelements.com/overlay/test",
            viewport = WebViewportPreset.P1080,
            reloadNonce = 0
        )
        assertNotNull(wv)
        val settings = wv!!.settings
        assertTrue(settings.javaScriptEnabled)
        assertTrue(settings.domStorageEnabled)
        assertTrue(settings.databaseEnabled)
        assertEquals(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW, settings.mixedContentMode)
        assertFalse(settings.userAgentString.contains("; wv"))

        // Attached to visible host container at exact fixed 1920x1080 dimensions
        host.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        host.layout(0, 0, 1080, 1920)
        assertTrue(wv.parent === host)
        assertEquals(View.VISIBLE, wv.visibility)
        assertEquals(1920, wv.layoutParams.width)
        assertEquals(1080, wv.layoutParams.height)
        assertEquals(1920, wv.measuredWidth)
        assertEquals(1080, wv.measuredHeight)

        // Sub-resource error does NOT set errorText, and redirects stay inside WebView
        val client = wv.webViewClient
        val subResourceReq = object : WebResourceRequest {
            override fun getUrl() = android.net.Uri.parse("https://streamelements.com/favicon.ico")
            override fun isForMainFrame() = false
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
        assertFalse(client.shouldOverrideUrlLoading(wv, subResourceReq))
        client.onReceivedError(wv, subResourceReq, null)
        client.onReceivedHttpError(wv, subResourceReq, null)
        assertEquals(null, WebLayerManager.observeLayerState("web_widget_compat").value.errorText)

        // Leaving interactive mode reattaches back to persistent visible host container
        WebLayerManager.prepareForInteractiveAttachment("web_widget_compat")
        WebLayerManager.reattachToHostContainer("web_widget_compat")
        assertTrue(wv.parent === host)
        assertEquals(View.VISIBLE, wv.visibility)

        WebLayerManager.detachHostContainer(host)
    }
}
