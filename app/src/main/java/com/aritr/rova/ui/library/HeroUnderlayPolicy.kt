package com.aritr.rova.ui.library

/**
 * Decides whether the static VideoFrame under-layer renders beneath the autoplay PlayerView.
 *
 * RCA (2026-06-15, device RZCYA1VBQ2H): the static `VideoFrame` (`ContentScale.Crop`) and the
 * PlayerView (`RESIZE_MODE_ZOOM`) fill identically only when the box is ~16:9. In the off-16:9 hero
 * box (~2.27:1) the two layers diverge and leak a grey band on DualShot recordings. The under-layer
 * exists only to mask the black shutter before the first decoded frame — once a frame is rendered it
 * has no job, so dropping it removes the leak source entirely. Framework-free -> JVM-tested.
 */
object HeroUnderlayPolicy {
    fun showStaticUnderlay(firstFrameRendered: Boolean): Boolean = !firstFrameRendered
}
