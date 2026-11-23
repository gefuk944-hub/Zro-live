package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import com.example.studio.LayerGraphicAdjustment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure 4x5 ColorMatrix builder combining all tonal, color, white balance, detail,
 * hue, and RGB channel controls for a single independent layer.
 */
fun buildGraphicColorMatrix(raw: LayerGraphicAdjustment): ColorMatrix {
    val adj = raw.clamped()
    val result = ColorMatrix()
    if (!adj.enabled || !adj.hasColorMatrixChanges()) return result

    if (adj.colorToneEnabled) {
        // 1. Exposure (photographic stops) + Gamma tonal gain/lift + Brightness + Highlights/Shadows
        val exposureScale = 2f.pow(adj.exposure / 100f)
        val gammaRatio = adj.gamma.coerceIn(0.20f, 3.00f)
        // Approximate gamma curve in linear RGBA space via midtone lift & slope
        val gammaSlope = (1f / gammaRatio).pow(0.45f)
        val gammaLift = (gammaRatio - 1f) * 32f

        // Contrast + Clarity + Sharpness micro-contrast pivot around mid-grey (128)
        val effectiveContrast = (adj.contrast / 100f) + (adj.clarity / 100f) * 0.35f + (adj.sharpness / 100f) * 0.22f
        val contrastScale = (1f + effectiveContrast).coerceIn(0.15f, 2.8f)

        // Highlights bias gain; Shadows bias offset lift
        val highlightGain = 1f + (adj.highlights / 100f) * 0.25f
        val shadowLift = (adj.shadows / 100f) * 42f
        val brightnessOffset = (adj.brightness / 100f) * 115f

        val combinedScale = (exposureScale * gammaSlope * contrastScale * highlightGain).coerceIn(0.1f, 3.5f)
        val combinedTranslate = (128f * (1f - contrastScale)) + brightnessOffset + shadowLift + gammaLift

        val toneMatrix = ColorMatrix(
            floatArrayOf(
                combinedScale, 0f, 0f, 0f, combinedTranslate,
                0f, combinedScale, 0f, 0f, combinedTranslate,
                0f, 0f, combinedScale, 0f, combinedTranslate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.timesAssign(toneMatrix)

        // 2. Saturation + Vibrance (vibrance applies moderated chroma boost)
        val effectiveSat = (1f + (adj.saturation / 100f) + (adj.vibrance / 100f) * 0.55f).coerceIn(0f, 2.6f)
        if (effectiveSat != 1f) {
            val satMatrix = ColorMatrix().apply { setToSaturation(effectiveSat) }
            result.timesAssign(satMatrix)
        }

        // 3. Temperature (warm red/amber vs cool blue) & Tint (green vs magenta)
        if (adj.temperature != 0f || adj.tint != 0f) {
            val temp = adj.temperature / 100f
            val tint = adj.tint / 100f
            val rScale = (1f + temp * 0.24f + tint * 0.12f).coerceIn(0.4f, 1.8f)
            val gScale = (1f + temp * 0.04f - tint * 0.22f).coerceIn(0.4f, 1.8f)
            val bScale = (1f - temp * 0.24f + tint * 0.12f).coerceIn(0.4f, 1.8f)
            val wbMatrix = ColorMatrix(
                floatArrayOf(
                    rScale, 0f, 0f, 0f, temp * 10f,
                    0f, gScale, 0f, 0f, -tint * 8f,
                    0f, 0f, bScale, 0f, -temp * 10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            result.timesAssign(wbMatrix)
        }

        // 4. Hue rotation (-180°..+180°) preserving luminance
        if (adj.hue != 0f) {
            val rad = (adj.hue * PI / 180.0)
            val cosV = cos(rad).toFloat()
            val sinV = sin(rad).toFloat()
            val lumR = 0.213f
            val lumG = 0.715f
            val lumB = 0.072f
            val hueMatrix = ColorMatrix(
                floatArrayOf(
                    lumR + cosV * (1f - lumR) + sinV * (-lumR),
                    lumG + cosV * (-lumG) + sinV * (-lumG),
                    lumB + cosV * (-lumB) + sinV * (1f - lumB),
                    0f, 0f,

                    lumR + cosV * (-lumR) + sinV * 0.143f,
                    lumG + cosV * (1f - lumG) + sinV * 0.140f,
                    lumB + cosV * (-lumB) + sinV * (-0.283f),
                    0f, 0f,

                    lumR + cosV * (-lumR) + sinV * (-(1f - lumR)),
                    lumG + cosV * (-lumG) + sinV * lumG,
                    lumB + cosV * (1f - lumB) + sinV * lumB,
                    0f, 0f,

                    0f, 0f, 0f, 1f, 0f
                )
            )
            result.timesAssign(hueMatrix)
        }
    }

    // 5. RGB Channel Controls
    if (adj.rgbEnabled && (adj.rgbRed != 1.0f || adj.rgbGreen != 1.0f || adj.rgbBlue != 1.0f)) {
        val rgbMatrix = ColorMatrix(
            floatArrayOf(
                adj.rgbRed, 0f, 0f, 0f, 0f,
                0f, adj.rgbGreen, 0f, 0f, 0f,
                0f, 0f, adj.rgbBlue, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.timesAssign(rgbMatrix)
    }

    return result
}

/**
 * Applies outer layer effects (Glow & Drop Shadow) strictly around a single layer node.
 * Bypassed cleanly when Before/After preview is active for that layer.
 */
fun Modifier.applyLayerOuterEffects(
    adjustment: LayerGraphicAdjustment,
    shape: Shape,
    legacyShadowEnabled: Boolean,
    bypassForBeforePreview: Boolean
): Modifier = composed {
    if (bypassForBeforePreview) {
        return@composed if (legacyShadowEnabled) Modifier.shadow(6.dp, shape) else Modifier
    }

    var mod: Modifier = Modifier

    // Drop Shadow (Graphic Adjustment takes precedence when enabled; otherwise preserve existing layer shadow)
    if (adjustment.enabled && adjustment.dropShadowEnabled && adjustment.dropShadowRadiusDp > 0.05f) {
        val radius = adjustment.dropShadowRadiusDp.coerceIn(0f, 24f).dp
        val shadowAlpha = adjustment.dropShadowOpacity.coerceIn(0.1f, 1.0f)
        val shadowColor = Color.Black.copy(alpha = shadowAlpha)
        mod = mod
            .drawBehind {
                val padPx = (adjustment.dropShadowRadiusDp * 0.45f).dp.toPx()
                drawRoundRect(
                    color = Color.Black.copy(alpha = shadowAlpha * 0.45f),
                    topLeft = Offset(padPx * 0.6f, padPx * 0.85f),
                    size = size,
                    cornerRadius = if (shape == RectangleShape) CornerRadius.Zero else CornerRadius(12f, 12f)
                )
            }
            .shadow(
                elevation = radius,
                shape = shape,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
    } else if (legacyShadowEnabled) {
        mod = mod.shadow(6.dp, shape)
    }

    // Outer Glow (works across Android 10 API 29 through Android 16 API 36)
    if (adjustment.enabled && adjustment.glowEnabled && adjustment.glowIntensity > 0.5f) {
        val intensity = (adjustment.glowIntensity / 100f).coerceIn(0f, 1f)
        val glowColor = Color(adjustment.glowColorHex)
        mod = mod.drawBehind {
            val spread = (4f + intensity * 14f).dp.toPx()
            val radius = (size.maxDimension * 0.65f + spread).coerceAtLeast(1f)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.55f * intensity),
                        glowColor.copy(alpha = 0.22f * intensity),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                topLeft = Offset(-spread, -spread),
                size = androidx.compose.ui.geometry.Size(size.width + spread * 2f, size.height + spread * 2f)
            )
        }
    }

    mod
}

/**
 * Applies inner per-layer graphic adjustments (ColorMatrix grading, Blur, Glow sheen, Outline)
 * strictly to this layer's rendered content without affecting any other layer or the scene.
 * Compatible with Android 10 (API 29) through Android 16 (API 36).
 */
fun Modifier.applyLayerInnerGraphicAdjustment(
    adjustment: LayerGraphicAdjustment,
    shape: Shape,
    bypassForBeforePreview: Boolean
): Modifier = composed {
    if (bypassForBeforePreview || !adjustment.enabled) return@composed Modifier

    var mod: Modifier = Modifier

    // 1. ColorMatrix applied via saveLayer Paint — works across ALL Android versions (API 24..36)
    //    and filters bitmaps, images, text, widgets, and backgrounds strictly inside this layer!
    if (adjustment.hasColorMatrixChanges()) {
        val matrix = buildGraphicColorMatrix(adjustment)
        val filter = ColorFilter.colorMatrix(matrix)
        mod = mod.drawWithContent {
            val bounds = Rect(Offset.Zero, size)
            val paint = Paint().apply { colorFilter = filter }
            drawIntoCanvas { canvas ->
                canvas.saveLayer(bounds, paint)
                drawContent()
                canvas.restore()
            }
        }
    }

    // 2. Blur (native GPU RenderEffect on API 31+; lightweight soft haze fallback on Android 10–11)
    if (adjustment.blurEnabled && adjustment.blurRadiusDp > 0.05f) {
        val radiusDp = adjustment.blurRadiusDp.coerceIn(0f, 25f)
        mod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mod.blur(radiusDp.dp)
        } else {
            val frostAlpha = ((radiusDp / 25f) * 0.42f).coerceIn(0.04f, 0.42f)
            mod.drawWithContent {
                drawContent()
                drawRect(color = Color(0xFFCBD5E1).copy(alpha = frostAlpha))
            }
        }
    }

    // 3. Subtle inner glow rim when Glow is enabled
    if (adjustment.glowEnabled && adjustment.glowIntensity > 0.5f) {
        val intensity = (adjustment.glowIntensity / 100f).coerceIn(0f, 1f)
        val glowColor = Color(adjustment.glowColorHex)
        mod = mod.border(
            BorderStroke((1f + intensity * 2.5f).dp, glowColor.copy(alpha = 0.65f * intensity)),
            shape
        )
    }

    // 4. Outline border when Outline is enabled
    if (adjustment.outlineEnabled && adjustment.outlineWidthDp > 0.05f) {
        mod = mod.border(
            BorderStroke(adjustment.outlineWidthDp.coerceIn(0.5f, 10f).dp, Color(adjustment.outlineColorHex)),
            shape
        )
    }

    mod
}
