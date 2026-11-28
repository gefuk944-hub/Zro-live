package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.AudioSettings
import com.example.data.model.DeviceTelemetry
import com.example.data.model.LiveBroadcastMode
import com.example.data.model.PerformanceProfile
import com.example.data.model.RtmpTestResult
import com.example.data.model.SmartRecommendation
import com.example.data.model.StreamLifecycleState
import com.example.data.model.VideoResolution
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.StreamSettings
import com.example.data.model.StreamVisibility
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.streaming.RtmpPublisher
import com.example.streaming.StreamStateManager
import com.example.streaming.StreamingForegroundService
import com.example.streaming.ZroStreamCoordinator
import com.example.studio.DualLinkState
import com.example.studio.DualPhoneTelemetry
import com.example.studio.SceneSlot
import com.example.studio.StudioWorkspaceState
import com.example.telemetry.AppConnectivitySnapshot
import com.example.telemetry.DeviceTelemetryMonitor
import com.example.ui.components.BrandGradientButton
import com.example.ui.components.ConnectivityStatusBar
import com.example.ui.components.DeviceTempChip
import com.example.ui.components.GlassCard
import com.example.ui.components.LiveStatusBadge
import com.example.ui.components.ProSubpageTopBar
import com.example.ui.components.SectionHeader
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald
import com.example.ui.theme.ZroLiveRed

/**
 * Dedicated Live Setup page reached from Home's prominent LIVE SETUP button.
 * Consolidates stream platform, YouTube details, thumbnail, RTMP/key, video quality,
 * connected phone status, camera status, audio setup shortcut, and selected scene.
 */
@Composable
fun LiveSetupScreen(
    streamSettings: StreamSettings,
    audioSettings: AudioSettings,
    streamTelemetry: StreamRuntimeTelemetry,
    dualTelemetry: DualPhoneTelemetry,
    workspace: StudioWorkspaceState,
    onBack: () -> Unit,
    onUpdateStreamSettings: (StreamSettings) -> Unit,
    onPickThumbnail: () -> Unit,
    onClearThumbnail: () -> Unit,
    onSelectScene: (SceneSlot) -> Unit,
    onOpenAudioStudio: () -> Unit,
    onOpenDualPairing: () -> Unit,
    onOpenCameraSettings: () -> Unit,
    onOpenSceneStudio: () -> Unit,
    onStartLive: () -> Unit,
    onStopLive: () -> Unit,
    rtmpTestResult: RtmpTestResult? = null,
    isTestingRtmp: Boolean = false,
    onSaveConfiguration: () -> Unit = {},
    onTestConnection: () -> Unit = {},
    connectivitySnapshot: AppConnectivitySnapshot = AppConnectivitySnapshot(),
    onProbeStunServer: () -> Unit = {},
    onSwitchBroadcastMode: (LiveBroadcastMode) -> Unit = { mode ->
        onUpdateStreamSettings(streamSettings.switchBroadcastMode(mode))
    },
    hasRecoverableSession: Boolean = false,
    onReconnectLive: () -> Unit = onStartLive,
    deviceTelemetry: DeviceTelemetry = DeviceTelemetry(),
    smartRecommendation: SmartRecommendation? = null,
    onApplySmartRecommendation: () -> Unit = {}
) {
    val context = LocalContext.current
    val isLive = streamTelemetry.state == StreamLifecycleState.LIVE ||
        streamTelemetry.state == StreamLifecycleState.PAUSED ||
        streamTelemetry.state == StreamLifecycleState.CONNECTING ||
        streamTelemetry.state == StreamLifecycleState.CONNECTED ||
        streamTelemetry.state == StreamLifecycleState.SENDING_VIDEO ||
        streamTelemetry.state == StreamLifecycleState.RECONNECTING

    var showStreamKey by remember { mutableStateOf(false) }
    var showShortsStreamKey by remember { mutableStateOf(false) }
    val (dualLiveSupported, dualLiveStatusNote) = remember(context) {
        StreamingForegroundService.checkDualLiveSupport(context)
    }

    val keyLooksLikeUrl = RtmpPublisher.looksLikeUrl(streamSettings.streamKey)
    val configValidation = RtmpPublisher.validateConfig(
        primaryUrl = streamSettings.effectivePrimaryServerUrl(),
        backupUrl = streamSettings.effectiveBackupServerUrl(),
        streamKey = streamSettings.streamKey
    )
    val configValidationError = if (configValidation.valid) null else configValidation.errorMessage
    // Honest status: NEVER show fake READY/LIVE while offline; show actual streamTelemetry.state!
    val effectiveBadgeState = streamTelemetry.state
    val showTimer = streamTelemetry.state == StreamLifecycleState.LIVE ||
        streamTelemetry.state == StreamLifecycleState.PAUSED

    val liveTimerState by StreamStateManager.state.collectAsStateWithLifecycle()
    val liveTimer = if (liveTimerState.isStreaming || isLive) {
        liveTimerState.formattedDuration
    } else {
        ZroStreamCoordinator.formatDuration(streamTelemetry.durationSec)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ProSubpageTopBar(
            title = "Live Setup",
            subtitle = "Broadcast destination, quality & sources",
            onBack = onBack,
            backTestTag = "live_setup_back_button",
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DeviceTempChip(
                        tempText = DeviceTelemetryMonitor.formatTemperature(deviceTelemetry),
                        isOverheating = deviceTelemetry.isOverheating || streamTelemetry.thermalThrottled
                    )
                    LiveStatusBadge(
                        isLive = streamTelemetry.state == StreamLifecycleState.LIVE,
                        isPaused = streamTelemetry.state == StreamLifecycleState.PAUSED,
                        isReconnecting = streamTelemetry.state == StreamLifecycleState.RECONNECTING,
                        brbActive = workspace.brbActive,
                        lifecycleState = effectiveBadgeState,
                        durationText = if (showTimer) liveTimer else ""
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("live_setup_list"),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 0. Separate Network / Server / Streaming Connectivity Status
            item {
                ConnectivityStatusBar(
                    snapshot = connectivitySnapshot,
                    onCheckConnectivity = onProbeStunServer,
                    showStunDetail = true
                )
            }

            // 1. Live Broadcast Mode (16:9 Live / Shorts Live / Dual Live)
            item {
                GlassCard {
                    SectionHeader(
                        title = "Live Broadcast Mode",
                        subtitle = "${streamSettings.broadcastMode.label} • ${streamSettings.effectiveResolutionDisplay()} • Saved per mode"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LiveBroadcastMode.entries.forEach { mode ->
                            val selected = streamSettings.broadcastMode == mode
                            FilterChip(
                                selected = selected,
                                onClick = { onSwitchBroadcastMode(mode) },
                                label = {
                                    Text(
                                        text = mode.shortLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ZroCobalt.copy(alpha = 0.24f),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("live_mode_${mode.name.lowercase()}")
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = streamSettings.broadcastMode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (streamSettings.broadcastMode == LiveBroadcastMode.DUAL_LIVE) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = (if (dualLiveSupported) ZroEmerald else ZroLiveRed).copy(alpha = 0.14f),
                            border = BorderStroke(
                                1.dp,
                                (if (dualLiveSupported) ZroEmerald else ZroLiveRed).copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dual_live_support_banner")
                        ) {
                            Text(
                                text = if (dualLiveSupported) {
                                    "Dual Encoder Ready: $dualLiveStatusNote"
                                } else {
                                    "Dual Live Unavailable: $dualLiveStatusNote"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (dualLiveSupported) ZroEmerald else ZroLiveRed,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // 2. Manual Stream Destination (RTMP URL + Stream Key — No Login Required)
            item {
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            SectionHeader(
                                title = "Stream Connection (RTMP)",
                                subtitle = if (configValidationError == null) {
                                    "Configured • Key ${RtmpPublisher.maskStreamKey(streamSettings.streamKey)}"
                                } else {
                                    "Enter your manual RTMP URL and Stream Key"
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://studio.youtube.com"))
                                    )
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .height(28.dp)
                                .testTag("open_youtube_studio_btn")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "Open YouTube Studio",
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("YT Studio", fontSize = 10.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    val urlTypoSuggestion = remember(streamSettings.rtmpUrl) {
                        RtmpPublisher.suggestCorrectedRtmpUrl(streamSettings.rtmpUrl)
                    }
                    val urlValidationError = remember(streamSettings.rtmpUrl) {
                        if (streamSettings.rtmpUrl.isNotBlank()) {
                            RtmpPublisher.validateServerUrl(streamSettings.rtmpUrl, "Stream RTMP URL")
                        } else {
                            null
                        }
                    }
                    OutlinedTextField(
                        value = streamSettings.rtmpUrl,
                        onValueChange = { onUpdateStreamSettings(streamSettings.copy(rtmpUrl = it)) },
                        label = { Text("Stream RTMP URL") },
                        placeholder = { Text("rtmp:// or rtmps:// ingest server URL", fontSize = 12.sp) },
                        isError = urlValidationError != null,
                        supportingText = {
                            val effective = streamSettings.effectivePrimaryServerUrl()
                            when {
                                urlValidationError != null -> Text(
                                    text = urlValidationError,
                                    color = ZroLiveRed,
                                    fontSize = 11.sp
                                )
                                effective.isNotBlank() -> Text(
                                    text = "Server: $effective",
                                    fontSize = 10.sp,
                                    fontFamily = JetBrainsMonoFontFamily
                                )
                                else -> Text(
                                    text = "Paste your RTMP / RTMPS server URL (not auto-filled)",
                                    fontSize = 10.sp,
                                    fontFamily = JetBrainsMonoFontFamily
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_setup_primary_url_input")
                    )
                    if (urlTypoSuggestion != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { onUpdateStreamSettings(streamSettings.copy(rtmpUrl = urlTypoSuggestion)) },
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("live_setup_fix_url_typo_btn")
                        ) {
                            Text(
                                text = "Fix typo → $urlTypoSuggestion",
                                fontSize = 11.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = ZroAmber
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = streamSettings.streamKey,
                        onValueChange = { onUpdateStreamSettings(streamSettings.copy(streamKey = it)) },
                        label = { Text("Stream Key") },
                        placeholder = { Text("Paste secret stream key", fontSize = 12.sp) },
                        isError = keyLooksLikeUrl,
                        supportingText = {
                            when {
                                keyLooksLikeUrl -> Text(
                                    text = "That looks like a Server URL, not a Stream Key.",
                                    color = ZroLiveRed,
                                    fontSize = 11.sp
                                )
                                streamSettings.streamKey.isNotBlank() -> Text(
                                    text = "Protected & masked (${RtmpPublisher.maskStreamKey(streamSettings.streamKey)})",
                                    fontSize = 10.sp,
                                    fontFamily = JetBrainsMonoFontFamily
                                )
                                else -> Text(
                                    text = "Enter only your secret Stream Key (kept masked).",
                                    fontSize = 10.sp
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (showStreamKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(
                                onClick = { showStreamKey = !showStreamKey },
                                modifier = Modifier.testTag("live_setup_toggle_key_visibility")
                            ) {
                                Icon(
                                    imageVector = if (showStreamKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showStreamKey) "Hide stream key" else "Show stream key"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_setup_stream_key_input")
                    )

                    // Secondary Shorts 9:16 RTMP destination when Dual Live is selected
                    if (streamSettings.broadcastMode == LiveBroadcastMode.DUAL_LIVE) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val shortsUrlError = remember(streamSettings.shortsRtmpUrl) {
                            if (streamSettings.shortsRtmpUrl.isNotBlank()) {
                                RtmpPublisher.validateServerUrl(streamSettings.shortsRtmpUrl, "Shorts RTMP URL")
                            } else null
                        }
                        val shortsKeyLooksLikeUrl = RtmpPublisher.looksLikeUrl(streamSettings.shortsStreamKey)
                        OutlinedTextField(
                            value = streamSettings.shortsRtmpUrl,
                            onValueChange = { onUpdateStreamSettings(streamSettings.copy(shortsRtmpUrl = it)) },
                            label = { Text("Shorts 9:16 RTMP URL (Dual Live)") },
                            placeholder = { Text("rtmp://a.rtmp.youtube.com/live2 (leave blank to reuse primary)", fontSize = 11.sp) },
                            isError = shortsUrlError != null,
                            supportingText = {
                                Text(
                                    text = shortsUrlError ?: "Secondary vertical 9:16 RTMP ingest server",
                                    color = if (shortsUrlError != null) ZroLiveRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("live_setup_shorts_url_input")
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = streamSettings.shortsStreamKey,
                            onValueChange = { onUpdateStreamSettings(streamSettings.copy(shortsStreamKey = it)) },
                            label = { Text("Shorts 9:16 Stream Key (Dual Live)") },
                            placeholder = { Text("Paste separate YouTube Shorts Live stream key", fontSize = 11.sp) },
                            isError = shortsKeyLooksLikeUrl,
                            supportingText = {
                                Text(
                                    text = when {
                                        shortsKeyLooksLikeUrl -> "That looks like a URL, not a Stream Key."
                                        streamSettings.shortsStreamKey.isNotBlank() ->
                                            "Protected & masked (${RtmpPublisher.maskStreamKey(streamSettings.shortsStreamKey)})"
                                        else -> "Required for simultaneous 9:16 Shorts stream (kept masked)."
                                    },
                                    color = if (shortsKeyLooksLikeUrl) ZroLiveRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            },
                            singleLine = true,
                            visualTransformation = if (showShortsStreamKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { showShortsStreamKey = !showShortsStreamKey },
                                    modifier = Modifier.testTag("live_setup_toggle_shorts_key_visibility")
                                ) {
                                    Icon(
                                        imageVector = if (showShortsStreamKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (showShortsStreamKey) "Hide shorts stream key" else "Show shorts stream key"
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("live_setup_shorts_key_input")
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    // Save Configuration & Optional Test Connection actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSaveConfiguration,
                            colors = ButtonDefaults.buttonColors(containerColor = ZroCobalt, contentColor = Color.White),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("live_setup_save_config_btn")
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Save Config", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = onTestConnection,
                            enabled = !isTestingRtmp,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("live_setup_test_connection_btn")
                        ) {
                            if (isTestingRtmp) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.NetworkCheck, contentDescription = null, modifier = Modifier.size(15.dp))
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(if (isTestingRtmp) "Testing…" else "Test Connection", fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Test Connection is optional — you can press Start Live directly.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Test Connection Result Banner
                    if (rtmpTestResult != null && !rtmpTestResult.inProgress) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val passed = rtmpTestResult.success == true
                        val accent = if (passed) ZroEmerald else ZroLiveRed
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = accent.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("live_setup_test_result_card")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (passed) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (passed) "Connection Test Passed" else "Connection Test Failed",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = accent,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = rtmpTestResult.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Real Connection & Encoder Status Strip
                    Spacer(modifier = Modifier.height(8.dp))
                    val statusAccent = when (effectiveBadgeState) {
                        StreamLifecycleState.LIVE -> ZroLiveRed
                        StreamLifecycleState.CONNECTED, StreamLifecycleState.SENDING_VIDEO -> ZroCobalt
                        StreamLifecycleState.READY -> ZroEmerald
                        StreamLifecycleState.CONNECTING, StreamLifecycleState.RECONNECTING, StreamLifecycleState.PAUSED -> ZroAmber
                        StreamLifecycleState.ERROR -> ZroLiveRed
                        StreamLifecycleState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, statusAccent.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_setup_connection_status_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CONNECTION & ENCODER",
                                    fontSize = 10.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = effectiveBadgeState.displayLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = statusAccent
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Video: ${if (streamTelemetry.videoEncoderReady) "ACTIVE (${streamTelemetry.actualFps}fps)" else "STANDBY"}",
                                    fontSize = 10.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = if (streamTelemetry.videoEncoderReady) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Audio: ${if (streamTelemetry.audioEncoderReady) "ACTIVE (AAC)" else "STANDBY"}",
                                    fontSize = 10.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = if (streamTelemetry.audioEncoderReady) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (streamTelemetry.totalFrames > 0L) {
                                    Text(
                                        text = "${streamTelemetry.actualBitrateKbps} kbps",
                                        fontSize = 10.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = Color.White
                                    )
                                }
                            }
                            val detailText = streamTelemetry.errorMessage
                                ?: streamTelemetry.statusDetail.ifBlank {
                                    if (configValidationError == null) {
                                        "Server: ${streamSettings.effectivePrimaryServerUrl()}"
                                    } else {
                                        configValidationError
                                    }
                                }
                            Text(
                                text = detailText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (streamTelemetry.errorMessage != null) ZroLiveRed else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!isLive && (streamTelemetry.errorMessage != null || hasRecoverableSession)) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = onReconnectLive,
                                    colors = ButtonDefaults.buttonColors(containerColor = ZroAmber, contentColor = Color.Black),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("live_setup_reconnect_btn")
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Reconnect Same Live Session", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // 2. Title, Description, Visibility & Thumbnail
            item {
                GlassCard {
                    SectionHeader(title = "Stream Details")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = streamSettings.streamTitle,
                        onValueChange = { onUpdateStreamSettings(streamSettings.copy(streamTitle = it)) },
                        label = { Text("Stream Title") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_setup_title_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = streamSettings.streamDescription,
                        onValueChange = { onUpdateStreamSettings(streamSettings.copy(streamDescription = it)) },
                        label = { Text("Description") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StreamVisibility.entries.forEach { vis ->
                            FilterChip(
                                selected = streamSettings.visibility == vis,
                                onClick = { onUpdateStreamSettings(streamSettings.copy(visibility = vis)) },
                                label = { Text(vis.label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ZroCobalt.copy(alpha = 0.22f),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 88.dp, height = 50.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable(onClick = onPickThumbnail),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!streamSettings.thumbnailUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = streamSettings.thumbnailUri,
                                    contentDescription = "Stream thumbnail",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = "Select thumbnail",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Stream Thumbnail", style = MaterialTheme.typography.labelLarge, color = Color.White)
                            Text(
                                text = if (streamSettings.thumbnailUri != null) "Custom image selected" else "Optional 16:9 cover image",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onPickThumbnail,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("live_setup_pick_thumbnail_btn")
                        ) {
                            Text(if (streamSettings.thumbnailUri != null) "Change" else "Choose")
                        }
                        if (streamSettings.thumbnailUri != null) {
                            OutlinedButton(
                                onClick = onClearThumbnail,
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }

            // 3. Video Quality (720p / 1080p / 1440p / 4K • 30 / 60 / 90 FPS • up to 40 Mbps where hardware supports)
            item {
                val maxSupportedBitrate = remember(deviceTelemetry, streamSettings.lowDataMode) {
                    DeviceTelemetryMonitor.maxAllowedBitrateKbps(
                        telemetry = deviceTelemetry,
                        profile = PerformanceProfile.PERFORMANCE,
                        lowRamMode = deviceTelemetry.isLowRamDevice,
                        lowDataMode = streamSettings.lowDataMode
                    ).coerceIn(2500, 40_000)
                }
                GlassCard {
                    SectionHeader(
                        title = "Video Encoder (${streamSettings.broadcastMode.shortLabel})",
                        subtitle = "${streamSettings.effectiveResolutionDisplay()} • ${streamSettings.fps} FPS • ${"%.1f".format(streamSettings.bitrateKbps / 1000f)} Mbps"
                    )
                    if (smartRecommendation != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ZroEmerald.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, ZroEmerald.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("live_setup_smart_recommend_card")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "AUTO-RECOMMENDED: ${smartRecommendation.resolution.shortLabel} • ${smartRecommendation.fps} FPS • ${"%.1f".format(smartRecommendation.bitrateKbps / 1000f)} Mbps",
                                        fontSize = 10.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = ZroEmerald
                                    )
                                    Text(
                                        text = smartRecommendation.rationale,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                OutlinedButton(
                                    onClick = onApplySmartRecommendation,
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("live_setup_apply_smart_btn")
                                ) {
                                    Text("Apply", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    val mainResolutions = listOf(
                        VideoResolution.P720,
                        VideoResolution.P1080,
                        VideoResolution.P1440,
                        VideoResolution.P2160
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        mainResolutions.forEach { res ->
                            val hwOk = DeviceTelemetryMonitor.supportsResolutionAndFps(deviceTelemetry, res, 30)
                            FilterChip(
                                selected = streamSettings.resolution == res,
                                enabled = hwOk && !isLive,
                                onClick = {
                                    val safeFps = if (DeviceTelemetryMonitor.supportsResolutionAndFps(deviceTelemetry, res, streamSettings.fps)) {
                                        streamSettings.fps
                                    } else {
                                        30
                                    }
                                    onUpdateStreamSettings(streamSettings.copy(resolution = res, fps = safeFps))
                                },
                                label = {
                                    Text(
                                        text = if (hwOk) res.shortLabel else "${res.shortLabel} N/A",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ZroCobalt.copy(alpha = 0.22f),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("live_setup_res_${res.name.lowercase()}")
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(30, 60, 90).forEach { fps ->
                            val fpsSupported = DeviceTelemetryMonitor.supportsResolutionAndFps(
                                deviceTelemetry,
                                streamSettings.resolution,
                                fps
                            )
                            FilterChip(
                                selected = streamSettings.fps == fps,
                                enabled = fpsSupported && !isLive,
                                onClick = { onUpdateStreamSettings(streamSettings.copy(fps = fps)) },
                                label = {
                                    Text(
                                        text = if (fpsSupported) "$fps FPS" else "$fps (N/A)",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ZroCobalt.copy(alpha = 0.22f),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("live_setup_fps_$fps")
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Video Bitrate (Max ${"%.1f".format(maxSupportedBitrate / 1000f)} Mbps)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${"%.1f".format(streamSettings.bitrateKbps / 1000f)} Mbps",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = Color.White
                        )
                    }
                    Slider(
                        value = streamSettings.bitrateKbps.coerceIn(1000, maxSupportedBitrate).toFloat(),
                        onValueChange = { kbps ->
                            onUpdateStreamSettings(
                                streamSettings.copy(bitrateKbps = ((kbps / 500f).toInt() * 500).coerceIn(1000, maxSupportedBitrate))
                            )
                        },
                        valueRange = 1000f..maxSupportedBitrate.toFloat(),
                        enabled = !isLive,
                        colors = SliderDefaults.colors(thumbColor = ZroCobalt, activeTrackColor = ZroCobalt)
                    )
                    Text(
                        text = "Hardware: ${deviceTelemetry.hardwareAvcEncoderName} • Temp: ${DeviceTelemetryMonitor.formatTemperature(deviceTelemetry)} • Auto thermal step-down active",
                        fontSize = 10.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (deviceTelemetry.isOverheating) ZroAmber else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 4. Connected Sources & Subpage Shortcuts
            item {
                GlassCard {
                    SectionHeader(title = "Connected Sources & Scene")
                    Spacer(modifier = Modifier.height(8.dp))

                    val phoneConnected = dualTelemetry.linkState == DualLinkState.CONNECTED
                    SetupStatusRow(
                        icon = Icons.Rounded.PhoneAndroid,
                        label = "Gameplay Phone Link",
                        value = if (phoneConnected) "${dualTelemetry.connectedDeviceName} (${dualTelemetry.latencyMs} ms)" else dualTelemetry.role.label,
                        accent = if (phoneConnected) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onOpenDualPairing,
                        testTag = "live_setup_dual_row"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SetupStatusRow(
                        icon = Icons.Rounded.Videocam,
                        label = "Facecam / Camera",
                        value = if (workspace.cameraEnabled) "ON • ${workspace.cameraSourceMode.label}" else "OFF",
                        accent = if (workspace.cameraEnabled) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onOpenCameraSettings,
                        testTag = "live_setup_camera_row"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SetupStatusRow(
                        icon = Icons.Rounded.GraphicEq,
                        label = "Audio Setup",
                        value = "Master ${(audioSettings.masterVolume * 100).toInt()}% • Team ${if (audioSettings.teamVoiceToLive) "LIVE" else "MUTED"}",
                        accent = if (audioSettings.micEnabled) ZroEmerald else ZroAmber,
                        onClick = onOpenAudioStudio,
                        testTag = "live_setup_audio_row"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SetupStatusRow(
                        icon = Icons.Rounded.Layers,
                        label = "Active Scene",
                        value = "${workspace.activeScene.name} (${workspace.activeScene.layers.size} sources)",
                        accent = ZroCobalt,
                        onClick = onOpenSceneStudio,
                        testTag = "live_setup_scene_row"
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SceneSlot.entries.forEach { slot ->
                            val selected = workspace.activeSceneSlot == slot
                            Surface(
                                onClick = { onSelectScene(slot) },
                                shape = RoundedCornerShape(6.dp),
                                color = if (selected) ZroCobalt.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = slot.shortLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Fixed bottom Start / Stop Live action dock
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                BrandGradientButton(
                    text = when {
                        streamTelemetry.state == StreamLifecycleState.LIVE -> "STOP LIVE • $liveTimer"
                        streamTelemetry.state == StreamLifecycleState.PAUSED -> "STOP LIVE (PAUSED • $liveTimer)"
                        isLive -> "CONNECTING… • STOP"
                        else -> "START LIVE"
                    },
                    icon = if (isLive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    destructive = isLive,
                    onClick = if (isLive) onStopLive else onStartLive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_setup_start_live_button")
                )
            }
        }
    }
}

@Composable
private fun SetupStatusRow(
    icon: ImageVector,
    label: String,
    value: String,
    accent: Color,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = label, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Open $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
