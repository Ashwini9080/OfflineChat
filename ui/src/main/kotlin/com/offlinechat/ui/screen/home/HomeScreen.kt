package com.offlinechat.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinechat.core.model.Conversation
import com.offlinechat.ui.component.ConversationListItem
import com.offlinechat.ui.component.TransportStatusBar
import com.offlinechat.ui.theme.AccentCyan
import com.offlinechat.ui.theme.AccentEmerald
import com.offlinechat.ui.theme.AccentPurple
import com.offlinechat.ui.theme.BgCardDark
import com.offlinechat.ui.theme.BgDeepDark
import com.offlinechat.ui.theme.BorderSubtle
import com.offlinechat.ui.theme.TextMuted
import com.offlinechat.ui.theme.TextPrimary
import com.offlinechat.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToDiscovery: () -> Unit,
    onNavigateToChat: (conversationId: String, peerId: String, peerDisplayName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.dismissError()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BgDeepDark,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgDeepDark,
                    titleContentColor = TextPrimary
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(AccentEmerald)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "OfflineChat",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToDiscovery) {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = "Scan Nearby",
                            tint = AccentCyan
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToDiscovery,
                containerColor = AccentEmerald,
                contentColor = Color.Black,
                shape = RoundedCornerShape(16.dp),
                icon = {
                    Icon(
                        imageVector = Icons.Default.BluetoothSearching,
                        contentDescription = null
                    )
                },
                text = {
                    Text(
                        text = "Scan Nearby",
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Mesh transport status strip
            TransportStatusBar(
                isScanning = state.isScanning,
                isAdvertising = state.isAdvertising,
                connectedCount = state.connectedPeerCount
            )

            // Local Device Identity Card
            state.identity?.let { identity ->
                DeviceIdentityCard(
                    deviceName = identity.displayName,
                    deviceId = identity.id,
                    isAdvertising = state.isAdvertising,
                    onToggleAdvertising = { enabled ->
                        if (enabled) viewModel.startAdvertising() else viewModel.stopAdvertising()
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Conversations Section
            if (state.conversations.isEmpty()) {
                EmptyConversationsView(onScanClick = onNavigateToDiscovery)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    item {
                        Text(
                            text = "Active Chats (${state.conversations.size})",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(state.conversations, key = { it.id }) { conversation ->
                        val ownId = state.identity?.id ?: ""
                        val remotePeerId = conversation.remotePeerId(ownId) ?: ""
                        val displayName = conversation.displayName
                            ?: "Peer (${remotePeerId.take(8)})"

                        ConversationListItem(
                            conversation = conversation,
                            displayName = displayName,
                            lastMessagePreview = "",
                            onClick = {
                                onNavigateToChat(conversation.id, remotePeerId, displayName)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceIdentityCard(
    deviceName: String,
    deviceId: String,
    isAdvertising: Boolean,
    onToggleAdvertising: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = BgCardDark),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(AccentEmerald, AccentCyan))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = deviceName,
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "ID: ${deviceId.take(12)}...",
                    color = AccentCyan,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    fontSize = 11.sp
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(
                    checked = isAdvertising,
                    onCheckedChange = onToggleAdvertising,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentEmerald,
                        checkedTrackColor = Color(0xFF064E3B)
                    )
                )
                Text(
                    text = if (isAdvertising) "Broadcasting" else "Hidden",
                    color = if (isAdvertising) AccentEmerald else TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun EmptyConversationsView(
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
                    .border(1.dp, BorderSubtle, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = null,
                    tint = AccentEmerald,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "No Nearby Conversations",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Discover nearby devices over Bluetooth or Wi-Fi Direct to start exchanging encrypted offline messages without cell service or internet.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            androidx.compose.material3.Button(
                onClick = onScanClick,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = AccentEmerald,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BluetoothSearching,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Discover Nearby Peers",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}
