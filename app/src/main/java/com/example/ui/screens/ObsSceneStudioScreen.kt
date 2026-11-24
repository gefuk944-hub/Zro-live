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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AudioMeterState
import com.example.data.model.YouTubeChatMessage
import com.example.studio.CanvasGridMode
import com.example.studio.CropAspectPreset
import com.example.studio.CropEdge
import com.example.studio.CustomGraphicPreset
import com.example.studio.DualPhoneTelemetry
import com.example.studio.GraphicPresetId
import com.example.studio.LayerAnimationType
import com.example.studio.LayerGraphicAdjustment
import com.example.studio.LayerSourceType
import com.example.studio.ResizeCorner
import com.example.studio.SceneLayer
import com.example.studio.SceneSlot
import com.example.studio.SceneTransitionType
import com.example.studio.StudioWorkspaceState
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
    onRenameLayer: (String, String) -> Unit = { _, _ -> },
    onRenameScreenCapture: (String) -> Unit = {},
    onMergeLayers: (List<String>, String) -> Unit = { _, _ -> },
    onUnmergeLayer: (String) -> Unit = {},
    onMoveLayerTo: ((String, Float, Float) -> Unit)? = null,
    onReloadBrowserLayer: (String) -> Unit = {}
) {
    val activeScene = workspace.activeScene
    val selectedLayer = activeScene.layers.firstOrNull { it.id == workspace.selectedLayerId }
    var dockTab by remember { mutableStateOf(StudioDockTab.SOURCES) }
    var beforePreviewActive by remember { mutableStateOf(false) }
    var graphicCategory by remember { mutableStateOf(GraphicCategoryTab.TONE) }
    var cropModeActive by remember { mutableStateOf(false) }
    var renameTargetLayer by remember { mutableStateOf<SceneLayer?>(null) }
    var showRenameCaptureDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showResetLayoutConfirm by remember { mutableStateOf(false) }
    var showAddSourceDialog by remember { mutableStateOf(false) }

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
        val maxCanvasHeight = (maxHeight * 0.28f).coerceIn(104.dp, 220.dp)
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        if (onBack != null) {
            ProSubpageTopBar(
                title = "Scene Studio",
                subtitle = activeScene.name,
                onBack = onBack,
                backTestTag = "studio_back_button",
                trailing = {
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
                        onClick = { onSwitchScene(slot) },
                        shape = RoundedCornerShape(6.dp),
                        color = if (selected) ZroCobalt.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            1.dp,
                            if (selected) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scene_tab_${slot.name.lowercase()}")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = slot.shortLabel,
                                fontSize = 9.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = if (selected) ZroCobalt else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = sceneName,
                                fontSize = 11.sp,
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
                audioMeters = audioMeters,
                chatMessages = chatMessages,
                reducedMotion = reducedMotion,
                dualTelemetry = dualTelemetry,
                cameraPermissionGranted = cameraPermissionGranted,
                enableLiveCameraPreview = workspace.cameraEnabled && cameraPermissionGranted,
                interactive = true,
                cropModeActive = cropModeActive,
                onSelectLayer = onSelectLayer,
                onMoveLayerBy = onMoveLayerBy,
                onMoveLayerTo = onMoveLayerTo,
                onResizeLayerCorner = onResizeLayerCorner,
                onCropLayerEdge = onCropLayerEdge,
                onDragFinished = onDragFinished,
                onReconnectPeer = onReconnectPeer,
                beforePreviewLayerId = if (beforePreviewActive && dockTab == StudioDockTab.GRAPHICS) selectedLayer?.id else null,
                modifier = Modifier.heightIn(max = maxCanvasHeight)
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("obs_studio_list"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 3. Compact Canvas Toolbar: Grid Mode (2x6 default, 4x4, Off), Snap, Safe Area, Lock, Orientation, Zoom
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                    item(key = "sources_header_card") {
                        GlassCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SectionHeader(
                                    title = "Sources (${activeScene.layers.size})",
                                    subtitle = "Tap source to select & show 8 transform handles",
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        onClick = { showMergeDialog = true },
                                        enabled = !activeScene.sceneLocked && activeScene.layers.count { !it.locked } >= 2,
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .height(30.dp)
                                            .testTag("studio_merge_layers_btn")
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.CallMerge, contentDescription = "Merge layers", modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Merge", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = onOpenCameraSettings,
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Rounded.Videocam, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Cam", fontSize = 11.sp)
                                    }
                                    Surface(
                                        onClick = { showAddSourceDialog = true },
                                        shape = RoundedCornerShape(6.dp),
                                        color = ZroCobalt,
                                        modifier = Modifier.testTag("studio_add_source_btn")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.Add, contentDescription = "Add source", tint = Color.White, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("Add", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
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
                    }

                    val ordered = activeScene.layers.sortedByDescending { it.zIndex }
                    items(ordered, key = { it.id }) { layer ->
                        val isSel = workspace.selectedLayerId == layer.id
                        val isCropped = layer.cropLeft > 0.005f || layer.cropRight > 0.005f ||
                            layer.cropTop > 0.005f || layer.cropBottom > 0.005f
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
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
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
                                        if (layer.type == LayerSourceType.MERGED_GROUP) {
                                            append("Merged (${layer.mergedChildren.size})")
                                        } else {
                                            append(layer.type.label)
                                        }
                                        append(" • ${(layer.width * 100).toInt()}×${(layer.height * 100).toInt()}%")
                                        if (layer.sourceMediaWidth > 0 && layer.sourceMediaHeight > 0) {
                                            append(" • ${layer.sourceMediaWidth}×${layer.sourceMediaHeight}")
                                        }
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
                                IconButton(
                                    onClick = { renameTargetLayer = layer },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("layer_rename_btn_${layer.id}")
                                ) {
                                    Icon(
                                        Icons.Rounded.Edit,
                                        contentDescription = "Rename layer",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                if (layer.type == LayerSourceType.MERGED_GROUP) {
                                    IconButton(
                                        onClick = { onUnmergeLayer(layer.id) },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("layer_unmerge_btn_${layer.id}")
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.CallSplit,
                                            contentDescription = "Unmerge layer",
                                            tint = ZroCobalt,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onToggleLayerVisibility(layer.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        if (layer.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                        contentDescription = "Toggle visibility",
                                        tint = if (layer.visible) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onToggleLayerLock(layer.id) },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("layer_lock_btn_${layer.id}")
                                ) {
                                    Icon(
                                        if (layer.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                        contentDescription = "Toggle lock",
                                        tint = if (layer.locked) ZroAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onMoveLayerZOrder(layer.id, true) },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Bring forward", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                                IconButton(
                                    onClick = { onMoveLayerZOrder(layer.id, false) },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "Send backward", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                                IconButton(
                                    onClick = { onDuplicateLayer(layer.id) },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Duplicate", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                                IconButton(
                                    onClick = { onDeleteLayer(layer.id) },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = ZroLiveRed, modifier = Modifier.size(14.dp))
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

                                // Quick Alignment & Fit buttons
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    SmallActionButton("Center H", Icons.Rounded.CenterFocusStrong) { onCenterH(selectedLayer.id) }
                                    SmallActionButton("Center V", Icons.Rounded.CenterFocusStrong) { onCenterV(selectedLayer.id) }
                                    SmallActionButton("Fit", Icons.Rounded.FitScreen) { onFitCanvas(selectedLayer.id) }
                                    SmallActionButton("Fill", Icons.Rounded.FitScreen) { onFillCanvas(selectedLayer.id) }
                                    SmallActionButton("Reset", Icons.Rounded.RestartAlt) { onResetTransform(selectedLayer.id) }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
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
                                    Text("Proportional Resize Lock", style = MaterialTheme.typography.labelMedium, color = Color.White)
                                    Switch(
                                        checked = selectedLayer.lockAspectRatio,
                                        onCheckedChange = { locked ->
                                            onUpdateLayerProps(selectedLayer.id) { it.copy(lockAspectRatio = locked) }
                                        }
                                    )
                                }

                                StudioSlider("X Position", "${(selectedLayer.x * 100).toInt()}%", selectedLayer.x, 0f..0.95f) { v ->
                                    onUpdateLayerProps(selectedLayer.id) { it.copy(x = v) }
                                }
                                StudioSlider("Y Position", "${(selectedLayer.y * 100).toInt()}%", selectedLayer.y, 0f..0.95f) { v ->
                                    onUpdateLayerProps(selectedLayer.id) { it.copy(y = v) }
                                }
                                StudioSlider("Width", "${(selectedLayer.width * 100).toInt()}%", selectedLayer.width, 0.08f..1f) { v ->
                                    onUpdateLayerProps(selectedLayer.id) { it.copy(width = v) }
                                }
                                StudioSlider("Height", "${(selectedLayer.height * 100).toInt()}%", selectedLayer.height, 0.08f..1f) { v ->
                                    onUpdateLayerProps(selectedLayer.id) { it.copy(height = v) }
                                }
                                StudioSlider("Rotate", "${selectedLayer.rotationDeg.toInt()}°", selectedLayer.rotationDeg, -180f..180f) { v ->
                                    onUpdateLayerProps(selectedLayer.id) { it.copy(rotationDeg = v) }
                                }
                                StudioSlider("Opacity", "${(selectedLayer.opacity * 100).toInt()}%", selectedLayer.opacity, 0.1f..1f) { v ->
                                    onUpdateLayerProps(selectedLayer.id) { it.copy(opacity = v) }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
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
                                                    text = "Trim Left, Right, Top, Bottom or drag amber handles on canvas",
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
                                                    Text("Reset", fontSize = 10.sp)
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

                                        StudioSlider(
                                            label = "Crop Left",
                                            valueText = "${(selectedLayer.cropLeft * 100).toInt()}%",
                                            value = selectedLayer.cropLeft,
                                            range = 0f..0.80f,
                                            onChange = { v -> onSetLayerCropLive(selectedLayer.id, v, null, null, null) },
                                            onChangeFinished = onDragFinished,
                                            sliderModifier = Modifier.testTag("crop_slider_left")
                                        )
                                        StudioSlider(
                                            label = "Crop Right",
                                            valueText = "${(selectedLayer.cropRight * 100).toInt()}%",
                                            value = selectedLayer.cropRight,
                                            range = 0f..0.80f,
                                            onChange = { v -> onSetLayerCropLive(selectedLayer.id, null, null, v, null) },
                                            onChangeFinished = onDragFinished,
                                            sliderModifier = Modifier.testTag("crop_slider_right")
                                        )
                                        StudioSlider(
                                            label = "Crop Top",
                                            valueText = "${(selectedLayer.cropTop * 100).toInt()}%",
                                            value = selectedLayer.cropTop,
                                            range = 0f..0.80f,
                                            onChange = { v -> onSetLayerCropLive(selectedLayer.id, null, v, null, null) },
                                            onChangeFinished = onDragFinished,
                                            sliderModifier = Modifier.testTag("crop_slider_top")
                                        )
                                        StudioSlider(
                                            label = "Crop Bottom",
                                            valueText = "${(selectedLayer.cropBottom * 100).toInt()}%",
                                            value = selectedLayer.cropBottom,
                                            range = 0f..0.80f,
                                            onChange = { v -> onSetLayerCropLive(selectedLayer.id, null, null, null, v) },
                                            onChangeFinished = onDragFinished,
                                            sliderModifier = Modifier.testTag("crop_slider_bottom")
                                        )
                                    }
                                }

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
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = { onPickBackgroundMediaForLayer(selectedLayer.id) },
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Gallery BG", fontSize = 11.sp)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    onUpdateLayerProps(selectedLayer.id) {
                                                        it.copy(mediaUri = null, motionBackgroundEnabled = !it.motionBackgroundEnabled)
                                                    }
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(if (selectedLayer.motionBackgroundEnabled) "Motion BG: ON" else "Motion BG: OFF", fontSize = 11.sp)
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
