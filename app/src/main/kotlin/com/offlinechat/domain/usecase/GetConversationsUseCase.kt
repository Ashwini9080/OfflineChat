package com.offlinechat.domain.usecase

import com.offlinechat.domain.model.Conversation
import com.offlinechat.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetConversationsUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository
) {
    operator fun invoke(): Flow<List<Conversation>> {
        return conversationRepository.getConversations()
    }
}
