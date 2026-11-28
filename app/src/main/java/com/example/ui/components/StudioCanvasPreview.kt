package com.example.ui.components

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.AudioMeterState
import com.example.data.model.LiveVideoSource
import com.example.data.model.YouTubeChatMessage
import com.example.streaming.ZroStreamCoordinator
import com.example.studio.CameraSourceMode
import com.example.studio.CanvasGridMode
import com.example.studio.ChromaKeyProcessor
import com.example.studio.CropEdge
import com.example.studio.DualLinkState
import com.example.studio.DualPhoneRole
import com.example.studio.DualPhoneTelemetry
import com.example.studio.FacecamShape
import com.example.studio.LayerAnimationType
import com.example.studio.LayerScaleMode
import com.example.studio.LayerSourceType
import com.example.studio.ResizeCorner
import com.example.studio.SceneLayer
import com.example.studio.SceneTransitionType
import com.example.studio.StudioScene
import com.example.studio.StudioWorkspaceReducer
import com.example.studio.StudioWorkspaceState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald
import com.example.ui.theme.ZroLiveRed
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * OBS-style interactive visual canvas editor:
 * - Real 4 draggable corner resize handles (Top-Left, Top-Right, Bottom-Left, Bottom-Right)
 * - Drag to move unlocked sources
 * - Selectable 2x6 (default), 4x4, or Grid Off overlay + center alignment guides + safe area
 * - Strict Phone 2 privacy guard: displays SOURCE DISCONNECTED when Phone 1 is offline
 * - Facecam shapes (Circle / Square / Rounded) with optional live CameraX preview when ON
 * - Fullscreen BRB & Ending broadcast motion graphics + Gallery media backgrounds
 */
@Composable
fun StudioCanvasPreview(
    workspace: StudioWorkspaceState,
    mirroredFrame: Bitmap?,
    audioMeters: AudioMeterState,
    chatMessages: List<YouTubeChatMessage>,
    reducedMotion: Boolean,
    dualTelemetry: DualPhoneTelemetry = DualPhoneTelemetry(),
    cameraPermissionGranted: Boolean = false,
    enableLiveCameraPreview: Boolean = false,
    interactive: Boolean = true,
    cropModeActive: Boolean = false,
    onSelectLayer: (String?) -> Unit = {},
    onMoveLayerBy: (String, Float, Float) -> Unit = { _, _, _ -> },
    onMoveLayerTo: ((String, Float, Float) -> Unit)? = null,
    onResizeLayerCorner: (String, ResizeCorner, Float, Float, Boolean) -> Unit = { _, _, _, _, _ -> },
    onCropLayerEdge: (String, CropEdge, Float) -> Unit = { _, _, _ -> },
    onDragFinished: () -> Unit = {},
    onReconnectPeer: (() -> Unit)? = null,
    beforePreviewLayerId: String? = null,
    webInteractLayerId: String? = null,
    localCapturedFrame: Bitmap? = null,
    modifier: Modifier = Modifier
) {
    val liveLocalFrame by ZroStreamCoordinator.localCapturedFrame.collectAsStateWithLifecycle()
    val liveStreamTelemetry by ZroStreamCoordinator.telemetry.collectAsStateWithLifecycle()
    val resolvedLocalFrame = localCapturedFrame ?: liveLocalFrame
    val activeScene = workspace.activeScene
    val aspect = if (activeScene.isVertical) 9f / 16f else 16f / 9f
    var isActivelyDraggingLayer by remember { mutableStateOf(false) }
    val borderColor = when {
        workspace.brbActive -> ZroAmber.copy(alpha = 0.85f)
        activeScene.sceneLocked -> ZroAmber.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.outline
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF07090D))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        val outerMaxW = maxWidth.coerceAtLeast(120.dp)
        val outerMaxH = maxHeight
        AnimatedContent(
            targetState = activeScene,
            contentKey = { it.slot },
            transitionSpec = {
                val dur = if (reducedMotion) 0 else targetState.transitionDurationMs.coerceIn(100, 700)
                when (targetState.transitionType) {
                    SceneTransitionType.CUT -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    SceneTransitionType.FADE -> fadeIn(tween(dur)) togetherWith fadeOut(tween(dur))
                    SceneTransitionType.SLIDE ->
                        slideInHorizontally(tween(dur)) { it / 3 } + fadeIn(tween(dur)) togetherWith
                            slideOutHorizontally(tween(dur)) { -it / 3 } + fadeOut(tween(dur))
                    SceneTransitionType.ZOOM ->
                        scaleIn(tween(dur), initialScale = 0.94f) + fadeIn(tween(dur)) togetherWith
                            scaleOut(tween(dur), targetScale = 1.04f) + fadeOut(tween(dur))
                }
            },
            label = "studio_scene_transition"
        ) { scene ->
            val sceneAspect = if (scene.isVertical) 9f / 16f else 16f / 9f
            val preferredW = if (scene.isVertical) outerMaxW * 0.56f else outerMaxW
            val fittedW = if (outerMaxH.value.isFinite() && outerMaxH < 1000.dp) {
                minOf(preferredW, outerMaxH * sceneAspect)
            } else {
                preferredW
            }
            BoxWithConstraints(
                modifier = Modifier
                    .width(fittedW)
                    .aspectRatio(sceneAspect)
                    .scale(workspace.canvasZoom.coerceIn(0.65f, 2.0f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF0B0E14))
                    .clipToBounds()
                    .clickable(enabled = interactive && webInteractLayerId == null) { onSelectLayer(null) }
                    .testTag("studio_canvas_surface")
            ) {
                val canvasWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val canvasHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

                val sortedLayers = scene.layers.sortedBy { it.zIndex }
                sortedLayers.forEach { layer ->
                    val isGameplayOrBg = layer.type == LayerSourceType.GAMEPLAY_SCREEN ||
                        layer.type == LayerSourceType.BACKGROUND ||
                        layer.type == LayerSourceType.BRB_BANNER ||
                        layer.type == LayerSourceType.ENDING_BANNER

                    // Privacy / Camera / BRB rules:
                    // 1. Facecam is hidden whenever cameraEnabled == false or BRB is active.
                    // 2. Gameplay capture is hidden whenever BRB is active.
                    val hiddenByBrb = workspace.brbActive &&
                        (layer.type == LayerSourceType.GAMEPLAY_SCREEN || layer.type == LayerSourceType.WEBCAM)
                    val hiddenByCameraOff = layer.type == LayerSourceType.WEBCAM && !workspace.cameraEnabled
                    val isVisible = layer.visible &&
                        !hiddenByBrb &&
                        !hiddenByCameraOff &&
                        (isGameplayOrBg || workspace.overlaysGlobalVisible) &&
                        (layer.type != LayerSourceType.CHAT_OVERLAY || workspace.chatOverlayEnabled)

                    if (isVisible) {
                        StudioLayerNode(
                            layer = layer,
                            selected = interactive && workspace.selectedLayerId == layer.id,
                            sceneLocked = scene.sceneLocked,
                            interactive = interactive,
                            canvasWidthPx = canvasWidthPx,
                            canvasHeightPx = canvasHeightPx,
                            mirroredFrame = mirroredFrame,
                            localCapturedFrame = resolvedLocalFrame,
                            isAutoSoloFallback = liveStreamTelemetry.activeVideoSource == LiveVideoSource.LOCAL_FULL_SCREEN && liveStreamTelemetry.state.isActive,
                            dualTelemetry = dualTelemetry,
                            cameraSourceMode = workspace.cameraSourceMode,
                            useFrontCamera = workspace.useFrontCamera,
                            cameraPermissionGranted = cameraPermissionGranted,
                            enableLiveCameraPreview = enableLiveCameraPreview,
                            audioMeters = audioMeters,
                            chatMessages = chatMessages,
                            pinnedChatAuthor = workspace.pinnedChatAuthor,
                            pinnedChatText = workspace.pinnedChatText,
                            chatTextSizeSp = workspace.chatTextSizeSp,
                            reducedMotion = reducedMotion,
                            onSelect = { onSelectLayer(layer.id) },
                            onMoveBy = { dx, dy ->
                                isActivelyDraggingLayer = true
                                onMoveLayerBy(layer.id, dx, dy)
                            },
                            onMoveTo = onMoveLayerTo?.let { cb ->
                                { rx, ry ->
                                    isActivelyDraggingLayer = true
                                    cb(layer.id, rx, ry)
                                }
                            },
                            onResizeCorner = { corner, dx, dy ->
                                val isCorner = corner == ResizeCorner.TOP_LEFT ||
                                    corner == ResizeCorner.TOP_RIGHT ||
                                    corner == ResizeCorner.BOTTOM_LEFT ||
                                    corner == ResizeCorner.BOTTOM_RIGHT
                                onResizeLayerCorner(layer.id, corner, dx, dy, if (isCorner) layer.lockAspectRatio else false)
                            },
                            onDragFinished = {
                                isActivelyDraggingLayer = false
                                onDragFinished()
                            },
                            onReconnectPeer = onReconnectPeer,
                            bypassGraphicAdjustment = beforePreviewLayerId == layer.id,
                            cropModeActive = cropModeActive && workspace.selectedLayerId == layer.id,
                            allowWebPageInteraction = webInteractLayerId == layer.id && layer.type == LayerSourceType.BROWSER_WEB
                        )
                    }
                }

                // Selectable Grid Overlay (2x6 default, 4x4, or Grid Off) + Safe Area + Illuminated 1dp Center Snap Guides
                val selectedLayer = sortedLayers.firstOrNull { it.id == workspace.selectedLayerId && it.visible }
                val snapGuideState = remember(selectedLayer, isActivelyDraggingLayer, workspace.showSnapGuides) {
                    if (selectedLayer != null && isActivelyDraggingLayer && workspace.showSnapGuides) {
                        StudioWorkspaceReducer.computeCenterSnapGuides(selectedLayer)
                    } else {
                        com.example.studio.SnapGuideState()
                    }
                }
                val gridMode = if (workspace.showGrid) workspace.gridMode else CanvasGridMode.OFF
                if (gridMode != CanvasGridMode.OFF || workspace.showSafeArea || workspace.showSnapGuides) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (gridMode != CanvasGridMode.OFF && gridMode.cols > 0 && gridMode.rows > 0) {
                            val gridColor = Color.White.copy(alpha = 0.14f)
                            for (c in 1 until gridMode.cols) {
                                val x = size.width * (c.toFloat() / gridMode.cols)
                                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                            }
                            for (r in 1 until gridMode.rows) {
                                val y = size.height * (r.toFloat() / gridMode.rows)
                                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                            }
                        }
                        if (workspace.showSnapGuides) {
                            val oneDpPx = 1.dp.toPx()
                            val vGuideColor = if (snapGuideState.snappedCenterX) ZroCobalt else ZroCobalt.copy(alpha = 0.24f)
                            val hGuideColor = if (snapGuideState.snappedCenterY) ZroCobalt else ZroCobalt.copy(alpha = 0.24f)
                            drawLine(
                                vGuideColor,
                                Offset(size.width / 2f, 0f),
                                Offset(size.width / 2f, size.height),
                                strokeWidth = if (snapGuideState.snappedCenterX) oneDpPx * 1.35f else oneDpPx
                            )
                            drawLine(
                                hGuideColor,
                                Offset(0f, size.height / 2f),
                                Offset(size.width, size.height / 2f),
                                strokeWidth = if (snapGuideState.snappedCenterY) oneDpPx * 1.35f else oneDpPx
                            )
                        }
                        if (workspace.showSafeArea) {
                            val insetX = size.width * 0.05f
                            val insetY = size.height * 0.05f
                            drawRect(
                                color = ZroAmber.copy(alpha = 0.55f),
                                topLeft = Offset(insetX, insetY),
                                size = Size(size.width - insetX * 2f, size.height - insetY * 2f),
                                style = Stroke(width = 1.5f)
                            )
                        }
                    }
                }

                // Top-most OBS Selection Box & 8 Draggable Transform Handles OR 4-Edge Crop Handles
                // Completely hidden when deselected (selectedLayerId == null) or when interacting directly inside a WebView
                val webInteractingSelected = selectedLayer != null &&
                    selectedLayer.type == LayerSourceType.BROWSER_WEB &&
                    webInteractLayerId == selectedLayer.id
                if (interactive && selectedLayer != null && !scene.sceneLocked && !selectedLayer.locked && !webInteractingSelected) {
                    if (cropModeActive) {
                        SelectedLayerCropOverlay(
                            layer = selectedLayer,
                            canvasWidthPx = canvasWidthPx,
                            canvasHeightPx = canvasHeightPx,
                            onCropEdge = { edge, delta ->
                                onCropLayerEdge(selectedLayer.id, edge, delta)
                            },
                            onDragFinished = onDragFinished
                        )
                    } else {
                        SelectedLayerHandlesOverlay(
                            layer = selectedLayer,
                            canvasWidthPx = canvasWidthPx,
                            canvasHeightPx = canvasHeightPx,
                            onResizeCorner = { corner, dx, dy ->
                                val isCorner = corner == ResizeCorner.TOP_LEFT ||
                                    corner == ResizeCorner.TOP_RIGHT ||
                                    corner == ResizeCorner.BOTTOM_LEFT ||
                                    corner == ResizeCorner.BOTTOM_RIGHT
                                onResizeLayerCorner(
                                    selectedLayer.id,
                                    corner,
                                    dx,
                                    dy,
                                    if (isCorner) selectedLayer.lockAspectRatio else false
                                )
                            },
                            onDragFinished = onDragFinished
                        )
                    }
                }

                // Compact top-left Scene & Grid status pill
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xCC0C0E12),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (scene.sceneLocked) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = "Scene Locked",
                                tint = ZroAmber,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                        Text(
                            text = "${scene.name} • ${if (scene.isVertical) "9:16 (1080×1920)" else "16:9 (1920×1080)"}" +
                                if (gridMode != CanvasGridMode.OFF) " • ${gridMode.label}" else "",
                            fontSize = 9.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = Color(0xFFD1D5DB)
                        )
                    }
                }

                if (beforePreviewLayerId != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .testTag("layer_before_preview_badge"),
                        shape = RoundedCornerShape(4.dp),
                        color = ZroAmber
                    ) {
                        Text(
                            text = "BEFORE (ORIGINAL)",
                            fontSize = 9.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioLayerNode(
    layer: SceneLayer,
    selected: Boolean,
    sceneLocked: Boolean,
    interactive: Boolean,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    mirroredFrame: Bitmap?,
    localCapturedFrame: Bitmap? = null,
    isAutoSoloFallback: Boolean = false,
    dualTelemetry: DualPhoneTelemetry,
    cameraSourceMode: CameraSourceMode,
    useFrontCamera: Boolean,
    cameraPermissionGranted: Boolean,
    enableLiveCameraPreview: Boolean,
    audioMeters: AudioMeterState,
    chatMessages: List<YouTubeChatMessage>,
    pinnedChatAuthor: String?,
    pinnedChatText: String?,
    chatTextSizeSp: Float,
    reducedMotion: Boolean,
    onSelect: () -> Unit,
    onMoveBy: (Float, Float) -> Unit,
    onMoveTo: ((Float, Float) -> Unit)? = null,
    onResizeCorner: (ResizeCorner, Float, Float) -> Unit,
    onDragFinished: () -> Unit,
    onReconnectPeer: (() -> Unit)?,
    bypassGraphicAdjustment: Boolean = false,
    cropModeActive: Boolean = false,
    allowWebPageInteraction: Boolean = false
) {
    val density = LocalDensity.current
    val xPx = (layer.x * canvasWidthPx).roundToInt()
    val yPx = (layer.y * canvasHeightPx).roundToInt()
    val wDp = with(density) { (layer.width * canvasWidthPx).toDp() }
    val hDp = with(density) { (layer.height * canvasHeightPx).toDp() }

    val canEdit = interactive && !sceneLocked && !layer.locked && !allowWebPageInteraction
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnMoveBy by rememberUpdatedState(onMoveBy)
    val latestOnMoveTo by rememberUpdatedState(onMoveTo)
    val latestLayerX by rememberUpdatedState(layer.x)
    val latestLayerY by rememberUpdatedState(layer.y)
    val latestOnResizeCorner by rememberUpdatedState(onResizeCorner)
    val latestOnDragFinished by rememberUpdatedState(onDragFinished)
    var dragRawX by remember(layer.id) { mutableFloatStateOf(layer.x) }
    var dragRawY by remember(layer.id) { mutableFloatStateOf(layer.y) }

    val animProgress = if (!reducedMotion && layer.animationEnabled && layer.animationType != LayerAnimationType.NONE) {
        val dur = (2200f / layer.animationSpeed.coerceIn(0.4f, 3f)).toInt().coerceIn(600, 5000)
        val transition = rememberInfiniteTransition(label = "layer_anim_${layer.id}")
        val v by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(dur, easing = LinearEasing), RepeatMode.Reverse),
            label = "layer_anim_val_${layer.id}"
        )
        v
    } else {
        0f
    }

    val animScale = if (layer.animationType == LayerAnimationType.PULSE) 1f + animProgress * 0.025f else 1f
    val animOffsetY = if (layer.animationType == LayerAnimationType.FLOAT) (animProgress - 0.5f) * 4f else 0f

    val layerShape = when {
        layer.type == LayerSourceType.WEBCAM -> when (layer.facecamShape) {
            FacecamShape.CIRCLE -> CircleShape
            FacecamShape.SQUARE -> RectangleShape
            FacecamShape.ROUNDED_RECT -> RoundedCornerShape(8.dp)
        }
        layer.type == LayerSourceType.GAMEPLAY_SCREEN ||
            layer.type == LayerSourceType.BACKGROUND ||
            layer.type == LayerSourceType.BRB_BANNER ||
            layer.type == LayerSourceType.ENDING_BANNER -> RectangleShape
        else -> RoundedCornerShape(4.dp)
    }

    val selectionBorder = when {
        allowWebPageInteraction -> BorderStroke(1.5.dp, ZroEmerald)
        selected && (layer.locked || sceneLocked) -> BorderStroke(1.5.dp, ZroAmber)
        selected -> BorderStroke(1.5.dp, ZroCobalt)
        layer.borderWidthDp > 0f -> BorderStroke(layer.borderWidthDp.dp, Color(layer.borderColorHex))
        else -> null
    }

    val latestRotationDeg by rememberUpdatedState(layer.rotationDeg)
    val effectiveOpacity = if (bypassGraphicAdjustment) 1.0f else layer.opacity.coerceIn(0.05f, 1f)

    Box(
        modifier = Modifier
            .offset { IntOffset(xPx, (yPx + animOffsetY).roundToInt()) }
            .size(width = wDp, height = hDp)
            .rotate(layer.rotationDeg)
            .scale(animScale)
            .alpha(effectiveOpacity)
            .applyLayerOuterEffects(
                adjustment = layer.graphicAdjustment,
                shape = layerShape,
                legacyShadowEnabled = layer.shadowEnabled,
                bypassForBeforePreview = bypassGraphicAdjustment
            )
            .testTag("canvas_layer_${layer.id}")
    ) {
        // Inner clipped layer visual surface
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(layerShape)
                .applyLayerInnerGraphicAdjustment(
                    adjustment = layer.graphicAdjustment,
                    shape = layerShape,
                    bypassForBeforePreview = bypassGraphicAdjustment
                )
                .then(if (selectionBorder != null) Modifier.border(selectionBorder, layerShape) else Modifier)
                .then(
                    if (interactive && !allowWebPageInteraction) {
                        Modifier.clickable { latestOnSelect() }
                    } else {
                        Modifier
                    }
                )
                .pointerInput(layer.id, canEdit, canvasWidthPx, canvasHeightPx) {
                    if (!canEdit) return@pointerInput
                    detectDragGestures(
                        onDragStart = {
                            dragRawX = latestLayerX
                            dragRawY = latestLayerY
                            latestOnSelect()
                        },
                        onDragEnd = { latestOnDragFinished() },
                        onDragCancel = { latestOnDragFinished() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val (canvasDxPx, canvasDyPx) = CanvasGestureMath.rotateLocalDragToCanvas(
                                localDx = dragAmount.x,
                                localDy = dragAmount.y,
                                rotationDeg = latestRotationDeg
                            )
                            val dx = canvasDxPx / canvasWidthPx
                            val dy = canvasDyPx / canvasHeightPx
                            val moveTo = latestOnMoveTo
                            if (moveTo != null) {
                                dragRawX += dx
                                dragRawY += dy
                                moveTo(dragRawX, dragRawY)
                            } else {
                                latestOnMoveBy(dx, dy)
                            }
                        }
                    )
                }
        ) {
            val hasCrop = layer.cropLeft > 0.001f || layer.cropTop > 0.001f ||
                layer.cropRight > 0.001f || layer.cropBottom > 0.001f
            // BROWSER_WEB handles fixed-viewport GPU UV clipping directly inside StudioWebSourceView
            // so the WebView internal layout is NEVER resized during crop drags (zero vertical reflow!).
            val useLayoutProjection = hasCrop && layer.type != LayerSourceType.BROWSER_WEB

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
            ) {
                Box(
                    modifier = if (useLayoutProjection) {
                        Modifier.croppedContentProjection(
                            cropLeft = layer.cropLeft,
                            cropTop = layer.cropTop,
                            cropRight = layer.cropRight,
                            cropBottom = layer.cropBottom
                        )
                    } else {
                        Modifier.fillMaxSize()
                    }
                ) {
                    LayerContentVisual(
                        layer = layer,
                        mirroredFrame = mirroredFrame,
                        localCapturedFrame = localCapturedFrame,
                        isAutoSoloFallback = isAutoSoloFallback,
                        dualTelemetry = dualTelemetry,
                        cameraSourceMode = cameraSourceMode,
                        useFrontCamera = useFrontCamera,
                        cameraPermissionGranted = cameraPermissionGranted,
                        enableLiveCameraPreview = enableLiveCameraPreview,
                        audioMeters = audioMeters,
                        chatMessages = chatMessages,
                        pinnedChatAuthor = pinnedChatAuthor,
                        pinnedChatText = pinnedChatText,
                        chatTextSizeSp = chatTextSizeSp,
                        animProgress = animProgress,
                        reducedMotion = reducedMotion,
                        bypassGraphicAdjustment = bypassGraphicAdjustment,
                        interactiveEditing = interactive,
                        allowWebPageInteraction = allowWebPageInteraction,
                        onReconnectPeer = onReconnectPeer
                    )
                }
            }

            // Crisp amber indicator along exact visible cropped border when in Crop Mode
            if (selected && cropModeActive) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(
                        color = ZroAmber,
                        topLeft = Offset.Zero,
                        size = size,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            if (layer.locked && selected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp),
                    shape = RoundedCornerShape(3.dp),
                    color = ZroAmber
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "Locked layer",
                        tint = Color.Black,
                        modifier = Modifier
                            .padding(2.dp)
                            .size(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedLayerHandlesOverlay(
    layer: SceneLayer,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    onResizeCorner: (ResizeCorner, Float, Float) -> Unit,
    onDragFinished: () -> Unit
) {
    val density = LocalDensity.current
    val handleTouchDp = 32.dp
    val handlePx = with(density) { handleTouchDp.toPx() }
    val halfHandlePx = handlePx / 2f
    val leftPx = layer.x * canvasWidthPx
    val topPx = layer.y * canvasHeightPx
    val widthPx = (layer.width * canvasWidthPx).coerceAtLeast(1f)
    val heightPx = (layer.height * canvasHeightPx).coerceAtLeast(1f)
    val cx = leftPx + widthPx / 2f
    val cy = topPx + heightPx / 2f
    val rad = Math.toRadians(layer.rotationDeg.toDouble())
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()

    val maxOffsetX = (canvasWidthPx - handlePx).coerceAtLeast(0f)
    val maxOffsetY = (canvasHeightPx - handlePx).coerceAtLeast(0f)

    fun rotatedHandleOffset(localX: Float, localY: Float): IntOffset {
        val rx = cx + localX * cosA - localY * sinA
        val ry = cy + localX * sinA + localY * cosA
        return IntOffset(
            x = (rx - halfHandlePx).coerceIn(0f, maxOffsetX).roundToInt(),
            y = (ry - halfHandlePx).coerceIn(0f, maxOffsetY).roundToInt()
        )
    }

    val halfW = widthPx / 2f
    val halfH = heightPx / 2f
    val latestOnResizeCorner by rememberUpdatedState(onResizeCorner)
    val latestOnDragFinished by rememberUpdatedState(onDragFinished)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("selected_layer_transform_box")
    ) {
        // Exact source bounding box outline following layer rotation
        Canvas(modifier = Modifier.fillMaxSize()) {
            rotate(degrees = layer.rotationDeg, pivot = Offset(cx, cy)) {
                drawRect(
                    color = ZroCobalt,
                    topLeft = Offset(leftPx, topPx),
                    size = Size(widthPx, heightPx),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        val handles = listOf(
            Triple(ResizeCorner.TOP_LEFT, "handle_tl", rotatedHandleOffset(-halfW, -halfH)),
            Triple(ResizeCorner.TOP_RIGHT, "handle_tr", rotatedHandleOffset(halfW, -halfH)),
            Triple(ResizeCorner.BOTTOM_LEFT, "handle_bl", rotatedHandleOffset(-halfW, halfH)),
            Triple(ResizeCorner.BOTTOM_RIGHT, "handle_br", rotatedHandleOffset(halfW, halfH)),
            Triple(ResizeCorner.TOP_CENTER, "handle_tc", rotatedHandleOffset(0f, -halfH)),
            Triple(ResizeCorner.BOTTOM_CENTER, "handle_bc", rotatedHandleOffset(0f, halfH)),
            Triple(ResizeCorner.CENTER_LEFT, "handle_cl", rotatedHandleOffset(-halfW, 0f)),
            Triple(ResizeCorner.CENTER_RIGHT, "handle_cr", rotatedHandleOffset(halfW, 0f))
        )

        handles.forEach { (corner, tag, offset) ->
            TransformBoundaryHandle(
                corner = corner,
                tag = tag,
                handleSizeDp = handleTouchDp,
                modifier = Modifier.offset { offset },
                canvasWidthPx = canvasWidthPx,
                canvasHeightPx = canvasHeightPx,
                rotationDeg = layer.rotationDeg,
                onResize = { dx, dy -> latestOnResizeCorner(corner, dx, dy) },
                onEnd = { latestOnDragFinished() }
            )
        }
    }
}

@Composable
private fun TransformBoundaryHandle(
    corner: ResizeCorner,
    tag: String,
    handleSizeDp: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    rotationDeg: Float = 0f,
    onResize: (Float, Float) -> Unit,
    onEnd: () -> Unit
) {
    val latestResize by rememberUpdatedState(onResize)
    val latestEnd by rememberUpdatedState(onEnd)
    val latestRotation by rememberUpdatedState(rotationDeg)

    val isHorizontalEdge = corner == ResizeCorner.TOP_CENTER || corner == ResizeCorner.BOTTOM_CENTER
    val isVerticalEdge = corner == ResizeCorner.CENTER_LEFT || corner == ResizeCorner.CENTER_RIGHT

    // Touch target centered directly on the transform boundary (clamped cleanly at canvas edges)
    Box(
        modifier = modifier
            .size(handleSizeDp)
            .pointerInput(corner, canvasWidthPx, canvasHeightPx) {
                detectDragGestures(
                    onDragEnd = { latestEnd() },
                    onDragCancel = { latestEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val (localDxPx, localDyPx) = if (kotlin.math.abs(latestRotation) > 0.5f) {
                            val rad = Math.toRadians((-latestRotation).toDouble())
                            val c = cos(rad).toFloat()
                            val s = sin(rad).toFloat()
                            (dragAmount.x * c - dragAmount.y * s) to (dragAmount.x * s + dragAmount.y * c)
                        } else {
                            dragAmount.x to dragAmount.y
                        }
                        latestResize(localDxPx / canvasWidthPx, localDyPx / canvasHeightPx)
                    }
                )
            }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(
                    width = when {
                        isHorizontalEdge -> 16.dp
                        isVerticalEdge -> 7.dp
                        else -> 11.dp
                    },
                    height = when {
                        isHorizontalEdge -> 7.dp
                        isVerticalEdge -> 16.dp
                        else -> 11.dp
                    }
                )
                .background(Color.White, RoundedCornerShape(2.dp))
                .border(1.5.dp, ZroCobalt, RoundedCornerShape(2.dp))
        )
    }
}

object CanvasGestureMath {
    /**
     * Converts a local drag delta measured inside a rotated Compose Box back into unrotated
     * canvas screen coordinates so dragging a rotated layer always follows the user's finger.
     */
    fun rotateLocalDragToCanvas(localDx: Float, localDy: Float, rotationDeg: Float): Pair<Float, Float> {
        if (kotlin.math.abs(rotationDeg) <= 0.5f) return localDx to localDy
        val rad = Math.toRadians(rotationDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return (localDx * c - localDy * s) to (localDx * s + localDy * c)
    }
}

/**
 * Measures an uncropped child at its full virtual source size and positions it so ONLY the
 * cropped sub-rectangle `[cropLeft..1-cropRight, cropTop..1-cropBottom]` fills the parent's
 * exact visible bounding box (works identically for Compose Images, GIFs, Video TextureView, and WebView).
 */
private fun Modifier.croppedContentProjection(
    cropLeft: Float,
    cropTop: Float,
    cropRight: Float,
    cropBottom: Float
): Modifier = this.layout { measurable, constraints ->
    val visW = (1f - cropLeft - cropRight).coerceIn(StudioWorkspaceReducer.MIN_CROP_VISIBLE, 1f)
    val visH = (1f - cropTop - cropBottom).coerceIn(StudioWorkspaceReducer.MIN_CROP_VISIBLE, 1f)
    val boxW = constraints.maxWidth.coerceAtLeast(1)
    val boxH = constraints.maxHeight.coerceAtLeast(1)
    val fullW = (boxW / visW).roundToInt().coerceAtLeast(boxW)
    val fullH = (boxH / visH).roundToInt().coerceAtLeast(boxH)
    val placeable = measurable.measure(
        Constraints.fixed(fullW, fullH)
    )
    val offsetX = -(fullW * cropLeft).roundToInt()
    val offsetY = -(fullH * cropTop).roundToInt()
    layout(boxW, boxH) {
        placeable.place(offsetX, offsetY)
    }
}

@Composable
private fun SelectedLayerCropOverlay(
    layer: SceneLayer,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    onCropEdge: (CropEdge, Float) -> Unit,
    onDragFinished: () -> Unit
) {
    val density = LocalDensity.current
    val layerW = (layer.width * canvasWidthPx).coerceAtLeast(20f)
    val layerH = (layer.height * canvasHeightPx).coerceAtLeast(20f)
    val boxLeftPx = (layer.x * canvasWidthPx).roundToInt()
    val boxTopPx = (layer.y * canvasHeightPx).roundToInt()
    val croppedWDp = with(density) { layerW.toDp() }
    val croppedHDp = with(density) { layerH.toDp() }
    val latestOnCrop by rememberUpdatedState(onCropEdge)
    val latestEnd by rememberUpdatedState(onDragFinished)

    Box(
        modifier = Modifier
            .offset { IntOffset(boxLeftPx, boxTopPx) }
            .size(width = croppedWDp, height = croppedHDp)
            .border(1.5.dp, ZroAmber)
            .testTag("selected_layer_crop_overlay")
    ) {
        // Left edge crop handle
        EdgeCropHandle(
            edge = CropEdge.LEFT,
            tag = "crop_handle_left",
            modifier = Modifier.align(Alignment.CenterStart),
            layerSpanPx = layerW,
            isHorizontal = false,
            onCropDelta = { d -> latestOnCrop(CropEdge.LEFT, d) },
            onEnd = { latestEnd() }
        )
        // Right edge crop handle
        EdgeCropHandle(
            edge = CropEdge.RIGHT,
            tag = "crop_handle_right",
            modifier = Modifier.align(Alignment.CenterEnd),
            layerSpanPx = layerW,
            isHorizontal = false,
            onCropDelta = { d -> latestOnCrop(CropEdge.RIGHT, d) },
            onEnd = { latestEnd() }
        )
        // Top edge crop handle
        EdgeCropHandle(
            edge = CropEdge.TOP,
            tag = "crop_handle_top",
            modifier = Modifier.align(Alignment.TopCenter),
            layerSpanPx = layerH,
            isHorizontal = true,
            onCropDelta = { d -> latestOnCrop(CropEdge.TOP, d) },
            onEnd = { latestEnd() }
        )
        // Bottom edge crop handle
        EdgeCropHandle(
            edge = CropEdge.BOTTOM,
            tag = "crop_handle_bottom",
            modifier = Modifier.align(Alignment.BottomCenter),
            layerSpanPx = layerH,
            isHorizontal = true,
            onCropDelta = { d -> latestOnCrop(CropEdge.BOTTOM, d) },
            onEnd = { latestEnd() }
        )
    }
}

@Composable
private fun EdgeCropHandle(
    edge: CropEdge,
    tag: String,
    modifier: Modifier,
    layerSpanPx: Float,
    isHorizontal: Boolean,
    onCropDelta: (Float) -> Unit,
    onEnd: () -> Unit
) {
    val latestDelta by rememberUpdatedState(onCropDelta)
    val latestEnd by rememberUpdatedState(onEnd)

    Box(
        modifier = modifier
            .size(width = if (isHorizontal) 34.dp else 22.dp, height = if (isHorizontal) 22.dp else 34.dp)
            .pointerInput(edge, layerSpanPx) {
                detectDragGestures(
                    onDragEnd = { latestEnd() },
                    onDragCancel = { latestEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val deltaPx = if (isHorizontal) dragAmount.y else dragAmount.x
                        latestDelta(deltaPx / layerSpanPx.coerceAtLeast(1f))
                    }
                )
            }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = if (isHorizontal) 20.dp else 5.dp, height = if (isHorizontal) 5.dp else 20.dp)
                .background(ZroAmber, RoundedCornerShape(3.dp))
                .border(1.dp, Color.Black, RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun LayerContentVisual(
    layer: SceneLayer,
    mirroredFrame: Bitmap?,
    localCapturedFrame: Bitmap? = null,
    isAutoSoloFallback: Boolean = false,
    dualTelemetry: DualPhoneTelemetry,
    cameraSourceMode: CameraSourceMode,
    useFrontCamera: Boolean,
    cameraPermissionGranted: Boolean,
    enableLiveCameraPreview: Boolean,
    audioMeters: AudioMeterState,
    chatMessages: List<YouTubeChatMessage>,
    pinnedChatAuthor: String?,
    pinnedChatText: String?,
    chatTextSizeSp: Float,
    animProgress: Float,
    reducedMotion: Boolean,
    bypassGraphicAdjustment: Boolean = false,
    interactiveEditing: Boolean = true,
    allowWebPageInteraction: Boolean = false,
    onReconnectPeer: (() -> Unit)?
) {
    val bgColor = if (layer.transparentBackground) Color.Transparent else Color(layer.backgroundColorHex)
    val textColor = Color(layer.textColorHex)
    val resolvedFontFamily = when (layer.fontStyle) {
        "MONO" -> JetBrainsMonoFontFamily
        "DISPLAY" -> SpaceGroteskFontFamily
        else -> FontFamily.SansSerif
    }
    val chromaActive = !bypassGraphicAdjustment &&
        layer.graphicAdjustment.enabled &&
        layer.graphicAdjustment.chromaKey.enabled &&
        layer.type.supportsChromaKey()

    val mediaContentScale = if (layer.scaleMode == LayerScaleMode.FIT) {
        ContentScale.Fit
    } else {
        ContentScale.FillBounds
    }

    when (layer.type) {
        LayerSourceType.GAMEPLAY_SCREEN -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (chromaActive) Color.Transparent else Color(0xFF0A0D13)),
                contentAlignment = Alignment.Center
            ) {
                val isPhone2 = dualTelemetry.role == DualPhoneRole.PHONE2_STUDIO
                val isConnected = dualTelemetry.linkState == DualLinkState.CONNECTED
                // Real gameplay frame priority:
                // 1) Connected 2nd Phone mirrored feed
                // 2) Streaming phone's real MediaProjection screen capture (Solo mode OR Auto Solo fallback)
                val activeGameplayBitmap: Bitmap? = when {
                    isPhone2 && isConnected -> mirroredFrame
                    isPhone2 && !isConnected -> if (isAutoSoloFallback) localCapturedFrame else null
                    else -> mirroredFrame ?: localCapturedFrame
                }

                if (activeGameplayBitmap != null && !activeGameplayBitmap.isRecycled) {
                    val displayFrame = remember(activeGameplayBitmap, chromaActive, layer.graphicAdjustment.chromaKey) {
                        if (chromaActive) {
                            ChromaKeyProcessor.applyChromaKey(activeGameplayBitmap, layer.graphicAdjustment.chromaKey)
                        } else {
                            activeGameplayBitmap
                        }
                    }
                    Image(
                        bitmap = displayFrame.asImageBitmap(),
                        contentDescription = if (isPhone2 && isConnected) "Authorized Phone 1 Gameplay Feed" else "Live Phone Screen Capture",
                        contentScale = mediaContentScale,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (isPhone2 && !isConnected && isAutoSoloFallback) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = ZroAmber.copy(alpha = 0.88f),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                        ) {
                            Text(
                                text = "AUTO SOLO • LOCAL SCREEN",
                                fontSize = 8.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                } else if (isPhone2 && !isConnected) {
                    // STRICT PRIVACY GUARD: Never show Phone 2's own screen!
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .padding(12.dp)
                            .testTag("source_disconnected_banner")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LinkOff,
                            contentDescription = "Source disconnected",
                            tint = ZroAmber,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SOURCE DISCONNECTED",
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = ZroAmber
                        )
                        Text(
                            text = "Waiting for authorized Phone 1 gameplay stream (Phone 2 screen is never captured)",
                            fontSize = 9.sp,
                            color = Color(0xFF9CA3AF),
                            textAlign = TextAlign.Center
                        )
                        if (onReconnectPeer != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                onClick = onReconnectPeer,
                                shape = RoundedCornerShape(4.dp),
                                color = ZroCobalt
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Refresh,
                                        contentDescription = "Reconnect",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text("Reconnect", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(10.dp)
                    ) {
                        Text(
                            text = if (isPhone2 && isConnected) {
                                "${layer.name.uppercase()} • LINKED"
                            } else {
                                layer.name.ifBlank { "GAMEPLAY SOURCE" }.uppercase()
                            },
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isConnected) ZroEmerald else Color(0xFF9CA3AF),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isPhone2) "Start game capture on Phone 1 to mirror video here" else "Select an authorized game capture in Live Setup",
                            fontSize = 9.sp,
                            color = Color(0xFF6B7280),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        LayerSourceType.MERGED_GROUP -> {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val groupW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val groupH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val density = LocalDensity.current
                layer.mergedChildren.sortedBy { it.zIndex }.forEach { child ->
                    if (!child.visible) return@forEach
                    val cx = (child.x * groupW).roundToInt()
                    val cy = (child.y * groupH).roundToInt()
                    val cwDp = with(density) { (child.width * groupW).toDp() }
                    val chDp = with(density) { (child.height * groupH).toDp() }
                    val childShape = when {
                        child.type == LayerSourceType.WEBCAM -> when (child.facecamShape) {
                            FacecamShape.CIRCLE -> CircleShape
                            FacecamShape.SQUARE -> RectangleShape
                            FacecamShape.ROUNDED_RECT -> RoundedCornerShape(8.dp)
                        }
                        child.type == LayerSourceType.GAMEPLAY_SCREEN ||
                            child.type == LayerSourceType.BACKGROUND -> RectangleShape
                        else -> RoundedCornerShape(4.dp)
                    }
                    val childHasCrop = child.cropLeft > 0.001f || child.cropTop > 0.001f ||
                        child.cropRight > 0.001f || child.cropBottom > 0.001f

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(cx, cy) }
                            .size(width = cwDp, height = chDp)
                            .rotate(child.rotationDeg)
                            .alpha(child.opacity.coerceIn(0.05f, 1f))
                            .applyLayerOuterEffects(
                                adjustment = child.graphicAdjustment,
                                shape = childShape,
                                legacyShadowEnabled = child.shadowEnabled,
                                bypassForBeforePreview = false
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(childShape)
                                .clipToBounds()
                                .applyLayerInnerGraphicAdjustment(
                                    adjustment = child.graphicAdjustment,
                                    shape = childShape,
                                    bypassForBeforePreview = false
                                )
                        ) {
                            Box(
                                modifier = if (childHasCrop) {
                                    Modifier.croppedContentProjection(
                                        cropLeft = child.cropLeft,
                                        cropTop = child.cropTop,
                                        cropRight = child.cropRight,
                                        cropBottom = child.cropBottom
                                    )
                                } else {
                                    Modifier.fillMaxSize()
                                }
                            ) {
                                LayerContentVisual(
                                    layer = child,
                                    mirroredFrame = mirroredFrame,
                                    localCapturedFrame = localCapturedFrame,
                                    isAutoSoloFallback = isAutoSoloFallback,
                                    dualTelemetry = dualTelemetry,
                                    cameraSourceMode = cameraSourceMode,
                                    useFrontCamera = useFrontCamera,
                                    cameraPermissionGranted = cameraPermissionGranted,
                                    enableLiveCameraPreview = enableLiveCameraPreview,
                                    audioMeters = audioMeters,
                                    chatMessages = chatMessages,
                                    pinnedChatAuthor = pinnedChatAuthor,
                                    pinnedChatText = pinnedChatText,
                                    chatTextSizeSp = chatTextSizeSp,
                                    animProgress = animProgress,
                                    reducedMotion = reducedMotion,
                                    onReconnectPeer = onReconnectPeer
                                )
                            }
                        }
                    }
                }
            }
        }

        LayerSourceType.WEBCAM -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (chromaActive) Color.Transparent else Color(0xFF141821)),
                contentAlignment = Alignment.Center
            ) {
                val isPreviewableCamera = cameraSourceMode == CameraSourceMode.PHONE2_FRONT ||
                    cameraSourceMode == CameraSourceMode.PHONE2_REAR ||
                    cameraSourceMode == CameraSourceMode.DJI_USB_UVC ||
                    cameraSourceMode == CameraSourceMode.EXTERNAL_UVC
                val frontCam = cameraSourceMode == CameraSourceMode.PHONE2_FRONT ||
                    (cameraSourceMode != CameraSourceMode.PHONE2_REAR && useFrontCamera)
                if (enableLiveCameraPreview && cameraPermissionGranted && isPreviewableCamera) {
                    if (chromaActive) {
                        CameraXChromaFacecamView(
                            useFront = frontCam,
                            chromaSettings = layer.graphicAdjustment.chromaKey,
                            scaleMode = layer.scaleMode,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CameraXFacecamPreview(
                            useFront = frontCam,
                            cameraSourceMode = cameraSourceMode,
                            scaleMode = layer.scaleMode,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Icon(
                            imageVector = if (cameraPermissionGranted) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                            contentDescription = "Facecam",
                            tint = if (cameraPermissionGranted) ZroEmerald else ZroAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = when (cameraSourceMode) {
                                CameraSourceMode.PHONE2_FRONT -> "FRONT CAM"
                                CameraSourceMode.PHONE2_REAR -> "REAR CAM"
                                CameraSourceMode.DJI_USB_UVC -> "DJI USB/UVC"
                                CameraSourceMode.EXTERNAL_UVC -> "HDMI / UVC"
                                CameraSourceMode.DJI_WIRELESS -> "DJI WIRELESS"
                                CameraSourceMode.PHONE1_REMOTE -> "PHONE 1 CAM"
                            },
                            fontSize = 8.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFD1D5DB),
                            maxLines = 1
                        )
                    }
                }
            }
        }

        LayerSourceType.IMAGE, LayerSourceType.LOGO, LayerSourceType.GIF -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (chromaActive) Color.Transparent else bgColor),
                contentAlignment = Alignment.Center
            ) {
                if (!layer.mediaUri.isNullOrBlank()) {
                    StudioImageLayerView(
                        layer = layer,
                        contentScale = mediaContentScale,
                        bypassGraphicAdjustment = bypassGraphicAdjustment,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = layer.textContent.ifBlank { layer.name },
                        color = textColor,
                        fontSize = (layer.fontSizeSp * 0.72f).coerceIn(8f, 20f).sp,
                        fontFamily = resolvedFontFamily,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }

        LayerSourceType.VIDEO_OVERLAY -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (chromaActive) Color.Transparent else Color(0xCC11141B)),
                contentAlignment = Alignment.Center
            ) {
                if (!layer.mediaUri.isNullOrBlank()) {
                    StudioVideoLayerView(
                        layer = layer,
                        bypassGraphicAdjustment = bypassGraphicAdjustment,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = ZroCobalt, modifier = Modifier.size(18.dp))
                        Text(
                            text = layer.name,
                            fontSize = 8.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        LayerSourceType.BROWSER_WEB -> {
            StudioWebSourceView(
                url = layer.webUrl,
                reloadNonce = layer.webReloadNonce,
                interactiveEditing = interactiveEditing,
                allowPageInteraction = allowWebPageInteraction,
                layerId = layer.id,
                viewportPreset = layer.webViewportPreset,
                cropLeft = layer.cropLeft,
                cropTop = layer.cropTop,
                cropRight = layer.cropRight,
                cropBottom = layer.cropBottom,
                chromaKey = if (bypassGraphicAdjustment || !layer.graphicAdjustment.enabled) {
                    com.example.studio.LayerChromaKeySettings.DEFAULT
                } else {
                    layer.graphicAdjustment.chromaKey
                },
                opacity = if (bypassGraphicAdjustment) 1.0f else layer.opacity,
                modifier = Modifier.fillMaxSize()
            )
        }

        LayerSourceType.SUBSCRIBER_COUNTER -> {
            if (layer.subUseExternalWidget && layer.subWidgetUrl.startsWith("https://")) {
                StudioWebSourceView(
                    url = layer.subWidgetUrl,
                    reloadNonce = layer.webReloadNonce,
                    interactiveEditing = interactiveEditing,
                    allowPageInteraction = allowWebPageInteraction,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val pct = if (layer.subGoal > 0) (layer.subCount.toFloat() / layer.subGoal.toFloat()).coerceIn(0f, 1f) else 0f
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = layer.subTitle.ifBlank { "SUBS" },
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF9CA3AF),
                            maxLines = 1
                        )
                        Text(
                            text = "${layer.subCount}/${layer.subGoal}",
                            fontSize = 9.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = ZroEmerald,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(pct)
                                .height(3.dp)
                                .background(ZroEmerald)
                        )
                    }
                }
            }
        }

        LayerSourceType.CHAT_OVERLAY -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (!pinnedChatText.isNullOrBlank()) {
                    Text(
                        text = "PIN ${pinnedChatAuthor.orEmpty()}: $pinnedChatText",
                        fontSize = (chatTextSizeSp * 0.62f).coerceIn(7f, 11f).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ZroAmber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (chatMessages.isEmpty()) {
                    Text(
                        text = "Live Chat Overlay",
                        fontSize = 8.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = Color(0xFF9CA3AF)
                    )
                } else {
                    chatMessages.takeLast(4).forEach { msg ->
                        Text(
                            text = "${msg.authorName}: ${msg.messageText}",
                            fontSize = (chatTextSizeSp * 0.65f).coerceIn(7f, 12f).sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        LayerSourceType.AUDIO_VISUALIZER -> {
            val level = audioMeters.micLevel.coerceIn(0f, 1f)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(horizontal = 5.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                val weights = listOf(0.55f, 0.85f, 1.0f, 0.72f, 0.92f, 0.62f, 0.78f, 0.50f)
                weights.forEach { w ->
                    val frac = (level * w).coerceIn(0.08f, 1f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(frac)
                            .background(if (frac > 0.85f) ZroLiveRed else ZroEmerald, RoundedCornerShape(1.dp))
                    )
                }
            }
        }

        LayerSourceType.BACKGROUND -> {
            Box(modifier = Modifier.fillMaxSize().background(if (chromaActive) Color.Transparent else bgColor)) {
                if (!layer.mediaUri.isNullOrBlank()) {
                    StudioImageLayerView(
                        layer = layer,
                        contentScale = mediaContentScale,
                        bypassGraphicAdjustment = bypassGraphicAdjustment,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (layer.motionBackgroundEnabled) {
                    BroadcastMotionBackdrop(
                        baseColor = bgColor,
                        accentColor = Color(layer.borderColorHex),
                        progress = if (reducedMotion) 0.25f else animProgress
                    )
                }
            }
        }

        LayerSourceType.BRB_BANNER, LayerSourceType.ENDING_BANNER, LayerSourceType.STARTING_SOON -> {
            val accentColor = Color(layer.borderColorHex)
            val bundledBackdropRes = when (layer.type) {
                LayerSourceType.STARTING_SOON -> R.drawable.img_scene_starting_soon
                LayerSourceType.BRB_BANNER -> R.drawable.img_scene_brb_gaming_1795732558862
                LayerSourceType.ENDING_BANNER -> R.drawable.img_scene_ending_gaming_1795732560448
                else -> R.drawable.img_scene_starting_soon
            }
            Box(modifier = Modifier.fillMaxSize()) {
                if (!layer.mediaUri.isNullOrBlank()) {
                    StudioImageLayerView(
                        layer = layer,
                        contentScale = ContentScale.Crop,
                        bypassGraphicAdjustment = bypassGraphicAdjustment,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x78080B14))
                    )
                } else if (!layer.transparentBackground) {
                    Image(
                        painter = painterResource(id = bundledBackdropRes),
                        contentDescription = layer.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x55060912),
                                        Color(0x88060912),
                                        Color(0xA8060912)
                                    )
                                )
                            )
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(2.5.dp)
                            .background(accentColor, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        text = layer.textContent.ifBlank { layer.name },
                        color = textColor,
                        fontSize = (layer.fontSizeSp * 0.78f).coerceIn(11f, 26f).sp,
                        fontFamily = resolvedFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.4.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (layer.subtitleText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            text = layer.subtitleText,
                            color = Color(0xFFD6DEEC),
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.SansSerif,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        LayerSourceType.DECORATIVE_FRAME -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = layer.borderWidthDp.coerceAtLeast(1.5f).dp,
                        color = Color(layer.borderColorHex),
                        shape = RoundedCornerShape(4.dp)
                    )
            )
        }

        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = layer.textContent.ifBlank { layer.name },
                    color = textColor,
                    fontSize = (layer.fontSizeSp * 0.72f).coerceIn(8f, 20f).sp,
                    fontFamily = resolvedFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BroadcastMotionBackdrop(
    baseColor: Color,
    accentColor: Color,
    progress: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(baseColor, Color(0xFF151923), baseColor),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
        )
        // Subtle dark broadcast diagonal scan lines moving smoothly
        val step = 28f
        val shift = progress * step
        val lineColor = accentColor.copy(alpha = 0.09f)
        var x = -size.height + shift
        while (x < size.width + size.height) {
            drawLine(
                color = lineColor,
                start = Offset(x, 0f),
                end = Offset(x + size.height * 0.45f, size.height),
                strokeWidth = 1.2f
            )
            x += step
        }
        // Clean horizontal accent hairline near bottom
        val sweepX = size.width * progress
        drawLine(
            color = accentColor.copy(alpha = 0.35f),
            start = Offset((sweepX - size.width * 0.18f).coerceAtLeast(0f), size.height * 0.88f),
            end = Offset((sweepX + size.width * 0.18f).coerceAtMost(size.width), size.height * 0.88f),
            strokeWidth = 2f
        )
    }
}

/**
 * Real CameraX PreviewView used ONLY when the user explicitly toggles Camera ON
 * and has granted runtime CAMERA permission. Supports Front, Rear, and External USB/UVC
 * (including DJI Osmo Action/Pocket UVC webcam mode and HDMI-to-USB UVC capture cards).
 * Unbinds cleanly when turned off or disposed.
 */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalLensFacing::class)
@Composable
fun CameraXFacecamPreview(
    useFront: Boolean,
    modifier: Modifier = Modifier,
    cameraSourceMode: CameraSourceMode = if (useFront) CameraSourceMode.PHONE2_FRONT else CameraSourceMode.PHONE2_REAR,
    scaleMode: LayerScaleMode = LayerScaleMode.STRETCH
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = androidx.compose.ui.platform.LocalContext.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = if (scaleMode == LayerScaleMode.FIT) PreviewView.ScaleType.FIT_CENTER else PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { previewView ->
            previewView.scaleType = if (scaleMode == LayerScaleMode.FIT) {
                PreviewView.ScaleType.FIT_CENTER
            } else {
                PreviewView.ScaleType.FILL_CENTER
            }
            val ctx = previewView.context
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                runCatching {
                    val cameraProvider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val wantExternal = cameraSourceMode == CameraSourceMode.DJI_USB_UVC ||
                        cameraSourceMode == CameraSourceMode.EXTERNAL_UVC
                    val selector = if (wantExternal) {
                        CameraSelector.Builder()
                            .addCameraFilter { cameras ->
                                val external = cameras.filter { cam ->
                                    cam.lensFacing == CameraSelector.LENS_FACING_EXTERNAL
                                }
                                if (external.isNotEmpty()) external else cameras.take(1)
                            }
                            .build()
                    } else if (cameraSourceMode == CameraSourceMode.PHONE2_FRONT || useFront) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    cameraProvider.unbindAll()
                    if (cameraProvider.hasCamera(selector)) {
                        cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview)
                    } else {
                        val fallback = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                        if (cameraProvider.hasCamera(fallback)) {
                            cameraProvider.bindToLifecycle(lifecycleOwner, fallback, preview)
                        }
                    }
                }
            }, ContextCompat.getMainExecutor(ctx))
        }
    )

    DisposableEffect(lifecycleOwner, useFront, cameraSourceMode) {
        onDispose {
            runCatching {
                val future = ProcessCameraProvider.getInstance(context)
                if (future.isDone) {
                    future.get().unbindAll()
                }
            }
        }
    }
}

/**
 * Full-screen YouTube-standard 16:9 broadcast output preview dialog.
 * Allows checking exact final live output (layers, chroma key, facecam, borders, safe area)
 * before or during a live broadcast.
 */
@Composable
fun FullScreen16x9PreviewDialog(
    workspace: StudioWorkspaceState,
    mirroredFrame: Bitmap?,
    localCapturedFrame: Bitmap? = null,
    audioMeters: AudioMeterState,
    chatMessages: List<YouTubeChatMessage>,
    dualTelemetry: DualPhoneTelemetry,
    cameraPermissionGranted: Boolean,
    resolutionLabel: String,
    fps: Int,
    statusLabel: String,
    sourceLabel: String,
    isLive: Boolean,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF05070B))
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .testTag("fullscreen_preview_dialog"),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Broadcast HUD bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isLive) ZroLiveRed else Color(0xFF1F2430)
                    ) {
                        Text(
                            text = statusLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "YouTube 16:9 Live Output Preview",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "$resolutionLabel • ${fps} FPS • $sourceLabel",
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("fullscreen_preview_close_btn")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FullscreenExit,
                        contentDescription = "Exit full-screen preview",
                        tint = Color.White
                    )
                }
            }

            // Full-bleed 16:9 YouTube Output Canvas (zero grid/editing chrome)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val cleanPreviewWorkspace = remember(workspace) {
                    workspace.copy(
                        showGrid = false,
                        showSnapGuides = false,
                        showSafeArea = false,
                        canvasZoom = 1f,
                        selectedLayerId = null
                    )
                }
                val previewAspect = if (workspace.activeScene.isVertical) 9f / 16f else 16f / 9f
                StudioCanvasPreview(
                    workspace = cleanPreviewWorkspace,
                    mirroredFrame = mirroredFrame,
                    localCapturedFrame = localCapturedFrame,
                    audioMeters = audioMeters,
                    chatMessages = chatMessages,
                    reducedMotion = false,
                    dualTelemetry = dualTelemetry,
                    cameraPermissionGranted = cameraPermissionGranted,
                    enableLiveCameraPreview = workspace.cameraEnabled && cameraPermissionGranted,
                    interactive = false,
                    modifier = if (workspace.activeScene.isVertical) {
                        Modifier.aspectRatio(previewAspect)
                    } else {
                        Modifier.fillMaxWidth().aspectRatio(previewAspect)
                    }
                )
            }

            // Bottom scene readout bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Scene: ${workspace.activeScene.name} (${workspace.activeScene.layers.count { it.visible }} active layers)",
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = Color(0xFF9CA3AF)
                )
                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1A202E),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Text("Close Preview", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
