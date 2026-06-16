package com.aritr.rova.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A System-D bespoke glyph (ADR-0031 §1/§5). Two tintable layers: a mono-safe [outline] that carries
 * the meaning on its own, and an optional duotone [accent] channel (the board's `.ac2` group) tinted
 * with the palette accent. Rendered by [com.aritr.rova.ui.components.SemanticIcon].
 */
@Immutable
data class RovaGlyph(val outline: ImageVector, val accent: ImageVector? = null)
