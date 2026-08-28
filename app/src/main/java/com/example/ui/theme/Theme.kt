package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Neo-Brutalism Professional Colors (Soft Pastels + True Black)
private val BrutalYellow = Color(0xFFFFCC00) // Deep yellow
private val BrutalCyan = Color(0xFF99E9F2)   // Soft cyan
private val BrutalPink = Color(0xFFFCC2D7)   // Soft pink
private val BrutalGreen = Color(0xFFB2F2BB)  // Soft green
private val BrutalBlack = Color(0xFF212529)  // Rich editorial black
private val BrutalWhite = Color(0xFFF8F9FA)  // Soft technical white
private val BrutalGray = Color(0xFFDEE2E6)   // Subtle gray


private val LightColorScheme = lightColorScheme(
    primary = BrutalYellow,
    onPrimary = BrutalBlack,
    primaryContainer = BrutalCyan,
    onPrimaryContainer = BrutalBlack,
    secondary = BrutalPink,
    onSecondary = BrutalBlack,
    secondaryContainer = BrutalGreen,
    onSecondaryContainer = BrutalBlack,
    background = BrutalWhite,
    onBackground = BrutalBlack,
    surface = BrutalWhite,
    onSurface = BrutalBlack,
    surfaceVariant = BrutalGray,
    onSurfaceVariant = BrutalBlack,
    error = Color.Red,
    onError = Color.White
)

fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) {
            return currentContext
        }
        currentContext = currentContext.baseContext
    }
    return null
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme // Force light mode for neo-brutalism contrast aesthetics

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity()
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
