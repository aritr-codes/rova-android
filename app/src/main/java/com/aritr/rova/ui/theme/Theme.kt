package com.aritr.rova.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.aritr.rova.ui.components.ReducedMotion


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
    val colorScheme = PaletteColorScheme.from(palette)

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
    // Pinned camera/media routes stay cinematic neutral-dark (ADR-0028 §2.4). The
    // pinned-env swap is provided INSIDE this composable's content by the caller, so
    // we apply forPinnedRoute here too to build the scheme from the neutral-dark base
    // carrying ONLY the active accent — never the (possibly light) app surface.
    val pinned = PinnedGlassEnvironment.forPinnedRoute(LocalGlassEnvironment.current)
    MaterialTheme(
        colorScheme = PaletteColorScheme.from(pinned.palette),
        typography = Typography,
        content = content,
    )
}
