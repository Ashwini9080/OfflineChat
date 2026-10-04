package com.offlinechat.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "bluetooth_address")
    val bluetoothAddress: String?,

    @ColumnInfo(name = "public_key_b64")
    val publicKeyBase64: String,

    @ColumnInfo(name = "rssi")
    val rssi: Int,

    @ColumnInfo(name = "is_trusted")
    val isTrusted: Boolean,

    @ColumnInfo(name = "transport_type")
    val transportType: String,

    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long
)
