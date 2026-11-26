package com.example.studio

import org.json.JSONArray
import org.json.JSONObject

/**
 * Backward-compatible, dependency-free JSON serializer for all 4 independent OBS scenes,
 * layer lock states, transforms, facecam shape, grid mode (2x6 default), BRB state,
 * saved authorized layout metadata, and 3-column customizable remote deck layout.
 */
object StudioPersistence {

    fun serializeWorkspace(state: StudioWorkspaceState): String {
        val root = JSONObject()
        root.put("activeSceneSlot", state.activeSceneSlot.name)
        root.put("gridMode", state.gridMode.name)
        root.put("showGrid", state.gridMode != CanvasGridMode.OFF)
        root.put("showSnapGuides", state.showSnapGuides)
        root.put("showSafeArea", state.showSafeArea)
        root.put("canvasZoom", state.canvasZoom.toDouble())
        root.put("overlaysGlobalVisible", state.overlaysGlobalVisible)
        root.put("cameraEnabled", state.cameraEnabled)
        root.put("useFrontCamera", state.useFrontCamera)
        root.put("cameraSourceMode", state.cameraSourceMode.name)
        root.put("brbActive", state.brbActive)
        root.put("previousSceneBeforeBrb", state.previousSceneBeforeBrb?.name ?: "")
        root.put("autoRestoreLastLayout", state.autoRestoreLastLayout)
        root.put("savedLayoutTimestampMs", state.savedLayoutTimestampMs)
        root.put("lastAuthorizedDeviceName", state.lastAuthorizedDeviceName)
        root.put("lastAuthorizedDeviceIp", state.lastAuthorizedDeviceIp)
        root.put("remoteTileScale", state.remoteTileScale.toDouble())
        root.put("chatTextSizeSp", state.chatTextSizeSp.toDouble())
        root.put("chatOverlayEnabled", state.chatOverlayEnabled)

        val tileOrderArr = JSONArray()
        state.remoteTileOrder.forEach { tileOrderArr.put(it.name) }
        root.put("remoteTileOrder", tileOrderArr)

        val scenesArr = JSONArray()
        SceneSlot.entries.forEach { slot ->
            val scene = state.scenes[slot] ?: StudioWorkspaceState.defaultSceneFor(slot)
            scenesArr.put(serializeScene(scene))
        }
        root.put("scenes", scenesArr)
        root.put("customGraphicPresets", GraphicAdjustmentJson.presetsToJsonArray(state.customGraphicPresets))
        return root.toString()
    }

    fun deserializeWorkspace(raw: String?, verticalDefault: Boolean = false): StudioWorkspaceState {
        if (raw.isNullOrBlank()) {
            return StudioWorkspaceState(scenes = StudioWorkspaceState.defaultFourScenes(verticalDefault))
        }
        return runCatching {
            val root = JSONObject(raw)
            val activeSlot = SceneSlot.fromName(root.optString("activeSceneSlot"))
            val scenesMap = StudioWorkspaceState.defaultFourScenes(verticalDefault).toMutableMap()
            val scenesArr = root.optJSONArray("scenes")
            if (scenesArr != null) {
                for (i in 0 until scenesArr.length()) {
                    val obj = scenesArr.optJSONObject(i) ?: continue
                    val parsed = deserializeScene(obj)
                    // Upgrade untouched legacy Scene 2 ("Vertical / Shorts") in 16:9 mode to 16:9 "STREAMING START SOON"
                    val isUntouchedLegacyShortsScene2 = !verticalDefault &&
                        parsed.slot == SceneSlot.SHORTS_VERTICAL &&
                        parsed.isVertical &&
                        parsed.name.equals("Vertical / Shorts", ignoreCase = true) &&
                        parsed.layers.map { it.id }.toSet() == setOf("s2_bg", "s2_gameplay", "s2_webcam", "s2_chat")
                    scenesMap[parsed.slot] = if (isUntouchedLegacyShortsScene2) {
                        StudioWorkspaceState.defaultSceneFor(SceneSlot.SHORTS_VERTICAL, verticalDefault = false)
                    } else {
                        parsed
                    }
                }
            }
            val parsedTiles = mutableListOf<RemoteControlTileId>()
            val tileArr = root.optJSONArray("remoteTileOrder")
            if (tileArr != null) {
                for (i in 0 until tileArr.length()) {
                    val item = RemoteControlTileId.fromName(tileArr.optString(i))
                    if (item != null && item !in parsedTiles) parsedTiles.add(item)
                }
            }
            val effectiveTiles = if (parsedTiles.isEmpty()) {
                RemoteControlTileId.DEFAULT_ORDER
            } else {
                parsedTiles
            }

            val gridMode = if (root.has("gridMode")) {
                CanvasGridMode.fromName(root.optString("gridMode"))
            } else {
                if (root.optBoolean("showGrid", true)) CanvasGridMode.GRID_2X6 else CanvasGridMode.OFF
            }
            val prevBrbRaw = root.optString("previousSceneBeforeBrb", "")
            val prevBrbSlot = if (prevBrbRaw.isNotBlank()) SceneSlot.fromName(prevBrbRaw) else null

            StudioWorkspaceState(
                activeSceneSlot = activeSlot,
                scenes = scenesMap,
                gridMode = gridMode,
                showGrid = gridMode != CanvasGridMode.OFF,
                showSnapGuides = root.optBoolean("showSnapGuides", true),
                showSafeArea = root.optBoolean("showSafeArea", false),
                canvasZoom = root.optDouble("canvasZoom", 1.0).toFloat().coerceIn(0.6f, 2.2f),
                overlaysGlobalVisible = root.optBoolean("overlaysGlobalVisible", true),
                cameraEnabled = root.optBoolean("cameraEnabled", false),
                useFrontCamera = root.optBoolean("useFrontCamera", true),
                cameraSourceMode = CameraSourceMode.fromName(root.optString("cameraSourceMode")),
                brbActive = root.optBoolean("brbActive", false),
                previousSceneBeforeBrb = prevBrbSlot,
                autoRestoreLastLayout = root.optBoolean("autoRestoreLastLayout", true),
                savedLayoutTimestampMs = root.optLong("savedLayoutTimestampMs", 0L),
                lastAuthorizedDeviceName = root.optString("lastAuthorizedDeviceName", ""),
                lastAuthorizedDeviceIp = root.optString("lastAuthorizedDeviceIp", ""),
                remoteTileOrder = effectiveTiles,
                remoteTileScale = root.optDouble("remoteTileScale", 1.0).toFloat().coerceIn(0.85f, 1.30f),
                chatTextSizeSp = root.optDouble("chatTextSizeSp", 13.0).toFloat().coerceIn(10f, 22f),
                chatOverlayEnabled = root.optBoolean("chatOverlayEnabled", true),
                customGraphicPresets = GraphicAdjustmentJson.presetsFromJsonArray(root.optJSONArray("customGraphicPresets"))
            )
        }.getOrDefault(StudioWorkspaceState())
    }

    fun serializeScene(scene: StudioScene): JSONObject {
        val obj = JSONObject()
        obj.put("slot", scene.slot.name)
        obj.put("name", scene.name)
        obj.put("isVertical", scene.isVertical)
        obj.put("sceneLocked", scene.sceneLocked)
        obj.put("transitionType", scene.transitionType.name)
        obj.put("transitionDurationMs", scene.transitionDurationMs)
        val layersArr = JSONArray()
        scene.layers.sortedBy { it.zIndex }.forEach { layer ->
            layersArr.put(serializeLayer(layer))
        }
        obj.put("layers", layersArr)
        return obj
    }

    fun deserializeScene(obj: JSONObject): StudioScene {
        val slot = SceneSlot.fromName(obj.optString("slot"))
        val layersArr = obj.optJSONArray("layers")
        val layers = mutableListOf<SceneLayer>()
        if (layersArr != null) {
            for (i in 0 until layersArr.length()) {
                val lObj = layersArr.optJSONObject(i) ?: continue
                layers.add(deserializeLayer(lObj, i))
            }
        }
        return StudioScene(
            slot = slot,
            name = obj.optString("name", slot.defaultName).ifBlank { slot.defaultName },
            isVertical = obj.optBoolean("isVertical", slot.defaultVertical),
            sceneLocked = obj.optBoolean("sceneLocked", false),
            transitionType = SceneTransitionType.fromName(obj.optString("transitionType")),
            transitionDurationMs = obj.optInt("transitionDurationMs", 300).coerceIn(0, 2000),
            layers = layers
        )
    }

    private fun serializeLayer(l: SceneLayer): JSONObject = JSONObject().apply {
        put("id", l.id)
        put("name", l.name)
        put("type", l.type.name)
        put("visible", l.visible)
        put("locked", l.locked)
        put("zIndex", l.zIndex)
        put("x", l.x.toDouble())
        put("y", l.y.toDouble())
        put("width", l.width.toDouble())
        put("height", l.height.toDouble())
        put("rotationDeg", l.rotationDeg.toDouble())
        put("opacity", l.opacity.toDouble())
        put("cropLeft", l.cropLeft.toDouble())
        put("cropTop", l.cropTop.toDouble())
        put("cropRight", l.cropRight.toDouble())
        put("cropBottom", l.cropBottom.toDouble())
        put("lockAspectRatio", l.lockAspectRatio)
        put("scaleMode", l.scaleMode.name)
        put("borderWidthDp", l.borderWidthDp.toDouble())
        put("borderColorHex", l.borderColorHex)
        put("shadowEnabled", l.shadowEnabled)
        put("facecamShape", l.facecamShape.name)
        put("textContent", l.textContent)
        put("subtitleText", l.subtitleText)
        put("fontStyle", l.fontStyle)
        put("fontSizeSp", l.fontSizeSp.toDouble())
        put("textColorHex", l.textColorHex)
        put("backgroundColorHex", l.backgroundColorHex)
        put("transparentBackground", l.transparentBackground)
        put("mediaUri", l.mediaUri ?: "")
        put("webUrl", l.webUrl)
        put("animationType", l.animationType.name)
        put("animationEnabled", l.animationEnabled)
        put("animationSpeed", l.animationSpeed.toDouble())
        put("motionBackgroundEnabled", l.motionBackgroundEnabled)
        put("subTitle", l.subTitle)
        put("subCount", l.subCount)
        put("subGoal", l.subGoal)
        put("subChannelUrl", l.subChannelUrl)
        put("subWidgetUrl", l.subWidgetUrl)
        put("subUseExternalWidget", l.subUseExternalWidget)
        put("presetTag", l.presetTag)
        put("sourceMediaWidth", l.sourceMediaWidth)
        put("sourceMediaHeight", l.sourceMediaHeight)
        put("origWidth", l.origWidth.toDouble())
        put("origHeight", l.origHeight.toDouble())
        put("graphicAdjustment", GraphicAdjustmentJson.toJson(l.graphicAdjustment.copy(opacity = l.opacity)))
        if (l.mergedChildren.isNotEmpty()) {
            val childArr = JSONArray()
            l.mergedChildren.sortedBy { it.zIndex }.forEach { child ->
                childArr.put(serializeLayer(child))
            }
            put("mergedChildren", childArr)
        }
    }

    private fun deserializeLayer(o: JSONObject, fallbackZ: Int): SceneLayer = SceneLayer(
        id = o.optString("id").ifBlank { "layer_$fallbackZ" },
        name = o.optString("name", "Layer ${fallbackZ + 1}"),
        type = LayerSourceType.fromName(o.optString("type")),
        visible = o.optBoolean("visible", true),
        locked = o.optBoolean("locked", false),
        zIndex = o.optInt("zIndex", fallbackZ),
        x = o.optDouble("x", 0.1).toFloat().coerceIn(-0.5f, 1.0f),
        y = o.optDouble("y", 0.1).toFloat().coerceIn(-0.5f, 1.0f),
        width = o.optDouble("width", 0.35).toFloat().coerceIn(0.05f, 1.0f),
        height = o.optDouble("height", 0.2).toFloat().coerceIn(0.05f, 1.0f),
        rotationDeg = o.optDouble("rotationDeg", 0.0).toFloat().coerceIn(-180f, 360f),
        opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0.05f, 1.0f),
        cropLeft = o.optDouble("cropLeft", 0.0).toFloat().coerceIn(0f, 0.80f),
        cropTop = o.optDouble("cropTop", 0.0).toFloat().coerceIn(0f, 0.80f),
        cropRight = o.optDouble("cropRight", 0.0).toFloat().coerceIn(0f, 0.80f),
        cropBottom = o.optDouble("cropBottom", 0.0).toFloat().coerceIn(0f, 0.80f),
        lockAspectRatio = o.optBoolean("lockAspectRatio", false),
        scaleMode = LayerScaleMode.fromName(o.optString("scaleMode")),
        borderWidthDp = o.optDouble("borderWidthDp", 0.0).toFloat().coerceIn(0f, 8f),
        borderColorHex = o.optLong("borderColorHex", 0xFF3B82F6L),
        shadowEnabled = o.optBoolean("shadowEnabled", false),
        facecamShape = FacecamShape.fromName(o.optString("facecamShape")),
        textContent = o.optString("textContent", ""),
        subtitleText = o.optString("subtitleText", ""),
        fontStyle = o.optString("fontStyle", "SANS").ifBlank { "SANS" },
        fontSizeSp = o.optDouble("fontSizeSp", 16.0).toFloat().coerceIn(8f, 48f),
        textColorHex = o.optLong("textColorHex", 0xFFFFFFFFL),
        backgroundColorHex = o.optLong("backgroundColorHex", 0xCC11141BL),
        transparentBackground = run {
            val rawTransparent = o.optBoolean("transparentBackground", false)
            val id = o.optString("id")
            val hasCustomMedia = o.optString("mediaUri", "").isNotBlank()
            // Upgrade legacy default s3_banner / s4_thanks so AI gaming artwork displays by default
            if (rawTransparent && !hasCustomMedia && (id == "s3_banner" || id == "s4_thanks")) false else rawTransparent
        },
        mediaUri = o.optString("mediaUri", "").ifBlank { null },
        webUrl = o.optString("webUrl", ""),
        animationType = LayerAnimationType.fromName(o.optString("animationType")),
        animationEnabled = o.optBoolean("animationEnabled", true),
        animationSpeed = o.optDouble("animationSpeed", 1.0).toFloat().coerceIn(0.25f, 3.0f),
        motionBackgroundEnabled = o.optBoolean("motionBackgroundEnabled", true),
        subTitle = o.optString("subTitle", "SUBSCRIBERS"),
        subCount = o.optInt("subCount", 0).coerceAtLeast(0),
        subGoal = o.optInt("subGoal", 1000).coerceAtLeast(1),
        subChannelUrl = o.optString("subChannelUrl", ""),
        subWidgetUrl = o.optString("subWidgetUrl", ""),
        subUseExternalWidget = o.optBoolean("subUseExternalWidget", false),
        presetTag = o.optString("presetTag", ""),
        sourceMediaWidth = o.optInt("sourceMediaWidth", 0).coerceAtLeast(0),
        sourceMediaHeight = o.optInt("sourceMediaHeight", 0).coerceAtLeast(0),
        origWidth = o.optDouble("origWidth", 0.0).toFloat().coerceIn(0f, 1f),
        origHeight = o.optDouble("origHeight", 0.0).toFloat().coerceIn(0f, 1f),
        graphicAdjustment = GraphicAdjustmentJson.fromJson(
            o.optJSONObject("graphicAdjustment"),
            fallbackOpacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0.05f, 1.0f)
        ),
        mergedChildren = buildList {
            val childArr = o.optJSONArray("mergedChildren")
            if (childArr != null) {
                for (ci in 0 until childArr.length()) {
                    val cObj = childArr.optJSONObject(ci) ?: continue
                    add(deserializeLayer(cObj, ci))
                }
            }
        }
    )
}
