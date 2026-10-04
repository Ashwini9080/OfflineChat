package com.offlinechat.data.discovery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiDirectPermissionHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    /**
     * Checks if Wi-Fi Direct hardware capability is supported on this device.
     */
    fun isWifiDirectSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
                wifiP2pManager != null
    }

    /**
     * Checks if Wi-Fi is currently enabled on the device.
     */
    fun isWifiEnabled(): Boolean {
        return wifiManager?.isWifiEnabled == true
    }

    /**
     * Returns the required runtime permissions based on the Android API level.
     * Android 13+ (API 33+) requires NEARBY_WIFI_DEVICES.
     * Android 12 and lower requires ACCESS_FINE_LOCATION and Wi-Fi state permissions.
     */
    fun getRequiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
        }
    }

    /**
     * Checks whether all required Wi-Fi Direct permissions have been granted.
     */
    fun hasRequiredPermissions(): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns the list of ungranted permissions that need to be requested from the user.
     */
    fun getMissingPermissions(): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Creates an Intent to navigate the user to Android Wi-Fi settings so they can enable Wi-Fi.
     */
    fun createEnableWifiIntent(): Intent {
        return Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates an Intent to open the Application Details Settings screen in case of permanently denied permissions.
     */
    fun createAppSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
