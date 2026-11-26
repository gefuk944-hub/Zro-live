package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VoiceOverOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AudioMeterState
import com.example.data.model.AudioSettings
import com.example.data.model.LiveVideoSource
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.YouTubeChatMessage
import com.example.dualphone.DualPhoneProtocol
import com.example.dualphone.DualRemoteCommand
import com.example.dualphone.WifiDirectState
import com.example.streaming.ZroStreamCoordinator
import com.example.studio.CropEdge
import com.example.studio.DualConnectionMethod
import com.example.studio.DualLinkState
import com.example.studio.DualPhoneRole
import com.example.studio.DualPhoneTelemetry
import com.example.studio.RemoteControlTileId
import com.example.studio.ResizeCorner
import com.example.studio.SceneLayer
import com.example.studio.SceneSlot
import com.example.studio.StudioWorkspaceState
import com.example.telemetry.AppConnectivitySnapshot
import com.example.ui.components.AudioLevelMeter
import com.example.ui.components.BrandGradientButton
import com.example.ui.components.ConnectivityStatusBar
import com.example.ui.components.GlassCard
import com.example.ui.components.LiveStatusBadge
import com.example.ui.components.ProSubpageTopBar
import com.example.ui.components.QrPairingDisplayCard
import com.example.ui.components.RenameLayerDialog
import com.example.ui.components.RenameScreenCaptureDialog
import com.example.ui.components.SectionHeader
import com.example.ui.components.StudioCanvasPreview
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald
import com.example.ui.theme.ZroLiveRed

enum class DualStudioSection(val label: String) {
    REMOTE_DECK("Remote Deck"),
    PAIRING("2-Phone Pairing")
}

/**
 * Broadcast Remote Control Deck & Dual-Phone Pairing Studio.
 * - Clean 3-column hardware-switcher style Remote Deck (customizable order + add/remove + reset default)
 * - Stop Live confirmation dialog
 * - One-Touch BRB / Privacy toggle + RESUME GAME SCENE banner
 * - Simplified Audio Mixer: single Master Volume slider + ONE separate TEAM VOICE MUTE button
 * - Phone 2 QR + Manual Address pairing & Phone 1 QR scanner + Manual Address entry
 */
@Composable
fun DualPhoneStudioScreen(
    initialSection: DualStudioSection = DualStudioSection.REMOTE_DECK,
    dualTelemetry: DualPhoneTelemetry,
    pairingUri: String,
    mirroredFrame: Bitmap?,
    workspace: StudioWorkspaceState,
    streamTelemetry: StreamRuntimeTelemetry,
    audioSettings: AudioSettings,
    audioMeters: AudioMeterState,
    screenshotCount: Int,
    reducedMotion: Boolean,
    onBack: (() -> Unit)? = null,
    onSelectRole: (DualPhoneRole) -> Unit,
    onSelectMethod: (DualConnectionMethod) -> Unit,
    onToggleAutoReconnect: (Boolean) -> Unit,
    onStartPhone2Host: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onConnectManual: (String, String, String) -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onDispatchRemote: (DualRemoteCommand) -> Unit,
    onStartLiveFlow: () -> Unit,
    onEndLiveConfirmed: () -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onTriggerBrb: () -> Unit,
    onResumeFromBrb: () -> Unit,
    onToggleTeamVoiceToLive: () -> Unit,
    onUpdateAudioSettings: (AudioSettings) -> Unit,
    onOpenChatPage: () -> Unit,
    onOpenAudioStudio: () -> Unit,
    onOpenCameraSettings: () -> Unit,
    onOpenSceneEditor: () -> Unit,
    onMoveRemoteTile: (RemoteControlTileId, Boolean) -> Unit,
    onAddRemoteTile: (RemoteControlTileId) -> Unit,
    onRemoveRemoteTile: (RemoteControlTileId) -> Unit,
    onResetRemoteDeck: () -> Unit,
    chatMessages: List<YouTubeChatMessage> = emptyList(),
    cameraPermissionGranted: Boolean = false,
    screenCaptureName: String = "Gameplay Capture",
    onSelectLayer: (String?) -> Unit = {},
    onMoveLayerBy: (String, Float, Float) -> Unit = { _, _, _ -> },
    onResizeLayerCorner: (String, ResizeCorner, Float, Float, Boolean) -> Unit = { _, _, _, _, _ -> },
    onCropLayerEdge: (String, CropEdge, Float) -> Unit = { _, _, _ -> },
    onDragFinished: () -> Unit = {},
    onRenameLayer: (String, String) -> Unit = { _, _ -> },
    onRenameScreenCapture: (String) -> Unit = {},
    onToggleLayerLock: (String) -> Unit = {},
    onToggleLayerVisibility: (String) -> Unit = {},
    onSwitchScene: (SceneSlot) -> Unit = {},
    onOpenFullScreenPreview: () -> Unit = {},
    wifiDirectState: WifiDirectState = WifiDirectState(),
    connectivitySnapshot: AppConnectivitySnapshot = AppConnectivitySnapshot(),
    onStartWifiDirectHost: () -> Unit = {},
    onStopWifiDirectHost: () -> Unit = {},
    onProbeStunServer: () -> Unit = {},
    onSharePhone1Screen: () -> Unit = {},
    localCapturedFrame: Bitmap? = null,
    onSetRelayServerUrl: (String) -> Unit = {},
    onStartPhone2RelayHost: (String) -> Unit = {},
    onConnectPhone1ViaRelay: (String, String) -> Unit = { _, _ -> }
) {
    var activeSection by remember(initialSection) { mutableStateOf(initialSection) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var editDeckMode by remember { mutableStateOf(false) }
    var showSetupGuide by remember { mutableStateOf(false) }
    var editLayersInRemote by remember { mutableStateOf(true) }
    var cropModeInRemote by remember { mutableStateOf(false) }
    var renameTargetLayer by remember { mutableStateOf<SceneLayer?>(null) }
    var showRenameCaptureDialog by remember { mutableStateOf(false) }

    var manualIp by remember { mutableStateOf(dualTelemetry.peerIp.ifBlank { "192.168.1." }) }
    var manualPort by remember { mutableStateOf(DualPhoneProtocol.DEFAULT_CONTROL_PORT.toString()) }
    var manualToken by remember { mutableStateOf("") }
    var relayUrlInput by remember(dualTelemetry.relayServerUrl) { mutableStateOf(dualTelemetry.relayServerUrl) }
    var relayTokenInput by remember { mutableStateOf("") }

    val isLive = streamTelemetry.state == StreamLifecycleState.LIVE
    val isPaused = streamTelemetry.state == StreamLifecycleState.PAUSED
    val isActiveStream = isLive || isPaused ||
        streamTelemetry.state == StreamLifecycleState.CONNECTING ||
        streamTelemetry.state == StreamLifecycleState.CONNECTED ||
        streamTelemetry.state == StreamLifecycleState.SENDING_VIDEO ||
        streamTelemetry.state == StreamLifecycleState.RECONNECTING

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Stop Live Stream?", color = Color.White) },
            text = {
                Text(
                    "Your live broadcast will end immediately and all encoder/capture resources will be released cleanly.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirm = false
                        onEndLiveConfirmed()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZroLiveRed, contentColor = Color.White),
                    modifier = Modifier.testTag("confirm_stop_live_btn")
                ) {
                    Text("Stop Live")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    renameTargetLayer?.let { target ->
        RenameLayerDialog(
            initialName = target.name,
            layerTypeLabel = target.type.label,
            onConfirm = { newName ->
                renameTargetLayer = null
                onRenameLayer(target.id, newName)
            },
            onDismiss = { renameTargetLayer = null }
        )
    }

    if (showRenameCaptureDialog) {
        RenameScreenCaptureDialog(
            currentName = screenCaptureName,
            onConfirm = { newName ->
                showRenameCaptureDialog = false
                onRenameScreenCapture(newName)
            },
            onDismiss = { showRenameCaptureDialog = false }
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val isCompactHeight = maxHeight < 460.dp
        val remotePreviewMaxHeight = if (isCompactHeight) 52.dp else (maxHeight * 0.24f).coerceIn(110.dp, 158.dp)
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        if (onBack != null) {
            ProSubpageTopBar(
                title = if (activeSection == DualStudioSection.PAIRING) "2-Phone Pairing" else "Remote Control",
                subtitle = dualTelemetry.role.label,
                onBack = onBack,
                backTestTag = "dual_back_button",
                trailing = {
                    LiveStatusBadge(
                        isLive = isLive,
                        isPaused = isPaused,
                        brbActive = workspace.brbActive,
                        reducedMotion = reducedMotion,
                        lifecycleState = streamTelemetry.state
                    )
                }
            )
        } else {
            // Compact broadcast header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = if (isCompactHeight) 3.dp else 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Remote Control",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    if (!isCompactHeight) {
                        Text(
                            text = when (dualTelemetry.linkState) {
                                DualLinkState.CONNECTED -> "${dualTelemetry.connectedDeviceName} • ${dualTelemetry.latencyMs} ms • ${dualTelemetry.mirroredResolution}"
                                DualLinkState.HOSTING -> "Hosting on ${dualTelemetry.localIp}:${dualTelemetry.hostPort}"
                                else -> dualTelemetry.role.label
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = if (dualTelemetry.linkState == DualLinkState.CONNECTED) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                LiveStatusBadge(
                    isLive = isLive,
                    isPaused = isPaused,
                    brbActive = workspace.brbActive,
                    reducedMotion = reducedMotion,
                    lifecycleState = streamTelemetry.state
                )
            }
        }

        // Top 2-tab switcher (Remote Deck / 2-Phone Pairing)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = if (isCompactHeight) 2.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DualStudioSection.entries.forEach { sec ->
                val selected = activeSection == sec
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) ZroCobalt.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        1.dp,
                        if (selected) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { activeSection = sec }
                        .testTag("dual_tab_${sec.name.lowercase()}")
                ) {
                    Text(
                        text = sec.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = if (isCompactHeight) 3.dp else 5.dp)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("dual_studio_list"),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (activeSection == DualStudioSection.REMOTE_DECK) {
                // One-Touch BRB Active Recovery Banner
                if (workspace.brbActive) {
                    item {
                        GlassCard(accentBorder = ZroAmber) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "BRB / PRIVACY ACTIVE",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = ZroAmber
                                    )
                                    Text(
                                        text = "Gameplay & Facecam are hidden. Saved Gameplay scene is untouched.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = onResumeFromBrb,
                                    colors = ButtonDefaults.buttonColors(containerColor = ZroEmerald, contentColor = Color.Black),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("brb_resume_game_scene_btn")
                                ) {
                                    Text("RESUME GAME", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Full Live Interactive Studio Preview (mirrors Studio canvas & supports Scene 1-4 switching + live layer editing)
                item {
                    val activeScene = workspace.activeScene
                    val selectedLayer = activeScene.layers.firstOrNull { it.id == workspace.selectedLayerId }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            1.dp,
                            if (dualTelemetry.role == DualPhoneRole.PHONE2_STUDIO && dualTelemetry.linkState != DualLinkState.CONNECTED) {
                                ZroAmber.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("remote_live_studio_preview")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            // Compact OBS-style Preview Header with Prominent Layers & 16:9 Preview Controls
                            val usingSecondPhoneSource = streamTelemetry.activeVideoSource == LiveVideoSource.REMOTE_SECOND_PHONE ||
                                (dualTelemetry.linkState == DualLinkState.CONNECTED && dualTelemetry.role == DualPhoneRole.PHONE2_STUDIO)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Live Studio Preview — ${activeScene.name}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (usingSecondPhoneSource) {
                                            "Source: 2nd Phone Screen (Phone 1 capture paused)"
                                        } else {
                                            "Source: This Phone Full Screen"
                                        },
                                        fontSize = 9.sp,
                                        lineHeight = 12.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = if (usingSecondPhoneSource) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("remote_active_source_badge")
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        onClick = onOpenSceneEditor,
                                        shape = RoundedCornerShape(6.dp),
                                        color = ZroCobalt.copy(alpha = 0.24f),
                                        border = BorderStroke(1.dp, ZroCobalt),
                                        modifier = Modifier.testTag("remote_layers_btn")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Layers,
                                                contentDescription = "Open Layers Panel",
                                                tint = Color.White,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                text = "Layers",
                                                fontSize = 10.sp,
                                                lineHeight = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White
                                            )
                                        }
                                    }
                                    Surface(
                                        onClick = onOpenFullScreenPreview,
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
                                        modifier = Modifier.testTag("remote_fullscreen_preview_btn")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Fullscreen,
                                                contentDescription = "16:9 Live Preview",
                                                tint = Color.White,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text("16:9", fontSize = 9.sp, lineHeight = 12.sp, color = Color.White)
                                        }
                                    }
                                    Surface(
                                        onClick = onOpenCameraSettings,
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
                                    ) {
                                        Text(
                                            text = "Cam",
                                            fontSize = 9.sp,
                                            lineHeight = 12.sp,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // 4-Scene Instant Live Switcher (Screen 1 / Screen 2 / Screen 3 / Screen 4)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                SceneSlot.entries.forEach { slot ->
                                    val selected = workspace.activeSceneSlot == slot
                                    val sceneName = workspace.scenes[slot]?.name ?: slot.defaultName
                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        color = if (selected) ZroCobalt.copy(alpha = 0.24f) else MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_SWITCH_SCENE, stringArg = slot.name))
                                            }
                                            .testTag("remote_preview_scene_${slot.name.lowercase()}")
                                    ) {
                                        Text(
                                            text = "${slot.shortLabel} • $sceneName",
                                            fontSize = 9.sp,
                                            lineHeight = 12.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            // Full OBS-style Live Canvas Preview (same live engine as Studio, interactive!)
                            StudioCanvasPreview(
                                workspace = workspace,
                                mirroredFrame = mirroredFrame,
                                localCapturedFrame = localCapturedFrame,
                                audioMeters = audioMeters,
                                chatMessages = chatMessages,
                                reducedMotion = reducedMotion,
                                dualTelemetry = dualTelemetry,
                                cameraPermissionGranted = cameraPermissionGranted,
                                enableLiveCameraPreview = workspace.cameraEnabled && cameraPermissionGranted,
                                interactive = editLayersInRemote,
                                cropModeActive = cropModeInRemote && editLayersInRemote,
                                onSelectLayer = onSelectLayer,
                                onMoveLayerBy = onMoveLayerBy,
                                onResizeLayerCorner = onResizeLayerCorner,
                                onCropLayerEdge = onCropLayerEdge,
                                onDragFinished = onDragFinished,
                                onReconnectPeer = onReconnect,
                                modifier = Modifier.heightIn(max = remotePreviewMaxHeight)
                            )

                            // Live Layer Edit + Crop + Rename Capture Bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .testTag("remote_edit_layers_toggle")
                                        .background(
                                            if (editLayersInRemote) ZroCobalt.copy(alpha = 0.24f) else MaterialTheme.colorScheme.surfaceContainer,
                                            RoundedCornerShape(5.dp)
                                        )
                                        .clickable { editLayersInRemote = !editLayersInRemote }
                                        .padding(horizontal = 7.dp, vertical = 4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(Icons.Rounded.Layers, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                        Text(if (editLayersInRemote) "Edit: ON" else "Edit: OFF", fontSize = 9.sp, lineHeight = 12.sp, color = Color.White)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .testTag("remote_crop_mode_toggle")
                                        .background(
                                            if (cropModeInRemote && editLayersInRemote) ZroAmber.copy(alpha = 0.24f) else MaterialTheme.colorScheme.surfaceContainer,
                                            RoundedCornerShape(5.dp)
                                        )
                                        .clickable { if (editLayersInRemote) cropModeInRemote = !cropModeInRemote }
                                        .padding(horizontal = 7.dp, vertical = 4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(Icons.Rounded.Crop, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                        Text(if (cropModeInRemote) "Crop ON" else "Crop", fontSize = 9.sp, lineHeight = 12.sp, color = Color.White)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .testTag("remote_rename_capture_btn")
                                        .widthIn(max = 126.dp)
                                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(5.dp))
                                        .clickable { showRenameCaptureDialog = true }
                                        .padding(horizontal = 7.dp, vertical = 4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "Rename screen capture", tint = Color.White, modifier = Modifier.size(12.dp))
                                        Text("Capture: $screenCaptureName", fontSize = 9.sp, lineHeight = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }

                            // Selected Layer Quick Actions (Rename / Lock / Hide)
                            if (selectedLayer != null && !isCompactHeight) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(1.dp, ZroCobalt.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("remote_selected_layer_bar")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${selectedLayer.name} • ${selectedLayer.type.label}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp))
                                                    .clickable { renameTargetLayer = selectedLayer }
                                                    .testTag("remote_rename_selected_layer_btn"),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Rounded.Edit, contentDescription = "Rename selected layer", tint = Color.White, modifier = Modifier.size(13.dp))
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp))
                                                    .clickable { onToggleLayerVisibility(selectedLayer.id) }
                                                    .testTag("remote_vis_selected_layer_btn"),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    if (selectedLayer.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                                    contentDescription = "Toggle visibility",
                                                    tint = if (selectedLayer.visible) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp))
                                                    .clickable { onToggleLayerLock(selectedLayer.id) }
                                                    .testTag("remote_lock_selected_layer_btn"),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    if (selectedLayer.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                                    contentDescription = "Toggle lock",
                                                    tint = if (selectedLayer.locked) ZroAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3-Column Broadcast Control Deck
                item {
                    SectionHeader(
                        title = "Broadcast Deck (3-Column)",
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (editDeckMode) {
                                    Box(
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp))
                                            .clickable(onClick = onResetRemoteDeck)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                            .testTag("remote_deck_reset_btn")
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("Default", fontSize = 10.sp, color = Color.White)
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp))
                                        .clickable { editDeckMode = !editDeckMode }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .testTag("remote_deck_customize_btn")
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Tune, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(if (editDeckMode) "Done" else "Customize", fontSize = 10.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(5.dp))

                    val tiles = workspace.remoteTileOrder.ifEmpty { RemoteControlTileId.DEFAULT_ORDER }
                    val rows = tiles.chunked(3)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("remote_deck_grid")
                    ) {
                        rows.forEach { rowTiles ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowTiles.forEach { tileId ->
                                    RemoteDeckButton(
                                        tileId = tileId,
                                        isLive = isLive,
                                        isPaused = isPaused,
                                        isActiveStream = isActiveStream,
                                        liveDurationSec = streamTelemetry.durationSec,
                                        workspace = workspace,
                                        audioSettings = audioSettings,
                                        editMode = editDeckMode,
                                        onClick = {
                                            when (tileId) {
                                                RemoteControlTileId.START_LIVE -> {
                                                    if (!isActiveStream) onStartLiveFlow()
                                                }
                                                RemoteControlTileId.PAUSE_RESUME -> {
                                                    onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_PAUSE_RESUME))
                                                }
                                                RemoteControlTileId.STOP_LIVE -> {
                                                    if (isActiveStream) showStopConfirm = true
                                                    else onEndLiveConfirmed()
                                                }
                                                RemoteControlTileId.MUTE_MIC -> {
                                                    onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_MUTE_MIC))
                                                }
                                                RemoteControlTileId.TEAM_VOICE_LIVE -> {
                                                    onToggleTeamVoiceToLive()
                                                }
                                                RemoteControlTileId.CAM_TOGGLE -> {
                                                    onToggleCamera(!workspace.cameraEnabled)
                                                }
                                                RemoteControlTileId.SCENE_1 -> {
                                                    onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_SWITCH_SCENE, stringArg = SceneSlot.GAMEPLAY_LIVE.name))
                                                }
                                                RemoteControlTileId.SCENE_2 -> {
                                                    onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_SWITCH_SCENE, stringArg = SceneSlot.SHORTS_VERTICAL.name))
                                                }
                                                RemoteControlTileId.SCENE_3 -> {
                                                    onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_SWITCH_SCENE, stringArg = SceneSlot.BRB_BREAK.name))
                                                }
                                                RemoteControlTileId.SCENE_4 -> {
                                                    onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_SWITCH_SCENE, stringArg = SceneSlot.ENDING_THANKS.name))
                                                }
                                                RemoteControlTileId.OPEN_CHAT -> onOpenChatPage()
                                                RemoteControlTileId.BRB_PRIVACY -> {
                                                    if (workspace.brbActive) onResumeFromBrb() else onTriggerBrb()
                                                }
                                                RemoteControlTileId.VOL_DOWN -> {
                                                    val next = (audioSettings.masterVolume - 0.1f).coerceIn(0f, 1.5f)
                                                    onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_SET_MASTER_VOL, floatArg = next))
                                                }
                                                RemoteControlTileId.VOL_UP -> {
                                                    val next = (audioSettings.masterVolume + 0.1f).coerceIn(0f, 1.5f)
                                                    onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_SET_MASTER_VOL, floatArg = next))
                                                }
                                                RemoteControlTileId.SCREENSHOT -> {
                                                    onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_SCREENSHOT))
                                                }
                                                RemoteControlTileId.TOGGLE_OVERLAYS -> {
                                                    onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_TOGGLE_OVERLAYS))
                                                }
                                                RemoteControlTileId.SWITCH_CAMERA -> {
                                                    onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_SWITCH_CAMERA))
                                                }
                                                RemoteControlTileId.OPEN_STUDIO -> onOpenSceneEditor()
                                            }
                                        },
                                        onMoveUp = { onMoveRemoteTile(tileId, true) },
                                        onMoveDown = { onMoveRemoteTile(tileId, false) },
                                        onRemove = { onRemoveRemoteTile(tileId) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(3 - rowTiles.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    // Add extra actions when in Customize mode
                    if (editDeckMode) {
                        val missing = RemoteControlTileId.entries.filter { it !in tiles }
                        if (missing.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            GlassCard {
                                Text("Add Button to Deck", style = MaterialTheme.typography.labelMedium, color = Color.White)
                                Spacer(modifier = Modifier.height(6.dp))
                                missing.chunked(2).forEach { pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        pair.forEach { avail ->
                                            OutlinedButton(
                                                onClick = { onAddRemoteTile(avail) },
                                                shape = RoundedCornerShape(6.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("add_remote_tile_${avail.name.lowercase()}")
                                            ) {
                                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(avail.shortLabel, fontSize = 11.sp, maxLines = 1)
                                            }
                                        }
                                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // Simplified Broadcast Audio Mixer (Single Combined Master Volume + ONE Team Voice Mute Button)
                item {
                    GlassCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionHeader(
                                title = "Quick Audio Mixer",
                                subtitle = "Combined stream volume + team voice privacy mute",
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = onOpenAudioStudio,
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Audio Studio", fontSize = 10.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        AudioLevelMeter(
                            level = audioMeters.micLevel,
                            peak = audioMeters.micPeak,
                            label = "Master Output Meter",
                            dbText = "${"%.0f".format(audioMeters.micDb)} dB",
                            muted = !audioSettings.micEnabled
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Master Volume",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                            Text(
                                text = "${(audioSettings.masterVolume * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = ZroCobalt
                            )
                        }
                        Slider(
                            value = audioSettings.masterVolume.coerceIn(0f, 1.5f),
                            onValueChange = { vol ->
                                onUpdateAudioSettings(audioSettings.copy(masterVolume = vol))
                                onDispatchRemote(DualRemoteCommand(DualPhoneProtocol.CMD_SET_MASTER_VOL, floatArg = vol))
                            },
                            valueRange = 0f..1.5f,
                            colors = SliderDefaults.colors(thumbColor = ZroCobalt, activeTrackColor = ZroCobalt),
                            modifier = Modifier.testTag("remote_master_volume_slider")
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        val teamLive = audioSettings.teamVoiceToLive
                        Surface(
                            onClick = onToggleTeamVoiceToLive,
                            shape = RoundedCornerShape(6.dp),
                            color = if (teamLive) ZroEmerald.copy(alpha = 0.16f) else ZroAmber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, if (teamLive) ZroEmerald else ZroAmber),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("remote_team_voice_mute_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (teamLive) Icons.Rounded.RecordVoiceOver else Icons.Rounded.VoiceOverOff,
                                        contentDescription = "Team Voice Mute",
                                        tint = if (teamLive) ZroEmerald else ZroAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column {
                                        Text(
                                            text = if (teamLive) "TEAM VOICE: LIVE" else "TEAM VOICE: MUTED",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = if (teamLive) {
                                                "Consented squad audio goes to live stream"
                                            } else {
                                                "Squad voice muted from stream • Your mic stays live"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = if (teamLive) "MUTE" else "UNMUTE",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (teamLive) ZroEmerald else ZroAmber
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Note: Zro Voice Room team audio is separated cleanly. Third-party in-game VoIP apps may mix into game audio on Android where OS audio isolation is restricted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // 2-PHONE PAIRING SECTION
                item {
                    GlassCard {
                        SectionHeader(
                            title = "Device Role",
                            subtitle = "Phone 1 streams authorized gameplay to Phone 2 Studio"
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DualPhoneRole.entries.forEach { role ->
                                val selected = dualTelemetry.role == role
                                Surface(
                                    onClick = { onSelectRole(role) },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (selected) ZroCobalt.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("dual_role_${role.name.lowercase()}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Rounded.PhoneAndroid,
                                            contentDescription = null,
                                            tint = if (selected) ZroCobalt else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(role.label, style = MaterialTheme.typography.labelLarge, color = Color.White)
                                            Text(role.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Connection Status & Controls
                item {
                    val connected = dualTelemetry.linkState == DualLinkState.CONNECTED
                    GlassCard(
                        accentBorder = when (dualTelemetry.linkState) {
                            DualLinkState.CONNECTED -> ZroEmerald
                            DualLinkState.HOSTING, DualLinkState.RECONNECTING -> ZroAmber
                            DualLinkState.ERROR -> ZroLiveRed
                            else -> null
                        }
                    ) {
                        SectionHeader(
                            title = "Connection Telemetry",
                            subtitle = dualTelemetry.linkState.label
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Device: ${dualTelemetry.connectedDeviceName.ifBlank { "None" }}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                            Text(
                                text = "${dualTelemetry.signalQualityLabel} • ${dualTelemetry.latencyMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = if (connected) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Transport: ${dualTelemetry.transportModeLabel} • Feed: ${dualTelemetry.mirroredResolution} @ ${dualTelemetry.mirroredFps} FPS • IP: ${dualTelemetry.localIp.ifBlank { "Mobile Data" }}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = ZroCobalt.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, ZroCobalt.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auto_solo_mode_info_card")
                        ) {
                            Text(
                                text = "Auto Solo Mode ON: If the gameplay phone disconnects or goes out of range, the live stream automatically switches to this phone's screen and restores the gameplay phone when it reconnects.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                            )
                        }
                        if (!dualTelemetry.lastError.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = dualTelemetry.lastError,
                                style = MaterialTheme.typography.bodySmall,
                                color = ZroAmber
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Auto-Reconnect on Drop", style = MaterialTheme.typography.labelLarge, color = Color.White)
                            Switch(
                                checked = dualTelemetry.autoReconnect,
                                onCheckedChange = onToggleAutoReconnect
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onReconnect,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reconnect")
                            }
                            OutlinedButton(
                                onClick = onDisconnect,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Disconnect")
                            }
                        }
                        if (connected && dualTelemetry.role == DualPhoneRole.PHONE1_GAMEPLAY) {
                            Spacer(modifier = Modifier.height(8.dp))
                            if (streamTelemetry.captureActive) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = ZroEmerald.copy(alpha = 0.16f),
                                    border = BorderStroke(1.dp, ZroEmerald.copy(alpha = 0.7f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "SCREEN SHARING ACTIVE — Streaming this phone's display to Studio Phone 2",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = ZroEmerald,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                    )
                                }
                            } else {
                                BrandGradientButton(
                                    text = "SHARE THIS PHONE'S SCREEN TO STUDIO",
                                    onClick = onSharePhone1Screen,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("phone1_share_screen_btn")
                                )
                            }
                        }
                    }
                }

                // Separate Network, Server & Streaming Connectivity Status + ICE/STUN Check
                item {
                    ConnectivityStatusBar(
                        snapshot = connectivitySnapshot,
                        onCheckConnectivity = onProbeStunServer,
                        showStunDetail = true
                    )
                }

                // Role-specific QR / Manual Address Pairing (ONLY QR Code & Manual Address)
                if (dualTelemetry.role == DualPhoneRole.PHONE2_STUDIO) {
                    item {
                        GlassCard {
                            SectionHeader(
                                title = "Phone 2 Studio Host",
                                subtitle = "Pair via QR Code or Manual Address. Phone 2 NEVER captures its own screen."
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                DualConnectionMethod.VISIBLE_OPTIONS.forEach { method ->
                                    val selected = dualTelemetry.connectionMethod == method ||
                                        (dualTelemetry.connectionMethod !in DualConnectionMethod.VISIBLE_OPTIONS &&
                                            method == DualConnectionMethod.QR_PAIRING)
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onSelectMethod(method) },
                                        label = { Text(method.label, fontSize = 11.sp, maxLines = 1) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = ZroCobalt.copy(alpha = 0.22f),
                                            selectedLabelColor = Color.White
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("pairing_method_${method.name.lowercase()}")
                                    )
                                }
                            }
                            if (dualTelemetry.connectionMethod == DualConnectionMethod.INTERNET_RELAY) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = relayUrlInput,
                                    onValueChange = {
                                        relayUrlInput = it
                                        onSetRelayServerUrl(it)
                                    },
                                    label = { Text("Secure Relay Server URL (wss://)") },
                                    placeholder = { Text("wss://relay.yourdomain.com/ws", fontSize = 11.sp) },
                                    supportingText = {
                                        Text(
                                            text = "Works across mobile data & different Wi-Fi networks via your reachable WebSocket relay.",
                                            fontSize = 10.sp
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("phone2_relay_url_input")
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                BrandGradientButton(
                                    text = if (dualTelemetry.linkState == DualLinkState.HOSTING) "REFRESH MOBILE DATA RELAY HOST" else "START MOBILE DATA RELAY HOST",
                                    onClick = { onStartPhone2RelayHost(relayUrlInput) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("start_phone2_relay_host_btn")
                                )
                            } else {
                                Spacer(modifier = Modifier.height(8.dp))
                                BrandGradientButton(
                                    text = if (dualTelemetry.linkState == DualLinkState.HOSTING) "REFRESH HOST QR" else "START PHONE 2 HOST",
                                    onClick = onStartPhone2Host,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("start_phone2_host_button")
                                )
                            }
                            if (wifiDirectState.supported) {
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = if (wifiDirectState.groupActive) onStopWifiDirectHost else onStartWifiDirectHost,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("wifi_direct_toggle_btn")
                                ) {
                                    Text(
                                        text = if (wifiDirectState.groupActive) {
                                            "Stop Wi-Fi Direct (${wifiDirectState.groupOwnerAddress})"
                                        } else {
                                            "Enable Wi-Fi Direct P2P (No Router Needed)"
                                        },
                                        fontSize = 11.sp
                                    )
                                }
                                Text(
                                    text = wifiDirectState.statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = if (wifiDirectState.groupActive) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                            if (pairingUri.isNotBlank() && dualTelemetry.pairingToken.isNotBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                QrPairingDisplayCard(
                                    pairingUri = pairingUri,
                                    localIp = dualTelemetry.localIp.ifBlank { "127.0.0.1" },
                                    port = dualTelemetry.hostPort,
                                    token = dualTelemetry.pairingToken
                                )
                            }
                        }
                    }
                } else {
                    item {
                        GlassCard {
                            SectionHeader(
                                title = "Connect Phone 1 to Phone 2 Studio",
                                subtitle = "Scan Phone 2's QR code or enter its manual IP address & token"
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            BrandGradientButton(
                                text = "SCAN PHONE 2 QR CODE",
                                icon = Icons.Rounded.QrCodeScanner,
                                onClick = onOpenQrScanner,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("scan_qr_button")
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = manualIp,
                                    onValueChange = { manualIp = it },
                                    label = { Text("Phone 2 IP") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1.4f)
                                        .testTag("manual_ip_input")
                                )
                                OutlinedTextField(
                                    value = manualPort,
                                    onValueChange = { manualPort = it },
                                    label = { Text("Port") },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.8f)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = manualToken,
                                onValueChange = { manualToken = it.uppercase() },
                                label = { Text("Secure Pairing Token") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manual_token_input")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { onConnectManual(manualIp, manualPort, manualToken) },
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("connect_manual_ip_button")
                            ) {
                                Text("Connect via Manual Address (Same Wi-Fi)")
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader(
                                title = "Mobile Data / Different Wi-Fi (Relay)",
                                subtitle = "Enter the same wss:// relay URL & Phone 2's Room Token"
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = relayUrlInput,
                                onValueChange = {
                                    relayUrlInput = it
                                    onSetRelayServerUrl(it)
                                },
                                label = { Text("Secure Relay Server URL (wss://)") },
                                placeholder = { Text("wss://relay.yourdomain.com/ws", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("phone1_relay_url_input")
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = relayTokenInput,
                                onValueChange = { relayTokenInput = it.uppercase() },
                                label = { Text("Room Token (from Phone 2)") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("phone1_relay_token_input")
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = { onConnectPhone1ViaRelay(relayUrlInput, relayTokenInput) },
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("connect_phone1_relay_btn")
                            ) {
                                Text("Connect via Mobile Data Relay")
                            }
                        }
                    }
                }

                // Concise Dual-Phone Setup Guide
                item {
                    GlassCard(onClick = { showSetupGuide = !showSetupGuide }) {
                        SectionHeader(
                            title = "Dual-Phone Setup Guide",
                            subtitle = if (showSetupGuide) "Tap to collapse" else "Tap for 4-step connection guide"
                        )
                        if (showSetupGuide) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "1. Put both phones on the same Wi-Fi network, or enable Wi-Fi Direct P2P on Phone 2.\n" +
                                    "2. On Phone 2 (Studio / Remote Control), select 'Phone 2 — Studio Control' and tap 'Start Phone 2 Host'.\n" +
                                    "3. On Phone 1 (Gameplay), scan Phone 2's QR Code or enter its Manual Address + Secure Token.\n" +
                                    "4. Tap 'Share This Phone's Screen to Studio' on Phone 1 — Phone 2 automatically switches the live feed to Phone 1 and shows only the Remote Control + compact preview.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun RemoteDeckButton(
    tileId: RemoteControlTileId,
    isLive: Boolean,
    isPaused: Boolean,
    isActiveStream: Boolean,
    liveDurationSec: Long = 0L,
    workspace: StudioWorkspaceState,
    audioSettings: AudioSettings,
    editMode: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActiveState = when (tileId) {
        RemoteControlTileId.START_LIVE -> isLive
        RemoteControlTileId.PAUSE_RESUME -> isPaused
        RemoteControlTileId.STOP_LIVE -> isActiveStream
        RemoteControlTileId.MUTE_MIC -> !audioSettings.micEnabled
        RemoteControlTileId.TEAM_VOICE_LIVE -> audioSettings.teamVoiceToLive
        RemoteControlTileId.CAM_TOGGLE -> workspace.cameraEnabled
        RemoteControlTileId.SCENE_1 -> !workspace.brbActive && workspace.activeSceneSlot == SceneSlot.GAMEPLAY_LIVE
        RemoteControlTileId.SCENE_2 -> !workspace.brbActive && workspace.activeSceneSlot == SceneSlot.SHORTS_VERTICAL
        RemoteControlTileId.SCENE_3 -> workspace.activeSceneSlot == SceneSlot.BRB_BREAK
        RemoteControlTileId.SCENE_4 -> workspace.activeSceneSlot == SceneSlot.ENDING_THANKS
        RemoteControlTileId.BRB_PRIVACY -> workspace.brbActive
        RemoteControlTileId.TOGGLE_OVERLAYS -> workspace.overlaysGlobalVisible
        else -> false
    }

    val semanticColor = when (tileId) {
        RemoteControlTileId.START_LIVE -> if (isLive) ZroLiveRed else ZroEmerald
        RemoteControlTileId.STOP_LIVE -> if (isActiveStream) ZroLiveRed else MaterialTheme.colorScheme.onSurfaceVariant
        RemoteControlTileId.PAUSE_RESUME -> if (isPaused) ZroAmber else MaterialTheme.colorScheme.onSurface
        RemoteControlTileId.MUTE_MIC -> if (!audioSettings.micEnabled) ZroLiveRed else ZroEmerald
        RemoteControlTileId.TEAM_VOICE_LIVE -> if (audioSettings.teamVoiceToLive) ZroEmerald else ZroAmber
        RemoteControlTileId.CAM_TOGGLE -> if (workspace.cameraEnabled) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant
        RemoteControlTileId.BRB_PRIVACY -> if (workspace.brbActive) ZroAmber else MaterialTheme.colorScheme.onSurface
        RemoteControlTileId.SCENE_1, RemoteControlTileId.SCENE_2, RemoteControlTileId.SCENE_3, RemoteControlTileId.SCENE_4 ->
            if (isActiveState) ZroCobalt else MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface
    }

    val bgColor = when {
        tileId == RemoteControlTileId.START_LIVE && isLive -> ZroLiveRed.copy(alpha = 0.22f)
        tileId == RemoteControlTileId.STOP_LIVE && isActiveStream -> ZroLiveRed.copy(alpha = 0.16f)
        tileId == RemoteControlTileId.BRB_PRIVACY && workspace.brbActive -> ZroAmber.copy(alpha = 0.2f)
        tileId == RemoteControlTileId.MUTE_MIC && !audioSettings.micEnabled -> ZroLiveRed.copy(alpha = 0.18f)
        isActiveState -> semanticColor.copy(alpha = 0.16f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }

    val borderColor = if (isActiveState) {
        semanticColor.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    }

    val icon: ImageVector = when (tileId) {
        RemoteControlTileId.START_LIVE -> Icons.Rounded.PlayArrow
        RemoteControlTileId.PAUSE_RESUME -> Icons.Rounded.Pause
        RemoteControlTileId.STOP_LIVE -> Icons.Rounded.Stop
        RemoteControlTileId.MUTE_MIC -> if (audioSettings.micEnabled) Icons.Rounded.Mic else Icons.Rounded.MicOff
        RemoteControlTileId.TEAM_VOICE_LIVE -> if (audioSettings.teamVoiceToLive) Icons.Rounded.RecordVoiceOver else Icons.Rounded.VoiceOverOff
        RemoteControlTileId.CAM_TOGGLE -> if (workspace.cameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff
        RemoteControlTileId.SCENE_1, RemoteControlTileId.SCENE_2, RemoteControlTileId.SCENE_3, RemoteControlTileId.SCENE_4 -> Icons.Rounded.Layers
        RemoteControlTileId.OPEN_CHAT -> Icons.AutoMirrored.Rounded.Chat
        RemoteControlTileId.BRB_PRIVACY -> Icons.Rounded.PrivacyTip
        RemoteControlTileId.VOL_DOWN -> Icons.AutoMirrored.Rounded.VolumeDown
        RemoteControlTileId.VOL_UP -> Icons.AutoMirrored.Rounded.VolumeUp
        RemoteControlTileId.SCREENSHOT -> Icons.Rounded.CameraAlt
        RemoteControlTileId.TOGGLE_OVERLAYS -> Icons.Rounded.Visibility
        RemoteControlTileId.SWITCH_CAMERA -> Icons.Rounded.Cameraswitch
        RemoteControlTileId.OPEN_STUDIO -> Icons.Rounded.Tune
    }

    val timerText = ZroStreamCoordinator.formatDuration(liveDurationSec)
    val displayLabel = when (tileId) {
        RemoteControlTileId.START_LIVE -> if (isLive) "LIVE $timerText" else "START LIVE"
        RemoteControlTileId.STOP_LIVE -> if (isActiveStream) "STOP $timerText" else "STOP LIVE"
        RemoteControlTileId.PAUSE_RESUME -> if (isPaused) "RESUME" else "PAUSE"
        RemoteControlTileId.MUTE_MIC -> if (audioSettings.micEnabled) "MIC ON" else "MIC MUTED"
        RemoteControlTileId.TEAM_VOICE_LIVE -> if (audioSettings.teamVoiceToLive) "TEAM LIVE" else "TEAM MUTE"
        RemoteControlTileId.CAM_TOGGLE -> if (workspace.cameraEnabled) "CAM ON" else "CAM OFF"
        RemoteControlTileId.BRB_PRIVACY -> if (workspace.brbActive) "BRB ON" else "BRB"
        else -> tileId.shortLabel
    }

    Surface(
        onClick = { if (!editMode) onClick() },
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .height(if (editMode) 82.dp else 64.dp)
            .testTag("remote_tile_${tileId.name.lowercase()}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = tileId.label,
                tint = semanticColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = displayLabel,
                fontSize = 10.sp,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (editMode) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move earlier", tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move later", tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove button", tint = ZroLiveRed, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
    }
}
