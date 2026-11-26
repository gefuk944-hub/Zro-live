package com.example.data.model

import androidx.annotation.Keep
import com.example.ui.theme.ZroThemeStyle
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

// --- STREAMING QUALITY, PERFORMANCE PROFILES & CAPTURE MODES ---

enum class PerformanceProfile(
    val label: String,
    val shortLabel: String,
    val description: String,
    val telemetryIntervalMs: Long,
    val meterIntervalMs: Long,
    val maxBitrateCapKbps: Int,
    val lightweightDsp: Boolean,
    val reduceUiAnimations: Boolean
) {
    PERFORMANCE(
        label = "Performance",
        shortLabel = "Perf",
        description = "Minimal UI overhead, throttled background polling, hardware-first encoding up to 40 Mbps where supported.",
        telemetryIntervalMs = 15_000L,
        meterIntervalMs = 180L,
        maxBitrateCapKbps = 40_000,
        lightweightDsp = true,
        reduceUiAnimations = true
    ),
    BALANCED(
        label = "Balanced",
        shortLabel = "Balanced",
        description = "Recommended stable streaming quality, full voice DSP, and smart thermal protection.",
        telemetryIntervalMs = 8_000L,
        meterIntervalMs = 85L,
        maxBitrateCapKbps = 20_000,
        lightweightDsp = false,
        reduceUiAnimations = false
    ),
    BATTERY_SAVER(
        label = "Battery Saver",
        shortLabel = "Battery",
        description = "Lower bitrate ceiling, lightweight audio DSP, minimal animations & reduced thermal heat.",
        telemetryIntervalMs = 20_000L,
        meterIntervalMs = 220L,
        maxBitrateCapKbps = 3_500,
        lightweightDsp = true,
        reduceUiAnimations = true
    );

    companion object {
        fun fromName(raw: String?): PerformanceProfile =
            entries.firstOrNull { it.name == raw } ?: BALANCED
    }
}

enum class CaptureMode(
    val label: String,
    val shortLabel: String,
    val description: String
) {
    GAME_APP_ONLY(
        label = "Game / App Only (Private)",
        shortLabel = "Game Only",
        description = "Android 14+ single-app capture. Streams only your selected game window—excludes notifications, home screen, and other apps."
    ),
    FULL_SCREEN(
        label = "Full Screen (Opt-In)",
        shortLabel = "Full Screen",
        description = "Captures your entire phone display including notifications and other apps. Disabled by default for privacy."
    );

    companion object {
        fun fromName(raw: String?): CaptureMode =
            entries.firstOrNull { it.name == raw } ?: FULL_SCREEN
    }
}

enum class VideoResolution(
    val label: String,
    val shortLabel: String,
    val width: Int,
    val height: Int,
    val defaultBitrateKbps: Int
) {
    P144("144p", "144p", 256, 144, 1000),
    P240("240p", "240p", 426, 240, 1200),
    P360("360p", "360p", 640, 360, 1800),
    P480("480p", "480p", 854, 480, 2800),
    P720("720p HD", "720p", 1280, 720, 4500),
    P1080("1080p Full HD", "1080p", 1920, 1080, 7500),
    P1440("1440p QHD", "1440p", 2560, 1440, 14000),
    P2160("4K UHD", "4K", 3840, 2160, 24000);

    companion object {
        fun fromName(raw: String?): VideoResolution =
            entries.firstOrNull { it.name == raw } ?: P720
    }
}

enum class StreamPreset(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val resolution: VideoResolution,
    val fps: Int,
    val recommendedBitrateKbps: Int,
    val requiresHighCapability: Boolean = false
) {
    DATA_SAVER("DATA_SAVER", "Data Saver", "144p • 30 FPS", VideoResolution.P144, 30, 1000),
    LOW("LOW", "Low", "360p • 30 FPS", VideoResolution.P360, 30, 1800),
    BALANCED("BALANCED", "Balanced", "480p • 30 FPS", VideoResolution.P480, 30, 2800),
    HD("HD", "HD", "720p • 30 FPS", VideoResolution.P720, 30, 4500),
    HD_SMOOTH("HD_SMOOTH", "HD Smooth", "720p • 60 FPS", VideoResolution.P720, 60, 6000),
    FULL_HD("FULL_HD", "Full HD", "1080p • 30 FPS", VideoResolution.P1080, 30, 7500),
    FULL_HD_SMOOTH("FULL_HD_SMOOTH", "Full HD Smooth", "1080p • 60 FPS", VideoResolution.P1080, 60, 10000, requiresHighCapability = true),
    QHD("QHD", "1440p QHD", "1440p • 30 FPS", VideoResolution.P1440, 30, 14000, requiresHighCapability = true),
    QHD_SMOOTH("QHD_SMOOTH", "1440p Smooth", "1440p • 60 FPS", VideoResolution.P1440, 60, 18000, requiresHighCapability = true),
    UHD_4K("UHD_4K", "4K UHD", "4K • 30 FPS", VideoResolution.P2160, 30, 24000, requiresHighCapability = true),
    UHD_4K_SMOOTH("UHD_4K_SMOOTH", "4K 60 FPS", "4K • 60 FPS", VideoResolution.P2160, 60, 35000, requiresHighCapability = true),
    CUSTOM("CUSTOM", "Custom", "Manual config", VideoResolution.P720, 30, 4500);

    companion object {
        fun fromName(raw: String?): StreamPreset =
            entries.firstOrNull { it.name == raw } ?: HD
    }
}

enum class StreamVisibility(val label: String) {
    PUBLIC("Public"),
    UNLISTED("Unlisted"),
    PRIVATE("Private");

    companion object {
        fun fromName(raw: String?): StreamVisibility =
            entries.firstOrNull { it.name == raw } ?: PUBLIC
    }
}

enum class StreamOrientation(val label: String) {
    LANDSCAPE("Landscape"),
    PORTRAIT("Portrait"),
    AUTO("Auto")
}

enum class EncoderPreference(val label: String) {
    HARDWARE("Hardware H.264 (Recommended)"),
    AUTO("Auto (Hardware + Fallback)")
}

enum class NoiseCancellationLevel(val label: String, val description: String) {
    OFF("Off", "Original natural microphone sound"),
    LOW("Low", "Light ambient room hiss reduction"),
    MEDIUM("Medium", "Balanced fan & AC suppression"),
    HIGH("High", "Strong background noise reduction"),
    VOICE_ONLY("Voice Only", "Aggressive close-voice isolation & ambient suppression"),
    AI_DSP("AI-DSP Adaptive", "Hardware NS + adaptive soft-Wiener filter");

    companion object {
        fun fromName(raw: String?): NoiseCancellationLevel =
            entries.firstOrNull { it.name == raw } ?: MEDIUM
    }
}

enum class AudioOutputRoute(val label: String) {
    AUTO("Auto"),
    SPEAKER("Speaker"),
    HEADSET("Wired / USB Headset"),
    BLUETOOTH("Bluetooth"),
    EARPIECE("Earpiece")
}

enum class OverlayCorner(val label: String) {
    TOP_LEFT("Top Left"),
    TOP_RIGHT("Top Right"),
    BOTTOM_LEFT("Bottom Left"),
    BOTTOM_RIGHT("Bottom Right")
}

enum class OverlayIconSize(val label: String, val dpValue: Int) {
    SMALL("Compact", 44),
    MEDIUM("Standard", 52),
    LARGE("Large", 60)
}

enum class StreamPlatform(val label: String, val defaultRtmpUrl: String) {
    YOUTUBE("YouTube Live", "rtmp://a.rtmp.youtube.com/live2"),
    CUSTOM_RTMP("Custom RTMP", "rtmp://");

    companion object {
        fun fromName(raw: String?): StreamPlatform =
            entries.firstOrNull { it.name == raw } ?: YOUTUBE
    }
}

enum class LiveBroadcastMode(
    val label: String,
    val shortLabel: String,
    val aspectLabel: String,
    val isVertical: Boolean,
    val description: String
) {
    LANDSCAPE_16_9(
        label = "16:9 Live",
        shortLabel = "16:9 Live",
        aspectLabel = "16:9",
        isVertical = false,
        description = "Standard YouTube landscape broadcast (1280×720 / 1920×1080) with saved 16:9 layout."
    ),
    SHORTS_9_16(
        label = "Shorts Live",
        shortLabel = "Shorts 9:16",
        aspectLabel = "9:16",
        isVertical = true,
        description = "Vertical 9:16 Shorts Live broadcast (720×1280 / 1080×1920) with independent vertical layout."
    ),
    DUAL_LIVE(
        label = "Dual Live",
        shortLabel = "Dual 16:9+9:16",
        aspectLabel = "16:9 + 9:16",
        isVertical = false,
        description = "Simultaneous 16:9 + 9:16 Dual Live using two H.264 encoders and separate RTMP destinations."
    );

    companion object {
        fun fromName(raw: String?): LiveBroadcastMode =
            entries.firstOrNull { it.name == raw } ?: LANDSCAPE_16_9
    }
}

data class ModeStreamPreset(
    val resolution: VideoResolution = VideoResolution.P720,
    val fps: Int = 30,
    val bitrateKbps: Int = 4500
)

// --- SETTINGS CONFIGURATION ---

data class StreamSettings(
    val platform: StreamPlatform = StreamPlatform.YOUTUBE,
    val rtmpUrl: String = "",
    val backupRtmpUrl: String = "",
    val useRtmps: Boolean = false,
    val useBackupServer: Boolean = false,
    val streamKey: String = "",
    val streamTitle: String = "Mobile Gaming Live — Zro live",
    val streamDescription: String = "Streaming live gameplay with squad voice powered by Zro live.",
    val thumbnailUri: String? = null,
    val visibility: StreamVisibility = StreamVisibility.PUBLIC,
    val preset: StreamPreset = StreamPreset.HD,
    val resolution: VideoResolution = VideoResolution.P720,
    val fps: Int = 30,
    val bitrateKbps: Int = 4500, // 1000..15000
    val adaptiveBitrate: Boolean = true,
    val encoder: EncoderPreference = EncoderPreference.HARDWARE,
    val orientation: StreamOrientation = StreamOrientation.LANDSCAPE,
    val streamDelaySec: Int = 0,
    // Default 1-phone full screen capture; switches automatically to 2nd phone when paired
    val captureMode: CaptureMode = CaptureMode.FULL_SCREEN,
    val fullScreenExplicitlyEnabled: Boolean = true,
    val screenCaptureName: String = "Gameplay Capture",
    // YouTube Live Chat API credentials (optional, honest fallback to YouTube Live URL)
    val youtubeVideoId: String = "",
    val youtubeApiKeyOrToken: String = "",
    val chatEnabled: Boolean = true,
    val chatVisible: Boolean = true,
    val chatNotifications: Boolean = true,
    val lowDataMode: Boolean = false,
    val broadcastMode: LiveBroadcastMode = LiveBroadcastMode.LANDSCAPE_16_9,
    val landscapePreset: ModeStreamPreset = ModeStreamPreset(VideoResolution.P720, 30, 4500),
    val shortsPreset: ModeStreamPreset = ModeStreamPreset(VideoResolution.P720, 30, 4500),
    val dualPreset: ModeStreamPreset = ModeStreamPreset(VideoResolution.P720, 30, 3500),
    val shortsRtmpUrl: String = "",
    val shortsStreamKey: String = ""
) {
    fun effectiveOrientation(): StreamOrientation =
        if (broadcastMode.isVertical) StreamOrientation.PORTRAIT else StreamOrientation.LANDSCAPE

    fun effectiveDimensions(): Pair<Int, Int> {
        val w = resolution.width
        val h = resolution.height
        return if (broadcastMode.isVertical) {
            minOf(w, h) to maxOf(w, h)
        } else {
            maxOf(w, h) to minOf(w, h)
        }
    }

    fun effectiveSecondaryShortsDimensions(): Pair<Int, Int> {
        val cappedRes = if (resolution.width > 1280) VideoResolution.P720 else resolution
        return minOf(cappedRes.width, cappedRes.height) to maxOf(cappedRes.width, cappedRes.height)
    }

    fun effectiveResolutionDisplay(): String {
        val (w, h) = effectiveDimensions()
        return if (broadcastMode == LiveBroadcastMode.DUAL_LIVE) {
            val (sw, sh) = effectiveSecondaryShortsDimensions()
            "${w}×${h} + ${sw}×${sh}"
        } else {
            "${w}×${h} (${broadcastMode.aspectLabel})"
        }
    }

    fun withSyncedActiveModePreset(): StreamSettings {
        val activePreset = ModeStreamPreset(resolution = resolution, fps = fps, bitrateKbps = bitrateKbps)
        return when (broadcastMode) {
            LiveBroadcastMode.LANDSCAPE_16_9 -> copy(
                orientation = StreamOrientation.LANDSCAPE,
                landscapePreset = activePreset
            )
            LiveBroadcastMode.SHORTS_9_16 -> copy(
                orientation = StreamOrientation.PORTRAIT,
                shortsPreset = activePreset
            )
            LiveBroadcastMode.DUAL_LIVE -> copy(
                orientation = StreamOrientation.LANDSCAPE,
                dualPreset = activePreset
            )
        }
    }

    fun switchBroadcastMode(target: LiveBroadcastMode): StreamSettings {
        if (target == broadcastMode) return withSyncedActiveModePreset()
        val synced = withSyncedActiveModePreset()
        val nextPreset = when (target) {
            LiveBroadcastMode.LANDSCAPE_16_9 -> synced.landscapePreset
            LiveBroadcastMode.SHORTS_9_16 -> synced.shortsPreset
            LiveBroadcastMode.DUAL_LIVE -> synced.dualPreset
        }
        return synced.copy(
            broadcastMode = target,
            orientation = if (target.isVertical) StreamOrientation.PORTRAIT else StreamOrientation.LANDSCAPE,
            resolution = nextPreset.resolution,
            fps = nextPreset.fps,
            bitrateKbps = nextPreset.bitrateKbps
        )
    }

    fun effectiveShortsServerUrl(): String {
        val raw = shortsRtmpUrl.trim()
        if (raw.isBlank()) return ""
        val withScheme = if (useRtmps && raw.startsWith("rtmp://", ignoreCase = true)) {
            "rtmps://" + raw.removePrefix("rtmp://")
        } else {
            raw
        }
        return com.example.streaming.RtmpPublisher.normalizeEndpointUrl(withScheme)
    }

    fun effectivePrimaryServerUrl(): String {
        val raw = rtmpUrl.trim()
        if (raw.isBlank()) return ""
        val withScheme = if (useRtmps && raw.startsWith("rtmp://", ignoreCase = true)) {
            "rtmps://" + raw.removePrefix("rtmp://")
        } else {
            raw
        }
        return com.example.streaming.RtmpPublisher.normalizeEndpointUrl(withScheme)
    }

    fun effectiveBackupServerUrl(): String {
        val raw = backupRtmpUrl.trim()
        if (raw.isBlank()) return ""
        val withScheme = if (useRtmps && raw.startsWith("rtmp://", ignoreCase = true)) {
            "rtmps://" + raw.removePrefix("rtmp://")
        } else {
            raw
        }
        return com.example.streaming.RtmpPublisher.normalizeEndpointUrl(withScheme)
    }
}

data class RtmpTestResult(
    val inProgress: Boolean = false,
    val success: Boolean? = null,
    val latencyMs: Long = 0L,
    val serverTested: String = "",
    val message: String = ""
)

data class AudioSettings(
    val micEnabled: Boolean = true,
    val gameAudioEnabled: Boolean = true,
    val teamVoiceEnabled: Boolean = true,
    // Dedicated live routing switch: OFF = friends stay private; ON = friends mixed into live stream when permitted
    val teamVoiceToLive: Boolean = false,
    val micVolume: Float = 0.88f,
    val gameVolume: Float = 0.65f,
    val teamVolume: Float = 0.85f,
    val masterVolume: Float = 1.0f,
    val stereoOutput: Boolean = true,
    val echoCancellation: Boolean = true,
    val automaticGainControl: Boolean = true,
    val noiseSuppression: Boolean = true,
    val windNoiseReduction: Boolean = true,
    val windReductionStrength: Float = 0.60f,
    val voiceClarityEnhancement: Boolean = true,
    val noiseGateEnabled: Boolean = true,
    val noiseGateThresholdDb: Float = -42f,
    val micSensitivity: Float = 1.0f,
    val voiceIsolationStrength: Float = 0.55f,
    val ambientNoiseReduction: Float = 0.50f,
    val closeVoiceFocusEnabled: Boolean = false,
    val noiseCancellationLevel: NoiseCancellationLevel = NoiseCancellationLevel.MEDIUM,
    val outputRoute: AudioOutputRoute = AudioOutputRoute.AUTO
)

data class OverlaySettings(
    val enabled: Boolean = false,
    val opacity: Float = 0.90f,
    val iconSize: OverlayIconSize = OverlayIconSize.MEDIUM,
    val position: OverlayCorner = OverlayCorner.TOP_RIGHT,
    val showChatBubble: Boolean = true,
    val showQuickControls: Boolean = true
)

data class NotificationSettings(
    val liveNotificationEnabled: Boolean = true,
    val showStreamTimer: Boolean = true,
    val connectionAlerts: Boolean = true,
    val teammateAlerts: Boolean = true
)

data class PerformanceSettings(
    val profile: PerformanceProfile = PerformanceProfile.BALANCED,
    val lowRamMode: Boolean = false, // Low Performance Mode for 4GB RAM devices
    val reduceAnimationsDuringGameplay: Boolean = true,
    val batteryOptimizationHint: Boolean = true,
    val temperatureMonitoring: Boolean = true,
    val autoThermalQualityStepDown: Boolean = true,
    val thermalThresholdCelsius: Float = 42.0f
)

data class GeneralSettings(
    val language: String = "English",
    val themeStyle: ZroThemeStyle = ZroThemeStyle.OBSIDIAN,
    val onboardingCompleted: Boolean = false,
    val guestModeSelected: Boolean = false
)

data class ZroAppSettings(
    val stream: StreamSettings = StreamSettings(),
    val audio: AudioSettings = AudioSettings(),
    val overlay: OverlaySettings = OverlaySettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val performance: PerformanceSettings = PerformanceSettings(),
    val general: GeneralSettings = GeneralSettings()
)

// --- LIVE RUNTIME STATE & TELEMETRY ---

enum class StreamLifecycleState(val displayLabel: String) {
    IDLE("OFFLINE"),
    READY("READY"),
    CONNECTING("CONNECTING"),
    CONNECTED("CONNECTED"),
    SENDING_VIDEO("SENDING VIDEO"),
    LIVE("LIVE"),
    PAUSED("PAUSED"),
    RECONNECTING("RECONNECTING"),
    ERROR("ERROR");

    val isActive: Boolean
        get() = this == CONNECTING ||
            this == CONNECTED ||
            this == SENDING_VIDEO ||
            this == LIVE ||
            this == PAUSED ||
            this == RECONNECTING
}

enum class StreamHealth(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
    OFFLINE("Offline")
}

enum class LiveVideoSource(val label: String) {
    LOCAL_FULL_SCREEN("This Phone Screen"),
    REMOTE_SECOND_PHONE("2nd Phone Screen")
}

data class StreamRuntimeTelemetry(
    val state: StreamLifecycleState = StreamLifecycleState.IDLE,
    val durationSec: Long = 0L,
    val actualBitrateKbps: Int = 0,
    val targetBitrateKbps: Int = 4500,
    val actualFps: Int = 0,
    val targetFps: Int = 30,
    val resolutionLabel: String = "720p",
    val droppedFrames: Long = 0L,
    val totalFrames: Long = 0L,
    val streamHealth: StreamHealth = StreamHealth.OFFLINE,
    val concurrentViewers: Int? = null,
    val gameAudioCaptureActive: Boolean = false,
    val gameAudioCaptureNote: String = "Ready (Android 10+ games permitting capture)",
    val reconnectAttempt: Int = 0,
    val errorMessage: String? = null,
    val activeSessionId: String? = null,
    // Capture & Dual-Phone auto source switching state
    val captureActive: Boolean = false,
    val capturePreviewActive: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.FULL_SCREEN,
    val activeVideoSource: LiveVideoSource = LiveVideoSource.LOCAL_FULL_SCREEN,
    val isReconnectedSession: Boolean = false,
    val captureSourceLabel: String = "This Phone Full Screen",
    val capturedContentVisible: Boolean = true,
    val privacyShieldActive: Boolean = false,
    val capturedWidth: Int = 0,
    val capturedHeight: Int = 0,
    val thermalThrottled: Boolean = false,
    val activeEncoderName: String = "Hardware H.264",
    // Honest RTMP / Encoder diagnostics
    val statusDetail: String = "",
    val activeServerUrl: String = "",
    val usingBackupServer: Boolean = false,
    val videoEncoderReady: Boolean = false,
    val audioEncoderReady: Boolean = false,
    val bytesSentTotal: Long = 0L,
    val deviceTempCelsius: Float? = null,
    val thermalLabel: String = "Unmeasured",
    val activeSceneName: String = "Gameplay"
)

data class AudioMeterState(
    val micLevel: Float = 0f,
    val micPeak: Float = 0f,
    val micDb: Float = -60f,
    val gameLevel: Float = 0f,
    val gameDb: Float = -60f,
    val teamLevel: Float = 0f,
    val teamDb: Float = -60f,
    val masterLevel: Float = 0f,
    val masterPeak: Float = 0f,
    val masterDb: Float = -60f,
    val isTestingMic: Boolean = false,
    val hardwareNsSupported: Boolean = false,
    val hardwareAecSupported: Boolean = false,
    val hardwareAgcSupported: Boolean = false,
    val speakingDetected: Boolean = false,
    val gateOpen: Boolean = false,
    val teamVoiceSourceAvailable: Boolean = false
)

data class DeviceTelemetry(
    // Null when sensor reading is unavailable—never report a fake "safe" temperature
    val batteryTempCelsius: Float? = null,
    val isTemperatureMeasured: Boolean = false,
    val thermalStatusLabel: String = "Unmeasured",
    val isOverheating: Boolean = false,
    val totalRamMb: Long = 4096L,
    val availableRamMb: Long = 2048L,
    val isLowRamDevice: Boolean = false,
    val networkType: String = "Unknown",
    val networkConnected: Boolean = false,
    val upstreamBandwidthKbps: Int = 0,
    val supports1080p60: Boolean = false,
    val supports720p90: Boolean = false,
    val supports1080p90: Boolean = false,
    val supports1440p30: Boolean = false,
    val supports1440p60: Boolean = false,
    val supports4k30: Boolean = false,
    val supports4k60: Boolean = false,
    val supports90Fps: Boolean = false,
    val maxEncoderBitrateKbps: Int = 15_000,
    val hardwareAvcEncoderName: String = "Auto",
    val supportsAppOnlyCapture: Boolean = false
)

data class SmartRecommendation(
    val preset: StreamPreset,
    val resolution: VideoResolution,
    val fps: Int,
    val bitrateKbps: Int,
    val rationale: String
)

// --- YOUTUBE LIVE CHAT ---

data class YouTubeChatMessage(
    val id: String,
    val authorName: String,
    val authorAvatarUrl: String?,
    val messageText: String,
    val publishedAt: String,
    val isOwner: Boolean = false,
    val isModerator: Boolean = false,
    val isHighlightedNew: Boolean = false
)

sealed interface YouTubeChatState {
    data object Disconnected : YouTubeChatState
    data object Connecting : YouTubeChatState
    data class Connected(
        val liveChatId: String,
        val messages: List<YouTubeChatMessage>,
        val concurrentViewers: Int?
    ) : YouTubeChatState
    data class Error(val message: String) : YouTubeChatState
}

// --- FIRESTORE MODELS (@Keep, @IgnoreExtraProperties, @DocumentId, no `is`-prefixed Booleans) ---

@Keep
@IgnoreExtraProperties
data class UserProfileDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val totalStreamDurationSec: Long = 0L,
    val totalSessions: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class StreamSessionDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val description: String? = null,
    val visibility: String = "PUBLIC",
    val resolutionLabel: String = "720p",
    val fps: Long = 30L,
    val bitrateKbps: Long = 4500L,
    val durationSec: Long = 0L,
    val droppedFrames: Long = 0L,
    val peakBitrateKbps: Long = 0L,
    val status: String = "ENDED",
    val platformLabel: String = "YouTube / RTMP",
    val rtmpUrl: String = "",
    val sceneSlotName: String = "GAMEPLAY_LIVE",
    val startedAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class VoiceRoomDoc(
    @DocumentId val id: String = "",
    val hostUid: String = "",
    val roomCode: String = "",
    val name: String = "",
    val visibility: String = "PUBLIC",
    val active: Boolean = true,
    val maxParticipants: Long = 8L,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class VoiceParticipantDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val muted: Boolean = false,
    val speaking: Boolean = false,
    val audioLevel: Long = 0L,
    val streamConsent: Boolean = true,
    val connectionQuality: String = "GOOD",
    val udpEndpoint: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
