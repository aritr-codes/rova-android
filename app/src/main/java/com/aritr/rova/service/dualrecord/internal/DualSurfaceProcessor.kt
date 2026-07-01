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
 *
 * Phase 6.1b smoke-fix:
 *  - `attachEncoderInput` now takes `width`/`height` so EglRouter can set
 *    `glViewport` per target.
 *  - `onInputSurface` calls `router.setInputBufferSize` from the
 *    `SurfaceRequest.resolution` BEFORE handing the camera the producer
 *    Surface (Bug 2 fix — SurfaceTexture would otherwise default to
 *    display density and starve frames).
 *  - `onOutputSurface` passes `SurfaceOutput.size` through to
 *    `router.addTarget` for the PREVIEW target's viewport.
 */
internal class DualSurfaceProcessor(
    lensFacing: LensFacing,
    displayRotation: Int,
    sensorOrientation: Int = 90,
    useFirstPrinciplesRender: Boolean = false,
    enableMatrixSnapshots: Boolean = false,
) : SurfaceProcessor {

    private val router = EglRouter(
        lensFacing,
        displayRotation,
        sensorOrientation,
        useFirstPrinciplesRender,
        enableMatrixSnapshots,
    ).also { it.setup() }
    private val released = AtomicBoolean(false)

    /** ADR-0035 — forward the thermal decimation factor to the router. */
    fun setEncodeDecimationFactor(factor: Int) {
        router.encodeDecimationFactor = factor
    }

    /**
     * Add an encoder input surface; `side` non-null. Must be called BEFORE
     * the first frame arrives. `width`/`height` are the encoder's
     * configured output dimensions (see `DualVideoRecorderConfig`).
     */
    fun attachEncoderInput(side: VideoSide, surface: Surface, width: Int, height: Int) {
        if (released.get()) return
        router.addTarget(side, TargetKind.ENCODER, surface, width, height)
    }

    /**
     * Phase 6.1c — register a UI-side TextureView surface as a preview
     * render target. Called from [RovaRecordingService.attachDualPreview]
     * when a [DualPreviewZone] TextureView attaches. Must be called
     * BEFORE the first frame arrives for the target to render.
     */
    fun attachPreviewInput(side: VideoSide, surface: Surface, width: Int, height: Int) {
        if (released.get()) return
        router.addTarget(side, TargetKind.PREVIEW, surface, width, height)
    }

    /**
     * Phase 6.1c — un-register a previously-attached preview target.
     * Called from [RovaRecordingService.detachDualPreview] when the
     * TextureView detaches. Idempotent.
     */
    fun detachPreviewInput(side: VideoSide) {
        if (released.get()) return
        router.removeTarget(side, TargetKind.PREVIEW)
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (released.get()) {
            RovaLog.w("DualSurfaceProcessor.onInputSurface after release — ignoring", null)
            return
        }
        // Set the input SurfaceTexture's default buffer size from the
        // camera-resolved resolution BEFORE provideSurface so the producer
        // dequeues correctly-sized frames (Bug 2 of the 6.1b smoke-fix).
        router.setInputBufferSize(request.resolution.width, request.resolution.height)
        // Diagnostic — capture the resolution CameraX actually delivers so
        // we can compare against AspectFitMath.SOURCE_ASPECT_W/_H (4:3). If
        // these disagree, the per-side crop math operates on a wrong-aspect
        // OES texture and produces the PORTRAIT-pinched / LANDSCAPE-squished
        // deformation seen in the 2026-05-19 on-device smoke. PR #25 set a
        // 4:3 ResolutionSelector hint with auto-fallback; this line confirms
        // whether the fallback engaged silently.
        val rw = request.resolution.width
        val rh = request.resolution.height
        val matches4to3 = rw > 0 && rh > 0 && rw * 3 == rh * 4
        RovaLog.d {
            "DualSurfaceProcessor.onInputSurface: deliveredSourceSize=${rw}x${rh} " +
                "aspect=${if (rh > 0) rw.toFloat() / rh.toFloat() else 0f} " +
                "matches4:3=$matches4to3"
        }
        val cameraSurface = router.inputSurface
        request.provideSurface(cameraSurface, /* executor */ Runnable::run, Consumer { result ->
            RovaLog.d { "DualSurfaceProcessor input surface released (result=${result.resultCode})" }
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
            RovaLog.d { "DualSurfaceProcessor.onOutputSurface event (code=${event.eventCode})" }
        })
        val size = output.size
        router.addTarget(side = null, kind = TargetKind.PREVIEW, surface = previewSurface, width = size.width, height = size.height)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        try { router.release() } catch (e: Throwable) { RovaLog.w("DualSurfaceProcessor router release", e) }
    }
}
