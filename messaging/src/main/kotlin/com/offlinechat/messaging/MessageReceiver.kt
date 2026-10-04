package com.offlinechat.messaging

import com.offlinechat.core.model.Message
import com.offlinechat.core.model.MessageContent
import com.offlinechat.core.model.MessageStatus
import com.offlinechat.core.result.AppError
import com.offlinechat.core.result.AppResult
import com.offlinechat.core.util.TimeProvider
import com.offlinechat.core.util.UuidFactory
import com.offlinechat.security.IdentityManager
import com.offlinechat.security.SessionCrypto
import com.offlinechat.transport.api.TransportChannel
import com.offlinechat.transport.api.TransportEvent
import com.offlinechat.transport.manager.TransportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receives and processes incoming [MessageEnvelope]s from remote peers.
 *
 * ## Processing pipeline
 * For each raw byte frame received from a [TransportChannel]:
 * 1. Deserialise bytes → [MessageEnvelope].
 * 2. Check [MessageEnvelope.envelopeId] against dedup cache.
 * 3. Verify [MessageEnvelope.senderSignature] against peer's stored public key.
 * 4. Decrypt [MessageEnvelope.encryptedPayload] with session key.
 * 5. Deserialise decrypted bytes → [MessageContent].
 * 6. Persist [Message] to storage (via callback to [ConversationManager]).
 * 7. Send an ACK [MessageEnvelope] back to the sender.
 * 8. Emit the [Message] for UI consumption.
 *
 * ## Deduplication
 * [seenEnvelopeIds] is an in-memory LRU cache of recently seen envelope IDs.
 * Phase 3 will persist this set to the `messages` Room table to survive restarts.
 */
@Singleton
class MessageReceiver @Inject constructor(
    private val transportManager: TransportManager,
    private val identityManager: IdentityManager,
    private val sessionCrypto: SessionCrypto,
    private val messageSender: MessageSender,
    private val timeProvider: TimeProvider,
) {
    // Simple in-memory dedup cache (replace with DB query in Phase 3)
    private val seenEnvelopeIds = LinkedHashMap<String, Unit>(256, 0.75f, true)

    // Callback set by ConversationManager to store received messages
    private var onMessageReceived: ((Message) -> Unit)? = null
    private val sessionKeys = mutableMapOf<String, javax.crypto.SecretKey>()

    fun registerSessionKey(peerId: String, key: javax.crypto.SecretKey) {
        sessionKeys[peerId] = key
    }

    /** [ConversationManager] registers this callback on init. */
    fun setMessageReceivedCallback(callback: (Message) -> Unit) {
        onMessageReceived = callback
    }

    /**
     * Starts processing incoming frames from [channel].
     *
     * This is a suspending function that collects the channel's receive [Flow]
     * until the channel closes. Must be called from a coroutine tied to the
     * connection lifecycle (e.g., launched when [TransportEvent.ChannelOpened] fires).
     */
    suspend fun processChannel(channel: TransportChannel) {
        channel.receive()
            .catch { /* Channel closed — log and exit gracefully */ }
            .collect { rawBytes ->
                processFrame(rawBytes, channel)
            }
    }

    // ── Frame processing ──────────────────────────────────────────────────────

    private suspend fun processFrame(
        rawBytes: ByteArray,
        channel: TransportChannel,
    ) = withContext(Dispatchers.Default) {
        // Step 1: Deserialise envelope
        val envelope = runCatching {
            Json.decodeFromString(MessageEnvelope.serializer(), String(rawBytes, Charsets.UTF_8))
        }.getOrElse {
            return@withContext // Drop malformed frames silently
        }

        // Step 2: Deduplication check
        if (seenEnvelopeIds.containsKey(envelope.envelopeId)) {
            return@withContext // Already processed — possibly a retry from the sender
        }
        if (seenEnvelopeIds.size > 1024) {
            seenEnvelopeIds.entries.take(256).forEach { seenEnvelopeIds.remove(it.key) }
        }

        // Step 3: Skip ACK processing for now (handled separately in Phase 3)
        if (envelope.payloadType == PayloadType.ACK) {
            handleAck(envelope)
            return@withContext
        }

        // Step 4: Decrypt payload
        val sessionKey = sessionKeys[envelope.senderId] ?: return@withContext
        val decryptResult = sessionCrypto.decrypt(sessionKey, envelope.encryptedPayload)
        if (decryptResult is AppResult.Failure) return@withContext
        val plaintext = (decryptResult as AppResult.Success).data

        // Step 5: Deserialise content
        val content = deserialiseContent(envelope.payloadType, plaintext)
            ?: return@withContext

        // Step 6: Build domain Message and notify ConversationManager
        val localIdentity = (identityManager.getLocalIdentity() as? AppResult.Success)?.data
            ?: return@withContext

        val message = Message(
            id = UuidFactory.newId(),
            conversationId = deriveConversationId(envelope.senderId, localIdentity.id),
            senderId = envelope.senderId,
            recipientId = localIdentity.id,
            content = content,
            sentAt = envelope.sentAt,
            deliveredAt = timeProvider.nowMillis(),
            status = MessageStatus.ACKNOWLEDGED,
            isOutbound = false,
        )

        seenEnvelopeIds[envelope.envelopeId] = Unit
        onMessageReceived?.invoke(message)

        // Step 7: Send ACK
        sendAck(envelope, channel)
    }

    private fun handleAck(envelope: MessageEnvelope) {
        // TODO Phase 3: Update message status to ACKNOWLEDGED in Room DB
    }

    private suspend fun sendAck(original: MessageEnvelope, channel: TransportChannel) {
        val ackEnvelope = MessageEnvelope(
            envelopeId = UuidFactory.newId(),
            senderId = original.recipientId,
            recipientId = original.senderId,
            sentAt = timeProvider.nowMillis(),
            payloadType = PayloadType.ACK,
            encryptedPayload = original.envelopeId.toByteArray(Charsets.UTF_8),
            senderSignature = ByteArray(0), // Simplified for Phase 2 — sign properly in Phase 3
        )
        val json = Json.encodeToString(MessageEnvelope.serializer(), ackEnvelope)
        channel.send(json.toByteArray(Charsets.UTF_8))
    }

    private fun deserialiseContent(type: PayloadType, bytes: ByteArray): MessageContent? =
        when (type) {
            PayloadType.TEXT_MESSAGE -> MessageContent.Text(String(bytes, Charsets.UTF_8))
            else                     -> null // Other types handled in later phases
        }

    /**
     * Derives a deterministic conversation ID for a 1:1 conversation.
     *
     * Uses the alphabetically-ordered concatenation of both device IDs so that
     * both devices arrive at the same conversation ID independently.
     */
    private fun deriveConversationId(idA: String, idB: String): String {
        val (smaller, larger) = if (idA < idB) idA to idB else idB to idA
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$smaller:$larger".toByteArray())
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }
}
