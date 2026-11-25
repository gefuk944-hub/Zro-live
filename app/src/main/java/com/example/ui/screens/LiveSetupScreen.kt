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
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
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
import com.example.data.model.RtmpTestResult
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamPlatform
import com.example.data.model.VideoResolution
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.StreamSettings
import com.example.data.model.StreamVisibility
import com.example.data.model.YouTubeAccountState
import com.example.streaming.RtmpPublisher
import com.example.streaming.ZroStreamCoordinator
import com.example.studio.DualLinkState
import com.example.studio.DualPhoneTelemetry
import com.example.studio.SceneSlot
import com.example.studio.StudioWorkspaceState
import com.example.ui.components.BrandGradientButton
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
    youTubeAccount: YouTubeAccountState = YouTubeAccountState(),
    rtmpTestResult: RtmpTestResult? = null,
    isTestingRtmp: Boolean = false,
    onConnectYouTubeAccount: () -> Unit = {},
    onDisconnectYouTubeAccount: () -> Unit = {},
    onSaveConfiguration: () -> Unit = {},
    onTestConnection: () -> Unit = {}
) {
    val context = LocalContext.current
    val isLive = streamTelemetry.state == StreamLifecycleState.LIVE ||
        streamTelemetry.state == StreamLifecycleState.PAUSED ||
        streamTelemetry.state == StreamLifecycleState.CONNECTING ||
        streamTelemetry.state == StreamLifecycleState.CONNECTED ||
        streamTelemetry.state == StreamLifecycleState.SENDING_VIDEO ||
        streamTelemetry.state == StreamLifecycleState.RECONNECTING

    var showStreamKey by remember { mutableStateOf(false) }

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
                LiveStatusBadge(
                    isLive = streamTelemetry.state == StreamLifecycleState.LIVE,
                    isPaused = streamTelemetry.state == StreamLifecycleState.PAUSED,
                    isReconnecting = streamTelemetry.state == StreamLifecycleState.RECONNECTING,
                    brbActive = workspace.brbActive,
                    lifecycleState = effectiveBadgeState,
                    durationText = if (showTimer) ZroStreamCoordinator.formatDuration(streamTelemetry.durationSec) else ""
                )
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
            // 1. Stream Destination (Simple Stream RTMP URL + Stream Key) & Optional YouTube Login
            item {
                GlassCard {
                    SectionHeader(
                        title = "Stream Connection",
                        subtitle = if (configValidationError == null) {
                            "Configured • Key ${RtmpPublisher.maskStreamKey(streamSettings.streamKey)}"
                        } else {
                            "Enter your Stream RTMP URL and Stream Key to go live"
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Optional YouTube / Google Account Connect Row (not mandatory for RTMP)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            1.dp,
                            if (youTubeAccount.signedIn) ZroEmerald.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("youtube_account_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AccountCircle,
                                    contentDescription = "YouTube Account",
                                    tint = if (youTubeAccount.signedIn) ZroEmerald else ZroLiveRed,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (youTubeAccount.signedIn) {
                                            youTubeAccount.displayName.ifBlank { "Google / YouTube Connected" }
                                        } else {
                                            "YouTube / Google Account (Optional)"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (youTubeAccount.signedIn) {
                                            youTubeAccount.email.ifBlank { "Signed in • RTMP ready" }
                                        } else {
                                            "Optional — manual RTMP streaming works without sign-in"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (youTubeAccount.signedIn) {
                                    OutlinedButton(
                                        onClick = onDisconnectYouTubeAccount,
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("youtube_disconnect_btn")
                                    ) {
                                        Text("Sign Out", fontSize = 11.sp)
                                    }
                                } else {
                                    Button(
                                        onClick = onConnectYouTubeAccount,
                                        enabled = !youTubeAccount.isLoading,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ZroLiveRed,
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("youtube_connect_btn")
                                    ) {
                                        if (youTubeAccount.isLoading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(13.dp),
                                                strokeWidth = 1.8.dp,
                                                color = Color.White
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }
                                        Text(
                                            if (youTubeAccount.isLoading) "Signing in…" else "Sign in",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            if (!youTubeAccount.statusMessage.isNullOrBlank()) {
                                Text(
                                    text = youTubeAccount.statusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (youTubeAccount.signedIn) ZroEmerald else ZroAmber
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
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
                                    Text("Open YouTube Studio", fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = streamSettings.rtmpUrl,
                        onValueChange = { onUpdateStreamSettings(streamSettings.copy(rtmpUrl = it)) },
                        label = { Text("Stream RTMP URL") },
                        placeholder = { Text("rtmp:// or rtmps:// ingest server URL", fontSize = 12.sp) },
                        supportingText = {
                            val effective = streamSettings.effectivePrimaryServerUrl()
                            Text(
                                text = if (effective.isNotBlank()) "Server: $effective" else "Paste your RTMP / RTMPS server URL (not auto-filled)",
                                fontSize = 10.sp,
                                fontFamily = JetBrainsMonoFontFamily
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_setup_primary_url_input")
                    )

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

            // 3. Video Quality (Resolution / FPS / Bitrate)
            item {
                GlassCard {
                    SectionHeader(
                        title = "Video Encoder",
                        subtitle = "${streamSettings.resolution.shortLabel} • ${streamSettings.fps} FPS • ${"%.1f".format(streamSettings.bitrateKbps / 1000f)} Mbps"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val mainResolutions = listOf(
                        VideoResolution.P480,
                        VideoResolution.P720,
                        VideoResolution.P1080
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        mainResolutions.forEach { res ->
                            FilterChip(
                                selected = streamSettings.resolution == res,
                                onClick = { onUpdateStreamSettings(streamSettings.copy(resolution = res)) },
                                label = { Text(res.shortLabel, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ZroCobalt.copy(alpha = 0.22f),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(30, 60).forEach { fps ->
                            FilterChip(
                                selected = streamSettings.fps == fps,
                                onClick = { onUpdateStreamSettings(streamSettings.copy(fps = fps)) },
                                label = { Text("$fps FPS", style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ZroCobalt.copy(alpha = 0.22f),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Video Bitrate", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${"%.1f".format(streamSettings.bitrateKbps / 1000f)} Mbps",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = Color.White
                        )
                    }
                    Slider(
                        value = streamSettings.bitrateKbps.toFloat(),
                        onValueChange = { kbps ->
                            onUpdateStreamSettings(streamSettings.copy(bitrateKbps = (kbps / 250f).toInt() * 250))
                        },
                        valueRange = 1000f..12000f,
                        colors = SliderDefaults.colors(thumbColor = ZroCobalt, activeTrackColor = ZroCobalt)
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
                    text = if (isLive) "STOP LIVE STREAM" else "START LIVE",
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
