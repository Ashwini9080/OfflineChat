package com.offlinechat.presentation.components

import androidx.compose.foundation.background
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
import com.offlinechat.domain.model.Conversation
import com.offlinechat.presentation.theme.AccentEmerald
import com.offlinechat.presentation.theme.TextMuted
import com.offlinechat.presentation.theme.TextPrimary
import com.offlinechat.presentation.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val avatarGradients = deriveGradient(conversation.peerId)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(avatarGradients)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = conversation.peerDisplayName.take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.peerDisplayName,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (conversation.lastActivityAt > 0L) {
                    Text(
                        text = formatTime(conversation.lastActivityAt),
                        color = if (conversation.unreadCount > 0) AccentEmerald else TextMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.lastMessage.ifBlank { "Tap to send an offline message" },
                    color = if (conversation.unreadCount > 0) TextPrimary else TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AccentEmerald)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = conversation.unreadCount.toString(),
                            color = Color.Black,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
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

private fun formatTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> "${diff / 3600_000}h"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}
