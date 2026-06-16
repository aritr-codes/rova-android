package com.aritr.rova.ui.theme

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
    private const val RECORD_ALPHA = 0.40f

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

        // RecordChrome (PR2, ADR-0028 §2.2): airy no-blur fill (~0.40) appropriate over
        // a LIVE camera — the 0.88 slab fought the preview. NO resolver scrim: the record
        // dock's bottomNavBrush owns the gradient; a resolver scrim would double-stack.
        if (role == GlassRole.RecordChrome) {
            return GlassMaterial(
                fill = p.glassTint.atLeastAlpha(RECORD_ALPHA),
                blurRadius = 0.dp,
                edge = p.edge,
                edgeTop = p.edgeTop,
                scrim = null,
            )
        }

        // Floating modal surfaces (BottomSheet / Dialog) — M2 (2026-06-16): a card that floats over the
        // live, scrolling library must read as a SOLID surface, not a translucent slab the content bleeds
        // through. Near-opaque palette-tinted fill (so it joins the theme identity) + NO blur (GlassSurface's
        // blur can't sample what's behind it anyway). The cast shadow lives on the FloatingGlassSheet wrapper.
        if (role == GlassRole.BottomSheet || role == GlassRole.Dialog) {
            return GlassMaterial(
                fill = p.glassTint.atLeastAlpha(0.95f),
                blurRadius = 0.dp,
                edge = p.edge,
                edgeTop = p.edgeTop,
                scrim = null,
            )
        }

        val blurAllowed = env.apiLevel >= 31   // reduceTransparency + RecordChrome + modal sheets handled above

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

    /** Raise this color's alpha to at least [min], leaving heavier tints untouched. */
    private fun Color.atLeastAlpha(min: Float): Color =
        if (alpha >= min) this else copy(alpha = min)
}
