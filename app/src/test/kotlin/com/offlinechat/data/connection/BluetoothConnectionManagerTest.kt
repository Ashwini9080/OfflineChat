package com.offlinechat.data.connection

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.PeerConnectionState
import com.offlinechat.domain.model.TransportType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothConnectionManagerTest {

    private lateinit var context: Context
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothDevice: BluetoothDevice
    private lateinit var bluetoothSocket: BluetoothSocket

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val samplePeer = Peer(
        deviceId = "peer-device-123",
        displayName = "Rahul's Phone",
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        transportType = TransportType.BLUETOOTH,
        rssi = -65
    )

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        bluetoothManager = mockk(relaxed = true)
        bluetoothAdapter = mockk(relaxed = true)
        bluetoothDevice = mockk(relaxed = true)
        bluetoothSocket = mockk(relaxed = true)

        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns bluetoothAdapter
        every { bluetoothAdapter.isEnabled } returns true
        every { bluetoothAdapter.getRemoteDevice("AA:BB:CC:DD:EE:FF") } returns bluetoothDevice
        every { bluetoothDevice.createInsecureRfcommSocketToServiceRecord(any()) } returns bluetoothSocket
        every { bluetoothDevice.bondState } returns BluetoothDevice.BOND_BONDED

        val pipeOut = java.io.PipedOutputStream()
        val inStream = java.io.PipedInputStream(pipeOut)
        val outStream = ByteArrayOutputStream()
        every { bluetoothSocket.inputStream } returns inStream
        every { bluetoothSocket.outputStream } returns outStream
        every { bluetoothSocket.isConnected } returns true
    }

    @Test
    fun `initial state is Idle for unknown peer`() {
        val manager = BluetoothConnectionManager(context, testDispatcher)
        val state = manager.getConnectionState("unknown-peer")
        assertTrue(state is PeerConnectionState.Idle)
    }

    @Test
    fun `successful connection transitions to Connected`() = runTest(testDispatcher) {
        val manager = BluetoothConnectionManager(context, testDispatcher)

        every { bluetoothSocket.connect() } returns Unit

        val result = manager.connect(samplePeer)

        assertTrue(result.isSuccess)
        val finalState = manager.getConnectionState(samplePeer.deviceId)
        assertTrue(finalState is PeerConnectionState.Connected)
        assertTrue(manager.isConnected(samplePeer.deviceId))
    }

    @Test
    fun `duplicate connection attempt while connected returns success immediately`() = runTest(testDispatcher) {
        val manager = BluetoothConnectionManager(context, testDispatcher)

        every { bluetoothSocket.connect() } returns Unit
        manager.connect(samplePeer)

        // Attempt second connection
        val secondResult = manager.connect(samplePeer)

        assertTrue(secondResult.isSuccess)
        // Verify socket.connect was not called a second time
        verify(exactly = 1) { bluetoothSocket.connect() }
    }

    @Test
    fun `connection failure transitions to ConnectionFailed and returns failure`() = runTest(testDispatcher) {
        val manager = BluetoothConnectionManager(context, testDispatcher)

        every { bluetoothSocket.connect() } throws IOException("read failed, socket might closed or timeout")

        val result = manager.connect(samplePeer)

        assertTrue(result.isFailure)
        assertFalse(manager.isConnected(samplePeer.deviceId))
        val state = manager.getConnectionState(samplePeer.deviceId)
        assertTrue(state is PeerConnectionState.ConnectionFailed || state is PeerConnectionState.ConnectionRejected)
    }

    @Test
    fun `bluetooth disabled returns failure and transitions to BluetoothDisabled`() = runTest(testDispatcher) {
        every { bluetoothAdapter.isEnabled } returns false

        val manager = BluetoothConnectionManager(context, testDispatcher)
        val result = manager.connect(samplePeer)

        assertTrue(result.isFailure)
        val state = manager.getConnectionState(samplePeer.deviceId)
        assertTrue(state is PeerConnectionState.BluetoothDisabled)
    }

    @Test
    fun `disconnect transitions to Disconnected and closes socket`() = runTest(testDispatcher) {
        val manager = BluetoothConnectionManager(context, testDispatcher)

        every { bluetoothSocket.connect() } returns Unit
        manager.connect(samplePeer)

        manager.disconnect(samplePeer.deviceId)

        val state = manager.getConnectionState(samplePeer.deviceId)
        assertTrue(state is PeerConnectionState.Disconnected)
        assertFalse(manager.isConnected(samplePeer.deviceId))
        verify { bluetoothSocket.close() }
    }

    @Test
    fun `cancelConnection resets state to Disconnected`() = runTest(testDispatcher) {
        val manager = BluetoothConnectionManager(context, testDispatcher)

        manager.cancelConnection(samplePeer.deviceId)

        val state = manager.getConnectionState(samplePeer.deviceId)
        assertTrue(state is PeerConnectionState.Disconnected)
    }
}
