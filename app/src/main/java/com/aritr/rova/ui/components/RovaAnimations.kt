package com.aritr.rova.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.library.PressFeedback
import com.aritr.rova.ui.theme.RecordChromeTokens

object RovaAnimations {

    @Composable
    fun pulsingOpacity(
        durationMillis: Int = 1000,
        minAlpha: Float = 0.3f,
        maxAlpha: Float = 1f
    ): Float {
        // WCAG 2.2 AA SC 2.3.3 / 2.2.2 (ADR-0020): honor the OS reduced-motion
        // toggle — hold a static, fully-visible value instead of pulsing.
        if (rememberReduceMotion()) return maxAlpha
        val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = minAlpha,
            targetValue = maxAlpha,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "Alpha"
        )
        return alpha
    }

    /**
     * Record-consistent press feedback: a subtle scale-down while [interactionSource] is pressed,
     * animated over [RecordChromeTokens.elementSpinMs]. Reduce-motion gated via [PressFeedback] +
     * [rememberReduceMotion] (WCAG 2.2 AA SC 2.3.3, ADR-0020 / checkA11yAnimationGated) — under
     * reduced motion the target is held at unity, so no animation runs. Apply to the card's inner
     * visual surface (NOT the Lazy item root) to avoid clipping/jitter against item bounds.
     */
    fun Modifier.pressScale(interactionSource: InteractionSource): Modifier = composed {
        val pressed by interactionSource.collectIsPressedAsState()
        val reduce = rememberReduceMotion()
        val target = PressFeedback.targetScale(pressed, reduce)
        val scale by animateFloatAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = RecordChromeTokens.elementSpinMs),
            label = "pressScale",
        )
        this.graphicsLayer { scaleX = scale; scaleY = scale }
    }

    fun Modifier.pulsingBorder(
        isRecording: Boolean,
        color: Color = Color.Red,
        strokeWidth: Int = 4
    ): Modifier = composed {
        if (isRecording) {
            val alpha = pulsingOpacity(durationMillis = 800, minAlpha = 0.5f, maxAlpha = 1f)
            this.border(
                width = strokeWidth.dp,
                color = color.copy(alpha = alpha),
                shape = RoundedCornerShape(16.dp)
            )
        } else {
            this
        }
    }
}
