package com.example.service

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.DrawingCacheManager
import com.example.data.DrawingRepository
import com.example.data.PairingRepository
import com.example.data.StreakManager
import com.example.model.StrokePath
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Passive Background Live Wallpaper Engine.
 * Features:
 * 1. Double Buffering: All strokes and streak badges are pre-rendered completely offscreen onto a secondary Bitmap.
 *    Only when fully rasterized is the bitmap atomically blitted to the wallpaper surface in one call, eliminating flicker.
 * 2. Snapshot Debouncing: Compares incoming Firestore payloads against current state to prevent infinite redraw cycles.
 * 3. Feedback Loop Prevention: Never broadcasts REFRESH_WALLPAPER in response to remote network changes.
 * 4. Bitmap Reuse & Memory Safety: Reuses allocated offscreen bitmap buffers to minimize GC churn at 120 FPS.
 */
class DrawWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return VectorDrawEngine()
    }

    inner class VectorDrawEngine : WallpaperService.Engine() {

        private val repository by lazy { DrawingRepository.getInstance(applicationContext) }
        private val pairingRepository by lazy { PairingRepository.getInstance(applicationContext) }
        private val streakManager by lazy { StreakManager.getInstance(applicationContext) }
        private val cacheManager by lazy { DrawingCacheManager.getInstance(applicationContext) }
        private val mainHandler = Handler(Looper.getMainLooper())
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        private var firestoreListener: ListenerRegistration? = null
        private var currentRoomCode: String = ""
        private var lastRenderedStrokes: List<StrokePath> = emptyList()
        private var lastRenderedPayloadHash: Int = 0
        private var lastRenderedStreakCount: Long = -1L

        // Double Buffering: Offscreen secondary buffer
        private var backBitmap: Bitmap? = null
        private var backCanvas: Canvas? = null
        private var currentSurfaceWidth: Int = 1080
        private var currentSurfaceHeight: Int = 2400
        private var renderJob: Job? = null

        // Progressive stroke animation state
        private var strokeAnimator: ValueAnimator? = null
        private var animationProgress: Float = 1.0f
        private var animatingStrokes: List<StrokePath> = emptyList()
        private var newlyAddedStartIndex: Int = 0

        // Vector Fire Drawable
        private var fireDrawable: Drawable? = null

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

        // Streak Badge Paints
        private val pillBackgroundPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.parseColor("#5514141E")
        }

        private val pillBorderPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.parseColor("#44FFFFFF")
        }

        private val streakTextPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                Log.d(TAG, "Received broadcast: $action -> refreshing wallpaper from local storage")
                attachFirestoreListener()
                loadAndDraw(animateIfNew = true)
            }
        }

        private var isReceiverRegistered = false

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            // Passive wallpaper layer: let all lockscreen touches pass through
            setTouchEventsEnabled(false)

            fireDrawable = ContextCompat.getDrawable(applicationContext, R.drawable.ic_fire_streak)?.mutate()
            registerWallpaperReceiver()

            try {
                pairingRepository.ensureRoomListener()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing room listener", e)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            attachFirestoreListener()
            loadAndDraw(animateIfNew = false)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (width > 0 && height > 0 && (width != currentSurfaceWidth || height != currentSurfaceHeight)) {
                currentSurfaceWidth = width
                currentSurfaceHeight = height
                reallocateBackBuffer(width, height)
            }
            renderStrokesOffscreenAndPost(lastRenderedStrokes)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                attachFirestoreListener()
                loadAndDraw(animateIfNew = false)
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            engineScope.cancel()
            strokeAnimator?.cancel()
            strokeAnimator = null
            detachFirestoreListener()
            unregisterWallpaperReceiver()

            backBitmap = null
            backCanvas = null
        }

        private fun registerWallpaperReceiver() {
            if (!isReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(DrawingRepository.ACTION_REFRESH_WALLPAPER)
                    addAction("ru.wwmaxik.drawlock.REFRESH_WALLPAPER")
                    addAction(DrawingCacheManager.ACTION_DRAWING_UPDATED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }
                isReceiverRegistered = true
            }
        }

        private fun unregisterWallpaperReceiver() {
            if (isReceiverRegistered) {
                try {
                    unregisterReceiver(receiver)
                } catch (_: Exception) {}
                isReceiverRegistered = false
            }
        }

        private fun attachFirestoreListener() {
            val roomCode = cacheManager.roomCode.value
            if (roomCode.isBlank()) {
                detachFirestoreListener()
                return
            }

            if (firestoreListener != null && currentRoomCode == roomCode) {
                return
            }

            detachFirestoreListener()
            currentRoomCode = roomCode

            try {
                val db = FirebaseFirestore.getInstance()
                val roomDoc = db.collection("rooms").document(roomCode)

                firestoreListener = roomDoc.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Firestore room listener error: ${error.message}", error)
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        handleRemoteSnapshot(snapshot)
                    }
                }
                Log.d(TAG, "Attached direct real-time Firestore listener to room: $roomCode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach Firestore listener: ${e.message}", e)
            }
        }

        private fun detachFirestoreListener() {
            firestoreListener?.remove()
            firestoreListener = null
            currentRoomCode = ""
        }

        /**
         * Parses and debounces remote Firestore snapshot payloads.
         * Discards identical payloads immediately to prevent infinite sync loops.
         */
        private fun handleRemoteSnapshot(snapshot: DocumentSnapshot) {
            try {
                // 1. Update streak info
                val remoteStreak = snapshot.getLong("streakCount") ?: 0L
                val remoteDate = snapshot.getString("lastActiveDate") ?: ""
                streakManager.updateStreakFromRemote(remoteStreak, remoteDate)

                // 2. Parse strokes
                val strokesRaw = snapshot.get("strokes") as? List<*> ?: emptyList<Any>()
                val remoteStrokes = ArrayList<StrokePath>(strokesRaw.size)

                for (item in strokesRaw) {
                    if (item is Map<*, *>) {
                        StrokePath.fromMap(item)?.let { remoteStrokes.add(it) }
                    }
                }

                // 3. Debounce check against current rendered state
                val payloadHash = remoteStrokes.hashCode()
                val currentStreak = streakManager.streakCount.value
                if (payloadHash == lastRenderedPayloadHash && currentStreak == lastRenderedStreakCount && lastRenderedStrokes.isNotEmpty()) {
                    Log.d(TAG, "Debouncing identical Firestore snapshot payload ($payloadHash)")
                    return
                }

                if (remoteStrokes.isNotEmpty()) {
                    val localStrokes = repository.getLatestDrawing()
                    val isDifferent = remoteStrokes.size != localStrokes.size ||
                            (remoteStrokes.isNotEmpty() && localStrokes.isNotEmpty() &&
                                    remoteStrokes.last().points.size != localStrokes.last().points.size)

                    if (isDifferent) {
                        // Save directly to strokes.json WITHOUT emitting a broadcast to prevent infinite loop
                        repository.saveDrawing(remoteStrokes, broadcastRefresh = false)
                        mainHandler.post {
                            if (remoteStrokes.size > lastRenderedStrokes.size && lastRenderedStrokes.isNotEmpty()) {
                                startProgressiveStrokeAnimation(remoteStrokes, lastRenderedStrokes.size)
                            } else {
                                renderStrokesOffscreenAndPost(remoteStrokes)
                            }
                        }
                    } else {
                        mainHandler.post {
                            renderStrokesOffscreenAndPost(remoteStrokes)
                        }
                    }
                } else if (snapshot.contains("strokes") && strokesRaw.isEmpty()) {
                    // Board was cleared
                    repository.saveDrawing(emptyList(), broadcastRefresh = false)
                    mainHandler.post {
                        renderStrokesOffscreenAndPost(emptyList())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling remote snapshot: ${e.message}", e)
            }
        }

        private fun loadAndDraw(animateIfNew: Boolean = false) {
            engineScope.launch(Dispatchers.IO) {
                val strokes = repository.getLatestDrawing()
                withContext(Dispatchers.Main) {
                    if (animateIfNew && strokes.size > lastRenderedStrokes.size && lastRenderedStrokes.isNotEmpty()) {
                        startProgressiveStrokeAnimation(strokes, lastRenderedStrokes.size)
                    } else {
                        renderStrokesOffscreenAndPost(strokes)
                    }
                }
            }
        }

        private fun reallocateBackBuffer(width: Int, height: Int) {
            val w = width.coerceAtLeast(100)
            val h = height.coerceAtLeast(100)

            val existing = backBitmap
            if (existing == null || existing.width != w || existing.height != h) {
                val newBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                backBitmap = newBmp
                backCanvas = Canvas(newBmp)
                Log.d(TAG, "Allocated fresh offscreen double-buffer bitmap: ${w}x${h}")
            }
        }

        /**
         * Pre-renders strokes onto the offscreen secondary buffer in background, then atomic blits to surface.
         */
        private fun renderStrokesOffscreenAndPost(strokes: List<StrokePath>) {
            strokeAnimator?.cancel()
            renderJob?.cancel()

            val holder = surfaceHolder ?: return
            val w = currentSurfaceWidth.coerceAtLeast(100)
            val h = currentSurfaceHeight.coerceAtLeast(100)

            renderJob = engineScope.launch(Dispatchers.Default) {
                reallocateBackBuffer(w, h)
                val bufferBmp = backBitmap ?: return@launch
                val bufferCanvas = backCanvas ?: return@launch

                synchronized(bufferBmp) {
                    if (bufferBmp.isRecycled) return@launch
                    bufferCanvas.drawColor(Color.parseColor("#121212"))

                    for (stroke in strokes) {
                        renderSingleStroke(bufferCanvas, stroke, w.toFloat(), h.toFloat(), 1.0f)
                    }

                    renderStreakBadge(bufferCanvas, w.toFloat(), h.toFloat())
                }

                withContext(Dispatchers.Main) {
                    atomicBlitToSurface(holder, bufferBmp)
                    lastRenderedStrokes = strokes
                    lastRenderedPayloadHash = strokes.hashCode()
                    lastRenderedStreakCount = streakManager.streakCount.value
                }
            }
        }

        private fun startProgressiveStrokeAnimation(fullStrokes: List<StrokePath>, startIndex: Int) {
            strokeAnimator?.cancel()
            renderJob?.cancel()
            animatingStrokes = fullStrokes
            newlyAddedStartIndex = startIndex.coerceIn(0, fullStrokes.size)

            val newlyAddedCount = (fullStrokes.size - newlyAddedStartIndex).coerceAtLeast(1)
            val durationMs = (newlyAddedCount * 250L).coerceIn(350L, 1200L)

            strokeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    animationProgress = anim.animatedValue as Float
                    drawProgressiveFrameDoubleBuffered(animatingStrokes, newlyAddedStartIndex, animationProgress)
                }
                start()
            }
        }

        private fun drawProgressiveFrameDoubleBuffered(
            strokes: List<StrokePath>,
            newlyAddedStart: Int,
            progress: Float
        ) {
            val holder = surfaceHolder ?: return
            val w = currentSurfaceWidth.coerceAtLeast(100)
            val h = currentSurfaceHeight.coerceAtLeast(100)

            reallocateBackBuffer(w, h)
            val bufferBmp = backBitmap ?: return
            val bufferCanvas = backCanvas ?: return

            synchronized(bufferBmp) {
                if (bufferBmp.isRecycled) return
                bufferCanvas.drawColor(Color.parseColor("#121212"))

                // 1. Draw existing strokes
                for (i in 0 until newlyAddedStart.coerceAtMost(strokes.size)) {
                    renderSingleStroke(bufferCanvas, strokes[i], w.toFloat(), h.toFloat(), 1.0f)
                }

                // 2. Animate new strokes
                val animatedCount = strokes.size - newlyAddedStart
                if (animatedCount > 0) {
                    val currentStrokeFloatIndex = progress * animatedCount
                    for (idx in 0 until animatedCount) {
                        val strokeIndex = newlyAddedStart + idx
                        if (strokeIndex >= strokes.size) break

                        val stroke = strokes[strokeIndex]
                        val strokeProgress = (currentStrokeFloatIndex - idx).coerceIn(0f, 1f)
                        if (strokeProgress > 0f) {
                            renderSingleStroke(bufferCanvas, stroke, w.toFloat(), h.toFloat(), strokeProgress)
                        }
                    }
                }

                // 3. Streak badge
                renderStreakBadge(bufferCanvas, w.toFloat(), h.toFloat())
            }

            atomicBlitToSurface(holder, bufferBmp)
            lastRenderedStrokes = strokes
            lastRenderedPayloadHash = strokes.hashCode()
            lastRenderedStreakCount = streakManager.streakCount.value
        }

        /**
         * Performs atomic 1-call blit of the pre-rendered offscreen buffer to the active wallpaper surface.
         */
        private fun atomicBlitToSurface(holder: SurfaceHolder, bufferBmp: Bitmap) {
            var surfaceCanvas: Canvas? = null
            try {
                surfaceCanvas = holder.lockCanvas()
                if (surfaceCanvas != null && !bufferBmp.isRecycled) {
                    surfaceCanvas.drawBitmap(bufferBmp, 0f, 0f, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error blitting buffer to surface: ${e.message}", e)
            } finally {
                if (surfaceCanvas != null) {
                    try {
                        holder.unlockCanvasAndPost(surfaceCanvas)
                    } catch (_: Exception) {}
                }
            }
        }

        private fun renderSingleStroke(
            canvas: Canvas,
            stroke: StrokePath,
            width: Float,
            height: Float,
            progress: Float
        ) {
            val points = stroke.points
            if (points.isEmpty()) return

            if (stroke.isFilled && points.size >= 3) {
                fillPaint.color = stroke.color
                val path = Path()
                path.moveTo(points[0].x * width, points[0].y * height)
                val visibleCount = (points.size * progress).toInt().coerceIn(3, points.size)
                for (i in 1 until visibleCount) {
                    path.lineTo(points[i].x * width, points[i].y * height)
                }
                path.close()
                canvas.drawPath(path, fillPaint)
            } else if (points.size == 1) {
                dotPaint.color = stroke.color
                val radius = (stroke.strokeWidth * width) / 2f
                canvas.drawCircle(
                    points[0].x * width,
                    points[0].y * height,
                    radius.coerceAtLeast(2f),
                    dotPaint
                )
            } else {
                strokePaint.color = stroke.color
                strokePaint.strokeWidth = (stroke.strokeWidth * width).coerceAtLeast(1f)

                val fullPath = Path()
                fullPath.moveTo(points[0].x * width, points[0].y * height)
                for (i in 1 until points.size) {
                    val p1 = points[i - 1]
                    val p2 = points[i]
                    val midX = (p1.x + p2.x) / 2f * width
                    val midY = (p1.y + p2.y) / 2f * height
                    fullPath.quadTo(p1.x * width, p1.y * height, midX, midY)
                }
                val last = points.last()
                fullPath.lineTo(last.x * width, last.y * height)

                if (progress >= 0.999f) {
                    canvas.drawPath(fullPath, strokePaint)
                } else {
                    val pm = PathMeasure(fullPath, false)
                    val length = pm.length
                    val partialPath = Path()
                    pm.getSegment(0f, length * progress, partialPath, true)
                    canvas.drawPath(partialPath, strokePaint)
                }
            }
        }

        private fun renderStreakBadge(canvas: Canvas, width: Float, height: Float) {
            val showStreak = streakManager.showStreakOnWallpaper.value
            val streakCount = streakManager.streakCount.value

            if (!showStreak || streakCount <= 0L) {
                return
            }

            val text = "$streakCount"
            val textWidth = streakTextPaint.measureText(text)

            val iconSize = 48f
            val paddingH = 32f
            val paddingV = 16f
            val spacing = 12f

            val pillWidth = paddingH * 2 + iconSize + spacing + textWidth
            val pillHeight = iconSize + paddingV * 2

            val rightMargin = 48f
            val topMargin = 96f

            val pillRight = width - rightMargin
            val pillLeft = pillRight - pillWidth
            val pillTop = topMargin
            val pillBottom = pillTop + pillHeight

            val rect = RectF(pillLeft, pillTop, pillRight, pillBottom)
            val cornerRadius = pillHeight / 2f

            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, pillBackgroundPaint)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, pillBorderPaint)

            val iconLeft = (pillLeft + paddingH).toInt()
            val iconTop = (pillTop + paddingV).toInt()
            val iconRight = (iconLeft + iconSize).toInt()
            val iconBottom = (iconTop + iconSize).toInt()

            fireDrawable?.let { drawable ->
                drawable.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                drawable.draw(canvas)
            }

            val textX = pillLeft + paddingH + iconSize + spacing
            val fontMetrics = streakTextPaint.fontMetrics
            val textY = (rect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f)

            canvas.drawText(text, textX, textY, streakTextPaint)
        }
    }

    companion object {
        private const val TAG = "DrawWallpaperService"
    }
}
