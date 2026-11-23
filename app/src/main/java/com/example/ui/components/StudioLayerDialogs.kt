package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.studio.SceneLayer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt

/**
 * Reusable dialog to rename any Studio layer/source cleanly.
 */
@Composable
fun RenameLayerDialog(
    initialName: String,
    layerTypeLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(initialName) { mutableStateOf(initialName) }
    val trimmed = text.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag("rename_layer_dialog"),
        title = {
            Text(
                text = "Rename Layer",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Source type: $layerTypeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 36) text = it },
                    label = { Text("Layer Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_layer_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (trimmed.isNotBlank()) onConfirm(trimmed)
                },
                enabled = trimmed.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ZroCobalt, contentColor = Color.White),
                modifier = Modifier.testTag("rename_layer_confirm_btn")
            ) {
                Text("Save Name")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Reusable dialog to edit the Screen Capture source name across Studio, Remote Control, and Live Broadcast.
 */
@Composable
fun RenameScreenCaptureDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(currentName) { mutableStateOf(currentName) }
    val trimmed = text.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag("rename_capture_dialog"),
        title = {
            Text(
                text = "Rename Screen Capture",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Updates your Screen Capture label across Studio scenes, Remote preview, and Broadcast controls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 36) text = it },
                    label = { Text("Screen Capture Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_capture_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (trimmed.isNotBlank()) onConfirm(trimmed)
                },
                enabled = trimmed.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ZroCobalt, contentColor = Color.White),
                modifier = Modifier.testTag("rename_capture_confirm_btn")
            ) {
                Text("Save Name")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog allowing users to select 2 or more unlocked layers in the active scene
 * and merge them into a single movable, resizable, croppable composite layer.
 */
@Composable
fun MergeLayersDialog(
    layers: List<SceneLayer>,
    preselectedLayerId: String?,
    onConfirmMerge: (List<String>, String) -> Unit,
    onDismiss: () -> Unit
) {
    val unlockedLayers = remember(layers) { layers.sortedByDescending { it.zIndex }.filterNot { it.locked } }
    val selectedIds = remember(layers, preselectedLayerId) {
        mutableStateListOf<String>().apply {
            if (preselectedLayerId != null && unlockedLayers.any { it.id == preselectedLayerId }) {
                add(preselectedLayerId)
            }
        }
    }
    var mergedName by remember { mutableStateOf("Merged Group") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag("merge_layers_dialog"),
        title = {
            Text(
                text = "Merge Layers",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Select 2 or more unlocked layers to combine into a single composite layer. You can unmerge or undo anytime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = mergedName,
                    onValueChange = { if (it.length <= 36) mergedName = it },
                    label = { Text("Merged Layer Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("merge_layers_name_input")
                )
                if (unlockedLayers.size < 2) {
                    Text(
                        text = "At least 2 unlocked layers are required in this scene to merge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ZroAmber
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        unlockedLayers.forEach { layer ->
                            val checked = layer.id in selectedIds
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (checked) ZroCobalt.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(
                                    1.dp,
                                    if (checked) ZroCobalt else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (checked) selectedIds.remove(layer.id) else selectedIds.add(layer.id)
                                    }
                                    .testTag("merge_pick_${layer.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { isChecked ->
                                            if (isChecked) {
                                                if (layer.id !in selectedIds) selectedIds.add(layer.id)
                                            } else {
                                                selectedIds.remove(layer.id)
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = ZroCobalt)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = layer.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = layer.type.label,
                                            fontSize = 10.sp,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmMerge(selectedIds.toList(), mergedName) },
                enabled = selectedIds.size >= 2,
                colors = ButtonDefaults.buttonColors(containerColor = ZroCobalt, contentColor = Color.White),
                modifier = Modifier.testTag("merge_layers_confirm_btn")
            ) {
                Text("Merge (${selectedIds.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
