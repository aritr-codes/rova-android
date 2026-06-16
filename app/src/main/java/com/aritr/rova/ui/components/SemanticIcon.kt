package com.aritr.rova.ui.components

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.LocalGlassEnvironment
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
