package com.aritr.rova.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.aritr.rova.data.QualityPresets
import com.aritr.rova.data.RovaPreset
import com.aritr.rova.service.RovaRecordingService
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
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true,
        )
    )
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }

    // Tutorial State
    var showTutorial by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableIntStateOf(0) }

    // Bottom sheet expanded controls scroll state (hoisted to avoid recreation)
    val expandedScrollState = rememberScrollState()

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

    // Auto-collapse sheet on recording start
    LaunchedEffect(serviceState.isPeriodicActive) {
        if (serviceState.isPeriodicActive) {
            scaffoldState.bottomSheetState.partialExpand()
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
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            sheetPeekHeight = 130.dp,
            sheetSwipeEnabled = !isUiLocked,
            sheetContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            sheetContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .alpha(if (isUiLocked) 0.5f else 1f),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // PEEK Area
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Quick Presets",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (scaffoldState.bottomSheetState.targetValue == SheetValue.Expanded) {
                                OutlinedButton(
                                    onClick = { presetNameInput = ""; showSavePresetDialog = true },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("Save Current", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val defaultPresets = listOf(
                                RovaPreset("Drill", 10, 1, 10, QualityPresets.FHD),
                                RovaPreset("Vlog", 60, 0, -1, QualityPresets.HD),
                            )
                            items(defaultPresets) { p ->
                                val isSelected = duration == p.duration &&
                                    interval == p.interval &&
                                    loopCount == p.loopCount &&
                                    resolution == p.resolution
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (!isUiLocked) {
                                            viewModel.duration.value = p.duration
                                            viewModel.interval.value = p.interval
                                            viewModel.loopCount.value = p.loopCount
                                            viewModel.resolution.value = p.resolution
                                        }
                                    },
                                    enabled = !isUiLocked,
                                    label = { Text(p.name) },
                                    leadingIcon = { if (isSelected) Icon(Icons.Default.Check, null) }
                                )
                            }
                            items(customPresets) { p ->
                                val isSelected = duration == p.duration &&
                                    interval == p.interval &&
                                    loopCount == p.loopCount &&
                                    resolution == p.resolution
                                InputChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (!isUiLocked) {
                                            viewModel.duration.value = p.duration
                                            viewModel.interval.value = p.interval
                                            viewModel.loopCount.value = p.loopCount
                                            viewModel.resolution.value = p.resolution
                                        }
                                    },
                                    enabled = !isUiLocked,
                                    label = { Text(p.name) },
                                    trailingIcon = {
                                        if (!isUiLocked) Icon(
                                            Icons.Default.Close, "Delete",
                                            Modifier
                                                .size(16.dp)
                                                .clickable { viewModel.deletePreset(p) }
                                        )
                                    },
                                    avatar = if (isSelected) { { Icon(Icons.Default.Check, null) } } else null
                                )
                            }
                        }

                        // Summary (Visible in Peek)
                        if (scaffoldState.bottomSheetState.currentValue == SheetValue.PartiallyExpanded) {
                            Spacer(Modifier.height(12.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                item { RecordStatChip("Duration ${formatDuration(duration)}") }
                                item { RecordStatChip("Interval ${formatInterval(interval)}") }
                                item { RecordStatChip("Loops ${if (loopCount == -1) "∞" else loopCount}") }
                                item { RecordStatChip(resolution) }
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    // EXPANDED Controls
                    Column(modifier = Modifier.verticalScroll(expandedScrollState)) {
                        Text(
                            "Record Duration",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        StepperControl(
                            value = duration,
                            onValueChange = { viewModel.duration.value = it.coerceIn(1, 300) },
                            range = 1..300,
                            step = if (duration < 60) 5 else 15,
                            unit = "s",
                            modifier = Modifier.padding(top = 8.dp),
                            enabled = !isUiLocked
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Interval Between Loops",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        StepperControl(
                            value = interval,
                            onValueChange = { viewModel.interval.value = it.coerceIn(0, 1440) },
                            range = 0..1440,
                            step = 1,
                            unit = "m",
                            modifier = Modifier.padding(top = 8.dp),
                            enabled = !isUiLocked
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Loop Count",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StepperControl(
                                value = if (loopCount == -1) 0 else loopCount,
                                onValueChange = { viewModel.loopCount.value = it.coerceIn(1, 999) },
                                range = 1..999,
                                step = 1,
                                unit = "",
                                modifier = Modifier.weight(1f),
                                enabled = !isUiLocked
                            )
                            FilterChip(
                                selected = loopCount == -1,
                                onClick = { viewModel.loopCount.value = if (loopCount == -1) 10 else -1 },
                                label = { Text("∞ Continuous") },
                                enabled = !isUiLocked
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        // Beta-smoke fix: backend already supports
                        // RovaSettings.resolution + the service-side
                        // QualitySelector mapping; the UI just never
                        // exposed a direct selector. Presets silently
                        // mutated it, which made it look like quality
                        // wasn't user-controllable. Single chip row
                        // bound to viewModel.resolution closes the gap.
                        Text(
                            "Video Quality",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            QualityPresets.PICKER_ORDER.forEach { option ->
                                FilterChip(
                                    selected = resolution == option,
                                    onClick = {
                                        if (!isUiLocked) viewModel.resolution.value = option
                                    },
                                    enabled = !isUiLocked,
                                    label = { Text(option) }
                                )
                            }
                        }
                        Spacer(Modifier.height(80.dp))
                    }
                }
            }
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

                // Top Bar + Battery Optimization Banner
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
                } // Column: top bar + battery banner

                // Bottom Overlay
                if (serviceState.isPeriodicActive) {
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
                }

                // Config summary (visible when idle)
                if (!serviceState.isPeriodicActive) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 90.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = Color.Black.copy(alpha = 0.56f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RecordDarkChip(formatDuration(duration))
                            RecordDarkChip("${if (loopCount == -1) "∞" else loopCount} loops")
                            RecordDarkChip(formatInterval(interval))
                            RecordDarkChip(resolution)
                        }
                    }
                }

                // FAB (Anchored above Peek)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                ) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (serviceState.isPeriodicActive) {
                                viewModel.stopRecording()
                            } else {
                                if (hasCapturePermissions) {
                                    // Phase 1.4 (ADR 0006 B11) — caller-side
                                    // FGS guard. RovaRecordingService.start now
                                    // returns StartResult; surface each Blocked
                                    // reason with the right message.
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
                                } else {
                                    permissionsState.launchMultiplePermissionRequest()
                                }
                            }
                        },
                        containerColor = if (serviceState.isPeriodicActive)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = if (serviceState.isPeriodicActive)
                            MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                        expanded = true,
                        icon = {
                            Icon(
                                if (serviceState.isPeriodicActive) Icons.Default.Stop else Icons.Default.Videocam,
                                null
                            )
                        },
                        text = {
                            Text(
                                text = if (serviceState.isPeriodicActive) "STOP RECORDING" else "START RECORDING",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        modifier = if (serviceState.isPeriodicActive)
                            Modifier
                                .fillMaxWidth(0.9f)
                                .height(72.dp)
                        else Modifier
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
                                        "Use the bottom sheet to set Duration, Interval, and Loop Count.",
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

// Formatting Helpers
fun formatDuration(seconds: Int): String {
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

fun formatInterval(minutes: Int): String {
    return if (minutes == 0) "No wait" else "${minutes}m"
}

@Composable
private fun RecordStatChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RecordDarkChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.White.copy(alpha = 0.14f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}
