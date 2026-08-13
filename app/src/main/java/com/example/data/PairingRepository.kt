package com.example.data

import android.content.Context
import android.util.Log
import com.example.model.DrawingData
import com.example.model.DrawingSerializer
import com.example.model.DrawingStroke
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

sealed interface PairingStatus {
    object Idle : PairingStatus
    object Loading : PairingStatus
    data class Created(val roomCode: String) : PairingStatus
    data class Joined(val roomCode: String) : PairingStatus
    data class Connected(val roomCode: String, val partnerId: String) : PairingStatus
    data class Error(val message: String) : PairingStatus
}

class PairingRepository private constructor(private val context: Context) {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val cacheManager = DrawingCacheManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val authMutex = Mutex()

    private val _pairingStatus = MutableStateFlow<PairingStatus>(PairingStatus.Idle)
    val pairingStatus: StateFlow<PairingStatus> = _pairingStatus.asStateFlow()

    private var roomListener: ListenerRegistration? = null
    private var activeRoomCode: String = ""

    init {
        scope.launch {
            try {
                ensureAuthenticated()
                val savedRoom = cacheManager.roomCode.value
                if (savedRoom.isNotBlank()) {
                    listenToRoom(savedRoom)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization auth error", e)
            }
        }
    }

    /**
     * Ensures Firebase Authentication has successfully completed before any Firestore operation.
     * Prevents PERMISSION_DENIED security rule rejections.
     */
    suspend fun ensureAuthenticated(): String {
        return authMutex.withLock {
            val current = auth.currentUser
            if (current != null) {
                return@withLock current.uid
            }

            try {
                Log.d(TAG, "Authenticating anonymously with Firebase...")
                val authResult = auth.signInAnonymously().await()
                val uid = authResult.user?.uid ?: throw IllegalStateException("Firebase user is null after sign in")
                Log.d(TAG, "Successfully authenticated as $uid")
                uid
            } catch (e: Exception) {
                Log.e(TAG, "Firebase anonymous authentication failed", e)
                throw e
            }
        }
    }

    fun ensureAuth(onComplete: ((String?) -> Unit)? = null) {
        scope.launch {
            try {
                val uid = ensureAuthenticated()
                onComplete?.invoke(uid)
            } catch (e: Exception) {
                Log.e(TAG, "ensureAuth error", e)
                onComplete?.invoke(cacheManager.userId.value)
            }
        }
    }

    private fun getCurrentUserId(): String {
        return auth.currentUser?.uid ?: cacheManager.userId.value
    }

    fun createRoom(onResult: (Result<String>) -> Unit) {
        _pairingStatus.value = PairingStatus.Loading
        scope.launch {
            try {
                val uid = ensureAuthenticated()
                val code = generate6DigitCode()

                val roomDoc = firestore.collection("rooms").document(code)
                val roomData = hashMapOf(
                    "createdAt" to FieldValue.serverTimestamp(),
                    "userA" to uid,
                    "userB" to "",
                    "strokes" to emptyList<Map<String, Any>>(),
                    "lastDrawing" to null,
                    "lastUpdated" to FieldValue.serverTimestamp()
                )

                roomDoc.set(roomData).await()
                activeRoomCode = code
                cacheManager.setRoomCode(code)
                cacheManager.setPartnerConnected(false)
                _pairingStatus.value = PairingStatus.Created(code)

                listenToRoom(code)
                onResult(Result.success(code))
            } catch (e: Exception) {
                Log.e("DrawLock", "Firestore error", e)
                val userFriendlyMessage = mapErrorToMessage(e, "Не удалось создать комнату. Попробуйте еще раз.")
                _pairingStatus.value = PairingStatus.Error(userFriendlyMessage)
                onResult(Result.failure(e))
            }
        }
    }

    fun joinRoom(code: String, onResult: (Result<Unit>) -> Unit) {
        val cleanCode = code.trim()
        if (cleanCode.length != 6) {
            val error = "Код комнаты должен состоять из 6 цифр"
            _pairingStatus.value = PairingStatus.Error(error)
            onResult(Result.failure(IllegalArgumentException(error)))
            return
        }

        _pairingStatus.value = PairingStatus.Loading
        scope.launch {
            try {
                val uid = ensureAuthenticated()
                val roomDoc = firestore.collection("rooms").document(cleanCode)
                val snapshot = roomDoc.get().await()

                if (!snapshot.exists()) {
                    val error = "Комната #$cleanCode не найдена"
                    _pairingStatus.value = PairingStatus.Error(error)
                    onResult(Result.failure(NoSuchElementException(error)))
                    return@launch
                }

                val userA = snapshot.getString("userA") ?: ""
                val userB = snapshot.getString("userB") ?: ""

                // Join as userB if not userA
                if (userA != uid && userB != uid) {
                    roomDoc.update("userB", uid).await()
                }

                activeRoomCode = cleanCode
                cacheManager.setRoomCode(cleanCode)
                cacheManager.setPartnerConnected(true)
                _pairingStatus.value = PairingStatus.Connected(cleanCode, if (userA == uid) userB else userA)

                listenToRoom(cleanCode)
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                Log.e("DrawLock", "Firestore error", e)
                val userFriendlyMessage = mapErrorToMessage(e, "Не удалось подключиться к комнате #$cleanCode")
                _pairingStatus.value = PairingStatus.Error(userFriendlyMessage)
                onResult(Result.failure(e))
            }
        }
    }

    fun ensureRoomListener() {
        val code = activeRoomCode.ifBlank { cacheManager.roomCode.value }
        if (code.isNotBlank() && roomListener == null) {
            listenToRoom(code)
        }
    }

    fun listenToRoom(code: String) {
        if (code.isBlank()) return
        scope.launch {
            try {
                ensureAuthenticated()
                roomListener?.remove()
                activeRoomCode = code

                val roomDoc = firestore.collection("rooms").document(code)
                roomListener = roomDoc.addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("DrawLock", "Firestore error in listenToRoom", error)
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        handleRoomUpdate(snapshot)
                    }
                }
            } catch (e: Exception) {
                Log.e("DrawLock", "Firestore error setting up room listener", e)
            }
        }
    }

    private fun handleRoomUpdate(snapshot: DocumentSnapshot) {
        val uid = getCurrentUserId()
        val userA = snapshot.getString("userA") ?: ""
        val userB = snapshot.getString("userB") ?: ""
        val isPartnerJoined = userB.isNotBlank() && (userA == uid || userB == uid)

        cacheManager.setPartnerConnected(isPartnerJoined)
        val partnerId = if (userA == uid) userB else userA
        if (isPartnerJoined) {
            _pairingStatus.value = PairingStatus.Connected(activeRoomCode, partnerId)
        } else if (userA == uid) {
            _pairingStatus.value = PairingStatus.Created(activeRoomCode)
        }

        // 1. Check for merged strokes array
        val rawStrokes = snapshot.get("strokes") as? List<*>
        if (rawStrokes != null) {
            val parsedStrokes = mutableListOf<com.example.model.StrokePath>()
            for (item in rawStrokes) {
                if (item is Map<*, *>) {
                    val stroke = com.example.model.StrokePath.fromMap(item)
                    if (stroke != null) {
                        parsedStrokes.add(stroke)
                    }
                }
            }

            Log.d(TAG, "Received ${parsedStrokes.size} merged strokes from Firestore room $activeRoomCode")

            // Save merged list into local strokes.json and broadcast REFRESH_WALLPAPER
            DrawingRepository.getInstance(context).saveDrawing(parsedStrokes, broadcastRefresh = true)

            // Update cached drawing state for active UI/Canvas
            val drawingData = DrawingData(
                senderId = partnerId.ifBlank { uid },
                timestamp = System.currentTimeMillis(),
                strokes = parsedStrokes.map { it.toDrawingStroke() }
            )
            cacheManager.saveNewDrawing(drawingData, notifyWallpaper = false)
            return
        }

        // 2. Legacy fallback for lastDrawing field
        val lastDrawingMap = snapshot.get("lastDrawing") as? Map<*, *>
        if (lastDrawingMap != null) {
            val senderId = lastDrawingMap["senderId"] as? String ?: ""
            val strokeDataJson = lastDrawingMap["strokeData"] as? String ?: ""
            if (strokeDataJson.isNotBlank()) {
                Log.d(TAG, "Received legacy drawing in room from $senderId (local: $uid)")
                val drawingData = DrawingSerializer.fromJson(strokeDataJson)
                if (drawingData != null) {
                    cacheManager.saveNewDrawing(drawingData, notifyWallpaper = true)
                }
            }
        }
    }

    /**
     * Appends new strokes to the Firestore "strokes" array using FieldValue.arrayUnion
     * Ensures simultaneous drawings from multiple partners merge seamlessly.
     */
    fun appendStrokes(newStrokes: List<DrawingStroke>, onComplete: ((Boolean) -> Unit)? = null) {
        if (newStrokes.isEmpty()) {
            onComplete?.invoke(true)
            return
        }

        val roomCode = activeRoomCode.ifBlank { cacheManager.roomCode.value }
        val strokeMaps = newStrokes.map { stroke ->
            com.example.model.StrokePath.fromDrawingStroke(stroke).toMap()
        }.toTypedArray()

        // Also update local cache & strokes.json immediately
        val currentStrokes = DrawingRepository.getInstance(context).getLatestDrawing().toMutableList()
        currentStrokes.addAll(newStrokes.map { com.example.model.StrokePath.fromDrawingStroke(it) })
        DrawingRepository.getInstance(context).saveDrawing(currentStrokes, broadcastRefresh = true)
        cacheManager.saveNewDrawing(
            DrawingData(
                senderId = getCurrentUserId(),
                timestamp = System.currentTimeMillis(),
                strokes = currentStrokes.map { it.toDrawingStroke() }
            ),
            notifyWallpaper = false
        )

        if (roomCode.isBlank()) {
            onComplete?.invoke(true)
            return
        }

        scope.launch {
            try {
                ensureAuthenticated()
                val roomDoc = firestore.collection("rooms").document(roomCode)
                roomDoc.update(
                    "strokes", FieldValue.arrayUnion(*strokeMaps),
                    "lastUpdated", FieldValue.serverTimestamp()
                ).await()
                Log.d(TAG, "Appended ${strokeMaps.size} strokes via arrayUnion to room $roomCode")
                onComplete?.invoke(true)
            } catch (e: Exception) {
                Log.w(TAG, "update strokes failed, trying set with merge: ${e.message}")
                try {
                    val roomDoc = firestore.collection("rooms").document(roomCode)
                    roomDoc.set(
                        mapOf(
                            "strokes" to FieldValue.arrayUnion(*strokeMaps),
                            "lastUpdated" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                    onComplete?.invoke(true)
                } catch (e2: Exception) {
                    Log.e(TAG, "Error appending strokes to Firestore: ${e2.message}", e2)
                    onComplete?.invoke(false)
                }
            }
        }
    }

    /**
     * Replaces the entire stroke array (e.g. on Undo or complete refresh)
     */
    fun replaceStrokes(strokes: List<DrawingStroke>, onComplete: ((Boolean) -> Unit)? = null) {
        val roomCode = activeRoomCode.ifBlank { cacheManager.roomCode.value }
        val strokeMaps = strokes.map { com.example.model.StrokePath.fromDrawingStroke(it).toMap() }

        val vectorStrokes = strokes.map { com.example.model.StrokePath.fromDrawingStroke(it) }
        DrawingRepository.getInstance(context).saveDrawing(vectorStrokes, broadcastRefresh = true)
        cacheManager.saveNewDrawing(
            DrawingData(
                senderId = getCurrentUserId(),
                timestamp = System.currentTimeMillis(),
                strokes = strokes
            ),
            notifyWallpaper = false
        )

        if (roomCode.isBlank()) {
            onComplete?.invoke(true)
            return
        }

        scope.launch {
            try {
                ensureAuthenticated()
                val roomDoc = firestore.collection("rooms").document(roomCode)
                roomDoc.update(
                    "strokes", strokeMaps,
                    "lastUpdated", FieldValue.serverTimestamp()
                ).await()
                onComplete?.invoke(true)
            } catch (e: Exception) {
                try {
                    val roomDoc = firestore.collection("rooms").document(roomCode)
                    roomDoc.set(
                        mapOf(
                            "strokes" to strokeMaps,
                            "lastUpdated" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                    onComplete?.invoke(true)
                } catch (e2: Exception) {
                    Log.e(TAG, "Error replacing strokes: ${e2.message}", e2)
                    onComplete?.invoke(false)
                }
            }
        }
    }

    /**
     * Clears the entire canvas:
     * 1. Sets "strokes" to empty list in Firestore document /rooms/{roomCode}
     * 2. Overwrites local strokes.json with empty array []
     * 3. Broadcasts ru.wwmaxik.drawlock.REFRESH_WALLPAPER
     */
    fun clearDrawing(onComplete: ((Boolean) -> Unit)? = null) {
        val roomCode = activeRoomCode.ifBlank { cacheManager.roomCode.value }

        // Local clear
        DrawingRepository.getInstance(context).clearDrawing(broadcastRefresh = true)
        cacheManager.saveNewDrawing(
            DrawingData(
                senderId = getCurrentUserId(),
                timestamp = System.currentTimeMillis(),
                strokes = emptyList()
            ),
            notifyWallpaper = false
        )

        if (roomCode.isBlank()) {
            onComplete?.invoke(true)
            return
        }

        scope.launch {
            try {
                ensureAuthenticated()
                val roomDoc = firestore.collection("rooms").document(roomCode)
                roomDoc.update(
                    "strokes", emptyList<Any>(),
                    "lastDrawing", null,
                    "lastUpdated", FieldValue.serverTimestamp()
                ).await()
                Log.d(TAG, "Cleared strokes array in Firestore room $roomCode")
                onComplete?.invoke(true)
            } catch (e: Exception) {
                try {
                    val roomDoc = firestore.collection("rooms").document(roomCode)
                    roomDoc.set(
                        mapOf(
                            "strokes" to emptyList<Any>(),
                            "lastDrawing" to null,
                            "lastUpdated" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                    onComplete?.invoke(true)
                } catch (e2: Exception) {
                    Log.e(TAG, "Error clearing room strokes in Firestore: ${e2.message}", e2)
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun sendDrawing(strokes: List<DrawingStroke>, onComplete: ((Boolean) -> Unit)? = null) {
        if (strokes.isEmpty()) {
            clearDrawing(onComplete)
        } else {
            replaceStrokes(strokes, onComplete)
        }
    }

    fun refreshDrawingFromFirestore(onComplete: ((Boolean) -> Unit)? = null) {
        val code = activeRoomCode.ifBlank { cacheManager.roomCode.value }
        if (code.isBlank()) {
            DrawingRepository.getInstance(context).broadcastRefresh()
            onComplete?.invoke(true)
            return
        }

        scope.launch {
            try {
                ensureAuthenticated()
                val snapshot = firestore.collection("rooms").document(code).get().await()
                if (snapshot.exists()) {
                    handleRoomUpdate(snapshot)
                }
                DrawingRepository.getInstance(context).broadcastRefresh()
                onComplete?.invoke(true)
            } catch (e: Exception) {
                Log.e("DrawLock", "Error manually refreshing drawing from Firestore: ${e.message}", e)
                DrawingRepository.getInstance(context).broadcastRefresh()
                onComplete?.invoke(false)
            }
        }
    }

    fun leaveRoom() {
        roomListener?.remove()
        roomListener = null
        activeRoomCode = ""
        cacheManager.clearRoom()
        _pairingStatus.value = PairingStatus.Idle
    }

    private fun generate6DigitCode(): String {
        return (100000 + Random.nextInt(900000)).toString()
    }

    private fun mapErrorToMessage(e: Exception, defaultMsg: String): String {
        if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            return "Ошибка доступа к серверу. Пожалуйста, проверьте подключение к сети."
        }
        val msg = e.localizedMessage ?: ""
        return when {
            msg.contains("PERMISSION_DENIED", ignoreCase = true) -> "Ошибка доступа к серверу. Проверьте интернет."
            msg.contains("UNAVAILABLE", ignoreCase = true) || msg.contains("network", ignoreCase = true) -> "Сервер временно недоступен. Проверьте подключение к интернету."
            else -> defaultMsg
        }
    }

    companion object {
        private const val TAG = "PairingRepository"

        @Volatile
        private var INSTANCE: PairingRepository? = null

        fun getInstance(context: Context): PairingRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PairingRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
