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
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * THROWAWAY. Attempt A: the exact intended DualSight pipeline — two concurrent SingleCameraConfigs
 * (rear primary full-frame + front secondary inset) sharing ONE UseCaseGroup (Preview + one
 * VideoCapture) with CompositionSettings → one composited MP4 from one encoder.
 *
 * API reconciled against CameraX 1.5.3 + google/jetpack-camera-app ConcurrentCameraSession.kt:
 *   - SingleCameraConfig is androidx.camera.core.ConcurrentCamera.SingleCameraConfig
 *   - ctor order (CameraSelector, UseCaseGroup, CompositionSettings, LifecycleOwner)
 *   - both configs SHARE one UseCaseGroup holding one VideoCapture (single composited encoder)
 *   - front mirror is bind-time: VideoCapture.Builder.setMirrorMode(MIRROR_MODE_ON_FRONT_ONLY)
 *   - CompositionSettings offsets are NDC (center-origin, Y-up, offset-after-scale)
 */
class CameraXConcurrentProbe(private val context: Context, private val owner: LifecycleOwner) {
    private val tag = "DualSightProbe"

    @SuppressLint("MissingPermission", "RestrictedApi")
    fun run(preview: Preview, onDone: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            try {
                val recorder = Recorder.Builder().build()
                // Front mirror requested at bind time; visually verify in the run protocol
                // (may be ignored under concurrent composition — codex-flagged risk).
                val videoCapture = VideoCapture.Builder(recorder)
                    .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                    .build()

                // ONE shared group (matches the JCA reference) → one composited encoder.
                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(videoCapture)
                    .build()

                // Rear primary: full frame (offset 0,0; scale 1,1).
                val rear = SingleCameraConfig(
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    group,
                    CompositionSettings.Builder().setAlpha(1f).setOffset(0f, 0f).setScale(1f, 1f).build(),
                    owner,
                )
                // Front secondary: bottom-right inset (NDC, center-origin, Y-up; JCA inset geometry).
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

                val concurrent = provider.bindToLifecycle(listOf(rear, front))
                Log.i(tag, "ATTEMPT-A bind OK cameras=${concurrent.cameras.size}")

                val name = "dualsight_probe_${System.currentTimeMillis()}"
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                }
                val out = MediaStoreOutputOptions.Builder(
                    context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                ).setContentValues(cv).build()

                var recording: Recording? = null
                recording = videoCapture.output
                    .prepareRecording(context, out)
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> Log.i(tag, "ATTEMPT-A recording started")
                            is VideoRecordEvent.Finalize -> {
                                if (event.hasError()) {
                                    Log.e(tag, "ATTEMPT-A FINALIZE error=${event.error} cause=${event.cause}")
                                    Log.i(tag, "VERDICT(attemptA) recordSucceeded=false")
                                } else {
                                    Log.i(tag, "ATTEMPT-A file=${event.outputResults.outputUri}")
                                    Log.i(tag, "VERDICT(attemptA) recordSucceeded=true (visually verify PiP + front mirror)")
                                }
                                provider.unbindAll()
                                onDone()
                            }
                            else -> {}
                        }
                    }

                // Stop after ~5s.
                Handler(Looper.getMainLooper()).postDelayed({ recording?.stop() }, 5_000)
            } catch (t: Throwable) {
                Log.e(tag, "ATTEMPT-A bind/record threw", t)
                Log.i(tag, "VERDICT(attemptA) recordSucceeded=false (exception)")
                onDone()
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
