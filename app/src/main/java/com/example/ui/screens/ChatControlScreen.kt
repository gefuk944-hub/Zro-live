package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.YouTubeChatMessage
import com.example.data.model.YouTubeChatState
import com.example.studio.StudioWorkspaceState
import com.example.ui.components.GlassCard
import com.example.ui.components.LiveStatusBadge
import com.example.ui.components.ProSubpageTopBar
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald

/**
 * Dedicated Live Chat Control page with:
 * - ProSubpageTopBar Back button returning cleanly to previous screen without losing state
 * - Compact live preview + broadcast status bar at top
 * - Chat messages with Pin / Reply / Send / Text Size / Overlay toggle / Refresh
 */
@Composable
fun ChatControlScreen(
    chatState: YouTubeChatState,
    workspace: StudioWorkspaceState,
    streamTelemetry: StreamRuntimeTelemetry,
    mirroredFrame: Bitmap?,
    onBack: () -> Unit,
    onConnectChat: () -> Unit,
    onRefreshChat: () -> Unit,
    onSendChatMessage: (String) -> Unit,
    onPinChatMessage: (YouTubeChatMessage) -> Unit,
    onUnpinChatMessage: () -> Unit,
    onSetChatTextSize: (Float) -> Unit,
    onSetChatOverlayEnabled: (Boolean) -> Unit
) {
    var draftMessage by remember { mutableStateOf("") }
    var showDisplayOptions by remember { mutableStateOf(false) }

    val connectedMessages = (chatState as? YouTubeChatState.Connected)?.messages.orEmpty()
    val viewerCount = (chatState as? YouTubeChatState.Connected)?.concurrentViewers ?: streamTelemetry.concurrentViewers

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ProSubpageTopBar(
            title = "Live Chat",
            subtitle = when (chatState) {
                is YouTubeChatState.Connected -> "${connectedMessages.size} messages • ${viewerCount ?: 0} viewers"
                is YouTubeChatState.Connecting -> "Connecting to YouTube Live Chat…"
                is YouTubeChatState.Error -> "Chat disconnected"
                YouTubeChatState.Disconnected -> "Not connected"
            },
            onBack = onBack,
            backTestTag = "chat_back_button",
            trailing = {
                IconButton(onClick = onRefreshChat, modifier = Modifier.testTag("chat_refresh_button")) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh chat", tint = Color.White)
                }
            }
        )

        // Compact live status + mini preview bar at top
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 72.dp, height = 42.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF0A0D13)),
                    contentAlignment = Alignment.Center
                ) {
                    if (mirroredFrame != null && !workspace.brbActive) {
                        Image(
                            bitmap = mirroredFrame.asImageBitmap(),
                            contentDescription = "Live stream mini preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = if (workspace.brbActive) "BRB" else workspace.activeScene.slot.shortLabel,
                            fontSize = 9.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = if (workspace.brbActive) ZroAmber else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LiveStatusBadge(
                            isLive = streamTelemetry.state == StreamLifecycleState.LIVE,
                            isPaused = streamTelemetry.state == StreamLifecycleState.PAUSED,
                            isReconnecting = streamTelemetry.state == StreamLifecycleState.RECONNECTING,
                            brbActive = workspace.brbActive
                        )
                        Text(
                            text = workspace.activeScene.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${streamTelemetry.actualBitrateKbps} kbps • ${streamTelemetry.actualFps} FPS • Overlay ${if (workspace.chatOverlayEnabled) "ON" else "OFF"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = { showDisplayOptions = !showDisplayOptions },
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(if (showDisplayOptions) "Hide" else "Display", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (showDisplayOptions) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show Chat on Stream Overlay", style = MaterialTheme.typography.labelLarge, color = Color.White)
                    Switch(
                        checked = workspace.chatOverlayEnabled,
                        onCheckedChange = onSetChatOverlayEnabled
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Chat Text Size", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${workspace.chatTextSizeSp.toInt()} sp", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFontFamily, color = Color.White)
                }
                Slider(
                    value = workspace.chatTextSizeSp,
                    onValueChange = onSetChatTextSize,
                    valueRange = 10f..22f,
                    colors = SliderDefaults.colors(thumbColor = ZroCobalt, activeTrackColor = ZroCobalt)
                )
            }
        }

        // Pinned message bar
        if (!workspace.pinnedChatText.isNullOrBlank()) {
            Surface(
                color = ZroAmber.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, ZroAmber.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = ZroAmber, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${workspace.pinnedChatAuthor.orEmpty()}: ${workspace.pinnedChatText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onUnpinChatMessage, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Unpin", tint = ZroAmber, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // Messages Feed
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("chat_message_list"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (connectedMessages.isEmpty()) {
                item {
                    GlassCard {
                        Text(
                            text = when (chatState) {
                                is YouTubeChatState.Error -> chatState.message
                                is YouTubeChatState.Connecting -> "Connecting to YouTube Live Chat…"
                                is YouTubeChatState.Connected -> "Connected. Waiting for viewer messages…"
                                YouTubeChatState.Disconnected -> "Connect your YouTube Live Video ID & API Key in Live Controls to stream real live chat messages."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onConnectChat,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.testTag("chat_connect_button")
                        ) {
                            Text("Connect Live Chat")
                        }
                    }
                }
            } else {
                items(connectedMessages, key = { it.id }) { msg ->
                    val isPinned = workspace.pinnedChatMessageId == msg.id
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            1.dp,
                            if (isPinned) ZroAmber.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = msg.authorName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (msg.isModerator) ZroEmerald else ZroCobalt
                                )
                                Text(
                                    text = msg.messageText,
                                    fontSize = workspace.chatTextSizeSp.sp,
                                    color = Color.White
                                )
                            }
                            IconButton(
                                onClick = { draftMessage = "@${msg.authorName} " },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = "Reply to ${msg.authorName}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { if (isPinned) onUnpinChatMessage() else onPinChatMessage(msg) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.PushPin,
                                    contentDescription = if (isPinned) "Unpin" else "Pin",
                                    tint = if (isPinned) ZroAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Message Composer Bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draftMessage,
                    onValueChange = { draftMessage = it },
                    placeholder = { Text("Send message to live chat…") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_composer_input")
                )
                Surface(
                    onClick = {
                        val trimmed = draftMessage.trim()
                        if (trimmed.isNotEmpty()) {
                            onSendChatMessage(trimmed)
                            draftMessage = ""
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = ZroCobalt,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("chat_send_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send chat", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
