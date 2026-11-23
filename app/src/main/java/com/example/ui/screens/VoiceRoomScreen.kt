package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.HeadsetMic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.AudioOutputRoute
import com.example.data.model.AudioSettings
import com.example.data.model.VoiceParticipantDoc
import com.example.data.model.VoiceRoomDoc
import com.example.ui.components.AudioLevelMeter
import com.example.ui.components.BrandGradientButton
import com.example.ui.components.GlassCard
import com.example.ui.components.ProSubpageTopBar
import com.example.ui.components.SectionHeader
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald
import com.example.ui.theme.ZroLiveRed
import com.example.ui.theme.ZroViolet

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceRoomScreen(
    isGuest: Boolean,
    isAuthLoading: Boolean,
    onSignInWithGoogle: () -> Unit,
    currentUserId: String,
    activeRoom: VoiceRoomDoc?,
    participants: List<VoiceParticipantDoc>,
    publicRooms: List<VoiceRoomDoc>,
    audioSettings: AudioSettings,
    selfStreamConsent: Boolean,
    teammateMutedMap: Map<String, Boolean>,
    teammateVolumeMap: Map<String, Float>,
    isLoading: Boolean,
    onCreateRoom: (name: String, isPrivate: Boolean, maxParticipants: Int) -> Unit,
    onJoinRoom: (roomCode: String) -> Unit,
    onLeaveRoom: () -> Unit,
    onToggleSelfMic: () -> Unit,
    onToggleSelfStreamConsent: (Boolean) -> Unit,
    onSelectAudioRoute: (AudioOutputRoute) -> Unit,
    onToggleTeammateMuted: (String) -> Unit,
    onChangeTeammateVolume: (String, Float) -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var newRoomName by remember { mutableStateOf("Ranked Squad Comms") }
    var createPrivate by remember { mutableStateOf(false) }
    var maxSlots by remember { mutableIntStateOf(6) }
    var joinCodeInput by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        if (onBack != null) {
            ProSubpageTopBar(
                title = "Voice Room",
                subtitle = activeRoom?.name ?: "Squad voice comms",
                onBack = onBack,
                backTestTag = "voice_room_back_button"
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            if (activeRoom != null) {
                // ACTIVE VOICE ROOM STAGE
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        accentBorder = ZroViolet
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(ZroEmerald)
                                    )
                                    Text(
                                        text = "VOICE CONNECTED",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = ZroEmerald
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                                    ) {
                                        Text(
                                            text = activeRoom.visibility,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = activeRoom.name,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = "Room ID: ${activeRoom.roomCode} • ${participants.size}/${activeRoom.maxParticipants} teammates",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = ZroCobalt
                                )
                            }

                            IconButton(
                                onClick = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "Join my Zro live Squad Voice Room '${activeRoom.name}' using Room ID: ${activeRoom.roomCode}"
                                        )
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Invite Teammates"))
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(ZroCobalt.copy(alpha = 0.16f))
                                    .testTag("invite_teammates_button")
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = "Invite Teammates", tint = ZroCobalt)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Unified Broadcasting Consent & Background Indicator
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, ZroCobalt.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Rounded.Sensors, contentDescription = null, tint = ZroCobalt, modifier = Modifier.size(20.dp))
                                    Column {
                                        Text(
                                            text = "Broadcast My Voice in Stream",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Stays running in background while you play games",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Switch(
                                    checked = selfStreamConsent,
                                    onCheckedChange = onToggleSelfStreamConsent
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Speaker / Headset / Bluetooth Route
                        Text("Audio Output Device", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AudioOutputRoute.entries.forEach { route ->
                                FilterChip(
                                    selected = audioSettings.outputRoute == route,
                                    onClick = { onSelectAudioRoute(route) },
                                    label = { Text(route.label) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Mic Mute & Leave Room Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onToggleSelfMic,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (audioSettings.micEnabled) ZroCobalt else ZroLiveRed
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("voice_room_mic_toggle")
                            ) {
                                Icon(
                                    imageVector = if (audioSettings.micEnabled) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (audioSettings.micEnabled) "Mic Active" else "Mic Muted")
                            }

                            OutlinedButton(
                                onClick = onLeaveRoom,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, ZroLiveRed),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("leave_voice_room_button")
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.ExitToApp, contentDescription = null, tint = ZroLiveRed)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Leave Room", color = Color(0xFFFCA5A5))
                            }
                        }
                    }
                }

                // Teammates Roster
                item {
                    SectionHeader(
                        title = "Squad Members (${participants.size})",
                        subtitle = "Individual teammate volumes, speaking indicators & stream consent"
                    )
                }

                items(participants, key = { it.userId }) { member ->
                    val isSelf = member.userId == currentUserId
                    val isLocallyMuted = teammateMutedMap[member.userId] ?: false
                    val localVol = teammateVolumeMap[member.userId] ?: 1.0f
                    val isSpeaking = member.speaking && !member.muted

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        accentBorder = if (isSpeaking) ZroEmerald else null
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(ZroCobalt.copy(alpha = 0.22f))
                                    .border(
                                        width = if (isSpeaking) 2.5.dp else 1.dp,
                                        color = if (isSpeaking) ZroEmerald else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!member.avatarUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = member.avatarUrl,
                                        contentDescription = member.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                } else {
                                    Text(
                                        text = member.displayName.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = if (isSelf) "${member.displayName} (You)" else member.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White
                                    )
                                    if (member.userId == activeRoom.hostUid) {
                                        Surface(shape = RoundedCornerShape(6.dp), color = ZroViolet.copy(alpha = 0.22f)) {
                                            Text(
                                                "HOST",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFC4B5FD),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SignalCellularAlt,
                                        contentDescription = "Connection",
                                        tint = when (member.connectionQuality) {
                                            "EXCELLENT", "GOOD" -> ZroEmerald
                                            "FAIR" -> ZroAmber
                                            else -> ZroLiveRed
                                        },
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (member.streamConsent) "Stream Consent: ON" else "Private Voice Only",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (member.streamConsent) ZroCobalt else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (!isSelf) {
                                IconButton(
                                    onClick = { onToggleTeammateMuted(member.userId) }
                                ) {
                                    Icon(
                                        imageVector = if (isLocallyMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                                        contentDescription = "Mute teammate",
                                        tint = if (isLocallyMuted) ZroLiveRed else Color.White
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = if (member.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                    contentDescription = "Mic state",
                                    tint = if (member.muted) ZroLiveRed else ZroEmerald
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        AudioLevelMeter(
                            level = (member.audioLevel / 100f).coerceIn(0f, 1f),
                            muted = member.muted || isLocallyMuted
                        )

                        if (!isSelf) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("Vol ${(localVol * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Slider(
                                    value = localVol,
                                    onValueChange = { onChangeTeammateVolume(member.userId, it) },
                                    valueRange = 0f..1.5f,
                                    enabled = !isLocallyMuted,
                                    colors = SliderDefaults.colors(thumbColor = ZroViolet, activeTrackColor = ZroViolet),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            } else {
                // LOBBY: JOIN BY ROOM CODE + CREATE ROOM + PUBLIC ROOMS
                item {
                    SectionHeader(
                        title = "Squad Voice Rooms",
                        subtitle = "Low-latency team comms that stay active while you play & stream"
                    )
                }

                // Join by Room ID Card
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), accentBorder = ZroCobalt) {
                        Text("Join with Room ID", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = joinCodeInput,
                                onValueChange = { joinCodeInput = it.uppercase().take(12) },
                                label = { Text("Room Code") },
                                placeholder = { Text("ZRO-4K9X") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("join_room_code_input")
                            )
                            Button(
                                onClick = { if (joinCodeInput.isNotBlank()) onJoinRoom(joinCodeInput) },
                                enabled = !isLoading && joinCodeInput.isNotBlank(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ZroCobalt),
                                modifier = Modifier
                                    .height(54.dp)
                                    .testTag("join_room_button")
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Text("Join")
                                }
                            }
                        }
                    }
                }

                // Create Voice Room Card
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), accentBorder = ZroViolet) {
                        Text("Create a Voice Room", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = newRoomName,
                            onValueChange = { newRoomName = it.take(60) },
                            label = { Text("Room Name") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("create_room_name_input")
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !createPrivate,
                                onClick = { createPrivate = false },
                                leadingIcon = { Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("Public Room") }
                            )
                            FilterChip(
                                selected = createPrivate,
                                onClick = { createPrivate = true },
                                leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("Private (Invite Code)") }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Max Teammates: $maxSlots", style = MaterialTheme.typography.titleSmall, color = Color.White)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(4, 6, 8, 12).forEach { cap ->
                                    FilterChip(
                                        selected = maxSlots == cap,
                                        onClick = { maxSlots = cap },
                                        label = { Text("$cap") }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        BrandGradientButton(
                            text = if (isLoading) "Creating Room…" else "Create & Enter Voice Room",
                            icon = Icons.Rounded.Add,
                            enabled = !isLoading,
                            onClick = { onCreateRoom(newRoomName, createPrivate, maxSlots) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("create_voice_room_button")
                        )
                    }
                }

                // Public Active Rooms List
                item {
                    SectionHeader(
                        title = "Public Active Rooms",
                        subtitle = "${publicRooms.size} open gaming lobbies"
                    )
                }

                if (publicRooms.isEmpty()) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Rounded.HeadsetMic, contentDescription = null, tint = ZroViolet, modifier = Modifier.size(28.dp))
                                Column {
                                    Text("No public rooms active right now", style = MaterialTheme.typography.titleSmall, color = Color.White)
                                    Text(
                                        "Create a room above and share its Room ID with your squad to start unified voice broadcasting.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(publicRooms, key = { it.roomCode }) { room ->
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(room.name, style = MaterialTheme.typography.titleMedium, color = Color.White)
                                    Text(
                                        text = "${room.roomCode} • ${room.memberUids.size}/${room.maxParticipants} online",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = ZroCobalt
                                    )
                                }
                                Button(
                                    onClick = { onJoinRoom(room.roomCode) },
                                    enabled = !isLoading,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ZroViolet)
                                ) {
                                    Text("Join")
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}
