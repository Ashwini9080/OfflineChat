package com.offlinechat.di

import com.offlinechat.data.repository.ConversationRepositoryImpl
import com.offlinechat.data.repository.MessageRepositoryImpl
import com.offlinechat.data.repository.PeerRepositoryImpl
import com.offlinechat.data.repository.PreferencesRepositoryImpl
import com.offlinechat.domain.repository.ConversationRepository
import com.offlinechat.domain.repository.MessageRepository
import com.offlinechat.domain.repository.PeerRepository
import com.offlinechat.domain.repository.PreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindConversationRepository(
        impl: ConversationRepositoryImpl
    ): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindMessageRepository(
        impl: MessageRepositoryImpl
    ): MessageRepository

    @Binds
    @Singleton
    abstract fun bindPeerRepository(
        impl: PeerRepositoryImpl
    ): PeerRepository

    @Binds
    @Singleton
    abstract fun bindPreferencesRepository(
        impl: PreferencesRepositoryImpl
    ): PreferencesRepository
}
