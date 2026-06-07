package com.aritr.rova.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The ambient glass environment (ADR-0028 §2.1), seeded once high in
 * [RovaTheme]. Stable: recomposes only on theme / reduce-transparency / config
 * change — all rare. Call sites read this via [LocalGlassEnvironment] and
 * resolve a concrete [GlassMaterial] through [GlassResolver].
 */
@Immutable
data class GlassEnvironment(
    val palette: RovaPalette,
    val apiLevel: Int,
    val reduceTransparency: Boolean,
)

/** The kind of surface a glass element backs (ADR-0028 §2.1). */
enum class GlassRole { RecordChrome, BottomSheet, Dialog, Card, NavBar, Banner }

/** The concrete material a role resolves to in a given environment. */
@Immutable
data class GlassMaterial(
    val fill: Color,
    val blurRadius: Dp,
    val edge: Color,
    val edgeTop: Color,
    val scrim: Brush?,
)

/**
 * Default environment for previews / un-provided trees: Aurora, a modern API
 * level, transparency allowed. Real trees override this in [RovaTheme].
 */
val LocalGlassEnvironment = staticCompositionLocalOf {
    GlassEnvironment(
        palette = rovaPalettes.getValue(ThemeSelection.AURORA),
        apiLevel = 31,
        reduceTransparency = false,
    )
}

/** Shared zero-blur constant for the no-blur paths. */
val NoBlur: Dp = 0.dp
