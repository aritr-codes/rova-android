package com.aritr.rova.ui.library

/**
 * Pure press-feedback scale. Record-consistent subtle scale-down on press; held at unity under
 * reduce-motion (WCAG 2.2 AA SC 2.3.3, ADR-0020). Framework-free -> JVM-tested; the Compose Modifier
 * that animates toward this target lives in RovaAnimations and is reduce-motion gated.
 */
object PressFeedback {
    const val PRESSED_SCALE = 0.97f
    fun targetScale(pressed: Boolean, reduceMotion: Boolean): Float =
        if (pressed && !reduceMotion) PRESSED_SCALE else 1f
}
