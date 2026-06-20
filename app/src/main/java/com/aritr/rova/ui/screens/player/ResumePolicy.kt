package com.aritr.rova.ui.screens.player

import kotlin.math.max
import kotlin.math.min

/** Pure resume-position rules (PR-6). Near-end positions restart from 0 instead of parking at the end. */
object ResumePolicy {
    fun nearEndThresholdMs(durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return min(3000L, max(1000L, (durationMs * 0.02).toLong()))
    }

    fun shouldRestartFromStart(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        return (durationMs - positionMs) <= nearEndThresholdMs(durationMs)
    }

    fun resolveOpenPosition(savedMs: Long?, durationMs: Long): Long {
        val saved = savedMs ?: return 0L
        if (durationMs <= 0L) return saved.coerceAtLeast(0L)
        if (saved >= durationMs) return durationMs        // saved at/after end -> park at end
        return if (shouldRestartFromStart(saved, durationMs)) 0L else saved
    }
}
