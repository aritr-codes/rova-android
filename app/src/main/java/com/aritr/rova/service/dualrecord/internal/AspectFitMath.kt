package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.service.dualrecord.VideoSide

/**
 * Phase 6.1c — pure-JVM math seam for the DualShot render pipeline.
 * Holds (1) aspect-fit letterbox viewport math and (2) cropMatrix
 * construction for the per-target UV transform.
 *
 * Pulled out of [EglRouter] so the math can be pinned by JVM unit
 * tests without an EGL context. See
 * `docs/superpowers/specs/2026-05-17-phase-6.1c-dualshot-render-design.md`
 * §5.4 for the contract.
 */
internal object AspectFitMath {

    /**
     * Aspect-fit viewport inside a surface of [surfaceW] × [surfaceH]
     * for content of aspect [contentAspect] (= width/height). Returns
     * `[x, y, w, h]` for `glViewport`. Pillar-box bars when content is
     * taller than surface; letterbox bars when content is wider than
     * surface. Surface dims must be positive; contentAspect must be > 0.
     */
    fun computeFitViewport(surfaceW: Int, surfaceH: Int, contentAspect: Float): IntArray {
        require(surfaceW > 0 && surfaceH > 0) { "surface dims must be > 0; was ${surfaceW}x${surfaceH}" }
        require(contentAspect > 0f) { "contentAspect must be > 0; was $contentAspect" }
        val surfaceAspect = surfaceW.toFloat() / surfaceH.toFloat()
        return if (contentAspect >= surfaceAspect) {
            // Content wider or equal: fit width, letterbox top+bottom.
            val viewportH = (surfaceW / contentAspect).toInt()
            val viewportY = (surfaceH - viewportH) / 2
            intArrayOf(0, viewportY, surfaceW, viewportH)
        } else {
            // Content taller: fit height, pillar-box left+right.
            val viewportW = (surfaceH * contentAspect).toInt()
            val viewportX = (surfaceW - viewportW) / 2
            intArrayOf(viewportX, 0, viewportW, surfaceH)
        }
    }
}
