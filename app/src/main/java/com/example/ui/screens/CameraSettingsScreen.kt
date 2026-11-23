package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.studio.CameraSourceMode
import com.example.studio.DualLinkState
import com.example.studio.DualPhoneTelemetry
import com.example.studio.FacecamShape
import com.example.studio.LayerSourceType
import com.example.studio.SceneLayer
import com.example.studio.StudioWorkspaceState
import com.example.ui.components.CameraXFacecamPreview
import com.example.ui.components.GlassCard
import com.example.ui.components.ProSubpageTopBar
import com.example.ui.components.SectionHeader
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald

/**
 * Dedicated Camera & Facecam Settings page.
 * - Explicit Camera ON / OFF switch (never turns on secretly without user action + permission)
 * - Source selector: Phone 2 Front, Phone 2 Rear, External Camera, Phone 1 Remote Camera
 * - Live CameraX preview when ON and permission granted
 * - Per-scene Facecam shape (Rounded / Square / Circle), position, size, border, shadow, opacity, lock
 */
@Composable
fun CameraSettingsScreen(
    workspace: StudioWorkspaceState,
    dualTelemetry: DualPhoneTelemetry,
    cameraPermissionGranted: Boolean,
    onBack: () -> Unit,
    onToggleCameraEnabled: (Boolean) -> Unit,
    onRequestCameraPermission: () -> Unit,
    onSelectCameraSource: (CameraSourceMode) -> Unit,
    onUpdateFacecamLayer: ((SceneLayer) -> SceneLayer) -> Unit,
    onToggleFacecamLock: (String) -> Unit,
    onSaveLayout: () -> Unit
) {
    val activeScene = workspace.activeScene
    val camLayer = activeScene.layers.firstOrNull { it.type == LayerSourceType.WEBCAM }
    val previewShape = when (camLayer?.facecamShape ?: FacecamShape.ROUNDED_RECT) {
        FacecamShape.CIRCLE -> CircleShape
        FacecamShape.SQUARE -> RectangleShape
        FacecamShape.ROUNDED_RECT -> RoundedCornerShape(10.dp)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ProSubpageTopBar(
            title = "Camera Settings",
            subtitle = "Facecam for ${activeScene.name}",
            onBack = onBack,
            backTestTag = "camera_settings_back_button",
            trailing = {
                OutlinedButton(
                    onClick = onSaveLayout,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("camera_save_layout_btn")
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save", style = MaterialTheme.typography.labelSmall)
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("camera_settings_list"),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Master Camera ON/OFF & Live Preview
            item {
                GlassCard(
                    accentBorder = if (workspace.cameraEnabled) ZroEmerald else null
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (workspace.cameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                                contentDescription = "Camera status",
                                tint = if (workspace.cameraEnabled) ZroEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = if (workspace.cameraEnabled) "Facecam ON" else "Facecam OFF",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White
                                )
                                Text(
                                    text = if (workspace.cameraEnabled) {
                                        "Active in ${activeScene.name} (hidden automatically during BRB)"
                                    } else {
                                        "Camera stays completely off until you enable it"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = workspace.cameraEnabled,
                            onCheckedChange = onToggleCameraEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ZroEmerald
                            ),
                            modifier = Modifier.testTag("camera_master_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    val borderDp = (camLayer?.borderWidthDp ?: 1.5f).dp
                    val borderCol = Color(camLayer?.borderColorHex ?: 0xFF3B82F6L)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF090B10))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val isLocalCam = workspace.cameraSourceMode == CameraSourceMode.PHONE2_FRONT ||
                            workspace.cameraSourceMode == CameraSourceMode.PHONE2_REAR
                        if (workspace.cameraEnabled && cameraPermissionGranted && isLocalCam) {
                            Box(
                                modifier = Modifier
                                    .size(
                                        width = if (camLayer?.facecamShape == FacecamShape.CIRCLE) 160.dp else 220.dp,
                                        height = 160.dp
                                    )
                                    .alpha(camLayer?.opacity ?: 1f)
                                    .clip(previewShape)
                                    .border(borderDp, borderCol, previewShape)
                            ) {
                                CameraXFacecamPreview(
                                    useFront = workspace.cameraSourceMode == CameraSourceMode.PHONE2_FRONT,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = if (workspace.cameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                                    contentDescription = null,
                                    tint = if (workspace.cameraEnabled) ZroAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = when {
                                        !workspace.cameraEnabled -> "Camera Preview Off"
                                        !cameraPermissionGranted -> "Camera permission required to preview"
                                        workspace.cameraSourceMode == CameraSourceMode.PHONE1_REMOTE -> {
                                            if (dualTelemetry.linkState == DualLinkState.CONNECTED) {
                                                "Phone 1 Remote Camera Paired (${dualTelemetry.connectedDeviceName})"
                                            } else {
                                                "Connect Phone 1 in Dual Phone Pairing to use Phone 1 Remote Camera"
                                            }
                                        }
                                        else -> "External USB Camera — connect a UVC-compatible camera supported by Android CameraX"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                if (workspace.cameraEnabled && !cameraPermissionGranted && isLocalCam) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(onClick = onRequestCameraPermission) {
                                        Text("Grant Camera Permission")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Camera Source Selector
            item {
                GlassCard {
                    SectionHeader(title = "Camera Source", subtitle = "Choose Phone 2 local, External USB, or Phone 1 remote camera")
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CameraSourceMode.entries.forEach { mode ->
                            val selected = workspace.cameraSourceMode == mode
                            Surface(
                                onClick = { onSelectCameraSource(mode) },
                                shape = RoundedCornerShape(6.dp),
                                color = if (selected) ZroCobalt.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("camera_source_${mode.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(mode.label, style = MaterialTheme.typography.labelLarge, color = Color.White)
                                        Text(
                                            mode.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (selected) {
                                        Text(
                                            "ACTIVE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            color = ZroCobalt
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Per-Scene Facecam Frame, Shape, Size, Border, Shadow, Opacity & Lock
            item {
                val locked = camLayer?.locked == true
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(
                            title = "Facecam Overlay (${activeScene.name})",
                            subtitle = "Saved independently per scene",
                            modifier = Modifier.weight(1f)
                        )
                        if (camLayer != null) {
                            OutlinedButton(
                                onClick = { onToggleFacecamLock(camLayer.id) },
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("camera_lock_toggle_btn")
                            ) {
                                Icon(
                                    imageVector = if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                    contentDescription = if (locked) "Unlock Facecam" else "Lock Facecam",
                                    tint = if (locked) ZroAmber else Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (locked) "Locked" else "Lock", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Crop Shape", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FacecamShape.entries.forEach { shape ->
                            val selected = (camLayer?.facecamShape ?: FacecamShape.ROUNDED_RECT) == shape
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateFacecamLayer { it.copy(facecamShape = shape) } },
                                label = { Text(shape.label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ZroCobalt.copy(alpha = 0.22f),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("facecam_shape_${shape.name.lowercase()}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    FacecamSliderRow(
                        label = "Width",
                        valueText = "${((camLayer?.width ?: 0.23f) * 100).toInt()}%",
                        value = camLayer?.width ?: 0.23f,
                        range = 0.12f..0.55f,
                        onChange = { w -> onUpdateFacecamLayer { it.copy(width = w) } }
                    )
                    FacecamSliderRow(
                        label = "Height",
                        valueText = "${((camLayer?.height ?: 0.33f) * 100).toInt()}%",
                        value = camLayer?.height ?: 0.33f,
                        range = 0.12f..0.60f,
                        onChange = { h -> onUpdateFacecamLayer { it.copy(height = h) } }
                    )
                    FacecamSliderRow(
                        label = "Border",
                        valueText = "${"%.1f".format(camLayer?.borderWidthDp ?: 1.5f)} dp",
                        value = camLayer?.borderWidthDp ?: 1.5f,
                        range = 0f..5f,
                        onChange = { b -> onUpdateFacecamLayer { it.copy(borderWidthDp = b) } }
                    )
                    FacecamSliderRow(
                        label = "Opacity",
                        valueText = "${((camLayer?.opacity ?: 1f) * 100).toInt()}%",
                        value = camLayer?.opacity ?: 1f,
                        range = 0.25f..1f,
                        onChange = { op -> onUpdateFacecamLayer { it.copy(opacity = op) } }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Drop Shadow", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        Switch(
                            checked = camLayer?.shadowEnabled ?: true,
                            onCheckedChange = { enabled -> onUpdateFacecamLayer { it.copy(shadowEnabled = enabled) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FacecamSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueText, style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFontFamily, color = Color.White)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = ZroCobalt, activeTrackColor = ZroCobalt)
        )
    }
}
