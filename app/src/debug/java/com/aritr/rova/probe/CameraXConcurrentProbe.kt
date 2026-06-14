package com.aritr.rova.probe

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.CompositionSettings
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

/**
 * THROWAWAY. Attempt A: the exact intended DualSight pipeline — two concurrent SingleCameraConfigs
 * (rear primary full-frame + front secondary inset) sharing ONE UseCaseGroup (Preview + one
 * VideoCapture) with CompositionSettings → one composited MP4 from one encoder. On success it then
 * runs a rebind-torture loop (Task 6): the segment-boundary rebind that Rova performs every clip,
 * under accumulated heat — the per-segment concurrent-rebind failure surface codex flagged HIGH.
 *
 * API reconciled against CameraX 1.5.3 + google/jetpack-camera-app ConcurrentCameraSession.kt:
 *   - SingleCameraConfig is androidx.camera.core.ConcurrentCamera.SingleCameraConfig
 *   - ctor order (CameraSelector, UseCaseGroup, CompositionSettings, LifecycleOwner)
 *   - both configs SHARE one UseCaseGroup holding one VideoCapture (single composited encoder)
 *   - front mirror is bind-time: VideoCapture.Builder.setMirrorMode(MIRROR_MODE_ON_FRONT_ONLY)
 *   - CompositionSettings offsets are NDC (center-origin, Y-up, offset-after-scale)
 *
 * False-verdict hardening (review round 1):
 *   - caller MUST invoke this only when the LifecycleOwner is RESUMED (bind in CREATED defers
 *     startup → premature stop → false negative).
 *   - CameraX closes CameraDevices asynchronously after unbindAll(); we wait CLOSE_DELAY_MS before
 *     the next bind / before handing off to attempt B, else openCamera hits ERROR_MAX_CAMERAS_IN_USE
 *     (a probe artifact, not device truth).
 *   - a watchdog guarantees a VERDICT line even if Finalize never fires (no silent hang).
 */
class CameraXConcurrentProbe(private val context: Context, private val owner: LifecycleOwner) {
    private val tag = "DualSightProbe"
    private val main = Handler(Looper.getMainLooper())

    private companion object {
        const val CLOSE_DELAY_MS = 1500L     // let CameraX finish releasing CameraDevices
        const val ATTEMPT_A_WATCHDOG_MS = 20_000L
    }

    @SuppressLint("MissingPermission", "RestrictedApi")
    fun run(preview: Preview, onDone: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            var settled = false
            var watchdog: Runnable? = null
            // Drains the camera, then continues after the async close completes.
            fun release(then: () -> Unit) {
                try { provider.unbindAll() } catch (_: Throwable) {}
                main.postDelayed(then, CLOSE_DELAY_MS)
            }
            try {
                val videoCapture = bindConcurrent(provider, preview)
                Log.i(tag, "ATTEMPT-A bind OK")

                watchdog = Runnable {
                    if (settled) return@Runnable
                    settled = true
                    Log.e(tag, "ATTEMPT-A no Finalize in ${ATTEMPT_A_WATCHDOG_MS}ms — probe watchdog")
                    Log.i(tag, "VERDICT(attemptA) recordSucceeded=false (watchdog timeout)")
                    release(onDone)
                }
                main.postDelayed(watchdog, ATTEMPT_A_WATCHDOG_MS)

                var recording: Recording? = null
                recording = videoCapture.output
                    .prepareRecording(context, mediaStoreOutput())
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> Log.i(tag, "ATTEMPT-A recording started")
                            is VideoRecordEvent.Finalize -> {
                                if (settled) return@start
                                settled = true
                                watchdog?.let { main.removeCallbacks(it) }
                                if (event.hasError()) {
                                    Log.e(tag, "ATTEMPT-A FINALIZE error=${event.error} cause=${event.cause}")
                                    Log.i(tag, "VERDICT(attemptA) recordSucceeded=false")
                                    release(onDone)
                                } else {
                                    Log.i(tag, "ATTEMPT-A file=${event.outputResults.outputUri}")
                                    Log.i(tag, "VERDICT(attemptA) recordSucceeded=true (visually verify PiP + front mirror)")
                                    Log.i(tag, "MIRROR check: open this attempt-A clip; front inset text must read correctly")
                                    release { runTorture(provider, preview, iterations = 20, onDone = onDone) }
                                }
                            }
                            else -> {}
                        }
                    }

                main.postDelayed({ recording?.stop() }, 5_000) // stop after ~5s
            } catch (t: Throwable) {
                if (settled) return@addListener
                settled = true
                watchdog?.let { main.removeCallbacks(it) }
                Log.e(tag, "ATTEMPT-A bind/record threw", t)
                Log.i(tag, "VERDICT(attemptA) recordSucceeded=false (exception)")
                release(onDone)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Task 6: only runs if attempt A succeeded. Repeats bind→record-2s→unbindAll→(wait close)→rebind
     * `iterations` times, mimicking Rova's per-clip segment-boundary rebind under accumulated heat.
     * Logs each iteration and a single VERDICT(torture) failedIter=<first failing iter, or -1>.
     */
    @SuppressLint("MissingPermission", "RestrictedApi")
    private fun runTorture(
        provider: ProcessCameraProvider,
        preview: Preview,
        iterations: Int,
        onDone: () -> Unit,
        iter: Int = 1,
        firstFailed: Int = -1,
    ) {
        if (iter > iterations) {
            Log.i(tag, "VERDICT(torture) failedIter=$firstFailed")
            onDone()
            return
        }
        fun next(ff: Int) {
            try { provider.unbindAll() } catch (_: Throwable) {}
            main.postDelayed({ runTorture(provider, preview, iterations, onDone, iter + 1, ff) }, CLOSE_DELAY_MS)
        }
        try {
            val videoCapture = bindConcurrent(provider, preview)
            Log.i(tag, "TORTURE iter=$iter bind=ok")
            var rec: Recording? = null
            rec = videoCapture.output
                .prepareRecording(context, tortureOutput(iter))
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) next(firstFailed)
                }
            main.postDelayed({ rec?.stop() }, 2_000)
        } catch (t: Throwable) {
            Log.i(tag, "TORTURE iter=$iter bind=FAIL ${t.javaClass.simpleName}: ${t.message}")
            next(if (firstFailed < 0) iter else firstFailed)
        }
    }

    /** Builds the shared group + rear/front configs and binds concurrently; returns the VideoCapture. */
    @SuppressLint("RestrictedApi")
    private fun bindConcurrent(provider: ProcessCameraProvider, preview: Preview): VideoCapture<Recorder> {
        val recorder = Recorder.Builder().build()
        // Front mirror requested at bind time; may be ignored under concurrent composition (codex-flagged) — verify visually.
        val videoCapture = VideoCapture.Builder(recorder)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .build()
        // ONE shared group (matches the JCA reference) → one composited encoder.
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(videoCapture)
            .build()
        val rear = SingleCameraConfig(
            CameraSelector.DEFAULT_BACK_CAMERA,
            group,
            CompositionSettings.Builder().setAlpha(1f).setOffset(0f, 0f).setScale(1f, 1f).build(),
            owner,
        )
        val front = SingleCameraConfig(
            CameraSelector.DEFAULT_FRONT_CAMERA,
            group,
            CompositionSettings.Builder()
                .setAlpha(1f)
                .setOffset(2f / 3f - 0.1f, -2f / 3f + 0.1f)
                .setScale(1f / 3f, 1f / 3f)
                .build(),
            owner,
        )
        provider.bindToLifecycle(listOf(rear, front))
        return videoCapture
    }

    private fun mediaStoreOutput(): MediaStoreOutputOptions {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "dualsight_probe_${System.currentTimeMillis()}")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        return MediaStoreOutputOptions.Builder(
            context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(cv).build()
    }

    /** Torture clips go to cache (not the gallery) — they only stress the rebind path. */
    private fun tortureOutput(iter: Int): FileOutputOptions {
        val dir = context.externalCacheDir ?: context.cacheDir
        return FileOutputOptions.Builder(File(dir, "dualsight_torture_$iter.mp4")).build()
    }
}
