package com.aritr.rova.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.os.PowerManager
import android.os.StatFs
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
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
import androidx.lifecycle.Observer
import com.aritr.rova.MainActivity
import com.aritr.rova.R
import com.aritr.rova.RovaApp
import com.aritr.rova.service.notification.NotificationState
import com.aritr.rova.service.notification.toCopy
import com.aritr.rova.service.scheduler.AlarmScheduler
import android.os.Binder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
import com.aritr.rova.data.QualityPresets
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionStore
import com.aritr.rova.data.Terminated
import com.aritr.rova.data.peakBudgetMultiplier
import com.aritr.rova.service.surface.HeadlessPreviewSurface
import com.aritr.rova.service.surface.HeadlessPreviewSurfaces
import com.aritr.rova.service.export.ExportPipeline
import com.aritr.rova.service.export.ExportResult
import com.aritr.rova.service.wakelock.WakeLockPolicy
import com.aritr.rova.utils.RovaCrashReporter
import com.aritr.rova.utils.RovaLog
import androidx.camera.video.VideoRecordEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

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
    private var stopAndMergeJob: Job? = null
    private var stopRequested = false
    // Phase 1.3: discriminator for the terminal record on merge success.
    // True when a stop arrived through RovaStopReceiver / SessionController.
    // requestStop (user-driven). False for natural completion (loop limit
    // reached, segment failure, storage exhaustion, etc). Reset at session
    // start. Read inside performMerge's NonCancellable terminal-write block,
    // so write-before-merge ordering is what matters; field is single-writer
    // (the requestStop coroutine) before that block runs.
    private var userStopRequested = false
    private var wakeLock: PowerManager.WakeLock? = null

    // C1: State is now an instance field, not a companion object global
    private val _serviceState = MutableStateFlow(RovaServiceState())

    // C2: Signals when the UI has provided a SurfaceProvider so recording can begin
    private var surfaceProviderReady = CompletableDeferred<Unit>()

    // R2: Signals when the current VideoRecordEvent.Finalize callback has fired
    private var recordingFinalized = CompletableDeferred<Unit>().also { it.complete(Unit) }

    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: androidx.camera.core.Camera? = null
    // Phase 3.5 — observer on `camera`'s cameraState LiveData; re-made on bind, nulled on unbind.
    private var cameraStateObserver: Observer<CameraState>? = null
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    // Smoke-test fix: tracks whether the live Preview is bound to the DUMMY
    // headless surface. CameraX's `Preview.setSurfaceProvider` hot-swap from
    // DUMMY → UI does not reliably re-cycle a fresh `SurfaceRequest` to the
    // new provider on all devices, so a DUMMY-bound first loop can leave the
    // PreviewView black until the next teardown/rebind. We use this flag to
    // gate a one-shot rebind in `startPeriodicRecording` once it's safe (no
    // active recording → no VideoCapture mid-segment teardown).
    private var boundToDummy = false

    // C13 / ADR 0002: headless Preview.SurfaceProvider for background recording.
    // Per-request surface lifecycle is internal to HeadlessPreviewSurface; the
    // service holds only the provider handle and closes it on teardown.
    private var headlessSurface: HeadlessPreviewSurface? = null

    // C18: per-session manifest store and the active session's identity.
    // currentSessionId / currentSessionDir are set in startPeriodicRecording
    // and cleared on session end. The existing segmentCount field is the
    // canonical sequence index — manifest filenames are derived from it.
    //
    // pendingPersistJobs holds every in-flight segment persistence Deferred
    // (sha1 + appendSegment). Stop awaits ALL of them before reading the
    // manifest, so no finalized segment is dropped from merge. Submission
    // order is preserved because SessionStore.submitPersistFinalizedSegment
    // launches on a scope whose dispatcher is the serial persist dispatcher.
    // C18 + Phase 1.2: SessionStore is process-singleton in RovaApp so the
    // service and RovaTickReceiver share one persistDispatcher (serializing
    // manifest writes across both writers). Constructing a second SessionStore
    // here would create a second serial dispatcher and let the receiver and
    // service race on the same on-disk manifests.
    private val sessionStore: SessionStore
        get() = (applicationContext as RovaApp).sessionStore
    private var currentSessionId: String? = null
    private var currentSessionDir: File? = null
    /**
     * Phase 1.4 (ADR 0006 B18). Decided once in `onStartCommand` Phase 1
     * preflight (RAM-only), used to compute the FGS-type bitfield BEFORE
     * `startForeground`, then persisted to the manifest at
     * `createSession` time, then driving the per-segment permission gate
     * for the remainder of the session. Immutable for the session
     * lifetime.
     */
    private var currentAudioMode: com.aritr.rova.data.AudioMode = com.aritr.rova.data.AudioMode.VIDEO_ONLY
    /**
     * Phase 1.4 (ADR 0006 B3 + B5/B12). Set by the per-segment gate when
     * a non-USER stop cause fires (PERMISSION_REVOKED, LOW_STORAGE) to
     * carry the reason from the gate to the eager-terminal-write in
     * [stopPeriodicRecordingAndMerge]. `null` for plain user STOP /
     * notification STOP / loop completion (those use [StopReason.USER]
     * via `userStopRequested` or stay `null` for COMPLETED).
     */
    @Volatile
    private var currentStopReason: com.aritr.rova.data.StopReason? = null
    private val pendingPersistJobs: MutableList<Deferred<SegmentRecord>> = mutableListOf()

    // Phase 1.2: tick wakeup signal. RovaTickReceiver forwards postTick(seq)
    // here via the registered SessionController; the recording loop suspends
    // on tickSignals.receive() to wait out an inter-segment interval.
    // UNLIMITED capacity means a tick that arrives before the loop is ready
    // to receive it is buffered, not dropped.
    private val tickSignals = Channel<Int>(Channel.UNLIMITED)
    private var nextTickSeq = 0
    private var sessionController: SessionController? = null

    /**
     * Phase 1.2 [RovaController] implementation. One instance per recording
     * session; lifetime exactly matches ServiceController registration.
     * Enforces the interface contract that [sessionId] is stable per
     * registration — the field is `val`, captured at construction.
     */
    private inner class SessionController(override val sessionId: String) : RovaController {
        override fun postTick(seq: Int, kind: TickKind) {
            when (kind) {
                TickKind.WAKE -> {
                    // trySend is non-blocking; UNLIMITED channel never rejects on
                    // capacity. A failure here would mean the channel is closed,
                    // which only happens at service teardown.
                    val ok = tickSignals.trySend(seq).isSuccess
                    if (!ok) RovaLog.w("SessionController.postTick($seq, WAKE): channel closed")
                }
                TickKind.WATCHDOG -> {
                    // Reaching here means the process is alive when the
                    // watchdog fired — recording is on track or already
                    // finished cleanly. No-op is intentional: WATCHDOG only
                    // matters when the controller is GONE (process killed),
                    // in which case RovaTickReceiver writes KILLED_BY_SYSTEM
                    // and we never run.
                    RovaLog.d("SessionController.postTick($seq, WATCHDOG): liveness OK, no-op")
                }
            }
        }

        override fun requestStop() {
            // Phase 1.3 — RovaStopReceiver and (legacy ACTION_STOP path
            // removed) invoke this.
            //
            // Ordering invariant: userStopRequested MUST be set BEFORE
            // stopPeriodicRecordingAndMerge launches its merge coroutine,
            // so the NonCancellable terminal-write block sees the right
            // discriminator. Both writes happen inside this single
            // serviceScope.launch — sequential statements, no race window.
            //
            // Why launch on serviceScope: the receiver thread is
            // RovaApp.appScope/IO; stopPeriodicRecordingAndMerge mutates
            // service state (recordingJob, _serviceState) and posts UI-
            // bound notifications, so it stays on the service's Main-bound
            // serviceScope per the rest of the codebase. The launch also
            // makes this safe to invoke from a receiver context that is
            // about to call goAsync().finish() — we do not block the
            // broadcast slot.
            RovaLog.d("SessionController.requestStop: sessionId=$sessionId")
            serviceScope.launch {
                userStopRequested = true
                // ADR 0006 B-fix-5: explicit StopReason.USER for the
                // notification-STOP / UI-stop path. Belt-and-braces with
                // the eager-write fallback (`currentStopReason ?:
                // StopReason.USER`) — but explicit assignment makes the
                // intent visible at the call site and prevents any
                // future first-writer-wins race from inheriting an
                // unrelated null/stale value.
                if (currentStopReason == null) {
                    currentStopReason = com.aritr.rova.data.StopReason.USER
                }
                stopPeriodicRecordingAndMerge()
            }
        }
    }

    /**
     * Phase 1.2 stop-path helper. Cancels any pending alarm for the active
     * session and unregisters the controller from [ServiceController]. Order
     * matters: cancel BEFORE unregister so an in-flight tick (already past
     * the AlarmManager but not yet at the receiver) sees the controller and
     * is forwarded as a no-op `postTick` rather than landing on an empty
     * registry and writing KILLED_BY_SYSTEM.
     */
    private fun cancelAlarmsAndUnregister() {
        val sid = currentSessionId
        if (sid != null) {
            try {
                // Cancels both WAKE and WATCHDOG slots — at stop time the
                // service does not know which were live; it wants the
                // session's alarm footprint zeroed.
                AlarmScheduler.cancelForSession(this, sid)
            } catch (e: Exception) {
                RovaLog.w("cancelAlarmsAndUnregister: AlarmScheduler.cancelForSession failed", e)
            }
        }
        sessionController?.let { controller ->
            ServiceController.unregister(controller)
        }
        sessionController = null
    }

    // C18: set when a segment's CameraX Finalize callback did not fire within
    // FINALIZE_TIMEOUT_MS during a cancellation path. The file is left on disk
    // (it may still be a valid segment that finalized late). When set, stop
    // skips merge entirely so Phase 1.5 recovery on next launch can validate
    // and offer the session to the user. Reset at session start.
    private var stopNeedsRecovery = false

    // Camera config
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentMode: String = "Portrait"
    private var flashMode = 0 // 0: OFF, 1: ON, 2: AUTO

    // Recording config
    private var nSeconds = 10L
    private var mMinutes = 10L
    private var limitLoops = -1 // -1 for continuous
    private var resolutionStr = QualityPresets.DEFAULT
    private var configuredResolution: String? = null // Track what resolution the camera is currently configured for

    /**
     * Phase 1.6 (ROADMAP_v6 §1.6 / ADR 0003). Frozen export tier for the
     * live session. Cached from the [SessionManifest.exportTier] returned
     * by `sessionStore.createSession(...)` so the per-segment storage gate
     * does NOT recompute from `Build.VERSION.SDK_INT` (which would drift if
     * a recovered session ever runs on a downgraded build) and does NOT
     * read the manifest from disk on the gate hot path.
     *
     * Cleared in [releaseResources] alongside the rest of the per-session
     * state.
     */
    private var currentExportTier: com.aritr.rova.data.ExportTier? = null

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
                // CameraX's own teardown drives release of the previous surface
                // via its provideSurface result callback — no eager release here.
                existingPreview.setSurfaceProvider(surfaceProvider)
            } else {
                serviceScope.launch { setupCamera() }
            }
        } else {
            preview?.let { existingPreview ->
                if (_serviceState.value.isPeriodicActive) {
                    RovaLog.d("setSurfaceProvider: UI surface removed during active session, switching to dummy surface")
                    existingPreview.setSurfaceProvider(createDummySurfaceProvider())
                }
            }
        }
    }

    fun stopCameraPreview() {
        if (_serviceState.value.isPeriodicActive) return // Don't stop if recording
        RovaLog.d("stopCameraPreview: Unbinding camera for background")
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        markCameraUnbound()  // Phase 3.5
        releaseDummySurface()
        preview = null
        videoCapture = null
        camera = null
        configuredResolution = null
        boundToDummy = false
        _serviceState.update { it.copy(isCameraActive = false) }
    }

    fun startCameraPreview() {
        if (lifecycleRegistry.currentState < Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!_serviceState.value.isCameraActive) {
            serviceScope.launch {
                // Smoke-test fix: brief grace window for the UI's
                // SurfaceProvider to attach before falling back to the
                // headless DUMMY surface. ServiceConnection.onServiceConnected
                // typically fires before RecordScreen's DisposableEffect runs
                // setSurfaceProvider, so without this window the cold-start
                // first loop binds to DUMMY and the later UI swap leaves
                // PreviewView black on devices where CameraX does not
                // re-cycle the SurfaceRequest. Background-only startup (no
                // UI ever) still proceeds via DUMMY after the timeout — the
                // existing 3 s `surfaceProviderReady.await()` in
                // startPeriodicRecording is the headless ceiling, so this
                // 500 ms window cannot delay a true headless launch by more
                // than a frame.
                if (currentSurfaceProvider == null) {
                    val waited = withTimeoutOrNull(500) {
                        while (currentSurfaceProvider == null) delay(20)
                    }
                    RovaLog.d(
                        "startCameraPreview: surface grace ${if (waited != null) "UI arrived" else "expired -> DUMMY"}"
                    )
                }
                setupCamera()
            }
        } else {
            RovaLog.d("startCameraPreview: Camera already active, skipping setup")
        }
    }

    companion object {
        const val CHANNEL_ID = "RovaRecordingChannel"
        const val NOTIFICATION_ID = 1
        // Phase 3.1 — title used for the legacy free-form
        // updateNotification(contentText) post-sites (init / error /
        // transient strings). Preserved bytecode-exact so behavior
        // parity holds at every legacy call site. The typed
        // updateNotification(NotificationState) overload derives
        // its own title per the mockup.
        private const val LEGACY_NOTIFICATION_TITLE = "🎥 Rova Recording Active"
        // Phase 1.3 — legacy ACTION_STOP service-intent constant removed.
        // Stop arrives via RovaStopReceiver.ACTION_STOP (broadcast).
        private const val WAKE_LOCK_RELAX_THRESHOLD_SECONDS = 120
        private const val WAKE_LOCK_FINAL_COUNTDOWN_SECONDS = 10
        private const val WAKE_LOCK_IDLE_UPDATE_STEP_SECONDS = 30

        // C18: bounds for the finalize-await race fix.
        // FINALIZE_TIMEOUT_MS — how long the cancel/exception catch waits for
        //   the real CameraX Finalize callback before synthesizing completion.
        // STOP_FINALIZE_AWAIT_MS — how long stopPeriodicRecordingAndMerge
        //   waits for recordingFinalized. MUST exceed FINALIZE_TIMEOUT_MS so
        //   the synthesized completion (timeout case) reaches stop before
        //   stop times out and snapshots prematurely.
        private const val FINALIZE_TIMEOUT_MS = 5_000L
        private const val STOP_FINALIZE_AWAIT_MS = 8_000L

        // Phase 1.2 — watchdog and wake-drift bounds.
        // WATCHDOG_GRACE_MS: extra slack past expected segment end before the
        //   watchdog fires. Larger than typical CameraX finalize variance so a
        //   slow encoder stop does not racily trip a liveness-OK alarm. Only
        //   matters when the process is dead — sets the upper bound on how
        //   late KILLED_BY_SYSTEM is written after an in-recording kill.
        // WAKE_DRIFT_BUDGET_MS: how much later than the scheduled trigger we
        //   accept before declaring the OS suppressed the alarm. Same value
        //   for the post-re-arm window. Phase 1.2 fixed; ADR 0004 may scale
        //   per-band.
        // WAKE_REARM_OFFSET_MS: short cushion past `now` for the re-arm
        //   trigger so the AlarmManager queue does not misfile it as a
        //   missed alarm.
        private const val WATCHDOG_GRACE_MS = 30_000L
        private const val WAKE_DRIFT_BUDGET_MS = 60_000L
        private const val WAKE_REARM_OFFSET_MS = 5_000L

        // beepStart playback await + acoustic tail margin. See beepStart()
        // KDoc for rationale.
        private const val BEEP_PLAYBACK_TIMEOUT_MS = 1_500L
        private const val BEEP_TAIL_MARGIN_MS = 150L

        /**
         * Phase 1.4 (ADR 0006 B6/B12). Per-segment storage-gate headroom
         * above the segment-bytes estimate. Covers MediaMuxer trailer
         * fsync + manifest atomic write + filesystem overhead during
         * merge.
         */
        private const val FINALIZE_HEADROOM_MIB = 50L

        /**
         * Phase 1.4 (ADR 0006 B11) — caller-side FGS-start guard.
         *
         * On Android 12+, `Context.startForegroundService(...)` itself
         * throws `ForegroundServiceStartNotAllowedException` when the app
         * is in a non-approved state. This is the **primary** Android 12+
         * start-restriction site; the inner `Service.startForeground(...)`
         * guard ([Site B][onStartCommand]) is defense-in-depth.
         *
         * The SDK_INT check + `e is ForegroundServiceStartNotAllowedException`
         * pattern is API-safe on pre-31 runtimes: the class symbol is only
         * resolved when the SDK gate passes.
         */
        fun start(context: Context, nSeconds: Float, mMinutes: Float, limitLoops: Int = -1, resolution: String = QualityPresets.DEFAULT): StartResult {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isAppVisible(context)) {
                RovaLog.w("start: refusing to launch camera recording while app is backgrounded")
                return StartResult.Blocked(StartBlocked.APP_NOT_VISIBLE)
            }
            val intent = Intent(context, RovaRecordingService::class.java).apply {
                putExtra("N_SECONDS", nSeconds)
                putExtra("M_MINUTES", mMinutes)
                putExtra("LIMIT_LOOPS", limitLoops)
                putExtra("RESOLUTION", resolution)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                StartResult.Started
            } catch (e: IllegalStateException) {
                val isFgsRestricted =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        e is android.app.ForegroundServiceStartNotAllowedException
                if (isFgsRestricted) {
                    RovaCrashReporter.recordException(e, "FGS start not allowed (caller, API 31+)")
                    StartResult.Blocked(StartBlocked.FGS_RESTRICTED)
                } else {
                    RovaCrashReporter.recordException(e, "FGS start ISE (caller)")
                    StartResult.Blocked(StartBlocked.UNKNOWN_ISE)
                }
            }
        }

        /**
         * Phase 1.3 — UI/notification stop entrypoint. Sends a same-UID
         * broadcast to [RovaStopReceiver]. The receiver does the
         * registry-first delivery (live controller → in-process stop;
         * registry-dead → manifest USER_STOPPED).
         *
         * Caller MUST resolve [sessionId] before invoking — typically via
         * `ServiceController.current()?.sessionId`. The receiver drops
         * intents missing the extra; this is by design so a stale UI
         * tap (no live session) can't poison the stop pipeline.
         *
         * No `startService` / `startForegroundService` here: ROADMAP_v4
         * §1.3 forbids it, and Android 12+ throws on FGS starts from any
         * background-only context. CI lint `checkStopNoGetService`
         * enforces.
         */
        fun stop(context: Context, sessionId: String) {
            if (sessionId.isEmpty()) {
                RovaLog.w("RovaRecordingService.stop: empty sessionId; ignoring")
                return
            }
            val intent = Intent(context, RovaStopReceiver::class.java).apply {
                action = RovaStopReceiver.ACTION_STOP
                // Distinct PI/data per session so any cached PendingIntent
                // resolves to the current sessionId — see RovaStopReceiver
                // companion docs.
                data = android.net.Uri.parse(
                    "${RovaStopReceiver.URI_SCHEME}://${RovaStopReceiver.URI_HOST_STOP}/$sessionId"
                )
                putExtra(RovaStopReceiver.EXTRA_SESSION_ID, sessionId)
            }
            context.sendBroadcast(intent)
        }

        // Flash mode constants
        const val FLASH_MODE_OFF = 0
        const val FLASH_MODE_ON = 1
        const val FLASH_MODE_AUTO = 2

        private fun isAppVisible(context: Context): Boolean {
            val processInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(processInfo)
            return processInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        currentMode = RovaSettings(this).mode
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createNotificationChannel()
        // C12: automatic cleanup is disabled until a recovery UI lands in
        // Phase 2. cleanupOrphanedSegments() is intentionally NOT called here;
        // segments persist across launches so recovery can offer them back.
        // Cleanup happens only via (a) successful same-session merge or
        // (b) explicit user-action discard in Phase 2.
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Phase 1.3 — legacy ACTION_STOP intent path removed. All stop
        // signals now arrive via RovaStopReceiver, which forwards through
        // SessionController.requestStop. Receiver-driven path avoids the
        // FGS-from-background restriction Android 12+ enforces on
        // startForegroundService.
        if (intent == null) {
            RovaLog.w("onStartCommand: ignoring null intent restart; recording must be launched from foreground UI")
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isAppVisible(this)) {
            RovaLog.w("onStartCommand: rejecting background start for camera session")
            stopSelf()
            return START_NOT_STICKY
        }

        nSeconds = intent.getFloatExtra("N_SECONDS", 10f).toLong()
        mMinutes = intent.getFloatExtra("M_MINUTES", 10f).toLong()
        limitLoops = intent.getIntExtra("LIMIT_LOOPS", -1)
        resolutionStr = intent.getStringExtra("RESOLUTION") ?: QualityPresets.DEFAULT
        segmentCount = 0
        stopRequested = false
        userStopRequested = false
        // Phase 2.4 — publish `segmentCount` into `_serviceState` so
        // the active HUD's status pill (Merging branch) and the
        // `MergeCompleteCard` can read the live finalized-segment
        // count. Reset is bundled with the existing session-start
        // update so the UI never sees a stale count from a prior
        // session.
        _serviceState.update {
            it.copy(totalLoops = limitLoops, currentLoop = 0, segmentCount = 0)
        }

        // ============================================================
        // ADR 0006 §"Init-window write order" (B15 + B19) — TWO-PHASE PREFLIGHT
        // ============================================================
        //
        // Phase 1 (RAM-only) MUST run BEFORE startForeground because
        // Android 12+ requires startForeground within ~5s of the
        // caller's startForegroundService. Disk I/O (StatFs) here would
        // risk an FGS-deadline ANR.
        //
        // Phase 2 (filesystem) runs AFTER startForeground but BEFORE
        // createSession. Both phases are pre-manifest.

        // ---- Phase 1: RAM-only preflight ----
        // CAMERA permission check (mandatory). Row 5 cleanup if missing.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            RovaLog.e("onStartCommand: CAMERA permission missing — aborting session (row 5)")
            stopSelf()
            return START_NOT_STICKY
        }
        // ADR 0006 B18: audioMode decided pre-FGS, immutable for the
        // session lifetime, drives the FGS-type bitfield AND the manifest
        // record AND the CameraX recorder configuration.
        val audioGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        currentAudioMode = if (audioGranted) {
            com.aritr.rova.data.AudioMode.VIDEO_AUDIO
        } else {
            com.aritr.rova.data.AudioMode.VIDEO_ONLY
        }
        // ADR 0006 B18: FGS type bitfield from pre-FGS audio-mode decision.
        // Hardcoding CAMERA|MICROPHONE here would crash under Android 14+
        // when RECORD_AUDIO is denied.
        val fgsType = when (currentAudioMode) {
            com.aritr.rova.data.AudioMode.VIDEO_AUDIO ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            com.aritr.rova.data.AudioMode.VIDEO_ONLY ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }

        // ---- Site B FGS guard (B10 + B20) ----
        // Wrap startForeground in BOTH IllegalStateException (FGS-restricted /
        // FGS-deadline) AND SecurityException (Android 14+ FGS-type vs
        // permission mismatch). Pre-manifest init window — no manifest to
        // roll back. Row 4b cleanup.
        val notification = createNotification(LEGACY_NOTIFICATION_TITLE, "Initializing background recording...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: IllegalStateException) {
            val isFgsRestricted =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
            val tag = if (isFgsRestricted) "FGS start not allowed (service, API 31+)" else "FGS start ISE (service)"
            RovaCrashReporter.recordException(e, tag)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            // ADR 0006 B18 + B20: Android 14+ throws SecurityException when
            // FGS type does not match granted permissions. Defense-in-depth
            // — pre-FGS audio-mode decision should prevent this.
            RovaCrashReporter.recordException(
                e, "FGS type SecurityException (audioMode=$currentAudioMode)"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        acquireWakeLock()

        // ---- Phase 2: filesystem preflight (post-FGS, pre-manifest) ----
        // ADR 0006 B19: storage check moves AFTER startForeground because
        // StatFs on adopted/removable storage may stall.
        // Phase 1.6 (ROADMAP_v6 §1.6 / ADR 0003): tier-aware peak budget.
        // Preflight runs BEFORE createSession; derive tier from the shared
        // SDK→tier helper that SessionStore.createSession will use moments
        // later, so preflight and the persisted manifest agree on the tier.
        val preflightTier = com.aritr.rova.data.currentExportTier()
        val peakBytes = estimatePeakBytes(preflightTier)
        if (!hasEnoughStorage(peakBytes)) {
            RovaLog.e("onStartCommand: Insufficient storage — aborting session (row 3)")
            updateNotification("Not enough storage to record. Free up space and try again.")
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Reset surface provider gate for this new session
        surfaceProviderReady = CompletableDeferred()
        if (currentSurfaceProvider != null) surfaceProviderReady.complete(Unit)
        startPeriodicRecording()

        return START_NOT_STICKY
    }

    /**
     * Phase 1.4 (ADR 0006 rows 6/7 + B-fix-1). Post-manifest init failure
     * cleanup. Writes `markTerminated(USER_STOPPED, INIT_FAILED)` for the
     * given session, inspects [MarkTerminatedResult], and tears down the
     * foreground service. Suspending — must run on a coroutine.
     *
     * Layered fail-safety: even if `markTerminated` returns
     * [MarkTerminatedResult.Failed], the service still tears down (Phase
     * 1.5 cold-launch will classify the residual session per the
     * `stopRequested=false, terminated=null` branch — KILLED_FORCE_STOP,
     * which is at worst a misclassification but never a leaked FGS).
     */
    private suspend fun markInitFailedAndStop(sessionId: String, where: String) {
        try {
            val result = sessionStore.markTerminated(
                sessionId,
                Terminated.USER_STOPPED,
                com.aritr.rova.data.StopReason.INIT_FAILED
            )
            when (result) {
                is com.aritr.rova.data.MarkTerminatedResult.Wrote ->
                    RovaLog.d("markInitFailedAndStop: wrote USER_STOPPED/INIT_FAILED for $sessionId at $where")
                is com.aritr.rova.data.MarkTerminatedResult.AlreadyTerminal ->
                    RovaLog.d(
                        "markInitFailedAndStop: $sessionId already" +
                            " ${result.existingTerminated}/${result.existingStopReason}; suppressed at $where"
                    )
                is com.aritr.rova.data.MarkTerminatedResult.Failed -> {
                    RovaLog.e(
                        "markInitFailedAndStop: markTerminated FAILED for $sessionId at $where" +
                            " (attempts=${result.attempts}); Phase 1.5 will classify",
                        result.cause
                    )
                    RovaCrashReporter.recordException(
                        result.cause, "markInitFailedAndStop FAILED at $where"
                    )
                }
            }
        } catch (t: Throwable) {
            RovaLog.e("markInitFailedAndStop: threw for $sessionId at $where", t)
        } finally {
            _serviceState.update { it.copy(isPeriodicActive = false) }
            cancelAlarmsAndUnregister()
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
        }
    }

    private fun startPeriodicRecording() {
        recordingJob?.cancel()
        recordingJob = serviceScope.launch {
            try {
                _serviceState.update { it.copy(isPeriodicActive = true) }

                // C18: allocate a fresh session and persist its initial manifest
                // before any segment is recorded. Manifest IO on Dispatchers.IO.
                val config = SessionConfig(
                    durationSeconds = nSeconds.toInt(),
                    intervalMinutes = mMinutes.toInt(),
                    resolution = resolutionStr,
                    loopCount = limitLoops,
                    mode = currentMode,
                )
                // ADR 0005 §"Concurrency Invariants" item 1 — startupMutex.
                // The recovery scan holds this mutex for its full duration;
                // we hold it across createSession + register so a session
                // cannot be observed half-registered. Without this, the
                // scan could see the new session dir on disk before
                // ServiceController.register() landed, classify it as
                // dead, and write KILLED_FORCE_STOP into a manifest the
                // service is about to start using. The age filter is the
                // backup defense; this mutex is the primary one.
                val app = applicationContext as RovaApp
                val manifest = app.startupMutex.withLock {
                    // ADR 0006 B18: persist audioMode to the manifest so
                    // recovery / UI / Phase 1.7 see the same audio-mode
                    // decision the FGS was started with.
                    val m = withContext(Dispatchers.IO) {
                        sessionStore.createSession(config, currentAudioMode)
                    }
                    currentSessionId = m.sessionId
                    currentSessionDir = sessionStore.sessionDir(m.sessionId)
                    // Phase 1.6: cache frozen tier from manifest; gate
                    // reads this, never SDK_INT.
                    currentExportTier = m.exportTier
                    pendingPersistJobs.clear()
                    stopNeedsRecovery = false
                    // ADR 0006 B-fix-5: reset currentStopReason at session
                    // start so a stale gate-fired reason from a prior
                    // session can't leak into this session's eager
                    // terminal write. Plain UI/notification STOP defaults
                    // to StopReason.USER via the eager-write fallback
                    // (`currentStopReason ?: StopReason.USER`).
                    currentStopReason = null
                    nextTickSeq = 0
                    // Drain stale ticks from any prior session/registration
                    // so a late receive() in this session doesn't unblock
                    // on a tick meant for the previous one.
                    while (tickSignals.tryReceive().isSuccess) { /* drain */ }
                    // Phase 1.2: register controller for this session.
                    // Receiver forwards postTick here only while this
                    // registration is live.
                    val controller = SessionController(m.sessionId)
                    if (ServiceController.register(controller)) {
                        sessionController = controller
                        m
                    } else {
                        // ADR 0006 row 6 (B-fix-1) — post-manifest init
                        // failure: createSession ran, manifest exists on
                        // disk. MUST write USER_STOPPED + INIT_FAILED so
                        // Phase 1.5 doesn't misclassify as KILLED_FORCE_STOP.
                        RovaLog.e("startPeriodicRecording: ServiceController already has a registration — refusing to start")
                        markInitFailedAndStop(m.sessionId, "register-collision")
                        null
                    }
                } ?: return@launch
                RovaLog.d("startPeriodicRecording: session=${manifest.sessionId}")

                // Phase 1.3 — re-post the foreground notification IMMEDIATELY
                // after the controller is registered. From this point
                // currentSessionId is non-null, so createNotification attaches
                // the STOP broadcast action targeting this session. Doing it
                // BEFORE the surface wait + setupCamera + camera-ready loop +
                // 2.5s stabilization is critical: those steps can block on
                // hostile-OEM CameraX startup hangs (ProcessCameraProvider.get
                // futures, Samsung MediaCodec init), and we MUST NOT leave the
                // user with a STOP-less notification while the session exists
                // and a stop signal is plumbed end-to-end. notify(SAME_ID,...)
                // does not restart the FGS deadline; the original
                // startForeground call from onStartCommand is sticky.
                updateNotification("Preparing camera...")

                // C2: Wait up to 3s for the UI to provide a SurfaceProvider.
                // If it never arrives (background-only launch), proceed headlessly.
                val surfaceArrived = withTimeoutOrNull(3000) { surfaceProviderReady.await() }
                if (surfaceArrived == null) {
                    RovaLog.w("startPeriodicRecording: SurfaceProvider timeout — proceeding headlessly")
                }

                // Beta-smoke fix: unconditional rebind on every record-start.
                // Pre-fix the path skipped reconfigure when configuredResolution
                // matched and isCameraActive was true, then relied on a
                // boundToDummy-conditional follow-up rebind to recover the
                // cold-launch race where the first setupCamera bound the DUMMY
                // surface before the UI's SurfaceProvider had committed. That
                // follow-up only fired when boundToDummy was true; on devices
                // where the DUMMY→UI hot-swap left PreviewView black without
                // updating boundToDummy, the user saw a black preview through
                // the entire first session.
                //
                // Record-start is the deterministic point at which the UI is
                // on screen and the user expects fresh frames — make every
                // start go through forceReconfigureCamera so the live binding
                // is guaranteed to be a fresh Preview attached to the current
                // SurfaceProvider (UI when present, DUMMY for headless). The
                // ~1 s reconfigure cost is absorbed by the existing 2.5 s
                // post-stabilize delay below.
                //
                // Headless / background launches still work: setupCamera reads
                // currentSurfaceProvider == null, falls back to the DUMMY
                // surface, and the recording loop proceeds. flipCamera is
                // unaffected — it owns its own forceReconfigureCamera path.
                RovaLog.d(
                    "startPeriodicRecording: forcing camera reconfigure on start" +
                        " (configured=$configuredResolution, requested=$resolutionStr," +
                        " surface=${if (currentSurfaceProvider != null) "UI" else "DUMMY"})"
                )
                forceReconfigureCamera()

                // Wait for camera to be fully active before recording.
                // ADR 0006 row 7 (B-fix-1) — post-manifest camera-bind /
                // camera-ready failure: manifest exists. MUST write
                // USER_STOPPED + INIT_FAILED.
                val cameraReady = withTimeoutOrNull(5000) {
                    while (!_serviceState.value.isCameraActive) {
                        delay(100)
                    }
                }
                if (cameraReady == null) {
                    RovaLog.e("startPeriodicRecording: Camera failed to activate within 5s — aborting")
                    updateNotification("Camera failed to start. Please restart recording.")
                    markInitFailedAndStop(manifest.sessionId, "camera-ready-timeout")
                    return@launch
                }

                // Let CameraX pipeline fully stabilize (encoder init, frame production)
                // Samsung devices need extra time for MediaCodec initialization
                delay(2500)
                RovaLog.d("startPeriodicRecording: Camera ready, starting recording loop")

                outer@ while (isActive) {
                    if (limitLoops != -1 && segmentCount >= limitLoops) {
                        stopPeriodicRecordingAndMerge()
                        break@outer
                    }

                    _serviceState.update { it.copy(currentLoop = segmentCount + 1) }
                    updateNotification(NotificationState.ClipRecording(segmentCount + 1, limitLoops.takeIf { it != -1 }))

                    // ADR 0006 B-fix-4: retry loop now branches on
                    // SegmentResult. RetryableFailure → reconfigure +
                    // retry. Success → advance. AbortNoOp / Terminated →
                    // break the OUTER loop immediately; do NOT reconfigure
                    // camera (which would race the in-flight teardown).
                    var lastResult: SegmentResult = SegmentResult.RetryableFailure
                    retry@ for (attempt in 1..3) {
                        _serviceState.update { it.copy(isRecording = true, recordingError = null) }
                        beepStart(mMinutes.toInt()) // Q3: beep on recording start
                        lastResult = recordSegment()
                        beepEnd(mMinutes.toInt()) // Q3: beep on recording stop
                        _serviceState.update { it.copy(isRecording = false) }

                        when (lastResult) {
                            SegmentResult.Success -> break@retry
                            SegmentResult.AbortNoOp,
                            SegmentResult.Terminated -> break@retry
                            SegmentResult.RetryableFailure -> {
                                if (attempt < 3) {
                                    RovaLog.w("Segment failed (attempt $attempt), retrying after camera reconfigure...")
                                    forceReconfigureCamera()
                                    delay(2500)
                                    withTimeoutOrNull(5000) {
                                        while (!_serviceState.value.isCameraActive) { delay(100) }
                                    }
                                }
                            }
                        }
                    }

                    when (lastResult) {
                        SegmentResult.Success -> {
                            // fall through to segmentCount++ + interval wait
                        }
                        SegmentResult.AbortNoOp -> {
                            // Layer-1 prerequisite race; service teardown
                            // already in progress via state machine. NO
                            // terminal write, NO stopPeriodicRecordingAndMerge
                            // call (would race the in-flight teardown).
                            RovaLog.d("startPeriodicRecording: AbortNoOp; breaking outer loop")
                            break@outer
                        }
                        SegmentResult.Terminated -> {
                            // Gate-fired or recovery-deferred. Either:
                            //   - gate already called stopPeriodicRecordingAndMerge,
                            //     which set stopRequested=true so the second
                            //     call below is a no-op (idempotent guard at
                            //     stopPeriodicRecordingAndMerge entry).
                            //   - finalize-timeout / exception path set
                            //     stopNeedsRecovery=true; the launched merge
                            //     body honors that and skips merge.
                            RovaLog.w("startPeriodicRecording: SegmentResult.Terminated; stopping session")
                            stopPeriodicRecordingAndMerge()
                            break@outer
                        }
                        SegmentResult.RetryableFailure -> {
                            // 3 attempts exhausted. Session must end.
                            RovaLog.e("startPeriodicRecording: Segment failed after retries, stopping session")
                            updateNotification("Recording failed. Stopping session.")
                            stopPeriodicRecordingAndMerge()
                            break@outer
                        }
                    }

                    segmentCount++
                    // Phase 2.4 — publish the new finalized-segment
                    // count so the merge HUD reads the real count
                    // when the service eventually flips
                    // `isMerging = true`. The private field is the
                    // canonical sequence index (manifest filenames
                    // are derived from it); this update only mirrors
                    // it onto the StateFlow.
                    _serviceState.update { it.copy(segmentCount = segmentCount) }

                    if (limitLoops != -1 && segmentCount >= limitLoops) {
                        stopPeriodicRecordingAndMerge()
                        break@outer
                    }

                    // Beta-smoke fix: interval is the wait BETWEEN
                    // recordings (end-to-start). A 10 s duration with
                    // a 1 m interval records for 10 s, then waits a
                    // full 60 s before the next recording starts.
                    // Pre-fix this subtracted `nSeconds` from the
                    // interval (start-to-start scheduling per the
                    // legacy ROADMAP.md §"Loop"); the user-facing
                    // "Interval Between Loops" copy reads end-to-start
                    // and that is the contract going forward.
                    val waitSeconds = (mMinutes * 60).toInt().coerceAtLeast(0)

                    if (waitSeconds > 0) {
                        waitForNextSegment(waitSeconds)
                    } else {
                        // Phase 1.8 / C17 (review round 3): zero-gap
                        // continuing iteration — refresh the bounded
                        // WakeLock timeout. waitForNextSegment's
                        // finally block is the per-segment refresh
                        // point only when waitSeconds > 0; continuous
                        // mode (interval == 0) bypasses it, so
                        // ACQUIRE_TIMEOUT_MS would otherwise expire
                        // silently on long continuous runs. The held
                        // branch of acquireWakeLock() refreshes via
                        // existing.acquire(ACQUIRE_TIMEOUT_MS).
                        acquireWakeLock()
                    }
                }
            } catch (e: CancellationException) {
                RovaLog.d("startPeriodicRecording: Cancelled")
                throw e
            } catch (e: Exception) {
                // ADR 0006 row 7 (B-fix-1) — generic post-manifest setup
                // failure (camera bind throw, config error, unexpected
                // exception). If a manifest exists, write USER_STOPPED +
                // INIT_FAILED before tearing down. Otherwise the throw
                // happened before createSession returned (pre-manifest);
                // just log + tear down.
                e.printStackTrace()
                updateNotification("Recording stopped: ${e.message?.take(60) ?: "unknown error"}")
                RovaCrashReporter.recordException(e, "startPeriodicRecording outer catch")
                val sid = currentSessionId
                if (sid != null) {
                    markInitFailedAndStop(sid, "outer-catch")
                } else {
                    _serviceState.update { it.copy(isPeriodicActive = false, isRecording = false) }
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    stopSelf()
                }
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
            markCameraUnbound()  // Phase 3.5
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
                markCameraUnbound()  // Phase 3.5
                _serviceState.update { it.copy(isCameraActive = false) }
            } else {
                val provider = withContext(Dispatchers.IO) {
                    ProcessCameraProvider.getInstance(this@RovaRecordingService).get()
                }
                cameraProvider = provider
            }

            val provider = cameraProvider ?: return@withLock

            RovaLog.d("setupCamera: Initializing UseCases (Preview + VideoCapture)")

            // Strict match against QualityPresets canonical labels:
            // any non-canonical resolutionStr falls through to Quality.FHD
            // exactly as before — we deliberately do NOT route through
            // QualityPresets.canonicalize here, since that would widen
            // the contract to honor "1080p" / "UHD" aliases the picker
            // never produces and the manifest never persists.
            val quality = when (resolutionStr) {
                QualityPresets.UHD -> Quality.UHD
                QualityPresets.FHD -> Quality.FHD
                QualityPresets.HD -> Quality.HD
                QualityPresets.SD -> Quality.SD
                else -> Quality.FHD
            }

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(quality, Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            val displayRotation = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                ?: android.view.Surface.ROTATION_0
            val targetRot = computeTargetRotation(displayRotation, currentMode)
            videoCapture = VideoCapture.Builder(recorder).setTargetRotation(targetRot).build()

            preview = Preview.Builder().setTargetRotation(targetRot).build()
            // Samsung devices require Preview to have an active surface for VideoCapture
            // to produce frames. Use a dummy surface as fallback when UI hasn't connected yet.
            val useDummy = currentSurfaceProvider == null
            val surfaceProvider = currentSurfaceProvider ?: createDummySurfaceProvider()
            preview?.setSurfaceProvider(surfaceProvider)
            RovaLog.d("setupCamera: SurfaceProvider=${if (useDummy) "DUMMY" else "UI"}")

            try {
                provider.unbindAll()
                RovaLog.d("setupCamera: Binding to lifecycle")
                camera = provider.bindToLifecycle(
                    this,
                    currentCameraSelector,
                    preview,
                    videoCapture
                )
                // Phase 3.5 — observe POST-bind runtime camera errors (additive to ADR 0006).
                camera?.let { observeCameraState(it) }
                configuredResolution = resolutionStr
                boundToDummy = useDummy
                _serviceState.update { it.copy(isCameraActive = true) }
                RovaLog.d("setupCamera: Camera binding COMPLETED. Active: true, resolution: $resolutionStr, boundToDummy=$boundToDummy")
                applyFlashState()
            } catch (e: Exception) {
                e.printStackTrace()
                RovaLog.e("setupCamera: Binding failed", e)
                _serviceState.update { it.copy(isCameraActive = false) }
            }
        }
    }

    // Phase 3.5 — attach a fresh cameraState observer after each bind. Each
    // bindToLifecycle yields a new Camera/LiveData; removeObserver here is a
    // harmless no-op vs. the old camera's LiveData. The observer is
    // lifecycle-bound to the service → auto-detaches on destroy.
    private fun observeCameraState(cam: androidx.camera.core.Camera) {
        val liveData = cam.cameraInfo.cameraState
        cameraStateObserver?.let { liveData.removeObserver(it) }
        val obs = Observer<CameraState> { camState ->
            (application as RovaApp).cameraStateSignal.onCameraState(camState.error?.code)
        }
        cameraStateObserver = obs
        liveData.observe(this, obs)
    }

    // Phase 3.5 — every unbindAll/teardown site routes here (no live camera).
    private fun markCameraUnbound() {
        (application as RovaApp).cameraStateSignal.onCameraUnbound()
        cameraStateObserver = null
    }

    // R1: Guard against flipping camera while a segment is actively recording.
    //
    // setupCamera() short-circuits when isCameraActive == true (line ~1017),
    // which is correct for preview-recovery callers that must be no-ops on
    // an already-bound pipeline. But that early-return swallows selector
    // changes — flipCamera must rebind. Route through forceReconfigureCamera
    // so the unbind / clear / setupCamera sequence runs end-to-end with the
    // new selector. Mirrors the rebind path already used by the recording
    // start flow (see call sites in startPeriodicRecording).
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
        serviceScope.launch { forceReconfigureCamera() }
    }

    /**
     * Phase 6 — Mode picker.
     * Mirrors [flipCamera] 1:1. Guarded on `isRecording` (silent no-op
     * mid-rec; the UI's `enabled = !isUiLocked` is the user-facing
     * gate). Calls `forceReconfigureCamera()` to rebind Preview +
     * VideoCapture with the new rotation.
     *
     * Future seamless-rebind slice: this guard and `flipCamera`'s
     * guard drop together; `forceReconfigureCamera` upgrades to
     * preserve the live Recording across rebind.
     */
    fun setMode(mode: String) {
        if (_serviceState.value.isRecording) {
            RovaLog.d("setMode: Ignored — recording in progress")
            return
        }
        currentMode = mode
        serviceScope.launch { forceReconfigureCamera() }
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

    /**
     * Returns a headless `Preview.SurfaceProvider` so CameraX's pipeline
     * produces frames when no UI preview is bound. Required on Samsung devices
     * where `VideoCapture` gates frame production on `Preview` having an
     * active surface. Implementation is API-gated per ADR 0002; per-request
     * surface lifecycle is internal to the impl.
     */
    private fun createDummySurfaceProvider(): Preview.SurfaceProvider {
        val existing = headlessSurface
        val surface = if (existing != null && !existing.isClosed) {
            existing
        } else {
            HeadlessPreviewSurfaces.create(ContextCompat.getMainExecutor(this)).also {
                headlessSurface = it
            }
        }
        RovaLog.d("createDummySurfaceProvider: headless surface ready (api ${Build.VERSION.SDK_INT})")
        return surface.provider
    }

    private fun releaseDummySurface() {
        // Closes the headless surface only on service teardown. Per-request
        // resources are released by their own provideSurface result callbacks.
        headlessSurface?.close()
        headlessSurface = null
    }

    /**
     * Phase 1.4 (ADR 0006 B-fix-4) — outcome of one [recordSegment]
     * invocation. Replaces the prior `Boolean` return type so the outer
     * recording loop can distinguish transient failures (retry / camera
     * reconfigure OK) from terminal/abort outcomes (must NOT retry —
     * service is stopping).
     *
     * The previous `Boolean` collapse caused two race-induced bugs:
     * - Gate-fired termination still entered the retry/reconfigure path,
     *   racing camera teardown with the in-flight stop.
     * - Layer-1 prerequisite-missed (no-op abort) entered retry too,
     *   eventually calling stopPeriodicRecordingAndMerge after spurious
     *   reconfigure cycles.
     */
    private sealed class SegmentResult {
        /** Segment finalized OK; persist coroutine submitted. Advance. */
        data object Success : SegmentResult()
        /**
         * Recording attempt failed in a way that may succeed on retry
         * (transient encoder failure, missing videoCapture, etc.).
         * Caller may invoke [forceReconfigureCamera] and retry up to the
         * existing per-segment retry budget.
         */
        data object RetryableFailure : SegmentResult()
        /**
         * Layer-1 prerequisite race (session ended between gate-pass and
         * call site). Caller MUST break out of the recording loop
         * immediately. NO terminal write; service teardown happens via
         * the state machine that already invalidated the prerequisite.
         */
        data object AbortNoOp : SegmentResult()
        /**
         * Gate fired a terminal stop (PERMISSION_REVOKED / LOW_STORAGE)
         * AND called [stopPeriodicRecordingAndMerge], OR the segment
         * deferred to Phase 1.5 recovery via `stopNeedsRecovery=true`.
         * Caller MUST break the recording loop. The outer
         * `stopPeriodicRecordingAndMerge` call is idempotent via the
         * `stopRequested` early-return so a second call is harmless.
         */
        data object Terminated : SegmentResult()
    }

    /**
     * Phase 1.4 (ADR 0006 B5/B12) — three-layer per-segment gate result.
     */
    private sealed class SegmentGateResult {
        /** All layers passed; proceed to recording. */
        data object Continue : SegmentGateResult()
        /**
         * Layer 1 (prerequisites) failed: invalid service state — session
         * ended, currentSessionId/Dir gone, controller unregistered.
         * `recordSegment` returns false silently (NO terminal write); the
         * outer loop's state machine handles it.
         */
        data object AbortNoOp : SegmentGateResult()
        /**
         * Layer 2 or Layer 3 fired: terminal-write required. The gate
         * has set [currentStopReason] and the caller must trigger
         * [stopPeriodicRecordingAndMerge].
         */
        data class Terminate(val reason: com.aritr.rova.data.StopReason) : SegmentGateResult()
    }

    /**
     * Phase 1.4 (ADR 0006 §"Per-Segment Safety Gates" B5 + B12 + B18).
     * Three-layer gate runs at the TOP of [recordSegment], BEFORE
     * `prepareRecording`/`start`.
     *
     * Layer 1 — service-state prerequisites (no-op abort):
     *   `_serviceState.value.isPeriodicActive`, `currentSessionId`,
     *   `currentSessionDir.exists()`, `sessionController` non-null.
     *   Layer-1 failures DO NOT write a terminal record (B12).
     *
     * Layer 2 — permission gate (audio-mode-aware per B18):
     *   - CAMERA missing → Terminate(PERMISSION_REVOKED).
     *   - RECORD_AUDIO missing AND audioMode == VIDEO_AUDIO →
     *     Terminate(PERMISSION_REVOKED). FGS-type bitfield is immutable;
     *     can't drop MIC type mid-stream.
     *   - RECORD_AUDIO missing AND audioMode == VIDEO_ONLY → Continue.
     *
     * Layer 3 — storage gate (B6 — uses `currentSessionDir` not
     *   `filesDir`). Estimate = bitrate * segmentDurationSec + 50 MiB
     *   headroom. `IllegalArgumentException` from `StatFs` is treated as
     *   layer-1-late-failure (no-op abort), not LOW_STORAGE.
     */
    private fun checkSegmentGates(): SegmentGateResult {
        // Layer 1 — prerequisites
        if (!_serviceState.value.isPeriodicActive) return SegmentGateResult.AbortNoOp
        val sid = currentSessionId ?: return SegmentGateResult.AbortNoOp
        val sdir = currentSessionDir ?: return SegmentGateResult.AbortNoOp
        if (!sdir.exists()) return SegmentGateResult.AbortNoOp
        if (sessionController?.sessionId != sid) return SegmentGateResult.AbortNoOp

        // Layer 2 — permission gate (audio-mode-aware)
        val cameraGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            RovaLog.w("checkSegmentGates: CAMERA permission revoked mid-session — terminating")
            return SegmentGateResult.Terminate(com.aritr.rova.data.StopReason.PERMISSION_REVOKED)
        }
        if (currentAudioMode == com.aritr.rova.data.AudioMode.VIDEO_AUDIO) {
            val audioGranted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!audioGranted) {
                RovaLog.w("checkSegmentGates: RECORD_AUDIO revoked in VIDEO_AUDIO session — terminating (FGS-type immutable per B18)")
                return SegmentGateResult.Terminate(com.aritr.rova.data.StopReason.PERMISSION_REVOKED)
            }
        }

        // Layer 3 — storage gate (Phase 1.6, ROADMAP_v6 §1.6 + ADR 0003).
        // Uses currentSessionDir per B6. bytesPerSecondForResolution is
        // BYTES/sec (B-fix-3, do NOT divide by 8).
        //
        // Required headroom = next-segment-estimate
        //                   + accumulatedSessionBytes × (tierMultiplier - 1)
        //                   + FINALIZE_HEADROOM_MIB.
        //
        // The (multiplier - 1) coefficient reserves ONLY the merge-overhead
        // leg beyond what's already on disk:
        //   - Tier 1 → 1× accumulated (final mux into the pending row).
        //   - Tier 2/3 → 2× accumulated (private merged + transient public copy).
        //
        // accumulatedSessionBytes() is filesystem-driven with a max(actual,
        // estimated) conservatism so the gate is not fooled by the lagging
        // manifest (segment persistence is async on a serial dispatcher;
        // between segments N and N+1 the manifest can report N records
        // while disk holds N+1 files).
        try {
            val tier = currentExportTier
                ?: return SegmentGateResult.AbortNoOp  // pre-createSession — layer-1 late failure
            val nextSegmentEstimate =
                com.aritr.rova.data.StorageEstimator.bytesPerSecondForResolution(resolutionStr) * nSeconds
            val accumulated = accumulatedSessionBytes(sdir)
            val mergeOverhead = accumulated * (tier.peakBudgetMultiplier - 1L)
            val safetyBuffer = FINALIZE_HEADROOM_MIB * 1024 * 1024L
            val required = nextSegmentEstimate + mergeOverhead + safetyBuffer

            val stat = StatFs(sdir.absolutePath)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            if (available < required) {
                RovaLog.w(
                    "checkSegmentGates: low storage (tier=$tier) — available=${available / 1024 / 1024}MB," +
                        " required=${required / 1024 / 1024}MB" +
                        " (next=${nextSegmentEstimate / 1024 / 1024}MB," +
                        " mergeOverhead=${mergeOverhead / 1024 / 1024}MB," +
                        " buffer=${safetyBuffer / 1024 / 1024}MB," +
                        " accumulated=${accumulated / 1024 / 1024}MB); terminating"
                )
                return SegmentGateResult.Terminate(com.aritr.rova.data.StopReason.LOW_STORAGE)
            }
        } catch (e: IllegalArgumentException) {
            // sessionDir disappeared between layer 1 and StatFs — treat as
            // layer-1-late-failure: no-op abort, NOT terminal write.
            RovaLog.w("checkSegmentGates: StatFs threw on $sdir; treating as layer-1-late-failure", e)
            return SegmentGateResult.AbortNoOp
        }

        return SegmentGateResult.Continue
    }

    /**
     * Phase 1.6 (ROADMAP_v6 §1.6) — thin wrapper around
     * [com.aritr.rova.data.StorageEstimator.accumulatedSessionBytes] using
     * the service's live session state ([segmentCount], [nSeconds],
     * [resolutionStr]). The pure helper lives in `data` so the
     * manifest-lag regression test does not need Robolectric.
     */
    private fun accumulatedSessionBytes(sessionDir: File): Long =
        com.aritr.rova.data.StorageEstimator.accumulatedSessionBytes(
            sessionDir = sessionDir,
            segmentCount = segmentCount,
            durationSeconds = nSeconds,
            resolution = resolutionStr
        )

    private suspend fun recordSegment(): SegmentResult {
        // ADR 0006 §"Per-Segment Safety Gates" — three-layer gate at top.
        when (val gate = checkSegmentGates()) {
            SegmentGateResult.Continue -> { /* proceed */ }
            SegmentGateResult.AbortNoOp -> {
                RovaLog.d("recordSegment: layer-1 prerequisite missed; AbortNoOp")
                return SegmentResult.AbortNoOp
            }
            is SegmentGateResult.Terminate -> {
                // Carry the reason from the gate to the eager terminal
                // write in stopPeriodicRecordingAndMerge (B3).
                currentStopReason = gate.reason
                userStopRequested = true  // terminal flow uses USER_STOPPED
                RovaLog.w("recordSegment: gate terminated with ${gate.reason}")
                stopPeriodicRecordingAndMerge()
                return SegmentResult.Terminated
            }
        }

        var videoFile: File? = null
        fun failRecording(message: String): SegmentResult {
            RovaLog.e("recordSegment: $message")
            updateNotification(message)
            _serviceState.update { it.copy(recordingError = message) }
            if (videoFile?.exists() == true) {
                videoFile?.delete()
            }
            return SegmentResult.RetryableFailure
        }

        // Phase 1.2 — watchdog state tracked across the whole function so
        // the outer finally cancels EXACTLY once on every exit path.
        // Reviewer (Codex v5) NO-GO fix: the prior version cancelled the
        // watchdog at the end of the recording delay, leaving stop() →
        // CameraX Finalize → persist submission unprotected. An
        // OEM/low-mem kill in that window would have been undetectable
        // until cold-launch recovery (Phase 1.5).
        var watchdogSid: String? = null
        var watchdogArmed = false

        try {
            val videoCap = videoCapture ?: run {
                return failRecording("Camera encoder is not ready")
            }

            // C18: segments belong to the active session; layout is
            //   videos/<sessionId>/segment_NNNN.mp4
            val sessionId = currentSessionId
                ?: return failRecording("No active session — startPeriodicRecording must run first")
            val sessionDir = currentSessionDir
                ?: return failRecording("Session directory missing for $sessionId")
            if (!sessionDir.exists()) sessionDir.mkdirs()

            // segmentCount is pre-increment here; finalize callback below
            // captures the same value as the segment's manifest filename.
            val segmentFilename = sessionStore.nextSegmentFilename(segmentCount)
            videoFile = File(sessionDir, segmentFilename)
            RovaLog.d("recordSegment: Preparing file: ${videoFile?.absolutePath}")

            val outputOptions = FileOutputOptions.Builder(requireNotNull(videoFile)).build()

            var pendingRecording = videoCap.output.prepareRecording(this, outputOptions)

            // ADR 0006 B18: audio-mode is locked at session start and
            // already verified by the per-segment gate. Drive the
            // recorder config from `currentAudioMode`, NOT a fresh
            // permission check. The gate guarantees that VIDEO_AUDIO
            // sessions still have RECORD_AUDIO; if mic was revoked,
            // the gate already terminated the session above.
            //
            // Lint-gate restoration: the gate-then-config window is not
            // atomic — RECORD_AUDIO can be revoked between the gate
            // pass and CameraX's audio enable. The FGS-type bitfield
            // is immutable mid-session (B18), so we cannot fall back
            // to video-only; route the race to the same
            // PERMISSION_REVOKED termination path the gate uses.
            if (currentAudioMode == com.aritr.rova.data.AudioMode.VIDEO_AUDIO) {
                try {
                    pendingRecording = pendingRecording.withAudioEnabled()
                } catch (se: SecurityException) {
                    RovaLog.w(
                        "recordSegment: RECORD_AUDIO revoked between gate and CameraX config — terminating",
                        se
                    )
                    currentStopReason = com.aritr.rova.data.StopReason.PERMISSION_REVOKED
                    return SegmentResult.Terminated
                }
            }

            // R2: Fresh deferred for this segment's finalize event
            recordingFinalized = CompletableDeferred()
            val recordingResult = CompletableDeferred<Boolean>()

            currentRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        RovaLog.d("Recording STARTED")
                    }
                    is VideoRecordEvent.Finalize -> {
                        currentRecording = null
                        val success = !event.hasError() &&
                            videoFile?.exists() == true &&
                            (videoFile?.length() ?: 0L) > 0L
                        if (success) {
                            RovaLog.d("Recording FINALIZED. Size: ${videoFile?.length()} bytes")
                            updateNotification("Segment Saved: ${((videoFile?.length() ?: 0L) / 1024)} KB")
                            // C18: persist segment to manifest. SHA-1 is computed
                            // on Dispatchers.IO so the finalize callback (Main)
                            // returns immediately. stopPeriodicRecordingAndMerge
                            // awaits lastSegmentPersistJob before reading the
                            // manifest.
                            val capturedSessionId = currentSessionId
                            val capturedFile = videoFile
                            val capturedFilename = segmentFilename
                            // Persist the ACTUAL recorded length (CameraX
                            // RecordingStats), not the configured clip length —
                            // an early-stopped 60 s clip must not show "1:00" in
                            // the Library/player when the .mp4 is 0:29.
                            val capturedDurationMs = recordedSegmentDurationMs(
                                recordedDurationNanos = event.recordingStats.recordedDurationNanos,
                                configuredFallbackMs = nSeconds * 1000L
                            )
                            // segmentCount is incremented by the outer loop
                            // after recordSegment returns — do NOT increment
                            // here. SessionStore reads segmentFile.length()
                            // when the persist coroutine runs; the file is
                            // fully written by then (finalize fires after).
                            // capturedFile inherits a non-null type via smart-cast
                            // from the success-condition checks above, so only
                            // capturedSessionId requires a null guard here.
                            if (capturedSessionId != null) {
                                // C18: submit to SessionStore's serial persist
                                // scope. Submission order == queue order ==
                                // append order. Stop's awaitAll rethrows any
                                // failure; do NOT switch to join().
                                val deferred = sessionStore.submitPersistFinalizedSegment(
                                    sessionId = capturedSessionId,
                                    segmentFile = capturedFile,
                                    filename = capturedFilename,
                                    durationMs = capturedDurationMs
                                )
                                pendingPersistJobs.add(deferred)
                            }
                        } else {
                            val errorMsg = if (event.hasError()) {
                                describeRecordingError(event.error)
                            } else {
                                "Recording produced an empty segment"
                            }
                            RovaLog.e("Recording ERROR: $errorMsg")
                            _serviceState.update { it.copy(recordingError = errorMsg) }
                            updateNotification(errorMsg)
                            if (videoFile?.exists() == true) videoFile?.delete()
                        }
                        if (!recordingResult.isCompleted) {
                            recordingResult.complete(success)
                        }
                        // R2: Signal that the file is fully written
                        if (!recordingFinalized.isCompleted) {
                            recordingFinalized.complete(Unit)
                        }
                    }
                    is VideoRecordEvent.Status -> { /* no-op */ }
                }
            }

            RovaLog.d("recordSegment: Recording initialized, waiting ${nSeconds}s")

            // Phase 1.2 — watchdog. Arm an alarm BEFORE the recording delay
            // and keep it ARMED across delay → stop() → Finalize callback →
            // persist-job submission. The cancel lives in the OUTER finally
            // (below the catches) so every exit path — normal success,
            // finalize-timeout recovery, cancellation rethrow, exception
            // return — clears the slot exactly once.
            //
            // Trigger time = expected end of recording + WATCHDOG_GRACE_MS.
            // The grace (30s) >> FINALIZE_TIMEOUT_MS (5s), so the watchdog
            // is still armed across the longest finalize wait. If the alarm
            // does fire on a still-alive process (very slow finalize),
            // SessionController.postTick(WATCHDOG) is a no-op; the only
            // side effect is one log line. If the process is dead, the
            // alarm fires elsewhere and the receiver writes KILLED_BY_SYSTEM.
            watchdogSid = currentSessionId
            if (watchdogSid != null) {
                val watchdogSeq = nextTickSeq++
                val watchdogTriggerAt =
                    System.currentTimeMillis() + (nSeconds * 1000L).toLong() + WATCHDOG_GRACE_MS
                try {
                    AlarmScheduler.arm(this, watchdogSid, watchdogSeq, watchdogTriggerAt, TickKind.WATCHDOG)
                    watchdogArmed = true
                } catch (e: Exception) {
                    RovaLog.w("recordSegment: watchdog arm failed for $watchdogSid", e)
                }
            }

            delay(nSeconds * 1000)

            RovaLog.d("recordSegment: Stopping recording normally")
            currentRecording?.stop()
            currentRecording = null

            // R2 + C18: wait for the Finalize callback. If it does not fire
            // within FINALIZE_TIMEOUT_MS the segment file may still be valid
            // (slow storage / OEM encoder stalls / large segments). Per Phase
            // 1.1 data-safety rule: do NOT delete. Set stopNeedsRecovery so
            // the loop ends and stop skips merge — Phase 1.5 will validate
            // the unconfirmed file on next launch.
            val success = withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { recordingResult.await() }
            if (success == null) {
                // ADR 0006 B-fix-4: finalize-timeout was previously
                // `return false` (treated as RetryableFailure by the
                // outer loop, which would reconfigure camera and overwrite
                // the same segment_NNNN.mp4). Now returns Terminated so
                // the loop breaks; the post-loop branch invokes
                // stopPeriodicRecordingAndMerge which honors stopNeedsRecovery
                // and skips merge.
                RovaLog.w("recordSegment: segment did not finalize within ${FINALIZE_TIMEOUT_MS}ms; deferring to recovery")
                stopNeedsRecovery = true
                if (!recordingFinalized.isCompleted) recordingFinalized.complete(Unit)
                return SegmentResult.Terminated
            }
            return if (success) SegmentResult.Success else SegmentResult.RetryableFailure

        } catch (e: CancellationException) {
            RovaLog.d("recordSegment: Cancelled")
            try { currentRecording?.stop() } catch (e2: Exception) {}
            currentRecording = null

            // C18: do NOT preemptively complete recordingFinalized. CameraX's
            // Finalize callback fires asynchronously after stop(); if we
            // complete here, stopPeriodicRecordingAndMerge will unblock and
            // snapshot pendingPersistJobs BEFORE the segment's persist job is
            // submitted by the real Finalize callback — silent data loss.
            //
            // Wait (within NonCancellable, since we're in a cancellation
            // handler) for either the real callback or a bounded timeout.
            // If timeout elapses, the segment is treated as orphaned: file
            // deleted, recordingFinalized synthesized so stop unblocks; no
            // persist job means the manifest does not record the orphan.
            val finalizedInTime = withContext(NonCancellable) {
                withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { recordingFinalized.await() }
            }
            if (finalizedInTime == null) {
                // CameraX did not fire Finalize in time. The segment file may
                // still be valid — slow NAND, encoder stalls, large segments
                // can push finalize past 5s on low-end devices. Per Phase 1.1
                // data-safety rule: do NOT delete (we don't actually know
                // it's invalid) and do NOT merge past it (would silently
                // truncate the user's recording). Defer to Phase 1.5 recovery
                // on next launch via the stopNeedsRecovery flag.
                RovaLog.w("recordSegment: cancelled segment did not finalize within ${FINALIZE_TIMEOUT_MS}ms; deferring to recovery")
                stopNeedsRecovery = true
                if (!recordingFinalized.isCompleted) recordingFinalized.complete(Unit)
            }
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            RovaLog.e("recordSegment: Exception: ${e.message}", e)

            // Same race fix as the cancellation path: wait for the real
            // Finalize callback before synthesizing completion. NonCancellable
            // is harmless here (parent not cancelled) but kept for symmetry.
            val finalizedInTime = withContext(NonCancellable) {
                withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { recordingFinalized.await() }
            }
            // Both branches set stopNeedsRecovery: the recording loop's retry
            // would write to the same segment_NNNN.mp4 path (segmentCount only
            // increments on success), which would overwrite either a possibly
            // valid late-finalizing file (timeout branch) or a confirmed file
            // with a persist job in flight (finalize-fired branch). End the
            // session cleanly and defer to Phase 1.5 recovery.
            if (finalizedInTime == null) {
                if (!recordingFinalized.isCompleted) recordingFinalized.complete(Unit)
                RovaLog.w("recordSegment: exception with no finalize callback within ${FINALIZE_TIMEOUT_MS}ms; deferring to recovery")
            } else {
                RovaLog.w("recordSegment: exception after finalize callback; deferring to recovery to avoid retry overwriting valid segment")
            }
            stopNeedsRecovery = true
            val msg = e.message ?: "Recording failed unexpectedly"
            updateNotification(msg)
            _serviceState.update { it.copy(recordingError = msg) }
            // ADR 0006 B-fix-4: was `return false` → outer treated as
            // RetryableFailure and reconfigured camera, overwriting a
            // possibly-recoverable segment file. Returning Terminated
            // breaks the loop; stopNeedsRecovery routes the merge skip.
            return SegmentResult.Terminated
        } finally {
            // Watchdog cancel — runs after EVERY exit:
            //   - normal `return success` (Finalize fired, persist submitted)
            //   - finalize-timeout `return false` with stopNeedsRecovery
            //   - CancellationException `throw e` (after bounded NonCancellable
            //     wait for the real Finalize callback)
            //   - Exception `return false` (after the same bounded wait)
            //   - early `failRecording` returns (watchdogArmed is false there;
            //     the cancel is a no-op)
            // Cancelling a non-existent slot is silent (FLAG_NO_CREATE).
            if (watchdogArmed && watchdogSid != null) {
                try {
                    AlarmScheduler.cancel(this, watchdogSid, TickKind.WATCHDOG)
                } catch (e: Exception) {
                    RovaLog.w("recordSegment: watchdog cancel failed for $watchdogSid", e)
                }
            }
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

    /**
     * Phase 1.3 — STOP action is gated on a non-null [sessionId].
     *
     * The init-window problem this solves: onStartCommand calls
     * startForeground BEFORE startPeriodicRecording's IO coroutine creates
     * the session. In that window currentSessionId is null and the
     * receiver requires EXTRA_SESSION_ID, so emitting a STOP action would
     * give the user a button that does nothing. We therefore:
     *
     * - First post (sessionId=null): no STOP action, "Initializing…" text.
     * - First update after createSession (currentSessionId non-null):
     *   STOP action attached, broadcast PI with EXTRA_SESSION_ID +
     *   identity-bearing Intent.data so per-session PIs do not collide.
     *
     * Init-window failure paths (storage exhaustion, controller-already-
     * registered, surface/camera failure) all reach stopForeground/stopSelf
     * before the user could meaningfully tap STOP — matching the no-action
     * notification.
     */
    private fun createNotification(title: String, contentText: String, sessionId: String? = null): Notification {
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)

        if (sessionId != null) {
            val stopIntent = Intent(this, RovaStopReceiver::class.java).apply {
                action = RovaStopReceiver.ACTION_STOP
                // Identity-bearing data — distinct PI per session so a
                // cached PendingIntent from a prior session never targets
                // the wrong sessionId.
                data = android.net.Uri.parse(
                    "${RovaStopReceiver.URI_SCHEME}://${RovaStopReceiver.URI_HOST_STOP}/$sessionId"
                )
                putExtra(RovaStopReceiver.EXTRA_SESSION_ID, sessionId)
            }
            // FLAG_UPDATE_CURRENT so re-posts after notification updates
            // refresh the extras (defensive — Intent.data already gives
            // unique identity, but UPDATE_CURRENT is the conservative pick
            // for broadcast PIs that carry extras).
            val stopPendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_media_pause, "STOP", stopPendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification(contentText: String) {
        // Phase 1.3 — pass the live sessionId so once a session exists the
        // STOP action is attached. notify(SAME_ID, ...) does not restart
        // the FGS deadline; the initial startForeground is sticky.
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            createNotification(LEGACY_NOTIFICATION_TITLE, contentText, currentSessionId)
        )
    }

    // Phase 3.1 — typed overload for the 4 mockup happy-path states.
    private fun updateNotification(state: NotificationState) {
        val copy = state.toCopy()
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            createNotification(copy.title, copy.body, currentSessionId)
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val state = _serviceState.value
        if (state.isPeriodicActive || state.isMerging) {
            RovaLog.w("onTaskRemoved: UI task removed while background session is active; keeping foreground service alive")
            setSurfaceProvider(null)
            return
        }

        RovaLog.d("onTaskRemoved: No active background work, stopping service")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseResources()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    private fun releaseResources() {
        recordingJob?.cancel()
        stopAndMergeJob?.cancel()
        try { currentRecording?.stop() } catch (e: Exception) {}
        currentRecording = null
        try { cameraProvider?.unbindAll() } catch (e: Exception) {}
        markCameraUnbound()  // Phase 3.5 — service teardown
        releaseDummySurface()
        releaseWakeLock()
        // Phase 1.2: belt-and-braces. Stop paths already call this; here it
        // covers onDestroy paths reached without going through stop (e.g. OS
        // teardown).
        cancelAlarmsAndUnregister()
        // C18: clear session state so a fresh service instance does not
        // accidentally inherit the prior session's identity.
        // sessionStore is now process-singleton in RovaApp — DO NOT close it
        // here (would cancel the persist scope used by the receiver too).
        currentSessionId = null
        currentSessionDir = null
        currentExportTier = null
        pendingPersistJobs.clear()
        stopNeedsRecovery = false
        // ADR 0006 B-fix-5: clear gate-fired stop reason on teardown so
        // a future service instance starts with a clean slate.
        currentStopReason = null
        // Close the tick channel so any pending receive() throws and the
        // recording loop unwinds cleanly.
        tickSignals.close()
        serviceScope.cancel()
        _serviceState.update { RovaServiceState() }
        RovaLog.d("releaseResources: Resources released")
    }

    private fun stopPeriodicRecordingAndMerge() {
        if (stopRequested) {
            RovaLog.d("stopPeriodicRecordingAndMerge: stop already requested, ignoring duplicate call")
            return
        }
        stopRequested = true
        acquireWakeLock()
        recordingJob?.cancel()
        _serviceState.update { it.copy(isPeriodicActive = false, isRecording = false) }

        try { currentRecording?.stop() } catch (e: Exception) { e.printStackTrace() }
        currentRecording = null

        stopAndMergeJob?.cancel()
        stopAndMergeJob = serviceScope.launch {
            // R2: Wait for the Finalize callback before reading the manifest.
            // H-1: Capture reference locally to avoid race with recordSegment replacing the field.
            // C18: timeout MUST exceed FINALIZE_TIMEOUT_MS so the synthesized
            // completion in recordSegment's cancel-catch (timeout case) reaches
            // us before our own timeout expires; otherwise we'd snapshot
            // pendingPersistJobs prematurely and silently drop a finalized
            // segment from the merge.
            val finalized = recordingFinalized
            withTimeoutOrNull(STOP_FINALIZE_AWAIT_MS) { finalized.await() }

            // ============================================================
            // ADR 0006 §"Terminal-Write Ordering" (B3 + B9) — EAGER
            // USER_STOPPED WRITE BEFORE MERGE
            // ============================================================
            //
            // For all USER_STOPPED flavors (notification STOP, UI stop,
            // gate-fired permission/storage termination), the manifest
            // terminal value is written BEFORE merge starts. ADR 0005's
            // `T == COMPLETED ⇔ merged artifact exists` invariant is
            // preserved because COMPLETED is still written ONLY at
            // merge-success commit point in performMerge's finally
            // (B7 carve-out).
            //
            // B9 caller contract on Failed: skip merge, surface a
            // degraded-state notification, log via RovaCrashReporter,
            // then stopForeground+stopSelf. Phase 1.5 cold-launch
            // classifies the residual session.
            if (userStopRequested) {
                val sid = currentSessionId
                if (sid != null) {
                    val reason = currentStopReason ?: com.aritr.rova.data.StopReason.USER
                    val result = sessionStore.markTerminated(
                        sid, Terminated.USER_STOPPED, reason
                    )
                    when (result) {
                        is com.aritr.rova.data.MarkTerminatedResult.Wrote -> {
                            RovaLog.d(
                                "stopPeriodicRecordingAndMerge: eager USER_STOPPED/$reason" +
                                    " written for $sid (B3)"
                            )
                            updateNotification(
                                when (reason) {
                                    com.aritr.rova.data.StopReason.PERMISSION_REVOKED ->
                                        "Stopped — required permission revoked. Re-grant in Settings."
                                    com.aritr.rova.data.StopReason.LOW_STORAGE ->
                                        "Stopped — device storage low. Free up space."
                                    else -> "Stopping recording…"
                                }
                            )
                        }
                        is com.aritr.rova.data.MarkTerminatedResult.AlreadyTerminal -> {
                            RovaLog.d(
                                "stopPeriodicRecordingAndMerge: $sid already" +
                                    " ${result.existingTerminated}/${result.existingStopReason}; eager write suppressed"
                            )
                        }
                        is com.aritr.rova.data.MarkTerminatedResult.Failed -> {
                            // ADR 0006 §"Caller contract on `Failed`":
                            // DO NOT proceed with merge. Merge would
                            // consume more disk and almost certainly fail
                            // under the same conditions. Defer to Phase
                            // 1.5 cold-launch classification.
                            RovaLog.e(
                                "stopPeriodicRecordingAndMerge: eager markTerminated FAILED" +
                                    " for $sid (attempts=${result.attempts}); skipping merge",
                                result.cause
                            )
                            RovaCrashReporter.recordException(
                                result.cause,
                                "stopPeriodicRecordingAndMerge eager markTerminated Failed"
                            )
                            updateNotification(
                                "Stopped — recording state could not be saved." +
                                    " Will recover on next launch."
                            )
                            cancelAlarmsAndUnregister()
                            @Suppress("DEPRECATION")
                            stopForeground(true)
                            stopSelf()
                            return@launch
                        }
                    }
                }
            }

            // C18: if a cancellation-path finalize timeout fired, the active
            // segment is in an unknown state — file possibly valid but not
            // confirmed by Finalize. Skip merge entirely; defer the session
            // to Phase 1.5 recovery on next launch. Merging "everything except
            // the last segment" would silently truncate the user's recording
            // without their consent.
            if (stopNeedsRecovery) {
                RovaLog.w("stopPeriodicRecordingAndMerge: session $currentSessionId has an unconfirmed segment; skipping merge — recovery available on next launch")
                updateNotification("Recording stopped — finishing will be offered when you reopen Rova")
                // Phase 1.2: cancel before unregister; leave terminated=null
                // so Phase 1.5 recovery classifies based on segment state.
                cancelAlarmsAndUnregister()
                @Suppress("DEPRECATION")
                stopForeground(true)
                stopSelf()
                return@launch
            }

            // C18: wait for ALL pending segment-persist jobs (sha1 + appendSegment)
            // before reading the manifest. No timeout — the work is bounded by
            // file size and serialized through the persist dispatcher. A
            // timeout here would silently drop finalized segments from merge.
            // awaitAll rethrows any persist failure; surface it to the catch
            // block below as a merge failure.
            val snapshot = pendingPersistJobs.toList()
            try {
                snapshot.awaitAll()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                RovaLog.e("stopPeriodicRecordingAndMerge: segment persist failed", e)
                _serviceState.update { it.copy(mergeError = "Failed to persist segment: ${e.message}") }
                updateNotification("Recording state corrupted: ${e.message?.take(60)}")
                cancelAlarmsAndUnregister()
                @Suppress("DEPRECATION")
                stopForeground(true)
                stopSelf()
                return@launch
            }

            val sessionId = currentSessionId
            val sessionDir = currentSessionDir
            val segments: List<File> = if (sessionId != null && sessionDir != null) {
                val manifest = withContext(Dispatchers.IO) { sessionStore.loadManifest(sessionId) }
                manifest?.segments
                    ?.map { File(sessionDir, it.filename) }
                    ?.filter { it.exists() && it.length() > 0 }
                    ?: emptyList()
            } else emptyList()

            if (segments.isNotEmpty()) {
                performMerge(segments)
            } else {
                cancelAlarmsAndUnregister()
                @Suppress("DEPRECATION")
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private suspend fun performMerge(segments: List<File>) {
        var mergeSucceeded = false
        try {
            _serviceState.update { it.copy(isMerging = true, mergeProgress = 0f, mergeError = null) }
            updateNotification(NotificationState.Merging(done = 0, total = segments.size))

            // Phase 1.7 commit-7 — single live entry into the tier
            // export pipeline. ExportPipeline.export dispatches by
            // currentExportTier(): Tier 1 publishes via MediaStore
            // pending row + MediaMuxer(FileDescriptor); Tier 2/3 mux to
            // a private temp + .part rename + bounded scanFile. The
            // exporter writes manifest export-state transitions; this
            // service only writes the terminal record (B7).
            val sid = currentSessionId
            val sessionDir = currentSessionDir
            if (sid == null || sessionDir == null) {
                RovaLog.w("performMerge: no active session; skipping export")
                _serviceState.update { it.copy(mergeError = "No active session") }
                return
            }
            if (!sessionDir.exists()) sessionDir.mkdirs()

            val result = ExportPipeline.export(
                context = this@RovaRecordingService,
                sessionStore = sessionStore,
                sessionId = sid,
                sessionDir = sessionDir,
                segments = segments
            ) { progress ->
                _serviceState.update { it.copy(mergeProgress = progress) }
                updateNotification(NotificationState.Merging((progress * segments.size).toInt(), segments.size))
            }

            when (result) {
                is ExportResult.Success -> {
                    updateNotification(NotificationState.MergeComplete(clipCount = segments.size))
                    withContext(Dispatchers.IO) {
                        segments.forEach {
                            if (!coroutineContext.isActive) throw CancellationException("Post-merge cleanup cancelled")
                            try { it.delete() } catch (_: Exception) {}
                        }
                    }
                    mergeSucceeded = true
                    delay(1000)
                }
                is ExportResult.MuxFailed,
                is ExportResult.CopyFailed,
                is ExportResult.RenameFailed,
                is ExportResult.PendingInsertFailed,
                is ExportResult.FinalizeFailed,
                is ExportResult.ManifestWriteFailed,
                is ExportResult.UnknownSession -> {
                    // No terminal write — manifest is in FAILED (or
                    // intermediate) state. Cold-launch ExportRecoveryRunner
                    // reconciles on next launch (ADR 0003 §Recovery
                    // routing); Phase 1.5 maps the resulting state to a
                    // discard-eligibility flag.
                    val msg = "Export failed: ${result.javaClass.simpleName}"
                    RovaLog.e("performMerge: $msg")
                    _serviceState.update { it.copy(mergeError = msg) }
                    updateNotification("Merge failed")
                    delay(3000)
                }
            }

        } catch (ce: CancellationException) {
            // C6: keep cancellation semantics intact — must not be converted
            // into a merge-failure UI state. finally still tears the service
            // down, which is the correct response to cancellation here.
            throw ce
        } catch (e: Exception) {
            e.printStackTrace()
            _serviceState.update { it.copy(mergeError = e.message) }
            updateNotification("Merge failed: ${e.message}")
            delay(3000)
        } finally {
            _serviceState.update { it.copy(isMerging = false) }
            // Phase 1.2 / 1.3: write the terminal record *before* unregister
            // and stopForeground/stopSelf, but only if merge actually
            // succeeded. Discriminator (Phase 1.3): userStopRequested
            // distinguishes user-driven stop (USER_STOPPED) from natural
            // completion (COMPLETED). The flag is set by SessionController.
            // requestStop on the same serviceScope before this coroutine's
            // merge work runs — by the time we reach this finally block on
            // a successful merge, the flag is stable.
            //
            // markTerminated runs on the SessionStore persist dispatcher in
            // NonCancellable so a service-teardown cancellation cannot abort
            // the terminal record mid-write. markTerminated is also first-
            // writer-wins, so a racing receiver-side USER_STOPPED (from a
            // duplicate broadcast) is harmless.
            if (mergeSucceeded) {
                val sid = currentSessionId
                if (sid != null) {
                    // ADR 0006 §"Migration table" + B7 carve-out:
                    // - userStopRequested → USER_STOPPED + USER (the user
                    //   pressed STOP; merge ran to completion as best-effort).
                    // - natural completion → COMPLETED + NONE (merge-success
                    //   commit point per B7 — the ONLY site where COMPLETED
                    //   may be written).
                    val reason: Terminated
                    val stopReason: com.aritr.rova.data.StopReason
                    if (userStopRequested) {
                        reason = Terminated.USER_STOPPED
                        stopReason = com.aritr.rova.data.StopReason.USER
                    } else {
                        reason = Terminated.COMPLETED
                        stopReason = com.aritr.rova.data.StopReason.NONE
                    }
                    try {
                        withContext(NonCancellable) {
                            when (val result = sessionStore.markTerminated(sid, reason, stopReason)) {
                                is com.aritr.rova.data.MarkTerminatedResult.Wrote -> {
                                    RovaLog.d("performMerge: wrote $reason / $stopReason for $sid")
                                }
                                is com.aritr.rova.data.MarkTerminatedResult.AlreadyTerminal -> {
                                    RovaLog.d(
                                        "performMerge: $sid already" +
                                            " ${result.existingTerminated}/${result.existingStopReason};" +
                                            " merge-success suppressed"
                                    )
                                }
                                is com.aritr.rova.data.MarkTerminatedResult.Failed -> {
                                    // ADR 0006 §"Caller contract on `Failed`
                                    // for `COMPLETED`": merged artifact
                                    // already exists; Phase 1.7 reconciles.
                                    // Surface degraded notification.
                                    RovaLog.e(
                                        "performMerge: markTerminated($reason) FAILED" +
                                            " for $sid (attempts=${result.attempts})", result.cause
                                    )
                                    updateNotification(
                                        "Recording finished but state save failed —" +
                                            " will reconcile on next launch."
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        RovaLog.e("performMerge: markTerminated($reason) threw for $sid", e)
                    }
                }
            }
            cancelAlarmsAndUnregister()
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
        }
    }

    // Q3: short beep on recording start/stop using rova_beep.mp3.
    //
    // Beta-smoke fix v2: bleed-prevention is now timing, not blanket
    // suppression. `beepStart` suspends until the speaker has fully
    // decayed before returning, so the next call (recordSegment) does
    // not open the mic on top of a still-radiating tone. `beepEnd` is
    // fire-and-forget — the mic is OFF when it runs, so no capture
    // path exists. For interval == 0 (continuous), the policy
    // suppresses both ends entirely because there is no natural gap
    // to hide a synchronous-await playback inside.
    //
    // Constants:
    // - BEEP_PLAYBACK_TIMEOUT_MS: defensive ceiling on the await.
    //   `rova_beep.mp3` is ~300 ms; any device that exceeds 1.5 s is
    //   broken in a way no amount of waiting will fix.
    // - BEEP_TAIL_MARGIN_MS: acoustic decay buffer past
    //   onCompletion. CameraX recorder.start() is the next thing to
    //   run; this margin keeps mic-open strictly after speaker-quiet.
    private suspend fun beepStart(intervalMinutes: Int) {
        if (!com.aritr.rova.service.audio.shouldPlayBeep(
                enableBeeps = RovaSettings(this).enableBeeps,
                audioMode = currentAudioMode,
                intervalMinutes = intervalMinutes
            )
        ) return
        val mp = try {
            MediaPlayer.create(this, R.raw.rova_beep) ?: return
        } catch (e: Exception) {
            RovaLog.w("beepStart: create failed", e)
            return
        }
        val done = CompletableDeferred<Unit>()
        mp.setOnCompletionListener {
            try { it.release() } catch (_: Throwable) {}
            done.complete(Unit)
        }
        try {
            mp.start()
            withTimeoutOrNull(BEEP_PLAYBACK_TIMEOUT_MS) { done.await() }
            delay(BEEP_TAIL_MARGIN_MS)
        } catch (e: Exception) {
            RovaLog.w("beepStart: playback failed", e)
            try { mp.release() } catch (_: Throwable) {}
        }
    }

    private fun beepEnd(intervalMinutes: Int) {
        if (!com.aritr.rova.service.audio.shouldPlayBeep(
                enableBeeps = RovaSettings(this).enableBeeps,
                audioMode = currentAudioMode,
                intervalMinutes = intervalMinutes
            )
        ) return
        try {
            val mp = MediaPlayer.create(this, R.raw.rova_beep) ?: return
            mp.setOnCompletionListener {
                try { it.release() } catch (_: Throwable) {}
            }
            mp.start()
        } catch (e: Exception) {
            RovaLog.w("beepEnd: failed", e)
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

    /**
     * Phase 1.6 (ROADMAP_v6 §1.6 / ADR 0003 / risk C7) — session-level
     * peak budget. Tier-aware: capture+final on Tier 1 (2×), or
     * capture+private+public on Tier 2/3 (3×). Indefinite-loop sessions
     * (`limitLoops == -1`) reserve [com.aritr.rova.data.StorageEstimator.INDEFINITE_LOOP_PREFLIGHT_HORIZON]
     * loops; the per-segment gate is the authoritative backstop beyond
     * that horizon.
     *
     * Pure delegate to [com.aritr.rova.data.StorageEstimator.estimatePeakBytes].
     * Preflight runs BEFORE `createSession`, so the [tier] argument
     * comes from the shared top-level [com.aritr.rova.data.currentExportTier]
     * helper — the same `Build.VERSION.SDK_INT` lookup
     * `SessionStore.createSession` performs moments later. The
     * service-side cache [currentExportTier] is populated only AFTER
     * `createSession` returns and is consumed by the per-segment gate,
     * not by this preflight.
     */
    private fun estimatePeakBytes(tier: com.aritr.rova.data.ExportTier): Long =
        com.aritr.rova.data.StorageEstimator.estimatePeakBytes(
            durationSeconds = nSeconds,
            loopCount = limitLoops,
            resolution = resolutionStr,
            tier = tier,
            mode = currentMode
        )

    private fun hasEnoughStorage(peakBytesRequired: Long): Boolean {
        return try {
            // ADR 0006 B21: read the canonical external root from
            // RovaApp.externalRoot (single resolution per process). If
            // null, fail-closed: cannot record without external storage.
            val path = (applicationContext as RovaApp).externalRoot?.absolutePath
                ?: run {
                    RovaLog.e("hasEnoughStorage: external storage unavailable")
                    return false
                }
            val stat = StatFs(path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            val required = peakBytesRequired + 50 * 1024 * 1024L
            if (available < required) {
                RovaLog.w("hasEnoughStorage: Available=${available / 1024 / 1024}MB, Required=${required / 1024 / 1024}MB")
            }
            available >= required
        } catch (e: Exception) {
            // ADR 0006 row 3 / B21 (B-fix-2) — fail-closed. Proceeding
            // optimistically into createSession on a StatFs / storage
            // probe failure can mean the session writes to disk that
            // disappears mid-mux, leaking partial files and possibly
            // hanging the FGS. Failing closed here triggers row 3
            // cleanup (stopForeground + stopSelf, no manifest).
            RovaLog.e("hasEnoughStorage: Check failed; failing closed (B-fix-2)", e)
            false
        }
    }

    /**
     * Phase 1.2: alarm-driven inter-segment wait. Replaces the prior
     * delay()-only path which lost time during Doze. Flow:
     *
     * 1. Allocate a fresh seq.
     * 2. Arm a WAKE alarm at `now + waitSeconds*1000` (per ADR 0001 the
     *    scheduler chooses exact-vs-inexact at the call site).
     * 3. Start an in-process delay that posts the same seq into
     *    [tickSignals] when the service is still alive. This is the normal
     *    foreground/live-process path.
     * 4. Suspend on [tickSignals.receive] inside [withTimeoutOrNull] with a
     *    drift envelope of `triggerAt + WAKE_DRIFT_BUDGET_MS`.
     * 5. On drift-budget timeout the alarm was OEM-suppressed — re-arm once
     *    and wait another budget. On a SECOND timeout we give up the wait
     *    and fall through; the outer loop continues to the next segment.
     *
     * Bounded-wait rationale: MIUI / Samsung / deep-battery modes can
     * silently drop `setAndAllowWhileIdle` (and even `setExactAndAllowWhileIdle`
     * once revoked). Without a timeout the recording session would stall
     * indefinitely while the FGS holds the camera — worse than continuing
     * with drift.
     *
     * Why both local delay and alarm: the live service must not depend on
     * broadcast delivery to progress to the next clip. The local delay is
     * cheap and precise while the process is alive. The WAKE alarm remains
     * the cross-process recovery path: if the process is killed during the
     * wait, the local delay dies with it, the pending WAKE alarm fires later,
     * and the receiver writes KILLED_BY_SYSTEM via the empty-controller
     * branch. The WATCHDOG kind covers the *recording* window in
     * [recordSegment].
     *
     * Wakelock is released for waits long enough to benefit; alarms use
     * RTC_WAKEUP. Notification countdown runs as a best-effort child job.
     */
    private suspend fun waitForNextSegment(waitSeconds: Int) {
        if (waitSeconds <= 0) return
        val sessionId = currentSessionId
        if (sessionId == null) {
            RovaLog.w("waitForNextSegment: no active sessionId; falling back to plain delay")
            delay(waitSeconds * 1000L)
            return
        }

        val seq = nextTickSeq++
        val triggerAt = System.currentTimeMillis() + waitSeconds * 1000L
        AlarmScheduler.arm(this, sessionId, seq, triggerAt, TickKind.WAKE)

        val liveDelayJob = serviceScope.launch {
            delay(waitSeconds * 1000L)
            val ok = tickSignals.trySend(seq).isSuccess
            if (ok) {
                RovaLog.d("waitForNextSegment: live delay delivered seq=$seq after ${waitSeconds}s")
            } else {
                RovaLog.w("waitForNextSegment: live delay could not deliver seq=$seq; channel closed")
            }
        }

        val canRelax = shouldRelaxWakeLock(waitSeconds)
        if (canRelax) {
            releaseWakeLock()
            RovaLog.d("waitForNextSegment: wakelock released during ${waitSeconds}s alarm-driven wait")
        }

        // Best-effort notification countdown. May be paused by Doze; the
        // alarm wake is the source of truth for resuming the loop.
        val countdownJob = serviceScope.launch {
            var remaining = waitSeconds
            while (remaining > 0 && kotlinx.coroutines.currentCoroutineContext().isActive) {
                val step = minOf(remaining, WAKE_LOCK_IDLE_UPDATE_STEP_SECONDS)
                _serviceState.update { it.copy(nextRecordingCountdown = remaining.toLong()) }
                updateNotification(NotificationState.GapWaiting(segmentCount + 1, formatCountdownSeconds(remaining), limitLoops.takeIf { it != -1 }))
                delay(step * 1000L)
                remaining -= step
            }
        }

        try {
            var rearmed = false
            while (true) {
                val now = System.currentTimeMillis()
                // First window: until original trigger + drift budget.
                // After re-arm: another full drift budget from now.
                val deadline = if (rearmed) now + WAKE_DRIFT_BUDGET_MS
                else triggerAt + WAKE_DRIFT_BUDGET_MS
                val timeoutMs = (deadline - now).coerceAtLeast(0L)

                val received = withTimeoutOrNull(timeoutMs) {
                    // Drop stale ticks (a prior arm fired late after we
                    // bumped seq). Receiver-side sessionId filtering already
                    // excludes other sessions, so any tick here is ours.
                    var s: Int
                    do {
                        s = tickSignals.receive()
                        if (s < seq) RovaLog.d("waitForNextSegment: discarding stale tick seq=$s (waiting for >= $seq)")
                    } while (s < seq)
                    s
                }
                if (received != null) break

                if (rearmed) {
                    RovaLog.w(
                        "waitForNextSegment: alarm dropped twice for seq=$seq sid=$sessionId" +
                            " (OEM suppression suspected); ending wait, advancing to next segment"
                    )
                    break
                }
                rearmed = true
                val rearmTrigger = System.currentTimeMillis() + WAKE_REARM_OFFSET_MS
                try {
                    AlarmScheduler.arm(this, sessionId, seq, rearmTrigger, TickKind.WAKE)
                    RovaLog.w(
                        "waitForNextSegment: drift budget elapsed for seq=$seq sid=$sessionId," +
                            " re-armed once at +${WAKE_REARM_OFFSET_MS}ms"
                    )
                } catch (e: Exception) {
                    RovaLog.w("waitForNextSegment: re-arm failed", e)
                }
            }
        } finally {
            liveDelayJob.cancel()
            try {
                AlarmScheduler.cancel(this, sessionId, TickKind.WAKE)
            } catch (e: Exception) {
                RovaLog.w("waitForNextSegment: wake cancel failed for $sessionId", e)
            }
            countdownJob.cancel()
            // Phase 1.8 / C17 (review round 2): always re-call
            // acquireWakeLock at the end of each inter-segment wait.
            // - Relax path (canRelax == true): re-acquires a fresh
            //   lock that was released for the long wait.
            // - Non-relax path: refreshes the bounded timeout so
            //   continuous short-interval sessions cannot exceed
            //   ACQUIRE_TIMEOUT_MS without a refresh point.
            // Both paths converge through acquireWakeLock's held-branch
            // refresh (round-2 fix above).
            acquireWakeLock()
        }
    }

    private suspend fun countdownWithWakeLock(waitSeconds: Int) {
        acquireWakeLock()
        for (i in waitSeconds downTo 1) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
            updateNotification(NotificationState.GapWaiting(segmentCount + 1, "${i}s", limitLoops.takeIf { it != -1 }))
            _serviceState.update { it.copy(nextRecordingCountdown = i.toLong()) }
            delay(1000)
        }
    }

    private fun shouldRelaxWakeLock(waitSeconds: Int): Boolean {
        if (waitSeconds < WAKE_LOCK_RELAX_THRESHOLD_SECONDS) return false

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoringOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)
        if (!ignoringOptimizations) {
            RovaLog.d("shouldRelaxWakeLock: Keeping wakelock because battery optimizations are still active")
        }
        return ignoringOptimizations
    }

    private fun formatCountdownSeconds(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    private fun acquireWakeLock() {
        val existing = wakeLock
        if (existing?.isHeld == true) {
            // Phase 1.8 / C17 (review round 2): refresh the bounded
            // timeout on every call. With setReferenceCounted(false),
            // a repeat acquire(timeout) on a held lock simply extends
            // its expiry — it does not stack. Without this refresh,
            // continuous sessions whose inter-segment gap is below
            // WAKE_LOCK_RELAX_THRESHOLD_SECONDS would never hit the
            // release/re-acquire branch and could outlive
            // ACQUIRE_TIMEOUT_MS, silently losing the wakelock.
            existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:RovaRecording"
        ).apply {
            setReferenceCounted(false)
            // Phase 1.8 / C17 — bounded acquire. Timeout rationale and
            // refresh model live in [WakeLockPolicy.ACQUIRE_TIMEOUT_MS].
            acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)
        }
        RovaLog.d("acquireWakeLock: Partial wakelock acquired")
    }

    private fun releaseWakeLock() {
        val existing = wakeLock
        // Phase 1.8 / C17 — null-first guarantees the post-state even
        // if the underlying release throws (already swallowed below).
        wakeLock = null
        if (existing != null && existing.isHeld) {
            // Phase 1.8 / C17 — exception-safe release. Android may
            // throw RuntimeException("WakeLock under-locked"); see
            // ADR 0006 §"WakeLock Ownership".
            WakeLockPolicy.safeRelease { existing.release() }
            RovaLog.d("releaseWakeLock: Partial wakelock released")
        }
    }
}

/**
 * Phase 1.4 (ADR 0006 B11). Outcome of a caller-side
 * [RovaRecordingService.start] attempt. UI surfaces [Blocked] reasons
 * directly to the user; no session manifest is written for any
 * non-[Started] outcome.
 */
sealed class StartResult {
    data object Started : StartResult()
    data class Blocked(val reason: StartBlocked) : StartResult()
}

enum class StartBlocked {
    /** App not in foreground / not in approved exemption. */
    APP_NOT_VISIBLE,
    /**
     * Android 12+ caller-side `ForegroundServiceStartNotAllowedException`.
     * Show "Cannot start recording — app must be in foreground."
     */
    FGS_RESTRICTED,
    /** Other [IllegalStateException] from `startForegroundService`. Rare. */
    UNKNOWN_ISE
}

/**
 * The actual recorded length (ms) of a finalized segment, derived from
 * CameraX's `RecordingStats.recordedDurationNanos`. Falls back to the
 * configured clip length only when the stat is non-positive (defensive
 * — should not happen on a successful finalize, but a bogus 0/negative
 * stat must not be persisted as a 0 ms / negative segment while the
 * file on disk has real bytes).
 *
 * Top-level + `internal` (rather than a `private` member) so the JVM
 * unit suite can pin the ns→ms conversion + fallback without
 * constructing the `Service` — same posture as
 * [com.aritr.rova.service.wakelock.WakeLockPolicy].
 */
internal fun recordedSegmentDurationMs(
    recordedDurationNanos: Long,
    configuredFallbackMs: Long
): Long {
    val fromStats = recordedDurationNanos / 1_000_000L // ns -> ms
    return if (fromStats > 0L) fromStats else configuredFallbackMs
}

/**
 * Phase 6 — Mode picker.
 * Derive CameraX target rotation from the display's natural rotation
 * plus the user-chosen Mode. Portrait = identity; Landscape = quarter-
 * turn clockwise. Mirrors the integer arithmetic `Surface.ROTATION_*`
 * use (0/1/2/3) so the math handles devices whose natural orientation
 * is non-portrait (tablets) correctly.
 *
 * `internal` (not `private`) so JVM tests in the same module can reach
 * the helper without Robolectric — Phase 3.5 PR #10 gotcha.
 */
internal fun computeTargetRotation(displayRotation: Int, mode: String): Int {
    val base = when (displayRotation) {
        android.view.Surface.ROTATION_0,
        android.view.Surface.ROTATION_90,
        android.view.Surface.ROTATION_180,
        android.view.Surface.ROTATION_270 -> displayRotation
        else -> android.view.Surface.ROTATION_0
    }
    return if (mode == "Landscape") (base + 1) % 4 else base
}
