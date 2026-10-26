package com.stickerstudio.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.stickerstudio.app.data.ThemeMode
import com.stickerstudio.app.ui.AppRoot
import com.stickerstudio.app.ui.theme.StickerStudioTheme

/** AppCompatActivity (not ComponentActivity) so per-app language switching works on every API level. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as StickerStudioApp).container
        if (savedInstanceState == null) container.updates.checkOnStartup()
        setContent {
            val mode by container.settings.theme.collectAsState(initial = ThemeMode.DARK)
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            StickerStudioTheme(darkTheme = dark) { AppRoot() }
        }
    }
}
