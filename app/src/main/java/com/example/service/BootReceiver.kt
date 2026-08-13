package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.DrawingCacheManager
import com.example.data.PairingRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val cacheManager = DrawingCacheManager.getInstance(context)
            val roomCode = cacheManager.roomCode.value
            if (roomCode.isNotBlank()) {
                PairingRepository.getInstance(context).listenToRoom(roomCode)
                if (cacheManager.shortcutNotificationEnabled.value) {
                    LockscreenNotificationManager.showLockscreenShortcutNotification(context, roomCode)
                }
            }
        }
    }
}
