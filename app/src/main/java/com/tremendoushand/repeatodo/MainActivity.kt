package com.tremendoushand.repeatodo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tremendoushand.repeatodo.data.AppSettings
import com.tremendoushand.repeatodo.data.ThemeMode
import com.tremendoushand.repeatodo.ui.RepeatodoNavHost
import com.tremendoushand.repeatodo.ui.theme.RepeatodoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RepeatodoApp).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            RepeatodoTheme(darkTheme = dark) {
                RepeatodoNavHost()
            }
        }
    }
}
