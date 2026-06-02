package com.aritr.rova.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aritr.rova.BuildConfig
import com.aritr.rova.R
import com.aritr.rova.RovaApp
import com.aritr.rova.data.QualityPresets
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Notifications
import com.aritr.rova.ui.components.focusHighlight
import com.aritr.rova.ui.theme.ThemeMode
import com.aritr.rova.ui.theme.RovaTokens
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.rovaQuietText
import com.aritr.rova.ui.warnings.SettingsPermissionsSection
import com.aritr.rova.ui.warnings.SettingsPermissionsSheetHost
import com.aritr.rova.ui.warnings.WarningCenterViewModel
import com.aritr.rova.ui.warnings.WarningId
import com.aritr.rova.ui.warnings.WarningScreen
import com.aritr.rova.ui.warnings.buildWarningCenterViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Phase 2.1B — App Settings re-skin matching mockups/new_uiux/06-app-settings.html.
 *
 * The screen is intentionally quiet: flat rows on the page background,
 * eyebrow-styled section labels, hairline dividers between rows. The
 * mockup's `backdrop-filter: blur` is replaced by `MaterialTheme.colorScheme`
 * surface roles + low alpha — see docs/UI_DESIGN_TOKENS.md §2.5 for why
 * real blur is NO-GO on minSdk 24.
 *
 * Behavior preserved (no recording-pipeline change):
 *  - keepScreenOn / enableBeeps / vibrateAlerts toggles unchanged
 *  - autoDeleteEnabled + autoDeleteKeepLatest chips ([5,10,25,50]) unchanged
 *  - Battery optimization CTA still routes through BatteryOptimizationHelper
 *  - Privacy + version row unchanged in destination
 *
 * exportFolderName surfaces here as a UI/persistence-only row. No
 * ExportPipeline / MediaStore consumer reads it yet; Phase 5 wires that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsViewModel: SettingsViewModel, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    // Phase 4.2 — WarningCenter VM for Settings "Permissions & status" routing.
    val app = remember(context) { context.applicationContext as RovaApp }
    val warningVm: WarningCenterViewModel = remember(app) { buildWarningCenterViewModel(app) }
    val settingsWarnings by warningVm.activeWarningsFor(WarningScreen.Settings)
        .collectAsStateWithLifecycle()
    var sheetWarningId by remember { mutableStateOf<WarningId?>(null) }
    val scope = rememberCoroutineScope()
    val enableBeeps by settingsViewModel.enableBeeps.collectAsStateWithLifecycle()
    val vibrateAlerts by settingsViewModel.vibrateAlerts.collectAsStateWithLifecycle()
    val keepScreenOn by settingsViewModel.keepScreenOn.collectAsStateWithLifecycle()
    val cameraGuidesEnabled by settingsViewModel.cameraGuidesEnabled.collectAsStateWithLifecycle()
    val autoDeleteEnabled by settingsViewModel.autoDeleteEnabled.collectAsStateWithLifecycle()
    val autoDeleteKeepLatest by settingsViewModel.autoDeleteKeepLatest.collectAsStateWithLifecycle()
    val exportFolderName by settingsViewModel.exportFolderName.collectAsStateWithLifecycle()
    val resolution by settingsViewModel.resolution.collectAsStateWithLifecycle()
    val durationSeconds by settingsViewModel.durationSeconds.collectAsStateWithLifecycle()
    val intervalMinutes by settingsViewModel.intervalMinutes.collectAsStateWithLifecycle()
    val loopCount by settingsViewModel.loopCount.collectAsStateWithLifecycle()
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()

    // Re-read battery-exempt state on resume so returning from the system
    // settings screen flips the badge without forcing a manual refresh.
    // Also re-seeds the recording-default flows on resume so changes made from
    // the record sheet while this screen was backgrounded are reflected.
    val lifecycleOwner = LocalLifecycleOwner.current
    var batteryExempt by remember {
        mutableStateOf(BatteryOptimizationHelper.isIgnoring(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = BatteryOptimizationHelper.isIgnoring(context)
                settingsViewModel.reloadRecordingDefaults()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showBatteryDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var openSheet by remember { mutableStateOf<RecordingDefaultSheet?>(null) }
    var openThemeSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back_cd)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
        ) {
            SettingsPermissionsSection(
                warningIds = settingsWarnings,
                onOpenSheet = { sheetWarningId = it },
            )
            SettingsSection(label = stringResource(R.string.settings_section_appearance)) {
                SettingsRow(
                    icon = Icons.Default.DarkMode,
                    label = stringResource(R.string.settings_theme_label),
                    supporting = stringResource(R.string.settings_theme_supporting),
                    value = themeModeLabel(themeMode),
                    onClick = { openThemeSheet = true },
                    trailing = { ChevronTrailing() },
                )
            }
            SettingsSection(label = stringResource(R.string.settings_section_recording_defaults)) {
                SettingsRow(
                    icon = Icons.Default.HighQuality,
                    label = stringResource(R.string.settings_default_resolution_label),
                    supporting = stringResource(R.string.settings_default_resolution_supporting),
                    value = QualityPresets.canonicalizeOrDefault(resolution),
                    onClick = { openSheet = RecordingDefaultSheet.RESOLUTION },
                    trailing = { ChevronTrailing() },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Timer,
                    label = stringResource(R.string.settings_clip_duration_label),
                    supporting = stringResource(R.string.settings_clip_duration_supporting),
                    value = recordClipValue(durationSeconds),
                    onClick = { openSheet = RecordingDefaultSheet.DURATION },
                    trailing = { ChevronTrailing() },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.HourglassEmpty,
                    label = stringResource(R.string.settings_interval_label),
                    supporting = stringResource(R.string.settings_interval_supporting),
                    value = recordWaitValue(intervalMinutes),
                    onClick = { openSheet = RecordingDefaultSheet.INTERVAL },
                    trailing = { ChevronTrailing() },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Repeat,
                    label = stringResource(R.string.settings_loops_label),
                    supporting = stringResource(R.string.settings_loops_supporting),
                    value = recordRepeatsValue(loopCount),
                    onClick = { openSheet = RecordingDefaultSheet.LOOPS },
                    trailing = { ChevronTrailing() },
                )
            }

            SettingsSection(label = stringResource(R.string.settings_section_recording_behavior)) {
                SettingsRow(
                    icon = Icons.Default.Smartphone,
                    label = stringResource(R.string.settings_keep_screen_on_label),
                    supporting = stringResource(R.string.settings_keep_screen_on_supporting),
                    checked = keepScreenOn,
                    onCheckedChange = { settingsViewModel.keepScreenOn.value = it }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.GridOn,
                    label = stringResource(R.string.settings_camera_guides_label),
                    supporting = stringResource(R.string.settings_camera_guides_supporting),
                    checked = cameraGuidesEnabled,
                    onCheckedChange = { settingsViewModel.cameraGuidesEnabled.value = it }
                )
            }

            SettingsSection(label = stringResource(R.string.settings_section_alerts)) {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    label = stringResource(R.string.settings_sound_cues_label),
                    supporting = stringResource(R.string.settings_sound_cues_supporting),
                    checked = enableBeeps,
                    onCheckedChange = { settingsViewModel.enableBeeps.value = it }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Vibration,
                    label = stringResource(R.string.settings_vibrate_alerts_label),
                    supporting = stringResource(R.string.settings_vibrate_alerts_supporting),
                    checked = vibrateAlerts,
                    onCheckedChange = { settingsViewModel.vibrateAlerts.value = it }
                )
            }

            SettingsSection(label = stringResource(R.string.settings_section_notifications)) {
                val notifUnavailable = stringResource(R.string.settings_system_notifications_unavailable)
                SettingsRow(
                    icon = Icons.Default.Notifications,
                    label = stringResource(R.string.settings_system_notifications_label),
                    supporting = stringResource(R.string.settings_system_notifications_supporting),
                    onClick = {
                        try {
                            context.startActivity(buildNotificationSettingsIntent(context))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                notifUnavailable,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    trailing = { ChevronTrailing() },
                )
            }

            SettingsSection(label = stringResource(R.string.settings_section_storage)) {
                SettingsRow(
                    icon = Icons.Default.DeleteSweep,
                    label = stringResource(R.string.settings_auto_delete_label),
                    supporting = if (autoDeleteEnabled) {
                        stringResource(
                            R.string.settings_auto_delete_supporting_on,
                            autoDeleteKeepLatest
                        )
                    } else {
                        stringResource(R.string.settings_auto_delete_supporting_off)
                    },
                    checked = autoDeleteEnabled,
                    onCheckedChange = { settingsViewModel.autoDeleteEnabled.value = it }
                )
                if (autoDeleteEnabled) {
                    KeepLatestChips(
                        selected = autoDeleteKeepLatest,
                        options = KEEP_LATEST_OPTIONS,
                        onSelect = { settingsViewModel.autoDeleteKeepLatest.value = it }
                    )
                }
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Folder,
                    label = stringResource(R.string.settings_export_folder_label),
                    supporting = stringResource(R.string.settings_export_folder_supporting),
                    value = exportFolderName.ifBlank {
                        stringResource(R.string.settings_export_folder_default)
                    },
                    onClick = { showFolderDialog = true },
                    trailing = { ChevronTrailing() }
                )
                SettingsDivider()
                val cacheClearedFmt = stringResource(R.string.settings_clear_cache_cleared)
                val cacheEmptyMsg = stringResource(R.string.settings_clear_cache_empty)
                SettingsRow(
                    icon = Icons.Default.CleaningServices,
                    label = stringResource(R.string.settings_clear_cache_label),
                    supporting = stringResource(R.string.settings_clear_cache_supporting),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val cacheDir = context.cacheDir
                            // Phase 2.1B review-fix — capture length BEFORE
                            // delete; File.length() on a deleted inode returns
                            // 0 on most filesystems, which silently undercounted
                            // the toast.
                            val deleted = cacheDir.walkBottomUp().sumOf { file ->
                                if (file == cacheDir) {
                                    0L
                                } else {
                                    val size = file.length()
                                    if (file.delete()) size else 0L
                                }
                            }
                            val mb = deleted / 1024.0 / 1024.0
                            launch(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    if (mb > 0.1) cacheClearedFmt.format(mb) else cacheEmptyMsg,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    trailing = { ChevronTrailing() }
                )
            }

            SettingsSection(label = stringResource(R.string.settings_section_reliability)) {
                SettingsRow(
                    icon = Icons.Default.BatteryAlert,
                    label = stringResource(R.string.settings_battery_optimization_label),
                    supporting = stringResource(R.string.settings_battery_optimization_supporting),
                    onClick = { showBatteryDialog = true },
                    trailing = { BatteryStatusBadge(exempt = batteryExempt) }
                )
            }

            SettingsSection(label = stringResource(R.string.settings_section_about)) {
                SettingsRow(
                    icon = Icons.Default.Info,
                    label = stringResource(R.string.settings_version_label),
                    value = BuildConfig.VERSION_NAME,
                    onClick = null,
                    trailing = null
                )
                SettingsDivider()
                val noBrowserMsg = stringResource(R.string.settings_privacy_no_browser)
                SettingsRow(
                    icon = Icons.Default.PrivacyTip,
                    label = stringResource(R.string.settings_privacy_label),
                    supporting = stringResource(R.string.settings_privacy_supporting),
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://aritr-codes.github.io/rova-privacy/")
                                )
                            )
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                noBrowserMsg,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    trailing = { ChevronTrailing() }
                )
            }
        }
    }

    sheetWarningId?.let { id ->
        SettingsPermissionsSheetHost(
            id = id,
            vm = warningVm,
            onDismiss = { sheetWarningId = null },
        )
    }

    if (showBatteryDialog) {
        BatteryOptimizationDialog(
            exempt = batteryExempt,
            onConfirm = {
                showBatteryDialog = false
                val intent = BatteryOptimizationHelper.buildRequestIntent(context.packageName)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_battery_settings_unavailable),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { showBatteryDialog = false }
        )
    }

    if (showFolderDialog) {
        ExportFolderDialog(
            initial = exportFolderName,
            onSave = { newValue ->
                settingsViewModel.exportFolderName.value = newValue
                showFolderDialog = false
            },
            onDismiss = { showFolderDialog = false }
        )
    }

    when (openSheet) {
        RecordingDefaultSheet.RESOLUTION -> SettingsOptionSheet(
            title = stringResource(R.string.settings_default_resolution_label),
            options = QualityPresets.PICKER_ORDER,
            selected = QualityPresets.canonicalizeOrDefault(resolution),
            optionLabel = { it },
            onPick = { settingsViewModel.resolution.value = it },
            onDismiss = { openSheet = null },
        )
        RecordingDefaultSheet.DURATION -> SettingsStepperSheet(
            title = stringResource(R.string.settings_clip_duration_label),
            valueLabel = recordClipValue(durationSeconds),
            atMin = RecordSettingBounds.clipAtMin(durationSeconds),
            atMax = RecordSettingBounds.clipAtMax(durationSeconds),
            onStep = { dir ->
                settingsViewModel.durationSeconds.value =
                    RecordSettingBounds.stepClip(durationSeconds, dir)
            },
            onDismiss = { openSheet = null },
        )
        RecordingDefaultSheet.INTERVAL -> SettingsStepperSheet(
            title = stringResource(R.string.settings_interval_label),
            valueLabel = recordWaitValue(intervalMinutes),
            atMin = RecordSettingBounds.waitAtMin(intervalMinutes),
            atMax = RecordSettingBounds.waitAtMax(intervalMinutes),
            onStep = { dir ->
                settingsViewModel.intervalMinutes.value =
                    RecordSettingBounds.stepWait(intervalMinutes, dir)
            },
            onDismiss = { openSheet = null },
        )
        RecordingDefaultSheet.LOOPS -> SettingsStepperSheet(
            title = stringResource(R.string.settings_loops_label),
            valueLabel = recordRepeatsStepperValue(loopCount),
            atMin = RecordSettingBounds.repeatsAtMin(loopCount),
            atMax = RecordSettingBounds.repeatsAtMax(loopCount),
            onStep = { dir ->
                settingsViewModel.loopCount.value =
                    RecordSettingBounds.stepRepeats(loopCount, dir)
            },
            onDismiss = { openSheet = null },
        )
        null -> Unit
    }

    if (openThemeSheet) {
        val systemLabel = stringResource(R.string.settings_theme_mode_system)
        val darkLabel = stringResource(R.string.settings_theme_mode_dark)
        val lightLabel = stringResource(R.string.settings_theme_mode_light)
        SettingsOptionSheet(
            title = stringResource(R.string.settings_theme_label),
            options = ThemeMode.entries,
            selected = themeMode,
            optionLabel = { mode ->
                when (mode) {
                    ThemeMode.SYSTEM -> systemLabel
                    ThemeMode.DARK -> darkLabel
                    ThemeMode.LIGHT -> lightLabel
                }
            },
            onPick = { settingsViewModel.themeMode.value = it },
            onDismiss = { openThemeSheet = false },
        )
    }
}

private val KEEP_LATEST_OPTIONS = listOf(5, 10, 25, 50)

/**
 * Phase 2.1B review-fix — defensive sanitizer for the export folder name
 * persisted via [RovaSettings.exportFolderName].
 *
 * Phase 5 will be the first export-pipeline consumer of this value, but
 * Phase 2.1B must not create bad persisted state in the meantime. Contract:
 *  - trim leading/trailing whitespace
 *  - cap at 32 characters (matches mockup's `maxlength="32"`)
 *  - single folder segment — strip path separators (`/`, `\`)
 *  - strip platform-invalid filename chars (`: * ? " < > |`)
 *  - strip ASCII control chars (`< 0x20`, `0x7F`)
 *  - reserved `.` / `..` resolve to the empty string
 *  - empty result means "use the existing default folder" — exactly the
 *    semantic [RovaSettings.exportFolderName] documents for `""`
 *
 * `internal` so the unit test in `SettingsExportFolderTest` can call it
 * directly without Compose / Android infra.
 */
internal fun sanitizeExportFolderName(input: String): String {
    val invalidChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    val filtered = input.filter { ch ->
        ch.code >= 0x20 && ch.code != 0x7F && ch !in invalidChars
    }
    val trimmed = filtered.trim().take(32).trim()
    return when (trimmed) {
        "", ".", ".." -> ""
        else -> trimmed
    }
}

@Composable
private fun SettingsSection(
    label: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = RovaTokens.eyebrow,
            color = rovaQuietText(dimAlpha = 0.45f),
            // WCAG 2.2 AA SC 1.3.1 (ADR-0020, SET-01): mark the section label
            // as a heading so TalkBack's heading navigation can jump between
            // settings groups (layout-only grouping is invisible to it).
            modifier = Modifier
                .padding(
                    start = RovaTokens.screenEdgeMargin,
                    end = RovaTokens.screenEdgeMargin,
                    top = 20.dp,
                    bottom = 8.dp
                )
                .semantics { heading() }
        )
        content()
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    supporting: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    // WCAG 2.2 AA SC 4.1.2 (ADR-0020, SET-02): a row carrying a Switch is one
    // toggleable node (role=Switch) so its label names the control and the
    // state is announced once. Non-toggle rows keep the plain clickable path.
    // Locals captured so the null-checks below smart-cast (a separate
    // `isToggle` boolean would not let the compiler narrow `checked`).
    val toggleChecked = checked
    val toggleChange = onCheckedChange
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { base ->
                // SC 2.4.7 (SET-03): visible focus ring on the interactive row.
                when {
                    toggleChecked != null && toggleChange != null -> base
                        .focusHighlight(RectangleShape)
                        .toggleable(
                            value = toggleChecked,
                            role = Role.Switch,
                            onValueChange = toggleChange,
                        )
                    onClick != null -> base
                        .focusHighlight(RectangleShape)
                        .clickable(onClick = onClick)
                    else -> base
                }
            }
            .heightIn(min = RovaTokens.minHitTarget)
            .padding(
                horizontal = RovaTokens.screenEdgeMargin,
                vertical = RovaTokens.settingsRowVerticalPadding
            ),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            modifier = Modifier.size(RovaTokens.camControlSize)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            )
            if (!supporting.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = rovaQuietText(dimAlpha = 0.55f)
                )
            }
            if (!value.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                )
            }
        }
        if (toggleChecked != null) {
            // Presentational — the toggleable Row owns role/state/name.
            Switch(
                checked = toggleChecked,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics { },
            )
        } else {
            trailing?.invoke()
        }
    }
}

@Composable
private fun ChevronTrailing() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.size(20.dp)
    )
}

@Composable
private fun BatteryStatusBadge(exempt: Boolean) {
    if (exempt) {
        // Quiet "Exempt" label — surface-variant tone keeps focus on the
        // soft warning when the user is NOT exempt. No green token exists
        // in RovaWarnings (4 severities locked per UI_DESIGN_TOKENS §2.10).
        Text(
            text = stringResource(R.string.settings_battery_exempt),
            style = RovaTokens.statusPillLabel,
            color = rovaQuietText(dimAlpha = 0.6f),
            modifier = Modifier.padding(end = 4.dp)
        )
    } else {
        Surface(
            shape = RoundedCornerShape(5.dp),
            color = RovaWarnings.soft.copy(alpha = 0.12f)
        ) {
            Text(
                text = stringResource(R.string.settings_battery_not_exempt),
                style = RovaTokens.statusPillLabel,
                color = RovaWarnings.soft.copy(alpha = 0.9f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun KeepLatestChips(
    selected: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = RovaTokens.screenEdgeMargin + RovaTokens.camControlSize + 11.dp,
                end = RovaTokens.screenEdgeMargin,
                bottom = 10.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = {
                    Text(
                        text = "$option",
                        style = RovaTokens.cellValue
                    )
                }
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(
            start = RovaTokens.screenEdgeMargin + RovaTokens.camControlSize + 11.dp,
            end = RovaTokens.screenEdgeMargin
        ),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(
            alpha = RovaTokens.settingsRowDividerAlpha
        )
    )
}

@Composable
private fun BatteryOptimizationDialog(
    exempt: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(text = stringResource(R.string.settings_battery_dialog_title)) },
        text = {
            Text(
                text = if (exempt) {
                    stringResource(R.string.settings_battery_dialog_body_exempt)
                } else {
                    stringResource(R.string.settings_battery_dialog_body_not_exempt)
                },
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.settings_battery_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_battery_dialog_dismiss))
            }
        }
    )
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_mode_system)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_mode_dark)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_mode_light)
}

/** Which recording-default picker sheet is open in [SettingsScreen]. */
private enum class RecordingDefaultSheet { RESOLUTION, DURATION, INTERVAL, LOOPS }

@Composable
private fun ExportFolderDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    // Sanitize live so the preview reflects what would actually persist
    // — typing "foo/bar" shows "foobar". Save persists the same value.
    val sanitized = sanitizeExportFolderName(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_export_folder_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { input -> value = input.take(32) },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.settings_export_folder_field_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (sanitized.isEmpty()) {
                        stringResource(R.string.settings_export_folder_preview_default)
                    } else {
                        stringResource(R.string.settings_export_folder_preview_named, sanitized)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = rovaQuietText(dimAlpha = 0.6f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(sanitized) }) {
                Text(text = stringResource(R.string.settings_export_folder_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_export_folder_cancel))
            }
        }
    )
}
