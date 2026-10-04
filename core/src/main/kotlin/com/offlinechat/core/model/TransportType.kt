package com.offlinechat.core.model

/**
 * Identifies which physical transport mechanism is being used.
 *
 * The [displayName] is shown in the UI (e.g., in the peer list) to give users
 * context about how a connection was established.
 *
 * When a new transport is implemented (e.g., Wi-Fi Direct), add a new entry here.
 * Callers that switch on [TransportType] will get a compile-time exhaustiveness
 * warning if the new entry is not handled.
 */
enum class TransportType(val displayName: String) {

    /** Bluetooth Classic RFCOMM stream, discovered via BLE advertising. */
    BLUETOOTH("Bluetooth"),

    /**
     * Wi-Fi Direct (P2P) transport.
     * Reserved for Phase 3 — the interface is defined but not yet implemented.
     */
    WIFI_DIRECT("Wi-Fi Direct"),

    /**
     * Local Wi-Fi (same AP / hotspot).
     * Reserved for future phases.
     */
    WIFI_LOCAL("Local Wi-Fi"),
}
