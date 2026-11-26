package com.example.streaming

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import com.example.data.model.YouTubeChatMessage
import com.example.studio.SceneSlot
import com.example.studio.StudioWorkspaceState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth coordinating StreamingForegroundService, StudioLiveCompositor,
 * FloatingOverlayService, ZroAudioEngine, Capture Preview Surface, and Compose UI.
 * Syncs live OBS canvas edits and Screen 1–4 switches instantaneously to the active RTMP stream.
 */
object ZroStreamCoordinator {

    enum class DeepLinkTarget { LIVE_CONTROLS, LIVE_CHAT, VOICE_ROOM }

    private val _telemetry = MutableStateFlow(StreamRuntimeTelemetry())
    val telemetry: StateFlow<StreamRuntimeTelemetry> = _telemetry.asStateFlow()

    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val _activeVoiceRoomName = MutableStateFlow<String?>(null)
    val activeVoiceRoomName: StateFlow<String?> = _activeVoiceRoomName.asStateFlow()

    /** Latest live captured frame from THIS phone's MediaProjection for OBS Preview & StudioLiveCompositor. */
    private val _localCapturedFrame = MutableStateFlow<Bitmap?>(null)
    val localCapturedFrame: StateFlow<Bitmap?> = _localCapturedFrame.asStateFlow()

    /** Latest live OBS studio workspace (scenes, layers, crops, opacity, text, BRB) rendered straight into the live stream. */
    private val _activeStudioWorkspace = MutableStateFlow(StudioWorkspaceState())
    val activeStudioWorkspace: StateFlow<StudioWorkspaceState> = _activeStudioWorkspace.asStateFlow()

    /** Latest YouTube live chat messages rendered inside CHAT_OVERLAY layers on the live broadcast canvas. */
    private val _liveChatMessages = MutableStateFlow<List<YouTubeChatMessage>>(emptyList())
    val liveChatMessages: StateFlow<List<YouTubeChatMessage>> = _liveChatMessages.asStateFlow()

    /** Latest real phone temperature label (e.g. "36.4°C" or "Unmeasured") shared with Notification & Floating Overlay. */
    private val _deviceTemperatureLabel = MutableStateFlow("Unmeasured")
    val deviceTemperatureLabel: StateFlow<String> = _deviceTemperatureLabel.asStateFlow()

    private val _navEvents = MutableSharedFlow<DeepLinkTarget>(extraBufferCapacity = 4)
    val navEvents: SharedFlow<DeepLinkTarget> = _navEvents.asSharedFlow()

    private val _audioQuickToggleEvents = MutableSharedFlow<AudioQuickAction>(extraBufferCapacity = 8)
    val audioQuickToggleEvents: SharedFlow<AudioQuickAction> = _audioQuickToggleEvents.asSharedFlow()

    private val _sceneSwitchEvents = MutableSharedFlow<SceneSlot>(extraBufferCapacity = 8)
    val sceneSwitchEvents: SharedFlow<SceneSlot> = _sceneSwitchEvents.asSharedFlow()

    private val _startLiveFromOverlayEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val startLiveFromOverlayEvents: SharedFlow<Unit> = _startLiveFromOverlayEvents.asSharedFlow()

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
        val sceneSlotName: String = "GAMEPLAY_LIVE",
        val wasCleanStop: Boolean = true,
        val errorMessage: String? = null
    )

    // Volatile snapshot of latest settings for Service & Overlay access
    @Volatile var currentStreamSettings: StreamSettings = StreamSettings()
    @Volatile var currentAudioSettings: AudioSettings = AudioSettings()
    @Volatile var currentOverlaySettings: OverlaySettings = OverlaySettings()
    @Volatile var currentPerfSettings: PerformanceSettings = PerformanceSettings()
    @Volatile var activeSceneSlotName: String = SceneSlot.GAMEPLAY_LIVE.name
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

    fun updateLocalCapturedFrame(bitmap: Bitmap?) {
        _localCapturedFrame.value = bitmap
    }

    fun updateStudioWorkspace(workspace: StudioWorkspaceState) {
        _activeStudioWorkspace.value = workspace
        activeSceneSlotName = workspace.activeSceneSlot.name
        _telemetry.update { prev ->
            if (prev.activeSceneName != workspace.activeScene.name) {
                prev.copy(activeSceneName = workspace.activeScene.name)
            } else {
                prev
            }
        }
    }

    fun updateLiveChatMessages(messages: List<YouTubeChatMessage>) {
        _liveChatMessages.value = messages
    }

    fun updateDeviceTemperature(tempC: Float?, label: String) {
        _deviceTemperatureLabel.value = label
        _telemetry.update { prev ->
            if (prev.deviceTempCelsius != tempC || prev.thermalLabel != label) {
                prev.copy(deviceTempCelsius = tempC, thermalLabel = label)
            } else {
                prev
            }
        }
    }

    /**
     * Switches Screen 1 / Screen 2 / Screen 3 / Screen 4 immediately — works from
     * the notification shade, floating overlay, or studio UI without opening MainActivity!
     */
    fun requestSwitchScene(context: Context?, slot: SceneSlot) {
        activeSceneSlotName = slot.name
        val current = _activeStudioWorkspace.value
        val updated = current.copy(
            activeSceneSlot = slot,
            brbActive = slot == SceneSlot.BRB_BREAK,
            selectedLayerId = null
        )
        _activeStudioWorkspace.value = updated
        _telemetry.update { it.copy(activeSceneName = updated.activeScene.name) }
        _sceneSwitchEvents.tryEmit(slot)

        if (context != null && (isSessionActive() || isCaptureActive())) {
            val intent = Intent(context, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_SWITCH_SCENE
                putExtra(StreamingForegroundService.EXTRA_SCENE_SLOT, slot.name)
            }
            runCatching { context.startService(intent) }
        }
    }

    fun requestStartLiveFromOverlay() {
        _startLiveFromOverlayEvents.tryEmit(Unit)
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
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    fun resetToIdle(error: String? = null) {
        releaseSessionLock()
        releaseCaptureLock()
        clearPendingMediaProjection()
        _localCapturedFrame.value = null
        val prevTemp = _telemetry.value.deviceTempCelsius
        val prevThermal = _telemetry.value.thermalLabel
        val prevScene = _activeStudioWorkspace.value.activeScene.name
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
            errorMessage = error,
            deviceTempCelsius = prevTemp,
            thermalLabel = prevThermal,
            activeSceneName = prevScene
        )
    }
}
