package com.aritr.rova.service.dualrecord.internal

import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import com.aritr.rova.service.dualrecord.LensFacing
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.utils.RovaLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 6.1a — `SurfaceProcessor` impl bridging CameraX to `EglRouter`.
 * Wraps a single EglRouter; encoder surfaces are registered via
 * `attachEncoderInput`. The CameraEffect target is `PREVIEW`, so CameraX
 * delivers PreviewView's expected output via `onOutputSurface`.
 *
 * Released-recorder safety: if `onInputSurface` fires after `release()`,
 * the impl LOGS and returns — does NOT throw (spec §1: safer pattern;
 * release()-before-rebind throwing risked an uncaught exception inside
 * CameraX's dispatcher). Impl-time verified against CameraX 1.4.2 behavior.
 */
internal class DualSurfaceProcessor(lensFacing: LensFacing) : SurfaceProcessor {

    private val router = EglRouter(lensFacing).also { it.setup() }
    private val released = AtomicBoolean(false)

    /** Add an encoder input surface; `side` non-null. Must be called BEFORE the first frame arrives. */
    fun attachEncoderInput(side: VideoSide, surface: Surface) {
        if (released.get()) return
        router.addTarget(side, surface)
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (released.get()) {
            RovaLog.w("DualSurfaceProcessor.onInputSurface after release — ignoring", null)
            return
        }
        val cameraSurface = router.inputSurface
        request.provideSurface(cameraSurface, /* executor */ Runnable::run, Consumer { result ->
            RovaLog.d("DualSurfaceProcessor input surface released (result=${result.resultCode})")
        })
        router.setOnFrameAvailableListener { router.renderFrame() }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (released.get()) {
            RovaLog.w("DualSurfaceProcessor.onOutputSurface after release — ignoring", null)
            output.close()
            return
        }
        val previewSurface = output.getSurface(/* executor */ Runnable::run, Consumer { event ->
            RovaLog.d("DualSurfaceProcessor.onOutputSurface event (code=${event.eventCode})")
        })
        router.addTarget(side = null, surface = previewSurface)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        try { router.release() } catch (e: Throwable) { RovaLog.w("DualSurfaceProcessor router release", e) }
    }
}
