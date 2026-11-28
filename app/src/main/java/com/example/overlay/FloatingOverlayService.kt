package com.example.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.R
import com.example.data.model.OverlayCorner
import com.example.data.model.OverlayIconShape
import com.example.data.model.StreamLifecycleState
import com.example.streaming.StreamNotificationManager
import com.example.streaming.StreamStateManager
import com.example.streaming.ZroStreamCoordinator
import com.example.studio.SceneSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Lightweight, memory-efficient floating live-control button rendered over games via SYSTEM_ALERT_WINDOW.
 * Features:
 * - Default Zro Live logo or user-uploaded custom channel logo image
 * - Customizable Shape (Circle, Square, Rectangle) and Size (Compact, Standard, Large, Custom Size)
 * - Closing the floating icon docks it neatly to a small screen-edge Zro Live icon (edge-hide) instead of killing it
 * - Tapping the edge-docked icon immediately restores the floating icon & opens floating controls
 * - Settings ON/OFF disables completely; Settings Restore brings it back from edge-hide cleanly
 */
class FloatingOverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.example.zro.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.example.zro.OVERLAY_HIDE"
        const val ACTION_REFRESH = "com.example.zro.OVERLAY_REFRESH"
        const val ACTION_RESTORE = "com.example.zro.OVERLAY_RESTORE"
        const val ACTION_MINIMIZE_EDGE = "com.example.zro.OVERLAY_MINIMIZE_EDGE"

        /**
         * BGMI multi-touch claw & joystick safe WindowManager flags:
         * FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL with WRAP_CONTENT bounds ensure touches
         * outside the compact floating icon pass straight through to the game without interference.
         */
        const val OVERLAY_WINDOW_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var rootContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var activeBadgeHolder: LinearLayout? = null
    private var logoFrame: FrameLayout? = null
    private var logoImageView: ImageView? = null
    private var badgeDotView: View? = null
    private var liveMiniStrip: LinearLayout? = null
    private var badgeTimerText: TextView? = null
    private var badgeTempText: TextView? = null

    private var edgeDockContainer: FrameLayout? = null
    private var edgeDockLogoImage: ImageView? = null
    private var edgeDockDotView: View? = null

    private var drawerPanel: LinearLayout? = null
    private var drawerStatusText: TextView? = null
    private var drawerMetaText: TextView? = null
    private var drawerStreamBtn: TextView? = null
    private var drawerPauseBtn: TextView? = null
    private var drawerBitrateRow: LinearLayout? = null
    private var drawerBitrateLabel: TextView? = null
    private var drawerMicBtn: TextView? = null
    private var drawerGameBtn: TextView? = null
    private var drawerTeamBtn: TextView? = null
    private val sceneButtons = mutableMapOf<SceneSlot, TextView>()

    private var isExpanded = false
    private var isEdgeHidden = false
    private var cachedLogoUri: String? = null
    private var cachedCustomLogoBitmap: Bitmap? = null
    private var lastNonHiddenX: Int = 0
    private var lastNonHiddenY: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removeOverlay()
                stopSelf()
            }
            ACTION_REFRESH -> {
                if (rootContainer != null) {
                    applyAppearancePreferences()
                    refreshControlsText()
                }
            }
            ACTION_MINIMIZE_EDGE -> {
                if (rootContainer != null) {
                    minimizeToEdgeDock()
                }
            }
            ACTION_RESTORE -> {
                if (!Settings.canDrawOverlays(this)) {
                    ZroStreamCoordinator.setOverlayVisible(false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                showOverlayIfNeeded()
                restoreFromEdgeDock(openControls = false, resetToDefaultCorner = true)
                applyAppearancePreferences()
                refreshControlsText()
            }
            else -> {
                if (!Settings.canDrawOverlays(this)) {
                    ZroStreamCoordinator.setOverlayVisible(false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                showOverlayIfNeeded()
                applyAppearancePreferences()
                refreshControlsText()
            }
        }
        return START_NOT_STICKY
    }

    private fun showOverlayIfNeeded() {
        if (rootContainer != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val overlaySettings = ZroStreamCoordinator.currentOverlaySettings
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = overlaySettings.opacity.coerceIn(0.45f, 1.0f)
        }

        // 1. Active Floating Logo Icon + unobtrusive live mini pill (timer/temp only when active)
        val badgeHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        activeBadgeHolder = badgeHolder

        val (initW, initH) = overlaySettings.effectiveDimensionsDp()
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(initW), dp(initH))
            clipToOutline = true
        }
        logoFrame = frame

        val logoImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.img_zro_logo_fg)
            contentDescription = "Zro Live floating controls"
        }
        logoImageView = logoImg
        frame.addView(logoImg)

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3B82F6"))
                setStroke(dp(1), Color.parseColor("#FF0B0F17"))
            }
        }
        badgeDotView = dot
        frame.addView(
            dot,
            FrameLayout.LayoutParams(dp(11), dp(11), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(3)
                marginEnd = dp(3)
            }
        )
        badgeHolder.addView(frame)

        // Compact live timer/temp strip underneath logo (hidden when idle so icon stays super clean)
        val miniStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DD0B0F17"))
                cornerRadius = dp(999).toFloat()
                setStroke(dp(1), Color.parseColor("#552563EB"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        liveMiniStrip = miniStrip

        val timerTv = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.MONOSPACE
        }
        badgeTimerText = timerTv
        miniStrip.addView(timerTv)

        val tempTv = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(4), 0, 0, 0)
        }
        badgeTempText = tempTv
        miniStrip.addView(tempTv)

        badgeHolder.addView(miniStrip)
        root.addView(badgeHolder)

        // 2. Compact Edge-Docked Hidden Zro Live Icon (shown when user taps Close)
        val edgeDock = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(46))
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0D1119"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#992563EB"))
            }
        }
        edgeDockContainer = edgeDock

        val edgeImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.img_zro_logo_fg)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            contentDescription = "Restore Zro Live floating controls"
        }
        edgeDockLogoImage = edgeImg
        edgeDock.addView(edgeImg)

        val edgeDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3B82F6"))
            }
        }
        edgeDockDotView = edgeDot
        edgeDock.addView(
            edgeDot,
            FrameLayout.LayoutParams(dp(7), dp(7), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(2)
                marginEnd = dp(2)
            }
        )
        root.addView(edgeDock)

        // 3. Expandable compact studio control drawer
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F50B0F17"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#662563EB"))
            }
        }
        val panelParams = LinearLayout.LayoutParams(dp(244), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }

        val statusTv = TextView(this).apply {
            text = "STANDBY • Ready"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        drawerStatusText = statusTv
        panel.addView(statusTv)

        val metaTv = TextView(this).apply {
            text = "Temp: -- • Screen 1"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, dp(8))
        }
        drawerMetaText = metaTv
        panel.addView(metaTv)

        // Primary Row: Start/Stop + Pause/Resume
        val primaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val streamBtn = createCompactTile("Start Live", "#2563EB") {
            val state = ZroStreamCoordinator.telemetry.value.state
            if (state.isActive) {
                ZroStreamCoordinator.requestStopStream(this)
            } else {
                ZroStreamCoordinator.requestStartLiveFromOverlay()
                openAppDestination(ZroStreamCoordinator.DeepLinkTarget.LIVE_CONTROLS)
            }
            toggleDrawer(false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                marginEnd = dp(4)
            }
        }
        drawerStreamBtn = streamBtn

        val pauseBtn = createCompactTile("Pause", "#B45309") {
            if (ZroStreamCoordinator.telemetry.value.state.isActive) {
                ZroStreamCoordinator.requestPauseResumeStream(this)
                refreshControlsText()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            }
        }
        drawerPauseBtn = pauseBtn
        primaryRow.addView(streamBtn)
        primaryRow.addView(pauseBtn)
        panel.addView(primaryRow)

        // Live Bitrate Quick Adjustment Row (visible while streaming)
        val brRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val brMinus = createCompactTile("-500", "#1E293B") {
            val current = ZroStreamCoordinator.telemetry.value.selectedBitrateKbps
                .takeIf { it > 0 } ?: ZroStreamCoordinator.currentStreamSettings.bitrateKbps
            ZroStreamCoordinator.requestUpdateLiveBitrate(this, (current - 500).coerceAtLeast(1000))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f).apply { marginEnd = dp(4) }
        }
        val brLabel = TextView(this).apply {
            text = "4.5 Mbps"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
        }
        drawerBitrateLabel = brLabel
        val brPlus = createCompactTile("+500", "#1E293B") {
            val current = ZroStreamCoordinator.telemetry.value.selectedBitrateKbps
                .takeIf { it > 0 } ?: ZroStreamCoordinator.currentStreamSettings.bitrateKbps
            ZroStreamCoordinator.requestUpdateLiveBitrate(this, (current + 500).coerceAtMost(12000))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f).apply { marginStart = dp(4) }
        }
        brRow.addView(brMinus)
        brRow.addView(brLabel)
        brRow.addView(brPlus)
        drawerBitrateRow = brRow
        panel.addView(brRow)

        // Screen 1 / Screen 2 / Screen 3 / Screen 4 Switcher Label
        val sceneHeader = TextView(this).apply {
            text = "SWITCH SCREEN"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, dp(4))
        }
        panel.addView(sceneHeader)

        sceneButtons.clear()
        val sceneRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        val s1Btn = createSceneTile(SceneSlot.GAMEPLAY_LIVE, "Screen 1 • Live", marginEndDp = 4)
        val s2Btn = createSceneTile(SceneSlot.SHORTS_VERTICAL, "Screen 2 • Start", marginStartDp = 4)
        sceneRow1.addView(s1Btn)
        sceneRow1.addView(s2Btn)
        panel.addView(sceneRow1)

        val sceneRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val s3Btn = createSceneTile(SceneSlot.BRB_BREAK, "Screen 3 • BRB", marginEndDp = 4)
        val s4Btn = createSceneTile(SceneSlot.ENDING_THANKS, "Screen 4 • End", marginStartDp = 4)
        sceneRow2.addView(s3Btn)
        sceneRow2.addView(s4Btn)
        panel.addView(sceneRow2)

        // Compact Audio Quick Row
        val audioRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val micBtn = createCompactTile("Mic", "#1E293B") {
            ZroStreamCoordinator.emitAudioQuickAction(ZroStreamCoordinator.AudioQuickAction.TOGGLE_MIC)
            refreshControlsText()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(3) }
        }
        val gameBtn = createCompactTile("Game", "#1E293B") {
            ZroStreamCoordinator.emitAudioQuickAction(ZroStreamCoordinator.AudioQuickAction.TOGGLE_GAME)
            refreshControlsText()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }
        val teamBtn = createCompactTile("Team", "#1E293B") {
            ZroStreamCoordinator.emitAudioQuickAction(ZroStreamCoordinator.AudioQuickAction.TOGGLE_TEAM)
            refreshControlsText()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3) }
        }
        drawerMicBtn = micBtn
        drawerGameBtn = gameBtn
        drawerTeamBtn = teamBtn
        audioRow.addView(micBtn)
        audioRow.addView(gameBtn)
        audioRow.addView(teamBtn)
        panel.addView(audioRow)

        // Footer Row: Studio / Collapse / Close (Edge-Hide)
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val studioBtn = createCompactTile("Studio", "#312E81") {
            openAppDestination(ZroStreamCoordinator.DeepLinkTarget.LIVE_CONTROLS)
            toggleDrawer(false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(3) }
        }
        val hideBtn = createCompactTile("Collapse", "#1E293B") {
            toggleDrawer(false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }
        // Close minimizes into compact screen-edge Zro Live icon instead of permanently disappearing
        val closeBtn = createCompactTile("Close", "#7F1D1D") {
            minimizeToEdgeDock()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3) }
        }
        bottomRow.addView(studioBtn)
        bottomRow.addView(hideBtn)
        bottomRow.addView(closeBtn)
        panel.addView(bottomRow)

        drawerPanel = panel
        root.addView(panel, panelParams)

        val typeFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val (grav, initX, initY) = cornerParams(overlaySettings.position)
        lastNonHiddenX = initX
        lastNonHiddenY = initY

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            typeFlag,
            OVERLAY_WINDOW_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = grav
            x = initX
            y = initY
        }

        attachDragAndTapHandler(badgeHolder, lp, wm, root, isEdgeDock = false)
        attachDragAndTapHandler(edgeDock, lp, wm, root, isEdgeDock = true)

        runCatching {
            wm.addView(root, lp)
            rootContainer = root
            layoutParams = lp
            isEdgeHidden = false
            ZroStreamCoordinator.setOverlayVisible(true)
            ZroStreamCoordinator.setOverlayEdgeHidden(false)
        }

        scope.launch {
            ZroStreamCoordinator.telemetry.collectLatest {
                refreshControlsText()
            }
        }
        scope.launch {
            StreamStateManager.state.collectLatest {
                refreshControlsText()
            }
        }
        scope.launch {
            ZroStreamCoordinator.activeStudioWorkspace.collectLatest {
                refreshControlsText()
            }
        }
        scope.launch {
            ZroStreamCoordinator.deviceTemperatureLabel.collectLatest {
                refreshControlsText()
            }
        }
    }

    private fun cornerParams(position: OverlayCorner): Triple<Int, Int, Int> {
        return when (position) {
            OverlayCorner.TOP_LEFT -> Triple(Gravity.TOP or Gravity.START, dp(12), dp(96))
            OverlayCorner.TOP_RIGHT -> Triple(Gravity.TOP or Gravity.END, dp(12), dp(96))
            OverlayCorner.BOTTOM_LEFT -> Triple(Gravity.BOTTOM or Gravity.START, dp(12), dp(128))
            OverlayCorner.BOTTOM_RIGHT -> Triple(Gravity.BOTTOM or Gravity.END, dp(12), dp(128))
        }
    }

    /**
     * Minimizes the floating icon into a tiny semi-transparent Zro Live icon pinned to the screen edge
     * so it stays out of the way of game controls and can be tapped anytime to reopen.
     */
    private fun minimizeToEdgeDock() {
        val lp = layoutParams ?: return
        val root = rootContainer ?: return
        val wm = windowManager ?: return
        if (!isEdgeHidden) {
            lastNonHiddenX = lp.x
            lastNonHiddenY = lp.y
        }
        isExpanded = false
        isEdgeHidden = true
        drawerPanel?.visibility = View.GONE
        activeBadgeHolder?.visibility = View.GONE
        edgeDockContainer?.visibility = View.VISIBLE
        root.alpha = 0.72f

        // Snap cleanly to the nearest screen edge (x = 0)
        lp.x = 0
        runCatching { wm.updateViewLayout(root, lp) }
        ZroStreamCoordinator.setOverlayEdgeHidden(true)
        refreshControlsText()
    }

    /**
     * Restores the floating icon from its edge-docked hidden state back to full clarity
     * and optionally opens the floating control drawer immediately.
     */
    private fun restoreFromEdgeDock(openControls: Boolean, resetToDefaultCorner: Boolean = false) {
        val lp = layoutParams ?: return
        val root = rootContainer ?: return
        val wm = windowManager ?: return
        val cfg = ZroStreamCoordinator.currentOverlaySettings

        isEdgeHidden = false
        edgeDockContainer?.visibility = View.GONE
        activeBadgeHolder?.visibility = View.VISIBLE
        root.alpha = cfg.opacity.coerceIn(0.45f, 1.0f)

        if (resetToDefaultCorner) {
            val (grav, initX, initY) = cornerParams(cfg.position)
            lp.gravity = grav
            lp.x = initX
            lp.y = initY
            lastNonHiddenX = initX
            lastNonHiddenY = initY
        } else {
            lp.x = lastNonHiddenX.coerceAtLeast(dp(10))
            lp.y = lastNonHiddenY
        }
        isExpanded = openControls
        drawerPanel?.visibility = if (openControls) View.VISIBLE else View.GONE
        runCatching { wm.updateViewLayout(root, lp) }
        ZroStreamCoordinator.setOverlayEdgeHidden(false)
        refreshControlsText()
    }

    private fun createSceneTile(
        slot: SceneSlot,
        label: String,
        marginStartDp: Int = 0,
        marginEndDp: Int = 0
    ): TextView {
        val tv = createCompactTile(label, "#1E293B") {
            ZroStreamCoordinator.requestSwitchScene(this, slot)
            refreshControlsText()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(marginStartDp)
                marginEnd = dp(marginEndDp)
            }
        }
        sceneButtons[slot] = tv
        return tv
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTapHandler(
        targetView: View,
        lp: WindowManager.LayoutParams,
        wm: WindowManager,
        root: View,
        isEdgeDock: Boolean
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        targetView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        dragged = true
                        val isEnd = (lp.gravity and Gravity.END) == Gravity.END
                        val isBottom = (lp.gravity and Gravity.BOTTOM) == Gravity.BOTTOM
                        if (isEdgeDock) {
                            // Keep docked to edge horizontally while allowing vertical repositioning
                            lp.x = 0
                            lp.y = if (isBottom) startY - dy else startY + dy
                        } else {
                            lp.x = if (isEnd) startX - dx else startX + dx
                            lp.y = if (isBottom) startY - dy else startY + dy
                            lastNonHiddenX = lp.x
                            lastNonHiddenY = lp.y
                        }
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        v.performClick()
                        if (isEdgeDock) {
                            restoreFromEdgeDock(openControls = true)
                        } else {
                            toggleDrawer(!isExpanded)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleDrawer(expand: Boolean) {
        if (isEdgeHidden) {
            restoreFromEdgeDock(openControls = expand)
            return
        }
        isExpanded = expand
        drawerPanel?.visibility = if (expand) View.VISIBLE else View.GONE
        refreshControlsText()
    }

    private fun applyAppearancePreferences() {
        val cfg = ZroStreamCoordinator.currentOverlaySettings
        val root = rootContainer ?: return
        root.alpha = if (isEdgeHidden) 0.72f else cfg.opacity.coerceIn(0.45f, 1.0f)

        // Apply custom dimensions & shape (Circle / Square / Rectangle + Small / Medium / Large / Custom Size)
        val (widthDp, heightDp) = cfg.effectiveDimensionsDp()
        val widthPx = dp(widthDp)
        val heightPx = dp(heightDp)
        val cornerPx = when (cfg.shape) {
            OverlayIconShape.CIRCLE -> (minOf(widthPx, heightPx) / 2f)
            OverlayIconShape.SQUARE -> dp(12).toFloat()
            OverlayIconShape.RECTANGLE -> dp(10).toFloat()
        }

        logoFrame?.let { frame ->
            val flp = frame.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(widthPx, heightPx)
            flp.width = widthPx
            flp.height = heightPx
            frame.layoutParams = flp
            frame.background = GradientDrawable().apply {
                setColor(Color.parseColor("#EC0D1119"))
                cornerRadius = cornerPx
                setStroke(dp(2), Color.parseColor("#CC3B82F6"))
            }
            frame.clipToOutline = true
            frame.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (cfg.shape == OverlayIconShape.CIRCLE) {
                        outline.setOval(0, 0, view.width, view.height)
                    } else {
                        outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
                    }
                }
            }
            frame.invalidateOutline()
        }

        // Load default Zro Live logo or user-selected custom channel logo safely
        val desiredUri = cfg.customLogoUri?.trim()?.takeIf { it.isNotBlank() }
        if (desiredUri != cachedLogoUri) {
            cachedLogoUri = desiredUri
            runCatching { cachedCustomLogoBitmap?.recycle() }
            cachedCustomLogoBitmap = desiredUri?.let { decodeCustomLogoSafely(it) }
        }
        val bmp = cachedCustomLogoBitmap?.takeIf { !it.isRecycled }
        if (bmp != null) {
            logoImageView?.setImageBitmap(bmp)
            edgeDockLogoImage?.setImageBitmap(bmp)
        } else {
            logoImageView?.setImageResource(R.drawable.img_zro_logo_fg)
            edgeDockLogoImage?.setImageResource(R.drawable.img_zro_logo_fg)
        }

        // Update corner gravity if user changed default corner while not actively dragged
        val lp = layoutParams
        val wm = windowManager
        if (lp != null && wm != null && !isEdgeHidden) {
            val (grav, _, _) = cornerParams(cfg.position)
            if (lp.gravity != grav) {
                val (_, initX, initY) = cornerParams(cfg.position)
                lp.gravity = grav
                lp.x = initX
                lp.y = initY
                lastNonHiddenX = initX
                lastNonHiddenY = initY
            }
            runCatching { wm.updateViewLayout(root, lp) }
        }
    }

    private fun decodeCustomLogoSafely(uriOrPath: String): Bitmap? {
        return runCatching {
            val uri = Uri.parse(uriOrPath)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (uri.scheme == "file" || uri.scheme.isNullOrBlank()) {
                val path = uri.path ?: uriOrPath
                val f = File(path)
                if (!f.exists()) return null
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                var sample = 1
                while (maxDim / sample > 256) sample *= 2
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            } else {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                var sample = 1
                while (maxDim / sample > 256) sample *= 2
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        }.getOrNull()
    }

    private fun refreshControlsText() {
        val tel = ZroStreamCoordinator.telemetry.value
        val audio = ZroStreamCoordinator.currentAudioSettings
        val workspace = ZroStreamCoordinator.activeStudioWorkspace.value
        val isLive = tel.state == StreamLifecycleState.LIVE
        val isPaused = tel.state == StreamLifecycleState.PAUSED
        val isActive = tel.state.isActive
        val timerState = StreamStateManager.state.value
        val timerStr = if (timerState.isStreaming || isActive) {
            timerState.formattedDuration
        } else {
            ZroStreamCoordinator.formatDuration(tel.durationSec)
        }
        val tempLabel = tel.deviceTempCelsius?.let { "${String.format(Locale.US, "%.1f", it)}°C" }
            ?: ZroStreamCoordinator.deviceTemperatureLabel.value

        val dotColor = when {
            tel.privacyShieldActive -> Color.parseColor("#10B981")
            isLive -> Color.parseColor("#EF4444")
            isPaused || tel.state == StreamLifecycleState.RECONNECTING -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#3B82F6")
        }
        (badgeDotView?.background as? GradientDrawable)?.setColor(dotColor)
        (edgeDockDotView?.background as? GradientDrawable)?.setColor(dotColor)

        val showTimer = isLive || isPaused
        val showTemp = tempLabel.isNotBlank() && tempLabel != "Unmeasured" && isActive
        liveMiniStrip?.visibility = if (showTimer || showTemp) View.VISIBLE else View.GONE
        badgeTimerText?.text = if (showTimer) timerStr else ""
        badgeTempText?.text = if (showTemp) tempLabel else ""
        badgeTempText?.setTextColor(
            if ((tel.deviceTempCelsius ?: 0f) >= ZroStreamCoordinator.currentPerfSettings.thermalThresholdCelsius) {
                Color.parseColor("#FCA5A5")
            } else {
                Color.parseColor("#93C5FD")
            }
        )

        drawerStatusText?.text = when {
            tel.privacyShieldActive && isLive -> "PRIVACY SHIELD • $timerStr"
            isLive -> "LIVE • $timerStr"
            tel.state == StreamLifecycleState.SENDING_VIDEO -> "SENDING VIDEO…"
            tel.state == StreamLifecycleState.CONNECTED -> "CONNECTED TO SERVER…"
            tel.state == StreamLifecycleState.CONNECTING -> "CONNECTING…"
            isPaused -> "PAUSED • $timerStr"
            tel.state == StreamLifecycleState.RECONNECTING -> "RECONNECTING…"
            tel.state == StreamLifecycleState.ERROR -> "ERROR • Check Live Setup"
            tel.capturePreviewActive -> "PREVIEW • Ready"
            else -> "${tel.state.displayLabel} • ${tel.resolutionLabel} ${tel.targetFps}fps"
        }

        val actualMbps = String.format(Locale.US, "%.1f", tel.actualBitrateKbps / 1000f)
        val targetMbps = String.format(Locale.US, "%.1f", (tel.selectedBitrateKbps.takeIf { it > 0 } ?: tel.targetBitrateKbps) / 1000f)
        drawerMetaText?.text = if (isActive) {
            "Temp: $tempLabel • $actualMbps/$targetMbps Mbps • ${workspace.activeScene.name}"
        } else {
            "Temp: $tempLabel • ${workspace.activeScene.name}"
        }

        drawerStreamBtn?.text = if (isActive) "Stop ($timerStr)" else "Start Live"
        (drawerStreamBtn?.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (isActive) "#DC2626" else "#2563EB")
        )

        drawerPauseBtn?.visibility = if (isActive) View.VISIBLE else View.GONE
        drawerPauseBtn?.text = if (isPaused) "Resume" else "Pause"
        (drawerPauseBtn?.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (isPaused) "#059669" else "#B45309")
        )

        drawerBitrateRow?.visibility = if (isActive) View.VISIBLE else View.GONE
        drawerBitrateLabel?.text = "Target $targetMbps M"

        // Highlight currently active Screen 1–4
        sceneButtons.forEach { (slot, btn) ->
            val selected = workspace.activeSceneSlot == slot
            (btn.background as? GradientDrawable)?.setColor(
                Color.parseColor(if (selected) "#2563EB" else "#1E293B")
            )
        }

        drawerMicBtn?.text = if (audio.micEnabled) "Mic ON" else "Mic OFF"
        (drawerMicBtn?.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (audio.micEnabled) "#1E293B" else "#7F1D1D")
        )
        drawerGameBtn?.text = if (audio.gameAudioEnabled) "Game ON" else "Game OFF"
        drawerTeamBtn?.text = if (audio.teamVoiceEnabled) "Team ON" else "Team OFF"
    }

    private fun createCompactTile(label: String, bgHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun openAppDestination(target: ZroStreamCoordinator.DeepLinkTarget) {
        ZroStreamCoordinator.emitNavEvent(target)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(StreamNotificationManager.EXTRA_NAV_TARGET, target.name)
        }
        runCatching { startActivity(intent) }
    }

    private fun removeOverlay() {
        rootContainer?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        rootContainer = null
        layoutParams = null
        isEdgeHidden = false
        runCatching { cachedCustomLogoBitmap?.recycle() }
        cachedCustomLogoBitmap = null
        cachedLogoUri = null
        ZroStreamCoordinator.setOverlayVisible(false)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        scope.cancel()
    }
}
