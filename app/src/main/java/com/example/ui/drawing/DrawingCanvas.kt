package com.example.ui.drawing

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import com.example.data.DrawingCacheManager
import com.example.data.DrawingRepository
import com.example.data.PairingRepository
import com.example.model.PointF
import com.example.model.StrokePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.hypot

enum class DrawingTool {
    PEN,
    ERASER,
    FILL
}

/**
 * 120 FPS High-Performance Vector Drawing Canvas.
 * - Hardware-accelerated native GPU rendering (zero ashmem pinning, zero software bitmap GC churn)
 * - Strict isCanvasLoaded lifecycle guard to prevent uninitialized empty state overwrites
 * - Smooth quadratic bezier curves and instant onDragEnd sync
 * - Interactive in-flight gesture rendering at display refresh rate
 */
@Composable
fun DrawingCanvas(
    modifier: Modifier = Modifier,
    activeTool: DrawingTool = DrawingTool.PEN,
    selectedColor: Color = Color(0xFF00F0FF),
    strokeWidthRatio: Float = 0.014f,
    eraserRadiusDp: Float = 30f,
    strokes: List<StrokePath>,
    isCanvasLoaded: Boolean = true,
    onStrokeAdded: (StrokePath) -> Unit,
    onStrokesReplaced: (List<StrokePath>) -> Unit,
    backgroundColor: Color = Color(0xFF121212)
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val drawingRepo = remember { DrawingRepository.getInstance(context) }
    val cacheManager = remember { DrawingCacheManager.getInstance(context) }
    val pairingRepo = remember { PairingRepository.getInstance(context) }

    val currentStrokesState by rememberUpdatedState(strokes)
    val currentIsLoadedState by rememberUpdatedState(isCanvasLoaded)
    val currentOnStrokeAdded by rememberUpdatedState(onStrokeAdded)
    val currentOnStrokesReplaced by rememberUpdatedState(onStrokesReplaced)
    val currentColorState by rememberUpdatedState(selectedColor)
    val currentWidthRatioState by rememberUpdatedState(strokeWidthRatio)
    val currentEraserRadiusDpState by rememberUpdatedState(eraserRadiusDp)

    var canvasSize by remember { mutableStateOf(IntSize(1080, 2400)) }
    val currentPoints = remember { mutableStateListOf<PointF>() }
    var lastRecordedPoint by remember { mutableStateOf<PointF?>(null) }

    val density = LocalDensity.current.density
    val minDistancePx = 4f * density // 4dp downsampling for smooth curves

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("drawing_canvas_box")
            .onSizeChanged { newSize ->
                if (newSize.width > 0 && newSize.height > 0 && newSize != canvasSize) {
                    canvasSize = newSize
                }
            }
            .pointerInput(activeTool, isCanvasLoaded) {
                if (!isCanvasLoaded) return@pointerInput

                when (activeTool) {
                    DrawingTool.PEN -> {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                currentPoints.clear()
                                val pt = PointF(offset.x / w, offset.y / h)
                                currentPoints.add(pt)
                                lastRecordedPoint = pt
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val currentPt = PointF(change.position.x / w, change.position.y / h)

                                val last = lastRecordedPoint
                                if (last == null) {
                                    currentPoints.add(currentPt)
                                    lastRecordedPoint = currentPt
                                } else {
                                    val distPx = hypot(
                                        (currentPt.x - last.x) * w,
                                        (currentPt.y - last.y) * h
                                    )
                                    if (distPx >= minDistancePx) {
                                        currentPoints.add(currentPt)
                                        lastRecordedPoint = currentPt
                                    }
                                }
                            },
                            onDragEnd = {
                                if (currentPoints.isNotEmpty() && currentIsLoadedState) {
                                    val newStroke = StrokePath(
                                        points = currentPoints.toList(),
                                        color = currentColorState.toArgb(),
                                        strokeWidth = currentWidthRatioState,
                                        alpha = currentColorState.alpha,
                                        isFilled = false
                                    )
                                    currentPoints.clear()
                                    lastRecordedPoint = null

                                    currentOnStrokeAdded(newStroke)

                                    val updatedStrokes = currentStrokesState + newStroke
                                    triggerInstantSync(
                                        context = context,
                                        drawingRepo = drawingRepo,
                                        cacheManager = cacheManager,
                                        pairingRepo = pairingRepo,
                                        coroutineScope = coroutineScope,
                                        strokesToSync = updatedStrokes,
                                        isLoaded = currentIsLoadedState
                                    )
                                } else {
                                    currentPoints.clear()
                                    lastRecordedPoint = null
                                }
                            },
                            onDragCancel = {
                                currentPoints.clear()
                                lastRecordedPoint = null
                            }
                        )
                    }

                    DrawingTool.FILL -> {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                currentPoints.clear()
                                val pt = PointF(offset.x / w, offset.y / h)
                                currentPoints.add(pt)
                                lastRecordedPoint = pt
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val currentPt = PointF(change.position.x / w, change.position.y / h)

                                val last = lastRecordedPoint
                                if (last == null) {
                                    currentPoints.add(currentPt)
                                    lastRecordedPoint = currentPt
                                } else {
                                    val distPx = hypot(
                                        (currentPt.x - last.x) * w,
                                        (currentPt.y - last.y) * h
                                    )
                                    if (distPx >= minDistancePx) {
                                        currentPoints.add(currentPt)
                                        lastRecordedPoint = currentPt
                                    }
                                }
                            },
                            onDragEnd = {
                                if (currentPoints.size >= 3 && currentIsLoadedState) {
                                    val filledShape = StrokePath(
                                        points = currentPoints.toList(),
                                        color = currentColorState.toArgb(),
                                        strokeWidth = currentWidthRatioState,
                                        alpha = currentColorState.alpha,
                                        isFilled = true
                                    )
                                    currentPoints.clear()
                                    lastRecordedPoint = null

                                    currentOnStrokeAdded(filledShape)

                                    val updatedStrokes = currentStrokesState + filledShape
                                    triggerInstantSync(
                                        context = context,
                                        drawingRepo = drawingRepo,
                                        cacheManager = cacheManager,
                                        pairingRepo = pairingRepo,
                                        coroutineScope = coroutineScope,
                                        strokesToSync = updatedStrokes,
                                        isLoaded = currentIsLoadedState
                                    )
                                } else {
                                    currentPoints.clear()
                                    lastRecordedPoint = null
                                }
                            },
                            onDragCancel = {
                                currentPoints.clear()
                                lastRecordedPoint = null
                            }
                        )
                    }

                    DrawingTool.ERASER -> {
                        var gestureErased = false
                        var activeStrokes = currentStrokesState

                        detectDragGestures(
                            onDragStart = { offset ->
                                gestureErased = false
                                activeStrokes = currentStrokesState
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val eraserPx = currentEraserRadiusDpState * density
                                val normRadius = eraserPx / w
                                val touchNorm = PointF(offset.x / w, offset.y / h)

                                val remaining = activeStrokes.filterNot { it.intersectsTouch(touchNorm, normRadius) }
                                if (remaining.size != activeStrokes.size) {
                                    activeStrokes = remaining
                                    gestureErased = true
                                    currentOnStrokesReplaced(remaining)
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val eraserPx = currentEraserRadiusDpState * density
                                val normRadius = eraserPx / w
                                val touchNorm = PointF(change.position.x / w, change.position.y / h)

                                val remaining = activeStrokes.filterNot { it.intersectsTouch(touchNorm, normRadius) }
                                if (remaining.size != activeStrokes.size) {
                                    activeStrokes = remaining
                                    gestureErased = true
                                    currentOnStrokesReplaced(remaining)
                                }
                            },
                            onDragEnd = {
                                if (gestureErased && currentIsLoadedState) {
                                    triggerInstantSync(
                                        context = context,
                                        drawingRepo = drawingRepo,
                                        cacheManager = cacheManager,
                                        pairingRepo = pairingRepo,
                                        coroutineScope = coroutineScope,
                                        strokesToSync = activeStrokes,
                                        isLoaded = currentIsLoadedState
                                    )
                                    gestureErased = false
                                }
                            },
                            onDragCancel = {
                                if (gestureErased && currentIsLoadedState) {
                                    triggerInstantSync(
                                        context = context,
                                        drawingRepo = drawingRepo,
                                        cacheManager = cacheManager,
                                        pairingRepo = pairingRepo,
                                        coroutineScope = coroutineScope,
                                        strokesToSync = activeStrokes,
                                        isLoaded = currentIsLoadedState
                                    )
                                    gestureErased = false
                                }
                            }
                        )
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().testTag("drawing_canvas_surface")) {
            val w = size.width
            val h = size.height

            // 1. Draw Background
            drawRect(color = backgroundColor)

            // 2. Hardware-accelerated rendering of all saved strokes
            for (stroke in strokes) {
                val points = stroke.points
                if (points.isEmpty()) continue

                val baseColor = stroke.color
                val baseAlpha = ((baseColor ushr 24) and 0xFF) / 255f
                val effectiveAlpha = (stroke.alpha * baseAlpha).coerceIn(0f, 1f)
                val composeColor = Color(
                    red = ((baseColor ushr 16) and 0xFF) / 255f,
                    green = ((baseColor ushr 8) and 0xFF) / 255f,
                    blue = (baseColor and 0xFF) / 255f,
                    alpha = effectiveAlpha
                )

                if (stroke.isFilled && points.size >= 3) {
                    val fillPath = Path().apply {
                        moveTo(points[0].x * w, points[0].y * h)
                        for (i in 1 until points.size) {
                            lineTo(points[i].x * w, points[i].y * h)
                        }
                        close()
                    }
                    drawPath(path = fillPath, color = composeColor, style = Fill)
                } else if (points.size == 1) {
                    val pt = points[0]
                    val radiusPx = (stroke.strokeWidth * w) / 2f
                    drawCircle(
                        color = composeColor,
                        radius = radiusPx.coerceAtLeast(2f),
                        center = Offset(pt.x * w, pt.y * h)
                    )
                } else {
                    val strokePx = (stroke.strokeWidth * w).coerceAtLeast(1f)
                    val curvePath = Path().apply {
                        moveTo(points[0].x * w, points[0].y * h)
                        for (i in 1 until points.size) {
                            val p1 = points[i - 1]
                            val p2 = points[i]
                            val midX = (p1.x + p2.x) / 2f * w
                            val midY = (p1.y + p2.y) / 2f * h
                            quadraticTo(p1.x * w, p1.y * h, midX, midY)
                        }
                        val last = points.last()
                        lineTo(last.x * w, last.y * h)
                    }

                    drawPath(
                        path = curvePath,
                        color = composeColor,
                        style = Stroke(
                            width = strokePx,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            // 3. Render active in-flight touch gesture on top at 120 FPS
            if (currentPoints.isNotEmpty()) {
                if (activeTool == DrawingTool.FILL && currentPoints.size >= 3) {
                    val fillPath = Path().apply {
                        val first = currentPoints.first()
                        moveTo(first.x * w, first.y * h)
                        for (i in 1 until currentPoints.size) {
                            val pt = currentPoints[i]
                            lineTo(pt.x * w, pt.y * h)
                        }
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        color = currentColorState.copy(alpha = 0.5f),
                        style = Fill
                    )
                }

                if (currentPoints.size == 1) {
                    val pt = currentPoints.first()
                    val radiusPx = (currentWidthRatioState * w) / 2f
                    drawCircle(
                        color = currentColorState,
                        radius = radiusPx.coerceAtLeast(2f),
                        center = Offset(pt.x * w, pt.y * h)
                    )
                } else if (currentPoints.size > 1) {
                    val inFlightPath = Path().apply {
                        val p0 = currentPoints[0]
                        moveTo(p0.x * w, p0.y * h)

                        for (i in 1 until currentPoints.size) {
                            val p1 = currentPoints[i - 1]
                            val p2 = currentPoints[i]
                            val midX = (p1.x + p2.x) / 2f * w
                            val midY = (p1.y + p2.y) / 2f * h
                            quadraticTo(p1.x * w, p1.y * h, midX, midY)
                        }
                        val last = currentPoints.last()
                        lineTo(last.x * w, last.y * h)
                    }

                    drawPath(
                        path = inFlightPath,
                        color = currentColorState,
                        style = Stroke(
                            width = (currentWidthRatioState * w).coerceAtLeast(1f),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
    }
}

private fun triggerInstantSync(
    context: Context,
    drawingRepo: DrawingRepository,
    cacheManager: DrawingCacheManager,
    pairingRepo: PairingRepository,
    coroutineScope: CoroutineScope,
    strokesToSync: List<StrokePath>,
    isLoaded: Boolean
) {
    if (!isLoaded) {
        Log.w("DrawingCanvas", "Skipping sync because canvas is not fully loaded yet")
        return
    }

    // 1. Synchronously save to strokes.json
    drawingRepo.saveDrawing(strokesToSync, broadcastRefresh = false)

    // 2. Broadcast REFRESH_WALLPAPER with FOREGROUND flag
    try {
        val refreshIntent = Intent("ru.wwmaxik.drawlock.REFRESH_WALLPAPER").apply {
            flags = Intent.FLAG_RECEIVER_FOREGROUND
        }
        context.sendBroadcast(refreshIntent)
    } catch (_: Exception) {}

    // 3. Asynchronously push to Firestore room document
    coroutineScope.launch(Dispatchers.IO) {
        try {
            val activeRoom = cacheManager.roomCode.value
            if (activeRoom.isNotBlank()) {
                pairingRepo.replaceStrokes(strokesToSync.map { it.toDrawingStroke() })
            }
        } catch (e: Exception) {
            Log.e("DrawingCanvas", "Error syncing stroke to Firestore: ${e.message}", e)
        }
    }
}
