package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HeadsetMic
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsInputComponent
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AudioMeterState
import com.example.data.model.DeviceTelemetry
import com.example.data.model.LiveVideoSource
import com.example.data.model.SmartRecommendation
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.StreamSessionDoc
import com.example.data.model.VoiceRoomDoc
import com.example.data.model.ZroAppSettings
import com.example.studio.DualLinkState
import com.example.studio.DualPhoneTelemetry
import com.example.studio.StudioWorkspaceState
import com.example.telemetry.ActiveLiveSessionSnapshot
import com.example.ui.components.BrandGradientButton
import com.example.ui.components.GlassCard
import com.example.ui.components.LiveStatusBadge
import com.example.ui.components.SectionHeader
import com.example.ui.components.StudioCanvasPreview
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald
import com.example.ui.theme.ZroLiveRed

/**
 * Clean, minimal OBS-inspired Home Dashboard.
 * Shows only essential broadcast status & actions:
 * - Prominent LIVE SETUP button
 * - Start / Resume / Stop Live button
 * - Connected Phone status, Current Scene, Quick Audio, Camera status
 * - Compact Studio / Remote / Chat / Voice shortcuts
 * - Recent stream sessions
 */
@Composable
fun HomeDashboardScreen(
    settings: ZroAppSettings,
    streamTelemetry: StreamRuntimeTelemetry,
    deviceTelemetry: DeviceTelemetry,
    smartRecommendation: SmartRecommendation,
    dualTelemetry: DualPhoneTelemetry,
    workspace: StudioWorkspaceState,
    activeVoiceRoom: VoiceRoomDoc?,
    recentSessions: List<StreamSessionDoc>,
    onOpenLiveSetup: () -> Unit,
    onStartStreamingClick: () -> Unit,
    onStopStreamingClick: () -> Unit,
    onOpenObsStudio: () -> Unit,
    onOpenRemoteDeck: () -> Unit,
    onOpenDualPairing: () -> Unit,
    onOpenAudioSettings: () -> Unit,
    onOpenCameraSettings: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenVoiceRoom: () -> Unit,
    recoverableSession: ActiveLiveSessionSnapshot? = null,
    mirroredFrame: Bitmap? = null,
    audioMeters: AudioMeterState = AudioMeterState(),
    cameraPermissionGranted: Boolean = false,
    onReconnectPreviousLive: () -> Unit = {},
    onDismissRecovery: () -> Unit = {},
    onOpenLiveManager: () -> Unit = {},
    onOpenFullScreenPreview: () -> Unit = {}
) {
    val isLive = streamTelemetry.state == StreamLifecycleState.LIVE
    val isPaused = streamTelemetry.state == StreamLifecycleState.PAUSED
    val isReconnecting = streamTelemetry.state == StreamLifecycleState.RECONNECTING
    val isActiveSession = isLive || isPaused || isReconnecting ||
        streamTelemetry.state == StreamLifecycleState.CONNECTING ||
        streamTelemetry.state == StreamLifecycleState.CONNECTED ||
        streamTelemetry.state == StreamLifecycleState.SENDING_VIDEO
    val reducedMotion = settings.performance.reduceAnimationsDuringGameplay || settings.performance.lowRamMode

    val phoneConnected = dualTelemetry.linkState == DualLinkState.CONNECTED

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("home_dashboard_list"),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Minimal Broadcast Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Zro live",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    val tempStr = deviceTelemetry.batteryTempCelsius?.let { " • ${it.toInt()}°C" } ?: ""
                    Text(
                        text = "${settings.stream.resolution.shortLabel} • ${settings.stream.fps} FPS • ${"%.1f".format(settings.stream.bitrateKbps / 1000f)} Mbps$tempStr",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LiveStatusBadge(
                    isLive = isLive,
                    isPaused = isPaused,
                    isReconnecting = isReconnecting,
                    privacyShield = streamTelemetry.privacyShieldActive,
                    brbActive = workspace.brbActive,
                    reducedMotion = reducedMotion,
                    lifecycleState = streamTelemetry.state,
                    durationText = if (isLive || isPaused) formatHomeDuration(streamTelemetry.durationSec) else ""
                )
            }
        }

        // 1b. Crash Recovery Banner (rejoins exact previous session without duplicates)
        if (recoverableSession != null && !isActiveSession) {
            item {
                GlassCard(
                    accentBorder = ZroAmber,
                    modifier = Modifier.testTag("home_reconnect_banner")
                ) {
                    Text(
                        text = "PREVIOUS LIVE INTERRUPTED",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = ZroAmber
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${recoverableSession.title.ifBlank { "Live Stream" }} • ${recoverableSession.resolutionLabel} ${recoverableSession.fps} FPS • Scene: ${recoverableSession.sceneSlotName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onReconnectPreviousLive,
                            colors = ButtonDefaults.buttonColors(containerColor = ZroEmerald, contentColor = Color.Black),
                            shape = RoundedCornerShape(7.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home_reconnect_btn")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reconnect to Previous Live", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = onDismissRecovery) {
                            Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // 1c. YouTube Standard 16:9 Live Output Preview Card
        item {
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "YouTube 16:9 Live Preview",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        val sourceLabel = if (streamTelemetry.activeVideoSource == LiveVideoSource.REMOTE_SECOND_PHONE || phoneConnected) {
                            "Source: 2nd Phone Screen"
                        } else {
                            "Source: This Phone Full Screen"
                        }
                        Text(
                            text = "${workspace.activeScene.name} • $sourceLabel",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = if (phoneConnected) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        onClick = onOpenFullScreenPreview,
                        shape = RoundedCornerShape(6.dp),
                        color = ZroCobalt.copy(alpha = 0.22f),
                        border = BorderStroke(1.dp, ZroCobalt),
                        modifier = Modifier.testTag("home_fullscreen_preview_btn")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Rounded.Fullscreen, contentDescription = "Full-Screen 16:9 Preview", tint = Color.White, modifier = Modifier.size(14.dp))
                            Text("Full 16:9", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val previewWorkspace = remember(workspace) {
                    workspace.copy(
                        showGrid = false,
                        showSnapGuides = false,
                        showSafeArea = false,
                        canvasZoom = 1f,
                        selectedLayerId = null
                    )
                }
                StudioCanvasPreview(
                    workspace = previewWorkspace,
                    mirroredFrame = mirroredFrame,
                    audioMeters = audioMeters,
                    chatMessages = emptyList(),
                    reducedMotion = reducedMotion,
                    dualTelemetry = dualTelemetry,
                    cameraPermissionGranted = cameraPermissionGranted,
                    enableLiveCameraPreview = workspace.cameraEnabled && cameraPermissionGranted,
                    interactive = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .testTag("home_16x9_preview_canvas")
                )
            }
        }

        // 2. Primary Broadcast Control Dock (LIVE SETUP + START/STOP LIVE)
        item {
            GlassCard(
                accentBorder = when {
                    workspace.brbActive -> ZroAmber
                    isLive -> ZroLiveRed
                    else -> null
                }
            ) {
                Text(
                    text = settings.stream.streamTitle.ifBlank { "Untitled Live Stream" },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                val configReady = settings.stream.rtmpUrl.isNotBlank() && settings.stream.streamKey.isNotBlank()
                Text(
                    text = "${settings.stream.visibility.label} • " +
                        if (configReady) "RTMP Configured" else "Configure URL & Key in Live Setup",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Prominent LIVE SETUP button
                BrandGradientButton(
                    text = "LIVE SETUP",
                    icon = Icons.Rounded.Tune,
                    onClick = onOpenLiveSetup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_live_setup_button")
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Start / Resume / End Live action
                Surface(
                    onClick = if (isActiveSession) onStopStreamingClick else onStartStreamingClick,
                    shape = RoundedCornerShape(8.dp),
                    color = if (isActiveSession) ZroLiveRed.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        if (isActiveSession) ZroLiveRed else MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dashboard_start_stream_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isActiveSession) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = if (isActiveSession) ZroLiveRed else ZroEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isPaused -> "END LIVE (PAUSED)"
                                isActiveSession -> "STOP LIVE STREAM"
                                else -> "START LIVE"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // 3. Compact Broadcast Status Grid (Connected Phone, Current Scene, Audio, Camera)
        item {
            SectionHeader(title = "Studio Status")
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusGridTile(
                        icon = Icons.Rounded.PhoneAndroid,
                        label = "Connected Phone",
                        value = if (phoneConnected) dualTelemetry.connectedDeviceName.ifBlank { "Paired" } else dualTelemetry.role.label,
                        statusDot = if (phoneConnected) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onOpenDualPairing,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_status_phone")
                    )
                    StatusGridTile(
                        icon = Icons.Rounded.Layers,
                        label = "Current Scene",
                        value = if (workspace.brbActive) "BRB / Privacy" else workspace.activeScene.name,
                        statusDot = if (workspace.brbActive) ZroAmber else ZroCobalt,
                        onClick = onOpenObsStudio,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_status_scene")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusGridTile(
                        icon = Icons.Rounded.GraphicEq,
                        label = "Quick Audio",
                        value = "Master ${(settings.audio.masterVolume * 100).toInt()}% • Team ${if (settings.audio.teamVoiceToLive) "LIVE" else "MUTED"}",
                        statusDot = if (settings.audio.micEnabled) ZroEmerald else ZroAmber,
                        onClick = onOpenAudioSettings,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_status_audio")
                    )
                    StatusGridTile(
                        icon = if (workspace.cameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                        label = "Facecam",
                        value = if (workspace.cameraEnabled) "ON • ${workspace.cameraSourceMode.label}" else "OFF",
                        statusDot = if (workspace.cameraEnabled) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onOpenCameraSettings,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_status_camera")
                    )
                }
            }
        }

        // 4. Compact Broadcast Shortcuts (Studio, Remote Deck, Chat, Voice)
        item {
            SectionHeader(title = "Quick Access")
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactShortcutButton(
                    icon = Icons.Rounded.Layers,
                    label = "Layers",
                    onClick = onOpenObsStudio,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_shortcut_studio")
                )
                CompactShortcutButton(
                    icon = Icons.Rounded.SettingsInputComponent,
                    label = "Remote",
                    onClick = onOpenRemoteDeck,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_shortcut_remote")
                )
                CompactShortcutButton(
                    icon = Icons.AutoMirrored.Rounded.Chat,
                    label = "Chat",
                    onClick = onOpenChat,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_shortcut_chat")
                )
                CompactShortcutButton(
                    icon = Icons.Rounded.HeadsetMic,
                    label = if (activeVoiceRoom != null) "Voice ON" else "Voice",
                    highlight = activeVoiceRoom != null,
                    onClick = onOpenVoiceRoom,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_voice_tile")
                )
            }
        }

        // 5. Recent Stream Info
        item {
            SectionHeader(
                title = "Recent Broadcasts",
                subtitle = if (recentSessions.isEmpty()) "Recommended: ${smartRecommendation.resolution.shortLabel} ${smartRecommendation.fps} FPS" else "${recentSessions.size} recorded locally",
                trailing = {
                    TextButton(
                        onClick = onOpenLiveManager,
                        modifier = Modifier.testTag("home_open_live_manager_btn")
                    ) {
                        Text("Live Manager", color = ZroCobalt)
                    }
                }
            )
        }

        if (recentSessions.isEmpty()) {
            item {
                GlassCard {
                    Text(
                        text = "No recent broadcasts yet. Tap LIVE SETUP to configure your RTMP destination, scene, and audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            item {
                GlassCard {
                    recentSessions.take(4).forEachIndexed { idx, session ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.title.ifBlank { "Live Stream" },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${session.resolutionLabel} • ${session.fps} FPS • ${session.bitrateKbps} kbps",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = formatHomeDuration(session.durationSec),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusGridTile(
    icon: ImageVector,
    label: String,
    value: String,
    statusDot: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(icon, contentDescription = label, tint = statusDot, modifier = Modifier.size(16.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CompactShortcutButton(
    icon: ImageVector,
    label: String,
    highlight: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (highlight) ZroEmerald.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (highlight) ZroEmerald.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlight) ZroEmerald else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

private fun formatHomeDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
