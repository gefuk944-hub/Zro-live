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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

    private var lastConnectIp: String = ""
    private var lastConnectControlPort: Int = DualPhoneProtocol.DEFAULT_CONTROL_PORT
    private var lastConnectVideoPort: Int = DualPhoneProtocol.DEFAULT_VIDEO_PORT
    private var lastConnectToken: String = ""
    private var lastConnectDeviceName: String = "Phone 1 Gameplay"
    private var lastConnectMethod: DualConnectionMethod = DualConnectionMethod.LOCAL_WIFI

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
                hostName = hostName
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
        presetToken: String? = null
    ) {
        disconnect(clearRole = false)
        intentionalStop.set(false)

        val token = presetToken?.takeIf { it.length >= 4 } ?: DualPhoneProtocol.generateSecureToken()
        val ip = discoverLocalIpv4Address()

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
                        signalQualityLabel = "Waiting for Phone 1",
                        latencyMs = 0,
                        lastError = null
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
            if (providedToken != expectedToken) {
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
                _telemetry.update {
                    it.copy(
                        linkState = DualLinkState.HOSTING,
                        connectedDeviceName = "",
                        signalQualityLabel = "Waiting for Phone 1",
                        mirroredFps = 0
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
                activeVideoSocket = client
                val dataIn = DataInputStream(BufferedInputStream(client.getInputStream(), 65536))
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
                if (activeVideoSocket == client) activeVideoSocket = null
                runCatching { client.close() }
            }
        }
    }

    /**
     * Phone 1 (Gameplay Client): connects to Phone 2 Studio Host via QR payload or Manual IP + Token.
     */
    fun connectPhone1ToStudio(
        ip: String,
        controlPort: Int = DualPhoneProtocol.DEFAULT_CONTROL_PORT,
        videoPort: Int = DualPhoneProtocol.DEFAULT_VIDEO_PORT,
        token: String,
        deviceName: String = "Phone 1 Gameplay",
        method: DualConnectionMethod = _telemetry.value.connectionMethod
    ) {
        val cleanIp = ip.trim()
        val cleanToken = token.trim().uppercase()
        if (!DualPhoneProtocol.isValidIpv4OrHost(cleanIp)) {
            _telemetry.update {
                it.copy(
                    linkState = DualLinkState.ERROR,
                    lastError = "Enter a valid IPv4 address (e.g. 192.168.1.24)."
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

                // Connect auxiliary video mirroring channel (best-effort; control still works if blocked)
                connectPhone1VideoChannel(lastConnectIp, negotiatedVideoPort)

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
                    Log.w(TAG, "Phone 1 connection interrupted: ${e.message}")
                    _telemetry.update {
                        it.copy(
                            linkState = if (it.autoReconnect && attempt < 5) DualLinkState.RECONNECTING else DualLinkState.ERROR,
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

                if (!intentionalStop.get() && _telemetry.value.autoReconnect && attempt < 5) {
                    scheduleAutoReconnect(attempt + 1)
                }
            }
        }
    }

    private fun connectPhone1VideoChannel(ip: String, videoPort: Int) {
        videoLoopJob?.cancel()
        videoLoopJob = scope.launch {
            try {
                val vs = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(ip, videoPort), 4000)
                }
                activeVideoSocket = vs
                videoDataOut = DataOutputStream(BufferedOutputStream(vs.getOutputStream(), 65536))
            } catch (e: Exception) {
                Log.w(TAG, "Optional video mirror socket unavailable: ${e.message}")
            }
        }
    }

    private fun scheduleAutoReconnect(nextAttempt: Int) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(nextAttempt * 1500L)
            if (!intentionalStop.get() && _telemetry.value.role == DualPhoneRole.PHONE1_GAMEPLAY) {
                startPhone1ConnectionAttempt(nextAttempt)
            }
        }
    }

    /**
     * Pushes a compressed JPEG gameplay frame from Phone 1 to Phone 2 over the Video Mirror socket.
     * Throttled to ~15 FPS so 4GB RAM devices stay cool and responsive.
     */
    fun pushMirroredFrameJpeg(jpegBytes: ByteArray, width: Int, height: Int, minIntervalMs: Long = 66L) {
        if (_telemetry.value.linkState != DualLinkState.CONNECTED) return
        val out = videoDataOut ?: return
        val now = System.currentTimeMillis()
        if (now - lastFrameSentMs.get() < minIntervalMs) return
        lastFrameSentMs.set(now)
        if (jpegBytes.isEmpty() || jpegBytes.size > MAX_MIRROR_FRAME_BYTES) return

        scope.launch {
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
            }
        }
    }

    fun sendRemoteCommand(cmd: DualRemoteCommand): Boolean {
        val writer = controlWriter ?: return false
        scope.launch {
            writeLineSafe(writer, DualPhoneProtocol.encodeCommand(cmd))
        }
        return true
    }

    fun sendTelemetrySnapshot(resolution: String, fps: Int, uploadKbps: Int, packetLossPercent: Float) {
        val writer = controlWriter ?: return
        scope.launch {
            writeLineSafe(writer, DualPhoneProtocol.encodeTelemetry(resolution, fps, uploadKbps, packetLossPercent))
        }
    }

    fun reconnect() {
        val t = _telemetry.value
        when (t.role) {
            DualPhoneRole.PHONE2_STUDIO -> startPhone2Host(
                controlPort = t.hostPort,
                videoPort = t.videoPort,
                method = t.connectionMethod
            )
            DualPhoneRole.PHONE1_GAMEPLAY -> {
                if (lastConnectIp.isNotBlank() && lastConnectToken.isNotBlank()) {
                    connectPhone1ToStudio(
                        ip = lastConnectIp,
                        controlPort = lastConnectControlPort,
                        videoPort = lastConnectVideoPort,
                        token = lastConnectToken,
                        deviceName = lastConnectDeviceName,
                        method = lastConnectMethod
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
