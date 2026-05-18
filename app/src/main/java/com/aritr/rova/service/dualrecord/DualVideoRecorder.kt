package com.aritr.rova.service.dualrecord

import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceProcessor
import androidx.core.util.Consumer
import com.aritr.rova.service.dualrecord.internal.AudioFanOut
import com.aritr.rova.service.dualrecord.internal.DualMuxer
import com.aritr.rova.service.dualrecord.internal.DualSurfaceProcessor
import com.aritr.rova.service.dualrecord.internal.EncoderSurface
import com.aritr.rova.service.dualrecord.internal.RotationCalculator
import com.aritr.rova.utils.RovaLog
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 6.1a — top-level public orchestrator for the dual-recording
 * pipeline. Construction is cheap (config only); resources are allocated
 * lazily by [asCameraEffect] (CameraEffect creation) and [start]
 * (encoders/muxer/audio).
 *
 * Lifecycle:
 *  1. `val recorder = DualVideoRecorder(config)`
 *  2. `recorder.asCameraEffect()` → `CameraEffect`. The 6.1b consumer
 *     attaches it to a `UseCaseGroup` + `bindToLifecycle`.
 *  3. `val recording = recorder.start(outputs, executor, callback)` →
 *     `DualRecording`. Encoders + audio + muxer become live; `Start` is
 *     delivered.
 *  4. `recording.stop()` → fires `Finalize`.
 *  5. `recorder.release()` → final teardown.
 *
 * `release()` is idempotent. After release, the recorder cannot be re-used.
 */
class DualVideoRecorder(private val config: DualVideoRecorderConfig) {

    private val released = AtomicBoolean(false)
    private val processor by lazy {
        DualSurfaceProcessor(
            lensFacing = config.lensFacing,
            displayRotation = config.displayRotation,
            sensorOrientation = config.sensorOrientation,
            useFirstPrinciplesRender = config.useFirstPrinciplesRender,
            enableMatrixSnapshots = config.enableMatrixSnapshots,
        )
    }
    @Volatile private var activeRecording: DualRecording? = null

    /**
     * D-deviation (Task 15): `CameraEffect`'s constructors are `protected`
     * (the public API is to subclass). The plan's verbatim
     * `CameraEffect(targets, executor, processor, errorListener)` call
     * therefore cannot compile; instead we expose an anonymous subclass
     * here that delegates to the protected 4-arg ctor verified against
     * camera-core 1.4.2 sources (`CameraEffect.java` line 328):
     *   protected CameraEffect(
     *       @Targets int targets,
     *       @NonNull Executor executor,
     *       @NonNull SurfaceProcessor surfaceProcessor,
     *       @NonNull Consumer<Throwable> errorListener)
     * `Consumer` is `androidx.core.util.Consumer` per the source's import.
     */
    private class PreviewEffect(
        executor: Executor,
        processor: SurfaceProcessor,
        errorListener: Consumer<Throwable>,
    ) : CameraEffect(PREVIEW, executor, processor, errorListener)

    fun asCameraEffect(): CameraEffect {
        check(!released.get()) { "DualVideoRecorder is released" }
        return PreviewEffect(
            executor = Executor { it.run() },
            processor = processor,
            errorListener = Consumer { e -> RovaLog.w("CameraEffect error", e) },
        )
    }

    fun start(
        outputs: DualOutput,
        executor: Executor,
        callback: (DualRecordEvent) -> Unit,
    ): DualRecording {
        check(!released.get()) { "DualVideoRecorder is released" }
        check(activeRecording == null) { "another DualRecording is active; stop() it first" }

        val muxer = DualMuxer(
            outputs.portraitFile, outputs.landscapeFile,
            onSideFailure = { side, e -> RovaLog.w("DualMuxer side $side failure", e) },
        )

        val portraitBps = config.portraitBitrate
        val landscapeBps = config.landscapeBitrate

        val portraitRot = RotationCalculator.tag(config.displayRotation, VideoSide.PORTRAIT)
        val landscapeRot = RotationCalculator.tag(config.displayRotation, VideoSide.LANDSCAPE)

        // Phase 6.1b smoke-fix #4 — set the per-side MP4 composition-matrix
        // rotation hint on each muxer BEFORE addTrack/start. This is the
        // sole rotation metadata; the encoder format no longer carries
        // KEY_ROTATION (MP4 ignores it on addTrack; some Qualcomm encoders
        // honor it at bitstream level → double-rotation now that the
        // EglRouter pre-rotates per-side pixels). See DualMuxer
        // setOrientationHint KDoc.
        muxer.setOrientationHint(VideoSide.PORTRAIT, portraitRot)
        muxer.setOrientationHint(VideoSide.LANDSCAPE, landscapeRot)

        val portraitEnc = EncoderSurface(
            side = VideoSide.PORTRAIT,
            outputSize = config.portraitOutputSize,
            bitrateBps = portraitBps,
            fps = config.fps,
            onFormatReady = { fmt ->
                // KEY_ROTATION intentionally NOT set: MP4 muxer reads from
                // setOrientationHint, and some hw encoders rotate pixels
                // when KEY_ROTATION is present (would conflict with the
                // EglRouter pre-rotation for landscape and is unnecessary
                // for portrait).
                muxer.addVideoTrack(VideoSide.PORTRAIT, fmt); muxer.maybeStart()
            },
            onSample = { buf, info -> muxer.writeVideo(VideoSide.PORTRAIT, buf, info) },
            onSideFailure = { e -> RovaLog.w("EncoderSurface PORTRAIT failure", e) },
        )
        val landscapeEnc = EncoderSurface(
            side = VideoSide.LANDSCAPE,
            outputSize = config.landscapeOutputSize,
            bitrateBps = landscapeBps,
            fps = config.fps,
            onFormatReady = { fmt ->
                // KEY_ROTATION intentionally NOT set — see PORTRAIT branch.
                muxer.addVideoTrack(VideoSide.LANDSCAPE, fmt); muxer.maybeStart()
            },
            onSample = { buf, info -> muxer.writeVideo(VideoSide.LANDSCAPE, buf, info) },
            onSideFailure = { e -> RovaLog.w("EncoderSurface LANDSCAPE failure", e) },
        )

        // Phase 6.1b smoke-fix: pass encoder output dims so EglRouter can
        // set glViewport per target (the GL pump can't query a Surface's
        // intrinsic size, so the dims must travel through the registration).
        processor.attachEncoderInput(
            VideoSide.PORTRAIT,
            portraitEnc.inputSurface,
            config.portraitOutputSize.width,
            config.portraitOutputSize.height,
        )
        processor.attachEncoderInput(
            VideoSide.LANDSCAPE,
            landscapeEnc.inputSurface,
            config.landscapeOutputSize.width,
            config.landscapeOutputSize.height,
        )

        val audio = AudioFanOut(
            sampleRate = config.audioSampleRate,
            bitrateBps = config.audioBitrate,
            onFormatReady = { fmt -> muxer.addAudioTrack(fmt); muxer.maybeStart() },
            onSample = { sides, buf, info -> muxer.writeAudio(sides, buf, info) },
            onFailure = { e -> RovaLog.w("AudioFanOut failure", e) },
        )

        portraitEnc.start(); landscapeEnc.start(); audio.start()
        executor.execute { callback(DualRecordEvent.Start) }

        return DualRecording(
            portraitEncoder = portraitEnc,
            landscapeEncoder = landscapeEnc,
            audio = audio,
            muxer = muxer,
            callback = callback,
            callbackExecutor = executor,
            // Phase 6.1b smoke-fix — allow recorder reuse across segments.
            onStopped = { activeRecording = null },
        ).also {
            activeRecording = it
        }
    }

    /**
     * Phase 6.1c — public seam for [com.aritr.rova.service.RovaRecordingService]
     * to attach a UI-side preview TextureView surface as a per-side
     * render target. Delegates to the lazy-initialised processor.
     */
    fun attachPreviewInput(side: VideoSide, surface: Surface, width: Int, height: Int) {
        check(!released.get()) { "DualVideoRecorder is released" }
        processor.attachPreviewInput(side, surface, width, height)
    }

    /**
     * Phase 6.1c — un-register a previously-attached preview target.
     * No-op after release.
     */
    fun detachPreviewInput(side: VideoSide) {
        if (released.get()) return
        processor.detachPreviewInput(side)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        try { activeRecording?.stop() } catch (e: Throwable) { RovaLog.w("DualVideoRecorder.release stop", e) }
        try { processor.release() } catch (e: Throwable) { RovaLog.w("DualVideoRecorder.release processor", e) }
        activeRecording = null
    }
}
