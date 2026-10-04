package com.offlinechat.data.discovery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class WifiDirectPermissionHelperTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var wifiManager: WifiManager
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var permissionHelper: WifiDirectPermissionHelper

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        wifiManager = mockk(relaxed = true)
        wifiP2pManager = mockk(relaxed = true)

        every { context.packageManager } returns packageManager
        every { context.applicationContext.getSystemService(Context.WIFI_SERVICE) } returns wifiManager
        every { context.getSystemService(Context.WIFI_P2P_SERVICE) } returns wifiP2pManager

        permissionHelper = WifiDirectPermissionHelper(context)
    }

    private fun setSdkInt(version: Int) {
        val field = Build.VERSION::class.java.getField("SDK_INT")
        field.isAccessible = true
        val modifiersField = Field::class.java.getDeclaredField("modifiers")
        modifiersField.isAccessible = true
        modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
        field.set(null, version)
    }

    @Test
    fun `isWifiDirectSupported returns true when feature and system service are available`() {
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) } returns true
        assertTrue(permissionHelper.isWifiDirectSupported())

        every { packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) } returns false
        assertFalse(permissionHelper.isWifiDirectSupported())
    }

    @Test
    fun `isWifiEnabled reports true only when WifiManager reports true`() {
        every { wifiManager.isWifiEnabled } returns true
        assertTrue(permissionHelper.isWifiEnabled())

        every { wifiManager.isWifiEnabled } returns false
        assertFalse(permissionHelper.isWifiEnabled())
    }

    @Test
    fun `getRequiredPermissions returns NEARBY_WIFI_DEVICES for Android 13+`() {
        try {
            setSdkInt(Build.VERSION_CODES.TIRAMISU)
            val permissions = permissionHelper.getRequiredPermissions()
            assertEquals(1, permissions.size)
            assertEquals(Manifest.permission.NEARBY_WIFI_DEVICES, permissions[0])
        } finally {
            setSdkInt(0)
        }
    }

    @Test
    fun `getRequiredPermissions returns Fine Location and Wifi State for legacy Android`() {
        try {
            setSdkInt(Build.VERSION_CODES.S)
            val permissions = permissionHelper.getRequiredPermissions()
            assertTrue(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
            assertTrue(permissions.contains(Manifest.permission.ACCESS_WIFI_STATE))
            assertTrue(permissions.contains(Manifest.permission.CHANGE_WIFI_STATE))
        } finally {
            setSdkInt(0)
        }
    }
}
