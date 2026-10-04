package com.offlinechat.presentation.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.presentation.theme.AccentRose
import com.offlinechat.presentation.theme.BubbleInbound
import com.offlinechat.presentation.theme.BubbleInboundText
import com.offlinechat.presentation.theme.BubbleOutbound
import com.offlinechat.presentation.theme.BubbleOutboundText
import com.offlinechat.presentation.theme.TextMuted
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
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
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
                .widthIn(min = 60.dp, max = 290.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    text = message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 20.sp
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                        color = if (isOutbound) Color(0xFFA7F3D0) else TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp
                    )

                    if (isOutbound) {
                        Spacer(modifier = Modifier.width(4.dp))
                        when (message.status) {
                            MessageStatus.PENDING -> Icon(
                                imageVector = Icons.Default.AccessTime,
                                contentDescription = "Pending",
                                tint = Color(0xFFA7F3D0),
                                modifier = Modifier.size(11.dp)
                            )
                            MessageStatus.SENT -> Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Sent",
                                tint = Color(0xFFA7F3D0),
                                modifier = Modifier.size(12.dp)
                            )
                            MessageStatus.DELIVERED -> Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Delivered",
                                tint = Color(0xFF6EE7B7),
                                modifier = Modifier.size(13.dp)
                            )
                            MessageStatus.FAILED -> Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Failed",
                                tint = AccentRose,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = isOutbound && message.status == MessageStatus.FAILED) {
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
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Delivery failed. Tap to retry",
                    color = AccentRose,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
