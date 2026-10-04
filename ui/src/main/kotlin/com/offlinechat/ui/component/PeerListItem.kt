package com.offlinechat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinechat.core.model.TransportType
import com.offlinechat.transport.api.PeerDevice
import com.offlinechat.ui.theme.AccentCyan
import com.offlinechat.ui.theme.AccentEmerald
import com.offlinechat.ui.theme.AccentPurple
import com.offlinechat.ui.theme.BgCardDark
import com.offlinechat.ui.theme.BorderSubtle
import com.offlinechat.ui.theme.TextMuted
import com.offlinechat.ui.theme.TextPrimary
import com.offlinechat.ui.theme.TextSecondary
import kotlin.math.abs

@Composable
fun PeerListItem(
    peer: PeerDevice,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnectClick: (PeerDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    val avatarColors = deriveAvatarGradient(peer.deviceId)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgCardDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .clickable(enabled = !isConnecting) { onConnectClick(peer) }
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(avatarColors)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peer.displayName.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = peer.displayName.ifBlank { "Nearby Device" },
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Transport icon
                    val (transportIcon, transportLabel) = when (peer.transport) {
                        TransportType.BLUETOOTH -> Icons.Default.Bluetooth to "Bluetooth"
                        TransportType.WIFI_DIRECT -> Icons.Default.NetworkWifi to "Wi-Fi Direct"
                        TransportType.WIFI_LOCAL -> Icons.Default.NetworkWifi to "Local Wi-Fi"
                    }

                    Icon(
                        imageVector = transportIcon,
                        contentDescription = transportLabel,
                        tint = AccentCyan,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = transportLabel,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // RSSI indicator
                    Icon(
                        imageVector = Icons.Default.SignalCellularAlt,
                        contentDescription = "Signal",
                        tint = if (peer.rssi > -70) AccentEmerald else TextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "${peer.rssi} dBm",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action button
            if (isConnected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF064E3B))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Connected",
                        tint = AccentEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Chat",
                        color = AccentEmerald,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = AccentCyan,
                    strokeWidth = 2.dp
                )
            } else {
                Button(
                    onClick = { onConnectClick(peer) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentEmerald,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(
                        text = "Connect",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun deriveAvatarGradient(deviceId: String): List<Color> {
    val hash = abs(deviceId.hashCode())
    val palette = listOf(
        listOf(Color(0xFF10B981), Color(0xFF06B6D4)),
        listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)),
        listOf(Color(0xFF3B82F6), Color(0xFF6366F1)),
        listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
        listOf(Color(0xFF14B8A6), Color(0xFF3B82F6))
    )
    return palette[hash % palette.size]
}
