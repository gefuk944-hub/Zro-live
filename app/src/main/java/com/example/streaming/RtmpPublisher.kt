package com.example.streaming

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocketFactory

/**
 * High-efficiency, pure-Kotlin RTMP/RTMPS 1.0 publisher optimized for 4GB RAM Android devices:
 * - Zero per-frame ByteArrayOutputStream / ByteArray heap allocations in steady-state video & audio paths
 * - Automatic Annex-B -> AVCC conversion in reusable scratch buffers
 * - Congestion detection: drops non-keyframes if TCP write stalls exceed threshold to prevent encoder lockups
 * - Live byte throughput metering & clean socket teardown
 */
class RtmpPublisher {

    companion object {
        private const val TAG = "RtmpPublisher"
        private const val CHUNK_SIZE = 4096
        private const val HANDSHAKE_SIZE = 1536
        private const val CONGESTION_WRITE_MS = 180L
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private val connected = AtomicBoolean(false)
    private val bytesSent = AtomicLong(0L)
    private val sendLock = Any()

    // Reusable buffers guarded by sendLock (zero per-frame GC allocation)
    private var videoScratch = ByteArray(128 * 1024)
    private var audioScratch = ByteArray(8 * 1024)
    private var congestedUntilNextKey = false

    val isConnected: Boolean
        get() = connected.get()

    fun consumeBytesSentSinceLast(): Long = bytesSent.getAndSet(0L)

    fun connect(rtmpBaseUrl: String, streamKey: String): Result<Unit> {
        return try {
            close()
            val cleanBase = rtmpBaseUrl.trim().trimEnd('/')
            val cleanKey = streamKey.trim()
            if (cleanBase.isBlank() || (!cleanBase.startsWith("rtmp://") && !cleanBase.startsWith("rtmps://"))) {
                return Result.failure(IllegalArgumentException("RTMP URL must start with rtmp:// or rtmps://"))
            }
            if (cleanKey.isBlank()) {
                return Result.failure(IllegalArgumentException("YouTube Stream Key is required to broadcast."))
            }

            val uri = URI(cleanBase)
            val isSecure = uri.scheme.equals("rtmps", ignoreCase = true)
            val host = uri.host ?: return Result.failure(IllegalArgumentException("Invalid RTMP host"))
            val port = if (uri.port > 0) uri.port else if (isSecure) 443 else 1935
            val appPath = uri.path.trimStart('/').ifBlank { "live2" }

            val rawSocket = if (isSecure) {
                SSLSocketFactory.getDefault().createSocket()
            } else {
                Socket()
            }
            rawSocket.tcpNoDelay = true
            rawSocket.sendBufferSize = 256 * 1024
            rawSocket.soTimeout = 10_000
            rawSocket.connect(InetSocketAddress(host, port), 8_000)

            socket = rawSocket
            val dataIn = DataInputStream(BufferedInputStream(rawSocket.getInputStream()))
            val dataOut = DataOutputStream(BufferedOutputStream(rawSocket.getOutputStream(), 32 * 1024))
            input = dataIn
            output = dataOut

            performHandshake(dataIn, dataOut)
            sendSetChunkSize(dataOut, CHUNK_SIZE)
            sendConnectCommand(dataOut, appPath, cleanBase)
            drainServerResponses(dataIn, timeoutMs = 1200)

            sendSimpleCommand(dataOut, "releaseStream", 2.0, cleanKey)
            sendSimpleCommand(dataOut, "FCPublish", 3.0, cleanKey)
            sendCreateStreamCommand(dataOut, 4.0)
            drainServerResponses(dataIn, timeoutMs = 1000)

            sendPublishCommand(dataOut, cleanKey)
            drainServerResponses(dataIn, timeoutMs = 1000)

            congestedUntilNextKey = false
            connected.set(true)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "RTMP connect failed: ${e.message}", e)
            close()
            Result.failure(e)
        }
    }

    fun sendAvcSequenceHeader(sps: ByteArray, pps: ByteArray) {
        if (!connected.get()) return
        val body = ByteArrayOutputStream(16 + sps.size + pps.size)
        body.write(0x17)
        body.write(0x00)
        body.write(0x00); body.write(0x00); body.write(0x00)
        body.write(0x01)
        body.write(if (sps.size > 1) sps[1].toInt() else 0x42)
        body.write(if (sps.size > 2) sps[2].toInt() else 0x00)
        body.write(if (sps.size > 3) sps[3].toInt() else 0x1F)
        body.write(0xFF)
        body.write(0xE1)
        body.write((sps.size shr 8) and 0xFF)
        body.write(sps.size and 0xFF)
        body.write(sps)
        body.write(0x01)
        body.write((pps.size shr 8) and 0xFF)
        body.write(pps.size and 0xFF)
        body.write(pps)

        val bytes = body.toByteArray()
        writeRtmpMessage(csid = 6, timestampMs = 0, typeId = 0x09, streamId = 1, payload = bytes, payloadLength = bytes.size)
    }

    fun sendVideoFrame(nalData: ByteArray, nalLength: Int, isKeyFrame: Boolean, timestampMs: Int): Boolean {
        if (!connected.get() || nalLength <= 0) return false
        synchronized(sendLock) {
            if (isKeyFrame) {
                congestedUntilNextKey = false
            } else if (congestedUntilNextKey) {
                // Shed non-keyframes during transient network stall until next I-frame
                return false
            }

            val offset = stripStartCodeOffset(nalData, nalLength)
            val len = nalLength - offset
            if (len <= 0) return true

            val totalLen = 9 + len
            if (videoScratch.size < totalLen) {
                videoScratch = ByteArray(maxOf(totalLen, videoScratch.size * 2))
            }

            videoScratch[0] = if (isKeyFrame) 0x17.toByte() else 0x27.toByte()
            videoScratch[1] = 0x01.toByte() // AVC NALU
            videoScratch[2] = 0x00
            videoScratch[3] = 0x00
            videoScratch[4] = 0x00
            videoScratch[5] = ((len shr 24) and 0xFF).toByte()
            videoScratch[6] = ((len shr 16) and 0xFF).toByte()
            videoScratch[7] = ((len shr 8) and 0xFF).toByte()
            videoScratch[8] = (len and 0xFF).toByte()
            System.arraycopy(nalData, offset, videoScratch, 9, len)

            val startNs = System.nanoTime()
            val ok = writeRtmpMessageLocked(
                csid = 6,
                timestampMs = timestampMs,
                typeId = 0x09,
                streamId = 1,
                payload = videoScratch,
                payloadLength = totalLen,
                outOverride = null
            )
            val writeMs = (System.nanoTime() - startNs) / 1_000_000L
            if (ok && writeMs > CONGESTION_WRITE_MS) {
                congestedUntilNextKey = true
            }
            return ok
        }
    }

    fun sendAacSequenceHeader(asc: ByteArray) {
        if (!connected.get()) return
        synchronized(sendLock) {
            val totalLen = 2 + asc.size
            if (audioScratch.size < totalLen) audioScratch = ByteArray(totalLen)
            audioScratch[0] = 0xAF.toByte()
            audioScratch[1] = 0x00
            System.arraycopy(asc, 0, audioScratch, 2, asc.size)
            writeRtmpMessageLocked(
                csid = 4,
                timestampMs = 0,
                typeId = 0x08,
                streamId = 1,
                payload = audioScratch,
                payloadLength = totalLen,
                outOverride = null
            )
        }
    }

    fun sendAudioFrame(aacData: ByteArray, aacLength: Int, timestampMs: Int): Boolean {
        if (!connected.get() || aacLength <= 0) return false
        synchronized(sendLock) {
            val totalLen = 2 + aacLength
            if (audioScratch.size < totalLen) {
                audioScratch = ByteArray(maxOf(totalLen, audioScratch.size * 2))
            }
            audioScratch[0] = 0xAF.toByte()
            audioScratch[1] = 0x01
            System.arraycopy(aacData, 0, audioScratch, 2, aacLength)
            return writeRtmpMessageLocked(
                csid = 4,
                timestampMs = timestampMs,
                typeId = 0x08,
                streamId = 1,
                payload = audioScratch,
                payloadLength = totalLen,
                outOverride = null
            )
        }
    }

    fun close() {
        connected.set(false)
        congestedUntilNextKey = false
        runCatching { output?.close() }
        runCatching { input?.close() }
        runCatching { socket?.close() }
        output = null
        input = null
        socket = null
    }

    private fun performHandshake(dataIn: DataInputStream, dataOut: DataOutputStream) {
        dataOut.writeByte(0x03)
        val c1 = ByteArray(HANDSHAKE_SIZE)
        dataOut.write(c1)
        dataOut.flush()

        val s0 = dataIn.readByte().toInt()
        if (s0 != 0x03) throw IllegalStateException("Unexpected RTMP server version: $s0")
        val s1 = ByteArray(HANDSHAKE_SIZE)
        dataIn.readFully(s1)
        val s2 = ByteArray(HANDSHAKE_SIZE)
        dataIn.readFully(s2)

        dataOut.write(s1)
        dataOut.flush()
    }

    private fun sendSetChunkSize(dataOut: DataOutputStream, size: Int) {
        val payload = ByteBuffer.allocate(4).putInt(size).array()
        writeRtmpMessage(csid = 2, timestampMs = 0, typeId = 0x01, streamId = 0, payload = payload, payloadLength = payload.size, outOverride = dataOut)
    }

    private fun sendConnectCommand(dataOut: DataOutputStream, app: String, tcUrl: String) {
        val b = ByteArrayOutputStream()
        writeAmfString(b, "connect")
        writeAmfNumber(b, 1.0)
        b.write(0x03)
        writeAmfPropString(b, "app", app)
        writeAmfPropString(b, "type", "nonprivate")
        writeAmfPropString(b, "flashVer", "FMLE/3.0 (compatible; ZroLive/1.0)")
        writeAmfPropString(b, "tcUrl", tcUrl)
        b.write(0x00); b.write(0x00); b.write(0x09)
        val bytes = b.toByteArray()
        writeRtmpMessage(csid = 3, timestampMs = 0, typeId = 0x14, streamId = 0, payload = bytes, payloadLength = bytes.size, outOverride = dataOut)
    }

    private fun sendSimpleCommand(dataOut: DataOutputStream, name: String, txId: Double, arg: String) {
        val b = ByteArrayOutputStream()
        writeAmfString(b, name)
        writeAmfNumber(b, txId)
        b.write(0x05)
        writeAmfString(b, arg)
        val bytes = b.toByteArray()
        writeRtmpMessage(csid = 3, timestampMs = 0, typeId = 0x14, streamId = 0, payload = bytes, payloadLength = bytes.size, outOverride = dataOut)
    }

    private fun sendCreateStreamCommand(dataOut: DataOutputStream, txId: Double) {
        val b = ByteArrayOutputStream()
        writeAmfString(b, "createStream")
        writeAmfNumber(b, txId)
        b.write(0x05)
        val bytes = b.toByteArray()
        writeRtmpMessage(csid = 3, timestampMs = 0, typeId = 0x14, streamId = 0, payload = bytes, payloadLength = bytes.size, outOverride = dataOut)
    }

    private fun sendPublishCommand(dataOut: DataOutputStream, streamKey: String) {
        val b = ByteArrayOutputStream()
        writeAmfString(b, "publish")
        writeAmfNumber(b, 5.0)
        b.write(0x05)
        writeAmfString(b, streamKey)
        writeAmfString(b, "live")
        val bytes = b.toByteArray()
        writeRtmpMessage(csid = 8, timestampMs = 0, typeId = 0x14, streamId = 1, payload = bytes, payloadLength = bytes.size, outOverride = dataOut)
    }

    private fun drainServerResponses(dataIn: DataInputStream, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(1024)
        while (System.currentTimeMillis() < deadline) {
            val avail = dataIn.available()
            if (avail > 0) {
                dataIn.read(buf, 0, minOf(avail, buf.size))
            } else {
                Thread.sleep(50)
                if (dataIn.available() == 0) break
            }
        }
    }

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
                val chunkLen = minOf(CHUNK_SIZE, payloadLength - offset)
                out.write(payload, offset, chunkLen)
                offset += chunkLen
            }
            out.flush()
            bytesSent.addAndGet(payloadLength.toLong() + 12L)
            true
        } catch (e: Exception) {
            Log.w(TAG, "RTMP write error: ${e.message}")
            connected.set(false)
            false
        }
    }

    private fun stripStartCodeOffset(data: ByteArray, length: Int): Int {
        if (length >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return 4
        }
        if (length >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            return 3
        }
        return 0
    }

    private fun writeAmfString(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.write(0x02)
        out.write((bytes.size shr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    private fun writeAmfPropString(out: ByteArrayOutputStream, key: String, value: String) {
        val kBytes = key.toByteArray(Charsets.UTF_8)
        out.write((kBytes.size shr 8) and 0xFF)
        out.write(kBytes.size and 0xFF)
        out.write(kBytes)
        writeAmfString(out, value)
    }

    private fun writeAmfNumber(out: ByteArrayOutputStream, value: Double) {
        out.write(0x00)
        val bits = java.lang.Double.doubleToRawLongBits(value)
        for (shift in 56 downTo 0 step 8) {
            out.write(((bits ushr shift) and 0xFF).toInt())
        }
    }
}
