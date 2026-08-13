package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.DrawingRepository
import com.example.model.DrawingData
import com.example.model.DrawingSerializer
import com.example.model.DrawingStroke
import com.example.model.PointF
import com.example.model.StrokePath
import com.example.model.StrokePoint
import com.example.model.StrokeSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("DrawLock", appName)
  }

  @Test
  fun `test vector stroke serialization and DrawingRepository persistence`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = DrawingRepository.getInstance(context)

    val strokes = listOf(
      StrokePath(
        points = listOf(PointF(0.1f, 0.2f), PointF(0.3f, 0.4f), PointF(0.5f, 0.6f)),
        color = 0xFF00F0FF.toInt(),
        strokeWidth = 0.014f
      )
    )

    repo.saveDrawing(strokes, broadcastRefresh = false)
    val loaded = repo.getLatestDrawing()

    assertEquals(1, loaded.size)
    assertEquals(0xFF00F0FF.toInt(), loaded[0].color)
    assertEquals(3, loaded[0].points.size)
    assertEquals(0.1f, loaded[0].points[0].x, 0.001f)
    assertEquals(0.2f, loaded[0].points[0].y, 0.001f)
  }

  @Test
  fun `test drawing serialization and deserialization`() {
    val original = DrawingData(
      senderId = "test_user_123",
      timestamp = 1700000000L,
      strokes = listOf(
        DrawingStroke(
          color = 0xFF00F0FF,
          widthRatio = 0.012f,
          points = listOf(
            StrokePoint(0.1f, 0.2f),
            StrokePoint(0.3f, 0.4f),
            StrokePoint(0.5f, 0.6f)
          )
        )
      )
    )

    val json = DrawingSerializer.toJson(original)
    val parsed = DrawingSerializer.fromJson(json)

    assertNotNull(parsed)
    assertEquals("test_user_123", parsed?.senderId)
    assertEquals(1, parsed?.strokes?.size)
    assertEquals(3, parsed?.strokes?.first()?.points?.size)
  }
}
