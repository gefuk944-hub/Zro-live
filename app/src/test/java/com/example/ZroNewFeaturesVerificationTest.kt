package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.SettingsStore
import com.example.data.model.OverlayCorner
import com.example.data.model.OverlayIconShape
import com.example.data.model.OverlayIconSize
import com.example.data.model.OverlaySettings
import com.example.data.model.PerformanceSettings
import com.example.data.model.StreamHealth
import com.example.streaming.StreamingForegroundService
import com.example.streaming.ZroStreamCoordinator
import com.example.studio.CropAspectPreset
import com.example.studio.CropEdge
import com.example.studio.LayerSourceType
import com.example.studio.ResizeCorner
import com.example.studio.SceneLayer
import com.example.studio.SceneSlot
import com.example.studio.StudioPersistence
import com.example.studio.StudioWorkspaceReducer
import com.example.studio.StudioWorkspaceState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZroNewFeaturesVerificationTest {

    // -------------------------------------------------------------------------
    // 1. FLOATING ICON UPGRADE: Shapes, Custom Sizes, Custom Logo, Edge-Hide
    // -------------------------------------------------------------------------

    @Test
    fun floatingOverlay_effectiveDimensionsAndShapes_matchExpectedGeometry() {
        val circleStd = OverlaySettings(shape = OverlayIconShape.CIRCLE, iconSize = OverlayIconSize.MEDIUM)
        assertEquals(52 to 52, circleStd.effectiveDimensionsDp())

        val squareLarge = OverlaySettings(shape = OverlayIconShape.SQUARE, iconSize = OverlayIconSize.LARGE)
        assertEquals(60 to 60, squareLarge.effectiveDimensionsDp())

        val rectCompact = OverlaySettings(shape = OverlayIconShape.RECTANGLE, iconSize = OverlayIconSize.SMALL)
        val (rw, rh) = rectCompact.effectiveDimensionsDp()
        assertTrue("Rectangle width ($rw) must exceed height ($rh)", rw > rh)

        val customCircle = OverlaySettings(
            shape = OverlayIconShape.CIRCLE,
            iconSize = OverlayIconSize.CUSTOM,
            customSizeDp = 72
        )
        assertEquals(72 to 72, customCircle.effectiveDimensionsDp())

        val customRect = OverlaySettings(
            shape = OverlayIconShape.RECTANGLE,
            iconSize = OverlayIconSize.CUSTOM,
            customWidthDp = 110,
            customHeightDp = 52
        )
        assertEquals(110 to 52, customRect.effectiveDimensionsDp())
    }

    @Test
    fun floatingOverlay_edgeHiddenState_andSettingsStorePersistence() = runBlocking {
        ZroStreamCoordinator.setOverlayVisible(true)
        ZroStreamCoordinator.setOverlayEdgeHidden(true)
        assertTrue(ZroStreamCoordinator.overlayEdgeHidden.value)

        // Turning overlay completely OFF clears edge-hidden flag cleanly
        ZroStreamCoordinator.setOverlayVisible(false)
        assertFalse(ZroStreamCoordinator.overlayEdgeHidden.value)

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx)
        val saved = OverlaySettings(
            enabled = true,
            opacity = 0.82f,
            iconSize = OverlayIconSize.CUSTOM,
            shape = OverlayIconShape.RECTANGLE,
            customSizeDp = 64,
            customWidthDp = 96,
            customHeightDp = 48,
            customLogoUri = "file:///data/user/0/com.example/files/zro_logo.png",
            position = OverlayCorner.BOTTOM_LEFT
        )
        store.saveOverlaySettings(saved)
        val loaded = store.settingsFlow.first().overlay
        assertEquals(OverlayIconShape.RECTANGLE, loaded.shape)
        assertEquals(OverlayIconSize.CUSTOM, loaded.iconSize)
        assertEquals(96, loaded.customWidthDp)
        assertEquals(48, loaded.customHeightDp)
        assertEquals(saved.customLogoUri, loaded.customLogoUri)
        assertEquals(OverlayCorner.BOTTOM_LEFT, loaded.position)
    }

    // -------------------------------------------------------------------------
    // 2. SCREEN 3 (BRB) & SCREEN 4 (ENDING) IN BOTH 16:9 AND SHORTS 9:16
    // -------------------------------------------------------------------------

    @Test
    fun screen3AndScreen4_haveGamingThumbnailsIn16x9AndShorts9x16() {
        val landscapeScenes = StudioWorkspaceState.defaultFourScenes(verticalDefault = false)
        val brb16 = landscapeScenes.getValue(SceneSlot.BRB_BREAK)
        val end16 = landscapeScenes.getValue(SceneSlot.ENDING_THANKS)
        assertFalse(brb16.isVertical)
        assertFalse(end16.isVertical)

        val brbLayer16 = brb16.layers.first { it.type == LayerSourceType.BRB_BANNER }
        val endLayer16 = end16.layers.first { it.type == LayerSourceType.ENDING_BANNER }
        assertEquals("BE RIGHT BACK!", brbLayer16.textContent)
        assertEquals("THANKS FOR WATCHING!", endLayer16.textContent)
        assertFalse("Screen 3 16:9 must show AI gaming background by default", brbLayer16.transparentBackground)
        assertFalse("Screen 4 16:9 must show AI gaming background by default", endLayer16.transparentBackground)

        val shortsScenes = StudioWorkspaceState.defaultFourScenes(verticalDefault = true)
        val brb916 = shortsScenes.getValue(SceneSlot.BRB_BREAK)
        val end916 = shortsScenes.getValue(SceneSlot.ENDING_THANKS)
        assertTrue(brb916.isVertical)
        assertTrue(end916.isVertical)

        val brbLayer916 = brb916.layers.first { it.type == LayerSourceType.BRB_BANNER }
        val endLayer916 = end916.layers.first { it.type == LayerSourceType.ENDING_BANNER }
        assertEquals("BE RIGHT BACK!", brbLayer916.textContent)
        assertEquals("THANKS FOR WATCHING!", endLayer916.textContent)
        assertFalse("Screen 3 Shorts must show AI gaming background by default", brbLayer916.transparentBackground)
        assertFalse("Screen 4 Shorts must show AI gaming background by default", endLayer916.transparentBackground)
    }

    @Test
    fun legacyTransparentScreen3And4_upgradeToShowNewAiGamingBackground() {
        val legacyState = StudioWorkspaceState().let { base ->
            val s3 = base.scenes.getValue(SceneSlot.BRB_BREAK).let { sc ->
                sc.copy(layers = sc.layers.map { if (it.id == "s3_banner") it.copy(transparentBackground = true, mediaUri = null) else it })
            }
            val s4 = base.scenes.getValue(SceneSlot.ENDING_THANKS).let { sc ->
                sc.copy(layers = sc.layers.map { if (it.id == "s4_thanks") it.copy(transparentBackground = true, mediaUri = null) else it })
            }
            base.copy(scenes = base.scenes + (SceneSlot.BRB_BREAK to s3) + (SceneSlot.ENDING_THANKS to s4))
        }
        val json = StudioPersistence.serializeWorkspace(legacyState)
        val restored = StudioPersistence.deserializeWorkspace(json)
        val brbBanner = restored.scenes.getValue(SceneSlot.BRB_BREAK).layers.first { it.id == "s3_banner" }
        val endBanner = restored.scenes.getValue(SceneSlot.ENDING_THANKS).layers.first { it.id == "s4_thanks" }
        assertFalse("Legacy s3_banner should upgrade to show new AI BRB art", brbBanner.transparentBackground)
        assertFalse("Legacy s4_thanks should upgrade to show new AI Ending art", endBanner.transparentBackground)
    }

    // -------------------------------------------------------------------------
    // 3. LAYER CROP & TRANSFORM FIX (ZERO EMPTY MARGIN / TIGHT BOUNDING BOX)
    // -------------------------------------------------------------------------

    @Test
    fun cropLayer_tightensVisibleBoundingBoxWithZeroEmptyArea_forImageGifVideoAndBrowser() {
        val layerTypes = listOf(
            LayerSourceType.IMAGE,
            LayerSourceType.GIF,
            LayerSourceType.VIDEO_OVERLAY,
            LayerSourceType.BROWSER_WEB
        )
        for (type in layerTypes) {
            val layer = SceneLayer(
                id = "test_${type.name}",
                name = "Test ${type.label}",
                type = type,
                x = 0.10f,
                y = 0.10f,
                width = 0.80f,
                height = 0.60f
            )
            var state = StudioWorkspaceReducer.addLayer(StudioWorkspaceState(), layer)

            // Crop 25% from left & 25% from right -> visible width must shrink cleanly to 50% of 0.80 = 0.40!
            state = StudioWorkspaceReducer.setLayerCrop(
                state = state,
                layerId = layer.id,
                cropLeft = 0.25f,
                cropRight = 0.25f,
                cropTop = 0.10f,
                cropBottom = 0.20f
            )
            val cropped = state.activeScene.layers.first { it.id == layer.id }
            assertEquals("Tight X for $type", 0.30f, cropped.x, 0.005f)
            assertEquals("Tight Width for $type", 0.40f, cropped.width, 0.005f)
            assertEquals("Tight Y for $type", 0.16f, cropped.y, 0.005f)
            assertEquals("Tight Height for $type", 0.42f, cropped.height, 0.005f)
            assertEquals("Original width preserved for $type", 0.80f, cropped.origWidth, 0.005f)
            assertEquals("Original height preserved for $type", 0.60f, cropped.origHeight, 0.005f)

            // Moving, resizing & rotating after crop must operate on the tight box and keep crop intact
            state = StudioWorkspaceReducer.moveLayerBy(state, layer.id, 0.05f, 0.04f)
            state = StudioWorkspaceReducer.resizeLayerFromCorner(state, layer.id, ResizeCorner.BOTTOM_RIGHT, 0.10f, 0.08f, proportional = false)
            state = StudioWorkspaceReducer.updateLayer(state, layer.id) { it.copy(rotationDeg = 45f) }
            val transformed = state.activeScene.layers.first { it.id == layer.id }
            assertEquals(0.25f, transformed.cropLeft, 0.005f)
            assertEquals(0.25f, transformed.cropRight, 0.005f)
            assertEquals(45f, transformed.rotationDeg, 0.01f)
            assertTrue("Cropped layer resized cleanly", transformed.width > cropped.width)

            // Reset Crop clears crop fractions while expanding back smoothly
            val resetState = StudioWorkspaceReducer.resetLayerCrop(state, layer.id)
            val afterReset = resetState.activeScene.layers.first { it.id == layer.id }
            assertEquals(0f, afterReset.cropLeft, 0.001f)
            assertEquals(0f, afterReset.cropRight, 0.001f)
            assertEquals(0f, afterReset.cropTop, 0.001f)
            assertEquals(0f, afterReset.cropBottom, 0.001f)

            // Restore Original Size brings back exact original uncropped dimensions
            val restoredOrigState = StudioWorkspaceReducer.restoreLayerOriginalSize(state, layer.id)
            val afterRestore = restoredOrigState.activeScene.layers.first { it.id == layer.id }
            assertEquals(0.80f, afterRestore.width, 0.005f)
            assertEquals(0.60f, afterRestore.height, 0.005f)
            assertEquals(0f, afterRestore.cropLeft, 0.001f)
            assertEquals(0f, afterRestore.cropTop, 0.001f)
        }
    }

    @Test
    fun cropLayerEdge_andAspectPreset_adjustTightBoundingBox() {
        val layer = SceneLayer(
            id = "edge_test",
            name = "Image Layer",
            type = LayerSourceType.IMAGE,
            x = 0.10f,
            y = 0.20f,
            width = 0.60f,
            height = 0.60f
        )
        var state = StudioWorkspaceReducer.addLayer(StudioWorkspaceState(), layer)
        state = StudioWorkspaceReducer.cropLayerEdge(state, layer.id, CropEdge.LEFT, 0.12f)
        val afterEdge = state.activeScene.layers.first { it.id == layer.id }
        assertEquals(0.172f, afterEdge.x, 0.005f)
        assertEquals(0.528f, afterEdge.width, 0.005f)
        assertEquals(0.12f, afterEdge.cropLeft, 0.005f)

        val tallLayer = SceneLayer(
            id = "tall_layer",
            name = "Tall Portrait Image",
            type = LayerSourceType.IMAGE,
            x = 0.25f,
            y = 0.10f,
            width = 0.30f,
            height = 0.80f
        )
        var tallState = StudioWorkspaceReducer.addLayer(StudioWorkspaceState(), tallLayer)
        val presetState = StudioWorkspaceReducer.applyCropAspectPreset(tallState, tallLayer.id, CropAspectPreset.RATIO_16_9)
        val afterPreset = presetState.activeScene.layers.first { it.id == tallLayer.id }
        assertTrue("16:9 crop preset should crop top/bottom of a tall layer", afterPreset.cropTop > 0f && afterPreset.cropBottom > 0f)
        assertTrue("Tight height must shrink after vertical crop", afterPreset.height < 0.80f)
    }

    // -------------------------------------------------------------------------
    // 4. LIVE BITRATE CONTROL & WEAK NETWORK SUGGESTION
    // -------------------------------------------------------------------------

    @Test
    fun weakNetworkBitrateSuggestion_suggestsSaferTargetOnlyWhenDegraded() {
        // Healthy stream: no suggestion
        assertNull(
            StreamingForegroundService.computeWeakNetworkSuggestedBitrate(
                measuredKbps = 4300,
                activeTargetKbps = 4500,
                health = StreamHealth.EXCELLENT,
                recentDroppedFrames = 0L
            )
        )

        // Poor health with low uplink: suggests safer rounded target strictly below active target
        val suggested = StreamingForegroundService.computeWeakNetworkSuggestedBitrate(
            measuredKbps = 2400,
            activeTargetKbps = 6000,
            health = StreamHealth.POOR,
            recentDroppedFrames = 5L
        )
        assertNotNull("Weak network should suggest a safer lower bitrate", suggested)
        assertTrue("Suggested bitrate ($suggested) must be >= 1000 and < 6000", suggested!! in 1000 until 6000)
        assertEquals("Suggested bitrate must be rounded to 500 kbps steps", 0, suggested % 500)

        // Live bitrate request updates selectedBitrateKbps cleanly
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ZroStreamCoordinator.requestUpdateLiveBitrate(ctx, 5500)
        assertEquals(5500, ZroStreamCoordinator.telemetry.value.selectedBitrateKbps)
    }

    // -------------------------------------------------------------------------
    // 5. PHONE HEATING & THERMAL AUTO-PROTECTION PERSISTENCE
    // -------------------------------------------------------------------------

    @Test
    fun performanceThermalSettings_persistAutoFpsAndThreshold() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx)
        val perf = PerformanceSettings(
            temperatureMonitoring = true,
            autoThermalQualityStepDown = true,
            autoThermalFpsStepDown = false,
            thermalThresholdCelsius = 40f
        )
        store.savePerformanceSettings(perf)
        val loaded = store.settingsFlow.first().performance
        assertTrue(loaded.temperatureMonitoring)
        assertTrue(loaded.autoThermalQualityStepDown)
        assertFalse(loaded.autoThermalFpsStepDown)
        assertEquals(40f, loaded.thermalThresholdCelsius, 0.01f)
    }
}
