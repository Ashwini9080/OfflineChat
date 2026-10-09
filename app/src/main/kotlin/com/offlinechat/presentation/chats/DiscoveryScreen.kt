package com.offlinechat.presentation.chats

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.offlinechat.domain.model.PeerConnectionState
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import com.offlinechat.domain.model.TransportType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinechat.domain.model.DiscoveryStatus
import com.offlinechat.presentation.components.PeerItem
import com.offlinechat.presentation.components.SecurityDialog
import com.offlinechat.presentation.theme.AccentCyan
import com.offlinechat.presentation.theme.AccentEmerald
import com.offlinechat.presentation.theme.BgCardDark
import com.offlinechat.presentation.theme.BgDeepDark
import com.offlinechat.presentation.theme.BorderSubtle
import com.offlinechat.presentation.theme.TextMuted
import com.offlinechat.presentation.theme.TextPrimary
import com.offlinechat.presentation.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToChat: (conversationId: String, peerId: String, peerDisplayName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Activity Result Launchers
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onBluetoothEnabled()
        }
    }

    val enableWifiLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onWifiEnabled()
    }

    val filteredPeers = remember(state.peers, state.selectedTransportFilter) {
        if (state.selectedTransportFilter == null) {
            state.peers
        } else {
            state.peers.filter { it.transportType == state.selectedTransportFilter }
        }
    }

    val requestPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val allGranted = permissionsMap.values.all { it }
        if (allGranted) {
            viewModel.onPermissionsGranted()
        } else {
            val activity = context as? Activity
            val anyPermanentlyDenied = permissionsMap.keys.any { perm ->
                activity != null && !activity.shouldShowRequestPermissionRationale(perm)
            }
            viewModel.onPermissionsDenied(permanentlyDenied = anyPermanentlyDenied)
        }
    }

    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Returned from discoverable prompt
    }

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is DiscoveryNavigationEvent.OpenChat -> {
                    onNavigateToChat(event.conversationId, event.peerId, event.peerDisplayName)
                }
            }
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissError()
        }
    }

    // Lifecycle safety: cancel active Bluetooth discovery when exiting screen
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            viewModel.stopScan()
        }
    }

    // Permission Rationale / Settings Dialog
    if (state.showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPermissionRationale() },
            containerColor = BgCardDark,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = AccentCyan
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.isPermissionPermanentlyDenied) "Permission Required in Settings" else "Nearby Permissions Required",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Text(
                    text = if (state.isPermissionPermanentlyDenied) {
                        "Bluetooth permissions are permanently restricted. To discover and chat with nearby devices offline, please grant Nearby Devices permissions in App Settings."
                    } else {
                        "OfflineChat requires Bluetooth and Nearby Devices permissions to discover and securely link with compatible phones in your vicinity without using the internet."
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (state.isPermissionPermanentlyDenied) {
                            context.startActivity(viewModel.permissionHelper.createAppSettingsIntent())
                            viewModel.dismissPermissionRationale()
                        } else {
                            val perms = viewModel.permissionHelper.getRequiredPermissions()
                            requestPermissionsLauncher.launch(perms.toTypedArray())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = Color.Black
                    )
                ) {
                    Text(
                        text = if (state.isPermissionPermanentlyDenied) "Open Settings" else "Grant Access",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPermissionRationale() }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }

    // Trust confirmation dialog before connecting
    state.pendingTrustPeer?.let { peer ->
        SecurityDialog(
            peer = peer,
            onConfirmTrust = { viewModel.confirmTrust(it) },
            onDismiss = { viewModel.dismissTrustDialog() }
        )
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
                    Text(
                        text = "Discover Nearby",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    // Make Discoverable Action
                    IconButton(
                        onClick = {
                            discoverableLauncher.launch(viewModel.permissionHelper.createDiscoverableIntent(120))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Make Discoverable",
                            tint = AccentCyan
                        )
                    }

                    // Scan / Stop Toggle Action
                    IconButton(
                        onClick = {
                            if (state.isScanning) {
                                viewModel.stopScan()
                            } else {
                                viewModel.startScan()
                            }
                        }
                    ) {
                        if (state.isScanning) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Scanning",
                                tint = Color(0xFFEF4444)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Scan",
                                tint = AccentCyan
                            )
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
            // Bluetooth Disabled Banner
            AnimatedVisibility(visible = state.isBluetoothDisabled, enter = fadeIn(), exit = fadeOut()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB45309)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.BluetoothDisabled,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bluetooth is turned off",
                                color = TextPrimary,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Turn it on to discover nearby devices.",
                                color = Color(0xFFFDE68A),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = {
                                enableBluetoothLauncher.launch(viewModel.permissionHelper.createEnableBluetoothIntent())
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF59E0B),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Turn On", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Bluetooth Unavailable Banner
            AnimatedVisibility(visible = state.isBluetoothUnavailable, enter = fadeIn(), exit = fadeOut()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDC2626)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Bluetooth hardware is not available on this device.",
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Wi-Fi Disabled Banner
            AnimatedVisibility(visible = state.isWifiDisabled, enter = fadeIn(), exit = fadeOut()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2338)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Wi-Fi is turned off",
                                color = TextPrimary,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Turn it on to discover nearby Wi-Fi Direct peers.",
                                color = Color(0xFFBAE6FD),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = {
                                enableWifiLauncher.launch(viewModel.wifiPermissionHelper.createEnableWifiIntent())
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentCyan,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Turn On", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Radar Pulse Banner with Status Details
            RadarPulseBanner(
                isScanning = state.isScanning,
                count = filteredPeers.size,
                status = state.discoveryStatus,
                onToggleScan = {
                    if (state.isScanning) viewModel.stopScan() else viewModel.startScan()
                }
            )

            // Transport Filter Selector Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransportFilterChip(
                    label = "All (${state.peers.size})",
                    selected = state.selectedTransportFilter == null,
                    onClick = { viewModel.selectTransportFilter(null) }
                )
                TransportFilterChip(
                    label = "Bluetooth",
                    icon = Icons.Default.Bluetooth,
                    selected = state.selectedTransportFilter == TransportType.BLUETOOTH,
                    onClick = { viewModel.selectTransportFilter(TransportType.BLUETOOTH) }
                )
                TransportFilterChip(
                    label = "Wi-Fi Direct",
                    icon = Icons.Default.NetworkWifi,
                    selected = state.selectedTransportFilter == TransportType.WIFI_DIRECT,
                    onClick = { viewModel.selectTransportFilter(TransportType.WIFI_DIRECT) }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (filteredPeers.isEmpty()) {
                val emptyMessage = when {
                    state.isScanning -> "Searching for nearby devices..."
                    state.selectedTransportFilter == TransportType.WIFI_DIRECT -> "No nearby Wi-Fi Direct devices found."
                    state.selectedTransportFilter == TransportType.BLUETOOTH -> "No nearby Bluetooth devices found."
                    else -> "No nearby compatible devices found."
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val emptyIcon = when (state.selectedTransportFilter) {
                            TransportType.WIFI_DIRECT -> Icons.Default.NetworkWifi
                            else -> Icons.Default.BluetoothSearching
                        }
                        Icon(
                            imageVector = emptyIcon,
                            contentDescription = null,
                            tint = if (state.isScanning) AccentCyan else TextMuted,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = emptyMessage,
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BgCardDark),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Make sure:",
                                    color = AccentCyan,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "• Bluetooth & Wi-Fi are turned on\n• Nearby device permissions are allowed\n• The other device is discoverable / has OfflineChat open",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 20.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    discoverableLauncher.launch(viewModel.permissionHelper.createDiscoverableIntent(120))
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Make Discoverable", color = AccentCyan, fontSize = 12.sp)
                            }

                            Button(
                                onClick = { viewModel.startScan() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AccentEmerald,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Scan Again", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredPeers, key = { it.deviceId }) { peer ->
                        val peerState = state.peerStates[peer.deviceId]
                            ?: if (peer.isConnected) PeerConnectionState.Connected else PeerConnectionState.Idle
                        PeerItem(
                            peer = peer,
                            connectionState = peerState,
                            onConnectClick = { viewModel.onConnectClicked(it) },
                            onCancelClick = { viewModel.onCancelConnectClicked(it) },
                            onOpenChatClick = { viewModel.onOpenChatClicked(it) },
                            onDisconnectClick = { viewModel.onDisconnectClicked(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarPulseBanner(
    isScanning: Boolean,
    count: Int,
    status: DiscoveryStatus,
    onToggleScan: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "pulse_radar")
    val scale by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )

    val statusText = when (status) {
        is DiscoveryStatus.Idle -> "Scanner Idle"
        is DiscoveryStatus.CheckingBluetooth -> "Checking Bluetooth state..."
        is DiscoveryStatus.CheckingWifi -> "Checking Wi-Fi state..."
        is DiscoveryStatus.CheckingPermissions -> "Checking permissions..."
        is DiscoveryStatus.StartingDiscovery -> "Starting peer discovery..."
        is DiscoveryStatus.Discovering -> "Active Discovery Scanning"
        is DiscoveryStatus.DeviceFound -> "$count device(s) discovered"
        is DiscoveryStatus.DiscoveryComplete -> "Discovery cycle completed"
        is DiscoveryStatus.DiscoveryCancelled -> "Discovery stopped"
        is DiscoveryStatus.BluetoothDisabled -> "Bluetooth disabled"
        is DiscoveryStatus.BluetoothUnavailable -> "Bluetooth unavailable"
        is DiscoveryStatus.WifiDisabled -> "Wi-Fi turned off"
        is DiscoveryStatus.WifiDirectUnsupported -> "Wi-Fi Direct unsupported"
        is DiscoveryStatus.PermissionRequired -> "Permissions missing"
        is DiscoveryStatus.PermissionDenied -> "Permissions denied"
        is DiscoveryStatus.PermissionPermanentlyDenied -> "Permission permanently restricted"
        is DiscoveryStatus.PermissionRevoked -> "Permissions revoked"
        is DiscoveryStatus.PermissionGranted -> "Permissions allowed"
        is DiscoveryStatus.ReadyForDiscovery -> "Radios ready"
        is DiscoveryStatus.DiscoveryFailed -> "Discovery failed"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isScanning) {
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .scale(scale)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .border(2.dp, AccentCyan, CircleShape)
                )
            }

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0F172A))
                    .border(1.dp, if (isScanning) AccentCyan else BorderSubtle, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = if (isScanning) AccentEmerald else TextMuted,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = statusText,
            color = if (isScanning) AccentEmerald else TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = if (count > 0) "$count compatible peer(s) found" else "Scanning Bluetooth & Wi-Fi Direct",
            color = TextMuted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun TransportFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) AccentCyan else BgCardDark)
            .border(
                1.dp,
                if (selected) AccentCyan else BorderSubtle,
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) Color.Black else TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                color = if (selected) Color.Black else TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
