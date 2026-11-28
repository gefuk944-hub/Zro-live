package com.example.streaming

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Snapshot of the active live stream timer and BGMI Ping Guard ABR metrics,
 * driven exclusively by [StreamingForegroundService] using monotonic [SystemClock.elapsedRealtime].
 */
data class StreamLiveTimerState(
    val isStreaming: Boolean = false,
    val isPaused: Boolean = false,
    val streamStartElapsedRealtime: Long = 0L,
    val elapsedMillis: Long = 0L,
    val elapsedSeconds: Long = 0L,
    val formattedDuration: String = "00:00:00",
    val socketWriteLatencyMs: Long = 0L,
    val abrActive: Boolean = false,
    val activeBitrateKbps: Int = 3800,
    val targetBitrateKbps: Int = 3800
)

/**
 * Singleton state holder publishing the authoritative live stream duration ("HH:MM:SS")
 * and real-time RTMP Ping Guard metrics to:
 *  - Start Live screen (`LiveStudioScreen`, `LiveSetupScreen`, `HomeDashboardScreen`)
 *  - Remote Control (`DualPhoneStudioScreen`)
 *  - Floating Overlay (`FloatingOverlayService`)
 *  - Persistent Foreground Notification (`StreamNotificationManager`)
 *
 * The timer is driven by a dedicated 500ms ticker inside [StreamingForegroundService]
 * computing `SystemClock.elapsedRealtime() - streamStartElapsedRealtime`, so it never
 * freezes when the user switches to BGMI or when RTMP stalls, and resets to `00:00:00`
 * ONLY when [resetToStopped] is called upon stream stop.
 */
object StreamStateManager {

    const val TIMER_TICK_INTERVAL_MS: Long = 500L

    private val _state = MutableStateFlow(StreamLiveTimerState())
    val state: StateFlow<StreamLiveTimerState> = _state.asStateFlow()

    private val lock = Any()

    /**
     * Marks the live RTMP broadcast as started and records the monotonic start timestamp.
     * Safe to call multiple times while already streaming without resetting the start anchor.
     */
    fun markStreamStarted(
        startElapsedRealtime: Long = safeElapsedRealtime(),
        targetBitrateKbps: Int = _state.value.targetBitrateKbps
    ): StreamLiveTimerState = synchronized(lock) {
        val current = _state.value
        val anchor = if (current.isStreaming && current.streamStartElapsedRealtime > 0L) {
            current.streamStartElapsedRealtime
        } else {
            startElapsedRealtime.coerceAtLeast(1L)
        }
        val elapsedMs = (startElapsedRealtime - anchor).coerceAtLeast(0L)
        val next = current.copy(
            isStreaming = true,
            isPaused = false,
            streamStartElapsedRealtime = anchor,
            elapsedMillis = elapsedMs,
            elapsedSeconds = elapsedMs / 1000L,
            formattedDuration = formatElapsedMillis(elapsedMs),
            activeBitrateKbps = targetBitrateKbps.coerceAtLeast(1000),
            targetBitrateKbps = targetBitrateKbps.coerceAtLeast(1000),
            abrActive = false
        )
        _state.value = next
        next
    }

    /**
     * Advances the published monotonic elapsed timer. Invoked every 500ms from
     * [StreamingForegroundService] while a broadcast is active.
     */
    fun tick(nowElapsedRealtime: Long = safeElapsedRealtime()): StreamLiveTimerState = synchronized(lock) {
        val current = _state.value
        if (!current.isStreaming || current.streamStartElapsedRealtime <= 0L) {
            return@synchronized current
        }
        val elapsedMs = (nowElapsedRealtime - current.streamStartElapsedRealtime).coerceAtLeast(0L)
        val elapsedSec = elapsedMs / 1000L
        val formatted = formatElapsedMillis(elapsedMs)
        val next = current.copy(
            elapsedMillis = elapsedMs,
            elapsedSeconds = elapsedSec,
            formattedDuration = formatted
        )
        _state.value = next
        next
    }

    /**
     * Updates pause state without resetting the monotonic broadcast timer.
     */
    fun setPaused(paused: Boolean): StreamLiveTimerState = synchronized(lock) {
        val current = _state.value
        if (!current.isStreaming) return@synchronized current
        val next = current.copy(isPaused = paused)
        _state.value = next
        next
    }

    /**
     * Updates BGMI Ping Guard socket write latency and adaptive bitrate telemetry.
     */
    fun updateNetworkMetrics(
        socketWriteLatencyMs: Long,
        activeBitrateKbps: Int,
        targetBitrateKbps: Int,
        abrActive: Boolean
    ): StreamLiveTimerState = synchronized(lock) {
        val current = _state.value
        val next = current.copy(
            socketWriteLatencyMs = socketWriteLatencyMs.coerceAtLeast(0L),
            activeBitrateKbps = activeBitrateKbps.coerceAtLeast(1000),
            targetBitrateKbps = targetBitrateKbps.coerceAtLeast(1000),
            abrActive = abrActive
        )
        _state.value = next
        next
    }

    /**
     * Resets the live timer back to `00:00:00`. MUST ONLY be called when the stream stops.
     */
    fun resetToStopped(): StreamLiveTimerState = synchronized(lock) {
        val stopped = StreamLiveTimerState(
            isStreaming = false,
            isPaused = false,
            streamStartElapsedRealtime = 0L,
            elapsedMillis = 0L,
            elapsedSeconds = 0L,
            formattedDuration = "00:00:00",
            socketWriteLatencyMs = 0L,
            abrActive = false,
            activeBitrateKbps = _state.value.targetBitrateKbps,
            targetBitrateKbps = _state.value.targetBitrateKbps
        )
        _state.value = stopped
        stopped
    }

    /**
     * Formats elapsed milliseconds as `HH:MM:SS` (e.g. `00:00:00`, `01:24:09`).
     */
    fun formatElapsedMillis(elapsedMillis: Long): String {
        val totalSeconds = (elapsedMillis.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun safeElapsedRealtime(): Long = runCatching {
        SystemClock.elapsedRealtime()
    }.getOrElse { System.nanoTime() / 1_000_000L }
}
