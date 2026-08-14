package com.example.data

import android.content.Context
import android.content.Intent
import com.example.model.DrawingData
import com.example.model.DrawingSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class DrawingCacheManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("drawlock_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _currentDrawing = MutableStateFlow<DrawingData?>(null)
    val currentDrawing: StateFlow<DrawingData?> = _currentDrawing.asStateFlow()

    private val _roomCode = MutableStateFlow(prefs.getString(KEY_ROOM_CODE, "") ?: "")
    val roomCode: StateFlow<String> = _roomCode.asStateFlow()

    private val _userId = MutableStateFlow(getOrCreateUserId())
    val userId: StateFlow<String> = _userId.asStateFlow()

    private val _partnerConnected = MutableStateFlow(prefs.getBoolean(KEY_PARTNER_CONNECTED, false))
    val partnerConnected: StateFlow<Boolean> = _partnerConnected.asStateFlow()

    init {
        loadCachedDrawing()
    }

    private fun getOrCreateUserId(): String {
        var id = prefs.getString(KEY_USER_ID, null)
        if (id.isNullOrBlank()) {
            id = "user_" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_USER_ID, id).apply()
        }
        return id
    }

    fun setRoomCode(code: String) {
        prefs.edit().putString(KEY_ROOM_CODE, code).apply()
        _roomCode.value = code
    }

    fun setPartnerConnected(connected: Boolean) {
        prefs.edit().putBoolean(KEY_PARTNER_CONNECTED, connected).apply()
        _partnerConnected.value = connected
    }

    fun clearRoom() {
        prefs.edit()
            .remove(KEY_ROOM_CODE)
            .remove(KEY_PARTNER_CONNECTED)
            .apply()
        _roomCode.value = ""
        _partnerConnected.value = false
    }

    fun saveNewDrawing(data: DrawingData, notifyWallpaper: Boolean = true) {
        _currentDrawing.value = data
        val json = DrawingSerializer.toJson(data)
        prefs.edit().putString(KEY_CACHED_DRAWING_JSON, json).apply()

        // Also save vector StrokePaths to DrawingRepository
        val vectorStrokes = data.strokes.map { stroke ->
            com.example.model.StrokePath(
                points = stroke.points.map { com.example.model.PointF(it.x, it.y) },
                color = stroke.color.toInt(),
                strokeWidth = stroke.widthRatio,
                alpha = stroke.alpha,
                isFilled = stroke.isFilled
            )
        }
        DrawingRepository.getInstance(context).saveDrawing(vectorStrokes, broadcastRefresh = false)

        if (notifyWallpaper) {
            DrawingRepository.getInstance(context).broadcastRefresh()
        }
    }

    private fun loadCachedDrawing() {
        val cachedJson = prefs.getString(KEY_CACHED_DRAWING_JSON, null)
        if (!cachedJson.isNullOrBlank()) {
            val data = DrawingSerializer.fromJson(cachedJson)
            _currentDrawing.value = data
        }
    }

    fun broadcastWallpaperUpdate() {
        val intent = Intent(ACTION_DRAWING_UPDATED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    companion object {
        const val ACTION_DRAWING_UPDATED = "ru.wwmaxik.drawlock.ACTION_DRAWING_UPDATED"
        private const val KEY_ROOM_CODE = "room_code"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PARTNER_CONNECTED = "partner_connected"
        private const val KEY_CACHED_DRAWING_JSON = "cached_drawing_json"

        @Volatile
        private var INSTANCE: DrawingCacheManager? = null

        fun getInstance(context: Context): DrawingCacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DrawingCacheManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
