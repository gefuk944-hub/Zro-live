package com.example.overlay

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Lightweight, memory-efficient floating gaming overlay (zero heavy Compose overhead in overlay window)
 * rendered over games via SYSTEM_ALERT_WINDOW after explicit user consent.
 * - Movable floating badge showing Zro live mark, live pulse dot, duration, and FPS/network telemetry.
 * - Compact expandable control drawer: Stream status, Start/Stop, Mic Mute, Game Audio, Team Voice,
 *   Chat shortcut, Voice Room shortcut, Hide Overlay, Close Overlay.
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
    private var badgeFpsText: TextView? = null
    private var drawerPanel: LinearLayout? = null
    private var drawerStatusText: TextView? = null
    private var drawerStreamBtn: TextView? = null
    private var drawerMicBtn: TextView? = null
    private var drawerGameBtn: TextView? = null
    private var drawerTeamBtn: TextView? = null

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
            alpha = overlaySettings.opacity.coerceIn(0.4f, 1.0f)
        }

        // Compact floating pill badge
        val badge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E610141F"))
                cornerRadius = dp(999).toFloat()
                setStroke(dp(1), Color.parseColor("#803B82F6"))
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
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), 0, 0, 0)
        }
        badgeTimerText = timerTv
        badge.addView(timerTv)

        val fpsTv = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#60A5FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), 0, 0, 0)
        }
        badgeFpsText = fpsTv
        badge.addView(fpsTv)

        root.addView(badge)

        // Expandable compact control panel
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F20B0F19"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#663B82F6"))
            }
        }
        val panelParams = LinearLayout.LayoutParams(dp(210), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }

        val statusTv = TextView(this).apply {
            text = "STANDBY • Ready"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        drawerStatusText = statusTv
        panel.addView(statusTv)

        val streamBtn = createDrawerActionButton("Start / Open Stream", "#2563EB") {
            val state = ZroStreamCoordinator.telemetry.value.state
            if (state == StreamLifecycleState.LIVE || state == StreamLifecycleState.PAUSED) {
                ZroStreamCoordinator.requestStopStream(this)
            } else {
                openAppDestination(ZroStreamCoordinator.DeepLinkTarget.LIVE_CONTROLS)
            }
            toggleDrawer(false)
        }
        drawerStreamBtn = streamBtn
        panel.addView(streamBtn)

        val micBtn = createDrawerActionButton("Mic: ON", "#1E293B") {
            ZroStreamCoordinator.emitAudioQuickAction(ZroStreamCoordinator.AudioQuickAction.TOGGLE_MIC)
            refreshControlsText()
        }
        drawerMicBtn = micBtn
        panel.addView(micBtn)

        val gameBtn = createDrawerActionButton("Game Audio: ON", "#1E293B") {
            ZroStreamCoordinator.emitAudioQuickAction(ZroStreamCoordinator.AudioQuickAction.TOGGLE_GAME)
            refreshControlsText()
        }
        drawerGameBtn = gameBtn
        panel.addView(gameBtn)

        val teamBtn = createDrawerActionButton("Team Voice: ON", "#1E293B") {
            ZroStreamCoordinator.emitAudioQuickAction(ZroStreamCoordinator.AudioQuickAction.TOGGLE_TEAM)
            refreshControlsText()
        }
        drawerTeamBtn = teamBtn
        panel.addView(teamBtn)

        panel.addView(createDrawerActionButton("Open Live Chat", "#312E81") {
            openAppDestination(ZroStreamCoordinator.DeepLinkTarget.LIVE_CHAT)
            toggleDrawer(false)
        })

        panel.addView(createDrawerActionButton("Voice Room", "#312E81") {
            openAppDestination(ZroStreamCoordinator.DeepLinkTarget.VOICE_ROOM)
            toggleDrawer(false)
        })

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        val hideBtn = createDrawerActionButton("Hide", "#1E293B") {
            toggleDrawer(false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            }
        }
        val closeBtn = createDrawerActionButton("Close", "#7F1D1D") {
            removeOverlay()
            stopSelf()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            }
        }
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
    }

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

        badge.setOnTouchListener { _, event ->
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
        rootContainer?.alpha = cfg.opacity.coerceIn(0.4f, 1.0f)
    }

    private fun refreshControlsText() {
        val tel = ZroStreamCoordinator.telemetry.value
        val audio = ZroStreamCoordinator.currentAudioSettings
        val isLive = tel.state == StreamLifecycleState.LIVE
        val isPaused = tel.state == StreamLifecycleState.PAUSED

        val dotDrawable = badgeDotView?.background as? GradientDrawable
        dotDrawable?.setColor(
            when {
                tel.privacyShieldActive -> Color.parseColor("#10B981")
                tel.state == StreamLifecycleState.LIVE -> Color.parseColor("#EF4444")
                tel.state == StreamLifecycleState.PAUSED || tel.state == StreamLifecycleState.RECONNECTING -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#3B82F6")
            }
        )

        badgeTimerText?.text = if (isLive || isPaused) ZroStreamCoordinator.formatDuration(tel.durationSec) else ""
        badgeFpsText?.text = when {
            tel.privacyShieldActive -> "SHIELD"
            isLive -> "${tel.actualFps.coerceAtLeast(tel.targetFps)}fps"
            else -> ""
        }

        drawerStatusText?.text = when {
            tel.privacyShieldActive && isLive -> "PRIVACY SHIELD • Game Hidden"
            tel.state == StreamLifecycleState.LIVE -> "LIVE (${tel.captureMode.shortLabel}) • ${ZroStreamCoordinator.formatDuration(tel.durationSec)}"
            tel.state == StreamLifecycleState.PAUSED -> "PAUSED • ${ZroStreamCoordinator.formatDuration(tel.durationSec)}"
            tel.state == StreamLifecycleState.RECONNECTING -> "RECONNECTING…"
            tel.capturePreviewActive -> "PREVIEW • ${tel.captureMode.shortLabel}"
            else -> "STANDBY • ${tel.resolutionLabel} ${tel.targetFps}fps"
        }

        drawerStreamBtn?.text = if (isLive || isPaused) "End Stream" else "Open Live Studio"
        (drawerStreamBtn?.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (isLive || isPaused) "#DC2626" else "#2563EB")
        )

        drawerMicBtn?.text = if (audio.micEnabled) "Mic: ON" else "Mic: MUTED"
        drawerGameBtn?.text = if (audio.gameAudioEnabled) "Game Audio: ON" else "Game Audio: OFF"
        drawerTeamBtn?.text = if (audio.teamVoiceEnabled) "Team Voice: ON" else "Team Voice: OFF"
    }

    private fun createDrawerActionButton(label: String, bgHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
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
