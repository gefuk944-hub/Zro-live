package com.example.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamRuntimeTelemetry
import com.example.studio.SceneSlot
import java.util.Locale

class StreamNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID_LIVE = "zro_live_stream_channel"
        const val CHANNEL_ID_ALERTS = "zro_live_alerts_channel"
        const val NOTIFICATION_ID_STREAM = 4001
        const val EXTRA_NAV_TARGET = "zro_nav_target"

        private const val REQ_OPEN_CONTROLS = 101
        private const val REQ_TOGGLE_PAUSE = 102
        private const val REQ_TOGGLE_MIC = 103
        private const val REQ_END_STREAM = 104
        private const val REQ_STOP_CAPTURE = 105
        private const val REQ_SCENE_1 = 201
        private const val REQ_SCENE_2 = 202
        private const val REQ_SCENE_3 = 203
        private const val REQ_SCENE_4 = 204
    }

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureChannels()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val liveChannel = NotificationChannel(
                CHANNEL_ID_LIVE,
                "Live Streaming & Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent live broadcast timer, temperature, Screen 1–4 quick switcher, and stream controls."
                setShowBadge(false)
            }
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Stream & Teammate Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Network reconnect notices, thermal warnings, and teammate room alerts."
            }
            nm.createNotificationChannel(liveChannel)
            nm.createNotificationChannel(alertChannel)
        }
    }

    fun buildSceneSwitchPendingIntent(slot: SceneSlot): PendingIntent {
        val reqCode = when (slot) {
            SceneSlot.GAMEPLAY_LIVE -> REQ_SCENE_1
            SceneSlot.SHORTS_VERTICAL -> REQ_SCENE_2
            SceneSlot.BRB_BREAK -> REQ_SCENE_3
            SceneSlot.ENDING_THANKS -> REQ_SCENE_4
        }
        return PendingIntent.getService(
            context,
            reqCode,
            Intent(context, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_SWITCH_SCENE
                putExtra(StreamingForegroundService.EXTRA_SCENE_SLOT, slot.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildNotification(
        telemetry: StreamRuntimeTelemetry,
        micMuted: Boolean,
        voiceRoomName: String?
    ): Notification {
        val openControlsIntent = PendingIntent.getActivity(
            context,
            REQ_OPEN_CONTROLS,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_NAV_TARGET, ZroStreamCoordinator.DeepLinkTarget.LIVE_CONTROLS.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val togglePauseIntent = PendingIntent.getService(
            context,
            REQ_TOGGLE_PAUSE,
            Intent(context, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_TOGGLE_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleMicIntent = PendingIntent.getService(
            context,
            REQ_TOGGLE_MIC,
            Intent(context, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_TOGGLE_MIC
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endStreamIntent = PendingIntent.getService(
            context,
            REQ_END_STREAM,
            Intent(context, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_STOP_STREAM
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopCaptureIntent = PendingIntent.getService(
            context,
            REQ_STOP_CAPTURE,
            Intent(context, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_STOP_CAPTURE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isStreaming = telemetry.state.isActive
        val isPaused = telemetry.state == StreamLifecycleState.PAUSED
        val timer = ZroStreamCoordinator.formatDuration(telemetry.durationSec)
        val tempLabel = formatTelemetryTemp(telemetry)
        val activeSlot = ZroStreamCoordinator.activeStudioWorkspace.value.activeSceneSlot
        val activeSceneLabel = when (activeSlot) {
            SceneSlot.GAMEPLAY_LIVE -> "Screen 1"
            SceneSlot.SHORTS_VERTICAL -> "Screen 2"
            SceneSlot.BRB_BREAK -> "Screen 3"
            SceneSlot.ENDING_THANKS -> "Screen 4"
        }

        val title = when {
            telemetry.privacyShieldActive && isStreaming ->
                "PRIVACY SHIELD • $timer • $tempLabel"
            telemetry.state == StreamLifecycleState.LIVE ->
                "LIVE • $timer • $tempLabel"
            telemetry.state == StreamLifecycleState.SENDING_VIDEO ->
                "SENDING VIDEO • $tempLabel"
            telemetry.state == StreamLifecycleState.CONNECTED ->
                "CONNECTED — Starting Encoder • $tempLabel"
            telemetry.state == StreamLifecycleState.PAUSED ->
                "PAUSED • $timer • $tempLabel"
            telemetry.state == StreamLifecycleState.RECONNECTING ->
                "RECONNECTING (${telemetry.reconnectAttempt}/4) • $tempLabel"
            telemetry.state == StreamLifecycleState.CONNECTING ->
                "Connecting to Stream Server…"
            telemetry.state == StreamLifecycleState.READY ->
                "READY • $activeSceneLabel • $tempLabel"
            telemetry.capturePreviewActive ->
                "Capture Preview Active • $activeSceneLabel • $tempLabel"
            !voiceRoomName.isNullOrBlank() ->
                "Voice Room: $voiceRoomName"
            else ->
                "Zro Live Ready • $tempLabel"
        }

        val bitrateMbps = String.format(Locale.US, "%.1f", telemetry.actualBitrateKbps / 1000f)
        val contentText = when {
            telemetry.privacyShieldActive && isStreaming ->
                "Video paused automatically while selected game is hidden."
            isStreaming ->
                "$activeSceneLabel (${telemetry.activeSceneName}) • ${telemetry.resolutionLabel} ${telemetry.actualFps.coerceAtLeast(telemetry.targetFps)}fps • $bitrateMbps Mbps"
            telemetry.capturePreviewActive ->
                "$activeSceneLabel • Previewing ${telemetry.captureSourceLabel}"
            else -> {
                val micLabel = if (micMuted) "Mic Muted" else "Mic Active"
                "Background squad voice active • $micLabel"
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_LIVE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openControlsIntent)

        // Expanded RemoteViews gives instant 1-tap Screen 1, Screen 2, Screen 3, Screen 4 + Pause/Resume + Mic + Stop without opening the studio
        runCatching {
            val bigView = RemoteViews(context.packageName, R.layout.notification_live_controls)
            bigView.setTextViewText(R.id.notif_status_title, title)
            bigView.setTextViewText(R.id.notif_temp_badge, tempLabel)
            bigView.setTextColor(
                R.id.notif_temp_badge,
                if ((telemetry.deviceTempCelsius ?: 0f) >= 42f) Color.parseColor("#FCA5A5") else Color.parseColor("#93C5FD")
            )
            bigView.setTextViewText(R.id.notif_sub_telemetry, contentText)

            val activeBg = Color.parseColor("#2563EB")
            val inactiveBg = Color.parseColor("#1E293B")
            bigView.setInt(R.id.notif_btn_screen_1, "setBackgroundColor", if (activeSlot == SceneSlot.GAMEPLAY_LIVE) activeBg else inactiveBg)
            bigView.setInt(R.id.notif_btn_screen_2, "setBackgroundColor", if (activeSlot == SceneSlot.SHORTS_VERTICAL) activeBg else inactiveBg)
            bigView.setInt(R.id.notif_btn_screen_3, "setBackgroundColor", if (activeSlot == SceneSlot.BRB_BREAK) activeBg else inactiveBg)
            bigView.setInt(R.id.notif_btn_screen_4, "setBackgroundColor", if (activeSlot == SceneSlot.ENDING_THANKS) activeBg else inactiveBg)

            bigView.setOnClickPendingIntent(R.id.notif_btn_screen_1, buildSceneSwitchPendingIntent(SceneSlot.GAMEPLAY_LIVE))
            bigView.setOnClickPendingIntent(R.id.notif_btn_screen_2, buildSceneSwitchPendingIntent(SceneSlot.SHORTS_VERTICAL))
            bigView.setOnClickPendingIntent(R.id.notif_btn_screen_3, buildSceneSwitchPendingIntent(SceneSlot.BRB_BREAK))
            bigView.setOnClickPendingIntent(R.id.notif_btn_screen_4, buildSceneSwitchPendingIntent(SceneSlot.ENDING_THANKS))

            bigView.setTextViewText(R.id.notif_btn_pause_resume, if (isPaused) "Resume" else "Pause")
            bigView.setOnClickPendingIntent(R.id.notif_btn_pause_resume, togglePauseIntent)

            bigView.setTextViewText(R.id.notif_btn_mic, if (micMuted) "Unmute" else "Mute Mic")
            bigView.setOnClickPendingIntent(R.id.notif_btn_mic, toggleMicIntent)

            bigView.setTextViewText(R.id.notif_btn_stop, if (isStreaming) "Stop Live" else "Stop")
            bigView.setOnClickPendingIntent(R.id.notif_btn_stop, if (isStreaming) endStreamIntent else stopCaptureIntent)

            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomBigContentView(bigView)
        }

        // Compact notification fallback actions
        if (isStreaming) {
            builder.addAction(0, if (isPaused) "Resume" else "Pause", togglePauseIntent)
        }
        builder.addAction(0, if (micMuted) "Unmute Mic" else "Mute Mic", toggleMicIntent)
        if (isStreaming) {
            builder.addAction(0, "End Stream", endStreamIntent)
        } else if (telemetry.captureActive) {
            builder.addAction(0, "Stop Capture", stopCaptureIntent)
        } else {
            builder.addAction(0, "Controls", openControlsIntent)
        }

        return builder.build()
    }

    private fun formatTelemetryTemp(telemetry: StreamRuntimeTelemetry): String {
        val temp = telemetry.deviceTempCelsius
        return if (temp != null) {
            "${String.format(Locale.US, "%.1f", temp)}°C"
        } else {
            val cached = ZroStreamCoordinator.deviceTemperatureLabel.value
            if (cached.isNotBlank() && cached != "Unmeasured") cached else telemetry.thermalLabel
        }
    }

    fun updateNotification(
        telemetry: StreamRuntimeTelemetry,
        micMuted: Boolean,
        voiceRoomName: String?
    ) {
        runCatching {
            nm.notify(NOTIFICATION_ID_STREAM, buildNotification(telemetry, micMuted, voiceRoomName))
        }
    }
}
