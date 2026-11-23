package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.studio.CustomGraphicPreset
import com.example.studio.GraphicPresetId
import com.example.studio.LayerGraphicAdjustment
import com.example.studio.SceneLayer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald
import com.example.ui.theme.ZroLiveRed
import kotlin.math.abs
import kotlin.math.roundToInt

enum class GraphicCategoryTab(val label: String) {
    TONE("Light"),
    COLOR("Color"),
    RGB("RGB"),
    DETAIL("Detail"),
    EFFECTS("Effects")
}

private val EFFECT_COLOR_SWATCHES = listOf(
    0xFF3B82F6L, // Cobalt Blue
    0xFF22C55EL, // Emerald Green
    0xFFF59E0BL, // Amber
    0xFFEF4444L, // Live Red
    0xFFA855F7L, // Purple
    0xFF06B6D4L, // Cyan
    0xFFFFFFFFL, // White
    0xFF000000L  // Black
)

/**
 * Emits the Layer-by-Layer Graphic Adjustment dock as separate lightweight LazyColumn items
 * (Header, Toolbar, Presets, Sliders) for smooth scrolling and low memory overhead.
 */
fun LazyListScope.graphicAdjustmentDockItems(
    selectedLayer: SceneLayer?,
    otherLayersInScene: List<SceneLayer>,
    sceneLocked: Boolean,
    customPresets: List<CustomGraphicPreset>,
    canUndo: Boolean,
    canRedo: Boolean,
    beforePreviewActive: Boolean,
    activeCategory: GraphicCategoryTab,
    onSelectCategory: (GraphicCategoryTab) -> Unit,
    onToggleBeforePreview: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onUpdateAdjustmentLive: (String, (LayerGraphicAdjustment) -> LayerGraphicAdjustment) -> Unit,
    onCommitAdjustment: () -> Unit,
    onApplyBuiltInPreset: (String, GraphicPresetId) -> Unit,
    onApplyCustomPreset: (String, CustomGraphicPreset) -> Unit,
    onSaveCustomPreset: (String, LayerGraphicAdjustment) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    onResetSelectedLayer: (String) -> Unit,
    onCopyToLayer: (sourceLayerId: String, targetLayerId: String) -> Unit
) {
    if (selectedLayer == null) {
        item(key = "graphic_adj_empty") {
            GlassCard(modifier = Modifier.testTag("graphic_adj_empty_card")) {
                SectionHeader(
                    title = "Graphic Adjustment",
                    subtitle = "Select any layer on the canvas or in Sources to adjust its color, lighting, detail, and effects independently."
                )
            }
        }
        return
    }

    val adj = selectedLayer.graphicAdjustment.copy(opacity = selectedLayer.opacity)
    val editable = !sceneLocked && !selectedLayer.locked

    item(key = "graphic_adj_header") {
        GraphicAdjustmentHeaderCard(
            selectedLayer = selectedLayer,
            adj = adj,
            sceneLocked = sceneLocked,
            editable = editable,
            beforePreviewActive = beforePreviewActive,
            onToggleEnabled = { on ->
                onUpdateAdjustmentLive(selectedLayer.id) { it.copy(enabled = on) }
                onCommitAdjustment()
            }
        )
    }

    item(key = "graphic_adj_toolbar") {
        GraphicAdjustmentToolbarCard(
            selectedLayer = selectedLayer,
            otherLayersInScene = otherLayersInScene,
            editable = editable,
            canUndo = canUndo,
            canRedo = canRedo,
            beforePreviewActive = beforePreviewActive,
            onToggleBeforePreview = onToggleBeforePreview,
            onUndo = onUndo,
            onRedo = onRedo,
            onResetSelectedLayer = { onResetSelectedLayer(selectedLayer.id) },
            onCopyToLayer = onCopyToLayer
        )
    }

    item(key = "graphic_adj_presets") {
        GraphicAdjustmentPresetsCard(
            selectedLayer = selectedLayer,
            adj = adj,
            editable = editable,
            customPresets = customPresets,
            onApplyBuiltInPreset = { preset -> onApplyBuiltInPreset(selectedLayer.id, preset) },
            onApplyCustomPreset = { custom -> onApplyCustomPreset(selectedLayer.id, custom) },
            onSaveCustomPreset = onSaveCustomPreset,
            onDeleteCustomPreset = onDeleteCustomPreset
        )
    }

    item(key = "graphic_adj_sliders") {
        GraphicAdjustmentSlidersCard(
            selectedLayer = selectedLayer,
            adj = adj,
            editable = editable,
            activeCategory = activeCategory,
            onSelectCategory = onSelectCategory,
            onUpdateAdjustmentLive = onUpdateAdjustmentLive,
            onCommitAdjustment = onCommitAdjustment
        )
    }
}

@Composable
private fun GraphicAdjustmentHeaderCard(
    selectedLayer: SceneLayer,
    adj: LayerGraphicAdjustment,
    sceneLocked: Boolean,
    editable: Boolean,
    beforePreviewActive: Boolean,
    onToggleEnabled: (Boolean) -> Unit
) {
    GlassCard(modifier = Modifier.testTag("graphic_adjustment_panel")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = if (adj.enabled) ZroCobalt else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Graphic Adj. — ${selectedLayer.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = when {
                        sceneLocked -> "SCENE LOCKED — Unlock scene to adjust"
                        selectedLayer.locked -> "LAYER LOCKED — Unlock in Sources to edit"
                        beforePreviewActive -> "BEFORE PREVIEW — Showing unadjusted original"
                        else -> "Applies only to '${selectedLayer.name}' • Saved per layer"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!editable || beforePreviewActive) ZroAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = adj.enabled,
                enabled = editable,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.testTag("graphic_master_enable_switch")
            )
        }
    }
}

@Composable
private fun GraphicAdjustmentToolbarCard(
    selectedLayer: SceneLayer,
    otherLayersInScene: List<SceneLayer>,
    editable: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    beforePreviewActive: Boolean,
    onToggleBeforePreview: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onResetSelectedLayer: () -> Unit,
    onCopyToLayer: (sourceLayerId: String, targetLayerId: String) -> Unit
) {
    var showCopyLayerDialog by remember { mutableStateOf(false) }

    if (showCopyLayerDialog) {
        AlertDialog(
            onDismissRequest = { showCopyLayerDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Copy Adjustments to Layer", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (otherLayersInScene.isEmpty()) {
                        Text(
                            text = "No other layers in this scene to copy into.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Copy '${selectedLayer.name}' graphic adjustments onto another unlocked layer:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        otherLayersInScene.forEach { target ->
                            Surface(
                                onClick = {
                                    if (!target.locked) {
                                        onCopyToLayer(selectedLayer.id, target.id)
                                        showCopyLayerDialog = false
                                    }
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(
                                    1.dp,
                                    if (target.locked) ZroAmber.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("graphic_copy_target_${target.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(target.name, style = MaterialTheme.typography.labelLarge, color = Color.White)
                                        Text(
                                            text = if (target.locked) "${target.type.label} • Locked" else target.type.label,
                                            fontSize = 10.sp,
                                            color = if (target.locked) ZroAmber else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (!target.locked) {
                                        Text("COPY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ZroCobalt)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCopyLayerDialog = false }) { Text("Close") }
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GraphicToolChip(
            label = "Undo",
            icon = Icons.AutoMirrored.Rounded.Undo,
            enabled = canUndo,
            selected = false,
            onClick = onUndo,
            tag = "graphic_undo_btn"
        )
        GraphicToolChip(
            label = "Redo",
            icon = Icons.AutoMirrored.Rounded.Redo,
            enabled = canRedo,
            selected = false,
            onClick = onRedo,
            tag = "graphic_redo_btn"
        )
        GraphicToolChip(
            label = if (beforePreviewActive) "Before (ON)" else "Before/After",
            icon = Icons.Rounded.Compare,
            enabled = true,
            selected = beforePreviewActive,
            accent = ZroAmber,
            onClick = onToggleBeforePreview,
            tag = "graphic_before_after_btn"
        )
        GraphicToolChip(
            label = "Copy to Layer",
            icon = Icons.Rounded.ContentCopy,
            enabled = otherLayersInScene.isNotEmpty(),
            selected = false,
            onClick = { showCopyLayerDialog = true },
            tag = "graphic_copy_layer_btn"
        )
        GraphicToolChip(
            label = "Reset Layer",
            icon = Icons.Rounded.RestartAlt,
            enabled = editable,
            selected = false,
            accent = ZroLiveRed,
            onClick = onResetSelectedLayer,
            tag = "graphic_reset_layer_btn"
        )
    }
}

@Composable
private fun GraphicAdjustmentPresetsCard(
    selectedLayer: SceneLayer,
    adj: LayerGraphicAdjustment,
    editable: Boolean,
    customPresets: List<CustomGraphicPreset>,
    onApplyBuiltInPreset: (GraphicPresetId) -> Unit,
    onApplyCustomPreset: (CustomGraphicPreset) -> Unit,
    onSaveCustomPreset: (String, LayerGraphicAdjustment) -> Unit,
    onDeleteCustomPreset: (String) -> Unit
) {
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameDraft by remember { mutableStateOf("") }

    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Save Custom Graphic Preset", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Saves the current graphic adjustments from '${selectedLayer.name}' so you can reuse them on any layer or scene.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = presetNameDraft,
                        onValueChange = { presetNameDraft = it.take(28) },
                        label = { Text("Preset Name") },
                        placeholder = { Text("e.g. Warm Facecam") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("graphic_custom_preset_name_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = presetNameDraft.trim()
                        if (trimmed.isNotEmpty()) {
                            onSaveCustomPreset(trimmed, adj)
                            presetNameDraft = ""
                            showSavePresetDialog = false
                        }
                    },
                    enabled = presetNameDraft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ZroCobalt, contentColor = Color.White),
                    modifier = Modifier.testTag("graphic_save_preset_confirm_btn")
                ) {
                    Text("Save Preset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) { Text("Cancel") }
            }
        )
    }

    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Presets",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            OutlinedButton(
                onClick = {
                    presetNameDraft = ""
                    showSavePresetDialog = true
                },
                enabled = editable,
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier
                    .height(28.dp)
                    .testTag("graphic_save_custom_preset_btn")
            ) {
                Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save Custom", fontSize = 10.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GraphicPresetId.entries.forEach { preset ->
                val isSelected = adj.presetId == preset && (preset != GraphicPresetId.CUSTOM || adj.customPresetId == null)
                FilterChip(
                    selected = isSelected,
                    enabled = editable,
                    onClick = {
                        if (preset == GraphicPresetId.CUSTOM) {
                            presetNameDraft = ""
                            showSavePresetDialog = true
                        } else {
                            onApplyBuiltInPreset(preset)
                        }
                    },
                    label = { Text(preset.label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ZroCobalt.copy(alpha = 0.24f),
                        selectedLabelColor = Color.White
                    ),
                    modifier = Modifier.testTag("graphic_preset_${preset.name.lowercase()}")
                )
            }
        }

        if (customPresets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Saved Custom Presets",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                customPresets.forEach { custom ->
                    val active = adj.customPresetId == custom.id
                    Surface(
                        onClick = { if (editable) onApplyCustomPreset(custom) },
                        shape = RoundedCornerShape(6.dp),
                        color = if (active) ZroEmerald.copy(alpha = 0.20f) else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            1.dp,
                            if (active) ZroEmerald else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                        ),
                        modifier = Modifier.testTag("graphic_custom_preset_${custom.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = custom.name,
                                fontSize = 11.sp,
                                color = Color.White,
                                maxLines = 1
                            )
                            IconButton(
                                onClick = { onDeleteCustomPreset(custom.id) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Delete preset ${custom.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphicAdjustmentSlidersCard(
    selectedLayer: SceneLayer,
    adj: LayerGraphicAdjustment,
    editable: Boolean,
    activeCategory: GraphicCategoryTab,
    onSelectCategory: (GraphicCategoryTab) -> Unit,
    onUpdateAdjustmentLive: (String, (LayerGraphicAdjustment) -> LayerGraphicAdjustment) -> Unit,
    onCommitAdjustment: () -> Unit
) {
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GraphicCategoryTab.entries.forEach { cat ->
                val selected = activeCategory == cat
                Surface(
                    onClick = { onSelectCategory(cat) },
                    shape = RoundedCornerShape(5.dp),
                    color = if (selected) ZroCobalt.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        1.dp,
                        if (selected) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("graphic_cat_${cat.name.lowercase()}")
                ) {
                    Text(
                        text = cat.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(vertical = 6.dp, horizontal = 2.dp)
                            .fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val controlsEnabled = editable && adj.enabled

        when (activeCategory) {
            GraphicCategoryTab.TONE -> {
                GroupEnableRow(
                    title = "Light & Tone Grading",
                    checked = adj.colorToneEnabled,
                    enabled = controlsEnabled,
                    onCheckedChange = { en ->
                        onUpdateAdjustmentLive(selectedLayer.id) { it.copy(colorToneEnabled = en) }
                        onCommitAdjustment()
                    }
                )
                val toneOn = controlsEnabled && adj.colorToneEnabled
                GraphicSliderRow(
                    label = "Brightness",
                    valueText = formatSignedInt(adj.brightness),
                    value = adj.brightness,
                    defaultValue = 0f,
                    range = -100f..100f,
                    enabled = toneOn,
                    tag = "graphic_slider_brightness",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(brightness = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Contrast",
                    valueText = formatSignedInt(adj.contrast),
                    value = adj.contrast,
                    defaultValue = 0f,
                    range = -100f..100f,
                    enabled = toneOn,
                    tag = "graphic_slider_contrast",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(contrast = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Exposure",
                    valueText = formatSignedInt(adj.exposure),
                    value = adj.exposure,
                    defaultValue = 0f,
                    range = -100f..100f,
                    enabled = toneOn,
                    tag = "graphic_slider_exposure",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(exposure = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Gamma",
                    valueText = "%.2f".format(adj.gamma),
                    value = adj.gamma,
                    defaultValue = 1.0f,
                    range = 0.20f..3.00f,
                    enabled = toneOn,
                    tag = "graphic_slider_gamma",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(gamma = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Highlights",
                    valueText = formatSignedInt(adj.highlights),
                    value = adj.highlights,
                    defaultValue = 0f,
                    range = -100f..100f,
                    enabled = toneOn,
                    tag = "graphic_slider_highlights",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(highlights = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Shadows",
                    valueText = formatSignedInt(adj.shadows),
                    value = adj.shadows,
                    defaultValue = 0f,
                    range = -100f..100f,
                    enabled = toneOn,
                    tag = "graphic_slider_shadows",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(shadows = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
            }

            GraphicCategoryTab.COLOR -> {
                GroupEnableRow(
                    title = "Color & White Balance",
                    checked = adj.colorToneEnabled,
                    enabled = controlsEnabled,
                    onCheckedChange = { en ->
                        onUpdateAdjustmentLive(selectedLayer.id) { it.copy(colorToneEnabled = en) }
                        onCommitAdjustment()
                    }
                )
                val colorOn = controlsEnabled && adj.colorToneEnabled
                GraphicSliderRow(
                    label = "Saturation",
                    valueText = formatSignedInt(adj.saturation),
                    value = adj.saturation,
                    defaultValue = 0f,
                    range = -100f..100f,
                    enabled = colorOn,
                    tag = "graphic_slider_saturation",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(saturation = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Vibrance",
                    valueText = formatSignedInt(adj.vibrance),
                    value = adj.vibrance,
                    defaultValue = 0f,
                    range = -100f..100f,
                    enabled = colorOn,
                    tag = "graphic_slider_vibrance",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(vibrance = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Temperature",
                    valueText = formatSignedInt(adj.temperature),
                    value = adj.temperature,
                    defaultValue = 0f,
                    range = -100f..100f,
                    enabled = colorOn,
                    tag = "graphic_slider_temperature",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(temperature = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Tint",
                    valueText = formatSignedInt(adj.tint),
                    value = adj.tint,
                    defaultValue = 0f,
                    range = -100f..100f,
                    enabled = colorOn,
                    tag = "graphic_slider_tint",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(tint = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Hue",
                    valueText = "${formatSignedInt(adj.hue)}°",
                    value = adj.hue,
                    defaultValue = 0f,
                    range = -180f..180f,
                    enabled = colorOn,
                    tag = "graphic_slider_hue",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(hue = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
            }

            GraphicCategoryTab.RGB -> {
                GroupEnableRow(
                    title = "RGB Channel Balance",
                    checked = adj.rgbEnabled,
                    enabled = controlsEnabled,
                    onCheckedChange = { en ->
                        onUpdateAdjustmentLive(selectedLayer.id) { it.copy(rgbEnabled = en) }
                        onCommitAdjustment()
                    }
                )
                val rgbOn = controlsEnabled && adj.rgbEnabled
                GraphicSliderRow(
                    label = "Red (R)",
                    valueText = "${(adj.rgbRed * 100f).roundToInt()}%",
                    value = adj.rgbRed,
                    defaultValue = 1.0f,
                    range = 0f..2.0f,
                    enabled = rgbOn,
                    activeColor = Color(0xFFEF4444),
                    tag = "graphic_slider_rgb_red",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(rgbRed = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Green (G)",
                    valueText = "${(adj.rgbGreen * 100f).roundToInt()}%",
                    value = adj.rgbGreen,
                    defaultValue = 1.0f,
                    range = 0f..2.0f,
                    enabled = rgbOn,
                    activeColor = Color(0xFF22C55E),
                    tag = "graphic_slider_rgb_green",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(rgbGreen = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Blue (B)",
                    valueText = "${(adj.rgbBlue * 100f).roundToInt()}%",
                    value = adj.rgbBlue,
                    defaultValue = 1.0f,
                    range = 0f..2.0f,
                    enabled = rgbOn,
                    activeColor = Color(0xFF3B82F6),
                    tag = "graphic_slider_rgb_blue",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(rgbBlue = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
            }

            GraphicCategoryTab.DETAIL -> {
                val detailOn = controlsEnabled && adj.colorToneEnabled
                GraphicSliderRow(
                    label = "Sharpness",
                    valueText = "${adj.sharpness.roundToInt()}",
                    value = adj.sharpness,
                    defaultValue = 0f,
                    range = 0f..100f,
                    enabled = detailOn,
                    tag = "graphic_slider_sharpness",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(sharpness = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Clarity",
                    valueText = formatSignedInt(adj.clarity),
                    value = adj.clarity,
                    defaultValue = 0f,
                    range = -100f..100f,
                    enabled = detailOn,
                    tag = "graphic_slider_clarity",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(clarity = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
            }

            GraphicCategoryTab.EFFECTS -> {
                GraphicSliderRow(
                    label = "Opacity",
                    valueText = "${(adj.opacity * 100f).roundToInt()}%",
                    value = adj.opacity,
                    defaultValue = 1.0f,
                    range = 0.05f..1.0f,
                    enabled = controlsEnabled,
                    tag = "graphic_slider_opacity",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(opacity = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )

                Spacer(modifier = Modifier.height(6.dp))
                GroupEnableRow(
                    title = "Blur",
                    checked = adj.blurEnabled,
                    enabled = controlsEnabled,
                    tag = "graphic_toggle_blur",
                    onCheckedChange = { en ->
                        onUpdateAdjustmentLive(selectedLayer.id) {
                            it.copy(
                                blurEnabled = en,
                                blurRadiusDp = if (en && it.blurRadiusDp < 0.5f) 4f else it.blurRadiusDp,
                                presetId = GraphicPresetId.CUSTOM,
                                customPresetId = null
                            )
                        }
                        onCommitAdjustment()
                    }
                )
                GraphicSliderRow(
                    label = "Blur Radius",
                    valueText = "${"%.1f".format(adj.blurRadiusDp)} dp",
                    value = adj.blurRadiusDp,
                    defaultValue = 0f,
                    range = 0f..25f,
                    enabled = controlsEnabled && adj.blurEnabled,
                    tag = "graphic_slider_blur",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(blurRadiusDp = v, blurEnabled = v > 0.05f, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )

                Spacer(modifier = Modifier.height(6.dp))
                GroupEnableRow(
                    title = "Glow",
                    checked = adj.glowEnabled,
                    enabled = controlsEnabled,
                    tag = "graphic_toggle_glow",
                    onCheckedChange = { en ->
                        onUpdateAdjustmentLive(selectedLayer.id) {
                            it.copy(
                                glowEnabled = en,
                                glowIntensity = if (en && it.glowIntensity < 5f) 45f else it.glowIntensity,
                                presetId = GraphicPresetId.CUSTOM,
                                customPresetId = null
                            )
                        }
                        onCommitAdjustment()
                    }
                )
                GraphicSliderRow(
                    label = "Glow Intensity",
                    valueText = "${adj.glowIntensity.roundToInt()}%",
                    value = adj.glowIntensity,
                    defaultValue = 0f,
                    range = 0f..100f,
                    enabled = controlsEnabled && adj.glowEnabled,
                    tag = "graphic_slider_glow",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(glowIntensity = v, glowEnabled = v > 0.5f, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                if (adj.glowEnabled) {
                    ColorSwatchRow(
                        selectedHex = adj.glowColorHex,
                        enabled = controlsEnabled,
                        onSelectHex = { hex ->
                            onUpdateAdjustmentLive(selectedLayer.id) { it.copy(glowColorHex = hex, presetId = GraphicPresetId.CUSTOM, customPresetId = null) }
                            onCommitAdjustment()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                GroupEnableRow(
                    title = "Drop Shadow",
                    checked = adj.dropShadowEnabled,
                    enabled = controlsEnabled,
                    tag = "graphic_toggle_shadow",
                    onCheckedChange = { en ->
                        onUpdateAdjustmentLive(selectedLayer.id) {
                            it.copy(
                                dropShadowEnabled = en,
                                dropShadowRadiusDp = if (en && it.dropShadowRadiusDp < 1f) 8f else it.dropShadowRadiusDp,
                                presetId = GraphicPresetId.CUSTOM,
                                customPresetId = null
                            )
                        }
                        onCommitAdjustment()
                    }
                )
                GraphicSliderRow(
                    label = "Shadow Size",
                    valueText = "${"%.1f".format(adj.dropShadowRadiusDp)} dp",
                    value = adj.dropShadowRadiusDp,
                    defaultValue = 0f,
                    range = 0f..24f,
                    enabled = controlsEnabled && adj.dropShadowEnabled,
                    tag = "graphic_slider_shadow_radius",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(dropShadowRadiusDp = v, dropShadowEnabled = v > 0.05f, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                GraphicSliderRow(
                    label = "Shadow Opacity",
                    valueText = "${(adj.dropShadowOpacity * 100f).roundToInt()}%",
                    value = adj.dropShadowOpacity,
                    defaultValue = 0.65f,
                    range = 0.10f..1.0f,
                    enabled = controlsEnabled && adj.dropShadowEnabled,
                    tag = "graphic_slider_shadow_opacity",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(dropShadowOpacity = v, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )

                Spacer(modifier = Modifier.height(6.dp))
                GroupEnableRow(
                    title = "Outline",
                    checked = adj.outlineEnabled,
                    enabled = controlsEnabled,
                    tag = "graphic_toggle_outline",
                    onCheckedChange = { en ->
                        onUpdateAdjustmentLive(selectedLayer.id) {
                            it.copy(
                                outlineEnabled = en,
                                outlineWidthDp = if (en && it.outlineWidthDp < 0.5f) 2f else it.outlineWidthDp,
                                presetId = GraphicPresetId.CUSTOM,
                                customPresetId = null
                            )
                        }
                        onCommitAdjustment()
                    }
                )
                GraphicSliderRow(
                    label = "Outline Width",
                    valueText = "${"%.1f".format(adj.outlineWidthDp)} dp",
                    value = adj.outlineWidthDp,
                    defaultValue = 0f,
                    range = 0f..10f,
                    enabled = controlsEnabled && adj.outlineEnabled,
                    tag = "graphic_slider_outline",
                    onChange = { v -> onUpdateAdjustmentLive(selectedLayer.id) { it.copy(outlineWidthDp = v, outlineEnabled = v > 0.05f, presetId = GraphicPresetId.CUSTOM, customPresetId = null) } },
                    onFinished = onCommitAdjustment
                )
                if (adj.outlineEnabled) {
                    ColorSwatchRow(
                        selectedHex = adj.outlineColorHex,
                        enabled = controlsEnabled,
                        onSelectHex = { hex ->
                            onUpdateAdjustmentLive(selectedLayer.id) { it.copy(outlineColorHex = hex, presetId = GraphicPresetId.CUSTOM, customPresetId = null) }
                            onCommitAdjustment()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GraphicToolChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    selected: Boolean,
    accent: Color = ZroCobalt,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = RoundedCornerShape(6.dp),
        color = when {
            selected -> accent.copy(alpha = 0.24f)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            1.dp,
            when {
                selected -> accent
                !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            }
        ),
        modifier = Modifier.testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    selected -> accent
                    accent == ZroLiveRed -> ZroLiveRed
                    else -> Color.White
                },
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = label,
                fontSize = 11.sp,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    accent == ZroLiveRed -> ZroLiveRed
                    else -> Color.White
                }
            )
        }
    }
}

@Composable
private fun GroupEnableRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier
        )
    }
}

@Composable
private fun GraphicSliderRow(
    label: String,
    valueText: String,
    value: Float,
    defaultValue: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    activeColor: Color = ZroCobalt,
    tag: String,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    val isModified = abs(value - defaultValue) > 0.005f
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = if (isModified && enabled) activeColor else Color.White
                )
                IconButton(
                    onClick = {
                        onChange(defaultValue)
                        onFinished()
                    },
                    enabled = enabled && isModified,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("${tag}_reset")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = "Reset $label",
                        tint = if (enabled && isModified) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            enabled = enabled,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = activeColor, activeTrackColor = activeColor),
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun ColorSwatchRow(
    selectedHex: Long,
    enabled: Boolean,
    onSelectHex: (Long) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Color", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        EFFECT_COLOR_SWATCHES.forEach { hex ->
            val selected = selectedHex == hex
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(hex))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.25f),
                        shape = CircleShape
                    )
                    .clickable(enabled = enabled) { onSelectHex(hex) }
            )
        }
    }
}

private fun formatSignedInt(value: Float): String {
    val v = value.roundToInt()
    return if (v > 0) "+$v" else "$v"
}
