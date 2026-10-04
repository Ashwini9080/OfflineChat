package com.offlinechat.di

import com.offlinechat.security.MessageSecurity
import com.offlinechat.security.MessageSecurityImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindMessageSecurity(
        impl: MessageSecurityImpl
    ): MessageSecurity
}
