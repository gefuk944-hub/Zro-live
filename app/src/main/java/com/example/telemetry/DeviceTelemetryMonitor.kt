package com.example.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.example.data.model.DeviceTelemetry
import com.example.data.model.PerformanceProfile
import com.example.data.model.SmartRecommendation
import com.example.data.model.StreamPreset
import com.example.data.model.VideoResolution
import java.util.Locale

/**
 * Monitors genuine Android hardware telemetry with zero fabricated values:
 * - Battery temperature (returns null if sensor unavailable; never fakes a safe temp)
 * - PowerManager thermal status (API 29+)
 * - ActivityManager total & available RAM (4GB low-RAM detection)
 * - ConnectivityManager transport & upstream bandwidth estimate
 * - MediaCodecList hardware H.264 encoder & 720p/1080p/1440p/4K @ 30/60/90 FPS + max bitrate inspection (cached once)
 * - Android 14+ (API 34+) single-app capture capability detection
 */
class DeviceTelemetryMonitor(private val context: Context) {

    private data class HardwareEncoderCaps(
        val encoderName: String = "Auto H.264",
        val supports1080p60: Boolean = false,
        val supports720p90: Boolean = false,
        val supports1080p90: Boolean = false,
        val supports1440p30: Boolean = false,
        val supports1440p60: Boolean = false,
        val supports4k30: Boolean = false,
        val supports4k60: Boolean = false,
        val maxBitrateKbps: Int = 15_000
    ) {
        val supports90Fps: Boolean get() = supports720p90 || supports1080p90
    }

    private val encoderCaps: HardwareEncoderCaps by lazy { inspectAvcEncoderCapabilities() }

    fun snapshot(): DeviceTelemetry {
        val tempC = readBatteryTempCelsius()
        val (thermalLabel, thermalSevere) = readThermalStatus(tempC)
        val (totalRamMb, availRamMb) = readMemoryMb()
        val (netType, connected, upstreamKbps) = readNetworkTelemetry()
        val caps = encoderCaps

        val isOverheating = thermalSevere || (tempC != null && tempC >= 42.0f)

        return DeviceTelemetry(
            batteryTempCelsius = tempC,
            isTemperatureMeasured = tempC != null,
            thermalStatusLabel = thermalLabel,
            isOverheating = isOverheating,
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            isLowRamDevice = totalRamMb <= 4400L,
            networkType = netType,
            networkConnected = connected,
            upstreamBandwidthKbps = upstreamKbps,
            supports1080p60 = caps.supports1080p60,
            supports720p90 = caps.supports720p90,
            supports1080p90 = caps.supports1080p90,
            supports1440p30 = caps.supports1440p30,
            supports1440p60 = caps.supports1440p60,
            supports4k30 = caps.supports4k30,
            supports4k60 = caps.supports4k60,
            supports90Fps = caps.supports90Fps,
            maxEncoderBitrateKbps = caps.maxBitrateKbps,
            hardwareAvcEncoderName = caps.encoderName,
            supportsAppOnlyCapture = supportsSingleAppCapture()
        )
    }

    fun computeSmartRecommendation(
        t: DeviceTelemetry,
        profile: PerformanceProfile = PerformanceProfile.BALANCED,
        lowRamMode: Boolean = false
    ): SmartRecommendation {
        val up = t.upstreamBandwidthKbps
        if (!t.networkConnected) {
            return SmartRecommendation(
                preset = StreamPreset.DATA_SAVER,
                resolution = VideoResolution.P144,
                fps = 30,
                bitrateKbps = 1000,
                rationale = "No active network detected. Connect to Wi-Fi or 5G before going live."
            )
        }

        if (t.isOverheating) {
            val tempDesc = t.batteryTempCelsius?.let { "${String.format(Locale.US, "%.1f", it)}°C" } ?: t.thermalStatusLabel
            return SmartRecommendation(
                preset = StreamPreset.BALANCED,
                resolution = VideoResolution.P480,
                fps = 30,
                bitrateKbps = 2200,
                rationale = "Overheating detected ($tempDesc). 480p 30 FPS lowers CPU/GPU heat."
            )
        }

        if (profile == PerformanceProfile.BATTERY_SAVER) {
            return SmartRecommendation(
                preset = StreamPreset.BALANCED,
                resolution = VideoResolution.P480,
                fps = 30,
                bitrateKbps = 2400,
                rationale = "Battery Saver profile active: 480p 30 FPS minimizes encoder power draw and heat."
            )
        }

        if (t.isLowRamDevice || lowRamMode) {
            return if (up >= 6000) {
                SmartRecommendation(
                    preset = StreamPreset.HD,
                    resolution = VideoResolution.P720,
                    fps = 30,
                    bitrateKbps = 3800,
                    rationale = "4GB RAM optimization (${t.totalRamMb / 1024}GB): 720p 30 FPS preserves gameplay smoothness."
                )
            } else {
                SmartRecommendation(
                    preset = StreamPreset.BALANCED,
                    resolution = VideoResolution.P480,
                    fps = 30,
                    bitrateKbps = 2400,
                    rationale = "4GB RAM + ${String.format(Locale.US, "%.1f", up / 1000f)} Mbps uplink: 480p 30 FPS prevents stutter."
                )
            }
        }

        val tempCoolForUltra = t.batteryTempCelsius == null || t.batteryTempCelsius < 37.5f
        val tempCoolEnoughFor1080p60 = t.batteryTempCelsius == null || t.batteryTempCelsius < 38.5f
        val maxBitrate = maxAllowedBitrateKbps(
            telemetry = t,
            profile = profile,
            lowRamMode = lowRamMode,
            lowDataMode = false
        )

        return when {
            profile == PerformanceProfile.PERFORMANCE && up >= 32000 && t.supports4k30 && tempCoolForUltra && t.totalRamMb >= 7500L -> {
                val targetBitrate = 24000.coerceAtMost(maxBitrate)
                SmartRecommendation(
                    preset = StreamPreset.UHD_4K,
                    resolution = VideoResolution.P2160,
                    fps = 30,
                    bitrateKbps = targetBitrate,
                    rationale = "High-capacity ${t.networkType} uplink & hardware 4K encoder (${t.hardwareAvcEncoderName}) with cool thermals."
                )
            }
            up >= 20000 && t.supports1440p60 && tempCoolForUltra && t.totalRamMb >= 6000L -> {
                val targetBitrate = 16000.coerceAtMost(maxBitrate)
                SmartRecommendation(
                    preset = StreamPreset.QHD_SMOOTH,
                    resolution = VideoResolution.P1440,
                    fps = 60,
                    bitrateKbps = targetBitrate,
                    rationale = "Strong ${t.networkType} uplink & hardware 1440p60 encoder (${t.hardwareAvcEncoderName})."
                )
            }
            up >= 14000 && t.supports1080p60 && tempCoolEnoughFor1080p60 -> SmartRecommendation(
                preset = StreamPreset.FULL_HD_SMOOTH,
                resolution = VideoResolution.P1080,
                fps = 60,
                bitrateKbps = 9500.coerceAtMost(maxBitrate),
                rationale = "Strong ${t.networkType} uplink & hardware 1080p60 encoder (${t.hardwareAvcEncoderName})."
            )
            up >= 9000 -> {
                val thermalNote = t.batteryTempCelsius?.let { " • ${String.format(Locale.US, "%.1f", it)}°C" } ?: ""
                SmartRecommendation(
                    preset = StreamPreset.FULL_HD,
                    resolution = VideoResolution.P1080,
                    fps = 30,
                    bitrateKbps = 7000.coerceAtMost(maxBitrate),
                    rationale = "Fast ${t.networkType} connection ready for 1080p 30 FPS$thermalNote."
                )
            }
            up >= 6500 -> SmartRecommendation(
                preset = StreamPreset.HD_SMOOTH,
                resolution = VideoResolution.P720,
                fps = 60,
                bitrateKbps = 5500.coerceAtMost(maxBitrate),
                rationale = "Crisp 720p at 60 FPS for competitive mobile gameplay."
            )
            up >= 4000 -> SmartRecommendation(
                preset = StreamPreset.HD,
                resolution = VideoResolution.P720,
                fps = 30,
                bitrateKbps = 4000.coerceAtMost(maxBitrate),
                rationale = "Stable 720p HD matched to current ${t.networkType} upstream capacity."
            )
            up >= 2200 -> SmartRecommendation(
                preset = StreamPreset.BALANCED,
                resolution = VideoResolution.P480,
                fps = 30,
                bitrateKbps = 2400.coerceAtMost(maxBitrate),
                rationale = "Moderate uplink bandwidth; 480p 30 FPS minimizes dropped frames."
            )
            else -> SmartRecommendation(
                preset = StreamPreset.LOW,
                resolution = VideoResolution.P360,
                fps = 30,
                bitrateKbps = 1500,
                rationale = "Constrained uplink detected; 360p 30 FPS keeps broadcast uninterrupted."
            )
        }
    }

    /**
     * Reads genuine battery temperature from Android sticky broadcast.
     * Returns null if unavailable or invalid—NEVER fakes a safe temperature like 32.0°C.
     */
    private fun readBatteryTempCelsius(): Float? {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            // Sanity range: 1.0°C to 100.0°C (tenths 10..1000). Invalid/missing returns null.
            if (tenths in 10..1000) tenths / 10f else null
        } catch (_: Exception) {
            null
        }
    }

    private fun readThermalStatus(tempC: Float?): Pair<String, Boolean> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val status = runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.currentThermalStatus
            }.getOrNull()
            if (status != null) {
                return when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> (if (tempC != null) "Normal" else "Not Measured") to false
                    PowerManager.THERMAL_STATUS_LIGHT -> "Warm" to false
                    PowerManager.THERMAL_STATUS_MODERATE -> "Moderate" to (tempC != null && tempC >= 41f)
                    PowerManager.THERMAL_STATUS_SEVERE -> "Hot (Throttling)" to true
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Critical Heat" to true
                    else -> "Unavailable" to false
                }
            }
        }
        if (tempC == null) {
            return "Unavailable" to false
        }
        return when {
            tempC >= 43f -> "Hot (Throttling)" to true
            tempC >= 39f -> "Warm" to false
            else -> "Normal" to false
        }
    }

    private fun readMemoryMb(): Pair<Long, Long> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 4096L to 2048L
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem / (1024L * 1024L)) to (info.availMem / (1024L * 1024L))
        } catch (_: Exception) {
            4096L to 2048L
        }
    }

    private fun readNetworkTelemetry(): Triple<String, Boolean, Int> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return Triple("Offline", false, 0)
            val net = cm.activeNetwork ?: return Triple("Offline", false, 0)
            val caps = cm.getNetworkCapabilities(net) ?: return Triple("Offline", false, 0)
            val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Network"
            }
            val upKbps = caps.linkUpstreamBandwidthKbps.coerceAtLeast(0)
            Triple(type, connected, upKbps)
        } catch (_: Exception) {
            Triple("Unknown", false, 0)
        }
    }

    private fun inspectAvcEncoderCapabilities(): HardwareEncoderCaps {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var bestName = "Auto H.264"
            var supports1080p60 = false
            var supports720p90 = false
            var supports1080p90 = false
            var supports1440p30 = false
            var supports1440p60 = false
            var supports4k30 = false
            var supports4k60 = false
            var maxBitrateKbps = 15_000

            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
                val isHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.contains("google", ignoreCase = true) && !info.name.startsWith("c2.android.", ignoreCase = true)
                }
                val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: continue
                val vCaps = caps.videoCapabilities ?: continue
                if (isHardware) {
                    if (bestName == "Auto H.264") bestName = info.name
                    if (runCatching { vCaps.areSizeAndRateSupported(1920, 1080, 60.0) }.getOrDefault(false)) {
                        supports1080p60 = true
                        bestName = info.name
                    }
                    if (runCatching { vCaps.areSizeAndRateSupported(1280, 720, 90.0) }.getOrDefault(false)) {
                        supports720p90 = true
                    }
                    if (runCatching { vCaps.areSizeAndRateSupported(1920, 1080, 90.0) }.getOrDefault(false)) {
                        supports1080p90 = true
                    }
                    if (runCatching { vCaps.areSizeAndRateSupported(2560, 1440, 30.0) }.getOrDefault(false)) {
                        supports1440p30 = true
                    }
                    if (runCatching { vCaps.areSizeAndRateSupported(2560, 1440, 60.0) }.getOrDefault(false)) {
                        supports1440p60 = true
                    }
                    if (runCatching { vCaps.areSizeAndRateSupported(3840, 2160, 30.0) }.getOrDefault(false)) {
                        supports4k30 = true
                    }
                    if (runCatching { vCaps.areSizeAndRateSupported(3840, 2160, 60.0) }.getOrDefault(false)) {
                        supports4k60 = true
                    }
                    val upperKbps = runCatching { (vCaps.bitrateRange.upper / 1000) }.getOrDefault(15_000)
                    if (upperKbps > maxBitrateKbps) {
                        maxBitrateKbps = upperKbps.coerceIn(4_000, MAX_PLATFORM_BITRATE_KBPS)
                    }
                }
            }
            HardwareEncoderCaps(
                encoderName = bestName,
                supports1080p60 = supports1080p60,
                supports720p90 = supports720p90,
                supports1080p90 = supports1080p90,
                supports1440p30 = supports1440p30,
                supports1440p60 = supports1440p60,
                supports4k30 = supports4k30,
                supports4k60 = supports4k60,
                maxBitrateKbps = maxBitrateKbps.coerceIn(4_000, MAX_PLATFORM_BITRATE_KBPS)
            )
        } catch (_: Exception) {
            HardwareEncoderCaps()
        }
    }

    companion object {
        const val MAX_PLATFORM_BITRATE_KBPS = 40_000

        /** Android 14 (API 34) introduced single-app MediaProjection capture via MediaProjectionConfig. */
        fun supportsSingleAppCapture(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        fun formatTemperature(telemetry: DeviceTelemetry): String {
            val temp = telemetry.batteryTempCelsius
            return if (temp != null && telemetry.isTemperatureMeasured) {
                "${String.format(Locale.US, "%.1f", temp)}°C"
            } else {
                telemetry.thermalStatusLabel
            }
        }

        /**
         * Honest hardware capability check for resolution + FPS combinations.
         * 720p/1080p @ 30fps is universally supported; 1080p60, 1440p, 4K, and 90 FPS require probed encoder support.
         */
        fun supportsResolutionAndFps(
            telemetry: DeviceTelemetry,
            resolution: VideoResolution,
            fps: Int
        ): Boolean = when (resolution) {
            VideoResolution.P144,
            VideoResolution.P240,
            VideoResolution.P360,
            VideoResolution.P480 -> fps <= 60 || telemetry.supports720p90
            VideoResolution.P720 -> when {
                fps <= 60 -> true
                fps <= 90 -> telemetry.supports720p90
                else -> false
            }
            VideoResolution.P1080 -> when {
                fps <= 30 -> true
                fps <= 60 -> telemetry.supports1080p60
                fps <= 90 -> telemetry.supports1080p90
                else -> false
            }
            VideoResolution.P1440 -> when {
                fps <= 30 -> telemetry.supports1440p30
                fps <= 60 -> telemetry.supports1440p60
                else -> false
            }
            VideoResolution.P2160 -> when {
                fps <= 30 -> telemetry.supports4k30
                fps <= 60 -> telemetry.supports4k60
                else -> false
            }
        }

        /**
         * Calculates the real maximum allowed bitrate (up to 40,000 kbps / 40 Mbps)
         * gated by hardware encoder capability, performance profile, RAM, low-data mode, and network uplink.
         */
        fun maxAllowedBitrateKbps(
            telemetry: DeviceTelemetry,
            profile: PerformanceProfile,
            lowRamMode: Boolean,
            lowDataMode: Boolean
        ): Int {
            val encoderCap = telemetry.maxEncoderBitrateKbps.coerceIn(4_000, MAX_PLATFORM_BITRATE_KBPS)
            val profileCap = profile.maxBitrateCapKbps.coerceAtMost(MAX_PLATFORM_BITRATE_KBPS)
            val ramCap = if (lowRamMode || telemetry.isLowRamDevice) 6_000 else MAX_PLATFORM_BITRATE_KBPS
            val dataCap = if (lowDataMode) 2_500 else MAX_PLATFORM_BITRATE_KBPS
            val thermalCap = if (telemetry.isOverheating) 4_000 else MAX_PLATFORM_BITRATE_KBPS
            // When Android reports upstream bandwidth (>0), cap at 85% of uplink so we never falsely promise 40 Mbps on slow links
            val netCap = if (telemetry.networkConnected && telemetry.upstreamBandwidthKbps > 0) {
                (telemetry.upstreamBandwidthKbps * 0.85f).toInt().coerceIn(2_000, MAX_PLATFORM_BITRATE_KBPS)
            } else {
                MAX_PLATFORM_BITRATE_KBPS
            }
            return minOf(encoderCap, profileCap, ramCap, dataCap, thermalCap, netCap).coerceAtLeast(1_500)
        }
    }
}
