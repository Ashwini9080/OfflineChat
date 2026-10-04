package com.offlinechat.data.discovery

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothPermissionModelTest {

    @Test
    fun `modern android 12 plus permission set requires scan connect and advertise without location`() {
        val modernPermissions = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )

        assertTrue(modernPermissions.contains("android.permission.BLUETOOTH_SCAN"))
        assertTrue(modernPermissions.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(modernPermissions.contains("android.permission.BLUETOOTH_ADVERTISE"))
        assertFalse(modernPermissions.contains("android.permission.ACCESS_FINE_LOCATION"))
    }

    @Test
    fun `legacy android 11 and lower permission set requires location for bluetooth beacon discovery`() {
        val legacyPermissions = listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        assertTrue(legacyPermissions.contains("android.permission.BLUETOOTH"))
        assertTrue(legacyPermissions.contains("android.permission.BLUETOOTH_ADMIN"))
        assertTrue(legacyPermissions.contains("android.permission.ACCESS_FINE_LOCATION"))
    }
}
