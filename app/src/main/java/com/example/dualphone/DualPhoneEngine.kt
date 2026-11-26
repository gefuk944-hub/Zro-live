package com.example.dualphone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.studio.DualConnectionMethod
import com.example.studio.DualLinkState
import com.example.studio.DualPhoneRole
import com.example.studio.DualPhoneTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Real LAN / Mobile Hotspot Dual-Phone Streaming Engine:
 * - Phone 2 (Studio Host): opens Control TCP socket + Mirror Video TCP socket, validates cryptographic session token,
 *   measures real PING/PONG RTT latency, receives low-latency mirrored gameplay frames, and dispatches Remote Control commands.
 * - Phone 1 (Gameplay Client): connects via QR payload or Manual IP, authenticates with token,
 *   streams compressed gameplay frames to Phone 2, executes incoming Remote Control commands, and auto-reconnects if dropped.
 */
class DualPhoneEngine {

    companion object {
        private const val TAG = "DualPhoneEngine"
        private const val MAX_MIRROR_FRAME_BYTES = 1_500_000
        val shared = DualPhoneEngine()

        fun discoverLocalIpv4Address(): String {
            return runCatching {
                var fallback: String? = null
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                for (ni in interfaces) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val name = ni.name.lowercase()
                    val preferred = name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("eth")
                    for (addr in ni.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            if (preferred) return host
                            if (fallback == null) fallback = host
                        }
                    }
                }
                fallback ?: "127.0.0.1"
            }.getOrDefault("127.0.0.1")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _telemetry = MutableStateFlow(
        DualPhoneTelemetry(localIp = "127.0.0.1")
    )
    val telemetry: StateFlow<DualPhoneTelemetry> = _telemetry.asStateFlow()

    init {
        scope.launch {
            val ip = discoverLocalIpv4Address()
            _telemetry.update { if (it.localIp == "127.0.0.1" || it.localIp.isBlank()) it.copy(localIp = ip) else it }
        }
    }

    private val _mirroredFrame = MutableStateFlow<Bitmap?>(null)
    val mirroredFrame: StateFlow<Bitmap?> = _mirroredFrame.asStateFlow()

    private val _incomingCommands = MutableSharedFlow<DualRemoteCommand>(extraBufferCapacity = 32)
    val incomingCommands: SharedFlow<DualRemoteCommand> = _incomingCommands.asSharedFlow()

    private var controlServerSocket: ServerSocket? = null
    private var videoServerSocket: ServerSocket? = null
    private var activeControlSocket: Socket? = null
    private var activeVideoSocket: Socket? = null

    @Volatile private var controlWriter: BufferedWriter? = null
    @Volatile private var videoDataOut: DataOutputStream? = null

    private var controlLoopJob: Job? = null
    private var videoLoopJob: Job? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null

    private val intentionalStop = AtomicBoolean(false)
    private val lastFrameSentMs = AtomicLong(0L)
    private val framesCounter = AtomicInteger(0)
    private val bytesCounter = AtomicLong(0L)
    private val droppedMirrorFrames = AtomicInteger(0)

    private var videoSenderJob: Job? = null
    private val latestMirrorFrameChannel = Channel<ByteArray>(capacity = Channel.CONFLATED)

    // Secure Internet Relay (wss:// WebSocket via OkHttp) for mobile data & different Wi-Fi networks
    private val relayHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    @Volatile private var activeRelaySocket: WebSocket? = null
    private var relayFrameSenderJob: Job? = null

    private var lastConnectIp: String = ""
    private var lastConnectControlPort: Int = DualPhoneProtocol.DEFAULT_CONTROL_PORT
    private var lastConnectVideoPort: Int = DualPhoneProtocol.DEFAULT_VIDEO_PORT
    private var lastConnectToken: String = ""
    private var lastConnectDeviceName: String = "Phone 1 Gameplay"
    private var lastConnectMethod: DualConnectionMethod = DualConnectionMethod.QR_PAIRING
    private var lastRelayServerUrl: String = ""

    fun setRole(role: DualPhoneRole) {
        if (_telemetry.value.role != role) {
            disconnect(clearRole = false)
        }
        _telemetry.update {
            it.copy(
                role = role,
                localIp = discoverLocalIpv4Address(),
                lastError = null
            )
        }
    }

    fun setConnectionMethod(method: DualConnectionMethod) {
        _telemetry.update { it.copy(connectionMethod = method, localIp = discoverLocalIpv4Address()) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        _telemetry.update { it.copy(autoReconnect = enabled) }
    }

    fun refreshLocalIp(): String {
        val ip = discoverLocalIpv4Address()
        _telemetry.update { it.copy(localIp = ip) }
        return ip
    }

    fun setRelayServerUrl(url: String) {
        val clean = url.trim()
        lastRelayServerUrl = clean
        _telemetry.update { it.copy(relayServerUrl = clean) }
    }

    fun currentPairingUri(hostName: String = "Zro Studio"): String {
        val t = _telemetry.value
        val ip = t.localIp.ifBlank { discoverLocalIpv4Address() }
        val token = t.pairingToken.ifBlank { DualPhoneProtocol.generateSecureToken() }
        return DualPhoneProtocol.buildPairingUri(
            DualPairingPayload(
                ip = ip,
                controlPort = t.hostPort,
                videoPort = t.videoPort,
                token = token,
                hostName = hostName,
                relayUrl = t.relayServerUrl
            )
        )
    }

    /**
     * Phone 2 (Streaming Studio Host): starts secure TCP control + video mirroring servers.
     * Supports ephemeral ports (port = 0) for isolated unit tests as well as default ports 47920/47921.
     */
    fun startPhone2Host(
        hostDeviceName: String = "Zro Studio (Phone 2)",
        controlPort: Int = DualPhoneProtocol.DEFAULT_CONTROL_PORT,
        videoPort: Int = DualPhoneProtocol.DEFAULT_VIDEO_PORT,
        method: DualConnectionMethod = _telemetry.value.connectionMethod,
        presetToken: String? = null,
        preferredIpOverride: String? = null
    ) {
        disconnect(clearRole = false)
        intentionalStop.set(false)

        val token = presetToken?.takeIf { it.length >= 4 } ?: DualPhoneProtocol.generateSecureToken()
        val ip = preferredIpOverride?.trim()?.takeIf { DualPhoneProtocol.isValidIpv4OrHost(it) }
            ?: discoverLocalIpv4Address()

        val relayUrl = _telemetry.value.relayServerUrl.trim()
        if (method == DualConnectionMethod.INTERNET_RELAY && !DualPhoneProtocol.isValidRelayUrl(relayUrl)) {
            _telemetry.update {
                it.copy(
                    role = DualPhoneRole.PHONE2_STUDIO,
                    connectionMethod = method,
                    linkState = DualLinkState.ERROR,
                    signalQualityLabel = "Relay URL Required",
                    lastError = "Enter a valid secure WebSocket relay URL (wss://...) to connect phones over mobile data or different Wi-Fi networks."
                )
            }
            return
        }

        if (method == DualConnectionMethod.INTERNET_RELAY) {
            startPhone2RelayHost(
                relayUrl = relayUrl,
                hostDeviceName = hostDeviceName,
                presetToken = token
            )
            return
        }

        controlLoopJob = scope.launch {
            try {
                val cServer = openServerSocket(controlPort)
                val vServer = openServerSocket(videoPort)
                controlServerSocket = cServer
                videoServerSocket = vServer

                val boundControlPort = cServer.localPort
                val boundVideoPort = vServer.localPort

                _telemetry.update {
                    it.copy(
                        role = DualPhoneRole.PHONE2_STUDIO,
                        connectionMethod = method,
                        linkState = DualLinkState.HOSTING,
                        localIp = ip,
                        hostPort = boundControlPort,
                        videoPort = boundVideoPort,
                        pairingToken = token,
                        connectedDeviceName = "",
                        peerIp = "",
                        transportModeLabel = "Direct LAN",
                        signalQualityLabel = "Waiting for Phone 1",
                        latencyMs = 0,
                        lastError = null
                    )
                }

                // If user also configured a relay URL, keep relay standby connected simultaneously so Phone 1 can join via mobile data!
                if (DualPhoneProtocol.isValidRelayUrl(relayUrl)) {
                    openRelaySocket(
                        relayUrl = relayUrl,
                        roomToken = token,
                        isStudioHost = true,
                        localDeviceName = hostDeviceName
                    )
                }

                // Launch video accept loop
                videoLoopJob = scope.launch {
                    runPhone2VideoAcceptLoop(vServer)
                }

                // Control accept loop
                while (isActive && !intentionalStop.get() && !cServer.isClosed) {
                    val client = try {
                        cServer.accept()
                    } catch (_: Exception) {
                        break
                    }
                    handlePhone2ControlClient(client, token, hostDeviceName, boundVideoPort)
                }
            } catch (e: Exception) {
                if (!intentionalStop.get()) {
                    Log.e(TAG, "Phone 2 host failed: ${e.message}", e)
                    _telemetry.update {
                        it.copy(
                            linkState = DualLinkState.ERROR,
                            signalQualityLabel = "Offline",
                            lastError = e.localizedMessage ?: "Failed to start Studio host socket."
                        )
                    }
                }
            }
        }
    }

    private fun openServerSocket(preferredPort: Int): ServerSocket {
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        } catch (_: Exception) {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        }
    }

    private suspend fun handlePhone2ControlClient(
        client: Socket,
        expectedToken: String,
        hostDeviceName: String,
        videoPort: Int
    ) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))

            // 1. Require AUTH handshake as first message
            val firstLine = reader.readLine()
            val authObj = firstLine?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (authObj == null || authObj.optString("type") != DualPhoneProtocol.MSG_AUTH) {
                writeLineSafe(writer, DualPhoneProtocol.encodeAuthFail("Authentication required."))
                runCatching { client.close() }
                return
            }
            val providedToken = authObj.optString("token", "")
            val tokenValid = MessageDigest.isEqual(
                providedToken.toByteArray(Charsets.UTF_8),
                expectedToken.toByteArray(Charsets.UTF_8)
            )
            if (!tokenValid) {
                writeLineSafe(writer, DualPhoneProtocol.encodeAuthFail("Invalid pairing token."))
                runCatching { client.close() }
                _telemetry.update { it.copy(lastError = "Rejected pairing attempt with invalid token.") }
                return
            }

            val peerName = authObj.optString("deviceName", "Phone 1 Gameplay")
            val peerAddress = client.inetAddress?.hostAddress ?: "LAN"

            runCatching { activeControlSocket?.close() }
            activeControlSocket = client
            controlWriter = writer
            client.soTimeout = 0

            writeLineSafe(writer, DualPhoneProtocol.encodeAuthOk(hostDeviceName, videoPort))

            _telemetry.update {
                it.copy(
                    linkState = DualLinkState.CONNECTED,
                    connectedDeviceName = peerName,
                    peerIp = peerAddress,
                    signalQualityLabel = "Strong LAN",
                    lastError = null
                )
            }

            // Start real PING RTT measurement every 1.5s
            pingJob?.cancel()
            pingJob = scope.launch {
                while (isActive && activeControlSocket == client && !client.isClosed) {
                    val sent = writeLineSafe(writer, DualPhoneProtocol.encodePing(System.currentTimeMillis()))
                    if (!sent) break
                    delay(1500L)
                }
            }

            // Read control messages from Phone 1
            while (!intentionalStop.get() && !client.isClosed) {
                val line = reader.readLine() ?: break
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                when (obj.optString("type")) {
                    DualPhoneProtocol.MSG_PONG -> {
                        val sentTs = obj.optLong("ts", 0L)
                        if (sentTs > 0L) {
                            val rtt = (System.currentTimeMillis() - sentTs).toInt().coerceAtLeast(1)
                            val quality = when {
                                rtt <= 25 -> "Excellent (${rtt}ms)"
                                rtt <= 70 -> "Good (${rtt}ms)"
                                rtt <= 150 -> "Fair (${rtt}ms)"
                                else -> "High Latency (${rtt}ms)"
                            }
                            _telemetry.update { it.copy(latencyMs = rtt, signalQualityLabel = quality) }
                        }
                    }
                    DualPhoneProtocol.MSG_PING -> {
                        writeLineSafe(writer, DualPhoneProtocol.encodePong(obj.optLong("ts", System.currentTimeMillis())))
                    }
                    DualPhoneProtocol.MSG_TELEMETRY -> {
                        _telemetry.update {
                            it.copy(
                                mirroredResolution = obj.optString("res", it.mirroredResolution),
                                mirroredFps = obj.optInt("fps", it.mirroredFps),
                                uploadKbps = obj.optInt("kbps", it.uploadKbps),
                                packetLossPercent = obj.optDouble("loss", it.packetLossPercent.toDouble()).toFloat()
                            )
                        }
                    }
                    DualPhoneProtocol.MSG_COMMAND -> {
                        _incomingCommands.tryEmit(DualPhoneProtocol.decodeCommand(obj))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Phone 2 client loop ended: ${e.message}")
        } finally {
            pingJob?.cancel()
            if (activeControlSocket == client) {
                activeControlSocket = null
                controlWriter = null
            }
            runCatching { client.close() }
            if (!intentionalStop.get() && controlServerSocket?.isClosed == false) {
                _mirroredFrame.value = null
                _telemetry.update {
                    it.copy(
                        linkState = DualLinkState.HOSTING,
                        connectedDeviceName = "",
                        signalQualityLabel = "Waiting for Phone 1",
                        mirroredFps = 0,
                        uploadKbps = 0
                    )
                }
            }
        }
    }

    private suspend fun runPhone2VideoAcceptLoop(vServer: ServerSocket) {
        while (!intentionalStop.get() && !vServer.isClosed) {
            val client = try {
                vServer.accept()
            } catch (_: Exception) {
                break
            }
            try {
                client.tcpNoDelay = true
                client.receiveBufferSize = 128 * 1024
                client.soTimeout = 4000
                val dataIn = DataInputStream(BufferedInputStream(client.getInputStream(), 131072))

                // Security: authenticate the video mirror socket with the active pairing token
                val presentedToken = runCatching { dataIn.readUTF().trim().uppercase() }.getOrNull().orEmpty()
                val expectedToken = _telemetry.value.pairingToken.trim().uppercase()
                val tokenValid = expectedToken.isNotBlank() && java.security.MessageDigest.isEqual(
                    presentedToken.toByteArray(Charsets.UTF_8),
                    expectedToken.toByteArray(Charsets.UTF_8)
                )
                if (!tokenValid) {
                    Log.w(TAG, "Rejected unauthenticated video mirror connection")
                    runCatching { client.close() }
                    continue
                }
                client.soTimeout = 0
                activeVideoSocket = client

                var lastStatMs = System.currentTimeMillis()
                var framesInSec = 0
                var bytesInSec = 0L

                while (!intentionalStop.get() && !client.isClosed) {
                    val frameLen = dataIn.readInt()
                    if (frameLen <= 0 || frameLen > MAX_MIRROR_FRAME_BYTES) break
                    val frameBytes = ByteArray(frameLen)
                    dataIn.readFully(frameBytes)
                    val bmp = BitmapFactory.decodeByteArray(frameBytes, 0, frameLen)
                    if (bmp != null) {
                        _mirroredFrame.value = bmp
                        framesInSec++
                        bytesInSec += frameLen
                        val now = System.currentTimeMillis()
                        if (now - lastStatMs >= 1000L) {
                            val kbps = ((bytesInSec * 8L) / 1000L).toInt()
                            _telemetry.update {
                                it.copy(
                                    mirroredFps = framesInSec,
                                    mirroredResolution = "${bmp.width}x${bmp.height}",
                                    uploadKbps = kbps,
                                    lastFrameEpochMs = now
                                )
                            }
                            framesInSec = 0
                            bytesInSec = 0L
                            lastStatMs = now
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Video mirror stream closed: ${e.message}")
            } finally {
                if (activeVideoSocket == client) {
                    activeVideoSocket = null
                    _mirroredFrame.value = null
                }
                runCatching { client.close() }
            }
        }
    }

    /**
     * Phone 2 (Streaming Studio Host) over Mobile Data / Different Wi-Fi via secure wss:// WebSocket Relay.
     */
    fun startPhone2RelayHost(
        relayUrl: String,
        hostDeviceName: String = "Zro Studio (Phone 2)",
        presetToken: String? = null
    ) {
        val cleanRelay = relayUrl.trim()
        if (!DualPhoneProtocol.isValidRelayUrl(cleanRelay)) {
            _telemetry.update {
                it.copy(
                    linkState = DualLinkState.ERROR,
                    signalQualityLabel = "Relay URL Invalid",
                    lastError = "Enter a valid wss:// relay server URL for cross-network Dual-Phone pairing."
                )
            }
            return
        }
        disconnect(clearRole = false)
        intentionalStop.set(false)
        lastRelayServerUrl = cleanRelay
        val token = presetToken?.takeIf { it.length >= 4 } ?: DualPhoneProtocol.generateSecureToken()
        _telemetry.update {
            it.copy(
                role = DualPhoneRole.PHONE2_STUDIO,
                connectionMethod = DualConnectionMethod.INTERNET_RELAY,
                linkState = DualLinkState.HOSTING,
                relayServerUrl = cleanRelay,
                pairingToken = token,
                connectedDeviceName = "",
                peerIp = "Internet Relay",
                transportModeLabel = "Secure WebSocket Relay",
                signalQualityLabel = "Connecting Relay…",
                latencyMs = 0,
                lastError = null
            )
        }
        openRelaySocket(
            relayUrl = cleanRelay,
            roomToken = token,
            isStudioHost = true,
            localDeviceName = hostDeviceName
        )
    }

    /**
     * Phone 1 (Gameplay Client) over Mobile Data / Different Wi-Fi via secure wss:// WebSocket Relay.
     */
    fun connectPhone1ViaRelay(
        relayUrl: String,
        token: String,
        deviceName: String = "Phone 1 Gameplay"
    ) {
        val cleanRelay = relayUrl.trim()
        val cleanToken = token.trim().uppercase()
        if (!DualPhoneProtocol.isValidRelayUrl(cleanRelay)) {
            _telemetry.update {
                it.copy(
                    linkState = DualLinkState.ERROR,
                    lastError = "Enter a valid wss:// WebSocket relay URL."
                )
            }
            return
        }
        if (cleanToken.length < 4) {
            _telemetry.update {
                it.copy(
                    linkState = DualLinkState.ERROR,
                    lastError = "Enter the 8-character Room Token shown on Phone 2."
                )
            }
            return
        }
        disconnect(clearRole = false)
        intentionalStop.set(false)
        lastRelayServerUrl = cleanRelay
        lastConnectToken = cleanToken
        lastConnectDeviceName = deviceName
        lastConnectMethod = DualConnectionMethod.INTERNET_RELAY

        _telemetry.update {
            it.copy(
                role = DualPhoneRole.PHONE1_GAMEPLAY,
                connectionMethod = DualConnectionMethod.INTERNET_RELAY,
                linkState = DualLinkState.CONNECTING,
                relayServerUrl = cleanRelay,
                peerIp = "Internet Relay",
                pairingToken = cleanToken,
                transportModeLabel = "Secure WebSocket Relay",
                signalQualityLabel = "Connecting Relay…",
                lastError = null
            )
        }
        openRelaySocket(
            relayUrl = cleanRelay,
            roomToken = cleanToken,
            isStudioHost = false,
            localDeviceName = deviceName
        )
    }

    private fun openRelaySocket(
        relayUrl: String,
        roomToken: String,
        isStudioHost: Boolean,
        localDeviceName: String
    ) {
        closeRelaySocket()
        val roleParam = if (isStudioHost) "studio" else "gameplay"
        val endpoint = DualPhoneProtocol.buildRelayRoomEndpoint(relayUrl, roomToken, roleParam)
        val req = Request.Builder().url(endpoint).build()
        var lastStatMs = System.currentTimeMillis()
        var framesInSec = 0
        var bytesInSec = 0L

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeRelaySocket = webSocket
                if (isStudioHost) {
                    _telemetry.update {
                        it.copy(
                            linkState = if (it.linkState == DualLinkState.CONNECTED) DualLinkState.CONNECTED else DualLinkState.HOSTING,
                            transportModeLabel = if (activeControlSocket != null) "Direct LAN + Relay" else "Secure WebSocket Relay",
                            signalQualityLabel = if (it.linkState == DualLinkState.CONNECTED) it.signalQualityLabel else "Relay Ready — Waiting for Phone 1",
                            lastError = null
                        )
                    }
                } else {
                    webSocket.send(DualPhoneProtocol.encodeAuth(roomToken, localDeviceName))
                    startRelayFrameSenderLoop(webSocket)
                    _telemetry.update {
                        it.copy(
                            linkState = DualLinkState.CONNECTING,
                            transportModeLabel = "Secure WebSocket Relay",
                            signalQualityLabel = "Authenticating via Relay…"
                        )
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (obj.optString("type")) {
                    DualPhoneProtocol.MSG_AUTH -> {
                        if (isStudioHost) {
                            val providedToken = obj.optString("token", "")
                            val valid = MessageDigest.isEqual(
                                providedToken.trim().uppercase().toByteArray(Charsets.UTF_8),
                                roomToken.trim().uppercase().toByteArray(Charsets.UTF_8)
                            )
                            if (!valid) {
                                webSocket.send(DualPhoneProtocol.encodeAuthFail("Invalid pairing token."))
                                return
                            }
                            val peerName = obj.optString("deviceName", "Phone 1 Gameplay")
                            webSocket.send(DualPhoneProtocol.encodeAuthOk(localDeviceName, 0))
                            _telemetry.update {
                                it.copy(
                                    linkState = DualLinkState.CONNECTED,
                                    connectedDeviceName = peerName,
                                    peerIp = "Internet Relay",
                                    transportModeLabel = "Secure WebSocket Relay",
                                    signalQualityLabel = "Relay Connected",
                                    lastError = null
                                )
                            }
                            startRelayPingLoop(webSocket)
                        }
                    }
                    DualPhoneProtocol.MSG_AUTH_OK -> {
                        if (!isStudioHost) {
                            val studioName = obj.optString("deviceName", "Zro Studio (Phone 2)")
                            _telemetry.update {
                                it.copy(
                                    linkState = DualLinkState.CONNECTED,
                                    connectedDeviceName = studioName,
                                    transportModeLabel = "Secure WebSocket Relay",
                                    signalQualityLabel = "Relay Connected",
                                    lastError = null
                                )
                            }
                            startRelayPingLoop(webSocket)
                        }
                    }
                    DualPhoneProtocol.MSG_AUTH_FAIL -> {
                        val reason = obj.optString("reason", "Relay authentication failed.")
                        intentionalStop.set(true)
                        _telemetry.update {
                            it.copy(
                                linkState = DualLinkState.ERROR,
                                signalQualityLabel = "Auth Failed",
                                lastError = reason
                            )
                        }
                        closeRelaySocket()
                    }
                    DualPhoneProtocol.MSG_PING -> {
                        webSocket.send(DualPhoneProtocol.encodePong(obj.optLong("ts", System.currentTimeMillis())))
                    }
                    DualPhoneProtocol.MSG_PONG -> {
                        val sentTs = obj.optLong("ts", 0L)
                        if (sentTs > 0L) {
                            val rtt = (System.currentTimeMillis() - sentTs).toInt().coerceAtLeast(1)
                            val q = when {
                                rtt <= 45 -> "Relay Excellent (${rtt}ms)"
                                rtt <= 110 -> "Relay Good (${rtt}ms)"
                                rtt <= 220 -> "Relay Fair (${rtt}ms)"
                                else -> "Relay High Latency (${rtt}ms)"
                            }
                            _telemetry.update { it.copy(latencyMs = rtt, signalQualityLabel = q) }
                        }
                    }
                    DualPhoneProtocol.MSG_TELEMETRY -> {
                        _telemetry.update {
                            it.copy(
                                mirroredResolution = obj.optString("res", it.mirroredResolution),
                                mirroredFps = obj.optInt("fps", it.mirroredFps),
                                uploadKbps = obj.optInt("kbps", it.uploadKbps),
                                packetLossPercent = obj.optDouble("loss", it.packetLossPercent.toDouble()).toFloat()
                            )
                        }
                    }
                    DualPhoneProtocol.MSG_COMMAND -> {
                        _incomingCommands.tryEmit(DualPhoneProtocol.decodeCommand(obj))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isStudioHost) return
                val arr = bytes.toByteArray()
                if (arr.isEmpty() || arr.size > MAX_MIRROR_FRAME_BYTES) return
                val bmp = runCatching { BitmapFactory.decodeByteArray(arr, 0, arr.size) }.getOrNull() ?: return
                _mirroredFrame.value = bmp
                framesInSec++
                bytesInSec += arr.size
                val now = System.currentTimeMillis()
                if (now - lastStatMs >= 1000L) {
                    val kbps = ((bytesInSec * 8L) / 1000L).toInt()
                    _telemetry.update {
                        it.copy(
                            mirroredFps = framesInSec,
                            mirroredResolution = "${bmp.width}x${bmp.height}",
                            uploadKbps = kbps,
                            lastFrameEpochMs = now
                        )
                    }
                    framesInSec = 0
                    bytesInSec = 0L
                    lastStatMs = now
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (activeRelaySocket == webSocket) activeRelaySocket = null
                relayFrameSenderJob?.cancel()
                if (!intentionalStop.get() && activeControlSocket == null) {
                    _mirroredFrame.value = null
                    _telemetry.update {
                        it.copy(
                            linkState = if (it.autoReconnect) DualLinkState.RECONNECTING else DualLinkState.ERROR,
                            signalQualityLabel = "Relay Disconnected",
                            lastError = t.localizedMessage ?: "Unable to reach relay server ($relayUrl)."
                        )
                    }
                    if (_telemetry.value.autoReconnect) {
                        scheduleRelayReconnect(relayUrl, roomToken, isStudioHost, localDeviceName)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (activeRelaySocket == webSocket) activeRelaySocket = null
                relayFrameSenderJob?.cancel()
                if (!intentionalStop.get() && activeControlSocket == null) {
                    _mirroredFrame.value = null
                    if (_telemetry.value.autoReconnect) {
                        scheduleRelayReconnect(relayUrl, roomToken, isStudioHost, localDeviceName)
                    }
                }
            }
        }
        activeRelaySocket = relayHttpClient.newWebSocket(req, listener)
    }

    private fun startRelayPingLoop(ws: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && activeRelaySocket == ws && !intentionalStop.get()) {
                val ok = ws.send(DualPhoneProtocol.encodePing(System.currentTimeMillis()))
                if (!ok) break
                delay(2000L)
            }
        }
    }

    private fun startRelayFrameSenderLoop(ws: WebSocket) {
        relayFrameSenderJob?.cancel()
        relayFrameSenderJob = scope.launch {
            for (jpegBytes in latestMirrorFrameChannel) {
                if (intentionalStop.get() || activeRelaySocket != ws) break
                // Backpressure guard: drop stale frame if relay outbound buffer exceeds 512 KB to keep latency low!
                if (ws.queueSize() > 512 * 1024L) {
                    droppedMirrorFrames.incrementAndGet()
                    continue
                }
                val sent = runCatching { ws.send(jpegBytes.toByteString(0, jpegBytes.size)) }.getOrDefault(false)
                if (sent) {
                    framesCounter.incrementAndGet()
                    bytesCounter.addAndGet(jpegBytes.size.toLong())
                } else {
                    droppedMirrorFrames.incrementAndGet()
                }
            }
        }
    }

    private fun scheduleRelayReconnect(
        relayUrl: String,
        roomToken: String,
        isStudioHost: Boolean,
        localDeviceName: String
    ) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2500L + Random.nextLong(200L, 800L))
            if (!intentionalStop.get() && activeControlSocket == null) {
                openRelaySocket(relayUrl, roomToken, isStudioHost, localDeviceName)
            }
        }
    }

    private fun closeRelaySocket() {
        relayFrameSenderJob?.cancel(); relayFrameSenderJob = null
        runCatching { activeRelaySocket?.close(1000, "Disconnected") }
        activeRelaySocket = null
    }

    /**
     * Phone 1 (Gameplay Client): connects to Phone 2 Studio Host via QR payload, Manual IP, or Mobile Data Relay.
     */
    fun connectPhone1ToStudio(
        ip: String,
        controlPort: Int = DualPhoneProtocol.DEFAULT_CONTROL_PORT,
        videoPort: Int = DualPhoneProtocol.DEFAULT_VIDEO_PORT,
        token: String,
        deviceName: String = "Phone 1 Gameplay",
        method: DualConnectionMethod = _telemetry.value.connectionMethod,
        relayUrl: String = _telemetry.value.relayServerUrl
    ) {
        val cleanIp = ip.trim()
        val cleanToken = token.trim().uppercase()
        val cleanRelay = relayUrl.trim()

        if (method == DualConnectionMethod.INTERNET_RELAY || (cleanIp.isBlank() && DualPhoneProtocol.isValidRelayUrl(cleanRelay))) {
            connectPhone1ViaRelay(cleanRelay, cleanToken, deviceName)
            return
        }

        if (!DualPhoneProtocol.isValidIpv4OrHost(cleanIp)) {
            _telemetry.update {
                it.copy(
                    linkState = DualLinkState.ERROR,
                    lastError = "Enter a valid IPv4 address (e.g. 192.168.1.24) or switch to Mobile Data / Relay."
                )
            }
            return
        }
        if (cleanToken.length < 4) {
            _telemetry.update {
                it.copy(
                    linkState = DualLinkState.ERROR,
                    lastError = "Enter the 8-character pairing token shown on Phone 2."
                )
            }
            return
        }

        disconnect(clearRole = false)
        intentionalStop.set(false)
        lastConnectIp = cleanIp
        lastConnectControlPort = controlPort
        lastConnectVideoPort = videoPort
        lastConnectToken = cleanToken
        lastConnectDeviceName = deviceName
        lastConnectMethod = method
        lastRelayServerUrl = cleanRelay

        startPhone1ConnectionAttempt(attempt = 1)
    }

    private fun startPhone1ConnectionAttempt(attempt: Int) {
        controlLoopJob = scope.launch {
            _telemetry.update {
                it.copy(
                    role = DualPhoneRole.PHONE1_GAMEPLAY,
                    connectionMethod = lastConnectMethod,
                    linkState = if (attempt == 1) DualLinkState.CONNECTING else DualLinkState.RECONNECTING,
                    peerIp = lastConnectIp,
                    hostPort = lastConnectControlPort,
                    videoPort = lastConnectVideoPort,
                    pairingToken = lastConnectToken,
                    signalQualityLabel = if (attempt == 1) "Connecting…" else "Reconnecting (#$attempt)…",
                    lastError = null
                )
            }

            var socket: Socket? = null
            try {
                socket = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(lastConnectIp, lastConnectControlPort), 5000)
                    soTimeout = 6000
                }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                // Authenticate with session token
                writeLineSafe(writer, DualPhoneProtocol.encodeAuth(lastConnectToken, lastConnectDeviceName))
                val respLine = reader.readLine()
                    ?: throw IllegalStateException("Phone 2 closed the connection during handshake.")
                val respObj = JSONObject(respLine)
                if (respObj.optString("type") != DualPhoneProtocol.MSG_AUTH_OK) {
                    val reason = respObj.optString("reason", "Authentication rejected by Phone 2.")
                    intentionalStop.set(true) // Don't spam reconnect on wrong token
                    throw SecurityException(reason)
                }

                val studioName = respObj.optString("deviceName", "Zro Studio (Phone 2)")
                val negotiatedVideoPort = respObj.optInt("videoPort", lastConnectVideoPort)
                socket.soTimeout = 0
                activeControlSocket = socket
                controlWriter = writer

                // Connect authenticated video mirroring channel with automatic recovery
                connectPhone1VideoChannel(lastConnectIp, negotiatedVideoPort, lastConnectToken)

                _telemetry.update {
                    it.copy(
                        linkState = DualLinkState.CONNECTED,
                        connectedDeviceName = studioName,
                        videoPort = negotiatedVideoPort,
                        signalQualityLabel = "Connected to Studio",
                        lastError = null
                    )
                }

                // Listen for PING & Remote Control Commands from Phone 2
                while (isActive && !intentionalStop.get() && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    when (obj.optString("type")) {
                        DualPhoneProtocol.MSG_PING -> {
                            writeLineSafe(writer, DualPhoneProtocol.encodePong(obj.optLong("ts", System.currentTimeMillis())))
                        }
                        DualPhoneProtocol.MSG_PONG -> {
                            val sentTs = obj.optLong("ts", 0L)
                            if (sentTs > 0L) {
                                val rtt = (System.currentTimeMillis() - sentTs).toInt().coerceAtLeast(1)
                                _telemetry.update {
                                    it.copy(latencyMs = rtt, signalQualityLabel = "Connected (${rtt}ms)")
                                }
                            }
                        }
                        DualPhoneProtocol.MSG_COMMAND -> {
                            _incomingCommands.tryEmit(DualPhoneProtocol.decodeCommand(obj))
                        }
                    }
                }
            } catch (sec: SecurityException) {
                _telemetry.update {
                    it.copy(
                        linkState = DualLinkState.ERROR,
                        signalQualityLabel = "Auth Failed",
                        lastError = sec.message ?: "Invalid pairing token."
                    )
                }
            } catch (e: Exception) {
                if (!intentionalStop.get()) {
                    // Automatic Mobile-Data / Different-Wi-Fi fallback: if direct LAN timed out and a relay URL is configured, switch to Relay!
                    if (DualPhoneProtocol.isValidRelayUrl(lastRelayServerUrl)) {
                        Log.i(TAG, "Direct LAN unavailable (${e.message}); falling back to secure WebSocket relay")
                        openRelaySocket(
                            relayUrl = lastRelayServerUrl,
                            roomToken = lastConnectToken,
                            isStudioHost = false,
                            localDeviceName = lastConnectDeviceName
                        )
                        return@launch
                    }
                    Log.w(TAG, "Phone 1 connection interrupted: ${e.message}")
                    _telemetry.update {
                        it.copy(
                            linkState = if (it.autoReconnect && attempt < 20) DualLinkState.RECONNECTING else DualLinkState.ERROR,
                            signalQualityLabel = "Disconnected",
                            lastError = e.localizedMessage ?: "Connection to Phone 2 lost."
                        )
                    }
                }
            } finally {
                if (activeControlSocket == socket) {
                    activeControlSocket = null
                    controlWriter = null
                }
                runCatching { socket?.close() }
                closeVideoChannel()

                if (!intentionalStop.get() && activeRelaySocket == null && _telemetry.value.autoReconnect && attempt < 20) {
                    scheduleAutoReconnect(attempt + 1)
                }
            }
        }
    }

    private fun connectPhone1VideoChannel(ip: String, videoPort: Int, token: String) {
        videoLoopJob?.cancel()
        videoSenderJob?.cancel()
        videoLoopJob = scope.launch {
            var retryDelayMs = 1000L
            while (isActive && !intentionalStop.get() && activeControlSocket?.isClosed == false) {
                var vs: Socket? = null
                try {
                    vs = Socket().apply {
                        tcpNoDelay = true
                        sendBufferSize = 128 * 1024
                        connect(InetSocketAddress(ip, videoPort), 4000)
                    }
                    activeVideoSocket = vs
                    val out = DataOutputStream(BufferedOutputStream(vs.getOutputStream(), 131072))
                    // Send pairing token header before streaming frames
                    out.writeUTF(token)
                    out.flush()
                    videoDataOut = out
                    retryDelayMs = 1000L

                    for (jpegBytes in latestMirrorFrameChannel) {
                        if (intentionalStop.get() || vs.isClosed || activeControlSocket?.isClosed == true) break
                        try {
                            synchronized(out) {
                                out.writeInt(jpegBytes.size)
                                out.write(jpegBytes)
                                out.flush()
                            }
                            framesCounter.incrementAndGet()
                            bytesCounter.addAndGet(jpegBytes.size.toLong())
                        } catch (_: Exception) {
                            droppedMirrorFrames.incrementAndGet()
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Video mirror socket warning: ${e.message}")
                } finally {
                    videoDataOut = null
                    if (activeVideoSocket == vs) activeVideoSocket = null
                    runCatching { vs?.close() }
                }
                if (!intentionalStop.get() && activeControlSocket?.isClosed == false) {
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2L).coerceAtMost(5000L)
                }
            }
        }
    }

    private fun scheduleAutoReconnect(nextAttempt: Int) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val baseDelay = (1000L shl (nextAttempt - 1).coerceAtMost(3)).coerceAtMost(8000L)
            val jitter = Random.nextLong(150L, 650L)
            delay(baseDelay + jitter)
            if (!intentionalStop.get() && _telemetry.value.role == DualPhoneRole.PHONE1_GAMEPLAY) {
                startPhone1ConnectionAttempt(nextAttempt)
            }
        }
    }

    /**
     * Pushes a compressed JPEG gameplay frame from Phone 1 to Phone 2 over a single-slot
     * CONFLATED latest-frame channel (up to 30 FPS) so stale frames are dropped immediately
     * instead of queueing up glass-to-glass latency.
     */
    fun pushMirroredFrameJpeg(jpegBytes: ByteArray, width: Int, height: Int, minIntervalMs: Long = 33L) {
        if (_telemetry.value.linkState != DualLinkState.CONNECTED) return
        if (videoDataOut == null && activeRelaySocket == null) return
        val now = System.currentTimeMillis()
        if (now - lastFrameSentMs.get() < minIntervalMs) return
        lastFrameSentMs.set(now)
        if (jpegBytes.isEmpty() || jpegBytes.size > MAX_MIRROR_FRAME_BYTES) return
        latestMirrorFrameChannel.trySend(jpegBytes)
    }

    fun sendRemoteCommand(cmd: DualRemoteCommand): Boolean {
        val encoded = DualPhoneProtocol.encodeCommand(cmd)
        val writer = controlWriter
        if (writer != null) {
            scope.launch { writeLineSafe(writer, encoded) }
            return true
        }
        val relay = activeRelaySocket ?: return false
        return runCatching { relay.send(encoded) }.getOrDefault(false)
    }

    fun sendTelemetrySnapshot(resolution: String, fps: Int, uploadKbps: Int, packetLossPercent: Float) {
        val encoded = DualPhoneProtocol.encodeTelemetry(resolution, fps, uploadKbps, packetLossPercent)
        val writer = controlWriter
        if (writer != null) {
            scope.launch { writeLineSafe(writer, encoded) }
            return
        }
        activeRelaySocket?.let { ws -> runCatching { ws.send(encoded) } }
    }

    fun reconnect() {
        val t = _telemetry.value
        when (t.role) {
            DualPhoneRole.PHONE2_STUDIO -> {
                if (t.connectionMethod == DualConnectionMethod.INTERNET_RELAY && DualPhoneProtocol.isValidRelayUrl(t.relayServerUrl)) {
                    startPhone2RelayHost(t.relayServerUrl, presetToken = t.pairingToken.takeIf { it.length >= 4 })
                } else {
                    startPhone2Host(
                        controlPort = t.hostPort,
                        videoPort = t.videoPort,
                        method = t.connectionMethod
                    )
                }
            }
            DualPhoneRole.PHONE1_GAMEPLAY -> {
                if (lastConnectMethod == DualConnectionMethod.INTERNET_RELAY && DualPhoneProtocol.isValidRelayUrl(lastRelayServerUrl) && lastConnectToken.isNotBlank()) {
                    connectPhone1ViaRelay(lastRelayServerUrl, lastConnectToken, lastConnectDeviceName)
                } else if (lastConnectIp.isNotBlank() && lastConnectToken.isNotBlank()) {
                    connectPhone1ToStudio(
                        ip = lastConnectIp,
                        controlPort = lastConnectControlPort,
                        videoPort = lastConnectVideoPort,
                        token = lastConnectToken,
                        deviceName = lastConnectDeviceName,
                        method = lastConnectMethod,
                        relayUrl = lastRelayServerUrl
                    )
                }
            }
            DualPhoneRole.SOLO_DEVICE -> Unit
        }
    }

    fun disconnect(clearRole: Boolean = false) {
        intentionalStop.set(true)
        reconnectJob?.cancel(); reconnectJob = null
        pingJob?.cancel(); pingJob = null
        controlLoopJob?.cancel(); controlLoopJob = null
        videoLoopJob?.cancel(); videoLoopJob = null
        closeRelaySocket()

        runCatching { activeControlSocket?.close() }; activeControlSocket = null
        controlWriter = null
        closeVideoChannel()
        runCatching { controlServerSocket?.close() }; controlServerSocket = null
        runCatching { videoServerSocket?.close() }; videoServerSocket = null

        _mirroredFrame.value = null
        _telemetry.update { prev ->
            prev.copy(
                role = if (clearRole) DualPhoneRole.SOLO_DEVICE else prev.role,
                linkState = DualLinkState.DISCONNECTED,
                connectedDeviceName = "",
                signalQualityLabel = "Offline",
                latencyMs = 0,
                mirroredFps = 0,
                uploadKbps = 0,
                lastError = null
            )
        }
    }

    private fun closeVideoChannel() {
        videoSenderJob?.cancel(); videoSenderJob = null
        runCatching { videoDataOut?.close() }; videoDataOut = null
        runCatching { activeVideoSocket?.close() }; activeVideoSocket = null
    }

    private fun writeLineSafe(writer: BufferedWriter, line: String): Boolean {
        return runCatching {
            synchronized(writer) {
                writer.write(line)
                writer.newLine()
                writer.flush()
            }
            true
        }.getOrDefault(false)
    }
}
