package com.aritr.loom.ui.screens

import android.Manifest
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.loom.data.LoomPreset
import com.aritr.loom.service.LoomRecordingService
import com.aritr.loom.ui.components.*
import com.aritr.loom.ui.components.LoomAnimations.pulsingOpacity
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onMergeFinished: () -> Unit = {},
    viewModel: RecordViewModel = viewModel()
) {
    val context = LocalContext.current
    val localView = LocalView.current
    val scope = rememberCoroutineScope()

    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val duration by viewModel.duration.collectAsStateWithLifecycle()
    val interval by viewModel.interval.collectAsStateWithLifecycle()
    val loopCount by viewModel.loopCount.collectAsStateWithLifecycle()
    val flashMode by viewModel.flashMode.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val backgroundMode by viewModel.backgroundMode.collectAsStateWithLifecycle()
    val enableBeeps by viewModel.enableBeeps.collectAsStateWithLifecycle()
    val customPresets by viewModel.customPresets.collectAsStateWithLifecycle()

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

    // Auto-collapse sheet on recording start
    LaunchedEffect(serviceState.isRecording) {
        if (serviceState.isRecording) {
            scaffoldState.bottomSheetState.partialExpand()
        }
    }

    val isUiLocked = serviceState.isRecording || serviceState.isMerging
    val isCameraActive = serviceState.isCameraActive

    // Camera Disconnected Alert
    if (serviceState.isRecording && !isCameraActive) {
        AlertDialog(
            onDismissRequest = { /* Force user to stop */ },
            title = { Text("Camera Disconnected") },
            text = { Text("The camera connection was lost during recording.") },
            confirmButton = {
                Button(
                    onClick = { LoomRecordingService.stop(context) },
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
                Text(
                    "Settings & Defaults",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Recording",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    SwitchRow(
                        Icons.Default.Lock, "Background Mode", "Record with screen off",
                        backgroundMode
                    ) { viewModel.backgroundMode.value = it }
                    SwitchRow(
                        Icons.Default.Smartphone, "Keep Screen On", "Prevent screen timeout",
                        keepScreenOn
                    ) { viewModel.keepScreenOn.value = it }
                    SwitchRow(
                        Icons.Default.VolumeUp, "Sounds", "Start/Stop beeps",
                        enableBeeps
                    ) { viewModel.enableBeeps.value = it }
                }
            }
        }
    ) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = 130.dp,
            sheetSwipeEnabled = !isUiLocked,
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                TextButton(onClick = { presetNameInput = ""; showSavePresetDialog = true }) {
                                    Text("Save Current")
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val defaultPresets = listOf(
                                LoomPreset("Drill", 10, 1, 10, "FHD"),
                                LoomPreset("Vlog", 60, 0, -1, "HD"),
                            )
                            items(defaultPresets) { p ->
                                val isSelected = duration == p.duration && interval == p.interval
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
                                val isSelected = duration == p.duration && interval == p.interval
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
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Duration: ${formatDuration(duration)}", style = MaterialTheme.typography.bodySmall)
                                Text("Interval: ${formatInterval(interval)}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Loops: ${if (loopCount == -1) "∞" else loopCount.toString()}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    // EXPANDED Controls
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                                label = { Text("∞") },
                                enabled = !isUiLocked
                            )
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
                if (permissionsState.allPermissionsGranted) {
                    AndroidView(
                        factory = { _ -> previewView },
                        modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            viewModel.setSurfaceProvider(view.surfaceProvider)
                        }
                    )
                }

                // Loading Overlay
                if (!isCameraActive && !serviceState.isMerging) {
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

                // Top Bar
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .background(Color.Red, CircleShape)
                                    .alpha(pulsingOpacity())
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "REC",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    } else if (serviceState.isRecording && !isCameraActive) {
                        Text("INITIALIZING...", color = Color.Yellow, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Loom", color = Color.White, fontWeight = FontWeight.Bold)
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
                                LoomRecordingService.FLASH_MODE_ON -> Icons.Default.FlashOn
                                LoomRecordingService.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                                else -> Icons.Default.FlashOff
                            }
                            Icon(
                                icon, "Flash",
                                tint = if (flashMode == LoomRecordingService.FLASH_MODE_ON) Color.Yellow else iconTint
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

                // Bottom Overlay
                if (serviceState.isPeriodicActive) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 140.dp)
                            .background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f)))
                            )
                            .padding(16.dp)
                    ) {
                        Text(
                            if (serviceState.isRecording) "Recording..." else "Next in ${serviceState.nextRecordingCountdown}s",
                            color = Color.White
                        )
                        LinearProgressIndicator(
                            progress = {
                                if (serviceState.totalLoops > 0)
                                    serviceState.currentLoop.toFloat() / serviceState.totalLoops
                                else 0f
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
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
                                LoomRecordingService.stop(context)
                            } else {
                                if (permissionsState.allPermissionsGranted) {
                                    LoomRecordingService.start(
                                        context,
                                        viewModel.duration.value.toFloat(),
                                        viewModel.interval.value.toFloat(),
                                        viewModel.loopCount.value,
                                        viewModel.resolution.value
                                    )
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
                                        "Loom will automatically start and stop recording loops based on your settings.",
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
