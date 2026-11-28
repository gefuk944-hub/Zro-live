package com.example

import android.media.MediaRecorder
import android.view.WindowManager
import com.example.audio.ZroAudioEngine
import com.example.data.model.AudioSettings
import com.example.data.model.StreamPreset
import com.example.data.model.StreamSettings
import com.example.overlay.FloatingOverlayService
import com.example.streaming.GlLiveStreamCompositor
import com.example.streaming.RtmpPublisher
import com.example.streaming.StreamStateManager
import com.example.streaming.StreamingForegroundService
import com.example.streaming.ZroStreamingEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies all 6 sections of the BGMI stability, video quality, monotonic timer,
 * dual audio capture, Ping Guard ABR, and touch-safety upgrade without altering
 * Crop logic, Source Loader, WebView scroll lock, or Remote Control UI.
 */
class BgmiStreamingOptimizationTest {

    @Before
    fun setUp() {
        StreamStateManager.resetToStopped()
    }

    @After
    fun tearDown() {
        StreamStateManager.resetToStopped()
    }

    // ── Section 1: Monotonic Foreground Service Timer (Fixes 00:00:02 Freeze) ──

    @Test
    fun streamStateManager_ticksContinuouslyPast2Seconds_andResetsOnlyOnStop() {
        val anchor = 100_000L
        val started = StreamStateManager.markStreamStarted(startElapsedRealtime = anchor, targetBitrateKbps = 3800)
        assertTrue(started.isStreaming)
        assertEquals("00:00:00", started.formattedDuration)

        // Simulate 500ms ticks advancing continuously well past 00:00:02 (to 01:02:05)
        val t2s = StreamStateManager.tick(anchor + 2_000L)
        assertEquals("00:00:02", t2s.formattedDuration)

        val t3s = StreamStateManager.tick(anchor + 3_500L)
        assertEquals("00:00:03", t3s.formattedDuration)

        // Pausing must NOT reset the monotonic live timer
        StreamStateManager.setPaused(true)
        val t1h2m5s = StreamStateManager.tick(anchor + 3_725_500L)
        assertEquals("01:02:05", t1h2m5s.formattedDuration)
        assertEquals(3725L, t1h2m5s.elapsedSeconds)

        // Calling markStreamStarted again while streaming must NOT wipe anchor
        val stillRunning = StreamStateManager.markStreamStarted(startElapsedRealtime = anchor + 3_730_000L)
        assertEquals(anchor, stillRunning.streamStartElapsedRealtime)
        assertTrue(stillRunning.elapsedSeconds >= 3725L)

        // ONLY stopping resets back to 00:00:00
        val stopped = StreamStateManager.resetToStopped()
        assertFalse(stopped.isStreaming)
        assertEquals("00:00:00", stopped.formattedDuration)
        assertEquals(0L, stopped.elapsedSeconds)
    }

    // ── Section 2: 16-px Alignment, Low DPI, Recommended Bitrates & Defaults ──

    @Test
    fun streamingEngine_alignsDimensionsTo16_lowDpi_andRecommendedBitrates() {
        // Strict multiples of 16
        val (w720, h720) = StreamingForegroundService.resolveAlignedDimensions(1280, 720, portrait = false)
        assertEquals(1280, w720)
        assertEquals(720, h720)
        assertEquals(0, w720 % 16)
        assertEquals(0, h720 % 16)

        val (w1080, h1080) = StreamingForegroundService.resolveAlignedDimensions(1920, 1080, portrait = false)
        assertEquals(1920, w1080)
        assertEquals(0, w1080 % 16)
        assertEquals(0, h1080 % 16)

        // Low DPI: 240 for 720p, 320 for 1080p
        assertEquals(240, ZroStreamingEngine.resolveVirtualDisplayDpi(1280, 720))
        assertEquals(320, ZroStreamingEngine.resolveVirtualDisplayDpi(1920, 1080))

        // Default bitrates: 3800 (720p30), 5500 (720p60 & 1080p30), 7500 (1080p60)
        assertEquals(3800, ZroStreamingEngine.recommendedBitrateKbps(1280, 720, 30))
        assertEquals(5500, ZroStreamingEngine.recommendedBitrateKbps(1280, 720, 60))
        assertEquals(5500, ZroStreamingEngine.recommendedBitrateKbps(1920, 1080, 30))
        assertEquals(7500, ZroStreamingEngine.recommendedBitrateKbps(1920, 1080, 60))
        assertEquals(2, ZroStreamingEngine.DEFAULT_KEYFRAME_INTERVAL_SEC)

        // Default StreamSettings & presets match crisp 30 FPS / 3800 kbps defaults
        val defaults = StreamSettings()
        assertEquals(30, defaults.fps)
        assertEquals(3800, defaults.bitrateKbps)
        assertEquals(3800, StreamPreset.HD.recommendedBitrateKbps)
        assertEquals(5500, StreamPreset.HD_SMOOTH.recommendedBitrateKbps)
        assertEquals(5500, StreamPreset.FULL_HD.recommendedBitrateKbps)
        assertEquals(7500, StreamPreset.FULL_HD_SMOOTH.recommendedBitrateKbps)
    }

    // ── Section 3: BGMI Dual Audio Capture (No VOICE_COMMUNICATION Conflict) ──

    @Test
    fun audioEngine_prefersVoiceRecognitionThenCamcorder_andSoftClipsDualMix() {
        val sources = ZroAudioEngine.preferredMicAudioSources()
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, sources[0])
        assertEquals(MediaRecorder.AudioSource.CAMCORDER, sources[1])
        assertFalse(sources.contains(MediaRecorder.AudioSource.VOICE_COMMUNICATION))

        // Simultaneous Game + Mic loud mix soft-clips cleanly within Short range without wraparound
        val cfg = AudioSettings(
            micEnabled = true,
            gameAudioEnabled = true,
            gameVolume = 1.0f,
            masterVolume = 1.2f
        )
        val mixed = ZroAudioEngine.mixLiveBroadcastSample(
            micNormalized = 0.95f,
            gameNormalized = 0.95f,
            teamNormalized = 0f,
            settings = cfg
        )
        assertTrue("Mixed PCM must stay safely positive and soft-clipped", mixed in 20_000..32_767)
    }

    // ── Section 4: BGMI Ping Guard Socket Config, Bounded Queue & 1s ABR ──

    @Test
    fun rtmpPingGuard_boundedQueueDropsStalePFrames_andAbrStepsDownAbove120ms() {
        // Bounded TCP send buffer strictly within 64KB..128KB
        assertTrue(RtmpPublisher.RTMP_SEND_BUFFER_BYTES in (64 * 1024)..(128 * 1024))
        assertEquals(35, RtmpPublisher.MAX_SEND_QUEUE_FRAMES)
        assertEquals(1_500L, RtmpPublisher.MAX_FRAME_QUEUE_AGE_MS)

        val queue = RtmpPublisher.Companion.BoundedRtmpSendQueue(maxFrames = 35, maxAgeMs = 1_500L)
        // Enqueue 1 keyframe + 34 P-frames = 35 frames at t=1000ms
        assertTrue(
            queue.enqueue(
                RtmpPublisher.Companion.QueuedRtmpPacket(
                    csid = 6, timestampMs = 0, typeId = 0x09, streamId = 1,
                    payload = byteArrayOf(1), isVideo = true, isKeyFrame = true,
                    isDroppablePFrame = false, enqueuedElapsedMs = 1_000L
                ),
                nowMs = 1_000L
            )
        )
        for (i in 1..34) {
            assertTrue(
                queue.enqueue(
                    RtmpPublisher.Companion.QueuedRtmpPacket(
                        csid = 6, timestampMs = i * 33, typeId = 0x09, streamId = 1,
                        payload = byteArrayOf(2), isVideo = true, isKeyFrame = false,
                        isDroppablePFrame = true, enqueuedElapsedMs = 1_000L + i * 10L
                    ),
                    nowMs = 1_000L + i * 10L
                )
            )
        }
        assertEquals(35, queue.size)

        // Adding another audio packet under congestion drops a P-frame (preserving keyframe & audio)
        assertTrue(
            queue.enqueue(
                RtmpPublisher.Companion.QueuedRtmpPacket(
                    csid = 4, timestampMs = 500, typeId = 0x08, streamId = 1,
                    payload = byteArrayOf(3), isVideo = false, isKeyFrame = false,
                    isDroppablePFrame = false, enqueuedElapsedMs = 1_400L
                ),
                nowMs = 1_400L
            )
        )
        assertEquals(35, queue.size)
        assertEquals(1L, queue.droppedPFramesCount)

        // Advancing time past 1.5s evicts stale P-frames automatically while keeping keyframe & audio
        assertTrue(
            queue.enqueue(
                RtmpPublisher.Companion.QueuedRtmpPacket(
                    csid = 4, timestampMs = 2_600, typeId = 0x08, streamId = 1,
                    payload = byteArrayOf(4), isVideo = false, isKeyFrame = false,
                    isDroppablePFrame = false, enqueuedElapsedMs = 3_200L
                ),
                nowMs = 3_200L
            )
        )
        assertEquals(3, queue.size) // 1 keyframe + 2 audio packets survived; all 34 stale P-frames dropped!
        assertEquals(34L, queue.droppedPFramesCount)

        // 1-second ABR: write latency > 120ms drops bitrate by 25%, floored at 1800 kbps for 720p
        val (step1, abr1) = ZroStreamingEngine.computePingGuardBitrateKbps(
            currentBitrateKbps = 3800,
            targetBitrateKbps = 3800,
            writeLatencyMs = 155L,
            width = 1280,
            height = 720,
            consecutiveHealthyTicks = 0
        )
        assertEquals(2850, step1)
        assertTrue(abr1)

        val (stepFloor, abrFloor) = ZroStreamingEngine.computePingGuardBitrateKbps(
            currentBitrateKbps = 2000,
            targetBitrateKbps = 3800,
            writeLatencyMs = 240L,
            width = 1280,
            height = 720,
            consecutiveHealthyTicks = 0
        )
        assertEquals(1800, stepFloor)
        assertTrue(abrFloor)

        // Recovery after network stabilizes (< 80ms for >= 4 seconds)
        val (recovered, _) = ZroStreamingEngine.computePingGuardBitrateKbps(
            currentBitrateKbps = 1800,
            targetBitrateKbps = 3800,
            writeLatencyMs = 35L,
            width = 1280,
            height = 720,
            consecutiveHealthyTicks = 5
        )
        assertTrue("Bitrate should recover smoothly when network stabilizes", recovered > 1800)
    }

    // ── Section 5 & 6: 16:9 Landscape Aspect Lock, Overlay Touch Safety & 30 FPS / WebView Throttling ──

    @Test
    fun glCompositor_locks20x9UltrawideInto16x9Landscape_andOverlayFlagsAllowMultiTouchClaw() {
        val mat = FloatArray(16)
        // 20:9 ultrawide phone (2400x1080) onto locked 16:9 1280x720 landscape canvas
        GlLiveStreamCompositor.computeAspectLockMatrix(
            srcWidth = 2400,
            srcHeight = 1080,
            dstWidth = 1280,
            dstHeight = 720,
            fillMode = false,
            outMatrix = mat
        )
        // Aspect-fit preserves full width (scaleX = 1) and scales Y down cleanly to prevent vertical stretch
        assertEquals(1f, mat[0], 0.001f)
        assertEquals((16f / 9f) / (20f / 9f), mat[5], 0.005f)

        // Aspect-fill scales X up (center crop) and keeps Y = 1 with zero distortion
        GlLiveStreamCompositor.computeAspectLockMatrix(
            srcWidth = 2400,
            srcHeight = 1080,
            dstWidth = 1280,
            dstHeight = 720,
            fillMode = true,
            outMatrix = mat
        )
        assertEquals((20f / 9f) / (16f / 9f), mat[0], 0.005f)
        assertEquals(1f, mat[5], 0.001f)

        // Low-end overheating guard defaults: 30 FPS & WebView upload throttled to 10–15 FPS (80ms)
        assertEquals(30, GlLiveStreamCompositor.DEFAULT_FPS)
        assertTrue(GlLiveStreamCompositor.WEB_OVERLAY_UPLOAD_INTERVAL_MS in 66L..100L)

        // Floating overlay touch safety flags: BOTH FLAG_NOT_FOCUSABLE and FLAG_NOT_TOUCH_MODAL set
        val flags = FloatingOverlayService.OVERLAY_WINDOW_FLAGS
        assertTrue((flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
        assertTrue((flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0)
    }
}
