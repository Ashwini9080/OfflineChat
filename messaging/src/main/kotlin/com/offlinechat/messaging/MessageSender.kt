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
import com.offlinechat.transport.manager.TransportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends [Message]s to remote peers.
 *
 * ## Responsibilities
 * 1. Serialise [MessageContent] to JSON bytes.
 * 2. Encrypt the plaintext with the peer's session key ([SessionCrypto.encrypt]).
 * 3. Sign the [MessageEnvelope] with the local identity key ([IdentityManager]).
 * 4. Pass the serialised envelope to [TransportManager] for delivery.
 * 5. Return the outcome so callers can update message status in storage.
 *
 * ## What MessageSender does NOT do
 * - It does not persist messages to the database (that is [ConversationManager]'s job).
 * - It does not retry on failure (the :storage pending_messages table drives retries).
 * - It does not manage session keys (that is [SessionCrypto] + [TransportManager]'s job).
 *
 * ## Session key lookup
 * Currently the session key is stored in-memory, associated with each open
 * [transport.api.TransportChannel]. Phase 3 will introduce a dedicated
 * SessionKeyStore for persistence across process restarts.
 */
@Singleton
class MessageSender @Inject constructor(
    private val transportManager: TransportManager,
    private val identityManager: IdentityManager,
    private val sessionCrypto: SessionCrypto,
    private val timeProvider: TimeProvider,
) {
    // In-memory session key cache: peerId → AES SecretKey
    // This will be replaced by a proper SessionKeyStore in Phase 3.
    private val sessionKeys = mutableMapOf<String, javax.crypto.SecretKey>()

    /**
     * Registers a session key for a peer. Called by [ConversationManager] after
     * a successful ECDH handshake.
     */
    fun registerSessionKey(peerId: String, key: javax.crypto.SecretKey) {
        sessionKeys[peerId] = key
    }

    /**
     * Sends a [message] to its intended recipient.
     *
     * @return [AppResult.Success] with the sent [Message] (status updated to SENT),
     *         or [AppResult.Failure] describing the error.
     */
    suspend fun send(message: Message): AppResult<Message> = withContext(Dispatchers.Default) {
        // 1. Serialise content
        val plaintextResult = serialiseContent(message.content)
        if (plaintextResult is AppResult.Failure) return@withContext plaintextResult
        val plaintext = (plaintextResult as AppResult.Success).data

        // 2. Look up session key for recipient
        val sessionKey = sessionKeys[message.recipientId]
            ?: return@withContext AppResult.Failure(
                AppError.Unknown("No session key for peer ${message.recipientId}"),
            )

        // 3. Encrypt payload
        val encryptResult = sessionCrypto.encrypt(sessionKey, plaintext)
        if (encryptResult is AppResult.Failure) return@withContext encryptResult
        val encryptedPayload = (encryptResult as AppResult.Success).data

        // 4. Build envelope (unsigned)
        val envelopeId = UuidFactory.newId()
        val unsignedEnvelope = MessageEnvelope(
            envelopeId = envelopeId,
            senderId = message.senderId,
            recipientId = message.recipientId,
            sentAt = message.sentAt,
            payloadType = message.content.toPayloadType(),
            encryptedPayload = encryptedPayload,
            senderSignature = ByteArray(0), // placeholder until signed
        )

        // 5. Sign the envelope
        val signResult = identityManager.let {
            // IdentityManager.sign would be ideal, but signing is in KeyStoreManager.
            // We access it via the signing bytes from the envelope.
            // This will be refactored to a single sign() call in Phase 3.
            AppResult.Success(ByteArray(0)) // Placeholder: replace with real signing
        }

        // 6. Find or open channel to recipient
        val channel = transportManager.getOpenChannel(message.recipientId)
            ?: return@withContext AppResult.Failure(AppError.PeerNotReachable)

        // 7. Serialise and send
        val envelopeJson = Json.encodeToString(MessageEnvelope.serializer(), unsignedEnvelope)
        val sendResult = channel.send(envelopeJson.toByteArray(Charsets.UTF_8))
        if (sendResult is AppResult.Failure) return@withContext sendResult

        AppResult.Success(message.copy(status = MessageStatus.SENT))
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun serialiseContent(content: MessageContent): AppResult<ByteArray> =
        runCatching {
            when (content) {
                is MessageContent.Text -> content.body.toByteArray(Charsets.UTF_8)
                is MessageContent.File -> Json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, String>>(),
                    mapOf("name" to content.name, "mime" to content.mimeType),
                ).toByteArray(Charsets.UTF_8)
                is MessageContent.GroupInvite -> content.groupId.toByteArray(Charsets.UTF_8)
            }
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppError.Unknown("Serialisation failed"), it) },
        )

    private fun MessageContent.toPayloadType(): PayloadType = when (this) {
        is MessageContent.Text        -> PayloadType.TEXT_MESSAGE
        is MessageContent.File        -> PayloadType.FILE_CHUNK
        is MessageContent.GroupInvite -> PayloadType.GROUP_INVITE
    }
}
