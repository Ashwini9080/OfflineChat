package com.offlinechat.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the `peers` table — the local trust store.
 *
 * When a new device is discovered and the user approves the connection, a [PeerEntity]
 * is created with [isTrusted] = true. Subsequent reconnections verify the public key
 * against this stored value — if it changes, the connection is rejected (key mismatch).
 *
 * @param deviceId               Stable [DeviceIdentity.id] (primary key).
 * @param displayName            Last known human-readable name.
 * @param publicSigningKeyBase64 Base64-encoded EC P-256 public key for signature verification.
 * @param bluetoothAddress       Last known Bluetooth MAC, for quick re-connection.
 * @param isTrusted              True once the user has explicitly approved this peer.
 * @param firstSeenAt            Unix epoch ms of first contact.
 * @param lastSeenAt             Unix epoch ms of most recent successful connection.
 */
@Entity(
    tableName = "peers",
    indices = [
        Index(value = ["bluetooth_address"]),
        Index(value = ["last_seen_at"]),
    ],
)
data class PeerEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "public_signing_key_b64")
    val publicSigningKeyBase64: String,

    @ColumnInfo(name = "bluetooth_address")
    val bluetoothAddress: String?,

    @ColumnInfo(name = "is_trusted")
    val isTrusted: Boolean,

    @ColumnInfo(name = "first_seen_at")
    val firstSeenAt: Long,

    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,
)
