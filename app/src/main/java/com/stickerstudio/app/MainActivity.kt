package com.stickerstudio.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.stickerstudio.app.data.ThemeMode
import com.stickerstudio.app.ui.AppRoot
import com.stickerstudio.app.ui.IncomingShare
import com.stickerstudio.app.ui.theme.StickerStudioTheme

/** AppCompatActivity (not ComponentActivity) so per-app language switching works on every API level. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as StickerStudioApp).container
        if (savedInstanceState == null) {
            container.updates.checkOnStartup()
            IncomingShare.handle(this, intent)
        }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IncomingShare.handle(this, intent)
    }
}
