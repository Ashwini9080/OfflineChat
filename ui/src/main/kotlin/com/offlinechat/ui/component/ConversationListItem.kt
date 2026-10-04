package com.offlinechat.ui.component

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.offlinechat.core.model.Conversation
import com.offlinechat.ui.theme.AccentEmerald
import com.offlinechat.ui.theme.TextMuted
import com.offlinechat.ui.theme.TextPrimary
import com.offlinechat.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun ConversationListItem(
    conversation: Conversation,
    displayName: String,
    lastMessagePreview: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val avatarColors = deriveAvatarGradient(conversation.id)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(avatarColors)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayName.take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Center content: Name & message snippet
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (conversation.lastActivityAt > 0L) {
                    Text(
                        text = formatRelativeTime(conversation.lastActivityAt),
                        color = if (conversation.unreadCount > 0) AccentEmerald else TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lastMessagePreview.ifBlank { "Tap to send offline message" },
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
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = conversation.unreadCount.toString(),
                            color = Color.Black,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

private fun deriveAvatarGradient(id: String): List<Color> {
    val hash = abs(id.hashCode())
    val palette = listOf(
        listOf(Color(0xFF10B981), Color(0xFF06B6D4)),
        listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)),
        listOf(Color(0xFF3B82F6), Color(0xFF6366F1)),
        listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
        listOf(Color(0xFF14B8A6), Color(0xFF3B82F6))
    )
    return palette[hash % palette.size]
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
