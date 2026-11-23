package com.example.ui.screens

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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.AudioSettings
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamPlatform
import com.example.data.model.VideoResolution
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.StreamSettings
import com.example.data.model.StreamVisibility
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
    onStopLive: () -> Unit
) {
    val isLive = streamTelemetry.state == StreamLifecycleState.LIVE ||
        streamTelemetry.state == StreamLifecycleState.PAUSED ||
        streamTelemetry.state == StreamLifecycleState.CONNECTING ||
        streamTelemetry.state == StreamLifecycleState.RECONNECTING

    var showStreamKey by remember { mutableStateOf(false) }

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
                    brbActive = workspace.brbActive
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
            // 1. Platform & Destination
            item {
                GlassCard {
                    SectionHeader(
                        title = "Broadcast Platform",
                        subtitle = if (streamSettings.streamKey.isNotBlank()) "Stream Key configured" else "Enter RTMP Stream Key below"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StreamPlatform.entries.forEach { platform ->
                            val selected = streamSettings.platform == platform
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val defaultUrl = if (platform == StreamPlatform.YOUTUBE && streamSettings.rtmpUrl.isBlank()) {
                                        "rtmp://a.rtmp.youtube.com/live2"
                                    } else {
                                        streamSettings.rtmpUrl
                                    }
                                    onUpdateStreamSettings(streamSettings.copy(platform = platform, rtmpUrl = defaultUrl))
                                },
                                label = { Text(platform.label, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ZroCobalt.copy(alpha = 0.22f),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = streamSettings.rtmpUrl,
                        onValueChange = { onUpdateStreamSettings(streamSettings.copy(rtmpUrl = it)) },
                        label = { Text("RTMP Server URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = streamSettings.streamKey,
                        onValueChange = { onUpdateStreamSettings(streamSettings.copy(streamKey = it)) },
                        label = { Text("Stream Key") },
                        singleLine = true,
                        visualTransformation = if (showStreamKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showStreamKey = !showStreamKey }) {
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
