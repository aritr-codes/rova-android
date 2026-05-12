package com.aritr.rova.ui.screens

import android.Manifest
import android.os.Build
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.RovaApp
import com.aritr.rova.service.RovaRecordingService
import com.aritr.rova.service.recovery.RecoveryReport
import com.aritr.rova.ui.recovery.RecoveryViewSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import com.aritr.rova.ui.components.*
import com.aritr.rova.ui.components.RecordHudFormatters
import com.aritr.rova.ui.components.RecordHudState
import com.aritr.rova.ui.components.RecordStatusStrip
import com.aritr.rova.ui.components.SessionTimer
import com.aritr.rova.ui.components.ClipProgressBand
import com.aritr.rova.ui.components.WaitingCountdown
import com.aritr.rova.ui.components.MergingProgressBand
import com.aritr.rova.ui.components.MergeCompleteCard
import com.aritr.rova.ui.warnings.WarningCenter
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
    val editingField by viewModel.editingField.collectAsStateWithLifecycle()

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

    // Q3: Wire keepScreenOn to the view flag
    DisposableEffect(keepScreenOn) {
        localView.keepScreenOn = keepScreenOn
        onDispose { localView.keepScreenOn = false }
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
            viewModel.setSurfaceProvider(null)
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
    LaunchedEffect(duration, loopCount, resolution) {
        rovaApp?.storageSignal?.recompute(duration, loopCount, resolution)
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
                Lifecycle.Event.ON_STOP -> viewModel.stopCameraPreview()
                Lifecycle.Event.ON_START -> viewModel.startCameraPreview()
                Lifecycle.Event.ON_RESUME -> rovaApp?.let {
                    it.notificationPermissionSignal.refresh()
                    it.thermalStatusSignal.refresh()
                    it.powerSignal.refresh()
                    it.batteryOptimizationSignal.refresh()
                    it.cameraPermissionSignal.refresh()
                    it.microphonePermissionSignal.refresh()
                    it.storageSignal.recompute(
                        viewModel.duration.value,
                        viewModel.loopCount.value,
                        viewModel.resolution.value
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

    // Slice 3 — wall-clock derived presentation timers for the active
    // HUD. Anchors live on the VM (set on rising edge of the matching
    // service flag); the composable ticks 1 Hz against those anchors
    // and re-publishes the elapsed seconds as Compose state. When the
    // anchor is null (idle / between rising edges) elapsed is 0 and
    // the producer suspends until the next anchor change.
    val sessionAnchorMillis by viewModel.sessionAnchorMillis.collectAsStateWithLifecycle()
    val clipAnchorMillis by viewModel.clipAnchorMillis.collectAsStateWithLifecycle()
    val sessionElapsedSeconds by produceState(
        initialValue = 0L,
        key1 = sessionAnchorMillis
    ) {
        val anchor = sessionAnchorMillis
        if (anchor == null) {
            value = 0L
            return@produceState
        }
        while (true) {
            val now = System.currentTimeMillis()
            value = ((now - anchor) / 1000L).coerceAtLeast(0L)
            delay(1000L)
        }
    }
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
    // Round the live-region announcement down to the nearest minute
    // so TalkBack speaks the timer at most once per 60 s (per
    // UI_ROADMAP §"Slice 3 special requirements" — no per-second
    // accessibility chatter).
    val sessionAnnouncementSeconds = sessionElapsedSeconds - (sessionElapsedSeconds % 60L)
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
        segmentCount = serviceState.segmentCount
    )

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

    // Beta-smoke fix: cover CameraX preview warm-up with the existing
    // loading overlay. The service flips `isCameraActive` true the
    // moment `bindToLifecycle` returns, but PreviewView's TextureView
    // does not receive its first frame until ~500-1500 ms later
    // (especially after the unconditional `forceReconfigureCamera` at
    // record-start in commit e79f225). Without this grace, the user
    // sees an unexplained black gap before the first frame. Setting
    // `cameraWarmingUp = true` for 1500 ms after the false→true
    // transition keeps the "Initializing Camera..." overlay on screen
    // through that window. Idle preview (no transition) is unaffected.
    var cameraWarmingUp by remember { mutableStateOf(false) }
    LaunchedEffect(isCameraActive) {
        if (isCameraActive) {
            cameraWarmingUp = true
            delay(1500)
            cameraWarmingUp = false
        } else {
            cameraWarmingUp = false
        }
    }

    // Camera Disconnected Alert
    if (serviceState.isRecording && !isCameraActive) {
        AlertDialog(
            onDismissRequest = { /* Force user to stop */ },
            title = { Text("Camera Disconnected") },
            text = { Text("The camera connection was lost during recording.") },
            confirmButton = {
                Button(
                    onClick = { viewModel.stopRecording() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("STOP RECORDING")
                }
            },
            icon = { Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error) }
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
                    "Open Rova on screen before starting. Android blocks camera launches from the background."
                com.aritr.rova.service.StartBlocked.FGS_RESTRICTED ->
                    "Cannot start recording — Android requires the app to be in the foreground."
                com.aritr.rova.service.StartBlocked.UNKNOWN_ISE ->
                    "Recording could not start. Please try again."
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
    val hardBlockActive = startBlocked   // = !cameraPermissionGranted || storageInsufficient (already computed)
    val sessionLocked = serviceState.isPeriodicActive || serviceState.isMerging
    val fabState = recordFabState(hudState, sessionLocked = sessionLocked, hardBlockActive = hardBlockActive)
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
        RecordHudState.Recording -> { statusText = "Recording"; statusDetail = "${clipElapsedSeconds}s of ${duration}s" }
        RecordHudState.Waiting   -> { statusText = "On break"; statusDetail = "next in ${displayedCountdownSeconds}s" }
        is RecordHudState.Merging -> { statusText = "Merging"; statusDetail = null }
        RecordHudState.Idle -> { statusText = "Ready to record"; statusDetail = null }
    }

    // Task 14 — the ModalNavigationDrawer wrapper (with its "Quick settings"
    // panel + the two SwitchRows) is removed; Keep-Screen-On / Sounds now live
    // in the Settings screen. settingsViewModel is still consumed for the
    // keepScreenOn DisposableEffect above.
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black
    ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color.Black)
            ) {
                // Camera Preview
                if (hasCapturePermissions) {
                    AndroidView(
                        factory = { _ -> previewView },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Loading Overlay — also held during the 1500 ms
                // CameraX warm-up window so the user does not see an
                // unexplained black gap between bind and first frame.
                if ((!isCameraActive || cameraWarmingUp) && !serviceState.isMerging) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(16.dp))
                            Text("Initializing Camera...", color = Color.White)
                        }
                    }
                }

                // Task 14 — the old "top bar" Column (app-bar Row + WarningCenter
                // + RecoveryEchoBanner + RecordStatusStrip) is broken apart:
                //   - WarningCenter() is a standalone Box child now (it renders a
                //     ModalBottomSheet / chip and positions itself).
                //   - the recovery echo becomes RecordRecoveryChip pinned under the
                //     status pill (see below).
                //   - only the active-HUD status strip stays in a slimmed Column,
                //     pushed below the top overlay.
                if (hudState == RecordHudState.Recording ||
                    hudState == RecordHudState.Waiting
                ) {
                    // Slice 3 / Phase 2.4 — persistent active-HUD status strip.
                    // Mounted only for Recording or Waiting; the merge body is
                    // self-contained and does not reuse the REC/WAIT badge layout.
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(top = 100.dp)
                    ) {
                        Spacer(Modifier.height(8.dp))
                        RecordStatusStrip(
                            isRecording = hudState == RecordHudState.Recording,
                            currentLoop = serviceState.currentLoop,
                            totalLoops = serviceState.totalLoops,
                            totalElapsedSeconds = sessionElapsedSeconds
                        )
                    }
                }
                // Slice 2 / Phase 2.4 — read-only recovery echo, now a chip pinned
                // just below the status pill. Idle only; hidden during Recording,
                // Waiting, or Merging so the active HUD owns the user's attention.
                if (hudState == RecordHudState.Idle && interruptedSessionCount > 0) {
                    RecordRecoveryChip(
                        count = interruptedSessionCount,
                        onReview = onNavigateToHistory,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(start = 16.dp, top = 70.dp),   // sits just below the status pill (~16 + pill height)
                    )
                }
                // ADR 0007 — renders the warning sheet (modal) or, if collapsed, a
                // chip. The chip lands near the status pill via this wrapper; the
                // modal sheet ignores the wrapper's position.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 16.dp, top = 110.dp)
                ) {
                    WarningCenter()
                }

                // ------------------------------------------------------
                // Bottom area:
                //   Recording / Waiting → Slice 3 active HUD with
                //                         SessionTimer + body + Stop.
                //   Merging              → Phase 2.4 merge body only;
                //                         no SessionTimer, no Stop.
                //   Idle                 → Slice 2 idle dock.
                // ------------------------------------------------------
                if (hudState is RecordHudState.Recording ||
                    hudState is RecordHudState.Waiting
                ) {
                    val timerSubtitle = when (hudState) {
                        RecordHudState.Recording -> RecordHudFormatters.formatRecordingMeta(
                            quality = resolution,
                            flashLabel = RecordHudFormatters.formatFlashLabel(flashMode)
                        )
                        RecordHudState.Waiting -> RecordHudFormatters.formatNextClipDurationLabel(duration)
                        else -> null
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 96.dp)
                    ) {
                        SessionTimer(
                            elapsedSeconds = sessionElapsedSeconds,
                            announcementSeconds = sessionAnnouncementSeconds,
                            label = "Session elapsed",
                            subtitle = timerSubtitle
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            // Clear the bottom nav. The big-red "Stop recording"
                            // Button is gone; Stop is the FAB now.
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = 90.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (hudState) {
                            RecordHudState.Recording -> ClipProgressBand(
                                elapsedSeconds = clipElapsedSeconds,
                                clipSeconds = duration
                            )
                            RecordHudState.Waiting -> WaitingCountdown(
                                nextClipInSeconds = displayedCountdownSeconds,
                                currentLoop = serviceState.currentLoop,
                                totalLoops = serviceState.totalLoops
                            )
                            else -> Unit
                        }
                    }
                } else if (hudState is RecordHudState.Merging) {
                    // Phase 2.4 — merge body anchored to the bottom of
                    // the screen. No SessionTimer, no Stop button: the
                    // merge runs `NonCancellable` for terminal-write
                    // atomicity (ADR 0006), so a Stop affordance would
                    // be a visual lie.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 32.dp)
                            // Clear the bottom nav.
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = 90.dp)
                    ) {
                        MergingProgressBand(
                            progress = hudState.progress,
                            currentSegment = hudState.currentSegment,
                            totalSegments = hudState.totalSegments
                        )
                    }
                } else if (!showCompleteCard) {
                    // Idle: camera-first body. The settings card sits above the
                    // bottom nav; the viewfinder is the camera preview behind. The
                    // recovery chip (if any) is rendered separately near the status
                    // pill (see above).
                    //
                    // Phase 2.4 — suppressed during the brief post-merge grace
                    // window so a rapid Start tap cannot race the impending nav to
                    // Library. The FAB owns Start; per-preset / save-preset
                    // plumbing is gone (the VM methods stay, just unused — spec
                    // dormancy).
                    RecordSettingsCard(
                        durationSeconds = duration,
                        loopCount = loopCount,
                        intervalMinutes = interval,
                        quality = resolution,
                        onOpenSheet = { viewModel.openSettingsSheet() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = 90.dp, start = 16.dp, end = 16.dp),   // clear the bottom nav; tune to RecordBottomNav's height
                    )
                }

                // ── Task 13/14 — new chrome: top overlay + cam-controls (top),
                // bottom nav with the Start/Stop FAB (bottom). Painted after
                // the bottom-area `when` so it sits on top of the camera
                // preview / status strip / settings card. The MergeCompleteCard
                // below still paints over this chrome.
                RecordTopOverlay(
                    hudState = hudState,
                    statusText = statusText,
                    statusDetail = statusDetail,
                    currentLoop = serviceState.currentLoop,
                    totalLoops = serviceState.totalLoops,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 16.dp, top = 16.dp),
                )
                RecordCameraControls(
                    flashMode = flashMode,
                    onCycleFlash = { if (!isUiLocked) viewModel.setFlashMode((flashMode + 1) % 3) },
                    onFlip = { if (!isUiLocked) viewModel.flipCamera() },
                    enabled = !isUiLocked,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        // 7.dp + the 9.dp the 30.dp glass circle is inset within its
                        // 48.dp touch box ≈ the mockup's 16.dp from the safe-area edge.
                        .padding(end = 7.dp, top = 7.dp),
                )
                RecordBottomNav(
                    fabState = fabState,
                    navItemsLocked = sessionLocked,
                    onLibrary = onNavigateToHistory,
                    onSettings = onNavigateToSettings,
                    onFabClick = onFabClick,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

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
            }   // close Box
        }       // close Scaffold content lambda

    // --------------------------------------------------------------
    // Edit sheets — single-target router driven by editingField. Lives outside
    // the Scaffold so the ModalBottomSheet overlays the entire screen. When
    // invoked from a SessionSettingsSheet row, the per-param sheet stacks ON TOP
    // of the combined sheet.
    // --------------------------------------------------------------
    when (editingField) {
        SheetTarget.ClipLength -> ClipLengthEditSheet(
            initialSeconds = duration,
            onCommit = { viewModel.duration.value = it.coerceIn(1, 300) },
            onCancel = { viewModel.closeSheet() }
        )
        SheetTarget.Repeats -> RepeatsEditSheet(
            initialLoopCount = loopCount,
            onCommit = { viewModel.loopCount.value = it },
            onCancel = { viewModel.closeSheet() }
        )
        SheetTarget.Wait -> WaitEditSheet(
            initialMinutes = interval,
            onCommit = { viewModel.interval.value = it.coerceAtLeast(0) },
            onCancel = { viewModel.closeSheet() }
        )
        SheetTarget.Quality -> QualityEditSheet(
            initialQuality = resolution,
            onCommit = { viewModel.resolution.value = it },
            onCancel = { viewModel.closeSheet() }
        )
        null -> Unit
    }

    // Task 12/14 — the combined "Session settings" sheet. Opened from the idle
    // settings card; each row hands off to the per-param editingField router
    // above, so the per-param sheet stacks ON TOP of this one.
    val combinedOpen by viewModel.combinedSettingsOpen.collectAsStateWithLifecycle()
    if (combinedOpen) {
        SessionSettingsSheet(
            durationSeconds = duration,
            loopCount = loopCount,
            intervalMinutes = interval,
            quality = resolution,
            onPickRow = { target -> viewModel.openSheet(target) },   // opens the existing per-param sheet ON TOP
            onDismiss = { viewModel.closeSettingsSheet() },
        )
    }

    // Phase 2.4 — Merge HUD progression.
    //
    // The legacy `AlertDialog` overlay is gone. Merging now renders
    // inside the active HUD column as a [MergingProgressBand] body
    // (see the `hudState is RecordHudState.Merging` branch below),
    // so it shares the same locked-chrome contract as Recording /
    // Waiting and never duplicates focus with the camera surface.
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
            // Stash the running clip count so the post-merge card
            // can still describe the session after the service has
            // flipped its counters back to zero.
            if (serviceState.segmentCount > 0) {
                lastCompleteClipCount = serviceState.segmentCount
            }
        } else if (wasMerging) {
            wasMerging = false
            if (serviceState.mergeError == null) {
                showCompleteCard = true
                delay(900L)
                showCompleteCard = false
                onMergeFinished()
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "Merge failed. Open Library to recover."
                    )
                }
            }
        }
    }

    // Task 14 — the "Save Preset" dialog is removed. The custom-preset CRUD VM
    // methods (savePreset / deletePreset) stay but are dormant (spec dormancy).
}

// Formatting Helpers — retained for any consumers outside RecordScreen.
fun formatDuration(seconds: Int): String {
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

fun formatInterval(minutes: Int): String {
    return if (minutes == 0) "No wait" else "${minutes}m"
}
