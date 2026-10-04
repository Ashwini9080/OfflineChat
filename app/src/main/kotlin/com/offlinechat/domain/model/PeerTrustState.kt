package com.offlinechat.domain.model

/**
 * Peer trust states representing the cryptographic verification lifecycle.
 *
 * UNKNOWN               - Device discovered via Bluetooth but no security handshake completed.
 * CONNECTED             - Bluetooth connected and session established, but identities have not yet been compared.
 * VERIFICATION_REQUIRED - Security verification is required (e.g. before sensitive exchange).
 * VERIFIED              - User has verified the peer's cryptographic Short Authentication String (SAS / Safety Number).
 * REVOKED               - Security alert: Peer presented a different identity key than previously recorded,
 *                         or the user explicitly revoked trust. Messages are blocked until verified.
 */
enum class PeerTrustState(val label: String) {
    UNKNOWN("Unknown"),
    CONNECTED("Connected"),
    VERIFICATION_REQUIRED("Verification Required"),
    VERIFIED("Verified"),
    REVOKED("Revoked")
}
