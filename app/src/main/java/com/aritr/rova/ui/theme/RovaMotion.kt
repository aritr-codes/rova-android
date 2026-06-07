package com.aritr.rova.ui.theme

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

    /** Standard container transition spring. */
    fun <T> containerSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = containerStiffness,
    )
}
