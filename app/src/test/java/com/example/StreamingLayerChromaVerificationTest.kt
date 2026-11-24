package com.example

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.data.model.AudioMeterState
import com.example.data.model.AudioSettings
import com.example.data.model.RtmpTestResult
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.StreamSettings
import com.example.data.model.YouTubeAccountState
import com.example.streaming.RtmpPublisher
import com.example.studio.ChromaKeyPreset
import com.example.studio.ChromaKeyProcessor
import com.example.studio.CropEdge
import com.example.studio.DualPhoneTelemetry
import com.example.studio.LayerChromaKeySettings
import com.example.studio.LayerGraphicAdjustment
import com.example.studio.LayerSourceType
import com.example.studio.ResizeCorner
import com.example.studio.SceneLayer
import com.example.studio.StudioPersistence
import com.example.studio.StudioWorkspaceReducer
import com.example.studio.StudioWorkspaceState
import com.example.ui.components.BrowserUrlValidator
import com.example.ui.screens.LiveSetupScreen
import com.example.ui.screens.ObsSceneStudioScreen
import com.example.ui.theme.ZroLiveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StreamingLayerChromaVerificationTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- 1. YOUTUBE RTMP / RTMPS CONFIGURATION & STREAM KEY VALIDATION ---

    @Test
    fun `rtmp validation enforces separated server URL and stream key with masking`() {
        val defaults = StreamSettings()
        assertEquals("rtmp://a.rtmp.youtube.com/live2", defaults.effectivePrimaryServerUrl())
        assertEquals("rtmp://b.rtmp.youtube.com/live2", defaults.effectiveBackupServerUrl())

        val rtmpsSettings = defaults.copy(useRtmps = true)
        assertEquals("rtmps://a.rtmp.youtube.com/live2", rtmpsSettings.effectivePrimaryServerUrl())
        assertEquals("rtmps://b.rtmp.youtube.com/live2", rtmpsSettings.effectiveBackupServerUrl())

        // Reject RTMP URL accidentally pasted into Stream Key
        val urlInKey = RtmpPublisher.validateConfig(
            primaryUrl = RtmpPublisher.YOUTUBE_PRIMARY_RTMP,
            backupUrl = RtmpPublisher.YOUTUBE_BACKUP_RTMP,
            streamKey = "rtmp://a.rtmp.youtube.com/live2"
        )
        assertFalse(urlInKey.valid)
        assertTrue(urlInKey.errorMessage.orEmpty().contains("Stream Key", ignoreCase = true))

        // Reject whitespace in Stream Key
        val spacesInKey = RtmpPublisher.validateConfig(
            primaryUrl = RtmpPublisher.YOUTUBE_PRIMARY_RTMP,
            streamKey = "abcd efgh ijkl"
        )
        assertFalse(spacesInKey.valid)

        // Accept valid primary + backup + secret stream key
        val valid = RtmpPublisher.validateConfig(
            primaryUrl = RtmpPublisher.YOUTUBE_PRIMARY_RTMP,
            backupUrl = RtmpPublisher.YOUTUBE_BACKUP_RTMP,
            streamKey = "abcd-1234-efgh-5678"
        )
        assertTrue(valid.valid)

        // Mask stream key cleanly without revealing full secret
        val masked = RtmpPublisher.maskStreamKey("abcd-1234-efgh-5678")
        assertTrue(masked.endsWith("5678"))
        assertFalse(masked.contains("abcd"))
    }

    // --- 2. H.264 MULTI-NAL ANNEX-B SPLITTING & AAC CONFIG ---

    @Test
    fun `annexB splitter extracts SPS PPS and multi-NAL keyframe slices cleanly`() {
        // Combined buffer: 4-byte SPS (0x67) + 3-byte PPS (0x68) + 4-byte IDR (0x65)
        val combined = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F,
            0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C.toByte(),
            0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21
        )
        val slices = RtmpPublisher.splitAnnexBNalus(combined, combined.size)
        assertEquals(3, slices.size)
        assertEquals(7, slices[0].nalType)
        assertEquals(8, slices[1].nalType)
        assertEquals(5, slices[2].nalType)

        val (sps, pps) = RtmpPublisher.extractSpsPpsFromBuffer(combined)
        assertNotNull(sps)
        assertNotNull(pps)
        assertEquals(4, sps!!.size)
        assertEquals(3, pps!!.size)

        // 44.1 kHz stereo AAC-LC AudioSpecificConfig = 0x12 0x10
        val asc44 = RtmpPublisher.buildAacAudioSpecificConfig(44100, stereo = true)
        assertEquals(2, asc44.size)
        assertEquals(0x12.toByte(), asc44[0])
        assertEquals(0x10.toByte(), asc44[1])
    }

    // --- 3. REAL LOOPBACK RTMP SERVER HANDSHAKE, PUBLISH, REJECTION & FAILOVER ---

    @Test
    fun `real loopback rtmp server verifies testConnection publish metadata video frames and bad key rejection`() {
        val secretKey = "zro-secret-key-9876"

        // A. Successful testConnection (verifies handshake + connect WITHOUT publishing)
        runWithMockRtmpServer(acceptPublish = true) { port, publishReceived ->
            val publisher = RtmpPublisher()
            val outcome = publisher.testConnection("rtmp://127.0.0.1:$port/live2", secretKey)
            assertTrue("Expected testConnection to succeed: ${outcome.detail}", outcome.success)
            assertFalse("testConnection must NOT expose full stream key", outcome.detail.contains(secretKey))
            assertFalse("testConnection must NOT trigger publish", publishReceived.get())
        }

        // B. Full publish + metadata + AVC/AAC sequence headers + multi-NAL video frame transmission
        runWithMockRtmpServer(acceptPublish = true) { port, publishReceived ->
            val publisher = RtmpPublisher()
            val connectRes = publisher.connect("rtmp://127.0.0.1:$port/live2", secretKey)
            assertTrue("Expected RTMP connect+publish to succeed: ${connectRes.exceptionOrNull()?.message}", connectRes.isSuccess)
            assertTrue(publisher.isConnected)
            assertTrue(publishReceived.get())

            assertTrue(publisher.sendMetaData(1280, 720, 30, 4500, 44100, 128, true))
            val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
            val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C.toByte())
            assertTrue(publisher.sendAvcSequenceHeader(sps, pps))
            assertTrue(publisher.sendAacSequenceHeader(RtmpPublisher.buildAacAudioSpecificConfig(44100, true)))

            val idrAnnexB = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
            assertTrue(publisher.sendVideoFrame(idrAnnexB, idrAnnexB.size, isKeyFrame = true, timestampMs = 0))
            assertEquals(1L, publisher.videoFramesConfirmed)
            assertTrue(publisher.totalBytesSent > 0L)
            publisher.close()
        }

        // C. Rejected Stream Key (NetStream.Publish.BadName) fails honestly and masks key
        runWithMockRtmpServer(acceptPublish = false) { port, _ ->
            val publisher = RtmpPublisher()
            val badRes = publisher.connect("rtmp://127.0.0.1:$port/live2", secretKey)
            assertTrue("Expected BadName to fail connect", badRes.isFailure)
            val msg = badRes.exceptionOrNull()?.message.orEmpty()
            assertFalse("Error message must never leak raw Stream Key", msg.contains(secretKey))
            assertFalse(publisher.isConnected)
        }

        // D. Primary -> Backup failover when primary port is closed
        runWithMockRtmpServer(acceptPublish = true) { backupPort, _ ->
            val closedSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val deadPrimaryPort = closedSocket.localPort
            closedSocket.close()

            val publisher = RtmpPublisher()
            val failoverRes = publisher.connectWithFailover(
                primaryUrl = "rtmp://127.0.0.1:$deadPrimaryPort/live2",
                backupUrl = "rtmp://127.0.0.1:$backupPort/live2",
                streamKey = secretKey,
                preferBackup = false
            )
            assertTrue("Expected automatic backup failover to succeed", failoverRes.isSuccess)
            assertTrue(failoverRes.getOrThrow().usedBackup)
            publisher.close()
        }
    }

    // --- 4. WEBSITE / BROWSER SOURCE URL SECURITY VALIDATION ---

    @Test
    fun `browser url validator accepts safe web urls and blocks unsafe schemes`() {
        assertEquals("https://example.com/alerts", BrowserUrlValidator.normalizeAndValidate("https://example.com/alerts"))
        assertEquals("https://streamlabs.com/widgets", BrowserUrlValidator.normalizeAndValidate("streamlabs.com/widgets"))
        assertEquals("http://localhost:8080/hud", BrowserUrlValidator.normalizeAndValidate("http://localhost:8080/hud"))

        assertNull(BrowserUrlValidator.normalizeAndValidate(""))
        assertNull(BrowserUrlValidator.normalizeAndValidate("javascript:alert(1)"))
        assertNull(BrowserUrlValidator.normalizeAndValidate("file:///etc/passwd"))
        assertNull(BrowserUrlValidator.normalizeAndValidate("content://com.android.contacts/data"))
        assertNull(BrowserUrlValidator.normalizeAndValidate("data:text/html,<h1>Hi</h1>"))
    }

    // --- 5. SMOOTH 8-HANDLE OBS TRANSFORM BOX & LOCK PROTECTION ---

    @Test
    fun `8-handle transform resize smooth moveLayerTo and lock protection behave accurately`() {
        val base = StudioWorkspaceState()
        val layer = SceneLayer(
            id = "test_img",
            name = "Overlay",
            type = LayerSourceType.IMAGE,
            x = 0.25f,
            y = 0.25f,
            width = 0.40f,
            height = 0.30f,
            locked = false
        )
        var state = StudioWorkspaceReducer.addLayer(base, layer)

        // All 4 edge-midpoint handles work accurately
        state = StudioWorkspaceReducer.resizeLayerFromCorner(state, "test_img", ResizeCorner.CENTER_RIGHT, 0.10f, 0f, false)
        var cur = state.activeScene.layers.first { it.id == "test_img" }
        assertEquals(0.50f, cur.width, 0.005f)
        assertEquals(0.30f, cur.height, 0.005f)

        state = StudioWorkspaceReducer.resizeLayerFromCorner(state, "test_img", ResizeCorner.BOTTOM_CENTER, 0f, 0.10f, false)
        cur = state.activeScene.layers.first { it.id == "test_img" }
        assertEquals(0.40f, cur.height, 0.005f)

        state = StudioWorkspaceReducer.resizeLayerFromCorner(state, "test_img", ResizeCorner.TOP_CENTER, 0f, -0.05f, false)
        cur = state.activeScene.layers.first { it.id == "test_img" }
        assertEquals(0.20f, cur.y, 0.005f)
        assertEquals(0.45f, cur.height, 0.005f)

        state = StudioWorkspaceReducer.resizeLayerFromCorner(state, "test_img", ResizeCorner.CENTER_LEFT, -0.05f, 0f, false)
        cur = state.activeScene.layers.first { it.id == "test_img" }
        assertEquals(0.20f, cur.x, 0.005f)
        assertEquals(0.55f, cur.width, 0.005f)

        // Smooth raw-anchor moveLayerTo moves smoothly past snap points without sticking
        state = StudioWorkspaceReducer.moveLayerTo(state, "test_img", 0.31f, 0.28f)
        cur = state.activeScene.layers.first { it.id == "test_img" }
        assertEquals(0.31f, cur.x, 0.02f)
        assertEquals(0.28f, cur.y, 0.02f)

        // Lock prevents movement, resizing, and cropping
        val lockedState = StudioWorkspaceReducer.toggleLayerLock(state, "test_img")
        val lockedLayer = lockedState.activeScene.layers.first { it.id == "test_img" }
        assertTrue(lockedLayer.locked)

        val afterMove = StudioWorkspaceReducer.moveLayerTo(lockedState, "test_img", 0.05f, 0.05f)
        val afterResize = StudioWorkspaceReducer.resizeLayerFromCorner(lockedState, "test_img", ResizeCorner.BOTTOM_RIGHT, 0.20f, 0.20f, false)
        val afterCrop = StudioWorkspaceReducer.cropLayerEdge(lockedState, "test_img", CropEdge.LEFT, 0.15f)

        assertEquals(lockedLayer.x, afterMove.activeScene.layers.first { it.id == "test_img" }.x, 0.0001f)
        assertEquals(lockedLayer.width, afterResize.activeScene.layers.first { it.id == "test_img" }.width, 0.0001f)
        assertEquals(lockedLayer.cropLeft, afterCrop.activeScene.layers.first { it.id == "test_img" }.cropLeft, 0.0001f)
    }

    // --- 6. CHROMA KEY PIXEL REMOVAL, COLOR SAMPLING & PER-LAYER ISOLATION ---

    @Test
    fun `chroma key removes green and blue backgrounds cleanly and stays isolated to selected layer`() {
        // Create 40x40 ARGB_8888 bitmap: pure broadcast green background (#00B140) with crimson subject center
        val greenBg = 0xFF00B140.toInt()
        val subjectRed = 0xFFD93829.toInt()
        val bmp = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(greenBg)
        for (y in 12..27) {
            for (x in 12..27) {
                bmp.setPixel(x, y, subjectRed)
            }
        }

        // Eyedropper sampling verifies exact background color at corner
        val sampledHex = ChromaKeyProcessor.sampleColorAt(bmp, 0.05f, 0.05f)
        assertEquals(0xFF00B140L, sampledHex)

        // Apply Green Screen Chroma Key
        val keyed = ChromaKeyProcessor.applyChromaKey(
            source = bmp,
            rawSettings = LayerChromaKeySettings.forPreset(ChromaKeyPreset.GREEN_SCREEN)
        )
        val cornerAlpha = AndroidColor.alpha(keyed.getPixel(2, 2))
        val subjectAlpha = AndroidColor.alpha(keyed.getPixel(20, 20))
        assertEquals("Green background pixel must become transparent", 0, cornerAlpha)
        assertEquals("Foreground subject pixel must stay opaque", 255, subjectAlpha)

        // Apply Blue Screen Chroma Key on blue background
        val blueBmp = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        blueBmp.eraseColor(0xFF0047BB.toInt())
        for (y in 12..27) {
            for (x in 12..27) {
                blueBmp.setPixel(x, y, 0xFFFFCC33.toInt())
            }
        }
        val keyedBlue = ChromaKeyProcessor.applyChromaKey(
            source = blueBmp,
            rawSettings = LayerChromaKeySettings.forPreset(ChromaKeyPreset.BLUE_SCREEN)
        )
        assertEquals("Blue background must become transparent", 0, AndroidColor.alpha(keyedBlue.getPixel(2, 2)))
        assertEquals("Yellow subject must remain opaque", 255, AndroidColor.alpha(keyedBlue.getPixel(20, 20)))

        // Per-layer isolation: enabling Chroma Key on layer A never touches layer B
        var workspace = StudioWorkspaceState()
        workspace = StudioWorkspaceReducer.addLayer(
            workspace,
            SceneLayer(id = "layer_a", name = "Green Screen Cam", type = LayerSourceType.IMAGE)
        )
        workspace = StudioWorkspaceReducer.addLayer(
            workspace,
            SceneLayer(id = "layer_b", name = "Other Image", type = LayerSourceType.IMAGE)
        )
        workspace = StudioWorkspaceReducer.updateLayerGraphicAdjustment(workspace, "layer_a") {
            it.copy(
                brightness = 18f,
                contrast = 12f,
                chromaKey = LayerChromaKeySettings.forPreset(ChromaKeyPreset.GREEN_SCREEN)
            )
        }
        val layerA = workspace.activeScene.layers.first { it.id == "layer_a" }
        val layerB = workspace.activeScene.layers.first { it.id == "layer_b" }
        assertTrue(layerA.graphicAdjustment.chromaKey.enabled)
        assertEquals(18f, layerA.graphicAdjustment.brightness, 0.01f)
        assertFalse(layerB.graphicAdjustment.chromaKey.enabled)
        assertEquals(0f, layerB.graphicAdjustment.brightness, 0.01f)

        // Source type capability checks
        assertTrue(LayerSourceType.IMAGE.supportsChromaKey())
        assertTrue(LayerSourceType.VIDEO_OVERLAY.supportsChromaKey())
        assertTrue(LayerSourceType.WEBCAM.supportsChromaKey())
        assertFalse(LayerSourceType.TEXT.supportsChromaKey())
        assertFalse(LayerSourceType.BROWSER_WEB.supportsChromaKey())
    }

    // --- 7. PERSISTENCE ROUND-TRIP ACROSS RESTARTS ---

    @Test
    fun `studio persistence preserves transform crop media dimensions graphic adjustments and chroma key`() {
        val customLayer = SceneLayer(
            id = "persisted_source",
            name = "Green Screen Overlay",
            type = LayerSourceType.IMAGE,
            x = 0.14f,
            y = 0.22f,
            width = 0.52f,
            height = 0.41f,
            rotationDeg = 12.5f,
            opacity = 0.88f,
            cropLeft = 0.12f,
            cropRight = 0.65f,
            cropTop = 0.08f,
            cropBottom = 0.10f,
            locked = true,
            visible = true,
            mediaUri = "file:///data/user/0/com.example/files/studio_media/test.png",
            sourceMediaWidth = 1920,
            sourceMediaHeight = 1080,
            graphicAdjustment = LayerGraphicAdjustment(
                brightness = 15f,
                contrast = 20f,
                saturation = -10f,
                sharpness = 35f,
                clarity = 14f,
                shadows = 9f,
                highlights = -8f,
                chromaKey = LayerChromaKeySettings(
                    enabled = true,
                    preset = ChromaKeyPreset.CUSTOM,
                    keyColorHex = 0xFF12A84BL,
                    similarity = 0.46f,
                    smoothness = 0.18f,
                    spillReduction = 0.55f,
                    edgeCleanup = 0.24f,
                    opacity = 0.92f
                )
            )
        )
        val original = StudioWorkspaceReducer.addLayer(StudioWorkspaceState(), customLayer)
        val json = StudioPersistence.serializeWorkspace(original)
        val restored = StudioPersistence.deserializeWorkspace(json)

        val restoredLayer = restored.activeScene.layers.first { it.id == "persisted_source" }
        assertEquals(0.14f, restoredLayer.x, 0.001f)
        assertEquals(0.22f, restoredLayer.y, 0.001f)
        assertEquals(0.52f, restoredLayer.width, 0.001f)
        assertEquals(0.41f, restoredLayer.height, 0.001f)
        assertEquals(12.5f, restoredLayer.rotationDeg, 0.01f)
        assertEquals(0.88f, restoredLayer.opacity, 0.001f)
        assertEquals(0.65f, restoredLayer.cropRight, 0.001f)
        assertTrue(restoredLayer.locked)
        assertEquals(1920, restoredLayer.sourceMediaWidth)
        assertEquals(1080, restoredLayer.sourceMediaHeight)
        assertEquals(15f, restoredLayer.graphicAdjustment.brightness, 0.01f)
        assertEquals(35f, restoredLayer.graphicAdjustment.sharpness, 0.01f)
        assertTrue(restoredLayer.graphicAdjustment.chromaKey.enabled)
        assertEquals(ChromaKeyPreset.CUSTOM, restoredLayer.graphicAdjustment.chromaKey.preset)
        assertEquals(0xFF12A84BL, restoredLayer.graphicAdjustment.chromaKey.keyColorHex)
        assertEquals(0.46f, restoredLayer.graphicAdjustment.chromaKey.similarity, 0.001f)
        assertEquals(0.55f, restoredLayer.graphicAdjustment.chromaKey.spillReduction, 0.001f)
    }

    // --- 8. COMPOSE UI VERIFICATION: LIVE SETUP & OBS STUDIO 8-HANDLE + CHROMA KEY ---

    @Test
    fun `live setup screen renders youtube connect primary backup servers stream key actions and status`() {
        composeRule.setContent {
            ZroLiveTheme {
                LiveSetupScreen(
                    streamSettings = StreamSettings(
                        rtmpUrl = RtmpPublisher.YOUTUBE_PRIMARY_RTMP,
                        backupRtmpUrl = RtmpPublisher.YOUTUBE_BACKUP_RTMP,
                        streamKey = "demo-key-1234"
                    ),
                    audioSettings = AudioSettings(),
                    streamTelemetry = StreamRuntimeTelemetry(
                        state = StreamLifecycleState.READY,
                        statusDetail = "Ready to connect"
                    ),
                    dualTelemetry = DualPhoneTelemetry(),
                    workspace = StudioWorkspaceState(),
                    onBack = {},
                    onUpdateStreamSettings = {},
                    onPickThumbnail = {},
                    onClearThumbnail = {},
                    onSelectScene = {},
                    onOpenAudioStudio = {},
                    onOpenDualPairing = {},
                    onOpenCameraSettings = {},
                    onOpenSceneStudio = {},
                    onStartLive = {},
                    onStopLive = {},
                    youTubeAccount = YouTubeAccountState(signedIn = false),
                    rtmpTestResult = RtmpTestResult(
                        inProgress = false,
                        success = true,
                        latencyMs = 42L,
                        serverTested = RtmpPublisher.YOUTUBE_PRIMARY_RTMP,
                        message = "Connected in 42 ms."
                    )
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("live_setup_list")
            .performScrollToNode(hasTestTag("youtube_connect_btn"))
        composeRule.onNodeWithTag("youtube_connect_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("live_setup_list")
            .performScrollToNode(hasTestTag("live_setup_primary_url_input"))
        composeRule.onNodeWithTag("live_setup_primary_url_input").assertIsDisplayed()
        composeRule.onNodeWithTag("live_setup_list")
            .performScrollToNode(hasTestTag("live_setup_backup_url_input"))
        composeRule.onNodeWithTag("live_setup_backup_url_input").assertIsDisplayed()
        composeRule.onNodeWithTag("live_setup_list")
            .performScrollToNode(hasTestTag("live_setup_stream_key_input"))
        composeRule.onNodeWithTag("live_setup_stream_key_input").assertIsDisplayed()
        composeRule.onNodeWithTag("live_setup_list")
            .performScrollToNode(hasTestTag("live_setup_save_config_btn"))
        composeRule.onNodeWithTag("live_setup_save_config_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("live_setup_list")
            .performScrollToNode(hasTestTag("live_setup_test_connection_btn"))
        composeRule.onNodeWithTag("live_setup_test_connection_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("live_setup_list")
            .performScrollToNode(hasTestTag("live_setup_connection_status_card"))
        composeRule.onNodeWithTag("live_setup_connection_status_card").assertIsDisplayed()
    }

    @Test
    fun `studio canvas displays all 8 transform handles and graphics dock exposes chroma key tab`() {
        val imgLayer = SceneLayer(
            id = "img_1",
            name = "Green Screen Logo",
            type = LayerSourceType.IMAGE,
            x = 0.20f,
            y = 0.20f,
            width = 0.45f,
            height = 0.40f
        )
        val workspace = StudioWorkspaceReducer.addLayer(StudioWorkspaceState(), imgLayer)

        composeRule.setContent {
            ZroLiveTheme {
                ObsSceneStudioScreen(
                    workspace = workspace,
                    mirroredFrame = null,
                    audioMeters = AudioMeterState(),
                    chatMessages = emptyList(),
                    dualTelemetry = DualPhoneTelemetry(),
                    cameraPermissionGranted = false,
                    canUndo = false,
                    canRedo = false,
                    reducedMotion = true,
                    onSwitchScene = {},
                    onRenameActiveScene = {},
                    onToggleSceneLock = {},
                    onToggleSceneVertical = {},
                    onUpdateTransition = { _, _ -> },
                    onUndo = {},
                    onRedo = {},
                    onSetGridMode = {},
                    onSaveLayoutExplicit = {},
                    onSetAutoRestoreLastLayout = {},
                    onResetLayoutConfirmed = {},
                    onResetActiveScene = {},
                    onSelectLayer = {},
                    onToggleLayerLock = {},
                    onToggleLayerVisibility = {},
                    onMoveLayerBy = { _, _, _ -> },
                    onResizeLayerCorner = { _, _, _, _, _ -> },
                    onDragFinished = {},
                    onUpdateLayerProps = { _, _ -> },
                    onCenterH = {},
                    onCenterV = {},
                    onFitCanvas = {},
                    onFillCanvas = {},
                    onResetTransform = {},
                    onMoveLayerZOrder = { _, _ -> },
                    onDuplicateLayer = {},
                    onDeleteLayer = {},
                    onAddLayerSource = {},
                    onPickImageOverlay = {},
                    onPickVideoOverlay = {},
                    onPickBackgroundMediaForLayer = {},
                    onUpdateCanvasOptions = { _, _, _, _ -> },
                    onOpenCameraSettings = {},
                    onReconnectPeer = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Verify all 8 OBS transform handles (4 corners + 4 edge midpoints) are present and visible
        listOf(
            "handle_tl", "handle_tr", "handle_bl", "handle_br",
            "handle_tc", "handle_bc", "handle_cl", "handle_cr"
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        }

        // Open Graphics dock and verify Chroma Key tab opens cleanly
        composeRule.onNodeWithTag("obs_studio_list")
            .performScrollToNode(hasTestTag("studio_dock_graphics"))
        composeRule.onNodeWithTag("studio_dock_graphics").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("obs_studio_list")
            .performScrollToNode(hasTestTag("graphic_cat_chroma_key"))
        composeRule.onNodeWithTag("graphic_cat_chroma_key").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("obs_studio_list")
            .performScrollToNode(hasTestTag("chroma_key_enable_switch"))
        composeRule.onNodeWithTag("chroma_key_enable_switch").assertIsDisplayed()
    }

    // --- MINIMAL REAL LOOPBACK RTMP SERVER FOR PROTOCOL VERIFICATION ---

    private fun runWithMockRtmpServer(
        acceptPublish: Boolean,
        block: (port: Int, publishReceived: AtomicBoolean) -> Unit
    ) {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        server.soTimeout = 5000
        val publishReceived = AtomicBoolean(false)
        val serverDone = CountDownLatch(1)

        val thread = Thread {
            try {
                val client = server.accept()
                client.soTimeout = 4000
                val inp = DataInputStream(client.getInputStream())
                val out = DataOutputStream(client.getOutputStream())

                // C0 + C1
                val c0 = inp.readUnsignedByte()
                val c1 = ByteArray(1536)
                inp.readFully(c1)

                // Send S0 + S1 + S2
                out.writeByte(c0)
                val s1 = ByteArray(1536)
                out.write(s1)
                out.write(c1)
                out.flush()

                // Read C2
                val c2 = ByteArray(1536)
                inp.readFully(c2)

                // Read incoming RTMP chunks from client and reply to connect / createStream / publish
                var clientChunkSize = 128
                while (!client.isClosed) {
                    val basic = try {
                        inp.readUnsignedByte()
                    } catch (_: Exception) {
                        break
                    }
                    val fmt = (basic ushr 6) and 0x03
                    var msgLen = 0
                    var typeId = 0
                    if (fmt == 0) {
                        inp.readUnsignedByte(); inp.readUnsignedByte(); inp.readUnsignedByte() // ts
                        msgLen = (inp.readUnsignedByte() shl 16) or (inp.readUnsignedByte() shl 8) or inp.readUnsignedByte()
                        typeId = inp.readUnsignedByte()
                        inp.readInt() // streamId
                    } else if (fmt == 1) {
                        inp.readUnsignedByte(); inp.readUnsignedByte(); inp.readUnsignedByte()
                        msgLen = (inp.readUnsignedByte() shl 16) or (inp.readUnsignedByte() shl 8) or inp.readUnsignedByte()
                        typeId = inp.readUnsignedByte()
                    } else if (fmt == 2) {
                        inp.readUnsignedByte(); inp.readUnsignedByte(); inp.readUnsignedByte()
                    }

                    if (msgLen <= 0 || msgLen > 128 * 1024) continue
                    val payload = ByteArray(msgLen)
                    var read = 0
                    while (read < msgLen) {
                        val step = minOf(clientChunkSize, msgLen - read)
                        inp.readFully(payload, read, step)
                        read += step
                        if (read < msgLen) {
                            inp.readUnsignedByte() // continuation header
                        }
                    }

                    if (typeId == 1 && payload.size >= 4) {
                        clientChunkSize = ((payload[0].toInt() and 0xFF) shl 24) or
                            ((payload[1].toInt() and 0xFF) shl 16) or
                            ((payload[2].toInt() and 0xFF) shl 8) or
                            (payload[3].toInt() and 0xFF)
                    } else if (typeId == 20) {
                        val text = String(payload, Charsets.UTF_8)
                        when {
                            text.contains("connect") -> {
                                val resp = buildAmfResult(txId = 1.0, extraNumber = null)
                                writeServerRtmpMsg(out, csid = 3, typeId = 20, streamId = 0, payload = resp)
                            }
                            text.contains("createStream") -> {
                                val resp = buildAmfResult(txId = 4.0, extraNumber = 7.0)
                                writeServerRtmpMsg(out, csid = 3, typeId = 20, streamId = 0, payload = resp)
                            }
                            text.contains("publish") -> {
                                publishReceived.set(true)
                                val code = if (acceptPublish) "NetStream.Publish.Start" else "NetStream.Publish.BadName"
                                val level = if (acceptPublish) "status" else "error"
                                val resp = buildOnStatus(level, code, "Mock server response")
                                writeServerRtmpMsg(out, csid = 3, typeId = 20, streamId = 7, payload = resp)
                            }
                        }
                    }
                }
                client.close()
            } catch (_: Exception) {
                // client closed cleanly
            } finally {
                runCatching { server.close() }
                serverDone.countDown()
            }
        }
        thread.isDaemon = true
        thread.start()

        try {
            block(server.localPort, publishReceived)
        } finally {
            runCatching { server.close() }
            serverDone.await(2, TimeUnit.SECONDS)
        }
    }

    private fun buildAmfResult(txId: Double, extraNumber: Double?): ByteArray {
        val out = ByteArrayOutputStream()
        writeAmfStr(out, "_result")
        writeAmfNum(out, txId)
        out.write(0x05) // null
        if (extraNumber != null) {
            writeAmfNum(out, extraNumber)
        } else {
            out.write(0x03) // object
            out.write(0x00); out.write(0x00); out.write(0x09)
        }
        return out.toByteArray()
    }

    private fun buildOnStatus(level: String, code: String, description: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeAmfStr(out, "onStatus")
        writeAmfNum(out, 0.0)
        out.write(0x05) // null
        out.write(0x03) // object
        writeAmfPropStr(out, "level", level)
        writeAmfPropStr(out, "code", code)
        writeAmfPropStr(out, "description", description)
        out.write(0x00); out.write(0x00); out.write(0x09)
        return out.toByteArray()
    }

    private fun writeAmfStr(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.write(0x02)
        out.write((b.size shr 8) and 0xFF)
        out.write(b.size and 0xFF)
        out.write(b)
    }

    private fun writeAmfPropStr(out: ByteArrayOutputStream, k: String, v: String) {
        val kb = k.toByteArray(Charsets.UTF_8)
        out.write((kb.size shr 8) and 0xFF)
        out.write(kb.size and 0xFF)
        out.write(kb)
        writeAmfStr(out, v)
    }

    private fun writeAmfNum(out: ByteArrayOutputStream, v: Double) {
        out.write(0x00)
        val bits = java.lang.Double.doubleToRawLongBits(v)
        for (i in 7 downTo 0) {
            out.write(((bits ushr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun writeServerRtmpMsg(
        out: DataOutputStream,
        csid: Int,
        typeId: Int,
        streamId: Int,
        payload: ByteArray
    ) {
        // Basic header (fmt=0) + 11-byte message header
        out.writeByte(csid and 0x3F)
        out.writeByte(0); out.writeByte(0); out.writeByte(0)
        out.writeByte((payload.size shr 16) and 0xFF)
        out.writeByte((payload.size shr 8) and 0xFF)
        out.writeByte(payload.size and 0xFF)
        out.writeByte(typeId)
        // Little-endian streamId
        out.writeByte(streamId and 0xFF)
        out.writeByte((streamId shr 8) and 0xFF)
        out.writeByte((streamId shr 16) and 0xFF)
        out.writeByte((streamId shr 24) and 0xFF)
        // Write in 128-byte chunks
        var offset = 0
        while (offset < payload.size) {
            if (offset > 0) {
                out.writeByte(0xC0 or (csid and 0x3F))
            }
            val chunk = minOf(128, payload.size - offset)
            out.write(payload, offset, chunk)
            offset += chunk
        }
        out.flush()
    }
}
