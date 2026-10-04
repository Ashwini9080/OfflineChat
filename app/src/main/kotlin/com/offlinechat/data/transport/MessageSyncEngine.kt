package com.offlinechat.data.transport

import android.content.Context
import android.util.Log
import com.offlinechat.domain.model.EncryptedMessagePayload
import com.offlinechat.domain.model.HandshakePayload
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.model.PeerTrustState
import com.offlinechat.domain.model.TransportType
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import com.offlinechat.domain.repository.PeerRepository
import com.offlinechat.domain.repository.PreferencesRepository
import com.offlinechat.domain.usecase.ReceiveMessageUseCase
import com.offlinechat.security.IdentityManager
import com.offlinechat.security.MessageSecurity
import com.offlinechat.service.ConnectionForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized, application-scoped message synchronization and delivery engine.
 *
 * Responsibilities:
 * 1. Collects incoming envelopes from active transports.
 * 2. Authenticates peer handshakes, detects key replacement attacks, and establishes ECDH session keys.
 * 3. Validates, authenticates, and decrypts incoming messages via [ReceiveMessageUseCase].
 * 4. Triggers bidirectional DELIVERY_ACK delivery receipts upon message receipt.
 * 5. Handles incoming ACK receipts to transition outbound messages from SENT -> DELIVERED.
 * 6. Enforces a 15-second delivery ACK timeout transitioning unacknowledged SENT messages to FAILED.
 * 7. Tracks active UI conversation to accurately increment unread counts when user is not viewing the chat.
 * 8. Flushes pending offline message queues using AES-256-GCM AEAD encryption when peers connect.
 */
@Singleton
class MessageSyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transport: MessageTransport,
    private val receiveMessageUseCase: ReceiveMessageUseCase,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val peerRepository: PeerRepository,
    private val identityManager: IdentityManager,
    private val messageSecurity: MessageSecurity,
    private val preferencesRepository: PreferencesRepository
) {
    companion object {
        private const val TAG = "MessageSyncEngine"
        const val ACK_TIMEOUT_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    // Active conversation currently displayed to user in ChatScreen
    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    // Map of messageId -> Job for ACK timeout detection
    private val ackTimeouts = ConcurrentHashMap<String, Job>()

    // Track peers to which we have sent our handshake in this session to prevent echo loops
    private val handshakesSent = ConcurrentHashMap.newKeySet<String>()

    fun setActiveConversation(conversationId: String?) {
        _activeConversationId.value = conversationId
    }

    fun watchAckTimeout(messageId: String, timeoutMillis: Long = ACK_TIMEOUT_MS) {
        ackTimeouts.remove(messageId)?.cancel()
        val job = scope.launch {
            delay(timeoutMillis)
            val current = messageRepository.getMessageById(messageId)
            if (current != null && (current.status == MessageStatus.SENT || current.status == MessageStatus.SENDING)) {
                Log.w(TAG, "Delivery ACK timed out after ${timeoutMillis}ms for message: $messageId -> marked FAILED")
                messageRepository.updateMessageStatus(messageId, MessageStatus.FAILED)
            }
            ackTimeouts.remove(messageId)
        }
        ackTimeouts[messageId] = job
    }

    fun start() {
        scope.launch {
            transport.incomingEnvelopes.collect { envelope ->
                handleEnvelope(envelope)
            }
        }
    }

    private suspend fun handleEnvelope(envelope: MessageEnvelope) {
        if (!MessageEnvelope.isValid(envelope)) {
            Log.w(TAG, "Dropping invalid envelope [type=${envelope.messageType}, id=${envelope.messageId}]")
            return
        }

        when (envelope.messageType) {
            "HANDSHAKE" -> handleHandshake(envelope)
            "TEXT" -> handleTextMessage(envelope)
            "ACK", "DELIVERY_ACK" -> handleAck(envelope)
            "READ" -> handleReadReceipt(envelope)
            else -> Log.w(TAG, "Unknown envelope messageType: ${envelope.messageType}")
        }
    }

    suspend fun sendHandshake(peerId: String) {
        try {
            val localName = runCatching { preferencesRepository.displayName.first() }.getOrNull()?.ifBlank { "Offline Peer" } ?: "Offline Peer"
            val payload = messageSecurity.createHandshakePayload(localName)
            val jsonString = json.encodeToString(HandshakePayload.serializer(), payload)

            val envelope = MessageEnvelope(
                protocolVersion = 1,
                messageId = UUID.randomUUID().toString(),
                conversationId = peerId,
                senderId = identityManager.deviceId,
                receiverId = peerId,
                timestamp = System.currentTimeMillis(),
                messageType = "HANDSHAKE",
                payload = jsonString.toByteArray(Charsets.UTF_8)
            )
            transport.sendEnvelope(envelope)
            handshakesSent.add(peerId)
            Log.d(TAG, "Sent authenticated handshake to peer: $peerId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send handshake to $peerId", e)
        }
    }

    private suspend fun handleHandshake(envelope: MessageEnvelope) {
        try {
            val payload = json.decodeFromString(HandshakePayload.serializer(), String(envelope.payload, Charsets.UTF_8))
            Log.d(TAG, "SyncEngine processing handshake for: ${payload.displayName} [${payload.deviceId}]")

            // 1. Process authenticated handshake & key agreement via MessageSecurity
            val handshakeResult = messageSecurity.processHandshake(payload)

            if (handshakeResult.isIdentityChanged) {
                Log.w(TAG, "SECURITY ALERT: Peer identity changed for ${payload.deviceId}! Message queue flush aborted.")
                return
            }

            // 2. Send mutual handshake if not yet sent in this session
            if (!handshakesSent.contains(payload.deviceId)) {
                sendHandshake(payload.deviceId)
            }

            // 3. Ensure direct conversation exists
            conversationRepository.getOrCreateConversation(
                peerId = payload.deviceId,
                peerDisplayName = payload.displayName,
                transportType = TransportType.BLUETOOTH
            )

            // 4. Keep connection foreground service running with peer name
            ConnectionForegroundService.startService(context, payload.displayName)

            // 5. Flush any queued offline messages for this newly connected peer (only if not revoked)
            if (handshakeResult.trustState != PeerTrustState.REVOKED) {
                flushPendingMessages(payload.deviceId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling handshake", e)
        }
    }

    private suspend fun handleTextMessage(envelope: MessageEnvelope) {
        try {
            // Validate, authenticate, decrypt, and store message in Room database
            val result = receiveMessageUseCase(envelope)
            if (result.isFailure) {
                Log.w(TAG, "Failed to process incoming text message: ${result.exceptionOrNull()?.message}")
                return
            }

            val savedMessage = result.getOrNull()
            if (savedMessage != null) {
                Log.i(TAG, "Saved authenticated incoming message [${savedMessage.id}] from ${savedMessage.senderId}")

                // Update unread count if user is not currently viewing this conversation
                val targetConversationId = savedMessage.conversationId
                if (_activeConversationId.value != targetConversationId) {
                    conversationRepository.incrementUnreadCount(targetConversationId)
                    Log.d(TAG, "Incremented unread count for conversation: $targetConversationId")
                } else {
                    conversationRepository.markAsRead(targetConversationId)
                }
            } else {
                Log.d(TAG, "Message [${envelope.messageId}] was duplicate; skipping storage")
            }

            // Always transmit DELIVERY_ACK receipt back to sender
            val ackEnvelope = MessageEnvelope(
                protocolVersion = 1,
                messageId = UUID.randomUUID().toString(),
                conversationId = envelope.conversationId,
                senderId = identityManager.deviceId,
                receiverId = envelope.senderId,
                timestamp = System.currentTimeMillis(),
                messageType = "DELIVERY_ACK",
                payload = envelope.messageId.toByteArray(Charsets.UTF_8)
            )
            transport.sendEnvelope(ackEnvelope)
            Log.d(TAG, "Sent DELIVERY_ACK receipt for message [${envelope.messageId}] to ${envelope.senderId}")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming text message", e)
        }
    }

    private suspend fun handleAck(envelope: MessageEnvelope) {
        try {
            val originalMessageId = String(envelope.payload, Charsets.UTF_8).trim()
            Log.i(TAG, "Received delivery ACK for message ID: $originalMessageId")
            ackTimeouts.remove(originalMessageId)?.cancel()
            messageRepository.updateMessageStatus(originalMessageId, MessageStatus.DELIVERED)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing ACK", e)
        }
    }

    private suspend fun handleReadReceipt(envelope: MessageEnvelope) {
        try {
            val originalMessageId = String(envelope.payload, Charsets.UTF_8).trim()
            Log.d(TAG, "Received READ receipt for message ID: $originalMessageId")
            // READ receipt also confirms delivery
            ackTimeouts.remove(originalMessageId)?.cancel()
            messageRepository.updateMessageStatus(originalMessageId, MessageStatus.DELIVERED)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing READ receipt", e)
        }
    }

    suspend fun flushPendingMessages(peerId: String) {
        val trustState = messageSecurity.getPeerTrustState(peerId)
        if (trustState == PeerTrustState.REVOKED) {
            Log.w(TAG, "Cannot flush pending messages: Peer $peerId identity is REVOKED")
            return
        }

        val pending = messageRepository.getPendingOutboundMessagesForPeer(peerId)
        if (pending.isEmpty()) return

        Log.d(TAG, "Flushing ${pending.size} pending offline messages for peer: $peerId")
        for (msg in pending) {
            // 1. Transition to SENDING
            messageRepository.updateMessageStatus(msg.id, MessageStatus.SENDING)

            try {
                // 2. Encrypt plaintext
                val encrypted = messageSecurity.encryptMessage(
                    plaintext = msg.text.toByteArray(Charsets.UTF_8),
                    recipientPeerId = peerId,
                    messageId = msg.id
                )
                val jsonPayload = json.encodeToString(EncryptedMessagePayload.serializer(), encrypted)

                val envelope = MessageEnvelope(
                    protocolVersion = 1,
                    messageId = msg.id,
                    conversationId = msg.conversationId,
                    senderId = msg.senderId,
                    receiverId = msg.receiverId,
                    timestamp = msg.timestamp,
                    messageType = "TEXT",
                    payload = jsonPayload.toByteArray(Charsets.UTF_8),
                    nonce = Base64.getDecoder().decode(encrypted.nonceBase64),
                    signature = Base64.getDecoder().decode(encrypted.signatureBase64)
                )

                val result = transport.sendEnvelope(envelope)
                if (result.isSuccess) {
                    // 3. Transition to SENT and register ACK timeout watcher
                    messageRepository.updateMessageStatus(msg.id, MessageStatus.SENT)
                    watchAckTimeout(msg.id)
                    Log.d(TAG, "Successfully flushed message [${msg.id}] to $peerId (SENT, awaiting ACK)")
                } else {
                    messageRepository.updateMessageStatus(msg.id, MessageStatus.FAILED)
                    Log.w(TAG, "Failed to flush pending message [${msg.id}]", result.exceptionOrNull())
                    break
                }
            } catch (e: Exception) {
                messageRepository.updateMessageStatus(msg.id, MessageStatus.FAILED)
                Log.e(TAG, "Failed to encrypt/flush pending message [${msg.id}]", e)
                break
            }
        }
    }
}
