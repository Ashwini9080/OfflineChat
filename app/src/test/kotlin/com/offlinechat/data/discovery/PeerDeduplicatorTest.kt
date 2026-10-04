package com.offlinechat.data.discovery

import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PeerDeduplicatorTest {

    private lateinit var deduplicator: PeerDeduplicator

    @Before
    fun setUp() {
        deduplicator = PeerDeduplicator()
    }

    @Test
    fun `adding new peer increases list count`() {
        val initialList = emptyList<Peer>()
        val result = deduplicator.updatePeerList(
            currentList = initialList,
            deviceId = "device_1",
            displayName = "Alice's Phone",
            bluetoothAddress = "AA:BB:CC:DD:EE:01",
            rssi = -60,
            transportType = TransportType.BLUETOOTH,
            timestamp = 1000L
        )

        assertEquals(1, result.size)
        assertEquals("device_1", result[0].deviceId)
        assertEquals("Alice's Phone", result[0].displayName)
        assertEquals(-60, result[0].rssi)
        assertEquals(1000L, result[0].lastSeenAt)
    }

    @Test
    fun `discovering existing device by deviceId updates RSSI and timestamp without creating duplicate`() {
        val initialPeer = Peer(
            deviceId = "device_1",
            displayName = "Alice's Phone",
            bluetoothAddress = "AA:BB:CC:DD:EE:01",
            rssi = -70,
            transportType = TransportType.BLUETOOTH,
            lastSeenAt = 1000L
        )
        val initialList = listOf(initialPeer)

        val updated = deduplicator.updatePeerList(
            currentList = initialList,
            deviceId = "device_1",
            displayName = "Alice's Phone",
            bluetoothAddress = "AA:BB:CC:DD:EE:01",
            rssi = -55,
            timestamp = 2000L
        )

        assertEquals(1, updated.size)
        assertEquals("device_1", updated[0].deviceId)
        assertEquals(-55, updated[0].rssi)
        assertEquals(2000L, updated[0].lastSeenAt)
    }

    @Test
    fun `discovering existing device by MAC address updates device and avoids duplicate`() {
        val initialPeer = Peer(
            deviceId = "aabbccdde01",
            displayName = "Bluetooth Device (EE:01)",
            bluetoothAddress = "AA:BB:CC:DD:EE:01",
            rssi = -80,
            transportType = TransportType.BLUETOOTH,
            lastSeenAt = 1000L
        )
        val initialList = listOf(initialPeer)

        // Discovered via BLE beacon with Keystore ID, matching same MAC
        val updated = deduplicator.updatePeerList(
            currentList = initialList,
            deviceId = "keystore_id_alice",
            displayName = "Alice's Pixel",
            bluetoothAddress = "aa:bb:cc:dd:ee:01",
            rssi = -62,
            timestamp = 3000L
        )

        assertEquals(1, updated.size)
        assertEquals("Alice's Pixel", updated[0].displayName)
        assertEquals(-62, updated[0].rssi)
        assertEquals(3000L, updated[0].lastSeenAt)
    }

    @Test
    fun `two devices with identical display names are preserved separately based on unique ID`() {
        val peerA = Peer(
            deviceId = "phone_a",
            displayName = "Galaxy S24",
            bluetoothAddress = "11:22:33:44:55:66",
            rssi = -65,
            transportType = TransportType.BLUETOOTH
        )
        val listWithPeerA = listOf(peerA)

        // Another physical device with identical default name "Galaxy S24"
        val result = deduplicator.updatePeerList(
            currentList = listWithPeerA,
            deviceId = "phone_b",
            displayName = "Galaxy S24",
            bluetoothAddress = "99:88:77:66:55:44",
            rssi = -50
        )

        assertEquals(2, result.size)
        assertTrue(result.any { it.deviceId == "phone_a" })
        assertTrue(result.any { it.deviceId == "phone_b" })
    }

    @Test
    fun `devices are ordered with strongest signal RSSI first`() {
        val list = mutableListOf<Peer>()
        val peersData = listOf(
            Triple("dev_weak", -90, "Far Device"),
            Triple("dev_strong", -45, "Near Device"),
            Triple("dev_medium", -70, "Mid Device")
        )

        var current = list.toList()
        for (data in peersData) {
            current = deduplicator.updatePeerList(
                currentList = current,
                deviceId = data.first,
                displayName = data.third,
                bluetoothAddress = null,
                rssi = data.second
            )
        }

        assertEquals(3, current.size)
        assertEquals("dev_strong", current[0].deviceId)
        assertEquals(-45, current[0].rssi)
        assertEquals("dev_medium", current[1].deviceId)
        assertEquals(-70, current[1].rssi)
        assertEquals("dev_weak", current[2].deviceId)
        assertEquals(-90, current[2].rssi)
    }

    @Test
    fun `generic fallback name is upgraded when friendly name is received`() {
        val genericPeer = Peer(
            deviceId = "dev_1",
            displayName = "Bluetooth Device (12:34)",
            bluetoothAddress = "AA:BB:CC:12:34:56",
            rssi = -80
        )
        val initialList = listOf(genericPeer)

        val updated = deduplicator.updatePeerList(
            currentList = initialList,
            deviceId = "dev_1",
            displayName = "Bob's Work Phone",
            bluetoothAddress = "AA:BB:CC:12:34:56",
            rssi = -75
        )

        assertEquals("Bob's Work Phone", updated[0].displayName)
    }

    @Test
    fun `mac-fallback deviceId is upgraded when application deviceId is received via ble beacon`() {
        // Initially discovered via classic inquiry with MAC-derived device ID
        val classicPeer = Peer(
            deviceId = "aabbcc123456",
            displayName = "Bluetooth Device (34:56)",
            bluetoothAddress = "AA:BB:CC:12:34:56",
            rssi = -82,
            transportType = TransportType.BLUETOOTH,
            lastSeenAt = 1000L
        )
        val initialList = listOf(classicPeer)

        // Subsequently discovered via BLE advertisement with verified application Keystore ID
        val updated = deduplicator.updatePeerList(
            currentList = initialList,
            deviceId = "user_keystore_bob",
            displayName = "Bob's Pixel",
            bluetoothAddress = "AA:BB:CC:12:34:56",
            rssi = -60,
            timestamp = 2500L
        )

        assertEquals(1, updated.size)
        assertEquals("user_keystore_bob", updated[0].deviceId)
        assertEquals("Bob's Pixel", updated[0].displayName)
        assertEquals(-60, updated[0].rssi)
        assertEquals(2500L, updated[0].lastSeenAt)
    }
}
