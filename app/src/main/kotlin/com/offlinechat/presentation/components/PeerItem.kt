package com.offlinechat.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.TransportType
import com.offlinechat.presentation.theme.AccentCyan
import com.offlinechat.presentation.theme.AccentEmerald
import com.offlinechat.presentation.theme.BgCardDark
import com.offlinechat.presentation.theme.BorderSubtle
import com.offlinechat.presentation.theme.TextMuted
import com.offlinechat.presentation.theme.TextPrimary
import com.offlinechat.presentation.theme.TextSecondary
import kotlin.math.abs

@Composable
fun PeerItem(
    peer: Peer,
    isConnecting: Boolean,
    onConnectClick: (Peer) -> Unit,
    modifier: Modifier = Modifier
) {
    val avatarGradients = deriveGradient(peer.deviceId)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgCardDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .clickable(enabled = !isConnecting) { onConnectClick(peer) }
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(avatarGradients)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peer.displayName.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = if (peer.transportType == TransportType.BLUETOOTH) Icons.Default.Bluetooth else Icons.Default.NetworkWifi
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = peer.transportType.displayName,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Icon(
                        imageVector = Icons.Default.SignalCellularAlt,
                        contentDescription = null,
                        tint = if (peer.rssi > -70) AccentEmerald else TextMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "${peer.rssi} dBm",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (peer.isConnected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF064E3B))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Connected",
                        tint = AccentEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Connected",
                        color = AccentEmerald,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
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
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = "Connect",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun deriveGradient(id: String): List<Color> {
    val hash = abs(id.hashCode())
    val palettes = listOf(
        listOf(Color(0xFF10B981), Color(0xFF06B6D4)),
        listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)),
        listOf(Color(0xFF3B82F6), Color(0xFF6366F1)),
        listOf(Color(0xFFF59E0B), Color(0xFFEF4444))
    )
    return palettes[hash % palettes.size]
}
