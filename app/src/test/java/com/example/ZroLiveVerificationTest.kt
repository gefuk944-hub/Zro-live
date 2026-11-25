package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.audio.VoiceDspProcessor
import com.example.chat.YouTubeLiveChatClient
import com.example.data.SettingsStore
import com.example.data.model.AudioSettings
import com.example.data.model.CaptureMode
import com.example.data.model.DeviceTelemetry
import com.example.data.model.NoiseCancellationLevel
import com.example.data.model.PerformanceProfile
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamSessionDoc
import com.example.data.model.StreamSettings
import com.example.data.model.VideoResolution
import com.example.streaming.ZroStreamCoordinator
import com.example.telemetry.DeviceTelemetryMonitor
import com.example.viewmodel.ZroMainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ZroLiveVerificationTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        ZroStreamCoordinator.resetToIdle()
    }

    @After
    fun tearDown() {
        ZroStreamCoordinator.resetToIdle()
        Dispatchers.resetMain()
    }

    @Test
    fun `guest mode initializes cleanly with zero repository and persists local settings and sessions`() = runBlocking {
        val store = SettingsStore(context)
        val monitor = DeviceTelemetryMonitor(context)
        val chatClient = YouTubeLiveChatClient()

        val vm = ZroMainViewModel(
            appContext = context,
            repository = null,
            settingsStore = store,
            telemetryMonitor = monitor,
            chatClient = chatClient,
            currentUserId = null,
            userDisplayName = "Guest Streamer",
            userAvatarUrl = null
        )

        assertTrue("ViewModel must report guest mode when unauthenticated", vm.isGuest)
        assertNull("Guest mode must not attach Firestore profile", vm.userProfile.value)
        assertTrue("Guest mode must not query public voice rooms", vm.publicVoiceRooms.value.isEmpty())

        // Verify local settings and local broadcast session history work without any account
        val updatedStream = StreamSettings(
            streamTitle = "Guest Ranked Match",
            streamKey = "guest-local-key-1234",
            resolution = VideoResolution.P720,
            fps = 30,
            bitrateKbps = 3500
        )
        store.saveStreamSettings(updatedStream)
        val reloaded = store.settingsFlow.first()
        assertEquals("Guest Ranked Match", reloaded.stream.streamTitle)
        assertEquals(CaptureMode.FULL_SCREEN, reloaded.stream.captureMode)
        assertTrue(reloaded.stream.fullScreenExplicitlyEnabled)

        store.appendLocalSession(
            StreamSessionDoc(
                id = "local_test_1",
                userId = "guest",
                title = "Guest Ranked Match",
                resolutionLabel = "720p",
                fps = 30L,
                bitrateKbps = 3500L,
                durationSec = 600L
            )
        )
        val localHistory = store.localSessionsFlow.first()
        assertTrue("Local session history should contain saved guest broadcast", localHistory.any { it.id == "local_test_1" })
    }

    @Test
    fun `capture mode defaults to full screen for 1 phone live and supports switching to game app only`() = runBlocking {
        val store = SettingsStore(context)

        // Default model starts ready for immediate 1-phone full-screen broadcast
        val defaultStream = StreamSettings()
        assertEquals(CaptureMode.FULL_SCREEN, defaultStream.captureMode)
        assertTrue(defaultStream.fullScreenExplicitlyEnabled)

        // User can switch to Game / App-Only mode and persist it
        store.saveStreamSettings(
            defaultStream.copy(
                captureMode = CaptureMode.GAME_APP_ONLY,
                fullScreenExplicitlyEnabled = false
            )
        )
        val gameOnly = store.settingsFlow.first()
        assertEquals(CaptureMode.GAME_APP_ONLY, gameOnly.stream.captureMode)
        assertFalse(gameOnly.stream.fullScreenExplicitlyEnabled)

        // Switching back to FULL_SCREEN persists cleanly
        store.saveStreamSettings(
            defaultStream.copy(
                captureMode = CaptureMode.FULL_SCREEN,
                fullScreenExplicitlyEnabled = true
            )
        )
        val fullScreen = store.settingsFlow.first()
        assertEquals(CaptureMode.FULL_SCREEN, fullScreen.stream.captureMode)
        assertTrue(fullScreen.stream.fullScreenExplicitlyEnabled)
    }

    @Test
    fun `temperature telemetry never fakes a safe reading when sensor is unmeasured`() {
        val unmeasured = DeviceTelemetry(
            batteryTempCelsius = null,
            isTemperatureMeasured = false,
            thermalStatusLabel = "Unavailable",
            isOverheating = false
        )
        val formattedUnmeasured = DeviceTelemetryMonitor.formatTemperature(unmeasured)
        assertEquals("Unavailable", formattedUnmeasured)
        assertFalse("Must never fake 32.0°C when unmeasured", formattedUnmeasured.contains("32"))

        val measured = DeviceTelemetry(
            batteryTempCelsius = 43.5f,
            isTemperatureMeasured = true,
            thermalStatusLabel = "Hot (Throttling)",
            isOverheating = true
        )
        assertEquals("43.5°C", DeviceTelemetryMonitor.formatTemperature(measured))

        // On Robolectric with no sticky ACTION_BATTERY_CHANGED intent, snapshot must report unmeasured (null), never 32.0f
        val monitor = DeviceTelemetryMonitor(context)
        val snap = monitor.snapshot()
        assertNotEquals(32.0f, snap.batteryTempCelsius)
        assertTrue("API 36 should report Android 14+ single-app capture support", snap.supportsAppOnlyCapture)
    }

    @Test
    fun `smart recommendation adapts for overheating battery saver and 4GB RAM devices`() {
        val monitor = DeviceTelemetryMonitor(context)

        val overheatingTelemetry = DeviceTelemetry(
            batteryTempCelsius = 44.0f,
            isTemperatureMeasured = true,
            thermalStatusLabel = "Hot (Throttling)",
            isOverheating = true,
            totalRamMb = 8192L,
            networkConnected = true,
            upstreamBandwidthKbps = 20000
        )
        val hotRec = monitor.computeSmartRecommendation(overheatingTelemetry, PerformanceProfile.BALANCED)
        assertEquals(VideoResolution.P480, hotRec.resolution)
        assertEquals(30, hotRec.fps)

        val batteryTelemetry = DeviceTelemetry(
            batteryTempCelsius = 35.0f,
            isTemperatureMeasured = true,
            isOverheating = false,
            totalRamMb = 8192L,
            networkConnected = true,
            upstreamBandwidthKbps = 20000
        )
        val batteryRec = monitor.computeSmartRecommendation(batteryTelemetry, PerformanceProfile.BATTERY_SAVER)
        assertEquals(VideoResolution.P480, batteryRec.resolution)
        assertEquals(30, batteryRec.fps)

        val lowRamTelemetry = DeviceTelemetry(
            batteryTempCelsius = null,
            isTemperatureMeasured = false,
            isOverheating = false,
            totalRamMb = 4096L,
            isLowRamDevice = true,
            networkConnected = true,
            upstreamBandwidthKbps = 12000
        )
        val lowRamRec = monitor.computeSmartRecommendation(lowRamTelemetry, PerformanceProfile.PERFORMANCE, lowRamMode = true)
        assertEquals(VideoResolution.P720, lowRamRec.resolution)
        assertEquals(30, lowRamRec.fps)
    }

    @Test
    fun `voice dsp fastSoftClip and lightweightMode process frames cleanly and zero muted frames`() {
        // Linear region preserved
        assertEquals(0.5f, VoiceDspProcessor.fastSoftClip(0.5f), 1e-5f)
        assertEquals(-0.8f, VoiceDspProcessor.fastSoftClip(-0.8f), 1e-5f)
        // Extreme peaks clamped smoothly inside [-1, 1]
        assertTrue(VoiceDspProcessor.fastSoftClip(10.0f) in 0.85f..1.0f)
        assertTrue(VoiceDspProcessor.fastSoftClip(-10.0f) in -1.0f..-0.85f)

        val dsp = VoiceDspProcessor(44100)
        val pcm = ShortArray(1024) { i ->
            (sin(2.0 * Math.PI * 440.0 * i / 44100.0) * 16000.0).toInt().toShort()
        }
        val activeSettings = AudioSettings(
            micEnabled = true,
            noiseCancellationLevel = NoiseCancellationLevel.MEDIUM,
            windNoiseReduction = true,
            voiceClarityEnhancement = true
        )
        val metrics = dsp.processInPlace(pcm, pcm.size, activeSettings, lightweightMode = true)
        assertTrue("Processed signal level should be in [0, 1]", metrics.normalizedLevel in 0f..1f)
        assertFalse("dBFS must be finite", metrics.dbFs.isNaN())
        assertTrue("Processed buffer should contain non-zero speech energy", pcm.any { abs(it.toInt()) > 100 })

        // Muted fast path zeroes buffer immediately
        val mutedSettings = activeSettings.copy(micEnabled = false)
        dsp.processInPlace(pcm, pcm.size, mutedSettings, lightweightMode = true)
        assertTrue("Muted fast-path must zero every PCM sample", pcm.all { it == 0.toShort() })
    }

    @Test
    fun `stream coordinator prevents duplicate sessions and cleans up capture state on reset`() {
        assertTrue(ZroStreamCoordinator.tryAcquireCaptureLock())
        assertFalse("Duplicate capture must be blocked", ZroStreamCoordinator.tryAcquireCaptureLock())

        assertTrue(ZroStreamCoordinator.tryAcquireSessionLock())
        assertFalse("Duplicate streaming session must be blocked", ZroStreamCoordinator.tryAcquireSessionLock())

        ZroStreamCoordinator.pendingMediaProjectionResultCode = -1
        ZroStreamCoordinator.pendingMediaProjectionData = Intent("test.projection")

        ZroStreamCoordinator.resetToIdle()

        assertFalse(ZroStreamCoordinator.isSessionActive())
        assertFalse(ZroStreamCoordinator.isCaptureActive())
        assertEquals(0, ZroStreamCoordinator.pendingMediaProjectionResultCode)
        assertNull(ZroStreamCoordinator.pendingMediaProjectionData)
        assertEquals(StreamLifecycleState.IDLE, ZroStreamCoordinator.telemetry.value.state)
        assertFalse(ZroStreamCoordinator.telemetry.value.captureActive)
        assertFalse(ZroStreamCoordinator.telemetry.value.privacyShieldActive)
    }
}
