package com.example

import android.app.Application
import com.example.data.DrawingCacheManager
import com.example.data.PairingRepository
import com.example.service.LockscreenNotificationManager
import com.google.firebase.FirebaseApp

class DrawLockApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        LockscreenNotificationManager.createNotificationChannel(this)

        val cacheManager = DrawingCacheManager.getInstance(this)
        val repo = PairingRepository.getInstance(this)
        repo.ensureAuth()

        val activeRoom = cacheManager.roomCode.value
        if (activeRoom.isNotBlank() && cacheManager.shortcutNotificationEnabled.value) {
            LockscreenNotificationManager.showLockscreenShortcutNotification(this, activeRoom)
        }
    }
}
