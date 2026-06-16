package com.aritr.rova.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.LocalGlassEnvironment
import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.SemanticIconSpec

/**
 * The single entry-point for glyph color (ADR-0031 §4). All in-app `Icon(...)` tints flow through
 * here so the theme engine can drive icon color from one seam. Reads the active palette from
 * [LocalGlassEnvironment]; `status` (locked RovaSemantics) wins over `role` (identity) when present.
 *
 * This is the ONLY file allowlisted by `checkSemanticIconNoRawAlpha` to apply a raw Color tint.
 */
@Composable
fun SemanticIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    role: IconRole = IconRole.Default,
    status: IconStatus? = null,
) {
    val palette = LocalGlassEnvironment.current.palette
    val tint = if (status != null) {
        SemanticIconSpec.statusTint(status)
    } else {
        SemanticIconSpec.tint(palette, role)
    }
    Icon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier, tint = tint)
}

/**
 * Two-layer overload for bespoke [RovaGlyph]s (ADR-0031 §1). Stacks the outline (tinted by role or
 * status via [SemanticIconSpec]) and the optional duotone accent layer (tinted by the palette accent —
 * the retintable channel). `status` locks the whole mark and suppresses the separate accent tint.
 * Callers MUST pass a size in [modifier] (both layers use `matchParentSize`).
 */
@Composable
fun SemanticIcon(
    glyph: RovaGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    role: IconRole = IconRole.Default,
    status: IconStatus? = null,
) {
    val palette = LocalGlassEnvironment.current.palette
    val baseTint = if (status != null) SemanticIconSpec.statusTint(status) else SemanticIconSpec.tint(palette, role)
    Box(modifier) {
        Icon(glyph.outline, contentDescription, Modifier.matchParentSize(), tint = baseTint)
        glyph.accent?.let { acc ->
            val accentTint = if (status != null) baseTint else palette.accent
            Icon(acc, contentDescription = null, Modifier.matchParentSize(), tint = accentTint)
        }
    }
}
