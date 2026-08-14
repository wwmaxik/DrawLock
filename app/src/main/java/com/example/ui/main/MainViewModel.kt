package com.example.ui.main

import android.app.Application
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DrawingCacheManager
import com.example.data.DrawingRepository
import com.example.data.PairingRepository
import com.example.data.PairingStatus
import com.example.data.StreakManager
import com.example.model.DrawingData
import com.example.model.DrawingStroke
import com.example.service.DrawWallpaperService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val roomCode: String = "",
    val isPartnerConnected: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val pairingStatus: PairingStatus = PairingStatus.Idle
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()
    private val repository = PairingRepository.getInstance(context)
    private val cacheManager = DrawingCacheManager.getInstance(context)
    private val streakManager = StreakManager.getInstance(context)

    val currentDrawing: StateFlow<DrawingData?> = cacheManager.currentDrawing
    val streakCount: StateFlow<Long> = streakManager.streakCount
    val showStreakOnWallpaper: StateFlow<Boolean> = streakManager.showStreakOnWallpaper

    private val _uiState = MutableStateFlow(
        MainUiState(
            roomCode = cacheManager.roomCode.value,
            isPartnerConnected = cacheManager.partnerConnected.value
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                repository.ensureAuthenticated()
            } catch (e: Exception) {
                Log.e("DrawLock", "Firestore error during VM auth init", e)
            }
        }

        viewModelScope.launch {
            repository.pairingStatus.collect { status ->
                when (status) {
                    is PairingStatus.Loading -> {
                        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, pairingStatus = status)
                    }
                    is PairingStatus.Created -> {
                        _uiState.value = _uiState.value.copy(
                            roomCode = status.roomCode,
                            isPartnerConnected = false,
                            isLoading = false,
                            errorMessage = null,
                            pairingStatus = status
                        )
                    }
                    is PairingStatus.Connected -> {
                        _uiState.value = _uiState.value.copy(
                            roomCode = status.roomCode,
                            isPartnerConnected = true,
                            isLoading = false,
                            errorMessage = null,
                            pairingStatus = status
                        )
                    }
                    is PairingStatus.Joined -> {
                        _uiState.value = _uiState.value.copy(
                            roomCode = status.roomCode,
                            isLoading = false,
                            errorMessage = null,
                            pairingStatus = status
                        )
                    }
                    is PairingStatus.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = status.message,
                            pairingStatus = status
                        )
                    }
                    is PairingStatus.Idle -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            roomCode = cacheManager.roomCode.value,
                            isPartnerConnected = cacheManager.partnerConnected.value,
                            pairingStatus = status
                        )
                    }
                }
            }
        }
    }

    fun createRoom() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                repository.ensureAuthenticated()
                repository.createRoom { result ->
                    // Room created
                }
            } catch (e: Exception) {
                Log.e("DrawLock", "Firestore error in createRoom VM", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Не удалось создать комнату. Проверьте интернет."
                )
            }
        }
    }

    fun joinRoom(code: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                repository.ensureAuthenticated()
                repository.joinRoom(code) { result ->
                    // Room joined
                }
            } catch (e: Exception) {
                Log.e("DrawLock", "Firestore error in joinRoom VM", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Не удалось подключиться к комнате #$code"
                )
            }
        }
    }

    fun leaveRoom() {
        repository.leaveRoom()
        _uiState.value = _uiState.value.copy(roomCode = "", isPartnerConnected = false)
    }

    fun setShowStreakOnWallpaper(enabled: Boolean) {
        streakManager.setShowStreakOnWallpaper(enabled)
    }

    fun sendTestDrawing(strokes: List<DrawingStroke>) {
        repository.sendDrawing(strokes)
    }

    fun appendStrokes(newStrokes: List<DrawingStroke>, onComplete: ((Boolean) -> Unit)? = null) {
        repository.appendStrokes(newStrokes, onComplete)
    }

    fun clearDrawing(onComplete: ((Boolean) -> Unit)? = null) {
        repository.clearDrawing(onComplete)
    }

    fun refreshWallpaper(onDone: (() -> Unit)? = null) {
        DrawingRepository.getInstance(context).broadcastRefresh()
        context.sendBroadcast(Intent("ru.wwmaxik.drawlock.REFRESH_WALLPAPER").apply {
            flags = Intent.FLAG_RECEIVER_FOREGROUND
        })

        repository.refreshDrawingFromFirestore {
            onDone?.invoke()
        }
    }

    fun getSetWallpaperIntent(): Intent {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, DrawWallpaperService::class.java)
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
