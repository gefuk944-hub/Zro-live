package com.example.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.data.model.OverlayCorner
import com.example.data.model.StreamLifecycleState
import com.example.streaming.StreamNotificationManager
import com.example.streaming.ZroStreamCoordinator
import com.example.studio.SceneSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * Lightweight, memory-efficient floating live-control button rendered over games via SYSTEM_ALERT_WINDOW.
 * - Floating pill: live pulse dot, ZRO mark, live timer (HH:MM:SS), and real phone temperature.
 * - Expandable drawer: Start / Stop Live, Pause / Resume, Screen 1 / Screen 2 / Screen 3 / Screen 4 switcher,
 *   Mic / Game / Team quick toggles, Open Chat, Hide, and Close.
 */
class FloatingOverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.example.zro.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.example.zro.OVERLAY_HIDE"
        const val ACTION_REFRESH = "com.example.zro.OVERLAY_REFRESH"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var rootContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var badgeDotView: View? = null
    private var badgeTimerText: TextView? = null
    private var badgeTempText: TextView? = null
    private var drawerPanel: LinearLayout? = null
    private var drawerStatusText: TextView? = null
    private var drawerMetaText: TextView? = null
    private var drawerStreamBtn: TextView? = null
    private var drawerPauseBtn: TextView? = null
    private var drawerMicBtn: TextView? = null
    private var drawerGameBtn: TextView? = null
    private var drawerTeamBtn: TextView? = null
    private val sceneButtons = mutableMapOf<SceneSlot, TextView>()

    private var isExpanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removeOverlay()
                stopSelf()
            }
            ACTION_REFRESH -> {
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

        // Compact floating pill badge
        val badge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EC0D1119"))
                cornerRadius = dp(999).toFloat()
                setStroke(dp(1), Color.parseColor("#882563EB"))
            }
        }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#64748B"))
            }
        }
        badgeDotView = dot
        badge.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(6) })

        val brandText = TextView(this).apply {
            text = "ZRO"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        badge.addView(brandText)

        val timerTv = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), 0, 0, 0)
        }
        badgeTimerText = timerTv
        badge.addView(timerTv)

        val tempTv = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), 0, 0, 0)
        }
        badgeTempText = tempTv
        badge.addView(tempTv)

        root.addView(badge)

        // Expandable compact studio control drawer
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
        val panelParams = LinearLayout.LayoutParams(dp(236), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
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

        // Footer Row: Studio / Hide / Close
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val studioBtn = createCompactTile("Studio", "#312E81") {
            openAppDestination(ZroStreamCoordinator.DeepLinkTarget.LIVE_CONTROLS)
            toggleDrawer(false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(3) }
        }
        val hideBtn = createCompactTile("Hide", "#1E293B") {
            toggleDrawer(false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }
        val closeBtn = createCompactTile("Close", "#7F1D1D") {
            removeOverlay()
            stopSelf()
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

        val (grav, initX, initY) = when (overlaySettings.position) {
            OverlayCorner.TOP_LEFT -> Triple(Gravity.TOP or Gravity.START, dp(12), dp(90))
            OverlayCorner.TOP_RIGHT -> Triple(Gravity.TOP or Gravity.END, dp(12), dp(90))
            OverlayCorner.BOTTOM_LEFT -> Triple(Gravity.BOTTOM or Gravity.START, dp(12), dp(120))
            OverlayCorner.BOTTOM_RIGHT -> Triple(Gravity.BOTTOM or Gravity.END, dp(12), dp(120))
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = grav
            x = initX
            y = initY
        }

        attachDragAndTapHandler(badge, lp, wm, root)

        runCatching {
            wm.addView(root, lp)
            rootContainer = root
            layoutParams = lp
            ZroStreamCoordinator.setOverlayVisible(true)
        }

        scope.launch {
            ZroStreamCoordinator.telemetry.collectLatest {
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
        badge: View,
        lp: WindowManager.LayoutParams,
        wm: WindowManager,
        root: View
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        badge.setOnTouchListener { v, event ->
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
                        lp.x = if (isEnd) startX - dx else startX + dx
                        lp.y = if (isBottom) startY - dy else startY + dy
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        v.performClick()
                        toggleDrawer(!isExpanded)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleDrawer(expand: Boolean) {
        isExpanded = expand
        drawerPanel?.visibility = if (expand) View.VISIBLE else View.GONE
        refreshControlsText()
    }

    private fun applyAppearancePreferences() {
        val cfg = ZroStreamCoordinator.currentOverlaySettings
        rootContainer?.alpha = cfg.opacity.coerceIn(0.45f, 1.0f)
    }

    private fun refreshControlsText() {
        val tel = ZroStreamCoordinator.telemetry.value
        val audio = ZroStreamCoordinator.currentAudioSettings
        val workspace = ZroStreamCoordinator.activeStudioWorkspace.value
        val isLive = tel.state == StreamLifecycleState.LIVE
        val isPaused = tel.state == StreamLifecycleState.PAUSED
        val isActive = tel.state.isActive
        val timerStr = ZroStreamCoordinator.formatDuration(tel.durationSec)
        val tempLabel = tel.deviceTempCelsius?.let { "${String.format(Locale.US, "%.1f", it)}°C" }
            ?: ZroStreamCoordinator.deviceTemperatureLabel.value

        val dotDrawable = badgeDotView?.background as? GradientDrawable
        dotDrawable?.setColor(
            when {
                tel.privacyShieldActive -> Color.parseColor("#10B981")
                isLive -> Color.parseColor("#EF4444")
                isPaused || tel.state == StreamLifecycleState.RECONNECTING -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#3B82F6")
            }
        )

        badgeTimerText?.text = if (isLive || isPaused) timerStr else ""
        badgeTempText?.text = if (tempLabel.isNotBlank() && tempLabel != "Unmeasured") tempLabel else ""
        badgeTempText?.setTextColor(
            if ((tel.deviceTempCelsius ?: 0f) >= 42f) Color.parseColor("#FCA5A5") else Color.parseColor("#93C5FD")
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

        drawerMetaText?.text = "Temp: $tempLabel • ${workspace.activeScene.name}"

        drawerStreamBtn?.text = if (isActive) "Stop ($timerStr)" else "Start Live"
        (drawerStreamBtn?.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (isActive) "#DC2626" else "#2563EB")
        )

        drawerPauseBtn?.visibility = if (isActive) View.VISIBLE else View.GONE
        drawerPauseBtn?.text = if (isPaused) "Resume" else "Pause"
        (drawerPauseBtn?.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (isPaused) "#059669" else "#B45309")
        )

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
