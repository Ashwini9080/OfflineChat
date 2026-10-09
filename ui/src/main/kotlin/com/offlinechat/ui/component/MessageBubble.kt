package com.offlinechat.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinechat.core.model.Message
import com.offlinechat.core.model.MessageContent
import com.offlinechat.core.model.MessageStatus
import com.offlinechat.ui.theme.AccentEmerald
import com.offlinechat.ui.theme.AccentRose
import com.offlinechat.ui.theme.BubbleInbound
import com.offlinechat.ui.theme.BubbleInboundText
import com.offlinechat.ui.theme.BubbleOutbound
import com.offlinechat.ui.theme.BubbleOutboundText
import com.offlinechat.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    onRetryClick: ((Message) -> Unit)? = null
) {
    val isOutbound = message.isOutbound

    val bubbleShape = if (isOutbound) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

    val bubbleColor = if (isOutbound) BubbleOutbound else BubbleInbound
    val textColor = if (isOutbound) BubbleOutboundText else BubbleInboundText

    val alignment = if (isOutbound) Alignment.End else Alignment.Start

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 64.dp, max = 300.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                when (val content = message.content) {
                    is MessageContent.Text -> {
                        Text(
                            text = content.body,
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 20.sp
                        )
                    }
                    is MessageContent.File -> {
                        Text(
                            text = "📎 ${content.name} (${content.sizeBytes / 1024} KB)",
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    is MessageContent.GroupInvite -> {
                        Text(
                            text = "👥 Group Invite: ${content.groupName.ifBlank { content.groupId }}",
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = formatTimestamp(message.sentAt),
                        color = if (isOutbound) Color(0xFFA7F3D0) else TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp
                    )

                    if (isOutbound) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageStatusIcon(status = message.status)
                    }
                }
            }
        }

        // Error retry banner if failed
        AnimatedVisibility(
            visible = isOutbound && message.status == MessageStatus.FAILED,
            enter = fadeIn(tween(200)) + scaleIn(tween(200))
        ) {
            Row(
                modifier = Modifier
                    .padding(top = 2.dp, end = 4.dp)
                    .clickable { onRetryClick?.invoke(message) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Retry",
                    tint = AccentRose,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Failed to deliver. Tap to retry",
                    color = AccentRose,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun MessageStatusIcon(status: MessageStatus) {
    when (status) {
        MessageStatus.PENDING -> {
            Icon(
                imageVector = Icons.Default.AccessTime,
                contentDescription = "Pending",
                tint = Color(0xFFA7F3D0),
                modifier = Modifier.size(12.dp)
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent",
                tint = Color(0xFFA7F3D0),
                modifier = Modifier.size(12.dp)
            )
        }
        MessageStatus.ACKNOWLEDGED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Acknowledged",
                tint = Color(0xFF6EE7B7),
                modifier = Modifier.size(14.dp)
            )
        }
        MessageStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Failed",
                tint = AccentRose,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

private fun formatTimestamp(millis: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date(millis))
}
