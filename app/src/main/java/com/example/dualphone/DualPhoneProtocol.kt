package com.example.dualphone

import android.net.Uri
import org.json.JSONObject
import java.security.SecureRandom

data class DualPairingPayload(
    val ip: String,
    val controlPort: Int = DualPhoneProtocol.DEFAULT_CONTROL_PORT,
    val videoPort: Int = DualPhoneProtocol.DEFAULT_VIDEO_PORT,
    val token: String,
    val hostName: String = "Zro Studio",
    val relayUrl: String = ""
)

data class DualRemoteCommand(
    val action: String,
    val stringArg: String = "",
    val floatArg: Float = 0f,
    val boolArg: Boolean = false
)

object DualPhoneProtocol {
    const val DEFAULT_CONTROL_PORT = 47920
    const val DEFAULT_VIDEO_PORT = 47921
    const val SCHEME = "zrolive"
    const val HOST = "pair"

    // Wire message types
    const val MSG_AUTH = "AUTH"
    const val MSG_AUTH_OK = "AUTH_OK"
    const val MSG_AUTH_FAIL = "AUTH_FAIL"
    const val MSG_PING = "PING"
    const val MSG_PONG = "PONG"
    const val MSG_COMMAND = "CMD"
    const val MSG_TELEMETRY = "TELEMETRY"

    // Remote command actions
    const val CMD_START_LIVE = "START_LIVE"
    const val CMD_END_LIVE = "END_LIVE"
    const val CMD_PAUSE_RESUME = "PAUSE_RESUME"
    const val CMD_MUTE_MIC = "MUTE_MIC"
    const val CMD_SET_MIC_VOL = "SET_MIC_VOL"
    const val CMD_SET_GAME_VOL = "SET_GAME_VOL"
    const val CMD_SET_TEAM_VOL = "SET_TEAM_VOL"
    const val CMD_SET_MASTER_VOL = "SET_MASTER_VOL"
    const val CMD_SET_TEAM_VOICE_LIVE = "SET_TEAM_VOICE_LIVE"
    const val CMD_SWITCH_SCENE = "SWITCH_SCENE"
    const val CMD_TOGGLE_OVERLAYS = "TOGGLE_OVERLAYS"
    const val CMD_SCREENSHOT = "SCREENSHOT"
    const val CMD_SWITCH_CAMERA = "SWITCH_CAMERA"
    const val CMD_TOGGLE_CAMERA = "TOGGLE_CAMERA"
    const val CMD_TRIGGER_BRB = "TRIGGER_BRB"
    const val CMD_RESUME_FROM_BRB = "RESUME_FROM_BRB"

    private const val TOKEN_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val secureRandom = SecureRandom()

    fun generateSecureToken(length: Int = 8): String {
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(TOKEN_CHARS[secureRandom.nextInt(TOKEN_CHARS.length)])
        }
        return sb.toString()
    }

    fun buildPairingUri(payload: DualPairingPayload): String {
        val encodedName = Uri.encode(payload.hostName.take(32))
        val base = "$SCHEME://$HOST?ip=${Uri.encode(payload.ip)}&port=${payload.controlPort}&vport=${payload.videoPort}&token=${Uri.encode(payload.token)}&name=$encodedName"
        return if (payload.relayUrl.isNotBlank()) {
            "$base&relay=${Uri.encode(payload.relayUrl.trim())}"
        } else {
            base
        }
    }

    /**
     * Parses a QR URI (`zrolive://pair?ip=...&port=...&vport=...&token=...&name=...&relay=...`)
     * or returns null if malformed / missing both IP and relay URL or token.
     */
    fun parsePairingUri(raw: String): DualPairingPayload? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return runCatching {
            val uri = Uri.parse(trimmed)
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host, ignoreCase = true)) {
                return null
            }
            val ip = uri.getQueryParameter("ip")?.trim().orEmpty()
            val relay = uri.getQueryParameter("relay")?.trim().orEmpty()
            val token = uri.getQueryParameter("token")?.trim().orEmpty()
            if ((ip.isBlank() && !isValidRelayUrl(relay)) || token.length < 4) return null
            val port = uri.getQueryParameter("port")?.toIntOrNull()?.coerceIn(1024, 65535) ?: DEFAULT_CONTROL_PORT
            val vport = uri.getQueryParameter("vport")?.toIntOrNull()?.coerceIn(1024, 65535) ?: DEFAULT_VIDEO_PORT
            val name = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: "Zro Studio"
            DualPairingPayload(
                ip = ip,
                controlPort = port,
                videoPort = vport,
                token = token,
                hostName = name,
                relayUrl = relay
            )
        }.getOrNull()
    }

    fun isValidRelayUrl(raw: String): Boolean {
        val s = raw.trim()
        if (s.length < 8 || s.length > 256) return false
        val lower = s.lowercase()
        if (!lower.startsWith("wss://") && !lower.startsWith("ws://")) return false
        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return false
        val host = uri.host?.trim().orEmpty()
        return host.isNotBlank() && !host.contains(" ")
    }

    fun buildRelayRoomEndpoint(relayUrl: String, roomToken: String, role: String): String {
        val base = relayUrl.trim().trimEnd('/')
        val sep = if (base.contains("?")) "&" else "?"
        return "${base}${sep}room=${Uri.encode(roomToken.trim().uppercase())}&role=${Uri.encode(role)}"
    }

    fun isValidIpv4OrHost(raw: String): Boolean {
        val s = raw.trim()
        if (s.isBlank() || s.length > 64) return false
        if (s.equals("localhost", ignoreCase = true)) return true
        val parts = s.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            val n = p.toIntOrNull()
            n != null && n in 0..255
        }
    }

    fun encodeAuth(token: String, deviceName: String): String =
        JSONObject().apply {
            put("type", MSG_AUTH)
            put("token", token)
            put("deviceName", deviceName.take(40))
        }.toString()

    fun encodeAuthOk(hostName: String, videoPort: Int): String =
        JSONObject().apply {
            put("type", MSG_AUTH_OK)
            put("deviceName", hostName.take(40))
            put("videoPort", videoPort)
        }.toString()

    fun encodeAuthFail(reason: String): String =
        JSONObject().apply {
            put("type", MSG_AUTH_FAIL)
            put("reason", reason)
        }.toString()

    fun encodePing(tsMs: Long): String =
        JSONObject().apply {
            put("type", MSG_PING)
            put("ts", tsMs)
        }.toString()

    fun encodePong(tsMs: Long): String =
        JSONObject().apply {
            put("type", MSG_PONG)
            put("ts", tsMs)
        }.toString()

    fun encodeCommand(cmd: DualRemoteCommand): String =
        JSONObject().apply {
            put("type", MSG_COMMAND)
            put("action", cmd.action)
            put("str", cmd.stringArg)
            put("flt", cmd.floatArg.toDouble())
            put("bool", cmd.boolArg)
        }.toString()

    fun decodeCommand(obj: JSONObject): DualRemoteCommand =
        DualRemoteCommand(
            action = obj.optString("action", ""),
            stringArg = obj.optString("str", ""),
            floatArg = obj.optDouble("flt", 0.0).toFloat(),
            boolArg = obj.optBoolean("bool", false)
        )

    fun encodeTelemetry(
        resolution: String,
        fps: Int,
        uploadKbps: Int,
        packetLossPercent: Float
    ): String =
        JSONObject().apply {
            put("type", MSG_TELEMETRY)
            put("res", resolution)
            put("fps", fps)
            put("kbps", uploadKbps)
            put("loss", packetLossPercent.toDouble())
        }.toString()
}
