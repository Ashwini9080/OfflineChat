package com.offlinechat.security.di

import android.content.Context
import com.offlinechat.core.util.SystemTimeProvider
import com.offlinechat.core.util.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the :security module.
 *
 * [IdentityManager], [KeyStoreManager], and [SessionCrypto] are all annotated
 * with [@Singleton] and [@Inject] so Hilt discovers them automatically.
 * This module only needs to provide types that cannot be annotated directly
 * (interfaces and third-party classes).
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    /**
     * Provides the [TimeProvider] used across the app.
     *
     * Binding [SystemTimeProvider] here (rather than using it directly in classes)
     * means tests can replace this binding with a fake clock without modifying
     * production code.
     */
    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider = SystemTimeProvider
}
