package com.example.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Normalized 2D coordinate (x: 0.0..1.0, y: 0.0..1.0 relative to full screen width/height)
 */
data class PointF(
    val x: Float,
    val y: Float
) {
    fun distanceTo(other: PointF): Float {
        val dx = this.x - other.x
        val dy = this.y - other.y
        return hypot(dx, dy)
    }
}

/**
 * Direct vector stroke model representing a smooth path or filled shape on canvas.
 * @param points List of normalized 2D points (0.0 to 1.0)
 * @param color 32-bit ARGB Integer (e.g. 0xFF00F0FF.toInt())
 * @param strokeWidth Normalized stroke width ratio relative to canvas width (e.g. 0.014f)
 * @param alpha Opacity ratio from 0.0f to 1.0f
 * @param isFilled If true, the closed path is filled as a polygon/shape instead of stroked
 */
data class StrokePath(
    val points: List<PointF>,
    val color: Int,
    val strokeWidth: Float,
    val alpha: Float = 1.0f,
    val isFilled: Boolean = false
) {
    val colorInt: Int get() = color

    fun toMap(): Map<String, Any> {
        return mapOf(
            "color" to color.toLong(),
            "strokeWidth" to strokeWidth.toDouble(),
            "width" to strokeWidth.toDouble(),
            "alpha" to alpha.toDouble(),
            "isFilled" to isFilled,
            "filled" to isFilled,
            "pts" to points.map { mapOf("x" to it.x.toDouble(), "y" to it.y.toDouble()) }
        )
    }

    fun toDrawingStroke(): DrawingStroke {
        return DrawingStroke(
            color = (color.toLong() and 0xFFFFFFFFL),
            widthRatio = strokeWidth,
            points = points.map { StrokePoint(it.x, it.y) },
            alpha = alpha,
            isFilled = isFilled
        )
    }

    /**
     * Checks if a touch point at (touchX, touchY) with given normalized radius collides with this stroke.
     */
    fun intersectsTouch(touch: PointF, radius: Float): Boolean {
        if (points.isEmpty()) return false
        val threshold = radius + (strokeWidth / 2f)

        // Single dot or short path
        if (points.size == 1) {
            return points[0].distanceTo(touch) <= threshold
        }

        // Check line segments
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            if (distanceToSegment(touch, p1, p2) <= threshold) {
                return true
            }
        }

        // If filled polygon, also check if touch is inside polygon bounds
        if (isFilled && isPointInPolygon(touch, points)) {
            return true
        }

        return false
    }

    private fun distanceToSegment(p: PointF, v: PointF, w: PointF): Float {
        val l2 = (w.x - v.x) * (w.x - v.x) + (w.y - v.y) * (w.y - v.y)
        if (l2 == 0f) return p.distanceTo(v)
        val t = max(0f, min(1f, ((p.x - v.x) * (w.x - v.x) + (p.y - v.y) * (w.y - v.y)) / l2))
        val projection = PointF(v.x + t * (w.x - v.x), v.y + t * (w.y - v.y))
        return p.distanceTo(projection)
    }

    private fun isPointInPolygon(p: PointF, poly: List<PointF>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]
            val pj = poly[j]
            if ((pi.y > p.y) != (pj.y > p.y) &&
                p.x < (pj.x - pi.x) * (p.y - pi.y) / ((pj.y - pi.y) + 1e-9f) + pi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    companion object {
        fun fromDrawingStroke(stroke: DrawingStroke): StrokePath {
            return StrokePath(
                points = stroke.points.map { PointF(it.x, it.y) },
                color = stroke.color.toInt(),
                strokeWidth = stroke.widthRatio,
                alpha = stroke.alpha,
                isFilled = stroke.isFilled
            )
        }

        fun fromMap(map: Map<*, *>): StrokePath? {
            val color = (map["color"] as? Number)?.toInt()
                ?: (map["c"] as? Number)?.toInt()
                ?: 0xFF00F0FF.toInt()
            val width = (map["strokeWidth"] as? Number)?.toFloat()
                ?: (map["width"] as? Number)?.toFloat()
                ?: (map["w"] as? Number)?.toFloat()
                ?: 0.014f
            val alpha = (map["alpha"] as? Number)?.toFloat()
                ?: (map["a"] as? Number)?.toFloat()
                ?: 1.0f
            val isFilled = (map["isFilled"] as? Boolean)
                ?: (map["filled"] as? Boolean)
                ?: (map["f"] as? Boolean)
                ?: false
            val ptsRaw = map["pts"] as? List<*> ?: return null
            val points = mutableListOf<PointF>()
            for (item in ptsRaw) {
                if (item is Map<*, *>) {
                    val x = (item["x"] as? Number)?.toFloat() ?: 0f
                    val y = (item["y"] as? Number)?.toFloat() ?: 0f
                    points.add(PointF(x, y))
                }
            }
            if (points.isEmpty()) return null
            return StrokePath(
                points = points,
                color = color,
                strokeWidth = width,
                alpha = alpha.coerceIn(0f, 1f),
                isFilled = isFilled
            )
        }
    }
}

/**
 * Fast serializer and parser for list of StrokePaths
 */
object StrokeSerializer {

    fun toJson(strokes: List<StrokePath>): String {
        val array = JSONArray()
        for (stroke in strokes) {
            val obj = JSONObject()
            obj.put("color", stroke.color)
            obj.put("strokeWidth", stroke.strokeWidth.toDouble())
            obj.put("width", stroke.strokeWidth.toDouble())
            obj.put("alpha", stroke.alpha.toDouble())
            obj.put("isFilled", stroke.isFilled)
            obj.put("filled", stroke.isFilled)

            val ptsArray = JSONArray()
            for (p in stroke.points) {
                val ptObj = JSONObject()
                ptObj.put("x", (p.x * 10000).toInt() / 10000.0)
                ptObj.put("y", (p.y * 10000).toInt() / 10000.0)
                ptsArray.put(ptObj)
            }
            obj.put("pts", ptsArray)
            array.put(obj)
        }
        return array.toString()
    }

    fun fromJson(jsonStr: String): List<StrokePath> {
        if (jsonStr.isBlank()) return emptyList()
        val result = mutableListOf<StrokePath>()
        try {
            val trimmed = jsonStr.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val color = if (obj.has("color")) obj.getInt("color") else obj.optInt("c", 0xFF00F0FF.toInt())
                    val width = if (obj.has("strokeWidth")) obj.getDouble("strokeWidth").toFloat()
                        else if (obj.has("width")) obj.getDouble("width").toFloat()
                        else obj.optDouble("w", 0.014).toFloat()
                    val alpha = if (obj.has("alpha")) obj.getDouble("alpha").toFloat()
                        else obj.optDouble("a", 1.0).toFloat()
                    val isFilled = obj.optBoolean("isFilled", obj.optBoolean("filled", obj.optBoolean("f", false)))
                    val ptsArray = obj.optJSONArray("pts") ?: JSONArray()
                    val points = mutableListOf<PointF>()
                    for (j in 0 until ptsArray.length()) {
                        val ptObj = ptsArray.getJSONObject(j)
                        val x = ptObj.optDouble("x", 0.0).toFloat()
                        val y = ptObj.optDouble("y", 0.0).toFloat()
                        points.add(PointF(x, y))
                    }
                    if (points.isNotEmpty()) {
                        result.add(StrokePath(
                            points = points,
                            color = color,
                            strokeWidth = width,
                            alpha = alpha.coerceIn(0f, 1f),
                            isFilled = isFilled
                        ))
                    }
                }
            } else if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                val strokesArray = root.optJSONArray("strokes") ?: JSONArray()
                for (i in 0 until strokesArray.length()) {
                    val obj = strokesArray.getJSONObject(i)
                    val color = if (obj.has("color")) {
                        obj.getInt("color")
                    } else if (obj.has("c")) {
                        obj.getLong("c").toInt()
                    } else {
                        0xFF00F0FF.toInt()
                    }
                    val width = if (obj.has("strokeWidth")) {
                        obj.getDouble("strokeWidth").toFloat()
                    } else if (obj.has("width")) {
                        obj.getDouble("width").toFloat()
                    } else {
                        obj.optDouble("w", 0.014).toFloat()
                    }
                    val alpha = if (obj.has("alpha")) {
                        obj.getDouble("alpha").toFloat()
                    } else {
                        obj.optDouble("a", 1.0).toFloat()
                    }
                    val isFilled = obj.optBoolean("isFilled", obj.optBoolean("filled", obj.optBoolean("f", false)))
                    val ptsArray = obj.optJSONArray("pts") ?: JSONArray()
                    val points = mutableListOf<PointF>()
                    for (j in 0 until ptsArray.length()) {
                        val ptObj = ptsArray.getJSONObject(j)
                        val x = ptObj.optDouble("x", 0.0).toFloat()
                        val y = ptObj.optDouble("y", 0.0).toFloat()
                        points.add(PointF(x, y))
                    }
                    if (points.isNotEmpty()) {
                        result.add(StrokePath(
                            points = points,
                            color = color,
                            strokeWidth = width,
                            alpha = alpha.coerceIn(0f, 1f),
                            isFilled = isFilled
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}
