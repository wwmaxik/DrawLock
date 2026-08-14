package com.example.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Manages the Daily Streak ("Огонёк") tracking and wallpaper badge visibility.
 */
class StreakManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _streakCount = MutableStateFlow(prefs.getLong(KEY_STREAK_COUNT, 0L))
    val streakCount: StateFlow<Long> = _streakCount.asStateFlow()

    private val _lastActiveDate = MutableStateFlow(prefs.getString(KEY_LAST_ACTIVE_DATE, "") ?: "")
    val lastActiveDate: StateFlow<String> = _lastActiveDate.asStateFlow()

    private val _showStreakOnWallpaper = MutableStateFlow(prefs.getBoolean(KEY_SHOW_STREAK_WALLPAPER, true))
    val showStreakOnWallpaper: StateFlow<Boolean> = _showStreakOnWallpaper.asStateFlow()

    fun getTodayUtcDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date())
    }

    fun getYesterdayUtcDate(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(cal.time)
    }

    /**
     * Toggles the display of the streak badge on the Live Wallpaper lock screen.
     */
    fun setShowStreakOnWallpaper(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_STREAK_WALLPAPER, enabled).apply()
        _showStreakOnWallpaper.value = enabled
        broadcastWallpaperRefresh()
    }

    /**
     * Calculates the new streak when a user draws and sends a drawing.
     * Updates Firestore room document atomically and updates local cache.
     */
    suspend fun recordDrawingSent(roomCode: String, currentUid: String): Long {
        val today = getTodayUtcDate()
        val yesterday = getYesterdayUtcDate()

        var currentStreak = _streakCount.value
        val lastDate = _lastActiveDate.value

        val newStreak: Long = when {
            lastDate == today -> {
                // Already active today, maintain streak
                if (currentStreak <= 0L) 1L else currentStreak
            }
            lastDate == yesterday -> {
                // Active consecutive day, increment streak
                currentStreak + 1L
            }
            else -> {
                // Streak broken or fresh start, reset to 1
                1L
            }
        }

        updateLocalStreak(newStreak, today)

        if (roomCode.isNotBlank()) {
            try {
                val roomDoc = firestore.collection("rooms").document(roomCode)
                val updates = mapOf(
                    "streakCount" to newStreak,
                    "lastActiveDate" to today,
                    "lastDrawerUid" to currentUid,
                    "lastUpdated" to FieldValue.serverTimestamp()
                )
                roomDoc.set(updates, SetOptions.merge()).await()
                Log.d(TAG, "Successfully updated streak in Firestore: $newStreak on $today")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating streak in Firestore: ${e.message}", e)
            }
        }

        broadcastWallpaperRefresh()
        return newStreak
    }

    /**
     * Updates local streak state from remote Firestore snapshot data.
     */
    fun updateStreakFromRemote(remoteStreak: Long, remoteDate: String) {
        val today = getTodayUtcDate()
        val yesterday = getYesterdayUtcDate()

        val verifiedStreak = when {
            remoteDate == today || remoteDate == yesterday -> remoteStreak
            remoteDate.isNotBlank() && remoteDate < yesterday -> 0L // Expired streak
            else -> remoteStreak
        }

        updateLocalStreak(verifiedStreak, remoteDate)
    }

    private fun updateLocalStreak(count: Long, date: String) {
        prefs.edit()
            .putLong(KEY_STREAK_COUNT, count)
            .putString(KEY_LAST_ACTIVE_DATE, date)
            .apply()
        _streakCount.value = count
        _lastActiveDate.value = date
    }

    private fun broadcastWallpaperRefresh() {
        try {
            val intent = Intent(ACTION_REFRESH_WALLPAPER).apply {
                flags = Intent.FLAG_RECEIVER_FOREGROUND
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending broadcast: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "StreakManager"
        private const val PREFS_NAME = "drawlock_prefs"
        const val KEY_STREAK_COUNT = "streak_count"
        const val KEY_LAST_ACTIVE_DATE = "last_active_date"
        const val KEY_SHOW_STREAK_WALLPAPER = "show_streak_on_wallpaper"
        const val ACTION_REFRESH_WALLPAPER = "ru.wwmaxik.drawlock.REFRESH_WALLPAPER"

        @Volatile
        private var INSTANCE: StreakManager? = null

        fun getInstance(context: Context): StreakManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StreakManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
