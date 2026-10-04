package com.offlinechat.data.repository

import com.offlinechat.data.local.database.MessageDao
import com.offlinechat.data.local.database.MessageEntity
import com.offlinechat.domain.model.Message
import com.offlinechat.domain.model.MessageStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MessageRepositoryImplTest {

    private lateinit var messageDao: MessageDao
    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        messageDao = mockk(relaxed = true)
        repository = MessageRepositoryImpl(messageDao)
    }

    @Test
    fun `getMessagesForConversation maps entity stream to domain models`() = runTest {
        val entity = MessageEntity(
            id = "msg-1",
            conversationId = "convo-1",
            senderId = "peer-a",
            receiverId = "peer-b",
            text = "Testing offline room",
            timestamp = 1000L,
            status = "DELIVERED",
            isOutbound = false
        )
        coEvery { messageDao.getMessagesForConversation("convo-1") } returns flowOf(listOf(entity))

        val result = repository.getMessagesForConversation("convo-1").first()

        assertEquals(1, result.size)
        assertEquals("Testing offline room", result[0].text)
        assertEquals(MessageStatus.DELIVERED, result[0].status)
    }

    @Test
    fun `saveMessage converts domain model to entity and calls upsert`() = runTest {
        val domain = Message(
            id = "msg-2",
            conversationId = "convo-1",
            senderId = "peer-a",
            receiverId = "peer-b",
            text = "Outbound offline text",
            timestamp = 2000L,
            status = MessageStatus.PENDING,
            isOutbound = true
        )

        repository.saveMessage(domain)

        coVerify {
            messageDao.upsertMessage(match {
                it.id == "msg-2" && it.text == "Outbound offline text" && it.status == "PENDING"
            })
        }
    }
}
