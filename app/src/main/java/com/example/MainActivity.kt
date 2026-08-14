package com.example

import android.app.WallpaperManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.drawing.DrawingActivity
import com.example.ui.main.MainScreen
import com.example.ui.main.MainViewModel
import com.example.ui.theme.DrawLockTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DrawLockTheme {
                MainScreen(
                    viewModel = viewModel,
                    onOpenLockDrawer = {
                        val intent = Intent(this, DrawingActivity::class.java)
                        startActivity(intent)
                    },
                    onSetWallpaper = {
                        launchWallpaperChooser()
                    }
                )
            }
        }
    }

    private fun launchWallpaperChooser() {
        try {
            startActivity(viewModel.getSetWallpaperIntent())
        } catch (e: Exception) {
            try {
                val chooserIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(chooserIntent)
            } catch (fallbackEx: Exception) {
                Toast.makeText(this, "Пожалуйста, выберите DrawLock в настройках живых обоев", Toast.LENGTH_LONG).show()
            }
        }
    }
}
