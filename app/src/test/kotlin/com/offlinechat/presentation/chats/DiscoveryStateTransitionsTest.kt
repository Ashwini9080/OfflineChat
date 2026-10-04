package com.offlinechat.presentation.chats

import com.offlinechat.domain.model.DiscoveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryStateTransitionsTest {

    @Test
    fun `idle state initialization defaults`() {
        val state = DiscoveryUiState()
        assertEquals(DiscoveryStatus.Idle, state.discoveryStatus)
        assertFalse(state.isScanning)
        assertFalse(state.isBluetoothDisabled)
        assertFalse(state.isBluetoothUnavailable)
        assertFalse(state.showPermissionRationale)
        assertTrue(state.peers.isEmpty())
    }

    @Test
    fun `transition to Discovering activates scanning indicator`() {
        var state = DiscoveryUiState()
        val status = DiscoveryStatus.Discovering

        state = state.copy(
            discoveryStatus = status,
            isScanning = true,
            isBluetoothDisabled = false
        )

        assertTrue(state.isScanning)
        assertEquals(DiscoveryStatus.Discovering, state.discoveryStatus)
        assertFalse(state.isBluetoothDisabled)
    }

    @Test
    fun `transition to BluetoothDisabled deactivates scanning and flags disabled banner`() {
        var state = DiscoveryUiState(isScanning = true)
        val status = DiscoveryStatus.BluetoothDisabled

        state = state.copy(
            discoveryStatus = status,
            isBluetoothDisabled = true,
            isScanning = false
        )

        assertFalse(state.isScanning)
        assertTrue(state.isBluetoothDisabled)
        assertEquals(DiscoveryStatus.BluetoothDisabled, state.discoveryStatus)
    }

    @Test
    fun `transition to PermissionRequired flags rationale and records missing list`() {
        var state = DiscoveryUiState(isScanning = true)
        val missing = listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT")
        val status = DiscoveryStatus.PermissionRequired(missing)

        state = state.copy(
            discoveryStatus = status,
            missingPermissions = status.permissions,
            showPermissionRationale = true,
            isScanning = false
        )

        assertFalse(state.isScanning)
        assertTrue(state.showPermissionRationale)
        assertEquals(2, state.missingPermissions.size)
        assertEquals("android.permission.BLUETOOTH_SCAN", state.missingPermissions[0])
    }

    @Test
    fun `transition to PermissionPermanentlyDenied flags settings redirect`() {
        var state = DiscoveryUiState()
        val status = DiscoveryStatus.PermissionPermanentlyDenied

        state = state.copy(
            discoveryStatus = status,
            isPermissionPermanentlyDenied = true,
            showPermissionRationale = true,
            isScanning = false
        )

        assertTrue(state.isPermissionPermanentlyDenied)
        assertTrue(state.showPermissionRationale)
    }

    @Test
    fun `transition to DiscoveryFailed exposes human readable error message`() {
        var state = DiscoveryUiState(isScanning = true)
        val status = DiscoveryStatus.DiscoveryFailed("Bluetooth adapter discovery failed to start")

        state = state.copy(
            discoveryStatus = status,
            errorMessage = status.reason,
            isScanning = false
        )

        assertFalse(state.isScanning)
        assertEquals("Bluetooth adapter discovery failed to start", state.errorMessage)
    }

    @Test
    fun `transition to DiscoveryComplete resets scanning flag`() {
        var state = DiscoveryUiState(isScanning = true)
        val status = DiscoveryStatus.DiscoveryComplete

        state = state.copy(
            discoveryStatus = status,
            isScanning = false
        )

        assertFalse(state.isScanning)
        assertEquals(DiscoveryStatus.DiscoveryComplete, state.discoveryStatus)
    }

    @Test
    fun `transition to PermissionRevoked stops scan and triggers rationale with missing list`() {
        var state = DiscoveryUiState(isScanning = true)
        val missing = listOf("android.permission.BLUETOOTH_SCAN")
        val status = DiscoveryStatus.PermissionRevoked

        state = state.copy(
            discoveryStatus = status,
            missingPermissions = missing,
            showPermissionRationale = true,
            isScanning = false
        )

        assertFalse(state.isScanning)
        assertTrue(state.showPermissionRationale)
        assertEquals(1, state.missingPermissions.size)
        assertEquals(DiscoveryStatus.PermissionRevoked, state.discoveryStatus)
    }

    @Test
    fun `transition to ReadyForDiscovery clears disabled flag`() {
        var state = DiscoveryUiState(isBluetoothDisabled = true)
        val status = DiscoveryStatus.ReadyForDiscovery

        state = state.copy(
            discoveryStatus = status,
            isBluetoothDisabled = false
        )

        assertFalse(state.isBluetoothDisabled)
        assertEquals(DiscoveryStatus.ReadyForDiscovery, state.discoveryStatus)
    }

    @Test
    fun `transition to DeviceFound updates count and preserves scanning state`() {
        var state = DiscoveryUiState(isScanning = true)
        val status = DiscoveryStatus.DeviceFound(3)

        state = state.copy(discoveryStatus = status)

        assertTrue(state.isScanning)
        assertEquals(3, (state.discoveryStatus as DiscoveryStatus.DeviceFound).count)
    }
}
