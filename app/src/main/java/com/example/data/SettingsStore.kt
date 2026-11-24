package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AudioOutputRoute
import com.example.data.model.AudioSettings
import com.example.data.model.CaptureMode
import com.example.data.model.EncoderPreference
import com.example.data.model.GeneralSettings
import com.example.data.model.NoiseCancellationLevel
import com.example.data.model.NotificationSettings
import com.example.data.model.OverlayCorner
import com.example.data.model.OverlayIconSize
import com.example.data.model.OverlaySettings
import com.example.data.model.PerformanceProfile
import com.example.data.model.PerformanceSettings
import com.example.data.model.StreamOrientation
import com.example.data.model.StreamPlatform
import com.example.data.model.StreamPreset
import com.example.data.model.StreamSessionDoc
import com.example.data.model.StreamSettings
import com.example.data.model.StreamVisibility
import com.example.data.model.VideoResolution
import com.example.data.model.ZroAppSettings
import com.example.studio.StudioPersistence
import com.example.studio.StudioWorkspaceState
import com.example.ui.theme.ZroThemeStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.zroDataStore: DataStore<Preferences> by preferencesDataStore(name = "zro_live_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val RTMP_URL = stringPreferencesKey("rtmp_url")
        val STREAM_KEY = stringPreferencesKey("stream_key")
        val STREAM_TITLE = stringPreferencesKey("stream_title")
        val STREAM_DESC = stringPreferencesKey("stream_desc")
        val VISIBILITY = stringPreferencesKey("visibility")
        val PRESET = stringPreferencesKey("preset")
        val RESOLUTION = stringPreferencesKey("resolution")
        val FPS = intPreferencesKey("fps")
        val BITRATE_KBPS = intPreferencesKey("bitrate_kbps")
        val ADAPTIVE_BITRATE = booleanPreferencesKey("adaptive_bitrate")
        val ENCODER = stringPreferencesKey("encoder")
        val ORIENTATION = stringPreferencesKey("orientation")
        val STREAM_DELAY = intPreferencesKey("stream_delay")
        val CAPTURE_MODE = stringPreferencesKey("capture_mode")
        val FULL_SCREEN_EXPLICIT = booleanPreferencesKey("full_screen_explicit")
        val YT_VIDEO_ID = stringPreferencesKey("yt_video_id")
        val YT_API_TOKEN = stringPreferencesKey("yt_api_token")
        val CHAT_ENABLED = booleanPreferencesKey("chat_enabled")
        val CHAT_VISIBLE = booleanPreferencesKey("chat_visible")
        val CHAT_NOTIFS = booleanPreferencesKey("chat_notifs")

        val MIC_ENABLED = booleanPreferencesKey("mic_enabled")
        val GAME_ENABLED = booleanPreferencesKey("game_enabled")
        val TEAM_ENABLED = booleanPreferencesKey("team_enabled")
        val MIC_VOL = floatPreferencesKey("mic_vol")
        val GAME_VOL = floatPreferencesKey("game_vol")
        val TEAM_VOL = floatPreferencesKey("team_vol")
        val STEREO = booleanPreferencesKey("stereo")
        val AEC = booleanPreferencesKey("aec")
        val AGC = booleanPreferencesKey("agc")
        val NS = booleanPreferencesKey("ns")
        val WIND = booleanPreferencesKey("wind")
        val CLARITY = booleanPreferencesKey("clarity")
        val GATE = booleanPreferencesKey("gate")
        val GATE_DB = floatPreferencesKey("gate_db")
        val NC_LEVEL = stringPreferencesKey("nc_level")
        val AUDIO_ROUTE = stringPreferencesKey("audio_route")

        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val OVERLAY_SIZE = stringPreferencesKey("overlay_size")
        val OVERLAY_POS = stringPreferencesKey("overlay_pos")
        val OVERLAY_CHAT_BUBBLE = booleanPreferencesKey("overlay_chat_bubble")
        val OVERLAY_QUICK_CONTROLS = booleanPreferencesKey("overlay_quick_controls")

        val NOTIF_LIVE = booleanPreferencesKey("notif_live")
        val NOTIF_TIMER = booleanPreferencesKey("notif_timer")
        val NOTIF_CONN = booleanPreferencesKey("notif_conn")
        val NOTIF_TEAM = booleanPreferencesKey("notif_team")

        val PERF_PROFILE = stringPreferencesKey("perf_profile")
        val PERF_LOW_RAM = booleanPreferencesKey("perf_low_ram")
        val PERF_REDUCE_ANIM = booleanPreferencesKey("perf_reduce_anim")
        val PERF_BATTERY = booleanPreferencesKey("perf_battery")
        val PERF_TEMP = booleanPreferencesKey("perf_temp")
        val PERF_THERMAL_STEP = booleanPreferencesKey("perf_thermal_step")

        val LANGUAGE = stringPreferencesKey("language")
        val THEME_STYLE = stringPreferencesKey("theme_style")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val GUEST_MODE_SELECTED = booleanPreferencesKey("guest_mode_selected")

        val LOCAL_SESSIONS_JSON = stringPreferencesKey("local_sessions_json")

        // Dual-Phone Studio & Enhanced Audio keys (additive, preserves all existing user data)
        val LOW_DATA_MODE = booleanPreferencesKey("low_data_mode")
        val TEAM_VOICE_TO_LIVE = booleanPreferencesKey("team_voice_to_live")
        val MASTER_VOL = floatPreferencesKey("master_vol")
        val WIND_STRENGTH = floatPreferencesKey("wind_strength")
        val MIC_SENSITIVITY = floatPreferencesKey("mic_sensitivity")
        val VOICE_ISOLATION = floatPreferencesKey("voice_isolation")
        val AMBIENT_REDUCTION = floatPreferencesKey("ambient_reduction")
        val CLOSE_VOICE_FOCUS = booleanPreferencesKey("close_voice_focus")
        val STUDIO_WORKSPACE_JSON = stringPreferencesKey("studio_workspace_json")
        val STUDIO_PREVIOUS_BACKUP_JSON = stringPreferencesKey("studio_previous_backup_json")
        val STREAM_PLATFORM = stringPreferencesKey("stream_platform")
        val THUMBNAIL_URI = stringPreferencesKey("thumbnail_uri")
        val AUTHORIZED_LAYOUT_JSON = stringPreferencesKey("authorized_layout_json")
        val SCREEN_CAPTURE_NAME = stringPreferencesKey("screen_capture_name")
        val BACKUP_RTMP_URL = stringPreferencesKey("backup_rtmp_url")
        val USE_RTMPS = booleanPreferencesKey("use_rtmps")
        val USE_BACKUP_SERVER = booleanPreferencesKey("use_backup_server")
    }

    val settingsFlow: Flow<ZroAppSettings> = context.zroDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p ->
            val explicitFullScreen = p[Keys.FULL_SCREEN_EXPLICIT] ?: false
            val savedCaptureMode = CaptureMode.fromName(p[Keys.CAPTURE_MODE])
            // Enforce privacy rule: never activate FULL_SCREEN unless explicitly enabled
            val effectiveCaptureMode = if (savedCaptureMode == CaptureMode.FULL_SCREEN && !explicitFullScreen) {
                CaptureMode.GAME_APP_ONLY
            } else {
                savedCaptureMode
            }

            ZroAppSettings(
                stream = StreamSettings(
                    platform = StreamPlatform.fromName(p[Keys.STREAM_PLATFORM]),
                    rtmpUrl = p[Keys.RTMP_URL] ?: "rtmp://a.rtmp.youtube.com/live2",
                    backupRtmpUrl = p[Keys.BACKUP_RTMP_URL] ?: "rtmp://b.rtmp.youtube.com/live2",
                    useRtmps = p[Keys.USE_RTMPS] ?: false,
                    useBackupServer = p[Keys.USE_BACKUP_SERVER] ?: false,
                    streamKey = p[Keys.STREAM_KEY] ?: "",
                    streamTitle = p[Keys.STREAM_TITLE] ?: "Mobile Gaming Live — Zro live",
                    streamDescription = p[Keys.STREAM_DESC] ?: "Streaming live gameplay with squad voice powered by Zro live.",
                    thumbnailUri = p[Keys.THUMBNAIL_URI]?.takeIf { it.isNotBlank() },
                    visibility = StreamVisibility.fromName(p[Keys.VISIBILITY]),
                    preset = StreamPreset.fromName(p[Keys.PRESET]),
                    resolution = VideoResolution.fromName(p[Keys.RESOLUTION]),
                    fps = p[Keys.FPS] ?: 30,
                    bitrateKbps = p[Keys.BITRATE_KBPS] ?: 4500,
                    adaptiveBitrate = p[Keys.ADAPTIVE_BITRATE] ?: true,
                    encoder = EncoderPreference.entries.firstOrNull { it.name == p[Keys.ENCODER] } ?: EncoderPreference.HARDWARE,
                    orientation = StreamOrientation.entries.firstOrNull { it.name == p[Keys.ORIENTATION] } ?: StreamOrientation.LANDSCAPE,
                    streamDelaySec = p[Keys.STREAM_DELAY] ?: 0,
                    captureMode = effectiveCaptureMode,
                    fullScreenExplicitlyEnabled = explicitFullScreen,
                    screenCaptureName = p[Keys.SCREEN_CAPTURE_NAME]?.takeIf { it.isNotBlank() } ?: "Gameplay Capture",
                    youtubeVideoId = p[Keys.YT_VIDEO_ID] ?: "",
                    youtubeApiKeyOrToken = p[Keys.YT_API_TOKEN] ?: "",
                    chatEnabled = p[Keys.CHAT_ENABLED] ?: true,
                    chatVisible = p[Keys.CHAT_VISIBLE] ?: true,
                    chatNotifications = p[Keys.CHAT_NOTIFS] ?: true,
                    lowDataMode = p[Keys.LOW_DATA_MODE] ?: false
                ),
                audio = AudioSettings(
                    micEnabled = p[Keys.MIC_ENABLED] ?: true,
                    gameAudioEnabled = p[Keys.GAME_ENABLED] ?: true,
                    teamVoiceEnabled = p[Keys.TEAM_ENABLED] ?: true,
                    teamVoiceToLive = p[Keys.TEAM_VOICE_TO_LIVE] ?: false,
                    micVolume = p[Keys.MIC_VOL] ?: 0.88f,
                    gameVolume = p[Keys.GAME_VOL] ?: 0.65f,
                    teamVolume = p[Keys.TEAM_VOL] ?: 0.85f,
                    masterVolume = p[Keys.MASTER_VOL] ?: 1.0f,
                    stereoOutput = p[Keys.STEREO] ?: true,
                    echoCancellation = p[Keys.AEC] ?: true,
                    automaticGainControl = p[Keys.AGC] ?: true,
                    noiseSuppression = p[Keys.NS] ?: true,
                    windNoiseReduction = p[Keys.WIND] ?: true,
                    windReductionStrength = p[Keys.WIND_STRENGTH] ?: 0.60f,
                    voiceClarityEnhancement = p[Keys.CLARITY] ?: true,
                    noiseGateEnabled = p[Keys.GATE] ?: true,
                    noiseGateThresholdDb = p[Keys.GATE_DB] ?: -42f,
                    micSensitivity = p[Keys.MIC_SENSITIVITY] ?: 1.0f,
                    voiceIsolationStrength = p[Keys.VOICE_ISOLATION] ?: 0.55f,
                    ambientNoiseReduction = p[Keys.AMBIENT_REDUCTION] ?: 0.50f,
                    closeVoiceFocusEnabled = p[Keys.CLOSE_VOICE_FOCUS] ?: false,
                    noiseCancellationLevel = NoiseCancellationLevel.fromName(p[Keys.NC_LEVEL]),
                    outputRoute = AudioOutputRoute.entries.firstOrNull { it.name == p[Keys.AUDIO_ROUTE] } ?: AudioOutputRoute.AUTO
                ),
                overlay = OverlaySettings(
                    enabled = p[Keys.OVERLAY_ENABLED] ?: false,
                    opacity = p[Keys.OVERLAY_OPACITY] ?: 0.90f,
                    iconSize = OverlayIconSize.entries.firstOrNull { it.name == p[Keys.OVERLAY_SIZE] } ?: OverlayIconSize.MEDIUM,
                    position = OverlayCorner.entries.firstOrNull { it.name == p[Keys.OVERLAY_POS] } ?: OverlayCorner.TOP_RIGHT,
                    showChatBubble = p[Keys.OVERLAY_CHAT_BUBBLE] ?: true,
                    showQuickControls = p[Keys.OVERLAY_QUICK_CONTROLS] ?: true
                ),
                notifications = NotificationSettings(
                    liveNotificationEnabled = p[Keys.NOTIF_LIVE] ?: true,
                    showStreamTimer = p[Keys.NOTIF_TIMER] ?: true,
                    connectionAlerts = p[Keys.NOTIF_CONN] ?: true,
                    teammateAlerts = p[Keys.NOTIF_TEAM] ?: true
                ),
                performance = PerformanceSettings(
                    profile = PerformanceProfile.fromName(p[Keys.PERF_PROFILE]),
                    lowRamMode = p[Keys.PERF_LOW_RAM] ?: false,
                    reduceAnimationsDuringGameplay = p[Keys.PERF_REDUCE_ANIM] ?: true,
                    batteryOptimizationHint = p[Keys.PERF_BATTERY] ?: true,
                    temperatureMonitoring = p[Keys.PERF_TEMP] ?: true,
                    autoThermalQualityStepDown = p[Keys.PERF_THERMAL_STEP] ?: true
                ),
                general = GeneralSettings(
                    language = p[Keys.LANGUAGE] ?: "English",
                    themeStyle = ZroThemeStyle.entries.firstOrNull { it.name == p[Keys.THEME_STYLE] } ?: ZroThemeStyle.OBSIDIAN,
                    onboardingCompleted = p[Keys.ONBOARDING_DONE] ?: false,
                    guestModeSelected = p[Keys.GUEST_MODE_SELECTED] ?: false
                )
            )
        }

    val localSessionsFlow: Flow<List<StreamSessionDoc>> = context.zroDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p -> parseLocalSessions(p[Keys.LOCAL_SESSIONS_JSON]) }

    suspend fun appendLocalSession(session: StreamSessionDoc) {
        context.zroDataStore.edit { p ->
            val current = parseLocalSessions(p[Keys.LOCAL_SESSIONS_JSON]).toMutableList()
            current.add(0, session)
            val trimmed = current.take(12)
            p[Keys.LOCAL_SESSIONS_JSON] = serializeLocalSessions(trimmed)
        }
    }

    suspend fun saveStreamSettings(s: StreamSettings) {
        context.zroDataStore.edit { p ->
            p[Keys.STREAM_PLATFORM] = s.platform.name
            p[Keys.RTMP_URL] = s.rtmpUrl
            p[Keys.BACKUP_RTMP_URL] = s.backupRtmpUrl
            p[Keys.USE_RTMPS] = s.useRtmps
            p[Keys.USE_BACKUP_SERVER] = s.useBackupServer
            p[Keys.STREAM_KEY] = s.streamKey
            p[Keys.STREAM_TITLE] = s.streamTitle
            p[Keys.STREAM_DESC] = s.streamDescription
            p[Keys.THUMBNAIL_URI] = s.thumbnailUri ?: ""
            p[Keys.VISIBILITY] = s.visibility.name
            p[Keys.PRESET] = s.preset.name
            p[Keys.RESOLUTION] = s.resolution.name
            p[Keys.FPS] = s.fps
            p[Keys.BITRATE_KBPS] = s.bitrateKbps
            p[Keys.ADAPTIVE_BITRATE] = s.adaptiveBitrate
            p[Keys.ENCODER] = s.encoder.name
            p[Keys.ORIENTATION] = s.orientation.name
            p[Keys.STREAM_DELAY] = s.streamDelaySec
            p[Keys.CAPTURE_MODE] = s.captureMode.name
            p[Keys.FULL_SCREEN_EXPLICIT] = s.fullScreenExplicitlyEnabled
            p[Keys.SCREEN_CAPTURE_NAME] = s.screenCaptureName.trim().ifBlank { "Gameplay Capture" }
            p[Keys.YT_VIDEO_ID] = s.youtubeVideoId
            p[Keys.YT_API_TOKEN] = s.youtubeApiKeyOrToken
            p[Keys.CHAT_ENABLED] = s.chatEnabled
            p[Keys.CHAT_VISIBLE] = s.chatVisible
            p[Keys.CHAT_NOTIFS] = s.chatNotifications
            p[Keys.LOW_DATA_MODE] = s.lowDataMode
        }
    }

    suspend fun saveAudioSettings(a: AudioSettings) {
        context.zroDataStore.edit { p ->
            p[Keys.MIC_ENABLED] = a.micEnabled
            p[Keys.GAME_ENABLED] = a.gameAudioEnabled
            p[Keys.TEAM_ENABLED] = a.teamVoiceEnabled
            p[Keys.TEAM_VOICE_TO_LIVE] = a.teamVoiceToLive
            p[Keys.MIC_VOL] = a.micVolume
            p[Keys.GAME_VOL] = a.gameVolume
            p[Keys.TEAM_VOL] = a.teamVolume
            p[Keys.MASTER_VOL] = a.masterVolume
            p[Keys.STEREO] = a.stereoOutput
            p[Keys.AEC] = a.echoCancellation
            p[Keys.AGC] = a.automaticGainControl
            p[Keys.NS] = a.noiseSuppression
            p[Keys.WIND] = a.windNoiseReduction
            p[Keys.WIND_STRENGTH] = a.windReductionStrength
            p[Keys.CLARITY] = a.voiceClarityEnhancement
            p[Keys.GATE] = a.noiseGateEnabled
            p[Keys.GATE_DB] = a.noiseGateThresholdDb
            p[Keys.MIC_SENSITIVITY] = a.micSensitivity
            p[Keys.VOICE_ISOLATION] = a.voiceIsolationStrength
            p[Keys.AMBIENT_REDUCTION] = a.ambientNoiseReduction
            p[Keys.CLOSE_VOICE_FOCUS] = a.closeVoiceFocusEnabled
            p[Keys.NC_LEVEL] = a.noiseCancellationLevel.name
            p[Keys.AUDIO_ROUTE] = a.outputRoute.name
        }
    }

    val studioWorkspaceFlow: Flow<StudioWorkspaceState> = context.zroDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p -> StudioPersistence.deserializeWorkspace(p[Keys.STUDIO_WORKSPACE_JSON]) }

    suspend fun saveStudioWorkspace(state: StudioWorkspaceState) {
        context.zroDataStore.edit { p ->
            p[Keys.STUDIO_WORKSPACE_JSON] = StudioPersistence.serializeWorkspace(state)
        }
    }

    suspend fun savePreviousStudioBackup(state: StudioWorkspaceState) {
        context.zroDataStore.edit { p ->
            p[Keys.STUDIO_PREVIOUS_BACKUP_JSON] = StudioPersistence.serializeWorkspace(state)
        }
    }

    suspend fun restorePreviousStudioBackup(): StudioWorkspaceState? {
        val prefs = context.zroDataStore.data.first()
        val raw = prefs[Keys.STUDIO_PREVIOUS_BACKUP_JSON] ?: return null
        val restored = StudioPersistence.deserializeWorkspace(raw)
        saveStudioWorkspace(restored)
        return restored
    }

    suspend fun saveAuthorizedDeviceLayout(state: StudioWorkspaceState) {
        val serialized = StudioPersistence.serializeWorkspace(state)
        context.zroDataStore.edit { p ->
            p[Keys.AUTHORIZED_LAYOUT_JSON] = serialized
            p[Keys.STUDIO_WORKSPACE_JSON] = serialized
        }
    }

    suspend fun loadAuthorizedDeviceLayout(): StudioWorkspaceState? {
        val prefs = context.zroDataStore.data.first()
        val raw = prefs[Keys.AUTHORIZED_LAYOUT_JSON] ?: prefs[Keys.STUDIO_WORKSPACE_JSON] ?: return null
        return StudioPersistence.deserializeWorkspace(raw)
    }

    suspend fun saveOverlaySettings(o: OverlaySettings) {
        context.zroDataStore.edit { p ->
            p[Keys.OVERLAY_ENABLED] = o.enabled
            p[Keys.OVERLAY_OPACITY] = o.opacity
            p[Keys.OVERLAY_SIZE] = o.iconSize.name
            p[Keys.OVERLAY_POS] = o.position.name
            p[Keys.OVERLAY_CHAT_BUBBLE] = o.showChatBubble
            p[Keys.OVERLAY_QUICK_CONTROLS] = o.showQuickControls
        }
    }

    suspend fun saveNotificationSettings(n: NotificationSettings) {
        context.zroDataStore.edit { p ->
            p[Keys.NOTIF_LIVE] = n.liveNotificationEnabled
            p[Keys.NOTIF_TIMER] = n.showStreamTimer
            p[Keys.NOTIF_CONN] = n.connectionAlerts
            p[Keys.NOTIF_TEAM] = n.teammateAlerts
        }
    }

    suspend fun savePerformanceSettings(perf: PerformanceSettings) {
        context.zroDataStore.edit { p ->
            p[Keys.PERF_PROFILE] = perf.profile.name
            p[Keys.PERF_LOW_RAM] = perf.lowRamMode
            p[Keys.PERF_REDUCE_ANIM] = perf.reduceAnimationsDuringGameplay
            p[Keys.PERF_BATTERY] = perf.batteryOptimizationHint
            p[Keys.PERF_TEMP] = perf.temperatureMonitoring
            p[Keys.PERF_THERMAL_STEP] = perf.autoThermalQualityStepDown
        }
    }

    suspend fun saveGeneralSettings(g: GeneralSettings) {
        context.zroDataStore.edit { p ->
            p[Keys.LANGUAGE] = g.language
            p[Keys.THEME_STYLE] = g.themeStyle.name
            p[Keys.ONBOARDING_DONE] = g.onboardingCompleted
            p[Keys.GUEST_MODE_SELECTED] = g.guestModeSelected
        }
    }

    private fun parseLocalSessions(raw: String?): List<StreamSessionDoc> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        StreamSessionDoc(
                            id = obj.optString("id", "local_$i"),
                            userId = "guest",
                            title = obj.optString("title", "Local Broadcast"),
                            visibility = obj.optString("visibility", "PUBLIC"),
                            resolutionLabel = obj.optString("resolutionLabel", "720p"),
                            fps = obj.optLong("fps", 30L),
                            bitrateKbps = obj.optLong("bitrateKbps", 4500L),
                            durationSec = obj.optLong("durationSec", 0L),
                            droppedFrames = obj.optLong("droppedFrames", 0L),
                            peakBitrateKbps = obj.optLong("peakBitrateKbps", 0L),
                            status = obj.optString("status", "ENDED")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeLocalSessions(sessions: List<StreamSessionDoc>): String {
        val arr = JSONArray()
        sessions.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("visibility", s.visibility)
                    put("resolutionLabel", s.resolutionLabel)
                    put("fps", s.fps)
                    put("bitrateKbps", s.bitrateKbps)
                    put("durationSec", s.durationSec)
                    put("droppedFrames", s.droppedFrames)
                    put("peakBitrateKbps", s.peakBitrateKbps)
                    put("status", s.status)
                }
            )
        }
        return arr.toString()
    }
}
