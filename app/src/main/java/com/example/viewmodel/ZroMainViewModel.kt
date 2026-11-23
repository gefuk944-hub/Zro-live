package com.example.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.ZroAudioEngine
import com.example.chat.YouTubeLiveChatClient
import com.example.data.SettingsStore
import com.example.data.ZroFirestoreRepository
import com.example.data.model.AudioMeterState
import com.example.data.model.AudioSettings
import com.example.data.model.CaptureMode
import com.example.data.model.DeviceTelemetry
import com.example.data.model.GeneralSettings
import com.example.data.model.NotificationSettings
import com.example.data.model.OverlaySettings
import com.example.data.model.PerformanceProfile
import com.example.data.model.PerformanceSettings
import com.example.data.model.SmartRecommendation
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamPreset
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.StreamSessionDoc
import com.example.data.model.StreamSettings
import com.example.data.model.UserProfileDoc
import com.example.data.model.VoiceParticipantDoc
import com.example.data.model.VoiceRoomDoc
import com.example.data.model.YouTubeChatMessage
import com.example.data.model.YouTubeChatState
import com.example.data.model.ZroAppSettings
import android.graphics.Bitmap
import android.os.Environment
import com.example.data.model.NoiseCancellationLevel
import com.example.dualphone.DualPhoneEngine
import com.example.dualphone.DualPhoneProtocol
import com.example.dualphone.DualRemoteCommand
import com.example.overlay.FloatingOverlayService
import com.example.streaming.StreamingForegroundService
import com.example.streaming.ZroStreamCoordinator
import com.example.studio.CameraSourceMode
import com.example.studio.CanvasGridMode
import com.example.studio.CropAspectPreset
import com.example.studio.CropEdge
import com.example.studio.DualConnectionMethod
import com.example.studio.DualLinkState
import com.example.studio.DualPhoneRole
import com.example.studio.DualPhoneTelemetry
import com.example.studio.CustomGraphicPreset
import com.example.studio.FacecamShape
import com.example.studio.GraphicPresetId
import com.example.studio.LayerAnimationType
import com.example.studio.LayerGraphicAdjustment
import com.example.studio.LayerSourceType
import com.example.studio.RemoteControlTileId
import com.example.studio.ResizeCorner
import com.example.studio.SceneLayer
import com.example.studio.SceneSlot
import com.example.studio.SceneTransitionType
import com.example.studio.StudioWorkspaceReducer
import com.example.studio.StudioWorkspaceState
import com.example.telemetry.DeviceTelemetryMonitor
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ZroMainViewModel(
    private val appContext: Context,
    private val repository: ZroFirestoreRepository?,
    private val settingsStore: SettingsStore,
    private val telemetryMonitor: DeviceTelemetryMonitor,
    private val chatClient: YouTubeLiveChatClient,
    val currentUserId: String?,
    val userDisplayName: String,
    val userAvatarUrl: String?
) : ViewModel() {

    companion object {
        private const val TAG = "ZroMainViewModel"
        private const val STOP_TIMEOUT_MS = 5000L
    }

    val isGuest: Boolean = currentUserId.isNullOrBlank() || repository == null

    val settings: StateFlow<ZroAppSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ZroAppSettings())

    // Zero Firestore listeners attached in Guest Mode!
    val userProfile: StateFlow<UserProfileDoc?> = if (isGuest) {
        MutableStateFlow(null)
    } else {
        repository!!.observeUserProfile(currentUserId!!)
            .catch { error ->
                val fsError = ZroFirestoreRepository.unwrapFirestoreException(error)
                if (fsError == null && error is CancellationException) throw error
                Log.w(TAG, "Profile observe warning", fsError ?: error)
                emit(null)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)
    }

    // Guest users store & view local broadcast history in DataStore; signed-in users sync via Firestore
    val recentSessions: StateFlow<List<StreamSessionDoc>> = if (isGuest) {
        settingsStore.localSessionsFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
    } else {
        repository!!.observeStreamSessions(currentUserId!!)
            .catch { error ->
                val fsError = ZroFirestoreRepository.unwrapFirestoreException(error)
                if (fsError == null && error is CancellationException) throw error
                Log.w(TAG, "Sessions observe warning", fsError ?: error)
                emit(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
    }

    val publicVoiceRooms: StateFlow<List<VoiceRoomDoc>> = if (isGuest) {
        MutableStateFlow(emptyList())
    } else {
        repository!!.observePublicVoiceRooms(currentUserId!!)
            .catch { error ->
                val fsError = ZroFirestoreRepository.unwrapFirestoreException(error)
                if (fsError == null && error is CancellationException) throw error
                Log.w(TAG, "Public rooms observe warning", fsError ?: error)
                emit(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
    }

    private val _activeRoomId = MutableStateFlow<String?>(null)
    val activeRoomId: StateFlow<String?> = _activeRoomId.asStateFlow()

    private val _localActiveRoom = MutableStateFlow<VoiceRoomDoc?>(null)
    private val _localParticipants = MutableStateFlow<List<VoiceParticipantDoc>>(emptyList())

    val activeRoom: StateFlow<VoiceRoomDoc?> = if (isGuest) {
        _localActiveRoom.asStateFlow()
    } else {
        _activeRoomId
            .flatMapLatest { rid ->
                if (rid.isNullOrBlank()) flowOf(null) else repository!!.observeVoiceRoom(rid)
            }
            .catch { error ->
                val fsError = ZroFirestoreRepository.unwrapFirestoreException(error)
                if (fsError == null && error is CancellationException) throw error
                Log.w(TAG, "Active room observe warning", fsError ?: error)
                emit(null)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)
    }

    val roomParticipants: StateFlow<List<VoiceParticipantDoc>> = if (isGuest) {
        _localParticipants.asStateFlow()
    } else {
        _activeRoomId
            .flatMapLatest { rid ->
                if (rid.isNullOrBlank()) flowOf(emptyList()) else repository!!.observeRoomParticipants(rid)
            }
            .catch { error ->
                val fsError = ZroFirestoreRepository.unwrapFirestoreException(error)
                if (fsError == null && error is CancellationException) throw error
                Log.w(TAG, "Participants observe warning", fsError ?: error)
                emit(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
    }

    private val _selfStreamConsent = MutableStateFlow(true)
    val selfStreamConsent: StateFlow<Boolean> = _selfStreamConsent.asStateFlow()

    private val _teammateLocalMuted = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val teammateLocalMuted: StateFlow<Map<String, Boolean>> = _teammateLocalMuted.asStateFlow()

    private val _teammateLocalVolumes = MutableStateFlow<Map<String, Float>>(emptyMap())
    val teammateLocalVolumes: StateFlow<Map<String, Float>> = _teammateLocalVolumes.asStateFlow()

    private val _voiceActionLoading = MutableStateFlow(false)
    val voiceActionLoading: StateFlow<Boolean> = _voiceActionLoading.asStateFlow()

    private val _deviceTelemetry = MutableStateFlow(
        DeviceTelemetry(
            supportsAppOnlyCapture = DeviceTelemetryMonitor.supportsSingleAppCapture()
        )
    )
    val deviceTelemetry: StateFlow<DeviceTelemetry> = _deviceTelemetry.asStateFlow()

    private val _smartRecommendation = MutableStateFlow(
        telemetryMonitor.computeSmartRecommendation(_deviceTelemetry.value)
    )
    val smartRecommendation: StateFlow<SmartRecommendation> = _smartRecommendation.asStateFlow()

    val streamTelemetry: StateFlow<StreamRuntimeTelemetry> = ZroStreamCoordinator.telemetry
    val audioMeters: StateFlow<AudioMeterState> = ZroAudioEngine.meterState
    val overlayVisible: StateFlow<Boolean> = ZroStreamCoordinator.overlayVisible

    // --- OBS 4-SCENE STUDIO & DUAL PHONE ENGINE STATE ---
    private val dualEngine: DualPhoneEngine = DualPhoneEngine.shared
    val dualTelemetry: StateFlow<DualPhoneTelemetry> = dualEngine.telemetry
    val mirroredFrame: StateFlow<Bitmap?> = dualEngine.mirroredFrame

    private val _studioState = MutableStateFlow(StudioWorkspaceState())
    val studioState: StateFlow<StudioWorkspaceState> = _studioState.asStateFlow()

    private val undoStack = ArrayDeque<StudioWorkspaceState>()
    private val redoStack = ArrayDeque<StudioWorkspaceState>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    private var dragSnapshotPushed = false
    private var lastAutoRestoredDeviceKey: String? = null

    private val _localScreenshotCount = MutableStateFlow(0)
    val localScreenshotCount: StateFlow<Int> = _localScreenshotCount.asStateFlow()

    private val _chatState = MutableStateFlow<YouTubeChatState>(YouTubeChatState.Disconnected)
    val chatState: StateFlow<YouTubeChatState> = _chatState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _isUiForeground = MutableStateFlow(true)
    private var chatPollJob: Job? = null

    init {
        // Hydrate persisted 4-Scene OBS Studio workspace once at startup (never clobber active drags/selections)
        viewModelScope.launch {
            val loaded = settingsStore.studioWorkspaceFlow.first()
            _studioState.value = loaded
        }

        // Auto-restore saved layout when the authorized gameplay phone connects to Phone 2 Studio
        viewModelScope.launch {
            dualEngine.telemetry.collect { dt ->
                if (dt.role == DualPhoneRole.PHONE2_STUDIO &&
                    dt.linkState == DualLinkState.CONNECTED &&
                    dt.connectedDeviceName.isNotBlank()
                ) {
                    val key = "${dt.connectedDeviceName}@${dt.peerIp}"
                    if (lastAutoRestoredDeviceKey != key) {
                        lastAutoRestoredDeviceKey = key
                        val cur = _studioState.value
                        if (cur.autoRestoreLastLayout) {
                            val saved = settingsStore.loadAuthorizedDeviceLayout()
                            if (saved != null) {
                                val updated = saved.copy(
                                    lastAuthorizedDeviceName = dt.connectedDeviceName,
                                    lastAuthorizedDeviceIp = dt.peerIp
                                )
                                _studioState.value = updated
                                showMessage("Restored saved Studio layout for ${dt.connectedDeviceName}")
                            } else {
                                commitStudioState(
                                    cur.copy(
                                        lastAuthorizedDeviceName = dt.connectedDeviceName,
                                        lastAuthorizedDeviceIp = dt.peerIp
                                    ),
                                    recordUndo = false
                                )
                            }
                        }
                    }
                } else if (dt.linkState == DualLinkState.DISCONNECTED) {
                    lastAutoRestoredDeviceKey = null
                }
            }
        }

        // Execute incoming Dual-Phone Remote Control commands received over LAN/Hotspot
        viewModelScope.launch {
            dualEngine.incomingCommands.collect { cmd ->
                executeRemoteCommandLocally(cmd, fromRemotePeer = true)
            }
        }
        if (!isGuest) {
            viewModelScope.launch {
                repository!!.ensureUserProfile(userDisplayName, userAvatarUrl)
            }
        }

        // Sync settings to coordinator & ZroAudioEngine
        viewModelScope.launch {
            settings.collectLatest { cfg ->
                ZroStreamCoordinator.currentStreamSettings = cfg.stream
                ZroStreamCoordinator.currentAudioSettings = cfg.audio
                ZroStreamCoordinator.currentOverlaySettings = cfg.overlay
                ZroStreamCoordinator.currentPerfSettings = cfg.performance
                ZroAudioEngine.updateSettings(appContext, cfg.audio)
                ZroAudioEngine.applyPerformanceSettings(cfg.performance)
                _smartRecommendation.value = telemetryMonitor.computeSmartRecommendation(
                    t = _deviceTelemetry.value,
                    profile = cfg.performance.profile,
                    lowRamMode = cfg.performance.lowRamMode
                )
            }
        }

        // React to quick audio toggle intents from Persistent Notification & Floating Overlay
        viewModelScope.launch {
            ZroStreamCoordinator.audioQuickToggleEvents.collect { action ->
                val cur = settings.value.audio
                val updated = when (action) {
                    ZroStreamCoordinator.AudioQuickAction.TOGGLE_MIC -> cur.copy(micEnabled = !cur.micEnabled)
                    ZroStreamCoordinator.AudioQuickAction.TOGGLE_GAME -> cur.copy(gameAudioEnabled = !cur.gameAudioEnabled)
                    ZroStreamCoordinator.AudioQuickAction.TOGGLE_TEAM -> cur.copy(teamVoiceEnabled = !cur.teamVoiceEnabled)
                }
                updateAudioSettings(updated)
            }
        }

        // Save finalized stream session stats (Firestore if signed in, local DataStore if Guest)
        viewModelScope.launch {
            ZroStreamCoordinator.streamStopCallback.collect { stats ->
                val sid = stats.sessionId
                if (!isGuest && !sid.isNullOrBlank()) {
                    repository!!.finalizeStreamSession(
                        sessionId = sid,
                        durationSec = stats.durationSec,
                        droppedFrames = stats.droppedFrames,
                        peakBitrateKbps = stats.peakBitrateKbps
                    )
                } else if (stats.durationSec > 0L) {
                    settingsStore.appendLocalSession(
                        StreamSessionDoc(
                            id = sid ?: "local_${System.currentTimeMillis()}",
                            userId = "guest",
                            title = stats.title,
                            visibility = stats.visibility,
                            resolutionLabel = stats.resolutionLabel,
                            fps = stats.fps.toLong(),
                            bitrateKbps = stats.bitrateKbps.toLong(),
                            durationSec = stats.durationSec,
                            droppedFrames = stats.droppedFrames,
                            peakBitrateKbps = stats.peakBitrateKbps.toLong(),
                            status = "ENDED"
                        )
                    )
                }
            }
        }

        // Sync voice peers only when signed in
        if (!isGuest) {
            viewModelScope.launch {
                roomParticipants.collectLatest { list ->
                    syncAudioEnginePeers(list)
                }
            }
        }

        // Foreground-only, profile-throttled hardware telemetry loop.
        // Runs MediaCodecList / ActivityManager / PowerManager reads off the main thread so startup never stalls!
        viewModelScope.launch {
            _isUiForeground.collectLatest { inForeground ->
                if (!inForeground) return@collectLatest
                while (isActive) {
                    val perf = settings.value.performance
                    if (perf.temperatureMonitoring) {
                        val snap = withContext(Dispatchers.IO) {
                            runCatching { telemetryMonitor.snapshot() }.getOrDefault(_deviceTelemetry.value)
                        }
                        _deviceTelemetry.value = snap
                        _smartRecommendation.value = telemetryMonitor.computeSmartRecommendation(
                            t = snap,
                            profile = perf.profile,
                            lowRamMode = perf.lowRamMode
                        )
                    }
                    // Longer interval if already streaming (StreamingForegroundService monitors thermals directly)
                    val baseDelay = perf.profile.telemetryIntervalMs
                    val effectiveDelay = if (ZroStreamCoordinator.isSessionActive()) baseDelay * 2 else baseDelay
                    delay(effectiveDelay)
                }
            }
        }
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        _isUiForeground.value = inForeground
        ZroAudioEngine.setUiForeground(inForeground)
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun showMessage(msg: String) {
        _snackbarMessage.value = msg
    }

    // --- SETTINGS UPDATES ---

    fun updateStreamSettings(updated: StreamSettings) {
        ZroStreamCoordinator.currentStreamSettings = updated
        viewModelScope.launch { settingsStore.saveStreamSettings(updated) }
    }

    /**
     * Updates capture mode while enforcing the privacy guarantee:
     * FULL_SCREEN can never be silently activated unless explicitly enabled by the user.
     */
    fun selectCaptureMode(mode: CaptureMode, explicitFullScreenConfirmed: Boolean = false) {
        val current = settings.value.stream
        if (mode == CaptureMode.FULL_SCREEN) {
            if (!explicitFullScreenConfirmed && !current.fullScreenExplicitlyEnabled) {
                showMessage("Full Screen capture is disabled by default for privacy. Confirm to opt in.")
                return
            }
            updateStreamSettings(current.copy(captureMode = CaptureMode.FULL_SCREEN, fullScreenExplicitlyEnabled = true))
        } else {
            updateStreamSettings(current.copy(captureMode = CaptureMode.GAME_APP_ONLY))
        }
    }

    fun setFullScreenOptInEnabled(enabled: Boolean) {
        val current = settings.value.stream
        val nextMode = if (!enabled && current.captureMode == CaptureMode.FULL_SCREEN) {
            CaptureMode.GAME_APP_ONLY
        } else {
            current.captureMode
        }
        updateStreamSettings(
            current.copy(
                fullScreenExplicitlyEnabled = enabled,
                captureMode = nextMode
            )
        )
    }

    fun selectPerformanceProfile(profile: PerformanceProfile) {
        val cur = settings.value.performance
        val updated = cur.copy(
            profile = profile,
            reduceAnimationsDuringGameplay = profile.reduceUiAnimations || cur.lowRamMode
        )
        updatePerformanceSettings(updated)
        showMessage("Performance Profile: ${profile.label} active.")
    }

    fun applyPreset(preset: StreamPreset) {
        val current = settings.value.stream
        val dev = _deviceTelemetry.value
        if (preset == StreamPreset.FULL_HD_SMOOTH && !dev.supports1080p60) {
            showMessage("1080p 60 FPS is not reported as hardware-supported on this device. Applied 1080p 30 FPS instead.")
            updateStreamSettings(
                current.copy(
                    preset = StreamPreset.FULL_HD,
                    resolution = StreamPreset.FULL_HD.resolution,
                    fps = 30,
                    bitrateKbps = StreamPreset.FULL_HD.recommendedBitrateKbps
                )
            )
            return
        }
        updateStreamSettings(
            current.copy(
                preset = preset,
                resolution = preset.resolution,
                fps = preset.fps,
                bitrateKbps = preset.recommendedBitrateKbps
            )
        )
    }

    fun applySmartRecommendation() {
        val rec = _smartRecommendation.value
        val cur = settings.value.stream
        updateStreamSettings(
            cur.copy(
                preset = rec.preset,
                resolution = rec.resolution,
                fps = rec.fps,
                bitrateKbps = rec.bitrateKbps
            )
        )
        showMessage("Applied Smart Settings: ${rec.resolution.shortLabel} • ${rec.fps} FPS • ${rec.bitrateKbps / 1000f} Mbps")
    }

    fun updateAudioSettings(updated: AudioSettings) {
        ZroStreamCoordinator.currentAudioSettings = updated
        ZroAudioEngine.updateSettings(appContext, updated)
        viewModelScope.launch {
            settingsStore.saveAudioSettings(updated)
            val roomId = _activeRoomId.value
            if (!isGuest && !roomId.isNullOrBlank()) {
                repository!!.updateParticipantState(roomId = roomId, muted = !updated.micEnabled)
            }
        }
    }

    fun toggleMicTest(): Boolean {
        return if (audioMeters.value.isTestingMic) {
            ZroAudioEngine.stopMicTest()
            false
        } else {
            val started = ZroAudioEngine.startMicTest(appContext)
            if (!started) {
                showMessage("Grant Microphone permission to test your audio pipeline.")
            }
            started
        }
    }

    fun updateOverlaySettings(updated: OverlaySettings) {
        ZroStreamCoordinator.currentOverlaySettings = updated
        viewModelScope.launch { settingsStore.saveOverlaySettings(updated) }
        if (updated.enabled && Settings.canDrawOverlays(appContext)) {
            startOrRefreshOverlay()
        } else if (!updated.enabled) {
            stopOverlay()
        }
    }

    fun startOrRefreshOverlay() {
        if (!Settings.canDrawOverlays(appContext)) {
            showMessage("Grant 'Display over other apps' permission to show the floating gaming overlay.")
            return
        }
        val intent = Intent(appContext, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_SHOW
        }
        runCatching { appContext.startService(intent) }
    }

    fun stopOverlay() {
        val intent = Intent(appContext, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_HIDE
        }
        runCatching { appContext.startService(intent) }
    }

    fun updateNotificationSettings(updated: NotificationSettings) {
        viewModelScope.launch { settingsStore.saveNotificationSettings(updated) }
    }

    fun updatePerformanceSettings(updated: PerformanceSettings) {
        ZroStreamCoordinator.currentPerfSettings = updated
        ZroAudioEngine.applyPerformanceSettings(updated)
        viewModelScope.launch { settingsStore.savePerformanceSettings(updated) }
    }

    fun updateGeneralSettings(updated: GeneralSettings) {
        viewModelScope.launch { settingsStore.saveGeneralSettings(updated) }
    }

    // --- CAPTURE PREVIEW & STREAMING CONTROL (WORKS IN GUEST & SIGNED-IN MODES) ---

    fun startCapturePreviewWithMediaProjection(resultCode: Int, data: Intent, mode: CaptureMode) {
        if (ZroStreamCoordinator.isSessionActive()) {
            showMessage("End the active stream before changing the captured game.")
            return
        }
        ZroStreamCoordinator.pendingMediaProjectionResultCode = resultCode
        ZroStreamCoordinator.pendingMediaProjectionData = data
        ZroStreamCoordinator.pendingCaptureMode = mode

        val svcIntent = Intent(appContext, StreamingForegroundService::class.java).apply {
            action = StreamingForegroundService.ACTION_START_CAPTURE_PREVIEW
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(svcIntent)
        } else {
            appContext.startService(svcIntent)
        }
        showMessage(
            if (mode == CaptureMode.GAME_APP_ONLY) {
                "Game capture preview active. Verify your game window, then tap Go Live."
            } else {
                "Full Screen capture preview active (Opt-In)."
            }
        )
    }

    fun stopCapture() {
        ZroStreamCoordinator.requestStopCapture(appContext)
        showMessage("Capture stopped and resources released.")
    }

    fun startBroadcastFromExistingCapture() {
        if (!ZroStreamCoordinator.isCaptureActive()) {
            showMessage("Select a game to capture first.")
            return
        }
        launchBroadcastInternal(resultCode = null, data = null, mode = settings.value.stream.captureMode)
    }

    fun launchBroadcastWithMediaProjection(resultCode: Int, data: Intent, mode: CaptureMode) {
        launchBroadcastInternal(resultCode = resultCode, data = data, mode = mode)
    }

    private fun launchBroadcastInternal(resultCode: Int?, data: Intent?, mode: CaptureMode) {
        if (ZroStreamCoordinator.isSessionActive()) {
            showMessage("A livestream session is already active.")
            return
        }
        val streamCfg = settings.value.stream
        if (streamCfg.streamKey.isBlank()) {
            showMessage("Enter your YouTube Stream Key before going live.")
            return
        }
        if (resultCode != null && data != null) {
            ZroStreamCoordinator.pendingMediaProjectionResultCode = resultCode
            ZroStreamCoordinator.pendingMediaProjectionData = data
            ZroStreamCoordinator.pendingCaptureMode = mode
        }

        viewModelScope.launch {
            val sessionId = if (!isGuest) {
                repository!!.createStreamSession(
                    title = streamCfg.streamTitle,
                    description = streamCfg.streamDescription,
                    visibility = streamCfg.visibility.name,
                    resolutionLabel = streamCfg.resolution.shortLabel,
                    fps = streamCfg.fps,
                    bitrateKbps = streamCfg.bitrateKbps
                ).getOrNull()
            } else {
                "local_${System.currentTimeMillis()}"
            }

            val svcIntent = Intent(appContext, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_START_STREAM
                putExtra(StreamingForegroundService.EXTRA_SESSION_ID, sessionId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(svcIntent)
            } else {
                appContext.startService(svcIntent)
            }

            if (settings.value.overlay.enabled && Settings.canDrawOverlays(appContext)) {
                startOrRefreshOverlay()
            }
        }
    }

    fun togglePauseStream() {
        ZroStreamCoordinator.requestPauseResumeStream(appContext)
    }

    fun endStream() {
        ZroStreamCoordinator.requestStopStream(appContext)
    }

    // --- TEAM VOICE ROOM (WORKS DIRECTLY WITHOUT LOGIN) ---

    fun createVoiceRoom(roomName: String, isPrivate: Boolean, maxParticipants: Int) {
        viewModelScope.launch {
            _voiceActionLoading.value = true
            val udpEp = ZroAudioEngine.startVoiceRoomAudio(appContext)
            if (isGuest) {
                val code = "ZRO-${(1000..9999).random()}"
                val selfId = currentUserId?.takeIf { it.isNotBlank() } ?: "local_streamer"
                val room = VoiceRoomDoc(
                    id = code,
                    hostUid = selfId,
                    roomCode = code,
                    name = roomName.trim().ifBlank { "Squad Voice Room" },
                    visibility = if (isPrivate) "PRIVATE" else "PUBLIC",
                    active = true,
                    maxParticipants = maxParticipants.coerceIn(2, 8).toLong(),
                    memberUids = listOf(selfId)
                )
                val selfParticipant = VoiceParticipantDoc(
                    userId = selfId,
                    displayName = userDisplayName,
                    avatarUrl = userAvatarUrl,
                    muted = !settings.value.audio.micEnabled,
                    speaking = false,
                    audioLevel = 0L,
                    streamConsent = _selfStreamConsent.value,
                    connectionQuality = "Local",
                    udpEndpoint = udpEp
                )
                _localActiveRoom.value = room
                _localParticipants.value = listOf(selfParticipant)
                _voiceActionLoading.value = false
                attachActiveVoiceRoom(room)
                showMessage("Voice Room '${room.name}' ready • Code: ${room.roomCode}")
                return@launch
            }
            val res = repository!!.createVoiceRoom(
                roomName = roomName,
                isPrivate = isPrivate,
                maxParticipants = maxParticipants,
                displayName = userDisplayName,
                avatarUrl = userAvatarUrl,
                streamConsent = _selfStreamConsent.value,
                udpEndpoint = udpEp
            )
            _voiceActionLoading.value = false
            res.onSuccess { room ->
                attachActiveVoiceRoom(room)
                showMessage("Voice Room '${room.name}' ready • Code: ${room.roomCode}")
            }.onFailure { err ->
                ZroAudioEngine.stopVoiceRoomAudio()
                showMessage(err.localizedMessage ?: "Failed to create voice room.")
            }
        }
    }

    fun joinVoiceRoom(roomCode: String) {
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.isBlank()) {
            showMessage("Enter a room code to join.")
            return
        }
        viewModelScope.launch {
            _voiceActionLoading.value = true
            val udpEp = ZroAudioEngine.startVoiceRoomAudio(appContext)
            if (isGuest) {
                val selfId = currentUserId?.takeIf { it.isNotBlank() } ?: "local_streamer"
                val room = VoiceRoomDoc(
                    id = cleanCode,
                    hostUid = selfId,
                    roomCode = cleanCode,
                    name = "Room $cleanCode",
                    visibility = "PUBLIC",
                    active = true,
                    maxParticipants = 6L,
                    memberUids = listOf(selfId)
                )
                val selfParticipant = VoiceParticipantDoc(
                    userId = selfId,
                    displayName = userDisplayName,
                    avatarUrl = userAvatarUrl,
                    muted = !settings.value.audio.micEnabled,
                    speaking = false,
                    audioLevel = 0L,
                    streamConsent = _selfStreamConsent.value,
                    connectionQuality = "Local",
                    udpEndpoint = udpEp
                )
                _localActiveRoom.value = room
                _localParticipants.value = listOf(selfParticipant)
                _voiceActionLoading.value = false
                attachActiveVoiceRoom(room)
                showMessage("Connected to Voice Room '$cleanCode'")
                return@launch
            }
            val res = repository!!.joinVoiceRoom(
                rawRoomCode = cleanCode,
                displayName = userDisplayName,
                avatarUrl = userAvatarUrl,
                streamConsent = _selfStreamConsent.value,
                udpEndpoint = udpEp
            )
            _voiceActionLoading.value = false
            res.onSuccess { room ->
                attachActiveVoiceRoom(room)
                showMessage("Joined Voice Room '${room.name}'")
            }.onFailure { err ->
                ZroAudioEngine.stopVoiceRoomAudio()
                showMessage(err.localizedMessage ?: "Could not join room '$cleanCode'.")
            }
        }
    }

    private fun attachActiveVoiceRoom(room: VoiceRoomDoc) {
        _activeRoomId.value = room.roomCode
        ZroStreamCoordinator.setActiveVoiceRoomName(room.name)
        ZroAudioEngine.setSpeakingObserver { speaking, level ->
            val rid = _activeRoomId.value ?: return@setSpeakingObserver
            if (isGuest) {
                val selfId = currentUserId?.takeIf { it.isNotBlank() } ?: "local_streamer"
                _localParticipants.update { list ->
                    list.map { p ->
                        if (p.userId == selfId) {
                            p.copy(
                                muted = !settings.value.audio.micEnabled,
                                speaking = speaking,
                                audioLevel = level.toLong(),
                                streamConsent = _selfStreamConsent.value
                            )
                        } else p
                    }
                }
            } else {
                viewModelScope.launch {
                    repository!!.updateParticipantState(
                        roomId = rid,
                        speaking = speaking,
                        audioLevel = level
                    )
                }
            }
        }
        if (ZroAudioEngine.hasMicPermission(appContext)) {
            val svc = Intent(appContext, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_START_VOICE_FG
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { appContext.startForegroundService(svc) }
            } else {
                runCatching { appContext.startService(svc) }
            }
        }
    }

    fun leaveVoiceRoom() {
        val rid = _activeRoomId.value ?: return
        viewModelScope.launch {
            _voiceActionLoading.value = true
            ZroAudioEngine.setSpeakingObserver(null)
            ZroAudioEngine.stopVoiceRoomAudio()
            if (!isGuest) {
                repository!!.leaveVoiceRoom(rid)
            }
            _localActiveRoom.value = null
            _localParticipants.value = emptyList()
            _activeRoomId.value = null
            ZroStreamCoordinator.setActiveVoiceRoomName(null)
            val svc = Intent(appContext, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_STOP_VOICE_FG
            }
            runCatching { appContext.startService(svc) }
            _voiceActionLoading.value = false
            showMessage("Left voice room.")
        }
    }

    fun toggleSelfStreamConsent(enabled: Boolean) {
        _selfStreamConsent.value = enabled
        val rid = _activeRoomId.value ?: return
        if (isGuest) {
            val selfId = currentUserId?.takeIf { it.isNotBlank() } ?: "local_streamer"
            _localParticipants.update { list ->
                list.map { p -> if (p.userId == selfId) p.copy(streamConsent = enabled) else p }
            }
        } else {
            viewModelScope.launch {
                repository!!.updateParticipantState(roomId = rid, streamConsent = enabled)
            }
        }
    }

    fun toggleTeammateMuted(uid: String) {
        _teammateLocalMuted.update { map ->
            val current = map[uid] ?: false
            map + (uid to !current)
        }
        syncAudioEnginePeers(roomParticipants.value)
    }

    fun setTeammateVolume(uid: String, volume: Float) {
        _teammateLocalVolumes.update { it + (uid to volume.coerceIn(0f, 1.5f)) }
        syncAudioEnginePeers(roomParticipants.value)
    }

    private fun syncAudioEnginePeers(participants: List<VoiceParticipantDoc>) {
        val mutedMap = _teammateLocalMuted.value
        val volMap = _teammateLocalVolumes.value
        val peers = participants
            .filter { it.userId != currentUserId }
            .map { p ->
                val parts = p.udpEndpoint?.split(":")
                val ip = parts?.getOrNull(0)
                val port = parts?.getOrNull(1)?.toIntOrNull() ?: 0
                ZroAudioEngine.TeammatePeerConfig(
                    uid = p.userId,
                    ip = ip,
                    port = port,
                    locallyMuted = mutedMap[p.userId] ?: false,
                    volume = volMap[p.userId] ?: 1.0f,
                    streamConsent = p.streamConsent
                )
            }
        ZroAudioEngine.updateTeammatePeers(peers)
    }

    // --- YOUTUBE LIVE CHAT (WORKS IN GUEST & SIGNED-IN MODES) ---

    fun connectYouTubeLiveChat() {
        val streamCfg = settings.value.stream
        if (!streamCfg.chatEnabled) {
            _chatState.value = YouTubeChatState.Disconnected
            return
        }
        chatPollJob?.cancel()
        _chatState.value = YouTubeChatState.Connecting

        chatPollJob = viewModelScope.launch {
            val detailsRes = chatClient.resolveLiveBroadcastDetails(
                videoIdRaw = streamCfg.youtubeVideoId,
                apiKeyOrToken = streamCfg.youtubeApiKeyOrToken
            )
            if (detailsRes.isFailure) {
                val msg = detailsRes.exceptionOrNull()?.localizedMessage
                    ?: "Could not connect to YouTube Live Chat API."
                _chatState.value = YouTubeChatState.Error(msg)
                return@launch
            }

            val details = detailsRes.getOrThrow()
            ZroStreamCoordinator.updateTelemetry { it.copy(concurrentViewers = details.concurrentViewers) }

            var pageToken: String? = null
            val accumulated = mutableListOf<YouTubeChatMessage>()
            val seenIds = mutableSetOf<String>()

            while (isActive) {
                val pollRes = chatClient.pollMessages(
                    liveChatId = details.liveChatId,
                    apiKeyOrToken = streamCfg.youtubeApiKeyOrToken,
                    pageToken = pageToken
                )
                if (pollRes.isFailure) {
                    _chatState.value = YouTubeChatState.Error(
                        pollRes.exceptionOrNull()?.localizedMessage ?: "Live chat polling interrupted."
                    )
                    return@launch
                }
                val batch = pollRes.getOrThrow()
                pageToken = batch.nextPageToken
                for (idx in accumulated.indices) {
                    if (accumulated[idx].isHighlightedNew) {
                        accumulated[idx] = accumulated[idx].copy(isHighlightedNew = false)
                    }
                }
                for (msg in batch.messages) {
                    if (seenIds.add(msg.id)) {
                        accumulated.add(msg)
                    }
                }
                val maxMsgs = if (settings.value.performance.lowRamMode) 50 else 120
                if (accumulated.size > maxMsgs) {
                    val drop = accumulated.size - maxMsgs
                    repeat(drop) { accumulated.removeAt(0) }
                }

                _chatState.value = YouTubeChatState.Connected(
                    liveChatId = details.liveChatId,
                    messages = accumulated.toList(),
                    concurrentViewers = details.concurrentViewers
                )
                // Respect Battery Saver / Performance profile by avoiding rapid polling
                val minDelay = when (settings.value.performance.profile) {
                    PerformanceProfile.BATTERY_SAVER -> 8000L
                    PerformanceProfile.PERFORMANCE -> 6000L
                    PerformanceProfile.BALANCED -> 4000L
                }
                delay(maxOf(batch.pollingIntervalMs, minDelay))
            }
        }
    }

    fun disconnectYouTubeLiveChat() {
        chatPollJob?.cancel()
        chatPollJob = null
        _chatState.value = YouTubeChatState.Disconnected
    }

    fun sendYouTubeChatMessage(text: String) {
        val current = _chatState.value
        if (current !is YouTubeChatState.Connected) {
            showMessage("Connect to an active YouTube Live Chat first.")
            return
        }
        val token = settings.value.stream.youtubeApiKeyOrToken
        viewModelScope.launch {
            val res = chatClient.sendMessage(current.liveChatId, token, text)
            res.onFailure { err ->
                showMessage(err.localizedMessage ?: "Failed to send chat message.")
            }.onSuccess {
                showMessage("Message sent to YouTube Live Chat.")
            }
        }
    }

    // --- OBS-STYLE 4-SCENE STUDIO, 4-CORNER CANVAS & INDEPENDENT LAYER MANAGEMENT ---

    private fun pushUndoSnapshot(prev: StudioWorkspaceState) {
        if (undoStack.size >= 25) undoStack.removeFirst()
        undoStack.addLast(prev)
        redoStack.clear()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = false
    }

    private fun commitStudioState(
        newState: StudioWorkspaceState,
        persist: Boolean = true,
        recordUndo: Boolean = true
    ) {
        val prev = _studioState.value
        if (recordUndo && newState != prev) {
            pushUndoSnapshot(prev)
        }
        _studioState.value = newState
        if (persist) {
            viewModelScope.launch { settingsStore.saveStudioWorkspace(newState) }
        }
    }

    fun undoStudio() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_studioState.value)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _studioState.value = prev
        viewModelScope.launch { settingsStore.saveStudioWorkspace(prev) }
        showMessage("Undid last Studio change")
    }

    fun redoStudio() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_studioState.value)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _studioState.value = next
        viewModelScope.launch { settingsStore.saveStudioWorkspace(next) }
        showMessage("Redid Studio change")
    }

    fun saveScreenLayoutExplicitly() {
        val dt = dualTelemetry.value
        val stamped = _studioState.value.copy(
            savedLayoutTimestampMs = System.currentTimeMillis(),
            lastAuthorizedDeviceName = dt.connectedDeviceName.ifBlank { _studioState.value.lastAuthorizedDeviceName },
            lastAuthorizedDeviceIp = dt.peerIp.ifBlank { _studioState.value.lastAuthorizedDeviceIp }
        )
        _studioState.value = stamped
        viewModelScope.launch {
            settingsStore.saveAuthorizedDeviceLayout(stamped)
            settingsStore.savePreviousStudioBackup(stamped)
            showMessage("Screen layout & all 4 scenes saved. Will auto-restore on restart/reconnect.")
        }
    }

    fun setAutoRestoreLastLayout(enabled: Boolean) {
        commitStudioState(_studioState.value.copy(autoRestoreLastLayout = enabled), recordUndo = false)
    }

    fun resetEntireLayoutConfirmed() {
        viewModelScope.launch { settingsStore.savePreviousStudioBackup(_studioState.value) }
        val fresh = StudioWorkspaceState(
            autoRestoreLastLayout = _studioState.value.autoRestoreLastLayout,
            remoteTileOrder = _studioState.value.remoteTileOrder,
            remoteTileScale = _studioState.value.remoteTileScale
        )
        commitStudioState(fresh)
        viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(fresh) }
        showMessage("Layout reset to default Studio configuration.")
    }

    fun triggerBrbPrivacy() {
        val next = StudioWorkspaceReducer.triggerBrbPrivacy(_studioState.value)
        commitStudioState(next)
        if (dualTelemetry.value.role == DualPhoneRole.PHONE2_STUDIO &&
            dualTelemetry.value.linkState == DualLinkState.CONNECTED
        ) {
            dualEngine.sendRemoteCommand(DualRemoteCommand(DualPhoneProtocol.CMD_TRIGGER_BRB))
        }
        showMessage("BRB / Privacy Active — Gameplay & Facecam hidden")
    }

    fun resumeFromBrbPrivacy() {
        val next = StudioWorkspaceReducer.resumeFromBrbPrivacy(_studioState.value)
        commitStudioState(next)
        if (dualTelemetry.value.role == DualPhoneRole.PHONE2_STUDIO &&
            dualTelemetry.value.linkState == DualLinkState.CONNECTED
        ) {
            dualEngine.sendRemoteCommand(DualRemoteCommand(DualPhoneProtocol.CMD_RESUME_FROM_BRB))
        }
        showMessage("Resumed ${next.activeScene.name} scene")
    }

    fun setCameraEnabled(enabled: Boolean) {
        val next = _studioState.value.copy(cameraEnabled = enabled)
        commitStudioState(next, recordUndo = false)
        if (dualTelemetry.value.role == DualPhoneRole.PHONE2_STUDIO &&
            dualTelemetry.value.linkState == DualLinkState.CONNECTED
        ) {
            dualEngine.sendRemoteCommand(DualRemoteCommand(DualPhoneProtocol.CMD_TOGGLE_CAMERA, boolArg = enabled))
        }
        showMessage(if (enabled) "Facecam ON" else "Facecam OFF — hidden from live output")
    }

    fun setCameraSourceMode(mode: CameraSourceMode) {
        val useFront = when (mode) {
            CameraSourceMode.PHONE2_REAR -> false
            CameraSourceMode.PHONE2_FRONT -> true
            else -> _studioState.value.useFrontCamera
        }
        commitStudioState(
            _studioState.value.copy(cameraSourceMode = mode, useFrontCamera = useFront),
            recordUndo = false
        )
    }

    fun updateFacecamForActiveScene(transform: (SceneLayer) -> SceneLayer) {
        val cur = _studioState.value
        val camLayer = cur.activeScene.layers.firstOrNull { it.type == LayerSourceType.WEBCAM }
        if (camLayer != null) {
            if (camLayer.locked) {
                showMessage("Facecam layer is locked in this scene. Unlock it first to adjust.")
                return
            }
            commitStudioState(StudioWorkspaceReducer.updateLayer(cur, camLayer.id, transform))
        } else {
            val created = transform(
                SceneLayer(
                    name = "Facecam",
                    type = LayerSourceType.WEBCAM,
                    visible = true,
                    x = 0.74f,
                    y = 0.62f,
                    width = 0.23f,
                    height = 0.33f,
                    facecamShape = FacecamShape.ROUNDED_RECT,
                    borderWidthDp = 1.5f,
                    borderColorHex = 0xFF3B82F6L,
                    shadowEnabled = true
                )
            )
            commitStudioState(StudioWorkspaceReducer.addLayer(cur, created))
        }
    }

    fun setStreamThumbnailUri(uri: String?) {
        val cur = settings.value.stream
        updateStreamSettings(cur.copy(thumbnailUri = uri))
        showMessage(if (uri != null) "Stream thumbnail selected." else "Stream thumbnail cleared.")
    }

    fun switchScene(slot: SceneSlot) {
        val next = StudioWorkspaceReducer.switchScene(_studioState.value, slot)
        commitStudioState(next)
        if (dualTelemetry.value.role == DualPhoneRole.PHONE2_STUDIO &&
            dualTelemetry.value.linkState == DualLinkState.CONNECTED
        ) {
            dualEngine.sendRemoteCommand(DualRemoteCommand(DualPhoneProtocol.CMD_SWITCH_SCENE, stringArg = slot.name))
        }
        showMessage("Switched to ${next.activeScene.name}")
    }

    fun renameActiveScene(newName: String) {
        val next = StudioWorkspaceReducer.renameActiveScene(_studioState.value, newName)
        commitStudioState(next)
    }

    fun toggleSceneLock() {
        val next = StudioWorkspaceReducer.toggleSceneLock(_studioState.value)
        commitStudioState(next)
        val locked = next.activeScene.sceneLocked
        showMessage(if (locked) "Scene Locked — accidental edits blocked while live." else "Scene Unlocked for editing.")
    }

    fun toggleActiveSceneVertical() {
        if (_studioState.value.activeScene.sceneLocked) {
            showMessage("Unlock the scene before switching horizontal/vertical layout.")
            return
        }
        commitStudioState(StudioWorkspaceReducer.toggleActiveSceneVertical(_studioState.value))
    }

    fun updateSceneTransition(type: SceneTransitionType, durationMs: Int) {
        commitStudioState(StudioWorkspaceReducer.updateSceneTransition(_studioState.value, type, durationMs))
    }

    fun duplicateActiveSceneTo(targetSlot: SceneSlot) {
        viewModelScope.launch { settingsStore.savePreviousStudioBackup(_studioState.value) }
        val next = StudioWorkspaceReducer.duplicateActiveSceneTo(_studioState.value, targetSlot)
        commitStudioState(next)
        showMessage("Copied scene layout into ${targetSlot.shortLabel}.")
    }

    fun saveSceneBackupSnapshot() {
        viewModelScope.launch {
            settingsStore.savePreviousStudioBackup(_studioState.value)
            settingsStore.saveStudioWorkspace(_studioState.value)
            showMessage("All 4 scenes saved! You can restore this snapshot anytime.")
        }
    }

    fun restorePreviousSceneBackup() {
        viewModelScope.launch {
            val restored = settingsStore.restorePreviousStudioBackup()
            if (restored != null) {
                _studioState.value = restored
                showMessage("Restored previous saved scene settings.")
            } else {
                showMessage("No previous backup saved yet. Tap 'Save Snapshot' first.")
            }
        }
    }

    fun resetActiveSceneToDefault() {
        viewModelScope.launch { settingsStore.savePreviousStudioBackup(_studioState.value) }
        val next = StudioWorkspaceReducer.resetActiveSceneToDefault(_studioState.value)
        commitStudioState(next)
        showMessage("Reset ${next.activeScene.name} to default layout.")
    }

    fun selectLayer(layerId: String?) {
        commitStudioState(StudioWorkspaceReducer.selectLayer(_studioState.value, layerId), persist = false)
    }

    fun toggleLayerLock(layerId: String) {
        val next = StudioWorkspaceReducer.toggleLayerLock(_studioState.value, layerId)
        commitStudioState(next)
        val target = next.activeScene.layers.firstOrNull { it.id == layerId }
        if (target != null) {
            showMessage(if (target.locked) "Locked '${target.name}'" else "Unlocked '${target.name}'")
        }
    }

    fun toggleLayerVisibility(layerId: String) {
        commitStudioState(StudioWorkspaceReducer.toggleLayerVisibility(_studioState.value, layerId))
    }

    fun addLayerSource(layer: SceneLayer) {
        if (_studioState.value.activeScene.sceneLocked) {
            showMessage("Scene is locked. Unlock scene to add new layers.")
            return
        }
        val next = StudioWorkspaceReducer.addLayer(_studioState.value, layer)
        commitStudioState(next)
        showMessage("Added '${layer.name}' to ${next.activeScene.name}")
    }

    fun addMediaLayerFromUri(uriString: String, isGif: Boolean = false, isVideo: Boolean = false) {
        val type = when {
            isVideo -> LayerSourceType.VIDEO_OVERLAY
            isGif -> LayerSourceType.GIF
            else -> LayerSourceType.IMAGE
        }
        val name = when (type) {
            LayerSourceType.VIDEO_OVERLAY -> "Video Overlay"
            LayerSourceType.GIF -> "Animated GIF"
            else -> "Custom Image"
        }
        addLayerSource(
            SceneLayer(
                name = name,
                type = type,
                x = 0.18f,
                y = 0.18f,
                width = 0.44f,
                height = 0.36f,
                mediaUri = uriString,
                transparentBackground = true,
                animationType = if (isGif) LayerAnimationType.PULSE else LayerAnimationType.NONE
            )
        )
    }

    fun addPresetOverlay(presetTag: String) {
        val layer = when (presetTag) {
            "STARTING_SOON" -> SceneLayer(
                name = "Starting Soon",
                type = LayerSourceType.STARTING_SOON,
                x = 0.12f,
                y = 0.25f,
                width = 0.76f,
                height = 0.38f,
                textContent = "STREAM STARTING SOON",
                fontSizeSp = 22f,
                borderWidthDp = 2f,
                borderColorHex = 0xFF3B82F6L,
                animationType = LayerAnimationType.GLOW,
                presetTag = "STARTING_SOON"
            )
            "BRB" -> SceneLayer(
                name = "BRB Banner",
                type = LayerSourceType.BRB_BANNER,
                x = 0.14f,
                y = 0.28f,
                width = 0.72f,
                height = 0.34f,
                textContent = "BE RIGHT BACK",
                fontSizeSp = 22f,
                borderWidthDp = 2f,
                borderColorHex = 0xFFF59E0BL,
                animationType = LayerAnimationType.PULSE,
                presetTag = "BRB"
            )
            "ENDING" -> SceneLayer(
                name = "Thanks for Watching",
                type = LayerSourceType.ENDING_BANNER,
                x = 0.12f,
                y = 0.22f,
                width = 0.76f,
                height = 0.36f,
                textContent = "THANKS FOR WATCHING!",
                fontSizeSp = 22f,
                borderWidthDp = 2f,
                borderColorHex = 0xFF10B981L,
                animationType = LayerAnimationType.FLOAT,
                presetTag = "ENDING"
            )
            "SUBSCRIBE" -> SceneLayer(
                name = "Subscribe Alert",
                type = LayerSourceType.ANIMATED_OVERLAY,
                x = 0.16f,
                y = 0.74f,
                width = 0.68f,
                height = 0.15f,
                textContent = "SUBSCRIBE & TURN ON NOTIFICATIONS",
                fontSizeSp = 13f,
                backgroundColorHex = 0xDDDC2626L,
                animationType = LayerAnimationType.PULSE,
                presetTag = "SUBSCRIBE"
            )
            else -> SceneLayer(
                name = "Announcement Banner",
                type = LayerSourceType.TEXT,
                x = 0.10f,
                y = 0.80f,
                width = 0.80f,
                height = 0.13f,
                textContent = "CUSTOM STREAM ANNOUNCEMENT",
                fontSizeSp = 14f,
                backgroundColorHex = 0xCC1E293BL,
                borderWidthDp = 1.5f,
                borderColorHex = 0xFF8B5CF6L,
                animationType = LayerAnimationType.TICKER_SCROLL,
                presetTag = "ANNOUNCEMENT"
            )
        }
        addLayerSource(layer)
    }

    fun moveLayerBy(layerId: String, dxNorm: Float, dyNorm: Float) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) return
        if (!dragSnapshotPushed) {
            pushUndoSnapshot(cur)
            dragSnapshotPushed = true
        }
        val next = StudioWorkspaceReducer.moveLayerBy(cur, layerId, dxNorm, dyNorm)
        commitStudioState(next, persist = false, recordUndo = false)
    }

    fun resizeLayerFromCorner(
        layerId: String,
        corner: ResizeCorner,
        dxNorm: Float,
        dyNorm: Float,
        proportional: Boolean = false
    ) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) return
        if (!dragSnapshotPushed) {
            pushUndoSnapshot(cur)
            dragSnapshotPushed = true
        }
        val next = StudioWorkspaceReducer.resizeLayerFromCorner(cur, layerId, corner, dxNorm, dyNorm, proportional)
        commitStudioState(next, persist = false, recordUndo = false)
    }

    fun persistCurrentStudioState() {
        dragSnapshotPushed = false
        val snapshot = _studioState.value
        viewModelScope.launch {
            settingsStore.saveStudioWorkspace(snapshot)
            if (snapshot.autoRestoreLastLayout) {
                settingsStore.saveAuthorizedDeviceLayout(snapshot)
            }
        }
    }

    fun updateLayerProps(layerId: String, transform: (SceneLayer) -> SceneLayer) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked) {
            showMessage("Scene is locked. Unlock scene to edit layers.")
            return
        }
        if (target.locked) {
            showMessage("'${target.name}' is locked. Unlock it in Sources to edit.")
            return
        }
        commitStudioState(StudioWorkspaceReducer.updateLayer(cur, layerId, transform))
    }

    fun alignLayerCenter(layerId: String) {
        commitStudioState(StudioWorkspaceReducer.alignLayerCenter(_studioState.value, layerId))
    }

    fun centerLayerHorizontally(layerId: String) {
        commitStudioState(StudioWorkspaceReducer.centerLayerHorizontally(_studioState.value, layerId))
    }

    fun centerLayerVertically(layerId: String) {
        commitStudioState(StudioWorkspaceReducer.centerLayerVertically(_studioState.value, layerId))
    }

    fun fitLayerFullCanvas(layerId: String) {
        commitStudioState(StudioWorkspaceReducer.fitLayerFullCanvas(_studioState.value, layerId))
    }

    fun fillLayerCanvas(layerId: String) {
        commitStudioState(StudioWorkspaceReducer.fillLayerCanvas(_studioState.value, layerId))
    }

    fun resetLayerTransform(layerId: String) {
        commitStudioState(StudioWorkspaceReducer.resetLayerTransform(_studioState.value, layerId))
    }

    // --- LAYER-BY-LAYER GRAPHIC ADJUSTMENT ---

    fun updateLayerGraphicAdjustmentLive(
        layerId: String,
        transform: (LayerGraphicAdjustment) -> LayerGraphicAdjustment
    ) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) return
        if (!dragSnapshotPushed) {
            pushUndoSnapshot(cur)
            dragSnapshotPushed = true
        }
        val next = StudioWorkspaceReducer.updateLayerGraphicAdjustment(cur, layerId, transform)
        commitStudioState(next, persist = false, recordUndo = false)
    }

    fun applyGraphicPresetToLayer(layerId: String, preset: GraphicPresetId) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) {
            showMessage("Unlock '${target.name}' before applying a preset.")
            return
        }
        val next = StudioWorkspaceReducer.applyGraphicPresetToLayer(cur, layerId, preset)
        commitStudioState(next)
        if (next.autoRestoreLastLayout) {
            viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(next) }
        }
        showMessage("Applied '${preset.label}' preset to '${target.name}'.")
    }

    fun applyCustomGraphicPresetToLayer(layerId: String, customPreset: CustomGraphicPreset) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) {
            showMessage("Unlock '${target.name}' before applying a preset.")
            return
        }
        val next = StudioWorkspaceReducer.applyCustomGraphicPresetToLayer(cur, layerId, customPreset)
        commitStudioState(next)
        if (next.autoRestoreLastLayout) {
            viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(next) }
        }
        showMessage("Applied custom preset '${customPreset.name}' to '${target.name}'.")
    }

    fun resetLayerGraphicAdjustment(layerId: String) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) {
            showMessage("Unlock '${target.name}' before resetting its graphic adjustments.")
            return
        }
        val next = StudioWorkspaceReducer.resetLayerGraphicAdjustment(cur, layerId)
        commitStudioState(next)
        if (next.autoRestoreLastLayout) {
            viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(next) }
        }
        showMessage("Reset graphic adjustments for '${target.name}'.")
    }

    fun copyGraphicAdjustmentToLayer(sourceLayerId: String, targetLayerId: String) {
        val cur = _studioState.value
        val dst = cur.activeScene.layers.firstOrNull { it.id == targetLayerId } ?: return
        if (cur.activeScene.sceneLocked || dst.locked) {
            showMessage("'${dst.name}' is locked. Unlock it first to paste graphic adjustments.")
            return
        }
        val next = StudioWorkspaceReducer.copyGraphicAdjustmentToLayer(cur, sourceLayerId, targetLayerId)
        commitStudioState(next)
        if (next.autoRestoreLastLayout) {
            viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(next) }
        }
        showMessage("Copied graphic adjustments to '${dst.name}'.")
    }

    fun saveCustomGraphicPreset(name: String, adjustment: LayerGraphicAdjustment) {
        val next = StudioWorkspaceReducer.saveCustomGraphicPreset(_studioState.value, name, adjustment)
        commitStudioState(next, recordUndo = false)
        if (next.autoRestoreLastLayout) {
            viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(next) }
        }
        showMessage("Saved custom preset '${name.trim()}'.")
    }

    fun deleteCustomGraphicPreset(presetId: String) {
        val next = StudioWorkspaceReducer.deleteCustomGraphicPreset(_studioState.value, presetId)
        commitStudioState(next, recordUndo = false)
        showMessage("Removed custom graphic preset.")
    }

    fun moveLayerZOrder(layerId: String, bringForward: Boolean) {
        commitStudioState(StudioWorkspaceReducer.moveLayerZOrder(_studioState.value, layerId, bringForward))
    }

    fun duplicateLayer(layerId: String) {
        commitStudioState(StudioWorkspaceReducer.duplicateLayer(_studioState.value, layerId))
    }

    fun deleteLayer(layerId: String) {
        val target = _studioState.value.activeScene.layers.firstOrNull { it.id == layerId }
        if (target?.locked == true) {
            showMessage("Unlock '${target.name}' before deleting it.")
            return
        }
        commitStudioState(StudioWorkspaceReducer.deleteLayer(_studioState.value, layerId))
    }

    // --- LAYER CROPPING, LAYER MERGING, RENAME LAYER & RENAME SCREEN CAPTURE ---

    fun cropLayerEdgeLive(layerId: String, edge: CropEdge, deltaNorm: Float) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) return
        if (!dragSnapshotPushed) {
            pushUndoSnapshot(cur)
            dragSnapshotPushed = true
        }
        val next = StudioWorkspaceReducer.cropLayerEdge(cur, layerId, edge, deltaNorm)
        commitStudioState(next, persist = false, recordUndo = false)
    }

    fun setLayerCropLive(
        layerId: String,
        cropLeft: Float? = null,
        cropTop: Float? = null,
        cropRight: Float? = null,
        cropBottom: Float? = null
    ) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) return
        if (!dragSnapshotPushed) {
            pushUndoSnapshot(cur)
            dragSnapshotPushed = true
        }
        val next = StudioWorkspaceReducer.setLayerCrop(cur, layerId, cropLeft, cropTop, cropRight, cropBottom)
        commitStudioState(next, persist = false, recordUndo = false)
    }

    fun applyLayerCropAspectPreset(layerId: String, preset: CropAspectPreset) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) {
            showMessage("Unlock '${target.name}' before cropping.")
            return
        }
        val next = StudioWorkspaceReducer.applyCropAspectPreset(cur, layerId, preset)
        commitStudioState(next)
        if (next.autoRestoreLastLayout) {
            viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(next) }
        }
        showMessage("Applied ${preset.label} crop to '${target.name}'.")
    }

    fun resetLayerCrop(layerId: String) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) {
            showMessage("Unlock '${target.name}' before resetting crop.")
            return
        }
        val next = StudioWorkspaceReducer.resetLayerCrop(cur, layerId)
        commitStudioState(next)
        if (next.autoRestoreLastLayout) {
            viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(next) }
        }
        showMessage("Reset crop on '${target.name}'.")
    }

    fun renameLayer(layerId: String, newName: String) {
        val clean = newName.trim().take(36)
        if (clean.isBlank()) {
            showMessage("Enter a valid layer name.")
            return
        }
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) {
            showMessage("Unlock '${target.name}' before renaming.")
            return
        }
        val next = StudioWorkspaceReducer.renameLayer(cur, layerId, clean)
        commitStudioState(next)
        if (target.type == LayerSourceType.GAMEPLAY_SCREEN) {
            val streamCur = settings.value.stream
            updateStreamSettings(streamCur.copy(screenCaptureName = clean))
            ZroStreamCoordinator.updateTelemetry { it.copy(captureSourceLabel = clean) }
        }
        if (next.autoRestoreLastLayout) {
            viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(next) }
        }
        showMessage("Renamed layer to '$clean'.")
    }

    fun renameScreenCapture(newName: String) {
        val clean = newName.trim().take(36)
        if (clean.isBlank()) {
            showMessage("Enter a valid screen capture name.")
            return
        }
        val streamCur = settings.value.stream
        updateStreamSettings(streamCur.copy(screenCaptureName = clean))
        ZroStreamCoordinator.updateTelemetry { it.copy(captureSourceLabel = clean) }
        val nextStudio = StudioWorkspaceReducer.renameGameplayCaptureLayers(_studioState.value, clean)
        commitStudioState(nextStudio)
        if (nextStudio.autoRestoreLastLayout) {
            viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(nextStudio) }
        }
        showMessage("Screen Capture renamed to '$clean'.")
    }

    fun mergeLayers(layerIds: List<String>, mergedName: String) {
        val cur = _studioState.value
        if (cur.activeScene.sceneLocked) {
            showMessage("Unlock the scene before merging layers.")
            return
        }
        val unlockedCount = cur.activeScene.layers.count { it.id in layerIds && !it.locked }
        if (unlockedCount < 2) {
            showMessage("Select at least 2 unlocked layers to merge.")
            return
        }
        val next = StudioWorkspaceReducer.mergeLayers(cur, layerIds, mergedName)
        commitStudioState(next)
        if (next.autoRestoreLastLayout) {
            viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(next) }
        }
        val createdName = next.activeScene.layers.firstOrNull { it.id == next.selectedLayerId }?.name ?: "Merged Layer"
        showMessage("Merged $unlockedCount layers into '$createdName'.")
    }

    fun unmergeLayer(mergedLayerId: String) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == mergedLayerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) {
            showMessage("Unlock '${target.name}' before unmerging.")
            return
        }
        val next = StudioWorkspaceReducer.unmergeLayer(cur, mergedLayerId)
        commitStudioState(next)
        if (next.autoRestoreLastLayout) {
            viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(next) }
        }
        showMessage("Unmerged '${target.name}' back into individual layers.")
    }

    fun setCanvasGridMode(mode: CanvasGridMode) {
        commitStudioState(
            _studioState.value.copy(
                gridMode = mode,
                showGrid = mode != CanvasGridMode.OFF
            ),
            recordUndo = false
        )
    }

    fun updateCanvasOptions(
        showGrid: Boolean = _studioState.value.showGrid,
        showSnapGuides: Boolean = _studioState.value.showSnapGuides,
        showSafeArea: Boolean = _studioState.value.showSafeArea,
        canvasZoom: Float = _studioState.value.canvasZoom
    ) {
        val nextGridMode = when {
            !showGrid -> CanvasGridMode.OFF
            _studioState.value.gridMode == CanvasGridMode.OFF -> CanvasGridMode.GRID_2X6
            else -> _studioState.value.gridMode
        }
        commitStudioState(
            _studioState.value.copy(
                gridMode = nextGridMode,
                showGrid = showGrid,
                showSnapGuides = showSnapGuides,
                showSafeArea = showSafeArea,
                canvasZoom = canvasZoom.coerceIn(0.6f, 2.2f)
            ),
            recordUndo = false
        )
    }

    // --- DUAL-PHONE STREAMING & REMOTE CONTROL ---

    fun setDualPhoneRole(role: DualPhoneRole) {
        dualEngine.setRole(role)
        showMessage("Switched role: ${role.label}")
    }

    fun setDualConnectionMethod(method: DualConnectionMethod) {
        dualEngine.setConnectionMethod(method)
    }

    fun setDualAutoReconnect(enabled: Boolean) {
        dualEngine.setAutoReconnect(enabled)
    }

    fun startPhone2StudioHost() {
        dualEngine.startPhone2Host(
            hostDeviceName = "Zro Studio (${Build.MODEL})",
            method = dualTelemetry.value.connectionMethod
        )
        showMessage("Phone 2 Studio Host active. Scan the QR code or enter IP + Token on Phone 1.")
    }

    fun connectPhone1ByQrUri(rawUri: String): Boolean {
        val payload = DualPhoneProtocol.parsePairingUri(rawUri)
        if (payload == null) {
            showMessage("Invalid Zro Live QR code. Scan the QR code displayed on Phone 2.")
            return false
        }
        dualEngine.connectPhone1ToStudio(
            ip = payload.ip,
            controlPort = payload.controlPort,
            videoPort = payload.videoPort,
            token = payload.token,
            deviceName = "Phone 1 (${Build.MODEL})",
            method = DualConnectionMethod.QR_PAIRING
        )
        showMessage("Connecting Phone 1 to ${payload.hostName} (${payload.ip})…")
        return true
    }

    fun connectPhone1Manual(ip: String, portText: String, token: String) {
        val port = portText.trim().toIntOrNull()?.coerceIn(1024, 65535) ?: DualPhoneProtocol.DEFAULT_CONTROL_PORT
        dualEngine.connectPhone1ToStudio(
            ip = ip.trim(),
            controlPort = port,
            videoPort = port + 1,
            token = token.trim().uppercase(),
            deviceName = "Phone 1 (${Build.MODEL})",
            method = DualConnectionMethod.MANUAL_IP
        )
    }

    fun disconnectDualPhone() {
        dualEngine.disconnect(clearRole = false)
        showMessage("Dual-phone link disconnected.")
    }

    fun reconnectDualPhone() {
        dualEngine.reconnect()
        showMessage("Reconnecting dual-phone session…")
    }

    fun currentPhone2PairingUri(): String = dualEngine.currentPairingUri("Zro Studio (${Build.MODEL})")

    fun moveRemoteTile(tileId: RemoteControlTileId, moveUp: Boolean) {
        commitStudioState(StudioWorkspaceReducer.moveRemoteTile(_studioState.value, tileId, moveUp), recordUndo = false)
    }

    fun addRemoteTile(tileId: RemoteControlTileId) {
        commitStudioState(StudioWorkspaceReducer.addRemoteTile(_studioState.value, tileId), recordUndo = false)
    }

    fun removeRemoteTile(tileId: RemoteControlTileId) {
        commitStudioState(StudioWorkspaceReducer.removeRemoteTile(_studioState.value, tileId), recordUndo = false)
    }

    fun resetRemoteDeckToDefault() {
        commitStudioState(StudioWorkspaceReducer.resetRemoteDeckToDefault(_studioState.value), recordUndo = false)
        showMessage("Restored default 3-column Remote Deck layout.")
    }

    fun setRemoteTileScale(scale: Float) {
        commitStudioState(_studioState.value.copy(remoteTileScale = scale.coerceIn(0.85f, 1.30f)), recordUndo = false)
    }

    /**
     * Dispatches a Remote Control action:
     * - If Phone 2 is connected to Phone 1: sends command over LAN to Phone 1 AND updates local Studio HUD.
     * - Otherwise: executes directly on this device!
     */
    fun dispatchRemoteControl(cmd: DualRemoteCommand) {
        val dt = dualTelemetry.value
        if (dt.role == DualPhoneRole.PHONE2_STUDIO && dt.linkState == DualLinkState.CONNECTED) {
            dualEngine.sendRemoteCommand(cmd)
        }
        executeRemoteCommandLocally(cmd, fromRemotePeer = false)
    }

    private fun executeRemoteCommandLocally(cmd: DualRemoteCommand, fromRemotePeer: Boolean) {
        val prefix = if (fromRemotePeer) "Remote: " else ""
        when (cmd.action) {
            DualPhoneProtocol.CMD_START_LIVE -> {
                if (!ZroStreamCoordinator.isSessionActive()) {
                    if (ZroStreamCoordinator.isCaptureActive()) {
                        startBroadcastFromExistingCapture()
                    } else {
                        showMessage("${prefix}Select a game capture source first to go live.")
                    }
                }
            }
            DualPhoneProtocol.CMD_END_LIVE -> {
                endStream()
                showMessage("${prefix}Live stream ended.")
            }
            DualPhoneProtocol.CMD_PAUSE_RESUME -> {
                togglePauseStream()
            }
            DualPhoneProtocol.CMD_MUTE_MIC -> {
                val cur = settings.value.audio
                val nextEnabled = if (cmd.stringArg == "EXPLICIT") cmd.boolArg else !cur.micEnabled
                updateAudioSettings(cur.copy(micEnabled = nextEnabled))
                showMessage(if (nextEnabled) "${prefix}Microphone LIVE" else "${prefix}Microphone MUTED")
            }
            DualPhoneProtocol.CMD_SET_MIC_VOL -> {
                val cur = settings.value.audio
                val nextVol = cmd.floatArg.coerceIn(0f, 1.25f)
                updateAudioSettings(cur.copy(micVolume = nextVol))
            }
            DualPhoneProtocol.CMD_SET_GAME_VOL -> {
                val cur = settings.value.audio
                val nextVol = cmd.floatArg.coerceIn(0f, 1.25f)
                updateAudioSettings(cur.copy(gameVolume = nextVol))
            }
            DualPhoneProtocol.CMD_SET_TEAM_VOL -> {
                val cur = settings.value.audio
                val nextVol = cmd.floatArg.coerceIn(0f, 1.25f)
                updateAudioSettings(cur.copy(teamVolume = nextVol))
            }
            DualPhoneProtocol.CMD_SET_MASTER_VOL -> {
                val cur = settings.value.audio
                val nextVol = cmd.floatArg.coerceIn(0f, 1.5f)
                updateAudioSettings(cur.copy(masterVolume = nextVol))
            }
            DualPhoneProtocol.CMD_SET_TEAM_VOICE_LIVE -> {
                setTeamVoiceToLive(cmd.boolArg)
            }
            DualPhoneProtocol.CMD_SWITCH_SCENE -> {
                val slot = SceneSlot.fromName(cmd.stringArg)
                val next = StudioWorkspaceReducer.switchScene(_studioState.value, slot)
                commitStudioState(next)
                showMessage("${prefix}Scene -> ${next.activeScene.name}")
            }
            DualPhoneProtocol.CMD_TOGGLE_OVERLAYS -> {
                val nextVis = !_studioState.value.overlaysGlobalVisible
                commitStudioState(_studioState.value.copy(overlaysGlobalVisible = nextVis))
                showMessage(if (nextVis) "${prefix}Overlays Visible" else "${prefix}Overlays Hidden")
            }
            DualPhoneProtocol.CMD_SWITCH_CAMERA -> {
                val nextFront = !_studioState.value.useFrontCamera
                commitStudioState(
                    _studioState.value.copy(
                        useFrontCamera = nextFront,
                        cameraSourceMode = if (nextFront) CameraSourceMode.PHONE2_FRONT else CameraSourceMode.PHONE2_REAR
                    ),
                    recordUndo = false
                )
                showMessage(if (nextFront) "${prefix}Switched to Front Camera" else "${prefix}Switched to Rear Camera")
            }
            DualPhoneProtocol.CMD_TOGGLE_CAMERA -> {
                val nextEnabled = cmd.boolArg
                commitStudioState(_studioState.value.copy(cameraEnabled = nextEnabled), recordUndo = false)
                showMessage(if (nextEnabled) "${prefix}Camera ON" else "${prefix}Camera OFF")
            }
            DualPhoneProtocol.CMD_TRIGGER_BRB -> {
                val next = StudioWorkspaceReducer.triggerBrbPrivacy(_studioState.value)
                commitStudioState(next)
                showMessage("${prefix}BRB / Privacy Active")
            }
            DualPhoneProtocol.CMD_RESUME_FROM_BRB -> {
                val next = StudioWorkspaceReducer.resumeFromBrbPrivacy(_studioState.value)
                commitStudioState(next)
                showMessage("${prefix}Resumed ${next.activeScene.name}")
            }
            DualPhoneProtocol.CMD_SCREENSHOT -> {
                captureStudioScreenshot()
            }
        }
    }

    fun captureStudioScreenshot() {
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = mirroredFrame.value
            val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: appContext.filesDir, "ZroScreenshots")
            dir.mkdirs()
            val file = File(dir, "zro_capture_${System.currentTimeMillis()}.png")
            val saved = runCatching {
                val toSave = bmp ?: Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(0xFF0A0F1E.toInt())
                }
                FileOutputStream(file).use { out ->
                    toSave.compress(Bitmap.CompressFormat.PNG, 95, out)
                }
                true
            }.getOrDefault(false)
            if (saved) {
                _localScreenshotCount.update { it + 1 }
                showMessage("Screenshot saved (${file.name})")
            } else {
                showMessage("Could not save screenshot to app storage.")
            }
        }
    }

    // --- AUDIO ROUTING (TEAM VOICE TO LIVE) & CLOSE VOICE FOCUS ---

    fun setTeamVoiceToLive(enabled: Boolean) {
        val cur = settings.value.audio
        updateAudioSettings(cur.copy(teamVoiceToLive = enabled))
        showMessage(
            if (enabled) "TEAM VOICE TO LIVE: ON — Consented squad voice now mixed into broadcast."
            else "TEAM VOICE TO LIVE: OFF — Squad stays private in your headphones."
        )
    }

    fun toggleTeamVoiceToLive() {
        val next = !settings.value.audio.teamVoiceToLive
        dispatchRemoteControl(DualRemoteCommand(DualPhoneProtocol.CMD_SET_TEAM_VOICE_LIVE, boolArg = next))
    }

    fun applyCloseVoiceFocusPreset(enabled: Boolean) {
        val cur = settings.value.audio
        val updated = if (enabled) {
            cur.copy(
                closeVoiceFocusEnabled = true,
                noiseCancellationLevel = NoiseCancellationLevel.VOICE_ONLY,
                noiseGateEnabled = true,
                noiseGateThresholdDb = -36f,
                voiceIsolationStrength = 0.85f,
                ambientNoiseReduction = 0.85f,
                windNoiseReduction = true,
                windReductionStrength = maxOf(cur.windReductionStrength, 0.72f),
                voiceClarityEnhancement = true
            )
        } else {
            cur.copy(
                closeVoiceFocusEnabled = false,
                noiseCancellationLevel = NoiseCancellationLevel.MEDIUM,
                noiseGateThresholdDb = -42f,
                voiceIsolationStrength = 0.55f,
                ambientNoiseReduction = 0.50f
            )
        }
        updateAudioSettings(updated)
        showMessage(
            if (enabled) "Close Voice Focus ON — Tuned for close speaking (~0.5 ft) with smooth voice gate."
            else "Close Voice Focus OFF — Restored balanced mic pickup."
        )
    }

    // --- PHONE 2 CHAT CONTROLS (PIN / TEXT SIZE / OVERLAY / REFRESH) ---

    fun pinChatMessage(msg: YouTubeChatMessage) {
        commitStudioState(
            _studioState.value.copy(
                pinnedChatMessageId = msg.id,
                pinnedChatAuthor = msg.authorName,
                pinnedChatText = msg.messageText
            )
        )
        showMessage("Pinned message from ${msg.authorName}")
    }

    fun unpinChatMessage() {
        commitStudioState(
            _studioState.value.copy(
                pinnedChatMessageId = null,
                pinnedChatAuthor = null,
                pinnedChatText = null
            )
        )
    }

    fun setChatTextSize(sizeSp: Float) {
        commitStudioState(_studioState.value.copy(chatTextSizeSp = sizeSp.coerceIn(10f, 22f)))
    }

    fun setChatOverlayEnabled(enabled: Boolean) {
        commitStudioState(_studioState.value.copy(chatOverlayEnabled = enabled))
    }

    fun refreshYouTubeLiveChat() {
        if (settings.value.stream.youtubeVideoId.isBlank()) {
            showMessage("Enter a YouTube Live Video ID to refresh live chat.")
            return
        }
        connectYouTubeLiveChat()
        showMessage("Refreshing YouTube Live Chat…")
    }

    suspend fun prepareForSignOut() {
        chatPollJob?.cancel()
        dualEngine.disconnect(clearRole = true)
        ZroAudioEngine.stopMicTest()
        val rid = _activeRoomId.value
        if (!rid.isNullOrBlank()) {
            ZroAudioEngine.stopVoiceRoomAudio()
            if (!isGuest) repository!!.leaveVoiceRoom(rid)
            _activeRoomId.value = null
            ZroStreamCoordinator.setActiveVoiceRoomName(null)
        }
        if (ZroStreamCoordinator.isSessionActive() || ZroStreamCoordinator.isCaptureActive()) {
            ZroStreamCoordinator.requestStopCapture(appContext)
        }
        stopOverlay()
    }

    override fun onCleared() {
        super.onCleared()
        chatPollJob?.cancel()
    }
}
