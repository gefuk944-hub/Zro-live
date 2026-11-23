package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.model.AudioSettings
import com.example.data.model.CaptureMode
import com.example.data.model.DeviceTelemetry
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
import com.example.data.model.StreamSettings
import com.example.data.model.ZroAppSettings
import com.example.telemetry.DeviceTelemetryMonitor
import com.example.ui.components.GlassCard
import com.example.ui.components.ProSubpageTopBar
import com.example.ui.components.SectionHeader
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald
import com.example.ui.theme.ZroLiveRed
import com.example.ui.theme.ZroThemeStyle

private enum class SettingsCategory(val label: String) {
    PERFORMANCE("Performance"),
    STREAMING("Streaming"),
    AUDIO("Audio"),
    OVERLAY("Overlay"),
    NOTIFICATIONS("Notifications"),
    GENERAL("General")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    isGuest: Boolean,
    isAuthLoading: Boolean,
    userDisplayName: String,
    settings: ZroAppSettings,
    deviceTelemetry: DeviceTelemetry,
    onUpdateStream: (StreamSettings) -> Unit,
    onSetFullScreenOptIn: (Boolean) -> Unit,
    onUpdateAudio: (AudioSettings) -> Unit,
    onUpdateOverlay: (OverlaySettings) -> Unit,
    onUpdateNotifications: (NotificationSettings) -> Unit,
    onUpdatePerformance: (PerformanceSettings) -> Unit,
    onSelectPerformanceProfile: (PerformanceProfile) -> Unit,
    onUpdateGeneral: (GeneralSettings) -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenAudioMixer: () -> Unit,
    onSignInOptional: () -> Unit,
    onSignOut: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var activeCategory by remember { mutableStateOf(SettingsCategory.PERFORMANCE) }
    val tempLabel = DeviceTelemetryMonitor.formatTemperature(deviceTelemetry)

    val hasMicPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val hasNotifPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val hasOverlayPerm = Settings.canDrawOverlays(context)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
        ) {
            if (onBack != null) {
                ProSubpageTopBar(
                    title = "Settings",
                    onBack = onBack,
                    backTestTag = "settings_back_button"
                )
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SettingsCategory.entries) { cat ->
                    val selected = cat == activeCategory
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) ZroCobalt.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            1.dp,
                            if (selected) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { activeCategory = cat }
                            .testTag("settings_cat_${cat.name.lowercase()}")
                    ) {
                        Text(
                            text = cat.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (activeCategory) {
                    SettingsCategory.PERFORMANCE -> {
                        item {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                accentBorder = if (deviceTelemetry.isOverheating) ZroLiveRed else ZroCobalt
                            ) {
                                SectionHeader(
                                    title = "Performance Profiles & Thermals",
                                    subtitle = "RAM: ${deviceTelemetry.availableRamMb}/${deviceTelemetry.totalRamMb} MB • Thermal: $tempLabel"
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                if (deviceTelemetry.isOverheating) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = ZroLiveRed.copy(alpha = 0.16f),
                                        border = BorderStroke(1.dp, ZroLiveRed.copy(alpha = 0.5f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = ZroLiveRed)
                                            Text(
                                                text = "Overheating detected ($tempLabel). Bitrate & DSP load will reduce automatically.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFFFCA5A5)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                } else if (!deviceTelemetry.isTemperatureMeasured) {
                                    Text(
                                        text = "Note: Direct battery temperature sensor reading is unavailable on this device/OS. Zro live will never display a fake safe temperature.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                }

                                Text("Performance Profile", style = MaterialTheme.typography.titleSmall, color = Color.White)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PerformanceProfile.entries.forEach { profile ->
                                        FilterChip(
                                            selected = settings.performance.profile == profile,
                                            onClick = { onSelectPerformanceProfile(profile) },
                                            label = { Text(profile.shortLabel) },
                                            modifier = Modifier.testTag("settings_perf_${profile.name.lowercase()}")
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = settings.performance.profile.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ZroCobalt
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                SettingsToggleRow(
                                    title = "Low Performance Mode (4GB RAM)",
                                    subtitle = if (deviceTelemetry.isLowRamDevice) {
                                        "Recommended for this device: caps FPS/bitrate, lightweight DSP & low memory footprint"
                                    } else {
                                        "Minimizes RAM usage, chat history buffer, and encoder load"
                                    },
                                    checked = settings.performance.lowRamMode,
                                    onCheckedChange = { enabled ->
                                        onUpdatePerformance(
                                            settings.performance.copy(
                                                lowRamMode = enabled,
                                                reduceAnimationsDuringGameplay = enabled || settings.performance.reduceAnimationsDuringGameplay
                                            )
                                        )
                                    }
                                )
                                SettingsToggleRow(
                                    title = "Reduce Animations During Gameplay",
                                    subtitle = "Disables pulsing badges and non-essential UI animations to save CPU/GPU",
                                    checked = settings.performance.reduceAnimationsDuringGameplay,
                                    onCheckedChange = { onUpdatePerformance(settings.performance.copy(reduceAnimationsDuringGameplay = it)) }
                                )
                                SettingsToggleRow(
                                    title = "Thermal Sensor Monitoring",
                                    subtitle = "Checks battery & OS thermal status while app is active or streaming",
                                    checked = settings.performance.temperatureMonitoring,
                                    onCheckedChange = { onUpdatePerformance(settings.performance.copy(temperatureMonitoring = it)) }
                                )
                                SettingsToggleRow(
                                    title = "Auto Thermal Workload Reduction",
                                    subtitle = "Steps down bitrate automatically when overheating is measured",
                                    checked = settings.performance.autoThermalQualityStepDown,
                                    onCheckedChange = { onUpdatePerformance(settings.performance.copy(autoThermalQualityStepDown = it)) }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Open Android Battery Optimization Settings")
                                }
                            }
                        }
                    }

                    SettingsCategory.STREAMING -> {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth(), accentBorder = ZroEmerald) {
                                SectionHeader(
                                    title = "Capture Mode & Privacy",
                                    subtitle = "Game / App-Only is default. Full Screen is disabled unless explicitly enabled."
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Active Mode: ${settings.stream.captureMode.label}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (settings.stream.captureMode == CaptureMode.GAME_APP_ONLY) ZroEmerald else ZroAmber
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                SettingsToggleRow(
                                    title = "Allow Full Screen Capture (Opt-In)",
                                    subtitle = "Off by default. Enables capturing your entire phone screen including notifications and other apps.",
                                    checked = settings.stream.fullScreenExplicitlyEnabled,
                                    onCheckedChange = onSetFullScreenOptIn
                                )
                            }
                        }

                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(
                                    title = "Streaming Configuration",
                                    subtitle = "Hardware encoder, orientation & adaptive bitrate"
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                Text("Video Encoder", style = MaterialTheme.typography.titleSmall, color = Color.White)
                                Spacer(modifier = Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    EncoderPreference.entries.forEach { enc ->
                                        FilterChip(
                                            selected = settings.stream.encoder == enc,
                                            onClick = { onUpdateStream(settings.stream.copy(encoder = enc)) },
                                            label = { Text(enc.label) }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Broadcast Orientation", style = MaterialTheme.typography.titleSmall, color = Color.White)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StreamOrientation.entries.forEach { ori ->
                                        FilterChip(
                                            selected = settings.stream.orientation == ori,
                                            onClick = { onUpdateStream(settings.stream.copy(orientation = ori)) },
                                            label = { Text(ori.label) }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                SettingsToggleRow(
                                    title = "Adaptive Bitrate",
                                    subtitle = "Step down video bitrate smoothly during mobile network drops",
                                    checked = settings.stream.adaptiveBitrate,
                                    onCheckedChange = { onUpdateStream(settings.stream.copy(adaptiveBitrate = it)) }
                                )
                            }
                        }
                    }

                    SettingsCategory.AUDIO -> {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(
                                    title = "Audio & Voice Processing",
                                    subtitle = "Natural speech with CPU-efficient DSP filtering",
                                    trailing = {
                                        OutlinedButton(onClick = onOpenAudioMixer, shape = RoundedCornerShape(10.dp)) {
                                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Open Mixer")
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                SettingsToggleRow(
                                    title = "Microphone Capture",
                                    subtitle = "Active in both Team Voice Room and YouTube Live",
                                    checked = settings.audio.micEnabled,
                                    onCheckedChange = { onUpdateAudio(settings.audio.copy(micEnabled = it)) }
                                )
                                SettingsToggleRow(
                                    title = "Game Audio Capture",
                                    subtitle = "Android 10+ Playback Capture where games allow it",
                                    checked = settings.audio.gameAudioEnabled,
                                    onCheckedChange = { onUpdateAudio(settings.audio.copy(gameAudioEnabled = it)) }
                                )
                                SettingsToggleRow(
                                    title = "Teammate Voice in Stream",
                                    subtitle = "Mixes consented squad voices into your broadcast bus",
                                    checked = settings.audio.teamVoiceEnabled,
                                    onCheckedChange = { onUpdateAudio(settings.audio.copy(teamVoiceEnabled = it)) }
                                )
                                SettingsToggleRow(
                                    title = "Echo Cancellation (AEC)",
                                    subtitle = "Prevents game speakers from looping into your mic",
                                    checked = settings.audio.echoCancellation,
                                    onCheckedChange = { onUpdateAudio(settings.audio.copy(echoCancellation = it)) }
                                )
                                SettingsToggleRow(
                                    title = "Voice Clarity Enhancement",
                                    subtitle = "Crisp speech intelligibility without harsh distortion",
                                    checked = settings.audio.voiceClarityEnhancement,
                                    onCheckedChange = { onUpdateAudio(settings.audio.copy(voiceClarityEnhancement = it)) }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Noise Cancellation Profile", style = MaterialTheme.typography.titleSmall, color = Color.White)
                                Spacer(modifier = Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    NoiseCancellationLevel.entries.forEach { nc ->
                                        FilterChip(
                                            selected = settings.audio.noiseCancellationLevel == nc,
                                            onClick = { onUpdateAudio(settings.audio.copy(noiseCancellationLevel = nc)) },
                                            label = { Text(nc.label) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SettingsCategory.OVERLAY -> {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth(), accentBorder = if (settings.overlay.enabled) ZroEmerald else null) {
                                SectionHeader(
                                    title = "Floating Gaming Overlay",
                                    subtitle = "Lightweight movable live badge & control drawer over games"
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                if (!hasOverlayPerm) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = ZroAmber.copy(alpha = 0.14f),
                                        border = BorderStroke(1.dp, ZroAmber.copy(alpha = 0.45f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Overlay Permission Required", style = MaterialTheme.typography.titleSmall, color = Color(0xFFFCD34D))
                                            Text(
                                                stringResource(R.string.permission_overlay_desc),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Button(
                                                onClick = onRequestOverlayPermission,
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = ZroAmber, contentColor = Color.Black),
                                                modifier = Modifier.testTag("grant_overlay_permission_button")
                                            ) {
                                                Text("Grant Display Over Apps")
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                }

                                SettingsToggleRow(
                                    title = "Enable Floating Overlay Icon",
                                    subtitle = "Turn off to disable the floating icon completely",
                                    checked = settings.overlay.enabled,
                                    onCheckedChange = { wantEnable ->
                                        if (wantEnable && !hasOverlayPerm) {
                                            onRequestOverlayPermission()
                                        } else {
                                            onUpdateOverlay(settings.overlay.copy(enabled = wantEnable))
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Overlay Opacity", style = MaterialTheme.typography.titleSmall, color = Color.White)
                                    Text(
                                        "${(settings.overlay.opacity * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = ZroCobalt
                                    )
                                }
                                Slider(
                                    value = settings.overlay.opacity,
                                    onValueChange = { onUpdateOverlay(settings.overlay.copy(opacity = it)) },
                                    valueRange = 0.45f..1.0f,
                                    colors = SliderDefaults.colors(thumbColor = ZroCobalt, activeTrackColor = ZroCobalt)
                                )

                                Text("Icon Size", style = MaterialTheme.typography.titleSmall, color = Color.White)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OverlayIconSize.entries.forEach { sz ->
                                        FilterChip(
                                            selected = settings.overlay.iconSize == sz,
                                            onClick = { onUpdateOverlay(settings.overlay.copy(iconSize = sz)) },
                                            label = { Text(sz.label) }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Default Corner Position", style = MaterialTheme.typography.titleSmall, color = Color.White)
                                Spacer(modifier = Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OverlayCorner.entries.forEach { corner ->
                                        FilterChip(
                                            selected = settings.overlay.position == corner,
                                            onClick = { onUpdateOverlay(settings.overlay.copy(position = corner)) },
                                            label = { Text(corner.label) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SettingsCategory.NOTIFICATIONS -> {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(
                                    title = "Stream & Capture Notifications",
                                    subtitle = "Persistent live status bar & Privacy Shield alerts"
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                SettingsToggleRow(
                                    title = "Persistent Live Notification",
                                    subtitle = "Shows LIVE status, Privacy Shield, mute & stop controls",
                                    checked = settings.notifications.liveNotificationEnabled,
                                    onCheckedChange = { onUpdateNotifications(settings.notifications.copy(liveNotificationEnabled = it)) }
                                )
                                SettingsToggleRow(
                                    title = "Live Stream Duration Timer",
                                    subtitle = "Battery-friendly elapsed time updates",
                                    checked = settings.notifications.showStreamTimer,
                                    onCheckedChange = { onUpdateNotifications(settings.notifications.copy(showStreamTimer = it)) }
                                )
                                SettingsToggleRow(
                                    title = "Connection & Drop Alerts",
                                    subtitle = "Notify when RTMP reconnects or network degrades",
                                    checked = settings.notifications.connectionAlerts,
                                    onCheckedChange = { onUpdateNotifications(settings.notifications.copy(connectionAlerts = it)) }
                                )
                                SettingsToggleRow(
                                    title = "Teammate Room Alerts",
                                    subtitle = "Notify when teammates join or leave your Voice Room",
                                    checked = settings.notifications.teammateAlerts,
                                    onCheckedChange = { onUpdateNotifications(settings.notifications.copy(teammateAlerts = it)) }
                                )
                            }
                        }
                    }

                    SettingsCategory.GENERAL -> {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(title = "Appearance & Theme")
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ZroThemeStyle.entries.forEach { style ->
                                        FilterChip(
                                            selected = settings.general.themeStyle == style,
                                            onClick = { onUpdateGeneral(settings.general.copy(themeStyle = style)) },
                                            label = { Text(style.label) }
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(
                                    title = "Permissions & Privacy Center",
                                    subtitle = "Requested only when needed — never hidden capture"
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                PermissionStatusRow(
                                    title = stringResource(R.string.permission_mic_title),
                                    desc = stringResource(R.string.permission_mic_desc),
                                    granted = hasMicPerm,
                                    onRequest = onRequestMicPermission
                                )
                                PermissionStatusRow(
                                    title = stringResource(R.string.permission_notif_title),
                                    desc = stringResource(R.string.permission_notif_desc),
                                    granted = hasNotifPerm,
                                    onRequest = onRequestNotificationPermission
                                )
                                PermissionStatusRow(
                                    title = stringResource(R.string.permission_overlay_title),
                                    desc = stringResource(R.string.permission_overlay_desc),
                                    granted = hasOverlayPerm,
                                    onRequest = onRequestOverlayPermission
                                )
                                PermissionStatusRow(
                                    title = stringResource(R.string.permission_screen_title),
                                    desc = stringResource(R.string.permission_screen_desc),
                                    granted = false,
                                    statusOverride = "On-Demand",
                                    onRequest = null
                                )

                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = ZroEmerald.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, ZroEmerald.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Security, contentDescription = null, tint = ZroEmerald)
                                        Text(
                                            text = stringResource(R.string.privacy_guarantee),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        // About & Feedback Card
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(
                                    title = "Zro live v1.0",
                                    subtitle = "All streaming, audio, overlay, and performance settings are stored locally on your device."
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = {
                                        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:support@zrolive.app")).apply {
                                            putExtra(Intent.EXTRA_SUBJECT, "Zro live Feedback (v1.0)")
                                        }
                                        runCatching { context.startActivity(mail) }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Send Feedback")
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    desc: String,
    granted: Boolean,
    statusOverride: String? = null,
    onRequest: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = "Granted", tint = ZroEmerald, modifier = Modifier.size(18.dp))
                Text("Granted", style = MaterialTheme.typography.labelMedium, color = ZroEmerald)
            }
        } else if (statusOverride != null) {
            Text(statusOverride, style = MaterialTheme.typography.labelMedium, color = ZroCobalt)
        } else if (onRequest != null) {
            OutlinedButton(onClick = onRequest, shape = RoundedCornerShape(10.dp)) {
                Text("Allow")
            }
        }
    }
}
