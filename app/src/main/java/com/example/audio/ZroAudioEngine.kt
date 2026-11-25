package com.example.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.model.AudioMeterState
import com.example.data.model.AudioOutputRoute
import com.example.data.model.AudioSettings
import com.example.data.model.NoiseCancellationLevel
import com.example.data.model.PerformanceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified, performance-optimized real-time audio engine for Zro live:
 * - Captures microphone via AudioRecord + hardware AEC/NS/AGC + CPU-efficient VoiceDspProcessor.
 * - Captures internal game audio via AudioPlaybackCaptureConfiguration (Android 10+ when MediaProjection active).
 * - Sends & receives real-time team voice UDP packets with pre-allocated buffers (zero per-frame heap churn).
 * - Throttles UI meter emissions (~12 Hz foreground, 0 Hz while gaming in background) to prevent Compose recomposition overhead.
 * - Guarantees complete AudioRecord / AudioTrack / Effect / Socket release when idle or stopped.
 */
object ZroAudioEngine {

    private const val TAG = "ZroAudioEngine"
    const val SAMPLE_RATE = 44100
    const val FRAME_SAMPLES = 1024 // ~23.2 ms per frame (exact AAC-LC frame size)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dsp = VoiceDspProcessor(SAMPLE_RATE)

    private val settingsRef = AtomicReference(AudioSettings())
    private val isCapturing = AtomicBoolean(false)
    private val isTestingMic = AtomicBoolean(false)
    private val isVoiceRoomActive = AtomicBoolean(false)
    private val isStreamingActive = AtomicBoolean(false)
    private val isUiForeground = AtomicBoolean(true)

    @Volatile private var lightweightDsp = false
    @Volatile private var meterEmitIntervalMs = 85L
    @Volatile private var lockedBroadcastStereo: Boolean? = null

    private var captureJob: Job? = null
    private var udpReceiveJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var gameAudioRecord: AudioRecord? = null
    private var playbackTrack: AudioTrack? = null
    private var aecEffect: AcousticEchoCanceler? = null
    private var nsEffect: NoiseSuppressor? = null
    private var agcEffect: AutomaticGainControl? = null

    private val broadcastConsumer = AtomicReference<((ByteArray, Int, Long) -> Unit)?>(null)
    private val speakingCallback = AtomicReference<((Boolean, Int) -> Unit)?>(null)

    // Pre-allocated UDP send scratch buffer & cached InetAddress map to avoid per-packet GC allocations
    private val udpSendScratch = ByteArray(36 + FRAME_SAMPLES * 2)
    private val resolvedPeerAddresses = ConcurrentHashMap<String, InetAddress>()
    @Volatile private var cachedUidHeader: ByteArray? = null
    @Volatile private var cachedUidStr: String? = null

    data class TeammatePeerConfig(
        val uid: String,
        val ip: String?,
        val port: Int,
        val locallyMuted: Boolean = false,
        val volume: Float = 1.0f,
        val streamConsent: Boolean = true
    )

    private val peerConfigs = ConcurrentHashMap<String, TeammatePeerConfig>()
    private val peerLatestFrames = ConcurrentHashMap<String, ShortArray>()
    private val peerLastPacketMs = ConcurrentHashMap<String, Long>()

    private val _meterState = MutableStateFlow(
        AudioMeterState(
            hardwareNsSupported = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false),
            hardwareAecSupported = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false),
            hardwareAgcSupported = runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)
        )
    )
    val meterState: StateFlow<AudioMeterState> = _meterState.asStateFlow()

    @Volatile
    private var localSenderId: String = "zro_${java.util.UUID.randomUUID().toString().take(8)}"

    private val _gameCaptureStatus = MutableStateFlow("Ready (Android 10+ games permitting capture)")
    val gameCaptureStatus: StateFlow<String> = _gameCaptureStatus.asStateFlow()

    @Volatile
    var localUdpEndpoint: String? = null
        private set

    fun setUiForeground(foreground: Boolean) {
        isUiForeground.set(foreground)
    }

    fun applyPerformanceSettings(perf: PerformanceSettings) {
        lightweightDsp = perf.lowRamMode || perf.profile.lightweightDsp
        meterEmitIntervalMs = if (perf.lowRamMode) {
            maxOf(perf.profile.meterIntervalMs, 180L)
        } else {
            perf.profile.meterIntervalMs
        }
    }

    fun updateSettings(context: Context, settings: AudioSettings) {
        val prev = settingsRef.getAndSet(settings)
        applyHardwareEffects(settings)
        if (prev.outputRoute != settings.outputRoute) {
            applyOutputRoute(context, settings.outputRoute)
        }
    }

    fun setBroadcastPcmConsumer(consumer: ((ByteArray, Int, Long) -> Unit)?) {
        broadcastConsumer.set(consumer)
    }

    fun setSpeakingObserver(observer: ((Boolean, Int) -> Unit)?) {
        speakingCallback.set(observer)
    }

    fun updateTeammatePeers(peers: List<TeammatePeerConfig>) {
        val activeIds = peers.map { it.uid }.toSet()
        peerConfigs.keys.filter { it !in activeIds }.forEach {
            peerConfigs.remove(it)
            peerLatestFrames.remove(it)
            peerLastPacketMs.remove(it)
            resolvedPeerAddresses.remove(it)
        }
        peers.forEach { cfg ->
            peerConfigs[cfg.uid] = cfg
            if (!cfg.ip.isNullOrBlank()) {
                runCatching { resolvedPeerAddresses[cfg.uid] = InetAddress.getByName(cfg.ip) }
            }
        }
    }

    fun startMicTest(context: Context): Boolean {
        if (!hasMicPermission(context)) return false
        isTestingMic.set(true)
        _meterState.update { it.copy(isTestingMic = true) }
        ensureCaptureRunning(context, mediaProjection = null)
        return true
    }

    fun stopMicTest() {
        isTestingMic.set(false)
        _meterState.update { it.copy(isTestingMic = false) }
        stopIfIdle()
    }

    fun startVoiceRoomAudio(context: Context): String? {
        if (!hasMicPermission(context)) return null
        isVoiceRoomActive.set(true)
        startUdpTransport()
        applyOutputRoute(context, settingsRef.get().outputRoute)
        ensureCaptureRunning(context, mediaProjection = null)
        return localUdpEndpoint
    }

    fun stopVoiceRoomAudio() {
        isVoiceRoomActive.set(false)
        peerConfigs.clear()
        peerLatestFrames.clear()
        peerLastPacketMs.clear()
        resolvedPeerAddresses.clear()
        stopUdpTransport()
        stopIfIdle()
    }

    fun startStreamingAudio(
        context: Context,
        mediaProjection: MediaProjection?,
        forceStereo: Boolean? = null
    ) {
        lockedBroadcastStereo = forceStereo
        isStreamingActive.set(true)
        if (mediaProjection != null) {
            attachGameAudioCapture(context, mediaProjection)
        }
        if (hasMicPermission(context)) {
            ensureCaptureRunning(context, mediaProjection)
        }
    }

    fun stopStreamingAudio() {
        isStreamingActive.set(false)
        lockedBroadcastStereo = null
        broadcastConsumer.set(null)
        releaseGameAudioRecord()
        stopIfIdle()
    }

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun attachGameAudioCapture(context: Context, mediaProjection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _gameCaptureStatus.value = "Android 10+ required for internal game audio capture"
            return
        }
        if (!hasMicPermission(context)) {
            _gameCaptureStatus.value = "Microphone permission required for audio playback capture"
            return
        }
        releaseGameAudioRecord()
        try {
            val record = Api29GameAudioHelper.createGameAudioRecord(mediaProjection, SAMPLE_RATE, FRAME_SAMPLES)
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record.startRecording()
                gameAudioRecord = record
                _gameCaptureStatus.value = "Active (capturing games allowing playback capture)"
            } else {
                record.release()
                _gameCaptureStatus.value = "Unavailable on this device configuration"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Game audio playback capture unavailable: ${e.message}")
            _gameCaptureStatus.value = "Blocked by device or game manifest restriction"
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private object Api29GameAudioHelper {
        @SuppressLint("MissingPermission")
        fun createGameAudioRecord(
            mediaProjection: MediaProjection,
            sampleRate: Int,
            frameSamples: Int
        ): AudioRecord {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(frameSamples * 4)

            return AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureCaptureRunning(context: Context, mediaProjection: MediaProjection?) {
        if (mediaProjection != null && gameAudioRecord == null) {
            attachGameAudioCapture(context, mediaProjection)
        }
        if (!isCapturing.compareAndSet(false, true)) return

        captureJob = scope.launch {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(FRAME_SAMPLES * 4)

                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    record.release()
                    isCapturing.set(false)
                    return@launch
                }
                audioRecord = record
                attachHardwareEffects(record.audioSessionId, settingsRef.get())
                // Only allocate AudioTrack when loopback or voice room playback is actually needed
                if (isTestingMic.get() || isVoiceRoomActive.get()) {
                    initPlaybackTrack()
                }
                record.startRecording()

                val micBuf = ShortArray(FRAME_SAMPLES)
                val gameBuf = ShortArray(FRAME_SAMPLES)
                val teamPlaybackBuf = ShortArray(FRAME_SAMPLES)
                val teamBroadcastBuf = ShortArray(FRAME_SAMPLES)
                val masterStereoBytes = ByteArray(FRAME_SAMPLES * 4)
                val masterMonoBytes = ByteArray(FRAME_SAMPLES * 2)
                val stereoByteBuf = ByteBuffer.wrap(masterStereoBytes).order(ByteOrder.LITTLE_ENDIAN)
                val monoByteBuf = ByteBuffer.wrap(masterMonoBytes).order(ByteOrder.LITTLE_ENDIAN)

                var lastSpeakingPushMs = 0L
                var lastMeterEmitMs = 0L
                var prevSpeaking = false

                while (isActive && isCapturing.get()) {
                    val read = record.read(micBuf, 0, FRAME_SAMPLES)
                    if (read <= 0) continue

                    val currentSettings = settingsRef.get()
                    val isLight = lightweightDsp
                    val micMetrics = dsp.processInPlace(micBuf, read, currentSettings, lightweightMode = isLight)

                    // Send processed voice over UDP when in a Voice Room and mic is enabled
                    if (isVoiceRoomActive.get() && currentSettings.micEnabled && micMetrics.speakingDetected) {
                        sendUdpVoiceFrame(micBuf, read)
                    }

                    // Read internal game audio (non-blocking)
                    var gameRead = 0
                    val gRec = gameAudioRecord
                    if (gRec != null && currentSettings.gameAudioEnabled) {
                        gameRead = try {
                            gRec.read(gameBuf, 0, read, AudioRecord.READ_NON_BLOCKING)
                        } catch (_: Exception) {
                            0
                        }
                    }

                    // Mix incoming teammate frames only when voice room is active
                    val nowMs = System.currentTimeMillis()
                    var activeTeamAudio = false
                    if (isVoiceRoomActive.get() && peerConfigs.isNotEmpty()) {
                        teamPlaybackBuf.fill(0)
                        teamBroadcastBuf.fill(0)
                        for ((uid, cfg) in peerConfigs) {
                            val lastMs = peerLastPacketMs[uid] ?: continue
                            if (nowMs - lastMs > 300L) continue
                            val frame = peerLatestFrames[uid] ?: continue
                            if (cfg.locallyMuted || !currentSettings.teamVoiceEnabled) continue
                            activeTeamAudio = true
                            val gain = cfg.volume * currentSettings.teamVolume
                            val len = minOf(read, frame.size)
                            for (i in 0 until len) {
                                val sample = (frame[i] * gain).toInt()
                                teamPlaybackBuf[i] = (teamPlaybackBuf[i] + sample).coerceIn(-32768, 32767).toShort()
                                if (cfg.streamConsent) {
                                    teamBroadcastBuf[i] = (teamBroadcastBuf[i] + sample).coerceIn(-32768, 32767).toShort()
                                }
                            }
                        }
                    }

                    // Play to user headphones/speaker: Mic test loopback OR incoming teammate voice
                    if (isTestingMic.get() || activeTeamAudio) {
                        if (playbackTrack == null) initPlaybackTrack()
                        val track = playbackTrack
                        if (track != null) {
                            if (isTestingMic.get()) {
                                track.write(micBuf, 0, read, AudioTrack.WRITE_NON_BLOCKING)
                            } else if (activeTeamAudio) {
                                track.write(teamPlaybackBuf, 0, read, AudioTrack.WRITE_NON_BLOCKING)
                            }
                        }
                    }

                    // Mix Master Livestream Bus (only allocate/encode bytes when consumer is attached or UI meter due)
                    val consumer = broadcastConsumer.get()
                    val shouldEmitMeter = isUiForeground.get() && (nowMs - lastMeterEmitMs >= meterEmitIntervalMs)
                    if (consumer != null || shouldEmitMeter) {
                        var masterSumSq = 0f
                        val stereo = lockedBroadcastStereo ?: currentSettings.stereoOutput
                        val byteBuf = if (stereo) stereoByteBuf else monoByteBuf
                        byteBuf.clear()

                        for (i in 0 until read) {
                            val micVal = if (currentSettings.micEnabled) micBuf[i] / 32768f else 0f
                            val gameVal = if (i < gameRead && currentSettings.gameAudioEnabled) {
                                (gameBuf[i] / 32768f) * currentSettings.gameVolume
                            } else 0f
                            val teamVal = if (activeTeamAudio && currentSettings.teamVoiceEnabled && currentSettings.teamVoiceToLive) {
                                teamBroadcastBuf[i] / 32768f
                            } else 0f

                            val mixedShort = mixLiveBroadcastSample(
                                micNormalized = micVal,
                                gameNormalized = gameVal,
                                teamNormalized = teamVal,
                                settings = currentSettings
                            )
                            if (shouldEmitMeter && (!isLight || (i and 1) == 0)) {
                                val normMixed = mixedShort / 32768f
                                masterSumSq += normMixed * normMixed
                            }
                            if (consumer != null) {
                                byteBuf.putShort(mixedShort)
                                if (stereo) byteBuf.putShort(mixedShort)
                            }
                        }

                        if (consumer != null) {
                            val outBytes = if (stereo) masterStereoBytes else masterMonoBytes
                            val outLen = if (stereo) read * 4 else read * 2
                            consumer.invoke(outBytes, outLen, System.nanoTime() / 1000L)
                        }

                        // Throttled UI meter state update (skips completely when gaming in background!)
                        if (shouldEmitMeter) {
                            lastMeterEmitMs = nowMs
                            val stride = if (isLight) 2 else 1
                            val (gameNorm, gameDb) = if (gameRead > 0 && currentSettings.gameAudioEnabled) {
                                VoiceDspProcessor.computeRmsNormalized(gameBuf, gameRead, stride)
                            } else 0f to -60f
                            val (teamNorm, teamDb) = if (activeTeamAudio) {
                                VoiceDspProcessor.computeRmsNormalized(teamPlaybackBuf, read, stride)
                            } else 0f to -60f

                            val rmsSamples = if (isLight) maxOf(1, (read + 1) / 2) else read
                            val masterRms = kotlin.math.sqrt(masterSumSq / rmsSamples)
                            val masterDb = VoiceDspProcessor.linearToDb(masterRms)
                            val masterNorm = ((masterDb + 60f) / 60f).coerceIn(0f, 1f)

                            _meterState.update { prev ->
                                prev.copy(
                                    micLevel = micMetrics.normalizedLevel,
                                    micPeak = micMetrics.peakLevel,
                                    micDb = micMetrics.dbFs,
                                    gameLevel = gameNorm,
                                    gameDb = gameDb,
                                    teamLevel = teamNorm,
                                    teamDb = teamDb,
                                    masterLevel = masterNorm,
                                    masterPeak = maxOf(masterNorm, prev.masterPeak * 0.90f),
                                    masterDb = masterDb,
                                    speakingDetected = micMetrics.speakingDetected,
                                    gateOpen = micMetrics.gateOpen,
                                    teamVoiceSourceAvailable = isVoiceRoomActive.get() && peerConfigs.isNotEmpty()
                                )
                            }
                        }
                    }

                    // Throttled speaking state notification for Firestore Voice Room presence
                    if (isVoiceRoomActive.get() && (micMetrics.speakingDetected != prevSpeaking || nowMs - lastSpeakingPushMs > 1800L)) {
                        prevSpeaking = micMetrics.speakingDetected
                        lastSpeakingPushMs = nowMs
                        val levelInt = (micMetrics.normalizedLevel * 100f).toInt().coerceIn(0, 100)
                        speakingCallback.get()?.invoke(micMetrics.speakingDetected, levelInt)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture loop error: ${e.message}", e)
            } finally {
                releaseCaptureResources()
                isCapturing.set(false)
            }
        }
    }

    private fun initPlaybackTrack() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(FRAME_SAMPLES * 4)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
                playbackTrack = track
            } else {
                track.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack init warning: ${e.message}")
        }
    }

    private fun attachHardwareEffects(sessionId: Int, settings: AudioSettings) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aecEffect = AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = settings.echoCancellation
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                nsEffect = NoiseSuppressor.create(sessionId)?.apply {
                    enabled = settings.noiseSuppression && settings.noiseCancellationLevel != NoiseCancellationLevel.OFF
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                agcEffect = AutomaticGainControl.create(sessionId)?.apply {
                    enabled = settings.automaticGainControl
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hardware audio effect attachment warning: ${e.message}")
        }
    }

    private fun applyHardwareEffects(settings: AudioSettings) {
        runCatching { aecEffect?.enabled = settings.echoCancellation }
        runCatching {
            nsEffect?.enabled = settings.noiseSuppression && settings.noiseCancellationLevel != NoiseCancellationLevel.OFF
        }
        runCatching { agcEffect?.enabled = settings.automaticGainControl }
    }

    @Suppress("DEPRECATION")
    fun applyOutputRoute(context: Context, route: AudioOutputRoute) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            when (route) {
                AudioOutputRoute.SPEAKER -> {
                    am.isSpeakerphoneOn = true
                }
                AudioOutputRoute.EARPIECE, AudioOutputRoute.HEADSET -> {
                    am.isSpeakerphoneOn = false
                }
                AudioOutputRoute.BLUETOOTH -> {
                    am.isSpeakerphoneOn = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Api31AudioRouteHelper.routeBluetooth(am)
                    } else {
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                    }
                }
                AudioOutputRoute.AUTO -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Api31AudioRouteHelper.clearRoute(am)
                    } else {
                        am.stopBluetoothSco()
                        am.isBluetoothScoOn = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio route update warning: ${e.message}")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private object Api31AudioRouteHelper {
        fun routeBluetooth(am: AudioManager) {
            val btDevice = am.availableCommunicationDevices.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (btDevice != null) am.setCommunicationDevice(btDevice)
        }

        fun clearRoute(am: AudioManager) {
            am.clearCommunicationDevice()
        }
    }

    // --- ZERO-ALLOCATION UDP PEER-TO-PEER VOICE TRANSPORT ---

    private fun startUdpTransport() {
        if (udpSocket != null) return
        try {
            val socket = DatagramSocket(0)
            socket.soTimeout = 1000
            udpSocket = socket
            val localIp = findLocalIpv4Address()
            localUdpEndpoint = if (localIp != null) "$localIp:${socket.localPort}" else null

            udpReceiveJob = scope.launch {
                val packetBuf = ByteArray(FRAME_SAMPLES * 2 + 48)
                val packet = DatagramPacket(packetBuf, packetBuf.size)
                while (isActive && isVoiceRoomActive.get()) {
                    try {
                        socket.receive(packet)
                        if (packet.length <= 36) continue
                        val senderUid = String(packet.data, 0, 36, Charsets.UTF_8).trim()
                        val sampleCount = minOf((packet.length - 36) / 2, FRAME_SAMPLES)
                        if (senderUid.isNotBlank() && sampleCount > 0) {
                            // Reuse existing ShortArray per peer to eliminate GC churn
                            val reusable = peerLatestFrames.computeIfAbsent(senderUid) { ShortArray(FRAME_SAMPLES) }
                            val data = packet.data
                            var byteIdx = 36
                            for (i in 0 until sampleCount) {
                                val lo = data[byteIdx].toInt() and 0xFF
                                val hi = data[byteIdx + 1].toInt()
                                reusable[i] = ((hi shl 8) or lo).toShort()
                                byteIdx += 2
                            }
                            peerLastPacketMs[senderUid] = System.currentTimeMillis()
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Normal receive timeout
                    } catch (e: Exception) {
                        if (isVoiceRoomActive.get()) {
                            Log.w(TAG, "UDP receive warning: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "UDP voice socket unavailable: ${e.message}")
        }
    }

    private fun sendUdpVoiceFrame(samples: ShortArray, count: Int) {
        val socket = udpSocket ?: return
        if (peerConfigs.isEmpty()) return
        val myUid = localSenderId

        val header = if (cachedUidStr == myUid && cachedUidHeader != null) {
            cachedUidHeader!!
        } else {
            myUid.padEnd(36, ' ').take(36).toByteArray(Charsets.UTF_8).also {
                cachedUidStr = myUid
                cachedUidHeader = it
            }
        }

        val safeCount = minOf(count, FRAME_SAMPLES)
        val payloadLen = 36 + safeCount * 2
        System.arraycopy(header, 0, udpSendScratch, 0, 36)
        var byteIdx = 36
        for (i in 0 until safeCount) {
            val s = samples[i].toInt()
            udpSendScratch[byteIdx] = (s and 0xFF).toByte()
            udpSendScratch[byteIdx + 1] = ((s ushr 8) and 0xFF).toByte()
            byteIdx += 2
        }

        for ((uid, target) in peerConfigs) {
            if (target.port <= 0) continue
            val addr = resolvedPeerAddresses[uid] ?: continue
            runCatching {
                socket.send(DatagramPacket(udpSendScratch, payloadLen, addr, target.port))
            }
        }
    }

    private fun stopUdpTransport() {
        udpReceiveJob?.cancel()
        udpReceiveJob = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        localUdpEndpoint = null
    }

    private fun findLocalIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun stopIfIdle() {
        if (!isTestingMic.get() && !isVoiceRoomActive.get() && !isStreamingActive.get()) {
            isCapturing.set(false)
            captureJob?.cancel()
            captureJob = null
            releaseCaptureResources()
            _meterState.update {
                it.copy(
                    micLevel = 0f,
                    micPeak = 0f,
                    micDb = -60f,
                    gameLevel = 0f,
                    gameDb = -60f,
                    teamLevel = 0f,
                    teamDb = -60f,
                    masterLevel = 0f,
                    masterPeak = 0f,
                    masterDb = -60f,
                    speakingDetected = false
                )
            }
        }
    }

    private fun releaseGameAudioRecord() {
        runCatching { gameAudioRecord?.stop() }
        runCatching { gameAudioRecord?.release() }
        gameAudioRecord = null
    }

    /**
     * Pure live broadcast bus mixer helper:
     * - Applies masterVolume scaling and soft-clipping.
     * - Enforces TEAM VOICE TO LIVE strictly: when settings.teamVoiceToLive is false,
     *   teammate voice is excluded from the live RTMP bus even if teamNormalized != 0.
     */
    fun mixLiveBroadcastSample(
        micNormalized: Float,
        gameNormalized: Float,
        teamNormalized: Float,
        settings: AudioSettings
    ): Short {
        val mic = if (settings.micEnabled) micNormalized else 0f
        val game = if (settings.gameAudioEnabled) gameNormalized else 0f
        val team = if (settings.teamVoiceEnabled && settings.teamVoiceToLive) teamNormalized else 0f
        val master = settings.masterVolume.coerceIn(0f, 1.5f)
        return VoiceDspProcessor.softClipMix((mic + game + team) * master)
    }

    private fun releaseCaptureResources() {
        runCatching { aecEffect?.release() }; aecEffect = null
        runCatching { nsEffect?.release() }; nsEffect = null
        runCatching { agcEffect?.release() }; agcEffect = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }; audioRecord = null
        runCatching { playbackTrack?.stop() }
        runCatching { playbackTrack?.release() }; playbackTrack = null
        releaseGameAudioRecord()
    }
}
