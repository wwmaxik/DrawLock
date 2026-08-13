package com.example.ui.lockscreen

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DrawingCacheManager
import com.example.data.DrawingRepository
import com.example.data.PairingRepository
import com.example.model.DrawingStroke
import com.example.model.PointF
import com.example.model.StrokePath
import com.example.ui.drawing.DrawingCanvas
import com.example.ui.drawing.DrawingTool
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DrawingPalette
import com.example.ui.theme.DrawLockTheme
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

class LockscreenDrawActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureLockscreenFlags()

        setContent {
            DrawLockTheme {
                LockscreenDrawScreen(
                    onDismiss = { finish() },
                    onDone = { strokes ->
                        triggerHaptic()
                        persistAndBroadcastDrawing(strokes)
                        finish()
                    },
                    onStrokeUpdated = { strokes ->
                        persistAndBroadcastDrawing(strokes)
                    }
                )
            }
        }
    }

    private fun persistAndBroadcastDrawing(strokes: List<DrawingStroke>) {
        val vectorStrokes = strokes.map { s ->
            StrokePath(
                points = s.points.map { PointF(it.x, it.y) },
                color = s.color.toInt(),
                strokeWidth = s.widthRatio,
                isFilled = s.isFilled
            )
        }

        // 1. Save normalized strokes JSON directly to strokes.json & SharedPreferences and dispatch REFRESH_WALLPAPER broadcast
        DrawingRepository.getInstance(applicationContext).saveDrawing(vectorStrokes, broadcastRefresh = true)

        // 2. Sync strokes with Firestore room without blocking UI
        PairingRepository.getInstance(applicationContext).replaceStrokes(strokes)
    }

    private fun configureLockscreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(35)
                }
            }
        } catch (e: Exception) {
            // Ignore if vibration unavailable
        }
    }
}

@Composable
fun LockscreenDrawScreen(
    onDismiss: () -> Unit,
    onDone: (List<DrawingStroke>) -> Unit,
    onStrokeUpdated: (List<DrawingStroke>) -> Unit
) {
    val context = LocalContext.current
    val cacheManager = remember { DrawingCacheManager.getInstance(context) }
    val drawingRepository = remember { DrawingRepository.getInstance(context) }
    val pairingRepository = remember { PairingRepository.getInstance(context) }

    // Brush and tool states
    var selectedTool by remember { mutableStateOf(DrawingTool.PEN) }
    var baseColor by remember { mutableStateOf(DrawingPalette[0]) }
    var selectedOpacity by remember { mutableFloatStateOf(1.0f) }
    var selectedWidthRatio by remember { mutableFloatStateOf(0.014f) }
    val strokes = remember { mutableStateListOf<DrawingStroke>() }

    // Tabbed panel state (0 = Инструменты, 1 = Настройки)
    var selectedTab by remember { mutableIntStateOf(0) }

    val effectiveColor = remember(baseColor, selectedOpacity) {
        baseColor.copy(alpha = selectedOpacity)
    }

    // Live sync: Collect incoming merged strokes from partner/Firestore
    val remoteDrawingData by cacheManager.currentDrawing.collectAsState()

    // Initialize with existing drawing strokes if available
    LaunchedEffect(Unit) {
        val existingVectorStrokes = drawingRepository.getLatestDrawing()
        if (existingVectorStrokes.isNotEmpty()) {
            strokes.clear()
            strokes.addAll(existingVectorStrokes.map { it.toDrawingStroke() })
        } else {
            val existingDrawing = cacheManager.currentDrawing.value
            if (existingDrawing != null && existingDrawing.strokes.isNotEmpty()) {
                strokes.clear()
                strokes.addAll(existingDrawing.strokes)
            }
        }
    }

    // Real-time canvas synchronization when partner draws or clears
    LaunchedEffect(remoteDrawingData) {
        remoteDrawingData?.let { data ->
            if (strokes.toList() != data.strokes) {
                strokes.clear()
                strokes.addAll(data.strokes)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Fullscreen High-Performance Drawing Canvas (>90% height) with Offscreen Bitmap Caching
        DrawingCanvas(
            modifier = Modifier.fillMaxSize(),
            activeTool = selectedTool,
            selectedColor = effectiveColor,
            strokeWidthRatio = selectedWidthRatio,
            strokes = strokes,
            onStrokeAdded = { newStroke ->
                strokes.add(newStroke)
            },
            onStrokeFinished = { newStroke ->
                pairingRepository.appendStrokes(listOf(newStroke))
                onStrokeUpdated(strokes.toList())
            },
            onStrokesErased = { remainingStrokes ->
                strokes.clear()
                strokes.addAll(remainingStrokes)
                pairingRepository.replaceStrokes(remainingStrokes)
                onStrokeUpdated(remainingStrokes)
            },
            onStrokesReplaced = { updatedStrokes ->
                strokes.clear()
                strokes.addAll(updatedStrokes)
                pairingRepository.replaceStrokes(updatedStrokes)
                onStrokeUpdated(updatedStrokes)
            },
            backgroundColor = DarkBackground
        )

        // Minimalist Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Close (X) Icon Button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xEE1E1E24), CircleShape)
                    .border(1.dp, DarkBorder, CircleShape)
                    .testTag("close_draw_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Right: Undo, Clear (Trash), and Primary Accent "Готово" Button
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Undo Button
                AnimatedVisibility(
                    visible = strokes.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    IconButton(
                        onClick = {
                            if (strokes.isNotEmpty()) {
                                strokes.removeAt(strokes.lastIndex)
                                pairingRepository.replaceStrokes(strokes.toList())
                                onStrokeUpdated(strokes.toList())
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xEE1E1E24), CircleShape)
                            .border(1.dp, DarkBorder, CircleShape)
                            .testTag("undo_stroke_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Отменить штрих",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Clear Canvas Button (Trash)
                AnimatedVisibility(
                    visible = strokes.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    IconButton(
                        onClick = {
                            strokes.clear()
                            pairingRepository.clearDrawing()
                            onStrokeUpdated(emptyList())
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xEE1E1E24), CircleShape)
                            .border(1.dp, DarkBorder, CircleShape)
                            .testTag("clear_canvas_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Очистить холст",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Primary Accent "Готово" Button
                Button(
                    onClick = {
                        onDone(strokes.toList())
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = DarkBackground
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier
                        .height(40.dp)
                        .testTag("done_drawing_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Готово",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Готово",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Dedicated Tabbed Controls UI: Sleek Dark Glassmorphism Card (#1E1E24)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color(0xF21E1E24),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, DarkBorder),
                shadowElevation = 14.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header Tabs: TAB 1: "Инструменты" | TAB 2: "Настройки"
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = NeonCyan,
                        divider = {},
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = NeonCyan,
                                height = 2.dp
                            )
                        }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Brush,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "Инструменты",
                                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                }
                            },
                            selectedContentColor = NeonCyan,
                            unselectedContentColor = TextMuted
                        )

                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "Настройки",
                                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                }
                            },
                            selectedContentColor = NeonCyan,
                            unselectedContentColor = TextMuted
                        )
                    }

                    // Tab Contents
                    when (selectedTab) {
                        0 -> {
                            // TAB 1: Инструменты (Pen, Eraser, Fill/Union)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ToolToggleButton(
                                    icon = Icons.Default.Brush,
                                    label = "Кисть",
                                    isSelected = selectedTool == DrawingTool.PEN,
                                    onSelect = { selectedTool = DrawingTool.PEN }
                                )

                                ToolToggleButton(
                                    icon = Icons.Default.AutoFixNormal,
                                    label = "Ластик",
                                    isSelected = selectedTool == DrawingTool.ERASER,
                                    onSelect = { selectedTool = DrawingTool.ERASER }
                                )

                                ToolToggleButton(
                                    icon = Icons.Default.FormatColorFill,
                                    label = "Заливка",
                                    isSelected = selectedTool == DrawingTool.FILL,
                                    onSelect = { selectedTool = DrawingTool.FILL }
                                )
                            }
                        }
                        1 -> {
                            // TAB 2: Настройки (Color Palette, Stroke Width, Opacity Slider)
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // 1. Color Palette (Horizontal scrollable)
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    items(DrawingPalette) { color ->
                                        val isSelected = baseColor == color
                                        Box(
                                            modifier = Modifier
                                                .size(if (isSelected) 32.dp else 26.dp)
                                                .clip(CircleShape)
                                                .background(color)
                                                .border(
                                                    width = if (isSelected) 2.5.dp else 0.dp,
                                                    color = if (isSelected) Color.White else Color.Transparent,
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    baseColor = color
                                                    if (selectedTool == DrawingTool.ERASER) {
                                                        selectedTool = DrawingTool.PEN
                                                    }
                                                }
                                                .testTag("color_dot_${color.value}")
                                        )
                                    }
                                }

                                // 2. Stroke Thickness Selector + Opacity Slider in Row/Column
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Stroke Thickness Selector
                                    Surface(
                                        color = Color(0xFF141418),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, DarkBorder)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(3.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CompactStrokeSegment(
                                                label = "Тонкий",
                                                dotSize = 4.dp,
                                                isSelected = selectedWidthRatio == 0.008f,
                                                onSelect = { selectedWidthRatio = 0.008f }
                                            )
                                            CompactStrokeSegment(
                                                label = "Средний",
                                                dotSize = 7.dp,
                                                isSelected = selectedWidthRatio == 0.014f,
                                                onSelect = { selectedWidthRatio = 0.014f }
                                            )
                                            CompactStrokeSegment(
                                                label = "Жирный",
                                                dotSize = 10.dp,
                                                isSelected = selectedWidthRatio == 0.024f,
                                                onSelect = { selectedWidthRatio = 0.024f }
                                            )
                                        }
                                    }

                                    // Opacity Percentage Badge
                                    Surface(
                                        color = Color(0xFF141418),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, DarkBorder),
                                        modifier = Modifier.padding(start = 6.dp)
                                    ) {
                                        Text(
                                            text = "${(selectedOpacity * 100).roundToInt()}%",
                                            color = NeonCyan,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }

                                // 3. Opacity Slider (10% to 100%)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Прозрачность",
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Slider(
                                        value = selectedOpacity,
                                        onValueChange = { selectedOpacity = it },
                                        valueRange = 0.1f..1.0f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = NeonCyan,
                                            activeTrackColor = NeonCyan,
                                            inactiveTrackColor = Color(0xFF2A2A32)
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolToggleButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        color = if (isSelected) NeonCyan.copy(alpha = 0.22f) else Color(0xFF26262E),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) NeonCyan else DarkBorder
        ),
        modifier = Modifier
            .clickable { onSelect() }
            .testTag("tool_button_$label")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) NeonCyan else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                color = if (isSelected) NeonCyan else TextSecondary,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
fun CompactStrokeSegment(
    label: String,
    dotSize: androidx.compose.ui.unit.Dp,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        color = if (isSelected) NeonCyan.copy(alpha = 0.22f) else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) NeonCyan else Color.Transparent
        ),
        modifier = Modifier.clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(if (isSelected) NeonCyan else TextMuted)
            )
            Text(
                text = label,
                color = if (isSelected) NeonCyan else TextMuted,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
