package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 29, 36])
class MainActivityLaunchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `home live setup obs studio 4 corners remote 3 column deck brb chat camera and back navigation work on android 10 and 16`() {
        // 1. Minimal Home renders immediately with zero login wall & 16:9 Live Output Preview
        composeRule.onNodeWithText("Zro live").assertIsDisplayed()
        composeRule.onNodeWithTag("home_fullscreen_preview_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("home_dashboard_list")
            .performScrollToNode(hasTestTag("home_live_setup_button"))
        composeRule.onNodeWithTag("home_live_setup_button").assertIsDisplayed()
        composeRule.onNodeWithTag("home_dashboard_list")
            .performScrollToNode(hasTestTag("dashboard_start_stream_button"))
        composeRule.onNodeWithTag("dashboard_start_stream_button").assertIsDisplayed()

        // Verify Studio tab is removed from Bottom Navigation (4 tabs only: Home, Remote, Live, Settings)
        composeRule.onNodeWithTag("nav_studio").assertDoesNotExist()
        composeRule.onNodeWithTag("nav_home").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_dual").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_live").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_settings").assertIsDisplayed()

        // 2. Home -> Live Setup -> Back returns cleanly to Home
        composeRule.onNodeWithTag("home_live_setup_button").performClick()
        composeRule.onNodeWithText("Live Setup").assertIsDisplayed()
        composeRule.onNodeWithTag("live_setup_start_live_button").assertIsDisplayed()
        composeRule.onNodeWithTag("live_setup_back_button").performClick()
        composeRule.onNodeWithTag("home_dashboard_list")
            .performScrollToNode(hasTestTag("home_live_setup_button"))
        composeRule.onNodeWithTag("home_live_setup_button").assertIsDisplayed()

        // 3. Remote Control -> Layers button opens complete OBS-style Layers & Studio panel with Back button
        composeRule.onNodeWithTag("nav_dual").performClick()
        composeRule.onNodeWithText("Remote Control").assertIsDisplayed()
        composeRule.onNodeWithTag("remote_layers_btn").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Layers & Studio").assertIsDisplayed()
        composeRule.onNodeWithTag("scene_tab_gameplay_live").assertIsDisplayed()
        composeRule.onNodeWithTag("grid_mode_grid_2x6").assertIsDisplayed()
        composeRule.onNodeWithTag("grid_mode_grid_4x4").performClick()
        composeRule.onNodeWithTag("studio_save_layout_btn").assertIsDisplayed()
        // Select Gameplay source from Sources dock -> 4 corner handles appear immediately on pinned canvas!
        composeRule.onNodeWithTag("obs_studio_list")
            .performScrollToNode(hasTestTag("source_row_s1_gameplay"))
        composeRule.onNodeWithTag("source_row_s1_gameplay").performClick()
        composeRule.onNodeWithTag("studio_canvas_surface").assertIsDisplayed()
        composeRule.onNodeWithTag("handle_tl", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("handle_tr", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("handle_bl", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("handle_br", useUnmergedTree = true).assertIsDisplayed()
        // Back from Layers panel returns directly to Remote Control
        composeRule.onNodeWithTag("studio_back_button").performClick()
        composeRule.onNodeWithText("Remote Control").assertIsDisplayed()

        // 4. Remote Control Deck: 3-column button grid, Master Volume + Team Voice Mute, One-Touch BRB/Resume, Chat -> Back to Remote
        composeRule.onNodeWithTag("remote_tile_start_live").assertIsDisplayed()
        // Scroll to Row 4 (BRB & Chat) on compact screen and trigger One-Touch BRB
        composeRule.onNodeWithTag("dual_studio_list")
            .performScrollToNode(hasTestTag("remote_tile_brb_privacy"))
        composeRule.onNodeWithTag("remote_tile_brb_privacy").assertIsDisplayed()
        composeRule.onNodeWithTag("remote_tile_open_chat").assertIsDisplayed()
        composeRule.onNodeWithTag("remote_tile_brb_privacy").performClick()
        // Scroll up to Resume Game Scene banner & restore saved gameplay scene
        composeRule.onNodeWithTag("dual_studio_list")
            .performScrollToNode(hasTestTag("brb_resume_game_scene_btn"))
        composeRule.onNodeWithTag("brb_resume_game_scene_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("brb_resume_game_scene_btn").performClick()
        // Open Chat from Remote Deck -> Back returns directly to Remote Control (not Home!)
        composeRule.onNodeWithTag("dual_studio_list")
            .performScrollToNode(hasTestTag("remote_tile_open_chat"))
        composeRule.onNodeWithTag("remote_tile_open_chat").performClick()
        composeRule.onNodeWithText("Live Chat").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_back_button").performClick()
        composeRule.onNodeWithText("Remote Control").assertIsDisplayed()
        // Verify Master Volume slider and single Team Voice Mute button exist
        composeRule.onNodeWithTag("dual_studio_list")
            .performScrollToNode(hasTestTag("remote_team_voice_mute_btn"))
        composeRule.onNodeWithTag("remote_master_volume_slider").assertIsDisplayed()
        composeRule.onNodeWithTag("remote_team_voice_mute_btn").assertIsDisplayed()

        // 5. Camera Settings subpage & Back button
        composeRule.onNodeWithTag("nav_home").performClick()
        composeRule.onNodeWithTag("home_dashboard_list")
            .performScrollToNode(hasTestTag("home_status_camera"))
        composeRule.onNodeWithTag("home_status_camera").performClick()
        composeRule.onNodeWithText("Camera Settings").assertIsDisplayed()
        composeRule.onNodeWithTag("camera_master_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("camera_settings_back_button").performClick()
        composeRule.onNodeWithTag("home_dashboard_list")
            .performScrollToNode(hasTestTag("home_live_setup_button"))
        composeRule.onNodeWithTag("home_live_setup_button").assertIsDisplayed()

        // 6. Voice Room opens directly with Back button and zero login wall
        composeRule.onNodeWithTag("home_dashboard_list")
            .performScrollToNode(hasTestTag("home_voice_tile"))
        composeRule.onNodeWithTag("home_voice_tile").performClick()
        composeRule.onNodeWithText("Voice Room").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_room_back_button").performClick()
        composeRule.onNodeWithTag("home_dashboard_list")
            .performScrollToNode(hasTestTag("home_live_setup_button"))
        composeRule.onNodeWithTag("home_live_setup_button").assertIsDisplayed()
    }
}
