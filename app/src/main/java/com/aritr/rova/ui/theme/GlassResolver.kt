package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Pure resolution of a [GlassRole] to a concrete [GlassMaterial] in a given
 * [GlassEnvironment] (ADR-0028 §2.2). Framework-free → JVM-testable. The single
 * place the blur/fallback/scrim rules live; [GlassSurface] only renders the
 * output.
 */
object GlassResolver {

    private val DefaultBlur = 24.dp
    private const val FALLBACK_ALPHA = 0.86f
    private const val RECORD_ALPHA = 0.88f

    fun resolve(env: GlassEnvironment, role: GlassRole): GlassMaterial {
        val p = env.palette

        // Reduce-Transparency wins over EVERY role (incl. RecordChrome): fully
        // solid high-contrast surface, no blur, no scrim shimmer. Checked FIRST so
        // an accessibility opt-out is never overridden by a role-specific branch.
        if (env.reduceTransparency) {
            return GlassMaterial(
                fill = p.glassTint.copy(alpha = 1f),
                blurRadius = 0.dp,
                edge = p.edge,
                edgeTop = p.edgeTop,
                scrim = null,
            )
        }

        // RecordChrome (transparency allowed): heavy fill + gradient scrim + edge,
        // never blur (§2.2/§2.3) — depth via scrim, not backdrop blur.
        if (role == GlassRole.RecordChrome) {
            return GlassMaterial(
                fill = p.glassTint.atLeastAlpha(RECORD_ALPHA),
                blurRadius = 0.dp,
                edge = p.edge,
                edgeTop = p.edgeTop,
                scrim = recordScrim(),
            )
        }

        val blurAllowed = env.apiLevel >= 31   // reduceTransparency + RecordChrome already handled above

        return if (blurAllowed) {
            GlassMaterial(
                fill = p.glassTint,
                blurRadius = DefaultBlur,
                edge = p.edge,
                edgeTop = p.edgeTop,
                scrim = null,
            )
        } else {
            GlassMaterial(
                fill = p.glassTint.atLeastAlpha(FALLBACK_ALPHA),
                blurRadius = 0.dp,
                edge = p.edge,
                edgeTop = p.edgeTop,
                scrim = null,
            )
        }
    }

    /** Bottom-anchored darkening scrim used behind record chrome. */
    private fun recordScrim(): Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            0.55f to Color.Black.copy(alpha = 0.22f),
            1.0f to Color.Black.copy(alpha = 0.55f),
        ),
    )

    /** Raise this color's alpha to at least [min], leaving heavier tints untouched. */
    private fun Color.atLeastAlpha(min: Float): Color =
        if (alpha >= min) this else copy(alpha = min)
}
