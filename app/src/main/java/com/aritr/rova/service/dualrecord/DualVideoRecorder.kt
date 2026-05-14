package com.aritr.rova.service.dualrecord

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
    private val processor by lazy { DualSurfaceProcessor(config.lensFacing) }
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

        val portraitEnc = EncoderSurface(
            side = VideoSide.PORTRAIT,
            outputSize = config.portraitOutputSize,
            bitrateBps = portraitBps,
            fps = config.fps,
            onFormatReady = { fmt ->
                fmt.setInteger(android.media.MediaFormat.KEY_ROTATION, portraitRot)
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
                fmt.setInteger(android.media.MediaFormat.KEY_ROTATION, landscapeRot)
                muxer.addVideoTrack(VideoSide.LANDSCAPE, fmt); muxer.maybeStart()
            },
            onSample = { buf, info -> muxer.writeVideo(VideoSide.LANDSCAPE, buf, info) },
            onSideFailure = { e -> RovaLog.w("EncoderSurface LANDSCAPE failure", e) },
        )

        processor.attachEncoderInput(VideoSide.PORTRAIT, portraitEnc.inputSurface)
        processor.attachEncoderInput(VideoSide.LANDSCAPE, landscapeEnc.inputSurface)

        val audio = AudioFanOut(
            sampleRate = config.audioSampleRate,
            bitrateBps = config.audioBitrate,
            onFormatReady = { fmt -> muxer.addAudioTrack(fmt); muxer.maybeStart() },
            onSample = { sides, buf, info -> muxer.writeAudio(sides, buf, info) },
            onFailure = { e -> RovaLog.w("AudioFanOut failure", e) },
        )

        portraitEnc.start(); landscapeEnc.start(); audio.start()
        executor.execute { callback(DualRecordEvent.Start) }

        return DualRecording(portraitEnc, landscapeEnc, audio, muxer, callback, executor).also {
            activeRecording = it
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        try { activeRecording?.stop() } catch (e: Throwable) { RovaLog.w("DualVideoRecorder.release stop", e) }
        try { processor.release() } catch (e: Throwable) { RovaLog.w("DualVideoRecorder.release processor", e) }
        activeRecording = null
    }
}
