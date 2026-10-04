package com.offlinechat

import android.app.Application
import com.offlinechat.data.transport.MessageSyncEngine
import com.offlinechat.security.IdentityManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {

    @Inject
    lateinit var identityManager: IdentityManager

    @Inject
    lateinit var messageSyncEngine: MessageSyncEngine

    override fun onCreate() {
        super.onCreate()
        // Initialize cryptographic hardware-backed Keystore identity
        identityManager.deviceId
        // Start background message synchronization and delivery engine
        messageSyncEngine.start()
    }
}
