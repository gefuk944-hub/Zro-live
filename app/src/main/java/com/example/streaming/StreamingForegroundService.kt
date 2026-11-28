package com.example.streaming

import android.app.Activity
import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.net.wifi.WifiManager
import android.content.ComponentCallbacks2
import com.example.audio.ZroAudioEngine
import com.example.ui.components.StudioImageLoaderProvider
import com.example.data.SettingsStore
import com.example.data.model.CaptureMode
import com.example.data.model.LiveBroadcastMode
import com.example.data.model.LiveVideoSource
import com.example.data.model.PerformanceProfile
import com.example.data.model.StreamHealth
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamOrientation
import com.example.dualphone.DualPhoneEngine
import com.example.studio.DualLinkState
import com.example.studio.DualPhoneRole
import com.example.studio.SceneSlot
import com.example.telemetry.ActiveLiveSessionSnapshot
import com.example.telemetry.CrashRecoveryStore
import com.example.telemetry.DeviceTelemetryMonitor
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lifecycle-safe, resource-efficient Android 14+ Foreground Service executing:
 * - Privacy-first MediaProjection capture preview (live preview of selected game/app window before going live)
 * - Android 14+ MediaProjection.Callback visibility handling (Privacy Shield pauses video immediately when game is hidden/closed)
 * - Hardware-accelerated H.264/AVC encoding with zero per-frame buffer allocations
 * - Dynamic Adaptive Bitrate + Thermal Auto-Protection + Performance Profile caps
 * - Complete teardown of VirtualDisplay, MediaProjection, Encoders, Surfaces, Audio & RTMP socket on stop
 */
class StreamingForegroundService : Service() {

    companion object {
        private const val TAG = "ZroStreamService"
        const val ACTION_START_CAPTURE_PREVIEW = "com.example.zro.ACTION_START_CAPTURE_PREVIEW"
        const val ACTION_UPDATE_PREVIEW_SURFACE = "com.example.zro.ACTION_UPDATE_PREVIEW_SURFACE"
        const val ACTION_STOP_CAPTURE = "com.example.zro.ACTION_STOP_CAPTURE"
        const val ACTION_START_STREAM = "com.example.zro.ACTION_START_STREAM"
        const val ACTION_STOP_STREAM = "com.example.zro.ACTION_STOP_STREAM"
        const val ACTION_TOGGLE_PAUSE = "com.example.zro.ACTION_TOGGLE_PAUSE"
        const val ACTION_TOGGLE_MIC = "com.example.zro.ACTION_TOGGLE_MIC"
        const val ACTION_START_VOICE_FG = "com.example.zro.ACTION_START_VOICE_FG"
        const val ACTION_STOP_VOICE_FG = "com.example.zro.ACTION_STOP_VOICE_FG"
        const val ACTION_SWITCH_VIDEO_SOURCE = "com.example.zro.ACTION_SWITCH_VIDEO_SOURCE"
        const val ACTION_SWITCH_SCENE = "com.example.zro.ACTION_SWITCH_SCENE"
        const val ACTION_UPDATE_LIVE_BITRATE = "com.example.zro.ACTION_UPDATE_LIVE_BITRATE"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_VIDEO_SOURCE = "extra_video_source"
        const val EXTRA_SCENE_SLOT = "extra_scene_slot"
        const val EXTRA_IS_RECONNECT = "extra_is_reconnect"
        const val EXTRA_BITRATE_KBPS = "extra_bitrate_kbps"

        /**
         * Suggests a safer lower bitrate when network uplink degrades (POOR health, rising dropped frames,
         * or measured uplink sustainably below 65% of target). Returns null when network is healthy.
         */
        fun computeWeakNetworkSuggestedBitrate(
            measuredKbps: Int,
            activeTargetKbps: Int,
            health: StreamHealth,
            recentDroppedFrames: Long
        ): Int? {
            if (activeTargetKbps <= 1500) return null
            val uplinkStarved = measuredKbps in 100 until (activeTargetKbps * 0.65f).toInt()
            val degraded = health == StreamHealth.POOR || recentDroppedFrames >= 3L || uplinkStarved
            if (!degraded) return null
            val baseTarget = if (measuredKbps >= 800) {
                (measuredKbps * 0.85f).toInt()
            } else {
                (activeTargetKbps * 0.70f).toInt()
            }
            val rounded = ((baseTarget / 500) * 500).coerceIn(1000, (activeTargetKbps - 500).coerceAtLeast(1000))
            return if (rounded < activeTargetKbps) rounded else null
        }

        /**
         * Aligns YouTube 16:9 / 9:16 broadcast dimensions to strict 16-pixel multiples
         * (e.g., 1280x720 -> 1280x720, 1920x1080 -> 1920x1088) for sharp hardware AVC encoding.
         */
        fun resolveAlignedDimensions(rawW: Int, rawH: Int, portrait: Boolean): Pair<Int, Int> {
            val w = if (portrait) minOf(rawW, rawH) else maxOf(rawW, rawH)
            val h = if (portrait) maxOf(rawW, rawH) else minOf(rawW, rawH)
            val alignedW = ZroStreamingEngine.alignTo16(w).coerceAtLeast(320)
            val alignedH = ZroStreamingEngine.alignTo16(h).coerceAtLeast(192)
            return alignedW to alignedH
        }

        /**
         * Verifies whether this device can genuinely sustain simultaneous Dual Live (16:9 + 9:16)
         * using two concurrent H.264 video encoders. Never fakes support on constrained devices.
         */
        fun checkDualLiveSupport(context: Context): Pair<Boolean, String> {
            return try {
                if (ZroStreamCoordinator.currentPerfSettings.lowRamMode) {
                    return false to "Dual Live is disabled while Low-RAM mode is active. Switch to 16:9 Live or Shorts Live."
                }
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(memInfo)
                val totalRamMb = if (memInfo.totalMem > 0L) memInfo.totalMem / (1024L * 1024L) else 4096L
                if (totalRamMb < 3400L) {
                    return false to "Dual Live requires at least 4 GB RAM (${totalRamMb} MB detected). Use 16:9 Live or Shorts Live."
                }

                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                val avcEncoders = list.codecInfos.filter { info ->
                    info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
                }
                val maxInstances = avcEncoders.maxOfOrNull { info ->
                    runCatching {
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).maxSupportedInstances
                    }.getOrDefault(1)
                } ?: 0

                if (maxInstances >= 2 || avcEncoders.size >= 2) {
                    true to "Supported (${totalRamMb / 1024} GB RAM • concurrent H.264 encoders ready)"
                } else {
                    false to "This device only exposes 1 H.264 encoder instance. Dual Live requires 2 concurrent encoders."
                }
            } catch (e: Exception) {
                false to "Unable to verify dual H.264 encoder capability (${e.localizedMessage ?: "unknown error"})."
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var streamingEngine: ZroStreamingEngine
    private lateinit var notifManager: StreamNotificationManager
    private lateinit var telemetryMonitor: DeviceTelemetryMonitor
    private lateinit var settingsStore: SettingsStore
    private lateinit var liveCompositor: StudioLiveCompositor
    private var streamWakeLock: PowerManager.WakeLock? = null
    private var streamWifiLock: WifiManager.WifiLock? = null

    // Pure GPU Zero-Copy OpenGL ES compositor (VirtualDisplay -> SurfaceTexture OES -> MediaCodec InputSurface)
    private var glCompositor: GlLiveStreamCompositor? = null
    private var glRenderThread: HandlerThread? = null
    @Volatile private var glLoopRunning = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var localCaptureVirtualDisplay: VirtualDisplay? = null
    private var localCaptureImageReader: ImageReader? = null
    private var localCaptureHandlerThread: HandlerThread? = null
    private var mirrorVirtualDisplay: VirtualDisplay? = null
    private var mirrorImageReader: ImageReader? = null
    private var mirrorHandlerThread: HandlerThread? = null
    private var videoEncoder: MediaCodec? = null
    private var videoInputSurface: Surface? = null
    private var audioEncoder: MediaCodec? = null
    private var rtmpPublisher: RtmpPublisher? = null

    // Secondary encoder & RTMP publisher for simultaneous Dual Live (16:9 + 9:16 Shorts)
    private var secondaryVirtualDisplay: VirtualDisplay? = null
    private var secondaryVideoEncoder: MediaCodec? = null
    private var secondaryVideoInputSurface: Surface? = null
    private var secondaryRtmpPublisher: RtmpPublisher? = null
    private var secondaryVideoDrainJob: Job? = null
    private var secondaryCachedSps: ByteArray? = null
    private var secondaryCachedPps: ByteArray? = null
    private var secondaryFirstVideoPtsUs = -1L
    private val isDualLiveActive = AtomicBoolean(false)
    private var activeSecondaryWidth = 720
    private var activeSecondaryHeight = 1280

    private var videoDrainJob: Job? = null
    private var audioDrainJob: Job? = null
    private var silentAudioFallbackJob: Job? = null
    private var monitorJob: Job? = null
    private var liveTimerJob: Job? = null
    private var compositorPumpJob: Job? = null
    private var dualMirrorObserverJob: Job? = null

    private val isStreaming = AtomicBoolean(false)
    private val isLiveConfirmed = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isPrivacyShieldActive = AtomicBoolean(false)
    private val isVoiceForeground = AtomicBoolean(false)
    private val encodedVideoFrames = AtomicLong(0L)
    private val droppedVideoFrames = AtomicLong(0L)
    private val framesInLastSecond = AtomicInteger(0)
    private val peakBitrateKbps = AtomicInteger(0)

    private var activeBitrateKbps = 3800
    private var selectedTargetBitrateKbps = 3800
    private var activeCaptureMode = CaptureMode.FULL_SCREEN
    @Volatile private var activeVideoSource = LiveVideoSource.LOCAL_FULL_SCREEN
    private var activeIsReconnect = false
    private var activeRtmpUrlUsed = ""
    private var activePlatformLabel = "RTMP Live"
    private var streamStartEpochMs = 0L
    private var streamStartElapsedRealtime = 0L
    private var streamClockOriginNs = 0L
    private var activeSessionId: String? = null
    private var activeSessionTitle = "Mobile Gaming Live"
    private var activeSessionVisibility = "PUBLIC"
    private var activeSessionResolutionLabel = "720p"
    @Volatile private var activeSessionFps = 30
    @Volatile private var baseSessionFps = 30
    @Volatile private var thermalFpsThrottledFlag = false
    private var activeStreamWidth = 1280
    private var activeStreamHeight = 720
    private var activeStereoAudio = true
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    private var cachedAsc: ByteArray? = null
    private var firstVideoPtsUs = -1L
    private var firstAudioPtsUs = -1L

    override fun onCreate() {
        super.onCreate()
        streamingEngine = ZroStreamingEngine()
        notifManager = StreamNotificationManager(this)
        telemetryMonitor = DeviceTelemetryMonitor(this)
        settingsStore = SettingsStore(applicationContext)
        liveCompositor = StudioLiveCompositor(applicationContext)
        val initialDev = telemetryMonitor.snapshot()
        ZroStreamCoordinator.updateDeviceTemperature(
            initialDev.batteryTempCelsius,
            DeviceTelemetryMonitor.formatTemperature(initialDev)
        )
        dualMirrorObserverJob = serviceScope.launch {
            DualPhoneEngine.shared.telemetry.collect { dt ->
                val proj = mediaProjection
                // Only spin up a separate mirror VirtualDisplay if localCaptureVirtualDisplay is NOT already running
                if (proj != null &&
                    localCaptureVirtualDisplay == null &&
                    dt.role == DualPhoneRole.PHONE1_GAMEPLAY &&
                    dt.linkState == DualLinkState.CONNECTED &&
                    mirrorVirtualDisplay == null
                ) {
                    startDualPhoneMirrorCaptureIfNeeded(proj)
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Clear non-essential UI bitmap/image caches during BGMI matches while keeping GPU compositor & encoders active
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            StudioImageLoaderProvider.clearMemoryCache()
            if (::liveCompositor.isInitialized) {
                liveCompositor.trimMemory()
            }
            glCompositor?.trimNonEssentialCaches()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && isStreaming.get()) {
            val floor = if (maxOf(activeStreamWidth, activeStreamHeight) >= 1280) {
                ZroStreamingEngine.MIN_ABR_FLOOR_720P_KBPS
            } else {
                1200
            }
            if (activeBitrateKbps > floor) {
                val reduced = (activeBitrateKbps * 0.85f).toInt().coerceAtLeast(floor)
                applyDynamicVideoBitrate(reduced)
                CrashRecoveryStore.recordEvent(
                    applicationContext,
                    "MEMORY",
                    "Trim memory level $level — cleared UI bitmap caches & stepped bitrate to ${reduced} kbps."
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE_PREVIEW -> startCapturePreviewOnly()
            ACTION_UPDATE_PREVIEW_SURFACE -> attachOrDetachPreviewSurface()
            ACTION_STOP_CAPTURE -> stopAllCaptureAndBroadcast(error = null)
            ACTION_START_STREAM -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                val isReconnect = intent.getBooleanExtra(EXTRA_IS_RECONNECT, false)
                startBroadcastSession(sessionId, isReconnect)
            }
            ACTION_SWITCH_VIDEO_SOURCE -> {
                val raw = intent.getStringExtra(EXTRA_VIDEO_SOURCE)
                val target = LiveVideoSource.entries.firstOrNull { it.name == raw } ?: LiveVideoSource.LOCAL_FULL_SCREEN
                switchActiveVideoSource(target)
            }
            ACTION_SWITCH_SCENE -> {
                val raw = intent.getStringExtra(EXTRA_SCENE_SLOT)
                val slot = SceneSlot.fromName(raw)
                // Switch active OBS scene immediately in background without opening MainActivity
                if (ZroStreamCoordinator.activeStudioWorkspace.value.activeSceneSlot != slot) {
                    ZroStreamCoordinator.requestSwitchScene(null, slot)
                }
                if (isStreaming.get()) {
                    requestVideoKeyFrame()
                }
                refreshNotification()
            }
            ACTION_UPDATE_LIVE_BITRATE -> {
                val requested = intent.getIntExtra(EXTRA_BITRATE_KBPS, activeBitrateKbps)
                handleLiveBitrateUpdate(requested)
            }
            ACTION_STOP_STREAM -> stopAllCaptureAndBroadcast(error = null)
            ACTION_TOGGLE_PAUSE -> {
                if (isStreaming.get()) {
                    val nowPaused = !isPaused.get()
                    isPaused.set(nowPaused)
                    StreamStateManager.setPaused(nowPaused)
                    ZroStreamCoordinator.updateTelemetry {
                        it.copy(state = if (nowPaused) StreamLifecycleState.PAUSED else StreamLifecycleState.LIVE)
                    }
                    if (!nowPaused) requestVideoKeyFrame()
                    refreshNotification()
                }
            }
            ACTION_TOGGLE_MIC -> {
                ZroStreamCoordinator.emitAudioQuickAction(ZroStreamCoordinator.AudioQuickAction.TOGGLE_MIC)
                refreshNotification()
            }
            ACTION_START_VOICE_FG -> {
                isVoiceForeground.set(true)
                if (!isStreaming.get() && mediaProjection == null) {
                    promoteForeground(includeMediaProjection = false)
                } else {
                    refreshNotification()
                }
            }
            ACTION_STOP_VOICE_FG -> {
                isVoiceForeground.set(false)
                if (!isStreaming.get() && mediaProjection == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    refreshNotification()
                }
            }
        }
        // Anti-kill foreground persistence while BGMI is in foreground
        return START_STICKY
    }

    private fun promoteForeground(includeMediaProjection: Boolean) {
        val notif = notifManager.buildNotification(
            telemetry = ZroStreamCoordinator.telemetry.value,
            micMuted = !ZroStreamCoordinator.currentAudioSettings.micEnabled,
            voiceRoomName = ZroStreamCoordinator.activeVoiceRoomName.value
        )
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    val type = if (includeMediaProjection) {
                        if (ZroAudioEngine.hasMicPermission(this)) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        } else {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        }
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                    startForeground(StreamNotificationManager.NOTIFICATION_ID_STREAM, notif, type)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    // Android 10 (API 29): FOREGROUND_SERVICE_TYPE_MICROPHONE was added in API 30.
                    // Only FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION is valid on Android 10.
                    if (includeMediaProjection) {
                        startForeground(
                            StreamNotificationManager.NOTIFICATION_ID_STREAM,
                            notif,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                    } else {
                        startForeground(StreamNotificationManager.NOTIFICATION_ID_STREAM, notif)
                    }
                }
                else -> {
                    startForeground(StreamNotificationManager.NOTIFICATION_ID_STREAM, notif)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Foreground promotion error: ${e.message}", e)
            runCatching { startForeground(StreamNotificationManager.NOTIFICATION_ID_STREAM, notif) }
        }
    }

    /**
     * Stage 1: Starts privacy-first capture preview so the user sees the actual captured game/app
     * before tapping Go Live. Releases immediately if cancelled or stopped.
     */
    private fun startCapturePreviewOnly() {
        if (isStreaming.get()) {
            Log.w(TAG, "Cannot start preview while already streaming")
            return
        }
        if (mediaProjection != null) {
            // Replace existing capture cleanly if user picks a different game
            releaseCaptureOnly()
        }

        val resultCode = ZroStreamCoordinator.pendingMediaProjectionResultCode
        val resultData = ZroStreamCoordinator.pendingMediaProjectionData
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            ZroStreamCoordinator.resetToIdle("Game capture permission was not granted.")
            return
        }
        activeCaptureMode = ZroStreamCoordinator.pendingCaptureMode

        if (!ZroStreamCoordinator.tryAcquireCaptureLock()) {
            // Already locked; proceed after previous cleanup
            ZroStreamCoordinator.releaseCaptureLock()
            ZroStreamCoordinator.tryAcquireCaptureLock()
        }

        // Android 14+ requirement: call startForeground(MEDIA_PROJECTION) BEFORE getMediaProjection()
        promoteForeground(includeMediaProjection = true)

        try {
            val projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projMgr.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection could not be initialized.")
            // Clear pending permission intent immediately so it is never retained
            ZroStreamCoordinator.clearPendingMediaProjection()
            mediaProjection = projection
            registerProjectionCallback(projection)

            val settings = ZroStreamCoordinator.currentStreamSettings
            val (width, height) = resolveDimensions(settings.resolution.width, settings.resolution.height, settings.effectiveOrientation())
            val previewSurface = ZroStreamCoordinator.previewSurfaceRef.get()
            if (previewSurface != null) {
                createOrUpdateVirtualDisplay(projection, width, height, previewSurface)
            }
            // Start single unified local screen ImageReader capture (feeds OBS Preview + Live Compositor + Dual-Phone mirror)
            startLocalScreenFrameCapture(projection, width, height)

            val sourceLabel = if (activeCaptureMode == CaptureMode.GAME_APP_ONLY) {
                "Selected Game / App Window (Private)"
            } else {
                "Full Phone Screen (Opt-In)"
            }

            isPrivacyShieldActive.set(false)
            val readyConfig = RtmpPublisher.validateConfig(
                primaryUrl = settings.effectivePrimaryServerUrl(),
                backupUrl = settings.effectiveBackupServerUrl(),
                streamKey = settings.streamKey
            ).valid
            ZroStreamCoordinator.updateTelemetry {
                it.copy(
                    state = StreamLifecycleState.IDLE,
                    statusDetail = if (readyConfig) {
                        "Offline — screen capture preview active. Press START LIVE to connect."
                    } else {
                        "Offline — capture preview active. Configure RTMP URL & Stream Key in Live Setup."
                    },
                    captureActive = true,
                    capturePreviewActive = true,
                    captureMode = activeCaptureMode,
                    captureSourceLabel = sourceLabel,
                    capturedContentVisible = true,
                    privacyShieldActive = false,
                    capturedWidth = width,
                    capturedHeight = height,
                    errorMessage = null
                )
            }
            refreshNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Capture preview init failed: ${e.message}", e)
            stopAllCaptureAndBroadcast(error = e.localizedMessage ?: "Failed to initialize game capture preview.")
        }
    }

    private fun attachOrDetachPreviewSurface() {
        if (isStreaming.get()) return
        val vd = virtualDisplay ?: return
        val surface = ZroStreamCoordinator.previewSurfaceRef.get()
        runCatching { vd.surface = surface }
    }

    private fun registerProjectionCallback(projection: MediaProjection) {
        streamingEngine.registerProjectionCallback(
            projection = projection,
            onStopped = {
                Log.i(TAG, "MediaProjection stopped by system or captured app ended")
                stopAllCaptureAndBroadcast(error = null)
            },
            onResized = { width, height ->
                if (width > 0 && height > 0) {
                    ZroStreamCoordinator.updateTelemetry {
                        it.copy(capturedWidth = width, capturedHeight = height)
                    }
                }
            },
            onVisibilityChanged = { isVisible ->
                val shieldOn = !isVisible
                isPrivacyShieldActive.set(shieldOn)
                Log.i(TAG, "Captured content visibility changed: visible=$isVisible, privacyShield=$shieldOn")
                ZroStreamCoordinator.updateTelemetry {
                    it.copy(
                        capturedContentVisible = isVisible,
                        privacyShieldActive = shieldOn
                    )
                }
                if (isVisible && isStreaming.get() && !isPaused.get()) {
                    requestVideoKeyFrame()
                }
                refreshNotification()
            }
        )
    }

    /**
     * Stage 2: Starts RTMP / YouTube Live broadcast.
     * - Default (1-Phone): captures Phone 1's full screen directly.
     * - 2-Phone Connected: streams the connected 2nd phone's mirrored screen immediately
     *   and supports automatic mid-stream switching between Phone 1 and Phone 2.
     */
    private fun startBroadcastSession(sessionId: String?, isReconnect: Boolean = false) {
        if (!ZroStreamCoordinator.tryAcquireSessionLock()) {
            Log.w(TAG, "Ignored duplicate stream start request")
            return
        }

        val dualState = DualPhoneEngine.shared.telemetry.value
        val secondPhoneAlreadyConnected =
            dualState.linkState == DualLinkState.CONNECTED &&
                dualState.role == DualPhoneRole.PHONE2_STUDIO

        val existingProjection = mediaProjection
        val resultCode = ZroStreamCoordinator.pendingMediaProjectionResultCode
        val resultData = ZroStreamCoordinator.pendingMediaProjectionData
        val hasLocalProjectionGrant = existingProjection != null || (resultCode == Activity.RESULT_OK && resultData != null)

        val settings = ZroStreamCoordinator.currentStreamSettings
        val primaryUrl = settings.effectivePrimaryServerUrl()
        val backupUrl = settings.effectiveBackupServerUrl()
        val isDualMode = settings.broadcastMode == LiveBroadcastMode.DUAL_LIVE
        val shortsUrl = settings.effectiveShortsServerUrl()

        // Step 1: Strict pre-flight validation BEFORE initializing MediaProjection, encoders, or RTMP sockets
        val preflight = streamingEngine.validatePreflight(
            context = this,
            settings = settings,
            hasLocalProjectionGrant = hasLocalProjectionGrant,
            secondPhoneConnected = secondPhoneAlreadyConnected
        )
        if (!preflight.ready) {
            ZroStreamCoordinator.resetToIdle(preflight.errorMessage ?: "Stream pre-flight validation failed.")
            return
        }
        preflight.warnings.forEach { warn ->
            Log.w(TAG, "Preflight notice: $warn")
        }

        val perf = ZroStreamCoordinator.currentPerfSettings
        val devSnapshot = telemetryMonitor.snapshot()
        ZroStreamCoordinator.updateDeviceTemperature(
            devSnapshot.batteryTempCelsius,
            DeviceTelemetryMonitor.formatTemperature(devSnapshot)
        )
        val hardwareAllowedBitrate = DeviceTelemetryMonitor.maxAllowedBitrateKbps(
            telemetry = devSnapshot,
            profile = perf.profile,
            lowRamMode = perf.lowRamMode,
            lowDataMode = settings.lowDataMode
        )
        val effectiveInitialBitrate = settings.bitrateKbps
            .coerceAtMost(hardwareAllowedBitrate)
            .coerceAtLeast(1000)

        activeSessionId = sessionId ?: "live_${System.currentTimeMillis()}"
        activeIsReconnect = isReconnect
        activeRtmpUrlUsed = primaryUrl
        activePlatformLabel = if (primaryUrl.contains("youtube.com", ignoreCase = true)) "YouTube Live" else "Custom RTMP"
        activeSessionTitle = settings.streamTitle
        activeSessionVisibility = settings.visibility.name
        activeSessionResolutionLabel = settings.resolution.shortLabel
        activeSessionFps = when {
            perf.profile == PerformanceProfile.BATTERY_SAVER || perf.lowRamMode || settings.lowDataMode || devSnapshot.isOverheating ->
                minOf(settings.fps, 30)
            settings.fps >= 90 && !devSnapshot.supports90Fps -> 60
            else -> settings.fps.coerceIn(15, 90)
        }
        activeBitrateKbps = effectiveInitialBitrate
        activeStereoAudio = ZroStreamCoordinator.currentAudioSettings.stereoOutput
        if (existingProjection == null) {
            activeCaptureMode = ZroStreamCoordinator.pendingCaptureMode
        }
        activeVideoSource = if (secondPhoneAlreadyConnected) {
            LiveVideoSource.REMOTE_SECOND_PHONE
        } else {
            LiveVideoSource.LOCAL_FULL_SCREEN
        }

        peakBitrateKbps.set(0)
        encodedVideoFrames.set(0L)
        droppedVideoFrames.set(0L)
        framesInLastSecond.set(0)
        isLiveConfirmed.set(false)
        isPaused.set(false)
        streamStartEpochMs = 0L
        cachedSps = null
        cachedPps = null
        cachedAsc = null
        firstVideoPtsUs = -1L
        firstAudioPtsUs = -1L

        val sourceLabel = when (activeVideoSource) {
            LiveVideoSource.REMOTE_SECOND_PHONE -> "2nd Phone Screen (Live Source)"
            LiveVideoSource.LOCAL_FULL_SCREEN -> if (activeCaptureMode == CaptureMode.GAME_APP_ONLY) {
                "Selected App Window"
            } else {
                "This Phone Full Screen"
            }
        }

        ZroStreamCoordinator.updateTelemetry {
            it.copy(
                state = StreamLifecycleState.CONNECTING,
                statusDetail = if (isReconnect) "Reconnecting to previous live session…" else "Initializing encoders & connecting to server…",
                activeServerUrl = primaryUrl,
                usingBackupServer = false,
                videoEncoderReady = false,
                audioEncoderReady = false,
                bytesSentTotal = 0L,
                durationSec = 0L,
                actualBitrateKbps = 0,
                targetBitrateKbps = activeBitrateKbps,
                actualFps = 0,
                targetFps = activeSessionFps,
                resolutionLabel = settings.resolution.shortLabel,
                droppedFrames = 0L,
                totalFrames = 0L,
                streamHealth = StreamHealth.GOOD,
                captureActive = true,
                capturePreviewActive = false,
                captureMode = activeCaptureMode,
                activeVideoSource = activeVideoSource,
                isReconnectedSession = isReconnect,
                captureSourceLabel = sourceLabel,
                thermalThrottled = false,
                errorMessage = null,
                activeSessionId = activeSessionId
            )
        }

        // Android 14+ requirement: call startForeground(MEDIA_PROJECTION) BEFORE getMediaProjection()
        promoteForeground(includeMediaProjection = hasLocalProjectionGrant)

        serviceScope.launch {
            try {
                // Persist active session snapshot for crash recovery (never writes stream key!)
                persistRecoverySnapshot(cleanlyEnded = false, lastError = null)
                if (isReconnect) {
                    CrashRecoveryStore.recordEvent(
                        applicationContext,
                        "RECOVERY",
                        "Reconnecting to previous live session (${activeSessionId ?: "unknown"})."
                    )
                }

                // Obtain or reuse MediaProjection strictly on ZroLiveEngineThread (guaranteed active Looper!)
                val projection: MediaProjection? = existingProjection ?: if (resultCode == Activity.RESULT_OK && resultData != null) {
                    val p = streamingEngine.acquireMediaProjection(
                        context = this@StreamingForegroundService,
                        resultCode = resultCode,
                        resultData = resultData,
                        onStopped = {
                            Log.i(TAG, "MediaProjection stopped by system or captured app ended")
                            stopAllCaptureAndBroadcast(error = null)
                        },
                        onResized = { width, height ->
                            if (width > 0 && height > 0) {
                                ZroStreamCoordinator.updateTelemetry {
                                    it.copy(capturedWidth = width, capturedHeight = height)
                                }
                            }
                        },
                        onVisibilityChanged = { isVisible ->
                            val shieldOn = !isVisible
                            isPrivacyShieldActive.set(shieldOn)
                            Log.i(TAG, "Captured content visibility changed: visible=$isVisible, privacyShield=$shieldOn")
                            ZroStreamCoordinator.updateTelemetry {
                                it.copy(
                                    capturedContentVisible = isVisible,
                                    privacyShieldActive = shieldOn
                                )
                            }
                            if (isVisible && isStreaming.get() && !isPaused.get()) {
                                requestVideoKeyFrame()
                            }
                            refreshNotification()
                        }
                    )
                    ZroStreamCoordinator.clearPendingMediaProjection()
                    ZroStreamCoordinator.tryAcquireCaptureLock()
                    mediaProjection = p
                    p
                } else {
                    null
                }

                // Step 2: Connect to RTMP/RTMPS server FIRST so SPS/PPS, AAC sequence header,
                // and the initial IDR keyframe are sent immediately with zero startup loss.
                ZroStreamCoordinator.updateTelemetry {
                    it.copy(
                        state = StreamLifecycleState.CONNECTING,
                        statusDetail = "Handshaking with streaming server…"
                    )
                }
                val publisher = RtmpPublisher()
                rtmpPublisher = publisher
                val connectResult = publisher.connectWithFailover(
                    primaryUrl = primaryUrl,
                    backupUrl = backupUrl,
                    streamKey = settings.streamKey,
                    preferBackup = settings.useBackupServer
                )
                if (connectResult.isFailure) {
                    val msg = connectResult.exceptionOrNull()?.localizedMessage
                        ?: "Unable to connect to RTMP server. Verify your Stream RTMP URL, Stream Key, and internet connection."
                    stopAllCaptureAndBroadcast(error = msg)
                    return@launch
                }

                val connInfo = connectResult.getOrThrow()
                val (width, height) = resolveDimensions(settings.resolution.width, settings.resolution.height, settings.effectiveOrientation())
                activeStreamWidth = width
                activeStreamHeight = height

                ZroStreamCoordinator.updateTelemetry {
                    it.copy(
                        state = StreamLifecycleState.CONNECTED,
                        activeServerUrl = connInfo.serverUrl,
                        usingBackupServer = connInfo.usedBackup,
                        statusDetail = "Connected (${connInfo.latencyMs} ms). Starting H.264 video & AAC audio encoders…"
                    )
                }
                refreshNotification()

                // Step 3: Send onMetaData & initial AAC sequence header immediately after publish confirmation
                publisher.sendMetaData(
                    width = width,
                    height = height,
                    fps = activeSessionFps,
                    videoBitrateKbps = activeBitrateKbps,
                    audioSampleRate = ZroAudioEngine.SAMPLE_RATE,
                    audioBitrateKbps = if (activeStereoAudio) 128 else 96,
                    stereo = activeStereoAudio
                )
                val fallbackAsc = RtmpPublisher.buildAacAudioSpecificConfig(ZroAudioEngine.SAMPLE_RATE, activeStereoAudio)
                cachedAsc = fallbackAsc
                publisher.sendAacSequenceHeader(fallbackAsc)

                // Acquire CPU partial wake lock + high-performance Wi-Fi lock for anti-kill BGMI stability
                acquireBroadcastWakeLock()

                // Step 4: Initialize Hardware H.264 Video Encoder & Single Monotonic A/V Clock
                selectedTargetBitrateKbps = activeBitrateKbps
                baseSessionFps = activeSessionFps
                thermalFpsThrottledFlag = false
                streamClockOriginNs = System.nanoTime()
                val encoderName = setupVideoEncoder(width, height, activeSessionFps, activeBitrateKbps)
                releaseVirtualDisplayOnly()
                // Stop any preview-only CPU ImageReader so live encoding uses pure GPU zero-copy VirtualDisplay -> OES
                stopLocalScreenFrameCapture()

                // Step 4B: If Dual Live (16:9 + 9:16), initialize separate secondary portrait H.264 encoder & RTMP publisher
                isDualLiveActive.set(false)
                secondaryCachedSps = null
                secondaryCachedPps = null
                secondaryFirstVideoPtsUs = -1L
                if (isDualMode) {
                    ZroStreamCoordinator.updateTelemetry {
                        it.copy(statusDetail = "16:9 connected. Connecting 9:16 Shorts Live secondary encoder…")
                    }
                    val secPub = RtmpPublisher()
                    secondaryRtmpPublisher = secPub
                    val secConnect = secPub.connectWithFailover(
                        primaryUrl = shortsUrl,
                        backupUrl = "",
                        streamKey = settings.shortsStreamKey,
                        preferBackup = false
                    )
                    if (secConnect.isFailure) {
                        val secErr = secConnect.exceptionOrNull()?.localizedMessage ?: "Secondary Shorts RTMP connection failed."
                        stopAllCaptureAndBroadcast(error = "Dual Live Shorts RTMP failed: $secErr")
                        return@launch
                    }
                    val (secRawW, secRawH) = settings.effectiveSecondaryShortsDimensions()
                    val (secW, secH) = resolveAlignedDimensions(secRawW, secRawH, portrait = true)
                    activeSecondaryWidth = secW
                    activeSecondaryHeight = secH
                    val secFps = minOf(activeSessionFps, 30)
                    val secBitrate = (activeBitrateKbps * 0.65f).toInt().coerceIn(1500, 6000)
                    secPub.sendMetaData(
                        width = secW,
                        height = secH,
                        fps = secFps,
                        videoBitrateKbps = secBitrate,
                        audioSampleRate = ZroAudioEngine.SAMPLE_RATE,
                        audioBitrateKbps = if (activeStereoAudio) 128 else 96,
                        stereo = activeStereoAudio
                    )
                    secPub.sendAacSequenceHeader(fallbackAsc)
                    setupSecondaryVideoEncoder(secW, secH, secFps, secBitrate)
                    isDualLiveActive.set(true)
                }

                ZroStreamCoordinator.updateTelemetry {
                    it.copy(
                        activeEncoderName = if (isDualLiveActive.get()) "$encoderName + Dual 9:16" else encoderName,
                        videoEncoderReady = true,
                        statusDetail = if (isDualLiveActive.get()) {
                            "Dual Live encoders active (16:9 + 9:16). Starting AAC audio mixer…"
                        } else {
                            "Video encoder active ($encoderName). Starting AAC audio mixer…"
                        }
                    )
                }

                // Step 5: Initialize AAC Audio Encoder & attach unified ZroAudioEngine mixer with locked stereo/mono parity
                setupAudioEncoder(activeStereoAudio)
                ZroAudioEngine.applyPerformanceSettings(perf)
                val hasMic = ZroAudioEngine.hasMicPermission(this@StreamingForegroundService)
                ZroAudioEngine.startStreamingAudio(
                    context = this@StreamingForegroundService,
                    mediaProjection = projection,
                    forceStereo = activeStereoAudio
                )
                if (hasMic) {
                    ZroAudioEngine.setBroadcastPcmConsumer { pcmBytes, length, ptsUs ->
                        if (!isPaused.get() && isStreaming.get()) {
                            feedPcmToAacEncoder(pcmBytes, length, ptsUs)
                        }
                    }
                } else {
                    // Keep YouTube Live audio track alive with valid silent AAC frames if mic permission is denied
                    startSilentAudioFallbackLoop(activeStereoAudio)
                }

                isStreaming.set(true)
                // Anchor authoritative monotonic Foreground Service timer immediately on RTMP start
                streamStartEpochMs = System.currentTimeMillis()
                streamStartElapsedRealtime = SystemClock.elapsedRealtime()
                StreamStateManager.markStreamStarted(
                    startElapsedRealtime = streamStartElapsedRealtime,
                    targetBitrateKbps = selectedTargetBitrateKbps
                )
                startLiveTimerLoop()

                ZroStreamCoordinator.updateTelemetry {
                    it.copy(
                        state = StreamLifecycleState.SENDING_VIDEO,
                        audioEncoderReady = true,
                        gameAudioCaptureActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projection != null,
                        gameAudioCaptureNote = ZroAudioEngine.gameCaptureStatus.value,
                        statusDetail = if (isDualLiveActive.get()) {
                            "Sending Dual Live (16:9 + 9:16 Shorts) H.264 & AAC…"
                        } else {
                            "Sending GPU Zero-Copy H.264 video & AAC audio…"
                        }
                    )
                }
                refreshNotification()

                // Start pure GPU Zero-Copy OpenGL compositor (VirtualDisplay -> SurfaceTexture OES -> MediaCodec InputSurface)
                // Falls back to StudioLiveCompositor hardware canvas only if EGL is unavailable or Dual Live secondary surface needs canvas
                startStudioCompositorLoop(projection, width, height, lockLandscape16x9 = !settings.broadcastMode.isVertical)

                // Request an immediate IDR keyframe and begin draining encoded H.264 + AAC frames
                requestVideoKeyFrame()
                startVideoDrainLoop()
                if (isDualLiveActive.get()) {
                    startSecondaryVideoDrainLoop()
                }
                startAudioDrainLoop()
                startTelemetryAndStabilityLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start broadcast: ${e.message}", e)
                CrashRecoveryStore.recordEvent(applicationContext, "ENCODER", "Broadcast init error: ${e.localizedMessage}")
                stopAllCaptureAndBroadcast(error = e.localizedMessage ?: "Broadcast initialization failed.")
            }
        }
    }

    /**
     * Switches live video source on the fly without disconnecting RTMP/YouTube Live:
     * Because StudioLiveCompositor pulls from the active video source every frame,
     * switching between 2nd Phone and Auto Solo local screen is seamless with zero RTMP drop.
     */
    private fun switchActiveVideoSource(target: LiveVideoSource) {
        if (activeVideoSource == target) return
        activeVideoSource = target
        if (!isStreaming.get()) {
            ZroStreamCoordinator.updateTelemetry {
                it.copy(
                    activeVideoSource = target,
                    captureSourceLabel = if (target == LiveVideoSource.REMOTE_SECOND_PHONE) {
                        "2nd Phone Screen (Live Source)"
                    } else {
                        "This Phone Full Screen"
                    }
                )
            }
            return
        }

        if (target == LiveVideoSource.REMOTE_SECOND_PHONE) {
            requestVideoKeyFrame()
            ZroStreamCoordinator.updateTelemetry {
                it.copy(
                    activeVideoSource = LiveVideoSource.REMOTE_SECOND_PHONE,
                    captureSourceLabel = "2nd Phone Screen (Auto-Switched)",
                    statusDetail = "Live — restored 2nd phone gameplay capture"
                )
            }
        } else {
            val proj = mediaProjection
            if (proj != null && localCaptureVirtualDisplay == null) {
                startLocalScreenFrameCapture(proj, activeStreamWidth, activeStreamHeight)
            }
            requestVideoKeyFrame()
            ZroStreamCoordinator.updateTelemetry {
                it.copy(
                    activeVideoSource = LiveVideoSource.LOCAL_FULL_SCREEN,
                    captureSourceLabel = if (proj != null) {
                        "This Phone Screen (Auto Solo Mode)"
                    } else {
                        "Auto Solo — Standby (Grant screen capture)"
                    },
                    statusDetail = if (proj != null) {
                        "Auto Solo Mode: switched to this phone's screen capture"
                    } else {
                        "2nd phone disconnected. Grant screen capture or reconnect 2nd phone."
                    }
                )
            }
        }
        refreshNotification()
    }

    /**
     * Captures this phone's real MediaProjection display frames via ImageReader on a dedicated HandlerThread.
     * Feeds BOTH StudioCanvasPreview (OBS Preview) and StudioLiveCompositor (Live RTMP H.264 output).
     */
    private fun startLocalScreenFrameCapture(projection: MediaProjection, targetW: Int, targetH: Int) {
        stopLocalScreenFrameCapture()
        runCatching {
            // Keep capture buffer crisp yet memory-bounded for smooth 30-60+ fps compositing
            val capW = targetW.coerceAtMost(1920)
            val capH = targetH.coerceAtMost(1080)
            val thread = HandlerThread("ZroLocalCapture").apply { start() }
            localCaptureHandlerThread = thread
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
            localCaptureImageReader = reader

            // Double-buffered bitmaps + pre-bound Canvas eliminate per-frame allocations & reduce GC heat
            var writeIndex = 0
            val frontBuffers = arrayOfNulls<Bitmap>(2)
            val frontCanvases = arrayOfNulls<Canvas>(2)
            var paddedTmp: Bitmap? = null
            val srcCrop = Rect(0, 0, capW, capH)
            val dstCrop = Rect(0, 0, capW, capH)

            // Reuse a single downscaled mirror buffer when acting as Dual-Phone Phone 1 (eliminates a 2nd VirtualDisplay!)
            val mirrorW = 854
            val mirrorH = 480
            var mirrorScaledBmp: Bitmap? = null
            var mirrorScaledCanvas: Canvas? = null
            val mirrorDstRect = Rect(0, 0, mirrorW, mirrorH)
            val mirrorBaos = ByteArrayOutputStream(48 * 1024)
            var lastMirrorPushMs = 0L

            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (isPrivacyShieldActive.get()) return@setOnImageAvailableListener
                    val plane = img.planes.firstOrNull() ?: return@setOnImageAvailableListener
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * capW
                    val paddedW = capW + rowPadding / pixelStride

                    val nextIdx = (writeIndex + 1) % 2
                    var outBmp = frontBuffers[nextIdx]
                    if (outBmp == null || outBmp.isRecycled || outBmp.width != capW || outBmp.height != capH) {
                        outBmp = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888)
                        frontBuffers[nextIdx] = outBmp
                        frontCanvases[nextIdx] = Canvas(outBmp)
                    }

                    if (paddedW == capW) {
                        outBmp.copyPixelsFromBuffer(plane.buffer)
                    } else {
                        var tmp = paddedTmp
                        if (tmp == null || tmp.isRecycled || tmp.width != paddedW || tmp.height != capH) {
                            tmp = Bitmap.createBitmap(paddedW, capH, Bitmap.Config.ARGB_8888)
                            paddedTmp = tmp
                        }
                        tmp.copyPixelsFromBuffer(plane.buffer)
                        val dstCanvas = frontCanvases[nextIdx] ?: Canvas(outBmp).also { frontCanvases[nextIdx] = it }
                        dstCanvas.drawBitmap(tmp, srcCrop, dstCrop, null)
                    }
                    writeIndex = nextIdx
                    ZroStreamCoordinator.updateLocalCapturedFrame(outBmp)

                    // Unified Dual-Phone mirror push (~15 FPS) without running a duplicate VirtualDisplay
                    val dt = DualPhoneEngine.shared.telemetry.value
                    if (dt.role == DualPhoneRole.PHONE1_GAMEPLAY &&
                        dt.linkState == DualLinkState.CONNECTED &&
                        !isPaused.get()
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastMirrorPushMs >= 66L) {
                            lastMirrorPushMs = now
                            var mBmp = mirrorScaledBmp
                            if (mBmp == null || mBmp.isRecycled) {
                                mBmp = Bitmap.createBitmap(mirrorW, mirrorH, Bitmap.Config.ARGB_8888)
                                mirrorScaledBmp = mBmp
                                mirrorScaledCanvas = Canvas(mBmp)
                            }
                            mirrorScaledCanvas?.drawBitmap(outBmp, srcCrop, mirrorDstRect, null)
                            mirrorBaos.reset()
                            mBmp.compress(Bitmap.CompressFormat.JPEG, 68, mirrorBaos)
                            DualPhoneEngine.shared.pushMirroredFrameJpeg(mirrorBaos.toByteArray(), mirrorW, mirrorH, minIntervalMs = 66L)
                        }
                    }
                } catch (_: Exception) {
                    // Best-effort frame acquisition
                } finally {
                    runCatching { img.close() }
                }
            }, handler)

            localCaptureVirtualDisplay = projection.createVirtualDisplay(
                "ZroLocalScreenReader",
                capW,
                capH,
                resolveDensityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        }.onFailure { e ->
            Log.w(TAG, "Local screen frame capture init warning: ${e.message}")
            stopLocalScreenFrameCapture()
        }
    }

    private fun stopLocalScreenFrameCapture() {
        runCatching { localCaptureVirtualDisplay?.release() }; localCaptureVirtualDisplay = null
        runCatching { localCaptureImageReader?.close() }; localCaptureImageReader = null
        runCatching { localCaptureHandlerThread?.quitSafely() }; localCaptureHandlerThread = null
    }

    /**
     * Dedicated 500ms monotonic live timer running strictly inside [StreamingForegroundService].
     * Publishes `HH:MM:SS` to [StreamStateManager] (Start Live, Remote Control, Floating Overlay,
     * and Persistent Notification) so the timer never freezes at `00:00:02` when switching to BGMI.
     */
    private fun startLiveTimerLoop() {
        liveTimerJob?.cancel()
        liveTimerJob = serviceScope.launch {
            var tickCount = 0
            while (isActive && isStreaming.get()) {
                val snap = StreamStateManager.tick(SystemClock.elapsedRealtime())
                ZroStreamCoordinator.updateTelemetry { prev ->
                    if (prev.durationSec == snap.elapsedSeconds) prev else prev.copy(durationSec = snap.elapsedSeconds)
                }
                tickCount++
                // Update persistent notification smoothly every 1s (every 2x 500ms ticks)
                if (tickCount % 2 == 0) {
                    refreshNotification()
                }
                delay(StreamStateManager.TIMER_TICK_INTERVAL_MS)
            }
        }
    }

    /**
     * Pure GPU Zero-Copy Broadcast Compositor Loop:
     *   `VirtualDisplay` -> `SurfaceTexture (OES)` -> `GlLiveStreamCompositor` -> `MediaCodec.createInputSurface()`
     *
     * Strictly paced to `activeSessionFps` (30 FPS / 33.3ms default) with zero per-frame allocations
     * and monotonic `System.nanoTime()` presentation timestamps synced with [ZroAudioEngine].
     * Removes CPU `ImageReader` / `Bitmap.compress()` from the live encoding path.
     */
    private fun startStudioCompositorLoop(
        projection: MediaProjection? = mediaProjection,
        width: Int = activeStreamWidth,
        height: Int = activeStreamHeight,
        lockLandscape16x9: Boolean = width >= height
    ) {
        stopStudioCompositorLoop()
        val primarySurface = videoInputSurface ?: return

        val thread = HandlerThread("ZroGlCompositorThread").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        glRenderThread = thread
        val glHandler = Handler(thread.looper)
        glLoopRunning = true

        glHandler.post {
            val compositor = GlLiveStreamCompositor(
                context = applicationContext,
                outputWidth = width,
                outputHeight = height,
                targetFps = activeSessionFps.coerceIn(15, 60),
                lockLandscape16x9 = lockLandscape16x9
            )
            val glReady = compositor.initialize(primarySurface, glHandler)
            if (glReady) {
                glCompositor = compositor
                val vdSurface = compositor.virtualDisplaySurface
                if (projection != null && vdSurface != null) {
                    streamingEngine.postOnEngineThread {
                        if (isStreaming.get()) {
                            createOrUpdateVirtualDisplay(projection, width, height, vdSurface)
                        }
                    }
                }
                var nextFrameDeadlineNs = System.nanoTime()
                val renderTick = object : Runnable {
                    override fun run() {
                        if (!glLoopRunning || !isStreaming.get()) {
                            compositor.release()
                            if (glCompositor === compositor) glCompositor = null
                            return
                        }
                        val workspace = ZroStreamCoordinator.activeStudioWorkspace.value
                        val isStaticScene = isPrivacyShieldActive.get() ||
                            workspace.brbActive ||
                            workspace.activeSceneSlot != SceneSlot.GAMEPLAY_LIVE
                        val targetFps = if (isStaticScene) {
                            minOf(activeSessionFps, 12).coerceAtLeast(10)
                        } else {
                            activeSessionFps.coerceIn(15, 60)
                        }
                        val frameIntervalNs = 1_000_000_000L / targetFps

                        if (!isPaused.get()) {
                            val remoteFrame = if (activeVideoSource == LiveVideoSource.REMOTE_SECOND_PHONE) {
                                DualPhoneEngine.shared.mirroredFrame.value
                            } else {
                                null
                            }
                            val nowNs = System.nanoTime()
                            compositor.onDrawFrame(
                                presentationTimeNs = nowNs,
                                workspace = workspace,
                                privacyShieldActive = isPrivacyShieldActive.get(),
                                remoteGameplayBitmap = remoteFrame
                            )
                        }

                        nextFrameDeadlineNs += frameIntervalNs
                        val nowAfter = System.nanoTime()
                        if (nextFrameDeadlineNs < nowAfter - frameIntervalNs) {
                            nextFrameDeadlineNs = nowAfter + frameIntervalNs
                        }
                        val delayMs = ((nextFrameDeadlineNs - nowAfter) / 1_000_000L).coerceIn(1L, 100L)
                        glHandler.postDelayed(this, delayMs)
                    }
                }
                glHandler.post(renderTick)
            } else {
                compositor.release()
                // Headless / non-EGL fallback (e.g., Robolectric tests or unsupported GPU driver)
                if (projection != null && localCaptureVirtualDisplay == null) {
                    startLocalScreenFrameCapture(projection, width, height)
                }
                startHardwareCanvasFallbackLoop(primarySurface)
            }
        }

        // Secondary 9:16 Shorts surface pump when Dual Live is active
        if (isDualLiveActive.get() && secondaryVideoInputSurface != null) {
            startSecondaryDualCanvasLoop()
        }
    }

    private fun startHardwareCanvasFallbackLoop(primarySurface: Surface) {
        compositorPumpJob?.cancel()
        compositorPumpJob = serviceScope.launch {
            while (isActive && isStreaming.get()) {
                val frameStartMs = System.currentTimeMillis()
                val workspace = ZroStreamCoordinator.activeStudioWorkspace.value
                val effectiveFps = activeSessionFps.coerceIn(15, 60)
                val targetIntervalMs = (1000L / effectiveFps).coerceAtLeast(16L)

                if (!isPaused.get()) {
                    val meters = ZroAudioEngine.meterState.value
                    val chat = ZroStreamCoordinator.liveChatMessages.value
                    val remoteFrame = DualPhoneEngine.shared.mirroredFrame.value
                    val localFrame = ZroStreamCoordinator.localCapturedFrame.value
                    val gameplayFrame = if (activeVideoSource == LiveVideoSource.REMOTE_SECOND_PHONE) {
                        remoteFrame ?: localFrame
                    } else {
                        localFrame ?: remoteFrame
                    }
                    try {
                        if (!primarySurface.isValid) break
                        val canvas = primarySurface.lockHardwareCanvas()
                        try {
                            liveCompositor.renderFrame(
                                canvas = canvas,
                                workspace = workspace,
                                gameplayBitmap = gameplayFrame,
                                audioMeters = meters,
                                chatMessages = chat,
                                privacyShieldActive = isPrivacyShieldActive.get(),
                                forceVerticalSceneOverride = false
                            )
                        } finally {
                            primarySurface.unlockCanvasAndPost(canvas)
                        }
                    } catch (_: Exception) {
                        delay(24L)
                    }
                }
                val elapsed = System.currentTimeMillis() - frameStartMs
                delay((targetIntervalMs - elapsed).coerceAtLeast(4L))
            }
        }
    }

    private fun startSecondaryDualCanvasLoop() {
        val secSurface = secondaryVideoInputSurface ?: return
        compositorPumpJob = serviceScope.launch {
            while (isActive && isStreaming.get() && isDualLiveActive.get()) {
                val frameStartMs = System.currentTimeMillis()
                val targetIntervalMs = (1000L / minOf(activeSessionFps, 30).coerceAtLeast(15))
                if (!isPaused.get() && secSurface.isValid) {
                    val workspace = ZroStreamCoordinator.activeStudioWorkspace.value
                    val gameplayFrame = DualPhoneEngine.shared.mirroredFrame.value
                        ?: ZroStreamCoordinator.localCapturedFrame.value
                    try {
                        val secCanvas = secSurface.lockHardwareCanvas()
                        try {
                            liveCompositor.renderFrame(
                                canvas = secCanvas,
                                workspace = workspace,
                                gameplayBitmap = gameplayFrame,
                                audioMeters = ZroAudioEngine.meterState.value,
                                chatMessages = ZroStreamCoordinator.liveChatMessages.value,
                                privacyShieldActive = isPrivacyShieldActive.get(),
                                forceVerticalSceneOverride = true
                            )
                        } finally {
                            secSurface.unlockCanvasAndPost(secCanvas)
                        }
                    } catch (_: Exception) {
                        delay(24L)
                    }
                }
                val elapsed = System.currentTimeMillis() - frameStartMs
                delay((targetIntervalMs - elapsed).coerceAtLeast(4L))
            }
        }
    }

    private fun stopStudioCompositorLoop() {
        glLoopRunning = false
        compositorPumpJob?.cancel()
        compositorPumpJob = null
        val thread = glRenderThread
        glRenderThread = null
        val comp = glCompositor
        glCompositor = null
        if (thread != null && thread.isAlive) {
            runCatching {
                Handler(thread.looper).post {
                    runCatching { comp?.release() }
                    runCatching { thread.quitSafely() }
                }
            }
        } else {
            runCatching { comp?.release() }
            runCatching { thread?.quitSafely() }
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireBroadcastWakeLock() {
        runCatching {
            if (streamWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                streamWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZroLive:BroadcastWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (streamWakeLock?.isHeld != true) {
                streamWakeLock?.acquire(6 * 60 * 60 * 1000L) // Max 6h safety timeout
            }
        }
        runCatching {
            if (streamWifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                streamWifiLock = runCatching {
                    wm?.createWifiLock(lockMode, "ZroLive:BroadcastWifiLock")
                }.getOrNull() ?: wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ZroLive:BroadcastWifiLock")
                streamWifiLock?.setReferenceCounted(false)
            }
            if (streamWifiLock?.isHeld != true) {
                streamWifiLock?.acquire()
            }
        }
    }

    private fun releaseBroadcastWakeLock() {
        runCatching {
            if (streamWakeLock?.isHeld == true) {
                streamWakeLock?.release()
            }
        }
        runCatching {
            if (streamWifiLock?.isHeld == true) {
                streamWifiLock?.release()
            }
        }
    }

    private fun releaseVirtualDisplayOnly() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
    }

    private suspend fun persistRecoverySnapshot(cleanlyEnded: Boolean, lastError: String?) {
        val sid = activeSessionId ?: return
        val cfg = ZroStreamCoordinator.currentStreamSettings
        val now = System.currentTimeMillis()
        val elapsed = if (streamStartEpochMs > 0L) (now - streamStartEpochMs) / 1000L else 0L
        runCatching {
            settingsStore.saveActiveLiveSnapshot(
                ActiveLiveSessionSnapshot(
                    sessionId = sid,
                    title = activeSessionTitle,
                    rtmpUrl = activeRtmpUrlUsed.ifBlank { cfg.effectivePrimaryServerUrl() },
                    platformLabel = activePlatformLabel,
                    resolutionName = cfg.resolution.name,
                    fps = activeSessionFps,
                    bitrateKbps = activeBitrateKbps,
                    activeSceneSlotName = ZroStreamCoordinator.activeSceneSlotName,
                    startedAtEpochMs = if (streamStartEpochMs > 0L) streamStartEpochMs else now,
                    lastUpdatedEpochMs = now,
                    durationSec = elapsed,
                    cleanlyEnded = cleanlyEnded,
                    lastError = lastError?.let { CrashRecoveryStore.scrubSecrets(it) }
                )
            )
        }
    }

    private fun hasActiveInternetConnection(): Boolean = streamingEngine.checkActiveInternet(this)

    private fun resolveDimensions(rawW: Int, rawH: Int, orientation: StreamOrientation): Pair<Int, Int> {
        val portrait = when (orientation) {
            StreamOrientation.PORTRAIT -> true
            StreamOrientation.LANDSCAPE -> false
            StreamOrientation.AUTO -> resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        return resolveAlignedDimensions(rawW, rawH, portrait)
    }

    private fun setupVideoEncoder(width: Int, height: Int, fps: Int, bitrateKbps: Int): String {
        val handle = streamingEngine.createVideoEncoder(
            width = width,
            height = height,
            fps = fps,
            bitrateKbps = bitrateKbps,
            preferHardware = true
        )
        videoInputSurface = handle.inputSurface
        videoEncoder = handle.codec
        return handle.encoderName
    }

    private fun setupSecondaryVideoEncoder(width: Int, height: Int, fps: Int, bitrateKbps: Int): String {
        val handle = streamingEngine.createVideoEncoder(
            width = width,
            height = height,
            fps = fps,
            bitrateKbps = bitrateKbps,
            preferHardware = true
        )
        secondaryVideoInputSurface = handle.inputSurface
        secondaryVideoEncoder = handle.codec
        return handle.encoderName
    }

    private fun createSecondaryVirtualDisplay(projection: MediaProjection, width: Int, height: Int, surface: Surface?) {
        secondaryVirtualDisplay = streamingEngine.createOrUpdateVirtualDisplay(
            projection = projection,
            name = "ZroLiveDualShortsCapture",
            width = width,
            height = height,
            densityDpi = resolveDensityDpi(),
            surface = surface,
            existing = secondaryVirtualDisplay
        )
    }

    private fun startSecondaryVideoDrainLoop() {
        secondaryVideoDrainJob?.cancel()
        secondaryVideoDrainJob = serviceScope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            var reusableNalBuf = ByteArray(192 * 1024)
            while (isActive && isStreaming.get() && isDualLiveActive.get()) {
                val codec = secondaryVideoEncoder ?: break
                try {
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 20_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val fmt = codec.outputFormat
                            val csd0 = fmt.getByteBuffer("csd-0")
                            val csd1 = fmt.getByteBuffer("csd-1")
                            if (csd0 != null) {
                                val b0 = duplicateToBytes(csd0)
                                val (s0, p0) = RtmpPublisher.extractSpsPpsFromBuffer(b0, b0.size)
                                if (s0 != null) secondaryCachedSps = s0
                                if (p0 != null) secondaryCachedPps = p0
                            }
                            if (csd1 != null) {
                                val b1 = duplicateToBytes(csd1)
                                val (s1, p1) = RtmpPublisher.extractSpsPpsFromBuffer(b1, b1.size)
                                if (p1 != null) secondaryCachedPps = p1
                                else if (secondaryCachedPps == null && b1.isNotEmpty()) secondaryCachedPps = extractNalWithoutStartCode(csd1)
                                if (s1 != null && secondaryCachedSps == null) secondaryCachedSps = s1
                            }
                            if (secondaryCachedSps != null && secondaryCachedPps != null) {
                                secondaryRtmpPublisher?.sendAvcSequenceHeader(secondaryCachedSps!!, secondaryCachedPps!!)
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            val size = bufferInfo.size
                            if (outBuf != null && size > 0) {
                                if (reusableNalBuf.size < size) {
                                    reusableNalBuf = ByteArray(maxOf(size, reusableNalBuf.size * 2))
                                }
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + size)
                                outBuf.get(reusableNalBuf, 0, size)

                                val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (isCodecConfig) {
                                    val (sps, pps) = RtmpPublisher.extractSpsPpsFromBuffer(reusableNalBuf, size)
                                    if (sps != null) secondaryCachedSps = sps
                                    if (pps != null) secondaryCachedPps = pps
                                    if (secondaryCachedSps != null && secondaryCachedPps != null) {
                                        secondaryRtmpPublisher?.sendAvcSequenceHeader(secondaryCachedSps!!, secondaryCachedPps!!)
                                    }
                                } else if (!isPaused.get() && !isPrivacyShieldActive.get()) {
                                    val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                    val ptsUs = bufferInfo.presentationTimeUs
                                    val tsMs = if (ptsUs > 0L) {
                                        if (secondaryFirstVideoPtsUs < 0L) secondaryFirstVideoPtsUs = ptsUs
                                        ((ptsUs - secondaryFirstVideoPtsUs) / 1000L).coerceAtLeast(0L).toInt()
                                    } else {
                                        val base = if (streamStartEpochMs > 0L) streamStartEpochMs else System.currentTimeMillis()
                                        (System.currentTimeMillis() - base).coerceAtLeast(0L).toInt()
                                    }
                                    secondaryRtmpPublisher?.sendVideoFrame(reusableNalBuf, size, isKey, tsMs)
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                } catch (e: Exception) {
                    if (isStreaming.get() && isDualLiveActive.get()) {
                        Log.w(TAG, "Secondary Dual Live encoder error: ${e.message}")
                        delay(50)
                    }
                }
            }
        }
    }

    private fun createOrUpdateVirtualDisplay(projection: MediaProjection, width: Int, height: Int, surface: Surface?) {
        val alignedW = ZroStreamingEngine.alignTo16(width)
        val alignedH = ZroStreamingEngine.alignTo16(height)
        virtualDisplay = streamingEngine.createOrUpdateVirtualDisplay(
            projection = projection,
            name = "ZroLiveCapture",
            width = alignedW,
            height = alignedH,
            densityDpi = ZroStreamingEngine.resolveVirtualDisplayDpi(alignedW, alignedH),
            surface = surface,
            existing = virtualDisplay
        )
    }

    private fun resolveDensityDpi(): Int =
        ZroStreamingEngine.resolveVirtualDisplayDpi(activeStreamWidth, activeStreamHeight)

    private fun setupAudioEncoder(stereo: Boolean) {
        audioEncoder = streamingEngine.createAudioEncoder(
            sampleRate = ZroAudioEngine.SAMPLE_RATE,
            stereo = stereo
        )
    }

    private fun startSilentAudioFallbackLoop(stereo: Boolean) {
        silentAudioFallbackJob?.cancel()
        silentAudioFallbackJob = serviceScope.launch {
            val frameBytes = ByteArray(ZroAudioEngine.FRAME_SAMPLES * if (stereo) 4 else 2)
            val frameDurationMs = (ZroAudioEngine.FRAME_SAMPLES * 1000L) / ZroAudioEngine.SAMPLE_RATE
            while (isActive) {
                if (isStreaming.get() && !isPaused.get()) {
                    feedPcmToAacEncoder(frameBytes, frameBytes.size, System.nanoTime() / 1000L)
                }
                delay(frameDurationMs.coerceAtLeast(20L))
            }
        }
    }

    private fun feedPcmToAacEncoder(bytes: ByteArray, length: Int, ptsUs: Long) {
        val codec = audioEncoder ?: return
        try {
            val inputIdx = codec.dequeueInputBuffer(5_000)
            if (inputIdx >= 0) {
                val buf = codec.getInputBuffer(inputIdx) ?: return
                buf.clear()
                val copyLen = minOf(length, buf.remaining())
                buf.put(bytes, 0, copyLen)
                codec.queueInputBuffer(inputIdx, 0, copyLen, ptsUs, 0)
            }
        } catch (_: Exception) {
            // Encoder closing or busy
        }
    }

    private fun startVideoDrainLoop() {
        videoDrainJob = serviceScope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            var reusableNalBuf = ByteArray(256 * 1024)
            var consecutiveCodecErrors = 0

            while (isActive && isStreaming.get()) {
                val codec = videoEncoder ?: break
                try {
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 20_000)
                    consecutiveCodecErrors = 0
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            extractAndSendAvcConfigFromFormat(codec.outputFormat)
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            val size = bufferInfo.size
                            if (outBuf != null && size > 0) {
                                if (reusableNalBuf.size < size) {
                                    reusableNalBuf = ByteArray(maxOf(size, reusableNalBuf.size * 2))
                                }
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + size)
                                outBuf.get(reusableNalBuf, 0, size)

                                val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (isCodecConfig) {
                                    // Many Android encoders deliver combined SPS+PPS via BUFFER_FLAG_CODEC_CONFIG
                                    val (sps, pps) = RtmpPublisher.extractSpsPpsFromBuffer(reusableNalBuf, size)
                                    if (sps != null) cachedSps = sps
                                    if (pps != null) cachedPps = pps
                                    if (cachedSps != null && cachedPps != null) {
                                        rtmpPublisher?.sendAvcSequenceHeader(cachedSps!!, cachedPps!!)
                                    }
                                } else if (!isPaused.get() && !isPrivacyShieldActive.get()) {
                                    val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                    val tsMs = computeRelativeVideoTimestampMs(bufferInfo.presentationTimeUs)
                                    val sent = rtmpPublisher?.sendVideoFrame(reusableNalBuf, size, isKey, tsMs) ?: false
                                    encodedVideoFrames.incrementAndGet()
                                    if (sent) {
                                        framesInLastSecond.incrementAndGet()
                                        // Confirm LIVE state once first real H.264 frame queues cleanly (timer is already anchored monotonically)
                                        if (isLiveConfirmed.compareAndSet(false, true)) {
                                            if (streamStartElapsedRealtime <= 0L) {
                                                streamStartEpochMs = System.currentTimeMillis()
                                                streamStartElapsedRealtime = SystemClock.elapsedRealtime()
                                                StreamStateManager.markStreamStarted(
                                                    startElapsedRealtime = streamStartElapsedRealtime,
                                                    targetBitrateKbps = selectedTargetBitrateKbps
                                                )
                                            }
                                            ZroStreamCoordinator.updateTelemetry { prev ->
                                                prev.copy(
                                                    state = StreamLifecycleState.LIVE,
                                                    statusDetail = "Live — GPU Zero-Copy H.264 video & AAC audio"
                                                )
                                            }
                                            refreshNotification()
                                        }
                                    } else {
                                        droppedVideoFrames.incrementAndGet()
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                } catch (ce: MediaCodec.CodecException) {
                    Log.e(TAG, "Hardware video encoder failure: ${ce.diagnosticInfo}", ce)
                    stopAllCaptureAndBroadcast(error = "Video encoder stopped (${ce.errorCode}). Try a lower resolution or 30 FPS.")
                    break
                } catch (e: Exception) {
                    if (isStreaming.get()) {
                        consecutiveCodecErrors++
                        Log.w(TAG, "Video drain warning ($consecutiveCodecErrors): ${e.message}")
                        if (consecutiveCodecErrors >= 10) {
                            stopAllCaptureAndBroadcast(error = "Video encoder stopped unexpectedly. Resources released safely.")
                            break
                        }
                        delay(50)
                    }
                }
            }
        }
    }

    private fun extractAndSendAvcConfigFromFormat(fmt: MediaFormat) {
        val csd0 = fmt.getByteBuffer("csd-0")
        val csd1 = fmt.getByteBuffer("csd-1")
        if (csd0 != null) {
            val bytes0 = duplicateToBytes(csd0)
            val (spsFrom0, ppsFrom0) = RtmpPublisher.extractSpsPpsFromBuffer(bytes0, bytes0.size)
            if (spsFrom0 != null) cachedSps = spsFrom0
            if (ppsFrom0 != null) cachedPps = ppsFrom0
        }
        if (csd1 != null) {
            val bytes1 = duplicateToBytes(csd1)
            val (spsFrom1, ppsFrom1) = RtmpPublisher.extractSpsPpsFromBuffer(bytes1, bytes1.size)
            if (ppsFrom1 != null) cachedPps = ppsFrom1
            else if (cachedPps == null && bytes1.isNotEmpty()) cachedPps = extractNalWithoutStartCode(csd1)
            if (spsFrom1 != null && cachedSps == null) cachedSps = spsFrom1
        }
        if (cachedSps != null && cachedPps != null) {
            rtmpPublisher?.sendAvcSequenceHeader(cachedSps!!, cachedPps!!)
        }
    }

    /**
     * Computes RTMP timestamp (ms) anchored to the single monotonic clock (`System.nanoTime() / 1000L`)
     * shared identically by both Video (`EGLExt.eglPresentationTimeANDROID`) and Audio (`ZroAudioEngine`)
     * for drift-free A/V sync across multi-hour BGMI sessions.
     */
    private fun computeRelativeVideoTimestampMs(ptsUs: Long): Int {
        val originUs = if (streamClockOriginNs > 0L) streamClockOriginNs / 1000L else -1L
        if (ptsUs > 0L) {
            if (originUs > 0L && kotlin.math.abs(ptsUs - originUs) < 3_600_000_000L) {
                return ((ptsUs - originUs) / 1000L).coerceAtLeast(0L).toInt()
            }
            if (firstVideoPtsUs < 0L) firstVideoPtsUs = ptsUs
            return ((ptsUs - firstVideoPtsUs) / 1000L).coerceAtLeast(0L).toInt()
        }
        return StreamStateManager.state.value.elapsedMillis.coerceAtLeast(0L).toInt()
    }

    private fun computeRelativeAudioTimestampMs(ptsUs: Long): Int {
        val originUs = if (streamClockOriginNs > 0L) streamClockOriginNs / 1000L else -1L
        if (ptsUs > 0L) {
            if (originUs > 0L && kotlin.math.abs(ptsUs - originUs) < 3_600_000_000L) {
                return ((ptsUs - originUs) / 1000L).coerceAtLeast(0L).toInt()
            }
            if (firstAudioPtsUs < 0L) {
                firstAudioPtsUs = if (firstVideoPtsUs > 0L && kotlin.math.abs(ptsUs - firstVideoPtsUs) < 5_000_000L) {
                    firstVideoPtsUs
                } else {
                    ptsUs
                }
            }
            return ((ptsUs - firstAudioPtsUs) / 1000L).coerceAtLeast(0L).toInt()
        }
        return StreamStateManager.state.value.elapsedMillis.coerceAtLeast(0L).toInt()
    }

    private fun startAudioDrainLoop() {
        audioDrainJob = serviceScope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            var reusableAacBuf = ByteArray(8 * 1024)

            while (isActive && isStreaming.get()) {
                val codec = audioEncoder ?: break
                try {
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 20_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val csd = codec.outputFormat.getByteBuffer("csd-0")
                            if (csd != null) {
                                val asc = duplicateToBytes(csd)
                                if (asc.isNotEmpty()) {
                                    cachedAsc = asc
                                    rtmpPublisher?.sendAacSequenceHeader(asc)
                                    if (isDualLiveActive.get()) secondaryRtmpPublisher?.sendAacSequenceHeader(asc)
                                }
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            val size = bufferInfo.size
                            if (outBuf != null && size > 0) {
                                val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (isCodecConfig) {
                                    val asc = ByteArray(size)
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + size)
                                    outBuf.get(asc, 0, size)
                                    cachedAsc = asc
                                    rtmpPublisher?.sendAacSequenceHeader(asc)
                                    if (isDualLiveActive.get()) secondaryRtmpPublisher?.sendAacSequenceHeader(asc)
                                } else if (!isPaused.get()) {
                                    if (reusableAacBuf.size < size) {
                                        reusableAacBuf = ByteArray(maxOf(size, reusableAacBuf.size * 2))
                                    }
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + size)
                                    outBuf.get(reusableAacBuf, 0, size)
                                    val tsMs = computeRelativeAudioTimestampMs(bufferInfo.presentationTimeUs)
                                    rtmpPublisher?.sendAudioFrame(reusableAacBuf, size, tsMs)
                                    if (isDualLiveActive.get()) secondaryRtmpPublisher?.sendAudioFrame(reusableAacBuf, size, tsMs)
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                } catch (_: Exception) {
                    if (isStreaming.get()) delay(50)
                }
            }
        }
    }

    private fun startTelemetryAndStabilityLoop() {
        monitorJob = serviceScope.launch {
            var tick = 0
            var lastDropped = 0L
            var ticksWithoutVideoSignal = 0
            var consecutiveHealthyLatencyTicks = 0
            while (isActive && isStreaming.get()) {
                delay(1000L)
                tick++

                val pub = rtmpPublisher
                pub?.pollServerControlMessages()

                // Read authoritative monotonic timer from StreamStateManager (never freezes)
                val elapsedSec = StreamStateManager.state.value.elapsedSeconds
                val bytes = pub?.consumeBytesSentSinceLast() ?: 0L
                val totalBytes = pub?.totalBytesSent ?: 0L
                val kbps = ((bytes * 8L) / 1000L).toInt()
                if (kbps > peakBitrateKbps.get()) peakBitrateKbps.set(kbps)
                val fpsNow = framesInLastSecond.getAndSet(0)
                val dropped = droppedVideoFrames.get() + (pub?.queueDroppedPFrames ?: 0L)
                val total = encodedVideoFrames.get()

                // 1-Second BGMI Ping Guard ABR: monitor RTMP socket write latency every 1s
                val writeLatencyMs = pub?.consumeMaxWriteLatencyMs() ?: 0L
                val streamCfgNow = ZroStreamCoordinator.currentStreamSettings
                if (writeLatencyMs <= ZroStreamingEngine.ABR_RECOVERY_LATENCY_MS) {
                    consecutiveHealthyLatencyTicks++
                } else {
                    consecutiveHealthyLatencyTicks = 0
                }
                if (streamCfgNow.adaptiveBitrate && !isPaused.get()) {
                    val (nextBitrate, abrActive) = ZroStreamingEngine.computePingGuardBitrateKbps(
                        currentBitrateKbps = activeBitrateKbps,
                        targetBitrateKbps = selectedTargetBitrateKbps,
                        writeLatencyMs = writeLatencyMs,
                        width = activeStreamWidth,
                        height = activeStreamHeight,
                        consecutiveHealthyTicks = consecutiveHealthyLatencyTicks
                    )
                    if (nextBitrate != activeBitrateKbps) {
                        applyDynamicVideoBitrate(nextBitrate)
                        if (nextBitrate < activeBitrateKbps) {
                            consecutiveHealthyLatencyTicks = 0
                        }
                    }
                    StreamStateManager.updateNetworkMetrics(
                        socketWriteLatencyMs = writeLatencyMs,
                        activeBitrateKbps = activeBitrateKbps,
                        targetBitrateKbps = selectedTargetBitrateKbps,
                        abrActive = abrActive
                    )
                }

                // If encoder is connected but produces no video frames within 4s, request a sync frame
                if (!isLiveConfirmed.get() && !isPaused.get() && !isPrivacyShieldActive.get()) {
                    ticksWithoutVideoSignal++
                    if (ticksWithoutVideoSignal == 3 || ticksWithoutVideoSignal == 6) {
                        requestVideoKeyFrame()
                    }
                }

                // Auto-reconnect on transient RTMP socket drop
                if (pub != null && !pub.isConnected && !isPaused.get()) {
                    attemptReconnect(pub)
                }

                // Adaptive Bitrate, Weak-Network Suggestions & Honest Thermal Auto-Protection every 5s
                var thermalStepApplied = ZroStreamCoordinator.telemetry.value.thermalThrottled
                var suggestedWeakBitrate = ZroStreamCoordinator.telemetry.value.weakNetworkSuggestedBitrateKbps
                var recentDropsWindow = 0L
                if (tick % 5 == 0) {
                    val perf = ZroStreamCoordinator.currentPerfSettings
                    val streamCfg = ZroStreamCoordinator.currentStreamSettings
                    val dev = telemetryMonitor.snapshot()
                    val tempMeasured = dev.batteryTempCelsius
                    val isThermalHot = dev.isOverheating ||
                        (tempMeasured != null && tempMeasured >= perf.thermalThresholdCelsius)
                    val isThermalSevere = tempMeasured != null && tempMeasured >= (perf.thermalThresholdCelsius + 3f)
                    ZroStreamCoordinator.updateDeviceTemperature(
                        dev.batteryTempCelsius,
                        DeviceTelemetryMonitor.formatTemperature(dev)
                    )
                    val recentDrops = dropped - lastDropped
                    recentDropsWindow = recentDrops
                    lastDropped = dropped

                    if (isThermalHot) {
                        if (perf.autoThermalQualityStepDown && activeBitrateKbps > 1500) {
                            val stepped = (activeBitrateKbps * 0.78f).toInt().coerceAtLeast(1500)
                            applyDynamicVideoBitrate(stepped)
                            thermalStepApplied = true
                        }
                        if (perf.autoThermalFpsStepDown) {
                            val desiredFpsCap = when {
                                isThermalSevere && activeSessionFps > 24 -> 24
                                activeSessionFps > 30 -> 30
                                else -> activeSessionFps
                            }
                            if (desiredFpsCap < activeSessionFps) {
                                activeSessionFps = desiredFpsCap
                                thermalFpsThrottledFlag = true
                                thermalStepApplied = true
                            }
                        }
                    } else {
                        // Restore thermal FPS step-down once device cools safely below (threshold - 2°C)
                        if (thermalFpsThrottledFlag &&
                            (tempMeasured == null || tempMeasured <= (perf.thermalThresholdCelsius - 2f))
                        ) {
                            activeSessionFps = baseSessionFps
                            thermalFpsThrottledFlag = false
                        }
                        if (streamCfg.adaptiveBitrate && recentDrops >= 4 && activeBitrateKbps > 1000) {
                            val stepped = (activeBitrateKbps * 0.85f).toInt().coerceAtLeast(1000)
                            applyDynamicVideoBitrate(stepped)
                        } else if (streamCfg.adaptiveBitrate && recentDrops == 0L && activeBitrateKbps < selectedTargetBitrateKbps) {
                            val cap = minOf(
                                selectedTargetBitrateKbps,
                                DeviceTelemetryMonitor.maxAllowedBitrateKbps(dev, perf.profile, perf.lowRamMode, streamCfg.lowDataMode)
                            )
                            if (activeBitrateKbps < cap) {
                                val recovered = (activeBitrateKbps + 350).coerceAtMost(cap)
                                applyDynamicVideoBitrate(recovered)
                            }
                        }
                    }

                    // Persist active live session heartbeat every 5s for instant crash recovery
                    if (isLiveConfirmed.get()) {
                        persistRecoverySnapshot(cleanlyEnded = false, lastError = null)
                    }
                }

                val dropRatio = if (total > 0) dropped.toFloat() / total.toFloat() else 0f
                val health = when {
                    rtmpPublisher?.isConnected != true -> StreamHealth.POOR
                    dropRatio > 0.08f -> StreamHealth.POOR
                    dropRatio > 0.03f -> StreamHealth.FAIR
                    kbps >= activeBitrateKbps * 0.65 -> StreamHealth.EXCELLENT
                    else -> StreamHealth.GOOD
                }

                if (isLiveConfirmed.get() && tick >= 6) {
                    suggestedWeakBitrate = if (health == StreamHealth.EXCELLENT || health == StreamHealth.GOOD) {
                        null
                    } else {
                        computeWeakNetworkSuggestedBitrate(
                            measuredKbps = kbps,
                            activeTargetKbps = activeBitrateKbps,
                            health = health,
                            recentDroppedFrames = recentDropsWindow
                        )
                    }
                }

                ZroStreamCoordinator.updateTelemetry { prev ->
                    prev.copy(
                        durationSec = elapsedSec,
                        actualBitrateKbps = kbps,
                        targetBitrateKbps = activeBitrateKbps,
                        selectedBitrateKbps = selectedTargetBitrateKbps,
                        weakNetworkSuggestedBitrateKbps = suggestedWeakBitrate,
                        targetFps = activeSessionFps,
                        actualFps = fpsNow,
                        droppedFrames = dropped,
                        totalFrames = total,
                        bytesSentTotal = totalBytes,
                        streamHealth = health,
                        thermalThrottled = thermalStepApplied,
                        thermalFpsThrottled = thermalFpsThrottledFlag,
                        gameAudioCaptureNote = ZroAudioEngine.gameCaptureStatus.value
                    )
                }

                // Sync real telemetry to Phone 2 Studio when Dual-Phone link is connected
                if (tick % 2 == 0) {
                    DualPhoneEngine.shared.sendTelemetrySnapshot(
                        resolution = activeSessionResolutionLabel,
                        fps = fpsNow,
                        uploadKbps = kbps,
                        packetLossPercent = (dropRatio * 100f).coerceIn(0f, 100f)
                    )
                }

                // Battery-friendly notification refresh (every 3s in Battery/Perf mode, 2s in Balanced)
                val notifMod = if (ZroStreamCoordinator.currentPerfSettings.profile == PerformanceProfile.BALANCED) 2 else 3
                if (tick % notifMod == 0) {
                    refreshNotification()
                }
            }
        }
    }

    private suspend fun attemptReconnect(publisher: RtmpPublisher) {
        val settings = ZroStreamCoordinator.currentStreamSettings
        val primaryUrl = settings.effectivePrimaryServerUrl()
        val backupUrl = settings.effectiveBackupServerUrl()
        for (attempt in 1..4) {
            if (!isStreaming.get()) return
            ZroStreamCoordinator.updateTelemetry {
                it.copy(
                    state = StreamLifecycleState.RECONNECTING,
                    statusDetail = "Connection dropped. Reconnecting (attempt $attempt/4)…",
                    reconnectAttempt = attempt,
                    streamHealth = StreamHealth.POOR
                )
            }
            refreshNotification()
            // Jittered exponential backoff prevents thundering-herd reconnect spikes across concurrent users
            delay(ZroStreamingEngine.computeReconnectBackoffMs(attempt))
            if (!hasActiveInternetConnection()) continue

            val res = runCatching {
                publisher.connectWithFailover(
                    primaryUrl = primaryUrl,
                    backupUrl = backupUrl,
                    streamKey = settings.streamKey,
                    preferBackup = settings.useBackupServer || (attempt % 2 == 0 && backupUrl.isNotBlank())
                )
            }.getOrElse { Result.failure(it) }
            if (res.isSuccess) {
                val info = res.getOrThrow()
                publisher.sendMetaData(
                    width = activeStreamWidth,
                    height = activeStreamHeight,
                    fps = activeSessionFps,
                    videoBitrateKbps = activeBitrateKbps,
                    audioSampleRate = ZroAudioEngine.SAMPLE_RATE,
                    audioBitrateKbps = if (activeStereoAudio) 128 else 96,
                    stereo = activeStereoAudio
                )
                val sps = cachedSps
                val pps = cachedPps
                val asc = cachedAsc ?: RtmpPublisher.buildAacAudioSpecificConfig(ZroAudioEngine.SAMPLE_RATE, activeStereoAudio)
                if (sps != null && pps != null) publisher.sendAvcSequenceHeader(sps, pps)
                publisher.sendAacSequenceHeader(asc)
                requestVideoKeyFrame()
                CrashRecoveryStore.recordEvent(applicationContext, "NETWORK", "Auto-reconnected to ${info.serverUrl} on attempt $attempt.")
                ZroStreamCoordinator.updateTelemetry {
                    it.copy(
                        state = when {
                            isPaused.get() -> StreamLifecycleState.PAUSED
                            isLiveConfirmed.get() -> StreamLifecycleState.LIVE
                            else -> StreamLifecycleState.SENDING_VIDEO
                        },
                        activeServerUrl = info.serverUrl,
                        usingBackupServer = info.usedBackup,
                        statusDetail = if (info.usedBackup) "Reconnected via Backup server" else "Reconnected to stream server",
                        reconnectAttempt = 0
                    )
                }
                refreshNotification()
                return
            }
        }
        val errMsg = "Stream disconnected after 4 reconnection attempts. Use Reconnect to Previous Live once network stabilizes."
        CrashRecoveryStore.recordEvent(applicationContext, "NETWORK", errMsg)
        stopAllCaptureAndBroadcast(error = errMsg)
    }

    private fun duplicateToBytes(buf: ByteBuffer): ByteArray {
        val dup = buf.duplicate()
        val arr = ByteArray(dup.remaining())
        dup.get(arr)
        return arr
    }

    /**
     * Adjusts video bitrate live without restarting the RTMP stream using MediaCodec.setParameters.
     * Reports honest feedback if the device H.264 encoder does not support dynamic runtime bitrate changes.
     */
    private fun handleLiveBitrateUpdate(requestedKbps: Int) {
        val perf = ZroStreamCoordinator.currentPerfSettings
        val streamCfg = ZroStreamCoordinator.currentStreamSettings
        val dev = telemetryMonitor.snapshot()
        val maxSafe = DeviceTelemetryMonitor.maxAllowedBitrateKbps(
            dev,
            perf.profile,
            perf.lowRamMode,
            streamCfg.lowDataMode
        ).coerceAtLeast(1500)
        val clamped = requestedKbps.coerceIn(1000, maxSafe)
        selectedTargetBitrateKbps = clamped

        if (!isStreaming.get() || videoEncoder == null) {
            activeBitrateKbps = clamped
            ZroStreamCoordinator.updateTelemetry { prev ->
                prev.copy(
                    selectedBitrateKbps = clamped,
                    targetBitrateKbps = clamped,
                    weakNetworkSuggestedBitrateKbps = null
                )
            }
            return
        }

        val applied = applyDynamicVideoBitrate(clamped)
        if (applied) {
            val mbps = String.format(java.util.Locale.US, "%.1f", clamped / 1000f)
            val capNote = if (clamped < requestedKbps) " (capped for thermal/device safety)" else ""
            ZroStreamCoordinator.updateTelemetry { prev ->
                prev.copy(
                    selectedBitrateKbps = clamped,
                    targetBitrateKbps = clamped,
                    liveBitrateChangeSupported = true,
                    liveBitrateStatusMessage = "Live bitrate applied: $mbps Mbps$capNote",
                    weakNetworkSuggestedBitrateKbps = null
                )
            }
            refreshNotification()
        } else {
            ZroStreamCoordinator.updateTelemetry { prev ->
                prev.copy(
                    selectedBitrateKbps = clamped,
                    liveBitrateChangeSupported = false,
                    liveBitrateStatusMessage = "This device's H.264 encoder rejected live bitrate changes. Stop stream, adjust bitrate in Live Setup, and reconnect."
                )
            }
        }
    }

    private fun applyDynamicVideoBitrate(newBitrateKbps: Int): Boolean {
        val encoder = videoEncoder ?: return false
        return runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateKbps * 1000)
            }
            encoder.setParameters(params)
            if (isDualLiveActive.get()) {
                val secKbps = (newBitrateKbps * 0.65f).toInt().coerceIn(1000, 6000)
                val secParams = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, secKbps * 1000)
                }
                runCatching { secondaryVideoEncoder?.setParameters(secParams) }
            }
            activeBitrateKbps = newBitrateKbps
            true
        }.getOrElse { e ->
            Log.w(TAG, "Dynamic bitrate change rejected by encoder: ${e.message}")
            false
        }
    }

    private fun requestVideoKeyFrame() {
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            videoEncoder?.setParameters(params)
            if (isDualLiveActive.get()) {
                secondaryVideoEncoder?.setParameters(params)
            }
        }
    }

    private fun extractNalWithoutStartCode(buf: ByteBuffer): ByteArray {
        val dup = buf.duplicate()
        val arr = ByteArray(dup.remaining())
        dup.get(arr)
        val offset = when {
            arr.size >= 4 && arr[0] == 0.toByte() && arr[1] == 0.toByte() && arr[2] == 0.toByte() && arr[3] == 1.toByte() -> 4
            arr.size >= 3 && arr[0] == 0.toByte() && arr[1] == 0.toByte() && arr[2] == 1.toByte() -> 3
            else -> 0
        }
        return arr.copyOfRange(offset, arr.size)
    }

    /**
     * When running as Phone 1 (Gameplay Device) with user-authorized MediaProjection capture,
     * mirrors lightweight 640x360 JPEG gameplay frames (~10-12 FPS) to Phone 2 Studio Preview.
     * Respects Privacy Shield: stops sending frames whenever captured game is hidden or paused.
     */
    private fun startDualPhoneMirrorCaptureIfNeeded(projection: MediaProjection) {
        val dt = DualPhoneEngine.shared.telemetry.value
        if (dt.role != DualPhoneRole.PHONE1_GAMEPLAY) return
        stopDualPhoneMirrorCapture()
        runCatching {
            val mirrorW = 854
            val mirrorH = 480
            val thread = HandlerThread("ZroDualMirror").apply { start() }
            mirrorHandlerThread = thread
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(mirrorW, mirrorH, PixelFormat.RGBA_8888, 2)
            mirrorImageReader = reader
            val baos = ByteArrayOutputStream(48 * 1024)
            var lastPushMs = 0L
            var reusablePaddedBmp: Bitmap? = null
            var reusableCroppedBmp: Bitmap? = null
            var reusableCropCanvas: Canvas? = null
            val srcRect = Rect(0, 0, mirrorW, mirrorH)
            val dstRect = Rect(0, 0, mirrorW, mirrorH)

            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (isPrivacyShieldActive.get() || isPaused.get()) return@setOnImageAvailableListener
                    if (DualPhoneEngine.shared.telemetry.value.linkState != DualLinkState.CONNECTED) {
                        return@setOnImageAvailableListener
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPushMs < 33L) return@setOnImageAvailableListener
                    lastPushMs = now

                    val plane = img.planes.firstOrNull() ?: return@setOnImageAvailableListener
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * mirrorW
                    val paddedW = mirrorW + rowPadding / pixelStride
                    var paddedBmp = reusablePaddedBmp
                    if (paddedBmp == null || paddedBmp.isRecycled || paddedBmp.width != paddedW || paddedBmp.height != mirrorH) {
                        runCatching { paddedBmp?.recycle() }
                        paddedBmp = Bitmap.createBitmap(paddedW, mirrorH, Bitmap.Config.ARGB_8888)
                        reusablePaddedBmp = paddedBmp
                    }
                    paddedBmp.copyPixelsFromBuffer(plane.buffer)
                    val outBmp = if (paddedW != mirrorW) {
                        var cropBmp = reusableCroppedBmp
                        if (cropBmp == null || cropBmp.isRecycled) {
                            cropBmp = Bitmap.createBitmap(mirrorW, mirrorH, Bitmap.Config.ARGB_8888)
                            reusableCroppedBmp = cropBmp
                            reusableCropCanvas = Canvas(cropBmp)
                        }
                        reusableCropCanvas?.drawBitmap(paddedBmp, srcRect, dstRect, null)
                        cropBmp
                    } else {
                        paddedBmp
                    }
                    baos.reset()
                    outBmp.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    DualPhoneEngine.shared.pushMirroredFrameJpeg(baos.toByteArray(), mirrorW, mirrorH, minIntervalMs = 33L)
                } catch (_: Exception) {
                    // Best-effort mirror frame
                } finally {
                    runCatching { img.close() }
                }
            }, handler)

            mirrorVirtualDisplay = projection.createVirtualDisplay(
                "ZroDualMirrorDisplay",
                mirrorW,
                mirrorH,
                resolveDensityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        }.onFailure { e ->
            Log.w(TAG, "Dual-phone mirror capture init skipped: ${e.message}")
            stopDualPhoneMirrorCapture()
        }
    }

    private fun stopDualPhoneMirrorCapture() {
        runCatching { mirrorVirtualDisplay?.release() }; mirrorVirtualDisplay = null
        runCatching { mirrorImageReader?.close() }; mirrorImageReader = null
        runCatching { mirrorHandlerThread?.quitSafely() }; mirrorHandlerThread = null
    }

    private fun releaseCaptureOnly() {
        stopLocalScreenFrameCapture()
        stopDualPhoneMirrorCapture()
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        runCatching { mediaProjection?.stop() }; mediaProjection = null
        ZroStreamCoordinator.updateLocalCapturedFrame(null)
        ZroStreamCoordinator.releaseCaptureLock()
    }

    private fun stopAllCaptureAndBroadcast(error: String?) {
        val wasStreaming = isStreaming.getAndSet(false)
        val wasConfirmedLive = isLiveConfirmed.getAndSet(false)
        isPaused.set(false)
        isPrivacyShieldActive.set(false)

        val finalDuration = StreamStateManager.state.value.elapsedSeconds.takeIf { it > 0L }
            ?: if (streamStartElapsedRealtime > 0L) (SystemClock.elapsedRealtime() - streamStartElapsedRealtime) / 1000L else 0L
        val finalDropped = droppedVideoFrames.get() + (rtmpPublisher?.queueDroppedPFrames ?: 0L)
        val finalPeak = peakBitrateKbps.get()
        val finishedSessionId = activeSessionId
        val finishedUrl = activeRtmpUrlUsed
        val finishedPlatform = activePlatformLabel
        activeSessionId = null
        streamStartEpochMs = 0L
        streamStartElapsedRealtime = 0L
        streamClockOriginNs = 0L

        isDualLiveActive.set(false)
        liveTimerJob?.cancel(); liveTimerJob = null
        StreamStateManager.resetToStopped()
        videoDrainJob?.cancel(); videoDrainJob = null
        secondaryVideoDrainJob?.cancel(); secondaryVideoDrainJob = null
        audioDrainJob?.cancel(); audioDrainJob = null
        silentAudioFallbackJob?.cancel(); silentAudioFallbackJob = null
        monitorJob?.cancel(); monitorJob = null
        stopStudioCompositorLoop()
        stopLocalScreenFrameCapture()
        releaseBroadcastWakeLock()

        stopDualPhoneMirrorCapture()
        ZroAudioEngine.stopStreamingAudio()
        runCatching { rtmpPublisher?.close() }; rtmpPublisher = null
        runCatching { secondaryRtmpPublisher?.close() }; secondaryRtmpPublisher = null
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        runCatching { secondaryVirtualDisplay?.release() }; secondaryVirtualDisplay = null
        runCatching { videoInputSurface?.release() }; videoInputSurface = null
        runCatching { secondaryVideoInputSurface?.release() }; secondaryVideoInputSurface = null
        runCatching { videoEncoder?.stop() }; runCatching { videoEncoder?.release() }; videoEncoder = null
        runCatching { secondaryVideoEncoder?.stop() }; runCatching { secondaryVideoEncoder?.release() }; secondaryVideoEncoder = null
        runCatching { audioEncoder?.stop() }; runCatching { audioEncoder?.release() }; audioEncoder = null
        runCatching { mediaProjection?.stop() }; mediaProjection = null

        val wasCleanStop = error == null
        if (wasCleanStop) {
            serviceScope.launch { runCatching { settingsStore.markActiveLiveCleanlyEnded() } }
        } else if (finishedSessionId != null) {
            val sanitizedErr = CrashRecoveryStore.scrubSecrets(error)
            CrashRecoveryStore.recordEvent(applicationContext, "NETWORK", "Interrupted live ($finishedSessionId): $sanitizedErr")
            serviceScope.launch {
                val cfg = ZroStreamCoordinator.currentStreamSettings
                runCatching {
                    settingsStore.saveActiveLiveSnapshot(
                        ActiveLiveSessionSnapshot(
                            sessionId = finishedSessionId,
                            title = activeSessionTitle,
                            rtmpUrl = finishedUrl.ifBlank { cfg.effectivePrimaryServerUrl() },
                            platformLabel = finishedPlatform,
                            resolutionName = cfg.resolution.name,
                            fps = activeSessionFps,
                            bitrateKbps = activeBitrateKbps,
                            activeSceneSlotName = ZroStreamCoordinator.activeSceneSlotName,
                            startedAtEpochMs = System.currentTimeMillis() - (finalDuration * 1000L),
                            lastUpdatedEpochMs = System.currentTimeMillis(),
                            durationSec = finalDuration,
                            cleanlyEnded = false,
                            lastError = sanitizedErr
                        )
                    )
                }
            }
        }

        ZroStreamCoordinator.resetToIdle(error = error)
        if (wasStreaming || finishedSessionId != null) {
            ZroStreamCoordinator.notifyStreamFinalized(
                ZroStreamCoordinator.StreamFinalStats(
                    sessionId = finishedSessionId,
                    title = activeSessionTitle,
                    visibility = activeSessionVisibility,
                    resolutionLabel = activeSessionResolutionLabel,
                    fps = activeSessionFps,
                    bitrateKbps = activeBitrateKbps,
                    durationSec = finalDuration,
                    droppedFrames = finalDropped,
                    peakBitrateKbps = finalPeak,
                    platformLabel = finishedPlatform,
                    rtmpUrl = finishedUrl,
                    sceneSlotName = ZroStreamCoordinator.activeSceneSlotName,
                    wasCleanStop = wasCleanStop,
                    errorMessage = error
                )
            )
        }

        val finishServiceLifecycle: () -> Unit = {
            if (isVoiceForeground.get()) {
                promoteForeground(includeMediaProjection = false)
            } else {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                runCatching { stopSelf() }
            }
        }
        if (::streamingEngine.isInitialized) {
            streamingEngine.postOnMainThread(finishServiceLifecycle)
        } else {
            finishServiceLifecycle()
        }
    }

    private fun refreshNotification() {
        val update = {
            notifManager.updateNotification(
                telemetry = ZroStreamCoordinator.telemetry.value,
                micMuted = !ZroStreamCoordinator.currentAudioSettings.micEnabled,
                voiceRoomName = ZroStreamCoordinator.activeVoiceRoomName.value
            )
        }
        if (::streamingEngine.isInitialized) {
            streamingEngine.postOnMainThread(update)
        } else {
            update()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllCaptureAndBroadcast(error = null)
        if (::liveCompositor.isInitialized) {
            liveCompositor.release()
        }
        if (::streamingEngine.isInitialized) {
            streamingEngine.shutdown()
        }
        serviceScope.cancel()
    }
}
