package com.aritr.loom.service

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
import com.aritr.loom.MainActivity
import com.aritr.loom.R
import android.os.Binder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import com.aritr.loom.data.LoomSettings
import com.aritr.loom.utils.VideoMerger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.camera.video.VideoRecordEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

data class LoomServiceState(
    val isRecording: Boolean = false,
    val nextRecordingCountdown: Long = 0,
    val segmentCount: Int = 0,
    val isPeriodicActive: Boolean = false,
    val totalLoops: Int = 0, // -1 for continuous
    val currentLoop: Int = 0,
    val isMerging: Boolean = false,
    val mergeProgress: Float = 0f,
    val mergeError: String? = null,
    val isCameraActive: Boolean = false
)

class LoomRecordingService : Service(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private var cameraProvider: ProcessCameraProvider? = null
    private var recordingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentRecording: Recording? = null
    private var segmentCount = 0
    private val setupMutex = kotlinx.coroutines.sync.Mutex()

    // C1: State is now an instance field, not a companion object global
    private val _serviceState = MutableStateFlow(LoomServiceState())

    // C2: Signals when the UI has provided a SurfaceProvider so recording can begin
    private var surfaceProviderReady = CompletableDeferred<Unit>()

    // R2: Signals when the current VideoRecordEvent.Finalize callback has fired
    private var recordingFinalized = CompletableDeferred<Unit>().also { it.complete(Unit) }

    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: androidx.camera.core.Camera? = null
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    // Camera config
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var flashMode = 0 // 0: OFF, 1: ON, 2: AUTO

    // Recording config
    private var nSeconds = 10L
    private var mMinutes = 10L
    private var limitLoops = -1 // -1 for continuous
    private var resolutionStr = "FHD"

    inner class LocalBinder : Binder() {
        fun getService(): LoomRecordingService = this@LoomRecordingService
        // C1: State exposed through the binder, not via static companion accessor
        fun getStateFlow(): StateFlow<LoomServiceState> = _serviceState.asStateFlow()
    }

    private val binder = LocalBinder()

    fun setSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        android.util.Log.d("LoomService", "setSurfaceProvider: received: $surfaceProvider")
        currentSurfaceProvider = surfaceProvider

        if (surfaceProvider != null) {
            // C2: Signal the recording loop that the surface is ready
            if (!surfaceProviderReady.isCompleted) {
                surfaceProviderReady.complete(Unit)
            }
            serviceScope.launch { setupCamera() }
            preview?.setSurfaceProvider(surfaceProvider)
        }
    }

    fun startCameraPreview() {
        if (lifecycleRegistry.currentState < Lifecycle.State.STARTED) {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }
        if (!_serviceState.value.isCameraActive) {
            serviceScope.launch { setupCamera() }
        } else {
            android.util.Log.d("LoomService", "startCameraPreview: Camera already active, skipping setup")
        }
    }

    companion object {
        const val CHANNEL_ID = "LoomRecordingChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "STOP_RECORDING"

        fun start(context: Context, nSeconds: Float, mMinutes: Float, limitLoops: Int = -1, resolution: String = "FHD") {
            val intent = Intent(context, LoomRecordingService::class.java).apply {
                putExtra("N_SECONDS", nSeconds)
                putExtra("M_MINUTES", mMinutes)
                putExtra("LIMIT_LOOPS", limitLoops)
                putExtra("RESOLUTION", resolution)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LoomRecordingService::class.java)
            context.stopService(intent)
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
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
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
            android.util.Log.w("LoomService", "onStartCommand: null intent (OS restart) — restoring params from LoomSettings")
            val settings = LoomSettings(this)
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
            android.util.Log.e("LoomService", "onStartCommand: Insufficient storage — aborting session")
            val notification = createNotification("Not enough storage to record. Free up space and try again.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
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

        lifecycleRegistry.currentState = Lifecycle.State.STARTED
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
                    android.util.Log.w("LoomService", "startPeriodicRecording: SurfaceProvider timeout — proceeding headlessly")
                }

                setupCamera()

                while (isActive) {
                    if (limitLoops != -1 && segmentCount >= limitLoops) {
                        stopPeriodicRecordingAndMerge()
                        break
                    }

                    _serviceState.update { it.copy(currentLoop = segmentCount + 1) }
                    updateNotification("Recording segment ${segmentCount + 1}...")

                    _serviceState.update { it.copy(isRecording = true) }
                    beep() // Q3: beep on recording start
                    recordSegment()
                    beep() // Q3: beep on recording stop
                    _serviceState.update { it.copy(isRecording = false) }
                    segmentCount++

                    if (limitLoops != -1 && segmentCount >= limitLoops) {
                        stopSelf()
                        break
                    }

                    val intervalSeconds = (mMinutes * 60).toInt()
                    val waitSeconds = intervalSeconds - nSeconds

                    if (waitSeconds > 0) {
                        for (i in waitSeconds.toInt() downTo 1) {
                            if (!isActive) break
                            updateNotification("Next recording in ${i}s | Segments: $segmentCount")
                            _serviceState.update { it.copy(nextRecordingCountdown = i.toLong()) }
                            delay(1000)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateNotification("Error: ${e.message}")
            } finally {
                _serviceState.update { it.copy(isPeriodicActive = false, isRecording = false) }
            }
        }
    }

    private suspend fun setupCamera() {
        setupMutex.withLock {
            android.util.Log.d("LoomService", "setupCamera: Starting setup workflow")

            if (currentSurfaceProvider == null) {
                android.util.Log.w("LoomService", "setupCamera: No SurfaceProvider — binding without preview display")
            }

            if (cameraProvider != null) {
                if (_serviceState.value.isCameraActive) {
                    android.util.Log.d("LoomService", "setupCamera: Camera already active. Skipping setup.")
                    return@withLock
                }
                if (_serviceState.value.isRecording) {
                    android.util.Log.w("LoomService", "setupCamera: Attempted to setup while recording! Aborting.")
                    return@withLock
                }
                android.util.Log.d("LoomService", "setupCamera: Unbinding existing provider for clean setup")
                try { cameraProvider?.unbindAll() } catch (e: Exception) {}
                _serviceState.update { it.copy(isCameraActive = false) }
            } else {
                val provider = withContext(Dispatchers.IO) {
                    ProcessCameraProvider.getInstance(this@LoomRecordingService).get()
                }
                cameraProvider = provider
            }

            val provider = cameraProvider ?: return@withLock

            android.util.Log.d("LoomService", "setupCamera: Initializing UseCases (Preview + VideoCapture)")

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
            preview?.setSurfaceProvider(currentSurfaceProvider)

            try {
                provider.unbindAll()
                android.util.Log.d("LoomService", "setupCamera: Binding to lifecycle")
                camera = provider.bindToLifecycle(
                    this,
                    currentCameraSelector,
                    preview,
                    videoCapture
                )
                _serviceState.update { it.copy(isCameraActive = true) }
                android.util.Log.d("LoomService", "setupCamera: Camera binding COMPLETED. Active: true")
                applyFlashState()
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("LoomService", "setupCamera: Binding failed", e)
                _serviceState.update { it.copy(isCameraActive = false) }
            }
        }
    }

    // R1: Guard against flipping camera while a segment is actively recording
    fun flipCamera() {
        if (_serviceState.value.isRecording) {
            android.util.Log.d("LoomService", "flipCamera: Ignored — recording in progress")
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

    private suspend fun recordSegment() {
        try {
            val videoCap = videoCapture ?: run {
                android.util.Log.e("LoomService", "recordSegment: VideoCapture is null!")
                return
            }

            val videoDir = File(getExternalFilesDir("videos"), "")
            if (!videoDir.exists()) videoDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val videoFile = File(videoDir, "segment_bg_$timestamp.mp4")
            android.util.Log.d("LoomService", "recordSegment: Preparing file: ${videoFile.absolutePath}")

            val outputOptions = FileOutputOptions.Builder(videoFile).build()

            var pendingRecording = videoCap.output.prepareRecording(this, outputOptions)

            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingRecording = pendingRecording.withAudioEnabled()
            } else {
                android.util.Log.w("LoomService", "recordSegment: Audio permission missing, recording video only")
            }

            // R2: Fresh deferred for this segment's finalize event
            recordingFinalized = CompletableDeferred()

            currentRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        android.util.Log.d("LoomService", "Recording STARTED")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!event.hasError()) {
                            android.util.Log.d("LoomService", "Recording FINALIZED. Size: ${videoFile.length()} bytes")
                            updateNotification("Segment Saved: ${(videoFile.length() / 1024)} KB")
                        } else {
                            currentRecording?.close()
                            currentRecording = null
                            android.util.Log.e("LoomService", "Recording ERROR: ${event.error}")
                            updateNotification("Recording Error: ${event.error}")
                            if (videoFile.exists()) videoFile.delete()
                        }
                        // R2: Signal that the file is fully written
                        recordingFinalized.complete(Unit)
                    }
                    is VideoRecordEvent.Status -> { /* no-op */ }
                }
            }

            android.util.Log.d("LoomService", "recordSegment: Recording initialized, waiting ${nSeconds}s")
            delay(nSeconds * 1000)

            android.util.Log.d("LoomService", "recordSegment: Stopping recording normally")
            currentRecording?.stop()
            currentRecording = null

            // R2: Wait for Finalize callback before returning
            withTimeoutOrNull(3000) { recordingFinalized.await() }

        } catch (e: CancellationException) {
            android.util.Log.d("LoomService", "recordSegment: Cancelled")
            try { currentRecording?.stop() } catch (e2: Exception) {}
            currentRecording = null
            recordingFinalized.complete(Unit)
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("LoomService", "recordSegment: Exception: ${e.message}", e)
            recordingFinalized.complete(Unit)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Loom Background Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, LoomRecordingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎥 Loom Recording Active")
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
        releaseResources()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseResources()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun releaseResources() {
        recordingJob?.cancel()
        try { currentRecording?.stop() } catch (e: Exception) {}
        currentRecording = null
        try { cameraProvider?.unbindAll() } catch (e: Exception) {}
        serviceScope.cancel()
        _serviceState.update { LoomServiceState() }
        android.util.Log.d("LoomService", "releaseResources: Resources released")
    }

    private fun stopPeriodicRecordingAndMerge() {
        recordingJob?.cancel()
        _serviceState.update { it.copy(isPeriodicActive = false, isRecording = false) }

        try { currentRecording?.stop() } catch (e: Exception) { e.printStackTrace() }
        currentRecording = null

        serviceScope.launch {
            // R2: Wait for the Finalize callback before scanning segments
            withTimeoutOrNull(3000) { recordingFinalized.await() }

            val videoDir = File(getExternalFilesDir("videos"), "")
            val segments = videoDir.listFiles { _, name ->
                name.startsWith("segment_bg_") && name.endsWith(".mp4")
            }?.sortedBy { it.name } ?: emptyList()

            if (segments.isNotEmpty()) {
                performMerge(segments)
            } else {
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
            val outputFile = File(videoDir, "Loom_${timestamp}.mp4")

            VideoMerger.mergeSegments(segments, outputFile) { progress ->
                _serviceState.update { it.copy(mergeProgress = progress) }
                updateNotification("Merging segments: ${(progress * 100).toInt()}%")
            }

            updateNotification("Video saved: ${outputFile.name}")
            segments.forEach { it.delete() }
            delay(1000)

        } catch (e: Exception) {
            e.printStackTrace()
            _serviceState.update { it.copy(mergeError = e.message) }
            updateNotification("Merge failed: ${e.message}")
            delay(3000)
        } finally {
            _serviceState.update { it.copy(isMerging = false) }
            stopForeground(true)
            stopSelf()
        }
    }

    // Q3: Short beep on recording start/stop using loom_beep.mp3, respects enableBeeps setting
    private fun beep() {
        if (!LoomSettings(this).enableBeeps) return
        try {
            val mp = MediaPlayer.create(this, R.raw.loom_beep)
            mp.setOnCompletionListener { it.release() }
            mp.start()
        } catch (e: Exception) {
            android.util.Log.w("LoomService", "beep: Failed to play sound", e)
        }
    }

    // C5: Delete segment files left over from a previous crashed session
    private fun cleanupOrphanedSegments() {
        serviceScope.launch(Dispatchers.IO) {
            val videoDir = File(getExternalFilesDir("videos"), "")
            val orphans = videoDir.listFiles { _, name ->
                name.startsWith("segment_bg_") && name.endsWith(".mp4")
            } ?: return@launch
            if (orphans.isNotEmpty()) {
                android.util.Log.w("LoomService", "cleanupOrphanedSegments: Deleting ${orphans.size} orphaned segment(s)")
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
                android.util.Log.w("LoomService", "hasEnoughStorage: Available=${available / 1024 / 1024}MB, Required=${required / 1024 / 1024}MB")
            }
            available >= required
        } catch (e: Exception) {
            android.util.Log.w("LoomService", "hasEnoughStorage: Check failed, proceeding optimistically", e)
            true
        }
    }
}
