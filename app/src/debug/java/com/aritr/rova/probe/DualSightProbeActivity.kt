package com.aritr.rova.probe

import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.aritr.rova.service.dualsight.ConcurrentCameraCapability
import com.aritr.rova.service.dualsight.LensFacing

/**
 * THROWAWAY debug-only probe. Launch with:
 *   adb -s RZCYA1VBQ2H shell am start -n com.aritr.rova/com.aritr.rova.probe.DualSightProbeActivity
 * Read results: adb -s RZCYA1VBQ2H logcat -s DualSightProbe:I
 */
class DualSightProbeActivity : ComponentActivity() {
    private val tag = "DualSightProbe"
    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        setContentView(previewView)
        runQueries()
    }

    private fun runQueries() {
        val pm = packageManager
        val hasFlag = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)

        // Camera2 platform truth.
        val cm = getSystemService(CameraManager::class.java)
        val camera2Combos: Set<Set<String>> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cm.concurrentCameraIds else emptySet()
        } catch (t: Throwable) {
            Log.e(tag, "Camera2 getConcurrentCameraIds threw", t); emptySet()
        }
        Log.i(tag, "QUERY api=${Build.VERSION.SDK_INT} featureFlag=$hasFlag camera2Combos=${camera2Combos.size}")
        camera2Combos.forEach { combo -> Log.i(tag, "  camera2Combo=$combo") }

        // CameraX arbiter.
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val combos = provider.availableConcurrentCameraInfos.map { infoList ->
                infoList.map { info ->
                    when (info.lensFacing) {
                        CameraSelector.LENS_FACING_FRONT -> LensFacing.FRONT
                        CameraSelector.LENS_FACING_BACK -> LensFacing.BACK
                        else -> LensFacing.OTHER
                    }
                }
            }
            val supported = ConcurrentCameraCapability.supportsConcurrentFrontAndBack(hasFlag, combos)
            Log.i(tag, "QUERY cameraXCombos=${combos.size} combos=$combos")
            Log.i(tag, "VERDICT(query) frontBackBindable=$supported")
            Log.i(tag, "Next: attempt A (CameraX CompositionSettings record)")
            runAttemptA()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun runAttemptA() {
        val preview = Preview.Builder().build()
        preview.surfaceProvider = previewView.surfaceProvider
        CameraXConcurrentProbe(this, this).run(preview) {
            Log.i(tag, "attempt A done — starting attempt B (Camera2 hardware truth)")
            runAttemptB()
        }
    }

    private fun runAttemptB() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(tag, "VERDICT(attemptB) recordSucceeded=false (API<R, getConcurrentCameraIds unavailable)")
            return
        }
        Camera2ConcurrentProbe(this).run {
            Log.i(tag, "attempt B done — probe complete")
            Log.i(tag, "MIRROR check: open attempt-A clip; front inset text must read correctly")
        }
    }
}
