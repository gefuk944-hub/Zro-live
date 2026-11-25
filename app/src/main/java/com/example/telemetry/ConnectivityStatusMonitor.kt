package com.example.telemetry

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.data.model.RtmpTestResult
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamRuntimeTelemetry
import com.example.dualphone.DualPhoneEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom

enum class NetworkLinkType(val label: String) {
    WIFI("Wi-Fi Online"),
    CELLULAR("Mobile Data"),
    ETHERNET("Ethernet"),
    OFFLINE("Offline")
}

data class NetworkStatusInfo(
    val isOnline: Boolean = false,
    val linkType: NetworkLinkType = NetworkLinkType.OFFLINE,
    val validated: Boolean = false,
    val upstreamKbps: Int = 0,
    val localIp: String = "",
    val detail: String = "Checking network…"
)

enum class ServerReachabilityState(val label: String) {
    UNTESTED("Not Tested"),
    TESTING("Testing…"),
    REACHABLE("Reachable"),
    UNREACHABLE("Unreachable")
}

data class IceServerConfig(
    val primaryStunHost: String = "stun.l.google.com",
    val primaryStunPort: Int = 19302,
    val secondaryStunHost: String = "stun1.l.google.com",
    val secondaryStunPort: Int = 19302,
    val customTurnUrl: String = ""
) {
    fun primaryStunUri(): String = "stun:$primaryStunHost:$primaryStunPort"
    fun secondaryStunUri(): String = "stun:$secondaryStunHost:$secondaryStunPort"
}

data class ServerStatusInfo(
    val state: ServerReachabilityState = ServerReachabilityState.UNTESTED,
    val rtmpServerHost: String = "",
    val rtmpLatencyMs: Long = 0L,
    val stunReachable: Boolean? = null,
    val stunLatencyMs: Long = 0L,
    val stunServerUsed: String = "stun.l.google.com:19302",
    val detail: String = "Run Test Connection to verify RTMP ingest server"
)

data class StreamingStatusInfo(
    val state: StreamLifecycleState = StreamLifecycleState.IDLE,
    val label: String = "OFFLINE",
    val detail: String = "Stream idle",
    val isActivelyStreaming: Boolean = false
)

data class AppConnectivitySnapshot(
    val network: NetworkStatusInfo = NetworkStatusInfo(),
    val server: ServerStatusInfo = ServerStatusInfo(),
    val streaming: StreamingStatusInfo = StreamingStatusInfo(),
    val iceConfig: IceServerConfig = IceServerConfig()
)

/**
 * Monitors real Android ConnectivityManager state, performs real RFC 5389 STUN UDP probes,
 * and keeps Network Status, Server Status, and Streaming Status strictly separate and truthful.
 */
class ConnectivityStatusMonitor(private val appContext: Context) {

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _networkStatus = MutableStateFlow(readCurrentNetworkStatus())
    val networkStatus: StateFlow<NetworkStatusInfo> = _networkStatus.asStateFlow()

    private val _stunStatus = MutableStateFlow<Pair<Boolean?, Long>>(null to 0L)
    val stunStatus: StateFlow<Pair<Boolean?, Long>> = _stunStatus.asStateFlow()

    private var registeredCallback: ConnectivityManager.NetworkCallback? = null

    fun startMonitoring() {
        _networkStatus.value = readCurrentNetworkStatus()
        val cm = connectivityManager ?: return
        if (registeredCallback != null) return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _networkStatus.value = readCurrentNetworkStatus()
            }

            override fun onLost(network: Network) {
                _networkStatus.value = readCurrentNetworkStatus()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                _networkStatus.value = readCurrentNetworkStatus()
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            registeredCallback = callback
        }
    }

    fun stopMonitoring() {
        val cb = registeredCallback ?: return
        registeredCallback = null
        runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
    }

    fun refreshNow(): NetworkStatusInfo {
        val current = readCurrentNetworkStatus()
        _networkStatus.value = current
        return current
    }

    fun readCurrentNetworkStatus(): NetworkStatusInfo {
        val cm = connectivityManager ?: return NetworkStatusInfo(
            isOnline = false,
            linkType = NetworkLinkType.OFFLINE,
            detail = "Network service unavailable"
        )
        return runCatching {
            val active = cm.activeNetwork
            val caps = if (active != null) cm.getNetworkCapabilities(active) else null
            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val upKbps = caps?.linkUpstreamBandwidthKbps?.coerceAtLeast(0) ?: 0
            val localIp = DualPhoneEngine.discoverLocalIpv4Address()

            val linkType = when {
                !hasInternet -> NetworkLinkType.OFFLINE
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkLinkType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkLinkType.ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkLinkType.CELLULAR
                else -> NetworkLinkType.WIFI
            }
            val detail = when (linkType) {
                NetworkLinkType.OFFLINE -> "No active internet connection"
                NetworkLinkType.WIFI -> buildString {
                    append("Wi-Fi")
                    if (localIp.isNotBlank() && localIp != "127.0.0.1") append(" • $localIp")
                    if (upKbps > 0) append(" • ${upKbps / 1000} Mbps up")
                }
                NetworkLinkType.CELLULAR -> buildString {
                    append("Cellular")
                    if (upKbps > 0) append(" • ${upKbps / 1000} Mbps up")
                }
                NetworkLinkType.ETHERNET -> "Ethernet • $localIp"
            }
            NetworkStatusInfo(
                isOnline = hasInternet,
                linkType = linkType,
                validated = validated,
                upstreamKbps = upKbps,
                localIp = localIp,
                detail = detail
            )
        }.getOrDefault(
            NetworkStatusInfo(
                isOnline = false,
                linkType = NetworkLinkType.OFFLINE,
                detail = "Unable to inspect network state"
            )
        )
    }

    /**
     * Real RFC 5389 STUN Binding Request over UDP to verify ICE/NAT traversal reachability
     * without spoofing or hardcoding results.
     */
    suspend fun probeStunServer(
        host: String = "stun.l.google.com",
        port: Int = 19302,
        timeoutMs: Int = 2500
    ): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        var socket: DatagramSocket? = null
        try {
            val txId = ByteArray(12).also { SecureRandom().nextBytes(it) }
            // RFC 5389 STUN Binding Request (20 bytes): Type 0x0001, Length 0x0000, Magic Cookie 0x2112A442, 12-byte TxId
            val req = ByteBuffer.allocate(20).apply {
                putShort(0x0001)
                putShort(0x0000)
                putInt(0x2112A442)
                put(txId)
            }.array()

            val address = InetAddress.getByName(host)
            socket = DatagramSocket().apply { soTimeout = timeoutMs }
            socket.send(DatagramPacket(req, req.size, address, port))

            val respBuf = ByteArray(256)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPacket)

            val rtt = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            // Verify STUN Binding Success Response (0x0101) and matching Magic Cookie
            val isStunSuccess = respPacket.length >= 20 &&
                (respBuf[0].toInt() and 0xFF) == 0x01 &&
                (respBuf[1].toInt() and 0xFF) == 0x01 &&
                (respBuf[4].toInt() and 0xFF) == 0x21 &&
                (respBuf[5].toInt() and 0xFF) == 0x12
            _stunStatus.value = isStunSuccess to rtt
            isStunSuccess to rtt
        } catch (_: Exception) {
            _stunStatus.value = false to 0L
            false to 0L
        } finally {
            runCatching { socket?.close() }
        }
    }

    companion object {
        fun buildSnapshot(
            network: NetworkStatusInfo,
            rtmpTest: RtmpTestResult?,
            isTestingRtmp: Boolean,
            stunState: Pair<Boolean?, Long>,
            streamTelemetry: StreamRuntimeTelemetry,
            configuredRtmpUrl: String,
            iceConfig: IceServerConfig = IceServerConfig()
        ): AppConnectivitySnapshot {
            val serverState = when {
                isTestingRtmp -> ServerReachabilityState.TESTING
                streamTelemetry.state == StreamLifecycleState.LIVE ||
                    streamTelemetry.state == StreamLifecycleState.SENDING_VIDEO ||
                    streamTelemetry.state == StreamLifecycleState.CONNECTED -> ServerReachabilityState.REACHABLE
                rtmpTest?.success == true -> ServerReachabilityState.REACHABLE
                rtmpTest?.success == false -> ServerReachabilityState.UNREACHABLE
                else -> ServerReachabilityState.UNTESTED
            }

            val hostDisplay = runCatching {
                val u = java.net.URI(configuredRtmpUrl.trim())
                u.host.orEmpty()
            }.getOrDefault("")

            val serverDetail = when (serverState) {
                ServerReachabilityState.TESTING -> "Handshaking with ingest server…"
                ServerReachabilityState.REACHABLE -> when {
                    streamTelemetry.state == StreamLifecycleState.LIVE ||
                        streamTelemetry.state == StreamLifecycleState.SENDING_VIDEO ->
                        "Connected to ${streamTelemetry.activeServerUrl.ifBlank { hostDisplay }}"
                    rtmpTest != null -> "Verified in ${rtmpTest.latencyMs} ms"
                    else -> "Ingest server reachable"
                }
                ServerReachabilityState.UNREACHABLE -> rtmpTest?.message ?: "Ingest server unreachable"
                ServerReachabilityState.UNTESTED -> if (hostDisplay.isBlank()) {
                    "Enter RTMP URL in Live Setup"
                } else {
                    "$hostDisplay (not tested yet)"
                }
            }

            val streamingActive = streamTelemetry.state == StreamLifecycleState.LIVE ||
                streamTelemetry.state == StreamLifecycleState.SENDING_VIDEO ||
                streamTelemetry.state == StreamLifecycleState.CONNECTED ||
                streamTelemetry.state == StreamLifecycleState.CONNECTING ||
                streamTelemetry.state == StreamLifecycleState.PAUSED ||
                streamTelemetry.state == StreamLifecycleState.RECONNECTING

            val streamingLabel = when (streamTelemetry.state) {
                StreamLifecycleState.IDLE -> "OFFLINE"
                StreamLifecycleState.READY -> "CAPTURE READY (OFFLINE)"
                StreamLifecycleState.CONNECTING -> "CONNECTING"
                StreamLifecycleState.CONNECTED -> "CONNECTED"
                StreamLifecycleState.SENDING_VIDEO -> "SENDING VIDEO"
                StreamLifecycleState.LIVE -> "LIVE"
                StreamLifecycleState.PAUSED -> "PAUSED"
                StreamLifecycleState.RECONNECTING -> "RECONNECTING"
                StreamLifecycleState.ERROR -> "ERROR"
            }

            return AppConnectivitySnapshot(
                network = network,
                server = ServerStatusInfo(
                    state = serverState,
                    rtmpServerHost = hostDisplay,
                    rtmpLatencyMs = rtmpTest?.latencyMs ?: 0L,
                    stunReachable = stunState.first,
                    stunLatencyMs = stunState.second,
                    stunServerUsed = "${iceConfig.primaryStunHost}:${iceConfig.primaryStunPort}",
                    detail = serverDetail
                ),
                streaming = StreamingStatusInfo(
                    state = streamTelemetry.state,
                    label = streamingLabel,
                    detail = streamTelemetry.errorMessage?.takeIf { it.isNotBlank() }
                        ?: streamTelemetry.statusDetail.ifBlank { "Not streaming" },
                    isActivelyStreaming = streamingActive
                ),
                iceConfig = iceConfig
            )
        }
    }
}
