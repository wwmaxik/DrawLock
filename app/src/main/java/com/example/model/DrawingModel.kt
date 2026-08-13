package com.example.model

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject

/**
 * Normalized 2D point (x: 0.0..1.0, y: 0.0..1.0)
 * Normalization ensures consistent rendering across devices with different aspect ratios.
 */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f
)

/**
 * A single continuous stroke with color and width
 */
data class DrawingStroke(
    val color: Long, // ARGB long value
    val widthRatio: Float, // Stroke width relative to canvas width
    val points: List<StrokePoint>,
    val isFilled: Boolean = false
)

/**
 * Complete drawing payload sent between paired devices
 */
data class DrawingData(
    val senderId: String,
    val timestamp: Long,
    val strokes: List<DrawingStroke>
)

/**
 * Serializer to convert DrawingData to and from compact JSON format
 */
object DrawingSerializer {

    fun toJson(drawingData: DrawingData): String {
        val root = JSONObject()
        root.put("senderId", drawingData.senderId)
        root.put("timestamp", drawingData.timestamp)

        val strokesArray = JSONArray()
        for (stroke in drawingData.strokes) {
            val strokeObj = JSONObject()
            strokeObj.put("c", stroke.color)
            strokeObj.put("w", stroke.widthRatio.toDouble())
            if (stroke.isFilled) {
                strokeObj.put("f", true)
            }

            val pointsArray = JSONArray()
            for (p in stroke.points) {
                val pointObj = JSONObject()
                pointObj.put("x", (p.x * 10000).toInt() / 10000.0)
                pointObj.put("y", (p.y * 10000).toInt() / 10000.0)
                pointsArray.put(pointObj)
            }
            strokeObj.put("pts", pointsArray)
            strokesArray.put(strokeObj)
        }
        root.put("strokes", strokesArray)
        return root.toString()
    }

    fun fromJson(jsonStr: String): DrawingData? {
        return try {
            val root = JSONObject(jsonStr)
            val senderId = root.optString("senderId", "")
            val timestamp = root.optLong("timestamp", System.currentTimeMillis())

            val strokesList = mutableListOf<DrawingStroke>()
            val strokesArray = root.optJSONArray("strokes") ?: JSONArray()

            for (i in 0 until strokesArray.length()) {
                val strokeObj = strokesArray.getJSONObject(i)
                val color = strokeObj.optLong("c", 0xFF00F0FF)
                val widthRatio = strokeObj.optDouble("w", 0.01).toFloat()
                val isFilled = strokeObj.optBoolean("f", strokeObj.optBoolean("filled", false))

                val ptsArray = strokeObj.optJSONArray("pts") ?: JSONArray()
                val pointsList = mutableListOf<StrokePoint>()
                for (j in 0 until ptsArray.length()) {
                    val pObj = ptsArray.getJSONObject(j)
                    val x = pObj.optDouble("x", 0.0).toFloat()
                    val y = pObj.optDouble("y", 0.0).toFloat()
                    pointsList.add(StrokePoint(x = x, y = y))
                }
                if (pointsList.isNotEmpty()) {
                    strokesList.add(DrawingStroke(color = color, widthRatio = widthRatio, points = pointsList, isFilled = isFilled))
                }
            }
            DrawingData(senderId = senderId, timestamp = timestamp, strokes = strokesList)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

/**
 * Renders normalized strokes onto a target Android Canvas
 */
object DrawingBitmapRenderer {

    fun drawStrokesOnCanvas(
        canvas: Canvas,
        strokes: List<DrawingStroke>,
        width: Float,
        height: Float
    ) {
        val strokePaint = Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        val fillPaint = Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.FILL
        }

        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue

            val strokePx = (stroke.widthRatio * width).coerceAtLeast(4f)
            val strokeColor = stroke.color.toInt()

            if (stroke.points.size == 1) {
                val p = stroke.points[0]
                fillPaint.color = strokeColor
                canvas.drawCircle(p.x * width, p.y * height, strokePx / 2f, fillPaint)
                continue
            }

            val path = Path()
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

            if (stroke.isFilled) {
                path.close()
                fillPaint.color = strokeColor
                canvas.drawPath(path, fillPaint)
            } else {
                strokePaint.color = strokeColor
                strokePaint.strokeWidth = strokePx
                canvas.drawPath(path, strokePaint)
            }
        }
    }
}
