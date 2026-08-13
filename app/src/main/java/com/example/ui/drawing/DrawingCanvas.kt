package com.example.ui.drawing

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.model.DrawingBitmapRenderer
import com.example.model.DrawingStroke
import com.example.model.StrokePoint
import kotlin.math.hypot

enum class DrawingTool {
    PEN,
    ERASER,
    FILL
}

@Composable
fun DrawingCanvas(
    modifier: Modifier = Modifier,
    activeTool: DrawingTool = DrawingTool.PEN,
    selectedColor: Color,
    strokeWidthRatio: Float,
    strokes: List<DrawingStroke>,
    onStrokeAdded: (DrawingStroke) -> Unit,
    onStrokeFinished: (DrawingStroke) -> Unit = {},
    onStrokesErased: (List<DrawingStroke>) -> Unit = {},
    onStrokesReplaced: (List<DrawingStroke>) -> Unit = {},
    backgroundColor: Color = Color(0xFF121212)
) {
    var canvasSize by remember { mutableStateOf(IntSize(1080, 2400)) }
    val currentPoints = remember { mutableStateListOf<StrokePoint>() }
    var currentTouchPoint by remember { mutableStateOf<Offset?>(null) }
    var lastRecordedPixel by remember { mutableStateOf<Offset?>(null) }

    // Performance Optimization: Offscreen Bitmap Cache for all finalized strokes
    var cachedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cachedCanvas by remember { mutableStateOf<AndroidCanvas?>(null) }
    var cacheVersion by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current.density
    val minDistancePx = 4f * density // 4dp touch downsampling threshold
    val minDistanceSq = minDistancePx * minDistancePx

    // Rebuild or update offscreen cached bitmap whenever strokes collection or canvas size changes
    LaunchedEffect(strokes, canvasSize, backgroundColor) {
        val w = canvasSize.width.coerceAtLeast(100)
        val h = canvasSize.height.coerceAtLeast(100)

        var bmp = cachedBitmap
        if (bmp == null || bmp.width != w || bmp.height != h || bmp.isRecycled) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cachedBitmap = bmp
            cachedCanvas = AndroidCanvas(bmp)
        }

        val canvas = cachedCanvas
        if (canvas != null && bmp != null) {
            canvas.drawColor(backgroundColor.toArgb())
            DrawingBitmapRenderer.drawStrokesOnCanvas(
                canvas = canvas,
                strokes = strokes,
                width = w.toFloat(),
                height = h.toFloat()
            )
            cacheVersion++
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cachedBitmap?.recycle()
            cachedBitmap = null
            cachedCanvas = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    canvasSize = size
                }
            }
            .pointerInput(selectedColor, strokeWidthRatio, activeTool, strokes, canvasSize) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val normX = (offset.x / canvasSize.width).coerceIn(0f, 1f)
                        val normY = (offset.y / canvasSize.height).coerceIn(0f, 1f)

                        when (activeTool) {
                            DrawingTool.ERASER -> {
                                currentTouchPoint = offset
                                eraseStrokesAt(normX, normY, strokes, onStrokesErased)
                            }
                            DrawingTool.PEN, DrawingTool.FILL -> {
                                currentPoints.clear()
                                currentPoints.add(StrokePoint(normX, normY))
                                lastRecordedPixel = offset
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val pos = change.position
                        val normX = (pos.x / canvasSize.width).coerceIn(0f, 1f)
                        val normY = (pos.y / canvasSize.height).coerceIn(0f, 1f)

                        when (activeTool) {
                            DrawingTool.ERASER -> {
                                currentTouchPoint = pos
                                eraseStrokesAt(normX, normY, strokes, onStrokesErased)
                            }
                            DrawingTool.PEN, DrawingTool.FILL -> {
                                // Touch Point Downsampling (Filter points closer than 4dp)
                                val last = lastRecordedPixel
                                if (last != null) {
                                    val dx = pos.x - last.x
                                    val dy = pos.y - last.y
                                    if ((dx * dx + dy * dy) < minDistanceSq) {
                                        return@detectDragGestures
                                    }
                                }
                                currentPoints.add(StrokePoint(normX, normY))
                                lastRecordedPixel = pos
                            }
                        }
                    },
                    onDragEnd = {
                        if (activeTool == DrawingTool.ERASER) {
                            currentTouchPoint = null
                        } else {
                            if (currentPoints.isNotEmpty()) {
                                val argbInt = selectedColor.toArgb()
                                val isFilled = (activeTool == DrawingTool.FILL)

                                val newStroke = DrawingStroke(
                                    color = (argbInt.toLong() and 0xFFFFFFFFL),
                                    widthRatio = strokeWidthRatio,
                                    points = currentPoints.toList(),
                                    isFilled = isFilled
                                )

                                currentPoints.clear()
                                lastRecordedPixel = null

                                if (isFilled) {
                                    // Apply path union / merge with overlapping filled shapes of same color
                                    val mergedList = mergeFilledStrokeIfOverlapping(
                                        existingStrokes = strokes,
                                        newStroke = newStroke,
                                        canvasWidth = canvasSize.width.toFloat(),
                                        canvasHeight = canvasSize.height.toFloat()
                                    )
                                    if (mergedList != null) {
                                        onStrokesReplaced(mergedList)
                                    } else {
                                        onStrokeAdded(newStroke)
                                        onStrokeFinished(newStroke)
                                    }
                                } else {
                                    // 1. Instantly append & render onto offscreen bitmap for constant O(1) frame time
                                    cachedCanvas?.let { c ->
                                        DrawingBitmapRenderer.drawStrokesOnCanvas(
                                            canvas = c,
                                            strokes = listOf(newStroke),
                                            width = canvasSize.width.toFloat(),
                                            height = canvasSize.height.toFloat()
                                        )
                                        cacheVersion++
                                    }
                                    onStrokeAdded(newStroke)
                                    onStrokeFinished(newStroke)
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        currentPoints.clear()
                        currentTouchPoint = null
                        lastRecordedPixel = null
                    }
                )
            }
            .testTag("drawing_canvas")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // 1. Draw pre-rendered offscreen bitmap cache (1 single GPU draw call: O(1) performance)
            val bmp = cachedBitmap
            if (bmp != null && !bmp.isRecycled) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawBitmap(bmp, 0f, 0f, null)
                }
            }

            // 2. Draw only the 1 active stroke currently being touched/dragged on top
            if (activeTool != DrawingTool.ERASER && currentPoints.isNotEmpty()) {
                val strokePx = (strokeWidthRatio * width).coerceAtLeast(3f)
                val isFill = (activeTool == DrawingTool.FILL)

                if (currentPoints.size == 1) {
                    val p = currentPoints[0]
                    drawCircle(
                        color = selectedColor,
                        radius = strokePx / 2f,
                        center = Offset(p.x * width, p.y * height)
                    )
                } else {
                    val activePath = Path().apply {
                        val first = currentPoints.first()
                        moveTo(first.x * width, first.y * height)

                        for (i in 1 until currentPoints.size) {
                            val prev = currentPoints[i - 1]
                            val current = currentPoints[i]
                            val midX = (prev.x + current.x) / 2f * width
                            val midY = (prev.y + current.y) / 2f * height
                            quadraticTo(prev.x * width, prev.y * height, midX, midY)
                        }
                        val last = currentPoints.last()
                        lineTo(last.x * width, last.y * height)
                        if (isFill) {
                            close()
                        }
                    }

                    if (isFill) {
                        drawPath(
                            path = activePath,
                            color = selectedColor
                        )
                        drawPath(
                            path = activePath,
                            color = selectedColor.copy(alpha = 0.9f),
                            style = Stroke(
                                width = strokePx,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    } else {
                        drawPath(
                            path = activePath,
                            color = selectedColor,
                            style = Stroke(
                                width = strokePx,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }

            // 3. Render eraser pointer indicator
            if (activeTool == DrawingTool.ERASER) {
                currentTouchPoint?.let { pos ->
                    drawCircle(
                        color = Color.White.copy(alpha = 0.5f),
                        radius = 24f * density,
                        center = pos,
                        style = Stroke(width = 2f * density)
                    )
                }
            }
        }
    }
}

/**
 * Checks for stroke collisions near normalized touch coordinate and deletes intersecting strokes.
 */
private fun eraseStrokesAt(
    touchX: Float,
    touchY: Float,
    strokes: List<DrawingStroke>,
    onStrokesErased: (List<DrawingStroke>) -> Unit,
    radiusThreshold: Float = 0.045f
) {
    val radiusSq = radiusThreshold * radiusThreshold
    val remainingStrokes = strokes.filter { stroke ->
        !isStrokeIntersecting(stroke, touchX, touchY, radiusSq)
    }

    if (remainingStrokes.size != strokes.size) {
        onStrokesErased(remainingStrokes)
    }
}

private fun isStrokeIntersecting(
    stroke: DrawingStroke,
    touchX: Float,
    touchY: Float,
    radiusSq: Float
): Boolean {
    if (stroke.points.isEmpty()) return false

    for (i in stroke.points.indices) {
        val p = stroke.points[i]
        val dx = p.x - touchX
        val dy = p.y - touchY
        if ((dx * dx + dy * dy) <= radiusSq) {
            return true
        }

        if (i > 0) {
            val prev = stroke.points[i - 1]
            if (distanceToSegmentSq(touchX, touchY, prev.x, prev.y, p.x, p.y) <= radiusSq) {
                return true
            }
        }
    }
    return false
}

private fun distanceToSegmentSq(
    px: Float, py: Float,
    x1: Float, y1: Float,
    x2: Float, y2: Float
): Float {
    val l2 = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)
    if (l2 == 0f) {
        val dx = px - x1
        val dy = py - y1
        return dx * dx + dy * dy
    }
    var t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2
    t = t.coerceIn(0f, 1f)
    val projX = x1 + t * (x2 - x1)
    val projY = y1 + t * (y2 - y1)
    val dx = px - projX
    val dy = py - projY
    return dx * dx + dy * dy
}

/**
 * Performs vector union on overlapping filled shapes using Android Path.op(..., Path.Op.UNION)
 */
private fun mergeFilledStrokeIfOverlapping(
    existingStrokes: List<DrawingStroke>,
    newStroke: DrawingStroke,
    canvasWidth: Float,
    canvasHeight: Float
): List<DrawingStroke>? {
    val overlappingIndex = existingStrokes.indexOfLast { s ->
        s.isFilled && s.color == newStroke.color && strokesOverlap(s, newStroke)
    }

    if (overlappingIndex == -1) return null

    val targetStroke = existingStrokes[overlappingIndex]
    val path1 = createAndroidPath(targetStroke, canvasWidth, canvasHeight)
    val path2 = createAndroidPath(newStroke, canvasWidth, canvasHeight)

    val unionPath = AndroidPath()
    val success = unionPath.op(path1, path2, AndroidPath.Op.UNION)

    if (!success || unionPath.isEmpty) return null

    // Combine point sets or append to maintain clean vector polygon
    val mergedPoints = targetStroke.points + newStroke.points
    val mergedStroke = DrawingStroke(
        color = targetStroke.color,
        widthRatio = targetStroke.widthRatio,
        points = mergedPoints,
        isFilled = true
    )

    val mutableList = existingStrokes.toMutableList()
    mutableList[overlappingIndex] = mergedStroke
    return mutableList
}

private fun strokesOverlap(s1: DrawingStroke, s2: DrawingStroke): Boolean {
    // Fast bounding box overlap check
    var minX1 = Float.MAX_VALUE; var maxX1 = Float.MIN_VALUE
    var minY1 = Float.MAX_VALUE; var maxY1 = Float.MIN_VALUE
    for (p in s1.points) {
        if (p.x < minX1) minX1 = p.x; if (p.x > maxX1) maxX1 = p.x
        if (p.y < minY1) minY1 = p.y; if (p.y > maxY1) maxY1 = p.y
    }

    var minX2 = Float.MAX_VALUE; var maxX2 = Float.MIN_VALUE
    var minY2 = Float.MAX_VALUE; var maxY2 = Float.MIN_VALUE
    for (p in s2.points) {
        if (p.x < minX2) minX2 = p.x; if (p.x > maxX2) maxX2 = p.x
        if (p.y < minY2) minY2 = p.y; if (p.y > maxY2) maxY2 = p.y
    }

    val margin = 0.03f
    return !(maxX1 + margin < minX2 || minX1 - margin > maxX2 || maxY1 + margin < minY2 || minY1 - margin > maxY2)
}

private fun createAndroidPath(stroke: DrawingStroke, width: Float, height: Float): AndroidPath {
    val path = AndroidPath()
    if (stroke.points.isEmpty()) return path

    val first = stroke.points.first()
    path.moveTo(first.x * width, first.y * height)

    for (i in 1 until stroke.points.size) {
        val prev = stroke.points[i - 1]
        val current = stroke.points[i]
        val midX = (prev.x + current.x) / 2f * width
        val midY = (prev.y + current.y) / 2f * height
        path.quadTo(prev.x * width, prev.y * height, midX, midY)
    }
    val last = stroke.points.last()
    path.lineTo(last.x * width, last.y * height)
    path.close()
    return path
}
