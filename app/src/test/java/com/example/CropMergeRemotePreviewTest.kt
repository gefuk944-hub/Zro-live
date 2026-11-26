package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.studio.CropAspectPreset
import com.example.studio.CropEdge
import com.example.studio.LayerSourceType
import com.example.studio.SceneSlot
import com.example.studio.StudioPersistence
import com.example.studio.StudioWorkspaceReducer
import com.example.studio.StudioWorkspaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36])
class CropMergeRemotePreviewTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `layer cropping supports 4 independent edges safe clamping aspect presets and reset`() {
        val initial = StudioWorkspaceState()
        val id = "s1_gameplay"

        val edgeCropped = StudioWorkspaceReducer.cropLayerEdge(
            StudioWorkspaceReducer.cropLayerEdge(initial, id, CropEdge.LEFT, 0.25f),
            id,
            CropEdge.TOP,
            0.15f
        )
        val afterEdge = edgeCropped.activeScene.layers.first { it.id == id }
        assertEquals(0.25f, afterEdge.cropLeft, 0.01f)
        assertEquals(0.15f, afterEdge.cropTop, 0.01f)
        assertEquals(0f, afterEdge.cropRight, 0.01f)
        assertEquals(0f, afterEdge.cropBottom, 0.01f)

        // Safe clamping prevents cropLeft + cropRight from exceeding 0.85f
        val overCropped = StudioWorkspaceReducer.setLayerCrop(
            edgeCropped,
            id,
            cropLeft = 0.60f,
            cropRight = 0.60f
        )
        val clamped = overCropped.activeScene.layers.first { it.id == id }
        assertTrue(clamped.cropLeft + clamped.cropRight <= 0.851f)

        // Aspect ratio preset 1:1 applies balanced horizontal crop on 16:9 layer
        val square = StudioWorkspaceReducer.applyCropAspectPreset(initial, id, CropAspectPreset.RATIO_1_1)
        val sqLayer = square.activeScene.layers.first { it.id == id }
        assertTrue(sqLayer.cropLeft > 0.1f && sqLayer.cropRight > 0.1f)

        // Reset restores all 4 edges to 0f
        val reset = StudioWorkspaceReducer.resetLayerCrop(square, id)
        val resetLayer = reset.activeScene.layers.first { it.id == id }
        assertEquals(0f, resetLayer.cropLeft, 0.001f)
        assertEquals(0f, resetLayer.cropRight, 0.001f)
        assertEquals(0f, resetLayer.cropTop, 0.001f)
        assertEquals(0f, resetLayer.cropBottom, 0.001f)
    }

    @Test
    fun `layer merging persists merged children through JSON and unmerges cleanly`() {
        val initial = StudioWorkspaceState()
        val originalCount = initial.activeScene.layers.size
        val mergeIds = listOf("s1_gameplay", "s1_branding")

        val mergedState = StudioWorkspaceReducer.mergeLayers(initial, mergeIds, "Game + Branding Bundle")
        assertEquals(originalCount - 1, mergedState.activeScene.layers.size)

        val mergedLayer = mergedState.activeScene.layers.firstOrNull { it.type == LayerSourceType.MERGED_GROUP }
        assertNotNull(mergedLayer)
        assertEquals("Game + Branding Bundle", mergedLayer!!.name)
        assertEquals(2, mergedLayer.mergedChildren.size)

        // Round-trip JSON persistence preserves mergedChildren
        val json = StudioPersistence.serializeWorkspace(mergedState)
        val restored = StudioPersistence.deserializeWorkspace(json)
        val restoredMerged = restored.activeScene.layers.firstOrNull { it.id == mergedLayer.id }
        assertNotNull(restoredMerged)
        assertEquals(LayerSourceType.MERGED_GROUP, restoredMerged!!.type)
        assertEquals(2, restoredMerged.mergedChildren.size)

        // Unmerge restores original layer count and individual sources
        val unmerged = StudioWorkspaceReducer.unmergeLayer(restored, restoredMerged.id)
        assertEquals(originalCount, unmerged.activeScene.layers.size)
        assertTrue(unmerged.activeScene.layers.none { it.type == LayerSourceType.MERGED_GROUP })
        assertTrue(unmerged.activeScene.layers.any { it.type == LayerSourceType.GAMEPLAY_SCREEN })
        assertTrue(unmerged.activeScene.layers.any { it.id == "s1_branding" })
    }

    @Test
    fun `rename layer and rename screen capture sync across scenes`() {
        val initial = StudioWorkspaceState()
        val renamedLayerState = StudioWorkspaceReducer.renameLayer(initial, "s1_branding", "Pro Squad Banner")
        assertEquals("Pro Squad Banner", renamedLayerState.activeScene.layers.first { it.id == "s1_branding" }.name)

        val renamedCaptureState = StudioWorkspaceReducer.renameGameplayCaptureLayers(renamedLayerState, "BGMI Ultra Feed")
        assertEquals(
            "BGMI Ultra Feed",
            renamedCaptureState.scenes[SceneSlot.GAMEPLAY_LIVE]!!.layers.first { it.type == LayerSourceType.GAMEPLAY_SCREEN }.name
        )
        // Scene 2 is now 16:9 Starting Soon ("STREAMING START SOON")
        assertEquals(
            "STREAMING START SOON",
            renamedCaptureState.scenes[SceneSlot.SHORTS_VERTICAL]!!.layers.first().textContent
        )
    }

    @Test
    fun `studio crop merge and rename controls work end to end in UI`() {
        // App launches cleanly without white-screen crash; Studio is now opened via Remote -> Layers
        composeRule.onNodeWithTag("nav_dual").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("remote_layers_btn").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Toggle canvas Crop mode in horizontal toolbar
        composeRule.onNodeWithTag("studio_crop_mode_btn").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Rename Screen Capture via dialog
        composeRule.onNodeWithTag("obs_studio_list").performScrollToNode(hasTestTag("studio_rename_capture_btn"))
        composeRule.onNodeWithTag("studio_rename_capture_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rename_capture_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("rename_capture_input").performTextClearance()
        composeRule.onNodeWithTag("rename_capture_input").performTextInput("Free Fire Live")
        composeRule.onNodeWithTag("rename_capture_confirm_btn").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("Free Fire Live", substring = true).fetchSemanticsNodes().isNotEmpty())

        // Rename an individual layer via dialog
        composeRule.onNodeWithTag("obs_studio_list").performScrollToNode(hasTestTag("layer_rename_btn_s1_branding"))
        composeRule.onNodeWithTag("layer_rename_btn_s1_branding").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rename_layer_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("rename_layer_input").performTextClearance()
        composeRule.onNodeWithTag("rename_layer_input").performTextInput("Channel Banner HD")
        composeRule.onNodeWithTag("rename_layer_confirm_btn").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("Channel Banner HD", substring = true).fetchSemanticsNodes().isNotEmpty())

        // Open Merge Layers dialog, select 2 layers, merge!
        composeRule.onNodeWithTag("obs_studio_list").performScrollToNode(hasTestTag("studio_merge_layers_btn"))
        composeRule.onNodeWithTag("studio_merge_layers_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("merge_layers_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("merge_pick_s1_branding").performClick()
        composeRule.onNodeWithTag("merge_layers_name_input").performTextClearance()
        composeRule.onNodeWithTag("merge_layers_name_input").performTextInput("Combined HUD")
        composeRule.onNodeWithTag("merge_layers_confirm_btn").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("Combined HUD", substring = true).fetchSemanticsNodes().isNotEmpty())

        // Switch to Transform tab and verify dedicated Layer Cropping controls
        composeRule.onNodeWithTag("obs_studio_list").performScrollToNode(hasTestTag("studio_dock_transform"))
        composeRule.onNodeWithTag("studio_dock_transform").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("obs_studio_list").performScrollToNode(hasTestTag("studio_crop_section"))
        composeRule.onNodeWithTag("crop_preset_ratio_1_1").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("layer_reset_crop_btn").assertIsDisplayed().performClick()
    }

    @Test
    fun `remote control displays full live studio preview synced across Scene 1 and Scene 2 with live editing`() {
        composeRule.onNodeWithTag("nav_dual").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Full Live Studio Preview & OBS Canvas render directly in Remote Control
        composeRule.onNodeWithTag("remote_live_studio_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("studio_canvas_surface").assertIsDisplayed()

        // Tap Scene 2 (Starting Soon 16:9) and verify full live preview updates immediately
        composeRule.onNodeWithTag("remote_preview_scene_shorts_vertical").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Live Studio Preview — Starting Soon", substring = true).assertIsDisplayed()

        // Tap Scene 1 (Gameplay) and verify preview switches back immediately
        composeRule.onNodeWithTag("remote_preview_scene_gameplay_live").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Live Studio Preview — Gameplay", substring = true).assertIsDisplayed()

        // Verify interactive Edit Layers & Crop toggles and Rename Capture button are present and functional
        composeRule.onNodeWithTag("remote_edit_layers_toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("remote_crop_mode_toggle").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("remote_rename_capture_btn").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rename_capture_dialog").assertIsDisplayed()
    }
}
