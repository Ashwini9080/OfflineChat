package com.offlinechat.core.model

/**
 * Represents a conversation thread between this device and one or more peers.
 *
 * For Phase 2 only [ConversationType.DIRECT] is used. [ConversationType.GROUP]
 * is modelled now so that the storage schema and ViewModel state handle it
 * without a breaking migration later.
 *
 * @param id              UUID v4 — stable across devices (shared by all participants).
 * @param type            DIRECT (1:1) or GROUP (multi-party).
 * @param participantIds  All participant [DeviceIdentity.id]s including this device.
 * @param displayName     Explicit name for group chats; null for 1:1 (derive from peer).
 * @param lastMessageId   [Message.id] of the most recent message, for preview display.
 * @param lastActivityAt  Unix epoch ms of the most recent activity — used for sorting.
 * @param unreadCount     Count of messages where [Message.readAt] is null and
 *                        [Message.isOutbound] is false.
 */
data class Conversation(
    val id: String,
    val type: ConversationType,
    val participantIds: List<String>,
    val displayName: String? = null,
    val lastMessageId: String? = null,
    val lastActivityAt: Long = 0L,
    val unreadCount: Int = 0,
) {
    /**
     * Returns the display name for a 1:1 conversation.
     * For DIRECT conversations, [displayName] is always null — the caller
     * must resolve the peer name from its [DeviceIdentity].
     */
    val isDirect: Boolean get() = type == ConversationType.DIRECT

    /**
     * The remote peer's ID for a DIRECT conversation.
     * Returns null for GROUP conversations — use [participantIds] instead.
     *
     * @param ownDeviceId This device's [DeviceIdentity.id], needed to determine
     *                    which participant is "the other person".
     */
    fun remotePeerId(ownDeviceId: String): String? {
        if (!isDirect) return null
        return participantIds.firstOrNull { it != ownDeviceId }
    }
}

// ── Conversation type ─────────────────────────────────────────────────────────

enum class ConversationType {

    /** Two participants: this device and one peer. */
    DIRECT,

    /**
     * Three or more participants sharing a group session key.
     * Implemented in Phase 4.
     */
    GROUP,
}
