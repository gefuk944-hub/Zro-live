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
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Generates high-contrast QR bitmaps for Phone 2 pairing and decodes QR codes from
 * CameraX YUV frames (Phone 1 scanner) or Bitmaps using ZXing.
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

    fun decodeQrFromYuv(yPlaneBytes: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || yPlaneBytes.size < width * height) return null
        return runCatching {
            val source = PlanarYUVLuminanceSource(
                yPlaneBytes,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            decodeFromLuminanceSource(source)
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
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
        return runCatching { reader.decodeWithState(binaryBitmap).text }.getOrNull()
    }
}
