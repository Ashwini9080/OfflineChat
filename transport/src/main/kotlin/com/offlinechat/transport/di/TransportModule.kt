package com.offlinechat.transport.di

import com.offlinechat.transport.api.Transport
import com.offlinechat.transport.bluetooth.BluetoothTransport
import com.offlinechat.transport.wifi.WiFiDirectTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the :transport module.
 *
 * [BluetoothTransport], [WiFiDirectTransport], and [TransportManager] are
 * @Singleton and @Inject-annotated, so Hilt finds them automatically.
 *
 * No explicit @Provides are needed here — this module is intentionally minimal.
 * Add @Binds mappings here if a [Transport] interface injection point is ever
 * needed (e.g., for testing with a fake transport).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {
    // Empty for now — all transport classes use constructor injection.
    // Add @Binds or @Provides here for test doubles or conditional transport selection.
}
