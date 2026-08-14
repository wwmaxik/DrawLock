package com.example

import android.app.Application
import com.example.data.PairingRepository
import com.google.firebase.FirebaseApp

class DrawLockApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val repo = PairingRepository.getInstance(this)
        repo.ensureAuth()
    }
}
