package com.offlinechat.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.presentation.components.MessageBubble
import com.offlinechat.presentation.theme.AccentCyan
import com.offlinechat.presentation.theme.AccentEmerald
import com.offlinechat.presentation.theme.AccentRose
import com.offlinechat.presentation.theme.BgCardDark
import com.offlinechat.presentation.theme.BgDeepDark
import com.offlinechat.presentation.theme.BgInputDark
import com.offlinechat.presentation.theme.BorderSubtle
import com.offlinechat.presentation.theme.StatusOnline
import com.offlinechat.presentation.theme.TextMuted
import com.offlinechat.presentation.theme.TextPrimary
import com.offlinechat.presentation.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    val isConnected = state.connectionState is PeerConnectionState.Connected
    val isConnecting = state.connectionState is PeerConnectionState.Connecting ||
            state.connectionState is PeerConnectionState.Authenticating ||
            state.isReconnecting

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissError()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = BgDeepDark,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgCardDark,
                    titleContentColor = TextPrimary
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = state.peerDisplayName.take(1).uppercase(),
                                color = AccentEmerald,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.peerDisplayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val statusColor = when (state.connectionState) {
                                    is PeerConnectionState.Connected -> StatusOnline
                                    is PeerConnectionState.Connecting, is PeerConnectionState.Authenticating -> Color(0xFFF59E0B)
                                    is PeerConnectionState.Disconnecting -> Color(0xFFF59E0B)
                                    is PeerConnectionState.ConnectionFailed, is PeerConnectionState.Failed,
                                    is PeerConnectionState.ConnectionRejected, is PeerConnectionState.ConnectionTimeout,
                                    is PeerConnectionState.ConnectionLost -> AccentRose
                                    is PeerConnectionState.BluetoothDisabled, is PeerConnectionState.PermissionRevoked -> Color(0xFFF59E0B)
                                    is PeerConnectionState.Idle, is PeerConnectionState.Disconnected -> TextMuted
                                }

                                val statusLabel = when (state.connectionState) {
                                    is PeerConnectionState.Connected -> "Connected"
                                    is PeerConnectionState.Connecting -> "Connecting..."
                                    is PeerConnectionState.Authenticating -> "Handshaking..."
                                    is PeerConnectionState.Disconnecting -> "Disconnecting..."
                                    is PeerConnectionState.ConnectionFailed -> "Connection Failed"
                                    is PeerConnectionState.ConnectionRejected -> "Connection Rejected"
                                    is PeerConnectionState.ConnectionTimeout -> "Connection Timeout"
                                    is PeerConnectionState.ConnectionLost -> "Connection Lost"
                                    is PeerConnectionState.BluetoothDisabled -> "Bluetooth Disabled"
                                    is PeerConnectionState.PermissionRevoked -> "Permission Revoked"
                                    is PeerConnectionState.Failed -> "Link failed"
                                    is PeerConnectionState.Idle, is PeerConnectionState.Disconnected -> "Disconnected"
                                }

                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = statusLabel,
                                    color = statusColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        // Reconnect action button when disconnected or failed
                        if (!isConnected) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1E293B))
                                    .clickable(enabled = !isConnecting) { viewModel.reconnect() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                if (isConnecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = AccentCyan,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Reconnect",
                                        tint = AccentCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isConnecting) "Linking" else "Connect",
                                    color = AccentCyan,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // End-to-end security banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0C1322))
                    .border(1.dp, BorderSubtle.copy(alpha = 0.4f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = AccentEmerald,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "End-to-End Encrypted via Hardware Keystore & AES-GCM",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp
                )
            }

            if (state.messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = state.peerDisplayName.take(1).uppercase(),
                                color = AccentEmerald,
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = state.peerDisplayName,
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isConnected) StatusOnline else AccentRose)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isConnected) "Connected" else "Disconnected",
                                color = if (isConnected) AccentEmerald else AccentRose,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        androidx.compose.material3.Card(
                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = BgCardDark),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "No messages yet.",
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (isConnected) {
                                        "Say hello! Messages travel directly device-to-device over Bluetooth with zero internet."
                                    } else {
                                        "Reconnect with ${state.peerDisplayName} to begin chatting offline."
                                    },
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            onRetryClick = { viewModel.retryMessage(it) }
                        )
                    }
                }
            }

            // Disconnected Notice Banner
            if (!isConnected) {
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFF3B1219)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentRose.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AccentRose)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Disconnected. Reconnect to continue chatting.",
                            color = Color(0xFFFECDD3),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.TextButton(
                            onClick = { viewModel.reconnect() }
                        ) {
                            Text(
                                text = "Reconnect",
                                color = AccentCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Input Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                color = BgCardDark,
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = if (isConnected) state.inputText else "",
                        onValueChange = { if (isConnected) viewModel.onInputTextChanged(it) },
                        enabled = isConnected,
                        placeholder = {
                            Text(
                                text = if (isConnected) "Type a message..." else "Message input disabled",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(22.dp)),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = BgInputDark,
                            unfocusedContainerColor = BgInputDark,
                            disabledContainerColor = BgInputDark.copy(alpha = 0.5f),
                            focusedBorderColor = AccentEmerald,
                            unfocusedBorderColor = BorderSubtle,
                            disabledBorderColor = BorderSubtle.copy(alpha = 0.4f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            disabledTextColor = TextMuted,
                            cursorColor = AccentEmerald
                        ),
                        maxLines = 4,
                        shape = RoundedCornerShape(22.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    val canSend = isConnected && state.inputText.isNotBlank() && !state.isSending

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (canSend) AccentEmerald else Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = AccentEmerald,
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = { viewModel.sendMessage() },
                                enabled = canSend
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = if (canSend) Color.Black else TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
