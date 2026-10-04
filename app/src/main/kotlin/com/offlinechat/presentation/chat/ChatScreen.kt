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
                                    is PeerConnectionState.Failed -> AccentRose
                                    is PeerConnectionState.Disconnected -> TextMuted
                                }

                                val statusLabel = when (state.connectionState) {
                                    is PeerConnectionState.Connected -> "Direct RFCOMM Active"
                                    is PeerConnectionState.Connecting -> "Connecting link..."
                                    is PeerConnectionState.Authenticating -> "Handshaking..."
                                    is PeerConnectionState.Failed -> "Link failed"
                                    is PeerConnectionState.Disconnected -> "Offline channel"
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Offline P2P Channel",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Messages are sent directly device-to-device with authenticated encryption. Zero internet, zero cloud servers.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
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
                        value = state.inputText,
                        onValueChange = { viewModel.onInputTextChanged(it) },
                        placeholder = {
                            Text("Type an offline message...", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(22.dp)),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = BgInputDark,
                            unfocusedContainerColor = BgInputDark,
                            focusedBorderColor = AccentEmerald,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = AccentEmerald
                        ),
                        maxLines = 4,
                        shape = RoundedCornerShape(22.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (state.inputText.isNotBlank() && !state.isSending) AccentEmerald else Color(0xFF1E293B)),
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
                                enabled = state.inputText.isNotBlank()
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = if (state.inputText.isNotBlank()) Color.Black else TextMuted,
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
