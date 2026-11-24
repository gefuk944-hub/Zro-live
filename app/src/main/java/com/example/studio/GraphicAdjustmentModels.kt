package com.example.studio

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Built-in and custom Graphic Adjustment presets for individual OBS Studio layers/sources.
 */
enum class GraphicPresetId(val label: String) {
    ORIGINAL("Original"),
    BRIGHT("Bright"),
    CINEMATIC("Cinematic"),
    HIGH_CONTRAST("High Contrast"),
    VIBRANT("Vibrant"),
    NATURAL("Natural"),
    CUSTOM("Custom");

    companion object {
        fun fromName(raw: String?): GraphicPresetId =
            entries.firstOrNull { it.name == raw } ?: ORIGINAL
    }
}

enum class ChromaKeyPreset(val label: String, val defaultColorHex: Long) {
    GREEN_SCREEN("Green Screen", 0xFF00B140L),
    BLUE_SCREEN("Blue Screen", 0xFF0047BBL),
    CUSTOM("Custom Color", 0xFF00B140L);

    companion object {
        fun fromName(raw: String?): ChromaKeyPreset =
            entries.firstOrNull { it.name == raw } ?: GREEN_SCREEN
    }
}

enum class ChromaKeyPreviewMode(val label: String) {
    CHROMA_KEY("Keyed"),
    ORIGINAL("Original"),
    CHECKERBOARD("Transparent")
}

/**
 * Per-layer Chroma Key (green/blue/custom screen removal) configuration.
 * Persisted independently for every layer alongside its graphic adjustments.
 */
data class LayerChromaKeySettings(
    val enabled: Boolean = false,
    val preset: ChromaKeyPreset = ChromaKeyPreset.GREEN_SCREEN,
    val keyColorHex: Long = 0xFF00B140L,
    val similarity: Float = 0.40f,       // 0.01..1.00
    val smoothness: Float = 0.12f,       // 0.00..0.60
    val spillReduction: Float = 0.35f,   // 0.00..1.00
    val edgeCleanup: Float = 0.15f,      // 0.00..1.00
    val opacity: Float = 1.0f            // 0.05..1.00
) {
    fun clamped(): LayerChromaKeySettings = copy(
        similarity = similarity.coerceIn(0.01f, 1.0f),
        smoothness = smoothness.coerceIn(0.0f, 0.60f),
        spillReduction = spillReduction.coerceIn(0.0f, 1.0f),
        edgeCleanup = edgeCleanup.coerceIn(0.0f, 1.0f),
        opacity = opacity.coerceIn(0.05f, 1.0f)
    )

    companion object {
        val DEFAULT = LayerChromaKeySettings()

        fun forPreset(preset: ChromaKeyPreset): LayerChromaKeySettings = when (preset) {
            ChromaKeyPreset.GREEN_SCREEN -> LayerChromaKeySettings(
                enabled = true,
                preset = ChromaKeyPreset.GREEN_SCREEN,
                keyColorHex = 0xFF00B140L,
                similarity = 0.40f,
                smoothness = 0.12f,
                spillReduction = 0.38f,
                edgeCleanup = 0.15f,
                opacity = 1.0f
            )
            ChromaKeyPreset.BLUE_SCREEN -> LayerChromaKeySettings(
                enabled = true,
                preset = ChromaKeyPreset.BLUE_SCREEN,
                keyColorHex = 0xFF0047BBL,
                similarity = 0.38f,
                smoothness = 0.14f,
                spillReduction = 0.35f,
                edgeCleanup = 0.15f,
                opacity = 1.0f
            )
            ChromaKeyPreset.CUSTOM -> LayerChromaKeySettings(
                enabled = true,
                preset = ChromaKeyPreset.CUSTOM
            )
        }
    }
}

/**
 * Independent, per-layer graphic adjustment configuration.
 * All defaults are strictly neutral (no visual change to existing layers).
 */
data class LayerGraphicAdjustment(
    val enabled: Boolean = true,
    val presetId: GraphicPresetId = GraphicPresetId.ORIGINAL,
    val customPresetId: String? = null,
    // Group enable/disable toggles
    val colorToneEnabled: Boolean = true,
    val rgbEnabled: Boolean = true,
    // Light & Tone
    val brightness: Float = 0f,        // -100..100
    val contrast: Float = 0f,          // -100..100
    val exposure: Float = 0f,          // -100..100
    val gamma: Float = 1.0f,           // 0.20..3.00
    val highlights: Float = 0f,        // -100..100
    val shadows: Float = 0f,           // -100..100
    // Color & White Balance
    val saturation: Float = 0f,        // -100..100
    val vibrance: Float = 0f,          // -100..100
    val temperature: Float = 0f,       // -100..100
    val tint: Float = 0f,              // -100..100
    val hue: Float = 0f,               // -180..180 deg
    // RGB Channel Multipliers
    val rgbRed: Float = 1.0f,          // 0.0..2.0
    val rgbGreen: Float = 1.0f,        // 0.0..2.0
    val rgbBlue: Float = 1.0f,         // 0.0..2.0
    // Detail & Focus
    val sharpness: Float = 0f,         // 0..100
    val clarity: Float = 0f,           // -100..100
    // Effects & Compositing
    val opacity: Float = 1.0f,         // 0.05..1.0 (synced with SceneLayer.opacity when edited)
    val blurEnabled: Boolean = false,
    val blurRadiusDp: Float = 0f,      // 0..25 dp
    val glowEnabled: Boolean = false,
    val glowIntensity: Float = 0f,     // 0..100
    val glowColorHex: Long = 0xFF3B82F6L,
    val dropShadowEnabled: Boolean = false,
    val dropShadowRadiusDp: Float = 0f,// 0..24 dp
    val dropShadowOpacity: Float = 0.65f, // 0.1..1.0
    val outlineEnabled: Boolean = false,
    val outlineWidthDp: Float = 0f,    // 0..10 dp
    val outlineColorHex: Long = 0xFF3B82F6L,
    // Independent per-layer Chroma Key (Green Screen / Blue Screen / Custom Color removal)
    val chromaKey: LayerChromaKeySettings = LayerChromaKeySettings.DEFAULT
) {
    fun hasColorMatrixChanges(): Boolean {
        if (!enabled) return false
        val toneChanged = colorToneEnabled && (
            brightness != 0f ||
                contrast != 0f ||
                exposure != 0f ||
                gamma != 1.0f ||
                highlights != 0f ||
                shadows != 0f ||
                saturation != 0f ||
                vibrance != 0f ||
                temperature != 0f ||
                tint != 0f ||
                hue != 0f ||
                sharpness != 0f ||
                clarity != 0f
            )
        val rgbChanged = rgbEnabled && (rgbRed != 1.0f || rgbGreen != 1.0f || rgbBlue != 1.0f)
        return toneChanged || rgbChanged
    }

    fun hasAnyActiveAdjustment(): Boolean {
        if (!enabled) return false
        return hasColorMatrixChanges() ||
            opacity < 0.999f ||
            (blurEnabled && blurRadiusDp > 0.05f) ||
            (glowEnabled && glowIntensity > 0.5f) ||
            (dropShadowEnabled && dropShadowRadiusDp > 0.05f) ||
            (outlineEnabled && outlineWidthDp > 0.05f) ||
            chromaKey.enabled
    }

    fun clamped(): LayerGraphicAdjustment = copy(
        brightness = brightness.coerceIn(-100f, 100f),
        contrast = contrast.coerceIn(-100f, 100f),
        exposure = exposure.coerceIn(-100f, 100f),
        gamma = gamma.coerceIn(0.20f, 3.00f),
        highlights = highlights.coerceIn(-100f, 100f),
        shadows = shadows.coerceIn(-100f, 100f),
        saturation = saturation.coerceIn(-100f, 100f),
        vibrance = vibrance.coerceIn(-100f, 100f),
        temperature = temperature.coerceIn(-100f, 100f),
        tint = tint.coerceIn(-100f, 100f),
        hue = hue.coerceIn(-180f, 180f),
        rgbRed = rgbRed.coerceIn(0f, 2.0f),
        rgbGreen = rgbGreen.coerceIn(0f, 2.0f),
        rgbBlue = rgbBlue.coerceIn(0f, 2.0f),
        sharpness = sharpness.coerceIn(0f, 100f),
        clarity = clarity.coerceIn(-100f, 100f),
        opacity = opacity.coerceIn(0.05f, 1.0f),
        blurRadiusDp = blurRadiusDp.coerceIn(0f, 25f),
        glowIntensity = glowIntensity.coerceIn(0f, 100f),
        dropShadowRadiusDp = dropShadowRadiusDp.coerceIn(0f, 24f),
        dropShadowOpacity = dropShadowOpacity.coerceIn(0.1f, 1.0f),
        outlineWidthDp = outlineWidthDp.coerceIn(0f, 10f),
        chromaKey = chromaKey.clamped()
    )

    companion object {
        val ORIGINAL = LayerGraphicAdjustment()

        fun forBuiltInPreset(preset: GraphicPresetId): LayerGraphicAdjustment = when (preset) {
            GraphicPresetId.ORIGINAL -> ORIGINAL
            GraphicPresetId.BRIGHT -> LayerGraphicAdjustment(
                enabled = true,
                presetId = GraphicPresetId.BRIGHT,
                brightness = 18f,
                contrast = 8f,
                exposure = 14f,
                gamma = 1.12f,
                highlights = 12f,
                shadows = 20f,
                saturation = 6f,
                vibrance = 12f,
                temperature = 6f,
                clarity = 8f
            )
            GraphicPresetId.CINEMATIC -> LayerGraphicAdjustment(
                enabled = true,
                presetId = GraphicPresetId.CINEMATIC,
                brightness = -4f,
                contrast = 24f,
                exposure = -5f,
                gamma = 0.94f,
                highlights = -12f,
                shadows = -18f,
                saturation = -10f,
                vibrance = 14f,
                temperature = 14f,
                tint = -6f,
                sharpness = 18f,
                clarity = 22f,
                rgbRed = 1.05f,
                rgbGreen = 1.00f,
                rgbBlue = 0.94f
            )
            GraphicPresetId.HIGH_CONTRAST -> LayerGraphicAdjustment(
                enabled = true,
                presetId = GraphicPresetId.HIGH_CONTRAST,
                brightness = 2f,
                contrast = 45f,
                exposure = 4f,
                gamma = 0.92f,
                highlights = 18f,
                shadows = -28f,
                saturation = 12f,
                vibrance = 10f,
                sharpness = 35f,
                clarity = 38f
            )
            GraphicPresetId.VIBRANT -> LayerGraphicAdjustment(
                enabled = true,
                presetId = GraphicPresetId.VIBRANT,
                brightness = 6f,
                contrast = 14f,
                exposure = 6f,
                gamma = 1.04f,
                highlights = 10f,
                shadows = 8f,
                saturation = 28f,
                vibrance = 42f,
                temperature = 8f,
                sharpness = 15f,
                clarity = 16f
            )
            GraphicPresetId.NATURAL -> LayerGraphicAdjustment(
                enabled = true,
                presetId = GraphicPresetId.NATURAL,
                brightness = 4f,
                contrast = 8f,
                exposure = 2f,
                gamma = 1.02f,
                highlights = -4f,
                shadows = 10f,
                saturation = 4f,
                vibrance = 12f,
                temperature = 4f,
                tint = 2f,
                sharpness = 10f,
                clarity = 6f
            )
            GraphicPresetId.CUSTOM -> LayerGraphicAdjustment(
                enabled = true,
                presetId = GraphicPresetId.CUSTOM
            )
        }
    }
}

/**
 * User-saved reusable Graphic Adjustment preset persisted inside StudioWorkspaceState.
 */
data class CustomGraphicPreset(
    val id: String = "gpreset_${UUID.randomUUID().toString().take(8)}",
    val name: String,
    val adjustment: LayerGraphicAdjustment
)

/**
 * Backward-compatible JSON serialization helpers for per-layer graphic adjustments
 * and saved custom presets. Never breaks older saved JSON without these fields.
 */
object GraphicAdjustmentJson {

    fun toJson(adj: LayerGraphicAdjustment): JSONObject = JSONObject().apply {
        put("enabled", adj.enabled)
        put("presetId", adj.presetId.name)
        put("customPresetId", adj.customPresetId ?: "")
        put("colorToneEnabled", adj.colorToneEnabled)
        put("rgbEnabled", adj.rgbEnabled)
        put("brightness", adj.brightness.toDouble())
        put("contrast", adj.contrast.toDouble())
        put("exposure", adj.exposure.toDouble())
        put("gamma", adj.gamma.toDouble())
        put("highlights", adj.highlights.toDouble())
        put("shadows", adj.shadows.toDouble())
        put("saturation", adj.saturation.toDouble())
        put("vibrance", adj.vibrance.toDouble())
        put("temperature", adj.temperature.toDouble())
        put("tint", adj.tint.toDouble())
        put("hue", adj.hue.toDouble())
        put("rgbRed", adj.rgbRed.toDouble())
        put("rgbGreen", adj.rgbGreen.toDouble())
        put("rgbBlue", adj.rgbBlue.toDouble())
        put("sharpness", adj.sharpness.toDouble())
        put("clarity", adj.clarity.toDouble())
        put("opacity", adj.opacity.toDouble())
        put("blurEnabled", adj.blurEnabled)
        put("blurRadiusDp", adj.blurRadiusDp.toDouble())
        put("glowEnabled", adj.glowEnabled)
        put("glowIntensity", adj.glowIntensity.toDouble())
        put("glowColorHex", adj.glowColorHex)
        put("dropShadowEnabled", adj.dropShadowEnabled)
        put("dropShadowRadiusDp", adj.dropShadowRadiusDp.toDouble())
        put("dropShadowOpacity", adj.dropShadowOpacity.toDouble())
        put("outlineEnabled", adj.outlineEnabled)
        put("outlineWidthDp", adj.outlineWidthDp.toDouble())
        put("outlineColorHex", adj.outlineColorHex)
        put("chromaKey", chromaKeyToJson(adj.chromaKey))
    }

    fun chromaKeyToJson(ck: LayerChromaKeySettings): JSONObject = JSONObject().apply {
        put("enabled", ck.enabled)
        put("preset", ck.preset.name)
        put("keyColorHex", ck.keyColorHex)
        put("similarity", ck.similarity.toDouble())
        put("smoothness", ck.smoothness.toDouble())
        put("spillReduction", ck.spillReduction.toDouble())
        put("edgeCleanup", ck.edgeCleanup.toDouble())
        put("opacity", ck.opacity.toDouble())
    }

    fun chromaKeyFromJson(o: JSONObject?): LayerChromaKeySettings {
        if (o == null) return LayerChromaKeySettings.DEFAULT
        return LayerChromaKeySettings(
            enabled = o.optBoolean("enabled", false),
            preset = ChromaKeyPreset.fromName(o.optString("preset")),
            keyColorHex = o.optLong("keyColorHex", 0xFF00B140L),
            similarity = o.optDouble("similarity", 0.40).toFloat(),
            smoothness = o.optDouble("smoothness", 0.12).toFloat(),
            spillReduction = o.optDouble("spillReduction", 0.35).toFloat(),
            edgeCleanup = o.optDouble("edgeCleanup", 0.15).toFloat(),
            opacity = o.optDouble("opacity", 1.0).toFloat()
        ).clamped()
    }

    fun fromJson(o: JSONObject?, fallbackOpacity: Float = 1.0f): LayerGraphicAdjustment {
        if (o == null) return LayerGraphicAdjustment(opacity = fallbackOpacity.coerceIn(0.05f, 1.0f))
        return LayerGraphicAdjustment(
            enabled = o.optBoolean("enabled", true),
            presetId = GraphicPresetId.fromName(o.optString("presetId")),
            customPresetId = o.optString("customPresetId", "").ifBlank { null },
            colorToneEnabled = o.optBoolean("colorToneEnabled", true),
            rgbEnabled = o.optBoolean("rgbEnabled", true),
            brightness = o.optDouble("brightness", 0.0).toFloat(),
            contrast = o.optDouble("contrast", 0.0).toFloat(),
            exposure = o.optDouble("exposure", 0.0).toFloat(),
            gamma = o.optDouble("gamma", 1.0).toFloat(),
            highlights = o.optDouble("highlights", 0.0).toFloat(),
            shadows = o.optDouble("shadows", 0.0).toFloat(),
            saturation = o.optDouble("saturation", 0.0).toFloat(),
            vibrance = o.optDouble("vibrance", 0.0).toFloat(),
            temperature = o.optDouble("temperature", 0.0).toFloat(),
            tint = o.optDouble("tint", 0.0).toFloat(),
            hue = o.optDouble("hue", 0.0).toFloat(),
            rgbRed = o.optDouble("rgbRed", 1.0).toFloat(),
            rgbGreen = o.optDouble("rgbGreen", 1.0).toFloat(),
            rgbBlue = o.optDouble("rgbBlue", 1.0).toFloat(),
            sharpness = o.optDouble("sharpness", 0.0).toFloat(),
            clarity = o.optDouble("clarity", 0.0).toFloat(),
            opacity = o.optDouble("opacity", fallbackOpacity.toDouble()).toFloat(),
            blurEnabled = o.optBoolean("blurEnabled", false),
            blurRadiusDp = o.optDouble("blurRadiusDp", 0.0).toFloat(),
            glowEnabled = o.optBoolean("glowEnabled", false),
            glowIntensity = o.optDouble("glowIntensity", 0.0).toFloat(),
            glowColorHex = o.optLong("glowColorHex", 0xFF3B82F6L),
            dropShadowEnabled = o.optBoolean("dropShadowEnabled", false),
            dropShadowRadiusDp = o.optDouble("dropShadowRadiusDp", 0.0).toFloat(),
            dropShadowOpacity = o.optDouble("dropShadowOpacity", 0.65).toFloat(),
            outlineEnabled = o.optBoolean("outlineEnabled", false),
            outlineWidthDp = o.optDouble("outlineWidthDp", 0.0).toFloat(),
            outlineColorHex = o.optLong("outlineColorHex", 0xFF3B82F6L),
            chromaKey = chromaKeyFromJson(o.optJSONObject("chromaKey"))
        ).clamped()
    }

    fun presetsToJsonArray(presets: List<CustomGraphicPreset>): JSONArray = JSONArray().apply {
        presets.forEach { preset ->
            put(
                JSONObject().apply {
                    put("id", preset.id)
                    put("name", preset.name)
                    put("adjustment", toJson(preset.adjustment))
                }
            )
        }
    }

    fun presetsFromJsonArray(arr: JSONArray?): List<CustomGraphicPreset> {
        if (arr == null) return emptyList()
        val list = mutableListOf<CustomGraphicPreset>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            if (name.isBlank()) continue
            val id = obj.optString("id", "").ifBlank { "gpreset_$i" }
            val adj = fromJson(obj.optJSONObject("adjustment"))
            list.add(CustomGraphicPreset(id = id, name = name, adjustment = adj))
        }
        return list
    }
}
