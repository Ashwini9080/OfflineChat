package com.offlinechat.data.discovery

import android.net.wifi.p2p.WifiP2pDevice
import com.offlinechat.domain.model.TransportType
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WifiDirectDeviceMapperTest {

    private lateinit var mapper: WifiDirectDeviceMapper

    @Before
    fun setUp() {
        mapper = WifiDirectDeviceMapper()
    }

    @Test
    fun `mapDevice correctly normalizes deviceAddress into lowercase hex deviceId`() {
        val device = mockk<WifiP2pDevice>()
        every { device.deviceAddress } returns "02:1A:3B:4C:5D:6E"
        every { device.deviceName } returns "Pixel 7"

        val peer = mapper.mapDevice(device, timestamp = 123456789L)

        assertEquals("021a3b4c5d6e", peer.deviceId)
        assertEquals("Pixel 7", peer.displayName)
        assertNull(peer.bluetoothAddress)
        assertEquals(TransportType.WIFI_DIRECT, peer.transportType)
        assertFalse(peer.isConnected)
        assertFalse(peer.isTrusted)
        assertEquals(-80, peer.rssi)
        assertEquals(123456789L, peer.lastSeenAt)
        assertTrue(peer.publicKeyBytes.isEmpty())
    }

    @Test
    fun `mapDevice handles null or blank deviceName with sensible fallback`() {
        val device = mockk<WifiP2pDevice>()
        every { device.deviceAddress } returns "02:1A:3B:4C:5D:6E"
        every { device.deviceName } returns null

        val peer = mapper.mapDevice(device)

        assertEquals("021a3b4c5d6e", peer.deviceId)
        assertTrue(peer.displayName.contains("5D:6E"))
    }

    @Test
    fun `mapDeviceList filters blank addresses and deduplicates by deviceId`() {
        val device1 = mockk<WifiP2pDevice>()
        every { device1.deviceAddress } returns "AA:BB:CC:DD:EE:01"
        every { device1.deviceName } returns "Device One"

        // Duplicate of device1
        val device1Dup = mockk<WifiP2pDevice>()
        every { device1Dup.deviceAddress } returns "AA:BB:CC:DD:EE:01"
        every { device1Dup.deviceName } returns "Device One Updated"

        val device2 = mockk<WifiP2pDevice>()
        every { device2.deviceAddress } returns "AA:BB:CC:DD:EE:02"
        every { device2.deviceName } returns "Device Two"

        val deviceBlank = mockk<WifiP2pDevice>()
        every { deviceBlank.deviceAddress } returns ""
        every { deviceBlank.deviceName } returns "Blank Device"

        val deviceNull = mockk<WifiP2pDevice>()
        every { deviceNull.deviceAddress } returns null
        every { deviceNull.deviceName } returns "Null Device"

        val peers = mapper.mapDeviceList(listOf(device1, device1Dup, device2, deviceBlank, deviceNull))

        assertEquals(2, peers.size)
        assertEquals("aabbccddee01", peers[0].deviceId)
        assertEquals("aabbccddee02", peers[1].deviceId)
    }
}
