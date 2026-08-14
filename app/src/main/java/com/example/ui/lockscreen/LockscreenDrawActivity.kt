package com.example.ui.lockscreen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.example.ui.drawing.DrawingScreen
import com.example.ui.theme.DrawLockTheme

/**
 * Backward compatibility alias for DrawingActivity.
 * Purely an in-app drawing screen without any keyguard hooks or lockscreen flags.
 */
class LockscreenDrawActivity : ComponentActivity() {

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
