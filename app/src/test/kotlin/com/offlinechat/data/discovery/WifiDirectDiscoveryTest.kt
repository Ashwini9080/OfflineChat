package com.offlinechat.data.discovery

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.offlinechat.domain.model.DiscoveryStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WifiDirectDiscoveryTest {

    private lateinit var context: Context
    private lateinit var permissionHelper: WifiDirectPermissionHelper
    private lateinit var p2pManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var discovery: WifiDirectDiscovery

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        permissionHelper = mockk(relaxed = true)
        p2pManager = mockk(relaxed = true)
        channel = mockk(relaxed = true)

        every { context.getSystemService(Context.WIFI_P2P_SERVICE) } returns p2pManager
        every { p2pManager.initialize(any(), any(), any()) } returns channel

        discovery = WifiDirectDiscovery(context, permissionHelper)
    }

    @Test
    fun `startDiscovery fails when Wi-Fi Direct is unsupported`() = runTest {
        every { permissionHelper.isWifiDirectSupported() } returns false

        val result = discovery.startDiscovery("My Device", "dev-1")

        assertTrue(result.isFailure)
        assertEquals(DiscoveryStatus.WifiDirectUnsupported, discovery.discoveryStatus.value)
        assertFalse(discovery.isDiscovering.value)
    }

    @Test
    fun `startDiscovery fails when Wi-Fi is disabled`() = runTest {
        every { permissionHelper.isWifiDirectSupported() } returns true
        every { permissionHelper.isWifiEnabled() } returns false

        val result = discovery.startDiscovery("My Device", "dev-1")

        assertTrue(result.isFailure)
        assertEquals(DiscoveryStatus.WifiDisabled, discovery.discoveryStatus.value)
        assertFalse(discovery.isDiscovering.value)
    }

    @Test
    fun `startDiscovery fails when required permissions are missing`() = runTest {
        every { permissionHelper.isWifiDirectSupported() } returns true
        every { permissionHelper.isWifiEnabled() } returns true
        every { permissionHelper.hasRequiredPermissions() } returns false
        every { permissionHelper.getMissingPermissions() } returns listOf("android.permission.NEARBY_WIFI_DEVICES")

        val result = discovery.startDiscovery("My Device", "dev-1")

        assertTrue(result.isFailure)
        val status = discovery.discoveryStatus.value
        assertTrue(status is DiscoveryStatus.PermissionRequired)
        assertEquals(listOf("android.permission.NEARBY_WIFI_DEVICES"), (status as DiscoveryStatus.PermissionRequired).permissions)
    }

    @Test
    fun `startDiscovery succeeds and initiates p2pManager discoverPeers`() = runTest {
        every { permissionHelper.isWifiDirectSupported() } returns true
        every { permissionHelper.isWifiEnabled() } returns true
        every { permissionHelper.hasRequiredPermissions() } returns true

        val actionSlot = slot<WifiP2pManager.ActionListener>()
        every { p2pManager.discoverPeers(any(), capture(actionSlot)) } answers {
            actionSlot.captured.onSuccess()
        }

        val result = discovery.startDiscovery("My Device", "dev-1")

        assertTrue(result.isSuccess)
        verify { p2pManager.discoverPeers(any(), any()) }
        assertEquals(DiscoveryStatus.Discovering, discovery.discoveryStatus.value)
        assertTrue(discovery.isDiscovering.value)
    }

    @Test
    fun `stopDiscovery stops peer discovery and marks status complete`() = runTest {
        discovery.stopDiscovery()

        verify { p2pManager.stopPeerDiscovery(any(), any()) }
        assertEquals(DiscoveryStatus.DiscoveryComplete, discovery.discoveryStatus.value)
        assertFalse(discovery.isDiscovering.value)
    }
}
