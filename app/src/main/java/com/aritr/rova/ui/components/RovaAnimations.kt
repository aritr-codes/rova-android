package com.aritr.rova.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
