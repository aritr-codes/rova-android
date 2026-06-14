package com.aritr.rova.probe

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File

/**
 * THROWAWAY. Attempt B: version-independent hardware truth. If CameraX refuses but raw Camera2
 * records both lenses concurrently, the limit is CameraX conservatism, not silicon.
 *
 * Opens BOTH CameraDevices from a getConcurrentCameraIds front+back combo (open both before
 * configuring either — concurrent ordering), gives each its own MediaRecorder (720p) surface,
 * configures a single-target session per camera, starts both recorders, runs ~5 s, then stops.
 *
 * Logging contract (fixed): emits exactly one `VERDICT(attemptB) recordSucceeded=<bool>` line.
 * This is two SEPARATE files (one per lens) — NOT the composited PiP. It only proves the silicon
 * can stream both ISPs + encode concurrently; the PiP composition is attempt A's job.
 */
@RequiresApi(Build.VERSION_CODES.R)
@SuppressLint("MissingPermission")
class Camera2ConcurrentProbe(private val context: Context) {
    private val tag = "DualSightProbe"
    private val cm = context.getSystemService(CameraManager::class.java)

    private lateinit var onDone: () -> Unit
    private var finished = false
    private val thread = HandlerThread("camera2-probe").also { it.start() }
    private val handler = Handler(thread.looper)

    // Per-lens state (index 0 = front, 1 = back).
    private val devices = arrayOfNulls<CameraDevice>(2)
    private val sessions = arrayOfNulls<CameraCaptureSession>(2)
    private val recorders = arrayOfNulls<MediaRecorder>(2)
    private val files = arrayOfNulls<File>(2)
    private var openedCount = 0
    private var configuredCount = 0

    fun run(onDone: () -> Unit) {
        this.onDone = onDone
        val pair = try {
            cm.concurrentCameraIds.firstOrNull { combo ->
                combo.any { facing(it) == "FRONT" } && combo.any { facing(it) == "BACK" }
            }
        } catch (t: Throwable) {
            Log.e(tag, "ATTEMPT-B getConcurrentCameraIds threw", t); null
        }
        if (pair == null) {
            finish(false, "no front+back combo in getConcurrentCameraIds")
            return
        }
        val frontId = pair.first { facing(it) == "FRONT" }
        val backId = pair.first { facing(it) == "BACK" }
        Log.i(tag, "ATTEMPT-B trying front=$frontId back=$backId")

        try {
            val dir = context.externalCacheDir ?: context.cacheDir
            files[0] = File(dir, "dualsight_probe_front_${System.currentTimeMillis()}.mp4")
            files[1] = File(dir, "dualsight_probe_back_${System.currentTimeMillis()}.mp4")
            recorders[0] = newRecorder(files[0]!!)
            recorders[1] = newRecorder(files[1]!!)
            // Watchdog: openCamera may never call back on a stuck HAL — guarantee a verdict.
            handler.postDelayed({ finish(false, "watchdog: no verdict in 20s (openCamera/session stuck)") }, 20_000)
            // Open BOTH before configuring either (concurrent ordering).
            cm.openCamera(frontId, deviceCallback(0), handler)
            cm.openCamera(backId, deviceCallback(1), handler)
        } catch (e: CameraAccessException) {
            finish(false, "openCamera CameraAccessException reason=${e.reason}")
        } catch (t: Throwable) {
            finish(false, "setup threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun deviceCallback(idx: Int) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            devices[idx] = camera
            openedCount++
            Log.i(tag, "ATTEMPT-B opened idx=$idx openedCount=$openedCount")
            if (openedCount == 2) configureBoth()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.i(tag, "ATTEMPT-B onDisconnected idx=$idx")
            camera.close()
            finish(false, "camera idx=$idx disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(tag, "ATTEMPT-B onError idx=$idx error=$error")
            camera.close()
            // error 3 = ERROR_MAX_CAMERAS_IN_USE — the canonical concurrent-denial code.
            finish(false, "camera idx=$idx onError code=$error")
        }
    }

    @Suppress("DEPRECATION")
    private fun configureBoth() {
        for (idx in 0..1) {
            val device = devices[idx] ?: return finish(false, "device idx=$idx null at configure")
            val surface = recorders[idx]!!.surface
            val cb = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    sessions[idx] = session
                    try {
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        req.addTarget(surface)
                        session.setRepeatingRequest(req.build(), null, handler)
                        configuredCount++
                        Log.i(tag, "ATTEMPT-B configured idx=$idx configuredCount=$configuredCount")
                        if (configuredCount == 2) startBoth()
                    } catch (t: Throwable) {
                        finish(false, "repeating request idx=$idx threw: ${t.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    finish(false, "session configure FAILED idx=$idx")
                }
            }
            try {
                device.createCaptureSession(listOf(surface), cb, handler)
            } catch (e: CameraAccessException) {
                return finish(false, "createCaptureSession idx=$idx reason=${e.reason}")
            }
        }
    }

    private fun startBoth() {
        try {
            recorders[0]!!.start()
            recorders[1]!!.start()
            Log.i(tag, "ATTEMPT-B both recorders started — running 5s")
        } catch (t: Throwable) {
            return finish(false, "recorder.start threw: ${t.message}")
        }
        handler.postDelayed({
            val ok = stopRecorders()
            finish(ok, if (ok) "both recorded 5s, non-empty files" else "stop/empty-file failure")
        }, 5_000)
    }

    /** Stops both recorders; returns true only if both produced non-empty files. */
    private fun stopRecorders(): Boolean {
        var ok = true
        for (idx in 0..1) {
            try {
                sessions[idx]?.stopRepeating()
                recorders[idx]?.stop()
            } catch (t: Throwable) {
                Log.e(tag, "ATTEMPT-B stop idx=$idx threw: ${t.message}")
                ok = false
            }
            val len = files[idx]?.length() ?: 0L
            Log.i(tag, "ATTEMPT-B file idx=$idx bytes=$len path=${files[idx]?.absolutePath}")
            if (len <= 0L) ok = false
        }
        return ok
    }

    private fun finish(success: Boolean, reason: String) {
        if (finished) return
        finished = true
        Log.i(tag, "VERDICT(attemptB) recordSucceeded=$success ($reason)")
        cleanup()
        onDone()
    }

    private fun cleanup() {
        for (idx in 0..1) {
            try { sessions[idx]?.close() } catch (_: Throwable) {}
            try { devices[idx]?.close() } catch (_: Throwable) {}
            try { recorders[idx]?.reset(); recorders[idx]?.release() } catch (_: Throwable) {}
        }
        thread.quitSafely()
    }

    private fun facing(id: String): String {
        val c = cm.getCameraCharacteristics(id)
        return when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> "FRONT"
            CameraMetadata.LENS_FACING_BACK -> "BACK"
            else -> "OTHER"
        }
    }

    private fun newRecorder(file: File): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(1280, 720)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(6_000_000)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        return r
    }
}
