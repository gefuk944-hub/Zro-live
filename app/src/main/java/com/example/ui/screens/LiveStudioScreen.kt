package com.example.ui.screens

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.chat.YouTubeLiveChatClient
import com.example.data.model.AudioMeterState
import com.example.data.model.AudioSettings
import com.example.data.model.CaptureMode
import com.example.data.model.DeviceTelemetry
import com.example.data.model.NoiseCancellationLevel
import com.example.data.model.PerformanceSettings
import com.example.data.model.StreamHealth
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamPreset
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.StreamSessionDoc
import com.example.data.model.StreamSettings
import com.example.data.model.StreamVisibility
import com.example.data.model.VideoResolution
import com.example.data.model.YouTubeChatState
import com.example.streaming.ZroStreamCoordinator
import com.example.telemetry.ActiveLiveSessionSnapshot
import com.example.telemetry.CrashLogEntry
import com.example.telemetry.DeviceTelemetryMonitor
import com.example.telemetry.SavedLiveConfigProfile
import com.example.ui.components.AudioLevelMeter
import com.example.ui.components.BrandGradientButton
import com.example.ui.components.GlassCard
import com.example.ui.components.LiveStatusBadge
import java.util.Locale
import com.example.ui.components.ProSubpageTopBar
import com.example.ui.components.RenameScreenCaptureDialog
import com.example.ui.components.SectionHeader
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald
import com.example.ui.theme.ZroLiveRed
import com.example.ui.theme.ZroObsidian
import com.example.ui.theme.ZroViolet

enum class LiveStudioTab(val label: String) {
    LIVE_MANAGER("Manager"),
    BROADCAST("Broadcast"),
    AUDIO_MIXER("Audio"),
    YOUTUBE_CHAT("Chat")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveStudioScreen(
    selectedTab: LiveStudioTab,
    onSelectTab: (LiveStudioTab) -> Unit,
    streamSettings: StreamSettings,
    audioSettings: AudioSettings,
    performanceSettings: PerformanceSettings,
    streamTelemetry: StreamRuntimeTelemetry,
    deviceTelemetry: DeviceTelemetry,
    audioMeters: AudioMeterState,
    chatState: YouTubeChatState,
    onUpdateStreamSettings: (StreamSettings) -> Unit,
    onSelectCaptureMode: (CaptureMode) -> Unit,
    onSelectGameCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onSelectPreset: (StreamPreset) -> Unit,
    onUpdateAudioSettings: (AudioSettings) -> Unit,
    onStartStreaming: () -> Unit,
    onPauseResumeStreaming: () -> Unit,
    onEndStreaming: () -> Unit,
    onToggleMicTest: () -> Unit,
    onToggleTeamVoiceToLive: () -> Unit = {},
    onApplyCloseVoiceFocus: (Boolean) -> Unit = {},
    onConnectChat: () -> Unit,
    onDisconnectChat: () -> Unit,
    onSendChatMessage: (String) -> Unit,
    onRenameScreenCapture: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    subpageTitle: String = "Audio & Broadcast Studio",
    recoverableSession: ActiveLiveSessionSnapshot? = null,
    recentSessions: List<StreamSessionDoc> = emptyList(),
    savedProfiles: List<SavedLiveConfigProfile> = emptyList(),
    crashLogs: List<CrashLogEntry> = emptyList(),
    onReconnectPreviousLive: () -> Unit = {},
    onDismissRecovery: () -> Unit = {},
    onCreateNewLive: (String) -> Unit = {},
    onOpenLiveSetup: () -> Unit = {},
    onOpenFullScreenPreview: () -> Unit = {},
    onResumeSavedSession: (StreamSessionDoc) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onSaveCurrentProfile: (String) -> Unit = {},
    onApplyProfile: (SavedLiveConfigProfile) -> Unit = {},
    onDeleteProfile: (String) -> Unit = {},
    onClearCrashLogs: () -> Unit = {}
) {
    val context = LocalContext.current
    var showEndStreamDialog by remember { mutableStateOf(false) }

    if (showEndStreamDialog) {
        AlertDialog(
            onDismissRequest = { showEndStreamDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("End Live Stream?", color = Color.White) },
            text = {
                Text(
                    "Your YouTube Live broadcast (${ZroStreamCoordinator.formatDuration(streamTelemetry.durationSec)}) and screen capture will stop immediately and all encoder resources will be released.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEndStreamDialog = false
                        onEndStreaming()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZroLiveRed),
                    modifier = Modifier.testTag("confirm_end_stream_button")
                ) {
                    Text("End Stream")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndStreamDialog = false }) {
                    Text("Keep Streaming")
                }
            }
        )
    }

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
                    title = subpageTitle,
                    onBack = onBack,
                    backTestTag = "live_studio_back_button"
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiveStudioTab.entries.forEach { tab ->
                    val active = tab == selectedTab
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectTab(tab) }
                            .testTag("studio_tab_${tab.name.lowercase()}"),
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) ZroCobalt.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            1.dp,
                            if (active) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when (tab) {
                                    LiveStudioTab.LIVE_MANAGER -> Icons.Rounded.History
                                    LiveStudioTab.BROADCAST -> Icons.Rounded.RadioButtonChecked
                                    LiveStudioTab.AUDIO_MIXER -> Icons.Rounded.GraphicEq
                                    LiveStudioTab.YOUTUBE_CHAT -> Icons.AutoMirrored.Rounded.Chat
                                },
                                contentDescription = tab.label,
                                tint = if (active) ZroCobalt else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            when (selectedTab) {
                LiveStudioTab.LIVE_MANAGER -> LiveManagerContent(
                    streamSettings = streamSettings,
                    streamTelemetry = streamTelemetry,
                    recoverableSession = recoverableSession,
                    recentSessions = recentSessions,
                    savedProfiles = savedProfiles,
                    crashLogs = crashLogs,
                    onReconnectPreviousLive = onReconnectPreviousLive,
                    onDismissRecovery = onDismissRecovery,
                    onCreateNewLive = onCreateNewLive,
                    onOpenLiveSetup = onOpenLiveSetup,
                    onOpenFullScreenPreview = onOpenFullScreenPreview,
                    onResumeSavedSession = onResumeSavedSession,
                    onDeleteSession = onDeleteSession,
                    onSaveCurrentProfile = onSaveCurrentProfile,
                    onApplyProfile = onApplyProfile,
                    onDeleteProfile = onDeleteProfile,
                    onClearCrashLogs = onClearCrashLogs
                )

                LiveStudioTab.BROADCAST -> BroadcastTabContent(
                    streamSettings = streamSettings,
                    performanceSettings = performanceSettings,
                    streamTelemetry = streamTelemetry,
                    deviceTelemetry = deviceTelemetry,
                    onUpdateStreamSettings = onUpdateStreamSettings,
                    onSelectCaptureMode = onSelectCaptureMode,
                    onSelectGameCapture = onSelectGameCapture,
                    onStopCapture = onStopCapture,
                    onStartStreaming = onStartStreaming,
                    onPauseResumeStreaming = onPauseResumeStreaming,
                    onRequestEndStreaming = { showEndStreamDialog = true },
                    onRenameScreenCapture = onRenameScreenCapture,
                    onOpenYouTubeStudio = { YouTubeLiveChatClient.openYouTubeStudioExternally(context) },
                    onOpenFullScreenPreview = onOpenFullScreenPreview
                )

                LiveStudioTab.AUDIO_MIXER -> AudioMixerTabContent(
                    audioSettings = audioSettings,
                    audioMeters = audioMeters,
                    streamTelemetry = streamTelemetry,
                    onUpdateAudioSettings = onUpdateAudioSettings,
                    onToggleMicTest = onToggleMicTest,
                    onToggleTeamVoiceToLive = onToggleTeamVoiceToLive,
                    onApplyCloseVoiceFocus = onApplyCloseVoiceFocus
                )

                LiveStudioTab.YOUTUBE_CHAT -> YouTubeChatTabContent(
                    streamSettings = streamSettings,
                    chatState = chatState,
                    onUpdateStreamSettings = onUpdateStreamSettings,
                    onConnectChat = onConnectChat,
                    onDisconnectChat = onDisconnectChat,
                    onSendChatMessage = onSendChatMessage,
                    onOpenExternalChat = {
                        YouTubeLiveChatClient.openYouTubeLiveChatExternally(context, streamSettings.youtubeVideoId)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BroadcastTabContent(
    streamSettings: StreamSettings,
    performanceSettings: PerformanceSettings,
    streamTelemetry: StreamRuntimeTelemetry,
    deviceTelemetry: DeviceTelemetry,
    onUpdateStreamSettings: (StreamSettings) -> Unit,
    onSelectCaptureMode: (CaptureMode) -> Unit,
    onSelectGameCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onStartStreaming: () -> Unit,
    onPauseResumeStreaming: () -> Unit,
    onRequestEndStreaming: () -> Unit,
    onRenameScreenCapture: (String) -> Unit = {},
    onOpenYouTubeStudio: () -> Unit,
    onOpenFullScreenPreview: () -> Unit = {}
) {
    val context = LocalContext.current
    var showStreamKey by remember { mutableStateOf(false) }
    var showRenameCaptureDialog by remember { mutableStateOf(false) }
    val isLive = streamTelemetry.state == StreamLifecycleState.LIVE
    val isPaused = streamTelemetry.state == StreamLifecycleState.PAUSED
    val isConnecting = streamTelemetry.state == StreamLifecycleState.CONNECTING ||
        streamTelemetry.state == StreamLifecycleState.CONNECTED ||
        streamTelemetry.state == StreamLifecycleState.SENDING_VIDEO
    val isReconnecting = streamTelemetry.state == StreamLifecycleState.RECONNECTING
    val isRunning = isLive || isPaused || isConnecting || isReconnecting
    val reducedMotion = performanceSettings.reduceAnimationsDuringGameplay || performanceSettings.lowRamMode
    val tempLabel = DeviceTelemetryMonitor.formatTemperature(deviceTelemetry)

    if (showRenameCaptureDialog) {
        RenameScreenCaptureDialog(
            currentName = streamSettings.screenCaptureName,
            onConfirm = { newName ->
                showRenameCaptureDialog = false
                onRenameScreenCapture(newName)
            },
            onDismiss = { showRenameCaptureDialog = false }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            ZroStreamCoordinator.attachPreviewSurface(context, null)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Real Live Captured Source Preview & Telemetry HUD
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(ZroObsidian)
            ) {
                // Live SurfaceView bound to real MediaProjection VirtualDisplay during capture preview!
                if (streamTelemetry.capturePreviewActive && !isRunning) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        ZroStreamCoordinator.attachPreviewSurface(ctx, holder.surface)
                                    }

                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                        ZroStreamCoordinator.attachPreviewSurface(ctx, holder.surface)
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        ZroStreamCoordinator.attachPreviewSurface(ctx, null)
                                    }
                                })
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("live_capture_preview_surface")
                    )
                }

                // Subtle readable scrim over preview
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (streamTelemetry.capturePreviewActive && !isRunning) Color(0x5506080E)
                            else Color(0xFF0E131F)
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiveStatusBadge(
                            isLive = isLive,
                            isPaused = isPaused,
                            isReconnecting = isReconnecting,
                            privacyShield = streamTelemetry.privacyShieldActive,
                            reducedMotion = reducedMotion,
                            lifecycleState = streamTelemetry.state,
                            durationText = if (isLive || isPaused) ZroStreamCoordinator.formatDuration(streamTelemetry.durationSec) else ""
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = onOpenFullScreenPreview,
                                shape = RoundedCornerShape(999.dp),
                                color = ZroCobalt.copy(alpha = 0.28f),
                                border = BorderStroke(1.dp, ZroCobalt),
                                modifier = Modifier.testTag("broadcast_fullscreen_preview_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(Icons.Rounded.Fullscreen, contentDescription = "16:9 Live Output Preview", tint = Color.White, modifier = Modifier.size(13.dp))
                                    Text("16:9", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)
                            ) {
                                Text(
                                    text = when {
                                        streamTelemetry.privacyShieldActive -> "Privacy Shield ON"
                                        streamTelemetry.concurrentViewers != null -> "${streamTelemetry.concurrentViewers} watching"
                                        streamTelemetry.capturePreviewActive -> "Live Preview Active"
                                        isLive -> streamTelemetry.captureMode.shortLabel
                                        else -> streamSettings.captureMode.shortLabel
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = if (streamTelemetry.privacyShieldActive || streamTelemetry.capturePreviewActive) ZroEmerald else Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Center Privacy Shield or Empty Capture Prompt
                    if (streamTelemetry.privacyShieldActive) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ZroEmerald.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, ZroEmerald.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Shield, contentDescription = null, tint = ZroEmerald, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Selected game is hidden or closed. Video broadcast paused to protect your privacy.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                        }
                    } else if (!streamTelemetry.captureActive && !isRunning) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VideogameAsset,
                                contentDescription = null,
                                tint = ZroCobalt,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap 'Select Game' below to preview your captured game before going live",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Bottom Telemetry Strip
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HudMetricPill("RES", streamSettings.resolution.shortLabel, Modifier.weight(1f))
                            HudMetricPill(
                                "FPS",
                                if (isLive) "${streamTelemetry.actualFps}/${streamTelemetry.targetFps}" else "${streamSettings.fps}",
                                Modifier.weight(1f)
                            )
                            HudMetricPill(
                                "BITRATE",
                                if (isLive) "${String.format(Locale.US, "%.1f", streamTelemetry.actualBitrateKbps / 1000f)}M"
                                else "${String.format(Locale.US, "%.1f", streamSettings.bitrateKbps / 1000f)}M",
                                Modifier.weight(1f)
                            )
                            HudMetricPill(
                                "DROPS",
                                "${streamTelemetry.droppedFrames}",
                                Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Health: ${streamTelemetry.streamHealth.label} • ${deviceTelemetry.networkType}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = when (streamTelemetry.streamHealth) {
                                    StreamHealth.EXCELLENT, StreamHealth.GOOD -> ZroEmerald
                                    StreamHealth.FAIR -> ZroAmber
                                    StreamHealth.POOR -> ZroLiveRed
                                    StreamHealth.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                text = "Thermal: $tempLabel",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = if (deviceTelemetry.isOverheating) ZroLiveRed else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Honest Overheating / Thermal Step-Down Banner
        if (deviceTelemetry.isOverheating || streamTelemetry.thermalThrottled) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ZroAmber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, ZroAmber.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = ZroAmber)
                        Text(
                            text = if (streamTelemetry.thermalThrottled) {
                                "Thermal protection stepped down bitrate to ${streamTelemetry.targetBitrateKbps} kbps ($tempLabel)."
                            } else {
                                "Device overheating detected ($tempLabel). Lower resolution or enable Battery Saver."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFCD34D)
                        )
                    }
                }
            }
        }

        // Honest Error Banner if RTMP / encoder / capture failed
        if (!streamTelemetry.errorMessage.isNullOrBlank()) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ZroLiveRed.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, ZroLiveRed.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = ZroLiveRed)
                        Text(
                            text = streamTelemetry.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFCA5A5)
                        )
                    }
                }
            }
        }

        // Privacy-First Game / App-Only Capture Control Card
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentBorder = if (streamSettings.captureMode == CaptureMode.GAME_APP_ONLY) ZroEmerald else ZroAmber
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(
                        title = streamSettings.screenCaptureName.ifBlank { "Game Capture Source" },
                        subtitle = if (deviceTelemetry.supportsAppOnlyCapture) {
                            "Android 14+ single-app window capture supported"
                        } else {
                            "Single-app capture requires Android 14+ (explicit opt-in required on older OS)"
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { showRenameCaptureDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("rename_screen_capture_btn")
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Rename screen capture", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rename", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureMode.entries.forEach { mode ->
                        FilterChip(
                            selected = streamSettings.captureMode == mode,
                            enabled = !isRunning,
                            onClick = { onSelectCaptureMode(mode) },
                            label = { Text(mode.shortLabel) },
                            modifier = Modifier.testTag("capture_mode_${mode.name.lowercase()}")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = streamSettings.captureMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onSelectGameCapture,
                        enabled = !isRunning,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, ZroCobalt),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("studio_select_game_button")
                    ) {
                        Icon(Icons.Rounded.VideogameAsset, contentDescription = null, tint = ZroCobalt, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (streamTelemetry.captureActive) "Change Game" else "Select Game",
                            color = Color.White
                        )
                    }

                    if (streamTelemetry.captureActive) {
                        OutlinedButton(
                            onClick = onStopCapture,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, ZroLiveRed),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("studio_stop_capture_button")
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, tint = ZroLiveRed, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop Capture", color = Color(0xFFFCA5A5))
                        }
                    }
                }
            }
        }

        // Primary Broadcast Controls
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isRunning) {
                    BrandGradientButton(
                        text = if (streamTelemetry.capturePreviewActive) "Go Live with Selected Game" else "Go Live on YouTube",
                        icon = Icons.Rounded.PlayArrow,
                        onClick = onStartStreaming,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("studio_go_live_button")
                    )
                } else {
                    OutlinedButton(
                        onClick = onPauseResumeStreaming,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, ZroAmber),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("studio_pause_resume_button")
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = if (isPaused) "Resume" else "Pause",
                            tint = ZroAmber
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isPaused) "Resume" else "Pause", color = Color.White)
                    }

                    val liveTimer = ZroStreamCoordinator.formatDuration(streamTelemetry.durationSec)
                    BrandGradientButton(
                        text = if (isLive || isPaused) "End • $liveTimer" else "End Stream",
                        icon = Icons.Rounded.Stop,
                        destructive = true,
                        onClick = onRequestEndStreaming,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("studio_end_stream_button")
                    )
                }
            }
        }

        // YouTube RTMP Credentials & Metadata
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = "YouTube Live Broadcast Setup",
                    subtitle = "Connect via YouTube Studio Stream Key & RTMP Ingest",
                    trailing = {
                        TextButton(onClick = onOpenYouTubeStudio) {
                            Text("YouTube Studio", color = ZroCobalt)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = ZroCobalt, modifier = Modifier.size(15.dp))
                        }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = streamSettings.rtmpUrl,
                    onValueChange = { onUpdateStreamSettings(streamSettings.copy(rtmpUrl = it)) },
                    label = { Text("Stream RTMP URL") },
                    placeholder = { Text("rtmp:// or rtmps:// ingest server URL") },
                    singleLine = true,
                    enabled = !isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rtmp_url_input")
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = streamSettings.streamKey,
                    onValueChange = { onUpdateStreamSettings(streamSettings.copy(streamKey = it)) },
                    label = { Text("Stream Key") },
                    placeholder = { Text("Enter your stream key") },
                    singleLine = true,
                    enabled = !isRunning,
                    visualTransformation = if (showStreamKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showStreamKey = !showStreamKey }) {
                            Icon(
                                imageVector = if (showStreamKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Toggle key visibility"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stream_key_input")
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = streamSettings.streamTitle,
                    onValueChange = { onUpdateStreamSettings(streamSettings.copy(streamTitle = it.take(120))) },
                    label = { Text("Stream Title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stream_title_input")
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = streamSettings.streamDescription,
                    onValueChange = { onUpdateStreamSettings(streamSettings.copy(streamDescription = it.take(500))) },
                    label = { Text("Description") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text("Visibility", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamVisibility.entries.forEach { vis ->
                        FilterChip(
                            selected = streamSettings.visibility == vis,
                            onClick = { onUpdateStreamSettings(streamSettings.copy(visibility = vis)) },
                            label = { Text(vis.label) }
                        )
                    }
                }
            }
        }

        // Video Quality & Manual Controls (720p / 1080p / 1440p / 4K • 30 / 60 / 90 FPS • up to 40 Mbps where supported)
        item {
            val maxBitrateKbps = remember(deviceTelemetry, performanceSettings.profile, performanceSettings.lowRamMode, streamSettings.lowDataMode) {
                DeviceTelemetryMonitor.maxAllowedBitrateKbps(
                    telemetry = deviceTelemetry,
                    profile = performanceSettings.profile,
                    lowRamMode = performanceSettings.lowRamMode || deviceTelemetry.isLowRamDevice,
                    lowDataMode = streamSettings.lowDataMode
                ).coerceIn(2500, 40_000)
            }
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = "Video Quality & Encoder",
                    subtitle = "Hardware: ${deviceTelemetry.hardwareAvcEncoderName} • Max ${String.format(Locale.US, "%.1f", maxBitrateKbps / 1000f)} Mbps"
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text("Resolution", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VideoResolution.entries.forEach { res ->
                        val resSupported = DeviceTelemetryMonitor.supportsResolutionAndFps(deviceTelemetry, res, 30)
                        FilterChip(
                            selected = streamSettings.resolution == res,
                            enabled = !isRunning && resSupported,
                            onClick = {
                                val nextFps = if (DeviceTelemetryMonitor.supportsResolutionAndFps(deviceTelemetry, res, streamSettings.fps)) {
                                    streamSettings.fps
                                } else {
                                    30
                                }
                                onUpdateStreamSettings(
                                    streamSettings.copy(
                                        preset = StreamPreset.CUSTOM,
                                        resolution = res,
                                        fps = nextFps
                                    )
                                )
                            },
                            label = { Text(if (resSupported) res.label else "${res.shortLabel} (N/A)") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Frame Rate (FPS)", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 90).forEach { fpsOpt ->
                        val supported = DeviceTelemetryMonitor.supportsResolutionAndFps(
                            deviceTelemetry,
                            streamSettings.resolution,
                            fpsOpt
                        )
                        FilterChip(
                            selected = streamSettings.fps == fpsOpt,
                            enabled = !isRunning && supported,
                            onClick = {
                                onUpdateStreamSettings(
                                    streamSettings.copy(preset = StreamPreset.CUSTOM, fps = fpsOpt)
                                )
                            },
                            label = { Text("$fpsOpt FPS${if (!supported) " (N/A)" else ""}") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Target Bitrate", style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text(
                        text = "${String.format(Locale.US, "%.1f", streamSettings.bitrateKbps / 1000f)} Mbps (${streamSettings.bitrateKbps} kbps)",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = ZroCobalt
                    )
                }
                Slider(
                    value = streamSettings.bitrateKbps.coerceIn(1000, maxBitrateKbps).toFloat(),
                    onValueChange = {
                        val rounded = (it / 500f).toInt() * 500
                        onUpdateStreamSettings(
                            streamSettings.copy(
                                preset = StreamPreset.CUSTOM,
                                bitrateKbps = rounded.coerceIn(1000, maxBitrateKbps)
                            )
                        )
                    },
                    valueRange = 1000f..maxBitrateKbps.toFloat(),
                    enabled = !isRunning,
                    colors = SliderDefaults.colors(thumbColor = ZroCobalt, activeTrackColor = ZroCobalt)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Adaptive Bitrate Recovery", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Text(
                            "Automatically lowers bitrate when network packets stall",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = streamSettings.adaptiveBitrate,
                        onCheckedChange = { onUpdateStreamSettings(streamSettings.copy(adaptiveBitrate = it)) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Low-Data Mode", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Text(
                            "Caps broadcast to 2.5 Mbps & 30 FPS for mobile data networks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = streamSettings.lowDataMode,
                        onCheckedChange = { onUpdateStreamSettings(streamSettings.copy(lowDataMode = it)) }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioMixerTabContent(
    audioSettings: AudioSettings,
    audioMeters: AudioMeterState,
    streamTelemetry: StreamRuntimeTelemetry,
    onUpdateAudioSettings: (AudioSettings) -> Unit,
    onToggleMicTest: () -> Unit,
    onToggleTeamVoiceToLive: () -> Unit,
    onApplyCloseVoiceFocus: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentBorder = if (audioMeters.isTestingMic) ZroEmerald else ZroCobalt
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Unified Livestream Audio Mixer", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(
                            text = "Real-time 3-bus broadcast mix (Mic + Game + Consented Squad)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onToggleMicTest,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (audioMeters.isTestingMic) ZroLiveRed else ZroEmerald
                        ),
                        modifier = Modifier.testTag("mic_test_button")
                    ) {
                        Icon(
                            imageVector = if (audioMeters.isTestingMic) Icons.Rounded.Stop else Icons.Rounded.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (audioMeters.isTestingMic) "Stop Test" else "Mic Test")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                AudioLevelMeter(
                    level = audioMeters.masterLevel,
                    peak = audioMeters.masterPeak,
                    label = "MASTER BROADCAST OUTPUT (${if (audioSettings.stereoOutput) "STEREO" else "MONO"})",
                    dbText = "${String.format(Locale.US, "%.1f", audioMeters.masterDb)} dBFS"
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Game Audio Capture: ${streamTelemetry.gameAudioCaptureNote}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                MixerChannelControl(
                    title = "Microphone (Stream + Squad)",
                    enabled = audioSettings.micEnabled,
                    volume = audioSettings.micVolume,
                    meterLevel = audioMeters.micLevel,
                    meterDb = audioMeters.micDb,
                    accent = ZroCobalt,
                    onToggle = { onUpdateAudioSettings(audioSettings.copy(micEnabled = it)) },
                    onVolumeChange = { onUpdateAudioSettings(audioSettings.copy(micVolume = it)) }
                )
                Spacer(modifier = Modifier.height(16.dp))
                MixerChannelControl(
                    title = "Game Audio (Internal Capture)",
                    enabled = audioSettings.gameAudioEnabled,
                    volume = audioSettings.gameVolume,
                    meterLevel = audioMeters.gameLevel,
                    meterDb = audioMeters.gameDb,
                    accent = ZroViolet,
                    onToggle = { onUpdateAudioSettings(audioSettings.copy(gameAudioEnabled = it)) },
                    onVolumeChange = { onUpdateAudioSettings(audioSettings.copy(gameVolume = it)) }
                )
                Spacer(modifier = Modifier.height(16.dp))
                MixerChannelControl(
                    title = "Teammate Voice Bus (Consented)",
                    enabled = audioSettings.teamVoiceEnabled,
                    volume = audioSettings.teamVolume,
                    meterLevel = audioMeters.teamLevel,
                    meterDb = audioMeters.teamDb,
                    accent = ZroEmerald,
                    onToggle = { onUpdateAudioSettings(audioSettings.copy(teamVoiceEnabled = it)) },
                    onVolumeChange = { onUpdateAudioSettings(audioSettings.copy(teamVolume = it)) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onToggleTeamVoiceToLive,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (audioSettings.teamVoiceToLive) ZroEmerald else Color(0xFF1F2937),
                        contentColor = if (audioSettings.teamVoiceToLive) Color.Black else Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("mixer_team_voice_live_button")
                ) {
                    Text(
                        text = if (audioSettings.teamVoiceToLive) "TEAM VOICE TO LIVE: ON (LIVE MIX)" else "TEAM VOICE TO LIVE: OFF (PRIVATE ONLY)",
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Master Stream Volume", style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text(
                        text = "${(audioSettings.masterVolume * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = ZroAmber
                    )
                }
                Slider(
                    value = audioSettings.masterVolume,
                    onValueChange = { onUpdateAudioSettings(audioSettings.copy(masterVolume = it)) },
                    valueRange = 0f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = ZroAmber, activeTrackColor = ZroAmber)
                )
            }
        }

        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = "Noise Cancellation Level",
                    subtitle = if (audioMeters.hardwareNsSupported) {
                        "Hardware NoiseSuppressor + CPU-optimized Soft-Wiener DSP"
                    } else {
                        "CPU-optimized Soft-Wiener DSP active (natural vocal preservation)"
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NoiseCancellationLevel.entries.forEach { level ->
                        FilterChip(
                            selected = audioSettings.noiseCancellationLevel == level,
                            onClick = { onUpdateAudioSettings(audioSettings.copy(noiseCancellationLevel = level)) },
                            label = { Text(level.label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = audioSettings.noiseCancellationLevel.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZroCobalt
                )
            }
        }

        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = "Voice Processing & Clarity DSP")
                Spacer(modifier = Modifier.height(10.dp))

                DspToggleRow(
                    title = "Stereo Broadcast Output",
                    subtitle = "AAC-LC 44.1 kHz Stereo (switch off for lower-bitrate Mono)",
                    checked = audioSettings.stereoOutput,
                    onCheckedChange = { onUpdateAudioSettings(audioSettings.copy(stereoOutput = it)) }
                )
                DspToggleRow(
                    title = "Acoustic Echo Cancellation (AEC)",
                    subtitle = if (audioMeters.hardwareAecSupported) "Hardware AEC supported & active" else "Not exposed by hardware HAL",
                    checked = audioSettings.echoCancellation,
                    onCheckedChange = { onUpdateAudioSettings(audioSettings.copy(echoCancellation = it)) }
                )
                DspToggleRow(
                    title = "Automatic Gain Control (AGC)",
                    subtitle = if (audioMeters.hardwareAgcSupported) "Balances soft callouts and loud reactions" else "Fast software limiter active",
                    checked = audioSettings.automaticGainControl,
                    onCheckedChange = { onUpdateAudioSettings(audioSettings.copy(automaticGainControl = it)) }
                )
                DspToggleRow(
                    title = "Close Voice Focus (~0.5 ft Preset)",
                    subtitle = "Optimized voice gate & direct-voice isolation for close speaking without clipping word ends. Note: software uses audio energy & SNR—cannot physically measure exact distance.",
                    checked = audioSettings.closeVoiceFocusEnabled,
                    onCheckedChange = onApplyCloseVoiceFocus
                )
                Text(
                    text = "Voice Gate Status: ${if (audioMeters.gateOpen) "OPEN (VOICE DETECTED)" else "CLOSED (BACKGROUND SUPPRESSED)"} • Mic DSP never alters game or team audio.",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = if (audioMeters.gateOpen) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                DspToggleRow(
                    title = "Wind & Fan Noise Reduction (Variable HPF)",
                    subtitle = "High-pass cutoff scales 75Hz–220Hz (${(audioSettings.windReductionStrength * 100).toInt()}%)",
                    checked = audioSettings.windNoiseReduction,
                    onCheckedChange = { onUpdateAudioSettings(audioSettings.copy(windNoiseReduction = it)) }
                )
                if (audioSettings.windNoiseReduction) {
                    Slider(
                        value = audioSettings.windReductionStrength,
                        onValueChange = { onUpdateAudioSettings(audioSettings.copy(windReductionStrength = it)) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = ZroCobalt, activeTrackColor = ZroCobalt),
                        modifier = Modifier.testTag("wind_reduction_slider")
                    )
                }

                DspSliderRow(
                    title = "Microphone Sensitivity",
                    valueLabel = "${(audioSettings.micSensitivity * 100).toInt()}%",
                    value = audioSettings.micSensitivity,
                    range = 0.4f..2.0f,
                    onChange = { onUpdateAudioSettings(audioSettings.copy(micSensitivity = it)) }
                )
                DspSliderRow(
                    title = "Voice Isolation Strength",
                    valueLabel = "${(audioSettings.voiceIsolationStrength * 100).toInt()}%",
                    value = audioSettings.voiceIsolationStrength,
                    range = 0f..1f,
                    onChange = { onUpdateAudioSettings(audioSettings.copy(voiceIsolationStrength = it)) }
                )
                DspSliderRow(
                    title = "Ambient Noise Reduction",
                    valueLabel = "${(audioSettings.ambientNoiseReduction * 100).toInt()}%",
                    value = audioSettings.ambientNoiseReduction,
                    range = 0f..1f,
                    onChange = { onUpdateAudioSettings(audioSettings.copy(ambientNoiseReduction = it)) }
                )

                DspToggleRow(
                    title = "Voice Clarity Enhancement",
                    subtitle = "Gentle vocal presence shelf (skipped in Battery Saver/Performance profile)",
                    checked = audioSettings.voiceClarityEnhancement,
                    onCheckedChange = { onUpdateAudioSettings(audioSettings.copy(voiceClarityEnhancement = it)) }
                )
                DspToggleRow(
                    title = "Smooth Noise Gate",
                    subtitle = "Mutes mic floor below ${audioSettings.noiseGateThresholdDb.toInt()} dBFS between callouts",
                    checked = audioSettings.noiseGateEnabled,
                    onCheckedChange = { onUpdateAudioSettings(audioSettings.copy(noiseGateEnabled = it)) }
                )
                if (audioSettings.noiseGateEnabled) {
                    Slider(
                        value = audioSettings.noiseGateThresholdDb,
                        onValueChange = { onUpdateAudioSettings(audioSettings.copy(noiseGateThresholdDb = it)) },
                        valueRange = -58f..-20f,
                        colors = SliderDefaults.colors(thumbColor = ZroCobalt, activeTrackColor = ZroCobalt)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun MixerChannelControl(
    title: String,
    enabled: Boolean,
    volume: Float,
    meterLevel: Float,
    meterDb: Float,
    accent: Color,
    onToggle: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${(volume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = accent
                )
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
        AudioLevelMeter(
            level = meterLevel,
            dbText = "${String.format(Locale.US, "%.1f", meterDb)} dB",
            muted = !enabled
        )
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            valueRange = 0f..1.25f,
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
        )
    }
}

@Composable
private fun DspToggleRow(
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
private fun DspSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetBrainsMonoFontFamily,
                color = ZroCobalt
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = ZroCobalt, activeTrackColor = ZroCobalt)
        )
    }
}

@Composable
private fun YouTubeChatTabContent(
    streamSettings: StreamSettings,
    chatState: YouTubeChatState,
    onUpdateStreamSettings: (StreamSettings) -> Unit,
    onConnectChat: () -> Unit,
    onDisconnectChat: () -> Unit,
    onSendChatMessage: (String) -> Unit,
    onOpenExternalChat: () -> Unit
) {
    var draftMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("YouTube Live Chat API", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenExternalChat) {
                        Text("Open YouTube", color = ZroCobalt)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = ZroCobalt, modifier = Modifier.size(15.dp))
                    }
                    Switch(
                        checked = streamSettings.chatEnabled,
                        onCheckedChange = {
                            onUpdateStreamSettings(streamSettings.copy(chatEnabled = it))
                            if (!it) onDisconnectChat()
                        }
                    )
                }
            }

            if (streamSettings.chatEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = streamSettings.youtubeVideoId,
                    onValueChange = { onUpdateStreamSettings(streamSettings.copy(youtubeVideoId = it)) },
                    label = { Text("YouTube Live Video ID or URL") },
                    placeholder = { Text("e.g. dQw4w9WgXcQ or youtube.com/live/...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = streamSettings.youtubeApiKeyOrToken,
                    onValueChange = { onUpdateStreamSettings(streamSettings.copy(youtubeApiKeyOrToken = it)) },
                    label = { Text("YouTube Data API Key (Live Chat Read)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val connected = chatState is YouTubeChatState.Connected
                    Button(
                        onClick = { if (connected) onDisconnectChat() else onConnectChat() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (connected) ZroLiveRed else ZroCobalt),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("connect_youtube_chat_button")
                    ) {
                        Text(if (connected) "Disconnect Chat" else "Connect Live Chat")
                    }
                    OutlinedButton(
                        onClick = { onUpdateStreamSettings(streamSettings.copy(chatVisible = !streamSettings.chatVisible)) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (streamSettings.chatVisible) "Hide Feed" else "Show Feed")
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (chatState) {
                YouTubeChatState.Disconnected -> {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Live Chat Disconnected", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Enter your active YouTube Live Video ID and API Key above to stream real viewer messages here, or tap 'Open YouTube' to launch YouTube Live Chat directly. Zro live never displays fake messages.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                YouTubeChatState.Connecting -> {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = ZroCobalt, strokeWidth = 2.dp)
                            Text("Connecting to YouTube Live Chat API…", color = Color.White)
                        }
                    }
                }
                is YouTubeChatState.Error -> {
                    GlassCard(modifier = Modifier.fillMaxWidth(), accentBorder = ZroLiveRed) {
                        Text("YouTube Chat Connection Error", style = MaterialTheme.typography.titleMedium, color = Color(0xFFFCA5A5))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(chatState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(onClick = onOpenExternalChat, shape = RoundedCornerShape(12.dp)) {
                            Text("Open YouTube Live Chat Directly")
                        }
                    }
                }
                is YouTubeChatState.Connected -> {
                    if (!streamSettings.chatVisible) {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text("Chat feed hidden. Tap 'Show Feed' above to view ${chatState.messages.size} messages.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (chatState.messages.isEmpty()) {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text("Connected to YouTube Live Chat", style = MaterialTheme.typography.titleMedium, color = ZroEmerald)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Waiting for live viewer messages…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(chatState.messages, key = { it.id }) { msg ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (msg.isHighlightedNew) ZroCobalt.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(
                                        1.dp,
                                        if (msg.isHighlightedNew) ZroCobalt.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        if (!msg.authorAvatarUrl.isNullOrBlank()) {
                                            AsyncImage(
                                                model = msg.authorAvatarUrl,
                                                contentDescription = msg.authorName,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = msg.authorName,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = when {
                                                        msg.isOwner -> ZroAmber
                                                        msg.isModerator -> ZroCobalt
                                                        else -> Color.White
                                                    }
                                                )
                                                if (msg.isOwner) {
                                                    Text("HOST", style = MaterialTheme.typography.labelSmall, color = ZroAmber)
                                                } else if (msg.isModerator) {
                                                    Text("MOD", style = MaterialTheme.typography.labelSmall, color = ZroCobalt)
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = msg.messageText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (chatState is YouTubeChatState.Connected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draftMessage,
                    onValueChange = { draftMessage = it.take(200) },
                    placeholder = { Text("Send message to YouTube Live Chat…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        if (draftMessage.isNotBlank()) {
                            onSendChatMessage(draftMessage)
                            draftMessage = ""
                        }
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ZroCobalt)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun HudMetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color(0xAA0C101B),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
