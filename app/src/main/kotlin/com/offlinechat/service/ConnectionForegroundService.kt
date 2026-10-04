package com.offlinechat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.offlinechat.MainActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ConnectionForegroundService : Service {

    constructor() : super()

    companion object {
        const val CHANNEL_ID = "offline_chat_active_connection"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.offlinechat.action.START_CONNECTION"
        const val ACTION_STOP = "com.offlinechat.action.STOP_CONNECTION"
        const val EXTRA_PEER_NAME = "extra_peer_name"

        fun startService(context: Context, peerName: String) {
            val intent = Intent(context, ConnectionForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PEER_NAME, peerName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ConnectionForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Nearby Peer"
                startForeground(NOTIFICATION_ID, buildNotification(peerName))
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(peerName: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Active P2P Offline Link")
            .setContentText("Connected with $peerName over Bluetooth RFCOMM")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Offline Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of ongoing Bluetooth or Wi-Fi Direct chats"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
