package com.aritr.rova.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Schedule
import androidx.core.content.ContextCompat
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
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
import androidx.compose.material.icons.filled.Movie
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
import com.aritr.rova.service.export.SafAndroidOps
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import com.aritr.rova.ui.components.focusHighlight
import com.aritr.rova.ui.locale.AppLocale
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface
import com.aritr.rova.ui.theme.ThemeSelection
import com.aritr.rova.ui.theme.RovaTokens
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.rovaQuietText
import com.aritr.rova.ui.vault.VaultAuthGate
import com.aritr.rova.ui.vault.toggleRequiresAuth
import com.aritr.rova.ui.warnings.SettingsPermissionsSection
import androidx.fragment.app.FragmentActivity
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
    val libraryCardPreview by settingsViewModel.libraryCardPreview.collectAsStateWithLifecycle()
    val autoDeleteEnabled by settingsViewModel.autoDeleteEnabled.collectAsStateWithLifecycle()
    val autoDeleteKeepLatest by settingsViewModel.autoDeleteKeepLatest.collectAsStateWithLifecycle()
    val resolution by settingsViewModel.resolution.collectAsStateWithLifecycle()
    val durationSeconds by settingsViewModel.durationSeconds.collectAsStateWithLifecycle()
    val intervalMinutes by settingsViewModel.intervalMinutes.collectAsStateWithLifecycle()
    val loopCount by settingsViewModel.loopCount.collectAsStateWithLifecycle()
    val themeSelection by settingsViewModel.themeSelection.collectAsStateWithLifecycle()
    val hideInVault by settingsViewModel.hideInVault.collectAsStateWithLifecycle()
    // ADR-0027 — daily recording window state.
    val scheduleEnabled by settingsViewModel.scheduleEnabled.collectAsStateWithLifecycle()
    val scheduleStartMinute by settingsViewModel.scheduleStartMinute.collectAsStateWithLifecycle()
    val scheduleStopMinute by settingsViewModel.scheduleStopMinute.collectAsStateWithLifecycle()
    val scheduleWeekdayMask by settingsViewModel.scheduleWeekdayMask.collectAsStateWithLifecycle()
    // POST_NOTIFICATIONS request launcher (API 33+); whatever the result, the
    // window still arms — the inline note tells the user if notifications are off.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* reflected by NotificationManagerCompat at fire time */ }

    // Re-read battery-exempt state on resume so returning from the system
    // settings screen flips the badge without forcing a manual refresh.
    // Also re-seeds the recording-default flows on resume so changes made from
    // the record sheet while this screen was backgrounded are reflected.
    val lifecycleOwner = LocalLifecycleOwner.current
    // Derive the badge/dialog state from the SHARED batteryOptimizationSignal —
    // not a second local read — so it can never contradict the Settings warning
    // strip, which reads the same signal. The exemption grant fires no system
    // broadcast, so the value is only fresh after an explicit refresh(); call it
    // on resume (e.g. returning from the system battery dialog) so BOTH the badge
    // and the warning strip re-read. Previously only the local copy refreshed,
    // leaving the strip stale while the badge flipped (SaveFolderSignal-class bug).
    val batteryExempt by app.batteryOptimizationSignal.isExempt.collectAsStateWithLifecycle()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                app.batteryOptimizationSignal.refresh()
                settingsViewModel.reloadRecordingDefaults()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showBatteryDialog by remember { mutableStateOf(false) }
    var openSheet by remember { mutableStateOf<RecordingDefaultSheet?>(null) }
    var openThemeSheet by remember { mutableStateOf(false) }
    var openLanguageSheet by remember { mutableStateOf(false) }
    val languageOptions = AppLocale.languagePickerOptions()
    val showLanguageRow = AppLocale.shouldShowLanguagePicker(languageOptions)
    val localeTag by settingsViewModel.localeTag.collectAsStateWithLifecycle()
    val saveLabel by settingsViewModel.saveLocationLabel.collectAsStateWithLifecycle()

    // B4 SAF track — folder picker. Probe first (uses temporary callback grant),
    // then take persistable permission, then persist. This order avoids:
    //   (a) dangling persisted grants when the probe fails, and
    //   (b) storing a URI whose persistent grant was silently not taken.
    val unusableMsg = stringResource(R.string.settings_save_location_unusable)
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val ok = SafAndroidOps.writeProbe(context, uri.toString())
                if (ok) {
                    // Probe passed with temporary callback grant; now make it
                    // permanent. A SecurityException here means the OS refused
                    // to persist — treat as failure rather than silently storing
                    // a URI that will break after the callback expires.
                    val granted = try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        true
                    } catch (_: SecurityException) {
                        false
                    }
                    if (granted) {
                        // Prefer DocumentFile.name (asks the provider for the
                        // display name); fall back to URI path segment parsing.
                        val label = DocumentFile.fromTreeUri(context, uri)?.name
                            ?: uri.lastPathSegment
                                ?.substringAfterLast(':')
                                ?.substringAfterLast('/')
                            ?: uri.toString()
                        launch(Dispatchers.Main) {
                            settingsViewModel.setSaveLocationFolder(uri.toString(), label)
                        }
                    } else {
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, unusableMsg, Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, unusableMsg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

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
            // ADR-0028 PR1 proof surface — the Appearance section renders through
            // GlassSurface(role = Card), so the active palette's glass tint is
            // visible here (Tide vs Aurora etc. differ). This is the only surface
            // migrated to GlassSurface in PR1.
            GlassSurface(
                role = GlassRole.Card,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SettingsSection(label = stringResource(R.string.settings_section_appearance)) {
                    SettingsRow(
                        icon = Icons.Default.DarkMode,
                        label = stringResource(R.string.settings_theme_label),
                        supporting = stringResource(R.string.settings_theme_supporting),
                        value = themeSelectionLabel(themeSelection),
                        onClick = { openThemeSheet = true },
                        trailing = { ChevronTrailing() },
                    )
                    if (showLanguageRow) {
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Default.Language,
                            label = stringResource(R.string.settings_language_label),
                            supporting = stringResource(R.string.settings_language_supporting),
                            value = languageOptionLabel(localeTag),
                            onClick = { openLanguageSheet = true },
                            trailing = { ChevronTrailing() },
                        )
                    }
                }
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

            SettingsSection(label = stringResource(R.string.settings_section_library)) {
                SettingsRow(
                    icon = Icons.Default.Movie,
                    label = stringResource(R.string.settings_library_card_preview_title),
                    supporting = stringResource(R.string.settings_library_card_preview_summary),
                    checked = libraryCardPreview,
                    onCheckedChange = { settingsViewModel.libraryCardPreview.value = it }
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

            // B5 / ADR-0025 — vault privacy toggle. ON->OFF is auth-gated
            // (toggleRequiresAuth); turning ON is free. The Switch's checked
            // state is driven by the persisted `hideInVault` flow, so a
            // cancelled OFF-attempt snaps back to ON (the flow never changed).
            SettingsSection(label = stringResource(R.string.settings_section_privacy)) {
                SettingsRow(
                    icon = Icons.Default.Lock,
                    label = stringResource(R.string.settings_hide_in_vault_title),
                    supporting = stringResource(R.string.settings_hide_in_vault_summary),
                    checked = hideInVault,
                    onCheckedChange = { desired ->
                        val current = settingsViewModel.hideInVault.value
                        if (toggleRequiresAuth(current, desired)) {
                            // ON->OFF: require device-credential auth before
                            // un-hiding. The Switch does not flip until the
                            // flow is set in onSucceeded / onUnavailable.
                            val activity = context as? FragmentActivity
                            if (activity != null) {
                                VaultAuthGate.authenticate(
                                    activity = activity,
                                    onSucceeded = { settingsViewModel.setHideInVault(false) },
                                    onCancelled = { /* leave ON — flow unchanged, Switch snaps back */ },
                                    // No screen lock enrolled — the in-app lock
                                    // can't protect anything, so allow turning off.
                                    onUnavailable = { settingsViewModel.setHideInVault(false) },
                                    // TODO(B5): wire ActivityResult to flip lock on keyguard return
                                    launchKeyguard = { intent -> activity.startActivity(intent) },
                                )
                            }
                        } else {
                            // Turning ON (or no-op) is free.
                            settingsViewModel.setHideInVault(desired)
                        }
                    }
                )
            }

            // ADR-0027 — daily recording window. Master switch + (when on)
            // start/stop time rows, weekday chips, and a one-tap explainer.
            SettingsSection(label = stringResource(R.string.schedule_section_title)) {
                SettingsRow(
                    icon = Icons.Default.Schedule,
                    label = stringResource(R.string.schedule_enable_label),
                    supporting = stringResource(R.string.schedule_enable_desc),
                    checked = scheduleEnabled,
                    onCheckedChange = { desired ->
                        if (desired && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        settingsViewModel.setScheduleEnabled(desired)
                    }
                )
                if (scheduleEnabled) {
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Schedule,
                        label = stringResource(R.string.schedule_start_time_label),
                        value = formatMinuteOfDay(scheduleStartMinute),
                        onClick = {
                            showTimePicker(context, scheduleStartMinute) {
                                settingsViewModel.setScheduleStartMinute(it)
                            }
                        },
                        trailing = { ChevronTrailing() },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Schedule,
                        label = stringResource(R.string.schedule_stop_time_label),
                        value = formatMinuteOfDay(scheduleStopMinute),
                        onClick = {
                            showTimePicker(context, scheduleStopMinute) {
                                settingsViewModel.setScheduleStopMinute(it)
                            }
                        },
                        trailing = { ChevronTrailing() },
                    )
                    SettingsDivider()
                    ScheduleWeekdayChips(
                        mask = scheduleWeekdayMask,
                        onChange = { settingsViewModel.setScheduleWeekdayMask(it) },
                    )
                    Text(
                        text = stringResource(R.string.schedule_one_tap_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = rovaQuietText(dimAlpha = 0.55f),
                        modifier = Modifier.padding(
                            start = RovaTokens.screenEdgeMargin,
                            end = RovaTokens.screenEdgeMargin,
                            top = 8.dp,
                            bottom = 4.dp,
                        ),
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val am = context.getSystemService(android.app.AlarmManager::class.java)
                        if (am != null && !am.canScheduleExactAlarms()) {
                            SettingsRow(
                                icon = Icons.Default.Schedule,
                                label = stringResource(R.string.schedule_exact_alarm_required),
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        )
                                    } catch (_: ActivityNotFoundException) {
                                        // No settings activity on this OEM build; the
                                        // scheduler degrades to inexact alarms.
                                    }
                                },
                                trailing = { ChevronTrailing() },
                            )
                        }
                    }
                }
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
                // B4 SAF track — custom save-location row (tree URI picker).
                SettingsRow(
                    icon = Icons.Default.Folder,
                    label = stringResource(R.string.settings_save_location_label),
                    value = saveLabel ?: stringResource(R.string.settings_save_location_internal),
                    onClick = { pickFolder.launch(null) },
                    trailing = { ChevronTrailing() },
                )
                if (saveLabel != null) {
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Folder,
                        label = stringResource(R.string.settings_save_location_use_internal),
                        onClick = { settingsViewModel.clearSaveLocationFolder() },
                        trailing = { ChevronTrailing() },
                    )
                }
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
            valueLabel = recordRepeatsCompactValue(loopCount),
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
        // ADR-0028 — Wave-1 ThemeSelection picker (Follow-System + signature six).
        // Wave-2 palettes exist in the enum but are not surfaced until a later PR.
        // Labels are resolved here (composable context) because SettingsOptionSheet's
        // optionLabel is a plain lambda, mirroring the prior theme-sheet pattern.
        val followLabel = stringResource(R.string.settings_theme_selection_follow_system)
        val auroraLabel = stringResource(R.string.settings_theme_selection_aurora)
        val tideLabel = stringResource(R.string.settings_theme_selection_tide)
        val jadeLabel = stringResource(R.string.settings_theme_selection_jade)
        val duskLabel = stringResource(R.string.settings_theme_selection_dusk)
        val eclipseLabel = stringResource(R.string.settings_theme_selection_eclipse)
        val daylightLabel = stringResource(R.string.settings_theme_selection_daylight)
        SettingsOptionSheet(
            title = stringResource(R.string.settings_theme_label),
            options = ThemeSelection.wave1Picker,
            selected = themeSelection,
            optionLabel = { sel ->
                when (sel) {
                    ThemeSelection.FOLLOW_SYSTEM -> followLabel
                    ThemeSelection.AURORA -> auroraLabel
                    ThemeSelection.TIDE -> tideLabel
                    ThemeSelection.JADE -> jadeLabel
                    ThemeSelection.DUSK -> duskLabel
                    ThemeSelection.ECLIPSE -> eclipseLabel
                    ThemeSelection.DAYLIGHT -> daylightLabel
                    else -> sel.name
                }
            },
            onPick = { settingsViewModel.themeSelection.value = it },
            onDismiss = { openThemeSheet = false },
        )
    }

    if (openLanguageSheet) {
        val systemLabel = stringResource(R.string.settings_language_system)
        SettingsOptionSheet(
            title = stringResource(R.string.settings_language_label),
            options = languageOptions,
            selected = languageOptions.firstOrNull { it.tag == localeTag } ?: languageOptions.first(),
            optionLabel = { option ->
                if (option.tag == null) systemLabel else endonymOf(option.tag)
            },
            onPick = { settingsViewModel.setLocale(context, it.tag) },
            onDismiss = { openLanguageSheet = false },
        )
    }
}

private val KEEP_LATEST_OPTIONS = listOf(5, 10, 25, 50)

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
                        .clickable(onClick = onClick, role = Role.Button)
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

/**
 * ADR-0027 — weekday selector for the daily window. Bit i = day eligible, where
 * bit 0=Mon..bit 6=Sun (matches ScheduleArmer). Empty mask (0) = every day; the
 * "Every day" chip clears the mask, day chips toggle their bit.
 */
@Composable
private fun ScheduleWeekdayChips(mask: Int, onChange: (Int) -> Unit) {
    // Mon..Sun short labels — locale-aware via Calendar would be ideal, but the
    // chip strip stays compact with fixed two-letter forms; full a11y label is
    // the day name below. Kept ASCII to avoid a strings explosion (7 keys/lang).
    val dayLabels = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.schedule_weekdays_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            modifier = Modifier.padding(
                start = RovaTokens.screenEdgeMargin,
                end = RovaTokens.screenEdgeMargin,
                top = 4.dp,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = RovaTokens.screenEdgeMargin, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = mask == 0,
                onClick = { onChange(0) },
                label = { Text(stringResource(R.string.schedule_every_day)) },
            )
            dayLabels.forEachIndexed { bit, label ->
                FilterChip(
                    selected = mask != 0 && (mask and (1 shl bit)) != 0,
                    onClick = {
                        // Toggle this bit; clearing the last bit falls back to
                        // "every day" (mask 0) so the window never has zero days.
                        val toggled = mask xor (1 shl bit)
                        onChange(toggled)
                    },
                    label = { Text(label) },
                )
            }
        }
    }
}

/** ADR-0027 — minutes-past-midnight → "HH:mm" (24h, locale-stable). */
private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val h = (minuteOfDay / 60).coerceIn(0, 23)
    val m = (minuteOfDay % 60).coerceIn(0, 59)
    return "%02d:%02d".format(h, m)
}

/** ADR-0027 — framework time picker; reports the chosen minute-of-day. */
private fun showTimePicker(context: Context, minuteOfDay: Int, onPicked: (Int) -> Unit) {
    android.app.TimePickerDialog(
        context,
        { _, hourOfDay, minute -> onPicked(hourOfDay * 60 + minute) },
        minuteOfDay / 60,
        minuteOfDay % 60,
        true,
    ).show()
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
private fun themeSelectionLabel(sel: ThemeSelection): String = when (sel) {
    ThemeSelection.FOLLOW_SYSTEM -> stringResource(R.string.settings_theme_selection_follow_system)
    ThemeSelection.AURORA -> stringResource(R.string.settings_theme_selection_aurora)
    ThemeSelection.TIDE -> stringResource(R.string.settings_theme_selection_tide)
    ThemeSelection.JADE -> stringResource(R.string.settings_theme_selection_jade)
    ThemeSelection.DUSK -> stringResource(R.string.settings_theme_selection_dusk)
    ThemeSelection.ECLIPSE -> stringResource(R.string.settings_theme_selection_eclipse)
    ThemeSelection.DAYLIGHT -> stringResource(R.string.settings_theme_selection_daylight)
    // Wave-2 not yet surfaced in the picker; fall back to the enum name (never shown in PR1).
    else -> sel.name
}

@Composable
private fun languageOptionLabel(tag: String?): String =
    if (tag == null) stringResource(R.string.settings_language_system) else endonymOf(tag)

/** Endonym (language name in its own language), capitalised. */
private fun endonymOf(tag: String): String {
    val locale = java.util.Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) }
}

/** Which recording-default picker sheet is open in [SettingsScreen]. */
private enum class RecordingDefaultSheet { RESOLUTION, DURATION, INTERVAL, LOOPS }
