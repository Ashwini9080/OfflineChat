package com.offlinechat.domain.usecase

import com.offlinechat.domain.model.Message
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetMessagesUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository
) {
    operator fun invoke(conversationId: String): Flow<List<Message>> {
        return messageRepository.getMessagesForConversation(conversationId)
    }

    suspend fun markAsRead(conversationId: String) {
        conversationRepository.markAsRead(conversationId)
    }
}
