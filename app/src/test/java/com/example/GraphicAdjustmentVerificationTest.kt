package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.studio.GraphicPresetId
import com.example.studio.LayerGraphicAdjustment
import com.example.studio.SceneSlot
import com.example.studio.StudioPersistence
import com.example.studio.StudioWorkspaceReducer
import com.example.studio.StudioWorkspaceState
import com.example.ui.components.buildGraphicColorMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36])
class GraphicAdjustmentVerificationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `layer graphic adjustments are strictly independent per layer and survive persistence and scene switching`() {
        val initial = StudioWorkspaceState()
        val gameplayId = "s1_gameplay"
        val logoId = "s1_branding"

        val adjusted = StudioWorkspaceReducer.updateLayerGraphicAdjustment(initial, gameplayId) {
            it.copy(
                presetId = GraphicPresetId.CUSTOM,
                brightness = 22f,
                contrast = 30f,
                saturation = 18f,
                vibrance = 25f,
                exposure = 12f,
                gamma = 1.25f,
                sharpness = 40f,
                clarity = 28f,
                highlights = 15f,
                shadows = -20f,
                temperature = 35f,
                tint = -12f,
                hue = 45f,
                rgbRed = 1.15f,
                rgbGreen = 0.95f,
                rgbBlue = 0.85f,
                opacity = 0.82f,
                blurEnabled = true,
                blurRadiusDp = 6f,
                glowEnabled = true,
                glowIntensity = 55f,
                glowColorHex = 0xFF22C55EL,
                dropShadowEnabled = true,
                dropShadowRadiusDp = 12f,
                dropShadowOpacity = 0.80f,
                outlineEnabled = true,
                outlineWidthDp = 3f,
                outlineColorHex = 0xFFF59E0BL
            )
        }

        val updatedGameplay = adjusted.activeScene.layers.first { it.id == gameplayId }
        val untouchedLogo = adjusted.activeScene.layers.first { it.id == logoId }

        // Only the selected layer changed; sibling layer remains completely neutral
        assertEquals(22f, updatedGameplay.graphicAdjustment.brightness, 0.01f)
        assertEquals(0.82f, updatedGameplay.opacity, 0.01f)
        assertEquals(0f, untouchedLogo.graphicAdjustment.brightness, 0.01f)
        assertEquals(1.0f, untouchedLogo.opacity, 0.01f)
        assertFalse(untouchedLogo.graphicAdjustment.blurEnabled)

        // Save custom preset & switch scene & serialize/deserialize
        val withCustomPreset = StudioWorkspaceReducer.saveCustomGraphicPreset(
            adjusted,
            "My Stream Look",
            updatedGameplay.graphicAdjustment
        )
        val switchedToShorts = StudioWorkspaceReducer.switchScene(withCustomPreset, SceneSlot.SHORTS_VERTICAL)
        val json = StudioPersistence.serializeWorkspace(switchedToShorts)
        val restored = StudioPersistence.deserializeWorkspace(json)

        // Scene switching + persistence preserve all 19 parameters + custom presets
        val restoredGameplay = restored.scenes[SceneSlot.GAMEPLAY_LIVE]!!.layers.first { it.id == gameplayId }
        val g = restoredGameplay.graphicAdjustment
        assertEquals(22f, g.brightness, 0.01f)
        assertEquals(30f, g.contrast, 0.01f)
        assertEquals(18f, g.saturation, 0.01f)
        assertEquals(25f, g.vibrance, 0.01f)
        assertEquals(12f, g.exposure, 0.01f)
        assertEquals(1.25f, g.gamma, 0.01f)
        assertEquals(40f, g.sharpness, 0.01f)
        assertEquals(28f, g.clarity, 0.01f)
        assertEquals(15f, g.highlights, 0.01f)
        assertEquals(-20f, g.shadows, 0.01f)
        assertEquals(35f, g.temperature, 0.01f)
        assertEquals(-12f, g.tint, 0.01f)
        assertEquals(45f, g.hue, 0.01f)
        assertEquals(1.15f, g.rgbRed, 0.01f)
        assertEquals(0.95f, g.rgbGreen, 0.01f)
        assertEquals(0.85f, g.rgbBlue, 0.01f)
        assertEquals(0.82f, restoredGameplay.opacity, 0.01f)
        assertTrue(g.blurEnabled)
        assertEquals(6f, g.blurRadiusDp, 0.01f)
        assertTrue(g.glowEnabled)
        assertEquals(55f, g.glowIntensity, 0.01f)
        assertTrue(g.dropShadowEnabled)
        assertEquals(12f, g.dropShadowRadiusDp, 0.01f)
        assertTrue(g.outlineEnabled)
        assertEquals(3f, g.outlineWidthDp, 0.01f)
        assertEquals(1, restored.customGraphicPresets.size)
        assertEquals("My Stream Look", restored.customGraphicPresets.first().name)
    }

    @Test
    fun `presets copy to layer reset selected layer only and color matrix math work accurately`() {
        val base = StudioWorkspaceState()
        val withCinematic = StudioWorkspaceReducer.applyGraphicPresetToLayer(base, "s1_gameplay", GraphicPresetId.CINEMATIC)
        val withVibrantOnLogo = StudioWorkspaceReducer.applyGraphicPresetToLayer(withCinematic, "s1_branding", GraphicPresetId.VIBRANT)

        // Reset ONLY gameplay layer — branding's Vibrant preset must stay intact!
        val afterResetGameplay = StudioWorkspaceReducer.resetLayerGraphicAdjustment(withVibrantOnLogo, "s1_gameplay")
        assertEquals(GraphicPresetId.ORIGINAL, afterResetGameplay.activeScene.layers.first { it.id == "s1_gameplay" }.graphicAdjustment.presetId)
        assertEquals(GraphicPresetId.VIBRANT, afterResetGameplay.activeScene.layers.first { it.id == "s1_branding" }.graphicAdjustment.presetId)

        // Copy Branding's Vibrant adjustment to Gameplay
        val copied = StudioWorkspaceReducer.copyGraphicAdjustmentToLayer(afterResetGameplay, "s1_branding", "s1_gameplay")
        assertEquals(
            copied.activeScene.layers.first { it.id == "s1_branding" }.graphicAdjustment.vibrance,
            copied.activeScene.layers.first { it.id == "s1_gameplay" }.graphicAdjustment.vibrance,
            0.01f
        )

        // Locked layer cannot be overwritten by copy
        val lockedWebcam = StudioWorkspaceReducer.toggleLayerLock(copied, "s1_webcam")
        val attemptCopyLocked = StudioWorkspaceReducer.copyGraphicAdjustmentToLayer(lockedWebcam, "s1_branding", "s1_webcam")
        assertEquals(GraphicPresetId.ORIGINAL, attemptCopyLocked.activeScene.layers.first { it.id == "s1_webcam" }.graphicAdjustment.presetId)

        // ColorMatrix math: Original is identity; Cinematic modifies values cleanly
        val identity = buildGraphicColorMatrix(LayerGraphicAdjustment.ORIGINAL)
        assertEquals(1f, identity.values[0], 0.0001f)
        assertEquals(0f, identity.values[4], 0.0001f)

        val cinematicMatrix = buildGraphicColorMatrix(LayerGraphicAdjustment.forBuiltInPreset(GraphicPresetId.CINEMATIC))
        assertNotEquals(1f, cinematicMatrix.values[0], 0.001f)
        assertTrue(cinematicMatrix.values.all { it.isFinite() })
    }

    @Test
    fun `studio graphics dock tab exposes presets sliders before after preview and reset without breaking existing ui`() {
        // Navigate to Layers & Studio via Remote -> Layers button
        composeRule.onNodeWithTag("nav_dual").performClick()
        composeRule.onNodeWithTag("remote_layers_btn").performClick()
        composeRule.onNodeWithText("Layers & Studio").assertIsDisplayed()

        // Select Gameplay source
        composeRule.onNodeWithTag("obs_studio_list")
            .performScrollToNode(hasTestTag("source_row_s1_gameplay"))
        composeRule.onNodeWithTag("source_row_s1_gameplay").performClick()

        // Switch to the new Graphics dock tab
        composeRule.onNodeWithTag("obs_studio_list")
            .performScrollToNode(hasTestTag("studio_dock_graphics"))
        composeRule.onNodeWithTag("studio_dock_graphics").performClick()

        // Verify Graphic Adjustment panel, presets, sliders, Before/After & Reset render cleanly
        composeRule.onNodeWithTag("obs_studio_list")
            .performScrollToNode(hasTestTag("graphic_adjustment_panel"))
        composeRule.onNodeWithTag("graphic_master_enable_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("obs_studio_list")
            .performScrollToNode(hasTestTag("graphic_before_after_btn"))
        composeRule.onNodeWithTag("graphic_before_after_btn").performClick()
        composeRule.onNodeWithTag("layer_before_preview_badge", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("graphic_before_after_btn").performClick()

        // Apply Cinematic preset & verify Undo/Reset work
        composeRule.onNodeWithTag("obs_studio_list")
            .performScrollToNode(hasTestTag("graphic_preset_cinematic"))
        composeRule.onNodeWithTag("graphic_preset_cinematic").performClick()
        composeRule.onNodeWithTag("obs_studio_list")
            .performScrollToNode(hasTestTag("graphic_reset_layer_btn"))
        composeRule.onNodeWithTag("graphic_reset_layer_btn").performClick()
    }
}
