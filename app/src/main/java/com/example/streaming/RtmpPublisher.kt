package com.example.streaming

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Pure-Kotlin RTMP/RTMPS 1.0 publisher built for reliable YouTube Live & Custom RTMP ingest:
 * - Strict Server URL vs Stream Key validation and stream-key masking (never logged in plaintext)
 * - Real RTMP handshake + TLS SNI verification for rtmps://
 * - Real AMF0 chunk reader handling server SetChunkSize, Ping->Pong, createStream ID, _error & onStatus
 * - Sends @setDataFrame / onMetaData for instant YouTube Studio ingestion lock
 * - Multi-NAL Annex-B splitter -> proper AVCC 4-byte length-prefixed NALUs + inline SPS/PPS extraction
 * - ADTS header stripping & ISO 14496-3 AAC AudioSpecificConfig generator
 * - Primary -> Backup RTMP server failover + non-broadcasting Test Connection diagnostics
 */
class RtmpPublisher {

    companion object {
        private const val TAG = "RtmpPublisher"
        private const val CLIENT_CHUNK_SIZE = 4096
        private const val DEFAULT_SERVER_CHUNK_SIZE = 128
        private const val HANDSHAKE_SIZE = 1536
        private const val CONGESTION_WRITE_MS = 220L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 8_000
        private const val HANDSHAKE_READ_TIMEOUT_MS = 6_000

        const val YOUTUBE_PRIMARY_RTMP = "rtmp://a.rtmp.youtube.com/live2"
        const val YOUTUBE_BACKUP_RTMP = "rtmp://b.rtmp.youtube.com/live2"
        const val YOUTUBE_PRIMARY_RTMPS = "rtmps://a.rtmps.youtube.com/live2"
        const val YOUTUBE_BACKUP_RTMPS = "rtmps://b.rtmps.youtube.com/live2"

        /**
         * Normalizes known YouTube RTMPS ingest hostnames (YouTube's TLS cert covers *.rtmps.youtube.com,
         * NOT a.rtmp.youtube.com). Never disables certificate or hostname validation.
         */
        fun normalizeEndpointUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""
            val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
            val scheme = parsed.scheme?.lowercase() ?: return trimmed
            val host = parsed.host?.lowercase() ?: return trimmed
            if (scheme == "rtmps" && (host == "a.rtmp.youtube.com" || host == "b.rtmp.youtube.com")) {
                val fixedHost = if (host.startsWith("b.")) "b.rtmps.youtube.com" else "a.rtmps.youtube.com"
                val portPart = if (parsed.port > 0) ":${parsed.port}" else ""
                val pathPart = parsed.rawPath.orEmpty().ifBlank { "/live2" }
                return "rtmps://$fixedHost$portPart$pathPart"
            }
            return trimmed
        }

        internal fun isDnsHostname(host: String): Boolean {
            if (host.isBlank() || host.contains(":")) return false // IPv6 literal
            return !host.all { it.isDigit() || it == '.' }
        }

        data class ConfigValidationResult(
            val valid: Boolean,
            val errorMessage: String? = null
        )

        data class ConnectSuccess(
            val serverUrl: String,
            val usedBackup: Boolean,
            val streamId: Int,
            val latencyMs: Long
        )

        data class ConnectionTestOutcome(
            val success: Boolean,
            val serverUrl: String,
            val latencyMs: Long,
            val detail: String
        )

        data class NalSlice(
            val offset: Int,
            val length: Int,
            val nalType: Int
        )

        fun maskStreamKey(key: String): String {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) return "(not set)"
            if (trimmed.length <= 4) return "••••"
            return "${"•".repeat((trimmed.length - 4).coerceAtMost(12))}${trimmed.takeLast(4)}"
        }

        fun looksLikeUrl(value: String): Boolean {
            val lower = value.trim().lowercase()
            return lower.startsWith("rtmp://") ||
                lower.startsWith("rtmps://") ||
                lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.contains("rtmp.youtube.com") ||
                lower.contains("/live2")
        }

        fun validateConfig(
            primaryUrl: String,
            backupUrl: String = "",
            streamKey: String
        ): ConfigValidationResult {
            val cleanPrimary = primaryUrl.trim()
            val cleanBackup = backupUrl.trim()
            val cleanKey = streamKey.trim()

            if (cleanPrimary.isBlank()) {
                return ConfigValidationResult(false, "Stream RTMP URL is required (e.g. rtmp:// or rtmps:// ingest server).")
            }
            val primaryUriError = validateServerUrl(cleanPrimary, "Primary Server URL")
            if (primaryUriError != null) {
                return ConfigValidationResult(false, primaryUriError)
            }
            if (cleanBackup.isNotBlank()) {
                val backupUriError = validateServerUrl(cleanBackup, "Backup Server URL")
                if (backupUriError != null) {
                    return ConfigValidationResult(false, backupUriError)
                }
            }
            if (cleanKey.isBlank()) {
                return ConfigValidationResult(false, "Stream Key is required. Copy your Stream Key from YouTube Studio.")
            }
            if (looksLikeUrl(cleanKey)) {
                return ConfigValidationResult(
                    false,
                    "RTMP server URL detected in the Stream Key field. Put the server URL in Primary Server URL and paste only your Stream Key here."
                )
            }
            if (cleanKey.any { it.isWhitespace() }) {
                return ConfigValidationResult(false, "Stream Key cannot contain spaces or line breaks.")
            }
            if (cleanKey.length < 4) {
                return ConfigValidationResult(false, "Stream Key looks too short. Copy the full Stream Key from YouTube Studio.")
            }
            return ConfigValidationResult(true)
        }

        private val MISSPELLED_YOUTUBE_DOMAINS = listOf(
            "youtude.com",
            "youtupe.com",
            "youtub.com",
            "yotube.com",
            "yuotube.com",
            "youutube.com",
            "yutube.com"
        )

        /**
         * Detects common misspelled YouTube RTMP ingest hostnames (e.g. a.rtmp.youtude.com -> a.rtmp.youtube.com)
         * so typos are caught instantly before attempting a slow TCP timeout to a parked/wrong IP.
         */
        fun suggestCorrectedRtmpUrl(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isBlank()) return null
            val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val scheme = parsed.scheme?.lowercase() ?: return null
            if (scheme != "rtmp" && scheme != "rtmps") return null
            val host = parsed.host?.lowercase() ?: return null
            val matchedTypo = MISSPELLED_YOUTUBE_DOMAINS.firstOrNull { typo ->
                host == typo || host.endsWith(".$typo")
            } ?: return null
            val fixedHost = host.substring(0, host.length - matchedTypo.length) + "youtube.com"
            val normalizedHost = if (scheme == "rtmps" && (fixedHost == "a.rtmp.youtube.com" || fixedHost == "b.rtmp.youtube.com")) {
                if (fixedHost.startsWith("b.")) "b.rtmps.youtube.com" else "a.rtmps.youtube.com"
            } else {
                fixedHost
            }
            val portPart = if (parsed.port > 0) ":${parsed.port}" else ""
            val pathPart = parsed.rawPath.orEmpty().ifBlank { "/live2" }
            return "$scheme://$normalizedHost$portPart$pathPart"
        }

        fun validateServerUrl(url: String, label: String = "Server URL"): String? {
            val trimmed = url.trim()
            val lower = trimmed.lowercase()
            if (!lower.startsWith("rtmp://") && !lower.startsWith("rtmps://")) {
                return "$label must start with rtmp:// or rtmps://"
            }
            val parsed = runCatching { URI(trimmed) }.getOrNull()
                ?: return "$label is not a valid RTMP URI."
            if (parsed.host.isNullOrBlank()) {
                return "$label is missing a valid server hostname."
            }
            val suggestion = suggestCorrectedRtmpUrl(trimmed)
            if (suggestion != null) {
                return "Typo in $label ('${parsed.host}' is misspelled). Use $suggestion"
            }
            return null
        }

        /**
         * Splits an Annex-B byte buffer into all individual NAL units (handling both 3-byte 00 00 01
         * and 4-byte 00 00 00 01 start codes). If no start code is found, treats the whole buffer as one NALU.
         */
        fun splitAnnexBNalus(data: ByteArray, length: Int): List<NalSlice> {
            val limit = minOf(length, data.size)
            if (limit <= 0) return emptyList()

            val starts = ArrayList<Pair<Int, Int>>(4) // (startCodePos, startCodeLen)
            var i = 0
            while (i + 2 < limit) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    if (i + 3 < limit && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                        starts.add(i to 4)
                        i += 4
                        continue
                    } else if (data[i + 2] == 1.toByte()) {
                        starts.add(i to 3)
                        i += 3
                        continue
                    }
                }
                i++
            }

            if (starts.isEmpty()) {
                val nalType = data[0].toInt() and 0x1F
                return listOf(NalSlice(0, limit, nalType))
            }

            val slices = ArrayList<NalSlice>(starts.size)
            for (idx in starts.indices) {
                val nalStart = starts[idx].first + starts[idx].second
                val nalEnd = if (idx + 1 < starts.size) starts[idx + 1].first else limit
                if (nalEnd > nalStart) {
                    val nalType = data[nalStart].toInt() and 0x1F
                    slices.add(NalSlice(nalStart, nalEnd - nalStart, nalType))
                }
            }
            return slices
        }

        /**
         * Extracts SPS (NAL type 7) and PPS (NAL type 8) from a combined csd-0 or BUFFER_FLAG_CODEC_CONFIG payload.
         */
        fun extractSpsPpsFromBuffer(data: ByteArray, length: Int = data.size): Pair<ByteArray?, ByteArray?> {
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            for (nal in splitAnnexBNalus(data, length)) {
                if (nal.nalType == 7 && sps == null) {
                    sps = data.copyOfRange(nal.offset, nal.offset + nal.length)
                } else if (nal.nalType == 8 && pps == null) {
                    pps = data.copyOfRange(nal.offset, nal.offset + nal.length)
                }
            }
            return sps to pps
        }

        /**
         * Generates a 2-byte ISO 14496-3 AudioSpecificConfig for AAC-LC (objectType = 2).
         */
        fun buildAacAudioSpecificConfig(sampleRate: Int, stereo: Boolean): ByteArray {
            val freqIndex = when (sampleRate) {
                96000 -> 0
                88200 -> 1
                64000 -> 2
                48000 -> 3
                44100 -> 4
                32000 -> 5
                24000 -> 6
                22050 -> 7
                16000 -> 8
                12000 -> 9
                11025 -> 10
                8000 -> 11
                else -> 4 // 44.1 kHz
            }
            val channelConfig = if (stereo) 2 else 1
            val objectType = 2 // AAC-LC
            val b0 = ((objectType shl 3) or (freqIndex ushr 1)) and 0xFF
            val b1 = (((freqIndex and 0x01) shl 7) or (channelConfig shl 3)) and 0xFF
            return byteArrayOf(b0.toByte(), b1.toByte())
        }
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private val connected = AtomicBoolean(false)
    private val bytesSentWindow = AtomicLong(0L)
    private val totalBytesSentCounter = AtomicLong(0L)
    private val videoFramesConfirmedCounter = AtomicLong(0L)
    private val audioFramesConfirmedCounter = AtomicLong(0L)
    private val sendLock = Any()

    private var serverChunkSize = DEFAULT_SERVER_CHUNK_SIZE
    private var publishedStreamId = 1
    private var avcHeaderSent = false
    private var aacHeaderSent = false
    private var firstKeyframeSent = false
    private var lastVideoTsMs = 0
    private var lastAudioTsMs = 0
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    private var activeServerUrl: String = ""

    // Chunk reassembly state for server->client RTMP messages
    private data class ChunkHeaderState(
        var timestamp: Int = 0,
        var timestampDelta: Int = 0,
        var messageLength: Int = 0,
        var messageTypeId: Int = 0,
        var messageStreamId: Int = 0
    )

    private data class RtmpIncomingMessage(
        val typeId: Int,
        val streamId: Int,
        val payload: ByteArray
    )

    private val incomingHeaders = HashMap<Int, ChunkHeaderState>()
    private val incomingPayloads = HashMap<Int, ByteArrayOutputStream>()

    // Reusable scratch buffers guarded by sendLock
    private var videoScratch = ByteArray(192 * 1024)
    private var audioScratch = ByteArray(16 * 1024)
    private var congestedUntilNextKey = false

    val isConnected: Boolean
        get() = connected.get()

    val totalBytesSent: Long
        get() = totalBytesSentCounter.get()

    val videoFramesConfirmed: Long
        get() = videoFramesConfirmedCounter.get()

    val audioFramesConfirmed: Long
        get() = audioFramesConfirmedCounter.get()

    val connectedServerUrl: String
        get() = activeServerUrl

    fun consumeBytesSentSinceLast(): Long = bytesSentWindow.getAndSet(0L)

    /**
     * Tests real TCP/TLS + RTMP C0/C1/S0/S1/S2 handshake + AMF0 connect command WITHOUT calling publish.
     * Safe to invoke anytime without starting a live broadcast on YouTube.
     */
    fun testConnection(serverUrl: String, streamKey: String): ConnectionTestOutcome {
        val cleanUrl = normalizeEndpointUrl(serverUrl)
        val validation = validateConfig(primaryUrl = cleanUrl, streamKey = streamKey)
        if (!validation.valid) {
            return ConnectionTestOutcome(
                success = false,
                serverUrl = cleanUrl,
                latencyMs = 0L,
                detail = validation.errorMessage ?: "Invalid RTMP configuration."
            )
        }

        val startMs = System.currentTimeMillis()
        var testSocket: Socket? = null
        return try {
            val parsed = parseEndpoint(cleanUrl)
            testSocket = openSocket(parsed)
            val dataIn = DataInputStream(BufferedInputStream(testSocket.getInputStream()))
            val dataOut = DataOutputStream(BufferedOutputStream(testSocket.getOutputStream(), 16 * 1024))

            serverChunkSize = DEFAULT_SERVER_CHUNK_SIZE
            incomingHeaders.clear()
            incomingPayloads.clear()

            performHandshake(dataIn, dataOut)
            sendSetChunkSize(dataOut, CLIENT_CHUNK_SIZE)
            sendConnectCommand(dataOut, parsed.appPath, cleanUrl)

            val connectResp = awaitCommandResponse(
                dataIn = dataIn,
                dataOut = dataOut,
                expectedTxId = 1.0,
                waitForPublishStatus = false,
                timeoutMs = 5_000L
            )
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            if (connectResp.isSuccess) {
                ConnectionTestOutcome(
                    success = true,
                    serverUrl = cleanUrl,
                    latencyMs = elapsed,
                    detail = "Connected to ${parsed.host}:${parsed.port} (${if (parsed.isSecure) "RTMPS/TLS" else "RTMP"}) in ${elapsed} ms. Ready to stream with key ${maskStreamKey(streamKey)}."
                )
            } else {
                ConnectionTestOutcome(
                    success = false,
                    serverUrl = cleanUrl,
                    latencyMs = elapsed,
                    detail = connectResp.exceptionOrNull()?.message ?: "Server rejected RTMP connect."
                )
            }
        } catch (e: Exception) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            ConnectionTestOutcome(
                success = false,
                serverUrl = cleanUrl,
                latencyMs = elapsed,
                detail = sanitizeErrorMessage(e, streamKey)
            )
        } finally {
            runCatching { testSocket?.close() }
        }
    }

    /**
     * Connects to primary RTMP/RTMPS server, with automatic failover to optional backup server.
     */
    fun connectWithFailover(
        primaryUrl: String,
        backupUrl: String,
        streamKey: String,
        preferBackup: Boolean = false
    ): Result<ConnectSuccess> {
        val cleanPrimary = primaryUrl.trim().trimEnd('/')
        val cleanBackup = backupUrl.trim().trimEnd('/')
        val validation = validateConfig(cleanPrimary, cleanBackup, streamKey)
        if (!validation.valid) {
            return Result.failure(IllegalArgumentException(validation.errorMessage ?: "Invalid stream configuration."))
        }

        val candidates = ArrayList<Pair<String, Boolean>>(2)
        if (preferBackup && cleanBackup.isNotBlank()) {
            candidates.add(cleanBackup to true)
            candidates.add(cleanPrimary to false)
        } else {
            candidates.add(cleanPrimary to false)
            if (cleanBackup.isNotBlank() && !cleanBackup.equals(cleanPrimary, ignoreCase = true)) {
                candidates.add(cleanBackup to true)
            }
        }

        var lastError: Throwable? = null
        for ((url, isBackup) in candidates) {
            val res = connectInternal(url, streamKey, isBackup)
            if (res.isSuccess) return res
            lastError = res.exceptionOrNull()
            Log.w(TAG, "RTMP connect attempt to ${if (isBackup) "backup" else "primary"} failed: ${lastError?.message}")
        }
        return Result.failure(
            lastError ?: IllegalStateException("Unable to connect to RTMP server. Verify your network and Stream Key.")
        )
    }

    fun connect(rtmpBaseUrl: String, streamKey: String): Result<Unit> {
        return connectInternal(rtmpBaseUrl.trim().trimEnd('/'), streamKey, usedBackup = false).map {}
    }

    private fun connectInternal(rtmpBaseUrl: String, streamKey: String, usedBackup: Boolean): Result<ConnectSuccess> {
        return try {
            close()
            val cleanBase = normalizeEndpointUrl(rtmpBaseUrl)
            val cleanKey = streamKey.trim()
            val validation = validateConfig(primaryUrl = cleanBase, streamKey = cleanKey)
            if (!validation.valid) {
                return Result.failure(IllegalArgumentException(validation.errorMessage ?: "Invalid RTMP config."))
            }

            val startMs = System.currentTimeMillis()
            val parsed = parseEndpoint(cleanBase)
            val rawSocket = openSocket(parsed)

            socket = rawSocket
            val dataIn = DataInputStream(BufferedInputStream(rawSocket.getInputStream()))
            val dataOut = DataOutputStream(BufferedOutputStream(rawSocket.getOutputStream(), 32 * 1024))
            input = dataIn
            output = dataOut

            serverChunkSize = DEFAULT_SERVER_CHUNK_SIZE
            publishedStreamId = 1
            avcHeaderSent = false
            aacHeaderSent = false
            firstKeyframeSent = false
            lastVideoTsMs = 0
            lastAudioTsMs = 0
            cachedSps = null
            cachedPps = null
            incomingHeaders.clear()
            incomingPayloads.clear()

            // 1. RTMP Handshake (C0/C1 -> S0/S1/S2 -> C2)
            performHandshake(dataIn, dataOut)

            // 2. Negotiate chunk size & window ack size
            sendSetChunkSize(dataOut, CLIENT_CHUNK_SIZE)
            sendWindowAckSize(dataOut, 2_500_000)

            // 3. RTMP connect command and wait for _result (txId = 1.0)
            sendConnectCommand(dataOut, parsed.appPath, cleanBase)
            val connectResult = awaitCommandResponse(
                dataIn = dataIn,
                dataOut = dataOut,
                expectedTxId = 1.0,
                waitForPublishStatus = false,
                timeoutMs = HANDSHAKE_READ_TIMEOUT_MS.toLong()
            )
            if (connectResult.isFailure) {
                throw connectResult.exceptionOrNull() ?: IllegalStateException("RTMP connect rejected by server.")
            }

            // 4. releaseStream + FCPublish + createStream (txId = 4.0)
            sendSimpleCommand(dataOut, "releaseStream", 2.0, cleanKey)
            sendSimpleCommand(dataOut, "FCPublish", 3.0, cleanKey)
            sendCreateStreamCommand(dataOut, 4.0)
            val createResult = awaitCommandResponse(
                dataIn = dataIn,
                dataOut = dataOut,
                expectedTxId = 4.0,
                waitForPublishStatus = false,
                timeoutMs = HANDSHAKE_READ_TIMEOUT_MS.toLong()
            )
            if (createResult.isFailure) {
                throw createResult.exceptionOrNull() ?: IllegalStateException("RTMP createStream rejected by server.")
            }
            publishedStreamId = createResult.getOrNull()?.streamId?.takeIf { it > 0 } ?: 1

            // 5. publish(streamKey, "live") on server-assigned streamId & confirm NetStream.Publish.Start
            sendPublishCommand(dataOut, cleanKey, publishedStreamId)
            val publishResult = awaitCommandResponse(
                dataIn = dataIn,
                dataOut = dataOut,
                expectedTxId = 5.0,
                waitForPublishStatus = true,
                timeoutMs = HANDSHAKE_READ_TIMEOUT_MS.toLong()
            )
            if (publishResult.isFailure) {
                throw publishResult.exceptionOrNull() ?: IllegalStateException("RTMP publish rejected. Verify your Stream Key.")
            }

            val latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            activeServerUrl = cleanBase
            congestedUntilNextKey = false
            totalBytesSentCounter.set(0L)
            bytesSentWindow.set(0L)
            videoFramesConfirmedCounter.set(0L)
            audioFramesConfirmedCounter.set(0L)
            connected.set(true)
            Result.success(
                ConnectSuccess(
                    serverUrl = cleanBase,
                    usedBackup = usedBackup,
                    streamId = publishedStreamId,
                    latencyMs = latencyMs
                )
            )
        } catch (e: Exception) {
            val safeMsg = sanitizeErrorMessage(e, streamKey)
            Log.e(TAG, "RTMP connect failed: $safeMsg")
            close()
            Result.failure(IllegalStateException(safeMsg))
        }
    }

    /**
     * Sends AMF0 @setDataFrame / onMetaData script tag so YouTube Studio locks resolution, FPS, and codecs immediately.
     */
    fun sendMetaData(
        width: Int,
        height: Int,
        fps: Int,
        videoBitrateKbps: Int,
        audioSampleRate: Int,
        audioBitrateKbps: Int,
        stereo: Boolean
    ): Boolean {
        if (!connected.get()) return false
        val b = ByteArrayOutputStream(256)
        writeAmfString(b, "@setDataFrame")
        writeAmfString(b, "onMetaData")
        // AMF0 ECMA Array (0x08) with 12 properties
        b.write(0x08)
        b.write(0x00); b.write(0x00); b.write(0x00); b.write(0x0C)
        writeAmfPropNumber(b, "width", width.toDouble())
        writeAmfPropNumber(b, "height", height.toDouble())
        writeAmfPropNumber(b, "framerate", fps.toDouble())
        writeAmfPropNumber(b, "videodatarate", videoBitrateKbps.toDouble())
        writeAmfPropNumber(b, "videocodecid", 7.0) // AVC / H.264
        writeAmfPropNumber(b, "audiodatarate", audioBitrateKbps.toDouble())
        writeAmfPropNumber(b, "audiosamplerate", audioSampleRate.toDouble())
        writeAmfPropNumber(b, "audiosamplesize", 16.0)
        writeAmfPropBoolean(b, "stereo", stereo)
        writeAmfPropNumber(b, "audiocodecid", 10.0) // AAC
        writeAmfPropString(b, "encoder", "ZroLive RTMP/1.0")
        writeAmfPropNumber(b, "filesize", 0.0)
        b.write(0x00); b.write(0x00); b.write(0x09)
        val payload = b.toByteArray()
        return writeRtmpMessage(
            csid = 5,
            timestampMs = 0,
            typeId = 0x12,
            streamId = publishedStreamId,
            payload = payload,
            payloadLength = payload.size
        )
    }

    fun sendAvcSequenceHeader(sps: ByteArray, pps: ByteArray): Boolean {
        if (!connected.get() || sps.isEmpty() || pps.isEmpty()) return false
        synchronized(sendLock) {
            cachedSps = sps.copyOf()
            cachedPps = pps.copyOf()
            val body = ByteArrayOutputStream(16 + sps.size + pps.size)
            body.write(0x17) // Keyframe + AVC
            body.write(0x00) // AVC sequence header
            body.write(0x00); body.write(0x00); body.write(0x00) // Composition time 0
            body.write(0x01) // configurationVersion
            body.write(if (sps.size > 1) sps[1].toInt() and 0xFF else 0x42)
            body.write(if (sps.size > 2) sps[2].toInt() and 0xFF else 0x00)
            body.write(if (sps.size > 3) sps[3].toInt() and 0xFF else 0x1F)
            body.write(0xFF) // 4-byte NAL length size
            body.write(0xE1) // 1 SPS
            body.write((sps.size shr 8) and 0xFF)
            body.write(sps.size and 0xFF)
            body.write(sps)
            body.write(0x01) // 1 PPS
            body.write((pps.size shr 8) and 0xFF)
            body.write(pps.size and 0xFF)
            body.write(pps)

            val bytes = body.toByteArray()
            val ok = writeRtmpMessageLocked(
                csid = 6,
                timestampMs = 0,
                typeId = 0x09,
                streamId = publishedStreamId,
                payload = bytes,
                payloadLength = bytes.size,
                outOverride = null
            )
            if (ok) avcHeaderSent = true
            return ok
        }
    }

    /**
     * Converts Annex-B H.264 output (single or multi-NAL!) to strict FLV AVCC format:
     * - Automatically extracts and transmits inline SPS/PPS if AVC sequence header has not been sent yet
     * - Prefixes EVERY video NAL unit with its own 4-byte big-endian length (required by YouTube Live)
     */
    fun sendVideoFrame(nalData: ByteArray, nalLength: Int, isKeyFrame: Boolean, timestampMs: Int): Boolean {
        if (!connected.get() || nalLength <= 0) return false
        synchronized(sendLock) {
            val nalus = splitAnnexBNalus(nalData, nalLength)
            if (nalus.isEmpty()) return false

            var inlineSps: ByteArray? = null
            var inlinePps: ByteArray? = null
            var hasIdr = false
            var avccPayloadBytes = 0
            val videoNalus = ArrayList<NalSlice>(nalus.size)

            for (nal in nalus) {
                when (nal.nalType) {
                    7 -> inlineSps = nalData.copyOfRange(nal.offset, nal.offset + nal.length)
                    8 -> inlinePps = nalData.copyOfRange(nal.offset, nal.offset + nal.length)
                    9 -> Unit // Access Unit Delimiter — skip
                    else -> {
                        if (nal.nalType == 5) hasIdr = true
                        videoNalus.add(nal)
                        avccPayloadBytes += 4 + nal.length
                    }
                }
            }

            if (inlineSps != null) cachedSps = inlineSps
            if (inlinePps != null) cachedPps = inlinePps
            if (!avcHeaderSent && cachedSps != null && cachedPps != null) {
                sendAvcSequenceHeader(cachedSps!!, cachedPps!!)
            }

            // If buffer contained only SPS/PPS codec config, nothing else to send as video frame
            if (videoNalus.isEmpty()) return avcHeaderSent

            // CRITICAL for YouTube Live: NEVER transmit video NALUs before AVCDecoderConfigurationRecord
            if (!avcHeaderSent) return false

            val effectiveKey = isKeyFrame || hasIdr
            // Wait for the very first IDR keyframe after AVC sequence header so YouTube locks video cleanly
            if (!firstKeyframeSent && !effectiveKey) {
                return false
            }
            if (effectiveKey) {
                congestedUntilNextKey = false
            } else if (congestedUntilNextKey) {
                return false
            }

            val totalLen = 5 + avccPayloadBytes
            if (videoScratch.size < totalLen) {
                videoScratch = ByteArray(maxOf(totalLen, videoScratch.size * 2))
            }

            videoScratch[0] = if (effectiveKey) 0x17.toByte() else 0x27.toByte()
            videoScratch[1] = 0x01.toByte() // AVC NALU
            videoScratch[2] = 0x00
            videoScratch[3] = 0x00
            videoScratch[4] = 0x00

            var cursor = 5
            for (nal in videoNalus) {
                val len = nal.length
                videoScratch[cursor] = ((len shr 24) and 0xFF).toByte()
                videoScratch[cursor + 1] = ((len shr 16) and 0xFF).toByte()
                videoScratch[cursor + 2] = ((len shr 8) and 0xFF).toByte()
                videoScratch[cursor + 3] = (len and 0xFF).toByte()
                System.arraycopy(nalData, nal.offset, videoScratch, cursor + 4, len)
                cursor += 4 + len
            }

            val safeTsMs = maxOf(lastVideoTsMs, timestampMs.coerceAtLeast(0))
            lastVideoTsMs = safeTsMs

            val startNs = System.nanoTime()
            val ok = writeRtmpMessageLocked(
                csid = 6,
                timestampMs = safeTsMs,
                typeId = 0x09,
                streamId = publishedStreamId,
                payload = videoScratch,
                payloadLength = totalLen,
                outOverride = null
            )
            val writeMs = (System.nanoTime() - startNs) / 1_000_000L
            if (ok) {
                if (effectiveKey) firstKeyframeSent = true
                videoFramesConfirmedCounter.incrementAndGet()
                if (writeMs > CONGESTION_WRITE_MS) {
                    congestedUntilNextKey = true
                }
            }
            return ok
        }
    }

    fun sendAacSequenceHeader(asc: ByteArray): Boolean {
        if (!connected.get() || asc.isEmpty()) return false
        synchronized(sendLock) {
            val totalLen = 2 + asc.size
            if (audioScratch.size < totalLen) audioScratch = ByteArray(totalLen)
            audioScratch[0] = 0xAF.toByte() // AAC, 44kHz, 16-bit, stereo flag (FLV standard)
            audioScratch[1] = 0x00 // AAC sequence header
            System.arraycopy(asc, 0, audioScratch, 2, asc.size)
            val ok = writeRtmpMessageLocked(
                csid = 4,
                timestampMs = 0,
                typeId = 0x08,
                streamId = publishedStreamId,
                payload = audioScratch,
                payloadLength = totalLen,
                outOverride = null
            )
            if (ok) aacHeaderSent = true
            return ok
        }
    }

    fun sendAudioFrame(aacData: ByteArray, aacLength: Int, timestampMs: Int): Boolean {
        if (!connected.get() || aacLength <= 0) return false
        synchronized(sendLock) {
            if (!aacHeaderSent) return false
            // Strip 7-byte ADTS header if hardware encoder prepended one (sync word 0xFFF)
            val adtsOffset = if (
                aacLength > 7 &&
                (aacData[0].toInt() and 0xFF) == 0xFF &&
                (aacData[1].toInt() and 0xF0) == 0xF0
            ) 7 else 0
            val rawLen = aacLength - adtsOffset
            if (rawLen <= 0) return false

            val totalLen = 2 + rawLen
            if (audioScratch.size < totalLen) {
                audioScratch = ByteArray(maxOf(totalLen, audioScratch.size * 2))
            }
            audioScratch[0] = 0xAF.toByte()
            audioScratch[1] = 0x01 // Raw AAC frame
            System.arraycopy(aacData, adtsOffset, audioScratch, 2, rawLen)

            val safeTsMs = maxOf(lastAudioTsMs, timestampMs.coerceAtLeast(0))
            lastAudioTsMs = safeTsMs

            val ok = writeRtmpMessageLocked(
                csid = 4,
                timestampMs = safeTsMs,
                typeId = 0x08,
                streamId = publishedStreamId,
                payload = audioScratch,
                payloadLength = totalLen,
                outOverride = null
            )
            if (ok) audioFramesConfirmedCounter.incrementAndGet()
            return ok
        }
    }

    /**
     * Non-blocking poll of any server control packets (Ping, SetChunkSize, onStatus disconnect)
     * during active streaming so YouTube keepalive pings are answered promptly.
     */
    fun pollServerControlMessages() {
        val dataIn = input ?: return
        val dataOut = output ?: return
        if (!connected.get()) return
        try {
            while (dataIn.available() > 0 && connected.get()) {
                val msg = readNextServerMessage(dataIn, dataOut) ?: break
                if (msg.typeId == 0x14) {
                    val parsed = parseAmf0Command(msg.payload)
                    if (parsed.commandName == "onStatus" && parsed.statusCode != null) {
                        val code = parsed.statusCode
                        if (code.contains("Error", ignoreCase = true) ||
                            code.contains("Failed", ignoreCase = true) ||
                            code.contains("Rejected", ignoreCase = true) ||
                            code.contains("Closed", ignoreCase = true) ||
                            code.contains("Unpublish", ignoreCase = true)
                        ) {
                            Log.w(TAG, "Server closed stream with status: $code")
                            connected.set(false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Server control poll closed connection: ${e.message}")
            connected.set(false)
        }
    }

    fun close() {
        connected.set(false)
        congestedUntilNextKey = false
        avcHeaderSent = false
        aacHeaderSent = false
        firstKeyframeSent = false
        lastVideoTsMs = 0
        lastAudioTsMs = 0
        runCatching { output?.close() }
        runCatching { input?.close() }
        runCatching { socket?.close() }
        output = null
        input = null
        socket = null
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Socket & Handshake
    // ─────────────────────────────────────────────────────────────────────────────

    private data class ParsedEndpoint(
        val host: String,
        val port: Int,
        val appPath: String,
        val isSecure: Boolean
    )

    private fun parseEndpoint(cleanUrl: String): ParsedEndpoint {
        val uri = URI(cleanUrl)
        val isSecure = uri.scheme.equals("rtmps", ignoreCase = true)
        val host = uri.host ?: throw IllegalArgumentException("Invalid RTMP host")
        val port = if (uri.port > 0) uri.port else if (isSecure) 443 else 1935
        val appPath = uri.path.trimStart('/').ifBlank { "live2" }
        return ParsedEndpoint(host, port, appPath, isSecure)
    }

    private fun openSocket(ep: ParsedEndpoint): Socket {
        // Connect plain TCP socket first so socket connect timeout applies AND Conscrypt SSLSocket
        // receives the exact target DNS hostname for SNI and SAN certificate verification.
        val plainSocket = Socket()
        plainSocket.tcpNoDelay = true
        plainSocket.sendBufferSize = 256 * 1024
        plainSocket.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
        plainSocket.connect(InetSocketAddress(ep.host, ep.port), SOCKET_CONNECT_TIMEOUT_MS)

        if (!ep.isSecure) {
            return plainSocket
        }

        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket = try {
            sslFactory.createSocket(plainSocket, ep.host, ep.port, true) as SSLSocket
        } catch (e: Exception) {
            runCatching { plainSocket.close() }
            throw e
        }

        return try {
            sslSocket.tcpNoDelay = true
            sslSocket.sendBufferSize = 256 * 1024
            sslSocket.soTimeout = HANDSHAKE_READ_TIMEOUT_MS

            val sslParams: SSLParameters = sslSocket.sslParameters
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
            if (isDnsHostname(ep.host)) {
                sslParams.serverNames = listOf(SNIHostName(ep.host))
            }
            sslSocket.sslParameters = sslParams
            sslSocket.startHandshake()

            // Explicit strict hostname verification against server TLS certificate SANs
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            if (!verifier.verify(ep.host, sslSocket.session)) {
                throw SSLPeerUnverifiedException("TLS certificate hostname does not match ${ep.host}")
            }
            sslSocket
        } catch (e: Exception) {
            runCatching { sslSocket.close() }
            throw e
        }
    }

    private fun performHandshake(dataIn: DataInputStream, dataOut: DataOutputStream) {
        dataOut.writeByte(0x03)
        val c1 = ByteArray(HANDSHAKE_SIZE)
        val nowSec = (System.currentTimeMillis() / 1000L).toInt()
        c1[0] = ((nowSec shr 24) and 0xFF).toByte()
        c1[1] = ((nowSec shr 16) and 0xFF).toByte()
        c1[2] = ((nowSec shr 8) and 0xFF).toByte()
        c1[3] = (nowSec and 0xFF).toByte()
        dataOut.write(c1)
        dataOut.flush()

        val s0 = dataIn.readByte().toInt()
        if (s0 != 0x03) throw IllegalStateException("Unexpected RTMP protocol version from server: $s0")
        val s1 = ByteArray(HANDSHAKE_SIZE)
        dataIn.readFully(s1)
        val s2 = ByteArray(HANDSHAKE_SIZE)
        dataIn.readFully(s2)

        // C2 echoes S1 back to server
        dataOut.write(s1)
        dataOut.flush()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // RTMP Control & AMF0 Command Senders
    // ─────────────────────────────────────────────────────────────────────────────

    private fun sendSetChunkSize(dataOut: DataOutputStream, size: Int) {
        val payload = ByteBuffer.allocate(4).putInt(size and 0x7FFFFFFF).array()
        writeRtmpMessage(csid = 2, timestampMs = 0, typeId = 0x01, streamId = 0, payload = payload, payloadLength = payload.size, outOverride = dataOut)
    }

    private fun sendWindowAckSize(dataOut: DataOutputStream, size: Int) {
        val payload = ByteBuffer.allocate(4).putInt(size).array()
        writeRtmpMessage(csid = 2, timestampMs = 0, typeId = 0x05, streamId = 0, payload = payload, payloadLength = payload.size, outOverride = dataOut)
    }

    private fun sendPong(dataOut: DataOutputStream, pingTimestamp: Int) {
        val payload = ByteBuffer.allocate(6)
            .putShort(7.toShort()) // Event type 7: PingResponse
            .putInt(pingTimestamp)
            .array()
        writeRtmpMessage(csid = 2, timestampMs = 0, typeId = 0x04, streamId = 0, payload = payload, payloadLength = payload.size, outOverride = dataOut)
    }

    private fun sendConnectCommand(dataOut: DataOutputStream, app: String, tcUrl: String) {
        val b = ByteArrayOutputStream(256)
        writeAmfString(b, "connect")
        writeAmfNumber(b, 1.0)
        b.write(0x03) // AMF0 Object
        writeAmfPropString(b, "app", app)
        writeAmfPropString(b, "type", "nonprivate")
        writeAmfPropString(b, "flashVer", "FMLE/3.0 (compatible; ZroLive/1.0)")
        writeAmfPropString(b, "tcUrl", tcUrl)
        writeAmfPropBoolean(b, "fpad", false)
        writeAmfPropNumber(b, "capabilities", 15.0)
        writeAmfPropNumber(b, "audioCodecs", 1024.0) // AAC
        writeAmfPropNumber(b, "videoCodecs", 128.0)  // H.264
        writeAmfPropNumber(b, "videoFunction", 1.0)
        b.write(0x00); b.write(0x00); b.write(0x09)
        val bytes = b.toByteArray()
        writeRtmpMessage(csid = 3, timestampMs = 0, typeId = 0x14, streamId = 0, payload = bytes, payloadLength = bytes.size, outOverride = dataOut)
    }

    private fun sendSimpleCommand(dataOut: DataOutputStream, name: String, txId: Double, arg: String) {
        val b = ByteArrayOutputStream(96)
        writeAmfString(b, name)
        writeAmfNumber(b, txId)
        b.write(0x05)
        writeAmfString(b, arg)
        val bytes = b.toByteArray()
        writeRtmpMessage(csid = 3, timestampMs = 0, typeId = 0x14, streamId = 0, payload = bytes, payloadLength = bytes.size, outOverride = dataOut)
    }

    private fun sendCreateStreamCommand(dataOut: DataOutputStream, txId: Double) {
        val b = ByteArrayOutputStream(48)
        writeAmfString(b, "createStream")
        writeAmfNumber(b, txId)
        b.write(0x05)
        val bytes = b.toByteArray()
        writeRtmpMessage(csid = 3, timestampMs = 0, typeId = 0x14, streamId = 0, payload = bytes, payloadLength = bytes.size, outOverride = dataOut)
    }

    private fun sendPublishCommand(dataOut: DataOutputStream, streamKey: String, streamId: Int) {
        val b = ByteArrayOutputStream(128)
        writeAmfString(b, "publish")
        writeAmfNumber(b, 5.0)
        b.write(0x05)
        writeAmfString(b, streamKey)
        writeAmfString(b, "live")
        val bytes = b.toByteArray()
        writeRtmpMessage(csid = 8, timestampMs = 0, typeId = 0x14, streamId = streamId, payload = bytes, payloadLength = bytes.size, outOverride = dataOut)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // RTMP Server Chunk Reader & AMF0 Response Parser
    // ─────────────────────────────────────────────────────────────────────────────

    private data class CommandWaitResult(
        val streamId: Int = 1,
        val statusCode: String? = null
    )

    private fun awaitCommandResponse(
        dataIn: DataInputStream,
        dataOut: DataOutputStream,
        expectedTxId: Double,
        waitForPublishStatus: Boolean,
        timeoutMs: Long
    ): Result<CommandWaitResult> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val msg = try {
                readNextServerMessage(dataIn, dataOut)
            } catch (ste: SocketTimeoutException) {
                return Result.failure(IllegalStateException("Timed out waiting for RTMP server response."))
            } catch (eof: EOFException) {
                return Result.failure(IllegalStateException("RTMP server closed the connection unexpectedly. Check your Stream Key and Server URL."))
            } ?: continue

            if (msg.typeId != 0x14) continue

            val cmd = parseAmf0Command(msg.payload)
            if (cmd.commandName == "_error") {
                val isOptionalPreCreateCmd = !waitForPublishStatus &&
                    kotlin.math.abs(expectedTxId - 4.0) < 0.01 &&
                    (kotlin.math.abs(cmd.txId - 2.0) < 0.01 || kotlin.math.abs(cmd.txId - 3.0) < 0.01)
                if (isOptionalPreCreateCmd) {
                    // Some custom RTMP servers (Nginx-RTMP, SRS, MediaMTX) don't implement releaseStream/FCPublish;
                    // continue awaiting createStream (_result txId=4.0).
                    continue
                }
                val desc = cmd.description ?: cmd.statusCode ?: "Server returned RTMP _error."
                return Result.failure(IllegalStateException("RTMP error: $desc"))
            }

            if (waitForPublishStatus) {
                if (cmd.commandName == "onStatus") {
                    val code = cmd.statusCode.orEmpty()
                    if (code.equals("NetStream.Publish.Start", ignoreCase = true) ||
                        code.contains("Publish.Start", ignoreCase = true)
                    ) {
                        return Result.success(CommandWaitResult(streamId = publishedStreamId, statusCode = code))
                    }
                    if (code.contains("BadName", ignoreCase = true) ||
                        code.contains("Rejected", ignoreCase = true) ||
                        code.contains("Failed", ignoreCase = true) ||
                        code.contains("Denied", ignoreCase = true) ||
                        code.contains("Error", ignoreCase = true)
                    ) {
                        val reason = cmd.description?.takeIf { it.isNotBlank() } ?: code
                        return Result.failure(IllegalStateException("Stream Key rejected by server ($reason)."))
                    }
                }
            } else {
                if (cmd.commandName == "_result" && kotlin.math.abs(cmd.txId - expectedTxId) < 0.01) {
                    val code = cmd.statusCode
                    if (code != null && (code.contains("Rejected", ignoreCase = true) || code.contains("Failed", ignoreCase = true))) {
                        return Result.failure(IllegalStateException("RTMP connect failed: ${cmd.description ?: code}"))
                    }
                    val assignedId = cmd.numericResult?.toInt()?.takeIf { it > 0 } ?: 1
                    return Result.success(CommandWaitResult(streamId = assignedId, statusCode = code))
                }
            }
        }
        return Result.failure(IllegalStateException("Timed out waiting for RTMP server confirmation."))
    }

    private fun readNextServerMessage(dataIn: DataInputStream, dataOut: DataOutputStream): RtmpIncomingMessage? {
        while (true) {
            val basicHeader = dataIn.readUnsignedByte()
            val fmt = (basicHeader ushr 6) and 0x03
            var csid = basicHeader and 0x3F
            when (csid) {
                0 -> csid = 64 + dataIn.readUnsignedByte()
                1 -> {
                    val b1 = dataIn.readUnsignedByte()
                    val b2 = dataIn.readUnsignedByte()
                    csid = 64 + b1 + (b2 shl 8)
                }
            }

            val header = incomingHeaders.getOrPut(csid) { ChunkHeaderState() }
            var extTimestampFlag = false

            when (fmt) {
                0 -> {
                    val ts = readUint24(dataIn)
                    header.messageLength = readUint24(dataIn)
                    header.messageTypeId = dataIn.readUnsignedByte()
                    header.messageStreamId = readInt32LE(dataIn)
                    extTimestampFlag = ts == 0xFFFFFF
                    header.timestamp = if (extTimestampFlag) dataIn.readInt() else ts
                    header.timestampDelta = 0
                }
                1 -> {
                    val delta = readUint24(dataIn)
                    header.messageLength = readUint24(dataIn)
                    header.messageTypeId = dataIn.readUnsignedByte()
                    extTimestampFlag = delta == 0xFFFFFF
                    header.timestampDelta = if (extTimestampFlag) dataIn.readInt() else delta
                    header.timestamp += header.timestampDelta
                }
                2 -> {
                    val delta = readUint24(dataIn)
                    extTimestampFlag = delta == 0xFFFFFF
                    header.timestampDelta = if (extTimestampFlag) dataIn.readInt() else delta
                    header.timestamp += header.timestampDelta
                }
                3 -> {
                    // Reuse previous header fields; handle extended timestamp if active
                    if (header.timestamp >= 0xFFFFFF || header.timestampDelta >= 0xFFFFFF) {
                        // Some servers omit ext ts on fmt=3; only read if clearly signaled
                    }
                }
            }

            val msgLen = header.messageLength.coerceIn(0, 2 * 1024 * 1024)
            val acc = incomingPayloads.getOrPut(csid) { ByteArrayOutputStream(msgLen) }
            val remaining = (msgLen - acc.size()).coerceAtLeast(0)
            val toRead = minOf(remaining, serverChunkSize)
            if (toRead > 0) {
                val chunk = ByteArray(toRead)
                dataIn.readFully(chunk)
                acc.write(chunk)
            }

            if (acc.size() >= msgLen) {
                val payload = acc.toByteArray()
                incomingPayloads.remove(csid)

                when (header.messageTypeId) {
                    0x01 -> {
                        // Set Chunk Size from server
                        if (payload.size >= 4) {
                            val newSize = ByteBuffer.wrap(payload).int and 0x7FFFFFFF
                            if (newSize in 1..65536) {
                                serverChunkSize = newSize
                            }
                        }
                        continue
                    }
                    0x04 -> {
                        // User Control Message: respond to PingRequest (event 6) with PingResponse (event 7)
                        if (payload.size >= 6) {
                            val eventType = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                            if (eventType == 6) {
                                val pingTs = ByteBuffer.wrap(payload, 2, 4).int
                                sendPong(dataOut, pingTs)
                            }
                        }
                        continue
                    }
                    0x03, 0x05, 0x06 -> {
                        // Ack / WindowAckSize / SetPeerBandwidth — consumed cleanly
                        continue
                    }
                    else -> {
                        return RtmpIncomingMessage(
                            typeId = header.messageTypeId,
                            streamId = header.messageStreamId,
                            payload = payload
                        )
                    }
                }
            }
        }
    }

    private data class ParsedAmfCommand(
        val commandName: String,
        val txId: Double,
        val numericResult: Double?,
        val statusCode: String?,
        val description: String?
    )

    private fun parseAmf0Command(payload: ByteArray): ParsedAmfCommand {
        val reader = Amf0Reader(payload)
        val name = reader.readValue() as? String ?: ""
        val txId = (reader.readValue() as? Double) ?: 0.0
        var numericResult: Double? = null
        var statusCode: String? = null
        var description: String? = null

        // Parse remaining command parameters (typically 2-3 AMF0 values)
        for (i in 0 until 4) {
            if (!reader.hasMore()) break
            when (val v = reader.readValue()) {
                is Double -> if (numericResult == null) numericResult = v
                is Map<*, *> -> {
                    val code = v["code"] as? String
                    val desc = v["description"] as? String
                    if (!code.isNullOrBlank()) statusCode = code
                    if (!desc.isNullOrBlank()) description = desc
                }
            }
        }
        return ParsedAmfCommand(name, txId, numericResult, statusCode, description)
    }

    private class Amf0Reader(private val buf: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < buf.size

        fun readValue(): Any? {
            if (pos >= buf.size) return null
            return when (buf[pos++].toInt() and 0xFF) {
                0x00 -> readDouble()
                0x01 -> if (pos < buf.size) (buf[pos++].toInt() != 0) else false
                0x02 -> readString()
                0x03 -> readObject()
                0x05, 0x06 -> null
                0x08 -> {
                    pos = (pos + 4).coerceAtMost(buf.size) // skip array count
                    readObject()
                }
                0x0A -> {
                    if (pos + 4 > buf.size) return emptyList<Any?>()
                    val count = readInt32()
                    val list = ArrayList<Any?>(count.coerceIn(0, 64))
                    repeat(count.coerceIn(0, 64)) { list.add(readValue()) }
                    list
                }
                else -> {
                    pos = buf.size
                    null
                }
            }
        }

        private fun readDouble(): Double {
            if (pos + 8 > buf.size) {
                pos = buf.size
                return 0.0
            }
            var bits = 0L
            for (i in 0 until 8) {
                bits = (bits shl 8) or (buf[pos++].toLong() and 0xFF)
            }
            return java.lang.Double.longBitsToDouble(bits)
        }

        private fun readInt32(): Int {
            if (pos + 4 > buf.size) {
                pos = buf.size
                return 0
            }
            val v = ((buf[pos].toInt() and 0xFF) shl 24) or
                ((buf[pos + 1].toInt() and 0xFF) shl 16) or
                ((buf[pos + 2].toInt() and 0xFF) shl 8) or
                (buf[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }

        private fun readUint16(): Int {
            if (pos + 2 > buf.size) {
                pos = buf.size
                return 0
            }
            val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }

        private fun readString(): String {
            val len = readUint16()
            if (len <= 0 || pos + len > buf.size) {
                pos = (pos + len).coerceAtMost(buf.size)
                return ""
            }
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            while (pos + 3 <= buf.size) {
                if (buf[pos] == 0.toByte() && buf[pos + 1] == 0.toByte() && buf[pos + 2] == 0x09.toByte()) {
                    pos += 3
                    break
                }
                val key = readString()
                if (key.isEmpty() && pos < buf.size && buf[pos] == 0x09.toByte()) {
                    pos++
                    break
                }
                val value = readValue()
                map[key] = value
            }
            return map
        }
    }

    private fun readUint24(dataIn: DataInputStream): Int {
        val b0 = dataIn.readUnsignedByte()
        val b1 = dataIn.readUnsignedByte()
        val b2 = dataIn.readUnsignedByte()
        return (b0 shl 16) or (b1 shl 8) or b2
    }

    private fun readInt32LE(dataIn: DataInputStream): Int {
        val b0 = dataIn.readUnsignedByte()
        val b1 = dataIn.readUnsignedByte()
        val b2 = dataIn.readUnsignedByte()
        val b3 = dataIn.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // RTMP Message Writer
    // ─────────────────────────────────────────────────────────────────────────────

    private fun writeRtmpMessage(
        csid: Int,
        timestampMs: Int,
        typeId: Int,
        streamId: Int,
        payload: ByteArray,
        payloadLength: Int,
        outOverride: DataOutputStream? = null
    ): Boolean {
        synchronized(sendLock) {
            return writeRtmpMessageLocked(csid, timestampMs, typeId, streamId, payload, payloadLength, outOverride)
        }
    }

    private fun writeRtmpMessageLocked(
        csid: Int,
        timestampMs: Int,
        typeId: Int,
        streamId: Int,
        payload: ByteArray,
        payloadLength: Int,
        outOverride: DataOutputStream?
    ): Boolean {
        val out = outOverride ?: output ?: return false
        return try {
            val ts = timestampMs.coerceAtLeast(0)
            val useExtTs = ts >= 0xFFFFFF
            val headerTs = if (useExtTs) 0xFFFFFF else ts

            out.writeByte(csid and 0x3F)
            out.writeByte((headerTs shr 16) and 0xFF)
            out.writeByte((headerTs shr 8) and 0xFF)
            out.writeByte(headerTs and 0xFF)
            out.writeByte((payloadLength shr 16) and 0xFF)
            out.writeByte((payloadLength shr 8) and 0xFF)
            out.writeByte(payloadLength and 0xFF)
            out.writeByte(typeId and 0xFF)
            out.writeByte(streamId and 0xFF)
            out.writeByte((streamId shr 8) and 0xFF)
            out.writeByte((streamId shr 16) and 0xFF)
            out.writeByte((streamId shr 24) and 0xFF)
            if (useExtTs) {
                out.writeInt(ts)
            }

            var offset = 0
            while (offset < payloadLength) {
                if (offset > 0) {
                    out.writeByte(0xC0 or (csid and 0x3F))
                    if (useExtTs) out.writeInt(ts)
                }
                val chunkLen = minOf(CLIENT_CHUNK_SIZE, payloadLength - offset)
                out.write(payload, offset, chunkLen)
                offset += chunkLen
            }
            out.flush()
            val written = payloadLength.toLong() + 12L
            bytesSentWindow.addAndGet(written)
            totalBytesSentCounter.addAndGet(written)
            true
        } catch (e: Exception) {
            Log.w(TAG, "RTMP write error: ${e.message}")
            connected.set(false)
            false
        }
    }

    private fun writeAmfString(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.write(0x02)
        out.write((bytes.size shr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    private fun writeAmfPropKey(out: ByteArrayOutputStream, key: String) {
        val kBytes = key.toByteArray(Charsets.UTF_8)
        out.write((kBytes.size shr 8) and 0xFF)
        out.write(kBytes.size and 0xFF)
        out.write(kBytes)
    }

    private fun writeAmfPropString(out: ByteArrayOutputStream, key: String, value: String) {
        writeAmfPropKey(out, key)
        writeAmfString(out, value)
    }

    private fun writeAmfPropNumber(out: ByteArrayOutputStream, key: String, value: Double) {
        writeAmfPropKey(out, key)
        writeAmfNumber(out, value)
    }

    private fun writeAmfPropBoolean(out: ByteArrayOutputStream, key: String, value: Boolean) {
        writeAmfPropKey(out, key)
        out.write(0x01)
        out.write(if (value) 1 else 0)
    }

    private fun writeAmfNumber(out: ByteArrayOutputStream, value: Double) {
        out.write(0x00)
        val bits = java.lang.Double.doubleToRawLongBits(value)
        for (shift in 56 downTo 0 step 8) {
            out.write(((bits ushr shift) and 0xFF).toInt())
        }
    }

    private fun sanitizeErrorMessage(e: Exception, streamKey: String): String {
        val raw = e.localizedMessage ?: e.message ?: "Connection failed."
        val key = streamKey.trim()
        val scrubbed = if (key.isNotEmpty()) raw.replace(key, maskStreamKey(key)) else raw
        if (e is SSLPeerUnverifiedException ||
            e is SSLHandshakeException ||
            scrubbed.contains("subjectAltNames", ignoreCase = true) ||
            scrubbed.contains("Hostname", ignoreCase = true) && scrubbed.contains("not verified", ignoreCase = true)
        ) {
            return "TLS certificate verification failed: server certificate does not match the hostname. Verify your RTMPS host (for YouTube RTMPS use rtmps://a.rtmps.youtube.com/live2, or rtmp://a.rtmp.youtube.com/live2)."
        }
        if (e is java.net.UnknownHostException) {
            return "RTMP server host not found. Check the spelling of your Stream RTMP URL and your internet connection."
        }
        if (e is SocketTimeoutException || scrubbed.contains("failed to connect to", ignoreCase = true)) {
            return "Timed out connecting to RTMP server. Verify the Stream RTMP URL spelling (e.g. rtmp://a.rtmp.youtube.com/live2) and check that port 1935/443 is reachable on your network."
        }
        return scrubbed
    }
}
