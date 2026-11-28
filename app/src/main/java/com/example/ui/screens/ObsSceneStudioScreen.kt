package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AudioMeterState
import com.example.data.model.LiveBroadcastMode
import com.example.data.model.YouTubeChatMessage
import com.example.studio.CanvasGridMode
import com.example.studio.CropAspectPreset
import com.example.studio.CropEdge
import com.example.studio.CustomGraphicPreset
import com.example.studio.DualPhoneTelemetry
import com.example.studio.GraphicPresetId
import com.example.studio.LayerAnimationType
import com.example.studio.LayerGraphicAdjustment
import com.example.studio.LayerScaleMode
import com.example.studio.LayerSourceType
import com.example.studio.ResizeCorner
import com.example.studio.SceneLayer
import com.example.studio.SceneSlot
import com.example.studio.SceneTransitionType
import com.example.studio.StudioWorkspaceState
import com.example.studio.WebViewportPreset
import androidx.compose.material.icons.rounded.Refresh
import com.example.ui.components.BrowserUrlValidator
import com.example.ui.components.GlassCard
import com.example.ui.components.GraphicCategoryTab
import com.example.ui.components.MergeLayersDialog
import com.example.ui.components.ProSubpageTopBar
import com.example.ui.components.RenameLayerDialog
import com.example.ui.components.RenameScreenCaptureDialog
import com.example.ui.components.SectionHeader
import com.example.ui.components.StudioCanvasPreview
import com.example.ui.components.graphicAdjustmentDockItems
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald
import com.example.ui.theme.ZroLiveRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class StudioDockTab(val label: String) {
    SOURCES("Sources"),
    TRANSFORM("Transform"),
    GRAPHICS("Graphics"),
    CUSTOMIZE("Scene & Media")
}

/**
 * Real OBS-Style Visual Scene & Canvas Studio:
 * - 4 independent saved scenes (Gameplay, Vertical/Shorts, BRB/Privacy, Ending/Thanks)
 * - 4 working draggable corner resize handles + drag to position
 * - Selectable 2x6 (default), 4x4, or Grid Off + Snap + Safe Area + Zoom + Undo/Redo
 * - Save Screen Layout + Auto-Restore Last Layout + Reset Layout (with confirmation)
 * - Sources & Layers dock (visible/hidden, lock/unlock, rename, Z-order, duplicate, delete)
 * - Fullscreen BRB & Ending customization (Gallery background, title, subtitle, font, speed)
 */
@Composable
fun ObsSceneStudioScreen(
    workspace: StudioWorkspaceState,
    mirroredFrame: Bitmap?,
    audioMeters: AudioMeterState,
    chatMessages: List<YouTubeChatMessage>,
    dualTelemetry: DualPhoneTelemetry,
    cameraPermissionGranted: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    reducedMotion: Boolean,
    onBack: (() -> Unit)? = null,
    onSwitchScene: (SceneSlot) -> Unit,
    onRenameActiveScene: (String) -> Unit,
    onToggleSceneLock: () -> Unit,
    onToggleSceneVertical: () -> Unit,
    onUpdateTransition: (SceneTransitionType, Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSetGridMode: (CanvasGridMode) -> Unit,
    onSaveLayoutExplicit: () -> Unit,
    onSetAutoRestoreLastLayout: (Boolean) -> Unit,
    onResetLayoutConfirmed: () -> Unit,
    onResetActiveScene: () -> Unit,
    onSelectLayer: (String?) -> Unit,
    onToggleLayerLock: (String) -> Unit,
    onToggleLayerVisibility: (String) -> Unit,
    onMoveLayerBy: (String, Float, Float) -> Unit,
    onResizeLayerCorner: (String, ResizeCorner, Float, Float, Boolean) -> Unit,
    onDragFinished: () -> Unit,
    onUpdateLayerProps: (String, (SceneLayer) -> SceneLayer) -> Unit,
    onCenterH: (String) -> Unit,
    onCenterV: (String) -> Unit,
    onFitCanvas: (String) -> Unit,
    onFillCanvas: (String) -> Unit,
    onResetTransform: (String) -> Unit,
    onMoveLayerZOrder: (String, Boolean) -> Unit,
    onDuplicateLayer: (String) -> Unit,
    onDeleteLayer: (String) -> Unit,
    onAddLayerSource: (SceneLayer) -> Unit,
    onPickImageOverlay: () -> Unit,
    onPickVideoOverlay: () -> Unit,
    onPickBackgroundMediaForLayer: (String) -> Unit,
    onUpdateCanvasOptions: (Boolean, Boolean, Boolean, Float) -> Unit,
    onOpenCameraSettings: () -> Unit,
    onReconnectPeer: () -> Unit,
    onUpdateLayerGraphicAdjustmentLive: (String, (LayerGraphicAdjustment) -> LayerGraphicAdjustment) -> Unit = { _, _ -> },
    onApplyGraphicPreset: (String, GraphicPresetId) -> Unit = { _, _ -> },
    onApplyCustomGraphicPreset: (String, CustomGraphicPreset) -> Unit = { _, _ -> },
    onSaveCustomGraphicPreset: (String, LayerGraphicAdjustment) -> Unit = { _, _ -> },
    onDeleteCustomGraphicPreset: (String) -> Unit = {},
    onResetLayerGraphicAdjustment: (String) -> Unit = {},
    onCopyGraphicAdjustmentToLayer: (String, String) -> Unit = { _, _ -> },
    screenCaptureName: String = "Gameplay Capture",
    onCropLayerEdge: (String, CropEdge, Float) -> Unit = { _, _, _ -> },
    onSetLayerCropLive: (String, Float?, Float?, Float?, Float?) -> Unit = { _, _, _, _, _ -> },
    onApplyCropAspectPreset: (String, CropAspectPreset) -> Unit = { _, _ -> },
    onResetLayerCrop: (String) -> Unit = {},
    onRestoreLayerOriginalSize: (String) -> Unit = {},
    onRenameLayer: (String, String) -> Unit = { _, _ -> },
    onRenameScreenCapture: (String) -> Unit = {},
    onMergeLayers: (List<String>, String) -> Unit = { _, _ -> },
    onUnmergeLayer: (String) -> Unit = {},
    onMoveLayerTo: ((String, Float, Float) -> Unit)? = null,
    onReloadBrowserLayer: (String) -> Unit = {},
    onOpenFullScreenPreview: (() -> Unit)? = null,
    onAlignCenter: (String) -> Unit = { id -> onCenterH(id); onCenterV(id) },
    onResizeLayerExact: (String, Float, Float) -> Unit = { id, w, h -> onUpdateLayerProps(id) { it.copy(width = w, height = h) } },
    onApplyCustomSizeUnits: (String, Float, Float) -> Unit = { _, _, _ -> },
    broadcastMode: LiveBroadcastMode = LiveBroadcastMode.LANDSCAPE_16_9,
    onSwitchBroadcastMode: (LiveBroadcastMode) -> Unit = {},
    onReorderLayerTopToBottom: (Int, Int) -> Unit = { _, _ -> },
    onReplaceLayerSource: (SceneLayer) -> Unit = {},
    localCapturedFrame: Bitmap? = null
) {
    val activeScene = workspace.activeScene
    val selectedLayer = activeScene.layers.firstOrNull { it.id == workspace.selectedLayerId }
    var dockTab by remember { mutableStateOf(StudioDockTab.SOURCES) }
    var beforePreviewActive by remember { mutableStateOf(false) }
    var graphicCategory by remember { mutableStateOf(GraphicCategoryTab.TONE) }
    var cropModeActive by remember { mutableStateOf(false) }
    var webInteractLayerId by remember { mutableStateOf<String?>(null) }
    var renameTargetLayer by remember { mutableStateOf<SceneLayer?>(null) }
    var showRenameCaptureDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showResetLayoutConfirm by remember { mutableStateOf(false) }
    var showAddSourceDialog by remember { mutableStateOf(false) }
    val layerRowHeightPx = with(LocalDensity.current) { 78.dp.toPx() }

    // Automatically hide selection border & blue handles when leaving Studio editor
    DisposableEffect(Unit) {
        onDispose {
            onSelectLayer(null)
        }
    }

    LaunchedEffect(workspace.selectedLayerId) {
        if (workspace.selectedLayerId != webInteractLayerId) {
            webInteractLayerId = null
        }
    }

    if (showResetLayoutConfirm) {
        AlertDialog(
            onDismissRequest = { showResetLayoutConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Reset Studio Layout?", color = Color.White) },
            text = {
                Text(
                    "This restores all 4 scenes and source positions to default broadcast layouts. Your previous layout snapshot will be backed up first.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetLayoutConfirm = false
                        onResetLayoutConfirmed()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZroLiveRed, contentColor = Color.White),
                    modifier = Modifier.testTag("confirm_reset_layout_btn")
                ) {
                    Text("Reset Layout")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetLayoutConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddSourceDialog) {
        AlertDialog(
            onDismissRequest = { showAddSourceDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Add Source to ${activeScene.name}", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val quickSources = listOf(
                        "Text Label" to {
                            onAddLayerSource(
                                SceneLayer(
                                    name = "Text Label",
                                    type = LayerSourceType.TEXT,
                                    x = 0.2f,
                                    y = 0.78f,
                                    width = 0.6f,
                                    height = 0.14f,
                                    textContent = "LIVE STREAM",
                                    fontSizeSp = 15f
                                )
                            )
                        },
                        "Facecam Overlay" to {
                            onAddLayerSource(
                                SceneLayer(
                                    name = "Facecam",
                                    type = LayerSourceType.WEBCAM,
                                    x = 0.72f,
                                    y = 0.62f,
                                    width = 0.24f,
                                    height = 0.32f,
                                    borderWidthDp = 1.5f,
                                    borderColorHex = 0xFF3B82F6L
                                )
                            )
                        },
                        "Image / Logo (Gallery)" to { onPickImageOverlay() },
                        "Video Overlay (Gallery)" to { onPickVideoOverlay() },
                        "Browser Web Source" to {
                            onAddLayerSource(
                                SceneLayer(
                                    name = "Browser Source",
                                    type = LayerSourceType.BROWSER_WEB,
                                    x = 0.14f,
                                    y = 0.14f,
                                    width = 0.56f,
                                    height = 0.36f,
                                    webUrl = ""
                                )
                            )
                            dockTab = StudioDockTab.TRANSFORM
                        },
                        "Subscriber Goal Widget" to {
                            onAddLayerSource(
                                SceneLayer(
                                    name = "Subscriber Counter",
                                    type = LayerSourceType.SUBSCRIBER_COUNTER,
                                    x = 0.68f,
                                    y = 0.05f,
                                    width = 0.28f,
                                    height = 0.15f,
                                    subTitle = "SUBSCRIBERS",
                                    subCount = 0,
                                    subGoal = 1000
                                )
                            )
                        },
                        "Audio Level Meter" to {
                            onAddLayerSource(
                                SceneLayer(
                                    name = "Audio Meter",
                                    type = LayerSourceType.AUDIO_VISUALIZER,
                                    x = 0.04f,
                                    y = 0.86f,
                                    width = 0.26f,
                                    height = 0.10f
                                )
                            )
                        }
                    )
                    quickSources.forEach { (title, action) ->
                        Surface(
                            onClick = {
                                showAddSourceDialog = false
                                action()
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddSourceDialog = false }) { Text("Close") }
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

    if (showMergeDialog) {
        MergeLayersDialog(
            layers = activeScene.layers,
            preselectedLayerId = workspace.selectedLayerId,
            onConfirmMerge = { ids, name ->
                showMergeDialog = false
                onMergeLayers(ids, name)
            },
            onDismiss = { showMergeDialog = false }
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val maxCanvasHeight = if (maxHeight < 600.dp) {
            (maxHeight * 0.28f).coerceIn(104.dp, 180.dp)
        } else {
            (maxHeight * 0.34f).coerceIn(180.dp, 260.dp)
        }
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        if (onBack != null) {
            ProSubpageTopBar(
                title = "Layers & Studio",
                subtitle = activeScene.name,
                onBack = onBack,
                backTestTag = "studio_back_button",
                trailing = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onOpenFullScreenPreview != null) {
                            IconButton(
                                onClick = onOpenFullScreenPreview,
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("studio_fullscreen_preview_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Fullscreen,
                                    contentDescription = "16:9 Live Output Preview",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onSaveLayoutExplicit,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("studio_save_layout_btn")
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save", fontSize = 11.sp)
                        }
                    }
                }
            )
        } else {
            // Compact Studio top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "OBS Scene Studio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (workspace.savedLayoutTimestampMs > 0L) {
                            "Saved ${formatSavedTime(workspace.savedLayoutTimestampMs)} • Auto-Restore ${if (workspace.autoRestoreLastLayout) "ON" else "OFF"}"
                        } else {
                            "Drag source or 8 transform handles to frame"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(34.dp).testTag("studio_undo_btn")) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Undo,
                            contentDescription = "Undo",
                            tint = if (canUndo) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(34.dp).testTag("studio_redo_btn")) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Redo,
                            contentDescription = "Redo",
                            tint = if (canRedo) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Surface(
                        onClick = onSaveLayoutExplicit,
                        shape = RoundedCornerShape(6.dp),
                        color = ZroCobalt,
                        modifier = Modifier.testTag("studio_save_layout_btn")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = "Save Layout", tint = Color.White, modifier = Modifier.size(14.dp))
                            Text("SAVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        // 1. Pinned 4 Independent Scene Tabs + Live OBS Visual Canvas (always visible while editing dock below)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SceneSlot.entries.forEach { slot ->
                    val selected = workspace.activeSceneSlot == slot
                    val sceneName = workspace.scenes[slot]?.name ?: slot.defaultName
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selected) ZroCobalt.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            1.dp,
                            if (selected) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSwitchScene(slot) }
                            .testTag("scene_tab_${slot.name.lowercase()}")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = slot.shortLabel,
                                fontSize = 9.sp,
                                lineHeight = 11.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = if (selected) ZroCobalt else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = sceneName,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

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
                interactive = true,
                cropModeActive = cropModeActive,
                webInteractLayerId = webInteractLayerId,
                onSelectLayer = { id ->
                    if (id == null) {
                        webInteractLayerId = null
                        cropModeActive = false
                        onDragFinished()
                    } else if (dockTab == StudioDockTab.SOURCES || dockTab == StudioDockTab.CUSTOMIZE) {
                        dockTab = StudioDockTab.TRANSFORM
                    }
                    onSelectLayer(id)
                },
                onMoveLayerBy = onMoveLayerBy,
                onMoveLayerTo = onMoveLayerTo,
                onResizeLayerCorner = onResizeLayerCorner,
                onCropLayerEdge = onCropLayerEdge,
                onDragFinished = onDragFinished,
                onReconnectPeer = onReconnectPeer,
                beforePreviewLayerId = if (beforePreviewActive && dockTab == StudioDockTab.GRAPHICS) selectedLayer?.id else null,
                modifier = Modifier.heightIn(max = maxCanvasHeight)
            )

            // Quick Selected Layer Bar (shows exact 1920x1080 px, Browser Interact toggle, and instant Deselect)
            if (selectedLayer != null) {
                val canvasBaseW = if (activeScene.isVertical) 1080 else 1920
                val canvasBaseH = if (activeScene.isVertical) 1920 else 1080
                val pxW = (selectedLayer.width * canvasBaseW).toInt()
                val pxH = (selectedLayer.height * canvasBaseH).toInt()
                val isWebInteracting = webInteractLayerId == selectedLayer.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${selectedLayer.name} • ${pxW}×${pxH}px",
                        fontSize = 10.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Quick Resize | Crop segmented toggle pill
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (!cropModeActive) ZroCobalt.copy(alpha = 0.34f) else Color.Transparent,
                                        RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
                                    )
                                    .clickable {
                                        if (cropModeActive) onDragFinished()
                                        cropModeActive = false
                                        webInteractLayerId = null
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                    .testTag("studio_mode_pill_resize")
                            ) {
                                Text(
                                    text = "Resize",
                                    fontSize = 9.sp,
                                    fontWeight = if (!cropModeActive) FontWeight.Bold else FontWeight.Medium,
                                    color = if (!cropModeActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (cropModeActive) ZroAmber.copy(alpha = 0.34f) else Color.Transparent,
                                        RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                                    )
                                    .clickable {
                                        cropModeActive = true
                                        webInteractLayerId = null
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                    .testTag("studio_mode_pill_crop")
                            ) {
                                Text(
                                    text = "Crop",
                                    fontSize = 9.sp,
                                    fontWeight = if (cropModeActive) FontWeight.Bold else FontWeight.Medium,
                                    color = if (cropModeActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (selectedLayer.type.supportsChromaKey()) {
                        val chromaOn = selectedLayer.graphicAdjustment.chromaKey.enabled
                        Surface(
                            onClick = {
                                onDragFinished()
                                dockTab = StudioDockTab.GRAPHICS
                                graphicCategory = GraphicCategoryTab.CHROMA_KEY
                            },
                            shape = RoundedCornerShape(5.dp),
                            color = if (chromaOn) ZroEmerald.copy(alpha = 0.24f) else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(
                                1.dp,
                                if (chromaOn) ZroEmerald else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier.testTag("studio_quick_chroma_btn")
                        ) {
                            Text(
                                text = if (chromaOn) "Key ON" else "Chroma",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (chromaOn) ZroEmerald else Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                    if (selectedLayer.type == LayerSourceType.BROWSER_WEB) {
                        Surface(
                            onClick = {
                                onDragFinished()
                                if (!isWebInteracting) cropModeActive = false
                                webInteractLayerId = if (isWebInteracting) null else selectedLayer.id
                            },
                            shape = RoundedCornerShape(5.dp),
                            color = if (isWebInteracting) ZroEmerald.copy(alpha = 0.24f) else ZroCobalt.copy(alpha = 0.22f),
                            border = BorderStroke(1.dp, if (isWebInteracting) ZroEmerald else ZroCobalt),
                            modifier = Modifier.testTag("browser_interact_toggle_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.TouchApp,
                                    contentDescription = if (isWebInteracting) "Exit webpage interaction" else "Interact with webpage",
                                    tint = if (isWebInteracting) ZroEmerald else Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = if (isWebInteracting) "Edit Layout" else "Interact Web",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = {
                            webInteractLayerId = null
                            cropModeActive = false
                            onDragFinished()
                            onSelectLayer(null)
                        },
                        shape = RoundedCornerShape(5.dp),
                        color = ZroEmerald.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, ZroEmerald.copy(alpha = 0.7f)),
                        modifier = Modifier.testTag("studio_deselect_layer_btn")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Done editing layer",
                                tint = ZroEmerald,
                                modifier = Modifier.size(12.dp)
                            )
                            Text("Done", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("obs_studio_list"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 3. Compact Canvas Toolbar: Live Mode (16:9 / Shorts / Dual), Grid Mode, Snap, Safe Area, Lock, Crop, Orientation, Zoom
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiveBroadcastMode.entries.forEach { mode ->
                        FilterChip(
                            selected = broadcastMode == mode,
                            onClick = { onSwitchBroadcastMode(mode) },
                            label = { Text(mode.shortLabel, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ZroEmerald.copy(alpha = 0.24f),
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.testTag("studio_mode_${mode.name.lowercase()}")
                        )
                    }
                    CanvasGridMode.entries.forEach { gm ->
                        val active = (if (workspace.showGrid) workspace.gridMode else CanvasGridMode.OFF) == gm
                        FilterChip(
                            selected = active,
                            onClick = { onSetGridMode(gm) },
                            label = { Text(gm.label, fontSize = 11.sp) },
                            leadingIcon = if (gm != CanvasGridMode.OFF) {
                                { Icon(Icons.Rounded.GridOn, contentDescription = null, modifier = Modifier.size(13.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ZroCobalt.copy(alpha = 0.22f),
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.testTag("grid_mode_${gm.name.lowercase()}")
                        )
                    }

                    FilterChip(
                        selected = workspace.showSnapGuides,
                        onClick = {
                            onUpdateCanvasOptions(workspace.showGrid, !workspace.showSnapGuides, workspace.showSafeArea, workspace.canvasZoom)
                        },
                        label = { Text("Snap", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ZroCobalt.copy(alpha = 0.22f),
                            selectedLabelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = workspace.showSafeArea,
                        onClick = {
                            onUpdateCanvasOptions(workspace.showGrid, workspace.showSnapGuides, !workspace.showSafeArea, workspace.canvasZoom)
                        },
                        label = { Text("Safe", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ZroAmber.copy(alpha = 0.22f),
                            selectedLabelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = activeScene.sceneLocked,
                        onClick = onToggleSceneLock,
                        label = { Text(if (activeScene.sceneLocked) "Locked" else "Lock", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                if (activeScene.sceneLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ZroAmber.copy(alpha = 0.22f),
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("scene_lock_toggle_btn")
                    )
                    FilterChip(
                        selected = cropModeActive,
                        onClick = { cropModeActive = !cropModeActive },
                        label = { Text(if (cropModeActive) "Crop ON" else "Crop", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Crop, contentDescription = null, modifier = Modifier.size(13.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ZroAmber.copy(alpha = 0.25f),
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("studio_crop_mode_btn")
                    )
                    FilterChip(
                        selected = activeScene.isVertical,
                        onClick = onToggleSceneVertical,
                        label = { Text(if (activeScene.isVertical) "9:16" else "16:9", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Rounded.ScreenRotation, contentDescription = null, modifier = Modifier.size(13.dp)) }
                    )

                    // Zoom controls
                    IconButton(
                        onClick = {
                            onUpdateCanvasOptions(workspace.showGrid, workspace.showSnapGuides, workspace.showSafeArea, (workspace.canvasZoom - 0.15f).coerceAtLeast(0.65f))
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Rounded.ZoomOut, contentDescription = "Zoom out", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Surface(
                        onClick = { onUpdateCanvasOptions(workspace.showGrid, workspace.showSnapGuides, workspace.showSafeArea, 1.0f) },
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = "${(workspace.canvasZoom * 100).toInt()}%",
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            onUpdateCanvasOptions(workspace.showGrid, workspace.showSnapGuides, workspace.showSafeArea, (workspace.canvasZoom + 0.15f).coerceAtMost(2.0f))
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Rounded.ZoomIn, contentDescription = "Zoom in", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // 4. Dock Tabs: Sources / Transform / Scene & Media
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StudioDockTab.entries.forEach { tab ->
                        val selected = dockTab == tab
                        Surface(
                            onClick = { dockTab = tab },
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) ZroCobalt.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, if (selected) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("studio_dock_${tab.name.lowercase()}")
                        ) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // 5. Active Dock Content
            when (dockTab) {
                StudioDockTab.SOURCES -> {
                    item(key = "sources_header_actions") {
                        GlassCard {
                            SectionHeader(
                                title = "Sources (${activeScene.layers.size})",
                                subtitle = "Tap source to select & show 8 transform handles"
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val canMerge = !activeScene.sceneLocked && activeScene.layers.count { !it.locked } >= 2
                                Surface(
                                    onClick = { if (canMerge) showMergeDialog = true },
                                    enabled = canMerge,
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(
                                        1.dp,
                                        if (canMerge) MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                    ),
                                    modifier = Modifier.testTag("studio_merge_layers_btn")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.CallMerge,
                                            contentDescription = "Merge layers",
                                            tint = if (canMerge) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Merge",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (canMerge) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                        )
                                    }
                                }
                                Surface(
                                    onClick = onOpenCameraSettings,
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Cam", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
                                    }
                                }
                                Surface(
                                    onClick = { showAddSourceDialog = true },
                                    shape = RoundedCornerShape(6.dp),
                                    color = ZroCobalt,
                                    modifier = Modifier.testTag("studio_add_source_btn")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Add, contentDescription = "Add source", tint = Color.White, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Add", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    item(key = "sources_capture_name_bar") {
                        // Screen Capture Name quick-edit bar
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SCREEN CAPTURE NAME",
                                        fontSize = 9.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = screenCaptureName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showRenameCaptureDialog = true },
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier
                                        .height(28.dp)
                                        .testTag("studio_rename_capture_btn")
                                ) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Rename screen capture", modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Edit Name", fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    if (selectedLayer != null) {
                        item(key = "sources_selected_layer_controls") {
                            GlassCard {
                                SectionHeader(
                                    title = "Selected: ${selectedLayer.name}",
                                    subtitle = "${selectedLayer.scaleMode.label} • Opacity ${(selectedLayer.opacity * 100).toInt()}%"
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SmallActionButton("Replace", Icons.Rounded.SwapHoriz) {
                                        if (selectedLayer.type == LayerSourceType.BROWSER_WEB) {
                                            dockTab = StudioDockTab.TRANSFORM
                                        } else {
                                            onReplaceLayerSource(selectedLayer)
                                        }
                                    }
                                    SmallActionButton("Fullscreen", Icons.Rounded.Fullscreen) {
                                        onFillCanvas(selectedLayer.id)
                                    }
                                    SmallActionButton("Reset Size", Icons.Rounded.RestartAlt) {
                                        onResetTransform(selectedLayer.id)
                                    }
                                    val nextScale = if (selectedLayer.scaleMode == LayerScaleMode.STRETCH) {
                                        LayerScaleMode.FIT
                                    } else {
                                        LayerScaleMode.STRETCH
                                    }
                                    SmallActionButton(
                                        label = if (selectedLayer.scaleMode == LayerScaleMode.STRETCH) "Stretch" else "Fit",
                                        icon = Icons.Rounded.FitScreen
                                    ) {
                                        onUpdateLayerProps(selectedLayer.id) { it.copy(scaleMode = nextScale) }
                                    }
                                    SmallActionButton("Delete", Icons.Rounded.DeleteOutline) {
                                        onDeleteLayer(selectedLayer.id)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                StudioSlider(
                                    label = "Opacity",
                                    valueText = "${(selectedLayer.opacity * 100).toInt()}%",
                                    value = selectedLayer.opacity,
                                    range = 0.1f..1f
                                ) { v ->
                                    onUpdateLayerProps(selectedLayer.id) { it.copy(opacity = v) }
                                }
                            }
                        }
                    }

                    val ordered = activeScene.layers.sortedByDescending { it.zIndex }
                    items(ordered.size, key = { idx -> ordered[idx].id }) { topIdx ->
                        val layer = ordered[topIdx]
                        val isSel = workspace.selectedLayerId == layer.id
                        val isCropped = layer.cropLeft > 0.005f || layer.cropRight > 0.005f ||
                            layer.cropTop > 0.005f || layer.cropBottom > 0.005f
                        var dragAccumY by remember(layer.id, topIdx) { mutableFloatStateOf(0f) }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSel) ZroCobalt.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                1.dp,
                                if (isSel) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectLayer(layer.id) }
                                .testTag("source_row_${layer.id}")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 5.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Drag-and-drop Z-index reorder grip
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp))
                                            .pointerInput(layer.id, topIdx, ordered.size) {
                                                detectVerticalDragGestures(
                                                    onDragStart = { dragAccumY = 0f },
                                                    onDragEnd = { dragAccumY = 0f },
                                                    onDragCancel = { dragAccumY = 0f }
                                                ) { change, dragAmount ->
                                                    change.consume()
                                                    dragAccumY += dragAmount
                                                    if (dragAccumY > layerRowHeightPx * 0.6f && topIdx < ordered.lastIndex) {
                                                        onReorderLayerTopToBottom(topIdx, topIdx + 1)
                                                        dragAccumY = 0f
                                                    } else if (dragAccumY < -layerRowHeightPx * 0.6f && topIdx > 0) {
                                                        onReorderLayerTopToBottom(topIdx, topIdx - 1)
                                                        dragAccumY = 0f
                                                    }
                                                }
                                            }
                                            .testTag("layer_drag_handle_${layer.id}"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DragHandle,
                                            contentDescription = "Drag to reorder layer",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = layer.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (layer.visible) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val meta = buildString {
                                            append("#${topIdx + 1} • ")
                                            if (layer.type == LayerSourceType.MERGED_GROUP) {
                                                append("Merged (${layer.mergedChildren.size})")
                                            } else {
                                                append(layer.type.label)
                                            }
                                            append(" • ${layer.scaleMode.label}")
                                            append(" • ${(layer.width * 100).toInt()}×${(layer.height * 100).toInt()}%")
                                            if (isCropped) append(" • Crop")
                                            if (layer.graphicAdjustment.chromaKey.enabled) append(" • Chroma")
                                        }
                                        Text(
                                            text = meta,
                                            fontSize = 10.sp,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp, 22.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp))
                                            .clickable {
                                                if (layer.type == LayerSourceType.BROWSER_WEB) {
                                                    onSelectLayer(layer.id)
                                                    dockTab = StudioDockTab.TRANSFORM
                                                } else {
                                                    onReplaceLayerSource(layer)
                                                }
                                            }
                                            .testTag("layer_replace_btn_${layer.id}"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.SwapHoriz,
                                            contentDescription = "Replace source",
                                            tint = ZroCobalt,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp, 22.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp))
                                            .clickable { renameTargetLayer = layer }
                                            .testTag("layer_rename_btn_${layer.id}"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.Edit,
                                            contentDescription = "Rename layer",
                                            tint = Color.White,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                    if (layer.type == LayerSourceType.MERGED_GROUP) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp, 22.dp)
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp))
                                                .clickable { onUnmergeLayer(layer.id) }
                                                .testTag("layer_unmerge_btn_${layer.id}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Rounded.CallSplit,
                                                contentDescription = "Unmerge layer",
                                                tint = ZroCobalt,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp, 22.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp))
                                            .clickable { onToggleLayerVisibility(layer.id) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            if (layer.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                            contentDescription = "Toggle visibility",
                                            tint = if (layer.visible) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp, 22.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp))
                                            .clickable { onToggleLayerLock(layer.id) }
                                            .testTag("layer_lock_btn_${layer.id}"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            if (layer.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                            contentDescription = "Toggle lock",
                                            tint = if (layer.locked) ZroAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp, 22.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp))
                                            .clickable { onMoveLayerZOrder(layer.id, true) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "Bring forward", tint = Color.White, modifier = Modifier.size(13.dp))
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp, 22.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp))
                                            .clickable { onMoveLayerZOrder(layer.id, false) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "Send backward", tint = Color.White, modifier = Modifier.size(13.dp))
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp, 22.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp))
                                            .clickable { onDuplicateLayer(layer.id) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Duplicate", tint = Color.White, modifier = Modifier.size(13.dp))
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp, 22.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp))
                                            .clickable { onDeleteLayer(layer.id) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = ZroLiveRed, modifier = Modifier.size(13.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                StudioDockTab.TRANSFORM -> {
                    item {
                        if (selectedLayer == null) {
                            GlassCard {
                                Text(
                                    text = "Select a source on the canvas or in the Sources tab to edit its position, size, crop, rotation, and alignment.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            GlassCard {
                                SectionHeader(
                                    title = "Transform — ${selectedLayer.name}",
                                    subtitle = if (selectedLayer.locked) "LOCKED — Unlock in Sources to edit" else "Drag 8 handles on canvas or fine-tune below"
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                // Quick Alignment & Fit buttons (Replace, Fit Fullscreen, Fill, Center, Reset)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    SmallActionButton("Replace", Icons.Rounded.SwapHoriz) { onReplaceLayerSource(selectedLayer) }
                                    SmallActionButton("Fullscreen", Icons.Rounded.Fullscreen) { onFillCanvas(selectedLayer.id) }
                                    SmallActionButton("Fit", Icons.Rounded.FitScreen) { onFitCanvas(selectedLayer.id) }
                                    SmallActionButton("Center", Icons.Rounded.CenterFocusStrong) { onAlignCenter(selectedLayer.id) }
                                    SmallActionButton("Center H", Icons.Rounded.CenterFocusStrong) { onCenterH(selectedLayer.id) }
                                    SmallActionButton("Center V", Icons.Rounded.CenterFocusStrong) { onCenterV(selectedLayer.id) }
                                    SmallActionButton("Reset", Icons.Rounded.RestartAlt) { onResetTransform(selectedLayer.id) }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                // Scale Mode: Stretch (Fill Box - default) vs Fit (Keep Inside Box)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Scale Mode",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                    LayerScaleMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = selectedLayer.scaleMode == mode,
                                            onClick = {
                                                onUpdateLayerProps(selectedLayer.id) { it.copy(scaleMode = mode) }
                                            },
                                            label = { Text(mode.label, fontSize = 11.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = ZroCobalt.copy(alpha = 0.24f),
                                                selectedLabelColor = Color.White
                                            ),
                                            modifier = Modifier.testTag("layer_scale_mode_${mode.name.lowercase()}")
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = selectedLayer.name,
                                    onValueChange = { txt -> onUpdateLayerProps(selectedLayer.id) { it.copy(name = txt) } },
                                    label = { Text("Source Name") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Keep Aspect Ratio", style = MaterialTheme.typography.labelMedium, color = Color.White)
                                    Switch(
                                        checked = selectedLayer.lockAspectRatio,
                                        onCheckedChange = { locked ->
                                            onUpdateLayerProps(selectedLayer.id) { it.copy(lockAspectRatio = locked) }
                                        },
                                        modifier = Modifier.testTag("studio_aspect_lock_switch")
                                    )
                                }

                                // Custom Grid / Ratio Size Presets (4x4, 6x2, 16:9, 1:1) & Exact Custom W x H
                                var customWText by remember(selectedLayer.id) { mutableStateOf("4") }
                                var customHText by remember(selectedLayer.id) { mutableStateOf("4") }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Custom Size Presets (W×H Grid Units)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { onApplyCustomSizeUnits(selectedLayer.id, 4f, 4f) },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp).testTag("size_preset_4x4")
                                    ) { Text("4×4", fontSize = 11.sp, color = Color.White) }
                                    OutlinedButton(
                                        onClick = { onApplyCustomSizeUnits(selectedLayer.id, 6f, 2f) },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp).testTag("size_preset_6x2")
                                    ) { Text("6×2", fontSize = 11.sp, color = Color.White) }
                                    OutlinedButton(
                                        onClick = { onApplyCustomSizeUnits(selectedLayer.id, 16f, 9f) },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp).testTag("size_preset_16x9")
                                    ) { Text("16:9", fontSize = 11.sp, color = Color.White) }
                                    OutlinedButton(
                                        onClick = { onApplyCustomSizeUnits(selectedLayer.id, 3f, 3f) },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp).testTag("size_preset_3x3")
                                    ) { Text("3×3", fontSize = 11.sp, color = Color.White) }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = customWText,
                                        onValueChange = { customWText = it.filter { ch -> ch.isDigit() || ch == '.' }.take(5) },
                                        label = { Text("W", fontSize = 10.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("custom_size_w_input")
                                    )
                                    OutlinedTextField(
                                        value = customHText,
                                        onValueChange = { customHText = it.filter { ch -> ch.isDigit() || ch == '.' }.take(5) },
                                        label = { Text("H", fontSize = 10.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("custom_size_h_input")
                                    )
                                    Button(
                                        onClick = {
                                            val w = customWText.toFloatOrNull() ?: 4f
                                            val h = customHText.toFloatOrNull() ?: 4f
                                            onApplyCustomSizeUnits(selectedLayer.id, w, h)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = ZroCobalt, contentColor = Color.White),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier.testTag("apply_custom_size_btn")
                                    ) {
                                        Text("Apply", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                val maxX = (1f - selectedLayer.width).coerceAtLeast(0.01f)
                                val maxY = (1f - selectedLayer.height).coerceAtLeast(0.01f)
                                val canvasW = if (activeScene.isVertical) 1080 else 1920
                                val canvasH = if (activeScene.isVertical) 1920 else 1080
                                StudioSlider(
                                    label = "X Position",
                                    valueText = "${(selectedLayer.x * 100).toInt()}% (${(selectedLayer.x * canvasW).toInt()}px)",
                                    value = selectedLayer.x.coerceIn(0f, maxX),
                                    range = 0f..maxX,
                                    onChange = { v -> onUpdateLayerProps(selectedLayer.id) { it.copy(x = v) } }
                                )
                                StudioSlider(
                                    label = "Y Position",
                                    valueText = "${(selectedLayer.y * 100).toInt()}% (${(selectedLayer.y * canvasH).toInt()}px)",
                                    value = selectedLayer.y.coerceIn(0f, maxY),
                                    range = 0f..maxY,
                                    onChange = { v -> onUpdateLayerProps(selectedLayer.id) { it.copy(y = v) } }
                                )
                                StudioSlider(
                                    label = "Width",
                                    valueText = "${(selectedLayer.width * 100).toInt()}% (${(selectedLayer.width * canvasW).toInt()}px)",
                                    value = selectedLayer.width,
                                    range = 0.08f..1f,
                                    onChange = { v -> onResizeLayerExact(selectedLayer.id, v, selectedLayer.height) },
                                    onChangeFinished = onDragFinished
                                )
                                StudioSlider(
                                    label = "Height",
                                    valueText = "${(selectedLayer.height * 100).toInt()}% (${(selectedLayer.height * canvasH).toInt()}px)",
                                    value = selectedLayer.height,
                                    range = 0.08f..1f,
                                    onChange = { v -> onResizeLayerExact(selectedLayer.id, selectedLayer.width, v) },
                                    onChangeFinished = onDragFinished
                                )
                                StudioSlider("Rotate", "${selectedLayer.rotationDeg.toInt()}°", selectedLayer.rotationDeg.coerceIn(0f, 360f), 0f..360f) { v ->
                                    onUpdateLayerProps(selectedLayer.id) { it.copy(rotationDeg = v) }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(0f, 90f, 180f, 270f).forEach { deg ->
                                        OutlinedButton(
                                            onClick = { onUpdateLayerProps(selectedLayer.id) { it.copy(rotationDeg = deg) } },
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.weight(1f).height(26.dp)
                                        ) {
                                            Text("${deg.toInt()}°", fontSize = 10.sp, color = Color.White)
                                        }
                                    }
                                }
                                StudioSlider("Opacity", "${(selectedLayer.opacity * 100).toInt()}%", selectedLayer.opacity, 0.1f..1f) { v ->
                                    onUpdateLayerProps(selectedLayer.id) { it.copy(opacity = v) }
                                }
                            }
                        }
                    }

                    if (selectedLayer != null) {
                        item(key = "transform_crop_card") {
                            // Dedicated Layer Cropping section (4 independent edges + presets + canvas handles + reset)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(
                                    1.dp,
                                    if (cropModeActive) ZroAmber else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("studio_crop_section")
                            ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Layer Cropping",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White
                                                )
                                                Text(
                                                    text = "Selection border matches the cropped area tightly with zero empty margin.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                FilterChip(
                                                    selected = cropModeActive,
                                                    onClick = { cropModeActive = !cropModeActive },
                                                    label = { Text(if (cropModeActive) "Handles ON" else "Handles", fontSize = 10.sp) },
                                                    leadingIcon = { Icon(Icons.Rounded.Crop, contentDescription = null, modifier = Modifier.size(12.dp)) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = ZroAmber.copy(alpha = 0.25f),
                                                        selectedLabelColor = Color.White
                                                    ),
                                                    modifier = Modifier.testTag("transform_crop_mode_btn")
                                                )
                                                OutlinedButton(
                                                    onClick = { onResetLayerCrop(selectedLayer.id) },
                                                    shape = RoundedCornerShape(6.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier
                                                        .height(32.dp)
                                                        .testTag("layer_reset_crop_btn")
                                                ) {
                                                    Text("Reset Crop", fontSize = 10.sp)
                                                }
                                            }
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            CropAspectPreset.entries.forEach { preset ->
                                                OutlinedButton(
                                                    onClick = { onApplyCropAspectPreset(selectedLayer.id, preset) },
                                                    shape = RoundedCornerShape(6.dp),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                    modifier = Modifier
                                                        .height(28.dp)
                                                        .testTag("crop_preset_${preset.name.lowercase()}")
                                                ) {
                                                    Text(preset.label, fontSize = 10.sp, color = Color.White)
                                                }
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = { onRestoreLayerOriginalSize(selectedLayer.id) },
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(28.dp)
                                                .testTag("layer_restore_original_size_btn")
                                        ) {
                                            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Restore Original Size", fontSize = 10.sp, color = Color.White)
                                        }

                                        StudioSlider(
                                            label = "Crop Left",
                                            valueText = "${(selectedLayer.cropLeft * 100).toInt()}%",
                                            value = selectedLayer.cropLeft,
                                            range = 0f..0.95f,
                                            onChange = { v -> onSetLayerCropLive(selectedLayer.id, v, null, null, null) },
                                            onChangeFinished = onDragFinished,
                                            sliderModifier = Modifier.testTag("crop_slider_left")
                                        )
                                        StudioSlider(
                                            label = "Crop Right",
                                            valueText = "${(selectedLayer.cropRight * 100).toInt()}%",
                                            value = selectedLayer.cropRight,
                                            range = 0f..0.95f,
                                            onChange = { v -> onSetLayerCropLive(selectedLayer.id, null, null, v, null) },
                                            onChangeFinished = onDragFinished,
                                            sliderModifier = Modifier.testTag("crop_slider_right")
                                        )
                                        StudioSlider(
                                            label = "Crop Top",
                                            valueText = "${(selectedLayer.cropTop * 100).toInt()}%",
                                            value = selectedLayer.cropTop,
                                            range = 0f..0.95f,
                                            onChange = { v -> onSetLayerCropLive(selectedLayer.id, null, v, null, null) },
                                            onChangeFinished = onDragFinished,
                                            sliderModifier = Modifier.testTag("crop_slider_top")
                                        )
                                        StudioSlider(
                                            label = "Crop Bottom",
                                            valueText = "${(selectedLayer.cropBottom * 100).toInt()}%",
                                            value = selectedLayer.cropBottom,
                                            range = 0f..0.95f,
                                            onChange = { v -> onSetLayerCropLive(selectedLayer.id, null, null, null, v) },
                                            onChangeFinished = onDragFinished,
                                            sliderModifier = Modifier.testTag("crop_slider_bottom")
                                        )
                                    }
                                }
                            }

                        item(key = "transform_content_card") {
                            GlassCard {
                                // Context-sensitive content editor (Text, BRB/Ending fullscreen, Browser URL, Subscriber Goal)
                                if (selectedLayer.type == LayerSourceType.TEXT ||
                                    selectedLayer.type == LayerSourceType.BRB_BANNER ||
                                    selectedLayer.type == LayerSourceType.ENDING_BANNER ||
                                    selectedLayer.type == LayerSourceType.STARTING_SOON ||
                                    selectedLayer.type == LayerSourceType.ANIMATED_OVERLAY ||
                                    selectedLayer.type == LayerSourceType.LOGO
                                ) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = selectedLayer.textContent,
                                        onValueChange = { txt -> onUpdateLayerProps(selectedLayer.id) { it.copy(textContent = txt) } },
                                        label = { Text("Headline / Text") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (selectedLayer.type == LayerSourceType.BRB_BANNER ||
                                        selectedLayer.type == LayerSourceType.ENDING_BANNER ||
                                        selectedLayer.type == LayerSourceType.STARTING_SOON
                                    ) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        OutlinedTextField(
                                            value = selectedLayer.subtitleText,
                                            onValueChange = { txt -> onUpdateLayerProps(selectedLayer.id) { it.copy(subtitleText = txt) } },
                                            label = { Text("Subtitle") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf("DISPLAY", "SANS", "MONO").forEach { style ->
                                                FilterChip(
                                                    selected = selectedLayer.fontStyle == style,
                                                    onClick = { onUpdateLayerProps(selectedLayer.id) { it.copy(fontStyle = style) } },
                                                    label = { Text(style, fontSize = 10.sp) },
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = { onPickBackgroundMediaForLayer(selectedLayer.id) },
                                                shape = RoundedCornerShape(6.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Gallery BG", fontSize = 10.sp)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    onUpdateLayerProps(selectedLayer.id) {
                                                        it.copy(mediaUri = null, transparentBackground = false)
                                                    }
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("reset_scene_ai_art_btn")
                                            ) {
                                                Text("AI Art BG", fontSize = 10.sp)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    onUpdateLayerProps(selectedLayer.id) {
                                                        it.copy(motionBackgroundEnabled = !it.motionBackgroundEnabled)
                                                    }
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(if (selectedLayer.motionBackgroundEnabled) "Motion ON" else "Motion OFF", fontSize = 10.sp)
                                            }
                                        }
                                        StudioSlider("Motion Speed", "${"%.1f".format(selectedLayer.animationSpeed)}×", selectedLayer.animationSpeed, 0.4f..2.5f) { v ->
                                            onUpdateLayerProps(selectedLayer.id) { it.copy(animationSpeed = v) }
                                        }
                                    }
                                }

                                if (selectedLayer.type == LayerSourceType.BROWSER_WEB) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    var urlDraft by remember(selectedLayer.id, selectedLayer.webUrl) {
                                        mutableStateOf(selectedLayer.webUrl)
                                    }
                                    var urlError by remember(selectedLayer.id) {
                                        mutableStateOf<String?>(null)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "Browser / Website Source",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White
                                            )
                                            OutlinedTextField(
                                                value = urlDraft,
                                                onValueChange = {
                                                    urlDraft = it
                                                    urlError = null
                                                },
                                                label = { Text("Website URL (https://...)") },
                                                placeholder = { Text("https://example.com/overlay", fontSize = 12.sp) },
                                                isError = urlError != null,
                                                supportingText = if (urlError != null) {
                                                    { Text(urlError!!, color = ZroLiveRed, fontSize = 11.sp) }
                                                } else null,
                                                singleLine = true,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("studio_browser_url_input")
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Button(
                                                    onClick = {
                                                        val normalizedUrl = BrowserUrlValidator.normalizeAndValidate(urlDraft)
                                                        if (normalizedUrl != null) {
                                                            urlError = null
                                                            onUpdateLayerProps(selectedLayer.id) {
                                                                it.copy(
                                                                    webUrl = normalizedUrl,
                                                                    webReloadNonce = it.webReloadNonce + 1
                                                                )
                                                            }
                                                        } else {
                                                            urlError = "Enter a valid https:// or http:// URL."
                                                        }
                                                    },
                                                    enabled = !selectedLayer.locked,
                                                    colors = ButtonDefaults.buttonColors(containerColor = ZroCobalt, contentColor = Color.White),
                                                    shape = RoundedCornerShape(6.dp),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .testTag("studio_browser_load_btn")
                                                ) {
                                                    Text("Load", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                                OutlinedButton(
                                                    onClick = { onReloadBrowserLayer(selectedLayer.id) },
                                                    enabled = selectedLayer.webUrl.isNotBlank(),
                                                    shape = RoundedCornerShape(6.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .testTag("studio_browser_reload_btn")
                                                ) {
                                                    Icon(Icons.Rounded.Refresh, contentDescription = "Reload page", modifier = Modifier.size(13.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Reload", fontSize = 11.sp)
                                                }
                                                    OutlinedButton(
                                                        onClick = {
                                                            urlDraft = ""
                                                            urlError = null
                                                            onUpdateLayerProps(selectedLayer.id) { it.copy(webUrl = "") }
                                                        },
                                                        enabled = !selectedLayer.locked && selectedLayer.webUrl.isNotBlank(),
                                                        shape = RoundedCornerShape(6.dp),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                        modifier = Modifier.testTag("studio_browser_clear_btn")
                                                    ) {
                                                        Text("Clear", fontSize = 11.sp)
                                                    }
                                                }
                                                val interactingNow = webInteractLayerId == selectedLayer.id
                                                OutlinedButton(
                                                    onClick = {
                                                        webInteractLayerId = if (interactingNow) null else selectedLayer.id
                                                    },
                                                    enabled = selectedLayer.webUrl.isNotBlank(),
                                                    shape = RoundedCornerShape(6.dp),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .testTag("studio_browser_interact_panel_btn")
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.TouchApp,
                                                        contentDescription = null,
                                                        tint = if (interactingNow) ZroEmerald else ZroCobalt,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = if (interactingNow) {
                                                            "Web Interaction ACTIVE (Tap to return to Drag/Resize)"
                                                        } else {
                                                            "Interact with Website (Click & Scroll inside page)"
                                                        },
                                                        fontSize = 11.sp,
                                                        color = Color.White
                                                    )
                                                }
                                                Text(
                                                    text = "Fixed Virtual Viewport (${selectedLayer.webViewportPreset.widthPx}×${selectedLayer.webViewportPreset.heightPx}) — zero reflow on crop",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    WebViewportPreset.entries.forEach { preset ->
                                                        val isSelected = selectedLayer.webViewportPreset == preset
                                                        FilterChip(
                                                            selected = isSelected,
                                                            onClick = {
                                                                onUpdateLayerProps(selectedLayer.id) {
                                                                    it.copy(webViewportPreset = preset)
                                                                }
                                                            },
                                                            label = { Text(preset.label, fontSize = 10.sp, maxLines = 1) },
                                                            colors = FilterChipDefaults.filterChipColors(
                                                                selectedContainerColor = ZroCobalt.copy(alpha = 0.25f),
                                                                selectedLabelColor = Color.White
                                                            ),
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .testTag("web_viewport_${preset.name.lowercase()}")
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                if (selectedLayer.type == LayerSourceType.SUBSCRIBER_COUNTER) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = selectedLayer.subTitle,
                                        onValueChange = { t -> onUpdateLayerProps(selectedLayer.id) { it.copy(subTitle = t) } },
                                        label = { Text("Counter Title") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = selectedLayer.subCount.toString(),
                                            onValueChange = { txt ->
                                                txt.toIntOrNull()?.let { c -> onUpdateLayerProps(selectedLayer.id) { it.copy(subCount = c.coerceAtLeast(0)) } }
                                            },
                                            label = { Text("Current") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = selectedLayer.subGoal.toString(),
                                            onValueChange = { txt ->
                                                txt.toIntOrNull()?.let { g -> onUpdateLayerProps(selectedLayer.id) { it.copy(subGoal = g.coerceAtLeast(1)) } }
                                            },
                                            label = { Text("Goal") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                StudioDockTab.GRAPHICS -> {
                    graphicAdjustmentDockItems(
                        selectedLayer = selectedLayer,
                        otherLayersInScene = activeScene.layers.filter { it.id != selectedLayer?.id },
                        sceneLocked = activeScene.sceneLocked,
                        customPresets = workspace.customGraphicPresets,
                        canUndo = canUndo,
                        canRedo = canRedo,
                        beforePreviewActive = beforePreviewActive,
                        activeCategory = graphicCategory,
                        onSelectCategory = { graphicCategory = it },
                        onToggleBeforePreview = { beforePreviewActive = !beforePreviewActive },
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onUpdateAdjustmentLive = onUpdateLayerGraphicAdjustmentLive,
                        onCommitAdjustment = onDragFinished,
                        onApplyBuiltInPreset = onApplyGraphicPreset,
                        onApplyCustomPreset = onApplyCustomGraphicPreset,
                        onSaveCustomPreset = onSaveCustomGraphicPreset,
                        onDeleteCustomPreset = onDeleteCustomGraphicPreset,
                        onResetSelectedLayer = onResetLayerGraphicAdjustment,
                        onCopyToLayer = onCopyGraphicAdjustmentToLayer,
                        mirroredFrame = mirroredFrame
                    )
                }

                StudioDockTab.CUSTOMIZE -> {
                    item {
                        GlassCard {
                            SectionHeader(
                                title = "Save Screen Layout & Auto-Restore",
                                subtitle = "Layout persists across app restarts and authorized Phone 1 reconnects"
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Auto-Restore Last Layout", style = MaterialTheme.typography.labelLarge, color = Color.White)
                                    Text(
                                        text = if (workspace.lastAuthorizedDeviceName.isNotBlank()) {
                                            "Linked device: ${workspace.lastAuthorizedDeviceName}"
                                        } else {
                                            "Automatically restores your saved scene layout on launch"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = workspace.autoRestoreLastLayout,
                                    onCheckedChange = onSetAutoRestoreLastLayout,
                                    modifier = Modifier.testTag("studio_auto_restore_switch")
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onSaveLayoutExplicit,
                                    colors = ButtonDefaults.buttonColors(containerColor = ZroCobalt, contentColor = Color.White),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("SAVE LAYOUT")
                                }
                                OutlinedButton(
                                    onClick = { showResetLayoutConfirm = true },
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("studio_reset_layout_btn")
                                ) {
                                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = ZroLiveRed, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("RESET LAYOUT", color = ZroLiveRed)
                                }
                            }
                        }
                    }

                    item {
                        GlassCard {
                            SectionHeader(title = "Scene Settings — ${activeScene.name}")
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = activeScene.name,
                                onValueChange = onRenameActiveScene,
                                label = { Text("Scene Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Scene Transition", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SceneTransitionType.entries.forEach { t ->
                                    FilterChip(
                                        selected = activeScene.transitionType == t,
                                        onClick = { onUpdateTransition(t, activeScene.transitionDurationMs) },
                                        label = { Text(t.label, fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = onResetActiveScene,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Reset Only '${activeScene.name}' to Default")
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun SmallActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(13.dp))
            Text(label, fontSize = 11.sp, color = Color.White)
        }
    }
}

@Composable
private fun StudioSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    sliderModifier: Modifier = Modifier,
    onChangeFinished: (() -> Unit)? = null,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueText, style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFontFamily, color = Color.White)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = ZroCobalt, activeTrackColor = ZroCobalt),
            modifier = sliderModifier
        )
    }
}

private fun formatSavedTime(epochMs: Long): String {
    return runCatching {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
    }.getOrDefault("Just now")
}
