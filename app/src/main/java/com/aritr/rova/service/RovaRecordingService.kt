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
import android.graphics.Color
import android.view.OrientationEventListener
import android.view.View
import android.widget.RemoteViews
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
import androidx.camera.core.UseCaseGroup
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import androidx.lifecycle.ProcessLifecycleOwner
import com.aritr.rova.MainActivity
import com.aritr.rova.R
import com.aritr.rova.RovaApp
import com.aritr.rova.service.notification.ChronoSpec
import com.aritr.rova.service.notification.DotState
import com.aritr.rova.service.notification.DotsPlan
import com.aritr.rova.service.notification.NotificationActionKey
import com.aritr.rova.service.notification.NotificationActionSpec
import com.aritr.rova.service.notification.NotificationBindPlan
import com.aritr.rova.service.notification.NotificationChannelConfig
import com.aritr.rova.service.notification.NotificationProgress
import com.aritr.rova.service.notification.NotificationState
import com.aritr.rova.service.notification.toActionSpecs
import com.aritr.rova.service.notification.toBindPlan
import com.aritr.rova.service.orientation.DEFAULT_ORIENTATION_POLICY_IS_AUTO
import com.aritr.rova.service.orientation.OrientationSnapState
import com.aritr.rova.service.orientation.firstSampleFallback
import com.aritr.rova.service.orientation.snapOrientation
import com.aritr.rova.service.notification.toChannelId
import com.aritr.rova.service.notification.isDismissible
import com.aritr.rova.service.scheduler.AlarmScheduler
import com.aritr.rova.ui.text.resolve
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
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
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
    // Bug A — the real saved-clip count, set in performMerge / performMergeDual
    // on the export-success path. Feeds the post-merge summary card so an early
    // user-stop (segmentCount still 0, but the partial clip IS saved) reads the
    // true count instead of "0 clips saved to library".
    val exportedClipCount: Int = 0,
    // Bug A — published BEFORE isMerging flips true so the in-merge "Merging
    // clip X of Y" band shows the real total even on an early stop (segmentCount
    // is still 0 there because the loop never completed an iteration).
    val mergeClipCount: Int = 0,
    val mergeError: String? = null,
    // B4c — true when the export failed because the custom SAF save folder is
    // gone/unwritable. Lets the record screen show an accurate "folder
    // unavailable, recording kept" message instead of the generic merge-failed
    // "Open Library to recover" copy (there is no Library entry for this case).
    val saveFolderUnavailable: Boolean = false,
    val recordingError: String? = null,
    val isCameraActive: Boolean = false,
    // B6 — true when the bound selector is the front camera. Drives the
    // record-screen flip-button icon/contentDescription swap. Updated after the
    // onCreate lens load, every flipCamera, and the setMode P+L snap-to-rear.
    val isFrontCamera: Boolean = false,
    // B6 (codex review) — whether the device exposes a front sensor. Gates the
    // flip button so a front-less device never shows a dead toggle (which would
    // also latch the "Switching…" overlay on a no-op tap). Set once in onCreate.
    val hasFrontCamera: Boolean = false,
    // Bug 3 — true ONLY while a real cold camera-acquire bind is in flight
    // (inside setupSingleCamera/setupDualCamera, between the start of the bind
    // body and every exit). The "Initializing Camera" overlay reads this flag
    // instead of an isCameraActive composition edge, so a warm nav-return
    // surface re-swap (RecordScreen re-enters composition, recreates PreviewView)
    // can no longer flash a spinner despite the camera staying warm (ADR-0021).
    // MUST be cleared on every setup exit path or the overlay latches on — see
    // the try/finally in setupSingleCamera/setupDualCamera.
    val coldAcquireInProgress: Boolean = false,
    // Camera-flip regression (device-confirmed 2026-06-08) — MONOTONIC counter,
    // incremented on every successful camera (re)bind (setupSingleCamera /
    // setupDualCamera success path). The record screen clears its front/back
    // "Switching…" overlay latch by observing this advance, NOT an isCameraActive
    // edge: a flip pulses isCameraActive true→false→true and the ~54 ms `false`
    // is dropped by StateFlow conflation, so an edge-keyed clear never re-fires
    // and the overlay latches on forever. A monotonic value cannot be hidden by
    // conflation — the END value the collector lands on is always greater than
    // the one captured at the flip tap. See FlipLatchPolicy.
    val cameraConfigGeneration: Long = 0L,
    // PR-α (ADR-0029 §Decision 3) — orientation HUD. currentSegmentRotation is
    // frozen at the active segment's start; pendingNextRotation is the latest stable
    // snapped rotation. When they differ the HUD shows "next clip will rotate".
    val currentSegmentRotation: Int = android.view.Surface.ROTATION_0,
    val pendingNextRotation: Int = android.view.Surface.ROTATION_0,
)

/**
 * Phase: render-architecture audit. Container for the two intrinsics
 * queried from `CameraCharacteristics` at `setupDualCamera` time. Spec §2.6.
 */
internal data class DualCameraIntrinsics(
    val size: android.util.Size,
    val sensorOrientation: Int,
)

/**
 * SAFE ASSUMPTION for portrait-natural phones (typical rear-camera mount).
 * NOT universally correct — some tablets/foldables have 0 or 180. Hit only
 * when CameraManager query fails or SENSOR_ORIENTATION is null
 * (Camera2 spec guarantees non-null per CameraCharacteristics docs, but
 * defensive).
 */
internal const val SENSOR_ORIENTATION_FALLBACK = 90

/**
 * Per spec §4.1 — RELEASE soft-fallback for OEM-bug SENSOR_ORIENTATION
 * values. DEBUG ctor `require` in EglRouter will fail-fast on illegal
 * values reaching it; RELEASE pre-sanitizes via this function to avoid
 * camera-startup crash.
 *
 * Logs WARN on fallback so QA + crash analytics surface the OEM quirk.
 */
internal fun sanitizeSensorOrientation(raw: Int): Int = when (raw) {
    0, 90, 180, 270 -> raw
    else -> {
        com.aritr.rova.utils.RovaLog.w(
            "SENSOR_ORIENTATION out-of-spec ($raw); falling back to $SENSOR_ORIENTATION_FALLBACK"
        )
        SENSOR_ORIENTATION_FALLBACK
    }
}

class RovaRecordingService : Service(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private var cameraProvider: ProcessCameraProvider? = null
    private var recordingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentRecording: Recording? = null
    private var segmentCount = 0
    private val setupMutex = kotlinx.coroutines.sync.Mutex()
    // ADR-0021 — camera warm across in-app navigation.
    // `appForeground` tracks the process foreground/background state
    // (NOT the per-screen NavBackStackEntry lifecycle, which fires ON_STOP
    // on in-app tab switches while the app is still foreground). Written on
    // the main thread by `processObserver`; read on the main thread inside
    // the `startCameraPreview` coroutine (serviceScope is Dispatchers.Main).
    // @Volatile is belt-and-suspenders against future dispatcher changes.
    @Volatile private var appForeground = true
    // In-flight idle-preview acquisition (set in startCameraPreview, cancelled
    // + nulled in stopCameraPreview); cancelling on release ensures a coroutine
    // suspended in the surface-grace window cannot bind after the app has
    // backgrounded.
    private var previewStartJob: Job? = null
    // Process-global foreground/background observer. ON_START only flips the
    // flag (acquisition stays screen-driven so foregrounding onto a non-camera
    // tab does not wake the camera); ON_STOP releases the idle preview
    // (stopCameraPreview is isPeriodicActive-guarded — recording is untouched).
    // Stored instance (not anonymous) so onDestroy removes this exact observer.
    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appForeground = true
        }
        override fun onStop(owner: LifecycleOwner) {
            appForeground = false
            stopCameraPreview()
        }
    }
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
    // PR-α (ADR-0029 §Decision 2,3) — device-orientation seam. Listener feeds
    // snapOrientation; orientationSnapState holds opaque hysteresis state. Enabled
    // only for the Single path on bind success; disabled on every unbind/teardown.
    // DualShot (P+L) owns its own rotation (ADR-0009) and never enables this.
    // THREADING (codex review): OrientationEventListener.enable() registers with no
    // Handler, so onOrientationChanged is delivered on the MAIN Looper — the same
    // thread as the segment loop and CameraX finalize callbacks. The three vars below
    // are therefore main-Looper-confined (no @Volatile/lock). Do NOT move the segment
    // loop off Dispatchers.Main or pass a background Handler to enable() without
    // revisiting this — both would turn these plain vars into a data race.
    private var orientationListener: OrientationEventListener? = null
    private var orientationSnapState: OrientationSnapState =
        OrientationSnapState(stable = android.view.Surface.ROTATION_0, candidate = null, candidateSinceMs = null)
    // hasStableSnap flips true once the listener delivers a non-UNKNOWN sample, so
    // segment start can tell a real snap from the seed and fall back deterministically.
    // lastEffectiveTargetRotation carries the PRIOR segment's persisted rotation forward.
    private var hasStableSnap: Boolean = false
    private var lastEffectiveTargetRotation: Int? = null
    // Phase 6.1b — dual recorder + dual recording handle for P+L mode.
    // Mirrors videoCapture / currentRecording lifecycle 1:1 for the dual
    // path. Released on service teardown.
    private var currentDualRecorder: com.aritr.rova.service.dualrecord.DualVideoRecorder? = null
    // Phase 6.1c — preview surfaces registered by DualPreviewZone. Survives
    // forceReconfigureCamera() (camera flip / mode change) by being replayed
    // onto the new DualVideoRecorder in setupDualCamera. Keyed by side;
    // re-registering the same side replaces the prior entry (TextureView
    // size-changed path).
    // Thread-safety: all access guarded by synchronized(pendingPreviewSurfaces).
    // UI thread (TextureView callbacks) mutates via attach/detachDualPreview;
    // service thread reads via setupDualCamera replay (snapshot-then-iterate).
    private val pendingPreviewSurfaces =
        mutableMapOf<com.aritr.rova.service.dualrecord.VideoSide, Triple<android.view.Surface, Int, Int>>()
    private var currentDualRecording: com.aritr.rova.service.dualrecord.DualRecording? = null
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

    // Phase 4.3 — second guard: prevent two concurrent recovery merges;
    // isPeriodicActive only covers live-recording sessions.
    @Volatile private var recoveryMergeInFlight: Boolean = false

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
                    RovaLog.d { "SessionController.postTick($seq, WATCHDOG): liveness OK, no-op" }
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
            requestStop(com.aritr.rova.data.StopReason.USER)
        }

        override fun requestStop(reason: com.aritr.rova.data.StopReason) {
            // ADR 0006 B-fix-5 / ADR-0027: explicit StopReason for the
            // notification-STOP / UI-stop / scheduled-window-close paths.
            // Belt-and-braces with the eager-write fallback
            // (`currentStopReason ?: StopReason.USER`) — explicit assignment
            // makes the intent visible at the call site and prevents any
            // future first-writer-wins race from inheriting a null/stale value.
            RovaLog.d { "SessionController.requestStop: sessionId=$sessionId reason=$reason" }
            serviceScope.launch {
                userStopRequested = true
                if (currentStopReason == null) {
                    currentStopReason = reason
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

    // ADR-0027 — daily-window provenance for this session. Set from the start
    // intent; persisted in the manifest (recovery evidence) and read by the
    // segment-loop self-heal to stop at window end.
    private var startedBySchedule = false
    private var scheduleWindowEndMillis = 0L

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

    /**
     * B5 / ADR-0025 — service-side cache of the session's frozen
     * `vaultIntentAtStart`, populated from the manifest at createSession time
     * (mirrors [currentExportTier]). Passed to [ExportPipeline.export] so a
     * vault-frozen session dispatches to the private vault route. Cleared in
     * [releaseResources].
     */
    private var currentVaultIntent: Boolean = false

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
        RovaLog.d { "setSurfaceProvider: received: $surfaceProvider" }
        currentSurfaceProvider = surfaceProvider

        if (surfaceProvider != null) {
            // C2: Signal the recording loop that the surface is ready
            if (!surfaceProviderReady.isCompleted) {
                surfaceProviderReady.complete(Unit)
            }
            // If camera is already set up, attach the surface provider to the
            // existing preview. Otherwise, launch full camera setup (which
            // will pick up currentSurfaceProvider).
            val existingPreview = preview
            if (existingPreview != null) {
                if (boundToDummy) {
                    // DUMMY -> UI swap. CameraX's Preview.setSurfaceProvider
                    // hot-swap does not reliably re-cycle a fresh SurfaceRequest
                    // to the new provider on many devices, leaving PreviewView
                    // black for the entire session (see boundToDummy field
                    // docs + the record-start forceReconfigureCamera pattern
                    // in startPeriodicRecording). Cold-start with denied
                    // CAMERA permission keeps PreviewView unmounted, so the
                    // 500 ms surface grace in startCameraPreview expires and
                    // setupCamera binds to DUMMY; the user then grants the
                    // permission in system Settings and returns -- the UI
                    // surface arrives here, into a preview already bound to
                    // DUMMY. Rebind cleanly so the new SurfaceRequest fires.
                    RovaLog.d { "setSurfaceProvider: DUMMY -> UI, forcing camera reconfigure" }
                    serviceScope.launch { forceReconfigureCamera() }
                } else {
                    // UI -> UI hot-swap (config change, screen rotation). Safe.
                    // CameraX's own teardown drives release of the previous
                    // surface via its provideSurface result callback -- no
                    // eager release here.
                    existingPreview.setSurfaceProvider(surfaceProvider)
                }
            } else {
                serviceScope.launch { setupCamera() }
            }
        } else {
            preview?.let { existingPreview ->
                if (_serviceState.value.isPeriodicActive) {
                    RovaLog.d { "setSurfaceProvider: UI surface removed during active session, switching to dummy surface" }
                    existingPreview.setSurfaceProvider(createDummySurfaceProvider())
                }
            }
        }
    }

    fun stopCameraPreview() {
        if (_serviceState.value.isPeriodicActive) return // Don't stop if recording
        previewStartJob?.cancel()
        previewStartJob = null
        RovaLog.d { "stopCameraPreview: Unbinding camera for background" }
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        currentDualRecording?.let { try { it.stop() } catch (_: Exception) {} }
        currentDualRecording = null
        currentDualRecorder?.release()
        currentDualRecorder = null
        markCameraUnbound()  // Phase 3.5
        // PR-α (ADR-0029 §Decision 2,3) — Single use cases unbound for background;
        // stop tracking. Re-enabled on the next Single bind success.
        disableOrientationTracking()
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
            previewStartJob?.cancel()
            previewStartJob = serviceScope.launch {
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
                    RovaLog.d {
                        "startCameraPreview: surface grace ${if (waited != null) "UI arrived" else "expired -> DUMMY"}"
                    }
                }
                // ADR-0021 — the app may have backgrounded while we waited in
                // the grace window. Do NOT bind the camera after a background
                // event (privacy/policy). stopCameraPreview also cancels this
                // job on ON_STOP; this re-check covers the ProcessLifecycleOwner
                // ON_STOP dispatch delay.
                if (!appForeground) {
                    RovaLog.d { "startCameraPreview: app backgrounded mid-grace, aborting setupCamera" }
                    return@launch
                }
                setupCamera()
            }
        } else {
            RovaLog.d { "startCameraPreview: Camera already active, skipping setup" }
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
        // B3 i18n task 9: LEGACY_NOTIFICATION_TITLE literal externalized to
        // R.string.notification_title_recording_active; resolved at the two
        // Service-scope call sites (a companion const cannot call getString).
        // Phase 1.3 — legacy ACTION_STOP service-intent constant removed.
        // Stop arrives via RovaStopReceiver.ACTION_STOP (broadcast).
        // M5 §5 — share action routed through MainActivity so the chooser
        // builder has access to an Activity context.
        const val ACTION_SHARE_RECORDING = "com.aritr.rova.action.SHARE_RECORDING"
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
        fun start(
            context: Context,
            nSeconds: Float,
            mMinutes: Float,
            limitLoops: Int = -1,
            resolution: String = QualityPresets.DEFAULT,
            startedBySchedule: Boolean = false,
            scheduleWindowEndMillis: Long = 0L,
        ): StartResult {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isAppVisible(context)) {
                RovaLog.w("start: refusing to launch camera recording while app is backgrounded")
                return StartResult.Blocked(StartBlocked.APP_NOT_VISIBLE)
            }
            val intent = Intent(context, RovaRecordingService::class.java).apply {
                putExtra("N_SECONDS", nSeconds)
                putExtra("M_MINUTES", mMinutes)
                putExtra("LIMIT_LOOPS", limitLoops)
                putExtra("RESOLUTION", resolution)
                putExtra("STARTED_BY_SCHEDULE", startedBySchedule)
                putExtra("SCHEDULE_WINDOW_END_MS", scheduleWindowEndMillis)
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

        // Phase 4.3 — recovery-merge action + notification ID.
        const val ACTION_RECOVER_MERGE = "com.aritr.rova.service.ACTION_RECOVER_MERGE"
        const val EXTRA_RECOVERY_SESSION_ID = "recovery_session_id"
        private const val NOTIFICATION_ID_RECOVERY_MERGE = 4096

        /**
         * Phase 4.3 — starts the FGS in recovery-merge mode for [sessionId].
         * The service's `onStartCommand` branches on [ACTION_RECOVER_MERGE]
         * and dispatches via [recoveryMergeStartGate]; the decision drives
         * either a merge launch or an immediate signal emission + stopSelf.
         */
        fun startRecoveryMerge(context: Context, sessionId: String) {
            val intent = Intent(context, RovaRecordingService::class.java).apply {
                action = ACTION_RECOVER_MERGE
                putExtra(EXTRA_RECOVERY_SESSION_ID, sessionId)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                val isFgsRestricted =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        e is android.app.ForegroundServiceStartNotAllowedException
                RovaCrashReporter.recordException(
                    e,
                    if (isFgsRestricted) "FGS start not allowed (recovery, API 31+)" else "FGS start ISE (recovery)"
                )
            }
        }

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
        val settings = RovaSettings(this)
        currentMode = settings.mode
        // B6 — restore the persisted lens choice. Backed-up pref (survives
        // reinstall like resolution/themeMode), unlike `mode` which resets.
        // Resolve through availability so a stale "prefer front" pref on a
        // front-less device seeds rear (and self-heals the pref) instead of
        // stranding the preview black (codex review).
        val deviceHasFrontCamera = hasFrontCamera()
        val seedFront = LensFlipPolicy.resolveIsFront(settings.preferFrontCamera, deviceHasFrontCamera)
        if (settings.preferFrontCamera && !seedFront) settings.preferFrontCamera = false
        currentCameraSelector = if (seedFront) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        _serviceState.update { it.copy(isFrontCamera = seedFront, hasFrontCamera = deviceHasFrontCamera) }
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createNotificationChannel()
        // ADR-0021 — release idle camera preview when the whole app
        // backgrounds (not on in-app tab switches).
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        // C12: automatic cleanup is disabled until a recovery UI lands in
        // Phase 2. cleanupOrphanedSegments() is intentionally NOT called here;
        // segments persist across launches so recovery can offer them back.
        // Cleanup happens only via (a) successful same-session merge or
        // (b) explicit user-action discard in Phase 2.
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // -------------------------------------------------------------------------
    // Phase 4.3 — recovery-merge FGS branch
    // -------------------------------------------------------------------------

    /**
     * Handles an [ACTION_RECOVER_MERGE] intent. Pure gate logic is in
     * [recoveryMergeStartGate] (JVM-testable); this method owns the
     * Android-side effects (startForeground, coroutine launch, stopSelf).
     *
     * Uses `_serviceState.value.isPeriodicActive` as the "recording active"
     * guard — that flag is true for the full recording session lifetime
     * (set in startPeriodicRecording, cleared on session end / onDestroy).
     */
    private fun handleRecoveryMergeStart(sessionId: String, startId: Int): Int {
        val app = application as RovaApp
        val signal = app.recoveryMergeOutcomeSignal
        val decision = recoveryMergeStartGate(
            isRecordingActive = (_serviceState.value.isPeriodicActive || recoveryMergeInFlight),
            sessionId = sessionId,
        )
        return when (decision) {
            is RecoveryMergeStartDecision.Reject -> {
                signal.emitOutcome(sessionId, decision.outcome)
                stopSelf(startId)
                START_NOT_STICKY
            }
            is RecoveryMergeStartDecision.Accept -> {
                // Milestone 2 — preflight headroom gate. Spec §5 #3.
                val manifest = sessionStore.loadManifest(decision.sessionId)
                if (manifest != null) {
                    val available = queryAllocatableBytes()
                    val accumulated = com.aritr.rova.data.StorageEstimator.accumulatedSessionBytes(
                        sessionDir = sessionStore.sessionDir(decision.sessionId),
                        segmentCount = manifest.segments.size,
                        durationSeconds = manifest.segments.sumOf { it.durationMs } / 1000L,
                        resolution = manifest.config.resolution,
                    )
                    if (!com.aritr.rova.service.recovery.StoragePreflight.hasHeadroom(available, accumulated)) {
                        signal.emitOutcome(
                            decision.sessionId,
                            RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage(
                                requiredBytes = accumulated + com.aritr.rova.service.recovery.StoragePreflight.FINALIZE_HEADROOM_BYTES,
                                availableBytes = available,
                            ),
                        )
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                }
                if (!startForegroundForRecoveryMerge(decision.sessionId)) {
                    // FGS start blocked (e.g. ForegroundServiceStartNotAllowedException) —
                    // surface to consumer so it doesn't hang waiting for an outcome.
                    signal.emitOutcome(
                        decision.sessionId,
                        RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.ServiceBusy,
                    )
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                recoveryMergeInFlight = true
                serviceScope.launch {
                    val merger = com.aritr.rova.service.recovery.RecoveryMerger(
                        loadSegments = { sid ->
                            val mf = sessionStore.loadManifest(sid)
                            val dir = sessionStore.sessionDir(sid)
                            mf?.segments
                                ?.map { java.io.File(dir, it.filename) }
                                ?.filter { it.exists() && it.length() > 0 }
                                ?: emptyList()
                        },
                        sessionDirOf = { sid -> sessionStore.sessionDir(sid) },
                        exportRecovered = { dir, segs, onProgress ->
                            com.aritr.rova.service.export.ExportPipeline.exportRecovered(
                                context = this@RovaRecordingService,
                                sessionStore = sessionStore,
                                sessionId = decision.sessionId,
                                sessionDir = dir,
                                segments = segs,
                                onProgress = onProgress,
                            )
                        },
                        signal = signal,
                        pollForStorage = { required, _ ->
                            val current = queryAllocatableBytes()
                            current >= required
                        },
                        onProgress = { sid, fraction ->
                            signal.emitInProgress(sid, fraction)
                            updateMergeNotification(sid, fraction)
                        },
                    )
                    try {
                        merger.run(decision.sessionId)
                    } finally {
                        recoveryMergeInFlight = false
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                        stopSelf(startId)
                    }
                }
                START_NOT_STICKY
            }
        }
    }

    /**
     * Milestone 2 — query free bytes on the external storage root. Uses
     * `StorageManager.getAllocatableBytes` on API 26+ (project minSdk is
     * 24 so this version check is needed) with `usableSpace` fallback for
     * API 24-25. Sources external root via `RovaApp.externalRoot` per
     * ADR-0006 (B21) — direct `getExternalFilesDir` in the Service is
     * forbidden.
     */
    private fun queryAllocatableBytes(): Long {
        val externalRoot = (application as RovaApp).externalRoot ?: return 0L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val sm = getSystemService(android.content.Context.STORAGE_SERVICE) as android.os.storage.StorageManager
                val uuid = sm.getUuidForPath(externalRoot)
                sm.getAllocatableBytes(uuid)
            } catch (t: Throwable) {
                externalRoot.usableSpace
            }
        } else {
            externalRoot.usableSpace
        }
    }

    // Milestone 2 — notification-update throttle state.
    private var lastMergeNotifyMillis: Long = 0L
    private val MERGE_NOTIFY_THROTTLE_MS = 500L

    // Milestone 5 §6 — session-channel rate-limit (~1Hz) with transition flush.
    @Volatile private var lastSessionNotifyMillis: Long = 0L
    @Volatile private var lastNotifiedState: NotificationState? = null
    private val MIN_SESSION_NOTIFY_INTERVAL_MS = 950L  // ~1Hz; just under a second to absorb scheduler jitter

    /**
     * Milestone 2 — progress-aware merge notification update. Throttled
     * to 500ms; final tick (`fraction >= 1f`) bypasses throttle. Reuses
     * the existing channel + NOTIFICATION_ID_RECOVERY_MERGE.
     */
    private fun updateMergeNotification(sessionId: String, fraction: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        val isFinal = fraction >= 1f
        if (!isFinal && now - lastMergeNotifyMillis < MERGE_NOTIFY_THROTTLE_MS) return
        lastMergeNotifyMillis = now
        val percent = (fraction.coerceIn(0f, 1f) * 100f).toInt()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_recovery_merge_title))
            .setContentText(getString(R.string.notification_recovery_merge_percent, percent))
            .setProgress(100, percent, false)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_RECOVERY_MERGE, notif)
    }

    private fun startForegroundForRecoveryMerge(sessionId: String): Boolean {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_recovery_merge_title))
            .setContentText(getString(R.string.notification_recovery_merge_session, sessionId.take(8)))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID_RECOVERY_MERGE, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID_RECOVERY_MERGE, notif)
            }
            true
        } catch (e: IllegalStateException) {
            val isFgsRestricted =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
            val tag = if (isFgsRestricted) "FGS start not allowed (recovery service-side, API 31+)" else "FGS start ISE (recovery service-side)"
            RovaCrashReporter.recordException(e, tag)
            false
        } catch (e: SecurityException) {
            RovaCrashReporter.recordException(e, "FGS type SecurityException (recovery)")
            false
        }
    }

    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Phase 4.3 — recovery-merge branch. Handled before the recording
        // path so the FGS-type / camera-permission guards don't apply.
        if (intent?.action == ACTION_RECOVER_MERGE) {
            val sessionId = intent.getStringExtra(EXTRA_RECOVERY_SESSION_ID).orEmpty()
            return handleRecoveryMergeStart(sessionId, startId)
        }

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
        startedBySchedule = intent.getBooleanExtra("STARTED_BY_SCHEDULE", false)
        scheduleWindowEndMillis = intent.getLongExtra("SCHEDULE_WINDOW_END_MS", 0L)
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
            it.copy(
                totalLoops = limitLoops,
                currentLoop = 0,
                segmentCount = 0,
                // Bug A — reset the saved-clip counters so the UI never sees a
                // prior session's count during this session's merge / summary.
                exportedClipCount = 0,
                mergeClipCount = 0,
                // Bug 3 — cleanliness: never carry a stale cold-acquire flag into
                // a new session. The setup try/finally already guarantees clearing,
                // this is belt-and-braces alongside the Bug A resets.
                coldAcquireInProgress = false
            )
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
        val notification = createNotification(
            getString(R.string.notification_title_recording_active),
            getString(R.string.notification_initializing)
        )
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
            updateNotification(getString(R.string.notification_storage_insufficient))
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
                    RovaLog.d { "markInitFailedAndStop: wrote USER_STOPPED/INIT_FAILED for $sessionId at $where" }
                is com.aritr.rova.data.MarkTerminatedResult.AlreadyTerminal ->
                    RovaLog.d {
                        "markInitFailedAndStop: $sessionId already" +
                            " ${result.existingTerminated}/${result.existingStopReason}; suppressed at $where"
                    }
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
                        val safUsable = hasUsableSafFolder()
                        // B4c (c) — don't silently switch the destination: if a
                        // custom save folder was chosen for this single-mode
                        // session but is now gone/unwritable, raise the advisory
                        // so the user learns this recording lands in the default
                        // location. The recording still succeeds either way.
                        val settings = com.aritr.rova.data.RovaSettings(this@RovaRecordingService)
                        if (!safUsable && settings.saveLocationTreeUri != null &&
                            config.mode != "PortraitLandscape") {
                            settings.saveFolderUnavailable = true
                        }
                        sessionStore.createSession(
                            config,
                            currentAudioMode,
                            safUsable,
                            vaultIntentAtStart = settings.hideInVault,
                            startedBySchedule = startedBySchedule,
                            scheduleWindowEndMillis = scheduleWindowEndMillis
                        )
                    }
                    currentSessionId = m.sessionId
                    currentSessionDir = sessionStore.sessionDir(m.sessionId)
                    // Phase 1.6: cache frozen tier from manifest; gate
                    // reads this, never SDK_INT.
                    currentExportTier = m.exportTier
                    // B5 / ADR-0025: cache frozen vault intent for the export
                    // dispatch; runExportPipeline passes it to ExportPipeline.export.
                    currentVaultIntent = m.vaultIntentAtStart
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
                RovaLog.d { "startPeriodicRecording: session=${manifest.sessionId}" }

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
                updateNotification(getString(R.string.notification_preparing_camera))

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
                RovaLog.d {
                    "startPeriodicRecording: forcing camera reconfigure on start" +
                        " (configured=$configuredResolution, requested=$resolutionStr," +
                        " surface=${if (currentSurfaceProvider != null) "UI" else "DUMMY"})"
                }
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
                    updateNotification(getString(R.string.notification_camera_failed))
                    markInitFailedAndStop(manifest.sessionId, "camera-ready-timeout")
                    return@launch
                }

                // Let CameraX pipeline fully stabilize (encoder init, frame production)
                // Samsung devices need extra time for MediaCodec initialization
                delay(2500)
                RovaLog.d { "startPeriodicRecording: Camera ready, starting recording loop" }

                outer@ while (isActive) {
                    if (limitLoops != -1 && segmentCount >= limitLoops) {
                        stopPeriodicRecordingAndMerge()
                        break@outer
                    }

                    _serviceState.update { it.copy(currentLoop = segmentCount + 1) }
                    updateNotification(NotificationState.ClipRecording(
                        current = segmentCount + 1,
                        total = limitLoops.takeIf { it != -1 },
                        etaSecondsRemaining = nSeconds.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    ))

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
                            RovaLog.d { "startPeriodicRecording: AbortNoOp; breaking outer loop" }
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
                            updateNotification(getString(R.string.notification_recording_failed_stopping))
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

                    // ADR-0027 self-heal: if a scheduled window has elapsed, stop
                    // here even if the window-close alarm was throttled or missed
                    // in Doze. Idempotent with the ScheduleStopReceiver path —
                    // stopPeriodicRecordingAndMerge guards re-entry, and
                    // currentStopReason is first-writer-wins so a reason already
                    // latched by the receiver (or a gate) survives. Like every
                    // user-stop, the captured segments are still merged; the
                    // session is classified USER_STOPPED + SCHEDULE_WINDOW.
                    if (startedBySchedule && scheduleWindowEndMillis > 0L &&
                        System.currentTimeMillis() >= scheduleWindowEndMillis
                    ) {
                        RovaLog.d { "startPeriodicRecording: schedule window elapsed — self-heal stop" }
                        userStopRequested = true
                        if (currentStopReason == null) {
                            currentStopReason = com.aritr.rova.data.StopReason.SCHEDULE_WINDOW
                        }
                        stopPeriodicRecordingAndMerge()
                        break@outer
                    }

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
                RovaLog.d { "startPeriodicRecording: Cancelled" }
                throw e
            } catch (e: Exception) {
                // ADR 0006 row 7 (B-fix-1) — generic post-manifest setup
                // failure (camera bind throw, config error, unexpected
                // exception). If a manifest exists, write USER_STOPPED +
                // INIT_FAILED before tearing down. Otherwise the throw
                // happened before createSession returned (pre-manifest);
                // just log + tear down.
                e.printStackTrace()
                updateNotification(
                    getString(
                        R.string.notification_recording_stopped_detail,
                        e.message?.take(60) ?: getString(R.string.notification_unknown_error)
                    )
                )
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
            RovaLog.d { "forceReconfigureCamera: Unbinding for fresh setup" }
            try { cameraProvider?.unbindAll() } catch (e: Exception) {}
            currentDualRecording?.let { try { it.stop() } catch (_: Exception) {} }
            currentDualRecording = null
            currentDualRecorder?.release()
            currentDualRecorder = null
            markCameraUnbound()  // Phase 3.5
            // PR-α (ADR-0029 §Decision 2,3) — Single use cases are being unbound for
            // a fresh setup; stop tracking. setupCamera() re-enables on Single rebind.
            disableOrientationTracking()
            preview = null
            videoCapture = null
            camera = null
            configuredResolution = null
            _serviceState.update { it.copy(isCameraActive = false) }
        }
        setupCamera()
    }

    private suspend fun setupCamera() {
        if (currentMode == "PortraitLandscape") {
            setupDualCamera()
        } else {
            setupSingleCamera()
        }
    }

    private suspend fun setupSingleCamera() {
        // BODY: verbatim move of the prior setupCamera() launch block.
        // Every line preserved character-for-character including comments,
        // whitespace, log statements, and the serviceScope.launch + mutex
        // structure. Do NOT modify any line of the extracted body.
        setupMutex.withLock {
            RovaLog.d { "setupCamera: Starting setup workflow" }

            RovaLog.d { "setupCamera: currentSurfaceProvider=${if (currentSurfaceProvider != null) "UI" else "null (will use dummy)"}" }

            if (cameraProvider != null) {
                if (_serviceState.value.isCameraActive) {
                    RovaLog.d { "setupCamera: Camera already active. Skipping setup." }
                    return@withLock
                }
                if (_serviceState.value.isRecording) {
                    RovaLog.w("setupCamera: Attempted to setup while recording! Aborting.")
                    return@withLock
                }
                RovaLog.d { "setupCamera: Unbinding existing provider for clean setup" }
                try { cameraProvider?.unbindAll() } catch (e: Exception) {}
                markCameraUnbound()  // Phase 3.5
                // PR-α (ADR-0029 §Decision 2,3) — existing use cases unbound before
                // rebind; stop tracking. Re-enabled on this setup's bind success.
                disableOrientationTracking()
                _serviceState.update { it.copy(isCameraActive = false) }
            } else {
                val provider = withContext(Dispatchers.IO) {
                    ProcessCameraProvider.getInstance(this@RovaRecordingService).get()
                }
                cameraProvider = provider
            }

            val provider = cameraProvider ?: return@withLock

            // Bug 3 — past the no-op guards: a real cold bind is now committed.
            // Raise the cold-acquire flag and clear it on EVERY exit (success,
            // bind-failure catch, or any throw) via finally so the overlay can't
            // latch on. The early no-op returns above (already-active, recording
            // guard, provider==null) intentionally never raised the flag.
            markColdAcquire(true)
            try {
            RovaLog.d { "setupCamera: Initializing UseCases (Preview + VideoCapture)" }

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
            // ADR-0029 (force-rotate) — under the Auto orientation policy the
            // OrientationEventListener owns capture rotation from the physical sensor,
            // and MainActivity force-follows the sensor (FULL_SENSOR) so displayRotation
            // is already the real device rotation. Applying the legacy Mode "+90°
            // Landscape" offset (computeTargetRotation) on top would double-rotate the
            // first segment until the first sensor event corrected it, so bind to the
            // raw (normalized) display rotation and let the listener take over. The Mode
            // offset path is retained ONLY for a future non-Auto lock policy.
            val targetRot = if (DEFAULT_ORIENTATION_POLICY_IS_AUTO) {
                computeTargetRotation(displayRotation, "Portrait") // identity-normalize; no Mode offset
            } else {
                computeTargetRotation(displayRotation, currentMode)
            }
            videoCapture = VideoCapture.Builder(recorder).setTargetRotation(targetRot).build()

            // Preview rotation is owned by PreviewView/display, not the sensor listener
            // (see enableOrientationTracking). Seed with the bind-time display rotation;
            // PreviewView corrects the on-screen transform as the Activity rotates.
            preview = Preview.Builder().setTargetRotation(targetRot).build()
            // Samsung devices require Preview to have an active surface for VideoCapture
            // to produce frames. Use a dummy surface as fallback when UI hasn't connected yet.
            val useDummy = currentSurfaceProvider == null
            val surfaceProvider = currentSurfaceProvider ?: createDummySurfaceProvider()
            preview?.setSurfaceProvider(surfaceProvider)
            RovaLog.d { "setupCamera: SurfaceProvider=${if (useDummy) "DUMMY" else "UI"}" }

            try {
                provider.unbindAll()
                RovaLog.d { "setupCamera: Binding to lifecycle" }
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
                // Bump the monotonic generation so the record screen can clear
                // its front/back "Switching…" latch without depending on the
                // conflation-prone isCameraActive edge (see cameraConfigGeneration).
                _serviceState.update { it.copy(isCameraActive = true, cameraConfigGeneration = it.cameraConfigGeneration + 1) }
                RovaLog.d { "setupCamera: Camera binding COMPLETED. Active: true, resolution: $resolutionStr, boundToDummy=$boundToDummy" }
                applyFlashState()
                // PR-α (ADR-0029 §Decision 2,3) — Single path only: begin tracking
                // physical device rotation, seeded from the SAME bind-time rotation
                // the use cases were built with so HUD/persist agree pre-sensor.
                enableOrientationTracking(targetRot)
            } catch (e: Exception) {
                e.printStackTrace()
                RovaLog.e("setupCamera: Binding failed", e)
                // Null out dangling use cases. Preview / VideoCapture were
                // constructed above before bindToLifecycle threw, so they
                // exist as objects even though the camera was never bound.
                // If we leave them set, the next setSurfaceProvider call
                // sees `preview != null && !boundToDummy` and takes the
                // hot-swap branch, attaching the UI surface to a Preview
                // that has no camera feeding it -- PreviewView stays black
                // forever. The fresh-install cold-launch path hits exactly
                // this race: CAMERA permission is denied at startup, the
                // service's startCameraPreview grace expires and setupCamera
                // runs without permission, bindToLifecycle throws, and the
                // user's later permission grant + return arrives into
                // dangling preview state. Clear it so the post-grant
                // setSurfaceProvider falls through to the else-branch that
                // launches a fresh setupCamera.
                try { provider.unbindAll() } catch (_: Exception) {}
                markCameraUnbound()
                // PR-α (ADR-0029 §Decision 2,3) — Single bind failed; stop tracking.
                disableOrientationTracking()
                preview = null
                videoCapture = null
                camera = null
                configuredResolution = null
                boundToDummy = false
                _serviceState.update { it.copy(isCameraActive = false) }
                // B6 (codex review) — if the FRONT lens failed to bind (front
                // sensor present per PackageManager but unusable, or an OEM quirk),
                // fall back to rear so the user isn't stranded on a black preview
                // (and the "Switching…" overlay, which clears on isCameraActive),
                // and self-heal the pref so the next cold launch doesn't repeat
                // the front bind. Rear binds on effectively every device, so this
                // re-entry resolves in one pass (the rear path never re-enters
                // this branch).
                if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                    RovaLog.w("setupCamera: front-camera bind failed — falling back to rear")
                    currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    RovaSettings(this@RovaRecordingService).preferFrontCamera = false
                    _serviceState.update { it.copy(isFrontCamera = false) }
                    serviceScope.launch { forceReconfigureCamera() }
                }
            }
            } finally {
                // Bug 3 — single guaranteed clear for every exit of the bind body.
                markColdAcquire(false)
            }
        }
    }

    /**
     * PR-α (ADR-0029 §Decision 2,3) — start tracking physical device rotation for
     * the SINGLE camera path. Idempotent. No-op when the default policy is not Auto
     * (seam for a future PortraitLock default). Seeds the snapper from the bind-time
     * rotation so HUD/persist agree before any sensor event arrives.
     */
    private fun enableOrientationTracking(seedRotation: Int) {
        disableOrientationTracking()
        orientationSnapState = OrientationSnapState(stable = seedRotation, candidate = null, candidateSinceMs = null)
        hasStableSnap = false
        _serviceState.update { it.copy(currentSegmentRotation = seedRotation, pendingNextRotation = seedRotation) }
        if (!DEFAULT_ORIENTATION_POLICY_IS_AUTO) return

        val listener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                // PR-α (codex review) — unregisterListener() stops only FUTURE delivery;
                // a sensor event already queued on the main looper can still run AFTER
                // disableOrientationTracking(). Identity guard: only the currently-
                // registered listener acts, so a superseded/disabled instance no-ops
                // instead of corrupting freshly re-seeded state or rotating a newly
                // bound use case with a stale value.
                if (orientationListener !== this) return
                val prevStable = orientationSnapState.stable
                orientationSnapState = snapOrientation(
                    degrees = orientation,
                    current = orientationSnapState,
                    nowMs = android.os.SystemClock.elapsedRealtime(),
                )
                if (orientation != android.view.OrientationEventListener.ORIENTATION_UNKNOWN) {
                    hasStableSnap = true
                }
                val newStable = orientationSnapState.stable
                if (newStable != prevStable) {
                    // ADR-0029 (force-rotate) — drive ONLY VideoCapture from the sensor.
                    // VideoCapture/Recorder ignores the change mid-clip and adopts it at
                    // the NEXT segment start (segment-boundary contract). Preview is NOT
                    // written here: with MainActivity force-following the sensor, PreviewView
                    // owns the on-screen rotation from the display; a second listener-driven
                    // preview rotation could diverge from the display and mis-rotate preview.
                    try { videoCapture?.targetRotation = newStable } catch (_: Exception) {}
                    refreshResolutionConsumers()
                    _serviceState.update { it.copy(pendingNextRotation = newStable) }
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationListener = listener
            RovaLog.d { "enableOrientationTracking: listener enabled (seed=$seedRotation)" }
        } else {
            RovaLog.w("enableOrientationTracking: device cannot detect orientation — staying at seed $seedRotation")
        }
    }

    /** PR-α — stop tracking; called on every unbind/teardown. Idempotent. */
    private fun disableOrientationTracking() {
        orientationListener?.let { try { it.disable() } catch (_: Exception) {} }
        orientationListener = null
        hasStableSnap = false
    }

    /**
     * PR-α (ADR-0029 §Decision 3) — after a live setTargetRotation the bound
     * VideoCapture's ResolutionInfo can shift aspect. The Single path has no live
     * crop/resolution consumer to re-pull today (PreviewView owns its transform,
     * DualShot owns its EGL crop), so this is a documented guard seam.
     */
    private fun refreshResolutionConsumers() {
        // Guard seam — no live Single-path ResolutionInfo consumer exists in PR-α.
    }

    /**
     * Phase: render-architecture audit (extends ADR-0009 query). Queries
     * BOTH the device's preferred 4:3 source size AND SENSOR_ORIENTATION
     * for the active lens, in a single CameraManager round-trip. Returns
     * a [DualCameraIntrinsics] container. Defensive fallback on any
     * failure step: `DualCameraIntrinsics(Size(1920, 1440), 90)`.
     *
     * Called once per setupDualCamera invocation; bypasses the CameraX-
     * bound camera so the intrinsics are known BEFORE Preview.Builder.
     *
     * The fallback `sensorOrientation = 90` is the SAFE ASSUMPTION for
     * portrait-natural phones (see [SENSOR_ORIENTATION_FALLBACK] KDoc).
     */
    private fun resolveDualCameraIntrinsics(): DualCameraIntrinsics {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE)
            as? android.hardware.camera2.CameraManager
            ?: run {
                RovaLog.w("resolveDualCameraIntrinsics: CameraManager unavailable — fallback")
                return DualCameraIntrinsics(android.util.Size(1920, 1440), SENSOR_ORIENTATION_FALLBACK)
            }
        val lensFacing = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        } else {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING
                ) == lensFacing
            } ?: run {
                RovaLog.w("resolveDualCameraIntrinsics: no cameraId for lensFacing=$lensFacing — fallback")
                return DualCameraIntrinsics(android.util.Size(1920, 1440), SENSOR_ORIENTATION_FALLBACK)
            }
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(
                android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: run {
                RovaLog.w("resolveDualCameraIntrinsics: null StreamConfigurationMap — fallback")
                return DualCameraIntrinsics(android.util.Size(1920, 1440), SENSOR_ORIENTATION_FALLBACK)
            }
            val rawSensorOrientation = chars.get(
                android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION
            ) ?: SENSOR_ORIENTATION_FALLBACK
            val sanitized = sanitizeSensorOrientation(rawSensorOrientation)
            val chosenSize = com.aritr.rova.service.dualrecord.internal.DualCameraSizeResolver
                .resolveDualCameraSize(map)
            RovaLog.d {
                "resolveDualCameraIntrinsics: size=${chosenSize.width}×${chosenSize.height} " +
                    "sensorOrientation=$sanitized (raw=$rawSensorOrientation, lensFacing=$lensFacing)"
            }
            DualCameraIntrinsics(chosenSize, sanitized)
        } catch (e: Exception) {
            RovaLog.w("resolveDualCameraIntrinsics: $e — fallback")
            DualCameraIntrinsics(android.util.Size(1920, 1440), SENSOR_ORIENTATION_FALLBACK)
        }
    }

    /**
     * Phase: render-architecture audit. DEBUG-only SharedPreferences read
     * for the first-principles render-path flag. RELEASE returns false
     * unconditionally (BuildConfig.DEBUG short-circuit).
     *
     * ClassCastException-safe: if a corrupt prefs file has a non-boolean
     * value at the key, swallow + WARN + return false (spec §4.1 row #3).
     *
     * Per spec §1.6: prefs key = "pref.dev.useFirstPrinciplesRender",
     * prefs file = "rova_dev_flags", default = false.
     */
    private fun readUseFirstPrinciplesRender(): Boolean {
        if (!com.aritr.rova.BuildConfig.DEBUG) return false
        return try {
            getSharedPreferences("rova_dev_flags", Context.MODE_PRIVATE)
                .getBoolean("pref.dev.useFirstPrinciplesRender", false)
        } catch (e: ClassCastException) {
            RovaLog.w("useFirstPrinciplesRender prefs type mismatch; defaulting false", e)
            false
        }
    }

    /**
     * Phase: render-architecture audit. DEBUG-only SharedPreferences read
     * for the debug snapshot flag. Same contract + safety as
     * [readUseFirstPrinciplesRender].
     */
    private fun readEnableMatrixSnapshots(): Boolean {
        if (!com.aritr.rova.BuildConfig.DEBUG) return false
        return try {
            getSharedPreferences("rova_dev_flags", Context.MODE_PRIVATE)
                .getBoolean("pref.dev.enableMatrixSnapshots", false)
        } catch (e: ClassCastException) {
            RovaLog.w("enableMatrixSnapshots prefs type mismatch; defaulting false", e)
            false
        }
    }

    private suspend fun setupDualCamera() {
        setupMutex.withLock {
            if (_serviceState.value.isCameraActive) {
                RovaLog.d { "setupDualCamera: camera already active, short-circuit" }
                return@withLock
            }

            if (cameraProvider == null) {
                val provider = withContext(Dispatchers.IO) {
                    ProcessCameraProvider.getInstance(this@RovaRecordingService).get()
                }
                cameraProvider = provider
            }

            val provider = cameraProvider ?: return@withLock

            // Bug 3 — past the no-op guards: a real cold bind is now committed.
            // Raise the cold-acquire flag; the finally below clears it on every
            // exit (success or bind-failure catch) so the overlay can't latch.
            markColdAcquire(true)
            try {
            RovaLog.d { "setupDualCamera: Initializing UseCases (Preview + CameraEffect)" }

            val displayRotation = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                ?: android.view.Surface.ROTATION_0

            val lensFacing = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                com.aritr.rova.service.dualrecord.LensFacing.FRONT
            } else {
                com.aritr.rova.service.dualrecord.LensFacing.BACK
            }

            // Render-audit (Task 11) — intrinsics + debug-flag reads MUST happen
            // before config construction so cameraInputSize/sensorOrientation/flags
            // are all available. Single CameraManager round-trip (resolveDualCamera-
            // Intrinsics already coalesces the size + sensorOrientation query).
            val intrinsics = resolveDualCameraIntrinsics()
            val cameraInputSize = intrinsics.size
            val useFirstPrinciplesRender = readUseFirstPrinciplesRender()
            val enableMatrixSnapshots = readEnableMatrixSnapshots()

            // 6.1b consumer config — FHD-locked for v1; 6.1c may lookup BitrateTable per resolution.
            val portraitSize = android.util.Size(1080, 1920)
            val landscapeSize = android.util.Size(1920, 1080)
            val config = com.aritr.rova.service.dualrecord.DualVideoRecorderConfig(
                cameraInputSize = intrinsics.size,
                portraitOutputSize = portraitSize,
                landscapeOutputSize = landscapeSize,
                portraitBitrate = 8_000_000,
                landscapeBitrate = 8_000_000,
                videoCodec = com.aritr.rova.service.dualrecord.VideoCodec.H264,
                audioBitrate = 128_000,
                audioSampleRate = 48_000,
                lensFacing = lensFacing,
                displayRotation = displayRotation,
                fps = 30,
                sensorOrientation = intrinsics.sensorOrientation,
                useFirstPrinciplesRender = useFirstPrinciplesRender,
                enableMatrixSnapshots = enableMatrixSnapshots,
            )

            // Spec §4.4 — QA-correlation log line at session-start. Flag-state
            // is observable in logcat regardless of render-path active. Same
            // line in release (both flags forced false) confirms by absence
            // that observed behavior is legacy + stable.
            // NOTE: RovaLog has no .i() — use .d() (same D-deviation as Phase 6.1a).
            RovaLog.d {
                "DualShot renderer mode: " +
                    "path=${if (config.useFirstPrinciplesRender) "v2-first-principles" else "legacy"}, " +
                    "snapshots=${if (config.enableMatrixSnapshots) "ENABLED" else "disabled"}, " +
                    "sensorOrientation=${config.sensorOrientation}, " +
                    "displayRotation=${config.displayRotation}, " +
                    "lensFacing=${config.lensFacing}, " +
                    "sourceSize=${config.cameraInputWidth}x${config.cameraInputHeight}"
            }

            currentDualRecorder = com.aritr.rova.service.dualrecord.DualVideoRecorder(config)
            // Phase 6.1c — replay any UI-registered preview surfaces onto
            // the new recorder. Survives camera flip / mode change without
            // re-creating the TextureViews in the UI. Snapshot inside
            // the synchronized block to avoid ConcurrentModificationException
            // if a TextureView callback fires during the replay iteration.
            val snapshot = synchronized(pendingPreviewSurfaces) {
                pendingPreviewSurfaces.toMap()
            }
            snapshot.forEach { (side, triple) ->
                currentDualRecorder?.attachPreviewInput(side, triple.first, triple.second, triple.third)
            }

            // ADR-0009 (PORTRAIT-stretch architectural fix) — pick the
            // largest 4:3 landscape source mode the device exposes
            // (PORTRAIT pixel-perfect when shortEdge ≥ 1920; PORTRAIT
            // upscales otherwise — both no-stretch, no-bars). The 4:3
            // source feeds AspectFitMath.buildSideAspectCrop's re-derived
            // per-side crops (PORTRAIT pivot-scale(27/64, 1, 1) +
            // LANDSCAPE pivot-scale(1, 3/4, 1) — see ADR-0009). Pre-fix
            // this block forced 1920×1080 (16:9) to dodge CameraX's
            // ~44% FOV center-crop default; the 4:3 strategy now dodges
            // it differently while also giving PORTRAIT a true 9:16
            // crop instead of a 1:1 square stretched into 9:16.
            //
            // setTargetRotation(ROTATION_0) keeps the camera producing
            // sensor-native landscape orientation — we own rotation
            // correction in the EglRouter/AspectFitMath pipeline.
            val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionStrategy(
                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                        cameraInputSize,
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                )
                .setAspectRatioStrategy(
                    androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                )
                .build()
            preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()
            val useDummy = currentSurfaceProvider == null
            val surfaceProvider = currentSurfaceProvider ?: createDummySurfaceProvider()
            preview?.setSurfaceProvider(surfaceProvider)
            RovaLog.d { "setupDualCamera: SurfaceProvider=${if (useDummy) "DUMMY" else "UI"}" }

            val ucg = UseCaseGroup.Builder()
                .addUseCase(preview!!)
                .addEffect(currentDualRecorder!!.asCameraEffect())
                .build()

            try {
                provider.unbindAll()
                RovaLog.d { "setupDualCamera: Binding to lifecycle (UseCaseGroup)" }
                camera = provider.bindToLifecycle(
                    this@RovaRecordingService,
                    currentCameraSelector,
                    ucg
                )
                camera?.let { observeCameraState(it) }
                configuredResolution = "FHD"
                boundToDummy = useDummy
                // Bump the monotonic generation (see cameraConfigGeneration) so a
                // single→P+L→single sequence keeps the flip latch conflation-proof.
                _serviceState.update { it.copy(isCameraActive = true, cameraConfigGeneration = it.cameraConfigGeneration + 1) }
                RovaLog.d { "setupDualCamera: Camera binding COMPLETED. boundToDummy=$boundToDummy" }
                applyFlashState()
            } catch (e: Exception) {
                e.printStackTrace()
                RovaLog.e("setupDualCamera: Binding failed", e)
                // Same dangling-state cleanup as setupSingleCamera's catch
                // (see comment there). UseCaseGroup carries the Preview +
                // DualVideoRecorder effect; on bind failure all references
                // need to be cleared so the next setSurfaceProvider call
                // re-launches setupCamera with the freshly-granted
                // permission rather than hot-swapping into an unbound
                // Preview.
                try { provider.unbindAll() } catch (_: Exception) {}
                currentDualRecording?.let { try { it.stop() } catch (_: Exception) {} }
                currentDualRecording = null
                currentDualRecorder?.release()
                currentDualRecorder = null
                markCameraUnbound()
                preview = null
                camera = null
                configuredResolution = null
                boundToDummy = false
                _serviceState.update { it.copy(isCameraActive = false) }
            }
            } finally {
                // Bug 3 — single guaranteed clear for every exit of the bind body.
                markColdAcquire(false)
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

    // Bug 3 — publish the real cold-acquire signal that gates the
    // "Initializing Camera" overlay. Raised at the start of the bind body in
    // setupSingleCamera/setupDualCamera and cleared on EVERY exit path via a
    // try/finally, so the overlay shows only during a genuine cold bind — never
    // on a warm nav-return surface re-swap (ADR-0021).
    private fun markColdAcquire(active: Boolean) {
        _serviceState.update { it.copy(coldAcquireInProgress = active) }
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
    /**
     * Phase 6.1c — DualPreviewZone TextureView attached. Caches the
     * surface in [pendingPreviewSurfaces] so it survives a camera
     * rebind, AND immediately forwards to the live DualVideoRecorder
     * if present.
     */
    fun attachDualPreview(
        side: com.aritr.rova.service.dualrecord.VideoSide,
        surface: android.view.Surface,
        width: Int,
        height: Int,
    ) {
        synchronized(pendingPreviewSurfaces) {
            pendingPreviewSurfaces[side] = Triple(surface, width, height)
        }
        currentDualRecorder?.attachPreviewInput(side, surface, width, height)
    }

    /**
     * Phase 6.1c — DualPreviewZone TextureView detached. Drops the
     * cached surface AND tells the live recorder to remove its target.
     */
    fun detachDualPreview(side: com.aritr.rova.service.dualrecord.VideoSide) {
        synchronized(pendingPreviewSurfaces) {
            pendingPreviewSurfaces.remove(side)
        }
        currentDualRecorder?.detachPreviewInput(side)
    }

    fun flipCamera() {
        // Single source of truth for the flip guard (B6). Blocks mid-recording
        // (a rebind would strand the active Recording) and P+L (DualShot is
        // rear-only — it binds the rear sensor's 4:3 source). The UI disables
        // the flip control in P+L, but enforce it at the service too so a stray
        // binder call / stale event can't strand a rear-only mode on the front
        // selector (UI convention ≠ service invariant). (codex review)
        val targetIsFront = currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA
        if (!LensFlipPolicy.shouldAllowFlip(
                mode = currentMode,
                isRecording = _serviceState.value.isRecording,
                targetIsFront = targetIsFront,
                hasFrontCamera = hasFrontCamera(),
            )
        ) {
            RovaLog.d { "flipCamera: Ignored — disallowed (recording, P+L rear-only, or no front camera)" }
            return
        }
        currentCameraSelector = if (targetIsFront) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        // B6 — persist the new lens choice so it survives process death / next
        // cold start (backed-up pref). Reflect into service state for the UI
        // icon/contentDescription swap.
        RovaSettings(this).preferFrontCamera = targetIsFront
        _serviceState.update { it.copy(isFrontCamera = targetIsFront) }
        serviceScope.launch { forceReconfigureCamera() }
    }

    /**
     * B6 — whether this device exposes a front sensor. Cheap PackageManager
     * feature probe (API 9+); gates the flip-to-front path and seeds/restores
     * the persisted lens so a front choice is never bound on a front-less
     * device (codex review — would strand the preview black and repeat across
     * launches).
     */
    private fun hasFrontCamera(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)

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
            RovaLog.d { "setMode: Ignored — recording in progress" }
            return
        }
        // Skip a redundant unbind/rebind when the requested mode is already the
        // bound config (a ~2s camera-ready gap otherwise). The UI mode state is
        // ViewModel-owned and already updated before this call, so skipping the
        // camera reconfigure cannot desync it.
        if (ModeReconfigurePolicy.shouldSkipReconfigure(
                requestedMode = mode,
                currentMode = currentMode,
                isCameraActive = _serviceState.value.isCameraActive,
                isFrontCamera = currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
            )
        ) {
            RovaLog.d { "setMode: already in mode '$mode' with camera active — skipping redundant reconfigure" }
            return
        }
        currentMode = mode
        // Phase 6.1b smoke-fix #6 — P+L is rear-only. If the user was on
        // the front camera and selects P+L, snap back to rear here so
        // forceReconfigureCamera below rebinds with the correct selector.
        // The RecordScreen flip button is also disabled in P+L mode
        // (RecordCameraControls.flipEnabled) so the user can't re-enter
        // the front-cam state until they leave P+L.
        if (mode == "PortraitLandscape") {
            if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                RovaLog.d { "setMode: P+L selected on front camera — auto-snapping to rear" }
                currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                // B6 — reflect the in-memory snap into service state so the flip
                // icon shows "rear" while in P+L. DELIBERATELY does NOT write the
                // preferFrontCamera pref: the snap is transient/mode-driven, so
                // leaving Portrait→P+L→Portrait restores the user's front choice
                // (re-seeded in the else branch below).
                _serviceState.update { it.copy(isFrontCamera = false) }
            }
        } else {
            // B6 (codex review) — entering a single mode (Portrait/Landscape):
            // restore the persisted lens (front only if the device has one) so a
            // P+L excursion never leaves the selector and the pref inconsistent.
            // Idempotent for Portrait↔Landscape switches.
            val front = LensFlipPolicy.resolveIsFront(
                RovaSettings(this).preferFrontCamera, hasFrontCamera()
            )
            currentCameraSelector = if (front) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            _serviceState.update { it.copy(isFrontCamera = front) }
        }
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
        RovaLog.d { "createDummySurfaceProvider: headless surface ready (api ${Build.VERSION.SDK_INT})" }
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

        // Layer 4 — thermal gate (Phase 4 Slice 3, ADR-0016).
        // Between-segment check (parallel to LOW_STORAGE Layer 3 — spec §3
        // non-goal: no mid-segment interrupt). Reads the push-listener-fed
        // thermalStatusSignal value. Triggers at CRITICAL or above; SEVERE
        // is intentionally the active-HUD banner's user-driven Stop affordance
        // (not auto-stop).
        val thermal = (applicationContext as? RovaApp)
            ?.thermalStatusSignal?.state?.value
            ?: com.aritr.rova.ui.signals.ThermalStatus.NONE
        if (com.aritr.rova.service.SegmentGateThermal.shouldTerminate(thermal)) {
            RovaLog.w("checkSegmentGates: thermal=$thermal at or above CRITICAL — terminating")
            return SegmentGateResult.Terminate(com.aritr.rova.data.StopReason.THERMAL)
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
                RovaLog.d { "recordSegment: layer-1 prerequisite missed; AbortNoOp" }
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

        // Phase 6.1b — dual dispatch: P+L mode uses DualVideoRecorder path.
        if (currentMode == "PortraitLandscape") {
            return recordSegmentDual()
        }

        var videoFile: File? = null
        // i18n-opt-out: failRecording() messages are dual-purpose diagnostics —
        // logged via RovaLog.e AND surfaced; callers pass internal-identifier
        // text ("…startPeriodicRecording must run first", "Session directory
        // missing for $sessionId") that is developer-facing context, not the
        // mockup-driven notification copy contract. Not externalized (B3 task 9).
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
            RovaLog.d { "recordSegment: Preparing file: ${videoFile?.absolutePath}" }

            // PR-α (ADR-0029 §Decision 2,3) — freeze THIS segment's rotation at its
            // start. Under Auto the device may have snapped since the last clip. If a
            // real stable snap exists use it; else fall back deterministically
            // (last persisted -> current snapped/seed -> portrait) and log the source.
            val segmentRotation: Int = if (hasStableSnap) {
                orientationSnapState.stable
            } else {
                val fb = firstSampleFallback(
                    lastEffective = lastEffectiveTargetRotation,
                    snappedDisplayRotation = orientationSnapState.stable,
                )
                RovaLog.d { "recordSegment: orientation first-sample fallback -> ${fb.rotation} (${fb.source})" }
                fb.rotation
            }
            _serviceState.update {
                it.copy(currentSegmentRotation = segmentRotation, pendingNextRotation = segmentRotation)
            }

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
                        RovaLog.d { "Recording STARTED" }
                    }
                    is VideoRecordEvent.Finalize -> {
                        currentRecording = null
                        val success = !event.hasError() &&
                            videoFile?.exists() == true &&
                            (videoFile?.length() ?: 0L) > 0L
                        if (success) {
                            RovaLog.d { "Recording FINALIZED. Size: ${videoFile?.length()} bytes" }
                            updateNotification(
                                getString(
                                    R.string.notification_segment_saved,
                                    (videoFile?.length() ?: 0L) / 1024
                                )
                            )
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
                                    durationMs = capturedDurationMs,
                                    effectiveTargetRotation = segmentRotation,
                                )
                                pendingPersistJobs.add(deferred)
                                // PR-α (ADR-0029 §Decision 2) — carry this clip's
                                // rotation forward; next segment's fallback prefers it.
                                lastEffectiveTargetRotation = segmentRotation
                            }
                        } else {
                            // A stop in flight aborts the in-flight segment mid-capture; CameraX
                            // finalizes it with ERROR_NO_VALID_DATA (or an empty file, no error).
                            // That is the EXPECTED outcome of a user/loop stop, not a capture
                            // failure — surfacing it flashed a false "No video data was captured"
                            // snackbar (~4s) while the merge of the valid prior segments succeeded.
                            // Suppress exactly that case; genuine failures (other error codes, or
                            // any no-data outside a stop) still surface. (FinalizeErrorPolicy.)
                            val suppress = FinalizeErrorPolicy.shouldSuppress(
                                stopInFlight = stopRequested || userStopRequested,
                                hasError = event.hasError(),
                                isNoValidData = event.hasError() &&
                                    event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA,
                            )
                            if (suppress) {
                                RovaLog.d { "Final segment aborted by stop (no valid data) — expected, not surfaced" }
                            } else {
                                val errorMsg = if (event.hasError()) {
                                    describeRecordingError(event.error)
                                } else {
                                    getString(R.string.notification_empty_segment)
                                }
                                RovaLog.e("Recording ERROR: $errorMsg")
                                _serviceState.update { it.copy(recordingError = errorMsg) }
                                updateNotification(errorMsg)
                            }
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

            RovaLog.d { "recordSegment: Recording initialized, waiting ${nSeconds}s" }

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

            RovaLog.d { "recordSegment: Stopping recording normally" }
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
            RovaLog.d { "recordSegment: Cancelled" }
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
            val msg = e.message ?: getString(R.string.notification_recording_failed_unexpected)
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

    /**
     * Phase 6.1b — dual-recording path for [recordSegment] when
     * `currentMode == "PortraitLandscape"`. Mirrors the single-mode path
     * structure 1:1 (watchdog, recordingFinalized deferred,
     * CancellationException / Exception catch blocks) so all existing
     * cancellation-safety and recovery invariants apply equally to dual.
     *
     * Per-side failure tolerance: if [DualRecordEvent.Finalize] delivers a
     * null file for one side (side failed mid-write per 6.1a DualMuxer
     * contract), only the present side is persisted. The error field is
     * logged but does NOT abort the segment — [DualMuxer] has already
     * handled the side gracefully.
     *
     * Duration: uses `nSeconds * 1000L` (configured clip length) as the
     * fallback. Unlike CameraX's [VideoRecordEvent.Finalize] which carries
     * `recordingStats.recordedDurationNanos`, [DualRecordEvent.Finalize]
     * carries no timing stats. This matches the `configuredFallbackMs`
     * argument that the single-mode path would use for an early-stopped clip
     * — acceptable for dual until MediaMuxer stats are exposed.
     */
    private suspend fun recordSegmentDual(): SegmentResult {
        val recorder = currentDualRecorder
        val sessionId = currentSessionId
        val sessionDir = currentSessionDir
        if (recorder == null || sessionId == null || sessionDir == null) {
            RovaLog.w("recordSegmentDual: dual recorder / session missing; aborting segment")
            return SegmentResult.RetryableFailure
        }
        if (!sessionDir.exists()) sessionDir.mkdirs()

        // sequence = segmentCount + 1 (pre-increment for this segment's
        // filenames; the outer loop increments segmentCount after Success).
        val seq = segmentCount + 1
        val portraitFile = com.aritr.rova.service.dualrecord.internal.SegmentPathBuilder.build(
            sessionDir, seq, com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT
        )
        val landscapeFile = com.aritr.rova.service.dualrecord.internal.SegmentPathBuilder.build(
            sessionDir, seq, com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE
        )
        val output = com.aritr.rova.service.dualrecord.DualOutput(portraitFile, landscapeFile)
        val durationMs = nSeconds * 1000L

        // R2: Fresh deferreds for this segment's finalize event — same
        // coordination pattern as single-mode so catch blocks work.
        recordingFinalized = CompletableDeferred()
        val recordingResult = CompletableDeferred<Boolean>()

        var watchdogSid: String? = null
        var watchdogArmed = false

        try {
            currentDualRecording = recorder.start(
                outputs = output,
                executor = ContextCompat.getMainExecutor(this),
            ) { event ->
                handleDualVideoEvent(
                    event = event,
                    sessionId = sessionId,
                    portraitFile = portraitFile,
                    landscapeFile = landscapeFile,
                    durationMs = durationMs,
                    recordingResult = recordingResult,
                )
            }

            RovaLog.d { "recordSegmentDual: Recording initialized, waiting ${nSeconds}s" }

            // Watchdog — same arm pattern as single-mode.
            watchdogSid = currentSessionId
            if (watchdogSid != null) {
                val watchdogSeq = nextTickSeq++
                val watchdogTriggerAt =
                    System.currentTimeMillis() + durationMs + WATCHDOG_GRACE_MS
                try {
                    AlarmScheduler.arm(this, watchdogSid, watchdogSeq, watchdogTriggerAt, TickKind.WATCHDOG)
                    watchdogArmed = true
                } catch (e: Exception) {
                    RovaLog.w("recordSegmentDual: watchdog arm failed for $watchdogSid", e)
                }
            }

            delay(durationMs)

            RovaLog.d { "recordSegmentDual: Stopping recording normally" }
            currentDualRecording?.stop()
            currentDualRecording = null

            // Wait for DualRecordEvent.Finalize — same bounded-timeout
            // pattern as single-mode.
            val success = withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { recordingResult.await() }
            if (success == null) {
                RovaLog.w("recordSegmentDual: segment did not finalize within ${FINALIZE_TIMEOUT_MS}ms; deferring to recovery")
                stopNeedsRecovery = true
                if (!recordingFinalized.isCompleted) recordingFinalized.complete(Unit)
                return SegmentResult.Terminated
            }
            return if (success) SegmentResult.Success else SegmentResult.RetryableFailure

        } catch (e: CancellationException) {
            RovaLog.d { "recordSegmentDual: Cancelled" }
            try { currentDualRecording?.stop() } catch (e2: Exception) {}
            currentDualRecording = null

            val finalizedInTime = withContext(NonCancellable) {
                withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { recordingFinalized.await() }
            }
            if (finalizedInTime == null) {
                RovaLog.w("recordSegmentDual: cancelled segment did not finalize within ${FINALIZE_TIMEOUT_MS}ms; deferring to recovery")
                stopNeedsRecovery = true
                if (!recordingFinalized.isCompleted) recordingFinalized.complete(Unit)
            }
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            RovaLog.e("recordSegmentDual: Exception: ${e.message}", e)

            val finalizedInTime = withContext(NonCancellable) {
                withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { recordingFinalized.await() }
            }
            if (finalizedInTime == null) {
                if (!recordingFinalized.isCompleted) recordingFinalized.complete(Unit)
                RovaLog.w("recordSegmentDual: exception with no finalize callback within ${FINALIZE_TIMEOUT_MS}ms; deferring to recovery")
            } else {
                RovaLog.w("recordSegmentDual: exception after finalize callback; deferring to recovery")
            }
            stopNeedsRecovery = true
            val msg = e.message ?: "Dual recording failed unexpectedly"
            updateNotification(msg)
            _serviceState.update { it.copy(recordingError = msg) }
            return SegmentResult.Terminated
        } finally {
            if (watchdogArmed && watchdogSid != null) {
                try {
                    AlarmScheduler.cancel(this, watchdogSid, TickKind.WATCHDOG)
                } catch (e: Exception) {
                    RovaLog.w("recordSegmentDual: watchdog cancel failed for $watchdogSid", e)
                }
            }
        }
    }

    /**
     * Phase 6.1b — [DualRecordEvent] callback for [recordSegmentDual].
     * Invoked on the executor thread provided to [DualVideoRecorder.start].
     *
     * On [DualRecordEvent.Finalize]: persists up to 2 [SegmentRecord]s (one
     * per side present) via [SessionStore.appendSegment] on [Dispatchers.IO].
     * Each persist job is wrapped in [serviceScope].async and added to
     * [pendingPersistJobs] so that [stopPeriodicRecordingAndMerge]'s
     * `awaitAll` fence covers both sides — matching the single-mode fix from
     * Phase 1.2 (documented at lines 1765-1769).
     *
     * Note: [SessionStore.submitPersistFinalizedSegment] is not used here
     * because it does not accept a [com.aritr.rova.service.dualrecord.VideoSide]
     * field. Each async block builds the [SegmentRecord] inline (sha1 + length
     * on IO) and calls [SessionStore.appendSegment], returning the record so
     * the Deferred carries [SegmentRecord] to match [pendingPersistJobs]'s type.
     *
     * Per-side failure tolerance: a null file means that side failed
     * mid-write (per 6.1a DualMuxer contract); only non-null sides are
     * persisted. A non-null [DualRecordEvent.Finalize.error] is logged but
     * does not abort — [DualMuxer] has already handled the side gracefully.
     *
     * Both-null + error: treated as [SegmentResult.RetryableFailure] (success=false)
     * so the per-segment retry budget eventually terminates the session.
     * The service's "tolerant" dual-record theme intentionally avoids
     * immediately setting [stopNeedsRecovery] for a single-segment mux failure.
     */
    private fun handleDualVideoEvent(
        event: com.aritr.rova.service.dualrecord.DualRecordEvent,
        sessionId: String,
        portraitFile: java.io.File,
        landscapeFile: java.io.File,
        durationMs: Long,
        recordingResult: CompletableDeferred<Boolean>,
    ) {
        when (event) {
            is com.aritr.rova.service.dualrecord.DualRecordEvent.Start -> {
                RovaLog.d { "recordSegmentDual: DualRecord Start" }
            }
            is com.aritr.rova.service.dualrecord.DualRecordEvent.Status -> {
                // Reserved — currently no status display for dual recording bytes
                // (matches existing single-mode VideoRecordEvent.Status no-op).
            }
            is com.aritr.rova.service.dualrecord.DualRecordEvent.Finalize -> {
                // Persist up to 2 SegmentRecords (one per side present); single-side
                // failure tolerance per 6.1a DualMuxer contract.
                //
                // T9b fix: each side's persist is wrapped in serviceScope.async so
                // stopPeriodicRecordingAndMerge's pendingPersistJobs.awaitAll() fence
                // covers both sides. A bare launch would not be tracked, silently
                // dropping in-flight dual persists if the service stops mid-write.
                val success = event.portraitFile != null || event.landscapeFile != null
                val pFile = event.portraitFile
                if (pFile != null) {
                    val portraitDeferred = serviceScope.async(Dispatchers.IO) {
                        val rec = com.aritr.rova.data.SegmentRecord(
                            filename = pFile.name,
                            durationMs = durationMs,
                            sizeBytes = pFile.length(),
                            sha1 = com.aritr.rova.data.SessionStore.sha1Of(pFile),
                            side = com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT,
                        )
                        sessionStore.appendSegment(sessionId, rec)
                        rec
                    }
                    pendingPersistJobs.add(portraitDeferred)
                }
                val lFile = event.landscapeFile
                if (lFile != null) {
                    val landscapeDeferred = serviceScope.async(Dispatchers.IO) {
                        val rec = com.aritr.rova.data.SegmentRecord(
                            filename = lFile.name,
                            durationMs = durationMs,
                            sizeBytes = lFile.length(),
                            sha1 = com.aritr.rova.data.SessionStore.sha1Of(lFile),
                            side = com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE,
                        )
                        sessionStore.appendSegment(sessionId, rec)
                        rec
                    }
                    pendingPersistJobs.add(landscapeDeferred)
                }
                if (event.error != null) {
                    RovaLog.w("recordSegmentDual: DualRecord Finalize error: ${event.error}", event.error)
                }
                if (!recordingResult.isCompleted) {
                    recordingResult.complete(success)
                }
                if (!recordingFinalized.isCompleted) {
                    recordingFinalized.complete(Unit)
                }
            }
        }
    }

    private fun describeRecordingError(errorCode: Int): String = when (errorCode) {
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> getString(R.string.notification_error_insufficient_storage)
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> getString(R.string.notification_error_source_inactive)
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> getString(R.string.notification_error_file_size_limit)
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> getString(R.string.notification_error_no_valid_data)
        VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> getString(R.string.notification_error_garbage_collected)
        else -> getString(R.string.notification_error_generic, errorCode)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)

        // Legacy channel — kept registered (and unused) so existing user
        // importance/sound overrides are not nuked silently on M5 install.
        // Cleanup deletion is a follow-on slice after one release.
        val legacy = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_legacy_name),
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(legacy)

        // M5 §4 — new dual-channel topology.
        val session = NotificationChannel(
            NotificationChannelConfig.SESSION_CHANNEL_ID,
            getString(R.string.notification_channel_session_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_session_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(session)

        val complete = NotificationChannel(
            NotificationChannelConfig.COMPLETE_CHANNEL_ID,
            getString(R.string.notification_channel_complete_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_complete_desc)
            setShowBadge(true)
        }
        mgr.createNotificationChannel(complete)
    }

    /**
     * M5 §5 — per-state builder. Derives channel, accent, icon, progress,
     * and action specs from [state] via the pure-helper extension functions;
     * routes every action intent through [buildActionIntent] so the activity
     * handles stop and share without the service touching chooser APIs.
     */
    private fun createNotification(state: NotificationState, sessionId: String?): Notification {
        val plan = state.toBindPlan()
        val channelId = state.toChannelId()
        val ongoing = !state.isDismissible()
        val autoCancel = state.isDismissible()

        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val collapsed = renderRemoteView(plan, expanded = false)
        val expanded = renderRemoteView(plan, expanded = true)

        // M5 Phase 3 §3.2: drop setColorized — title color + dots + green
        // border on notif_surface_complete carry the celebratory signal.
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(plan.title.resolve(this))
            .setContentText(plan.body.resolve(this))
            .setSmallIcon(plan.iconRes)
            .setColor(plan.accent)
            .setContentIntent(openPendingIntent)
            .setOngoing(ongoing)
            .setAutoCancel(autoCancel)
            .setOnlyAlertOnce(!plan.isComplete)
            .setVisibility(
                if (plan.isComplete) NotificationCompat.VISIBILITY_PRIVATE
                else NotificationCompat.VISIBILITY_PUBLIC
            )
            .setShowWhen(plan.isComplete)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)

        state.toActionSpecs().forEach { spec ->
            val intent = buildActionIntent(spec, sessionId)
            val pendingIntent = PendingIntent.getActivity(
                this,
                spec.key.ordinal + 100,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val action = NotificationCompat.Action.Builder(spec.iconRes, getString(spec.labelRes), pendingIntent)
                .build()
            builder.addAction(action)
        }

        return builder.build()
    }

    private fun renderRemoteView(plan: NotificationBindPlan, expanded: Boolean): RemoteViews {
        val layoutRes = if (expanded) plan.layoutExpandedRes else plan.layoutCollapsedRes
        val rv = RemoteViews(packageName, layoutRes)

        // B3 i18n task 9b: resolve UiText title/body once at the Context edge.
        val title = plan.title.resolve(this)
        val body = plan.body.resolve(this)

        // Surface drawable — runtime swap between default + complete variant.
        rv.setInt(R.id.notif_root, "setBackgroundResource", plan.surfaceRes)

        // Title + optional explicit color (MergeComplete only).
        rv.setTextViewText(R.id.notif_title, title)
        plan.titleColor?.let { rv.setTextColor(R.id.notif_title, it) }
        // WCAG 2.2 AA SC 1.1.1 (ADR-0020, NOTI-05): explicit CD on the custom
        // notification title so TalkBack names it in SystemUI's process.
        rv.setContentDescription(R.id.notif_title, title)

        if (expanded) {
            rv.setTextViewText(R.id.notif_body, body)

            // Chronometer / body toggle (Phase 3.1 §4.2).
            // ClipRecording + GapWaiting → Chronometer visible, body hidden.
            // Merging + MergeComplete    → body visible, Chronometer hidden.
            val chrono = plan.chrono
            if (chrono != null) {
                rv.setViewVisibility(R.id.notif_body, View.GONE)
                rv.setViewVisibility(R.id.notif_chrono, View.VISIBLE)
                rv.setChronometer(R.id.notif_chrono, chrono.baseElapsedMs, null, true)
                rv.setBoolean(R.id.notif_chrono, "setCountDown", true)
                // NOTI-05: a raw ticking Chronometer reads as bare digits; the
                // body line ("Clip 2 · recording") is the meaningful label, so
                // borrow it as the timer's content description.
                rv.setContentDescription(R.id.notif_chrono, body)
            } else {
                rv.setViewVisibility(R.id.notif_chrono, View.GONE)
                rv.setViewVisibility(R.id.notif_body, View.VISIBLE)
            }

            // Dots row (expanded only). Phase 3.1 §2 + §4.3:
            //   - setColorFilter tints the src drawable (Phase 3.1 fix: src not background)
            //   - setFloat(weightSum) makes visible pills fill the row regardless of count
            val dots = plan.dots
            if (!dots.visible) {
                rv.setViewVisibility(R.id.notif_dots_row, View.GONE)
            } else {
                rv.setViewVisibility(R.id.notif_dots_row, View.VISIBLE)
                // WCAG 2.2 AA SC 4.1.2 (ADR-0020, NOTI-06): the dot pills are
                // unlabeled ImageViews. DotsPlan already computes a spoken
                // summary ("Clip 3 of 6", "All 6 clips complete") — wire it to
                // the row so the group is announced as one unit.
                // B3 i18n task 9b: null CD preserves the old "" sentinel byte-for-byte.
                rv.setContentDescription(R.id.notif_dots_row, dots.contentDescription?.resolve(this) ?: "")
                val pills = dots.pills
                val visibleCount = pills.size.coerceAtMost(8)
                rv.setFloat(R.id.notif_dots_row, "setWeightSum", visibleCount.toFloat())

                val slotIds = intArrayOf(
                    R.id.notif_dot_0, R.id.notif_dot_1, R.id.notif_dot_2, R.id.notif_dot_3,
                    R.id.notif_dot_4, R.id.notif_dot_5, R.id.notif_dot_6, R.id.notif_dot_7
                )
                val containerIds = intArrayOf(
                    R.id.notif_dot_0, R.id.notif_dot_1, R.id.notif_dot_2, R.id.notif_dot_3,
                    R.id.notif_dot_4, R.id.notif_dot_5, R.id.notif_dot_6, R.id.notif_dot_7_container
                )

                for (i in 0 until 8) {
                    if (i < visibleCount) {
                        rv.setViewVisibility(containerIds[i], View.VISIBLE)
                        val pill = pills[i]
                        val color = when (pill.kind) {
                            DotState.Kind.DONE -> dots.accent
                            DotState.Kind.CURRENT -> (dots.accent and 0x00FFFFFF) or 0x66000000
                            DotState.Kind.TODO -> 0x1FFFFFFF
                            DotState.Kind.COUNT_PILL -> 0x14FFFFFF
                        }
                        rv.setInt(slotIds[i], "setColorFilter", color)
                    } else {
                        rv.setViewVisibility(containerIds[i], View.GONE)
                    }
                }

                // Count-pill label overlay: visible only when last visible pill is COUNT_PILL
                val lastPill = pills.lastOrNull()
                if (lastPill?.kind == DotState.Kind.COUNT_PILL && lastPill.countLabel != null) {
                    rv.setTextViewText(R.id.notif_dot_count_label, lastPill.countLabel)
                    rv.setViewVisibility(R.id.notif_dot_count_label, View.VISIBLE)
                } else {
                    rv.setViewVisibility(R.id.notif_dot_count_label, View.GONE)
                }
            }

            bindProgress(rv, plan.progress)
        } else {
            if (plan.collapsedTail != null) {
                rv.setTextViewText(R.id.notif_tail, plan.collapsedTail)
                rv.setViewVisibility(R.id.notif_tail, View.VISIBLE)
            } else {
                rv.setViewVisibility(R.id.notif_tail, View.GONE)
            }
        }

        return rv
    }

    private fun bindProgress(rv: RemoteViews, progress: NotificationProgress?) {
        if (progress == null) {
            rv.setViewVisibility(R.id.notif_progress, View.GONE)
            return
        }
        rv.setProgressBar(R.id.notif_progress, progress.max, progress.current, progress.indeterminate)
        rv.setViewVisibility(R.id.notif_progress, View.VISIBLE)
        val cd = if (progress.indeterminate) {
            getString(R.string.notification_progress_cd_indeterminate)
        } else {
            val percent = if (progress.max > 0) (progress.current * 100 / progress.max) else 0
            getString(R.string.notification_progress_cd_determinate, percent)
        }
        rv.setContentDescription(R.id.notif_progress, cd)
    }

    private fun buildActionIntent(spec: NotificationActionSpec, sessionIdContext: String?): Intent {
        return when (spec.key) {
            NotificationActionKey.STOP, NotificationActionKey.STOP_EARLY ->
                // Reuses the existing user-stop broadcast pipeline.
                // RovaStopReceiver.ACTION_STOP is the canonical entry point;
                // the in-app Stop FAB already routes through it.
                Intent(this, MainActivity::class.java)
                    .setAction(RovaStopReceiver.ACTION_STOP)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            NotificationActionKey.OPEN ->
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            NotificationActionKey.VIEW_IN_LIBRARY ->
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TARGET_TAB, MainActivity.TAB_HISTORY)
                    .also { i -> spec.sessionIdExtra?.let { i.putExtra(MainActivity.EXTRA_SESSION_ID, it) } }
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            NotificationActionKey.SHARE -> {
                // Share routes through MainActivity so chooser construction
                // has access to Activity context.
                Intent(this, MainActivity::class.java)
                    .setAction(ACTION_SHARE_RECORDING)
                    .also { i -> spec.sessionIdExtra?.let { i.putExtra(MainActivity.EXTRA_SESSION_ID, it) } }
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
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
            builder.addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop_legacy),
                stopPendingIntent
            )
        }

        return builder.build()
    }

    private fun updateNotification(contentText: String) {
        // Phase 1.3 — pass the live sessionId so once a session exists the
        // STOP action is attached. notify(SAME_ID, ...) does not restart
        // the FGS deadline; the initial startForeground is sticky.
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            createNotification(
                getString(R.string.notification_title_recording_active),
                contentText,
                currentSessionId
            )
        )
    }

    // M5 §5 — typed overload delegates to the per-state builder.
    // The legacy createNotification(title, body, sessionId) path is
    // preserved for the String-based updateNotification(contentText)
    // overload above; do not remove it.
    private fun updateNotification(state: NotificationState) {
        val now = android.os.SystemClock.elapsedRealtime()
        val previousState = lastNotifiedState
        lastNotifiedState = state
        val sameStateClass = previousState != null && previousState::class == state::class
        if (!sameStateClass) {
            lastSessionNotifyMillis = 0L  // force-emit on transition
        }
        val isSessionChannel = state.toChannelId() == NotificationChannelConfig.SESSION_CHANNEL_ID
        val isHighRate = state is NotificationState.ClipRecording || state is NotificationState.GapWaiting
        if (isSessionChannel && isHighRate) {
            if (now - lastSessionNotifyMillis < MIN_SESSION_NOTIFY_INTERVAL_MS) return
            lastSessionNotifyMillis = now
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            createNotification(state, currentSessionId)
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

        RovaLog.d { "onTaskRemoved: No active background work, stopping service" }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ADR-0021 — symmetric removal of the process-lifecycle observer.
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        releaseResources()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    private fun releaseResources() {
        recordingJob?.cancel()
        stopAndMergeJob?.cancel()
        try { currentRecording?.stop() } catch (e: Exception) {}
        currentRecording = null
        currentDualRecording?.let { try { it.stop() } catch (_: Exception) {} }
        currentDualRecording = null
        currentDualRecorder?.release()
        currentDualRecorder = null
        try { cameraProvider?.unbindAll() } catch (e: Exception) {}
        markCameraUnbound()  // Phase 3.5 — service teardown
        // PR-α (ADR-0029 §Decision 2,3) — service teardown; stop orientation tracking.
        disableOrientationTracking()
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
        currentVaultIntent = false
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
        RovaLog.d { "releaseResources: Resources released" }
    }

    private fun stopPeriodicRecordingAndMerge() {
        if (stopRequested) {
            RovaLog.d { "stopPeriodicRecordingAndMerge: stop already requested, ignoring duplicate call" }
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
                            RovaLog.d {
                                "stopPeriodicRecordingAndMerge: eager USER_STOPPED/$reason" +
                                    " written for $sid (B3)"
                            }
                            updateNotification(
                                when (reason) {
                                    com.aritr.rova.data.StopReason.PERMISSION_REVOKED ->
                                        getString(R.string.notification_stopped_permission_revoked)
                                    com.aritr.rova.data.StopReason.LOW_STORAGE ->
                                        getString(R.string.notification_stopped_low_storage)
                                    com.aritr.rova.data.StopReason.THERMAL ->
                                        getString(R.string.notification_stopped_thermal)
                                    com.aritr.rova.data.StopReason.SCHEDULE_WINDOW ->
                                        getString(R.string.notification_stopped_schedule_window)
                                    else -> getString(R.string.notification_stopping)
                                }
                            )
                        }
                        is com.aritr.rova.data.MarkTerminatedResult.AlreadyTerminal -> {
                            RovaLog.d {
                                "stopPeriodicRecordingAndMerge: $sid already" +
                                    " ${result.existingTerminated}/${result.existingStopReason}; eager write suppressed"
                            }
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
                            updateNotification(getString(R.string.notification_stopped_state_save_failed))
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
                updateNotification(getString(R.string.notification_stopped_recovery_offered))
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
                updateNotification(getString(R.string.notification_state_corrupted, e.message?.take(60)))
                cancelAlarmsAndUnregister()
                @Suppress("DEPRECATION")
                stopForeground(true)
                stopSelf()
                return@launch
            }

            val sessionId = currentSessionId
            val sessionDir = currentSessionDir
            // Phase 6.1b T13: load the manifest once and branch on the
            // session mode. P+L (mode == "PortraitLandscape") goes through
            // performMergeDual, which runs ExportPipeline.export twice (once
            // per side) with independent atomicity per D12. Single-mode
            // (Portrait / Landscape) falls back to the pre-T13 single
            // performMerge call — semantically byte-identical to the prior
            // implementation (same `segments` filter, same call site).
            val manifest: com.aritr.rova.data.SessionManifest? = if (sessionId != null) {
                withContext(Dispatchers.IO) { sessionStore.loadManifest(sessionId) }
            } else null

            if (manifest != null && sessionDir != null && manifest.config.mode == "PortraitLandscape") {
                val portraitSegments = manifest.segments
                    .filter { it.side == com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT }
                    .map { File(sessionDir, it.filename) }
                    .filter { it.exists() && it.length() > 0 }
                val landscapeSegments = manifest.segments
                    .filter { it.side == com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE }
                    .map { File(sessionDir, it.filename) }
                    .filter { it.exists() && it.length() > 0 }

                if (portraitSegments.isNotEmpty() || landscapeSegments.isNotEmpty()) {
                    performMergeDual(portraitSegments, landscapeSegments)
                } else {
                    cancelAlarmsAndUnregister()
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    stopSelf()
                }
            } else {
                // Single-mode path — semantically unchanged from prior impl.
                // Re-uses the `manifest` loaded above (DRY, no second
                // loadManifest call); produces the same `segments` content
                // and the same call to performMerge as before T13.
                val segments: List<File> = if (sessionId != null && sessionDir != null) {
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
    }

    /**
     * Phase 6.1b T13 — sole call-shape wrapper around
     * [ExportPipeline.export]. The Phase-1.7 single-entry gate
     * (`checkExportPipelineSingleEntry`, `app/build.gradle.kts`) requires
     * exactly one literal `ExportPipeline.export(` occurrence across
     * `app/src/main/java/com/aritr/rova/`; this helper holds that sole
     * site so [performMerge] (single-mode) and [performMergeDual]
     * (P+L, two passes) both route through one definition.
     *
     * Call shape MUST match the single-mode invocation byte-for-byte
     * (modulo the [side] arg and the per-call [onProgress] lambda) — the
     * single-mode path's behavior is invariant per the Phase 6.1b
     * "byte-identical single-mode semantics" contract: `context` is
     * `this@RovaRecordingService`, `sessionStore` is the service-injected
     * store, `mediaScanWaiter` uses the default ([AndroidMediaScanWaiter]
     * built from `context`), and the trailing-lambda binding is preserved
     * by the caller's lambda body.
     */
    private suspend fun runExportPipeline(
        sessionId: String,
        sessionDir: File,
        segments: List<File>,
        side: com.aritr.rova.service.dualrecord.VideoSide?,
        onProgress: (Float) -> Unit
    ): ExportResult {
        return ExportPipeline.export(
            context = this@RovaRecordingService,
            sessionStore = sessionStore,
            sessionId = sessionId,
            sessionDir = sessionDir,
            segments = segments,
            side = side,
            // ADR-0024 — pass the frozen tier from the manifest so a SAF-frozen
            // session dispatches to SafExporter rather than the live SDK tier.
            // currentExportTier (field) is cached from m.exportTier at createSession
            // time; it is non-null for any session that has reached runExportPipeline.
            frozenTier = currentExportTier,
            // B5 / ADR-0025 — frozen vault intent; routes to the private vault
            // exporter ahead of the SDK/SAF tiers when the session opted in.
            vaultIntent = currentVaultIntent,
            onProgress = onProgress
        )
    }

    private suspend fun performMerge(segments: List<File>) {
        var mergeSucceeded = false
        try {
            // Bug A — publish the real merge total BEFORE isMerging flips so the
            // in-merge band reads "Merging clip X of <segments.size>" even on an
            // early stop where segmentCount is still 0.
            _serviceState.update {
                it.copy(
                    isMerging = true,
                    mergeProgress = 0f,
                    mergeError = null,
                    mergeClipCount = segments.size
                )
            }
            updateNotification(NotificationState.Merging(done = 0, total = segments.size, mergeProgressPercent = 0))

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

            val result = runExportPipeline(
                sessionId = sid,
                sessionDir = sessionDir,
                segments = segments,
                side = null
            ) { progress ->
                _serviceState.update { it.copy(mergeProgress = progress) }
                updateNotification(NotificationState.Merging(
                    done = (progress * segments.size).toInt().coerceIn(0, segments.size),
                    total = segments.size,
                    mergeProgressPercent = (progress * 100f).toInt().coerceIn(0, 100)
                ))
            }

            when (result) {
                is ExportResult.Success -> {
                    // Bug A — record the real saved-clip count so the post-merge
                    // summary card surfaces it (segmentCount is 0 on an early stop).
                    _serviceState.update { it.copy(exportedClipCount = segments.size) }
                    updateNotification(NotificationState.MergeComplete(
                        clipCount = segments.size,
                        totalDurationSeconds = null, // segments: List<File> — durationMs not available here
                        sessionId = currentSessionId
                    ))
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
                is ExportResult.InsufficientStorage,
                is ExportResult.UnknownSession -> {
                    // No terminal write — manifest is in FAILED (or
                    // intermediate) state. Cold-launch ExportRecoveryRunner
                    // reconciles on next launch (ADR 0003 §Recovery
                    // routing); Phase 1.5 maps the resulting state to a
                    // discard-eligibility flag.
                    val msg = "Export failed: ${result.javaClass.simpleName}"
                    RovaLog.e("performMerge: $msg")
                    _serviceState.update { it.copy(mergeError = msg) }
                    updateNotification(getString(R.string.notification_merge_failed))
                    delay(3000)
                }
                is ExportResult.SafFolderUnavailable -> {
                    // ADR-0024 — folder gone/revoked at export time. SafExporter
                    // already wrote FAILED (no COMPLETED). Raise the flag so the
                    // warning surfaces; recording retained for re-pick/resume.
                    com.aritr.rova.data.RovaSettings(this).saveFolderUnavailable = true
                    val msg = "Export failed: save folder unavailable"
                    RovaLog.e("performMerge: $msg")
                    // B4c — flag the SAF-specific case so the record screen shows
                    // "folder unavailable, recording kept" instead of the generic
                    // merge-failed "Open Library to recover" (no Library entry here).
                    _serviceState.update { it.copy(mergeError = msg, saveFolderUnavailable = true) }
                    updateNotification(getString(R.string.notification_save_folder_unavailable))
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
            updateNotification(getString(R.string.notification_merge_failed_detail, e.message))
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
                                    RovaLog.d { "performMerge: wrote $reason / $stopReason for $sid" }
                                }
                                is com.aritr.rova.data.MarkTerminatedResult.AlreadyTerminal -> {
                                    RovaLog.d {
                                        "performMerge: $sid already" +
                                            " ${result.existingTerminated}/${result.existingStopReason};" +
                                            " merge-success suppressed"
                                    }
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
                                    updateNotification(getString(R.string.notification_merge_state_save_failed))
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

    /**
     * Phase 6.1b T13 — P+L variant of [performMerge]. Drives two
     * independent [ExportPipeline.export] passes (PORTRAIT then LANDSCAPE)
     * and writes the single terminal record once both sides settle.
     *
     * Independent atomicity per D12: the LANDSCAPE pass runs even when
     * PORTRAIT failed (and vice versa). Per-side success/failure routes
     * through the per-side mutators (T11/T12); the shared `exportState`
     * is advanced to `FINALIZED` here only when at least one side
     * succeeded — calling `setExportFailed` would wipe the per-side
     * pointers needed by Task 14 recovery, so the both-sides-failed case
     * leaves shared `exportState` untouched and defers to recovery
     * (TODO T17: explicit shared-FAILED write that preserves per-side
     * fields, or per-side `exportState` split).
     *
     * Progress is split 0–50% portrait / 50–100% landscape.
     *
     * The terminal markTerminated write fires once per session regardless
     * of per-side outcome, mirroring [performMerge]'s contract — partial
     * success is allowed (manifest reflects it via the per-side fields).
     * On both-sides-failure no terminal write is performed; recovery on
     * next launch picks up the failed sides per Task 14.
     */
    private suspend fun performMergeDual(
        portraitSegments: List<File>,
        landscapeSegments: List<File>
    ) {
        var portraitOk = false
        var landscapeOk = false
        // Bug A — one logical P+L session = one clip count (matches the dual
        // MergeComplete notification, which uses max not sum). Computed up here
        // so it can seed mergeClipCount BEFORE isMerging flips true.
        val dualClipCount = maxOf(portraitSegments.size, landscapeSegments.size)
        try {
            _serviceState.update {
                it.copy(
                    isMerging = true,
                    mergeProgress = 0f,
                    mergeError = null,
                    mergeClipCount = dualClipCount
                )
            }
            // Phase 6.1b smoke-fix #5 — notification clip count is the
            // user-facing per-side count (the loop count), NOT the sum of
            // both sides. Owner reported "Merging 4 clips" for a 2-loop
            // P+L recording — the 4 was portrait(2) + landscape(2). Both
            // sides have the same loop count in practice; `max` is
            // defensive against degenerate tolerant-mode cases where one
            // side has all-failed segments and the count is asymmetric.
            // Mirrors the single-mode "X clips saved" semantic
            // (NotificationCopy "$clipCount clips saved to Library"):
            // X always means "clips within the saved content", not "raw
            // segments across all output streams."
            val userClipCount = dualClipCount
            updateNotification(NotificationState.Merging(done = 0, total = userClipCount, mergeProgressPercent = 0))

            val sid = currentSessionId
            val sessionDir = currentSessionDir
            if (sid == null || sessionDir == null) {
                RovaLog.w("performMergeDual: no active session; skipping export")
                _serviceState.update { it.copy(mergeError = "No active session") }
                return
            }
            if (!sessionDir.exists()) sessionDir.mkdirs()

            // Portrait pass
            if (portraitSegments.isNotEmpty()) {
                val portraitResult = runExportPipeline(
                    sessionId = sid,
                    sessionDir = sessionDir,
                    segments = portraitSegments,
                    side = com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT
                ) { progress ->
                    _serviceState.update { it.copy(mergeProgress = progress * 0.5f) }
                    // Notification `done` tracks OVERALL merge progress
                    // (0..userClipCount) so the count advances smoothly
                    // across the portrait→landscape boundary. Portrait
                    // owns the 0..50% slice → done in 0..userClipCount/2.
                    val overall = progress * 0.5f
                    updateNotification(
                        NotificationState.Merging(
                            done = (overall * userClipCount).toInt().coerceIn(0, userClipCount),
                            total = userClipCount,
                            mergeProgressPercent = (overall * 100f).toInt().coerceIn(0, 100)
                        )
                    )
                }
                portraitOk = portraitResult is ExportResult.Success
                if (!portraitOk) RovaLog.e("performMergeDual: portrait export failed: $portraitResult")
            }

            // Landscape pass — runs even if portrait failed (independent
            // atomicity per D12). Progress: 50–100%.
            if (landscapeSegments.isNotEmpty()) {
                val landscapeResult = runExportPipeline(
                    sessionId = sid,
                    sessionDir = sessionDir,
                    segments = landscapeSegments,
                    side = com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE
                ) { progress ->
                    _serviceState.update { it.copy(mergeProgress = 0.5f + progress * 0.5f) }
                    // Landscape owns 50..100% → done in userClipCount/2..userClipCount.
                    val overall = 0.5f + progress * 0.5f
                    updateNotification(
                        NotificationState.Merging(
                            done = (overall * userClipCount).toInt().coerceIn(0, userClipCount),
                            total = userClipCount,
                            mergeProgressPercent = (overall * 100f).toInt().coerceIn(0, 100)
                        )
                    )
                }
                landscapeOk = landscapeResult is ExportResult.Success
                if (!landscapeOk) RovaLog.e("performMergeDual: landscape export failed: $landscapeResult")
            }

            // Cleanup successful-side raw segments only; failed-side raws
            // stay on disk for Task 14 recovery to consume.
            withContext(Dispatchers.IO) {
                if (portraitOk) {
                    portraitSegments.forEach {
                        if (!coroutineContext.isActive) throw CancellationException("Post-merge cleanup cancelled")
                        try { it.delete() } catch (_: Exception) {}
                    }
                }
                if (landscapeOk) {
                    landscapeSegments.forEach {
                        if (!coroutineContext.isActive) throw CancellationException("Post-merge cleanup cancelled")
                        try { it.delete() } catch (_: Exception) {}
                    }
                }
            }

            if (portraitOk || landscapeOk) {
                // Phase 6.1b T13 — OQ-C lock: T12's per-side exporters skip
                // the shared setExportFinalized; advance it here so consumers
                // observing the shared exportState see FINALIZED on at least
                // one-side success. setExportFinalized only touches
                // exportState (and optionally privateTempPath, kept here),
                // so the per-side pointers populated by T11/T12 are
                // preserved.
                val sidForFinalize = currentSessionId
                if (sidForFinalize != null) {
                    try {
                        withContext(NonCancellable) {
                            sessionStore.setExportFinalized(sidForFinalize, clearPrivateTempPath = false)
                        }
                    } catch (e: Exception) {
                        RovaLog.e("performMergeDual: shared setExportFinalized threw for $sidForFinalize", e)
                    }
                }
                // Bug A — record the real saved-clip count for the summary card.
                _serviceState.update { it.copy(exportedClipCount = userClipCount) }
                updateNotification(NotificationState.MergeComplete(
                    clipCount = userClipCount,
                    totalDurationSeconds = null, // portraitSegments/landscapeSegments: List<File> — durationMs not available here
                    sessionId = currentSessionId
                ))
                delay(1000)
            } else {
                // TODO T17: when both sides failed, advance shared
                // exportState to FAILED without wiping the per-side
                // pointers (current setExportFailed nulls publicTargetPath /
                // pendingUri / privateTempPath, which kills Task 14
                // diagnostics). Either add a new mutator that flips
                // exportState only, or split exportState per side.
                _serviceState.update { it.copy(mergeError = "Both sides failed") }
                updateNotification(getString(R.string.notification_merge_failed))
                delay(3000)
            }

        } catch (ce: CancellationException) {
            // C6 mirror: keep cancellation semantics intact — must not be
            // converted into a merge-failure UI state. finally still tears
            // the service down.
            throw ce
        } catch (e: Exception) {
            e.printStackTrace()
            _serviceState.update { it.copy(mergeError = e.message) }
            updateNotification(getString(R.string.notification_merge_failed_detail, e.message))
            delay(3000)
        } finally {
            _serviceState.update { it.copy(isMerging = false) }
            // Phase 1.2 / 1.3 / 6.1b T13: write the terminal record before
            // unregister and stopForeground/stopSelf. For P+L, the terminal
            // write fires when at least one side succeeded (mirroring the
            // single-mode `mergeSucceeded` gate in performMerge — partial
            // success is acceptable; the manifest reflects the partial
            // outcome via the per-side fields). On both-sides failure no
            // terminal write is performed; ExportRecoveryRunner picks up
            // the failed sides on next launch per Task 14.
            //
            // Discriminator (Phase 1.3): userStopRequested distinguishes
            // user-driven stop (USER_STOPPED) from natural completion
            // (COMPLETED). markTerminated runs in NonCancellable on the
            // SessionStore persist dispatcher and is first-writer-wins.
            if (portraitOk || landscapeOk) {
                val sid = currentSessionId
                if (sid != null) {
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
                                    RovaLog.d { "performMergeDual: wrote $reason / $stopReason for $sid" }
                                }
                                is com.aritr.rova.data.MarkTerminatedResult.AlreadyTerminal -> {
                                    RovaLog.d {
                                        "performMergeDual: $sid already" +
                                            " ${result.existingTerminated}/${result.existingStopReason};" +
                                            " merge-success suppressed"
                                    }
                                }
                                is com.aritr.rova.data.MarkTerminatedResult.Failed -> {
                                    RovaLog.e(
                                        "performMergeDual: markTerminated($reason) FAILED" +
                                            " for $sid (attempts=${result.attempts})", result.cause
                                    )
                                    updateNotification(getString(R.string.notification_merge_state_save_failed))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        RovaLog.e("performMergeDual: markTerminated($reason) threw for $sid", e)
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

    /**
     * ADR-0024 — returns true when a usable SAF save folder is configured
     * for this session. P+L sessions always fall back to the SDK tier
     * (SAF is deferred for dual-stream this slice). Checks both the
     * persisted tree URI and the live read+write permission grant.
     */
    private fun hasUsableSafFolder(): Boolean {
        val settings = com.aritr.rova.data.RovaSettings(this)
        val tree = settings.saveLocationTreeUri ?: return false
        // P+L SAF is deferred this slice — P+L sessions fall back to the SDK tier.
        if (settings.mode == "PortraitLandscape") return false
        // B4c — probe write-usability, not just the grant: a folder deleted in a
        // file manager keeps its persisted grant, so a grant-only check would
        // freeze SAF and fail at export. isTargetWritable falls back to the SDK
        // tier here when the folder is gone, so the recording still exports.
        return com.aritr.rova.service.export.SafAndroidOps.isTargetWritable(this, tree)
    }

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
                RovaLog.d { "waitForNextSegment: live delay delivered seq=$seq after ${waitSeconds}s" }
            } else {
                RovaLog.w("waitForNextSegment: live delay could not deliver seq=$seq; channel closed")
            }
        }

        val canRelax = shouldRelaxWakeLock(waitSeconds)
        if (canRelax) {
            releaseWakeLock()
            RovaLog.d { "waitForNextSegment: wakelock released during ${waitSeconds}s alarm-driven wait" }
        }

        // Best-effort notification countdown. May be paused by Doze; the
        // alarm wake is the source of truth for resuming the loop.
        val countdownJob = serviceScope.launch {
            var remaining = waitSeconds
            while (remaining > 0 && kotlinx.coroutines.currentCoroutineContext().isActive) {
                val step = minOf(remaining, WAKE_LOCK_IDLE_UPDATE_STEP_SECONDS)
                _serviceState.update { it.copy(nextRecordingCountdown = remaining.toLong()) }
                updateNotification(NotificationState.GapWaiting(
                    nextNumber = segmentCount + 1,
                    nextInLabel = formatCountdownSeconds(remaining),
                    total = limitLoops.takeIf { it != -1 },
                    nextStartsInSeconds = remaining,
                    gapTotalSeconds = waitSeconds.coerceAtLeast(0)
                ))
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
                        if (s < seq) RovaLog.d { "waitForNextSegment: discarding stale tick seq=$s (waiting for >= $seq)" }
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
            updateNotification(NotificationState.GapWaiting(
                    nextNumber = segmentCount + 1,
                    nextInLabel = "${i}s",
                    total = limitLoops.takeIf { it != -1 },
                    nextStartsInSeconds = i,
                    gapTotalSeconds = waitSeconds.coerceAtLeast(0)
                ))
            _serviceState.update { it.copy(nextRecordingCountdown = i.toLong()) }
            delay(1000)
        }
    }

    private fun shouldRelaxWakeLock(waitSeconds: Int): Boolean {
        if (waitSeconds < WAKE_LOCK_RELAX_THRESHOLD_SECONDS) return false

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoringOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)
        if (!ignoringOptimizations) {
            RovaLog.d { "shouldRelaxWakeLock: Keeping wakelock because battery optimizations are still active" }
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
        RovaLog.d { "acquireWakeLock: Partial wakelock acquired" }
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
            RovaLog.d { "releaseWakeLock: Partial wakelock released" }
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
