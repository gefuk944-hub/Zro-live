package com.example.studio

import android.graphics.Bitmap
import android.util.LruCache
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Fast, real-time perceptual YCbCr Chroma Key engine for removing green screen,
 * blue screen, or any custom sampled background color on individual Studio layers.
 *
 * Features:
 * - Perceptual YCbCr chroma distance keying (accurate under uneven studio lighting)
 * - Smooth feathered alpha ramp (Similarity + Smoothness)
 * - Edge Cleanup matte tightening to remove residual fringe halos
 * - Despill / Spill Reduction neutralizing green/blue bounce on foreground edges
 * - Foreground opacity multiplier & zero-mutation of original source bitmaps
 */
object ChromaKeyProcessor {

    private val keyedBitmapCache = object : LruCache<String, Bitmap>(16) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun clearCache() {
        keyedBitmapCache.evictAll()
    }

    fun cacheKey(sourceId: String, settings: LayerChromaKeySettings, width: Int, height: Int): String {
        val c = settings.clamped()
        return "$sourceId:${width}x${height}:${c.keyColorHex}:" +
            "${(c.similarity * 100).toInt()}:${(c.smoothness * 100).toInt()}:" +
            "${(c.spillReduction * 100).toInt()}:${(c.edgeCleanup * 100).toInt()}:${(c.opacity * 100).toInt()}"
    }

    fun applyChromaKeyCached(
        sourceKey: String,
        source: Bitmap,
        settings: LayerChromaKeySettings
    ): Bitmap {
        if (!settings.enabled) return source
        val key = cacheKey(sourceKey, settings, source.width, source.height)
        keyedBitmapCache.get(key)?.let { cached ->
            if (!cached.isRecycled) return cached
        }
        val keyed = applyChromaKey(source, settings)
        keyedBitmapCache.put(key, keyed)
        return keyed
    }

    /**
     * Applies broadcast YCbCr Chroma Keying to [source] and returns a new ARGB_8888 Bitmap
     * with transparent background pixels where chroma matches [settings.keyColorHex].
     */
    fun applyChromaKey(
        source: Bitmap,
        rawSettings: LayerChromaKeySettings
    ): Bitmap {
        val settings = rawSettings.clamped()
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val keyColor = settings.keyColorHex.toInt()
        val keyR = ((keyColor shr 16) and 0xFF) / 255f
        val keyG = ((keyColor shr 8) and 0xFF) / 255f
        val keyB = (keyColor and 0xFF) / 255f
        val keyCb = rgbToCb(keyR, keyG, keyB)
        val keyCr = rgbToCr(keyR, keyG, keyB)

        // Normalize similarity & smoothness into YCbCr chroma radius space (max chroma distance ~0.72)
        val simRadius = settings.similarity * 0.52f
        val smoothBand = max(0.004f, settings.smoothness * 0.45f)
        val spillAmount = settings.spillReduction
        val edgeCleanup = settings.edgeCleanup
        val opacityScale = settings.opacity

        // Identify dominant channel of key color for targeted despill
        val keyIsGreenDominant = keyG >= keyR && keyG >= keyB
        val keyIsBlueDominant = keyB > keyG && keyB >= keyR

        val alphaPass = FloatArray(pixels.size)

        for (i in pixels.indices) {
            val px = pixels[i]
            val origA = ((px ushr 24) and 0xFF) / 255f
            if (origA <= 0.001f) {
                alphaPass[i] = 0f
                continue
            }
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f

            val cb = rgbToCb(r, g, b)
            val cr = rgbToCr(r, g, b)
            val dist = hypot((cb - keyCb).toDouble(), (cr - keyCr).toDouble()).toFloat()

            // Base matte alpha via smoothstep ramp
            val rawMask = when {
                dist <= simRadius -> 0f
                dist >= simRadius + smoothBand -> 1f
                else -> {
                    val t = ((dist - simRadius) / smoothBand).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t)
                }
            }

            // Edge cleanup tightens semi-transparent fringe pixels cleanly
            val cleanedMask = if (edgeCleanup > 0.01f && rawMask < 0.999f) {
                val cutoff = edgeCleanup * 0.45f
                if (rawMask <= cutoff) 0f
                else ((rawMask - cutoff) / (1f - cutoff)).coerceIn(0f, 1f)
            } else {
                rawMask
            }

            alphaPass[i] = (origA * cleanedMask).coerceIn(0f, 1f)
        }

        // Optional 1-pass neighbor matte erosion when edgeCleanup > 0.20 to eliminate 1px green halo
        val finalAlphas = if (edgeCleanup > 0.20f && width > 2 && height > 2) {
            val eroded = alphaPass.copyOf()
            val strength = ((edgeCleanup - 0.20f) / 0.80f).coerceIn(0f, 1f)
            for (y in 1 until height - 1) {
                val row = y * width
                for (x in 1 until width - 1) {
                    val idx = row + x
                    val cur = alphaPass[idx]
                    if (cur > 0f && cur < 0.98f) {
                        val minNeighbor = min(
                            min(alphaPass[idx - 1], alphaPass[idx + 1]),
                            min(alphaPass[idx - width], alphaPass[idx + width])
                        )
                        eroded[idx] = cur * (1f - strength * 0.65f) + minNeighbor * (strength * 0.65f)
                    }
                }
            }
            eroded
        } else {
            alphaPass
        }

        // Composite output pixels with Spill Reduction & Opacity
        for (i in pixels.indices) {
            val outAlpha = (finalAlphas[i] * opacityScale).coerceIn(0f, 1f)
            if (outAlpha <= 0.004f) {
                pixels[i] = 0x00000000
                continue
            }

            val px = pixels[i]
            var r = ((px shr 16) and 0xFF) / 255f
            var g = ((px shr 8) and 0xFF) / 255f
            var b = (px and 0xFF) / 255f

            if (spillAmount > 0.01f) {
                when {
                    keyIsGreenDominant -> {
                        val avgRb = (r + b) * 0.5f
                        if (g > avgRb) {
                            val excess = (g - avgRb) * spillAmount
                            g = (g - excess).coerceIn(0f, 1f)
                            r = (r + excess * 0.18f).coerceIn(0f, 1f)
                            b = (b + excess * 0.18f).coerceIn(0f, 1f)
                        }
                    }
                    keyIsBlueDominant -> {
                        val avgRg = (r + g) * 0.5f
                        if (b > avgRg) {
                            val excess = (b - avgRg) * spillAmount
                            b = (b - excess).coerceIn(0f, 1f)
                            r = (r + excess * 0.18f).coerceIn(0f, 1f)
                            g = (g + excess * 0.18f).coerceIn(0f, 1f)
                        }
                    }
                    else -> {
                        // Custom key color despill via luma blend along near-key pixels
                        val cb = rgbToCb(r, g, b)
                        val cr = rgbToCr(r, g, b)
                        val dist = hypot((cb - keyCb).toDouble(), (cr - keyCr).toDouble()).toFloat()
                        val spillWeight = (1f - (dist / max(0.05f, simRadius + smoothBand + 0.18f))).coerceIn(0f, 1f) * spillAmount
                        if (spillWeight > 0.01f) {
                            val luma = 0.299f * r + 0.587f * g + 0.114f * b
                            r = r + (luma - r) * spillWeight
                            g = g + (luma - g) * spillWeight
                            b = b + (luma - b) * spillWeight
                        }
                    }
                }
            }

            val aInt = (outAlpha * 255f + 0.5f).toInt().coerceIn(0, 255)
            val rInt = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gInt = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bInt = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = (aInt shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(true)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    /**
     * Samples the average pixel color in a small 3x3 neighborhood at normalized coordinates (nx, ny)
     * from a source Bitmap for the Eyedropper color sampling tool.
     */
    fun sampleColorAt(bitmap: Bitmap, normX: Float, normY: Float): Long {
        val cx = (normX.coerceIn(0f, 1f) * (bitmap.width - 1)).toInt().coerceIn(0, bitmap.width - 1)
        val cy = (normY.coerceIn(0f, 1f) * (bitmap.height - 1)).toInt().coerceIn(0, bitmap.height - 1)
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val pxX = (cx + dx).coerceIn(0, bitmap.width - 1)
                val pxY = (cy + dy).coerceIn(0, bitmap.height - 1)
                val c = bitmap.getPixel(pxX, pxY)
                rSum += (c shr 16) and 0xFF
                gSum += (c shr 8) and 0xFF
                bSum += c and 0xFF
                count++
            }
        }
        val r = (rSum / count).toInt().coerceIn(0, 255)
        val g = (gSum / count).toInt().coerceIn(0, 255)
        val b = (bSum / count).toInt().coerceIn(0, 255)
        return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    private fun rgbToCb(r: Float, g: Float, b: Float): Float =
        -0.168736f * r - 0.331264f * g + 0.5f * b

    private fun rgbToCr(r: Float, g: Float, b: Float): Float =
        0.5f * r - 0.418688f * g - 0.081312f * b
}
