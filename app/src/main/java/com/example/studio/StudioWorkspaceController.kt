package com.example.studio

import kotlin.math.abs

/**
 * Pure, immutable state reducer for OBS-style 4-Scene Studio, Real 4-Corner Handle Resizing,
 * Strict Layer/Scene Lock Protection, One-Touch BRB Privacy Switching, and Remote Deck Editing.
 */
object StudioWorkspaceReducer {

    private const val SNAP_THRESHOLD = 0.022f
    private const val MIN_WIDTH = 0.08f
    private const val MIN_HEIGHT = 0.06f

    fun switchScene(state: StudioWorkspaceState, slot: SceneSlot): StudioWorkspaceState {
        val targetScene = state.scenes[slot] ?: StudioWorkspaceState.defaultSceneFor(slot)
        val exitingBrb = slot != SceneSlot.BRB_BREAK && state.brbActive
        return state.copy(
            activeSceneSlot = slot,
            selectedLayerId = targetScene.layers.lastOrNull()?.id,
            brbActive = if (slot == SceneSlot.BRB_BREAK) true else if (exitingBrb) false else state.brbActive,
            previousSceneBeforeBrb = if (exitingBrb) null else state.previousSceneBeforeBrb
        )
    }

    /**
     * One-Touch BRB / Privacy Switch:
     * Preserves the exact current gameplay scene without modifying or resetting it,
     * and switches immediately to the saved BRB / Privacy scene.
     */
    fun triggerBrbPrivacy(state: StudioWorkspaceState): StudioWorkspaceState {
        if (state.brbActive && state.activeSceneSlot == SceneSlot.BRB_BREAK) return state
        val savedPrev = if (state.activeSceneSlot != SceneSlot.BRB_BREAK) {
            state.activeSceneSlot
        } else {
            state.previousSceneBeforeBrb ?: SceneSlot.GAMEPLAY_LIVE
        }
        val brbScene = state.scenes[SceneSlot.BRB_BREAK] ?: StudioWorkspaceState.defaultSceneFor(SceneSlot.BRB_BREAK)
        return state.copy(
            activeSceneSlot = SceneSlot.BRB_BREAK,
            selectedLayerId = brbScene.layers.lastOrNull()?.id,
            brbActive = true,
            previousSceneBeforeBrb = savedPrev
        )
    }

    /**
     * Restores the exact gameplay scene that was active prior to pressing BRB / Privacy
     * without altering any saved layer coordinates, crops, or settings.
     */
    fun resumeFromBrbPrivacy(state: StudioWorkspaceState): StudioWorkspaceState {
        val targetSlot = state.previousSceneBeforeBrb ?: SceneSlot.GAMEPLAY_LIVE
        val targetScene = state.scenes[targetSlot] ?: StudioWorkspaceState.defaultSceneFor(targetSlot)
        return state.copy(
            activeSceneSlot = targetSlot,
            selectedLayerId = targetScene.layers.lastOrNull()?.id,
            brbActive = false,
            previousSceneBeforeBrb = null
        )
    }

    fun renameActiveScene(state: StudioWorkspaceState, newName: String): StudioWorkspaceState {
        val clean = newName.trim().take(36)
        if (clean.isBlank()) return state
        return updateActiveScene(state) { it.copy(name = clean) }
    }

    fun toggleActiveSceneVertical(state: StudioWorkspaceState): StudioWorkspaceState {
        if (state.activeScene.sceneLocked) return state
        return updateActiveScene(state) { it.copy(isVertical = !it.isVertical) }
    }

    fun toggleSceneLock(state: StudioWorkspaceState): StudioWorkspaceState {
        return updateActiveScene(state) { it.copy(sceneLocked = !it.sceneLocked) }
    }

    fun updateSceneTransition(
        state: StudioWorkspaceState,
        type: SceneTransitionType,
        durationMs: Int
    ): StudioWorkspaceState {
        return updateActiveScene(state) {
            it.copy(
                transitionType = type,
                transitionDurationMs = durationMs.coerceIn(0, 1200)
            )
        }
    }

    fun duplicateActiveSceneTo(state: StudioWorkspaceState, targetSlot: SceneSlot): StudioWorkspaceState {
        if (targetSlot == state.activeSceneSlot) return state
        val src = state.activeScene
        val copiedLayers = src.layers.mapIndexed { idx, layer ->
            layer.copy(id = "copy_${targetSlot.index}_${idx}_${System.nanoTime().toString().takeLast(5)}")
        }
        val cloned = src.copy(
            slot = targetSlot,
            name = "${src.name.take(24)} Copy",
            layers = copiedLayers
        )
        return state.copy(
            scenes = state.scenes + (targetSlot to cloned),
            activeSceneSlot = targetSlot,
            selectedLayerId = copiedLayers.lastOrNull()?.id
        )
    }

    fun resetActiveSceneToDefault(
        state: StudioWorkspaceState,
        verticalDefault: Boolean = state.activeScene.isVertical
    ): StudioWorkspaceState {
        val fresh = StudioWorkspaceState.defaultSceneFor(state.activeSceneSlot, verticalDefault = verticalDefault)
        return state.copy(
            scenes = state.scenes + (state.activeSceneSlot to fresh),
            selectedLayerId = fresh.layers.lastOrNull()?.id
        )
    }

    fun selectLayer(state: StudioWorkspaceState, layerId: String?): StudioWorkspaceState {
        return state.copy(selectedLayerId = layerId)
    }

    fun toggleLayerLock(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        return updateActiveScene(state) { scene ->
            scene.copy(
                layers = scene.layers.map { l ->
                    if (l.id == layerId) l.copy(locked = !l.locked) else l
                }
            )
        }
    }

    fun toggleLayerVisibility(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        return updateActiveScene(state) { scene ->
            scene.copy(
                layers = scene.layers.map { l ->
                    if (l.id == layerId) l.copy(visible = !l.visible) else l
                }
            )
        }
    }

    fun addLayer(state: StudioWorkspaceState, layer: SceneLayer): StudioWorkspaceState {
        val scene = state.activeScene
        if (scene.sceneLocked) return state
        val nextZ = (scene.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
        val indexed = layer.copy(zIndex = nextZ)
        val updated = scene.copy(layers = scene.layers + indexed)
        return state.copy(
            scenes = state.scenes + (state.activeSceneSlot to updated),
            selectedLayerId = indexed.id
        )
    }

    fun updateLayer(
        state: StudioWorkspaceState,
        layerId: String,
        transform: (SceneLayer) -> SceneLayer
    ): StudioWorkspaceState {
        val scene = state.activeScene
        if (scene.sceneLocked) return state
        val target = scene.layers.firstOrNull { it.id == layerId } ?: return state
        // STRICT LOCK PROTECTION: locked layers cannot be moved, resized, cropped, rotated, or edited
        if (target.locked) return state
        val updatedLayer = transform(target).let { clampLayerBounds(it) }
        return updateActiveScene(state) { s ->
            s.copy(layers = s.layers.map { if (it.id == layerId) updatedLayer else it })
        }
    }

    fun moveLayerBy(
        state: StudioWorkspaceState,
        layerId: String,
        dxNormalized: Float,
        dyNormalized: Float
    ): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            val maxX = (1f - layer.width).coerceAtLeast(0f)
            val maxY = (1f - layer.height).coerceAtLeast(0f)
            val nx = (layer.x + dxNormalized).coerceIn(0f, maxX)
            val ny = (layer.y + dyNormalized).coerceIn(0f, maxY)
            layer.copy(x = nx, y = ny)
        }
    }

    /**
     * Smooth OBS-style positioning from a continuous raw drag anchor.
     * Strictly clamps within the 16:9 canvas bounds [0..1-w, 0..1-h] so layers never disappear
     * off-canvas or get trapped in a corner.
     */
    fun moveLayerTo(
        state: StudioWorkspaceState,
        layerId: String,
        rawX: Float,
        rawY: Float
    ): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            val maxX = (1f - layer.width).coerceAtLeast(0f)
            val maxY = (1f - layer.height).coerceAtLeast(0f)
            var nx = rawX.coerceIn(0f, maxX)
            var ny = rawY.coerceIn(0f, maxY)
            if (state.showSnapGuides) {
                nx = snapCoordinate(nx, layer.width, state.gridMode.cols).coerceIn(0f, maxX)
                ny = snapCoordinate(ny, layer.height, state.gridMode.rows).coerceIn(0f, maxY)
            }
            layer.copy(x = nx, y = ny)
        }
    }

    /**
     * Real OBS-style 8-point transform handle resizing (4 corners + 4 edge midpoints).
     * - Anchors the opposite corner or edge strictly within canvas bounds [0..1]
     * - Supports true proportional scaling across ALL 8 handles when aspect lock is active
     * - Prevents layers from escaping canvas boundaries or getting stuck in corners
     */
    fun resizeLayerFromCorner(
        state: StudioWorkspaceState,
        layerId: String,
        corner: ResizeCorner,
        dxNorm: Float,
        dyNorm: Float,
        proportional: Boolean = false
    ): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            val left = layer.x.coerceIn(0f, 1f - MIN_WIDTH)
            val top = layer.y.coerceIn(0f, 1f - MIN_HEIGHT)
            val right = (left + layer.width).coerceIn(left + MIN_WIDTH, 1.0f)
            val bottom = (top + layer.height).coerceIn(top + MIN_HEIGHT, 1.0f)
            val origW = (right - left).coerceAtLeast(MIN_WIDTH)
            val origH = (bottom - top).coerceAtLeast(MIN_HEIGHT)
            val aspect = (origW / origH).coerceIn(0.1f, 10f)
            val useProportional = proportional || layer.lockAspectRatio

            var newLeft = left
            var newTop = top
            var newRight = right
            var newBottom = bottom

            fun fitProportional(desiredW: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
                val safeMaxW = maxW.coerceAtLeast(MIN_WIDTH)
                val safeMaxH = maxH.coerceAtLeast(MIN_HEIGHT)
                val upperW = minOf(safeMaxW, safeMaxH * aspect).coerceAtLeast(MIN_WIDTH)
                val lowerW = maxOf(MIN_WIDTH, MIN_HEIGHT * aspect).coerceAtMost(upperW)
                var w = desiredW.coerceIn(lowerW, upperW)
                var h = w / aspect
                if (h > safeMaxH) {
                    h = safeMaxH
                    w = (h * aspect).coerceIn(MIN_WIDTH, safeMaxW)
                } else if (h < MIN_HEIGHT) {
                    h = MIN_HEIGHT
                    w = (h * aspect).coerceIn(MIN_WIDTH, safeMaxW)
                }
                return w to h
            }

            fun chooseProportionalWidth(rawW: Float, rawH: Float): Float {
                val relW = abs((rawW - origW) / origW)
                val relH = abs((rawH - origH) / origH)
                return if (relW >= relH) rawW else rawH * aspect
            }

            when (corner) {
                ResizeCorner.TOP_LEFT -> {
                    val rawW = (right - (left + dxNorm))
                    val rawH = (bottom - (top + dyNorm))
                    if (useProportional) {
                        val (w, h) = fitProportional(chooseProportionalWidth(rawW, rawH), maxW = right, maxH = bottom)
                        newLeft = right - w
                        newTop = bottom - h
                    } else {
                        newLeft = (left + dxNorm).coerceIn(0f, right - MIN_WIDTH)
                        newTop = (top + dyNorm).coerceIn(0f, bottom - MIN_HEIGHT)
                    }
                }
                ResizeCorner.TOP_RIGHT -> {
                    val rawW = (right + dxNorm) - left
                    val rawH = bottom - (top + dyNorm)
                    if (useProportional) {
                        val (w, h) = fitProportional(chooseProportionalWidth(rawW, rawH), maxW = 1f - left, maxH = bottom)
                        newRight = left + w
                        newTop = bottom - h
                    } else {
                        newRight = (right + dxNorm).coerceIn(left + MIN_WIDTH, 1.0f)
                        newTop = (top + dyNorm).coerceIn(0f, bottom - MIN_HEIGHT)
                    }
                }
                ResizeCorner.BOTTOM_LEFT -> {
                    val rawW = right - (left + dxNorm)
                    val rawH = (bottom + dyNorm) - top
                    if (useProportional) {
                        val (w, h) = fitProportional(chooseProportionalWidth(rawW, rawH), maxW = right, maxH = 1f - top)
                        newLeft = right - w
                        newBottom = top + h
                    } else {
                        newLeft = (left + dxNorm).coerceIn(0f, right - MIN_WIDTH)
                        newBottom = (bottom + dyNorm).coerceIn(top + MIN_HEIGHT, 1.0f)
                    }
                }
                ResizeCorner.BOTTOM_RIGHT -> {
                    val rawW = (right + dxNorm) - left
                    val rawH = (bottom + dyNorm) - top
                    if (useProportional) {
                        val (w, h) = fitProportional(chooseProportionalWidth(rawW, rawH), maxW = 1f - left, maxH = 1f - top)
                        newRight = left + w
                        newBottom = top + h
                    } else {
                        newRight = (right + dxNorm).coerceIn(left + MIN_WIDTH, 1.0f)
                        newBottom = (bottom + dyNorm).coerceIn(top + MIN_HEIGHT, 1.0f)
                    }
                }
                ResizeCorner.TOP_CENTER -> {
                    if (useProportional) {
                        val rawH = bottom - (top + dyNorm)
                        val (w, h) = fitProportional(rawH * aspect, maxW = 1f, maxH = bottom)
                        val cx = (left + right) / 2f
                        newTop = bottom - h
                        newLeft = (cx - w / 2f).coerceIn(0f, (1f - w).coerceAtLeast(0f))
                        newRight = newLeft + w
                    } else {
                        newTop = (top + dyNorm).coerceIn(0f, bottom - MIN_HEIGHT)
                    }
                }
                ResizeCorner.BOTTOM_CENTER -> {
                    if (useProportional) {
                        val rawH = (bottom + dyNorm) - top
                        val (w, h) = fitProportional(rawH * aspect, maxW = 1f, maxH = 1f - top)
                        val cx = (left + right) / 2f
                        newBottom = top + h
                        newLeft = (cx - w / 2f).coerceIn(0f, (1f - w).coerceAtLeast(0f))
                        newRight = newLeft + w
                    } else {
                        newBottom = (bottom + dyNorm).coerceIn(top + MIN_HEIGHT, 1.0f)
                    }
                }
                ResizeCorner.CENTER_LEFT -> {
                    if (useProportional) {
                        val rawW = right - (left + dxNorm)
                        val (w, h) = fitProportional(rawW, maxW = right, maxH = 1f)
                        val cy = (top + bottom) / 2f
                        newLeft = right - w
                        newTop = (cy - h / 2f).coerceIn(0f, (1f - h).coerceAtLeast(0f))
                        newBottom = newTop + h
                    } else {
                        newLeft = (left + dxNorm).coerceIn(0f, right - MIN_WIDTH)
                    }
                }
                ResizeCorner.CENTER_RIGHT -> {
                    if (useProportional) {
                        val rawW = (right + dxNorm) - left
                        val (w, h) = fitProportional(rawW, maxW = 1f - left, maxH = 1f)
                        val cy = (top + bottom) / 2f
                        newRight = left + w
                        newTop = (cy - h / 2f).coerceIn(0f, (1f - h).coerceAtLeast(0f))
                        newBottom = newTop + h
                    } else {
                        newRight = (right + dxNorm).coerceIn(left + MIN_WIDTH, 1.0f)
                    }
                }
            }

            val finalW = (newRight - newLeft).coerceIn(MIN_WIDTH, 1.0f)
            val finalH = (newBottom - newTop).coerceIn(MIN_HEIGHT, 1.0f)
            layer.copy(
                x = newLeft.coerceIn(0f, (1f - finalW).coerceAtLeast(0f)),
                y = newTop.coerceIn(0f, (1f - finalH).coerceAtLeast(0f)),
                width = finalW,
                height = finalH
            )
        }
    }

    fun resizeLayer(
        state: StudioWorkspaceState,
        layerId: String,
        newWidth: Float,
        newHeight: Float
    ): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            val widthChanged = abs(newWidth - layer.width) >= abs(newHeight - layer.height)
            val aspect = (layer.width / layer.height.coerceAtLeast(0.001f)).coerceIn(0.1f, 10f)
            val (finalW, finalH) = if (layer.lockAspectRatio) {
                if (widthChanged) {
                    val upperW = minOf(1.0f, 1.0f * aspect)
                    val lowerW = maxOf(MIN_WIDTH, MIN_HEIGHT * aspect).coerceAtMost(upperW)
                    val w = newWidth.coerceIn(lowerW, upperW)
                    val h = (w / aspect).coerceIn(MIN_HEIGHT, 1.0f)
                    w to h
                } else {
                    val upperH = minOf(1.0f, 1.0f / aspect)
                    val lowerH = maxOf(MIN_HEIGHT, MIN_WIDTH / aspect).coerceAtMost(upperH)
                    val h = newHeight.coerceIn(lowerH, upperH)
                    val w = (h * aspect).coerceIn(MIN_WIDTH, 1.0f)
                    w to h
                }
            } else {
                newWidth.coerceIn(MIN_WIDTH, 1.0f) to newHeight.coerceIn(MIN_HEIGHT, 1.0f)
            }
            val nextX = layer.x.coerceIn(0f, (1f - finalW).coerceAtLeast(0f))
            val nextY = layer.y.coerceIn(0f, (1f - finalH).coerceAtLeast(0f))
            layer.copy(x = nextX, y = nextY, width = finalW, height = finalH)
        }
    }

    /**
     * Adjusts a layer to an exact custom dimension or grid ratio (e.g., 4x4, 6x2, 16x9, 1x1, or custom WxH units)
     * computed accurately against the YouTube 16:9 (1920x1080) or 9:16 canvas without clipping or overflowing bounds.
     */
    fun applyCustomDimensionUnits(
        state: StudioWorkspaceState,
        layerId: String,
        unitsW: Float,
        unitsH: Float,
        referenceGridCols: Float = 8f,
        referenceGridRows: Float = 4.5f
    ): StudioWorkspaceState {
        val safeW = unitsW.coerceIn(0.5f, 16f)
        val safeH = unitsH.coerceIn(0.5f, 16f)
        val canvasAspect = if (state.activeScene.isVertical) 9f / 16f else 16f / 9f
        return updateLayer(state, layerId) { layer ->
            // Compute visual ratio matching unitsW : unitsH on physical canvas coordinates,
            // scaled proportionally to grid units (e.g. 4x4 -> square tile, 6x2 -> wide 3:1 banner).
            var normW = (safeW / referenceGridCols).coerceIn(MIN_WIDTH, 1.0f)
            var normH = ((normW * canvasAspect) * (safeH / safeW)).coerceIn(MIN_HEIGHT, 1.0f)
            if (normH >= 1.0f) {
                normH = 1.0f
                normW = ((normH / canvasAspect) * (safeW / safeH)).coerceIn(MIN_WIDTH, 1.0f)
            }
            val cx = layer.x + layer.width / 2f
            val cy = layer.y + layer.height / 2f
            val nextX = (cx - normW / 2f).coerceIn(0f, (1f - normW).coerceAtLeast(0f))
            val nextY = (cy - normH / 2f).coerceIn(0f, (1f - normH).coerceAtLeast(0f))
            layer.copy(x = nextX, y = nextY, width = normW, height = normH)
        }
    }

    fun centerLayerHorizontally(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            layer.copy(x = ((1f - layer.width) / 2f).coerceIn(0f, (1f - layer.width).coerceAtLeast(0f)))
        }
    }

    fun centerLayerVertically(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            layer.copy(y = ((1f - layer.height) / 2f).coerceIn(0f, (1f - layer.height).coerceAtLeast(0f)))
        }
    }

    fun alignLayerCenter(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            layer.copy(
                x = ((1f - layer.width) / 2f).coerceIn(0f, (1f - layer.width).coerceAtLeast(0f)),
                y = ((1f - layer.height) / 2f).coerceIn(0f, (1f - layer.height).coerceAtLeast(0f))
            )
        }
    }

    /**
     * Fit to 16:9 YouTube Canvas: scales layer as large as possible inside 1920x1080
     * while preserving its aspect ratio (media aspect if known, otherwise current layer visual aspect),
     * centered with zero cropping or clipping.
     */
    fun fitLayerFullCanvas(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        val canvasAspect = if (state.activeScene.isVertical) 9f / 16f else 16f / 9f
        return updateLayer(state, layerId) { layer ->
            val mediaAspect = if (layer.sourceMediaWidth > 0 && layer.sourceMediaHeight > 0) {
                layer.sourceMediaWidth.toFloat() / layer.sourceMediaHeight.toFloat()
            } else if (layer.type == LayerSourceType.GAMEPLAY_SCREEN || layer.type == LayerSourceType.BACKGROUND) {
                canvasAspect
            } else {
                ((layer.width * canvasAspect) / layer.height.coerceAtLeast(0.01f)).coerceIn(0.25f, 4.0f)
            }
            val (fitW, fitH) = if (mediaAspect >= canvasAspect) {
                1.0f to (canvasAspect / mediaAspect).coerceIn(MIN_HEIGHT, 1.0f)
            } else {
                (mediaAspect / canvasAspect).coerceIn(MIN_WIDTH, 1.0f) to 1.0f
            }
            layer.copy(
                x = ((1f - fitW) / 2f).coerceIn(0f, 1f),
                y = ((1f - fitH) / 2f).coerceIn(0f, 1f),
                width = fitW,
                height = fitH,
                rotationDeg = 0f,
                cropLeft = 0f,
                cropTop = 0f,
                cropRight = 0f,
                cropBottom = 0f
            )
        }
    }

    /**
     * Fill 16:9 YouTube Canvas edge-to-edge (100% width x 100% height at origin 0,0).
     */
    fun fillLayerCanvas(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            layer.copy(
                x = 0f,
                y = 0f,
                width = 1f,
                height = 1f,
                rotationDeg = 0f
            )
        }
    }

    fun resetLayerTransform(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            when (layer.type) {
                LayerSourceType.GAMEPLAY_SCREEN, LayerSourceType.BACKGROUND,
                LayerSourceType.STARTING_SOON, LayerSourceType.BRB_BANNER, LayerSourceType.ENDING_BANNER ->
                    layer.copy(x = 0f, y = 0f, width = 1f, height = 1f, rotationDeg = 0f, cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f, opacity = 1f, scaleMode = LayerScaleMode.STRETCH)
                LayerSourceType.WEBCAM ->
                    layer.copy(x = 0.02f, y = 0.63f, width = 0.21f, height = 0.34f, rotationDeg = 0f, cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f, opacity = 1f, scaleMode = LayerScaleMode.STRETCH)
                else ->
                    layer.copy(x = 0.25f, y = 0.25f, width = 0.50f, height = 0.35f, rotationDeg = 0f, cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f, opacity = 1f, scaleMode = LayerScaleMode.STRETCH)
            }
        }
    }

    fun moveLayerZOrder(state: StudioWorkspaceState, layerId: String, bringForward: Boolean): StudioWorkspaceState {
        val scene = state.activeScene
        if (scene.sceneLocked) return state
        val sorted = scene.layers.sortedBy { it.zIndex }.toMutableList()
        val idx = sorted.indexOfFirst { it.id == layerId }
        if (idx < 0) return state
        if (sorted[idx].locked) return state
        val swapIdx = if (bringForward) idx + 1 else idx - 1
        if (swapIdx !in sorted.indices) return state
        val item = sorted.removeAt(idx)
        sorted.add(swapIdx, item)
        val reindexed = sorted.mapIndexed { newZ, l -> l.copy(zIndex = newZ) }
        return updateActiveScene(state) { it.copy(layers = reindexed) }
    }

    /**
     * Reorders layers from a top-to-bottom Layer Manager list (index 0 = highest zIndex).
     */
    fun reorderLayerTopToBottom(
        state: StudioWorkspaceState,
        fromTopIndex: Int,
        toTopIndex: Int
    ): StudioWorkspaceState {
        val scene = state.activeScene
        if (scene.sceneLocked) return state
        val desc = scene.layers.sortedByDescending { it.zIndex }.toMutableList()
        if (fromTopIndex !in desc.indices || toTopIndex !in desc.indices || fromTopIndex == toTopIndex) {
            return state
        }
        val moved = desc.removeAt(fromTopIndex)
        desc.add(toTopIndex, moved)
        // Reassign zIndex so top of list has highest zIndex
        val asc = desc.asReversed().mapIndexed { newZ, l -> l.copy(zIndex = newZ) }
        return updateActiveScene(state) { it.copy(layers = asc) }
    }

    /**
     * Replaces a layer's underlying media or website source while preserving its exact position, size, and Z-order.
     */
    fun replaceLayerSource(
        state: StudioWorkspaceState,
        layerId: String,
        mediaUri: String? = null,
        mediaWidth: Int = 0,
        mediaHeight: Int = 0,
        isGif: Boolean = false,
        isVideo: Boolean = false,
        webUrl: String? = null
    ): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            when {
                webUrl != null -> layer.copy(
                    webUrl = webUrl,
                    webReloadNonce = layer.webReloadNonce + 1
                )
                mediaUri != null -> {
                    val nextType = when {
                        layer.type == LayerSourceType.BACKGROUND ||
                            layer.type == LayerSourceType.STARTING_SOON ||
                            layer.type == LayerSourceType.BRB_BANNER ||
                            layer.type == LayerSourceType.ENDING_BANNER ||
                            layer.type == LayerSourceType.LOGO -> layer.type
                        isVideo -> LayerSourceType.VIDEO_OVERLAY
                        isGif -> LayerSourceType.GIF
                        else -> LayerSourceType.IMAGE
                    }
                    layer.copy(
                        type = nextType,
                        mediaUri = mediaUri,
                        sourceMediaWidth = if (mediaWidth > 0) mediaWidth else layer.sourceMediaWidth,
                        sourceMediaHeight = if (mediaHeight > 0) mediaHeight else layer.sourceMediaHeight
                    )
                }
                else -> layer
            }
        }
    }

    fun duplicateLayer(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        val scene = state.activeScene
        if (scene.sceneLocked) return state
        val src = scene.layers.firstOrNull { it.id == layerId } ?: return state
        val nextZ = (scene.layers.maxOfOrNull { it.zIndex } ?: 0) + 1
        val copy = src.copy(
            id = "dup_${System.nanoTime().toString().takeLast(6)}",
            name = "${src.name.take(24)} Copy",
            locked = false,
            zIndex = nextZ,
            x = (src.x + 0.04f).coerceAtMost(0.85f),
            y = (src.y + 0.04f).coerceAtMost(0.85f)
        )
        return state.copy(
            scenes = state.scenes + (state.activeSceneSlot to scene.copy(layers = scene.layers + copy)),
            selectedLayerId = copy.id
        )
    }

    fun deleteLayer(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        val scene = state.activeScene
        if (scene.sceneLocked) return state
        val target = scene.layers.firstOrNull { it.id == layerId } ?: return state
        if (target.locked) return state
        val remaining = scene.layers.filterNot { it.id == layerId }
            .sortedBy { it.zIndex }
            .mapIndexed { idx, l -> l.copy(zIndex = idx) }
        return state.copy(
            scenes = state.scenes + (state.activeSceneSlot to scene.copy(layers = remaining)),
            selectedLayerId = remaining.lastOrNull()?.id
        )
    }

    // --- LAYER RENAMING & SCREEN CAPTURE RENAMING ---

    fun renameLayer(state: StudioWorkspaceState, layerId: String, newName: String): StudioWorkspaceState {
        val clean = newName.trim().take(36)
        if (clean.isBlank()) return state
        val scene = state.activeScene
        if (scene.sceneLocked) return state
        val target = scene.layers.firstOrNull { it.id == layerId } ?: return state
        if (target.locked) return state
        return updateActiveScene(state) { s ->
            s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(name = clean) else it })
        }
    }

    fun renameGameplayCaptureLayers(state: StudioWorkspaceState, newName: String): StudioWorkspaceState {
        val clean = newName.trim().take(36)
        if (clean.isBlank()) return state
        val updatedScenes = state.scenes.mapValues { (_, scene) ->
            scene.copy(
                layers = scene.layers.map { layer ->
                    if (layer.type == LayerSourceType.GAMEPLAY_SCREEN) {
                        layer.copy(name = clean)
                    } else {
                        layer
                    }
                }
            )
        }
        return state.copy(scenes = updatedScenes)
    }

    // --- LAYER CROPPING (4-EDGE INTERACTIVE + PRESETS + RESET) ---

    fun cropLayerEdge(
        state: StudioWorkspaceState,
        layerId: String,
        edge: CropEdge,
        deltaNorm: Float
    ): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            val maxCombined = 0.85f
            when (edge) {
                CropEdge.LEFT -> {
                    val maxLeft = (maxCombined - layer.cropRight).coerceIn(0f, 0.45f)
                    layer.copy(cropLeft = (layer.cropLeft + deltaNorm).coerceIn(0f, maxLeft))
                }
                CropEdge.RIGHT -> {
                    val maxRight = (maxCombined - layer.cropLeft).coerceIn(0f, 0.45f)
                    layer.copy(cropRight = (layer.cropRight - deltaNorm).coerceIn(0f, maxRight))
                }
                CropEdge.TOP -> {
                    val maxTop = (maxCombined - layer.cropBottom).coerceIn(0f, 0.45f)
                    layer.copy(cropTop = (layer.cropTop + deltaNorm).coerceIn(0f, maxTop))
                }
                CropEdge.BOTTOM -> {
                    val maxBottom = (maxCombined - layer.cropTop).coerceIn(0f, 0.45f)
                    layer.copy(cropBottom = (layer.cropBottom - deltaNorm).coerceIn(0f, maxBottom))
                }
            }
        }
    }

    fun setLayerCrop(
        state: StudioWorkspaceState,
        layerId: String,
        cropLeft: Float? = null,
        cropTop: Float? = null,
        cropRight: Float? = null,
        cropBottom: Float? = null
    ): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            val l = (cropLeft ?: layer.cropLeft).coerceIn(0f, 0.45f)
            val r = (cropRight ?: layer.cropRight).coerceIn(0f, (0.85f - l).coerceIn(0f, 0.45f))
            val t = (cropTop ?: layer.cropTop).coerceIn(0f, 0.45f)
            val b = (cropBottom ?: layer.cropBottom).coerceIn(0f, (0.85f - t).coerceIn(0f, 0.45f))
            layer.copy(cropLeft = l, cropTop = t, cropRight = r, cropBottom = b)
        }
    }

    fun applyCropAspectPreset(
        state: StudioWorkspaceState,
        layerId: String,
        preset: CropAspectPreset
    ): StudioWorkspaceState {
        val scene = state.activeScene
        val canvasAspect = if (scene.isVertical) 9f / 16f else 16f / 9f
        return updateLayer(state, layerId) { layer ->
            val targetRatio = preset.targetRatio
            if (targetRatio == null) {
                layer.copy(cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f)
            } else {
                val physicalW = (layer.width * canvasAspect).coerceAtLeast(0.01f)
                val physicalH = layer.height.coerceAtLeast(0.01f)
                val currentRatio = physicalW / physicalH
                if (currentRatio > targetRatio) {
                    val keepFrac = (targetRatio / currentRatio).coerceIn(0.15f, 1f)
                    val trimEach = ((1f - keepFrac) / 2f).coerceIn(0f, 0.42f)
                    layer.copy(cropLeft = trimEach, cropRight = trimEach, cropTop = 0f, cropBottom = 0f)
                } else {
                    val keepFrac = (currentRatio / targetRatio).coerceIn(0.15f, 1f)
                    val trimEach = ((1f - keepFrac) / 2f).coerceIn(0f, 0.42f)
                    layer.copy(cropLeft = 0f, cropRight = 0f, cropTop = trimEach, cropBottom = trimEach)
                }
            }
        }
    }

    fun resetLayerCrop(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            layer.copy(cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f)
        }
    }

    // --- LAYER MERGING & UNMERGING (COMPOSITE GROUP) ---

    fun mergeLayers(
        state: StudioWorkspaceState,
        layerIds: List<String>,
        mergedName: String = ""
    ): StudioWorkspaceState {
        val scene = state.activeScene
        if (scene.sceneLocked) return state
        val idSet = layerIds.toSet()
        val targets = scene.layers
            .filter { it.id in idSet && !it.locked }
            .sortedBy { it.zIndex }
        if (targets.size < 2) return state

        val minX = targets.minOf { it.x }
        val minY = targets.minOf { it.y }
        val maxX = targets.maxOf { it.x + it.width }
        val maxY = targets.maxOf { it.y + it.height }
        val unionW = (maxX - minX).coerceAtLeast(MIN_WIDTH)
        val unionH = (maxY - minY).coerceAtLeast(MIN_HEIGHT)
        val clampedW = unionW.coerceAtMost(1.0f)
        val clampedH = unionH.coerceAtMost(1.0f)

        val relativeChildren = targets.mapIndexed { idx, child ->
            val relX = ((child.x - minX) / unionW).coerceIn(0f, 0.98f)
            val relY = ((child.y - minY) / unionH).coerceIn(0f, 0.98f)
            val relW = (child.width / unionW).coerceIn(0.05f, 1.0f)
            val relH = (child.height / unionH).coerceIn(0.05f, 1.0f)
            child.copy(
                zIndex = idx,
                x = relX,
                y = relY,
                width = relW,
                height = relH,
                locked = false
            )
        }

        val resolvedName = mergedName.trim().take(36).ifBlank {
            "Merged (${targets.size} layers)"
        }
        val insertZ = targets.maxOf { it.zIndex }
        val mergedLayer = SceneLayer(
            id = "merged_${System.nanoTime().toString().takeLast(6)}",
            name = resolvedName,
            type = LayerSourceType.MERGED_GROUP,
            visible = true,
            locked = false,
            zIndex = insertZ,
            x = minX.coerceIn(0f, (1f - clampedW).coerceAtLeast(0f)),
            y = minY.coerceIn(0f, (1f - clampedH).coerceAtLeast(0f)),
            width = clampedW,
            height = clampedH,
            transparentBackground = true,
            mergedChildren = relativeChildren
        )

        val remaining = scene.layers
            .filterNot { it.id in targets.map { t -> t.id }.toSet() }
            .toMutableList()
        remaining.add(mergedLayer)
        val reindexed = remaining.sortedBy { it.zIndex }.mapIndexed { idx, l -> l.copy(zIndex = idx) }
        return state.copy(
            scenes = state.scenes + (state.activeSceneSlot to scene.copy(layers = reindexed)),
            selectedLayerId = mergedLayer.id
        )
    }

    fun unmergeLayer(
        state: StudioWorkspaceState,
        mergedLayerId: String
    ): StudioWorkspaceState {
        val scene = state.activeScene
        if (scene.sceneLocked) return state
        val parent = scene.layers.firstOrNull { it.id == mergedLayerId } ?: return state
        if (parent.locked || parent.type != LayerSourceType.MERGED_GROUP || parent.mergedChildren.isEmpty()) {
            return state
        }

        val restoredChildren = parent.mergedChildren.sortedBy { it.zIndex }.mapIndexed { idx, child ->
            val absW = (child.width * parent.width).coerceIn(MIN_WIDTH, 1.0f)
            val absH = (child.height * parent.height).coerceIn(MIN_HEIGHT, 1.0f)
            val absX = (parent.x + child.x * parent.width).coerceIn(0f, (1f - absW).coerceAtLeast(0f))
            val absY = (parent.y + child.y * parent.height).coerceIn(0f, (1f - absH).coerceAtLeast(0f))
            child.copy(
                zIndex = parent.zIndex * 10 + idx,
                x = absX,
                y = absY,
                width = absW,
                height = absH,
                visible = parent.visible && child.visible
            )
        }

        val rebuilt = scene.layers
            .flatMap { l ->
                if (l.id == mergedLayerId) restoredChildren else listOf(l.copy(zIndex = l.zIndex * 10))
            }
            .sortedBy { it.zIndex }
            .mapIndexed { idx, l -> l.copy(zIndex = idx) }

        return state.copy(
            scenes = state.scenes + (state.activeSceneSlot to scene.copy(layers = rebuilt)),
            selectedLayerId = restoredChildren.lastOrNull()?.id
        )
    }

    fun moveRemoteTile(state: StudioWorkspaceState, tileId: RemoteControlTileId, moveUp: Boolean): StudioWorkspaceState {
        val list = state.remoteTileOrder.toMutableList()
        val idx = list.indexOf(tileId)
        if (idx < 0) return state
        val swap = if (moveUp) idx - 1 else idx + 1
        if (swap !in list.indices) return state
        val item = list.removeAt(idx)
        list.add(swap, item)
        return state.copy(remoteTileOrder = list)
    }

    fun addRemoteTile(state: StudioWorkspaceState, tileId: RemoteControlTileId): StudioWorkspaceState {
        if (tileId in state.remoteTileOrder) return state
        return state.copy(remoteTileOrder = state.remoteTileOrder + tileId)
    }

    fun removeRemoteTile(state: StudioWorkspaceState, tileId: RemoteControlTileId): StudioWorkspaceState {
        if (state.remoteTileOrder.size <= 3) return state
        return state.copy(remoteTileOrder = state.remoteTileOrder.filterNot { it == tileId })
    }

    fun resetRemoteDeckToDefault(state: StudioWorkspaceState): StudioWorkspaceState {
        return state.copy(
            remoteTileOrder = RemoteControlTileId.DEFAULT_ORDER,
            remoteTileScale = 1.0f
        )
    }

    // --- LAYER-BY-LAYER GRAPHIC ADJUSTMENT REDUCERS ---

    fun updateLayerGraphicAdjustment(
        state: StudioWorkspaceState,
        layerId: String,
        transform: (LayerGraphicAdjustment) -> LayerGraphicAdjustment
    ): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            val currentAdj = layer.graphicAdjustment.copy(opacity = layer.opacity)
            val nextAdj = transform(currentAdj).clamped()
            layer.copy(
                opacity = nextAdj.opacity,
                graphicAdjustment = nextAdj
            )
        }
    }

    fun applyGraphicPresetToLayer(
        state: StudioWorkspaceState,
        layerId: String,
        preset: GraphicPresetId
    ): StudioWorkspaceState {
        return updateLayerGraphicAdjustment(state, layerId) {
            LayerGraphicAdjustment.forBuiltInPreset(preset)
        }
    }

    fun applyCustomGraphicPresetToLayer(
        state: StudioWorkspaceState,
        layerId: String,
        customPreset: CustomGraphicPreset
    ): StudioWorkspaceState {
        return updateLayerGraphicAdjustment(state, layerId) {
            customPreset.adjustment.copy(
                enabled = true,
                presetId = GraphicPresetId.CUSTOM,
                customPresetId = customPreset.id
            )
        }
    }

    fun resetLayerGraphicAdjustment(
        state: StudioWorkspaceState,
        layerId: String
    ): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            layer.copy(
                opacity = 1.0f,
                graphicAdjustment = LayerGraphicAdjustment.ORIGINAL
            )
        }
    }

    fun copyGraphicAdjustmentToLayer(
        state: StudioWorkspaceState,
        sourceLayerId: String,
        targetLayerId: String
    ): StudioWorkspaceState {
        if (sourceLayerId == targetLayerId) return state
        val scene = state.activeScene
        if (scene.sceneLocked) return state
        val src = scene.layers.firstOrNull { it.id == sourceLayerId } ?: return state
        val dst = scene.layers.firstOrNull { it.id == targetLayerId } ?: return state
        if (dst.locked) return state
        val copied = src.graphicAdjustment.copy(opacity = src.opacity).clamped()
        return updateLayer(state, targetLayerId) { layer ->
            layer.copy(
                opacity = copied.opacity,
                graphicAdjustment = copied
            )
        }
    }

    fun saveCustomGraphicPreset(
        state: StudioWorkspaceState,
        name: String,
        adjustment: LayerGraphicAdjustment
    ): StudioWorkspaceState {
        val clean = name.trim().take(28)
        if (clean.isBlank()) return state
        val clamped = adjustment.clamped()
        val existingIdx = state.customGraphicPresets.indexOfFirst { it.name.equals(clean, ignoreCase = true) }
        val updatedList = if (existingIdx >= 0) {
            val prev = state.customGraphicPresets[existingIdx]
            state.customGraphicPresets.toMutableList().apply {
                this[existingIdx] = prev.copy(name = clean, adjustment = clamped)
            }
        } else {
            (state.customGraphicPresets + CustomGraphicPreset(name = clean, adjustment = clamped)).takeLast(16)
        }
        return state.copy(customGraphicPresets = updatedList)
    }

    fun deleteCustomGraphicPreset(
        state: StudioWorkspaceState,
        presetId: String
    ): StudioWorkspaceState {
        return state.copy(
            customGraphicPresets = state.customGraphicPresets.filterNot { it.id == presetId }
        )
    }

    private fun snapCoordinate(pos: Float, size: Float, divisions: Int): Float {
        if (abs(pos) < SNAP_THRESHOLD) return 0f
        if (abs((pos + size) - 1f) < SNAP_THRESHOLD) return 1f - size
        if (abs((pos + size / 2f) - 0.5f) < SNAP_THRESHOLD) return 0.5f - size / 2f
        if (divisions > 1) {
            for (i in 1 until divisions) {
                val gridLine = i.toFloat() / divisions.toFloat()
                if (abs(pos - gridLine) < SNAP_THRESHOLD * 0.8f) return gridLine
                if (abs((pos + size) - gridLine) < SNAP_THRESHOLD * 0.8f) return gridLine - size
            }
        }
        return pos
    }

    private fun clampLayerBounds(layer: SceneLayer): SceneLayer {
        val cl = layer.cropLeft.coerceIn(0f, 0.45f)
        val cr = layer.cropRight.coerceIn(0f, (0.85f - cl).coerceIn(0f, 0.45f))
        val ct = layer.cropTop.coerceIn(0f, 0.45f)
        val cb = layer.cropBottom.coerceIn(0f, (0.85f - ct).coerceIn(0f, 0.45f))
        val safeW = layer.width.coerceIn(MIN_WIDTH, 1.0f)
        val safeH = layer.height.coerceIn(MIN_HEIGHT, 1.0f)
        val maxX = (1f - safeW).coerceAtLeast(0f)
        val maxY = (1f - safeH).coerceAtLeast(0f)
        return layer.copy(
            x = layer.x.coerceIn(0f, maxX),
            y = layer.y.coerceIn(0f, maxY),
            width = safeW,
            height = safeH,
            rotationDeg = ((layer.rotationDeg % 360f) + 360f) % 360f,
            opacity = layer.opacity.coerceIn(0.05f, 1.0f),
            cropLeft = cl,
            cropTop = ct,
            cropRight = cr,
            cropBottom = cb
        )
    }

    private fun updateActiveScene(
        state: StudioWorkspaceState,
        transform: (StudioScene) -> StudioScene
    ): StudioWorkspaceState {
        val updated = transform(state.activeScene)
        return state.copy(scenes = state.scenes + (state.activeSceneSlot to updated))
    }
}
