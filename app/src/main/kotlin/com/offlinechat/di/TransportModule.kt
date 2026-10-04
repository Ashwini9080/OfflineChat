package com.offlinechat.di

import com.offlinechat.data.connection.BluetoothConnectionManager
import com.offlinechat.domain.connection.ConnectionManager
import com.offlinechat.data.discovery.DeviceDiscovery
import com.offlinechat.data.discovery.DiscoveryManager
import com.offlinechat.data.transport.MessageTransport
import com.offlinechat.data.transport.TransportManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {

    @Binds
    @Singleton
    abstract fun bindConnectionManager(
        bluetoothConnectionManager: BluetoothConnectionManager
    ): ConnectionManager

    @Binds
    @Singleton
    abstract fun bindMessageTransport(
        transportManager: TransportManager
    ): MessageTransport

    @Binds
    @Singleton
    abstract fun bindDeviceDiscovery(
        discoveryManager: DiscoveryManager
    ): DeviceDiscovery
}
