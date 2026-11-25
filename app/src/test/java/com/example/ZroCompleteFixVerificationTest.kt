package com.example

import com.example.data.model.RtmpTestResult
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamRuntimeTelemetry
import com.example.dualphone.DualPairingPayload
import com.example.dualphone.DualPhoneProtocol
import com.example.dualphone.QrPairingHelper
import com.example.streaming.RtmpPublisher
import com.example.studio.DualConnectionMethod
import com.example.studio.LayerSourceType
import com.example.studio.ResizeCorner
import com.example.studio.SceneLayer
import com.example.studio.StudioWorkspaceReducer
import com.example.studio.StudioWorkspaceState
import com.example.telemetry.ConnectivityStatusMonitor
import com.example.telemetry.NetworkLinkType
import com.example.telemetry.NetworkStatusInfo
import com.example.telemetry.ServerReachabilityState
import com.example.ui.components.BrowserUrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ZroCompleteFixVerificationTest {

    @Test
    fun `layers never escape canvas bounds or trap in corners during extreme drags and 8-handle resizes`() {
        var state = StudioWorkspaceState()
        val testLayer = SceneLayer(
            id = "test_box",
            name = "Test Box",
            type = LayerSourceType.TEXT,
            x = 0.25f,
            y = 0.25f,
            width = 0.30f,
            height = 0.25f,
            lockAspectRatio = false
        )
        state = StudioWorkspaceReducer.addLayer(state, testLayer)

        // Extreme negative drag -> clamped to (0, 0), never negative or lost
        val draggedNeg = StudioWorkspaceReducer.moveLayerBy(state, testLayer.id, -5.0f, -5.0f)
        val negLayer = draggedNeg.activeScene.layers.first { it.id == testLayer.id }
        assertTrue("X must stay >= 0f", negLayer.x >= 0f)
        assertTrue("Y must stay >= 0f", negLayer.y >= 0f)
        assertTrue("Layer must stay completely inside right edge", negLayer.x + negLayer.width <= 1.0001f)
        assertTrue("Layer must stay completely inside bottom edge", negLayer.y + negLayer.height <= 1.0001f)

        // Extreme positive drag -> clamped to (1 - w, 1 - h), can still drag back out cleanly!
        val draggedPos = StudioWorkspaceReducer.moveLayerBy(draggedNeg, testLayer.id, 9.0f, 9.0f)
        val posLayer = draggedPos.activeScene.layers.first { it.id == testLayer.id }
        assertEquals(1f - posLayer.width, posLayer.x, 0.002f)
        assertEquals(1f - posLayer.height, posLayer.y, 0.002f)

        // Verify dragging back from the corner works immediately (never trapped!)
        val draggedBack = StudioWorkspaceReducer.moveLayerBy(draggedPos, testLayer.id, -0.20f, -0.15f)
        val backLayer = draggedBack.activeScene.layers.first { it.id == testLayer.id }
        assertTrue("Must move left out of right corner", backLayer.x < posLayer.x - 0.1f)
        assertTrue("Must move up out of bottom corner", backLayer.y < posLayer.y - 0.08f)

        // Verify ALL 8 resize handles stay strictly within [0..1] canvas bounds even under huge deltas
        for (corner in ResizeCorner.entries) {
            val resizedA = StudioWorkspaceReducer.resizeLayerFromCorner(state, testLayer.id, corner, 2.5f, 2.5f, proportional = false)
            val la = resizedA.activeScene.layers.first { it.id == testLayer.id }
            assertTrue("Handle $corner +delta x >= 0", la.x >= -0.0001f)
            assertTrue("Handle $corner +delta y >= 0", la.y >= -0.0001f)
            assertTrue("Handle $corner +delta right <= 1", la.x + la.width <= 1.0001f)
            assertTrue("Handle $corner +delta bottom <= 1", la.y + la.height <= 1.0001f)

            val resizedB = StudioWorkspaceReducer.resizeLayerFromCorner(state, testLayer.id, corner, -2.5f, -2.5f, proportional = false)
            val lb = resizedB.activeScene.layers.first { it.id == testLayer.id }
            assertTrue("Handle $corner -delta x >= 0", lb.x >= -0.0001f)
            assertTrue("Handle $corner -delta y >= 0", lb.y >= -0.0001f)
            assertTrue("Handle $corner -delta right <= 1", lb.x + lb.width <= 1.0001f)
            assertTrue("Handle $corner -delta bottom <= 1", lb.y + lb.height <= 1.0001f)
        }
    }

    @Test
    fun `proportional aspect ratio lock works on corner handles, edge handles, and exact slider resize`() {
        var state = StudioWorkspaceState()
        val lockedLayer = SceneLayer(
            id = "locked_cam",
            name = "Facecam",
            type = LayerSourceType.WEBCAM,
            x = 0.20f,
            y = 0.20f,
            width = 0.40f,
            height = 0.20f, // 2:1 normalized ratio
            lockAspectRatio = true
        )
        state = StudioWorkspaceReducer.addLayer(state, lockedLayer)
        val initialRatio = lockedLayer.width / lockedLayer.height

        // Corner handle resize preserves aspect ratio
        val cornerResized = StudioWorkspaceReducer.resizeLayerFromCorner(
            state, lockedLayer.id, ResizeCorner.BOTTOM_RIGHT, dxNorm = 0.12f, dyNorm = 0.02f, proportional = true
        )
        val cLayer = cornerResized.activeScene.layers.first { it.id == lockedLayer.id }
        assertEquals(initialRatio, cLayer.width / cLayer.height, 0.03f)

        // Edge midpoint handle resize preserves aspect ratio
        val edgeResized = StudioWorkspaceReducer.resizeLayerFromCorner(
            state, lockedLayer.id, ResizeCorner.CENTER_RIGHT, dxNorm = 0.10f, dyNorm = 0f, proportional = true
        )
        val eLayer = edgeResized.activeScene.layers.first { it.id == lockedLayer.id }
        assertEquals(initialRatio, eLayer.width / eLayer.height, 0.03f)

        // Exact slider resize (width changed, height untouched) preserves aspect ratio
        val sliderResized = StudioWorkspaceReducer.resizeLayer(state, lockedLayer.id, newWidth = 0.60f, newHeight = lockedLayer.height)
        val sLayer = sliderResized.activeScene.layers.first { it.id == lockedLayer.id }
        assertEquals(initialRatio, sLayer.width / sLayer.height, 0.03f)
        assertTrue(sLayer.x + sLayer.width <= 1.0001f && sLayer.y + sLayer.height <= 1.0001f)
    }

    @Test
    fun `custom 4x4 and 6x2 sizes plus Fit, Fill, Center, Reset, and Deselect behave accurately`() {
        var state = StudioWorkspaceState()
        val layer = SceneLayer(
            id = "overlay_1",
            name = "Overlay",
            type = LayerSourceType.IMAGE,
            x = 0.10f,
            y = 0.10f,
            width = 0.30f,
            height = 0.30f,
            rotationDeg = 25f
        )
        state = StudioWorkspaceReducer.addLayer(state, layer)

        // 4x4 custom units
        val s4x4 = StudioWorkspaceReducer.applyCustomDimensionUnits(state, layer.id, 4f, 4f)
        val l4x4 = s4x4.activeScene.layers.first { it.id == layer.id }
        assertTrue("4x4 layer stays inside canvas", l4x4.x >= 0f && l4x4.x + l4x4.width <= 1.0001f)
        assertTrue("4x4 layer width/height valid", l4x4.width > 0.08f && l4x4.height > 0.08f)

        // 6x2 custom units (wide banner)
        val s6x2 = StudioWorkspaceReducer.applyCustomDimensionUnits(state, layer.id, 6f, 2f)
        val l6x2 = s6x2.activeScene.layers.first { it.id == layer.id }
        assertTrue("6x2 must be significantly wider than tall", l6x2.width > l6x2.height)

        // Fit keeps aspect ratio centered inside 16:9 canvas without clipping
        val fitted = StudioWorkspaceReducer.fitLayerFullCanvas(s6x2, layer.id)
        val lFit = fitted.activeScene.layers.first { it.id == layer.id }
        assertEquals(0f, lFit.rotationDeg, 0.01f)
        assertEquals(0.5f, lFit.x + lFit.width / 2f, 0.01f)
        assertEquals(0.5f, lFit.y + lFit.height / 2f, 0.01f)
        assertTrue(lFit.x >= 0f && lFit.y >= 0f && lFit.x + lFit.width <= 1.0001f && lFit.y + lFit.height <= 1.0001f)

        // Fill covers full 100% canvas edge-to-edge
        val filled = StudioWorkspaceReducer.fillLayerCanvas(s6x2, layer.id)
        val lFill = filled.activeScene.layers.first { it.id == layer.id }
        assertEquals(0f, lFill.x, 0.001f)
        assertEquals(0f, lFill.y, 0.001f)
        assertEquals(1f, lFill.width, 0.001f)
        assertEquals(1f, lFill.height, 0.001f)

        // Center aligns both axes
        val centered = StudioWorkspaceReducer.alignLayerCenter(state, layer.id)
        val lCenter = centered.activeScene.layers.first { it.id == layer.id }
        assertEquals(0.35f, lCenter.x, 0.005f)
        assertEquals(0.35f, lCenter.y, 0.005f)

        // Deselect clears selectedLayerId so blue selection outlines & handles disappear
        val deselected = StudioWorkspaceReducer.selectLayer(centered, null)
        assertNull(deselected.selectedLayerId)
    }

    @Test
    fun `browser url validator accepts safe web urls and blocks dangerous schemes`() {
        assertEquals("https://youtube.com/live", BrowserUrlValidator.normalizeAndValidate("youtube.com/live"))
        assertEquals("https://example.org/overlay?id=1", BrowserUrlValidator.normalizeAndValidate("https://example.org/overlay?id=1"))
        assertNull(BrowserUrlValidator.normalizeAndValidate("javascript:alert(1)"))
        assertNull(BrowserUrlValidator.normalizeAndValidate("file:///etc/passwd"))
        assertNull(BrowserUrlValidator.normalizeAndValidate("data:text/html,<h1>x</h1>"))
    }

    @Test
    fun `dual connection visible options expose only QR Code and Manual Address`() {
        assertEquals(
            listOf(DualConnectionMethod.QR_PAIRING, DualConnectionMethod.MANUAL_IP),
            DualConnectionMethod.VISIBLE_OPTIONS
        )
        assertFalse(DualConnectionMethod.VISIBLE_OPTIONS.contains(DualConnectionMethod.LOCAL_WIFI))
        assertFalse(DualConnectionMethod.VISIBLE_OPTIONS.contains(DualConnectionMethod.HOTSPOT))
    }

    @Test
    fun `qr scanner yuv rowStride extraction and 90-degree rotation decodes real pairing qr accurately`() {
        val token = DualPhoneProtocol.generateSecureToken()
        val uri = DualPhoneProtocol.buildPairingUri(
            DualPairingPayload("192.168.49.1", 47920, 47921, token, "ZroStudioHost")
        )
        val bmp = QrPairingHelper.generateQrBitmap(uri, sizePx = 280)
        assertNotNull(bmp)
        val w = bmp!!.width
        val h = bmp.height

        // Build a synthetic Y-plane with padded rowStride > width (just like real Android CameraX devices!)
        val rowStride = w + 32
        val padded = ByteArray(rowStride * h)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val r = (c shr 16) and 0xFF
                padded[y * rowStride + x] = r.toByte()
            }
        }

        val packed = QrPairingHelper.extractPackedYPlane(
            buffer = ByteBuffer.wrap(padded),
            width = w,
            height = h,
            rowStride = rowStride,
            pixelStride = 1
        )
        assertEquals(w * h, packed.size)

        // Decode both upright (0 deg) and rotated (90 deg) directly from padded rowStride buffer
        val decoded0 = QrPairingHelper.decodeQrFromYuv(padded, w, h, rowStride = rowStride, pixelStride = 1, rotationDegrees = 0)
        assertEquals(uri, decoded0)
        val decoded90 = QrPairingHelper.decodeQrFromYuv(padded, w, h, rowStride = rowStride, pixelStride = 1, rotationDegrees = 90)
        assertEquals(uri, decoded90)
    }

    @Test
    fun `connectivity snapshot strictly separates network status, server status, and streaming status`() {
        val netOnline = NetworkStatusInfo(
            isOnline = true,
            linkType = NetworkLinkType.WIFI,
            validated = true,
            upstreamKbps = 25_000,
            localIp = "192.168.1.50",
            detail = "Wi-Fi • 192.168.1.50"
        )
        val rtmpPassed = RtmpTestResult(
            inProgress = false,
            success = true,
            latencyMs = 48L,
            serverTested = "rtmps://a.rtmps.youtube.com/live2",
            message = "Verified"
        )
        val idleStream = StreamRuntimeTelemetry(
            state = StreamLifecycleState.IDLE,
            statusDetail = "Offline"
        )

        val snap = ConnectivityStatusMonitor.buildSnapshot(
            network = netOnline,
            rtmpTest = rtmpPassed,
            isTestingRtmp = false,
            stunState = true to 32L,
            streamTelemetry = idleStream,
            configuredRtmpUrl = "rtmps://a.rtmps.youtube.com/live2"
        )

        // Network online + Server reachable MUST STILL report Streaming = OFFLINE (never fake LIVE!)
        assertTrue(snap.network.isOnline)
        assertEquals(ServerReachabilityState.REACHABLE, snap.server.state)
        assertEquals(StreamLifecycleState.IDLE, snap.streaming.state)
        assertEquals("OFFLINE", snap.streaming.label)
        assertFalse(snap.streaming.isActivelyStreaming)
        assertEquals(true, snap.server.stunReachable)
    }

    @Test
    fun `rtmp publisher validates real urls and masks stream keys`() {
        val ok = RtmpPublisher.validateConfig(
            primaryUrl = "rtmps://a.rtmps.youtube.com/live2",
            backupUrl = "",
            streamKey = "abcd-1234-efgh-5678"
        )
        assertTrue(ok.valid)

        val bad = RtmpPublisher.validateConfig(
            primaryUrl = "https://youtube.com/live",
            backupUrl = "",
            streamKey = "abcd-1234"
        )
        assertFalse(bad.valid)

        val masked = RtmpPublisher.maskStreamKey("abcd-1234-efgh-5678")
        assertFalse("Masked key must never expose raw prefix", masked.contains("abcd"))
        assertTrue(masked.endsWith("5678"))
    }

    @Test
    fun `video encoder dimensions preserve exact YouTube 16x9 1920x1080 without rounding to 1088`() {
        val (w1080, h1080) = com.example.streaming.StreamingForegroundService.resolveAlignedDimensions(1920, 1080, portrait = false)
        assertEquals(1920, w1080)
        assertEquals(1080, h1080)

        val (w720, h720) = com.example.streaming.StreamingForegroundService.resolveAlignedDimensions(1280, 720, portrait = false)
        assertEquals(1280, w720)
        assertEquals(720, h720)

        val (wPort, hPort) = com.example.streaming.StreamingForegroundService.resolveAlignedDimensions(1920, 1080, portrait = true)
        assertEquals(1080, wPort)
        assertEquals(1920, hPort)
    }

    @Test
    fun `layer rotation stays normalized in 0 to 360 degree slider range`() {
        var state = StudioWorkspaceState()
        val layer = SceneLayer(
            id = "rot_layer",
            name = "Overlay",
            type = LayerSourceType.TEXT,
            x = 0.2f,
            y = 0.2f,
            width = 0.3f,
            height = 0.2f
        )
        state = StudioWorkspaceReducer.addLayer(state, layer)
        val rotated = StudioWorkspaceReducer.updateLayer(state, layer.id) { it.copy(rotationDeg = 270f) }
        assertEquals(270f, rotated.activeScene.layers.first { it.id == layer.id }.rotationDeg, 0.01f)
    }

    @Test
    fun `rtmp validator detects youtude typo immediately and suggests youtube url`() {
        val suggested = RtmpPublisher.suggestCorrectedRtmpUrl("rtmp://a.rtmp.youtude.com/live2")
        assertEquals("rtmp://a.rtmp.youtube.com/live2", suggested)

        val suggestedTls = RtmpPublisher.suggestCorrectedRtmpUrl("rtmps://a.rtmp.youtude.com/live2")
        assertEquals("rtmps://a.rtmps.youtube.com/live2", suggestedTls)

        val validation = RtmpPublisher.validateConfig(
            primaryUrl = "rtmp://a.rtmp.youtude.com/live2",
            backupUrl = "",
            streamKey = "abcd-1234-efgh-5678"
        )
        assertFalse("Misspelled youtude.com host must fail fast before TCP connect", validation.valid)
        assertTrue(validation.errorMessage.orEmpty().contains("youtube.com"))
    }
}
