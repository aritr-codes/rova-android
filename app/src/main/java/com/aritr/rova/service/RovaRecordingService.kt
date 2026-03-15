package com.aritr.rova.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.aritr.rova.MainActivity
import com.aritr.rova.R
import android.os.Binder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.utils.RovaLog
import com.aritr.rova.utils.VideoMerger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ContentValues
import android.graphics.SurfaceTexture
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.camera.video.VideoRecordEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

data class RovaServiceState(
    val isRecording: Boolean = false,
    val nextRecordingCountdown: Long = 0,
    val segmentCount: Int = 0,
    val isPeriodicActive: Boolean = false,
    val totalLoops: Int = 0, // -1 for continuous
    val currentLoop: Int = 0,
    val isMerging: Boolean = false,
    val mergeProgress: Float = 0f,
    val mergeError: String? = null,
    val recordingError: String? = null,
    val isCameraActive: Boolean = false
)

class RovaRecordingService : Service(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private var cameraProvider: ProcessCameraProvider? = null
    private var recordingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentRecording: Recording? = null
    private var segmentCount = 0
    private val setupMutex = kotlinx.coroutines.sync.Mutex()

    // C1: State is now an instance field, not a companion object global
    private val _serviceState = MutableStateFlow(RovaServiceState())

    // C2: Signals when the UI has provided a SurfaceProvider so recording can begin
    private var surfaceProviderReady = CompletableDeferred<Unit>()

    // R2: Signals when the current VideoRecordEvent.Finalize callback has fired
    private var recordingFinalized = CompletableDeferred<Unit>().also { it.complete(Unit) }

    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: androidx.camera.core.Camera? = null
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    // Dummy surface for headless recording (Samsung devices need an active Preview surface)
    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    // Camera config
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var flashMode = 0 // 0: OFF, 1: ON, 2: AUTO

    // Recording config
    private var nSeconds = 10L
    private var mMinutes = 10L
    private var limitLoops = -1 // -1 for continuous
    private var resolutionStr = "FHD"
    private var configuredResolution: String? = null // Track what resolution the camera is currently configured for

    inner class LocalBinder : Binder() {
        fun getService(): RovaRecordingService = this@RovaRecordingService
        // C1: State exposed through the binder, not via static companion accessor
        fun getStateFlow(): StateFlow<RovaServiceState> = _serviceState.asStateFlow()
    }

    private val binder = LocalBinder()

    fun clearRecordingError() {
        _serviceState.update { it.copy(recordingError = null) }
    }

    fun setSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        RovaLog.d("setSurfaceProvider: received: $surfaceProvider")
        currentSurfaceProvider = surfaceProvider

        if (surfaceProvider != null) {
            // C2: Signal the recording loop that the surface is ready
            if (!surfaceProviderReady.isCompleted) {
                surfaceProviderReady.complete(Unit)
            }
            // If camera is already set up, just attach the surface provider to the existing preview.
            // Otherwise, launch full camera setup (which will pick up currentSurfaceProvider).
            val existingPreview = preview
            if (existingPreview != null) {
                // Real UI surface replaces any dummy surface
                releaseDummySurface()
                existingPreview.setSurfaceProvider(surfaceProvider)
            } else {
                serviceScope.launch { setupCamera() }
            }
        }
    }

    fun stopCameraPreview() {
        if (_serviceState.value.isPeriodicActive) return // Don't stop if recording
        RovaLog.d("stopCameraPreview: Unbinding camera for background")
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        releaseDummySurface()
        preview = null
        videoCapture = null
        camera = null
        configuredResolution = null
        _serviceState.update { it.copy(isCameraActive = false) }
    }

    fun startCameraPreview() {
        if (lifecycleRegistry.currentState < Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!_serviceState.value.isCameraActive) {
            serviceScope.launch { setupCamera() }
        } else {
            RovaLog.d("startCameraPreview: Camera already active, skipping setup")
        }
    }

    companion object {
        const val CHANNEL_ID = "RovaRecordingChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "STOP_RECORDING"

        fun start(context: Context, nSeconds: Float, mMinutes: Float, limitLoops: Int = -1, resolution: String = "FHD") {
            val intent = Intent(context, RovaRecordingService::class.java).apply {
                putExtra("N_SECONDS", nSeconds)
                putExtra("M_MINUTES", mMinutes)
                putExtra("LIMIT_LOOPS", limitLoops)
                putExtra("RESOLUTION", resolution)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RovaRecordingService::class.java).apply {
                action = ACTION_STOP
            }
            ContextCompat.startForegroundService(context, intent)
        }

        // Flash mode constants
        const val FLASH_MODE_OFF = 0
        const val FLASH_MODE_ON = 1
        const val FLASH_MODE_AUTO = 2
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createNotificationChannel()
        // C5: Remove any segment files left over from a prior crashed session
        cleanupOrphanedSegments()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPeriodicRecordingAndMerge()
            return START_NOT_STICKY
        }

        if (intent != null) {
            nSeconds = intent.getFloatExtra("N_SECONDS", 10f).toLong()
            mMinutes = intent.getFloatExtra("M_MINUTES", 10f).toLong()
            limitLoops = intent.getIntExtra("LIMIT_LOOPS", -1)
            resolutionStr = intent.getStringExtra("RESOLUTION") ?: "FHD"
        } else {
            // C4: OS restarted service (START_STICKY) — restore from user settings
            RovaLog.w("onStartCommand: null intent (OS restart) — restoring params from RovaSettings")
            val settings = RovaSettings(this)
            nSeconds = settings.durationSeconds.toLong()
            mMinutes = settings.intervalMinutes.toLong()
            limitLoops = settings.loopCount
            resolutionStr = settings.resolution
        }
        segmentCount = 0
        _serviceState.update { it.copy(totalLoops = limitLoops, currentLoop = 0) }

        // R4: Abort early if there is not enough free storage
        val estimatedBytes = estimateSessionBytes()
        if (!hasEnoughStorage(estimatedBytes)) {
            RovaLog.e("onStartCommand: Insufficient storage — aborting session")
            val notification = createNotification("Not enough storage to record. Free up space and try again.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification("Initializing background recording...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        // Reset surface provider gate for this new session
        surfaceProviderReady = CompletableDeferred()
        if (currentSurfaceProvider != null) surfaceProviderReady.complete(Unit)
        startPeriodicRecording()

        return START_STICKY
    }

    private fun startPeriodicRecording() {
        recordingJob?.cancel()
        recordingJob = serviceScope.launch {
            try {
                _serviceState.update { it.copy(isPeriodicActive = true) }

                // C2: Wait up to 3s for the UI to provide a SurfaceProvider.
                // If it never arrives (background-only launch), proceed headlessly.
                val surfaceArrived = withTimeoutOrNull(3000) { surfaceProviderReady.await() }
                if (surfaceArrived == null) {
                    RovaLog.w("startPeriodicRecording: SurfaceProvider timeout — proceeding headlessly")
                }

                // Re-setup camera only if resolution changed or camera isn't active
                if (configuredResolution != resolutionStr || !_serviceState.value.isCameraActive) {
                    RovaLog.d("startPeriodicRecording: Reconfiguring camera (configured=$configuredResolution, requested=$resolutionStr)")
                    forceReconfigureCamera()
                } else {
                    RovaLog.d("startPeriodicRecording: Camera already configured for $resolutionStr, reusing")
                }

                // Wait for camera to be fully active before recording
                val cameraReady = withTimeoutOrNull(5000) {
                    while (!_serviceState.value.isCameraActive) {
                        delay(100)
                    }
                }
                if (cameraReady == null) {
                    RovaLog.e("startPeriodicRecording: Camera failed to activate within 5s — aborting")
                    updateNotification("Camera failed to start. Please restart recording.")
                    _serviceState.update { it.copy(isPeriodicActive = false) }
                    @Suppress("DEPRECATION")
            stopForeground(true)
                    stopSelf()
                    return@launch
                }
                // Let CameraX pipeline fully stabilize (encoder init, frame production)
                // Samsung devices need extra time for MediaCodec initialization
                delay(2500)
                RovaLog.d("startPeriodicRecording: Camera ready, starting recording loop")

                while (isActive) {
                    if (limitLoops != -1 && segmentCount >= limitLoops) {
                        stopPeriodicRecordingAndMerge()
                        break
                    }

                    _serviceState.update { it.copy(currentLoop = segmentCount + 1) }
                    updateNotification("Recording segment ${segmentCount + 1}...")

                    // Retry logic for transient encoder failures (Samsung ERROR_NO_VALID_DATA)
                    var segmentSucceeded = false
                    for (attempt in 1..3) {
                        _serviceState.update { it.copy(isRecording = true, recordingError = null) }
                        beep() // Q3: beep on recording start
                        recordSegment()
                        beep() // Q3: beep on recording stop
                        _serviceState.update { it.copy(isRecording = false) }

                        if (_serviceState.value.recordingError == null) {
                            segmentSucceeded = true
                            break
                        }
                        if (attempt < 3) {
                            RovaLog.w("Segment failed (attempt $attempt), retrying after camera reconfigure...")
                            forceReconfigureCamera()
                            delay(2500)
                            withTimeoutOrNull(5000) {
                                while (!_serviceState.value.isCameraActive) { delay(100) }
                            }
                        }
                    }
                    if (segmentSucceeded) segmentCount++

                    if (limitLoops != -1 && segmentCount >= limitLoops) {
                        stopPeriodicRecordingAndMerge()
                        break
                    }

                    val intervalSeconds = (mMinutes * 60).toInt()
                    val waitSeconds = (intervalSeconds - nSeconds).coerceAtLeast(0)

                    if (waitSeconds > 0) {
                        for (i in waitSeconds.toInt() downTo 1) {
                            if (!isActive) break
                            updateNotification("Next recording in ${i}s | Segments: $segmentCount")
                            _serviceState.update { it.copy(nextRecordingCountdown = i.toLong()) }
                            delay(1000)
                        }
                    }
                }
            } catch (e: CancellationException) {
                RovaLog.d("startPeriodicRecording: Cancelled")
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                updateNotification("Recording stopped: ${e.message?.take(60) ?: "unknown error"}")
            } finally {
                _serviceState.update { it.copy(isPeriodicActive = false, isRecording = false) }
            }
        }
    }

    /**
     * Force unbind and re-setup camera. Used when recording starts to ensure
     * VideoCapture is configured with the correct resolution.
     */
    private suspend fun forceReconfigureCamera() {
        setupMutex.withLock {
            RovaLog.d("forceReconfigureCamera: Unbinding for fresh setup")
            try { cameraProvider?.unbindAll() } catch (e: Exception) {}
            preview = null
            videoCapture = null
            camera = null
            configuredResolution = null
            _serviceState.update { it.copy(isCameraActive = false) }
        }
        setupCamera()
    }

    private suspend fun setupCamera() {
        setupMutex.withLock {
            RovaLog.d("setupCamera: Starting setup workflow")

            RovaLog.d("setupCamera: currentSurfaceProvider=${if (currentSurfaceProvider != null) "UI" else "null (will use dummy)"}")

            if (cameraProvider != null) {
                if (_serviceState.value.isCameraActive) {
                    RovaLog.d("setupCamera: Camera already active. Skipping setup.")
                    return@withLock
                }
                if (_serviceState.value.isRecording) {
                    RovaLog.w("setupCamera: Attempted to setup while recording! Aborting.")
                    return@withLock
                }
                RovaLog.d("setupCamera: Unbinding existing provider for clean setup")
                try { cameraProvider?.unbindAll() } catch (e: Exception) {}
                _serviceState.update { it.copy(isCameraActive = false) }
            } else {
                val provider = withContext(Dispatchers.IO) {
                    ProcessCameraProvider.getInstance(this@RovaRecordingService).get()
                }
                cameraProvider = provider
            }

            val provider = cameraProvider ?: return@withLock

            RovaLog.d("setupCamera: Initializing UseCases (Preview + VideoCapture)")

            val quality = when (resolutionStr) {
                "4K" -> Quality.UHD
                "FHD" -> Quality.FHD
                "HD" -> Quality.HD
                "SD" -> Quality.SD
                else -> Quality.FHD
            }

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(quality, Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            preview = Preview.Builder().build()
            // Samsung devices require Preview to have an active surface for VideoCapture
            // to produce frames. Use a dummy surface as fallback when UI hasn't connected yet.
            val surfaceProvider = currentSurfaceProvider ?: createDummySurfaceProvider()
            preview?.setSurfaceProvider(surfaceProvider)
            RovaLog.d("setupCamera: SurfaceProvider=${if (currentSurfaceProvider != null) "UI" else "DUMMY"}")

            try {
                provider.unbindAll()
                RovaLog.d("setupCamera: Binding to lifecycle")
                camera = provider.bindToLifecycle(
                    this,
                    currentCameraSelector,
                    preview,
                    videoCapture
                )
                configuredResolution = resolutionStr
                _serviceState.update { it.copy(isCameraActive = true) }
                RovaLog.d("setupCamera: Camera binding COMPLETED. Active: true, resolution: $resolutionStr")
                applyFlashState()
            } catch (e: Exception) {
                e.printStackTrace()
                RovaLog.e("setupCamera: Binding failed", e)
                _serviceState.update { it.copy(isCameraActive = false) }
            }
        }
    }

    // R1: Guard against flipping camera while a segment is actively recording
    fun flipCamera() {
        if (_serviceState.value.isRecording) {
            RovaLog.d("flipCamera: Ignored — recording in progress")
            return
        }
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        serviceScope.launch { setupCamera() }
    }

    fun setFlashMode(mode: Int) {
        flashMode = mode
        applyFlashState()
    }

    private fun applyFlashState() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            cam.cameraControl.enableTorch(flashMode == FLASH_MODE_ON)
        }
    }

    fun getSupportedResolutions(): List<String> = listOf("4K", "FHD", "HD", "SD")

    /**
     * Creates a dummy SurfaceProvider so the camera pipeline produces frames even
     * when no UI preview is available. Required on Samsung devices where CameraX
     * gates VideoCapture frame production on Preview having an active surface.
     */
    private fun createDummySurfaceProvider(): Preview.SurfaceProvider {
        RovaLog.d("createDummySurfaceProvider: Creating headless surface")
        return Preview.SurfaceProvider { request ->
            releaseDummySurface()
            val texture = SurfaceTexture(0)
            texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            val surface = Surface(texture)
            dummySurfaceTexture = texture
            dummySurface = surface
            request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {
                // Surface released by CameraX — clean up
                surface.release()
                texture.release()
                if (dummySurface === surface) {
                    dummySurface = null
                    dummySurfaceTexture = null
                }
            }
        }
    }

    private fun releaseDummySurface() {
        dummySurface?.release()
        dummySurfaceTexture?.release()
        dummySurface = null
        dummySurfaceTexture = null
    }

    private suspend fun recordSegment() {
        try {
            val videoCap = videoCapture ?: run {
                RovaLog.e("recordSegment: VideoCapture is null!")
                return
            }

            val videoDir = File(getExternalFilesDir("videos"), "")
            if (!videoDir.exists()) videoDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val videoFile = File(videoDir, "segment_bg_$timestamp.mp4")
            RovaLog.d("recordSegment: Preparing file: ${videoFile.absolutePath}")

            val outputOptions = FileOutputOptions.Builder(videoFile).build()

            var pendingRecording = videoCap.output.prepareRecording(this, outputOptions)

            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingRecording = pendingRecording.withAudioEnabled()
            } else {
                RovaLog.w("recordSegment: Audio permission missing, recording video only")
            }

            // R2: Fresh deferred for this segment's finalize event
            recordingFinalized = CompletableDeferred()

            currentRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        RovaLog.d("Recording STARTED")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!event.hasError()) {
                            RovaLog.d("Recording FINALIZED. Size: ${videoFile.length()} bytes")
                            updateNotification("Segment Saved: ${(videoFile.length() / 1024)} KB")
                        } else {
                            currentRecording?.close()
                            currentRecording = null
                            RovaLog.e("Recording ERROR: ${event.error}")
                            val errorMsg = describeRecordingError(event.error)
                            updateNotification(errorMsg)
                            _serviceState.update { it.copy(recordingError = errorMsg) }
                            if (videoFile.exists()) videoFile.delete()
                        }
                        // R2: Signal that the file is fully written
                        recordingFinalized.complete(Unit)
                    }
                    is VideoRecordEvent.Status -> { /* no-op */ }
                }
            }

            RovaLog.d("recordSegment: Recording initialized, waiting ${nSeconds}s")
            delay(nSeconds * 1000)

            RovaLog.d("recordSegment: Stopping recording normally")
            currentRecording?.stop()
            currentRecording = null

            // R2: Wait for Finalize callback before returning
            withTimeoutOrNull(3000) { recordingFinalized.await() }

        } catch (e: CancellationException) {
            RovaLog.d("recordSegment: Cancelled")
            try { currentRecording?.stop() } catch (e2: Exception) {}
            currentRecording = null
            recordingFinalized.complete(Unit)
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            RovaLog.e("recordSegment: Exception: ${e.message}", e)
            recordingFinalized.complete(Unit)
        }
    }

    private fun describeRecordingError(errorCode: Int): String = when (errorCode) {
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "Not enough storage space"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "Camera was disconnected"
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "File size limit reached"
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "No video data was captured"
        VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> "Recording was interrupted"
        else -> "Recording failed (code $errorCode)"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rova Background Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, RovaRecordingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎥 Rova Recording Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(contentText))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Trigger a clean stop: finalize any in-progress segment, merge, then shut down.
        // The foreground service survives task removal (stopWithTask removed from manifest)
        // until performMerge() calls stopSelf() after the merge completes.
        stopPeriodicRecordingAndMerge()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseResources()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    private fun releaseResources() {
        recordingJob?.cancel()
        try { currentRecording?.stop() } catch (e: Exception) {}
        currentRecording = null
        try { cameraProvider?.unbindAll() } catch (e: Exception) {}
        releaseDummySurface()
        serviceScope.cancel()
        _serviceState.update { RovaServiceState() }
        RovaLog.d("releaseResources: Resources released")
    }

    private fun stopPeriodicRecordingAndMerge() {
        recordingJob?.cancel()
        _serviceState.update { it.copy(isPeriodicActive = false, isRecording = false) }

        try { currentRecording?.stop() } catch (e: Exception) { e.printStackTrace() }
        currentRecording = null

        serviceScope.launch {
            // R2: Wait for the Finalize callback before scanning segments
            // H-1: Capture reference locally to avoid race with recordSegment replacing the field
            val finalized = recordingFinalized
            withTimeoutOrNull(3000) { finalized.await() }

            val videoDir = File(getExternalFilesDir("videos"), "")
            val segments = videoDir.listFiles { _, name ->
                name.startsWith("segment_bg_") && name.endsWith(".mp4")
            }?.sortedBy { it.name } ?: emptyList()

            if (segments.isNotEmpty()) {
                performMerge(segments)
            } else {
                @Suppress("DEPRECATION")
            stopForeground(true)
                stopSelf()
            }
        }
    }

    private suspend fun performMerge(segments: List<File>) {
        try {
            _serviceState.update { it.copy(isMerging = true, mergeProgress = 0f, mergeError = null) }
            updateNotification("Merging ${segments.size} segments...")

            val videoDir = File(getExternalFilesDir("videos"), "")
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFile = File(videoDir, "Rova_${timestamp}.mp4")

            VideoMerger.mergeSegments(segments, outputFile) { progress ->
                _serviceState.update { it.copy(mergeProgress = progress) }
                updateNotification("Merging segments: ${(progress * 100).toInt()}%")
            }

            updateNotification("Video saved: ${outputFile.name}")
            // Copy to public Movies directory so it appears in the system gallery
            copyToPublicMovies(outputFile)
            segments.forEach { it.delete() }
            delay(1000)

        } catch (e: Exception) {
            e.printStackTrace()
            _serviceState.update { it.copy(mergeError = e.message) }
            updateNotification("Merge failed: ${e.message}")
            delay(3000)
        } finally {
            _serviceState.update { it.copy(isMerging = false) }
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
        }
    }

    private fun copyToPublicMovies(file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Rova")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val uri = contentResolver.insert(collection, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
                RovaLog.d("copyToPublicMovies: Saved to gallery: $uri")
            }
        } catch (e: Exception) {
            RovaLog.e("copyToPublicMovies: Failed to save to gallery", e)
        }
    }

    // Q3: Short beep on recording start/stop using rova_beep.mp3, respects enableBeeps setting
    private fun beep() {
        if (!RovaSettings(this).enableBeeps) return
        try {
            val mp = MediaPlayer.create(this, R.raw.rova_beep) ?: return
            mp.setOnCompletionListener { it.release() }
            mp.start()
        } catch (e: Exception) {
            RovaLog.w("beep: Failed to play sound", e)
        }
    }

    // C5: Delete segment files left over from a previous crashed session
    // H-3: Only delete segments older than 24 hours to avoid destroying in-progress merge data
    private fun cleanupOrphanedSegments() {
        serviceScope.launch(Dispatchers.IO) {
            val videoDir = File(getExternalFilesDir("videos"), "")
            val ageThresholdMs = 24 * 60 * 60 * 1000L // 24 hours
            val cutoff = System.currentTimeMillis() - ageThresholdMs
            val orphans = videoDir.listFiles { _, name ->
                name.startsWith("segment_bg_") && name.endsWith(".mp4")
            }?.filter { it.lastModified() < cutoff } ?: return@launch
            if (orphans.isNotEmpty()) {
                RovaLog.w("cleanupOrphanedSegments: Deleting ${orphans.size} orphaned segment(s) older than 24h")
                orphans.forEach { it.delete() }
            }
        }
    }

    private fun estimateSessionBytes(): Long {
        val loops = if (limitLoops == -1) 10L else limitLoops.toLong()
        val bytesPerSecond = when (resolutionStr) {
            "4K" -> 3_000_000L
            "FHD" -> 1_500_000L
            "HD" -> 750_000L
            else -> 500_000L
        }
        return nSeconds * loops * bytesPerSecond
    }

    private fun hasEnoughStorage(estimatedBytes: Long): Boolean {
        return try {
            val path = getExternalFilesDir(null)?.path ?: return true
            val stat = StatFs(path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            val required = estimatedBytes + 50 * 1024 * 1024L
            if (available < required) {
                RovaLog.w("hasEnoughStorage: Available=${available / 1024 / 1024}MB, Required=${required / 1024 / 1024}MB")
            }
            available >= required
        } catch (e: Exception) {
            RovaLog.w("hasEnoughStorage: Check failed, proceeding optimistically", e)
            true
        }
    }
}
