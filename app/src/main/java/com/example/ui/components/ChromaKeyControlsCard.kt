package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.studio.ChromaKeyPreset
import com.example.studio.ChromaKeyPreviewMode
import com.example.studio.ChromaKeyProcessor
import com.example.studio.GraphicPresetId
import com.example.studio.LayerChromaKeySettings
import com.example.studio.LayerGraphicAdjustment
import com.example.studio.LayerSourceType
import com.example.studio.SceneLayer
import com.example.studio.StudioMediaStore
import com.example.studio.WebLayerManager
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val CHROMA_KEY_SWATCHES = listOf(
    0xFF00B140L, // Studio Green Screen
    0xFF00FF00L, // Pure Chroma Green
    0xFF0047BBL, // Studio Blue Screen
    0xFF0000FFL, // Pure Chroma Blue
    0xFF00E5FFL, // Cyan Screen
    0xFFFF00FFL, // Magenta Key
    0xFFFFFFFFL, // White Luma Key
    0xFF000000L  // Black Key
)

/**
 * Per-layer Chroma Key (Green Screen Removal) control panel placed inside Graphic Adjustment.
 * Supports:
 * - Enable/Disable per layer
 * - Green Screen / Blue Screen / Custom presets
 * - Color swatches + fine RGB Color Picker + interactive Eyedropper color sampling from source preview
 * - Original / Chroma Key / Transparent Checkerboard preview modes
 * - Real-time Similarity, Smoothness, Spill Reduction, Edge Cleanup, Opacity controls
 * - Honest unsupported source notice for non-keyable layers (e.g. Text, Browser, Chat)
 */
@Composable
fun ChromaKeyControlsSection(
    selectedLayer: SceneLayer,
    adj: LayerGraphicAdjustment,
    editable: Boolean,
    controlsEnabled: Boolean,
    mirroredFrame: Bitmap? = null,
    onUpdateAdjustmentLive: (String, (LayerGraphicAdjustment) -> LayerGraphicAdjustment) -> Unit,
    onCommitAdjustment: () -> Unit
) {
    val context = LocalContext.current
    val supported = selectedLayer.type.supportsChromaKey()
    val chroma = adj.chromaKey
    var previewMode by remember(selectedLayer.id) { mutableStateOf(ChromaKeyPreviewMode.CHECKERBOARD) }
    var samplerArmed by remember(selectedLayer.id) { mutableStateOf(false) }

    if (!supported) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = ZroAmber.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, ZroAmber.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chroma_key_unsupported_notice")
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = "Chroma Key unsupported",
                    tint = ZroAmber,
                    modifier = Modifier.size(16.dp)
                )
                Column {
                    Text(
                        text = "Chroma Key Not Supported on ${selectedLayer.type.label}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ZroAmber
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Select a Website/Browser, Image, GIF, Logo, Video, Facecam/Camera, Background, or Gameplay layer to remove a Green, Blue, Black/Luma, Magenta, or Custom background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    // Load preview source bitmap (live WebView frame, real media bitmap, mirrored frame, or chroma reference card)
    var sourcePreviewBitmap by remember(selectedLayer.id, selectedLayer.mediaUri, selectedLayer.webUrl) { mutableStateOf<Bitmap?>(null) }
    var keyedPreviewBitmap by remember(selectedLayer.id, selectedLayer.mediaUri, selectedLayer.webUrl) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(selectedLayer.id, selectedLayer.mediaUri, selectedLayer.webUrl, mirroredFrame, chroma.preset) {
        val src = withContext(Dispatchers.IO) {
            when {
                selectedLayer.type == LayerSourceType.BROWSER_WEB -> {
                    val webBmp = WebLayerManager.getLatestFrame(selectedLayer.id)
                    if (webBmp != null && !webBmp.isRecycled && webBmp.width > 0 && webBmp.height > 0) {
                        runCatching { Bitmap.createScaledBitmap(webBmp, 320, 180, true) }.getOrNull()
                            ?: createReferenceChromaSample(chroma.keyColorHex)
                    } else {
                        createReferenceChromaSample(chroma.keyColorHex)
                    }
                }
                !selectedLayer.mediaUri.isNullOrBlank() -> {
                    StudioMediaStore.decodeScaledBitmap(
                        context = context,
                        uriString = selectedLayer.mediaUri,
                        isVideo = selectedLayer.type == LayerSourceType.VIDEO_OVERLAY,
                        maxDimensionPx = 320
                    ) ?: createReferenceChromaSample(chroma.keyColorHex)
                }
                mirroredFrame != null && !mirroredFrame.isRecycled -> {
                    runCatching { Bitmap.createScaledBitmap(mirroredFrame, 320, 180, true) }.getOrNull()
                        ?: createReferenceChromaSample(chroma.keyColorHex)
                }
                else -> createReferenceChromaSample(chroma.keyColorHex)
            }
        }
        sourcePreviewBitmap = src
    }

    LaunchedEffect(sourcePreviewBitmap, chroma) {
        val src = sourcePreviewBitmap ?: return@LaunchedEffect
        val out = withContext(Dispatchers.Default) {
            ChromaKeyProcessor.applyChromaKey(
                source = src,
                rawSettings = chroma.copy(enabled = true)
            )
        }
        keyedPreviewBitmap = out
    }

    fun updateChroma(transform: (LayerChromaKeySettings) -> LayerChromaKeySettings) {
        onUpdateAdjustmentLive(selectedLayer.id) { current ->
            current.copy(
                chromaKey = transform(current.chromaKey),
                presetId = GraphicPresetId.CUSTOM,
                customPresetId = null
            )
        }
    }

    // Enable / Disable row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "CHROMA KEY (GREEN SCREEN)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (chroma.enabled) ZroEmerald else Color.White
            )
            Text(
                text = "Applies only to '${selectedLayer.name}' — layers behind show through.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = chroma.enabled,
            enabled = controlsEnabled,
            onCheckedChange = { on ->
                updateChroma { it.copy(enabled = on) }
                onCommitAdjustment()
            },
            modifier = Modifier.testTag("chroma_key_enable_switch")
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Quick Presets: Green (#00FF00), Blue (#0000FF), Black / Luma (#000000), Magenta (#FF00FF), Custom
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ChromaKeyPreset.entries.forEach { preset ->
            val selected = chroma.preset == preset
            FilterChip(
                selected = selected,
                enabled = controlsEnabled,
                onClick = {
                    val nextSettings = if (preset == ChromaKeyPreset.CUSTOM) {
                        chroma.copy(enabled = true, preset = ChromaKeyPreset.CUSTOM)
                    } else {
                        LayerChromaKeySettings.forPreset(preset)
                    }
                    updateChroma { nextSettings }
                    onCommitAdjustment()
                },
                label = { Text(preset.label, fontSize = 10.sp, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ZroEmerald.copy(alpha = 0.22f),
                    selectedLabelColor = Color.White
                ),
                modifier = Modifier.testTag("chroma_preset_${preset.name.lowercase()}")
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Preview Mode selector + Eyedropper Color Sampler button
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChromaKeyPreviewMode.entries.forEach { mode ->
            val active = previewMode == mode
            Surface(
                onClick = { previewMode = mode },
                shape = RoundedCornerShape(5.dp),
                color = if (active) ZroCobalt.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, if (active) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier
                    .weight(1f)
                    .testTag("chroma_preview_mode_${mode.name.lowercase()}")
            ) {
                Text(
                    text = mode.label,
                    fontSize = 10.sp,
                    color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 5.dp, horizontal = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Surface(
            onClick = {
                if (controlsEnabled) {
                    samplerArmed = !samplerArmed
                    if (samplerArmed) previewMode = ChromaKeyPreviewMode.ORIGINAL
                }
            },
            shape = RoundedCornerShape(5.dp),
            color = if (samplerArmed) ZroAmber.copy(alpha = 0.24f) else MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, if (samplerArmed) ZroAmber else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            modifier = Modifier.testTag("chroma_eyedropper_btn")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Colorize,
                    contentDescription = "Sample key color from preview",
                    tint = if (samplerArmed) ZroAmber else Color.White,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (samplerArmed) "Tap Preview" else "Sample",
                    fontSize = 10.sp,
                    color = if (samplerArmed) ZroAmber else Color.White
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

    // Live Key Preview Box (Original / Keyed / Transparent Checkerboard + Tap-to-Sample Eyedropper)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(6.dp))
            .border(
                1.dp,
                if (samplerArmed) ZroAmber else MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                RoundedCornerShape(6.dp)
            )
            .pointerInput(samplerArmed, sourcePreviewBitmap, controlsEnabled) {
                if (!samplerArmed || !controlsEnabled) return@pointerInput
                detectTapGestures { offset ->
                    val src = sourcePreviewBitmap ?: return@detectTapGestures
                    val nx = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val ny = (offset.y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val sampledHex = ChromaKeyProcessor.sampleColorAt(src, nx, ny)
                    updateChroma {
                        it.copy(
                            enabled = true,
                            preset = ChromaKeyPreset.CUSTOM,
                            keyColorHex = sampledHex
                        )
                    }
                    onCommitAdjustment()
                    samplerArmed = false
                    previewMode = ChromaKeyPreviewMode.CHECKERBOARD
                }
            }
            .testTag("chroma_key_preview_box"),
        contentAlignment = Alignment.Center
    ) {
        when (previewMode) {
            ChromaKeyPreviewMode.CHECKERBOARD -> {
                TransparentCheckerboardBackground(modifier = Modifier.fillMaxSize())
            }
            ChromaKeyPreviewMode.CHROMA_KEY -> {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0E14)))
            }
            ChromaKeyPreviewMode.ORIGINAL -> {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0E14)))
            }
        }

        val displayBmp = if (previewMode == ChromaKeyPreviewMode.ORIGINAL || samplerArmed) {
            sourcePreviewBitmap
        } else {
            keyedPreviewBitmap ?: sourcePreviewBitmap
        }
        if (displayBmp != null) {
            Image(
                bitmap = displayBmp.asImageBitmap(),
                contentDescription = "Chroma key preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(5.dp),
            shape = RoundedCornerShape(4.dp),
            color = Color(0xCC0B0E14)
        ) {
            Text(
                text = if (samplerArmed) "TAP ANY BACKGROUND COLOR TO SAMPLE" else previewMode.label.uppercase(),
                fontSize = 9.sp,
                fontFamily = JetBrainsMonoFontFamily,
                color = if (samplerArmed) ZroAmber else Color.White,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Key Color Picker (Current Color Chip + Quick Swatches + Fine RGB Sliders)
    val keyRgb = chroma.keyColorHex.toInt()
    val keyR = (keyRgb shr 16) and 0xFF
    val keyG = (keyRgb shr 8) and 0xFF
    val keyB = keyRgb and 0xFF
    val hexString = String.format("#%02X%02X%02X", keyR, keyG, keyB)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(chroma.keyColorHex))
                    .border(1.dp, Color.White, RoundedCornerShape(4.dp))
            )
            Text(
                text = "Key Color $hexString",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetBrainsMonoFontFamily,
                color = Color.White
            )
        }
        Surface(
            onClick = {
                if (controlsEnabled) {
                    updateChroma { LayerChromaKeySettings.DEFAULT }
                    onCommitAdjustment()
                }
            },
            shape = RoundedCornerShape(5.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            modifier = Modifier.testTag("chroma_key_reset_btn")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = "Reset Chroma Key", tint = Color.White, modifier = Modifier.size(12.dp))
                Text("Reset Key", fontSize = 10.sp, color = Color.White)
            }
        }
    }

    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CHROMA_KEY_SWATCHES.forEach { hex ->
            val selected = (chroma.keyColorHex and 0x00FFFFFFL) == (hex and 0x00FFFFFFL)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(hex))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable(enabled = controlsEnabled) {
                        val preset = when (hex) {
                            0xFF00FF00L, 0xFF00B140L -> ChromaKeyPreset.GREEN_SCREEN
                            0xFF0000FFL, 0xFF0047BBL -> ChromaKeyPreset.BLUE_SCREEN
                            0xFF000000L -> ChromaKeyPreset.BLACK_LUMA
                            0xFFFF00FFL -> ChromaKeyPreset.MAGENTA_KEY
                            else -> ChromaKeyPreset.CUSTOM
                        }
                        updateChroma { it.copy(enabled = true, preset = preset, keyColorHex = hex) }
                        onCommitAdjustment()
                    }
            )
        }
    }

    Spacer(modifier = Modifier.height(6.dp))
    // Custom Hex #RRGGBB Input
    var hexDraft by remember(selectedLayer.id, hexString) { mutableStateOf(hexString) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = hexDraft,
            onValueChange = { raw ->
                val filtered = raw.uppercase().filter { it == '#' || it in '0'..'9' || it in 'A'..'F' }.take(7)
                hexDraft = filtered
            },
            label = { Text("Custom Hex (#RRGGBB)", fontSize = 10.sp) },
            singleLine = true,
            enabled = controlsEnabled,
            modifier = Modifier
                .weight(1f)
                .testTag("chroma_hex_input")
        )
        Button(
            onClick = {
                val clean = hexDraft.removePrefix("#").trim()
                if (clean.length == 6) {
                    val rgb = clean.toLongOrNull(16)
                    if (rgb != null) {
                        val fullHex = 0xFF000000L or rgb
                        updateChroma { it.copy(enabled = true, preset = ChromaKeyPreset.CUSTOM, keyColorHex = fullHex) }
                        onCommitAdjustment()
                    }
                }
            },
            enabled = controlsEnabled && hexDraft.removePrefix("#").length == 6,
            colors = ButtonDefaults.buttonColors(containerColor = ZroCobalt, contentColor = Color.White),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.testTag("chroma_hex_apply_btn")
        ) {
            Text("Apply Hex", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    Spacer(modifier = Modifier.height(6.dp))
    val keyControlsActive = controlsEnabled && chroma.enabled

    // Custom Color RGB fine-tuning sliders (compact)
    ChromaRgbMiniSlider("Key R", keyR, Color(0xFFEF4444), controlsEnabled) { newR ->
        val packed = (0xFFL shl 24) or (newR.toLong() shl 16) or (keyG.toLong() shl 8) or keyB.toLong()
        updateChroma { it.copy(preset = ChromaKeyPreset.CUSTOM, keyColorHex = packed) }
    }
    ChromaRgbMiniSlider("Key G", keyG, Color(0xFF22C55E), controlsEnabled) { newG ->
        val packed = (0xFFL shl 24) or (keyR.toLong() shl 16) or (newG.toLong() shl 8) or keyB.toLong()
        updateChroma { it.copy(preset = ChromaKeyPreset.CUSTOM, keyColorHex = packed) }
    }
    ChromaRgbMiniSlider("Key B", keyB, Color(0xFF3B82F6), controlsEnabled) { newB ->
        val packed = (0xFFL shl 24) or (keyR.toLong() shl 16) or (keyG.toLong() shl 8) or newB.toLong()
        updateChroma { it.copy(preset = ChromaKeyPreset.CUSTOM, keyColorHex = packed) }
    }

    Spacer(modifier = Modifier.height(6.dp))

    // Real-time Chroma Key sliders (0.00..1.00 normalized -> 0%..100% display)
    ChromaSliderRow(
        label = "Similarity",
        valueText = "${(chroma.similarity * 100f).roundToInt()}%",
        value = chroma.similarity,
        defaultValue = 0.40f,
        range = 0.0f..1.0f,
        enabled = keyControlsActive,
        tag = "chroma_slider_similarity",
        onChange = { v -> updateChroma { it.copy(similarity = v) } },
        onFinished = onCommitAdjustment
    )
    ChromaSliderRow(
        label = "Smoothness",
        valueText = "${(chroma.smoothness * 100f).roundToInt()}%",
        value = chroma.smoothness,
        defaultValue = 0.12f,
        range = 0.0f..1.0f,
        enabled = keyControlsActive,
        tag = "chroma_slider_smoothness",
        onChange = { v -> updateChroma { it.copy(smoothness = v) } },
        onFinished = onCommitAdjustment
    )
    ChromaSliderRow(
        label = "Spill Reduction",
        valueText = "${(chroma.spillReduction * 100f).roundToInt()}%",
        value = chroma.spillReduction,
        defaultValue = 0.35f,
        range = 0.0f..1.0f,
        enabled = keyControlsActive,
        tag = "chroma_slider_spill",
        onChange = { v -> updateChroma { it.copy(spillReduction = v) } },
        onFinished = onCommitAdjustment
    )
    ChromaSliderRow(
        label = "Edge Cleanup",
        valueText = "${(chroma.edgeCleanup * 100f).roundToInt()}%",
        value = chroma.edgeCleanup,
        defaultValue = 0.15f,
        range = 0.0f..1.0f,
        enabled = keyControlsActive,
        tag = "chroma_slider_edge",
        onChange = { v -> updateChroma { it.copy(edgeCleanup = v) } },
        onFinished = onCommitAdjustment
    )
    ChromaSliderRow(
        label = "Key Opacity",
        valueText = "${(chroma.opacity * 100f).roundToInt()}%",
        value = chroma.opacity,
        defaultValue = 1.0f,
        range = 0.05f..1.0f,
        enabled = keyControlsActive,
        tag = "chroma_slider_opacity",
        onChange = { v -> updateChroma { it.copy(opacity = v) } },
        onFinished = onCommitAdjustment
    )
}

@Composable
private fun ChromaRgbMiniSlider(
    label: String,
    value: Int,
    accent: Color,
    enabled: Boolean,
    onChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = JetBrainsMonoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
        )
        Text(
            text = value.toString().padStart(3, ' '),
            fontSize = 10.sp,
            fontFamily = JetBrainsMonoFontFamily,
            color = Color.White,
            modifier = Modifier.width(28.dp)
        )
    }
}

@Composable
private fun ChromaSliderRow(
    label: String,
    valueText: String,
    value: Float,
    defaultValue: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    tag: String,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    val isModified = abs(value - defaultValue) > 0.01f
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = if (isModified && enabled) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isModified && enabled) {
                    IconButton(
                        onClick = {
                            onChange(defaultValue)
                            onFinished()
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = "Reset $label",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = ZroEmerald,
                activeTrackColor = ZroEmerald
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .testTag(tag)
        )
    }
}

/**
 * Clean fallback reference sample (studio backdrop matching active key color + silhouette subject)
 * used ONLY in the Chroma Key tuning preview when a camera/gameplay layer has no live frame yet.
 */
private fun createReferenceChromaSample(keyColorHex: Long): Bitmap {
    val w = 240
    val h = 135
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = keyColorHex.toInt() }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

    val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5B89B.toInt() }
    val shirtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F2937.toInt() }
    val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3B82F6.toInt() }

    // Simple subject bust silhouette so user immediately sees background turn transparent
    canvas.drawCircle(w * 0.5f, h * 0.36f, h * 0.18f, subjectPaint)
    canvas.drawRoundRect(
        RectF(w * 0.30f, h * 0.56f, w * 0.70f, h.toFloat() + 12f),
        26f,
        26f,
        shirtPaint
    )
    canvas.drawRoundRect(
        RectF(w * 0.41f, h * 0.70f, w * 0.59f, h * 0.80f),
        6f,
        6f,
        accentPaint
    )
    return bmp
}
