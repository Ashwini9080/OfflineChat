package com.offlinechat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinechat.transport.api.PeerDevice
import com.offlinechat.ui.theme.AccentCyan
import com.offlinechat.ui.theme.AccentEmerald
import com.offlinechat.ui.theme.BgCardDark
import com.offlinechat.ui.theme.BgInputDark
import com.offlinechat.ui.theme.BorderSubtle
import com.offlinechat.ui.theme.TextMuted
import com.offlinechat.ui.theme.TextPrimary
import com.offlinechat.ui.theme.TextSecondary
import java.security.MessageDigest
import kotlin.math.abs

@Composable
fun TrustVerificationDialog(
    peer: PeerDevice,
    onConfirmTrust: (PeerDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val fingerprint = deriveFingerprintHex(peer.publicSigningKeyBytes)
    val visualEmoji = deriveVisualEmoji(peer.publicSigningKeyBytes)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCardDark,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF064E3B)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = AccentEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Verify Peer Identity",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "This is your first connection with \"${peer.displayName}\". Verify the security fingerprint below to ensure end-to-end cryptographic authenticity.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Start
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Visual emoji fingerprint
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgInputDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = visualEmoji,
                        fontSize = 24.sp,
                        letterSpacing = 4.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Hex fingerprint
                Text(
                    text = fingerprint,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = AccentCyan,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Device ID: ${peer.deviceId.take(16)}...",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmTrust(peer) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentEmerald,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Trust & Chat",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

private fun deriveFingerprintHex(keyBytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
    return digest.take(16).chunked(4).joinToString(" ") { chunk ->
        chunk.joinToString("") { "%02X".format(it) }
    }
}

private fun deriveVisualEmoji(keyBytes: ByteArray): String {
    val emojis = listOf(
        "⚡", "🛡️", "🔥", "💎", "⭐", "🚀", "🪐", "🔑", "🌊", "🌲",
        "🎯", "🧭", "⚓", "👑", "🔮", "🍀", "🦊", "🦅", "🦁", "🐉"
    )
    val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
    return (0..3).map { i ->
        val idx = abs(digest[i].toInt()) % emojis.size
        emojis[idx]
    }.joinToString(" ")
}
