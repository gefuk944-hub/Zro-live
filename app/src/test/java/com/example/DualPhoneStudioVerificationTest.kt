package com.example

import com.example.audio.VoiceDspProcessor
import com.example.audio.ZroAudioEngine
import com.example.data.model.AudioSettings
import com.example.data.model.NoiseCancellationLevel
import com.example.dualphone.DualPairingPayload
import com.example.dualphone.DualPhoneEngine
import com.example.dualphone.DualPhoneProtocol
import com.example.dualphone.DualRemoteCommand
import com.example.dualphone.QrPairingHelper
import com.example.studio.CameraSourceMode
import com.example.studio.CanvasGridMode
import com.example.studio.DualConnectionMethod
import com.example.studio.DualLinkState
import com.example.studio.FacecamShape
import com.example.studio.LayerSourceType
import com.example.studio.RemoteControlTileId
import com.example.studio.ResizeCorner
import com.example.studio.SceneLayer
import com.example.studio.SceneSlot
import com.example.studio.SceneTransitionType
import com.example.studio.StudioPersistence
import com.example.studio.StudioWorkspaceReducer
import com.example.studio.StudioWorkspaceState
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DualPhoneStudioVerificationTest {

    @Test
    fun `qr pairing uri encodes into qr bitmap and decodes back with exact token and ports`() {
        val token = DualPhoneProtocol.generateSecureToken()
        val payload = DualPairingPayload(
            ip = "192.168.1.42",
            controlPort = 47920,
            videoPort = 47921,
            token = token,
            hostName = "Phone2Studio"
        )
        val uri = DualPhoneProtocol.buildPairingUri(payload)
        val bitmap = QrPairingHelper.generateQrBitmap(uri, sizePx = 320)
        assertNotNull("Generated QR bitmap must not be null", bitmap)

        val decodedText = QrPairingHelper.decodeQrFromBitmap(bitmap!!)
        assertEquals(uri, decodedText)

        val parsed = DualPhoneProtocol.parsePairingUri(decodedText!!)
        assertNotNull(parsed)
        assertEquals("192.168.1.42", parsed!!.ip)
        assertEquals(47920, parsed.controlPort)
        assertEquals(47921, parsed.videoPort)
        assertEquals(token, parsed.token)
        assertEquals("Phone2Studio", parsed.hostName)
    }

    @Test
    fun `dual phone real loopback tcp socket enforces token auth and delivers remote commands`() = runBlocking {
        val phone2Host = DualPhoneEngine()
        val phone1Client = DualPhoneEngine()
        try {
            phone2Host.startPhone2Host(
                hostDeviceName = "TestStudioPhone2",
                controlPort = 0,
                videoPort = 0,
                method = DualConnectionMethod.LOCAL_WIFI,
                presetToken = "ZRO9988A"
            )

            withTimeout(4000) {
                while (phone2Host.telemetry.value.linkState != DualLinkState.HOSTING) {
                    delay(40)
                }
            }
            val controlPort = phone2Host.telemetry.value.hostPort
            val videoPort = phone2Host.telemetry.value.videoPort
            assertTrue("Ephemeral control port must be bound", controlPort > 1024)

            // 1. Wrong token MUST be rejected by Phone 2
            phone1Client.connectPhone1ToStudio(
                ip = "127.0.0.1",
                controlPort = controlPort,
                videoPort = videoPort,
                token = "WRONGTOK",
                deviceName = "RoguePhone"
            )
            withTimeout(4000) {
                while (phone1Client.telemetry.value.linkState != DualLinkState.ERROR) {
                    delay(40)
                }
            }
            assertEquals(DualLinkState.ERROR, phone1Client.telemetry.value.linkState)

            // 2. Correct token connects both devices over loopback TCP
            phone1Client.connectPhone1ToStudio(
                ip = "127.0.0.1",
                controlPort = controlPort,
                videoPort = videoPort,
                token = "ZRO9988A",
                deviceName = "GamingPhone1"
            )
            withTimeout(4000) {
                while (phone1Client.telemetry.value.linkState != DualLinkState.CONNECTED ||
                    phone2Host.telemetry.value.linkState != DualLinkState.CONNECTED
                ) {
                    delay(40)
                }
            }
            assertEquals("GamingPhone1", phone2Host.telemetry.value.connectedDeviceName)
            assertEquals("TestStudioPhone2", phone1Client.telemetry.value.connectedDeviceName)

            // 3. Remote command from Phone 2 reaches Phone 1 cleanly
            val receivedDeferred = async {
                withTimeout(4000) {
                    phone1Client.incomingCommands.first()
                }
            }
            delay(60)
            val sent = phone2Host.sendRemoteCommand(
                DualRemoteCommand(
                    action = DualPhoneProtocol.CMD_TRIGGER_BRB
                )
            )
            assertTrue(sent)
            val received = receivedDeferred.await()
            assertEquals(DualPhoneProtocol.CMD_TRIGGER_BRB, received.action)
        } finally {
            phone1Client.disconnect(clearRole = true)
            phone2Host.disconnect(clearRole = true)
        }
    }

    @Test
    fun `four corner resize handles grid modes alignment and layout persistence work accurately`() {
        var ws = StudioWorkspaceState()
        assertEquals("Preferred default grid mode must be 2x6", CanvasGridMode.GRID_2X6, ws.gridMode)

        val testLayer = SceneLayer(
            id = "box_1",
            name = "Gameplay Box",
            type = LayerSourceType.GAMEPLAY_SCREEN,
            x = 0.20f,
            y = 0.20f,
            width = 0.40f,
            height = 0.40f
        )
        ws = StudioWorkspaceReducer.addLayer(ws, testLayer).copy(showSnapGuides = false)

        // Bottom-Right handle grows width & height while keeping x, y anchored
        ws = StudioWorkspaceReducer.resizeLayerFromCorner(ws, "box_1", ResizeCorner.BOTTOM_RIGHT, 0.10f, 0.08f, false)
        var l = ws.activeScene.layers.first { it.id == "box_1" }
        assertEquals(0.20f, l.x, 0.002f)
        assertEquals(0.20f, l.y, 0.002f)
        assertEquals(0.50f, l.width, 0.002f)
        assertEquals(0.48f, l.height, 0.002f)

        // Top-Left handle moves x, y inward and reduces width, height while keeping right/bottom anchored
        ws = StudioWorkspaceReducer.resizeLayerFromCorner(ws, "box_1", ResizeCorner.TOP_LEFT, 0.05f, 0.04f, false)
        l = ws.activeScene.layers.first { it.id == "box_1" }
        assertEquals(0.25f, l.x, 0.002f)
        assertEquals(0.24f, l.y, 0.002f)
        assertEquals(0.45f, l.width, 0.002f)
        assertEquals(0.44f, l.height, 0.002f)

        // Top-Right and Bottom-Left handles
        ws = StudioWorkspaceReducer.resizeLayerFromCorner(ws, "box_1", ResizeCorner.TOP_RIGHT, 0.05f, -0.04f, false)
        ws = StudioWorkspaceReducer.resizeLayerFromCorner(ws, "box_1", ResizeCorner.BOTTOM_LEFT, -0.05f, 0.06f, false)
        l = ws.activeScene.layers.first { it.id == "box_1" }
        assertEquals(0.20f, l.x, 0.003f)
        assertEquals(0.20f, l.y, 0.003f)
        assertEquals(0.55f, l.width, 0.003f)
        assertEquals(0.54f, l.height, 0.003f)

        // Center H & Center V
        ws = StudioWorkspaceReducer.centerLayerHorizontally(ws, "box_1")
        ws = StudioWorkspaceReducer.centerLayerVertically(ws, "box_1")
        l = ws.activeScene.layers.first { it.id == "box_1" }
        assertEquals((1f - l.width) / 2f, l.x, 0.002f)
        assertEquals((1f - l.height) / 2f, l.y, 0.002f)

        // Fill canvas & save layout persistence across restart
        ws = StudioWorkspaceReducer.fillLayerCanvas(ws, "box_1")
            .copy(
                gridMode = CanvasGridMode.GRID_4X4,
                cameraEnabled = true,
                cameraSourceMode = CameraSourceMode.PHONE2_REAR,
                autoRestoreLastLayout = true,
                savedLayoutTimestampMs = 1700000000000L,
                lastAuthorizedDeviceName = "Phone1Gamer"
            )
        val encoded = StudioPersistence.serializeWorkspace(ws)
        val restored = StudioPersistence.deserializeWorkspace(encoded)
        assertEquals(CanvasGridMode.GRID_4X4, restored.gridMode)
        assertTrue(restored.cameraEnabled)
        assertEquals(CameraSourceMode.PHONE2_REAR, restored.cameraSourceMode)
        assertTrue(restored.autoRestoreLastLayout)
        assertEquals("Phone1Gamer", restored.lastAuthorizedDeviceName)
        val restoredBox = restored.scenes[SceneSlot.GAMEPLAY_LIVE]!!.layers.first { it.id == "box_1" }
        assertEquals(1f, restoredBox.width, 0.001f)
        assertEquals(1f, restoredBox.height, 0.001f)
    }

    @Test
    fun `one touch brb switches cleanly to brb scene and resume restores exact saved gameplay scene`() {
        var ws = StudioWorkspaceState()
        // Customize gameplay scene first
        ws = StudioWorkspaceReducer.updateLayer(ws, "s1_webcam") {
            it.copy(x = 0.65f, y = 0.55f, facecamShape = FacecamShape.CIRCLE)
        }

        // Trigger One-Touch BRB / Privacy
        ws = StudioWorkspaceReducer.triggerBrbPrivacy(ws)
        assertTrue("BRB must be active", ws.brbActive)
        assertEquals(SceneSlot.BRB_BREAK, ws.activeSceneSlot)
        assertEquals(SceneSlot.GAMEPLAY_LIVE, ws.previousSceneBeforeBrb)

        // Resume Game Scene restores exact saved Gameplay layout untouched
        ws = StudioWorkspaceReducer.resumeFromBrbPrivacy(ws)
        assertFalse("BRB must be cleared after resume", ws.brbActive)
        assertEquals(SceneSlot.GAMEPLAY_LIVE, ws.activeSceneSlot)
        val cam = ws.activeScene.layers.first { it.id == "s1_webcam" }
        assertEquals(0.65f, cam.x, 0.001f)
        assertEquals(0.55f, cam.y, 0.001f)
        assertEquals(FacecamShape.CIRCLE, cam.facecamShape)
    }

    @Test
    fun `remote deck supports 3 column default layout customization add remove and reset`() {
        var ws = StudioWorkspaceState()
        assertEquals("Default 3-column deck must have 15 compact buttons (5x3)", 15, ws.remoteTileOrder.size)

        ws = StudioWorkspaceReducer.addRemoteTile(ws, RemoteControlTileId.SWITCH_CAMERA)
        assertTrue(RemoteControlTileId.SWITCH_CAMERA in ws.remoteTileOrder)

        ws = StudioWorkspaceReducer.removeRemoteTile(ws, RemoteControlTileId.SCREENSHOT)
        assertFalse(RemoteControlTileId.SCREENSHOT in ws.remoteTileOrder)

        ws = StudioWorkspaceReducer.resetRemoteDeckToDefault(ws)
        assertEquals(RemoteControlTileId.DEFAULT_ORDER, ws.remoteTileOrder)
    }

    @Test
    fun `four scenes persist independently with custom image browser overlay and subscriber counter`() {
        var ws = StudioWorkspaceState()

        ws = StudioWorkspaceReducer.addLayer(
            ws,
            SceneLayer(
                id = "custom_img_1",
                name = "My Thumbnail",
                type = LayerSourceType.IMAGE,
                mediaUri = "content://media/picker/image_42.png",
                x = 0.25f,
                y = 0.20f,
                width = 0.50f,
                height = 0.40f
            )
        )
        ws = StudioWorkspaceReducer.addLayer(
            ws,
            SceneLayer(
                id = "custom_web_1",
                name = "Alert Browser",
                type = LayerSourceType.BROWSER_WEB,
                webUrl = "https://example.com/alerts",
                transparentBackground = true
            )
        )

        ws = StudioWorkspaceReducer.switchScene(ws, SceneSlot.SHORTS_VERTICAL)
        ws = StudioWorkspaceReducer.renameActiveScene(ws, "My Starting Soon 16:9")
        assertFalse(ws.activeScene.isVertical)

        ws = StudioWorkspaceReducer.switchScene(ws, SceneSlot.BRB_BREAK)
        ws = StudioWorkspaceReducer.updateSceneTransition(ws, SceneTransitionType.SLIDE, 500)

        ws = StudioWorkspaceReducer.switchScene(ws, SceneSlot.ENDING_THANKS)
        ws = StudioWorkspaceReducer.addLayer(
            ws,
            SceneLayer(
                id = "sub_ending",
                name = "Sub Goal",
                type = LayerSourceType.SUBSCRIBER_COUNTER,
                subTitle = "ROAD TO 5K",
                subCount = 4320,
                subGoal = 5000
            )
        )

        val encoded = StudioPersistence.serializeWorkspace(ws)
        val restored = StudioPersistence.deserializeWorkspace(encoded)

        val s1 = restored.scenes[SceneSlot.GAMEPLAY_LIVE]!!
        assertTrue(s1.layers.any { it.id == "custom_img_1" && it.mediaUri == "content://media/picker/image_42.png" })
        assertTrue(s1.layers.any { it.id == "custom_web_1" && it.webUrl == "https://example.com/alerts" })

        val s2 = restored.scenes[SceneSlot.SHORTS_VERTICAL]!!
        assertEquals("My Starting Soon 16:9", s2.name)
        assertFalse(s2.isVertical)
        assertFalse("Scene 1 custom image must NOT leak into Scene 2", s2.layers.any { it.id == "custom_img_1" })

        val s3 = restored.scenes[SceneSlot.BRB_BREAK]!!
        assertEquals(SceneTransitionType.SLIDE, s3.transitionType)
        assertEquals(500, s3.transitionDurationMs)

        val s4 = restored.scenes[SceneSlot.ENDING_THANKS]!!
        val sub = s4.layers.first { it.id == "sub_ending" }
        assertEquals(4320, sub.subCount)
        assertEquals(5000, sub.subGoal)
    }

    @Test
    fun `layer lock and scene lock strictly block dragging resizing editing and deleting until unlocked`() {
        var ws = StudioWorkspaceState()
        val testLayer = SceneLayer(
            id = "lock_test_logo",
            name = "Logo",
            type = LayerSourceType.LOGO,
            x = 0.20f,
            y = 0.20f,
            width = 0.30f,
            height = 0.20f,
            locked = false
        )
        ws = StudioWorkspaceReducer.addLayer(ws, testLayer)

        ws = StudioWorkspaceReducer.toggleLayerLock(ws, "lock_test_logo")
        assertTrue(ws.activeScene.layers.first { it.id == "lock_test_logo" }.locked)

        val afterMove = StudioWorkspaceReducer.moveLayerBy(ws, "lock_test_logo", 0.15f, 0.10f)
        val afterCornerResize = StudioWorkspaceReducer.resizeLayerFromCorner(afterMove, "lock_test_logo", ResizeCorner.BOTTOM_RIGHT, 0.10f, 0.10f)
        val afterDelete = StudioWorkspaceReducer.deleteLayer(afterCornerResize, "lock_test_logo")

        val stillThere = afterDelete.activeScene.layers.first { it.id == "lock_test_logo" }
        assertEquals(0.20f, stillThere.x, 0.0001f)
        assertEquals(0.20f, stillThere.y, 0.0001f)
        assertEquals(0.30f, stillThere.width, 0.0001f)

        ws = StudioWorkspaceReducer.toggleLayerLock(afterDelete, "lock_test_logo").copy(showSnapGuides = false)
        ws = StudioWorkspaceReducer.moveLayerBy(ws, "lock_test_logo", 0.15f, 0.10f)
        val moved = ws.activeScene.layers.first { it.id == "lock_test_logo" }
        assertEquals(0.35f, moved.x, 0.005f)
        assertEquals(0.30f, moved.y, 0.005f)

        ws = StudioWorkspaceReducer.toggleSceneLock(ws)
        val blockedBySceneLock = StudioWorkspaceReducer.moveLayerBy(ws, "lock_test_logo", 0.20f, 0.20f)
        val unchanged = blockedBySceneLock.activeScene.layers.first { it.id == "lock_test_logo" }
        assertEquals(moved.x, unchanged.x, 0.0001f)
    }

    @Test
    fun `team voice to live toggle strictly separates private teammate audio from live broadcast bus`() {
        val baseSettings = AudioSettings(
            micEnabled = false,
            gameAudioEnabled = false,
            teamVoiceEnabled = true,
            teamVoiceToLive = false,
            masterVolume = 1.0f
        )

        val privateMixed = ZroAudioEngine.mixLiveBroadcastSample(
            micNormalized = 0f,
            gameNormalized = 0f,
            teamNormalized = 0.55f,
            settings = baseSettings
        )
        assertEquals("Teammate voice must be silent on live bus when TEAM VOICE TO LIVE is OFF", 0.toShort(), privateMixed)

        val liveMixed = ZroAudioEngine.mixLiveBroadcastSample(
            micNormalized = 0f,
            gameNormalized = 0f,
            teamNormalized = 0.55f,
            settings = baseSettings.copy(teamVoiceToLive = true)
        )
        assertTrue("Teammate voice must be mixed into live bus when TEAM VOICE TO LIVE is ON", abs(liveMixed.toInt()) > 15000)

        // Mic stays live when team voice is muted
        val micOnlyWhileTeamMuted = ZroAudioEngine.mixLiveBroadcastSample(
            micNormalized = 0.35f,
            gameNormalized = 0f,
            teamNormalized = 0.50f,
            settings = baseSettings.copy(micEnabled = true, teamVoiceToLive = false)
        )
        assertTrue("User microphone must remain live when Team Voice is muted", abs(micOnlyWhileTeamMuted.toInt()) > 5000)
    }

    @Test
    fun `close voice focus and wind reduction slider attenuate rumble and distant noise while preserving close speech`() {
        val dsp = VoiceDspProcessor(sampleRate = 44100)
        val closeFocusSettings = AudioSettings(
            micEnabled = true,
            micVolume = 1.0f,
            closeVoiceFocusEnabled = true,
            noiseCancellationLevel = NoiseCancellationLevel.VOICE_ONLY,
            noiseGateEnabled = true,
            noiseGateThresholdDb = -36f,
            windNoiseReduction = true,
            windReductionStrength = 0.90f,
            voiceIsolationStrength = 0.85f,
            ambientNoiseReduction = 0.85f
        )

        val quietBuf = ShortArray(1024) { i ->
            (sin(2.0 * PI * 300.0 * i / 44100.0) * 160.0).toInt().toShort()
        }
        var quietMetrics = dsp.processInPlace(quietBuf.copyOf(), 1024, closeFocusSettings)
        repeat(10) {
            quietMetrics = dsp.processInPlace(quietBuf.copyOf(), 1024, closeFocusSettings)
        }
        assertFalse("Voice gate should stay closed for quiet distant background noise", quietMetrics.gateOpen)
        assertTrue("Quiet ambient noise should be heavily attenuated (< -48 dBFS)", quietMetrics.dbFs < -48f)

        val closeVoiceBuf = ShortArray(1024) { i ->
            (sin(2.0 * PI * 850.0 * i / 44100.0) * 7500.0).toInt().toShort()
        }
        val voiceMetrics = dsp.processInPlace(closeVoiceBuf.copyOf(), 1024, closeFocusSettings)
        assertTrue("Voice gate must open for close direct speech", voiceMetrics.gateOpen)
        assertTrue("Close direct speech must remain loud and clear (> -22 dBFS)", voiceMetrics.dbFs > -22f)

        val windDspOn = VoiceDspProcessor(sampleRate = 44100)
        val windDspOff = VoiceDspProcessor(sampleRate = 44100)
        val rumble = ShortArray(1024) { i ->
            (sin(2.0 * PI * 55.0 * i / 44100.0) * 9000.0).toInt().toShort()
        }
        val noWindSettings = AudioSettings(
            micEnabled = true,
            noiseCancellationLevel = NoiseCancellationLevel.OFF,
            noiseGateEnabled = false,
            windNoiseReduction = false,
            voiceClarityEnhancement = false
        )
        val strongWindSettings = noWindSettings.copy(windNoiseReduction = true, windReductionStrength = 1.0f)
        val mOff = windDspOff.processInPlace(rumble.copyOf(), 1024, noWindSettings)
        val mOn = windDspOn.processInPlace(rumble.copyOf(), 1024, strongWindSettings)
        assertTrue(
            "Wind Reduction HPF must reduce 55Hz wind rumble (on=${mOn.dbFs} dB vs off=${mOff.dbFs} dB)",
            mOn.dbFs < mOff.dbFs - 4f
        )
    }
}
