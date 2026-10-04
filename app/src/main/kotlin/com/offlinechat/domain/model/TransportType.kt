package com.offlinechat.domain.model

enum class TransportType(val displayName: String) {
    BLUETOOTH("Bluetooth"),
    WIFI_DIRECT("Wi-Fi Direct")
}

enum class MessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    FAILED
}
