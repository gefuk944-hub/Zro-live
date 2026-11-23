package com.example.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamRuntimeTelemetry

class StreamNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID_LIVE = "zro_live_stream_channel"
        const val CHANNEL_ID_ALERTS = "zro_live_alerts_channel"
        const val NOTIFICATION_ID_STREAM = 4001
        const val EXTRA_NAV_TARGET = "zro_nav_target"
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
                description = "Persistent live broadcast timer, game capture status, and quick mute/stop controls."
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

    fun buildNotification(
        telemetry: StreamRuntimeTelemetry,
        micMuted: Boolean,
        voiceRoomName: String?
    ): Notification {
        val openControlsIntent = PendingIntent.getActivity(
            context,
            101,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_NAV_TARGET, ZroStreamCoordinator.DeepLinkTarget.LIVE_CONTROLS.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleMicIntent = PendingIntent.getService(
            context,
            103,
            Intent(context, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_TOGGLE_MIC
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endStreamIntent = PendingIntent.getService(
            context,
            104,
            Intent(context, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_STOP_STREAM
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopCaptureIntent = PendingIntent.getService(
            context,
            105,
            Intent(context, StreamingForegroundService::class.java).apply {
                action = StreamingForegroundService.ACTION_STOP_CAPTURE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isStreaming = telemetry.state == StreamLifecycleState.LIVE ||
            telemetry.state == StreamLifecycleState.PAUSED ||
            telemetry.state == StreamLifecycleState.CONNECTING ||
            telemetry.state == StreamLifecycleState.RECONNECTING

        val title = when {
            telemetry.privacyShieldActive && isStreaming ->
                "Game Hidden — Privacy Shield Active • ${ZroStreamCoordinator.formatDuration(telemetry.durationSec)}"
            telemetry.state == StreamLifecycleState.LIVE ->
                "LIVE (${telemetry.captureMode.shortLabel}) • ${ZroStreamCoordinator.formatDuration(telemetry.durationSec)}"
            telemetry.state == StreamLifecycleState.PAUSED ->
                "STREAM PAUSED • ${ZroStreamCoordinator.formatDuration(telemetry.durationSec)}"
            telemetry.state == StreamLifecycleState.RECONNECTING ->
                "RECONNECTING (Attempt ${telemetry.reconnectAttempt})"
            telemetry.state == StreamLifecycleState.CONNECTING ->
                "Starting YouTube Live…"
            telemetry.capturePreviewActive ->
                "Game Capture Preview Ready (${telemetry.captureMode.shortLabel})"
            !voiceRoomName.isNullOrBlank() ->
                "Voice Room: $voiceRoomName"
            else ->
                "Zro live Ready"
        }

        val bitrateMbps = String.format("%.1f", telemetry.actualBitrateKbps / 1000f)
        val contentText = when {
            telemetry.privacyShieldActive && isStreaming ->
                "Video paused automatically while selected game is in background."
            isStreaming ->
                "${telemetry.resolutionLabel} • ${telemetry.actualFps.coerceAtLeast(telemetry.targetFps)} FPS • $bitrateMbps Mbps • ${telemetry.streamHealth.label}"
            telemetry.capturePreviewActive ->
                "Previewing ${telemetry.captureSourceLabel}. Not live yet."
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
            .addAction(
                0,
                if (micMuted) "Unmute Mic" else "Mute Mic",
                toggleMicIntent
            )
            .addAction(0, "Controls", openControlsIntent)

        if (isStreaming) {
            builder.addAction(0, "End Stream", endStreamIntent)
        } else if (telemetry.captureActive) {
            builder.addAction(0, "Stop Capture", stopCaptureIntent)
        }

        return builder.build()
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
