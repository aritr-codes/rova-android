package com.aritr.rova.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Role-aware glass surface (ADR-0028 §2.1). Reads [LocalGlassEnvironment],
 * resolves a [GlassMaterial] via [GlassResolver], and renders, back-to-front:
 *   1. a fill layer (blurred ONLY when the resolver returned a non-zero radius),
 *   2. an optional scrim + edge layer,
 *   3. the sharp [content] layer (never blurred).
 *
 * This is the single sanctioned `Modifier.blur` site in the app (enforced by
 * `checkGlassSurfaceRoleUsage`). NOTE: Compose's `Modifier.blur` blurs a
 * composable's own pixels — it is NOT a CSS `backdrop-filter`; it cannot sample
 * what is painted behind the surface. Depth comes from fill + scrim + edge. The
 * blur is therefore confined to the back fill layer and MUST never touch the
 * content subtree.
 */
@Composable
fun GlassSurface(
    role: GlassRole,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable () -> Unit,
) {
    val env = LocalGlassEnvironment.current
    val material = GlassResolver.resolve(env, role)

    Box(modifier = modifier.clip(shape)) {
        // Layer 1 — fill (blurred only on the blur path; never wraps content).
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (material.blurRadius.value > 0f) Modifier.blur(material.blurRadius)
                    else Modifier,
                )
                .background(material.fill, shape),
        )
        // Layer 2 — scrim (if any) + edge highlight.
        Box(
            Modifier
                .matchParentSize()
                .then(material.scrim?.let { Modifier.background(it, shape) } ?: Modifier)
                .border(1.dp, material.edge, shape),
        )
        // Layer 3 — sharp content, on top, unblurred.
        content()
    }
}
