package com.aritr.rova.ui.screens.player

/** Pure resume-position resolution seam (framework-free, JVM-testable). */
object PlayerResumeMath {
    fun startPositionMs(saved: Long?, durationMs: Long): Long =
        ResumePolicy.resolveOpenPosition(saved, durationMs)
}
