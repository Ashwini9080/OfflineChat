package com.offlinechat.data.connection

import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnifiedConnectionManagerTest {

    private lateinit var bluetoothConnectionManager: BluetoothConnectionManager
    private lateinit var wifiDirectConnectionManager: WifiDirectConnectionManager
    private lateinit var unifiedConnectionManager: UnifiedConnectionManager

    @Before
    fun setUp() {
        bluetoothConnectionManager = mockk(relaxed = true)
        wifiDirectConnectionManager = mockk(relaxed = true)
        unifiedConnectionManager = UnifiedConnectionManager(
            bluetoothConnectionManager = bluetoothConnectionManager,
            wifiDirectConnectionManager = wifiDirectConnectionManager
        )
    }

    @Test
    fun `connect with BLUETOOTH peer delegates strictly to BluetoothConnectionManager`() = runTest {
        val peer = Peer(
            deviceId = "bt-123",
            displayName = "BT Device",
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            transportType = TransportType.BLUETOOTH
        )
        coEvery { bluetoothConnectionManager.connect(peer) } returns Result.success(Unit)

        val result = unifiedConnectionManager.connect(peer)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { bluetoothConnectionManager.connect(peer) }
        coVerify(exactly = 0) { wifiDirectConnectionManager.connect(any()) }
    }

    @Test
    fun `connect with WIFI_DIRECT peer delegates strictly to WifiDirectConnectionManager`() = runTest {
        val peer = Peer(
            deviceId = "wifi-456",
            displayName = "Wi-Fi Device",
            bluetoothAddress = null,
            transportType = TransportType.WIFI_DIRECT
        )
        coEvery { wifiDirectConnectionManager.connect(peer) } returns Result.success(Unit)

        val result = unifiedConnectionManager.connect(peer)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { wifiDirectConnectionManager.connect(peer) }
        coVerify(exactly = 0) { bluetoothConnectionManager.connect(any()) }
    }

    @Test
    fun `isConnected returns true if either transport manager reports connected`() {
        every { bluetoothConnectionManager.isConnected("peer-1") } returns true
        every { wifiDirectConnectionManager.isConnected("peer-1") } returns false
        assertTrue(unifiedConnectionManager.isConnected("peer-1"))

        every { bluetoothConnectionManager.isConnected("peer-2") } returns false
        every { wifiDirectConnectionManager.isConnected("peer-2") } returns true
        assertTrue(unifiedConnectionManager.isConnected("peer-2"))

        every { bluetoothConnectionManager.isConnected("peer-3") } returns false
        every { wifiDirectConnectionManager.isConnected("peer-3") } returns false
        assertFalse(unifiedConnectionManager.isConnected("peer-3"))
    }

    @Test
    fun `disconnect calls disconnect on both transport managers`() = runTest {
        unifiedConnectionManager.disconnect("peer-xyz")

        coVerify(exactly = 1) { bluetoothConnectionManager.disconnect("peer-xyz") }
        coVerify(exactly = 1) { wifiDirectConnectionManager.disconnect("peer-xyz") }
    }

    @Test
    fun `disconnectAll cleans up all transport managers`() = runTest {
        unifiedConnectionManager.disconnectAll()

        coVerify(exactly = 1) { bluetoothConnectionManager.disconnectAll() }
        coVerify(exactly = 1) { wifiDirectConnectionManager.disconnectAll() }
    }
}
