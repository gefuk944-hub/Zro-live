package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.data.model.StreamLifecycleState
import com.example.data.model.StreamRuntimeTelemetry
import com.example.data.model.StreamSessionDoc
import com.example.data.model.StreamSettings
import com.example.streaming.ZroStreamCoordinator
import com.example.telemetry.ActiveLiveSessionSnapshot
import com.example.telemetry.CrashLogEntry
import com.example.telemetry.SavedLiveConfigProfile
import com.example.ui.components.GlassCard
import com.example.ui.components.SectionHeader
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald
import com.example.ui.theme.ZroLiveRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LiveManagerFilter(val label: String) {
    ALL("All"),
    INTERRUPTED("Interrupted"),
    ENDED("Ended"),
    PROFILES("Profiles"),
    LOGS("Logs")
}

/**
 * Professional OBS-inspired Live Manager:
 * - Crash Recovery banner ("Reconnect to Previous Live" without creating duplicates)
 * - Create New Live & Save Live Profile actions
 * - Search + Status filter chips (All / Interrupted / Ended / Profiles / Diagnostics Logs)
 * - Previous / Saved Live Sessions with title, date/time, platform, status badge, Reconnect & Delete
 * - Saved Live Configuration Profiles (apply/delete)
 * - Secret-scrubbed Crash & Recovery Diagnostics Logs viewer
 */
@Composable
fun LiveManagerContent(
    streamSettings: StreamSettings,
    streamTelemetry: StreamRuntimeTelemetry,
    recoverableSession: ActiveLiveSessionSnapshot?,
    recentSessions: List<StreamSessionDoc>,
    savedProfiles: List<SavedLiveConfigProfile>,
    crashLogs: List<CrashLogEntry>,
    onReconnectPreviousLive: () -> Unit,
    onDismissRecovery: () -> Unit,
    onCreateNewLive: (String) -> Unit,
    onOpenLiveSetup: () -> Unit,
    onOpenFullScreenPreview: () -> Unit,
    onResumeSavedSession: (StreamSessionDoc) -> Unit,
    onDeleteSession: (String) -> Unit,
    onSaveCurrentProfile: (String) -> Unit,
    onApplyProfile: (SavedLiveConfigProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onClearCrashLogs: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(LiveManagerFilter.ALL) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newLiveTitle by remember { mutableStateOf("") }
    var showSaveProfileDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }

    val query = searchQuery.trim().lowercase()
    val filteredSessions = remember(recentSessions, query, activeFilter) {
        recentSessions.filter { s ->
            val matchesQuery = query.isBlank() ||
                s.title.lowercase().contains(query) ||
                s.platformLabel.lowercase().contains(query) ||
                s.resolutionLabel.lowercase().contains(query)
            val matchesFilter = when (activeFilter) {
                LiveManagerFilter.ALL -> true
                LiveManagerFilter.INTERRUPTED -> s.status.equals("INTERRUPTED", ignoreCase = true)
                LiveManagerFilter.ENDED -> s.status.equals("ENDED", ignoreCase = true)
                LiveManagerFilter.PROFILES, LiveManagerFilter.LOGS -> false
            }
            matchesQuery && matchesFilter
        }
    }
    val filteredProfiles = remember(savedProfiles, query) {
        savedProfiles.filter { p ->
            query.isBlank() ||
                p.name.lowercase().contains(query) ||
                p.title.lowercase().contains(query) ||
                p.platformLabel.lowercase().contains(query)
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Create New Live", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter a broadcast title to configure a fresh live stream.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newLiveTitle,
                        onValueChange = { newLiveTitle = it },
                        label = { Text("Broadcast Title") },
                        placeholder = { Text("e.g., Ranked Squad Stream") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_manager_new_title_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCreateDialog = false
                        onCreateNewLive(newLiveTitle)
                        newLiveTitle = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZroCobalt, contentColor = Color.White),
                    modifier = Modifier.testTag("live_manager_confirm_new_btn")
                ) {
                    Text("Configure Live")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showSaveProfileDialog) {
        AlertDialog(
            onDismissRequest = { showSaveProfileDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Save Live Configuration", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Saves your current title, RTMP URL, resolution (${streamSettings.resolution.shortLabel}), ${streamSettings.fps} FPS, bitrate, and active scene.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        label = { Text("Profile Name") },
                        placeholder = { Text("e.g., YouTube 1080p Gaming") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_manager_profile_name_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSaveProfileDialog = false
                        onSaveCurrentProfile(newProfileName)
                        newProfileName = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZroEmerald, contentColor = Color.Black),
                    modifier = Modifier.testTag("live_manager_confirm_profile_btn")
                ) {
                    Text("Save Profile")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveProfileDialog = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("live_manager_list"),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Crash / Disconnect Recovery Banner ("Reconnect to Previous Live")
        if (recoverableSession != null) {
            item {
                GlassCard(
                    accentBorder = ZroAmber,
                    modifier = Modifier.testTag("live_manager_reconnect_banner")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "INTERRUPTED LIVE DETECTED",
                                fontSize = 10.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = ZroAmber
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = recoverableSession.title.ifBlank { "Previous Live Broadcast" },
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${recoverableSession.platformLabel} • ${recoverableSession.fps} FPS • ${ZroStreamCoordinator.formatDuration(recoverableSession.durationSec)} elapsed",
                                fontSize = 11.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!recoverableSession.lastError.isNullOrBlank()) {
                                Text(
                                    text = recoverableSession.lastError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ZroAmber,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onReconnectPreviousLive,
                            colors = ButtonDefaults.buttonColors(containerColor = ZroEmerald, contentColor = Color.Black),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("live_manager_reconnect_btn")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reconnect to Previous Live", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onDismissRecovery,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("live_manager_dismiss_recovery_btn")
                        ) {
                            Text("Dismiss", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // 2. Primary Live Manager Toolbar (Create New Live / Save Profile / 16:9 Preview)
        item {
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(
                        title = "Live Manager",
                        subtitle = if (streamTelemetry.state == StreamLifecycleState.LIVE) {
                            "LIVE NOW • ${ZroStreamCoordinator.formatDuration(streamTelemetry.durationSec)}"
                        } else {
                            "${recentSessions.size} sessions • ${savedProfiles.size} saved profiles"
                        },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onOpenFullScreenPreview,
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("live_manager_preview_btn")
                    ) {
                        Icon(Icons.Rounded.Fullscreen, contentDescription = "16:9 Preview", modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("16:9", fontSize = 11.sp)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showCreateDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = ZroCobalt, contentColor = Color.White),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("live_manager_create_new_btn")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Create New Live", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { showSaveProfileDialog = true },
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("live_manager_save_profile_btn")
                    ) {
                        Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Save Profile", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search sessions, platform, or profiles…", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "Search", modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_manager_search_input")
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LiveManagerFilter.entries.forEach { f ->
                        val sel = activeFilter == f
                        FilterChip(
                            selected = sel,
                            onClick = { activeFilter = f },
                            label = { Text(f.label, fontSize = 10.sp, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ZroCobalt.copy(alpha = 0.22f),
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.testTag("live_manager_filter_${f.name.lowercase()}")
                        )
                    }
                }
            }
        }

        // 3. Saved Live Configuration Profiles Section
        if (activeFilter == LiveManagerFilter.PROFILES || (activeFilter == LiveManagerFilter.ALL && filteredProfiles.isNotEmpty())) {
            item {
                SectionHeader(
                    title = "Saved Live Profiles",
                    subtitle = "Apply saved RTMP destination, quality & scene in one tap"
                )
            }
            if (filteredProfiles.isEmpty()) {
                item {
                    GlassCard {
                        Text(
                            text = "No saved profiles yet. Tap 'Save Profile' to save your current RTMP setup and scene.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(filteredProfiles, key = { it.id }) { profile ->
                    GlassCard(
                        modifier = Modifier.testTag("live_profile_card_${profile.id}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${profile.title} • ${profile.platformLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${profile.fps} FPS • ${profile.bitrateKbps} kbps • ${profile.sceneSlotName}",
                                    fontSize = 10.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = Color(0xFF9CA3AF)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = { onApplyProfile(profile) },
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("apply_profile_${profile.id}")
                                ) {
                                    Text("Apply", fontSize = 11.sp)
                                }
                                IconButton(
                                    onClick = { onDeleteProfile(profile.id) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete profile",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Previous & Interrupted Live Sessions History
        if (activeFilter != LiveManagerFilter.PROFILES && activeFilter != LiveManagerFilter.LOGS) {
            item {
                SectionHeader(
                    title = "Previous Live Sessions",
                    subtitle = "Reconnect interrupted streams without duplicating broadcasts"
                )
            }
            if (filteredSessions.isEmpty()) {
                item {
                    GlassCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (query.isNotBlank()) "No sessions match '$searchQuery'." else "No previous live sessions recorded yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = onOpenLiveSetup,
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Setup", fontSize = 11.sp)
                            }
                        }
                    }
                }
            } else {
                items(filteredSessions, key = { it.id }) { session ->
                    val isInterrupted = session.status.equals("INTERRUPTED", ignoreCase = true)
                    val isLiveSession = session.status.equals("LIVE", ignoreCase = true)
                    val badgeColor = when {
                        isLiveSession -> ZroLiveRed
                        isInterrupted -> ZroAmber
                        else -> ZroEmerald
                    }
                    GlassCard(
                        accentBorder = if (isInterrupted) ZroAmber.copy(alpha = 0.7f) else null,
                        modifier = Modifier.testTag("live_session_card_${session.id}")
                    ) {
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
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = badgeColor.copy(alpha = 0.18f),
                                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.55f))
                                    ) {
                                        Text(
                                            text = session.status.uppercase(),
                                            fontSize = 9.sp,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            color = badgeColor,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                    Text(
                                        text = session.platformLabel.ifBlank { "RTMP Live" },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = session.title.ifBlank { "Live Broadcast" },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${formatSessionDate(session.startedAtEpochMs)} • ${session.resolutionLabel} ${session.fps}fps • ${ZroStreamCoordinator.formatDuration(session.durationSec)}",
                                    fontSize = 10.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = Color(0xFF9CA3AF)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Button(
                                    onClick = { onResumeSavedSession(session) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isInterrupted) ZroAmber else ZroCobalt,
                                        contentColor = if (isInterrupted) Color.Black else Color.White
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("resume_session_${session.id}")
                                ) {
                                    Icon(
                                        imageVector = if (isInterrupted) Icons.Rounded.Refresh else Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(if (isInterrupted) "Reconnect" else "Resume", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                                IconButton(
                                    onClick = { onDeleteSession(session.id) },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("delete_session_${session.id}")
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete session",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Diagnostics & Crash Logs Viewer
        if (activeFilter == LiveManagerFilter.LOGS) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(
                        title = "Crash & Network Diagnostics",
                        subtitle = "Stream keys are automatically redacted from logs",
                        modifier = Modifier.weight(1f)
                    )
                    if (crashLogs.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClearCrashLogs,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("live_manager_clear_logs_btn")
                        ) {
                            Text("Clear", fontSize = 11.sp)
                        }
                    }
                }
            }
            if (crashLogs.isEmpty()) {
                item {
                    GlassCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.BugReport, contentDescription = null, tint = ZroEmerald, modifier = Modifier.size(18.dp))
                            Text(
                                text = "No crashes or unexpected disconnects recorded.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(crashLogs) { entry ->
                    GlassCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = entry.category,
                                fontSize = 10.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = if (entry.category == "CRASH") ZroLiveRed else ZroAmber
                            )
                            Text(
                                text = formatSessionDate(entry.timestampMs),
                                fontSize = 10.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = entry.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

private fun formatSessionDate(epochMs: Long): String {
    if (epochMs <= 0L) return "Recent"
    return runCatching {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
    }.getOrDefault("Recent")
}
