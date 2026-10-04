package com.offlinechat.core.model

/**
 * The long-term cryptographic identity of this device.
 *
 * Created once on first launch and persisted securely via [security.IdentityManager].
 * Never transmitted in plaintext — only [publicSigningKey] and [publicDhKey] are shared.
 *
 * @param id             Derived as hex(SHA-256(publicSigningKey))[0..31] — stable, unique.
 * @param displayName    User-chosen or device-model-derived human readable name.
 * @param publicSigningKey  Raw bytes of the EC P-256 public key used for signing.
 * @param publicDhKey    Raw bytes of the EC P-256 public key used for key exchange.
 *                       This is the EPHEMERAL key generated per-session at runtime
 *                       from in-memory keypairs — not stored long-term.
 * @param createdAt      Unix epoch ms when the identity was first generated.
 */
data class DeviceIdentity(
    val id: String,
    val displayName: String,
    val publicSigningKey: ByteArray,
    val publicDhKey: ByteArray,
    val createdAt: Long,
) {
    // ── Custom equals/hashCode because ByteArray uses referential equality ──────

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceIdentity) return false
        return id == other.id &&
            displayName == other.displayName &&
            publicSigningKey.contentEquals(other.publicSigningKey) &&
            publicDhKey.contentEquals(other.publicDhKey) &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + publicSigningKey.contentHashCode()
        result = 31 * result + publicDhKey.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "DeviceIdentity(id=$id, displayName=$displayName, createdAt=$createdAt)"
}
