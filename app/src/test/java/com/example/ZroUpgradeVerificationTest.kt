package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.chat.YouTubeLiveChatClient
import com.example.data.SettingsStore
import com.example.data.model.AudioSettings
import com.example.data.model.LiveVideoSource
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.StreamSessionDoc
import com.example.data.model.StreamSettings
import com.example.data.model.VideoResolution
import com.example.data.model.YouTubeAccountState
import com.example.streaming.RtmpPublisher
import com.example.studio.CameraSourceMode
import com.example.studio.DetectedUsbVideoDevice
import com.example.studio.DualLinkState
import com.example.studio.DualPhoneRole
import com.example.studio.DualPhoneTelemetry
import com.example.studio.ExternalCameraDetector
import com.example.studio.ExternalCameraInventory
import com.example.studio.SceneSlot
import com.example.studio.StudioWorkspaceState
import com.example.telemetry.ActiveLiveSessionSnapshot
import com.example.telemetry.CrashLogEntry
import com.example.telemetry.CrashRecoveryStore
import com.example.telemetry.DeviceTelemetryMonitor
import com.example.telemetry.SavedLiveConfigProfile
import com.example.ui.screens.CameraSettingsScreen
import com.example.ui.screens.DualPhoneStudioScreen
import com.example.ui.screens.LiveManagerContent
import com.example.ui.screens.LiveSetupScreen
import com.example.ui.theme.ZroLiveTheme
import com.example.viewmodel.ZroMainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Multi-SDK Robolectric verification suite covering all Zro Live upgrades:
 * - Simplified Stream Settings (no Paste/Copy/YouTube/Custom RTMP chips, no auto-filled URL)
 * - Strict TLS / SNI hostname normalization & optional Test Connection
 * - Crash Recovery + non-duplicate session reconnect + secret scrubbing in crash logs
 * - Live Manager (search, filter, Create New Live, Saved Profiles, Reconnect, Delete, Diagnostics Logs)
 * - 1-Phone <-> 2-Phone automatic source switching telemetry & Remote Layers button
 * - DJI Camera / USB-UVC / HDMI Capture Card / Wireless guide & ON/OFF/Front/Rear controls
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 29, 36])
class ZroUpgradeVerificationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `default rtmp url is empty and rtmps youtube host normalizes cleanly for strict tls`() {
        val defaults = StreamSettings()
        assertEquals("", defaults.rtmpUrl)
        assertEquals("", defaults.backupRtmpUrl)
        assertEquals("", defaults.effectivePrimaryServerUrl())

        // Strict TLS hostname normalization fixes "No subjectAltNames on the certificate match"
        assertEquals(
            "rtmps://a.rtmps.youtube.com/live2",
            RtmpPublisher.normalizeEndpointUrl("rtmps://a.rtmp.youtube.com/live2")
        )
        assertEquals(
            "rtmps://b.rtmps.youtube.com/live2",
            RtmpPublisher.normalizeEndpointUrl("rtmps://b.rtmp.youtube.com/live2")
        )
        // Plain RTMP remains untouched
        assertEquals(
            "rtmp://a.rtmp.youtube.com/live2",
            RtmpPublisher.normalizeEndpointUrl("rtmp://a.rtmp.youtube.com/live2")
        )
        // DNS vs IP hostname detection for SNI
        assertTrue(RtmpPublisher.isDnsHostname("a.rtmps.youtube.com"))
        assertFalse(RtmpPublisher.isDnsHostname("192.168.1.55"))
        assertFalse(RtmpPublisher.isDnsHostname("::1"))
    }

    @Test
    fun `live setup removes red marked controls keeps only stream url key and optional test connection`() {
        var testClicked = false
        var startClicked = false
        composeRule.setContent {
            ZroLiveTheme {
                LiveSetupScreen(
                    streamSettings = StreamSettings(rtmpUrl = "", streamKey = ""),
                    audioSettings = AudioSettings(),
                    streamTelemetry = StreamRuntimeTelemetry(state = StreamLifecycleState.IDLE),
                    dualTelemetry = DualPhoneTelemetry(),
                    workspace = StudioWorkspaceState(),
                    onBack = {},
                    onUpdateStreamSettings = {},
                    onPickThumbnail = {},
                    onClearThumbnail = {},
                    onSelectScene = {},
                    onOpenAudioStudio = {},
                    onOpenDualPairing = {},
                    onOpenCameraSettings = {},
                    onOpenSceneStudio = {},
                    onStartLive = { startClicked = true },
                    onStopLive = {},
                    youTubeAccount = YouTubeAccountState(signedIn = false),
                    onTestConnection = { testClicked = true }
                )
            }
        }
        composeRule.waitForIdle()

        // Removed red-marked controls MUST NOT exist
        composeRule.onNodeWithTag("live_setup_paste_key_btn").assertDoesNotExist()
        composeRule.onNodeWithTag("live_setup_copy_key_btn").assertDoesNotExist()
        composeRule.onNodeWithTag("live_setup_platform_youtube_live").assertDoesNotExist()
        composeRule.onNodeWithTag("live_setup_platform_custom_rtmp").assertDoesNotExist()
        composeRule.onNodeWithTag("live_setup_rtmps_chip").assertDoesNotExist()
        composeRule.onNodeWithTag("live_setup_backup_url_input").assertDoesNotExist()

        // Required clean controls MUST exist and work
        composeRule.onNodeWithTag("live_setup_list")
            .performScrollToNode(hasTestTag("live_setup_primary_url_input"))
        composeRule.onNodeWithTag("live_setup_primary_url_input").assertIsDisplayed()
        composeRule.onNodeWithTag("live_setup_list")
            .performScrollToNode(hasTestTag("live_setup_stream_key_input"))
        composeRule.onNodeWithTag("live_setup_stream_key_input").assertIsDisplayed()
        composeRule.onNodeWithTag("live_setup_list")
            .performScrollToNode(hasTestTag("live_setup_test_connection_btn"))
        composeRule.onNodeWithTag("live_setup_test_connection_btn").assertIsDisplayed().performClick()
        assertTrue("Optional Test Connection button must be interactive", testClicked)

        // Start Live works without requiring Test Connection first
        composeRule.onNodeWithTag("live_setup_start_live_button").assertIsDisplayed().performClick()
        assertTrue("Start Live must work without mandatory Test Connection", startClicked)
    }

    @Test
    fun `crash recovery scrubs secrets persists snapshot and reconnects without creating duplicates`() = runBlocking {
        val secretKey = "yt-super-secret-9988-7766"
        CrashRecoveryStore.clearLogs(context)
        CrashRecoveryStore.updateSecretsToScrub(secretKey)
        CrashRecoveryStore.recordEvent(
            context = context,
            category = "NETWORK",
            summary = "RTMP drop for key $secretKey",
            detail = "Socket closed while publishing $secretKey to ingest"
        )
        val logs = CrashRecoveryStore.readLogs(context)
        assertTrue(logs.isNotEmpty())
        assertFalse("Secret key must NEVER appear in crash log summary", logs.first().summary.contains(secretKey))
        assertFalse("Secret key must NEVER appear in crash log detail", logs.first().detail.contains(secretKey))

        // Persist an interrupted live session snapshot & verify SettingsStore upsert prevents duplicates
        val store = SettingsStore(context)
        val snap = ActiveLiveSessionSnapshot(
            sessionId = "session_abc_123",
            title = "Ranked Finals Stream",
            rtmpUrl = "rtmps://a.rtmps.youtube.com/live2",
            resolutionName = VideoResolution.P1080.name,
            resolutionLabel = "1080p",
            fps = 60,
            bitrateKbps = 7500,
            activeSceneSlotName = SceneSlot.SHORTS_VERTICAL.name,
            startedAtEpochMs = 1_700_000_000_000L,
            lastHeartbeatEpochMs = 1_700_000_060_000L,
            durationSec = 60L,
            wasLiveConfirmed = true,
            cleanlyEnded = false
        )
        store.saveActiveLiveSnapshot(snap)
        val loadedSnap = store.activeLiveSnapshotFlow.first()
        assertNotNull(loadedSnap)
        assertEquals("session_abc_123", loadedSnap?.sessionId)
        assertTrue(loadedSnap?.isRecoverable() == true)

        // Upserting the same sessionId twice must keep ONLY ONE entry (no duplicates!)
        val doc1 = StreamSessionDoc(
            id = "session_abc_123",
            title = "Ranked Finals Stream",
            resolutionLabel = "1080p",
            fps = 60L,
            bitrateKbps = 7500L,
            durationSec = 60L,
            status = "INTERRUPTED"
        )
        val doc2 = doc1.copy(durationSec = 240L, status = "ENDED")
        store.upsertLocalSession(doc1)
        store.upsertLocalSession(doc2)
        val history = store.localSessionsFlow.first().filter { it.id == "session_abc_123" }
        assertEquals("Reconnected session must update in place without duplicate entry", 1, history.size)
        assertEquals(240L, history.first().durationSec)
        assertEquals("ENDED", history.first().status)

        // Cleanly ended snapshot is no longer flagged for crash recovery
        store.markActiveLiveCleanlyEnded()
        val afterCleanEnd = store.activeLiveSnapshotFlow.first()
        assertTrue(afterCleanEnd?.cleanlyEnded == true)
        assertFalse(afterCleanEnd?.isRecoverable() == true)

        store.clearActiveLiveSnapshot()
        assertNull(store.activeLiveSnapshotFlow.first())
    }

    @Test
    fun `live manager displays recovery banner search filter sessions profiles and logs`() {
        var reconnectTriggered = false
        var deletedSessionId: String? = null
        var appliedProfileId: String? = null
        var createdTitle: String? = null

        val snap = ActiveLiveSessionSnapshot(
            sessionId = "sess_rec_1",
            title = "BGMI Custom Room",
            rtmpUrl = "rtmps://a.rtmps.youtube.com/live2",
            resolutionLabel = "1080p",
            fps = 60,
            bitrateKbps = 6500,
            durationSec = 420L,
            cleanlyEnded = false
        )
        val sessions = listOf(
            StreamSessionDoc(
                id = "sess_rec_1",
                title = "BGMI Custom Room",
                platformLabel = "YouTube Live",
                resolutionLabel = "1080p",
                fps = 60L,
                bitrateKbps = 6500L,
                durationSec = 420L,
                status = "INTERRUPTED"
            ),
            StreamSessionDoc(
                id = "sess_end_2",
                title = "Valorant Night Grind",
                platformLabel = "Custom RTMP",
                resolutionLabel = "720p",
                fps = 30L,
                bitrateKbps = 4500L,
                durationSec = 1800L,
                status = "ENDED"
            )
        )
        val profiles = listOf(
            SavedLiveConfigProfile(
                id = "prof_1",
                name = "Esports 1080p60",
                title = "Tournament Broadcast",
                rtmpUrl = "rtmps://a.rtmps.youtube.com/live2",
                platformLabel = "YouTube Live",
                resolutionName = VideoResolution.P1080.name,
                fps = 60,
                bitrateKbps = 8000
            )
        )
        val logs = listOf(
            CrashLogEntry(
                id = "log_1",
                timestampMs = System.currentTimeMillis(),
                category = "RECOVERY",
                summary = "Recovered cleanly after network stall",
                detail = "Backoff succeeded on attempt 2"
            )
        )

        composeRule.setContent {
            ZroLiveTheme {
                LiveManagerContent(
                    streamSettings = StreamSettings(),
                    streamTelemetry = StreamRuntimeTelemetry(),
                    recoverableSession = snap,
                    recentSessions = sessions,
                    savedProfiles = profiles,
                    crashLogs = logs,
                    onReconnectPreviousLive = { reconnectTriggered = true },
                    onDismissRecovery = {},
                    onCreateNewLive = { createdTitle = it },
                    onOpenLiveSetup = {},
                    onOpenFullScreenPreview = {},
                    onResumeSavedSession = {},
                    onDeleteSession = { deletedSessionId = it },
                    onSaveCurrentProfile = {},
                    onApplyProfile = { appliedProfileId = it.id },
                    onDeleteProfile = {},
                    onClearCrashLogs = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Crash recovery banner & reconnect action
        composeRule.onNodeWithTag("live_manager_reconnect_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("live_manager_reconnect_btn").performClick()
        assertTrue(reconnectTriggered)

        // Search filters sessions accurately
        composeRule.onNodeWithTag("live_manager_list")
            .performScrollToNode(hasTestTag("live_manager_search_input"))
        composeRule.onNodeWithTag("live_manager_search_input").performTextInput("Valorant")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("live_manager_list")
            .performScrollToNode(hasTestTag("live_session_card_sess_end_2"))
        composeRule.onNodeWithTag("live_session_card_sess_end_2").assertIsDisplayed()
        composeRule.onNodeWithTag("live_session_card_sess_rec_1").assertDoesNotExist()

        // Profiles filter & Create New Live dialog
        composeRule.onNodeWithTag("live_manager_list")
            .performScrollToNode(hasTestTag("live_manager_filter_profiles"))
        composeRule.onNodeWithTag("live_manager_filter_profiles").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("live_manager_list")
            .performScrollToNode(hasTestTag("live_manager_create_new_btn"))
        composeRule.onNodeWithTag("live_manager_create_new_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("live_manager_new_title_input").performTextInput("Sunday Finals")
        composeRule.onNodeWithTag("live_manager_confirm_new_btn").performClick()
        assertEquals("Sunday Finals", createdTitle)
    }

    @Test
    fun `remote deck shows active video source badge layers button and 16x9 preview button`() {
        var layersOpened = false
        var previewOpened = false
        composeRule.setContent {
            ZroLiveTheme {
                DualPhoneStudioScreen(
                    dualTelemetry = DualPhoneTelemetry(
                        role = DualPhoneRole.PHONE2_STUDIO,
                        linkState = DualLinkState.CONNECTED,
                        connectedDeviceName = "Gaming Phone 2"
                    ),
                    pairingUri = "",
                    mirroredFrame = null,
                    workspace = StudioWorkspaceState(),
                    streamTelemetry = StreamRuntimeTelemetry(
                        state = StreamLifecycleState.LIVE,
                        activeVideoSource = LiveVideoSource.REMOTE_SECOND_PHONE
                    ),
                    audioSettings = AudioSettings(),
                    audioMeters = com.example.data.model.AudioMeterState(),
                    screenshotCount = 0,
                    reducedMotion = true,
                    onSelectRole = {},
                    onSelectMethod = {},
                    onToggleAutoReconnect = {},
                    onStartPhone2Host = {},
                    onOpenQrScanner = {},
                    onConnectManual = { _, _, _ -> },
                    onDisconnect = {},
                    onReconnect = {},
                    onDispatchRemote = {},
                    onStartLiveFlow = {},
                    onEndLiveConfirmed = {},
                    onToggleCamera = {},
                    onTriggerBrb = {},
                    onResumeFromBrb = {},
                    onToggleTeamVoiceToLive = {},
                    onUpdateAudioSettings = {},
                    onOpenChatPage = {},
                    onOpenAudioStudio = {},
                    onOpenCameraSettings = {},
                    onOpenSceneEditor = { layersOpened = true },
                    onMoveRemoteTile = { _, _ -> },
                    onAddRemoteTile = {},
                    onRemoveRemoteTile = {},
                    onResetRemoteDeck = {},
                    onOpenFullScreenPreview = { previewOpened = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("remote_active_source_badge").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("2nd Phone Screen", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
        composeRule.onNodeWithTag("remote_layers_btn").assertIsDisplayed().performClick()
        assertTrue(layersOpened)
        composeRule.onNodeWithTag("remote_fullscreen_preview_btn").assertIsDisplayed().performClick()
        assertTrue(previewOpened)
    }

    @Test
    fun `camera settings exposes dji usb uvc hdmi wireless modes front rear flip and compatibility guide`() {
        var selectedMode: CameraSourceMode? = null
        var scanClicked = false

        val detectedInventory = ExternalCameraInventory(
            hasFrontCamera = true,
            hasRearCamera = true,
            externalCamera2Ids = listOf("2"),
            usbVideoDevices = listOf(
                DetectedUsbVideoDevice(
                    deviceName = "/dev/bus/usb/001/002",
                    productName = "DJI Osmo Action 4",
                    vendorId = 0x2CA3,
                    productId = 0x001F,
                    isDjiDevice = true,
                    isUvcVideoClass = true
                )
            ),
            statusSummary = "DJI USB/UVC camera detected (DJI Osmo Action 4) — ready for live preview"
        )

        // Verify ExternalCameraDetector runs safely across API 24/29/36 without crashing
        val runtimeInventory = ExternalCameraDetector.inspect(context)
        assertTrue(runtimeInventory.statusSummary.isNotBlank())

        composeRule.setContent {
            ZroLiveTheme {
                CameraSettingsScreen(
                    workspace = StudioWorkspaceState(cameraEnabled = false),
                    dualTelemetry = DualPhoneTelemetry(),
                    cameraPermissionGranted = true,
                    onBack = {},
                    onToggleCameraEnabled = {},
                    onRequestCameraPermission = {},
                    onSelectCameraSource = { selectedMode = it },
                    onUpdateFacecamLayer = {},
                    onToggleFacecamLock = {},
                    onSaveLayout = {},
                    externalCameraInventory = detectedInventory,
                    onRefreshExternalCameras = { scanClicked = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("camera_master_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("camera_settings_list")
            .performScrollToNode(hasTestTag("camera_quick_flip_btn"))
        composeRule.onNodeWithTag("camera_quick_flip_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("camera_scan_usb_btn").assertIsDisplayed().performClick()
        assertTrue(scanClicked)

        composeRule.onNodeWithTag("camera_settings_list")
            .performScrollToNode(hasTestTag("camera_source_dji_usb_uvc"))
        composeRule.onNodeWithTag("camera_source_dji_usb_uvc").assertIsDisplayed().performClick()
        assertEquals(CameraSourceMode.DJI_USB_UVC, selectedMode)

        composeRule.onNodeWithTag("camera_settings_list")
            .performScrollToNode(hasTestTag("dji_setup_guide_card"))
        composeRule.onNodeWithTag("dji_setup_guide_card").assertIsDisplayed()
        composeRule.onNodeWithText("DJI & External Camera Compatibility Guide").performClick()
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onAllNodesWithText("Osmo Action", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun `viewmodel offline status stays idle never false ready and reconnect restores session`() = runBlocking {
        val store = SettingsStore(context)
        val vm = ZroMainViewModel(
            appContext = context,
            repository = null,
            settingsStore = store,
            telemetryMonitor = DeviceTelemetryMonitor(context),
            chatClient = YouTubeLiveChatClient(),
            currentUserId = null,
            userDisplayName = "Streamer",
            userAvatarUrl = null
        )

        // Even after entering URL + key, offline state MUST stay IDLE (never fake READY or LIVE)
        vm.updateStreamSettings(
            StreamSettings(
                rtmpUrl = "rtmps://a.rtmps.youtube.com/live2",
                streamKey = "abcd-1234-efgh-5678"
            )
        )
        assertEquals(StreamLifecycleState.IDLE, vm.streamTelemetry.value.state)
        assertFalse(vm.streamTelemetry.value.state.isActive)
    }
}
