package com.aritr.rova.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Harbor90,
    onPrimary = Ink95,
    primaryContainer = Harbor40,
    onPrimaryContainer = Ink10,
    secondary = Copper90,
    onSecondary = Ink95,
    secondaryContainer = Copper40,
    onSecondaryContainer = Sand10,
    tertiary = Sage90,
    onTertiary = Ink95,
    tertiaryContainer = Sage40,
    onTertiaryContainer = Sand10,
    error = RecordingRed,
    errorContainer = OnRecordingRedContainer,
    onError = Color.White,
    onErrorContainer = RecordingRedContainer,
    background = Midnight,
    onBackground = Sand10,
    surface = MidnightSurface,
    onSurface = Sand10,
    surfaceVariant = MidnightSurfaceAlt,
    onSurfaceVariant = Ink30,
    outline = MidnightOutline,
    outlineVariant = Ink80,
    inverseSurface = Sand30,
    inverseOnSurface = Ink95,
    inversePrimary = Harbor40
)

private val LightColorScheme = lightColorScheme(
    primary = Harbor40,
    onPrimary = Color.White,
    primaryContainer = Harbor90,
    onPrimaryContainer = Ink95,
    secondary = Copper40,
    onSecondary = Color.White,
    secondaryContainer = Copper90,
    onSecondaryContainer = Ink95,
    tertiary = Sage40,
    onTertiary = Color.White,
    tertiaryContainer = Sage90,
    onTertiaryContainer = Ink95,
    error = RecordingRed,
    onError = Color.White,
    errorContainer = RecordingRedContainer,
    onErrorContainer = OnRecordingRedContainer,
    background = Sand30,
    onBackground = Ink95,
    surface = Sand10,
    onSurface = Ink95,
    surfaceVariant = Sand60,
    onSurfaceVariant = Ink80,
    outline = Sand90,
    outlineVariant = Sand60,
    inverseSurface = MidnightSurface,
    inverseOnSurface = Sand10,
    inversePrimary = Harbor90
)

@Composable
fun RovaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
