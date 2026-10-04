package com.offlinechat.data.connection

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class WifiDirectPeerConnectionTest {

    private lateinit var p2pManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var peerConnection: WifiDirectPeerConnection

    private val peerId = "02:00:00:00:00:01"
    private val peer = Peer(
        deviceId = peerId,
        displayName = "Phone B (Wi-Fi)",
        bluetoothAddress = null,
        transportType = TransportType.WIFI_DIRECT
    )

    @Before
    fun setUp() {
        p2pManager = mockk(relaxed = true)
        channel = mockk(relaxed = true)
        peerConnection = WifiDirectPeerConnection(peerId, p2pManager, channel)
    }

    @Test
    fun `initial state is Idle and isConnected is false`() {
        assertEquals(PeerConnectionState.Idle, peerConnection.connectionState.value)
        assertFalse(peerConnection.isConnected)
        assertNull(peerConnection.connectionInfo.value)
    }

    @Test
    fun `onConnected updates connectionInfo and transitions state to Connected as Group Owner`() {
        val info = mockk<WifiP2pInfo>()
        val group = mockk<WifiP2pGroup>()
        val mockAddress = mockk<InetAddress>()

        every { mockAddress.hostAddress } returns "192.168.49.1"
        every { info.groupFormed } returns true
        every { info.isGroupOwner } returns true
        every { info.groupOwnerAddress } returns mockAddress
        every { group.networkName } returns "DIRECT-xy-OfflineChat"
        every { group.passphrase } returns "secretPassphrase123"
        every { group.clientList } returns listOf(mockk<WifiP2pDevice>(), mockk<WifiP2pDevice>())

        peerConnection.onConnected(info, group)

        assertEquals(PeerConnectionState.Connected, peerConnection.connectionState.value)
        assertTrue(peerConnection.isConnected)

        val connInfo = peerConnection.connectionInfo.value
        assertNotNull(connInfo)
        assertTrue(connInfo!!.groupFormed)
        assertTrue(connInfo.isGroupOwner)
        assertEquals("192.168.49.1", connInfo.groupOwnerAddress)
        assertEquals("DIRECT-xy-OfflineChat", connInfo.networkName)
        assertEquals("secretPassphrase123", connInfo.passphrase)
        assertEquals(2, connInfo.clientCount)
    }

    @Test
    fun `onConnected updates connectionInfo as Group Client with dynamic GO address`() {
        val info = mockk<WifiP2pInfo>()
        val mockAddress = mockk<InetAddress>()

        every { mockAddress.hostAddress } returns "192.168.49.1"
        every { info.groupFormed } returns true
        every { info.isGroupOwner } returns false
        every { info.groupOwnerAddress } returns mockAddress

        peerConnection.onConnected(info, null)

        assertEquals(PeerConnectionState.Connected, peerConnection.connectionState.value)
        val connInfo = peerConnection.connectionInfo.value
        assertNotNull(connInfo)
        assertTrue(connInfo!!.groupFormed)
        assertFalse(connInfo.isGroupOwner)
        assertEquals("192.168.49.1", connInfo.groupOwnerAddress)
    }

    @Test
    fun `onDisconnected transitions state to Disconnected and clears connection info`() {
        val info = mockk<WifiP2pInfo>(relaxed = true)
        peerConnection.onConnected(info, null)
        assertTrue(peerConnection.isConnected)

        peerConnection.onDisconnected()

        assertEquals(PeerConnectionState.Disconnected, peerConnection.connectionState.value)
        assertFalse(peerConnection.isConnected)
        assertNull(peerConnection.connectionInfo.value)
    }

    @Test
    fun `disconnect calls p2pManager removeGroup and transitions state`() = runTest {
        val info = mockk<WifiP2pInfo>(relaxed = true)
        peerConnection.onConnected(info, null)

        val actionListenerSlot = slot<WifiP2pManager.ActionListener>()
        every { p2pManager.removeGroup(channel, capture(actionListenerSlot)) } answers {
            actionListenerSlot.captured.onSuccess()
        }

        peerConnection.disconnect()

        verify { p2pManager.removeGroup(channel, any()) }
        assertEquals(PeerConnectionState.Disconnected, peerConnection.connectionState.value)
    }
}
