package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsInputComponent
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsInputComponent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.chat.YouTubeLiveChatClient
import com.example.data.SettingsStore
import com.example.data.model.CaptureMode
import com.example.data.model.LiveVideoSource
import com.example.data.model.StreamLifecycleState
import com.example.data.model.YouTubeChatState
import com.example.data.model.ZroAppSettings
import com.example.dualphone.DualPhoneProtocol
import com.example.dualphone.DualRemoteCommand
import com.example.dualphone.WifiDirectHelper
import com.example.streaming.RtmpPublisher
import com.example.streaming.StreamNotificationManager
import com.example.streaming.ZroStreamCoordinator
import com.example.studio.DualLinkState
import com.example.studio.DualPhoneRole
import com.example.telemetry.DeviceTelemetryMonitor
import com.example.ui.components.FullScreen16x9PreviewDialog
import com.example.ui.components.QrScannerDialog
import com.example.ui.screens.CameraSettingsScreen
import com.example.ui.screens.ChatControlScreen
import com.example.ui.screens.DualPhoneStudioScreen
import com.example.ui.screens.DualStudioSection
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.screens.LiveSetupScreen
import com.example.ui.screens.LiveStudioScreen
import com.example.ui.screens.LiveStudioTab
import com.example.ui.screens.ObsSceneStudioScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VoiceRoomScreen
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroLiveTheme
import com.example.viewmodel.ZroMainViewModel

enum class ZroNavTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("Home", Icons.Rounded.Home, Icons.Outlined.Home),
    DUAL("Remote", Icons.Rounded.SettingsInputComponent, Icons.Outlined.SettingsInputComponent),
    LIVE("Live", Icons.Rounded.Sensors, Icons.Outlined.Sensors),
    SETTINGS("Settings", Icons.Rounded.Settings, Icons.Outlined.Settings)
}

/**
 * Pushable subpages managed on a real back stack so Back always returns to
 * the exact previous page with state preserved (never resetting or forcing Home).
 */
private enum class AppSubpage {
    LIVE_SETUP,
    LAYERS_PANEL,
    CHAT,
    CAMERA_SETTINGS,
    AUDIO_STUDIO,
    DUAL_PAIRING,
    VOICE_ROOM
}

private enum class PendingCapturePurpose {
    PREVIEW_ONLY,
    GO_LIVE_DIRECT
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Paint dark obsidian immediately on window decor to prevent any white frame on startup
        window.setBackgroundDrawableResource(R.color.zro_obsidian)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        handleDeepLinkIntent(intent)
        setContent {
            ZroAppRoot()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val targetName = intent?.getStringExtra(StreamNotificationManager.EXTRA_NAV_TARGET) ?: return
        val target = ZroStreamCoordinator.DeepLinkTarget.entries.firstOrNull { it.name == targetName } ?: return
        ZroStreamCoordinator.emitNavEvent(target)
    }
}

@Composable
private fun ZroAppRoot() {
    val context = LocalContext.current
    val settingsStore = remember(context) { SettingsStore(context.applicationContext) }
    val appSettings by settingsStore.settingsFlow.collectAsStateWithLifecycle(initialValue = ZroAppSettings())

    ZroLiveTheme(themeStyle = appSettings.general.themeStyle) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ZroMainShell(settingsStore = settingsStore)
        }
    }
}

@Composable
private fun ZroMainShell(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val localDisplayName = "Mobile Gaming Studio"

    val viewModel: ZroMainViewModel = viewModel(
        key = "zro_local_session",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                ZroMainViewModel(
                    appContext = app,
                    repository = null,
                    settingsStore = settingsStore,
                    telemetryMonitor = DeviceTelemetryMonitor(app),
                    chatClient = YouTubeLiveChatClient(),
                    currentUserId = null,
                    userDisplayName = localDisplayName,
                    userAvatarUrl = null
                )
            }
        }
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppForegroundChanged(true)
                Lifecycle.Event.ON_STOP -> viewModel.onAppForegroundChanged(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val recentSessions by viewModel.recentSessions.collectAsStateWithLifecycle()
    val publicRooms by viewModel.publicVoiceRooms.collectAsStateWithLifecycle()
    val activeRoom by viewModel.activeRoom.collectAsStateWithLifecycle()
    val participants by viewModel.roomParticipants.collectAsStateWithLifecycle()
    val selfStreamConsent by viewModel.selfStreamConsent.collectAsStateWithLifecycle()
    val teammateMuted by viewModel.teammateLocalMuted.collectAsStateWithLifecycle()
    val teammateVolumes by viewModel.teammateLocalVolumes.collectAsStateWithLifecycle()
    val voiceLoading by viewModel.voiceActionLoading.collectAsStateWithLifecycle()
    val deviceTelemetry by viewModel.deviceTelemetry.collectAsStateWithLifecycle()
    val smartRec by viewModel.smartRecommendation.collectAsStateWithLifecycle()
    val streamTelemetry by viewModel.streamTelemetry.collectAsStateWithLifecycle()
    val audioMeters by viewModel.audioMeters.collectAsStateWithLifecycle()
    val chatState by viewModel.chatState.collectAsStateWithLifecycle()
    val snackbarMsg by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val studioWorkspace by viewModel.studioState.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    val dualTelemetry by viewModel.dualTelemetry.collectAsStateWithLifecycle()
    val mirroredFrame by viewModel.mirroredFrame.collectAsStateWithLifecycle()
    val screenshotCount by viewModel.localScreenshotCount.collectAsStateWithLifecycle()
    val youTubeAccount by viewModel.youTubeAccount.collectAsStateWithLifecycle()
    val rtmpTestResult by viewModel.rtmpTestResult.collectAsStateWithLifecycle()
    val isTestingRtmp by viewModel.isTestingRtmp.collectAsStateWithLifecycle()
    val recoverableSession by viewModel.recoverableSession.collectAsStateWithLifecycle()
    val savedLiveProfiles by viewModel.savedLiveProfiles.collectAsStateWithLifecycle()
    val crashLogs by viewModel.crashLogs.collectAsStateWithLifecycle()
    val externalCameraInventory by viewModel.externalCameraInventory.collectAsStateWithLifecycle()
    val connectivitySnapshot by viewModel.connectivitySnapshot.collectAsStateWithLifecycle()
    val wifiDirectState by viewModel.wifiDirectState.collectAsStateWithLifecycle()

    // Real Navigation History: tab history + pushable subpage back stack
    var currentTab by remember { mutableStateOf(ZroNavTab.HOME) }
    val tabBackStack = remember { mutableStateListOf<ZroNavTab>() }
    val subpageStack = remember { mutableStateListOf<AppSubpage>() }
    var studioSubTab by remember { mutableStateOf(LiveStudioTab.LIVE_MANAGER) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showFullScreenPreview by remember { mutableStateOf(false) }
    var cameraPermGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingBgLayerId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val pushSubpage: (AppSubpage) -> Unit = { page ->
        snackbarHostState.currentSnackbarData?.dismiss()
        if (subpageStack.lastOrNull() != page) {
            subpageStack.add(page)
        }
    }

    val popBack: () -> Unit = {
        snackbarHostState.currentSnackbarData?.dismiss()
        when {
            subpageStack.isNotEmpty() -> subpageStack.removeAt(subpageStack.lastIndex)
            tabBackStack.isNotEmpty() -> currentTab = tabBackStack.removeAt(tabBackStack.lastIndex)
            currentTab != ZroNavTab.HOME -> currentTab = ZroNavTab.HOME
        }
    }

    val selectTopTab: (ZroNavTab) -> Unit = { tab ->
        snackbarHostState.currentSnackbarData?.dismiss()
        subpageStack.clear()
        if (currentTab != tab) {
            if (tabBackStack.lastOrNull() != currentTab) {
                tabBackStack.add(currentTab)
            }
            if (tabBackStack.size > 10) tabBackStack.removeAt(0)
            currentTab = tab
        }
    }

    // Privacy-first capture confirmation dialogs
    var pendingCapturePurpose by remember { mutableStateOf(PendingCapturePurpose.PREVIEW_ONLY) }
    var pendingCaptureModeLaunched by remember { mutableStateOf(CaptureMode.GAME_APP_ONLY) }
    var showAndroid13CaptureLimitDialog by remember { mutableStateOf(false) }
    var showFullScreenOptInDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = subpageStack.isNotEmpty() || tabBackStack.isNotEmpty() || currentTab != ZroNavTab.HOME) {
        popBack()
    }

    LaunchedEffect(Unit) {
        ZroStreamCoordinator.navEvents.collect { target ->
            when (target) {
                ZroStreamCoordinator.DeepLinkTarget.LIVE_CONTROLS -> {
                    subpageStack.clear()
                    currentTab = ZroNavTab.LIVE
                    studioSubTab = LiveStudioTab.BROADCAST
                }
                ZroStreamCoordinator.DeepLinkTarget.LIVE_CHAT -> {
                    pushSubpage(AppSubpage.CHAT)
                }
                ZroStreamCoordinator.DeepLinkTarget.VOICE_ROOM -> {
                    pushSubpage(AppSubpage.VOICE_ROOM)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.autoSwitchToRemoteEvent.collect {
            // 2nd phone paired: automatically switch Phone 1 to Remote Control + compact preview
            subpageStack.clear()
            currentTab = ZroNavTab.DUAL
        }
    }

    LaunchedEffect(snackbarMsg) {
        val msg = snackbarMsg
        if (!msg.isNullOrBlank()) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    // --- RUNTIME PERMISSION & ZERO-PERMISSION PHOTO PICKER LAUNCHERS ---

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            when (pendingCapturePurpose) {
                PendingCapturePurpose.PREVIEW_ONLY -> {
                    viewModel.startCapturePreviewWithMediaProjection(
                        resultCode = result.resultCode,
                        data = data,
                        mode = pendingCaptureModeLaunched
                    )
                }
                PendingCapturePurpose.GO_LIVE_DIRECT -> {
                    viewModel.launchBroadcastWithMediaProjection(
                        resultCode = result.resultCode,
                        data = data,
                        mode = pendingCaptureModeLaunched
                    )
                }
            }
        } else {
            viewModel.showMessage("Capture cancelled. Nothing is being recorded.")
        }
    }

    val thumbnailPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.setStreamThumbnailUri(uri.toString())
        }
    }

    val imageOverlayPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.importAndAddMediaLayer(uri, isVideoHint = false)
        }
    }

    val videoOverlayPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.importAndAddMediaLayer(uri, isVideoHint = true)
        }
    }

    val layerBackgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val layerId = pendingBgLayerId
        pendingBgLayerId = null
        if (uri != null && layerId != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.importBackgroundMediaForLayer(layerId, uri)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.showMessage("Notification permission denied. Live status notification may be hidden.")
        }
    }

    var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermGranted = granted
        if (granted) {
            pendingCameraAction?.invoke()
        } else {
            viewModel.showMessage("Camera permission denied. Camera remains OFF.")
        }
        pendingCameraAction = null
    }

    val ensureCameraPermissionAndRun: (() -> Unit) -> Unit = { action ->
        val grantedNow = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        cameraPermGranted = grantedNow
        if (grantedNow) {
            action()
        } else {
            pendingCameraAction = action
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val handleToggleCamera: (Boolean) -> Unit = { enable ->
        if (!enable) {
            viewModel.setCameraEnabled(false)
        } else {
            ensureCameraPermissionAndRun { viewModel.setCameraEnabled(true) }
        }
    }

    var pendingActionAfterMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingActionAfterMic?.invoke()
        } else {
            viewModel.showMessage("Microphone permission is required for voice chat and stream commentary.")
        }
        pendingActionAfterMic = null
    }

    val ensureMicPermissionAndRun: (() -> Unit) -> Unit = { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingActionAfterMic = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val openCameraQrScanner: () -> Unit = {
        ensureCameraPermissionAndRun { showQrScanner = true }
    }

    var pendingWifiDirectAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val wifiDirectPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingWifiDirectAction?.invoke()
        } else {
            viewModel.showMessage("Nearby Wi-Fi Devices permission is required for Wi-Fi Direct P2P pairing.")
        }
        pendingWifiDirectAction = null
    }

    val ensureWifiDirectPermissionAndRun: (() -> Unit) -> Unit = { action ->
        val requiredPerm = WifiDirectHelper.requiredPermissionForSdk()
        if (requiredPerm == null ||
            ContextCompat.checkSelfPermission(context, requiredPerm) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingWifiDirectAction = action
            wifiDirectPermissionLauncher.launch(requiredPerm)
        }
    }

    val launchPrivacyAwareCapture: (PendingCapturePurpose, CaptureMode, Boolean) -> Unit = { purpose, mode, _ ->
        pendingCapturePurpose = purpose
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (mode == CaptureMode.GAME_APP_ONLY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pendingCaptureModeLaunched = CaptureMode.GAME_APP_ONLY
                mediaProjectionLauncher.launch(Api34ProjectionHelper.createSingleAppIntent(mgr))
            } else {
                showAndroid13CaptureLimitDialog = true
            }
        } else {
            // Default 1-phone full-screen capture starts directly without blocking opt-in dialog
            pendingCaptureModeLaunched = CaptureMode.FULL_SCREEN
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Api34ProjectionHelper.createDefaultDisplayIntent(mgr)
            } else {
                mgr.createScreenCaptureIntent()
            }
            mediaProjectionLauncher.launch(intent)
        }
    }

    val requestSelectGameCapture: () -> Unit = {
        launchPrivacyAwareCapture(PendingCapturePurpose.PREVIEW_ONLY, settings.stream.captureMode, true)
    }

    val requestStartBroadcast: () -> Unit = {
        val streamCheck = RtmpPublisher.validateConfig(
            primaryUrl = settings.stream.effectivePrimaryServerUrl(),
            backupUrl = settings.stream.effectiveBackupServerUrl(),
            streamKey = settings.stream.streamKey
        )
        if (!streamCheck.valid) {
            pushSubpage(AppSubpage.LIVE_SETUP)
            viewModel.showMessage(streamCheck.errorMessage ?: "Enter your Stream RTMP URL and Stream Key in Live Setup.")
        } else {
            val secondPhoneConnected = dualTelemetry.linkState == DualLinkState.CONNECTED &&
                dualTelemetry.role == DualPhoneRole.PHONE2_STUDIO
            val proceedStart = {
                when {
                    secondPhoneConnected -> {
                        // 2nd phone is already paired: stream its screen directly & show Remote Control on Phone 1
                        viewModel.startBroadcastFromSecondPhone()
                        subpageStack.clear()
                        currentTab = ZroNavTab.DUAL
                    }
                    streamTelemetry.captureActive && ZroStreamCoordinator.isCaptureActive() -> {
                        viewModel.startBroadcastFromExistingCapture()
                    }
                    else -> {
                        // Default 1-phone mode: capture full screen of this phone immediately and start live
                        launchPrivacyAwareCapture(PendingCapturePurpose.GO_LIVE_DIRECT, settings.stream.captureMode, true)
                    }
                }
            }
            if (settings.audio.micEnabled) {
                ensureMicPermissionAndRun(proceedStart)
            } else {
                proceedStart()
            }
        }
    }

    val handleReconnectPreviousLive: () -> Unit = {
        if (viewModel.prepareReconnectPreviousLive()) {
            requestStartBroadcast()
        } else {
            pushSubpage(AppSubpage.LIVE_SETUP)
        }
    }

    val handleResumeSavedSession: (com.example.data.model.StreamSessionDoc) -> Unit = { session ->
        if (viewModel.prepareResumeSavedSession(session)) {
            requestStartBroadcast()
        } else {
            pushSubpage(AppSubpage.LIVE_SETUP)
        }
    }

    val openOverlayPermissionSettings: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(intent) }
    }

    if (showQrScanner) {
        QrScannerDialog(
            onQrScanned = { raw ->
                showQrScanner = false
                viewModel.connectPhone1ByQrUri(raw)
            },
            onDismiss = { showQrScanner = false }
        )
    }

    if (showAndroid13CaptureLimitDialog) {
        AlertDialog(
            onDismissRequest = { showAndroid13CaptureLimitDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Single-App Capture Requires Android 14+", color = Color.White) },
            text = {
                Text(
                    "Your device is running Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}). Android added OS-level single-game capture in Android 14 (API 34). Zro live will NOT silently capture your entire phone.\n\nYou can explicitly opt into Full Screen Capture if you want to broadcast on this device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAndroid13CaptureLimitDialog = false
                        viewModel.selectCaptureMode(CaptureMode.FULL_SCREEN, explicitFullScreenConfirmed = true)
                        launchPrivacyAwareCapture(pendingCapturePurpose, CaptureMode.FULL_SCREEN, true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZroAmber, contentColor = Color.Black),
                    modifier = Modifier.testTag("confirm_android13_fullscreen_optin")
                ) {
                    Text("Use Full Screen (Opt-In)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAndroid13CaptureLimitDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showFullScreenOptInDialog) {
        AlertDialog(
            onDismissRequest = { showFullScreenOptInDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Enable Full Screen Capture?", color = Color.White) },
            text = {
                Text(
                    "Full Screen mode captures your entire display—including incoming messages and notifications. For privacy, Game / App-Only mode is recommended on Android 14+.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFullScreenOptInDialog = false
                        viewModel.selectCaptureMode(CaptureMode.FULL_SCREEN, explicitFullScreenConfirmed = true)
                        launchPrivacyAwareCapture(pendingCapturePurpose, CaptureMode.FULL_SCREEN, true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZroAmber, contentColor = Color.Black),
                    modifier = Modifier.testTag("confirm_fullscreen_optin")
                ) {
                    Text("Enable Full Screen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullScreenOptInDialog = false }) {
                    Text("Keep Game-Only")
                }
            }
        )
    }

    val reducedMotion = settings.performance.reduceAnimationsDuringGameplay || settings.performance.lowRamMode
    val connectedChatMessages = (chatState as? YouTubeChatState.Connected)?.messages.orEmpty()
    val activeSubpage = subpageStack.lastOrNull()

    if (showFullScreenPreview) {
        val isLiveNow = streamTelemetry.state == StreamLifecycleState.LIVE
        val sourceLabel = if (streamTelemetry.activeVideoSource == LiveVideoSource.REMOTE_SECOND_PHONE ||
            (dualTelemetry.linkState == DualLinkState.CONNECTED && dualTelemetry.role == DualPhoneRole.PHONE2_STUDIO)
        ) {
            "2nd Phone Screen"
        } else {
            "This Phone Full Screen"
        }
        FullScreen16x9PreviewDialog(
            workspace = studioWorkspace,
            mirroredFrame = mirroredFrame,
            audioMeters = audioMeters,
            chatMessages = connectedChatMessages,
            dualTelemetry = dualTelemetry,
            cameraPermissionGranted = cameraPermGranted,
            resolutionLabel = settings.stream.resolution.shortLabel,
            fps = settings.stream.fps,
            statusLabel = streamTelemetry.state.displayLabel,
            sourceLabel = sourceLabel,
            isLive = isLiveNow,
            onDismiss = { showFullScreenPreview = false }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 680.dp

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { snackbarData ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = snackbarData.visuals.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                maxLines = 2
                            )
                        }
                    }
                }
            },
            bottomBar = {
                // Hide bottom bar when on a dedicated subpage so Back navigation stays crisp & uncluttered
                if (!useNavigationRail && activeSubpage == null) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        ZroNavTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectTopTab(tab) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ZroCobalt,
                                    selectedTextColor = Color.White,
                                    indicatorColor = ZroCobalt.copy(alpha = 0.16f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag("nav_${tab.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail && activeSubpage == null) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        ZroNavTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { selectTopTab(tab) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = ZroCobalt,
                                    selectedTextColor = Color.White,
                                    indicatorColor = ZroCobalt.copy(alpha = 0.16f)
                                )
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (activeSubpage) {
                        AppSubpage.LIVE_SETUP -> LiveSetupScreen(
                            streamSettings = settings.stream,
                            audioSettings = settings.audio,
                            streamTelemetry = streamTelemetry,
                            dualTelemetry = dualTelemetry,
                            workspace = studioWorkspace,
                            onBack = popBack,
                            onUpdateStreamSettings = { viewModel.updateStreamSettings(it) },
                            onPickThumbnail = {
                                thumbnailPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onClearThumbnail = { viewModel.setStreamThumbnailUri(null) },
                            onSelectScene = { viewModel.switchScene(it) },
                            onOpenAudioStudio = { pushSubpage(AppSubpage.AUDIO_STUDIO) },
                            onOpenDualPairing = { pushSubpage(AppSubpage.DUAL_PAIRING) },
                            onOpenCameraSettings = { pushSubpage(AppSubpage.CAMERA_SETTINGS) },
                            onOpenSceneStudio = { pushSubpage(AppSubpage.LAYERS_PANEL) },
                            onStartLive = requestStartBroadcast,
                            onStopLive = { viewModel.endStream() },
                            youTubeAccount = youTubeAccount,
                            rtmpTestResult = rtmpTestResult,
                            isTestingRtmp = isTestingRtmp,
                            onConnectYouTubeAccount = { viewModel.signInYouTubeAccount(context) },
                            onDisconnectYouTubeAccount = { viewModel.signOutYouTubeAccount() },
                            onSaveConfiguration = { viewModel.saveStreamConfiguration() },
                            onTestConnection = { viewModel.testRtmpConnection() },
                            connectivitySnapshot = connectivitySnapshot,
                            onProbeStunServer = { viewModel.probeStunAndRefreshConnectivity() }
                        )

                        AppSubpage.LAYERS_PANEL -> ObsSceneStudioScreen(
                            workspace = studioWorkspace,
                            mirroredFrame = mirroredFrame,
                            audioMeters = audioMeters,
                            chatMessages = connectedChatMessages,
                            dualTelemetry = dualTelemetry,
                            cameraPermissionGranted = cameraPermGranted,
                            canUndo = canUndo,
                            canRedo = canRedo,
                            reducedMotion = reducedMotion,
                            onBack = popBack,
                            onSwitchScene = { viewModel.switchScene(it) },
                            onRenameActiveScene = { viewModel.renameActiveScene(it) },
                            onToggleSceneLock = { viewModel.toggleSceneLock() },
                            onToggleSceneVertical = { viewModel.toggleActiveSceneVertical() },
                            onUpdateTransition = { t, dur -> viewModel.updateSceneTransition(t, dur) },
                            onUndo = { viewModel.undoStudio() },
                            onRedo = { viewModel.redoStudio() },
                            onSetGridMode = { viewModel.setCanvasGridMode(it) },
                            onSaveLayoutExplicit = { viewModel.saveScreenLayoutExplicitly() },
                            onSetAutoRestoreLastLayout = { viewModel.setAutoRestoreLastLayout(it) },
                            onResetLayoutConfirmed = { viewModel.resetEntireLayoutConfirmed() },
                            onResetActiveScene = { viewModel.resetActiveSceneToDefault() },
                            onSelectLayer = { viewModel.selectLayer(it) },
                            onToggleLayerLock = { viewModel.toggleLayerLock(it) },
                            onToggleLayerVisibility = { viewModel.toggleLayerVisibility(it) },
                            onMoveLayerBy = { id, dx, dy -> viewModel.moveLayerBy(id, dx, dy) },
                            onResizeLayerCorner = { id, corner, dx, dy, prop ->
                                viewModel.resizeLayerFromCorner(id, corner, dx, dy, prop)
                            },
                            onDragFinished = { viewModel.persistCurrentStudioState() },
                            onUpdateLayerProps = { id, transform -> viewModel.updateLayerProps(id, transform) },
                            onCenterH = { viewModel.centerLayerHorizontally(it) },
                            onCenterV = { viewModel.centerLayerVertically(it) },
                            onFitCanvas = { viewModel.fitLayerFullCanvas(it) },
                            onFillCanvas = { viewModel.fillLayerCanvas(it) },
                            onResetTransform = { viewModel.resetLayerTransform(it) },
                            onMoveLayerZOrder = { id, forward -> viewModel.moveLayerZOrder(id, forward) },
                            onDuplicateLayer = { viewModel.duplicateLayer(it) },
                            onDeleteLayer = { viewModel.deleteLayer(it) },
                            onAddLayerSource = { viewModel.addLayerSource(it) },
                            onPickImageOverlay = {
                                imageOverlayPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onPickVideoOverlay = {
                                videoOverlayPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            onPickBackgroundMediaForLayer = { layerId ->
                                pendingBgLayerId = layerId
                                layerBackgroundPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onUpdateCanvasOptions = { grid, snap, safe, zoom ->
                                viewModel.updateCanvasOptions(grid, snap, safe, zoom)
                            },
                            onOpenCameraSettings = { pushSubpage(AppSubpage.CAMERA_SETTINGS) },
                            onReconnectPeer = { viewModel.reconnectDualPhone() },
                            onUpdateLayerGraphicAdjustmentLive = { id, transform ->
                                viewModel.updateLayerGraphicAdjustmentLive(id, transform)
                            },
                            onApplyGraphicPreset = { id, preset ->
                                viewModel.applyGraphicPresetToLayer(id, preset)
                            },
                            onApplyCustomGraphicPreset = { id, custom ->
                                viewModel.applyCustomGraphicPresetToLayer(id, custom)
                            },
                            onSaveCustomGraphicPreset = { name, adj ->
                                viewModel.saveCustomGraphicPreset(name, adj)
                            },
                            onDeleteCustomGraphicPreset = { presetId ->
                                viewModel.deleteCustomGraphicPreset(presetId)
                            },
                            onResetLayerGraphicAdjustment = { id ->
                                viewModel.resetLayerGraphicAdjustment(id)
                            },
                            onCopyGraphicAdjustmentToLayer = { srcId, dstId ->
                                viewModel.copyGraphicAdjustmentToLayer(srcId, dstId)
                            },
                            screenCaptureName = settings.stream.screenCaptureName,
                            onCropLayerEdge = { id, edge, delta -> viewModel.cropLayerEdgeLive(id, edge, delta) },
                            onSetLayerCropLive = { id, l, t, r, b -> viewModel.setLayerCropLive(id, l, t, r, b) },
                            onApplyCropAspectPreset = { id, preset -> viewModel.applyLayerCropAspectPreset(id, preset) },
                            onResetLayerCrop = { id -> viewModel.resetLayerCrop(id) },
                            onRenameLayer = { id, name -> viewModel.renameLayer(id, name) },
                            onRenameScreenCapture = { viewModel.renameScreenCapture(it) },
                            onMergeLayers = { ids, name -> viewModel.mergeLayers(ids, name) },
                            onUnmergeLayer = { id -> viewModel.unmergeLayer(id) },
                            onMoveLayerTo = { id, x, y -> viewModel.moveLayerTo(id, x, y) },
                            onReloadBrowserLayer = { id -> viewModel.reloadBrowserLayer(id) },
                            onOpenFullScreenPreview = { showFullScreenPreview = true },
                            onAlignCenter = { id -> viewModel.alignLayerCenter(id) },
                            onResizeLayerExact = { id, w, h -> viewModel.resizeLayerExact(id, w, h) },
                            onApplyCustomSizeUnits = { id, uw, uh -> viewModel.applyLayerCustomSizeUnits(id, uw, uh) }
                        )

                        AppSubpage.CHAT -> ChatControlScreen(
                            chatState = chatState,
                            workspace = studioWorkspace,
                            streamTelemetry = streamTelemetry,
                            mirroredFrame = mirroredFrame,
                            onBack = popBack,
                            onConnectChat = { viewModel.connectYouTubeLiveChat() },
                            onRefreshChat = { viewModel.refreshYouTubeLiveChat() },
                            onSendChatMessage = { viewModel.sendYouTubeChatMessage(it) },
                            onPinChatMessage = { viewModel.pinChatMessage(it) },
                            onUnpinChatMessage = { viewModel.unpinChatMessage() },
                            onSetChatTextSize = { viewModel.setChatTextSize(it) },
                            onSetChatOverlayEnabled = { viewModel.setChatOverlayEnabled(it) }
                        )

                        AppSubpage.CAMERA_SETTINGS -> CameraSettingsScreen(
                            workspace = studioWorkspace,
                            dualTelemetry = dualTelemetry,
                            cameraPermissionGranted = cameraPermGranted,
                            onBack = popBack,
                            onToggleCameraEnabled = handleToggleCamera,
                            onRequestCameraPermission = {
                                ensureCameraPermissionAndRun { viewModel.setCameraEnabled(true) }
                            },
                            onSelectCameraSource = { viewModel.setCameraSourceMode(it) },
                            onUpdateFacecamLayer = { viewModel.updateFacecamForActiveScene(it) },
                            onToggleFacecamLock = { viewModel.toggleLayerLock(it) },
                            onSaveLayout = { viewModel.saveScreenLayoutExplicitly() },
                            externalCameraInventory = externalCameraInventory,
                            onRefreshExternalCameras = { viewModel.refreshExternalCameras() }
                        )

                        AppSubpage.AUDIO_STUDIO -> LiveStudioScreen(
                            selectedTab = LiveStudioTab.AUDIO_MIXER,
                            onSelectTab = { studioSubTab = it },
                            streamSettings = settings.stream,
                            audioSettings = settings.audio,
                            performanceSettings = settings.performance,
                            streamTelemetry = streamTelemetry,
                            deviceTelemetry = deviceTelemetry,
                            audioMeters = audioMeters,
                            chatState = chatState,
                            onUpdateStreamSettings = { viewModel.updateStreamSettings(it) },
                            onSelectCaptureMode = { mode ->
                                if (mode == CaptureMode.FULL_SCREEN && !settings.stream.fullScreenExplicitlyEnabled) {
                                    pendingCapturePurpose = PendingCapturePurpose.PREVIEW_ONLY
                                    showFullScreenOptInDialog = true
                                } else {
                                    viewModel.selectCaptureMode(mode, explicitFullScreenConfirmed = settings.stream.fullScreenExplicitlyEnabled)
                                }
                            },
                            onSelectGameCapture = requestSelectGameCapture,
                            onStopCapture = { viewModel.stopCapture() },
                            onSelectPreset = { viewModel.applyPreset(it) },
                            onUpdateAudioSettings = { viewModel.updateAudioSettings(it) },
                            onStartStreaming = requestStartBroadcast,
                            onPauseResumeStreaming = { viewModel.togglePauseStream() },
                            onEndStreaming = { viewModel.endStream() },
                            onToggleMicTest = { ensureMicPermissionAndRun { viewModel.toggleMicTest() } },
                            onToggleTeamVoiceToLive = { viewModel.toggleTeamVoiceToLive() },
                            onApplyCloseVoiceFocus = { viewModel.applyCloseVoiceFocusPreset(it) },
                            onConnectChat = { viewModel.connectYouTubeLiveChat() },
                            onDisconnectChat = { viewModel.disconnectYouTubeLiveChat() },
                            onSendChatMessage = { viewModel.sendYouTubeChatMessage(it) },
                            onRenameScreenCapture = { viewModel.renameScreenCapture(it) },
                            onBack = popBack,
                            subpageTitle = "Audio Studio"
                        )

                        AppSubpage.DUAL_PAIRING -> DualPhoneStudioScreen(
                            initialSection = DualStudioSection.PAIRING,
                            dualTelemetry = dualTelemetry,
                            pairingUri = viewModel.currentPhone2PairingUri(),
                            mirroredFrame = mirroredFrame,
                            workspace = studioWorkspace,
                            streamTelemetry = streamTelemetry,
                            audioSettings = settings.audio,
                            audioMeters = audioMeters,
                            screenshotCount = screenshotCount,
                            reducedMotion = reducedMotion,
                            onBack = popBack,
                            onSelectRole = { viewModel.setDualPhoneRole(it) },
                            onSelectMethod = { viewModel.setDualConnectionMethod(it) },
                            onToggleAutoReconnect = { viewModel.setDualAutoReconnect(it) },
                            onStartPhone2Host = { viewModel.startPhone2StudioHost() },
                            onOpenQrScanner = openCameraQrScanner,
                            onConnectManual = { ip, port, token -> viewModel.connectPhone1Manual(ip, port, token) },
                            onDisconnect = { viewModel.disconnectDualPhone() },
                            onReconnect = { viewModel.reconnectDualPhone() },
                            onDispatchRemote = { viewModel.dispatchRemoteControl(it) },
                            onStartLiveFlow = requestStartBroadcast,
                            onEndLiveConfirmed = { viewModel.endStream() },
                            onToggleCamera = handleToggleCamera,
                            onTriggerBrb = { viewModel.triggerBrbPrivacy() },
                            onResumeFromBrb = { viewModel.resumeFromBrbPrivacy() },
                            onToggleTeamVoiceToLive = { viewModel.toggleTeamVoiceToLive() },
                            onUpdateAudioSettings = { viewModel.updateAudioSettings(it) },
                            onOpenChatPage = { pushSubpage(AppSubpage.CHAT) },
                            onOpenAudioStudio = { pushSubpage(AppSubpage.AUDIO_STUDIO) },
                            onOpenCameraSettings = { pushSubpage(AppSubpage.CAMERA_SETTINGS) },
                            onOpenSceneEditor = { pushSubpage(AppSubpage.LAYERS_PANEL) },
                            onMoveRemoteTile = { id, up -> viewModel.moveRemoteTile(id, up) },
                            onAddRemoteTile = { viewModel.addRemoteTile(it) },
                            onRemoveRemoteTile = { viewModel.removeRemoteTile(it) },
                            onResetRemoteDeck = { viewModel.resetRemoteDeckToDefault() },
                            chatMessages = connectedChatMessages,
                            cameraPermissionGranted = cameraPermGranted,
                            screenCaptureName = settings.stream.screenCaptureName,
                            onSelectLayer = { viewModel.selectLayer(it) },
                            onMoveLayerBy = { id, dx, dy -> viewModel.moveLayerBy(id, dx, dy) },
                            onResizeLayerCorner = { id, corner, dx, dy, prop ->
                                viewModel.resizeLayerFromCorner(id, corner, dx, dy, prop)
                            },
                            onCropLayerEdge = { id, edge, delta -> viewModel.cropLayerEdgeLive(id, edge, delta) },
                            onDragFinished = { viewModel.persistCurrentStudioState() },
                            onRenameLayer = { id, name -> viewModel.renameLayer(id, name) },
                            onRenameScreenCapture = { viewModel.renameScreenCapture(it) },
                            onToggleLayerLock = { viewModel.toggleLayerLock(it) },
                            onToggleLayerVisibility = { viewModel.toggleLayerVisibility(it) },
                            onSwitchScene = { viewModel.switchScene(it) },
                            onOpenFullScreenPreview = { showFullScreenPreview = true },
                            wifiDirectState = wifiDirectState,
                            connectivitySnapshot = connectivitySnapshot,
                            onStartWifiDirectHost = { ensureWifiDirectPermissionAndRun { viewModel.startWifiDirectHost() } },
                            onStopWifiDirectHost = { viewModel.stopWifiDirectHost() },
                            onProbeStunServer = { viewModel.probeStunAndRefreshConnectivity() },
                            onSharePhone1Screen = requestSelectGameCapture
                        )

                        AppSubpage.VOICE_ROOM -> VoiceRoomScreen(
                            isGuest = false,
                            isAuthLoading = false,
                            onSignInWithGoogle = {},
                            currentUserId = "local_streamer",
                            activeRoom = activeRoom,
                            participants = participants,
                            publicRooms = publicRooms,
                            audioSettings = settings.audio,
                            selfStreamConsent = selfStreamConsent,
                            teammateMutedMap = teammateMuted,
                            teammateVolumeMap = teammateVolumes,
                            isLoading = voiceLoading,
                            onCreateRoom = { name, isPrivate, maxSlots ->
                                ensureMicPermissionAndRun { viewModel.createVoiceRoom(name, isPrivate, maxSlots) }
                            },
                            onJoinRoom = { code ->
                                ensureMicPermissionAndRun { viewModel.joinVoiceRoom(code) }
                            },
                            onLeaveRoom = { viewModel.leaveVoiceRoom() },
                            onToggleSelfMic = {
                                viewModel.updateAudioSettings(settings.audio.copy(micEnabled = !settings.audio.micEnabled))
                            },
                            onToggleSelfStreamConsent = { viewModel.toggleSelfStreamConsent(it) },
                            onSelectAudioRoute = { route ->
                                viewModel.updateAudioSettings(settings.audio.copy(outputRoute = route))
                            },
                            onToggleTeammateMuted = { viewModel.toggleTeammateMuted(it) },
                            onChangeTeammateVolume = { uid, vol -> viewModel.setTeammateVolume(uid, vol) },
                            onBack = popBack
                        )

                        null -> when (currentTab) {
                            ZroNavTab.HOME -> HomeDashboardScreen(
                                settings = settings,
                                streamTelemetry = streamTelemetry,
                                deviceTelemetry = deviceTelemetry,
                                smartRecommendation = smartRec,
                                dualTelemetry = dualTelemetry,
                                workspace = studioWorkspace,
                                activeVoiceRoom = activeRoom,
                                recentSessions = recentSessions,
                                onOpenLiveSetup = { pushSubpage(AppSubpage.LIVE_SETUP) },
                                onStartStreamingClick = requestStartBroadcast,
                                onStopStreamingClick = { viewModel.endStream() },
                                onOpenObsStudio = { pushSubpage(AppSubpage.LAYERS_PANEL) },
                                onOpenRemoteDeck = { selectTopTab(ZroNavTab.DUAL) },
                                onOpenDualPairing = { pushSubpage(AppSubpage.DUAL_PAIRING) },
                                onOpenAudioSettings = { pushSubpage(AppSubpage.AUDIO_STUDIO) },
                                onOpenCameraSettings = { pushSubpage(AppSubpage.CAMERA_SETTINGS) },
                                onOpenChat = { pushSubpage(AppSubpage.CHAT) },
                                onOpenVoiceRoom = { pushSubpage(AppSubpage.VOICE_ROOM) },
                                recoverableSession = recoverableSession,
                                mirroredFrame = mirroredFrame,
                                audioMeters = audioMeters,
                                cameraPermissionGranted = cameraPermGranted,
                                onReconnectPreviousLive = handleReconnectPreviousLive,
                                onDismissRecovery = { viewModel.dismissRecoverableSession() },
                                onOpenLiveManager = {
                                    studioSubTab = LiveStudioTab.LIVE_MANAGER
                                    selectTopTab(ZroNavTab.LIVE)
                                },
                                onOpenFullScreenPreview = { showFullScreenPreview = true },
                                connectivitySnapshot = connectivitySnapshot,
                                onProbeStunServer = { viewModel.probeStunAndRefreshConnectivity() }
                            )

                            ZroNavTab.DUAL -> DualPhoneStudioScreen(
                                initialSection = DualStudioSection.REMOTE_DECK,
                                dualTelemetry = dualTelemetry,
                                pairingUri = viewModel.currentPhone2PairingUri(),
                                mirroredFrame = mirroredFrame,
                                workspace = studioWorkspace,
                                streamTelemetry = streamTelemetry,
                                audioSettings = settings.audio,
                                audioMeters = audioMeters,
                                screenshotCount = screenshotCount,
                                reducedMotion = reducedMotion,
                                onSelectRole = { viewModel.setDualPhoneRole(it) },
                                onSelectMethod = { viewModel.setDualConnectionMethod(it) },
                                onToggleAutoReconnect = { viewModel.setDualAutoReconnect(it) },
                                onStartPhone2Host = { viewModel.startPhone2StudioHost() },
                                onOpenQrScanner = openCameraQrScanner,
                                onConnectManual = { ip, port, token -> viewModel.connectPhone1Manual(ip, port, token) },
                                onDisconnect = { viewModel.disconnectDualPhone() },
                                onReconnect = { viewModel.reconnectDualPhone() },
                                onDispatchRemote = { viewModel.dispatchRemoteControl(it) },
                                onStartLiveFlow = requestStartBroadcast,
                                onEndLiveConfirmed = { viewModel.endStream() },
                                onToggleCamera = handleToggleCamera,
                                onTriggerBrb = { viewModel.triggerBrbPrivacy() },
                                onResumeFromBrb = { viewModel.resumeFromBrbPrivacy() },
                                onToggleTeamVoiceToLive = { viewModel.toggleTeamVoiceToLive() },
                                onUpdateAudioSettings = { viewModel.updateAudioSettings(it) },
                                onOpenChatPage = { pushSubpage(AppSubpage.CHAT) },
                                onOpenAudioStudio = { pushSubpage(AppSubpage.AUDIO_STUDIO) },
                                onOpenCameraSettings = { pushSubpage(AppSubpage.CAMERA_SETTINGS) },
                                onOpenSceneEditor = { pushSubpage(AppSubpage.LAYERS_PANEL) },
                                onMoveRemoteTile = { id, up -> viewModel.moveRemoteTile(id, up) },
                                onAddRemoteTile = { viewModel.addRemoteTile(it) },
                                onRemoveRemoteTile = { viewModel.removeRemoteTile(it) },
                                onResetRemoteDeck = { viewModel.resetRemoteDeckToDefault() },
                                chatMessages = connectedChatMessages,
                                cameraPermissionGranted = cameraPermGranted,
                                screenCaptureName = settings.stream.screenCaptureName,
                                onSelectLayer = { viewModel.selectLayer(it) },
                                onMoveLayerBy = { id, dx, dy -> viewModel.moveLayerBy(id, dx, dy) },
                                onResizeLayerCorner = { id, corner, dx, dy, prop ->
                                    viewModel.resizeLayerFromCorner(id, corner, dx, dy, prop)
                                },
                                onCropLayerEdge = { id, edge, delta -> viewModel.cropLayerEdgeLive(id, edge, delta) },
                                onDragFinished = { viewModel.persistCurrentStudioState() },
                                onRenameLayer = { id, name -> viewModel.renameLayer(id, name) },
                                onRenameScreenCapture = { viewModel.renameScreenCapture(it) },
                                onToggleLayerLock = { viewModel.toggleLayerLock(it) },
                                onToggleLayerVisibility = { viewModel.toggleLayerVisibility(it) },
                                onSwitchScene = { viewModel.switchScene(it) },
                                onOpenFullScreenPreview = { showFullScreenPreview = true },
                                wifiDirectState = wifiDirectState,
                                connectivitySnapshot = connectivitySnapshot,
                                onStartWifiDirectHost = { ensureWifiDirectPermissionAndRun { viewModel.startWifiDirectHost() } },
                                onStopWifiDirectHost = { viewModel.stopWifiDirectHost() },
                                onProbeStunServer = { viewModel.probeStunAndRefreshConnectivity() },
                                onSharePhone1Screen = requestSelectGameCapture
                            )

                            ZroNavTab.LIVE -> LiveStudioScreen(
                                selectedTab = studioSubTab,
                                onSelectTab = { studioSubTab = it },
                                streamSettings = settings.stream,
                                audioSettings = settings.audio,
                                performanceSettings = settings.performance,
                                streamTelemetry = streamTelemetry,
                                deviceTelemetry = deviceTelemetry,
                                audioMeters = audioMeters,
                                chatState = chatState,
                                onUpdateStreamSettings = { viewModel.updateStreamSettings(it) },
                                onSelectCaptureMode = { mode ->
                                    viewModel.selectCaptureMode(mode, explicitFullScreenConfirmed = true)
                                },
                                onSelectGameCapture = requestSelectGameCapture,
                                onStopCapture = { viewModel.stopCapture() },
                                onSelectPreset = { viewModel.applyPreset(it) },
                                onUpdateAudioSettings = { viewModel.updateAudioSettings(it) },
                                onStartStreaming = requestStartBroadcast,
                                onPauseResumeStreaming = { viewModel.togglePauseStream() },
                                onEndStreaming = { viewModel.endStream() },
                                onToggleMicTest = { ensureMicPermissionAndRun { viewModel.toggleMicTest() } },
                                onToggleTeamVoiceToLive = { viewModel.toggleTeamVoiceToLive() },
                                onApplyCloseVoiceFocus = { viewModel.applyCloseVoiceFocusPreset(it) },
                                onConnectChat = { viewModel.connectYouTubeLiveChat() },
                                onDisconnectChat = { viewModel.disconnectYouTubeLiveChat() },
                                onSendChatMessage = { viewModel.sendYouTubeChatMessage(it) },
                                onRenameScreenCapture = { viewModel.renameScreenCapture(it) },
                                recoverableSession = recoverableSession,
                                recentSessions = recentSessions,
                                savedProfiles = savedLiveProfiles,
                                crashLogs = crashLogs,
                                onReconnectPreviousLive = handleReconnectPreviousLive,
                                onDismissRecovery = { viewModel.dismissRecoverableSession() },
                                onCreateNewLive = { title ->
                                    viewModel.createNewLiveDraft(title)
                                    pushSubpage(AppSubpage.LIVE_SETUP)
                                },
                                onOpenLiveSetup = { pushSubpage(AppSubpage.LIVE_SETUP) },
                                onOpenFullScreenPreview = { showFullScreenPreview = true },
                                onResumeSavedSession = handleResumeSavedSession,
                                onDeleteSession = { viewModel.deleteSessionFromHistory(it) },
                                onSaveCurrentProfile = { viewModel.saveCurrentConfigAsProfile(it) },
                                onApplyProfile = { viewModel.applyLiveProfile(it) },
                                onDeleteProfile = { viewModel.deleteLiveProfile(it) },
                                onClearCrashLogs = { viewModel.clearCrashLogs() }
                            )

                            ZroNavTab.SETTINGS -> SettingsScreen(
                                isGuest = true,
                                isAuthLoading = false,
                                userDisplayName = localDisplayName,
                                settings = settings,
                                deviceTelemetry = deviceTelemetry,
                                onUpdateStream = { viewModel.updateStreamSettings(it) },
                                onSetFullScreenOptIn = { viewModel.setFullScreenOptInEnabled(it) },
                                onUpdateAudio = { viewModel.updateAudioSettings(it) },
                                onUpdateOverlay = { viewModel.updateOverlaySettings(it) },
                                onUpdateNotifications = { viewModel.updateNotificationSettings(it) },
                                onUpdatePerformance = { viewModel.updatePerformanceSettings(it) },
                                onSelectPerformanceProfile = { viewModel.selectPerformanceProfile(it) },
                                onUpdateGeneral = { viewModel.updateGeneralSettings(it) },
                                onRequestMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                onRequestNotificationPermission = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onRequestOverlayPermission = openOverlayPermissionSettings,
                                onOpenAudioMixer = { pushSubpage(AppSubpage.AUDIO_STUDIO) },
                                onSignInOptional = {},
                                onSignOut = {}
                            )
                        }
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private object Api34ProjectionHelper {
    fun createSingleAppIntent(mgr: MediaProjectionManager): Intent =
        mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())

    fun createDefaultDisplayIntent(mgr: MediaProjectionManager): Intent =
        mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
}
