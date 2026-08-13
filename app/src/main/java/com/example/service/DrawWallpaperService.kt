package com.example.service

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.animation.DecelerateInterpolator
import com.example.data.DrawingRepository
import com.example.data.PairingRepository
import com.example.model.StrokePath
import com.example.model.StrokeSerializer
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import java.io.File

/**
 * Direct Vector Live Wallpaper Service with Real-Time Firestore Synchronization
 * and Smooth PathMeasure Progressive Stroke Drawing Animation.
 * Renders vector paths directly onto the SurfaceHolder canvas, eliminating PNG files and memory crashes.
 * Listens directly to Firestore room updates to instantly reflect partner strokes even when the main app is closed.
 */
class DrawWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return VectorDrawEngine()
    }

    inner class VectorDrawEngine : WallpaperService.Engine() {

        private val repository by lazy { DrawingRepository.getInstance(applicationContext) }
        private val pairingRepository by lazy { PairingRepository.getInstance(applicationContext) }
        private val mainHandler = Handler(Looper.getMainLooper())

        private var firestoreListener: ListenerRegistration? = null
        private var currentRoomCode: String = ""
        private var lastRenderedStrokes: List<StrokePath> = emptyList()

        // Progressive drawing animation state
        private var strokeAnimator: ValueAnimator? = null
        private var animationProgress: Float = 1.0f
        private var animatingStrokes: List<StrokePath> = emptyList()
        private var newlyAddedStartIndex: Int = 0

        private val strokePaint = Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val fillPaint = Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.FILL
        }

        private val dotPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                Log.d("DrawLock_WP", "Received broadcast action: $action -> checking room listener & reloading strokes")
                attachFirestoreListener()
                loadAndDraw(animateIfNew = true)
            }
        }

        private var isReceiverRegistered = false

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            // Disable touch events on live wallpaper layer so lockscreen gestures and taps pass through
            setTouchEventsEnabled(false)

            registerWallpaperReceiver()

            // Ensure background listener for real-time room sync
            try {
                pairingRepository.ensureRoomListener()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing room listener from wallpaper service", e)
            }
        }

        private fun registerWallpaperReceiver() {
            if (isReceiverRegistered) return
            try {
                val filter = IntentFilter().apply {
                    addAction(DrawingRepository.ACTION_REFRESH_WALLPAPER)
                    addAction("ru.wwmaxik.drawlock.REFRESH_WALLPAPER")
                    addAction("ru.wwmaxik.drawlock.ACTION_REFRESH_WALLPAPER")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }
                isReceiverRegistered = true
                Log.d("DrawLock_WP", "Registered REFRESH_WALLPAPER BroadcastReceiver successfully")
            } catch (e: Exception) {
                Log.e("DrawLock_WP", "Error registering receiver: ${e.message}", e)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            registerWallpaperReceiver()
            attachFirestoreListener()
            Log.d("DrawLock_WP", "onSurfaceCreated triggered -> executing loadAndDraw()")
            loadAndDraw(animateIfNew = false)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            Log.d("DrawLock_WP", "onSurfaceChanged: ${width}x${height} -> executing drawFrame()")
            drawFrame(animationProgress)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            Log.d("DrawLock_WP", "onVisibilityChanged: visible=$visible")
            if (visible) {
                attachFirestoreListener()
                loadAndDraw(animateIfNew = false)
            } else {
                // Cancel active animation when screen turns off to guarantee zero idle battery consumption
                cancelActiveAnimation()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            cancelActiveAnimation()
            detachFirestoreListener()
        }

        override fun onDestroy() {
            super.onDestroy()
            cancelActiveAnimation()
            detachFirestoreListener()
            if (isReceiverRegistered) {
                try {
                    unregisterReceiver(receiver)
                    isReceiverRegistered = false
                } catch (e: Exception) {
                    // Receiver might already be unregistered
                }
            }
        }

        /**
         * Attaches a direct Firestore real-time listener using MetadataChanges.INCLUDE
         * to receive instant partner drawing updates at the lowest latency.
         */
        private fun attachFirestoreListener() {
            val prefs = applicationContext.getSharedPreferences("drawlock_prefs", Context.MODE_PRIVATE)
            val roomCode = prefs.getString("room_code", "") ?: ""

            if (roomCode.isBlank()) {
                detachFirestoreListener()
                return
            }

            if (firestoreListener != null && currentRoomCode == roomCode) {
                return // Listener already active for this room
            }

            detachFirestoreListener()
            currentRoomCode = roomCode

            try {
                val firestore = FirebaseFirestore.getInstance()
                val roomDoc = firestore.collection("rooms").document(roomCode)

                firestoreListener = roomDoc.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null) {
                        Log.e("DrawLock_WP", "Direct Firestore listener error in DrawWallpaperService: ${error.message}", error)
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        handleFirestoreSnapshot(snapshot)
                    }
                }
                Log.d("DrawLock_WP", "Attached direct Firestore listener in DrawWallpaperService for room: $roomCode")
            } catch (e: Exception) {
                Log.e("DrawLock_WP", "Failed to attach Firestore listener in wallpaper engine: ${e.message}", e)
            }
        }

        private fun detachFirestoreListener() {
            try {
                firestoreListener?.remove()
                firestoreListener = null
                currentRoomCode = ""
            } catch (e: Exception) {
                Log.e("DrawLock_WP", "Error detaching Firestore listener: ${e.message}")
            }
        }

        /**
         * Parses incoming merged strokes from Firestore snapshot and triggers progressive drawing animation.
         */
        private fun handleFirestoreSnapshot(snapshot: DocumentSnapshot) {
            try {
                val rawStrokes = snapshot.get("strokes") as? List<*>
                val parsedStrokes = mutableListOf<StrokePath>()

                if (rawStrokes != null) {
                    for (item in rawStrokes) {
                        if (item is Map<*, *>) {
                            val stroke = StrokePath.fromMap(item)
                            if (stroke != null) {
                                parsedStrokes.add(stroke)
                            }
                        }
                    }
                }

                // Check if strokes changed compared to currently loaded state
                if (parsedStrokes != lastRenderedStrokes) {
                    Log.d("DrawLock_WP", "Direct Firestore update received: ${parsedStrokes.size} strokes (previous: ${lastRenderedStrokes.size})")

                    // 1. Immediately write to local strokes.json
                    val strokesFile = File(filesDir, "strokes.json")
                    val json = StrokeSerializer.toJson(parsedStrokes)
                    strokesFile.writeText(json)

                    // 2. Update repository without recursive broadcast
                    repository.saveDrawing(parsedStrokes, broadcastRefresh = false)

                    // 3. Trigger progressive stroke drawing animation
                    applyNewStrokes(parsedStrokes, animate = true)
                }
            } catch (e: Exception) {
                Log.e("DrawLock_WP", "Error handling Firestore snapshot in wallpaper service: ${e.message}", e)
            }
        }

        private fun loadAndDraw(animateIfNew: Boolean) {
            val strokesFile = File(filesDir, "strokes.json")
            val strokes: List<StrokePath> = if (strokesFile.exists() && strokesFile.length() > 0) {
                try {
                    val json = strokesFile.readText()
                    StrokeSerializer.fromJson(json)
                } catch (e: Exception) {
                    Log.e("DrawLock_WP", "Error reading strokes.json: ${e.message}")
                    repository.getLatestDrawing()
                }
            } else {
                repository.getLatestDrawing()
            }
            applyNewStrokes(strokes, animate = animateIfNew)
        }

        /**
         * Compares incoming stroke list with existing rendered state and initiates PathMeasure animation if new strokes are appended.
         */
        private fun applyNewStrokes(newStrokes: List<StrokePath>, animate: Boolean) {
            val prevStrokes = lastRenderedStrokes

            // Check if strokes are appended
            val isAppended = prevStrokes.isNotEmpty() &&
                    newStrokes.size > prevStrokes.size &&
                    newStrokes.subList(0, prevStrokes.size) == prevStrokes

            // Determine if animation should be played (e.g. when new strokes appended or initial fresh non-empty draw)
            val shouldAnimate = animate && isVisible && (isAppended || (prevStrokes.isEmpty() && newStrokes.isNotEmpty()))

            lastRenderedStrokes = newStrokes
            animatingStrokes = newStrokes

            if (shouldAnimate) {
                newlyAddedStartIndex = if (isAppended) prevStrokes.size else 0
                startStrokeAnimation()
            } else {
                cancelActiveAnimation()
                animationProgress = 1.0f
                newlyAddedStartIndex = newStrokes.size
                drawFrame(1.0f)
            }
        }

        /**
         * Starts a 1200ms ValueAnimator with DecelerateInterpolator.
         * Runs on UI Looper thread and draws each frame synchronously onto the Surface canvas.
         */
        private fun startStrokeAnimation() {
            mainHandler.post {
                cancelActiveAnimation()

                strokeAnimator = ValueAnimator.ofFloat(0.0f, 1.0f).apply {
                    duration = 1200L
                    interpolator = DecelerateInterpolator(1.5f)
                    addUpdateListener { animator ->
                        val progress = animator.animatedValue as Float
                        animationProgress = progress
                        drawFrame(progress)
                    }
                }
                strokeAnimator?.start()
                Log.d("DrawLock_WP", "Started PathMeasure stroke animation (1200ms) for newly added strokes (index >= $newlyAddedStartIndex)")
            }
        }

        private fun cancelActiveAnimation() {
            strokeAnimator?.let {
                if (it.isRunning) {
                    it.cancel()
                }
            }
            strokeAnimator = null
        }

        /**
         * Renders vector paths onto the SurfaceHolder canvas:
         * - Background: Solid dark #121212
         * - Old completed strokes (index < newlyAddedStartIndex): Rendered 100% complete
         * - New strokes (index >= newlyAddedStartIndex): Progressively drawn using android.graphics.PathMeasure
         */
        @Synchronized
        fun drawFrame(progress: Float = 1.0f) {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null

            try {
                canvas = holder.lockCanvas()
                if (canvas == null) {
                    return
                }

                val width = canvas.width.toFloat()
                val height = canvas.height.toFloat()

                // Step 1: Clear background to solid dark #121212
                canvas.drawColor(Color.parseColor("#121212"))

                val strokes = animatingStrokes.ifEmpty { lastRenderedStrokes }
                val newStartIndex = newlyAddedStartIndex.coerceIn(0, strokes.size)

                // Step 2: Render all stroke paths
                for (index in strokes.indices) {
                    val strokePath = strokes[index]
                    if (strokePath.points.isEmpty()) continue

                    val strokeColorInt = strokePath.colorInt
                    val strokePx = if (strokePath.strokeWidth <= 0.2f) {
                        (strokePath.strokeWidth * width).coerceAtLeast(12f)
                    } else {
                        (strokePath.strokeWidth * (width / 1080f)).coerceAtLeast(12f)
                    }

                    // Single tap dot handling
                    if (strokePath.points.size == 1) {
                        val isNew = index >= newStartIndex
                        if (isNew && progress < 0.2f) {
                            // Delay dot appearing slightly for natural timing
                            continue
                        }
                        val dotScale = if (isNew) ((progress - 0.2f) / 0.8f).coerceIn(0f, 1f) else 1.0f
                        dotPaint.color = strokeColorInt
                        val p = strokePath.points[0]
                        canvas.drawCircle(p.x * width, p.y * height, (strokePx / 2f) * dotScale, dotPaint)
                        continue
                    }

                    strokePaint.color = strokeColorInt
                    strokePaint.strokeWidth = strokePx

                    // Build full bezier path
                    val fullPath = Path()
                    val first = strokePath.points[0]
                    fullPath.moveTo(first.x * width, first.y * height)

                    for (i in 1 until strokePath.points.size) {
                        val prev = strokePath.points[i - 1]
                        val curr = strokePath.points[i]
                        val prevX = prev.x * width
                        val prevY = prev.y * height
                        val currX = curr.x * width
                        val currY = curr.y * height
                        val midX = (prevX + currX) / 2f
                        val midY = (prevY + currY) / 2f
                        fullPath.quadTo(prevX, prevY, midX, midY)
                    }

                    val last = strokePath.points.last()
                    fullPath.lineTo(last.x * width, last.y * height)

                    if (strokePath.isFilled) {
                        fullPath.close()
                    }

                    val isNewStroke = index >= newStartIndex
                    if (isNewStroke && progress < 1.0f) {
                        // Progressive PathMeasure rendering for incoming new stroke
                        val pathMeasure = PathMeasure(fullPath, false)
                        val length = pathMeasure.length
                        if (length > 0f) {
                            val animatedPath = Path()
                            pathMeasure.getSegment(0f, length * progress, animatedPath, true)
                            canvas.drawPath(animatedPath, strokePaint)
                        }
                    } else {
                        // Fully rendered existing stroke (filled or stroked)
                        if (strokePath.isFilled) {
                            fillPaint.color = strokeColorInt
                            canvas.drawPath(fullPath, fillPaint)
                        } else {
                            canvas.drawPath(fullPath, strokePaint)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("DrawLock_WP", "Error in vector drawFrame: ${e.message}", e)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e("DrawLock_WP", "Error unlocking and posting canvas: ${e.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "DrawWallpaperService"
    }
}

