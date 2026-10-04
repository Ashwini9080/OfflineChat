package com.offlinechat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerConnectionStateTest {

    @Test
    fun `peer connection states reflect expected types`() {
        val disconnected: PeerConnectionState = PeerConnectionState.Disconnected
        val connecting: PeerConnectionState = PeerConnectionState.Connecting
        val authenticating: PeerConnectionState = PeerConnectionState.Authenticating
        val connected: PeerConnectionState = PeerConnectionState.Connected
        val failed: PeerConnectionState = PeerConnectionState.Failed("Socket timed out")

        assertTrue(disconnected is PeerConnectionState.Disconnected)
        assertTrue(connecting is PeerConnectionState.Connecting)
        assertTrue(authenticating is PeerConnectionState.Authenticating)
        assertTrue(connected is PeerConnectionState.Connected)
        assertTrue(failed is PeerConnectionState.Failed)
        assertEquals("Socket timed out", (failed as PeerConnectionState.Failed).reason)
    }
}
