package com.offlinechat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerConnectionStateTest {

    @Test
    fun `peer connection states reflect expected types and properties`() {
        val idle: PeerConnectionState = PeerConnectionState.Idle
        val connecting: PeerConnectionState = PeerConnectionState.Connecting
        val authenticating: PeerConnectionState = PeerConnectionState.Authenticating
        val connected: PeerConnectionState = PeerConnectionState.Connected
        val disconnecting: PeerConnectionState = PeerConnectionState.Disconnecting
        val disconnected: PeerConnectionState = PeerConnectionState.Disconnected
        val failed: PeerConnectionState = PeerConnectionState.Failed("Socket timed out")
        val connFailed: PeerConnectionState = PeerConnectionState.ConnectionFailed("Host unreachable")
        val connRejected: PeerConnectionState = PeerConnectionState.ConnectionRejected("Connection refused by peer")
        val connTimeout: PeerConnectionState = PeerConnectionState.ConnectionTimeout(15000L)
        val connLost: PeerConnectionState = PeerConnectionState.ConnectionLost("Radio link dropped")
        val btDisabled: PeerConnectionState = PeerConnectionState.BluetoothDisabled
        val permRevoked: PeerConnectionState = PeerConnectionState.PermissionRevoked

        assertTrue(idle is PeerConnectionState.Idle)
        assertTrue(connecting is PeerConnectionState.Connecting)
        assertTrue(authenticating is PeerConnectionState.Authenticating)
        assertTrue(connected is PeerConnectionState.Connected)
        assertTrue(disconnecting is PeerConnectionState.Disconnecting)
        assertTrue(disconnected is PeerConnectionState.Disconnected)

        assertTrue(failed is PeerConnectionState.Failed)
        assertEquals("Socket timed out", (failed as PeerConnectionState.Failed).reason)

        assertTrue(connFailed is PeerConnectionState.ConnectionFailed)
        assertEquals("Host unreachable", (connFailed as PeerConnectionState.ConnectionFailed).reason)

        assertTrue(connRejected is PeerConnectionState.ConnectionRejected)
        assertEquals("Connection refused by peer", (connRejected as PeerConnectionState.ConnectionRejected).reason)

        assertTrue(connTimeout is PeerConnectionState.ConnectionTimeout)
        assertEquals(15000L, (connTimeout as PeerConnectionState.ConnectionTimeout).timeoutMs)

        assertTrue(connLost is PeerConnectionState.ConnectionLost)
        assertEquals("Radio link dropped", (connLost as PeerConnectionState.ConnectionLost).reason)

        assertTrue(btDisabled is PeerConnectionState.BluetoothDisabled)
        assertTrue(permRevoked is PeerConnectionState.PermissionRevoked)
    }
}
