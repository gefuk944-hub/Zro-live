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
import android.net.Uri
import android.os.Environment
import com.example.data.model.LiveBroadcastMode
import com.example.data.model.LiveVideoSource
import com.example.data.model.NoiseCancellationLevel
import com.example.data.model.RtmpTestResult
import com.example.data.model.VideoResolution
import com.example.dualphone.DualPhoneEngine
import com.example.dualphone.DualPhoneProtocol
import com.example.dualphone.DualRemoteCommand
import com.example.dualphone.WifiDirectHelper
import com.example.dualphone.WifiDirectState
import com.example.overlay.FloatingOverlayService
import com.example.streaming.RtmpPublisher
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
import com.example.studio.ExternalCameraDetector
import com.example.studio.ExternalCameraInventory
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
import com.example.studio.StudioMediaStore
import com.example.studio.StudioWorkspaceReducer
import com.example.studio.StudioWorkspaceState
import com.example.telemetry.ActiveLiveSessionSnapshot
import com.example.telemetry.AppConnectivitySnapshot
import com.example.telemetry.ConnectivityStatusMonitor
import com.example.telemetry.CrashLogEntry
import com.example.telemetry.CrashRecoveryStore
import com.example.telemetry.DeviceTelemetryMonitor
import com.example.telemetry.SavedLiveConfigProfile
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    val localCapturedFrame: StateFlow<Bitmap?> = ZroStreamCoordinator.localCapturedFrame
    val audioMeters: StateFlow<AudioMeterState> = ZroAudioEngine.meterState
    val overlayVisible: StateFlow<Boolean> = ZroStreamCoordinator.overlayVisible
    val overlayEdgeHidden: StateFlow<Boolean> = ZroStreamCoordinator.overlayEdgeHidden

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

    private val _rtmpTestResult = MutableStateFlow<RtmpTestResult?>(null)
    val rtmpTestResult: StateFlow<RtmpTestResult?> = _rtmpTestResult.asStateFlow()

    private val _isTestingRtmp = MutableStateFlow(false)
    val isTestingRtmp: StateFlow<Boolean> = _isTestingRtmp.asStateFlow()

    private val _chatState = MutableStateFlow<YouTubeChatState>(YouTubeChatState.Disconnected)
    val chatState: StateFlow<YouTubeChatState> = _chatState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _isUiForeground = MutableStateFlow(true)
    private var chatPollJob: Job? = null

    // --- CRASH RECOVERY, LIVE MANAGER PROFILES & EXTERNAL / DJI CAMERA INVENTORY ---
    val recoverableSession: StateFlow<ActiveLiveSessionSnapshot?> = combine(
        settingsStore.activeLiveSnapshotFlow,
        ZroStreamCoordinator.telemetry
    ) { snapshot, tel ->
        if (snapshot != null && !snapshot.cleanlyEnded && !tel.state.isActive) snapshot else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val savedLiveProfiles: StateFlow<List<SavedLiveConfigProfile>> = settingsStore.savedLiveProfilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _crashLogs = MutableStateFlow<List<CrashLogEntry>>(emptyList())
    val crashLogs: StateFlow<List<CrashLogEntry>> = _crashLogs.asStateFlow()

    private val _externalCameraInventory = MutableStateFlow(ExternalCameraInventory())
    val externalCameraInventory: StateFlow<ExternalCameraInventory> = _externalCameraInventory.asStateFlow()

    private val connectivityMonitor = ConnectivityStatusMonitor(appContext)
    private val wifiDirectHelper = WifiDirectHelper(appContext)
    val wifiDirectState: StateFlow<WifiDirectState> = wifiDirectHelper.state

    val connectivitySnapshot: StateFlow<AppConnectivitySnapshot> = combine(
        combine(
            connectivityMonitor.networkStatus,
            _rtmpTestResult,
            _isTestingRtmp,
            connectivityMonitor.stunStatus
        ) { net, rtmpRes, testing, stun ->
            QuadConnectivityInput(net, rtmpRes, testing, stun)
        },
        streamTelemetry,
        settings
    ) { q, streamTel, cfg ->
        ConnectivityStatusMonitor.buildSnapshot(
            network = q.network,
            rtmpTest = q.rtmpTest,
            isTestingRtmp = q.isTestingRtmp,
            stunState = q.stunState,
            streamTelemetry = streamTel,
            configuredRtmpUrl = cfg.stream.effectivePrimaryServerUrl()
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        ConnectivityStatusMonitor.buildSnapshot(
            network = connectivityMonitor.networkStatus.value,
            rtmpTest = null,
            isTestingRtmp = false,
            stunState = null to 0L,
            streamTelemetry = streamTelemetry.value,
            configuredRtmpUrl = ""
        )
    )

    private val _autoSwitchToRemoteEvent = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val autoSwitchToRemoteEvent: SharedFlow<Boolean> = _autoSwitchToRemoteEvent.asSharedFlow()

    @Volatile private var pendingReuseSessionId: String? = null
    private var wasSecondPhoneConnected: Boolean = false

    init {
        connectivityMonitor.startMonitoring()
        // Install secret-scrubbing crash logger so unexpected crashes never lose live state
        CrashRecoveryStore.installUncaughtHandler(appContext)
        viewModelScope.launch(Dispatchers.IO) {
            _crashLogs.value = CrashRecoveryStore.readLogs(appContext)
            _externalCameraInventory.value = ExternalCameraDetector.inspect(appContext)
        }

        // Hydrate persisted Studio workspace for the active broadcast mode (16:9 Live, Shorts Live, or Dual Live)
        viewModelScope.launch {
            val initialMode = settingsStore.settingsFlow.first().stream.broadcastMode
            val loaded = settingsStore.loadStudioWorkspaceForMode(initialMode)
            _studioState.value = loaded
            ZroStreamCoordinator.updateStudioWorkspace(loaded)
        }

        // Sync scene switches triggered from Persistent Notification or Floating Overlay (Screen 1-4)
        viewModelScope.launch {
            ZroStreamCoordinator.sceneSwitchEvents.collect { slot ->
                val next = StudioWorkspaceReducer.switchScene(_studioState.value, slot)
                _studioState.value = next
                ZroStreamCoordinator.updateStudioWorkspace(next)
                val mode = activeBroadcastMode()
                settingsStore.saveStudioWorkspace(next, mode)
                if (dualTelemetry.value.role == DualPhoneRole.PHONE2_STUDIO &&
                    dualTelemetry.value.linkState == DualLinkState.CONNECTED
                ) {
                    dualEngine.sendRemoteCommand(DualRemoteCommand(DualPhoneProtocol.CMD_SWITCH_SCENE, stringArg = slot.name))
                }
            }
        }

        // Sync live chat messages to compositor so CHAT_OVERLAY layers appear in actual RTMP output
        viewModelScope.launch {
            _chatState.collect { st ->
                val msgs = (st as? YouTubeChatState.Connected)?.messages.orEmpty()
                ZroStreamCoordinator.updateLiveChatMessages(msgs)
            }
        }

        // Auto-switch live video source between Phone 1 full screen and connected 2nd Phone screen (Auto Solo Mode)
        // AND auto-restore saved layout when the authorized gameplay phone connects
        viewModelScope.launch {
            dualEngine.telemetry.collect { dt ->
                if (dt.role == DualPhoneRole.PHONE2_STUDIO &&
                    dt.linkState == DualLinkState.CONNECTED
                ) {
                    if (!wasSecondPhoneConnected) {
                        wasSecondPhoneConnected = true
                        // Automatically restore 2nd Phone gameplay capture!
                        ZroStreamCoordinator.requestSwitchVideoSource(appContext, LiveVideoSource.REMOTE_SECOND_PHONE)
                        _autoSwitchToRemoteEvent.tryEmit(true)
                        val peerLabel = dt.connectedDeviceName.ifBlank { "2nd Phone" }
                        showMessage("Connected $peerLabel (${dt.transportModeLabel}) — live source restored to gameplay phone.")
                    }
                    if (dt.connectedDeviceName.isNotBlank()) {
                        val key = "${dt.connectedDeviceName}@${dt.peerIp}"
                        if (lastAutoRestoredDeviceKey != key) {
                            lastAutoRestoredDeviceKey = key
                            val cur = _studioState.value
                            if (cur.autoRestoreLastLayout) {
                                val saved = settingsStore.loadAuthorizedDeviceLayout(settings.value.stream.broadcastMode)
                                if (saved != null) {
                                    val updated = saved.copy(
                                        lastAuthorizedDeviceName = dt.connectedDeviceName,
                                        lastAuthorizedDeviceIp = dt.peerIp
                                    )
                                    _studioState.value = updated
                                    ZroStreamCoordinator.updateStudioWorkspace(updated)
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
                    }
                } else if (dt.linkState != DualLinkState.CONNECTED) {
                    lastAutoRestoredDeviceKey = null
                    if (wasSecondPhoneConnected) {
                        wasSecondPhoneConnected = false
                        ZroStreamCoordinator.requestSwitchVideoSource(appContext, LiveVideoSource.LOCAL_FULL_SCREEN)
                        if (ZroStreamCoordinator.isSessionActive()) {
                            showMessage("Auto Solo Mode: Gameplay phone disconnected — live stream switched to this phone's screen.")
                        } else {
                            showMessage("Auto Solo Mode: Gameplay phone disconnected — using this phone until it reconnects.")
                        }
                    }
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
                CrashRecoveryStore.updateSecretsToScrub(cfg.stream.streamKey, cfg.stream.shortsStreamKey)
                ZroAudioEngine.updateSettings(appContext, cfg.audio)
                ZroAudioEngine.applyPerformanceSettings(cfg.performance)
                if (!ZroStreamCoordinator.isSessionActive()) {
                    val configValid = RtmpPublisher.validateConfig(
                        primaryUrl = cfg.stream.effectivePrimaryServerUrl(),
                        backupUrl = cfg.stream.effectiveBackupServerUrl(),
                        streamKey = cfg.stream.streamKey
                    ).valid
                    ZroStreamCoordinator.updateTelemetry { cur ->
                        if (cur.state == StreamLifecycleState.IDLE || cur.state == StreamLifecycleState.READY) {
                            cur.copy(
                                // Never show false READY/LIVE while offline — keep IDLE until real connection starts
                                state = StreamLifecycleState.IDLE,
                                statusDetail = if (configValid) {
                                    "Offline — RTMP configured. Press Start Live to connect."
                                } else {
                                    "Offline — enter Stream RTMP URL & Stream Key in Live Setup."
                                },
                                targetBitrateKbps = cfg.stream.bitrateKbps,
                                targetFps = cfg.stream.fps,
                                resolutionLabel = cfg.stream.resolution.shortLabel
                            )
                        } else cur
                    }
                }
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

        // Save finalized stream session stats (upsert by sessionId so reconnected sessions never duplicate)
        viewModelScope.launch {
            ZroStreamCoordinator.streamStopCallback.collect { stats ->
                val sid = stats.sessionId ?: "local_${System.currentTimeMillis()}"
                val finalStatus = if (stats.wasCleanStop) "ENDED" else "INTERRUPTED"
                if (!isGuest && !stats.sessionId.isNullOrBlank()) {
                    repository!!.finalizeStreamSession(
                        sessionId = stats.sessionId,
                        durationSec = stats.durationSec,
                        droppedFrames = stats.droppedFrames,
                        peakBitrateKbps = stats.peakBitrateKbps
                    )
                }
                // Always upsert into local Live Manager history so offline/guest & signed-in users both have instant access
                if (stats.durationSec > 0L || !stats.wasCleanStop) {
                    settingsStore.upsertLocalSession(
                        StreamSessionDoc(
                            id = sid,
                            userId = currentUserId ?: "guest",
                            title = stats.title,
                            visibility = stats.visibility,
                            resolutionLabel = stats.resolutionLabel,
                            fps = stats.fps.toLong(),
                            bitrateKbps = stats.bitrateKbps.toLong(),
                            durationSec = stats.durationSec,
                            droppedFrames = stats.droppedFrames,
                            peakBitrateKbps = stats.peakBitrateKbps.toLong(),
                            status = finalStatus,
                            platformLabel = stats.platformLabel,
                            rtmpUrl = stats.rtmpUrl,
                            sceneSlotName = stats.sceneSlotName,
                            startedAtEpochMs = System.currentTimeMillis() - (stats.durationSec * 1000L)
                        )
                    )
                }
                refreshCrashLogs()
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
                        ZroStreamCoordinator.updateDeviceTemperature(
                            tempC = snap.batteryTempCelsius,
                            label = DeviceTelemetryMonitor.formatTemperature(snap)
                        )
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
        val synced = updated.withSyncedActiveModePreset()
        ZroStreamCoordinator.currentStreamSettings = synced
        viewModelScope.launch { settingsStore.saveStreamSettings(synced) }
    }

    fun switchBroadcastMode(targetMode: LiveBroadcastMode) {
        val currentCfg = settings.value.stream
        if (currentCfg.broadcastMode == targetMode) return
        if (ZroStreamCoordinator.isSessionActive()) {
            showMessage("End the active live stream before switching between 16:9 Live and Shorts Live.")
            return
        }
        val prevMode = currentCfg.broadcastMode
        val prevStudio = _studioState.value
        val nextCfg = currentCfg.switchBroadcastMode(targetMode)
        ZroStreamCoordinator.currentStreamSettings = nextCfg
        viewModelScope.launch {
            settingsStore.saveStudioWorkspace(prevStudio, prevMode)
            settingsStore.saveStreamSettings(nextCfg)
            val nextStudio = settingsStore.loadStudioWorkspaceForMode(targetMode)
            undoStack.clear()
            redoStack.clear()
            _canUndo.value = false
            _canRedo.value = false
            _studioState.value = nextStudio
            ZroStreamCoordinator.updateStudioWorkspace(nextStudio)
            showMessage("Switched to ${targetMode.label} (${nextCfg.effectiveResolutionDisplay()})")
        }
    }

    fun saveStreamConfiguration(updated: StreamSettings = settings.value.stream) {
        val cleaned = updated.copy(
            rtmpUrl = updated.rtmpUrl.trim(),
            backupRtmpUrl = updated.backupRtmpUrl.trim(),
            streamKey = updated.streamKey.trim(),
            shortsRtmpUrl = updated.shortsRtmpUrl.trim(),
            shortsStreamKey = updated.shortsStreamKey.trim()
        )
        updateStreamSettings(cleaned)
        val validation = RtmpPublisher.validateConfig(
            primaryUrl = cleaned.effectivePrimaryServerUrl(),
            backupUrl = cleaned.effectiveBackupServerUrl(),
            streamKey = cleaned.streamKey
        )
        if (!validation.valid) {
            showMessage("Saved draft — ${validation.errorMessage ?: "Invalid RTMP config."}")
            return
        }
        if (cleaned.broadcastMode == LiveBroadcastMode.DUAL_LIVE) {
            val shortsValidation = RtmpPublisher.validateConfig(
                primaryUrl = cleaned.effectiveShortsServerUrl(),
                backupUrl = "",
                streamKey = cleaned.shortsStreamKey
            )
            if (!shortsValidation.valid) {
                showMessage("Saved draft — Dual Live also needs a valid Shorts 9:16 RTMP URL & Stream Key.")
                return
            }
        }
        showMessage("Streaming configuration saved (${cleaned.broadcastMode.label} • Key ${RtmpPublisher.maskStreamKey(cleaned.streamKey)}).")
    }

    fun testRtmpConnection() {
        if (_isTestingRtmp.value) return
        val cfg = settings.value.stream
        val primaryUrl = if (cfg.useBackupServer && cfg.effectiveBackupServerUrl().isNotBlank()) {
            cfg.effectiveBackupServerUrl()
        } else {
            cfg.effectivePrimaryServerUrl()
        }
        val backupUrl = if (cfg.useBackupServer) {
            cfg.effectivePrimaryServerUrl()
        } else {
            cfg.effectiveBackupServerUrl()
        }
        val validation = RtmpPublisher.validateConfig(
            primaryUrl = primaryUrl,
            backupUrl = backupUrl,
            streamKey = cfg.streamKey
        )
        if (!validation.valid) {
            val errMsg = validation.errorMessage ?: "Invalid RTMP configuration."
            _rtmpTestResult.value = RtmpTestResult(
                inProgress = false,
                success = false,
                serverTested = primaryUrl,
                message = errMsg
            )
            showMessage(errMsg)
            return
        }

        _isTestingRtmp.value = true
        _rtmpTestResult.value = RtmpTestResult(
            inProgress = true,
            success = null,
            serverTested = primaryUrl,
            message = "Testing RTMP handshake…"
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val publisher = RtmpPublisher()
                val primaryRes = publisher.testConnection(primaryUrl, cfg.streamKey)
                if (primaryRes.success || backupUrl.isBlank() || backupUrl == primaryUrl) {
                    RtmpTestResult(
                        inProgress = false,
                        success = primaryRes.success,
                        latencyMs = primaryRes.latencyMs,
                        serverTested = primaryRes.serverUrl,
                        message = primaryRes.detail
                    )
                } else {
                    val backupRes = publisher.testConnection(backupUrl, cfg.streamKey)
                    if (backupRes.success) {
                        RtmpTestResult(
                            inProgress = false,
                            success = true,
                            latencyMs = backupRes.latencyMs,
                            serverTested = backupRes.serverUrl,
                            message = "Backup server reachable (${backupRes.latencyMs} ms). Primary failed: ${primaryRes.detail}"
                        )
                    } else {
                        RtmpTestResult(
                            inProgress = false,
                            success = false,
                            latencyMs = primaryRes.latencyMs,
                            serverTested = primaryRes.serverUrl,
                            message = primaryRes.detail
                        )
                    }
                }
            }
            _rtmpTestResult.value = result
            _isTestingRtmp.value = false
            showMessage(
                if (result.success == true) "RTMP connection verified (${result.latencyMs} ms)."
                else result.message
            )
        }
    }

    /**
     * Updates capture mode while enforcing the privacy guarantee:
     * FULL_SCREEN can never be silently activated unless explicitly enabled by the user.
     */
    fun selectCaptureMode(mode: CaptureMode, explicitFullScreenConfirmed: Boolean = true) {
        val current = settings.value.stream
        if (mode == CaptureMode.FULL_SCREEN) {
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

    // --- CRASH RECOVERY, LIVE MANAGER PROFILES & EXTERNAL CAMERAS ---

    /**
     * Restores the exact interrupted live session (same sessionId, RTMP URL, title, resolution/FPS/bitrate, scene)
     * without creating a duplicate broadcast. Returns true if config is valid and ready to launch.
     */
    fun prepareReconnectPreviousLive(): Boolean {
        val snap = recoverableSession.value
        val cur = settings.value.stream
        if (snap != null) {
            val restoredRes = VideoResolution.fromName(snap.resolutionName)
            val restored = cur.copy(
                streamTitle = snap.title.ifBlank { cur.streamTitle },
                rtmpUrl = snap.rtmpUrl.ifBlank { cur.rtmpUrl },
                resolution = restoredRes,
                fps = snap.fps.coerceIn(24, 90),
                bitrateKbps = snap.bitrateKbps.coerceIn(1000, 40000)
            )
            updateStreamSettings(restored)
            val restoredSlot = SceneSlot.fromName(snap.activeSceneSlotName)
            if (_studioState.value.activeSceneSlot != restoredSlot) {
                commitStudioState(StudioWorkspaceReducer.switchScene(_studioState.value, restoredSlot), recordUndo = false)
            }
            pendingReuseSessionId = snap.sessionId
            val validation = RtmpPublisher.validateConfig(
                primaryUrl = restored.effectivePrimaryServerUrl(),
                backupUrl = restored.effectiveBackupServerUrl(),
                streamKey = restored.streamKey
            )
            if (!validation.valid) {
                showMessage("Restored '${snap.title}'. Enter your Stream Key in Live Setup to reconnect.")
                return false
            }
            showMessage("Reconnecting to '${snap.title}' (same stream key & session)…")
            return true
        }
        val lastKnownSid = streamTelemetry.value.activeSessionId
        val validation = RtmpPublisher.validateConfig(
            primaryUrl = cur.effectivePrimaryServerUrl(),
            backupUrl = cur.effectiveBackupServerUrl(),
            streamKey = cur.streamKey
        )
        if (!validation.valid) {
            showMessage("Enter your Stream RTMP URL and Stream Key in Live Setup to reconnect.")
            return false
        }
        if (!lastKnownSid.isNullOrBlank()) {
            pendingReuseSessionId = lastKnownSid
        }
        showMessage("Reconnecting live stream with same stream key & session…")
        return true
    }

    /**
     * Loads a previous/saved live session from Live Manager and marks its sessionId for seamless resume.
     */
    fun prepareResumeSavedSession(session: StreamSessionDoc): Boolean {
        val cur = settings.value.stream
        val matchedRes = VideoResolution.entries.firstOrNull {
            it.shortLabel.equals(session.resolutionLabel, ignoreCase = true)
        } ?: cur.resolution
        val restored = cur.copy(
            streamTitle = session.title.ifBlank { cur.streamTitle },
            rtmpUrl = session.rtmpUrl.ifBlank { cur.rtmpUrl },
            resolution = matchedRes,
            fps = session.fps.toInt().coerceIn(24, 90),
            bitrateKbps = session.bitrateKbps.toInt().coerceIn(1000, 40000)
        )
        updateStreamSettings(restored)
        val slot = SceneSlot.fromName(session.sceneSlotName)
        if (_studioState.value.activeSceneSlot != slot) {
            commitStudioState(StudioWorkspaceReducer.switchScene(_studioState.value, slot), recordUndo = false)
        }
        pendingReuseSessionId = session.id
        showMessage("Loaded '${session.title}' for reconnect (reusing existing session).")
        return RtmpPublisher.validateConfig(
            primaryUrl = restored.effectivePrimaryServerUrl(),
            backupUrl = restored.effectiveBackupServerUrl(),
            streamKey = restored.streamKey
        ).valid
    }

    fun createNewLiveDraft(title: String = "") {
        pendingReuseSessionId = null
        val cur = settings.value.stream
        val nextTitle = title.trim().ifBlank { "Live Broadcast — Zro Live" }
        updateStreamSettings(cur.copy(streamTitle = nextTitle))
        showMessage("Ready to configure new live: $nextTitle")
    }

    fun dismissRecoverableSession() {
        pendingReuseSessionId = null
        viewModelScope.launch { settingsStore.clearActiveLiveSnapshot() }
    }

    fun saveCurrentConfigAsProfile(profileName: String) {
        val cleanName = profileName.trim().ifBlank { "Live Profile ${savedLiveProfiles.value.size + 1}" }
        val cfg = settings.value.stream
        val platformLabel = if (cfg.rtmpUrl.contains("youtube.com", ignoreCase = true)) "YouTube Live" else "Custom RTMP"
        val profile = SavedLiveConfigProfile(
            id = "prof_${System.currentTimeMillis()}",
            name = cleanName,
            title = cfg.streamTitle,
            rtmpUrl = cfg.rtmpUrl.trim(),
            platformLabel = platformLabel,
            resolutionName = cfg.resolution.name,
            fps = cfg.fps,
            bitrateKbps = cfg.bitrateKbps,
            sceneSlotName = _studioState.value.activeSceneSlot.name,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        viewModelScope.launch {
            settingsStore.saveLiveConfigProfile(profile)
            showMessage("Saved live profile '$cleanName'.")
        }
    }

    fun applyLiveProfile(profile: SavedLiveConfigProfile) {
        pendingReuseSessionId = null
        val cur = settings.value.stream
        val updated = cur.copy(
            streamTitle = profile.title.ifBlank { cur.streamTitle },
            rtmpUrl = profile.rtmpUrl,
            resolution = VideoResolution.fromName(profile.resolutionName),
            fps = profile.fps.coerceIn(24, 90),
            bitrateKbps = profile.bitrateKbps.coerceIn(1000, 40000)
        )
        updateStreamSettings(updated)
        val slot = SceneSlot.fromName(profile.sceneSlotName)
        if (_studioState.value.activeSceneSlot != slot) {
            commitStudioState(StudioWorkspaceReducer.switchScene(_studioState.value, slot), recordUndo = false)
        }
        showMessage("Applied live profile '${profile.name}'.")
    }

    fun deleteLiveProfile(profileId: String) {
        viewModelScope.launch {
            settingsStore.deleteLiveConfigProfile(profileId)
            showMessage("Removed saved live profile.")
        }
    }

    fun deleteSessionFromHistory(sessionId: String) {
        viewModelScope.launch {
            settingsStore.deleteLocalSession(sessionId)
            showMessage("Removed session from Live Manager history.")
        }
    }

    fun refreshCrashLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            _crashLogs.value = CrashRecoveryStore.readLogs(appContext)
        }
    }

    fun clearCrashLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            CrashRecoveryStore.clearLogs(appContext)
            _crashLogs.value = emptyList()
        }
        showMessage("Cleared diagnostics logs.")
    }

    fun refreshExternalCameras() {
        viewModelScope.launch(Dispatchers.IO) {
            val inv = ExternalCameraDetector.inspect(appContext)
            _externalCameraInventory.value = inv
        }
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
        val maxBitrate = DeviceTelemetryMonitor.maxAllowedBitrateKbps(
            telemetry = dev,
            profile = settings.value.performance.profile,
            lowRamMode = settings.value.performance.lowRamMode,
            lowDataMode = current.lowDataMode
        )
        if (!DeviceTelemetryMonitor.supportsResolutionAndFps(dev, preset.resolution, preset.fps)) {
            val rec = _smartRecommendation.value
            showMessage("${preset.resolution.shortLabel} ${preset.fps} FPS is not hardware-supported on this device. Applied ${rec.resolution.shortLabel} ${rec.fps} FPS instead.")
            updateStreamSettings(
                current.copy(
                    preset = rec.preset,
                    resolution = rec.resolution,
                    fps = rec.fps,
                    bitrateKbps = rec.bitrateKbps.coerceAtMost(maxBitrate)
                )
            )
            return
        }
        updateStreamSettings(
            current.copy(
                preset = preset,
                resolution = preset.resolution,
                fps = preset.fps,
                bitrateKbps = preset.recommendedBitrateKbps.coerceAtMost(maxBitrate)
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
            if (ZroStreamCoordinator.overlayVisible.value) {
                val refreshIntent = Intent(appContext, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_REFRESH
                }
                runCatching { appContext.startService(refreshIntent) }
            } else {
                startOrRefreshOverlay()
            }
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

    fun restoreFloatingOverlay() {
        if (!Settings.canDrawOverlays(appContext)) {
            showMessage("Grant 'Display over other apps' permission to restore the floating icon.")
            return
        }
        val cur = settings.value.overlay
        if (!cur.enabled) {
            val reenabled = cur.copy(enabled = true)
            ZroStreamCoordinator.currentOverlaySettings = reenabled
            viewModelScope.launch { settingsStore.saveOverlaySettings(reenabled) }
        }
        ZroStreamCoordinator.requestRestoreOverlay(appContext)
        showMessage("Floating Zro Live icon restored.")
    }

    fun importCustomOverlayLogo(uri: Uri) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                StudioMediaStore.importMediaToAppStorage(appContext, uri, isVideoHint = false).getOrNull()
            }
            val finalUri = imported?.persistentUri ?: uri.toString()
            val cur = settings.value.overlay
            val oldUri = cur.customLogoUri
            updateOverlaySettings(cur.copy(customLogoUri = finalUri))
            if (!oldUri.isNullOrBlank() && oldUri != finalUri) {
                withContext(Dispatchers.IO) {
                    StudioMediaStore.deleteIfAppManaged(appContext, oldUri)
                }
            }
            showMessage("Custom floating icon logo applied.")
        }
    }

    fun clearCustomOverlayLogo() {
        val cur = settings.value.overlay
        val oldUri = cur.customLogoUri
        updateOverlaySettings(cur.copy(customLogoUri = null))
        if (!oldUri.isNullOrBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                StudioMediaStore.deleteIfAppManaged(appContext, oldUri)
            }
        }
        showMessage("Restored default Zro Live floating logo.")
    }

    fun stopOverlay() {
        val intent = Intent(appContext, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_HIDE
        }
        runCatching { appContext.startService(intent) }
    }

    /**
     * Adjusts video bitrate live while streaming (or updates saved setup target when offline).
     */
    fun updateLiveBitrate(bitrateKbps: Int) {
        val safeKbps = bitrateKbps.coerceIn(1000, 12000)
        val cur = settings.value.stream
        if (cur.bitrateKbps != safeKbps) {
            updateStreamSettings(cur.copy(bitrateKbps = safeKbps))
        }
        ZroStreamCoordinator.requestUpdateLiveBitrate(appContext, safeKbps)
    }

    fun applyWeakNetworkSuggestedBitrate() {
        val suggested = streamTelemetry.value.weakNetworkSuggestedBitrateKbps ?: return
        updateLiveBitrate(suggested)
        showMessage("Stepping target bitrate to ${String.format(java.util.Locale.US, "%.1f", suggested / 1000f)} Mbps for weak network stability.")
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
        val secondPhoneReady = dualTelemetry.value.linkState == DualLinkState.CONNECTED &&
            dualTelemetry.value.role == DualPhoneRole.PHONE2_STUDIO
        if (!ZroStreamCoordinator.isCaptureActive() && !secondPhoneReady) {
            showMessage("Grant screen capture permission or connect a 2nd phone first.")
            return
        }
        launchBroadcastInternal(resultCode = null, data = null, mode = settings.value.stream.captureMode)
    }

    fun startBroadcastFromSecondPhone() {
        launchBroadcastInternal(resultCode = null, data = null, mode = CaptureMode.FULL_SCREEN)
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
        val configCheck = RtmpPublisher.validateConfig(
            primaryUrl = streamCfg.effectivePrimaryServerUrl(),
            backupUrl = streamCfg.effectiveBackupServerUrl(),
            streamKey = streamCfg.streamKey
        )
        if (!configCheck.valid) {
            showMessage(configCheck.errorMessage ?: "Invalid RTMP streaming settings.")
            return
        }
        if (streamCfg.broadcastMode == LiveBroadcastMode.DUAL_LIVE) {
            val (dualOk, dualReason) = StreamingForegroundService.checkDualLiveSupport(appContext)
            if (!dualOk) {
                showMessage(dualReason)
                return
            }
            val shortsCheck = RtmpPublisher.validateConfig(
                primaryUrl = streamCfg.effectiveShortsServerUrl(),
                backupUrl = "",
                streamKey = streamCfg.shortsStreamKey
            )
            if (!shortsCheck.valid) {
                showMessage("Dual Live needs a valid Shorts 9:16 RTMP URL & Stream Key in Live Setup.")
                return
            }
            if (streamCfg.effectiveShortsServerUrl().equals(streamCfg.effectivePrimaryServerUrl(), ignoreCase = true) &&
                streamCfg.shortsStreamKey.trim() == streamCfg.streamKey.trim()
            ) {
                showMessage("Dual Live requires a separate Stream Key for 9:16 Shorts Live.")
                return
            }
        }
        if (resultCode != null && data != null) {
            ZroStreamCoordinator.pendingMediaProjectionResultCode = resultCode
            ZroStreamCoordinator.pendingMediaProjectionData = data
            ZroStreamCoordinator.pendingCaptureMode = mode
        }

        val reuseId = pendingReuseSessionId
        pendingReuseSessionId = null
        val isReconnect = !reuseId.isNullOrBlank()

        viewModelScope.launch {
            val sessionId = if (isReconnect) {
                reuseId
            } else if (!isGuest) {
                repository!!.createStreamSession(
                    title = streamCfg.streamTitle,
                    description = streamCfg.streamDescription,
                    visibility = streamCfg.visibility.name,
                    resolutionLabel = streamCfg.resolution.shortLabel,
                    fps = streamCfg.fps,
                    bitrateKbps = streamCfg.bitrateKbps
                ).getOrNull() ?: "local_${System.currentTimeMillis()}"
            } else {
                "local_${System.currentTimeMillis()}"
            }

            val svcIntent = Intent(appContext, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_START_STREAM
                putExtra(StreamingForegroundService.EXTRA_SESSION_ID, sessionId)
                putExtra(StreamingForegroundService.EXTRA_IS_RECONNECT, isReconnect)
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

    private fun activeBroadcastMode(): LiveBroadcastMode = settings.value.stream.broadcastMode

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
        ZroStreamCoordinator.updateStudioWorkspace(newState)
        if (persist) {
            val mode = activeBroadcastMode()
            viewModelScope.launch { settingsStore.saveStudioWorkspace(newState, mode) }
        }
    }

    fun undoStudio() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_studioState.value)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _studioState.value = prev
        ZroStreamCoordinator.updateStudioWorkspace(prev)
        val mode = activeBroadcastMode()
        viewModelScope.launch { settingsStore.saveStudioWorkspace(prev, mode) }
        showMessage("Undid last Studio change")
    }

    fun redoStudio() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_studioState.value)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _studioState.value = next
        ZroStreamCoordinator.updateStudioWorkspace(next)
        val mode = activeBroadcastMode()
        viewModelScope.launch { settingsStore.saveStudioWorkspace(next, mode) }
        showMessage("Redid Studio change")
    }

    fun saveScreenLayoutExplicitly() {
        val dt = dualTelemetry.value
        val mode = activeBroadcastMode()
        val stamped = _studioState.value.copy(
            savedLayoutTimestampMs = System.currentTimeMillis(),
            lastAuthorizedDeviceName = dt.connectedDeviceName.ifBlank { _studioState.value.lastAuthorizedDeviceName },
            lastAuthorizedDeviceIp = dt.peerIp.ifBlank { _studioState.value.lastAuthorizedDeviceIp }
        )
        _studioState.value = stamped
        ZroStreamCoordinator.updateStudioWorkspace(stamped)
        viewModelScope.launch {
            settingsStore.saveAuthorizedDeviceLayout(stamped, mode)
            settingsStore.savePreviousStudioBackup(stamped, mode)
            showMessage("Saved ${mode.label} layout & all 4 scenes.")
        }
    }

    fun setAutoRestoreLastLayout(enabled: Boolean) {
        commitStudioState(_studioState.value.copy(autoRestoreLastLayout = enabled), recordUndo = false)
    }

    fun resetEntireLayoutConfirmed() {
        val mode = activeBroadcastMode()
        viewModelScope.launch { settingsStore.savePreviousStudioBackup(_studioState.value, mode) }
        val fresh = StudioWorkspaceState(
            scenes = StudioWorkspaceState.defaultFourScenes(verticalDefault = mode.isVertical),
            autoRestoreLastLayout = _studioState.value.autoRestoreLastLayout,
            remoteTileOrder = _studioState.value.remoteTileOrder,
            remoteTileScale = _studioState.value.remoteTileScale
        )
        commitStudioState(fresh)
        viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(fresh, mode) }
        showMessage("Reset ${mode.label} layout to defaults.")
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
        val mode = activeBroadcastMode()
        viewModelScope.launch {
            settingsStore.savePreviousStudioBackup(_studioState.value, mode)
            settingsStore.saveStudioWorkspace(_studioState.value, mode)
            showMessage("All 4 scenes saved for ${mode.label}!")
        }
    }

    fun restorePreviousSceneBackup() {
        val mode = activeBroadcastMode()
        viewModelScope.launch {
            val restored = settingsStore.restorePreviousStudioBackup(mode)
            if (restored != null) {
                _studioState.value = restored
                ZroStreamCoordinator.updateStudioWorkspace(restored)
                showMessage("Restored previous saved ${mode.label} scene settings.")
            } else {
                showMessage("No previous backup saved yet. Tap 'Save Snapshot' first.")
            }
        }
    }

    fun resetActiveSceneToDefault() {
        val mode = activeBroadcastMode()
        viewModelScope.launch { settingsStore.savePreviousStudioBackup(_studioState.value, mode) }
        val next = StudioWorkspaceReducer.resetActiveSceneToDefault(_studioState.value, verticalDefault = mode.isVertical)
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

    fun addMediaLayerFromUri(
        uriString: String,
        isGif: Boolean = false,
        isVideo: Boolean = false,
        mediaWidth: Int = 0,
        mediaHeight: Int = 0
    ) {
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
        val sceneVertical = _studioState.value.activeScene.isVertical
        val (w, h) = StudioMediaStore.computeNormalizedLayerSize(
            mediaWidthPx = mediaWidth,
            mediaHeightPx = mediaHeight,
            sceneIsVertical = sceneVertical
        )
        val x = ((1f - w) / 2f).coerceIn(0.02f, 0.90f)
        val y = ((1f - h) / 2f).coerceIn(0.02f, 0.90f)
        addLayerSource(
            SceneLayer(
                name = name,
                type = type,
                x = x,
                y = y,
                width = w,
                height = h,
                mediaUri = uriString,
                sourceMediaWidth = mediaWidth,
                sourceMediaHeight = mediaHeight,
                transparentBackground = true,
                animationType = LayerAnimationType.NONE
            )
        )
    }

    fun importAndAddMediaLayer(uri: Uri, isVideoHint: Boolean = false) {
        if (_studioState.value.activeScene.sceneLocked) {
            showMessage("Scene is locked. Unlock scene to add new layers.")
            return
        }
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                StudioMediaStore.importMediaToAppStorage(appContext, uri, isVideoHint = isVideoHint).getOrNull()
            }
            if (imported != null) {
                addMediaLayerFromUri(
                    uriString = imported.persistentUri,
                    isGif = imported.isGif,
                    isVideo = imported.isVideo,
                    mediaWidth = imported.widthPx,
                    mediaHeight = imported.heightPx
                )
            } else {
                val mime = appContext.contentResolver.getType(uri).orEmpty()
                val isGif = mime.contains("gif", ignoreCase = true) || uri.toString().endsWith(".gif", ignoreCase = true)
                addMediaLayerFromUri(uri.toString(), isGif = isGif, isVideo = isVideoHint)
            }
        }
    }

    fun importBackgroundMediaForLayer(layerId: String, uri: Uri) {
        importAndReplaceLayerSource(layerId = layerId, uri = uri, isVideoHint = false)
    }

    fun importAndReplaceLayerSource(layerId: String, uri: Uri, isVideoHint: Boolean = false) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) {
            showMessage("Unlock '${target.name}' before replacing its source.")
            return
        }
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                StudioMediaStore.importMediaToAppStorage(appContext, uri, isVideoHint = isVideoHint).getOrNull()
            }
            val mime = appContext.contentResolver.getType(uri).orEmpty()
            val isGif = imported?.isGif ?: (mime.contains("gif", ignoreCase = true) || uri.toString().endsWith(".gif", ignoreCase = true))
            val isVideo = imported?.isVideo ?: (isVideoHint || mime.startsWith("video/", ignoreCase = true))
            val finalUri = imported?.persistentUri ?: uri.toString()
            val next = StudioWorkspaceReducer.replaceLayerSource(
                state = _studioState.value,
                layerId = layerId,
                mediaUri = finalUri,
                mediaWidth = imported?.widthPx ?: 0,
                mediaHeight = imported?.heightPx ?: 0,
                isGif = isGif,
                isVideo = isVideo
            )
            commitStudioState(next)
            showMessage("Replaced source for '${target.name}'.")
        }
    }

    fun reloadBrowserLayer(layerId: String) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (target.type != LayerSourceType.BROWSER_WEB) return
        val next = StudioWorkspaceReducer.updateLayer(cur, layerId) {
            it.copy(webReloadNonce = it.webReloadNonce + 1)
        }
        commitStudioState(next, persist = false, recordUndo = false)
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

    fun moveLayerTo(layerId: String, rawX: Float, rawY: Float) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) return
        if (!dragSnapshotPushed) {
            pushUndoSnapshot(cur)
            dragSnapshotPushed = true
        }
        val next = StudioWorkspaceReducer.moveLayerTo(cur, layerId, rawX, rawY)
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
        val mode = activeBroadcastMode()
        viewModelScope.launch {
            settingsStore.saveStudioWorkspace(snapshot, mode)
            if (snapshot.autoRestoreLastLayout) {
                settingsStore.saveAuthorizedDeviceLayout(snapshot, mode)
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

    fun resizeLayerExact(layerId: String, newWidth: Float, newHeight: Float) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) return
        if (!dragSnapshotPushed) {
            pushUndoSnapshot(cur)
            dragSnapshotPushed = true
        }
        val next = StudioWorkspaceReducer.resizeLayer(cur, layerId, newWidth, newHeight)
        commitStudioState(next, persist = false, recordUndo = false)
    }

    fun applyLayerCustomSizeUnits(layerId: String, unitsW: Float, unitsH: Float) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked) {
            showMessage("Scene is locked. Unlock scene to resize layers.")
            return
        }
        if (target.locked) {
            showMessage("'${target.name}' is locked. Unlock it first.")
            return
        }
        val next = StudioWorkspaceReducer.applyCustomDimensionUnits(cur, layerId, unitsW, unitsH)
        commitStudioState(next)
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

    fun reorderLayerTopToBottom(fromTopIndex: Int, toTopIndex: Int) {
        commitStudioState(StudioWorkspaceReducer.reorderLayerTopToBottom(_studioState.value, fromTopIndex, toTopIndex))
    }

    fun duplicateLayer(layerId: String) {
        commitStudioState(StudioWorkspaceReducer.duplicateLayer(_studioState.value, layerId))
    }

    fun deleteLayer(layerId: String) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId }
        if (target?.locked == true) {
            showMessage("Unlock '${target.name}' before deleting it.")
            return
        }
        val next = StudioWorkspaceReducer.deleteLayer(cur, layerId)
        commitStudioState(next)
        val removedUri = target?.mediaUri
        if (!removedUri.isNullOrBlank()) {
            val stillReferenced = next.scenes.values.any { scene ->
                scene.layers.any { it.mediaUri == removedUri }
            }
            if (!stillReferenced) {
                viewModelScope.launch(Dispatchers.IO) {
                    StudioMediaStore.deleteIfAppManaged(appContext, removedUri)
                }
            }
        }
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

    fun restoreLayerOriginalSize(layerId: String) {
        val cur = _studioState.value
        val target = cur.activeScene.layers.firstOrNull { it.id == layerId } ?: return
        if (cur.activeScene.sceneLocked || target.locked) {
            showMessage("Unlock '${target.name}' before restoring original size.")
            return
        }
        val next = StudioWorkspaceReducer.restoreLayerOriginalSize(cur, layerId)
        commitStudioState(next)
        if (next.autoRestoreLastLayout) {
            viewModelScope.launch { settingsStore.saveAuthorizedDeviceLayout(next) }
        }
        showMessage("Restored original uncropped size for '${target.name}'.")
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
        val p2pIp = wifiDirectState.value.takeIf { it.groupActive && it.groupOwnerAddress.isNotBlank() }?.groupOwnerAddress
        dualEngine.startPhone2Host(
            hostDeviceName = "Zro Studio (${Build.MODEL})",
            method = dualTelemetry.value.connectionMethod,
            preferredIpOverride = p2pIp
        )
        showMessage("Phone 2 Studio Host active. Scan the QR code or enter IP + Token on Phone 1.")
    }

    fun startWifiDirectHost() {
        wifiDirectHelper.startDirectGroup { ownerIp ->
            dualEngine.startPhone2Host(
                hostDeviceName = "Zro Studio (${Build.MODEL})",
                method = DualConnectionMethod.QR_PAIRING,
                preferredIpOverride = ownerIp
            )
            showMessage("Wi-Fi Direct ready ($ownerIp) — QR code updated for Phone 1.")
        }
    }

    fun stopWifiDirectHost() {
        wifiDirectHelper.stopDirectGroup()
        showMessage("Wi-Fi Direct group stopped.")
    }

    fun probeStunAndRefreshConnectivity() {
        connectivityMonitor.refreshNow()
        viewModelScope.launch {
            val (ok, rtt) = connectivityMonitor.probeStunServer()
            showMessage(
                if (ok) "STUN/ICE server reachable (${rtt} ms)."
                else "STUN/ICE probe failed — verify active internet connection or firewall."
            )
        }
    }

    fun setDualRelayServerUrl(url: String) {
        dualEngine.setRelayServerUrl(url)
    }

    fun startPhone2RelayHost(relayUrl: String) {
        dualEngine.setRelayServerUrl(relayUrl)
        dualEngine.startPhone2RelayHost(
            relayUrl = relayUrl,
            hostDeviceName = "Zro Studio (${Build.MODEL})"
        )
        if (DualPhoneProtocol.isValidRelayUrl(relayUrl)) {
            showMessage("Mobile Data Relay Host connecting — share the Room Token or QR code with Phone 1.")
        } else {
            showMessage("Enter a valid secure wss:// WebSocket relay URL.")
        }
    }

    fun connectPhone1ViaRelay(relayUrl: String, token: String) {
        dualEngine.setRelayServerUrl(relayUrl)
        dualEngine.connectPhone1ViaRelay(
            relayUrl = relayUrl,
            token = token,
            deviceName = "Phone 1 (${Build.MODEL})"
        )
        if (DualPhoneProtocol.isValidRelayUrl(relayUrl) && token.trim().length >= 4) {
            showMessage("Connecting Phone 1 over Mobile Data / Internet Relay…")
        }
    }

    fun connectPhone1ByQrUri(rawUri: String): Boolean {
        val payload = DualPhoneProtocol.parsePairingUri(rawUri)
        if (payload == null) {
            showMessage("Invalid Zro Live QR code. Scan the QR code displayed on Phone 2.")
            return false
        }
        if (payload.relayUrl.isNotBlank()) {
            dualEngine.setRelayServerUrl(payload.relayUrl)
        }
        if (payload.ip.isBlank() && DualPhoneProtocol.isValidRelayUrl(payload.relayUrl)) {
            dualEngine.connectPhone1ViaRelay(
                relayUrl = payload.relayUrl,
                token = payload.token,
                deviceName = "Phone 1 (${Build.MODEL})"
            )
            showMessage("Connecting Phone 1 to ${payload.hostName} via Secure Relay…")
            return true
        }
        dualEngine.connectPhone1ToStudio(
            ip = payload.ip,
            controlPort = payload.controlPort,
            videoPort = payload.videoPort,
            token = payload.token,
            deviceName = "Phone 1 (${Build.MODEL})",
            method = DualConnectionMethod.QR_PAIRING
        )
        showMessage(
            if (payload.relayUrl.isNotBlank()) "Connecting Phone 1 to ${payload.hostName} (LAN with automatic Relay fallback)…"
            else "Connecting Phone 1 to ${payload.hostName} (${payload.ip})…"
        )
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
        connectivityMonitor.stopMonitoring()
    }
}

private data class QuadConnectivityInput(
    val network: com.example.telemetry.NetworkStatusInfo,
    val rtmpTest: RtmpTestResult?,
    val isTestingRtmp: Boolean,
    val stunState: Pair<Boolean?, Long>
)
