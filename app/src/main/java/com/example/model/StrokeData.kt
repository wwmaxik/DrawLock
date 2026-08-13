package com.example.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Normalized 2D coordinate (x: 0.0..1.0, y: 0.0..1.0 relative to canvas width/height)
 */
data class PointF(
    val x: Float,
    val y: Float
)

/**
 * Direct vector stroke model representing a smooth path on canvas
 * @param points List of normalized 2D points (0.0 to 1.0)
 * @param color 32-bit ARGB Integer (e.g. 0xFF00FF9F.toInt())
 * @param strokeWidth Normalized stroke width ratio relative to canvas width (e.g. 0.014f)
 */
data class StrokePath(
    val points: List<PointF>,
    val color: Int,
    val strokeWidth: Float,
    val isFilled: Boolean = false
) {
    val colorInt: Int get() = color

    fun toMap(): Map<String, Any> {
        return mapOf(
            "color" to color.toLong(),
            "width" to strokeWidth.toDouble(),
            "filled" to isFilled,
            "pts" to points.map { mapOf("x" to it.x.toDouble(), "y" to it.y.toDouble()) }
        )
    }

    // Conversion helper to DrawingStroke for backwards compatibility
    fun toDrawingStroke(): DrawingStroke {
        return DrawingStroke(
            color = (color.toLong() and 0xFFFFFFFFL),
            widthRatio = strokeWidth,
            points = points.map { StrokePoint(it.x, it.y) },
            isFilled = isFilled
        )
    }

    companion object {
        fun fromDrawingStroke(stroke: DrawingStroke): StrokePath {
            return StrokePath(
                points = stroke.points.map { PointF(it.x, it.y) },
                color = stroke.color.toInt(),
                strokeWidth = stroke.widthRatio,
                isFilled = stroke.isFilled
            )
        }

        fun fromMap(map: Map<*, *>): StrokePath? {
            val color = (map["color"] as? Number)?.toInt()
                ?: (map["c"] as? Number)?.toInt()
                ?: 0xFF00F0FF.toInt()
            val width = (map["width"] as? Number)?.toFloat()
                ?: (map["w"] as? Number)?.toFloat()
                ?: 0.014f
            val isFilled = (map["filled"] as? Boolean)
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
            return StrokePath(points = points, color = color, strokeWidth = width, isFilled = isFilled)
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
            obj.put("width", stroke.strokeWidth.toDouble())
            if (stroke.isFilled) {
                obj.put("filled", true)
            }

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
                    val color = obj.optInt("color", 0xFF00F0FF.toInt())
                    val width = obj.optDouble("width", 0.014).toFloat()
                    val isFilled = obj.optBoolean("filled", false)
                    val ptsArray = obj.optJSONArray("pts") ?: JSONArray()
                    val points = mutableListOf<PointF>()
                    for (j in 0 until ptsArray.length()) {
                        val ptObj = ptsArray.getJSONObject(j)
                        val x = ptObj.optDouble("x", 0.0).toFloat()
                        val y = ptObj.optDouble("y", 0.0).toFloat()
                        points.add(PointF(x, y))
                    }
                    if (points.isNotEmpty()) {
                        result.add(StrokePath(points = points, color = color, strokeWidth = width, isFilled = isFilled))
                    }
                }
            } else if (trimmed.startsWith("{")) {
                // Handle DrawingData wrapper format
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
                    val width = if (obj.has("width")) {
                        obj.getDouble("width").toFloat()
                    } else {
                        obj.optDouble("w", 0.014).toFloat()
                    }
                    val isFilled = obj.optBoolean("filled", obj.optBoolean("f", false))
                    val ptsArray = obj.optJSONArray("pts") ?: JSONArray()
                    val points = mutableListOf<PointF>()
                    for (j in 0 until ptsArray.length()) {
                        val ptObj = ptsArray.getJSONObject(j)
                        val x = ptObj.optDouble("x", 0.0).toFloat()
                        val y = ptObj.optDouble("y", 0.0).toFloat()
                        points.add(PointF(x, y))
                    }
                    if (points.isNotEmpty()) {
                        result.add(StrokePath(points = points, color = color, strokeWidth = width, isFilled = isFilled))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}
