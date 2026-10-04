package com.offlinechat.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinechat.ui.theme.AccentCyan
import com.offlinechat.ui.theme.AccentEmerald
import com.offlinechat.ui.theme.BgCardDarkHover
import com.offlinechat.ui.theme.TextMuted
import com.offlinechat.ui.theme.TextSecondary

@Composable
fun TransportStatusBar(
    isScanning: Boolean,
    isAdvertising: Boolean,
    connectedCount: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BgCardDarkHover)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Radio pulse indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isScanning || isAdvertising) AccentEmerald else TextMuted)
                    .alpha(if (isScanning) alphaAnim else 1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = when {
                    isScanning -> "Scanning nearby Bluetooth peers..."
                    isAdvertising -> "Advertising presence via BLE"
                    else -> "Offline mesh ready"
                },
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CellTower,
                contentDescription = null,
                tint = if (connectedCount > 0) AccentCyan else TextMuted,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (connectedCount > 0) "$connectedCount Connected" else "0 Active Links",
                color = if (connectedCount > 0) AccentCyan else TextMuted,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp
            )
        }
    }
}
