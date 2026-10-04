package com.offlinechat.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.offlinechat.domain.model.Peer
import com.offlinechat.presentation.theme.AccentCyan
import com.offlinechat.presentation.theme.AccentEmerald
import com.offlinechat.presentation.theme.BgCardDark
import com.offlinechat.presentation.theme.BgInputDark
import com.offlinechat.presentation.theme.BorderSubtle
import com.offlinechat.presentation.theme.TextMuted
import com.offlinechat.presentation.theme.TextPrimary
import com.offlinechat.presentation.theme.TextSecondary
import java.security.MessageDigest
import kotlin.math.abs

@Composable
fun SecurityDialog(
    peer: Peer,
    onConfirmTrust: (Peer) -> Unit,
    onDismiss: () -> Unit
) {
    val fingerprint = deriveFingerprint(peer.publicKeyBytes)
    val emojis = deriveVisualEmojis(peer.publicKeyBytes)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCardDark,
        shape = RoundedCornerShape(18.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF064E3B)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = AccentEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Verify Peer Fingerprint",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Confirm security fingerprint with \"${peer.displayName}\" to ensure end-to-end cryptographic authenticity (TOFU).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Start
                )

                Spacer(modifier = Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(BgInputDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = emojis,
                        fontSize = 22.sp,
                        letterSpacing = 4.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = fingerprint,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = AccentCyan,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Device ID: ${peer.deviceId.take(14)}...",
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
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Trust & Connect", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

private fun deriveFingerprint(key: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key)
    return digest.take(12).chunked(4).joinToString(" ") { chunk ->
        chunk.joinToString("") { "%02X".format(it) }
    }
}

private fun deriveVisualEmojis(key: ByteArray): String {
    val emojis = listOf("🛡️", "⚡", "💎", "⭐", "🚀", "🪐", "🔑", "🌊", "🌲", "👑")
    val digest = MessageDigest.getInstance("SHA-256").digest(key)
    return (0..3).map { i ->
        val idx = abs(digest[i].toInt()) % emojis.size
        emojis[idx]
    }.joinToString(" ")
}
