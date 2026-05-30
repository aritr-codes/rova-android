package com.aritr.rova.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Reduced-motion decision seam (WCAG 2.2 AA SC 2.3.3 "Animation from
 * Interactions" / 2.2.2 "Pause, Stop, Hide"; ADR-0020). Honors the OS
 * "remove animations" accessibility toggle (and developer-options animation
 * scale), which both surface through `Settings.Global` animation scales = `0f`.
 *
 * Pure-helper pattern: [isReduced] is framework-free and unit-tested; the
 * Compose reader [rememberReduceMotion] is the thin framework seam.
 */
object ReducedMotion {

    /**
     * True when the user has disabled animations — either the window
     * transition scale or the animator-duration scale is `0`. A non-zero
     * scale (slowed/sped, but present) still permits motion.
     */
    fun isReduced(transitionScale: Float, animatorScale: Float): Boolean =
        transitionScale == 0f || animatorScale == 0f

    /** Reads the two global animation scales (default `1f` if unset/unreadable). */
    fun isReduced(context: Context): Boolean {
        val transition = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f,
        )
        val animator = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        )
        return isReduced(transition, animator)
    }
}

/**
 * Composable accessor for [ReducedMotion.isReduced]. Read once per composition
 * — the OS animation scale changes rarely and only via system settings, so a
 * keyed [remember] over the resolved value is sufficient and avoids a
 * ContentObserver on every animated node.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { ReducedMotion.isReduced(context) }
}
