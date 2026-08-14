package com.example.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.example.model.StrokePath
import com.example.model.StrokeSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository to persist and retrieve vector StrokePaths directly from strokes.json and SharedPreferences.
 * Supports async reading/writing on Dispatchers.IO to prevent main-thread ANR / UI blocking.
 */
class DrawingRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Saves list of StrokePaths as JSON directly to strokes.json and SharedPreferences.
     */
    fun saveDrawing(strokes: List<StrokePath>, broadcastRefresh: Boolean = true) {
        val json = StrokeSerializer.toJson(strokes)
        try {
            val file = File(context.filesDir, FILE_STROKES_JSON)
            file.writeText(json)
            Log.d(TAG, "Saved ${strokes.size} vector strokes directly to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing strokes.json: ${e.message}", e)
        }

        prefs.edit().putString(KEY_LATEST_DRAWING_JSON, json).apply()
        if (broadcastRefresh) {
            broadcastRefresh()
        }
    }

    /**
     * Suspend version to save drawing asynchronously without blocking the calling thread.
     */
    suspend fun saveDrawingAsync(strokes: List<StrokePath>, broadcastRefresh: Boolean = true) {
        withContext(Dispatchers.IO) {
            saveDrawing(strokes, broadcastRefresh)
        }
    }

    /**
     * Clears all strokes, overwrites strokes.json with empty array "[]", and broadcasts wallpaper refresh.
     */
    fun clearDrawing(broadcastRefresh: Boolean = true) {
        try {
            val file = File(context.filesDir, FILE_STROKES_JSON)
            file.writeText("[]")
            Log.d(TAG, "Overwrote ${file.absolutePath} with empty array []")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing strokes.json: ${e.message}", e)
        }

        prefs.edit().putString(KEY_LATEST_DRAWING_JSON, "[]").apply()
        if (broadcastRefresh) {
            broadcastRefresh()
        }
    }

    /**
     * Retrieves the latest saved list of StrokePaths directly from strokes.json file, with SharedPreferences fallback.
     */
    fun getLatestDrawing(): List<StrokePath> {
        try {
            val file = File(context.filesDir, FILE_STROKES_JSON)
            if (file.exists() && file.length() > 0) {
                val json = file.readText()
                if (json.isNotBlank()) {
                    val list = StrokeSerializer.fromJson(json)
                    Log.d(TAG, "Loaded ${list.size} strokes from file ${file.absolutePath}")
                    return list
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading strokes.json: ${e.message}", e)
        }

        val json = prefs.getString(KEY_LATEST_DRAWING_JSON, null) ?: return emptyList()
        return StrokeSerializer.fromJson(json)
    }

    /**
     * Offloads reading and deserializing strokes from disk to Dispatchers.IO.
     */
    suspend fun getLatestDrawingAsync(): List<StrokePath> = withContext(Dispatchers.IO) {
        getLatestDrawing()
    }

    /**
     * Returns raw JSON string of the latest drawing.
     */
    fun getLatestDrawingJson(): String? {
        try {
            val file = File(context.filesDir, FILE_STROKES_JSON)
            if (file.exists() && file.length() > 0) {
                return file.readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading raw strokes.json: ${e.message}", e)
        }
        return prefs.getString(KEY_LATEST_DRAWING_JSON, null)
    }

    /**
     * Saves raw JSON string directly into strokes.json and SharedPreferences.
     */
    fun saveDrawingJson(json: String, broadcastRefresh: Boolean = true) {
        try {
            val file = File(context.filesDir, FILE_STROKES_JSON)
            file.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing raw strokes.json: ${e.message}", e)
        }
        prefs.edit().putString(KEY_LATEST_DRAWING_JSON, json).apply()
        if (broadcastRefresh) {
            broadcastRefresh()
        }
    }

    /**
     * Sends system broadcast to notify DrawWallpaperService to redraw vector paths immediately.
     */
    fun broadcastRefresh() {
        try {
            val intent = Intent(ACTION_REFRESH_WALLPAPER).apply {
                flags = Intent.FLAG_RECEIVER_FOREGROUND
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent broadcast: $ACTION_REFRESH_WALLPAPER")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting refresh: ${e.message}", e)
        }
    }

    companion object {
        const val TAG = "DrawingRepository"
        const val PREFS_NAME = "drawlock_prefs"
        const val KEY_LATEST_DRAWING_JSON = "latest_drawing_json"
        const val FILE_STROKES_JSON = "strokes.json"
        const val ACTION_REFRESH_WALLPAPER = "ru.wwmaxik.drawlock.REFRESH_WALLPAPER"

        @Volatile
        private var INSTANCE: DrawingRepository? = null

        fun getInstance(context: Context): DrawingRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DrawingRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
