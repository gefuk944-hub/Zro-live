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
        description = "Minimal UI overhead, throttled background polling, hardware-first encoding for max game FPS.",
        telemetryIntervalMs = 15_000L,
        meterIntervalMs = 180L,
        maxBitrateCapKbps = 15_000,
        lightweightDsp = true,
        reduceUiAnimations = true
    ),
    BALANCED(
        label = "Balanced",
        shortLabel = "Balanced",
        description = "Recommended stable streaming quality, full voice DSP, and smart thermal protection.",
        telemetryIntervalMs = 8_000L,
        meterIntervalMs = 85L,
        maxBitrateCapKbps = 10_000,
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
            entries.firstOrNull { it.name == raw } ?: GAME_APP_ONLY
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
    P1440("1440p QHD", "1440p", 2560, 1440, 13000);

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
    QHD("QHD", "1440p QHD", "1440p • 30 FPS", VideoResolution.P1440, 30, 13000, requiresHighCapability = true),
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

// --- SETTINGS CONFIGURATION ---

data class StreamSettings(
    val platform: StreamPlatform = StreamPlatform.YOUTUBE,
    val rtmpUrl: String = "rtmp://a.rtmp.youtube.com/live2",
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
    // Privacy-first capture configuration: GAME_APP_ONLY by default; FULL_SCREEN disabled by default
    val captureMode: CaptureMode = CaptureMode.GAME_APP_ONLY,
    val fullScreenExplicitlyEnabled: Boolean = false,
    val screenCaptureName: String = "Gameplay Capture",
    // YouTube Live Chat API credentials (optional, honest fallback to YouTube Live URL)
    val youtubeVideoId: String = "",
    val youtubeApiKeyOrToken: String = "",
    val chatEnabled: Boolean = true,
    val chatVisible: Boolean = true,
    val chatNotifications: Boolean = true,
    val lowDataMode: Boolean = false
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

enum class StreamLifecycleState {
    IDLE,
    CONNECTING,
    LIVE,
    PAUSED,
    RECONNECTING,
    ERROR
}

enum class StreamHealth(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
    OFFLINE("Offline")
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
    // Game / App-Only Capture & Privacy Shield state
    val captureActive: Boolean = false,
    val capturePreviewActive: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.GAME_APP_ONLY,
    val captureSourceLabel: String = "No game selected",
    val capturedContentVisible: Boolean = true,
    val privacyShieldActive: Boolean = false,
    val capturedWidth: Int = 0,
    val capturedHeight: Int = 0,
    val thermalThrottled: Boolean = false,
    val activeEncoderName: String = "Hardware H.264"
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
