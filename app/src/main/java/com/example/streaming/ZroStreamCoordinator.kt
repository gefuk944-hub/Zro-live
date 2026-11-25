package com.example.streaming

import android.content.Context
import android.content.Intent
import android.view.Surface
import com.example.data.model.AudioSettings
import com.example.data.model.CaptureMode
import com.example.data.model.LiveVideoSource
import com.example.data.model.OverlaySettings
import com.example.data.model.PerformanceSettings
import com.example.data.model.StreamHealth
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.StreamSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth coordinating StreamingForegroundService, FloatingOverlayService,
 * ZroAudioEngine, Capture Preview Surface, and Compose UI. Prevents duplicate capture/streaming sessions.
 */
object ZroStreamCoordinator {

    enum class DeepLinkTarget { LIVE_CONTROLS, LIVE_CHAT, VOICE_ROOM }

    private val _telemetry = MutableStateFlow(StreamRuntimeTelemetry())
    val telemetry: StateFlow<StreamRuntimeTelemetry> = _telemetry.asStateFlow()

    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val _activeVoiceRoomName = MutableStateFlow<String?>(null)
    val activeVoiceRoomName: StateFlow<String?> = _activeVoiceRoomName.asStateFlow()

    private val _navEvents = MutableSharedFlow<DeepLinkTarget>(extraBufferCapacity = 4)
    val navEvents: SharedFlow<DeepLinkTarget> = _navEvents.asSharedFlow()

    private val _audioQuickToggleEvents = MutableSharedFlow<AudioQuickAction>(extraBufferCapacity = 8)
    val audioQuickToggleEvents: SharedFlow<AudioQuickAction> = _audioQuickToggleEvents.asSharedFlow()

    private val _streamStopCallback = MutableSharedFlow<StreamFinalStats>(extraBufferCapacity = 4)
    val streamStopCallback: SharedFlow<StreamFinalStats> = _streamStopCallback.asSharedFlow()

    enum class AudioQuickAction { TOGGLE_MIC, TOGGLE_GAME, TOGGLE_TEAM }

    data class StreamFinalStats(
        val sessionId: String?,
        val title: String,
        val visibility: String,
        val resolutionLabel: String,
        val fps: Int,
        val bitrateKbps: Int,
        val durationSec: Long,
        val droppedFrames: Long,
        val peakBitrateKbps: Int,
        val platformLabel: String = "RTMP Live",
        val rtmpUrl: String = "",
        val sceneSlotName: String = "SCENE_1",
        val wasCleanStop: Boolean = true,
        val errorMessage: String? = null
    )

    // Volatile snapshot of latest settings for Service & Overlay access
    @Volatile var currentStreamSettings: StreamSettings = StreamSettings()
    @Volatile var currentAudioSettings: AudioSettings = AudioSettings()
    @Volatile var currentOverlaySettings: OverlaySettings = OverlaySettings()
    @Volatile var currentPerfSettings: PerformanceSettings = PerformanceSettings()
    @Volatile var activeSceneSlotName: String = "SCENE_1"
    @Volatile var pendingMediaProjectionResultCode: Int = 0
    @Volatile var pendingMediaProjectionData: Intent? = null
    @Volatile var pendingCaptureMode: CaptureMode = CaptureMode.FULL_SCREEN

    // Live pre-stream capture preview surface (bound by LiveStudioScreen SurfaceView)
    val previewSurfaceRef = AtomicReference<Surface?>(null)

    private val sessionLock = AtomicBoolean(false)
    private val captureLock = AtomicBoolean(false)

    fun tryAcquireSessionLock(): Boolean = sessionLock.compareAndSet(false, true)
    fun releaseSessionLock() { sessionLock.set(false) }
    fun isSessionActive(): Boolean = sessionLock.get()

    fun tryAcquireCaptureLock(): Boolean = captureLock.compareAndSet(false, true)
    fun releaseCaptureLock() { captureLock.set(false) }
    fun isCaptureActive(): Boolean = captureLock.get()

    fun clearPendingMediaProjection() {
        pendingMediaProjectionResultCode = 0
        pendingMediaProjectionData = null
    }

    fun attachPreviewSurface(context: Context, surface: Surface?) {
        previewSurfaceRef.set(surface)
        if (isCaptureActive() && !isSessionActive()) {
            val intent = Intent(context, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_UPDATE_PREVIEW_SURFACE
            }
            runCatching { context.startService(intent) }
        }
    }

    fun updateTelemetry(transform: (StreamRuntimeTelemetry) -> StreamRuntimeTelemetry) {
        _telemetry.update(transform)
    }

    fun setOverlayVisible(visible: Boolean) {
        _overlayVisible.value = visible
    }

    fun setActiveVoiceRoomName(name: String?) {
        _activeVoiceRoomName.value = name
    }

    fun emitNavEvent(target: DeepLinkTarget) {
        _navEvents.tryEmit(target)
    }

    fun emitAudioQuickAction(action: AudioQuickAction) {
        _audioQuickToggleEvents.tryEmit(action)
    }

    fun notifyStreamFinalized(stats: StreamFinalStats) {
        _streamStopCallback.tryEmit(stats)
    }

    fun requestStopStream(context: Context) {
        val intent = Intent(context, StreamingForegroundService::class.java).apply {
            action = StreamingForegroundService.ACTION_STOP_STREAM
        }
        runCatching { context.startService(intent) }
    }

    fun requestStopCapture(context: Context) {
        val intent = Intent(context, StreamingForegroundService::class.java).apply {
            action = StreamingForegroundService.ACTION_STOP_CAPTURE
        }
        runCatching { context.startService(intent) }
    }

    fun requestPauseResumeStream(context: Context) {
        val intent = Intent(context, StreamingForegroundService::class.java).apply {
            action = StreamingForegroundService.ACTION_TOGGLE_PAUSE
        }
        runCatching { context.startService(intent) }
    }

    /**
     * Switches live stream video source seamlessly between Phone 1 full-screen capture
     * and Phone 2 mirrored gameplay feed without disconnecting YouTube / RTMP Live.
     */
    fun requestSwitchVideoSource(context: Context, source: LiveVideoSource) {
        updateTelemetry {
            it.copy(
                activeVideoSource = source,
                captureSourceLabel = when (source) {
                    LiveVideoSource.REMOTE_SECOND_PHONE -> "2nd Phone Screen (Live Source)"
                    LiveVideoSource.LOCAL_FULL_SCREEN -> "This Phone Full Screen"
                }
            )
        }
        if (isSessionActive()) {
            val intent = Intent(context, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_SWITCH_VIDEO_SOURCE
                putExtra(StreamingForegroundService.EXTRA_VIDEO_SOURCE, source.name)
            }
            runCatching { context.startService(intent) }
        }
    }

    fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val h = safe / 3600
        val m = (safe % 3600) / 60
        val s = safe % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    fun resetToIdle(error: String? = null) {
        releaseSessionLock()
        releaseCaptureLock()
        clearPendingMediaProjection()
        _telemetry.value = StreamRuntimeTelemetry(
            state = if (error != null) StreamLifecycleState.ERROR else StreamLifecycleState.IDLE,
            streamHealth = StreamHealth.OFFLINE,
            targetBitrateKbps = currentStreamSettings.bitrateKbps,
            targetFps = currentStreamSettings.fps,
            resolutionLabel = currentStreamSettings.resolution.shortLabel,
            captureActive = false,
            capturePreviewActive = false,
            captureMode = currentStreamSettings.captureMode,
            activeVideoSource = LiveVideoSource.LOCAL_FULL_SCREEN,
            captureSourceLabel = "This Phone Full Screen",
            capturedContentVisible = true,
            privacyShieldActive = false,
            errorMessage = error
        )
    }
}
