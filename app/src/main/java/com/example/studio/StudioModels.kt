package com.example.studio

import java.util.UUID

// --- DUAL PHONE STREAMING STUDIO MODELS ---

enum class DualPhoneRole(val label: String, val subtitle: String) {
    SOLO_DEVICE(
        label = "Solo Device",
        subtitle = "Play & stream directly from this phone"
    ),
    PHONE1_GAMEPLAY(
        label = "Phone 1 — Gameplay",
        subtitle = "Captures authorized game screen, game audio & mic; streams feed to Phone 2"
    ),
    PHONE2_STUDIO(
        label = "Phone 2 — Studio Control",
        subtitle = "Receives Phone 1 gameplay source; manages scenes, overlays, camera, audio & chat"
    )
}

enum class DualConnectionMethod(val label: String) {
    QR_PAIRING("QR Code"),
    MANUAL_IP("Manual Address"),
    INTERNET_RELAY("Mobile Data / Relay"),
    LOCAL_WIFI("Local Wi-Fi"),
    HOTSPOT("Mobile Hotspot");

    companion object {
        val VISIBLE_OPTIONS: List<DualConnectionMethod> = listOf(QR_PAIRING, MANUAL_IP, INTERNET_RELAY)
    }
}

enum class DualLinkState(val label: String) {
    DISCONNECTED("Disconnected"),
    HOSTING("Waiting for Phone 1"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    RECONNECTING("Reconnecting"),
    ERROR("Connection Error")
}

data class DualPhoneTelemetry(
    val role: DualPhoneRole = DualPhoneRole.SOLO_DEVICE,
    val connectionMethod: DualConnectionMethod = DualConnectionMethod.QR_PAIRING,
    val linkState: DualLinkState = DualLinkState.DISCONNECTED,
    val localIp: String = "",
    val hostPort: Int = 47920,
    val videoPort: Int = 47921,
    val pairingToken: String = "",
    val connectedDeviceName: String = "",
    val peerIp: String = "",
    val relayServerUrl: String = "",
    val transportModeLabel: String = "Direct LAN",
    val signalQualityLabel: String = "Offline",
    val latencyMs: Int = 0,
    val mirroredFps: Int = 0,
    val mirroredResolution: String = "720p",
    val packetLossPercent: Float = 0f,
    val uploadKbps: Int = 0,
    val autoReconnect: Boolean = true,
    val isRecordingLocal: Boolean = false,
    val screenshotCount: Int = 0,
    val lastScreenshotPath: String? = null,
    val lastError: String? = null,
    val lastFrameEpochMs: Long = 0L
)

// --- OBS-STYLE CANVAS GRID, RESIZE CORNERS, FACECAM & 4-SCENE MODELS ---

enum class CanvasGridMode(val label: String, val cols: Int, val rows: Int) {
    GRID_2X6("2 × 6", 6, 2),
    GRID_4X4("4 × 4", 4, 4),
    OFF("Grid Off", 0, 0);

    companion object {
        fun fromName(raw: String?): CanvasGridMode =
            entries.firstOrNull { it.name == raw } ?: GRID_2X6
    }
}

enum class ResizeCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TOP_CENTER,
    BOTTOM_CENTER,
    CENTER_LEFT,
    CENTER_RIGHT
}

enum class CropEdge {
    LEFT,
    TOP,
    RIGHT,
    BOTTOM
}

enum class CropAspectPreset(val label: String, val targetRatio: Float?) {
    FREE("Free", null),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_1_1("1:1", 1f),
    RATIO_9_16("9:16", 9f / 16f)
}

enum class FacecamShape(val label: String) {
    ROUNDED_RECT("Rounded"),
    SQUARE("Square"),
    CIRCLE("Circle");

    companion object {
        fun fromName(raw: String?): FacecamShape =
            entries.firstOrNull { it.name == raw } ?: ROUNDED_RECT
    }
}

enum class CameraSourceMode(val label: String, val subtitle: String) {
    PHONE2_FRONT("Front Camera", "Built-in front facecam on this device"),
    PHONE2_REAR("Rear Camera", "Built-in rear camera on this device"),
    DJI_USB_UVC("DJI / USB-UVC & HDMI Card", "DJI Osmo Pocket 3 / Action 4-5 Pro (UVC mode) or HDMI-to-USB capture card"),
    DJI_WIRELESS("DJI Wireless Companion", "Wireless DJI Mimo / RTMP-LAN camera companion feed over local Wi-Fi"),
    EXTERNAL_UVC("External USB Camera", "Generic USB Video Class (UVC) camera supported by Android Camera2/CameraX"),
    PHONE1_REMOTE("2nd Phone Camera", "Authorized paired second-phone camera feed over local link");

    companion object {
        fun fromName(raw: String?): CameraSourceMode =
            entries.firstOrNull { it.name == raw } ?: PHONE2_FRONT
    }
}

enum class SceneSlot(
    val index: Int,
    val defaultName: String,
    val shortLabel: String,
    val defaultVertical: Boolean
) {
    GAMEPLAY_LIVE(0, "Gameplay", "Scene 1", false),
    SHORTS_VERTICAL(1, "Starting Soon", "Scene 2", false),
    BRB_BREAK(2, "BRB / Privacy", "Scene 3", false),
    ENDING_THANKS(3, "Ending / Thanks", "Scene 4", false);

    companion object {
        fun fromName(raw: String?): SceneSlot =
            entries.firstOrNull { it.name == raw } ?: GAMEPLAY_LIVE
    }
}

enum class LayerScaleMode(val label: String) {
    STRETCH("Stretch"),
    FIT("Fit");

    companion object {
        fun fromName(raw: String?): LayerScaleMode =
            entries.firstOrNull { it.name == raw } ?: STRETCH
    }
}

enum class SceneTransitionType(val label: String) {
    CUT("Cut"),
    FADE("Fade"),
    SLIDE("Slide"),
    ZOOM("Zoom");

    companion object {
        fun fromName(raw: String?): SceneTransitionType =
            entries.firstOrNull { it.name == raw } ?: FADE
    }
}

enum class LayerSourceType(val label: String, val category: String) {
    GAMEPLAY_SCREEN("Gameplay Capture", "Capture"),
    WEBCAM("Facecam", "Capture"),
    IMAGE("Image", "Media"),
    LOGO("Channel Logo", "Media"),
    GIF("GIF Overlay", "Animated"),
    VIDEO_OVERLAY("Video Overlay", "Media"),
    TEXT("Text", "Graphics"),
    ANIMATED_OVERLAY("Animated Overlay", "Animated"),
    BROWSER_WEB("Browser Source", "Web"),
    SUBSCRIBER_COUNTER("Subscriber Counter", "Widgets"),
    CHAT_OVERLAY("Chat Overlay", "Widgets"),
    STARTING_SOON("Starting Soon", "Scenes"),
    BRB_BANNER("BRB Scene", "Scenes"),
    ENDING_BANNER("Ending Scene", "Scenes"),
    BACKGROUND("Background", "Graphics"),
    AUDIO_VISUALIZER("Audio Meter", "Animated"),
    DECORATIVE_FRAME("Frame Border", "Graphics"),
    MERGED_GROUP("Merged Layer", "Composite");

    fun supportsChromaKey(): Boolean = when (this) {
        IMAGE, LOGO, VIDEO_OVERLAY, WEBCAM, GAMEPLAY_SCREEN, BACKGROUND -> true
        else -> false
    }

    companion object {
        fun fromName(raw: String?): LayerSourceType =
            entries.firstOrNull { it.name == raw } ?: TEXT
    }
}

enum class LayerAnimationType(val label: String) {
    NONE("None"),
    PULSE("Pulse"),
    FLOAT("Float"),
    GLOW("Subtle Glow"),
    TICKER_SCROLL("Scroll"),
    SHIMMER("Motion Sweep");

    companion object {
        fun fromName(raw: String?): LayerAnimationType =
            entries.firstOrNull { it.name == raw } ?: NONE
    }
}

/**
 * Independent OBS-style source/layer with strict lock/unlock protection.
 * Coordinates and sizes are normalized (0f..1f) relative to the scene canvas.
 */
data class SceneLayer(
    val id: String = "layer_${UUID.randomUUID().toString().take(8)}",
    val name: String,
    val type: LayerSourceType,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val zIndex: Int = 0,
    val x: Float = 0.1f,
    val y: Float = 0.1f,
    val width: Float = 0.4f,
    val height: Float = 0.25f,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1.0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 0f,
    val cropBottom: Float = 0f,
    val lockAspectRatio: Boolean = false,
    val scaleMode: LayerScaleMode = LayerScaleMode.STRETCH,
    val borderWidthDp: Float = 0f,
    val borderColorHex: Long = 0xFF3B82F6L,
    val shadowEnabled: Boolean = false,
    val facecamShape: FacecamShape = FacecamShape.ROUNDED_RECT,
    val textContent: String = "",
    val subtitleText: String = "",
    val fontStyle: String = "SANS", // SANS, MONO, DISPLAY
    val fontSizeSp: Float = 16f,
    val textColorHex: Long = 0xFFFFFFFFL,
    val backgroundColorHex: Long = 0xCC11141BL,
    val transparentBackground: Boolean = false,
    val mediaUri: String? = null,
    val webUrl: String = "",
    val animationType: LayerAnimationType = LayerAnimationType.NONE,
    val animationEnabled: Boolean = true,
    val animationSpeed: Float = 1.0f,
    val motionBackgroundEnabled: Boolean = true,
    // Subscriber Counter configuration (honest manual count/goal or official widget URL)
    val subTitle: String = "SUBSCRIBERS",
    val subCount: Int = 0,
    val subGoal: Int = 1000,
    val subChannelUrl: String = "",
    val subWidgetUrl: String = "",
    val subUseExternalWidget: Boolean = false,
    val presetTag: String = "",
    val graphicAdjustment: LayerGraphicAdjustment = LayerGraphicAdjustment(),
    val mergedChildren: List<SceneLayer> = emptyList(),
    val sourceMediaWidth: Int = 0,
    val sourceMediaHeight: Int = 0,
    val webReloadNonce: Int = 0
)

data class StudioScene(
    val slot: SceneSlot,
    val name: String = slot.defaultName,
    val isVertical: Boolean = slot.defaultVertical,
    val sceneLocked: Boolean = false,
    val transitionType: SceneTransitionType = SceneTransitionType.FADE,
    val transitionDurationMs: Int = 300,
    val layers: List<SceneLayer> = emptyList()
)

/**
 * 3-column compact Remote Control Deck buttons.
 * Default order produces a clean 5×3 broadcast switcher grid:
 *   [ START LIVE ] [ PAUSE ]      [ STOP ]
 *   [ MIC MUTE ]   [ TEAM MUTE ]  [ CAM ON/OFF ]
 *   [ SCENE 1 ]    [ SCENE 2 ]    [ SCENE 3 ]
 *   [ SCENE 4 ]    [ CHAT ]       [ BRB ]
 *   [ VOLUME - ]   [ VOLUME + ]   [ SCREENSHOT ]
 */
enum class RemoteControlTileId(val label: String, val shortLabel: String) {
    START_LIVE("Start Live", "START LIVE"),
    PAUSE_RESUME("Pause / Resume", "PAUSE"),
    STOP_LIVE("Stop Live", "STOP"),
    MUTE_MIC("Mic Mute", "MIC MUTE"),
    TEAM_VOICE_LIVE("Team Voice Mute", "TEAM MUTE"),
    CAM_TOGGLE("Camera On/Off", "CAM"),
    SCENE_1("Scene 1", "SCENE 1"),
    SCENE_2("Scene 2", "SCENE 2"),
    SCENE_3("Scene 3", "SCENE 3"),
    SCENE_4("Scene 4", "SCENE 4"),
    OPEN_CHAT("Live Chat", "CHAT"),
    BRB_PRIVACY("BRB / Privacy", "BRB"),
    VOL_DOWN("Volume -", "VOL −"),
    VOL_UP("Volume +", "VOL +"),
    SCREENSHOT("Screenshot", "SHOT"),
    // Optional extra supported actions users can add in Deck Customization
    TOGGLE_OVERLAYS("Overlays", "OVERLAYS"),
    SWITCH_CAMERA("Flip Camera", "FLIP CAM"),
    OPEN_STUDIO("Open Studio", "STUDIO");

    companion object {
        val DEFAULT_ORDER: List<RemoteControlTileId> = listOf(
            START_LIVE, PAUSE_RESUME, STOP_LIVE,
            MUTE_MIC, TEAM_VOICE_LIVE, CAM_TOGGLE,
            SCENE_1, SCENE_2, SCENE_3,
            SCENE_4, OPEN_CHAT, BRB_PRIVACY,
            VOL_DOWN, VOL_UP, SCREENSHOT
        )

        fun fromName(raw: String?): RemoteControlTileId? {
            if (raw.isNullOrBlank()) return null
            // Clean backward-compatible migration from earlier tile IDs
            return when (raw) {
                "START_STOP_LIVE" -> START_LIVE
                "SCENE_SWITCH" -> SCENE_1
                "VOL_MASTER" -> VOL_UP
                "VOL_MIC", "VOL_GAME", "VOL_TEAM" -> null
                else -> entries.firstOrNull { it.name == raw }
            }
        }
    }
}

data class StudioWorkspaceState(
    val activeSceneSlot: SceneSlot = SceneSlot.GAMEPLAY_LIVE,
    val scenes: Map<SceneSlot, StudioScene> = defaultFourScenes(),
    val selectedLayerId: String? = null,
    val gridMode: CanvasGridMode = CanvasGridMode.GRID_2X6,
    val showGrid: Boolean = true,
    val showSnapGuides: Boolean = true,
    val showSafeArea: Boolean = false,
    val canvasZoom: Float = 1.0f,
    val overlaysGlobalVisible: Boolean = true,
    val cameraEnabled: Boolean = false,
    val useFrontCamera: Boolean = true,
    val cameraSourceMode: CameraSourceMode = CameraSourceMode.PHONE2_FRONT,
    val brbActive: Boolean = false,
    val previousSceneBeforeBrb: SceneSlot? = null,
    val autoRestoreLastLayout: Boolean = true,
    val savedLayoutTimestampMs: Long = 0L,
    val lastAuthorizedDeviceName: String = "",
    val lastAuthorizedDeviceIp: String = "",
    val remoteTileOrder: List<RemoteControlTileId> = RemoteControlTileId.DEFAULT_ORDER,
    val remoteTileScale: Float = 1.0f,
    val chatTextSizeSp: Float = 13f,
    val chatOverlayEnabled: Boolean = true,
    val pinnedChatMessageId: String? = null,
    val pinnedChatAuthor: String? = null,
    val pinnedChatText: String? = null,
    val customGraphicPresets: List<CustomGraphicPreset> = emptyList()
) {
    val activeScene: StudioScene
        get() = scenes[activeSceneSlot] ?: defaultSceneFor(activeSceneSlot)

    companion object {
        fun defaultFourScenes(verticalDefault: Boolean = false): Map<SceneSlot, StudioScene> = mapOf(
            SceneSlot.GAMEPLAY_LIVE to defaultSceneFor(SceneSlot.GAMEPLAY_LIVE, verticalDefault),
            SceneSlot.SHORTS_VERTICAL to defaultSceneFor(SceneSlot.SHORTS_VERTICAL, verticalDefault),
            SceneSlot.BRB_BREAK to defaultSceneFor(SceneSlot.BRB_BREAK, verticalDefault),
            SceneSlot.ENDING_THANKS to defaultSceneFor(SceneSlot.ENDING_THANKS, verticalDefault)
        )

        fun defaultSceneFor(slot: SceneSlot, verticalDefault: Boolean = false): StudioScene = when (slot) {
            SceneSlot.GAMEPLAY_LIVE -> if (verticalDefault) {
                StudioScene(
                    slot = SceneSlot.GAMEPLAY_LIVE,
                    name = "Shorts Gameplay",
                    isVertical = true,
                    transitionType = SceneTransitionType.FADE,
                    transitionDurationMs = 250,
                    layers = listOf(
                        SceneLayer(
                            id = "s1_gameplay",
                            name = "Gameplay Capture",
                            type = LayerSourceType.GAMEPLAY_SCREEN,
                            visible = true,
                            locked = false,
                            zIndex = 0,
                            x = 0f,
                            y = 0f,
                            width = 1f,
                            height = 1f,
                            scaleMode = LayerScaleMode.STRETCH
                        ),
                        SceneLayer(
                            id = "s1_branding",
                            name = "Sponsor / Branding Bar",
                            type = LayerSourceType.TEXT,
                            visible = true,
                            locked = false,
                            zIndex = 1,
                            x = 0.04f,
                            y = 0.91f,
                            width = 0.92f,
                            height = 0.06f,
                            textContent = "SPONSOR • ZEO LIVE • SUBSCRIBE",
                            fontSizeSp = 11f,
                            backgroundColorHex = 0xCC0D1321L,
                            borderWidthDp = 1f,
                            borderColorHex = 0xFF3B82F6L
                        ),
                        SceneLayer(
                            id = "s1_chat",
                            name = "Live Widget",
                            type = LayerSourceType.CHAT_OVERLAY,
                            visible = true,
                            locked = false,
                            zIndex = 2,
                            x = 0.04f,
                            y = 0.68f,
                            width = 0.56f,
                            height = 0.21f,
                            opacity = 0.88f,
                            backgroundColorHex = 0xAA0C0E12L
                        ),
                        SceneLayer(
                            id = "s1_webcam",
                            name = "Facecam",
                            type = LayerSourceType.WEBCAM,
                            visible = true,
                            locked = false,
                            zIndex = 3,
                            x = 0.64f,
                            y = 0.04f,
                            width = 0.32f,
                            height = 0.19f,
                            facecamShape = FacecamShape.ROUNDED_RECT,
                            borderWidthDp = 1.5f,
                            borderColorHex = 0xFF3B82F6L,
                            shadowEnabled = true
                        )
                    )
                )
            } else {
                StudioScene(
                    slot = SceneSlot.GAMEPLAY_LIVE,
                    name = "Gameplay",
                    isVertical = false,
                    transitionType = SceneTransitionType.FADE,
                    transitionDurationMs = 250,
                    layers = listOf(
                        SceneLayer(
                            id = "s1_gameplay",
                            name = "Gameplay Capture",
                            type = LayerSourceType.GAMEPLAY_SCREEN,
                            visible = true,
                            locked = false,
                            zIndex = 0,
                            x = 0f,
                            y = 0f,
                            width = 1f,
                            height = 1f,
                            scaleMode = LayerScaleMode.STRETCH
                        ),
                        SceneLayer(
                            id = "s1_branding",
                            name = "Sponsor / Branding Bar",
                            type = LayerSourceType.TEXT,
                            visible = true,
                            locked = false,
                            zIndex = 1,
                            x = 0.25f,
                            y = 0.89f,
                            width = 0.73f,
                            height = 0.09f,
                            textContent = "SPONSOR • ZEO LIVE • SUBSCRIBE",
                            fontSizeSp = 12f,
                            backgroundColorHex = 0xCC0D1321L,
                            borderWidthDp = 1f,
                            borderColorHex = 0xFF3B82F6L
                        ),
                        SceneLayer(
                            id = "s1_chat",
                            name = "Live Widget",
                            type = LayerSourceType.CHAT_OVERLAY,
                            visible = true,
                            locked = false,
                            zIndex = 2,
                            x = 0.02f,
                            y = 0.14f,
                            width = 0.24f,
                            height = 0.46f,
                            opacity = 0.88f,
                            backgroundColorHex = 0xAA0C0E12L
                        ),
                        SceneLayer(
                            id = "s1_webcam",
                            name = "Facecam",
                            type = LayerSourceType.WEBCAM,
                            visible = true,
                            locked = false,
                            zIndex = 3,
                            x = 0.02f,
                            y = 0.63f,
                            width = 0.21f,
                            height = 0.34f,
                            facecamShape = FacecamShape.ROUNDED_RECT,
                            borderWidthDp = 1.5f,
                            borderColorHex = 0xFF3B82F6L,
                            shadowEnabled = true
                        ),
                        SceneLayer(
                            id = "s1_sub",
                            name = "Subscriber Goal",
                            type = LayerSourceType.SUBSCRIBER_COUNTER,
                            visible = true,
                            locked = false,
                            zIndex = 4,
                            x = 0.74f,
                            y = 0.03f,
                            width = 0.24f,
                            height = 0.13f,
                            subTitle = "SUBSCRIBERS",
                            subCount = 0,
                            subGoal = 1000,
                            borderWidthDp = 1f,
                            borderColorHex = 0xFF22C55EL
                        )
                    )
                )
            }

            SceneSlot.SHORTS_VERTICAL -> StudioScene(
                slot = SceneSlot.SHORTS_VERTICAL,
                name = "Starting Soon",
                isVertical = verticalDefault,
                transitionType = SceneTransitionType.FADE,
                transitionDurationMs = 280,
                layers = listOf(
                    SceneLayer(
                        id = "s2_starting_soon",
                        name = "Streaming Start Soon",
                        type = LayerSourceType.STARTING_SOON,
                        visible = true,
                        locked = false,
                        zIndex = 0,
                        x = 0f,
                        y = 0f,
                        width = 1f,
                        height = 1f,
                        scaleMode = LayerScaleMode.STRETCH,
                        textContent = "STREAMING START SOON",
                        subtitleText = "Live broadcast starting shortly — Stay tuned!",
                        fontStyle = "DISPLAY",
                        fontSizeSp = 24f,
                        textColorHex = 0xFFFFFFFFL,
                        backgroundColorHex = 0xFF0A1020L,
                        borderWidthDp = 2f,
                        borderColorHex = 0xFF3B82F6L,
                        animationType = LayerAnimationType.SHIMMER,
                        animationEnabled = true,
                        motionBackgroundEnabled = true,
                        presetTag = "STARTING_SOON"
                    )
                )
            )

            SceneSlot.BRB_BREAK -> StudioScene(
                slot = SceneSlot.BRB_BREAK,
                name = "BRB / Privacy",
                isVertical = verticalDefault,
                transitionType = SceneTransitionType.FADE,
                transitionDurationMs = 250,
                layers = listOf(
                    SceneLayer(
                        id = "s3_bg",
                        name = "BRB Motion Backdrop",
                        type = LayerSourceType.BACKGROUND,
                        visible = true,
                        locked = false,
                        zIndex = 0,
                        x = 0f,
                        y = 0f,
                        width = 1f,
                        height = 1f,
                        backgroundColorHex = 0xFF10141DL,
                        motionBackgroundEnabled = true,
                        animationEnabled = true,
                        animationSpeed = 1.0f
                    ),
                    SceneLayer(
                        id = "s3_banner",
                        name = "Be Right Back Fullscreen",
                        type = LayerSourceType.BRB_BANNER,
                        visible = true,
                        locked = false,
                        zIndex = 1,
                        x = 0.0f,
                        y = 0.0f,
                        width = 1.0f,
                        height = 1.0f,
                        textContent = "BE RIGHT BACK!",
                        subtitleText = "Stream paused briefly — Gameplay & camera hidden",
                        fontStyle = "DISPLAY",
                        fontSizeSp = 24f,
                        textColorHex = 0xFFF8FAACL,
                        backgroundColorHex = 0x0010141DL,
                        transparentBackground = true,
                        borderWidthDp = 0f,
                        borderColorHex = 0xFFF59E0BL,
                        animationType = LayerAnimationType.SHIMMER,
                        animationEnabled = true,
                        animationSpeed = 1.0f,
                        motionBackgroundEnabled = true,
                        presetTag = "BRB"
                    )
                )
            )

            SceneSlot.ENDING_THANKS -> StudioScene(
                slot = SceneSlot.ENDING_THANKS,
                name = "Ending / Thanks",
                isVertical = verticalDefault,
                transitionType = SceneTransitionType.FADE,
                transitionDurationMs = 350,
                layers = listOf(
                    SceneLayer(
                        id = "s4_bg",
                        name = "Ending Motion Backdrop",
                        type = LayerSourceType.BACKGROUND,
                        visible = true,
                        locked = false,
                        zIndex = 0,
                        x = 0f,
                        y = 0f,
                        width = 1f,
                        height = 1f,
                        backgroundColorHex = 0xFF0E131CL,
                        motionBackgroundEnabled = true,
                        animationEnabled = true,
                        animationSpeed = 0.9f
                    ),
                    SceneLayer(
                        id = "s4_thanks",
                        name = "Thanks for Watching Fullscreen",
                        type = LayerSourceType.ENDING_BANNER,
                        visible = true,
                        locked = false,
                        zIndex = 1,
                        x = 0.0f,
                        y = 0.0f,
                        width = 1.0f,
                        height = 1.0f,
                        textContent = "THANKS FOR WATCHING!",
                        subtitleText = "Subscribe & turn on notifications for the next live stream",
                        fontStyle = "DISPLAY",
                        fontSizeSp = 23f,
                        textColorHex = 0xFFFFFFFFL,
                        backgroundColorHex = 0x000E131CL,
                        transparentBackground = true,
                        borderWidthDp = 0f,
                        borderColorHex = 0xFF22C55EL,
                        animationType = LayerAnimationType.FLOAT,
                        animationEnabled = true,
                        animationSpeed = 0.9f,
                        motionBackgroundEnabled = true,
                        presetTag = "ENDING"
                    )
                )
            )
        }
    }
}
