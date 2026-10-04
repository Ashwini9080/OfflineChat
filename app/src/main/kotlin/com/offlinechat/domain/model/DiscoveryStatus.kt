package com.offlinechat.domain.model

/**
 * Represents the discrete lifecycle stages of Bluetooth device discovery.
 *
 * Lifecycle flow:
 * Idle -> CheckingBluetooth -> CheckingPermissions -> StartingDiscovery -> Discovering -> DeviceFound -> DiscoveryComplete
 *
 * Terminal/Interrupted states:
 * DiscoveryFailed, DiscoveryCancelled, BluetoothDisabled, BluetoothUnavailable, PermissionRequired, PermissionPermanentlyDenied
 */
sealed interface DiscoveryStatus {
    data object Idle : DiscoveryStatus
    data object CheckingBluetooth : DiscoveryStatus
    data object CheckingPermissions : DiscoveryStatus
    data object StartingDiscovery : DiscoveryStatus
    data object Discovering : DiscoveryStatus
    data class DeviceFound(val count: Int) : DiscoveryStatus
    data object DiscoveryComplete : DiscoveryStatus
    data class DiscoveryFailed(val reason: String) : DiscoveryStatus
    data object DiscoveryCancelled : DiscoveryStatus
    data object BluetoothDisabled : DiscoveryStatus
    data object BluetoothUnavailable : DiscoveryStatus
    data class PermissionRequired(val permissions: List<String>) : DiscoveryStatus
    data object PermissionDenied : DiscoveryStatus
    data object PermissionPermanentlyDenied : DiscoveryStatus
    data object PermissionRevoked : DiscoveryStatus
    data object PermissionGranted : DiscoveryStatus
    data object ReadyForDiscovery : DiscoveryStatus
}
