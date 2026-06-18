package com.aritr.rova.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.MergeMotion
import com.aritr.rova.ui.theme.RovaIcons

/**
 * Branded "merge in progress" indicator (ADR-0031 §6/§8, Icon P2 Track A).
 * Renders [RovaIcons.Merge] through the [SemanticIcon] seam, locked to
 * [IconStatus.Processing] (`RovaSemantics.escalating`), spinning one revolution
 * per [MergeMotion.SPIN_PERIOD_MS]. Drop-in replacement for the two static merge
 * indicators (Record-home StatusPill, recovery card header).
 *
 * Decorative: `contentDescription = null` — both host surfaces already announce
 * "Merging" through their own polite live regions, so a spoken glyph would
 * double-announce.
 *
 * Reduced motion (WCAG 2.2 AA SC 2.3.3 / 2.2.2, ADR-0020): when the OS toggle is
 * on we render the glyph STATIC and never build the infinite transition (no
 * wasted recomposition). The mark still communicates "merging"; only the spin
 * is dropped. The same-file `rememberReduceMotion()` read satisfies
 * `checkA11yAnimationGated`.
 */
@Composable
fun ProcessingGlyph(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val reduceMotion = rememberReduceMotion()
    val sized = modifier.size(size)
    if (reduceMotion) {
        SemanticIcon(
            glyph = RovaIcons.Merge,
            contentDescription = null,
            status = IconStatus.Processing,
            modifier = sized,
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "mergeSpin")
    val fraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = MergeMotion.SPIN_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "mergeSpinFraction",
    )
    SemanticIcon(
        glyph = RovaIcons.Merge,
        contentDescription = null,
        status = IconStatus.Processing,
        // Read `fraction` INSIDE the graphicsLayer lambda so rotation updates in the
        // draw phase only — no recomposition at animation-frame cadence (codex).
        modifier = sized.graphicsLayer { rotationZ = MergeMotion.angle(fraction, reduceMotion = false) },
    )
}
