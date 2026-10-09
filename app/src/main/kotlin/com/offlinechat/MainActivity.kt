package com.offlinechat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.offlinechat.domain.connection.ConnectionManager
import com.offlinechat.presentation.components.UpdateDialog
import com.offlinechat.presentation.navigation.OfflineChatNavGraph
import com.offlinechat.presentation.theme.OfflineChatTheme
import com.offlinechat.updater.AppUpdateManager
import com.offlinechat.updater.UpdateState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var connectionManager: ConnectionManager

    @Inject
    lateinit var appUpdateManager: AppUpdateManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Start background incoming server listener as soon as permissions are granted
        connectionManager.startServerListener()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()
        connectionManager.startServerListener()

        // Check for dev server updates silently on startup
        appUpdateManager.checkForUpdates(silent = true)

        setContent {
            OfflineChatTheme {
                val updateState by appUpdateManager.updateState.collectAsState()

                LaunchedEffect(updateState) {
                    if (updateState is UpdateState.UpToDate) {
                        Toast.makeText(this@MainActivity, "OfflineChat is up to date!", Toast.LENGTH_SHORT).show()
                        appUpdateManager.dismissUpdate()
                    }
                }

                OfflineChatNavGraph()

                UpdateDialog(
                    updateState = updateState,
                    onStartDownload = { info -> appUpdateManager.startDownload(info) },
                    onInstall = { apkFile -> appUpdateManager.installApk(this@MainActivity, apkFile) },
                    onDismiss = { appUpdateManager.dismissUpdate() },
                    onRetry = { appUpdateManager.checkForUpdates(silent = false) }
                )
            }
        }
    }

    /**
     * Requests all permissions needed by both transport layers at startup.
     *
     * **Bluetooth (RFCOMM / BLE):**
     * - API 31+ → BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE
     * - API 30-  → ACCESS_FINE_LOCATION (required for classic discovery)
     *
     * **Wi-Fi Direct:**
     * - API 33+ → NEARBY_WIFI_DEVICES
     * - API 32-  → ACCESS_FINE_LOCATION (already requested above for API 26–30,
     *              but must be explicitly listed for API 31–32 devices here too)
     */
    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // ── Bluetooth permissions ─────────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+)
            addIfMissing(permissionsToRequest, Manifest.permission.BLUETOOTH_SCAN)
            addIfMissing(permissionsToRequest, Manifest.permission.BLUETOOTH_CONNECT)
            addIfMissing(permissionsToRequest, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            // Android 11 and below — Location required for BLE scanning
            addIfMissing(permissionsToRequest, Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // ── Wi-Fi Direct permissions ──────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+) — dedicated nearby Wi-Fi permission
            addIfMissing(permissionsToRequest, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            // Android 12 and 12L (API 31–32) — Fine location still required for Wi-Fi Direct
            // Android 11 and below already requested ACCESS_FINE_LOCATION above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addIfMissing(permissionsToRequest, Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun addIfMissing(list: MutableList<String>, permission: String) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            list.add(permission)
        }
    }
}
