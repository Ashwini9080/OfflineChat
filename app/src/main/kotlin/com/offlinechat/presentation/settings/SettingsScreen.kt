package com.offlinechat.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinechat.domain.model.TransportType
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
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.feedbackMessage) {
        state.feedbackMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissFeedback()
        }
    }

    if (state.isEditingName) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelEditingName() },
            containerColor = BgCardDark,
            title = {
                Text("Edit Device Display Name", color = TextPrimary)
            },
            text = {
                OutlinedTextField(
                    value = state.editedName,
                    onValueChange = { viewModel.onEditedNameChanged(it) },
                    singleLine = true,
                    label = { Text("Display Name") }
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.saveEditedName() },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald, contentColor = Color.Black)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.cancelEditingName() }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    if (state.isEditingServerUrl) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelEditingServerUrl() },
            containerColor = BgCardDark,
            title = {
                Text("Edit Dev Update Server URL", color = TextPrimary)
            },
            text = {
                OutlinedTextField(
                    value = state.editedServerUrl,
                    onValueChange = { viewModel.onEditedServerUrlChanged(it) },
                    singleLine = true,
                    label = { Text("Server URL (e.g. http://10.33.160.61:8080)") }
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.saveEditedServerUrl() },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald, contentColor = Color.Black)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.cancelEditingServerUrl() }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
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
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                title = {
                    Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section: Device Identity
            Text("DEVICE IDENTITY", color = AccentEmerald, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

            Card(
                colors = CardDefaults.cardColors(containerColor = BgCardDark),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF064E3B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.Shield, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = state.displayName, color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(text = "Tap edit to change name", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { viewModel.startEditingName() }) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = AccentCyan, modifier = Modifier.size(20.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Cryptographic Fingerprint (Keystore)", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = state.deviceId,
                        color = AccentCyan,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp
                    )
                }
            }

            // Section: Discovery & Networking
            Text("NETWORKING & DISCOVERY", color = AccentEmerald, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

            Card(
                colors = CardDefaults.cardColors(containerColor = BgCardDark),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Radar, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Auto BLE Discovery", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                                Text("Broadcast & scan for nearby devices", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Switch(
                            checked = state.isAutoDiscovery,
                            onCheckedChange = { viewModel.toggleAutoDiscovery(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentEmerald, checkedTrackColor = Color(0xFF064E3B))
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("Preferred Transport", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = state.preferredTransport == TransportType.BLUETOOTH,
                            onClick = { viewModel.setPreferredTransport(TransportType.BLUETOOTH) },
                            colors = RadioButtonDefaults.colors(selectedColor = AccentEmerald)
                        )
                        Icon(imageVector = Icons.Default.Bluetooth, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Bluetooth Classic / BLE", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = state.preferredTransport == TransportType.WIFI_DIRECT,
                            onClick = { viewModel.setPreferredTransport(TransportType.WIFI_DIRECT) },
                            colors = RadioButtonDefaults.colors(selectedColor = AccentEmerald)
                        )
                        Icon(imageVector = Icons.Default.NetworkWifi, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Wi-Fi Direct (High speed P2P)", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Section: Security Protocol
            Text("SECURITY & PRIVACY", color = AccentEmerald, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

            Card(
                colors = CardDefaults.cardColors(containerColor = BgCardDark),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Decentralized P2P Architecture", color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Private keys are generated in hardware-backed Android Keystore and never stored in plain text.\n" +
                                "• Handshakes use Elliptic Curve Diffie-Hellman (ECDH secp256r1) to derive per-session keys.\n" +
                                "• Messages are encrypted with AES-256-GCM (AEAD) and verified on arrival.\n" +
                                "• Fully offline: No cloud servers, Firebase, or central authorities.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 18.sp
                    )
                }
            }

            // Section: App Updates & Dev Sync (Tarika 2)
            Text("APP UPDATES (OTA DEV SYNC)", color = AccentEmerald, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

            Card(
                colors = CardDefaults.cardColors(containerColor = BgCardDark),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0F2937)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.SystemUpdate, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "In-App Auto-Updater", color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(text = "Current: v${state.appVersionName} (Build ${state.appVersionCode})", color = AccentCyan, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("Dev Update Server Endpoint", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.updateServerUrl,
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.startEditingServerUrl() }) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Endpoint", tint = AccentCyan, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { viewModel.checkForUpdates() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black)
                    ) {
                        Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Check for Updates Now", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
