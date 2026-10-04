package com.offlinechat.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import com.offlinechat.presentation.theme.AccentCyan
import com.offlinechat.presentation.theme.AccentEmerald
import com.offlinechat.presentation.theme.AccentRose
import com.offlinechat.presentation.theme.BgCardDark
import com.offlinechat.presentation.theme.BorderSubtle
import com.offlinechat.presentation.theme.StatusOnline
import com.offlinechat.presentation.theme.TextMuted
import com.offlinechat.presentation.theme.TextPrimary
import com.offlinechat.presentation.theme.TextSecondary
import kotlin.math.abs

@Composable
fun PeerItem(
    peer: Peer,
    connectionState: PeerConnectionState = if (peer.isConnected) PeerConnectionState.Connected else PeerConnectionState.Idle,
    onConnectClick: (Peer) -> Unit,
    onCancelClick: (Peer) -> Unit = {},
    onOpenChatClick: (Peer) -> Unit = {},
    onDisconnectClick: (Peer) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isConnecting = connectionState is PeerConnectionState.Connecting ||
            connectionState is PeerConnectionState.Authenticating
    val isConnected = peer.isConnected || connectionState is PeerConnectionState.Connected

    val avatarGradients = deriveGradient(peer.deviceId)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgCardDark)
            .border(
                1.dp,
                if (isConnected) AccentEmerald.copy(alpha = 0.5f) else BorderSubtle,
                RoundedCornerShape(16.dp)
            )
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with deterministic gradient
                Box(
                    modifier = Modifier
                        .size(46.dp)
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
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    // Status Line
                    when {
                        isConnected -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(StatusOnline)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Connected",
                                    color = AccentEmerald,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        isConnecting -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    color = AccentCyan,
                                    strokeWidth = 1.5.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Status: Connecting...",
                                    color = AccentCyan,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        connectionState is PeerConnectionState.ConnectionLost -> {
                            Text(
                                text = "Status: Connection Lost",
                                color = AccentRose,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        connectionState is PeerConnectionState.ConnectionTimeout -> {
                            Text(
                                text = "Status: Connection Timeout",
                                color = AccentRose,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        connectionState is PeerConnectionState.ConnectionFailed -> {
                            Text(
                                text = "Status: Connection Failed",
                                color = AccentRose,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        connectionState is PeerConnectionState.ConnectionRejected -> {
                            Text(
                                text = "Status: Connection Rejected",
                                color = AccentRose,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        connectionState is PeerConnectionState.BluetoothDisabled -> {
                            Text(
                                text = "Status: Bluetooth Disabled",
                                color = Color(0xFFF59E0B),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        else -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Status: Available",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(8.dp))
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
                                    color = TextMuted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Spacer(modifier = Modifier.width(6.dp))
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
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Row conforming to Phase 4 spec
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    isConnected -> {
                        // After successful connection: [ Open Chat ] and [ Disconnect ]
                        OutlinedButton(
                            onClick = { onDisconnectClick(peer) },
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LinkOff,
                                contentDescription = null,
                                tint = AccentRose,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Disconnect",
                                color = TextPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { onOpenChatClick(peer) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentEmerald,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Open Chat",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    isConnecting -> {
                        // During connection: Status: Connecting... [ Cancel ]
                        OutlinedButton(
                            onClick = { onCancelClick(peer) },
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentRose.copy(alpha = 0.6f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = AccentRose,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Cancel",
                                color = AccentRose,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    else -> {
                        // Default Available or Failed: [ Connect ]
                        Button(
                            onClick = { onConnectClick(peer) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentEmerald,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
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
