package com.example.streaming

import android.app.Activity
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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.example.audio.ZroAudioEngine
import com.example.data.model.CaptureMode
import com.example.data.model.PerformanceProfile
import com.example.data.model.StreamHealth
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamOrientation
import com.example.dualphone.DualPhoneEngine
import com.example.studio.DualLinkState
import com.example.studio.DualPhoneRole
import com.example.telemetry.DeviceTelemetryMonitor
import java.io.ByteArrayOutputStream
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
        const val EXTRA_SESSION_ID = "extra_session_id"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notifManager: StreamNotificationManager
    private lateinit var telemetryMonitor: DeviceTelemetryMonitor

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mirrorVirtualDisplay: VirtualDisplay? = null
    private var mirrorImageReader: ImageReader? = null
    private var mirrorHandlerThread: HandlerThread? = null
    private var videoEncoder: MediaCodec? = null
    private var videoInputSurface: Surface? = null
    private var audioEncoder: MediaCodec? = null
    private var rtmpPublisher: RtmpPublisher? = null

    private var videoDrainJob: Job? = null
    private var audioDrainJob: Job? = null
    private var monitorJob: Job? = null

    private val isStreaming = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isPrivacyShieldActive = AtomicBoolean(false)
    private val isVoiceForeground = AtomicBoolean(false)
    private val encodedVideoFrames = AtomicLong(0L)
    private val droppedVideoFrames = AtomicLong(0L)
    private val framesInLastSecond = AtomicInteger(0)
    private val peakBitrateKbps = AtomicInteger(0)

    private var activeBitrateKbps = 4500
    private var activeCaptureMode = CaptureMode.GAME_APP_ONLY
    private var streamStartEpochMs = 0L
    private var activeSessionId: String? = null
    private var activeSessionTitle = "Mobile Gaming Live"
    private var activeSessionVisibility = "PUBLIC"
    private var activeSessionResolutionLabel = "720p"
    private var activeSessionFps = 30
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    private var cachedAsc: ByteArray? = null

    override fun onCreate() {
        super.onCreate()
        notifManager = StreamNotificationManager(this)
        telemetryMonitor = DeviceTelemetryMonitor(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE_PREVIEW -> startCapturePreviewOnly()
            ACTION_UPDATE_PREVIEW_SURFACE -> attachOrDetachPreviewSurface()
            ACTION_STOP_CAPTURE -> stopAllCaptureAndBroadcast(error = null)
            ACTION_START_STREAM -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                startBroadcastSession(sessionId)
            }
            ACTION_STOP_STREAM -> stopAllCaptureAndBroadcast(error = null)
            ACTION_TOGGLE_PAUSE -> {
                if (isStreaming.get()) {
                    val nowPaused = !isPaused.get()
                    isPaused.set(nowPaused)
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
        return START_NOT_STICKY
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
            val (width, height) = resolveDimensions(settings.resolution.width, settings.resolution.height, settings.orientation)
            val previewSurface = ZroStreamCoordinator.previewSurfaceRef.get()
            createOrUpdateVirtualDisplay(projection, width, height, previewSurface)
            startDualPhoneMirrorCaptureIfNeeded(projection)

            val sourceLabel = if (activeCaptureMode == CaptureMode.GAME_APP_ONLY) {
                "Selected Game / App Window (Private)"
            } else {
                "Full Phone Screen (Opt-In)"
            }

            isPrivacyShieldActive.set(false)
            ZroStreamCoordinator.updateTelemetry {
                it.copy(
                    state = StreamLifecycleState.IDLE,
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34ProjectionCallbackHelper.register(
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
        } else {
            // Android 10-13 (API 29-33): register standard MediaProjection.Callback without API 34 overrides
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    stopAllCaptureAndBroadcast(error = null)
                }
            }, null)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34ProjectionCallbackHelper {
        fun register(
            projection: MediaProjection,
            onStopped: () -> Unit,
            onResized: (Int, Int) -> Unit,
            onVisibilityChanged: (Boolean) -> Unit
        ) {
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = onStopped()
                override fun onCapturedContentResize(width: Int, height: Int) = onResized(width, height)
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) = onVisibilityChanged(isVisible)
            }, null)
        }
    }

    /**
     * Stage 2: Starts YouTube Live broadcast.
     * Reuses the active Capture Preview MediaProjection if present (zero double-prompt),
     * or initializes a fresh MediaProjection from pending consent if user started directly.
     */
    private fun startBroadcastSession(sessionId: String?) {
        if (!ZroStreamCoordinator.tryAcquireSessionLock()) {
            Log.w(TAG, "Ignored duplicate stream start request")
            return
        }

        val existingProjection = mediaProjection
        val resultCode = ZroStreamCoordinator.pendingMediaProjectionResultCode
        val resultData = ZroStreamCoordinator.pendingMediaProjectionData

        if (existingProjection == null && (resultCode != Activity.RESULT_OK || resultData == null)) {
            ZroStreamCoordinator.resetToIdle("Select a game to capture before starting your stream.")
            return
        }

        val settings = ZroStreamCoordinator.currentStreamSettings
        val perf = ZroStreamCoordinator.currentPerfSettings
        val profileCap = perf.profile.maxBitrateCapKbps
        val lowRamCap = if (perf.lowRamMode) 4500 else 15000
        val lowDataCap = if (settings.lowDataMode) 2500 else 15000
        val effectiveInitialBitrate = settings.bitrateKbps
            .coerceAtMost(minOf(profileCap, lowRamCap, lowDataCap))
            .coerceAtLeast(1000)

        activeSessionId = sessionId
        activeSessionTitle = settings.streamTitle
        activeSessionVisibility = settings.visibility.name
        activeSessionResolutionLabel = settings.resolution.shortLabel
        activeSessionFps = if (perf.profile == PerformanceProfile.BATTERY_SAVER || perf.lowRamMode || settings.lowDataMode) {
            minOf(settings.fps, 30)
        } else {
            settings.fps
        }
        activeBitrateKbps = effectiveInitialBitrate
        if (existingProjection == null) {
            activeCaptureMode = ZroStreamCoordinator.pendingCaptureMode
        }

        peakBitrateKbps.set(0)
        encodedVideoFrames.set(0L)
        droppedVideoFrames.set(0L)
        framesInLastSecond.set(0)
        isPaused.set(false)
        cachedSps = null
        cachedPps = null
        cachedAsc = null

        val sourceLabel = if (activeCaptureMode == CaptureMode.GAME_APP_ONLY) {
            "Selected Game / App Window (Private)"
        } else {
            "Full Phone Screen (Opt-In)"
        }

        ZroStreamCoordinator.updateTelemetry {
            it.copy(
                state = StreamLifecycleState.CONNECTING,
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
                captureSourceLabel = sourceLabel,
                thermalThrottled = false,
                errorMessage = null,
                activeSessionId = sessionId
            )
        }

        // Android 14+ requirement: call startForeground(MEDIA_PROJECTION) BEFORE getMediaProjection()
        promoteForeground(includeMediaProjection = true)

        serviceScope.launch {
            try {
                // 1. Obtain or reuse MediaProjection
                val projection = existingProjection ?: run {
                    val projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val p = projMgr.getMediaProjection(resultCode, resultData!!)
                        ?: throw IllegalStateException("MediaProjection could not be initialized.")
                    ZroStreamCoordinator.clearPendingMediaProjection()
                    ZroStreamCoordinator.tryAcquireCaptureLock()
                    mediaProjection = p
                    registerProjectionCallback(p)
                    p
                }

                // 2. Connect to YouTube Live RTMP ingest first so invalid key/offline errors fail honestly
                val publisher = RtmpPublisher()
                rtmpPublisher = publisher
                val connectResult = publisher.connect(settings.rtmpUrl, settings.streamKey)
                if (connectResult.isFailure) {
                    val msg = connectResult.exceptionOrNull()?.localizedMessage
                        ?: "Unable to connect to YouTube RTMP server. Verify your Stream Key and internet connection."
                    stopAllCaptureAndBroadcast(error = msg)
                    return@launch
                }

                // 3. Configure Hardware Video Encoder & bind VirtualDisplay to encoder Surface
                val (width, height) = resolveDimensions(settings.resolution.width, settings.resolution.height, settings.orientation)
                val encoderName = setupVideoEncoder(width, height, activeSessionFps, activeBitrateKbps)
                createOrUpdateVirtualDisplay(projection, width, height, videoInputSurface)
                startDualPhoneMirrorCaptureIfNeeded(projection)

                // 4. Configure AAC Audio Encoder & attach unified ZroAudioEngine mixer
                setupAudioEncoder(ZroStreamCoordinator.currentAudioSettings.stereoOutput)
                ZroAudioEngine.applyPerformanceSettings(perf)
                ZroAudioEngine.startStreamingAudio(this@StreamingForegroundService, projection)
                ZroAudioEngine.setBroadcastPcmConsumer { pcmBytes, length, ptsUs ->
                    if (!isPaused.get() && isStreaming.get()) {
                        feedPcmToAacEncoder(pcmBytes, length, ptsUs)
                    }
                }

                isStreaming.set(true)
                streamStartEpochMs = System.currentTimeMillis()
                ZroStreamCoordinator.updateTelemetry {
                    it.copy(
                        state = StreamLifecycleState.LIVE,
                        activeEncoderName = encoderName,
                        gameAudioCaptureActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                        gameAudioCaptureNote = ZroAudioEngine.gameCaptureStatus.value
                    )
                }
                refreshNotification()

                startVideoDrainLoop()
                startAudioDrainLoop()
                startTelemetryAndStabilityLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start broadcast: ${e.message}", e)
                stopAllCaptureAndBroadcast(error = e.localizedMessage ?: "Broadcast initialization failed.")
            }
        }
    }

    private fun resolveDimensions(rawW: Int, rawH: Int, orientation: StreamOrientation): Pair<Int, Int> {
        val portrait = when (orientation) {
            StreamOrientation.PORTRAIT -> true
            StreamOrientation.LANDSCAPE -> false
            StreamOrientation.AUTO -> resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        val w = if (portrait) minOf(rawW, rawH) else maxOf(rawW, rawH)
        val h = if (portrait) maxOf(rawW, rawH) else minOf(rawW, rawH)
        // Align to 16px macroblock boundaries for broad low-end hardware encoder compatibility
        return ((w + 15) / 16 * 16) to ((h + 15) / 16 * 16)
    }

    private fun selectHardwareAvcEncoderName(): String? {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.firstOrNull { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isHardwareAccelerated
                    } else {
                        !info.name.contains("google", ignoreCase = true) && !info.name.startsWith("c2.android.", ignoreCase = true)
                    }
            }?.name
        } catch (_: Exception) {
            null
        }
    }

    private fun setupVideoEncoder(width: Int, height: Int, fps: Int, bitrateKbps: Int): String {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
        }

        val hwName = selectHardwareAvcEncoderName()
        val codec = try {
            if (hwName != null) MediaCodec.createByCodecName(hwName)
            else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (e: Exception) {
            Log.w(TAG, "Hardware encoder $hwName failed to open, using default AVC encoder: ${e.message}")
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some low-end devices reject CBR; retry with VBR cleanly
            Log.w(TAG, "CBR configure rejected, retrying default bitrate mode: ${e.message}")
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        videoInputSurface = codec.createInputSurface()
        codec.start()
        videoEncoder = codec
        return hwName ?: codec.name
    }

    private fun createOrUpdateVirtualDisplay(projection: MediaProjection, width: Int, height: Int, surface: Surface?) {
        val existing = virtualDisplay
        if (existing != null) {
            runCatching {
                existing.resize(width, height, resolveDensityDpi())
                existing.surface = surface
                return
            }
            runCatching { existing.release() }
            virtualDisplay = null
        }

        virtualDisplay = projection.createVirtualDisplay(
            "ZroLiveCapture",
            width,
            height,
            resolveDensityDpi(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
    }

    private fun resolveDensityDpi(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resources.displayMetrics.densityDpi
        } else {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            metrics.densityDpi
        }
    }

    private fun setupAudioEncoder(stereo: Boolean) {
        val channels = if (stereo) 2 else 1
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, ZroAudioEngine.SAMPLE_RATE, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (stereo) 128_000 else 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioEncoder = codec
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
            // Single reusable buffer eliminates 30-60 heap allocations per second!
            var reusableNalBuf = ByteArray(256 * 1024)
            var consecutiveCodecErrors = 0

            while (isActive && isStreaming.get()) {
                val codec = videoEncoder ?: break
                try {
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 20_000)
                    consecutiveCodecErrors = 0
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val fmt = codec.outputFormat
                            val spsBuf = fmt.getByteBuffer("csd-0")
                            val ppsBuf = fmt.getByteBuffer("csd-1")
                            if (spsBuf != null && ppsBuf != null) {
                                val sps = extractNalWithoutStartCode(spsBuf)
                                val pps = extractNalWithoutStartCode(ppsBuf)
                                cachedSps = sps
                                cachedPps = pps
                                rtmpPublisher?.sendAvcSequenceHeader(sps, pps)
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            val size = bufferInfo.size
                            if (outBuf != null && size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                // Privacy Shield: skip sending video frames whenever paused OR captured game is hidden!
                                if (!isPaused.get() && !isPrivacyShieldActive.get()) {
                                    if (reusableNalBuf.size < size) {
                                        reusableNalBuf = ByteArray(maxOf(size, reusableNalBuf.size * 2))
                                    }
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + size)
                                    outBuf.get(reusableNalBuf, 0, size)

                                    val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                    val tsMs = (System.currentTimeMillis() - streamStartEpochMs).toInt()
                                    val sent = rtmpPublisher?.sendVideoFrame(reusableNalBuf, size, isKey, tsMs) ?: false
                                    encodedVideoFrames.incrementAndGet()
                                    if (sent) {
                                        framesInLastSecond.incrementAndGet()
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

    private fun startAudioDrainLoop() {
        audioDrainJob = serviceScope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            // Single reusable AAC buffer (zero per-frame heap allocation)
            var reusableAacBuf = ByteArray(8 * 1024)

            while (isActive && isStreaming.get()) {
                val codec = audioEncoder ?: break
                try {
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 20_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val csd = codec.outputFormat.getByteBuffer("csd-0")
                            if (csd != null) {
                                val asc = ByteArray(csd.remaining())
                                csd.get(asc)
                                cachedAsc = asc
                                rtmpPublisher?.sendAacSequenceHeader(asc)
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            val size = bufferInfo.size
                            if (outBuf != null && size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                if (!isPaused.get()) {
                                    if (reusableAacBuf.size < size) {
                                        reusableAacBuf = ByteArray(maxOf(size, reusableAacBuf.size * 2))
                                    }
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + size)
                                    outBuf.get(reusableAacBuf, 0, size)
                                    val tsMs = (System.currentTimeMillis() - streamStartEpochMs).toInt()
                                    rtmpPublisher?.sendAudioFrame(reusableAacBuf, size, tsMs)
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
            while (isActive && isStreaming.get()) {
                delay(1000L)
                tick++
                val elapsedSec = (System.currentTimeMillis() - streamStartEpochMs) / 1000L
                val bytes = rtmpPublisher?.consumeBytesSentSinceLast() ?: 0L
                val kbps = ((bytes * 8L) / 1000L).toInt()
                if (kbps > peakBitrateKbps.get()) peakBitrateKbps.set(kbps)
                val fpsNow = framesInLastSecond.getAndSet(0)
                val dropped = droppedVideoFrames.get()
                val total = encodedVideoFrames.get()

                // Auto-reconnect on transient RTMP socket drop
                val pub = rtmpPublisher
                if (pub != null && !pub.isConnected && !isPaused.get()) {
                    attemptReconnect(pub)
                }

                // Adaptive Bitrate & Honest Thermal Auto-Protection every 5s
                var thermalStepApplied = ZroStreamCoordinator.telemetry.value.thermalThrottled
                if (tick % 5 == 0) {
                    val perf = ZroStreamCoordinator.currentPerfSettings
                    val streamCfg = ZroStreamCoordinator.currentStreamSettings
                    val dev = telemetryMonitor.snapshot()
                    val recentDrops = dropped - lastDropped
                    lastDropped = dropped

                    if (perf.autoThermalQualityStepDown && dev.isOverheating && activeBitrateKbps > 1200) {
                        val stepped = (activeBitrateKbps * 0.78f).toInt().coerceAtLeast(1200)
                        applyDynamicVideoBitrate(stepped)
                        thermalStepApplied = true
                    } else if (streamCfg.adaptiveBitrate && recentDrops >= 4 && activeBitrateKbps > 1000) {
                        val stepped = (activeBitrateKbps * 0.85f).toInt().coerceAtLeast(1000)
                        applyDynamicVideoBitrate(stepped)
                    } else if (streamCfg.adaptiveBitrate && recentDrops == 0L && !dev.isOverheating && activeBitrateKbps < streamCfg.bitrateKbps) {
                        val cap = minOf(streamCfg.bitrateKbps, perf.profile.maxBitrateCapKbps)
                        if (activeBitrateKbps < cap) {
                            val recovered = (activeBitrateKbps + 250).coerceAtMost(cap)
                            applyDynamicVideoBitrate(recovered)
                        }
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

                ZroStreamCoordinator.updateTelemetry { prev ->
                    prev.copy(
                        durationSec = elapsedSec,
                        actualBitrateKbps = kbps,
                        targetBitrateKbps = activeBitrateKbps,
                        actualFps = fpsNow,
                        droppedFrames = dropped,
                        totalFrames = total,
                        streamHealth = health,
                        thermalThrottled = thermalStepApplied,
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
        for (attempt in 1..4) {
            if (!isStreaming.get()) return
            ZroStreamCoordinator.updateTelemetry {
                it.copy(state = StreamLifecycleState.RECONNECTING, reconnectAttempt = attempt, streamHealth = StreamHealth.POOR)
            }
            refreshNotification()
            delay(attempt * 2000L)
            val res = publisher.connect(settings.rtmpUrl, settings.streamKey)
            if (res.isSuccess) {
                val sps = cachedSps
                val pps = cachedPps
                val asc = cachedAsc
                if (sps != null && pps != null) publisher.sendAvcSequenceHeader(sps, pps)
                if (asc != null) publisher.sendAacSequenceHeader(asc)
                requestVideoKeyFrame()
                ZroStreamCoordinator.updateTelemetry {
                    it.copy(state = if (isPaused.get()) StreamLifecycleState.PAUSED else StreamLifecycleState.LIVE, reconnectAttempt = 0)
                }
                refreshNotification()
                return
            }
        }
        stopAllCaptureAndBroadcast(error = "Stream disconnected after 4 reconnection attempts. Check your network stability.")
    }

    private fun applyDynamicVideoBitrate(newBitrateKbps: Int) {
        activeBitrateKbps = newBitrateKbps
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateKbps * 1000)
            }
            videoEncoder?.setParameters(params)
        }
    }

    private fun requestVideoKeyFrame() {
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            videoEncoder?.setParameters(params)
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
            val mirrorW = 640
            val mirrorH = 360
            val thread = HandlerThread("ZroDualMirror").apply { start() }
            mirrorHandlerThread = thread
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(mirrorW, mirrorH, PixelFormat.RGBA_8888, 2)
            mirrorImageReader = reader
            val baos = ByteArrayOutputStream(32 * 1024)
            var lastPushMs = 0L

            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (isPrivacyShieldActive.get() || isPaused.get()) return@setOnImageAvailableListener
                    if (DualPhoneEngine.shared.telemetry.value.linkState != DualLinkState.CONNECTED) {
                        return@setOnImageAvailableListener
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPushMs < 85L) return@setOnImageAvailableListener
                    lastPushMs = now

                    val plane = img.planes.firstOrNull() ?: return@setOnImageAvailableListener
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * mirrorW
                    val paddedW = mirrorW + rowPadding / pixelStride
                    val bmp = Bitmap.createBitmap(paddedW, mirrorH, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    val cropped = if (paddedW != mirrorW) {
                        Bitmap.createBitmap(bmp, 0, 0, mirrorW, mirrorH).also { bmp.recycle() }
                    } else {
                        bmp
                    }
                    baos.reset()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 68, baos)
                    cropped.recycle()
                    DualPhoneEngine.shared.pushMirroredFrameJpeg(baos.toByteArray(), mirrorW, mirrorH, minIntervalMs = 80L)
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
        stopDualPhoneMirrorCapture()
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        runCatching { mediaProjection?.stop() }; mediaProjection = null
        ZroStreamCoordinator.releaseCaptureLock()
    }

    private fun stopAllCaptureAndBroadcast(error: String?) {
        val wasStreaming = isStreaming.getAndSet(false)
        isPaused.set(false)
        isPrivacyShieldActive.set(false)

        val finalDuration = if (streamStartEpochMs > 0L) (System.currentTimeMillis() - streamStartEpochMs) / 1000L else 0L
        val finalDropped = droppedVideoFrames.get()
        val finalPeak = peakBitrateKbps.get()
        val finishedSessionId = activeSessionId
        activeSessionId = null
        streamStartEpochMs = 0L

        videoDrainJob?.cancel(); videoDrainJob = null
        audioDrainJob?.cancel(); audioDrainJob = null
        monitorJob?.cancel(); monitorJob = null

        stopDualPhoneMirrorCapture()
        ZroAudioEngine.stopStreamingAudio()
        runCatching { rtmpPublisher?.close() }; rtmpPublisher = null
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        runCatching { videoInputSurface?.release() }; videoInputSurface = null
        runCatching { videoEncoder?.stop() }; runCatching { videoEncoder?.release() }; videoEncoder = null
        runCatching { audioEncoder?.stop() }; runCatching { audioEncoder?.release() }; audioEncoder = null
        runCatching { mediaProjection?.stop() }; mediaProjection = null

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
                    peakBitrateKbps = finalPeak
                )
            )
        }

        if (isVoiceForeground.get()) {
            promoteForeground(includeMediaProjection = false)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun refreshNotification() {
        notifManager.updateNotification(
            telemetry = ZroStreamCoordinator.telemetry.value,
            micMuted = !ZroStreamCoordinator.currentAudioSettings.micEnabled,
            voiceRoomName = ZroStreamCoordinator.activeVoiceRoomName.value
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllCaptureAndBroadcast(error = null)
        serviceScope.cancel()
    }
}
