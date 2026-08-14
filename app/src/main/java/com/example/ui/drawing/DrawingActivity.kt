package com.example.ui.drawing

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.R
import com.example.data.DrawingCacheManager
import com.example.data.DrawingRepository
import com.example.data.PairingRepository
import com.example.data.StreakManager
import com.example.model.StrokePath
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DrawingPalette
import com.example.ui.theme.DrawLockTheme
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Dedicated In-App Drawing Experience.
 * Strict 1:1 pixel-perfect edge-to-edge normalized coordinate sync with Live Wallpaper.
 * Real-time instant on-stroke-release auto-sync, Undo/Clear immediate sync, Zen/Focus drawing mode,
 * adjustable eraser radius, and async storage loading with empty state guards.
 */
class DrawingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        setContent {
            DrawLockTheme {
                DrawingScreen(
                    onClose = { finish() },
                    onDrawingFinished = { finish() }
                )
            }
        }
    }
}

@Composable
fun DrawingScreen(
    onClose: () -> Unit,
    onDrawingFinished: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val drawingRepo = remember { DrawingRepository.getInstance(context) }
    val cacheManager = remember { DrawingCacheManager.getInstance(context) }
    val pairingRepo = remember { PairingRepository.getInstance(context) }
    val streakManager = remember { StreakManager.getInstance(context) }

    val roomCode by cacheManager.roomCode.collectAsState()
    val streakCount by streakManager.streakCount.collectAsState()

    val strokes = remember { mutableStateListOf<StrokePath>() }
    var isCanvasLoaded by remember { mutableStateOf(false) }

    var activeTool by remember { mutableStateOf(DrawingTool.PEN) }
    var selectedBaseColor by remember { mutableStateOf(Color(0xFF00F0FF)) }
    var strokeAlpha by remember { mutableFloatStateOf(1.0f) }
    var selectedWidthRatio by remember { mutableFloatStateOf(0.014f) }
    var eraserSizeDp by remember { mutableFloatStateOf(30f) }
    var isUiVisible by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Tools, 1: Settings
    var isSending by remember { mutableStateOf(false) }

    val effectiveColor = remember(selectedBaseColor, strokeAlpha) {
        selectedBaseColor.copy(alpha = strokeAlpha)
    }

    // Load initial drawing on background IO thread to prevent UI freezing
    LaunchedEffect(Unit) {
        val existing = drawingRepo.getLatestDrawingAsync()
        strokes.clear()
        strokes.addAll(existing)
        isCanvasLoaded = true
    }

    fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(15)
                }
            }
        } catch (_: Exception) {}
    }

    fun syncDrawingState(currentStrokes: List<StrokePath>) {
        if (!isCanvasLoaded) {
            Log.w("DrawingActivity", "Skipping syncDrawingState: canvas is not loaded yet")
            return
        }

        // 1. Synchronously save to strokes.json
        drawingRepo.saveDrawing(currentStrokes, broadcastRefresh = false)

        // 2. Broadcast REFRESH_WALLPAPER with FOREGROUND flag for instant redraw
        try {
            val refreshIntent = Intent("ru.wwmaxik.drawlock.REFRESH_WALLPAPER").apply {
                flags = Intent.FLAG_RECEIVER_FOREGROUND
            }
            context.sendBroadcast(refreshIntent)
        } catch (_: Exception) {}

        // 3. Asynchronously sync to Firestore room document
        coroutineScope.launch(Dispatchers.IO) {
            try {
                pairingRepo.ensureAuthenticated()
                if (roomCode.isNotBlank()) {
                    pairingRepo.replaceStrokes(currentStrokes.map { it.toDrawingStroke() })
                }
            } catch (e: Exception) {
                Log.e("DrawingActivity", "Error syncing drawing state to Firestore: ${e.message}", e)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // 1. Physical Screen 1:1 Vector Canvas (Edge-to-Edge, Always 100% Full-Screen)
        DrawingCanvas(
            modifier = Modifier.fillMaxSize(),
            activeTool = activeTool,
            selectedColor = effectiveColor,
            strokeWidthRatio = selectedWidthRatio,
            eraserRadiusDp = eraserSizeDp,
            strokes = strokes.toList(),
            isCanvasLoaded = isCanvasLoaded,
            onStrokeAdded = { newStroke ->
                strokes.add(newStroke)
                triggerHapticFeedback()
            },
            onStrokesReplaced = { updatedStrokes ->
                strokes.clear()
                strokes.addAll(updatedStrokes)
                triggerHapticFeedback()
            },
            backgroundColor = DarkBackground
        )

        // 2. Floating Top Header Bar (Animated in Zen Mode)
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xCC1A1A26),
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left actions: Close & Toggle Zen/Focus Mode
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("btn_close_drawer")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = TextSecondary
                            )
                        }

                        IconButton(
                            onClick = {
                                isUiVisible = false
                                triggerHapticFeedback()
                            },
                            modifier = Modifier.testTag("btn_toggle_zen_mode")
                        ) {
                            Icon(
                                imageVector = Icons.Default.VisibilityOff,
                                contentDescription = "Скрыть панели",
                                tint = TextSecondary
                            )
                        }
                    }

                    // Center: Streak / Огонёк Badge + Undo/Clear
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Daily Streak Glass Badge
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0x40FF5722),
                            border = BorderStroke(1.dp, Color(0x66FF5722)),
                            modifier = Modifier.padding(end = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fire_streak),
                                    contentDescription = "Огонёк",
                                    tint = Color(0xFFFF5722),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "$streakCount",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        // Undo with immediate local & remote sync
                        IconButton(
                            onClick = {
                                if (isCanvasLoaded && strokes.isNotEmpty()) {
                                    strokes.removeAt(strokes.lastIndex)
                                    triggerHapticFeedback()
                                    syncDrawingState(strokes.toList())
                                }
                            },
                            enabled = isCanvasLoaded && strokes.isNotEmpty(),
                            modifier = Modifier.testTag("btn_undo")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Отменить",
                                tint = if (isCanvasLoaded && strokes.isNotEmpty()) TextPrimary else TextMuted
                            )
                        }

                        // Clear with immediate local & remote sync
                        IconButton(
                            onClick = {
                                if (isCanvasLoaded && strokes.isNotEmpty()) {
                                    strokes.clear()
                                    triggerHapticFeedback()
                                    syncDrawingState(emptyList())
                                }
                            },
                            enabled = isCanvasLoaded && strokes.isNotEmpty(),
                            modifier = Modifier.testTag("btn_clear")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Очистить",
                                tint = if (isCanvasLoaded && strokes.isNotEmpty()) Color(0xFFFF5252) else TextMuted
                            )
                        }
                    }

                    // Done / Finish Button ("Готово")
                    Button(
                        onClick = {
                            if (isSending || !isCanvasLoaded) return@Button
                            isSending = true
                            triggerHapticFeedback()

                            coroutineScope.launch(Dispatchers.IO) {
                                val strokeList = strokes.toList()

                                // 1. Safety final flush to strokes.json
                                drawingRepo.saveDrawing(strokeList, broadcastRefresh = false)

                                // 2. Broadcast REFRESH_WALLPAPER with FOREGROUND flag
                                try {
                                    val refreshIntent = Intent("ru.wwmaxik.drawlock.REFRESH_WALLPAPER").apply {
                                        flags = Intent.FLAG_RECEIVER_FOREGROUND
                                    }
                                    context.sendBroadcast(refreshIntent)
                                } catch (_: Exception) {}

                                // 3. Update Streak & Finalize Firestore
                                try {
                                    val currentUid = pairingRepo.ensureAuthenticated()
                                    if (roomCode.isNotBlank()) {
                                        streakManager.recordDrawingSent(roomCode, currentUid)
                                        pairingRepo.replaceStrokes(strokeList.map { it.toDrawingStroke() })
                                    }
                                } catch (e: Exception) {
                                    Log.e("DrawingActivity", "Error recording streak in Done handler: ${e.message}", e)
                                }

                                // 4. Clean exit
                                withContext(Dispatchers.Main) {
                                    onDrawingFinished()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        modifier = Modifier.testTag("btn_done_send")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Готово",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // 3. Minimal Floating Restore Button (Zen Mode Active)
        AnimatedVisibility(
            visible = !isUiVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 12.dp, end = 16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xCC1E1E2E),
                border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable {
                        isUiVisible = true
                        triggerHapticFeedback()
                    }
                    .testTag("btn_restore_ui")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Показать инструменты",
                        tint = NeonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Инструменты",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 4. Sleek Floating Glassmorphism Tabbed Panel (Bottom)
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xEE1E1E2E),
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Tab Header: Инструменты / Настройки
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = NeonCyan,
                        divider = {},
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = NeonCyan,
                                height = 3.dp
                            )
                        }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = {
                                selectedTab = 0
                                triggerHapticFeedback()
                            },
                            text = {
                                Text(
                                    "Инструменты",
                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == 0) NeonCyan else TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedTab = 1
                                triggerHapticFeedback()
                            },
                            text = {
                                Text(
                                    "Настройки",
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == 1) NeonCyan else TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Tab 1: Tools (Кисть, Заливка, Ластик)
                    AnimatedVisibility(
                        visible = selectedTab == 0,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ToolOptionButton(
                                icon = Icons.Default.Brush,
                                label = "Кисть",
                                isSelected = activeTool == DrawingTool.PEN,
                                onClick = {
                                    activeTool = DrawingTool.PEN
                                    triggerHapticFeedback()
                                }
                            )

                            ToolOptionButton(
                                icon = Icons.Default.FormatColorFill,
                                label = "Заливка",
                                isSelected = activeTool == DrawingTool.FILL,
                                onClick = {
                                    activeTool = DrawingTool.FILL
                                    triggerHapticFeedback()
                                }
                            )

                            ToolOptionButton(
                                icon = Icons.Default.DeleteOutline,
                                label = "Ластик",
                                isSelected = activeTool == DrawingTool.ERASER,
                                onClick = {
                                    activeTool = DrawingTool.ERASER
                                    triggerHapticFeedback()
                                }
                            )
                        }
                    }

                    // Tab 2: Settings (Палитра, Толщина / Размер ластика, Прозрачность)
                    AnimatedVisibility(
                        visible = selectedTab == 1,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (activeTool == DrawingTool.ERASER) {
                                // Eraser Settings: Only Size Slider
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Размер ластика",
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${eraserSizeDp.roundToInt()} dp",
                                        color = NeonCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Slider(
                                    value = eraserSizeDp,
                                    onValueChange = { eraserSizeDp = it },
                                    valueRange = 10f..80f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = NeonCyan,
                                        activeTrackColor = NeonCyan,
                                        inactiveTrackColor = Color(0x33FFFFFF)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                // Pen / Fill Settings: Color Palette, Stroke Width, Opacity
                                // 1. Color Palette
                                Text(
                                    text = "Цвет",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    DrawingPalette.forEach { color ->
                                        val isSelected = (selectedBaseColor == color)
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(color)
                                                .border(
                                                    width = if (isSelected) 3.dp else 1.dp,
                                                    color = if (isSelected) Color.White else Color(0x33FFFFFF),
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    selectedBaseColor = color
                                                    triggerHapticFeedback()
                                                }
                                        )
                                    }
                                }

                                // 2. Stroke Width Preset Selector & Slider
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Толщина линии",
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${(selectedWidthRatio * 1000).roundToInt() / 10f} dp",
                                        color = NeonCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Slider(
                                    value = selectedWidthRatio,
                                    onValueChange = { selectedWidthRatio = it },
                                    valueRange = 0.005f..0.045f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = NeonCyan,
                                        activeTrackColor = NeonCyan,
                                        inactiveTrackColor = Color(0x33FFFFFF)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // 3. Opacity Slider
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Прозрачность",
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${(strokeAlpha * 100).roundToInt()}%",
                                        color = NeonCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Slider(
                                    value = strokeAlpha,
                                    onValueChange = { strokeAlpha = it },
                                    valueRange = 0.1f..1.0f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = NeonCyan,
                                        activeTrackColor = NeonCyan,
                                        inactiveTrackColor = Color(0x33FFFFFF)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolOptionButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) Color(0x3300F0FF) else Color(0x15FFFFFF),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) NeonCyan else Color(0x22FFFFFF)
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) NeonCyan else TextSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = if (isSelected) NeonCyan else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
