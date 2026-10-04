package com.offlinechat.data.discovery

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import com.offlinechat.domain.model.TransportType
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.UUID

class DeviceMappingTest {

    private lateinit var mapper: DeviceMapper
    private val testServiceUuid = UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb")

    @Before
    fun setUp() {
        mapper = DeviceMapper()
    }

    @Test
    fun `classic device mapping preserves name, normalized ID, MAC address and RSSI`() {
        val mockDevice = mockk<BluetoothDevice>(relaxed = true) {
            every { name } returns "Pixel 8 Pro"
            every { address } returns "AA:BB:CC:11:22:33"
        }

        val peer = mapper.mapClassicDevice(
            device = mockDevice,
            extraName = null,
            rssi = -64,
            timestamp = 1000L
        )

        assertEquals("aabbcc112233", peer.deviceId)
        assertEquals("Pixel 8 Pro", peer.displayName)
        assertEquals("AA:BB:CC:11:22:33", peer.bluetoothAddress)
        assertEquals(-64, peer.rssi)
        assertEquals(TransportType.BLUETOOTH, peer.transportType)
        assertEquals(1000L, peer.lastSeenAt)
    }

    @Test
    fun `classic device with extraName overrides null device name`() {
        val mockDevice = mockk<BluetoothDevice>(relaxed = true) {
            every { name } returns null
            every { address } returns "12:34:56:78:9A:BC"
        }

        val peer = mapper.mapClassicDevice(
            device = mockDevice,
            extraName = "Discovered Tablet",
            rssi = -72
        )

        assertEquals("Discovered Tablet", peer.displayName)
        assertEquals("123456789abc", peer.deviceId)
        assertEquals(-72, peer.rssi)
    }

    @Test
    fun `classic device with missing name and extraName falls back to MAC-based placeholder`() {
        val mockDevice = mockk<BluetoothDevice>(relaxed = true) {
            every { name } returns null
            every { address } returns "11:22:33:44:55:66"
        }

        val peer = mapper.mapClassicDevice(
            device = mockDevice,
            extraName = null,
            rssi = -85
        )

        assertEquals("Bluetooth Device (55:66)", peer.displayName)
        assertEquals("112233445566", peer.deviceId)
        assertEquals(-85, peer.rssi)
    }

    @Test
    fun `classic device handles SecurityException on device name gracefully`() {
        val mockDevice = mockk<BluetoothDevice>(relaxed = true) {
            every { name } throws SecurityException("BLUETOOTH_CONNECT permission missing")
            every { address } returns "AA:BB:CC:DD:EE:FF"
        }

        val peer = mapper.mapClassicDevice(
            device = mockDevice,
            extraName = null,
            rssi = -70
        )

        assertEquals("Bluetooth Device (EE:FF)", peer.displayName)
        assertEquals("aabbccddeeff", peer.deviceId)
    }

    @Test
    fun `classic device defaults invalid zero or minimum RSSI to minus 80`() {
        val mockDevice = mockk<BluetoothDevice>(relaxed = true) {
            every { name } returns "Headphones"
            every { address } returns "00:11:22:33:44:55"
        }

        val peerMin = mapper.mapClassicDevice(
            device = mockDevice,
            extraName = null,
            rssi = Short.MIN_VALUE.toInt()
        )
        assertEquals(-80, peerMin.rssi)

        val peerZero = mapper.mapClassicDevice(
            device = mockDevice,
            extraName = null,
            rssi = 0
        )
        assertEquals(-80, peerZero.rssi)
    }

    @Test
    fun `ble scan result extracts app deviceId from matching service data`() {
        val mockDevice = mockk<BluetoothDevice>(relaxed = true) {
            every { name } returns "Galaxy Note"
            every { address } returns "99:88:77:66:55:44"
        }

        val servicePayload = "usr_alice123".toByteArray(StandardCharsets.UTF_8)
        val mockRecord = mockk<ScanRecord>(relaxed = true) {
            every { getServiceData(ParcelUuid(testServiceUuid)) } returns servicePayload
            every { deviceName } returns "Alice's Galaxy Note"
        }

        val mockResult = mockk<ScanResult>(relaxed = true) {
            every { device } returns mockDevice
            every { scanRecord } returns mockRecord
            every { rssi } returns -58
        }

        val peer = mapper.mapBleScanResult(mockResult, testServiceUuid, timestamp = 5000L)

        assertNotNull(peer)
        assertEquals("usr_alice123", peer!!.deviceId)
        assertEquals("Alice's Galaxy Note", peer.displayName)
        assertEquals("99:88:77:66:55:44", peer.bluetoothAddress)
        assertEquals(-58, peer.rssi)
        assertEquals(TransportType.BLUETOOTH, peer.transportType)
        assertEquals(5000L, peer.lastSeenAt)
    }

    @Test
    fun `ble scan result without service data falls back to normalized MAC address ID`() {
        val mockDevice = mockk<BluetoothDevice>(relaxed = true) {
            every { name } returns "Generic BLE Beacon"
            every { address } returns "DE:AD:BE:EF:00:01"
        }

        val mockRecord = mockk<ScanRecord>(relaxed = true) {
            every { getServiceData(any()) } returns null
            every { deviceName } returns null
        }

        val mockResult = mockk<ScanResult>(relaxed = true) {
            every { device } returns mockDevice
            every { scanRecord } returns mockRecord
            every { rssi } returns -77
        }

        val peer = mapper.mapBleScanResult(mockResult, testServiceUuid)

        assertNotNull(peer)
        assertEquals("deadbeef0001", peer!!.deviceId)
        assertEquals("Generic BLE Beacon", peer.displayName)
        assertEquals("DE:AD:BE:EF:00:01", peer.bluetoothAddress)
        assertEquals(-77, peer.rssi)
    }

    @Test
    fun `ble scan result with null device returns null`() {
        val mockResult = mockk<ScanResult>(relaxed = true) {
            every { device } returns null
        }

        val peer = mapper.mapBleScanResult(mockResult, testServiceUuid)
        assertNull(peer)
    }
}
