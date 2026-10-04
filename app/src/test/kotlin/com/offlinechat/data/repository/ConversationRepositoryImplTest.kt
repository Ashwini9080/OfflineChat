package com.offlinechat.data.repository

import com.offlinechat.data.local.database.ConversationDao
import com.offlinechat.data.local.database.ConversationEntity
import com.offlinechat.domain.model.TransportType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class ConversationRepositoryImplTest {

    private lateinit var conversationDao: ConversationDao
    private lateinit var repository: ConversationRepositoryImpl

    @Before
    fun setUp() {
        conversationDao = mockk(relaxed = true)
        repository = ConversationRepositoryImpl(conversationDao)
    }

    @Test
    fun `getConversations correctly maps entities including unread counts and sort order`() = runTest {
        val entity1 = ConversationEntity(
            id = "conv_1",
            peerId = "peer_alice",
            peerDisplayName = "Alice",
            lastMessage = "Hey there!",
            lastActivityAt = 1000L,
            unreadCount = 3,
            transportType = "BLUETOOTH"
        )
        val entity2 = ConversationEntity(
            id = "conv_2",
            peerId = "peer_bob",
            peerDisplayName = "Bob",
            lastMessage = "See you offline",
            lastActivityAt = 900L,
            unreadCount = 0,
            transportType = "BLUETOOTH"
        )
        coEvery { conversationDao.getAllConversations() } returns flowOf(listOf(entity1, entity2))

        val conversations = repository.getConversations().first()

        assertEquals(2, conversations.size)
        assertEquals("Alice", conversations[0].peerDisplayName)
        assertEquals(3, conversations[0].unreadCount)
        assertEquals("Bob", conversations[1].peerDisplayName)
        assertEquals(0, conversations[1].unreadCount)
    }

    @Test
    fun `getOrCreateConversation reuses existing conversation when peer already exists`() = runTest {
        val existingEntity = ConversationEntity(
            id = "existing_conv_id",
            peerId = "peer_alice",
            peerDisplayName = "Alice",
            lastMessage = "Old message",
            lastActivityAt = 500L,
            unreadCount = 1,
            transportType = "BLUETOOTH"
        )
        coEvery { conversationDao.getConversationByPeerId("peer_alice") } returns existingEntity

        val result = repository.getOrCreateConversation("peer_alice", "Alice", TransportType.BLUETOOTH)

        assertEquals("existing_conv_id", result.id)
        assertEquals("Alice", result.peerDisplayName)
        coVerify(exactly = 0) { conversationDao.upsertConversation(any()) }
    }

    @Test
    fun `getOrCreateConversation creates new conversation if peer does not exist`() = runTest {
        coEvery { conversationDao.getConversationByPeerId("new_peer") } returns null

        val result = repository.getOrCreateConversation("new_peer", "New Peer", TransportType.BLUETOOTH)

        assertNotNull(result.id)
        assertEquals("new_peer", result.peerId)
        assertEquals("New Peer", result.peerDisplayName)
        assertEquals(0, result.unreadCount)
        coVerify { conversationDao.upsertConversation(match { it.peerId == "new_peer" }) }
    }

    @Test
    fun `incrementUnreadCount delegates to conversationDao`() = runTest {
        repository.incrementUnreadCount("conv_123")
        coVerify { conversationDao.incrementUnreadCount("conv_123") }
    }

    @Test
    fun `markAsRead delegates to conversationDao`() = runTest {
        repository.markAsRead("conv_123")
        coVerify { conversationDao.markAsRead("conv_123") }
    }
}
