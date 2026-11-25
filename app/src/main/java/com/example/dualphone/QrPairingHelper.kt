package com.example.dualphone

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer

/**
 * Generates high-contrast QR bitmaps for Phone 2 pairing and decodes QR codes reliably
 * from CameraX YUV frames (properly stripping rowStride/pixelStride padding and handling
 * sensor rotation + inverted contrast) or Bitmaps using ZXing.
 */
object QrPairingHelper {

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        if (content.isBlank() || sizePx <= 0) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }.getOrNull()
    }

    /**
     * Extracts tightly packed grayscale Y-plane bytes (width * height) from a CameraX ImageProxy
     * plane while accurately handling rowStride > width and pixelStride >= 1 on real devices.
     */
    fun extractPackedYPlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int = 1
    ): ByteArray {
        val dup = buffer.duplicate()
        dup.rewind()
        val out = ByteArray(width * height)
        if (rowStride == width && pixelStride == 1 && dup.remaining() >= out.size) {
            dup.get(out)
            return out
        }
        val rowData = ByteArray(rowStride.coerceAtLeast(width))
        for (row in 0 until height) {
            val remaining = dup.remaining()
            if (remaining <= 0) break
            val toRead = minOf(rowStride, remaining)
            dup.get(rowData, 0, toRead)
            val dstOffset = row * width
            if (pixelStride == 1) {
                val copyLen = minOf(width, toRead)
                System.arraycopy(rowData, 0, out, dstOffset, copyLen)
            } else {
                for (col in 0 until width) {
                    val srcIdx = col * pixelStride
                    if (srcIdx < toRead) {
                        out[dstOffset + col] = rowData[srcIdx]
                    }
                }
            }
        }
        return out
    }

    fun rotateLuma(bytes: ByteArray, width: Int, height: Int, rotationDegrees: Int): Triple<ByteArray, Int, Int> {
        return when (((rotationDegrees % 360) + 360) % 360) {
            90 -> {
                val dst = ByteArray(width * height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val newX = height - 1 - y
                        val newY = x
                        dst[newY * height + newX] = bytes[y * width + x]
                    }
                }
                Triple(dst, height, width)
            }
            180 -> {
                val dst = ByteArray(width * height)
                for (i in bytes.indices) {
                    dst[bytes.size - 1 - i] = bytes[i]
                }
                Triple(dst, width, height)
            }
            270 -> {
                val dst = ByteArray(width * height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val newX = y
                        val newY = width - 1 - x
                        dst[newY * height + newX] = bytes[y * width + x]
                    }
                }
                Triple(dst, height, width)
            }
            else -> Triple(bytes, width, height)
        }
    }

    fun decodeQrFromYuv(
        yPlaneBytes: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        pixelStride: Int = 1,
        rotationDegrees: Int = 0
    ): String? {
        if (width <= 0 || height <= 0 || yPlaneBytes.isEmpty()) return null
        return runCatching {
            val packed = if (rowStride == width && pixelStride == 1 && yPlaneBytes.size >= width * height) {
                yPlaneBytes
            } else {
                extractPackedYPlane(ByteBuffer.wrap(yPlaneBytes), width, height, rowStride, pixelStride)
            }
            // Attempt raw orientation first, then rotated orientation if sensor rotated
            val directSource = PlanarYUVLuminanceSource(packed, width, height, 0, 0, width, height, false)
            decodeFromLuminanceSource(directSource)?.let { return@runCatching it }

            if (rotationDegrees % 360 != 0) {
                val (rotBytes, rotW, rotH) = rotateLuma(packed, width, height, rotationDegrees)
                val rotSource = PlanarYUVLuminanceSource(rotBytes, rotW, rotH, 0, 0, rotW, rotH, false)
                decodeFromLuminanceSource(rotSource)?.let { return@runCatching it }
            }
            null
        }.getOrNull()
    }

    fun decodeQrFromBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        return runCatching {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            decodeFromLuminanceSource(source)
        }.getOrNull()
    }

    private fun decodeFromLuminanceSource(source: com.google.zxing.LuminanceSource): String? {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.CHARACTER_SET to "UTF-8"
                )
            )
        }
        val attempts = listOf(
            BinaryBitmap(HybridBinarizer(source)),
            BinaryBitmap(GlobalHistogramBinarizer(source)),
            BinaryBitmap(HybridBinarizer(source.invert()))
        )
        for (bmp in attempts) {
            val text = runCatching { reader.decodeWithState(bmp).text }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }
}
