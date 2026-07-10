package com.aritr.rova.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Centralized motion tokens (ADR-0028 §3.2). All animated surfaces consume
 * these so motion is consistent and can be reasoned about in one place. Every
 * looping/auto-playing use site MUST additionally gate on the reduced-motion
 * seam (`rememberReduceMotion()` / `ReducedMotion.isReduced`) per ADR-0020 —
 * enforced by `checkA11yAnimationGated`. PR1 only defines the tokens.
 */
object RovaMotion {
    const val containerStiffness: Float = 380f
    const val containerDamping: Float = 30f
    const val chipToggleMs: Int = 120
    const val dockShrinkMs: Int = 200
    const val recordPulseMs: Int = 2000

    // ── Trust System V1 ladder (frozen `docs/design/warnings-recovery.html`
    // :111–:112, §12 motion table). Additive — the existing 120/200ms tokens
    // are the same `--t-micro` / `--t-small` rungs and are unchanged.

    /** Container enter — sheet in, chip restore. `--t-container:300ms`. */
    const val containerMs: Int = 300

    /**
     * Container exit — sheet out, chip collapse. `--t-exit:220ms`. Deliberately
     * faster than [containerMs]: a surface the user has dismissed should not
     * linger.
     */
    const val exitMs: Int = 220

    /**
     * The house easing curve, `cubic-bezier(.2,.8,.2,1)` — adopted from the
     * bento spec and used by *all* Trust System motion. Front-loaded travel
     * (an ease-out): most of the distance is covered early, so the surface
     * feels responsive to touch rather than slow to start.
     */
    val easeStandard: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    /** Standard container transition spring. */
    fun <T> containerSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = containerStiffness,
    )
}
