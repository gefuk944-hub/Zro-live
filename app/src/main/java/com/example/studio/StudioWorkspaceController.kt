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

    fun resetActiveSceneToDefault(state: StudioWorkspaceState): StudioWorkspaceState {
        val fresh = StudioWorkspaceState.defaultSceneFor(state.activeSceneSlot)
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
            var nx = (layer.x + dxNormalized).coerceIn(-0.4f, 0.95f)
            var ny = (layer.y + dyNormalized).coerceIn(-0.4f, 0.95f)
            if (state.showSnapGuides) {
                nx = snapCoordinate(nx, layer.width, state.gridMode.cols)
                ny = snapCoordinate(ny, layer.height, state.gridMode.rows)
            }
            layer.copy(x = nx, y = ny)
        }
    }

    /**
     * Real OBS-style 4-corner handle resizing.
     * Anchors the opposite corner while dragging TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, or BOTTOM_RIGHT.
     * Enforces minimum dimensions, optional proportional scaling, and strict layer/scene lock rules.
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
            val left = layer.x
            val top = layer.y
            val right = layer.x + layer.width
            val bottom = layer.y + layer.height
            val aspect = if (layer.height > 0.001f) layer.width / layer.height else 16f / 9f
            val useProportional = proportional || layer.lockAspectRatio

            var newLeft = left
            var newTop = top
            var newRight = right
            var newBottom = bottom

            when (corner) {
                ResizeCorner.TOP_LEFT -> {
                    newLeft = (left + dxNorm).coerceIn(-0.4f, right - MIN_WIDTH)
                    newTop = (top + dyNorm).coerceIn(-0.4f, bottom - MIN_HEIGHT)
                    if (useProportional) {
                        val w = (right - newLeft).coerceIn(MIN_WIDTH, 1.0f)
                        val h = (w / aspect).coerceIn(MIN_HEIGHT, 1.0f)
                        newLeft = right - w
                        newTop = bottom - h
                    }
                }
                ResizeCorner.TOP_RIGHT -> {
                    newRight = (right + dxNorm).coerceIn(left + MIN_WIDTH, 1.4f)
                    newTop = (top + dyNorm).coerceIn(-0.4f, bottom - MIN_HEIGHT)
                    if (useProportional) {
                        val w = (newRight - left).coerceIn(MIN_WIDTH, 1.0f)
                        val h = (w / aspect).coerceIn(MIN_HEIGHT, 1.0f)
                        newRight = left + w
                        newTop = bottom - h
                    }
                }
                ResizeCorner.BOTTOM_LEFT -> {
                    newLeft = (left + dxNorm).coerceIn(-0.4f, right - MIN_WIDTH)
                    newBottom = (bottom + dyNorm).coerceIn(top + MIN_HEIGHT, 1.4f)
                    if (useProportional) {
                        val w = (right - newLeft).coerceIn(MIN_WIDTH, 1.0f)
                        val h = (w / aspect).coerceIn(MIN_HEIGHT, 1.0f)
                        newLeft = right - w
                        newBottom = top + h
                    }
                }
                ResizeCorner.BOTTOM_RIGHT -> {
                    newRight = (right + dxNorm).coerceIn(left + MIN_WIDTH, 1.4f)
                    newBottom = (bottom + dyNorm).coerceIn(top + MIN_HEIGHT, 1.4f)
                    if (useProportional) {
                        val w = (newRight - left).coerceIn(MIN_WIDTH, 1.0f)
                        val h = (w / aspect).coerceIn(MIN_HEIGHT, 1.0f)
                        newRight = left + w
                        newBottom = top + h
                    }
                }
            }

            val finalW = (newRight - newLeft).coerceIn(MIN_WIDTH, 1.0f)
            val finalH = (newBottom - newTop).coerceIn(MIN_HEIGHT, 1.0f)
            layer.copy(
                x = newLeft.coerceIn(-0.45f, 0.95f),
                y = newTop.coerceIn(-0.45f, 0.95f),
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
            val clampedW = newWidth.coerceIn(MIN_WIDTH, 1.0f)
            val clampedH = if (layer.lockAspectRatio && layer.width > 0.01f) {
                val ratio = layer.height / layer.width
                (clampedW * ratio).coerceIn(MIN_HEIGHT, 1.0f)
            } else {
                newHeight.coerceIn(MIN_HEIGHT, 1.0f)
            }
            layer.copy(width = clampedW, height = clampedH)
        }
    }

    fun centerLayerHorizontally(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            layer.copy(x = ((1f - layer.width) / 2f).coerceIn(0f, 1f))
        }
    }

    fun centerLayerVertically(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            layer.copy(y = ((1f - layer.height) / 2f).coerceIn(0f, 1f))
        }
    }

    fun alignLayerCenter(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            layer.copy(
                x = ((1f - layer.width) / 2f).coerceIn(0f, 1f),
                y = ((1f - layer.height) / 2f).coerceIn(0f, 1f)
            )
        }
    }

    fun fitLayerFullCanvas(state: StudioWorkspaceState, layerId: String): StudioWorkspaceState {
        return updateLayer(state, layerId) { layer ->
            layer.copy(
                x = 0f,
                y = 0f,
                width = 1f,
                height = 1f,
                rotationDeg = 0f,
                cropLeft = 0f,
                cropTop = 0f,
                cropRight = 0f,
                cropBottom = 0f
            )
        }
    }

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
                LayerSourceType.GAMEPLAY_SCREEN, LayerSourceType.BACKGROUND ->
                    layer.copy(x = 0f, y = 0f, width = 1f, height = 1f, rotationDeg = 0f, cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f, opacity = 1f)
                LayerSourceType.WEBCAM ->
                    layer.copy(x = 0.74f, y = 0.62f, width = 0.23f, height = 0.33f, rotationDeg = 0f, cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f, opacity = 1f)
                else ->
                    layer.copy(x = 0.25f, y = 0.25f, width = 0.50f, height = 0.35f, rotationDeg = 0f, cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f, opacity = 1f)
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
            x = minX.coerceIn(-0.45f, 0.95f),
            y = minY.coerceIn(-0.45f, 0.95f),
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
            val absX = (parent.x + child.x * parent.width).coerceIn(-0.45f, 0.95f)
            val absY = (parent.y + child.y * parent.height).coerceIn(-0.45f, 0.95f)
            val absW = (child.width * parent.width).coerceIn(MIN_WIDTH, 1.0f)
            val absH = (child.height * parent.height).coerceIn(MIN_HEIGHT, 1.0f)
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
        return layer.copy(
            x = layer.x.coerceIn(-0.45f, 0.95f),
            y = layer.y.coerceIn(-0.45f, 0.95f),
            width = layer.width.coerceIn(MIN_WIDTH, 1.0f),
            height = layer.height.coerceIn(MIN_HEIGHT, 1.0f),
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
