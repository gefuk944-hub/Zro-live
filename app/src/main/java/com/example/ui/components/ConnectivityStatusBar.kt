package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StreamLifecycleState
import com.example.telemetry.AppConnectivitySnapshot
import com.example.telemetry.NetworkLinkType
import com.example.telemetry.ServerReachabilityState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ZroAmber
import com.example.ui.theme.ZroCobalt
import com.example.ui.theme.ZroEmerald
import com.example.ui.theme.ZroLiveRed

/**
 * Compact OBS-style telemetry strip displaying Network Status, Server Status, and Streaming Status
 * completely separately so offline, server-unreachable, and live states are never confused.
 */
@Composable
fun ConnectivityStatusBar(
    snapshot: AppConnectivitySnapshot,
    modifier: Modifier = Modifier,
    onCheckConnectivity: (() -> Unit)? = null,
    showStunDetail: Boolean = false
) {
    val netColor = when (snapshot.network.linkType) {
        NetworkLinkType.OFFLINE -> ZroLiveRed
        NetworkLinkType.WIFI, NetworkLinkType.ETHERNET -> ZroEmerald
        NetworkLinkType.CELLULAR -> ZroCobalt
    }
    val serverColor = when (snapshot.server.state) {
        ServerReachabilityState.REACHABLE -> ZroEmerald
        ServerReachabilityState.TESTING -> ZroAmber
        ServerReachabilityState.UNREACHABLE -> ZroLiveRed
        ServerReachabilityState.UNTESTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val streamColor = when (snapshot.streaming.state) {
        StreamLifecycleState.LIVE -> ZroLiveRed
        StreamLifecycleState.SENDING_VIDEO, StreamLifecycleState.CONNECTED -> ZroCobalt
        StreamLifecycleState.CONNECTING, StreamLifecycleState.RECONNECTING, StreamLifecycleState.PAUSED -> ZroAmber
        StreamLifecycleState.ERROR -> ZroLiveRed
        StreamLifecycleState.IDLE, StreamLifecycleState.READY -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("connectivity_status_bar")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TelemetryPill(
                    title = "NETWORK",
                    value = snapshot.network.linkType.label,
                    icon = if (snapshot.network.isOnline) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                    accent = netColor,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("status_pill_network")
                )
                TelemetryPill(
                    title = "SERVER",
                    value = when (snapshot.server.state) {
                        ServerReachabilityState.REACHABLE ->
                            if (snapshot.server.rtmpLatencyMs > 0L) "OK ${snapshot.server.rtmpLatencyMs}ms" else "Reachable"
                        else -> snapshot.server.state.label
                    },
                    icon = when (snapshot.server.state) {
                        ServerReachabilityState.REACHABLE -> Icons.Rounded.CloudDone
                        ServerReachabilityState.UNREACHABLE -> Icons.Rounded.CloudOff
                        else -> Icons.Rounded.CloudQueue
                    },
                    accent = serverColor,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("status_pill_server")
                )
                TelemetryPill(
                    title = "STREAM",
                    value = snapshot.streaming.label,
                    icon = Icons.Rounded.Sensors,
                    accent = streamColor,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("status_pill_streaming")
                )
            }

            if (showStunDetail || !snapshot.network.isOnline) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { m -> if (onCheckConnectivity != null) m.clickable(onClick = onCheckConnectivity) else m }
                        .padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val stunLabel = when (snapshot.server.stunReachable) {
                        true -> "STUN/ICE: OK (${snapshot.server.stunLatencyMs} ms) • ${snapshot.server.stunServerUsed}"
                        false -> "STUN/ICE: Unreachable (${snapshot.server.stunServerUsed})"
                        null -> "ICE: ${snapshot.iceConfig.primaryStunUri()}"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Dns,
                            contentDescription = "ICE STUN server",
                            tint = when (snapshot.server.stunReachable) {
                                true -> ZroEmerald
                                false -> ZroAmber
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (!snapshot.network.isOnline) {
                                "No internet detected — connect Wi-Fi or Mobile Data before starting live"
                            } else {
                                stunLabel
                            },
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = if (!snapshot.network.isOnline) ZroLiveRed else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (onCheckConnectivity != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Check ICE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ZroCobalt,
                            modifier = Modifier.testTag("connectivity_probe_ice_btn")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryPill(
    title: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Text(
                    text = title,
                    fontSize = 9.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = "$title status",
                    tint = accent,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = value,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
