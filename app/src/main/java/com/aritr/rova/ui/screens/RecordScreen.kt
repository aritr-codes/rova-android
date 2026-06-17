package com.aritr.rova.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aritr.rova.R
import com.aritr.rova.RovaApp
import android.view.Surface
import com.aritr.rova.service.orientation.OrientationPolicyResolver
import com.aritr.rova.ui.screens.chrome.ChromeMode
import com.aritr.rova.ui.screens.chrome.ChromeOrientation
import com.aritr.rova.ui.screens.chrome.ChromeSlot
import com.aritr.rova.ui.screens.chrome.DeviceLandscape
import com.aritr.rova.ui.screens.chrome.NavEdge
import com.aritr.rova.ui.screens.chrome.RecordChromeLockPolicy
import com.aritr.rova.ui.screens.chrome.chromeMode
import com.aritr.rova.ui.screens.chrome.clusterEdge
import com.aritr.rova.ui.screens.chrome.landscapeSense
import com.aritr.rova.ui.screens.chrome.placementFor
import com.aritr.rova.ui.screens.chrome.shortestPathDelta
import com.aritr.rova.ui.screens.chrome.uiCounterRotationDegrees
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.RovaRecordingService
import com.aritr.rova.service.recovery.RecoveryReport
import com.aritr.rova.ui.recovery.RecoveryViewSource
import com.aritr.rova.utils.RovaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import com.aritr.rova.ui.components.*
import com.aritr.rova.ui.theme.RecordChromeTokens
import com.aritr.rova.ui.components.MergeCompleteCount
import com.aritr.rova.ui.components.RecordHudState
import com.aritr.rova.ui.components.MergeCompleteCard
import com.aritr.rova.ui.warnings.ThermalTipsSheet
import com.aritr.rova.ui.warnings.WarningCenter
import com.aritr.rova.ui.warnings.WarningCenterViewModel
import com.aritr.rova.ui.warnings.buildWarningCenterViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onMergeFinished: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: RecordViewModel = viewModel(),
    settingsViewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val localView = LocalView.current
    val scope = rememberCoroutineScope()

    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val duration by viewModel.duration.collectAsStateWithLifecycle()
    val interval by viewModel.interval.collectAsStateWithLifecycle()
    val loopCount by viewModel.loopCount.collectAsStateWithLifecycle()
    val flashMode by viewModel.flashMode.collectAsStateWithLifecycle()
    val resolution by viewModel.resolution.collectAsStateWithLifecycle()
    val mode by viewModel.topology.collectAsStateWithLifecycle()
    val orientationPolicy by viewModel.orientationPolicy.collectAsStateWithLifecycle()
    val orientationLockRotation by viewModel.orientationLockRotation.collectAsStateWithLifecycle()

    // ADR-0029 (DualShot portrait-lock · codex review 2026-06-10) — DualShot is
    // portrait-only and its EGL preview surfaces (ADR-0009) must not be bound while
    // the window is rotating. Selecting DualShot from a LANDSCAPE Single window
    // otherwise commits the camera rebind concurrently with the landscape→portrait
    // rotation, churning the preview TextureViews mid-bind → EGL_BAD_SURFACE
    // (device-confirmed, only one pane renders). Fix: keep Single bound through the
    // rotation and commit DualShot (the rebind) only once the window has settled to
    // portrait. `pendingMode` holds the requested-but-not-yet-committed mode; the
    // orientation request below is driven off `desiredMode` so the window starts
    // rotating immediately, while `viewModel.setTopology` fires only after
    // `isPortrait` flips true. See [DualShotPortraitGate].
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    var pendingMode by remember { mutableStateOf<String?>(null) }
    val desiredMode = pendingMode ?: mode
    LaunchedEffect(pendingMode, isPortrait) {
        val pm = pendingMode
        if (pm != null && isPortrait) {
            viewModel.setTopology(pm)
            pendingMode = null
        }
    }

    val combinedOpen by viewModel.combinedSettingsOpen.collectAsStateWithLifecycle()
    val allPresets by viewModel.allPresets.collectAsStateWithLifecycle()
    val activePresetId by viewModel.activePresetId.collectAsStateWithLifecycle()

        // Phase 4.1c — one shared WarningCenterViewModel instance feeds both
        // WarningCenter mounts (idle + active HUD) AND the Settings sheet's
        // "Reset snoozed warnings" row. Constructed via the same factory the
        // standalone WarningCenter would use, so the persistence wiring is
        // identical.
        val warningCenterApp = remember(context) { context.applicationContext as RovaApp }
        val warningVm: WarningCenterViewModel = viewModel(
            key = "WarningCenterViewModel",
            factory = viewModelFactory {
                initializer { buildWarningCenterViewModel(warningCenterApp) }
            },
        )
        val snoozedSet by warningVm.snoozedForever.collectAsStateWithLifecycle()

        // Phase 4 Slice 3 — host state for the thermal tips bottom sheet.
        // rememberSaveable so it survives configuration changes (e.g. rotation
        // while the sheet is open). The lambda is passed to both WarningCenter
        // mounts so either the Idle echo banner or (future) the active HUD can
        // trigger it.
        var showTipsSheet by rememberSaveable { mutableStateOf(false) }

        // Phase 4.3 — session id pending CANT_MERGE resolution (C2.4 sheet).
        // Null when no recovery merge has failed pre-flight with InsufficientStorage.
        val pendingSid by warningVm.pendingCantMergeSessionId.collectAsStateWithLifecycle()

        // Phase 4 Slice 2 — refresh the auto-stop echo signal on ON_RESUME
        // so a session that auto-stopped while the user was backgrounded
        // surfaces as soon as they return. The signal is reason-agnostic;
        // WarningPrecedence filters to LOW_STORAGE.
        val autoStopLifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(autoStopLifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    warningCenterApp.autoStopEchoSignal.refresh()
                }
            }
            autoStopLifecycleOwner.lifecycle.addObserver(observer)
            onDispose { autoStopLifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // B1 — resume-reseed recording defaults from prefs so an edit made in App
        // Settings is reflected here and a stepper nudge cannot clobber it.
        // Registered before the storage-signal recompute effect so the reseed
        // lands before recompute reads viewModel.duration.value on the same resume.
        val recordLifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(recordLifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.reloadRecordingDefaults()
                }
            }
            recordLifecycleOwner.lifecycle.addObserver(observer)
            onDispose { recordLifecycleOwner.lifecycle.removeObserver(observer) }
        }

    // Slice 2 — read-only echo of the existing app-level recovery
    // report. No new RecoveryViewModel ownership; the History tab
    // remains the sole owner of Discard. RovaApp.instance is set in
    // onCreate; under previews / tests it may be null, in which case
    // the banner stays hidden.
    val rovaApp = remember { context.applicationContext as? RovaApp }
    val emptyRecoveryFlow = remember { MutableStateFlow<RecoveryReport?>(null) }
    val recoveryReport by (rovaApp?.recoveryReport ?: emptyRecoveryFlow)
        .collectAsStateWithLifecycle()
    // Slice 2 review fix — count must mirror the eligibility filter
    // History applies (RecoveryUiStateMapper). Raw classifications.size
    // would expose BLOCKED / AUTO_DISCARD_ELIGIBLE / COMPLETED rows
    // that History never renders, so the echo could light up while
    // History had nothing to show.
    //
    // The eligibility check pays the same per-session loadManifest
    // disk read RecoveryViewModel pays (exists / length / readText /
    // JSON parse — see SessionStore.loadManifest). It MUST run off the
    // main thread; doing it inside `remember { ... }` would block the
    // Compose composition pass each time the recovery report flips.
    // produceState carries the IO into a coroutine pinned to
    // Dispatchers.IO and republishes the result as Compose state.
    // The producer is keyed on `recoveryReport` and `rovaApp` so the
    // load only re-runs when the source flow emits a new report
    // (typically once per process lifetime, on cold-start scan).
    val interruptedSessionCount by produceState(
        initialValue = 0,
        key1 = recoveryReport,
        key2 = rovaApp
    ) {
        val store = rovaApp?.takeIf { it.videosRoot != null }?.sessionStore
        if (store == null) {
            value = 0
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            RecoveryViewSource.eligibleSessionCount(
                report = recoveryReport,
                dismissedIds = emptySet(),
                loadManifest = store::loadManifest
            )
        }
    }

    // Phase 4.1b — hard-block Start-gate. CAMERA_PERMISSION_DENIED (#1) or
    // STORAGE_INSUFFICIENT (#3) disable the Start button; the inline
    // WarningCenter banner shows the actionable detail. Read the leaf
    // signals directly — NOT off the WarningCenter's resolved warning,
    // which can be a higher-priority non-gating warning (e.g. exact-alarm)
    // while storage is also insufficient. When this is not a RovaApp
    // (preview / odd contexts) the gate is open.
    val cameraPermissionFlow = remember(rovaApp) {
        rovaApp?.cameraPermissionSignal?.state ?: MutableStateFlow(true)
    }
    val storageInsufficientFlow = remember(rovaApp) {
        rovaApp?.storageSignal?.insufficientToStart ?: MutableStateFlow(false)
    }
    val cameraPermissionGranted by cameraPermissionFlow.collectAsStateWithLifecycle()
    val storageInsufficient by storageInsufficientFlow.collectAsStateWithLifecycle()
    val startBlocked = !cameraPermissionGranted || storageInsufficient

    val keepScreenOn by settingsViewModel.keepScreenOn.collectAsStateWithLifecycle()
    val cameraGuidesEnabled by settingsViewModel.cameraGuidesEnabled.collectAsStateWithLifecycle()

    // Q3: Wire keepScreenOn to the view flag
    DisposableEffect(keepScreenOn) {
        localView.keepScreenOn = keepScreenOn
        onDispose { localView.keepScreenOn = false }
    }

    // PR-ε (spec §2, §9) — chrome model by smallest width (orientation-stable).
    // FixedPhysical (compact phones): window locked portrait, chrome contents
    // counter-rotate. Adaptive (sw600dp+): pre-ε behavior — window rotates.
    val chromeModeNow = chromeMode(configuration.smallestScreenWidthDp)

    // Snapped physical rotation (sensor-driven; works with the system auto-rotate
    // lock OFF — the owner's daily configuration — and with the window
    // orientation-locked, where LocalConfiguration / display.rotation freeze).
    val snappedRotation by viewModel.deviceRotation.collectAsStateWithLifecycle()

    // PR-ε (spec §2.4) — ONE unwrapped accumulator angle for the whole screen,
    // shortest-path retarget (never animate to the raw target — a 270°→0°
    // transition would spin the long way). First composition JUMPS to the
    // current angle (no spin-on-entry). Known cosmetic edge: on COLD entry held
    // landscape the signal seeds ROTATION_0 until the listener's first dwell-
    // filtered emission, so one 180ms spin plays shortly after entry — not a
    // bug; the locked window offers no better seed (Display.rotation frozen).
    // Reduced motion snaps instead of animating (ADR-0020 /
    // checkA11yAnimationGated).
    val reduceMotion = rememberReduceMotion()
    // γ orientation policy gates the chrome too (owner smoke 2026-06-12,
    // finding #4): under Lock the chrome freezes to the LOCKED orientation —
    // markings match the pinned capture, exactly like iOS with the rotation
    // lock engaged — instead of following grip. Same resolver as the service's
    // capture path, so chrome and capture can never disagree.
    val effectiveChromeRotation = OrientationPolicyResolver.resolve(
        policy = orientationPolicy,
        lockRotation = orientationLockRotation,
        snappedRotation = snappedRotation,
    )
    val spin = remember { Animatable(uiCounterRotationDegrees(effectiveChromeRotation)) }
    LaunchedEffect(effectiveChromeRotation, chromeModeNow, reduceMotion) {
        if (chromeModeNow != ChromeMode.FixedPhysical) return@LaunchedEffect
        val target = spin.value + shortestPathDelta(spin.value, uiCounterRotationDegrees(effectiveChromeRotation))
        if (reduceMotion) spin.snapTo(target)
        else spin.animateTo(target, animationSpec = tween(RecordChromeTokens.elementSpinMs))
    }
    // ADR-0029 (force-rotate, pre-ε Adaptive behavior) — the Single-camera Record
    // viewfinder follows the physical sensor like a camera app, even when the
    // system auto-rotate lock is ON. FULL_SENSOR ignores the lock and allows all
    // four orientations; the service's OrientationEventListener already snaps
    // capture to the same physical value.
    //
    // Gated to Single (mode != "DualShot"): DualShot pins its own rotation in the
    // EGL path (ADR-0009) and does NOT participate in orientation tracking, so a
    // landscape window mismatches its pinned capture/output and the preview tears
    // (codex review). DualShot is therefore LOCKED to portrait — explicitly
    // SCREEN_ORIENTATION_PORTRAIT, not USER: with the system auto-rotate lock off,
    // USER would keep whatever orientation Single last forced (e.g. the landscape
    // that breaks the EGL render). Driven off `desiredMode` (pending ?: committed)
    // so a landscape→DualShot pick starts the window rotating to portrait BEFORE
    // the deferred camera rebind commits.
    val targetOrientation = if (desiredMode == DualShotPortraitGate.DUAL_SHOT) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    // PR-ε (spec §2.1, ADR-0029 §B″) — THE single setRequestedOrientation site
    // for the record route (gate: checkRecordChromeLockSingleSite). Lock = route
    // ∧ FixedPhysical. §B″5 rewrite (floating panel, owner-ratified 2026-06-12):
    // the settings surface on compact is now FloatingSettingsPanel — chrome that
    // spins as a unit — so NO modal releases the lock anymore; the window stays
    // locked portrait, always, on compact. `modalOpen` is pinned to false (the
    // policy parameter is retained for the API and the Adaptive contract, where
    // shouldLock is false regardless). Known deviation: the thermal tips
    // ModalBottomSheet now renders portrait under the lock (§B″5, follow-up).
    // Subsumes the DualShot-only portrait lock (lock target is PORTRAIT, which
    // is exactly what DualShot required). Adaptive keeps the pre-ε per-state
    // behavior verbatim via `targetOrientation` above (DualShot → PORTRAIT,
    // Single → FULL_SENSOR). The original requestedOrientation is captured and
    // restored on dispose so this never leaks a policy change to other screens.
    val lockChrome = RecordChromeLockPolicy.shouldLock(
        isRecordRoute = true,   // RecordScreen only composes on the record route.
        modalOpen = false,      // ADR-0029 §B″5 — compact never unlocks post-panel.
        chromeMode = chromeModeNow,
    )
    // Counter-rotation is only valid while the window is actually locked
    // (final-review finding #2 — double compensation otherwise). Post-§B″5
    // rewrite lockChrome is constant true on FixedPhysical (modalOpen pinned
    // false), so the guard is vestigial-but-correct; kept so the expression
    // stays valid if the lock policy ever grows another release condition.
    // FU-4 — defer the per-frame `spin.value` read to draw time. Threaded as a
    // provider so the 180ms spin re-runs only each SpinningBox graphicsLayer
    // block, not the chrome subtree's composition. Remembered so an unrelated
    // RecordScreen recomposition doesn't hand intermediates a new lambda identity
    // (codex: do NOT key the remember on spin.value).
    val spinDegrees: () -> Float = remember(chromeModeNow, lockChrome, spin) {
        { if (chromeModeNow == ChromeMode.FixedPhysical && lockChrome) spin.value else 0f }
    }
    DisposableEffect(lockChrome, targetOrientation) {
        val activity = localView.context as? Activity
        val previousOrientation = activity?.requestedOrientation
        if (activity != null) {
            activity.requestedOrientation = if (lockChrome) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                // Adaptive or modal-open: pre-ε behavior exactly as the replaced
                // block implemented it.
                targetOrientation
            }
        }
        onDispose {
            if (activity != null && previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
        }
    }

    // ADR-0029 (force-rotate) — seamless rotation, like a native camera app. The
    // default ROTATION_ANIMATION_ROTATE screenshots the old orientation and rotates
    // the screenshot, so the viewfinder visibly flips before CameraX re-applies the
    // corrected transform on a later frame. ROTATION_ANIMATION_SEAMLESS skips that
    // screenshot-rotate and lets the app render directly in the new orientation (the
    // system falls back automatically when it can't render in time). Applied for the
    // whole Record screen and restored on leave so other screens keep the default.
    DisposableEffect(Unit) {
        val window = (localView.context as? Activity)?.window
        val previousAnim = window?.attributes?.rotationAnimation
        if (window != null) {
            window.attributes = window.attributes.apply {
                rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
            }
        }
        onDispose {
            if (window != null && previousAnim != null) {
                window.attributes = window.attributes.apply { rotationAnimation = previousAnim }
            }
        }
    }

    // Preview Management (Retain instance to prevent black screen)
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(previewView) {
        viewModel.setSurfaceProvider(previewView.surfaceProvider)
        onDispose {
            // Bug 3 — only null the provider when a periodic session is ACTIVE.
            // Then the service swaps to its DUMMY surface so background recording
            // keeps producing frames (the existing isPeriodicActive path).
            //
            // When idle+warm, do NOT null it: the service's setSurfaceProvider(null)
            // branch is a no-op while idle, so nulling here only clears
            // currentSurfaceProvider/pendingSurfaceProvider and forces a fresh
            // attach (new SurfaceRequest → brief black) on the next tab-return.
            // Keeping the provider makes the return the device-tested UI→UI
            // hot-swap instead of a churn (ADR-0021 warm-keep).
            if (viewModel.serviceState.value.isPeriodicActive) {
                viewModel.setSurfaceProvider(null)
            }
        }
    }

    // Permissions
    val permissionsState = rememberMultiplePermissionsState(
        permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    )
    // Phase 1.4 (ADR 0006) permission policy:
    // - CAMERA: mandatory (always required to start).
    // - POST_NOTIFICATIONS on API 33+: mandatory at session start
    //   (§"POST_NOTIFICATIONS revoked (33+)" hard-block lock).
    //   Mid-session revocation remains tolerant (service continues; UI
    //   re-prompts on next foreground entry).
    // - RECORD_AUDIO: OPTIONAL. The service's pre-FGS Phase 1 preflight
    //   reads RECORD_AUDIO state and decides AudioMode (VIDEO_AUDIO vs
    //   VIDEO_ONLY) per B18. Audio-permission absence does NOT block
    //   start — it just locks the session into VIDEO_ONLY mode for the
    //   duration.
    val hasCapturePermissions = permissionsState.permissions
        .filterNot { it.permission == Manifest.permission.RECORD_AUDIO }
        .all { it.status is PermissionStatus.Granted }

    // Recording error snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(serviceState.recordingError) {
        serviceState.recordingError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearRecordingError()
        }
    }

    // Phase 4.1b — keep the camera/mic permission signals current when an
    // in-app grant doesn't pause the Activity (Accompanist refreshes its
    // own permission state on ON_RESUME; mirror that into our signals).
    LaunchedEffect(permissionsState.permissions.map { it.status }) {
        rovaApp?.let {
            it.cameraPermissionSignal.refresh()
            it.microphonePermissionSignal.refresh()
        }
    }
    // Phase 4.1b — recompute the storage estimate whenever the clip
    // settings change (covers preset-select and every edit sheet) and on
    // first composition.
    LaunchedEffect(duration, loopCount, resolution, mode) {
        rovaApp?.storageSignal?.recompute(duration, loopCount, resolution, mode)
    }

    // Release camera when app goes to background (unless recording); on
    // resume, re-poll the WarningCenter signals that have no broadcast
    // (Phase 4.1 — exactAlarmSignal self-refreshes via RovaApp's receiver;
    // cameraStateSignal is fed by the recording service;
    // Phase 4.1b adds camera/mic permission + storage).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // ADR-0021 — camera release is owned by the process lifecycle
                // (RovaRecordingService's ProcessLifecycleOwner observer), NOT
                // this per-screen NavBackStackEntry lifecycle, which fires
                // ON_STOP on in-app tab switches while the app is still
                // foreground. This observer is acquire-only.
                Lifecycle.Event.ON_START -> viewModel.startCameraPreview()
                Lifecycle.Event.ON_RESUME -> rovaApp?.let {
                    it.notificationPermissionSignal.refresh()
                    it.thermalStatusSignal.refresh()
                    it.powerSignal.refresh()
                    it.batteryOptimizationSignal.refresh()
                    it.cameraPermissionSignal.refresh()
                    it.microphonePermissionSignal.refresh()
                    it.saveFolderSignal.refresh()                  // ← NEW (B4b ADR-0024)
                    it.storageSignal.recompute(
                        viewModel.duration.value,
                        viewModel.loopCount.value,
                        viewModel.resolution.value,
                        viewModel.topology.value
                    )
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isUiLocked = serviceState.isPeriodicActive || serviceState.isMerging
    val isCameraActive = serviceState.isCameraActive

    // Slice 3 — wall-clock derived presentation timer for the active
    // HUD's clip pill. Anchor lives on the VM (set on the rising edge
    // of the matching service flag); the composable ticks 1 Hz against
    // the anchor and re-publishes the elapsed seconds as Compose state.
    // When the anchor is null (idle / between rising edges) elapsed is 0
    // and the producer suspends until the next anchor change.
    val clipAnchorMillis by viewModel.clipAnchorMillis.collectAsStateWithLifecycle()
    val clipElapsedSeconds by produceState(
        initialValue = 0,
        key1 = clipAnchorMillis
    ) {
        val anchor = clipAnchorMillis
        if (anchor == null) {
            value = 0
            return@produceState
        }
        while (true) {
            val now = System.currentTimeMillis()
            value = ((now - anchor) / 1000L).toInt().coerceAtLeast(0)
            delay(1000L)
        }
    }
    // Phase 2.4 — fold the merge axis into the HUD state. The
    // resolution priority in `RecordHudState.from` makes `Merging`
    // win over `Idle` for the brief window where the service has
    // flipped `isPeriodicActive` off but `isMerging` is still true,
    // so the HUD does not flash an "Idle" frame between the last
    // clip and the merge card.
    val hudState = RecordHudState.from(
        isPeriodicActive = serviceState.isPeriodicActive,
        isRecording = serviceState.isRecording,
        isMerging = serviceState.isMerging,
        mergeProgress = serviceState.mergeProgress,
        segmentCount = serviceState.segmentCount,
        // Bug A — real merge total so the band reads "clip X of Y" on an
        // early stop where segmentCount is still 0.
        mergeClipCount = serviceState.mergeClipCount
    )

    // R2 — drive StorageLowMidRecSignal while in an active HUD state.
    // Clear on Idle transition so a stale "low" never leaks into the
    // next session's idle chrome. The signal is consumed by the
    // WarningCenter active-state mount (STORAGE_LOW_MID_REC → TopBanner).
    LaunchedEffect(hudState, duration, resolution) {
        if (hudState is RecordHudState.Idle) {
            rovaApp?.storageLowMidRecSignal?.clear()
            return@LaunchedEffect
        }
        while (true) {
            rovaApp?.storageLowMidRecSignal?.poll(duration, resolution)
            delay(30_000L)
        }
    }

    // Phase 2.4 — merge transition state. Declared up here (rather
    // than next to the LaunchedEffect that drives them) because the
    // bottom-area Column references `showCompleteCard` and
    // `lastCompleteClipCount` to suppress the idle dock during the
    // post-merge grace window. The LaunchedEffect sits later in the
    // composable body and still picks up these vars by closure.
    var wasMerging by remember { mutableStateOf(false) }
    var showCompleteCard by remember { mutableStateOf(false) }
    var lastCompleteClipCount by remember { mutableStateOf(0) }

    // Slice 3 review fix — local-tick countdown for the Waiting body.
    // The service decrements `nextRecordingCountdown` in 30-second
    // chunks during the alarm-driven wait path
    // (RovaRecordingService — alarm tick coalescing), so reading the
    // service value directly produces a HUD that holds 01:00 for ~30 s
    // then jumps to 00:30. We anchor on each service emission and
    // tick down 1 Hz locally between emissions, so the user sees a
    // smooth `MM:SS` countdown. Service emissions act as correction
    // anchors: when the next 30 s chunk lands, the producer keys flip
    // and the countdown re-anchors to whatever the service reports —
    // even if that means jumping forward to correct local drift.
    val serviceCountdownSeconds = serviceState.nextRecordingCountdown
    val displayedCountdownSeconds by produceState(
        initialValue = serviceCountdownSeconds,
        key1 = serviceCountdownSeconds,
        key2 = hudState
    ) {
        // Outside the Waiting body, surface the service value
        // verbatim — there is no local tick to maintain.
        if (hudState != RecordHudState.Waiting) {
            value = serviceCountdownSeconds
            return@produceState
        }
        var remaining = serviceCountdownSeconds.coerceAtLeast(0L)
        value = remaining
        while (remaining > 0L) {
            delay(1000L)
            remaining = (remaining - 1L).coerceAtLeast(0L)
            value = remaining
        }
    }

    // Bug 3 — the overlay is now gated on a REAL service cold-acquire signal
    // (RovaServiceState.coldAcquireInProgress, set by markColdAcquire while a
    // bind is genuinely in flight), NOT an isCameraActive composition edge. The
    // old `cameraWarmingUp` / `prevCameraActive` LaunchedEffect-edge timer re-fired
    // on every tab return (RecordScreen leaves composition on tab-away and
    // recreates its PreviewView on return), flashing "Initializing Camera…" for
    // 1.5 s despite the warm-kept camera never rebinding (ADR-0021). Reading the
    // service flag means a warm nav-return surface re-swap shows nothing, and a
    // genuine cold start shows the overlay for exactly the real bind window.
    val coldAcquireInProgress = serviceState.coldAcquireInProgress

    // B6 — a lens flip kicks off a ~2s unbind/rebind during which the camera
    // is inactive (black). `flipInFlight` is raised by the flip taps below and
    // cleared once the rebind reports completion, so the loading overlay can read
    // "Switching…" (intentional) instead of the generic "Initializing Camera…".
    // No animation here beyond the existing indeterminate spinner, so no
    // reduced-motion gate applies (checkA11yAnimationGated only triggers on
    // rememberInfiniteTransition / infiniteRepeatable).
    //
    // Camera-flip regression (device-confirmed 2026-06-08): the latch was cleared
    // by `LaunchedEffect(isCameraActive) { if (isCameraActive) flipInFlight = false }`.
    // A flip pulses isCameraActive true→false→true; on a warm rebind the `false`
    // window is ~54 ms and `serviceState` is a conflated StateFlow, so the Compose
    // collector misses the `false` — isCameraActive never changes from composition's
    // view, the keyed effect never re-fires, and the opaque overlay latched on
    // forever over a working preview. Gate the clear on the service's MONOTONIC
    // `cameraConfigGeneration` instead (FlipLatchPolicy): the captured-at-tap value
    // vs the latest value always differ after a (re)bind, which conflation cannot
    // hide. The 4 s backstop guarantees the overlay can never latch even if a bind
    // never reports success.
    var flipInFlight by remember { mutableStateOf(false) }
    var flipStartGeneration by remember { mutableStateOf(0L) }
    LaunchedEffect(serviceState.cameraConfigGeneration) {
        if (FlipLatchPolicy.shouldClear(flipInFlight, flipStartGeneration, serviceState.cameraConfigGeneration)) {
            flipInFlight = false
        }
    }
    LaunchedEffect(flipInFlight) {
        if (flipInFlight) {
            delay(4000)
            flipInFlight = false
        }
    }

    // Camera Disconnected Alert
    if (serviceState.isRecording && !isCameraActive) {
        RovaAlertDialog(
            onDismissRequest = { /* Force user to stop */ },
            icon = Icons.Default.Error,
            title = stringResource(R.string.record_camera_disconnected_title),
            text = stringResource(R.string.record_camera_disconnected_body),
            confirmText = stringResource(R.string.record_camera_disconnected_stop),
            destructive = true,
            confirmFilled = true,
            onConfirm = { viewModel.stopRecording() },
        )
    }

    // ----------------------------------------------------------------
    // Start handler — used by the idle dock Start button. Surfaces the
    // existing StartResult.Blocked snackbar paths verbatim.
    // ----------------------------------------------------------------
    val onStart: () -> Unit = handler@{
        if (!hasCapturePermissions) {
            permissionsState.launchMultiplePermissionRequest()
            return@handler
        }
        // Phase 1.4 (ADR 0006 B11) — caller-side FGS guard.
        val result = RovaRecordingService.start(
            context,
            viewModel.duration.value.toFloat(),
            viewModel.interval.value.toFloat(),
            viewModel.loopCount.value,
            viewModel.resolution.value
        )
        if (result is com.aritr.rova.service.StartResult.Blocked) {
            val msg = when (result.reason) {
                com.aritr.rova.service.StartBlocked.APP_NOT_VISIBLE ->
                    context.getString(R.string.record_blocked_app_not_visible)
                com.aritr.rova.service.StartBlocked.FGS_RESTRICTED ->
                    context.getString(R.string.record_blocked_fgs_restricted)
                com.aritr.rova.service.StartBlocked.UNKNOWN_ISE ->
                    context.getString(R.string.record_blocked_unknown)
            }
            scope.launch { snackbarHostState.showSnackbar(msg) }
        }
    }

    // ----------------------------------------------------------------
    // Task 13 — new-chrome inputs (top overlay status pill + bottom-nav
    // FAB). The FAB subsumes the old idle-dock Start button and the
    // big-red Stop button; on Disabled it is a no-op (the warning sheet
    // carries the actionable CTA).
    // ----------------------------------------------------------------
    // isUiLocked (declared above) == isPeriodicActive || isMerging — the same "session running" predicate
    // recordFabState/RecordBottomNav want; no need for a second copy.
    val fabState = recordFabState(hudState, sessionLocked = isUiLocked, hardBlockActive = startBlocked)
    val onFabClick: () -> Unit = {
        when (fabState) {
            RecordFabState.Start -> onStart()
            RecordFabState.Stop -> viewModel.stopRecording()
            RecordFabState.Disabled -> { /* no-op — the warning sheet carries the CTA */ }
        }
    }
    // status pill text — exact active-state copy is R2's concern
    val statusText: String
    val statusDetail: String?
    when (hudState) {
        RecordHudState.Recording -> { statusText = stringResource(R.string.record_status_recording); statusDetail = stringResource(R.string.record_status_detail_clip_of, clipElapsedSeconds, duration) }
        // Bug B — startup grace before the first clip; static detail (no countdown).
        RecordHudState.Starting  -> { statusText = stringResource(R.string.record_status_starting); statusDetail = stringResource(R.string.record_status_detail_preparing) }
        RecordHudState.Waiting   -> { statusText = stringResource(R.string.record_status_on_break); statusDetail = stringResource(R.string.record_status_detail_next_in, displayedCountdownSeconds) }
        is RecordHudState.Merging -> { statusText = stringResource(R.string.record_status_merging); statusDetail = null }
        RecordHudState.Idle -> { statusText = stringResource(R.string.record_status_ready); statusDetail = null }
    }

    // Task 14 — the ModalNavigationDrawer wrapper (with its "Quick settings"
    // panel + the two SwitchRows) is removed; Keep-Screen-On / Sounds now live
    // in the Settings screen. settingsViewModel is still consumed for the
    // keepScreenOn DisposableEffect above.
    val chromeOrientation =
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE)
            ChromeOrientation.LANDSCAPE else ChromeOrientation.PORTRAIT
    // PR-β′ (spec 2026-06-10 §4) — "rotate, don't redesign": the landscape chrome
    // edge + rail order derive from the DISPLAY ROTATION sense (to match the stock
    // camera), not the nav-bar inset. Non-null exactly in landscape; configChanges
    // recompose re-reads it on rotation.
    val currentSense: DeviceLandscape?
    val currentDeviceRotation: Int?
    run {
        @Suppress("DEPRECATION")
        val rot = LocalView.current.display?.rotation ?: Surface.ROTATION_0
        currentSense = landscapeSense(rot)
        // Expose the raw Surface.ROTATION_* Int for OrientationRow / lockRotationForLandscapePick;
        // null in portrait (rot == ROTATION_0 or ROTATION_2) so the picker defaults to ROTATION_90.
        currentDeviceRotation = if (currentSense != null) rot else null
    }
    val clusterAnchor: NavEdge? = currentSense?.let { clusterEdge(it) }
    // §B′ polish P1 (2026-06-11, owner: "correctness fix, not visual tweak") —
    // in landscape the system bars live on the SIDES and the punch-hole on a
    // side edge, so single-bar insets (statusBars/navigationBars) under-pad and
    // chrome collides with system UI. Landscape chrome therefore pads the full
    // safeDrawing union (status + nav bars + display cutout). Portrait keeps
    // the original per-bar insets — byte-identical.
    val chromeTopInsets = if (currentSense != null) WindowInsets.safeDrawing else WindowInsets.statusBars
    // §B′ polish P2 — generic transient-message rule: anything mounted in the
    // top transient slots (WARNING_CENTER, RECOVERY_CHIP, ACTIVE_HUD) clears
    // the device-anchored cluster band on its edge. New banner types inherit
    // this automatically by mounting in the same slots.
    val transientClusterClearance = when (clusterAnchor) {
        NavEdge.Leading -> Modifier.padding(start = RecordChromeTokens.clusterBandClearance)
        NavEdge.Trailing -> Modifier.padding(end = RecordChromeTokens.clusterBandClearance)
        null -> Modifier
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                // Camera Preview
                if (hasCapturePermissions) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (mode == DualShotPortraitGate.DUAL_SHOT) {
                            // Phase 6.1c — DualShot mode renders via two
                            // dedicated TextureView zones; the
                            // PreviewView is bound by CameraX but covered
                            // visually by the DualPreviewZone. Shown in
                            // BOTH idle and recording states (per spec
                            // §4.2 — live preview throughout recording).
                            // Callbacks wrapped in remember(viewModel) so
                            // the TextureView's SurfaceTextureListener
                            // closures stay stable across recomposition
                            // (Task 11 code-review concern).
                            val registerPreview = remember(viewModel) {
                                { side: com.aritr.rova.service.dualrecord.VideoSide,
                                  surface: android.view.Surface,
                                  w: Int,
                                  h: Int ->
                                    viewModel.attachDualPreview(side, surface, w, h)
                                }
                            }
                            val unregisterPreview = remember(viewModel) {
                                { side: com.aritr.rova.service.dualrecord.VideoSide ->
                                    viewModel.detachDualPreview(side)
                                }
                            }
                            DualPreviewZone(
                                registerPreviewSurface = registerPreview,
                                unregisterPreviewSurface = unregisterPreview,
                                modifier = Modifier.fillMaxSize(),
                                guidesEnabled = cameraGuidesEnabled,
                            )
                        } else {
                            // Single Portrait / Landscape modes — existing
                            // PreviewView path, with the decorative guides
                            // overlaid above it.
                            AndroidView(
                                factory = { _ -> previewView },
                                modifier = Modifier.fillMaxSize()
                            )
                            CameraGuides(
                                visible = cameraGuidesEnabled,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // Loading Overlay — shown ONLY during a genuine cold camera
                // acquire (service coldAcquireInProgress) or an in-flight lens
                // flip. Bug 3: the bare `!isCameraActive` term was DROPPED — it
                // let the brief warm-return surface gap flash a spinner even
                // though the camera never rebound. Suppressed when camera
                // permission is missing (no camera to initialize; the hard-block
                // sheet already explains why) and during a merge (the merge HUD
                // owns the screen). Show-gate lives in RecordOverlayPolicy; the
                // Initializing-vs-Switching TEXT selection below is unchanged.
                if (RecordOverlayPolicy.showInitializingOverlay(
                        coldAcquire = coldAcquireInProgress,
                        flipInFlight = flipInFlight,
                        isMerging = serviceState.isMerging,
                        hasCapturePermissions = hasCapturePermissions,
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(16.dp))
                            // B6 — during a lens flip the black gap is intentional;
                            // label it "Switching…" instead of the cold-start copy.
                            Text(
                                stringResource(
                                    if (flipInFlight) R.string.record_switching_camera
                                    else R.string.record_initializing_camera,
                                ),
                                color = Color.White,
                            )
                        }
                    }
                }

                // Chrome (recovery chip, idle WarningCenter, active HUD, settings
                // card, top overlay / cam-controls, bottom nav) is suppressed while
                // the SettingsSheet is open — but ONLY in PORTRAIT, where the sheet is
                // a camera-peek panel so only the camera preview shows behind it.
                // PR-β′ (spec 2026-06-10) — "rotate, don't redesign": landscape now
                // behaves like portrait. The settings sheet covers the rotated cluster
                // (config strip + nav), so chrome is suppressed while it's open in BOTH
                // orientations. MergeCompleteCard + loading overlay stay outside the gate.
                // PR-ε floating panel (ADR-0029 §B″5, 2026-06-12) — Adaptive-only now:
                // on FixedPhysical the settings surface is a floating card over the
                // viewfinder with NO scrim, ratified so the strip/nav stay VISIBLE
                // below it; the panel's full-screen tap-catcher consumes input so the
                // visible chrome can't be hit while it's open.
                if (!combinedOpen || chromeModeNow == ChromeMode.FixedPhysical) {
                // A11y (ADR-0020) — while the floating panel is open the chrome
                // stays COMPOSED (visible under the no-scrim card) but must leave
                // the semantics tree: the panel's tap-catcher blocks touch, yet
                // TalkBack could otherwise traverse to and ACTIVATE the Start
                // FAB / nav / recovery chip underneath. clearAndSetSemantics
                // prunes every descendant node while occluded. (hideFromAccessibility
                // needs compose-ui 1.8; BOM 2025.01.01 ships 1.7.x.)
                Box(
                    Modifier
                        .matchParentSize()
                        .then(if (combinedOpen) Modifier.clearAndSetSemantics {} else Modifier),
                ) {
                // Slice 2 / Phase 2.4 — read-only recovery echo, now a chip pinned
                // just below the status pill. Idle only; hidden during Recording,
                // Waiting, or Merging so the active HUD owns the user's attention.
                if (hudState == RecordHudState.Idle && interruptedSessionCount > 0) {
                    RecordRecoveryChip(
                        count = interruptedSessionCount,
                        onReview = onNavigateToHistory,
                        modifier = slotModifier(
                            placementFor(ChromeSlot.RECOVERY_CHIP, chromeOrientation),
                            chromeTopInsets,
                        ).then(transientClusterClearance),
                        spinDegrees = spinDegrees,
                    )
                }
                // ADR 0007 — idle WarningCenter mount: renders the warning sheet
                // (modal) or, if collapsed, a chip for idle-reachable warnings. The
                // chip lands near the status pill via this wrapper; the modal sheet
                // ignores the wrapper's position. Mid-rec TopBanner-mapped ids are
                // rendered by the separate active-state mount inside the active HUD
                // Column below.
                if (hudState is RecordHudState.Idle) {
                    Box(
                        modifier = slotModifier(
                            placementFor(ChromeSlot.WARNING_CENTER, chromeOrientation),
                            chromeTopInsets,
                        ).then(transientClusterClearance)
                    ) {
                        WarningCenter(
                            hudState = RecordHudState.Idle,
                            onStopRecording = {},
                            vm = warningVm,
                            onNavigateToHistory = onNavigateToHistory,
                            onOpenThermalTips = { showTipsSheet = true },
                            onKeepRawFromSheet = { sessionId ->
                                scope.launch {
                                    try {
                                        val store = rovaApp?.takeIf { it.videosRoot != null }?.sessionStore
                                        store?.markTerminated(
                                            sessionId = sessionId,
                                            terminated = Terminated.MULTI_SEGMENT_KEPT,
                                            stopReason = StopReason.NONE,
                                        )
                                        rovaApp?.recoveryMergeOutcomeSignal?.acknowledge(sessionId)
                                    } catch (t: Throwable) {
                                        RovaLog.w("C2.4 keepRaw failed for $sessionId", t)
                                    }
                                }
                            },
                            onDiscardFromSheet = { sessionId ->
                                scope.launch {
                                    try {
                                        val store = rovaApp?.takeIf { it.videosRoot != null }?.sessionStore
                                        store?.discardSession(sessionId)
                                        rovaApp?.recoveryMergeOutcomeSignal?.acknowledge(sessionId)
                                    } catch (t: Throwable) {
                                        RovaLog.w("C2.4 discard failed for $sessionId", t)
                                    }
                                }
                            },
                            pendingCantMergeSessionId = pendingSid,
                        )
                    }
                }

                // ------------------------------------------------------
                // R2 bottom-area body:
                //   Idle                          → camera-first idle dock
                //                                   (RecordSettingsCard).
                //   Recording / Waiting / Merging → R2 active-state HUD:
                //                                   WarningCenter (mid-rec
                //                                   TopBanner) + RecordActiveHud
                //                                   (loop-pill + status-pill),
                //                                   top-centered. The status pill
                //                                   carries the per-clip /
                //                                   waiting-countdown / merge
                //                                   progress.
                // ------------------------------------------------------
                when (hudState) {
                    RecordHudState.Idle -> {
                        // Idle: camera-first body. The settings card is rendered
                        // once for all states below the `when` (dimmed when active).
                    }
                    RecordHudState.Recording,
                    RecordHudState.Starting,
                    RecordHudState.Waiting,
                    is RecordHudState.Merging -> {
                        // R2 active-state HUD: WarningCenter (mid-rec TopBanner) +
                        // RecordActiveHud (loop-pill + status-pill). The status pill
                        // consumes clipSecondsLeft / waitSecondsLeft / Merging-progress
                        // per state; the helper dispatches on `state`. Stop is the
                        // bottom-nav FAB.
                        val midRecWarnings: @Composable () -> Unit = {
                            WarningCenter(
                                hudState = hudState,
                                onStopRecording = { viewModel.stopRecording() },
                                vm = warningVm,
                                onNavigateToHistory = onNavigateToHistory,
                                onOpenThermalTips = { showTipsSheet = true },
                            )
                        }
                        val activeHud: @Composable (Modifier) -> Unit = { hudModifier ->
                            RecordActiveHud(
                                state = hudState,
                                loopIndex = serviceState.currentLoop,
                                loopTotal = serviceState.totalLoops,
                                clipSecondsLeft = (duration - clipElapsedSeconds).coerceAtLeast(0),
                                waitSecondsLeft = displayedCountdownSeconds.toInt().coerceAtLeast(0),
                                // PR-α (ADR-0029 §Decision 3) — the device rotated but the
                                // current clip stays frozen; flag the next-clip rotation.
                                rotatingNextClip = serviceState.pendingNextRotation != serviceState.currentSegmentRotation,
                                orientation = chromeOrientation,
                                spinDegrees = spinDegrees,
                                modifier = hudModifier,
                            )
                        }
                        // PR-ε refinement (owner 2026-06-12 #7): the HUD belongs to the
                        // PHYSICAL top edge as held, not to the locked window's top. In a
                        // 90°/270° grip the window-top slot is a physical SIDE edge, so the
                        // spun group re-anchors to the side-center of the locked-portrait
                        // window — which IS the physical top-center. Mid-rec warning
                        // banners are text surfaces (spec §3 — they never spin) and keep
                        // the window-top slot in every grip. ROTATION_180 keeps the
                        // window-top anchor (reverse-portrait: same edge geometry).
                        val hudSideAnchor = if (
                            chromeModeNow == ChromeMode.FixedPhysical && lockChrome
                        ) {
                            when (effectiveChromeRotation) {
                                1 -> Alignment.CenterEnd    // ROTATION_90 — window right = physical top
                                3 -> Alignment.CenterStart  // ROTATION_270 — window left = physical top
                                else -> null
                            }
                        } else null
                        if (hudSideAnchor == null) {
                            // Portrait grip / Adaptive — original top-centered stack.
                            Column(
                                modifier = slotModifier(
                                    placementFor(ChromeSlot.ACTIVE_HUD, chromeOrientation),
                                    chromeTopInsets,
                                ).then(transientClusterClearance).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                midRecWarnings()
                                activeHud(Modifier)
                            }
                        } else {
                            Column(
                                modifier = slotModifier(
                                    placementFor(ChromeSlot.ACTIVE_HUD, chromeOrientation),
                                    chromeTopInsets,
                                ).then(transientClusterClearance).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                midRecWarnings()
                            }
                            activeHud(
                                Modifier
                                    .align(hudSideAnchor)
                                    .padding(horizontal = 10.dp),
                            )
                        }
                    }
                }

                // Phase 2 — the settings card is shared chrome: shown in every
                // HUD state, dimmed + read-only while a session is active
                // (mockups/new_uiux/01-record-home.html — the recording/break
                // states show the card at 75% opacity). Suppressed only during
                // the brief post-merge MergeCompleteCard grace window.
                if (!showCompleteCard) {
                    // ADR-0029 — ignore re-taps while a deferred switch is in flight;
                    // otherwise route a landscape→P+L cycle through the portrait gate
                    // (see [DualShotPortraitGate]). Shared by both card layouts.
                    val onCycleModeGated: () -> Unit = {
                        if (pendingMode == null) {
                            val next = CaptureMode.cycleNext(mode)
                            if (DualShotPortraitGate.shouldDefer(next, isPortrait)) {
                                pendingMode = next
                            } else {
                                viewModel.cycleMode()
                            }
                        }
                    }
                    val modeLabel = stringResource(CaptureMode.forTopology(mode).labelRes)
                    // PR-β′ — ONE config strip in both orientations: the portrait
                    // horizontal pill (PARAMS_CARD placement) rotated to a vertical
                    // strip on the cluster edge, just inboard of the nav rail (spec §5).
                    // Tap opens the settings sheet exactly like portrait. The sheet
                    // covers it when open (no §B mutual-exclusion gate).
                    val configEdgeModifier = if (currentSense != null) {
                        Modifier
                            .align(if (clusterAnchor == NavEdge.Trailing) Alignment.CenterEnd else Alignment.CenterStart)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            // §B′ polish P3 — single-source inboard offset clearing the rail band.
                            .padding(
                                end = if (clusterAnchor == NavEdge.Trailing) RecordChromeTokens.railBandInboardOffset else 0.dp,
                                start = if (clusterAnchor == NavEdge.Trailing) 0.dp else RecordChromeTokens.railBandInboardOffset,
                            )
                    } else {
                        slotModifier(
                            placementFor(ChromeSlot.PARAMS_CARD, chromeOrientation),
                            WindowInsets.navigationBars,
                        ).fillMaxWidth()
                    }
                    RecordSettingsCard(
                        durationSeconds = duration,
                        loopCount = loopCount,
                        intervalMinutes = interval,
                        quality = resolution,
                        mode = modeLabel,
                        onOpenSheet = { viewModel.openSettingsSheet() },
                        onCycleMode = onCycleModeGated,
                        sense = currentSense,
                        dimmed = hudState != RecordHudState.Idle,
                        modifier = configEdgeModifier,
                        orientationPolicy = orientationPolicy,
                        orientationLockRotation = orientationLockRotation,
                        spinDegrees = spinDegrees,
                    )
                }

                // ── Task 13/14 — new chrome: top overlay + cam-controls (top),
                // bottom nav with the Start/Stop FAB (bottom). Painted after
                // the bottom-area `when` so it sits on top of the camera
                // preview / status strip / settings card. The MergeCompleteCard
                // below still paints over this chrome.
                if (hudState is RecordHudState.Idle) {
                    RecordTopOverlay(
                        hudState = hudState,
                        statusText = statusText,
                        statusDetail = statusDetail,
                        currentLoop = serviceState.currentLoop,
                        totalLoops = serviceState.totalLoops,
                        modifier = slotModifier(
                            placementFor(ChromeSlot.STATUS_OVERLAY, chromeOrientation),
                            chromeTopInsets,
                        ),
                        spinDegrees = spinDegrees,
                    )
                    // Phase 6.1b smoke-fix #6 — flip-camera disabled in P+L
                    // mode (rear-only by design — DualShot from one full-FOV
                    // sensor frame; entry-level devices like Samsung A17
                    // don't support concurrent rear+front streams either).
                    // Gated INDEPENDENTLY of `enabled` so flash stays usable
                    // in DualShot; `onFlip` lambda also re-checks to prevent
                    // accessibility-tool callers from bypassing the visual
                    // disable.
                    // B6 (codex review) — also require a front sensor: a
                    // front-less device must not show a dead flip toggle (a no-op
                    // tap would latch the "Switching…" overlay).
                    val flipAllowed = !isUiLocked && mode != DualShotPortraitGate.DUAL_SHOT && serviceState.hasFrontCamera
                    RecordCameraControls(
                        flashMode = flashMode,
                        onCycleFlash = { if (!isUiLocked) viewModel.setFlashMode((flashMode + 1) % 3) },
                        // Capture the current generation BEFORE launching the flip
                        // so the clear (FlipLatchPolicy) fires on the next bind. Block
                        // re-taps while a flip is in flight so the 4 s backstop timer
                        // (keyed on flipInFlight) is never stranded by a second flip.
                        onFlip = {
                            if (flipAllowed && !flipInFlight) {
                                flipStartGeneration = serviceState.cameraConfigGeneration
                                flipInFlight = true
                                viewModel.flipCamera()
                            }
                        },
                        enabled = !isUiLocked,
                        flipEnabled = flipAllowed,
                        isFrontCamera = serviceState.isFrontCamera,
                        orientation = chromeOrientation,
                        modifier = slotModifier(
                            placementFor(ChromeSlot.CAMERA_CONTROLS, chromeOrientation),
                            chromeTopInsets,
                        ),
                        spinDegrees = spinDegrees,
                    )
                }
                // PR-β′ — ONE nav in both orientations: the portrait bottom bar rotated
                // to a vertical rail on the cluster edge (rotation-mapped order). Nav-
                // Settings navigates to the Settings SCREEN in BOTH orientations (same as
                // portrait — the config-strip tap opens the sheet); the §B rail-toggles-
                // sheet repurposing is removed (acceptance — same muscle memory).
                RecordBottomNav(
                    fabState = fabState,
                    navItemsLocked = isUiLocked,
                    onLibrary = onNavigateToHistory,
                    onSettings = onNavigateToSettings,
                    onFabClick = onFabClick,
                    sense = currentSense,
                    modifier = if (currentSense != null) {
                        Modifier.align(if (clusterAnchor == NavEdge.Trailing) Alignment.CenterEnd else Alignment.CenterStart)
                    } else {
                        Modifier.align(Alignment.BottomCenter)
                    },
                    spinDegrees = spinDegrees,
                )
                }   // close a11y wrapper — semantics pruned while panel open
                }   // close chrome gate — suppressed behind the sheet (Adaptive only; §B″5)

                // Phase 2.4 — Merge Complete card. Brief overlay
                // shown for ~900 ms between merge success and the
                // existing auto-navigation to Library, so the success
                // state registers before the screen swap. The grace
                // is owned by `LaunchedEffect(isMerging, mergeError)`
                // above — this block only renders.
                if (showCompleteCard) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        MergeCompleteCard(clipCount = lastCompleteClipCount)
                    }
                }

                // Task 14 — the in-app tutorial overlay (3-step walkthrough) is
                // removed; onboarding owns the first-run tutorial now (spec A2).

                // Settings surface — presentation by chrome mode (ADR-0029 §B″5,
                // owner-ratified 2026-06-12). FixedPhysical (compact): a floating
                // near-square card over the viewfinder — record CHROME that spins
                // as one unit via SpinningBox; no scrim; tap-outside / ✕ / back
                // dismiss. Adaptive (sw600dp+): the pre-existing camera-peek
                // bottom sheet / side panel, untouched. Both are always emitted
                // and own their open/close animation via `visible`; edits write
                // through immediately via the SAME ViewModel plumbing.
                // Phase 4 Slice 3 — thermal tips bottom sheet. Hosted here so
                // the sheet survives the same HUD transitions as the settings
                // surface. On compact it renders portrait under the permanent
                // lock (§B″5 known deviation, follow-up).
                if (showTipsSheet) {
                    ThermalTipsSheet(onDismiss = { showTipsSheet = false })
                }

                if (chromeModeNow == ChromeMode.FixedPhysical) {
                    FloatingSettingsPanel(
                        visible = combinedOpen,
                        spinDegrees = spinDegrees,
                        durationSeconds = duration,
                        loopCount = loopCount,
                        intervalMinutes = interval,
                        quality = resolution,
                        currentMode = mode,
                        editable = !isUiLocked,
                        presets = allPresets,
                        activePresetId = activePresetId,
                        onApplyPreset = viewModel::applyPreset,
                        onSavePreset = viewModel::savePreset,
                        onDeletePreset = viewModel::deletePreset,
                        onDurationChange = { viewModel.duration.value = it },
                        onLoopCountChange = { viewModel.loopCount.value = it },
                        onIntervalChange = { viewModel.interval.value = it },
                        onQualityChange = { viewModel.resolution.value = it },
                        onModePick = { picked ->
                            // ADR-0029 — defer a landscape→DualShot pick until portrait;
                            // any other pick commits now and clears a pending switch.
                            // (On compact the window is always portrait, so this never
                            // defers in practice — kept identical to the sheet wiring.)
                            if (DualShotPortraitGate.shouldDefer(picked, isPortrait)) {
                                pendingMode = picked
                            } else {
                                pendingMode = null
                                viewModel.setTopology(picked)
                            }
                        },
                        snoozedCount = snoozedSet.size,
                        onResetSnoozes = if (snoozedSet.isNotEmpty()) {
                            { warningVm.clearSnoozes() }
                        } else null,
                        orientationPolicy = orientationPolicy,
                        orientationLockRotation = orientationLockRotation,
                        orientationEnabled = !isUiLocked && mode != DualShotPortraitGate.DUAL_SHOT,
                        currentDeviceRotation = currentDeviceRotation,
                        onOrientationPick = { policy, lockRot ->
                            if (!isUiLocked) viewModel.setOrientationPolicy(policy, lockRot)
                        },
                        onDismiss = { viewModel.closeSettingsSheet() },
                    )
                } else {
                SettingsSheet(
                    visible = combinedOpen,
                    durationSeconds = duration,
                    loopCount = loopCount,
                    intervalMinutes = interval,
                    quality = resolution,
                    currentMode = mode,
                    editable = !isUiLocked,
                    presets = allPresets,
                    activePresetId = activePresetId,
                    onApplyPreset = viewModel::applyPreset,
                    onSavePreset = viewModel::savePreset,
                    onDeletePreset = viewModel::deletePreset,
                    statusText = statusText,
                    flashMode = flashMode,
                    flipEnabled = !isUiLocked && mode != DualShotPortraitGate.DUAL_SHOT && serviceState.hasFrontCamera,
                    isFrontCamera = serviceState.isFrontCamera,
                    onCycleFlash = { if (!isUiLocked) viewModel.setFlashMode((flashMode + 1) % 3) },
                    onFlip = {
                        if (!isUiLocked && mode != DualShotPortraitGate.DUAL_SHOT && serviceState.hasFrontCamera) {
                            flipInFlight = true
                            viewModel.flipCamera()
                        }
                    },
                    onDurationChange = { viewModel.duration.value = it },
                    onLoopCountChange = { viewModel.loopCount.value = it },
                    onIntervalChange = { viewModel.interval.value = it },
                    onQualityChange = { viewModel.resolution.value = it },
                    onModePick = { picked ->
                        // ADR-0029 — defer a landscape→DualShot pick until portrait; any
                        // other pick commits now and clears a pending switch.
                        if (DualShotPortraitGate.shouldDefer(picked, isPortrait)) {
                            pendingMode = picked
                        } else {
                            pendingMode = null
                            viewModel.setTopology(picked)
                        }
                    },
                    snoozedCount = snoozedSet.size,
                    onResetSnoozes = if (snoozedSet.isNotEmpty()) {
                        { warningVm.clearSnoozes() }
                    } else null,
                    orientationPolicy = orientationPolicy,
                    orientationLockRotation = orientationLockRotation,
                    orientationEnabled = !isUiLocked && mode != DualShotPortraitGate.DUAL_SHOT,
                    currentDeviceRotation = currentDeviceRotation,
                    onOrientationPick = { policy, lockRot ->
                        if (!isUiLocked) viewModel.setOrientationPolicy(policy, lockRot)
                    },
                    onDismiss = { viewModel.closeSettingsSheet() },
                )
                }   // close settings-surface presentation branch (panel / sheet)
            }   // close Box
        }       // close Scaffold content lambda

    // Phase 2.4 — Merge HUD progression. Merging renders inside the
    // active HUD's [StatusPill] (RecordActiveHud's Merging branch),
    // sharing the locked-chrome contract with Recording / Waiting.
    //
    // On the falling edge of `isMerging`:
    //   - mergeError == null → show the brief [MergeCompleteCard]
    //     overlay, then call the existing `onMergeFinished` to
    //     navigate to Library. The grace window (about 900 ms) lets
    //     the success state register before the screen swap.
    //   - mergeError != null → show a single-shot snackbar so the
    //     user has a breadcrumb. Per Phase 2.4 scope, we add no
    //     recover/merge actions here — the Library recovery card
    //     surfaces on next launch via the existing Phase 1.5 path.
    LaunchedEffect(serviceState.isMerging, serviceState.mergeError) {
        if (serviceState.isMerging) {
            wasMerging = true
            // Bug A — the count is resolved on the FALLING edge (below), not
            // here. On the rising edge `exportedClipCount` is not yet published
            // (performMerge sets it only on the success path), so stashing here
            // captured 0 on an early user-stop ("0 clips saved to library").
        } else if (wasMerging) {
            wasMerging = false
            // Bug A — resolve the real saved-clip count right before the
            // MergeCompleteCard shows. exportedClipCount is success-owned (set
            // in performMerge); segmentCount is the loop-exhaust fallback.
            val real = MergeCompleteCount.resolve(
                serviceState.exportedClipCount,
                serviceState.segmentCount,
            )
            if (real > 0) lastCompleteClipCount = real
            // B4c — the save-folder flag can flip at SESSION START (a custom
            // folder gone at freeze → fell back to default) while the user never
            // leaves the record screen, so ON_RESUME never re-reads it. Refresh
            // here, on return to idle, so the SAVE_FOLDER_UNAVAILABLE advisory
            // actually surfaces after a fallback recording.
            rovaApp?.saveFolderSignal?.refresh()
            if (serviceState.mergeError == null) {
                showCompleteCard = true
                delay(900L)
                showCompleteCard = false
                onMergeFinished()
            } else {
                scope.launch {
                    // B4c — a gone/unwritable custom SAF folder is not a merge
                    // failure with a Library recovery entry; show the accurate
                    // "folder unavailable, recording kept" copy instead.
                    snackbarHostState.showSnackbar(
                        context.getString(
                            if (serviceState.saveFolderUnavailable)
                                R.string.record_save_folder_unavailable
                            else R.string.record_merge_failed
                        )
                    )
                }
            }
        }
    }

    // Task 14 — the "Save Preset" dialog is removed. The custom-preset CRUD VM
    // methods (savePreset / deletePreset) stay but are dormant (spec dormancy).
}

