package com.offlinechat.data.connection

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.offlinechat.data.discovery.WifiDirectPermissionHelper
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WifiDirectConnectionManagerTest {

    private lateinit var context: Context
    private lateinit var permissionHelper: WifiDirectPermissionHelper
    private lateinit var p2pManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var manager: WifiDirectConnectionManager

    private val peer = Peer(
        deviceId = "aabbccddee01",
        displayName = "Phone B",
        bluetoothAddress = null,
        transportType = TransportType.WIFI_DIRECT
    )

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        permissionHelper = mockk(relaxed = true)
        p2pManager = mockk(relaxed = true)
        channel = mockk(relaxed = true)

        every { context.getSystemService(Context.WIFI_P2P_SERVICE) } returns p2pManager
        every { p2pManager.initialize(any(), any(), any()) } returns channel

        manager = WifiDirectConnectionManager(context, permissionHelper)
    }

    @Test
    fun `connect fails when Wi-Fi Direct is unsupported`() = runTest {
        every { permissionHelper.isWifiDirectSupported() } returns false

        val result = manager.connect(peer)

        assertTrue(result.isFailure)
        val state = manager.getConnectionState(peer.deviceId)
        assertTrue(state is PeerConnectionState.ConnectionFailed)
    }

    @Test
    fun `connect fails when Wi-Fi is disabled`() = runTest {
        every { permissionHelper.isWifiDirectSupported() } returns true
        every { permissionHelper.isWifiEnabled() } returns false

        val result = manager.connect(peer)

        assertTrue(result.isFailure)
        assertEquals(PeerConnectionState.WifiDisabled, manager.getConnectionState(peer.deviceId))
    }

    @Test
    fun `connect fails when required permissions are missing`() = runTest {
        every { permissionHelper.isWifiDirectSupported() } returns true
        every { permissionHelper.isWifiEnabled() } returns true
        every { permissionHelper.hasRequiredPermissions() } returns false

        val result = manager.connect(peer)

        assertTrue(result.isFailure)
        assertEquals(PeerConnectionState.PermissionRevoked, manager.getConnectionState(peer.deviceId))
    }

    @Test
    fun `cancelConnection resets state to Disconnected`() = runTest {
        manager.cancelConnection(peer.deviceId)

        assertEquals(PeerConnectionState.Disconnected, manager.getConnectionState(peer.deviceId))
        assertFalse(manager.isConnected(peer.deviceId))
    }

    @Test
    fun `disconnect sets state to Disconnected`() = runTest {
        manager.disconnect(peer.deviceId)

        assertEquals(PeerConnectionState.Disconnected, manager.getConnectionState(peer.deviceId))
        assertFalse(manager.isConnected(peer.deviceId))
    }

    @Test
    fun `observeConnectionState exposes initial Idle state`() = runTest {
        val state = manager.observeConnectionState("unknown_peer").first()
        assertEquals(PeerConnectionState.Idle, state)
    }
}
