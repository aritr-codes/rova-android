package com.aritr.rova.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.aritr.rova.ui.components.RovaAnimations.pulsingOpacity
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
    val customPresets by viewModel.customPresets.collectAsStateWithLifecycle()
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

    val keepScreenOn by settingsViewModel.keepScreenOn.collectAsStateWithLifecycle()
    val enableBeeps by settingsViewModel.enableBeeps.collectAsStateWithLifecycle()

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

    // UI State
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }

    // Tutorial State
    var showTutorial by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableIntStateOf(0) }

    // Battery optimization banner — dismissed flag survives rotation; resets on next app launch
    var batteryBannerDismissed by rememberSaveable { mutableStateOf(false) }
    val showBatteryBanner = !batteryBannerDismissed && !BatteryOptimizationHelper.isIgnoring(context)

    // Recording error snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(serviceState.recordingError) {
        serviceState.recordingError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearRecordingError()
        }
    }

    // Release camera when app goes to background (unless recording)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.stopCameraPreview()
                Lifecycle.Event.ON_START -> viewModel.startCameraPreview()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isUiLocked = serviceState.isPeriodicActive || serviceState.isMerging
    val isCameraActive = serviceState.isCameraActive

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

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !serviceState.isRecording,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Drawer "Quick settings" panel preserved per Slice 2
                    // contract; Slice 5 owns drawer cleanup.
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                "Quick settings",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Adjust capture behavior without leaving the camera.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                            )
                        }
                    }
                    Text(
                        "Recording",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    SwitchRow(
                        Icons.Default.Smartphone, "Keep Screen On", "Prevent screen timeout",
                        keepScreenOn
                    ) { settingsViewModel.keepScreenOn.value = it }
                    SwitchRow(
                        Icons.AutoMirrored.Filled.VolumeUp, "Sounds", "Start/Stop beeps",
                        enableBeeps
                    ) { settingsViewModel.enableBeeps.value = it }
                }
            }
        }
    ) {
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

                // Top Bar + Battery Optimization Banner + Recovery Echo
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(listOf(Color.Black.copy(0.6f), Color.Transparent))
                            )
                            .padding(16.dp)
                            .padding(top = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Settings", tint = Color.White)
                        }
                        if (serviceState.isRecording && isCameraActive) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color.Black.copy(alpha = 0.35f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .size(12.dp)
                                            .background(Color.Red, CircleShape)
                                            .alpha(pulsingOpacity())
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "REC",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        } else if (serviceState.isRecording && !isCameraActive) {
                            Text("INITIALIZING...", color = Color.Yellow, fontWeight = FontWeight.Bold)
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Rova", color = Color.White, fontWeight = FontWeight.Bold)
                                Text(
                                    "Hands-free loop recorder",
                                    color = Color.White.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        Row {
                            val iconTint = if (isUiLocked) Color.Gray else Color.White
                            IconButton(
                                onClick = {
                                    if (!isUiLocked) viewModel.setFlashMode((flashMode + 1) % 3)
                                },
                                enabled = !isUiLocked
                            ) {
                                val icon = when (flashMode) {
                                    RovaRecordingService.FLASH_MODE_ON -> Icons.Default.FlashOn
                                    RovaRecordingService.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                                    else -> Icons.Default.FlashOff
                                }
                                Icon(
                                    icon, "Flash",
                                    tint = if (flashMode == RovaRecordingService.FLASH_MODE_ON) Color.Yellow else iconTint
                                )
                            }
                            IconButton(
                                onClick = { if (!isUiLocked) viewModel.flipCamera() },
                                enabled = !isUiLocked
                            ) {
                                Icon(Icons.Default.FlipCameraIos, "Flip", tint = iconTint)
                            }
                            IconButton(
                                onClick = { showTutorial = true; tutorialStep = 0 },
                                enabled = !isUiLocked
                            ) {
                                Icon(Icons.Outlined.Info, "Help", tint = iconTint)
                            }
                        }
                    }
                    if (showBatteryBanner) {
                        BatteryOptimizationBanner(
                            onAction = {
                                val intent = BatteryOptimizationHelper.buildRequestIntent(context.packageName)
                                try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
                            },
                            onDismiss = { batteryBannerDismissed = true }
                        )
                    }
                    // Slice 2 — read-only recovery echo. Idle only;
                    // hidden when no interrupted sessions are tracked.
                    if (!serviceState.isPeriodicActive && interruptedSessionCount > 0) {
                        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            RecoveryEchoBanner(
                                interruptedCount = interruptedSessionCount,
                                onReviewInHistory = onNavigateToHistory
                            )
                        }
                    }
                } // Column: top bar + battery banner + recovery echo

                // ------------------------------------------------------
                // Bottom area: idle dock OR active overlay + Stop FAB.
                // ------------------------------------------------------
                if (serviceState.isPeriodicActive) {
                    // Active mode is owned by Slice 3. Slice 2 preserves
                    // the existing active UI verbatim.
                    SessionStatusCard(
                        isRecording = serviceState.isRecording,
                        nextRecordingIn = serviceState.nextRecordingCountdown,
                        currentLoop = serviceState.currentLoop,
                        totalLoops = serviceState.totalLoops,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 140.dp)
                            .padding(horizontal = 16.dp)
                    )
                    // Stop FAB
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = { viewModel.stopRecording() },
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            expanded = true,
                            icon = { Icon(Icons.Default.Stop, null) },
                            text = {
                                Text(
                                    text = "STOP RECORDING",
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .height(72.dp)
                        )
                    }
                } else {
                    // Idle: Slice 2 dock anchored at the bottom of the
                    // overlay box. Sibling of the bottom nav, never an
                    // absolute overlay.
                    RecordIdleDock(
                        durationSeconds = duration,
                        loopCount = loopCount,
                        intervalMinutes = interval,
                        quality = resolution,
                        customPresets = customPresets,
                        onCellTap = { viewModel.openSheet(it) },
                        onPresetSelected = { p ->
                            viewModel.duration.value = p.duration
                            viewModel.interval.value = p.interval
                            viewModel.loopCount.value = p.loopCount
                            viewModel.resolution.value = p.resolution
                        },
                        onPresetDeleted = { viewModel.deletePreset(it) },
                        onSavePreset = { presetNameInput = ""; showSavePresetDialog = true },
                        onStart = onStart,
                        startEnabled = !isUiLocked,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                // Tutorial Overlay
                if (showTutorial) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.8f))
                            .clickable {
                                if (tutorialStep < 2) tutorialStep++ else showTutorial = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            when (tutorialStep) {
                                0 -> {
                                    Icon(Icons.Default.Tune, null, tint = Color.White, modifier = Modifier.size(48.dp))
                                    Spacer(Modifier.height(16.dp))
                                    Text("Customize Your Recording", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                    Text(
                                        "Tap any of the four cells to set Clip length, Repeats, Wait, or Quality.",
                                        color = Color.White.copy(0.8f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                                1 -> {
                                    Icon(Icons.Default.Videocam, null, tint = Color.White, modifier = Modifier.size(48.dp))
                                    Spacer(Modifier.height(16.dp))
                                    Text("Hands-Free Mode", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                    Text(
                                        "Rova will automatically start and stop recording loops based on your settings.",
                                        color = Color.White.copy(0.8f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                                2 -> {
                                    Icon(Icons.Default.Save, null, tint = Color.White, modifier = Modifier.size(48.dp))
                                    Spacer(Modifier.height(16.dp))
                                    Text("Save Presets", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                    Text(
                                        "Save your favorite configurations for quick access later.",
                                        color = Color.White.copy(0.8f),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(24.dp))
                                    Button(onClick = { showTutorial = false }) { Text("Got it!") }
                                }
                            }
                            Spacer(Modifier.height(32.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                repeat(3) { i ->
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (i == tutorialStep) Color.White else Color.Gray)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------
    // Edit sheets — single-target router driven by editingField. The
    // sheet lives outside the drawer/scaffold so the ModalBottomSheet
    // overlays the entire screen including the drawer scrim.
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

    // Merge Overlay
    if (serviceState.isMerging) {
        AlertDialog(
            onDismissRequest = { /* Prevent dismiss */ },
            title = { Text("Merging Video...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(
                        progress = { serviceState.mergeProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${(serviceState.mergeProgress * 100).toInt()}%")
                }
            },
            confirmButton = {},
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        )
    }

    // Handle Navigation on Merge Complete
    var wasMerging by remember { mutableStateOf(false) }
    LaunchedEffect(serviceState.isMerging, serviceState.mergeError) {
        if (serviceState.isMerging) {
            wasMerging = true
        } else {
            if (wasMerging && serviceState.mergeError == null) {
                onMergeFinished()
            }
            wasMerging = false
        }
    }

    // Save Preset Dialog
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text("Save Preset") },
            text = {
                OutlinedTextField(
                    value = presetNameInput,
                    onValueChange = { presetNameInput = it },
                    label = { Text("Preset Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (presetNameInput.isNotBlank()) {
                            viewModel.savePreset(presetNameInput)
                            showSavePresetDialog = false
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// Formatting Helpers — retained for any consumers outside RecordScreen.
fun formatDuration(seconds: Int): String {
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

fun formatInterval(minutes: Int): String {
    return if (minutes == 0) "No wait" else "${minutes}m"
}
