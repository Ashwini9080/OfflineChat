package com.offlinechat.messaging

import com.offlinechat.core.model.Conversation
import com.offlinechat.core.model.ConversationType
import com.offlinechat.core.model.Message
import com.offlinechat.core.model.MessageStatus
import com.offlinechat.core.result.AppResult
import com.offlinechat.core.util.TimeProvider
import com.offlinechat.core.util.UuidFactory
import com.offlinechat.transport.api.PeerDevice
import com.offlinechat.transport.api.TransportEvent
import com.offlinechat.transport.manager.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The central coordinator for messaging activity.
 *
 * ## Responsibilities
 * - Listens to [TransportManager.events] and reacts to new channels
 *   by starting [MessageReceiver.processChannel] coroutines.
 * - Exposes a [messages] flow that the UI layer collects to update chat screens.
 * - Provides [sendMessage] for the UI to call when the user taps Send.
 * - Manages in-memory conversation state (Phase 3 will delegate to Room).
 *
 * ## Phase 3 migration note
 * In Phase 3, the in-memory [conversations] and [messageLists] maps will be
 * replaced by Room DAO calls. [ConversationManager] will remain the coordinator
 * but will read/write through [storage.repository.MessageRepository] and
 * [storage.repository.ConversationRepository].
 */
@Singleton
class ConversationManager @Inject constructor(
    private val transportManager: TransportManager,
    private val messageSender: MessageSender,
    private val messageReceiver: MessageReceiver,
    private val timeProvider: TimeProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // In-memory store (replace with Room in Phase 3)
    private val conversations = mutableMapOf<String, Conversation>()
    private val messageLists  = mutableMapOf<String, MutableList<Message>>()

    private val _messages = MutableSharedFlow<Message>(replay = 0, extraBufferCapacity = 256)

    /** Hot [SharedFlow] emitting every new [Message] (inbound or outbound). */
    val messages: SharedFlow<Message> = _messages.asSharedFlow()

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Starts observing transport events.
     *
     * Call this once from the Application class after Hilt injection completes.
     * Idempotent — calling multiple times is safe.
     */
    fun start() {
        // Register callback so MessageReceiver can route inbound messages here
        messageReceiver.setMessageReceivedCallback { message ->
            scope.launch { onMessageReceived(message) }
        }

        // React to new channels by starting receive loops and registering session keys
        transportManager.events
            .filterIsInstance<TransportEvent.ChannelOpened>()
            .onEach { event ->
                event.channel.sessionKey?.let { key ->
                    messageSender.registerSessionKey(event.peer.deviceId, key)
                    messageReceiver.registerSessionKey(event.peer.deviceId, key)
                }
                scope.launch {
                    messageReceiver.processChannel(event.channel)
                }
            }
            .launchIn(scope)
    }

    // ── Outbound ──────────────────────────────────────────────────────────────

    /**
     * Sends a text message to [peer].
     *
     * Creates a [Message] in PENDING status, attempts delivery, and emits the
     * result (SENT or FAILED) on [messages].
     */
    suspend fun sendTextMessage(
        conversationId: String,
        senderId: String,
        peer: PeerDevice,
        text: String,
    ) {
        val message = Message(
            id = UuidFactory.newId(),
            conversationId = conversationId,
            senderId = senderId,
            recipientId = peer.deviceId,
            content = com.offlinechat.core.model.MessageContent.Text(text),
            sentAt = timeProvider.nowMillis(),
            status = MessageStatus.PENDING,
            isOutbound = true,
        )

        storeMessage(message)
        _messages.emit(message)

        val result = messageSender.send(message)
        val updated = result.getOrNull() ?: message.copy(status = MessageStatus.FAILED)
        storeMessage(updated)
        _messages.emit(updated)
    }

    // ── Inbound ───────────────────────────────────────────────────────────────

    private suspend fun onMessageReceived(message: Message) {
        ensureConversationExists(message.conversationId, message.senderId)
        storeMessage(message)
        _messages.emit(message)
    }

    // ── Conversation management ───────────────────────────────────────────────

    /**
     * Gets or creates a conversation ID for a 1:1 chat with [peerId].
     *
     * Uses the same deterministic derivation as [MessageReceiver.deriveConversationId]
     * so both devices share the same conversation ID without coordination.
     */
    fun getOrCreateDirectConversation(localDeviceId: String, peerId: String): Conversation {
        val conversationId = deriveConversationId(localDeviceId, peerId)
        return conversations.getOrPut(conversationId) {
            Conversation(
                id = conversationId,
                type = ConversationType.DIRECT,
                participantIds = listOf(localDeviceId, peerId),
                lastActivityAt = timeProvider.nowMillis(),
            )
        }
    }

    fun getMessages(conversationId: String): List<Message> =
        messageLists[conversationId]?.toList() ?: emptyList()

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun ensureConversationExists(conversationId: String, peerId: String) {
        if (!conversations.containsKey(conversationId)) {
            conversations[conversationId] = Conversation(
                id = conversationId,
                type = ConversationType.DIRECT,
                participantIds = listOf(peerId), // localId added when available
                lastActivityAt = timeProvider.nowMillis(),
            )
        }
    }

    private fun storeMessage(message: Message) {
        messageLists.getOrPut(message.conversationId) { mutableListOf() }.also { list ->
            val existingIndex = list.indexOfFirst { it.id == message.id }
            if (existingIndex >= 0) list[existingIndex] = message
            else list.add(message)
        }
        // Update conversation's lastActivityAt
        conversations[message.conversationId]?.let { conv ->
            conversations[message.conversationId] = conv.copy(
                lastActivityAt = message.sentAt,
                lastMessageId = message.id,
            )
        }
    }

    private fun deriveConversationId(idA: String, idB: String): String {
        val (smaller, larger) = if (idA < idB) idA to idB else idB to idA
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$smaller:$larger".toByteArray())
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }
}
