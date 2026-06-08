package com.aritr.rova.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.aritr.rova.ui.components.ReducedMotion

// Phase 2.1A — dark scheme aligned with docs/UI_DESIGN_TOKENS.md §2.1.
// `primary` flips Harbor90 → InfraBlue (#5B7FFF) and `error` flips
// RecordingRed → SignalRed (#EF4444); the surface stack picks up the
// new Midnight / MidnightSurface / MidnightSurfaceAlt hex values from
// Color.kt. Container slots (primaryContainer, errorContainer, etc.)
// and on* tints stay as-shipped — only color values landed here, no
// slot-mapping changes.
private val DarkColorScheme = darkColorScheme(
    primary = InfraBlue,
    onPrimary = Color.White,
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
    error = SignalRed,
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
    // Phase 2.1A — pinned to the pre-2.1A MidnightSurface hex (#18212B) so
    // the light scheme is byte-identical with shipped Slices 1-4 even after
    // the dark MidnightSurface moved to #0E1216.
    inverseSurface = LightInverseSurface,
    inverseOnSurface = Sand10,
    inversePrimary = Harbor90
)

@Composable
fun RovaTheme(
    darkTheme: Boolean = true,        // ← M4 (2026-05-27): pin dark.
    // Was: `isSystemInDarkTheme()`. Light-mode users on fresh install were
    // landing on the unfinished light scheme. Full Light/Dark/System
    // switcher ships in a later milestone via RovaSettings.themeMode.
    // B2 — system-bar icon polarity. Defaults to `!darkTheme` (light bg →
    // dark icons). MainActivity overrides it per route so the pinned-dark
    // screens (viewfinder/player/onboarding) keep light icons in Light theme,
    // instead of invisible dark icons over their black surface. RovaTheme is
    // the SINGLE writer of bar polarity; RovaDarkSurface deliberately does not
    // touch the window bars.
    lightStatusBarIcons: Boolean = !darkTheme,
    dynamicColor: Boolean = false,
    // ADR-0028 — the active liquid-glass palette, seeded into LocalGlassEnvironment
    // for any descendant GlassSurface. Defaults to Aurora so existing call sites
    // (and previews) stay valid. The MaterialTheme color scheme is still driven by
    // [darkTheme] in PR1 (Aurora→dark, Daylight→light) — palette-driven MaterialTheme
    // colors migrate per surface in later PRs, so PR1 has no visual regression.
    palette: RovaPalette = rovaPalettes.getValue(ThemeSelection.AURORA),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightStatusBarIcons
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = lightStatusBarIcons
        }
    }

    val context = LocalContext.current
    val glassEnv = GlassEnvironment(
        palette = palette,
        apiLevel = Build.VERSION.SDK_INT,
        // ADR-0028 — reuse the available pure a11y seam as the transparency-
        // degradation proxy. A dedicated "reduce transparency" system read can
        // replace this later without touching GlassResolver.
        reduceTransparency = ReducedMotion.isReduced(context),
    )

    CompositionLocalProvider(LocalGlassEnvironment provides glassEnv) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}

/**
 * B2 — forces the dark [MaterialTheme] color scheme for a subtree, WITHOUT
 * the window-bar SideEffect that [RovaTheme] runs. Used to pin surfaces that
 * have only a dark design source (camera viewfinder, video player, onboarding)
 * to dark even when the app theme is Light, so their descendants that read
 * `colorScheme.*` get dark values consistent with their hardcoded dark
 * backgrounds (avoids light-on-dark mismatch). Status-bar polarity is owned by
 * the outer [RovaTheme] and is intentionally left untouched here.
 */
@Composable
fun RovaDarkSurface(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content,
    )
}
