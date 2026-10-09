package com.offlinechat.transport

import com.offlinechat.core.model.DeviceIdentity
import com.offlinechat.core.model.TransportType
import com.offlinechat.core.result.AppError
import com.offlinechat.core.result.AppResult
import com.offlinechat.transport.api.PeerDevice
import com.offlinechat.transport.api.TransportChannel
import com.offlinechat.transport.bluetooth.BluetoothTransport
import com.offlinechat.transport.manager.TransportManager
import com.offlinechat.transport.wifi.WiFiDirectTransport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class TransportFallbackAndConcurrencyTest {

    private val bluetoothTransport = mockk<BluetoothTransport>(relaxed = true)
    private val wifiDirectTransport = mockk<WiFiDirectTransport>(relaxed = true)

    private val manager = TransportManager(bluetoothTransport, wifiDirectTransport)

    private val dummySessionKey = SecretKeySpec(ByteArray(32) { 0x01 }, "AES")

    private val samplePeerWithBt = PeerDevice(
        deviceId = "peer_device_abc",
        displayName = "Nearby Phone",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        publicSigningKeyBytes = ByteArray(32),
        publicDhKeyBytes = ByteArray(32),
        rssi = -60,
        transport = TransportType.WIFI_DIRECT,
        discoveredAt = System.currentTimeMillis()
    )

    private val mockWifiChannel = mockk<TransportChannel>(relaxed = true) {
        coEvery { isConnected } returns true
        coEvery { peerId } returns "peer_device_abc"
        coEvery { transportType } returns TransportType.WIFI_DIRECT
        coEvery { sessionKey } returns dummySessionKey
    }

    private val mockBtChannel = mockk<TransportChannel>(relaxed = true) {
        coEvery { isConnected } returns true
        coEvery { peerId } returns "peer_device_abc"
        coEvery { transportType } returns TransportType.BLUETOOTH
        coEvery { sessionKey } returns dummySessionKey
    }

    @Test
    fun testFallbackToBluetoothWhenWifiDirectFails() = runBlocking {
        // Wi-Fi Direct connect fails
        coEvery { wifiDirectTransport.connect(any()) } returns AppResult.Failure(
            AppError.PeerNotReachable
        )
        // Bluetooth connect succeeds
        coEvery { bluetoothTransport.connect(any()) } returns AppResult.Success(mockBtChannel)

        val result = manager.connect(samplePeerWithBt)

        assertTrue(result is AppResult.Success)
        val channel = (result as AppResult.Success).data
        assertEquals(TransportType.BLUETOOTH, channel.transportType)

        // Verify Wi-Fi Direct was attempted first
        coVerify(exactly = 1) { wifiDirectTransport.connect(samplePeerWithBt) }
        // Verify Bluetooth was attempted as fallback
        coVerify(exactly = 1) { bluetoothTransport.connect(match { it.transport == TransportType.BLUETOOTH }) }
    }

    @Test
    fun testNoFallbackWhenWifiDirectSucceeds() = runBlocking {
        coEvery { wifiDirectTransport.connect(any()) } returns AppResult.Success(mockWifiChannel)

        val result = manager.connect(samplePeerWithBt)

        assertTrue(result is AppResult.Success)
        val channel = (result as AppResult.Success).data
        assertEquals(TransportType.WIFI_DIRECT, channel.transportType)

        coVerify(exactly = 1) { wifiDirectTransport.connect(samplePeerWithBt) }
        coVerify(exactly = 0) { bluetoothTransport.connect(any()) }
    }

    @Test
    fun testDuplicateActiveChannelReturnedImmediately() = runBlocking {
        coEvery { wifiDirectTransport.connect(any()) } returns AppResult.Success(mockWifiChannel)

        // First connection
        val result1 = manager.connect(samplePeerWithBt)
        assertTrue(result1 is AppResult.Success)

        // Second connection to the same peer returns already open channel without reconnecting
        val result2 = manager.connect(samplePeerWithBt)
        assertTrue(result2 is AppResult.Success)

        coVerify(exactly = 1) { wifiDirectTransport.connect(samplePeerWithBt) }
    }

    @Test
    fun testShutdownClosesAllChannels() = runBlocking {
        coEvery { wifiDirectTransport.connect(any()) } returns AppResult.Success(mockWifiChannel)
        manager.connect(samplePeerWithBt)

        manager.shutdown()

        coVerify(exactly = 1) { mockWifiChannel.close() }
        coVerify(exactly = 1) { wifiDirectTransport.shutdown() }
        coVerify(exactly = 1) { bluetoothTransport.shutdown() }
    }
}
