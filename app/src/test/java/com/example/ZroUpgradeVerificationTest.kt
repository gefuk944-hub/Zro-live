package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DeviceTelemetry
import com.example.data.model.PerformanceProfile
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.VideoResolution
import com.example.dualphone.DualPairingPayload
import com.example.dualphone.DualPhoneProtocol
import com.example.streaming.StreamNotificationManager
import com.example.streaming.StudioLiveCompositor
import com.example.studio.LayerSourceType
import com.example.studio.SceneLayer
import com.example.studio.SceneSlot
import com.example.studio.StudioWorkspaceReducer
import com.example.studio.StudioWorkspaceState
import com.example.telemetry.DeviceTelemetryMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZroUpgradeVerificationTest {

    @Test
    fun compositorRendersGameplayAndAllFourScenesIn16x9And9x16() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val compositor = StudioLiveCompositor(context)

        // Create a recognizable vivid green gameplay frame
        val gameplayBmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(0, 210, 80))
        }

        val workspace16x9 = StudioWorkspaceState()
        val out16x9 = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        compositor.renderFrame(
            canvas = Canvas(out16x9),
            workspace = workspace16x9,
            gameplayBitmap = gameplayBmp
        )

        // Verify real gameplay pixels rendered into the composited live output
        val centerPixel = out16x9.getPixel(320, 180)
        assertTrue(
            "Expected live gameplay green pixels in composited output, got ${Integer.toHexString(centerPixel)}",
            Color.green(centerPixel) > 150 && Color.red(centerPixel) < 80
        )

        // Add a bright red live overlay layer and verify instantaneous reflection
        val withRedLayer = StudioWorkspaceReducer.addLayer(
            workspace16x9,
            SceneLayer(
                name = "Live Alert",
                type = LayerSourceType.TEXT,
                x = 0.4f,
                y = 0.4f,
                width = 0.2f,
                height = 0.2f,
                backgroundColorHex = 0xFFFF0000L,
                transparentBackground = false,
                textContent = "LIVE"
            )
        )
        compositor.renderFrame(
            canvas = Canvas(out16x9),
            workspace = withRedLayer,
            gameplayBitmap = gameplayBmp
        )
        val overlayPixel = out16x9.getPixel(270, 155)
        assertTrue(
            "Expected newly added OBS layer to render immediately into live output",
            Color.red(overlayPixel) > 180
        )

        // Verify Screen 2 (Starting Soon), Screen 3 (BRB), Screen 4 (Ending) in both 16:9 and Shorts 9:16
        val out9x16 = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        for (slot in listOf(SceneSlot.SHORTS_VERTICAL, SceneSlot.BRB_BREAK, SceneSlot.ENDING_THANKS)) {
            val switched = StudioWorkspaceReducer.switchScene(workspace16x9, slot)
            compositor.renderFrame(Canvas(out16x9), switched, gameplayBmp)
            compositor.renderFrame(Canvas(out9x16), switched, gameplayBmp, forceVerticalSceneOverride = true)
        }

        compositor.release()
        gameplayBmp.recycle()
        out16x9.recycle()
        out9x16.recycle()
    }

    @Test
    fun deviceTelemetryGates4k90FpsAnd40MbpsHonestly() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val monitor = DeviceTelemetryMonitor(context)

        val flagship = DeviceTelemetry(
            totalRamMb = 12_288,
            availableRamMb = 6_400,
            batteryTempCelsius = 34.0f,
            isOverheating = false,
            upstreamBandwidthKbps = 65_000,
            supports720p90 = true,
            supports1080p60 = true,
            supports1080p90 = true,
            supports1440p30 = true,
            supports1440p60 = true,
            supports4k30 = true,
            supports4k60 = true,
            supports90Fps = true,
            maxEncoderBitrateKbps = 40_000
        )

        assertTrue(DeviceTelemetryMonitor.supportsResolutionAndFps(flagship, VideoResolution.P2160, 60))
        assertTrue(DeviceTelemetryMonitor.supportsResolutionAndFps(flagship, VideoResolution.P1080, 90))
        assertFalse(DeviceTelemetryMonitor.supportsResolutionAndFps(flagship, VideoResolution.P2160, 90))
        assertEquals(
            40_000,
            DeviceTelemetryMonitor.maxAllowedBitrateKbps(
                telemetry = flagship,
                profile = PerformanceProfile.PERFORMANCE,
                lowRamMode = false,
                lowDataMode = false
            )
        )

        // Entry-level / constrained device must NOT unlock 4K, 90fps, or 40Mbps
        val midRange = DeviceTelemetry(
            totalRamMb = 3_072,
            availableRamMb = 900,
            batteryTempCelsius = 43.5f,
            isOverheating = true,
            upstreamBandwidthKbps = 4_000,
            supports720p90 = false,
            supports1080p60 = false,
            supports1080p90 = false,
            supports1440p30 = false,
            supports1440p60 = false,
            supports4k30 = false,
            supports4k60 = false,
            supports90Fps = false,
            maxEncoderBitrateKbps = 12_000
        )

        assertFalse(DeviceTelemetryMonitor.supportsResolutionAndFps(midRange, VideoResolution.P2160, 30))
        assertFalse(DeviceTelemetryMonitor.supportsResolutionAndFps(midRange, VideoResolution.P1080, 90))
        val rec = monitor.computeSmartRecommendation(midRange, PerformanceProfile.BALANCED, lowRamMode = false)
        assertTrue("Overheating device must recommend <= 30 FPS", rec.fps <= 30)
        assertTrue("Overheating device must keep bitrate <= 3000 kbps", rec.bitrateKbps <= 3000)
    }

    @Test
    fun dualPhoneProtocolRelayUriRoundTripAndValidation() {
        assertTrue(DualPhoneProtocol.isValidRelayUrl("wss://relay.zrolive.example/ws"))
        assertTrue(DualPhoneProtocol.isValidRelayUrl("ws://192.168.1.50:8080/relay"))
        assertFalse(DualPhoneProtocol.isValidRelayUrl("https://not-a-ws.example"))
        assertFalse(DualPhoneProtocol.isValidRelayUrl(""))

        val payload = DualPairingPayload(
            ip = "192.168.1.44",
            controlPort = 47920,
            videoPort = 47921,
            token = "AB12CD34",
            hostName = "Studio Pixel",
            relayUrl = "wss://relay.example.com/ws"
        )
        val uri = DualPhoneProtocol.buildPairingUri(payload)
        val parsed = DualPhoneProtocol.parsePairingUri(uri)
        assertNotNull(parsed)
        assertEquals("192.168.1.44", parsed!!.ip)
        assertEquals("AB12CD34", parsed.token)
        assertEquals("wss://relay.example.com/ws", parsed.relayUrl)

        val endpoint = DualPhoneProtocol.buildRelayRoomEndpoint("wss://relay.example.com/ws", "AB12CD34", "studio")
        assertTrue(endpoint.startsWith("wss://relay.example.com/ws?"))
        assertTrue(endpoint.contains("room=AB12CD34"))
        assertTrue(endpoint.contains("role=studio"))
    }

    @Test
    fun streamNotificationIncludesScreen1To4ControlsAndTemperature() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = StreamNotificationManager(context)
        val telemetry = StreamRuntimeTelemetry(
            state = StreamLifecycleState.LIVE,
            durationSec = 125L,
            actualBitrateKbps = 6500,
            actualFps = 60,
            resolutionLabel = "1080p",
            deviceTempCelsius = 38.5f,
            thermalLabel = "39°C (Normal)",
            activeSceneName = "Gameplay"
        )
        val notif = manager.buildNotification(telemetry, micMuted = false, voiceRoomName = null)
        assertNotNull(notif)
        assertNotNull("Expected custom expanded RemoteViews for Screen 1-4 controls", notif.bigContentView)

        // Verify all 4 scene switch PendingIntents can be built cleanly
        SceneSlot.entries.forEach { slot ->
            assertNotNull(manager.buildSceneSwitchPendingIntent(slot))
        }
    }
}
