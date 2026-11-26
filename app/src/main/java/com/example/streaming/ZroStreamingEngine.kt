package com.example.streaming

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import com.example.audio.ZroAudioEngine
import com.example.data.model.LiveBroadcastMode
import com.example.data.model.StreamSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * Production-grade Android RTMP + MediaProjection streaming core for Zro live.
 *
 * Threading & Looper guarantee:
 * - Owns a dedicated [HandlerThread] ("ZroLiveEngineThread") started immediately at creation.
 * - Every [MediaProjection.registerCallback], [MediaProjection.createVirtualDisplay], and
 *   engine-level Android callback is explicitly bound to [engineHandler] (never `null` and never
 *   running bare `Handler()` on `Dispatchers.IO` / `DefaultDispatcher-worker-*`).
 * - Every UI / Service lifecycle / main-looper callback is dispatched via [mainHandler]
 *   backed by [Looper.getMainLooper].
 */
class ZroStreamingEngine(
    threadName: String = ENGINE_THREAD_NAME
) {

    companion object {
        private const val TAG = "ZroStreamingEngine"
        const val ENGINE_THREAD_NAME = "ZroLiveEngineThread"

        /**
         * Pure jittered exponential backoff calculation for RTMP auto-reconnection.
         */
        fun computeReconnectBackoffMs(attempt: Int, random: Random = Random.Default): Long {
            val safeAttempt = attempt.coerceIn(1, 10)
            val baseMs = (1500L shl (safeAttempt - 1)).coerceAtMost(10_000L)
            val jitterMs = random.nextLong(250L, 1200L)
            return baseMs + jitterMs
        }
    }

    data class PreflightResult(
        val ready: Boolean,
        val errorMessage: String? = null,
        val warnings: List<String> = emptyList(),
        val micPermissionGranted: Boolean = false
    )

    data class VideoEncoderHandle(
        val codec: MediaCodec,
        val inputSurface: Surface,
        val encoderName: String
    )

    private val released = AtomicBoolean(false)
    private val engineThread: HandlerThread = HandlerThread(threadName).apply {
        start()
    }

    /** Dedicated Handler bound to [ENGINE_THREAD_NAME]'s active [Looper]. */
    val engineHandler: Handler = Handler(engineThread.looper)

    /** Main-thread Handler bound explicitly to [Looper.getMainLooper]. */
    val mainHandler: Handler = Handler(Looper.getMainLooper())

    val engineLooper: Looper
        get() = engineThread.looper

    val engineThreadName: String
        get() = engineThread.name

    val isEngineLooperActive: Boolean
        get() = !released.get() && engineThread.isAlive && engineThread.looper != null

    /**
     * Posts work safely onto the dedicated [ENGINE_THREAD_NAME] looper thread.
     */
    fun postOnEngineThread(action: () -> Unit): Boolean {
        if (released.get() || !engineThread.isAlive) return false
        return engineHandler.post {
            if (!released.get()) {
                action()
            }
        }
    }

    /**
     * Posts work safely onto Android's main thread ([Looper.getMainLooper]).
     */
    fun postOnMainThread(action: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return true
        }
        return mainHandler.post(action)
    }

    /**
     * Suspends and executes [block] on [ENGINE_THREAD_NAME] where a valid [Looper] is guaranteed.
     * Prevents `Can't create handler inside thread DefaultDispatcher-worker-* that has not called Looper.prepare()`.
     */
    suspend fun <T> runOnEngineThread(block: () -> T): T {
        if (released.get() || !engineThread.isAlive) {
            throw IllegalStateException("ZroStreamingEngine has been released.")
        }
        if (Looper.myLooper() == engineThread.looper) {
            return block()
        }
        return suspendCancellableCoroutine { cont ->
            val posted = engineHandler.post {
                if (!cont.isActive) return@post
                try {
                    cont.resume(block())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }
            if (!posted && cont.isActive) {
                cont.resumeWithException(IllegalStateException("Unable to dispatch onto $ENGINE_THREAD_NAME."))
            }
        }
    }

    /**
     * Strict pre-flight validation before initializing MediaProjection, hardware encoders, or RTMP sockets.
     */
    fun validatePreflight(
        context: Context,
        settings: StreamSettings,
        hasLocalProjectionGrant: Boolean,
        secondPhoneConnected: Boolean,
        hasActiveInternet: Boolean = checkActiveInternet(context),
        dualLiveSupportCheck: (Context) -> Pair<Boolean, String> = StreamingForegroundService::checkDualLiveSupport
    ): PreflightResult {
        if (!isEngineLooperActive) {
            return PreflightResult(
                ready = false,
                errorMessage = "Streaming engine looper is not active. Restart live setup and try again."
            )
        }

        if (!hasLocalProjectionGrant && !secondPhoneConnected) {
            return PreflightResult(
                ready = false,
                errorMessage = "Grant screen capture permission or connect a 2nd phone to start live."
            )
        }

        val primaryUrl = settings.effectivePrimaryServerUrl()
        val backupUrl = settings.effectiveBackupServerUrl()
        val configValidation = RtmpPublisher.validateConfig(primaryUrl, backupUrl, settings.streamKey)
        if (!configValidation.valid) {
            return PreflightResult(
                ready = false,
                errorMessage = configValidation.errorMessage ?: "Invalid RTMP stream configuration."
            )
        }

        if (settings.broadcastMode == LiveBroadcastMode.DUAL_LIVE) {
            val (dualSupported, dualReason) = dualLiveSupportCheck(context)
            if (!dualSupported) {
                return PreflightResult(ready = false, errorMessage = dualReason)
            }
            val shortsUrl = settings.effectiveShortsServerUrl()
            val shortsValidation = RtmpPublisher.validateConfig(shortsUrl, "", settings.shortsStreamKey)
            if (!shortsValidation.valid) {
                return PreflightResult(
                    ready = false,
                    errorMessage = "Dual Live requires a valid Shorts 9:16 RTMP URL & Stream Key. ${shortsValidation.errorMessage ?: ""}".trim()
                )
            }
            if (shortsUrl.equals(primaryUrl, ignoreCase = true) &&
                settings.shortsStreamKey.trim() == settings.streamKey.trim()
            ) {
                return PreflightResult(
                    ready = false,
                    errorMessage = "Dual Live requires two separate Stream Keys (one for 16:9 Live and one for 9:16 Shorts Live)."
                )
            }
        }

        if (!hasActiveInternet) {
            return PreflightResult(
                ready = false,
                errorMessage = "No active internet connection detected. Connect to Wi-Fi or mobile data and try again."
            )
        }

        val micGranted = runCatching { ZroAudioEngine.hasMicPermission(context) }.getOrDefault(false)
        val warnings = mutableListOf<String>()
        if (!micGranted) {
            warnings.add("Microphone permission is not granted; streaming with silent AAC audio track.")
        }

        return PreflightResult(
            ready = true,
            errorMessage = null,
            warnings = warnings,
            micPermissionGranted = micGranted
        )
    }

    fun checkActiveInternet(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Acquires [MediaProjection] and registers its callback explicitly on [engineHandler] (never `null`).
     * Caller MUST have already started the Foreground Service with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`.
     */
    suspend fun acquireMediaProjection(
        context: Context,
        resultCode: Int,
        resultData: Intent,
        onStopped: () -> Unit,
        onResized: (Int, Int) -> Unit,
        onVisibilityChanged: (Boolean) -> Unit
    ): MediaProjection {
        if (resultCode != Activity.RESULT_OK) {
            throw IllegalStateException("MediaProjection permission was not granted.")
        }
        return runOnEngineThread {
            val projMgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projMgr.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection could not be initialized.")
            registerProjectionCallback(
                projection = projection,
                onStopped = onStopped,
                onResized = onResized,
                onVisibilityChanged = onVisibilityChanged
            )
            projection
        }
    }

    /**
     * Registers [MediaProjection.Callback] bound explicitly to [engineHandler] on both API 29-33 and API 34+.
     * Eliminates bare `Handler()` creation on coroutine worker threads.
     */
    fun registerProjectionCallback(
        projection: MediaProjection,
        onStopped: () -> Unit,
        onResized: (Int, Int) -> Unit,
        onVisibilityChanged: (Boolean) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34ProjectionCallbackHelper.register(
                projection = projection,
                handler = engineHandler,
                onStopped = onStopped,
                onResized = onResized,
                onVisibilityChanged = onVisibilityChanged
            )
        } else {
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() = onStopped()
                },
                engineHandler
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34ProjectionCallbackHelper {
        fun register(
            projection: MediaProjection,
            handler: Handler,
            onStopped: () -> Unit,
            onResized: (Int, Int) -> Unit,
            onVisibilityChanged: (Boolean) -> Unit
        ) {
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() = onStopped()
                    override fun onCapturedContentResize(width: Int, height: Int) = onResized(width, height)
                    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) = onVisibilityChanged(isVisible)
                },
                handler
            )
        }
    }

    /**
     * Creates or updates a [VirtualDisplay] with explicit [engineHandler] binding (never `null`).
     */
    fun createOrUpdateVirtualDisplay(
        projection: MediaProjection,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface?,
        existing: VirtualDisplay?,
        onStopped: (() -> Unit)? = null
    ): VirtualDisplay? {
        if (existing != null) {
            val updated = runCatching {
                existing.resize(width, height, densityDpi)
                existing.surface = surface
                existing
            }.getOrNull()
            if (updated != null) return updated
            runCatching { existing.release() }
        }

        val callback = object : VirtualDisplay.Callback() {
            override fun onStopped() {
                Log.i(TAG, "VirtualDisplay '$name' stopped")
                onStopped?.invoke()
            }
        }

        return projection.createVirtualDisplay(
            name,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            callback,
            engineHandler
        )
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

    /**
     * Initializes an H.264/AVC [MediaCodec] encoder with CBR -> VBR -> default fallback cascade
     * and repeat-previous-frame keepalive so static gameplay frames never stall YouTube Live.
     */
    fun createVideoEncoder(
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
        preferHardware: Boolean = true
    ): VideoEncoderHandle {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }

        val hwName = if (preferHardware) selectHardwareAvcEncoderName() else null
        var codec = try {
            if (hwName != null) MediaCodec.createByCodecName(hwName)
            else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (e: Exception) {
            Log.w(TAG, "Hardware encoder $hwName failed to open, using default AVC encoder: ${e.message}")
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }

        val configured = try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            true
        } catch (e: Exception) {
            Log.w(TAG, "CBR configure rejected, retrying VBR: ${e.message}")
            try {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                true
            } catch (vbrErr: Exception) {
                Log.w(TAG, "Hardware VBR configure failed, falling back to default AVC encoder: ${vbrErr.message}")
                runCatching { codec.release() }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val fallbackFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                }
                codec.configure(fallbackFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                true
            }
        }
        if (!configured) {
            runCatching { codec.release() }
            throw IllegalStateException("Could not configure H.264 video encoder on this device.")
        }

        val inputSurface = codec.createInputSurface()
        codec.start()
        return VideoEncoderHandle(
            codec = codec,
            inputSurface = inputSurface,
            encoderName = codec.name
        )
    }

    /**
     * Initializes an AAC-LC audio [MediaCodec] encoder (44.1 kHz, 128 kbps stereo / 96 kbps mono).
     */
    fun createAudioEncoder(
        sampleRate: Int = ZroAudioEngine.SAMPLE_RATE,
        stereo: Boolean = true
    ): MediaCodec {
        val channels = if (stereo) 2 else 1
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (stereo) 128_000 else 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        return codec
    }

    /**
     * Cleanly stops the dedicated [HandlerThread] looper.
     */
    fun shutdown() {
        if (!released.compareAndSet(false, true)) return
        runCatching { engineHandler.removeCallbacksAndMessages(null) }
        runCatching { mainHandler.removeCallbacksAndMessages(null) }
        runCatching { engineThread.quitSafely() }
    }
}
