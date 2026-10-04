package com.offlinechat.data.transport

import android.content.Context
import android.util.Log
import com.offlinechat.domain.model.HandshakePayload
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageEnvelope
import com.offlinechat.domain.model.MessageStatus
import com.offlinechat.domain.model.Peer
import com.offlinechat.domain.model.TransportType
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import com.offlinechat.domain.repository.PeerRepository
import com.offlinechat.security.IdentityManager
import com.offlinechat.service.ConnectionForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized, application-scoped message synchronization and delivery engine.
 *
 * Responsibilities:
 * 1. Collects incoming envelopes from the active transport regardless of which UI screen is open.
 * 2. Saves incoming chat messages to Room SQLite database and triggers ACK delivery receipts.
 * 3. Handles incoming ACK receipts to transition outbound messages from SENT -> DELIVERED.
 * 4. Handles mutual handshakes to link peer identities and start foreground services.
 * 5. Flushes pending offline message queues automatically when a peer reconnects.
 */
@Singleton
class MessageSyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transport: MessageTransport,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val peerRepository: PeerRepository,
    private val identityManager: IdentityManager
) {
    companion object {
        private const val TAG = "MessageSyncEngine"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        scope.launch {
            transport.incomingEnvelopes.collect { envelope ->
                handleEnvelope(envelope)
            }
        }
    }

    private suspend fun handleEnvelope(envelope: MessageEnvelope) {
        when (envelope.messageType) {
            "HANDSHAKE" -> handleHandshake(envelope)
            "TEXT" -> handleTextMessage(envelope)
            "ACK" -> handleAck(envelope)
            "READ" -> handleReadReceipt(envelope)
            else -> Log.w(TAG, "Unknown envelope messageType: ${envelope.messageType}")
        }
    }

    private suspend fun handleHandshake(envelope: MessageEnvelope) {
        try {
            val payload = json.decodeFromString(HandshakePayload.serializer(), String(envelope.payload, Charsets.UTF_8))
            Log.d(TAG, "SyncEngine processing handshake for: ${payload.displayName} [${payload.deviceId}]")

            // 1. Save or update peer
            val peer = Peer(
                deviceId = payload.deviceId,
                displayName = payload.displayName,
                bluetoothAddress = null,
                transportType = TransportType.BLUETOOTH,
                isConnected = true,
                isTrusted = true,
                lastSeenAt = System.currentTimeMillis()
            )
            peerRepository.saveOrUpdatePeer(peer)

            // 2. Ensure direct conversation exists
            conversationRepository.getOrCreateConversation(
                peerId = payload.deviceId,
                peerDisplayName = payload.displayName,
                transportType = TransportType.BLUETOOTH
            )

            // 3. Keep connection foreground service running with peer name
            ConnectionForegroundService.startService(context, payload.displayName)

            // 4. Flush any queued offline messages for this newly connected peer
            flushPendingMessages(payload.deviceId)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling handshake", e)
        }
    }

    private suspend fun handleTextMessage(envelope: MessageEnvelope) {
        try {
            val text = String(envelope.payload, Charsets.UTF_8)
            Log.d(TAG, "Incoming text message [${envelope.messageId}] from ${envelope.senderId}: $text")

            val incomingMessage = Message(
                id = envelope.messageId,
                conversationId = envelope.conversationId.ifEmpty { envelope.senderId },
                senderId = envelope.senderId,
                receiverId = envelope.receiverId,
                text = text,
                timestamp = envelope.timestamp,
                status = MessageStatus.DELIVERED,
                isOutbound = false
            )

            // 1. Save message to local Room database
            messageRepository.saveMessage(incomingMessage)

            // 2. Update conversation preview
            conversationRepository.updateLastMessage(
                conversationId = incomingMessage.conversationId,
                text = text,
                timestamp = envelope.timestamp
            )

            // 3. Automatically send back an ACK delivery receipt
            val ackEnvelope = MessageEnvelope(
                messageId = UUID.randomUUID().toString(),
                conversationId = incomingMessage.conversationId,
                senderId = identityManager.deviceId,
                receiverId = envelope.senderId,
                timestamp = System.currentTimeMillis(),
                messageType = "ACK",
                payload = envelope.messageId.toByteArray(Charsets.UTF_8)
            )
            transport.sendEnvelope(ackEnvelope)
            Log.d(TAG, "Sent delivery ACK for message [${envelope.messageId}] to ${envelope.senderId}")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming text message", e)
        }
    }

    private suspend fun handleAck(envelope: MessageEnvelope) {
        try {
            val originalMessageId = String(envelope.payload, Charsets.UTF_8)
            Log.d(TAG, "Received delivery ACK for message ID: $originalMessageId")
            messageRepository.updateMessageStatus(originalMessageId, MessageStatus.DELIVERED)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing ACK", e)
        }
    }

    private suspend fun handleReadReceipt(envelope: MessageEnvelope) {
        try {
            val originalMessageId = String(envelope.payload, Charsets.UTF_8)
            Log.d(TAG, "Received READ receipt for message ID: $originalMessageId")
            messageRepository.updateMessageStatus(originalMessageId, MessageStatus.READ)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing READ receipt", e)
        }
    }

    suspend fun flushPendingMessages(peerId: String) {
        val pending = messageRepository.getPendingOutboundMessagesForPeer(peerId)
        if (pending.isEmpty()) return

        Log.d(TAG, "Flushing ${pending.size} pending offline messages for peer: $peerId")
        for (msg in pending) {
            val envelope = MessageEnvelope(
                messageId = msg.id,
                conversationId = msg.conversationId,
                senderId = msg.senderId,
                receiverId = msg.receiverId,
                timestamp = msg.timestamp,
                messageType = "TEXT",
                payload = msg.text.toByteArray(Charsets.UTF_8)
            )
            val result = transport.sendEnvelope(envelope)
            if (result.isSuccess) {
                messageRepository.updateMessageStatus(msg.id, MessageStatus.SENT)
                Log.d(TAG, "Successfully flushed message [${msg.id}] to $peerId")
            } else {
                Log.w(TAG, "Failed to flush pending message [${msg.id}]", result.exceptionOrNull())
                break
            }
        }
    }
}
