# B1 Settings Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface the four recording-default prefs (resolution / clip duration / interval / loops) as editable rows in App Settings, plus a single honest "System notification settings" deep-link row — without diverging from the record sheet's values.

**Architecture:** App Settings and the record sheet edit the *same* `RovaSettings` prefs through two ViewModels (`SettingsViewModel` shared; `RecordViewModel` record-nav-scoped). To prevent a stale-read / clobber bug, both ViewModels re-read the prefs on their screen's `ON_RESUME` (resume-reseed). All step/clamp/format logic is reused from `RecordSettingBounds` + `RecordSettingsFormat` + `QualityPresets`; no logic is re-implemented. Pickers are two small reusable bottom sheets. The notification row's API-level decision is a pure, unit-tested helper.

**Tech Stack:** Kotlin, Jetpack Compose (Material3 `ModalBottomSheet`), JUnit (JVM-only, `isReturnDefaultValues = true`). Build: `./gradlew.bat :app:testDebugUnitTest :app:lintDebug --no-daemon` (runs the 25 `check*` gates).

**Spec:** `docs/superpowers/specs/2026-05-30-b1-settings-expansion-design.md`. **Branch:** `feat/settings-expansion-b1`.

---

## File Structure

**New**
- `app/src/main/java/com/aritr/rova/ui/screens/NotificationSettingsIntent.kt` — pure `notificationSettingsTarget(sdkInt)` + `NotifSettingsTarget` enum + thin `buildNotificationSettingsIntent` wrapper.
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsStepperSheet.kt` — generic `−`/value/`+` bottom sheet (duration, interval, loops).
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsOptionSheet.kt` — generic single-select bottom sheet (resolution).
- `app/src/test/java/com/aritr/rova/ui/screens/NotificationSettingsIntentTest.kt` — both API branches.

**Modified**
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt` — 4 write-through flows + collectors + `reloadRecordingDefaults()`.
- `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt` — `reloadRecordingDefaults()`.
- `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` — `ON_RESUME` reseed call.
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` — `ON_RESUME` reseed; Recording-defaults section; Notifications section.
- `CHANGELOG.md` — `[Unreleased]` entry.

**Unchanged on purpose:** `RovaSettings.kt` (no new key), the service, the record `SettingsSheet` controls, every `check*` task.

---

## Task 1: Notification-settings deep-link decision helper (pure, TDD)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/NotificationSettingsIntent.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/NotificationSettingsIntentTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/ui/screens/NotificationSettingsIntentTest.kt`:

```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationSettingsIntentTest {

    @Test
    fun api26AndAbove_usesAppNotificationSettings() {
        assertEquals(NotifSettingsTarget.APP_NOTIFICATION_SETTINGS, notificationSettingsTarget(26))
        assertEquals(NotifSettingsTarget.APP_NOTIFICATION_SETTINGS, notificationSettingsTarget(34))
    }

    @Test
    fun api24And25_fallBackToAppDetails() {
        assertEquals(NotifSettingsTarget.APP_DETAILS, notificationSettingsTarget(24))
        assertEquals(NotifSettingsTarget.APP_DETAILS, notificationSettingsTarget(25))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.NotificationSettingsIntentTest" --no-daemon`
Expected: FAIL — `Unresolved reference: NotifSettingsTarget` / `notificationSettingsTarget`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/aritr/rova/ui/screens/NotificationSettingsIntent.kt`:

```kotlin
package com.aritr.rova.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Which system screen the Settings "System notification settings" row opens.
 *
 * The recording foreground-service notification is mandatory and per-channel
 * muting is the OS's job, so this row routes the user OUT to the platform
 * rather than faking app-level notification toggles (B1 design §2.3).
 *
 * The API-level decision is split into this pure enum + [notificationSettingsTarget]
 * so it is unit-testable under `isReturnDefaultValues = true`; the actual
 * [Intent] build (android.jar, a no-op under JVM tests) stays in the thin
 * [buildNotificationSettingsIntent] wrapper.
 */
enum class NotifSettingsTarget {
    /** API 26+: per-app channel list. */
    APP_NOTIFICATION_SETTINGS,

    /** API 24-25: app details page (no channel screen exists pre-O). */
    APP_DETAILS,
}

/** Pure decision: per-app channel settings on O+, app-details fallback below. */
fun notificationSettingsTarget(sdkInt: Int): NotifSettingsTarget =
    if (sdkInt >= Build.VERSION_CODES.O) {
        NotifSettingsTarget.APP_NOTIFICATION_SETTINGS
    } else {
        NotifSettingsTarget.APP_DETAILS
    }

/** Builds the platform intent for the resolved [notificationSettingsTarget]. */
fun buildNotificationSettingsIntent(context: Context): Intent =
    when (notificationSettingsTarget(Build.VERSION.SDK_INT)) {
        NotifSettingsTarget.APP_NOTIFICATION_SETTINGS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        NotifSettingsTarget.APP_DETAILS ->
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.NotificationSettingsIntentTest" --no-daemon`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/NotificationSettingsIntent.kt app/src/test/java/com/aritr/rova/ui/screens/NotificationSettingsIntentTest.kt
git commit -m "feat(settings): notification deep-link target helper (B1 task 1)"
```

---

## Task 2: `SettingsViewModel` — recording-default flows + reseed

No unit test: `SettingsViewModel` is an `AndroidViewModel` (framework-bound; the repo keeps ViewModels untested under the JVM-only policy). Verified by compile + the Task 7 build.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt`

- [ ] **Step 1: Add the four write-through flows**

In `SettingsViewModel`, after the `exportFolderName` flow (line ~34), add:

```kotlin
    // B1 — recording defaults, surfaced as editable rows in App Settings.
    // These are the SAME persisted prefs the record SettingsSheet edits via
    // RecordViewModel. SharedPreferences is the single source of truth; both
    // ViewModels resume-reseed (see reloadRecordingDefaults + RecordViewModel)
    // so the in-memory copies converge and neither clobbers the other.
    val resolution = MutableStateFlow(settings.resolution)
    val durationSeconds = MutableStateFlow(settings.durationSeconds)
    val intervalMinutes = MutableStateFlow(settings.intervalMinutes)
    val loopCount = MutableStateFlow(settings.loopCount)
```

- [ ] **Step 2: Add the write-back collectors**

Inside the existing `init { … }` block, after the `exportFolderName` collector (line ~43), add:

```kotlin
        viewModelScope.launch { resolution.collect { settings.resolution = it } }
        viewModelScope.launch { durationSeconds.collect { settings.durationSeconds = it } }
        viewModelScope.launch { intervalMinutes.collect { settings.intervalMinutes = it } }
        viewModelScope.launch { loopCount.collect { settings.loopCount = it } }
```

- [ ] **Step 3: Add the reseed function**

After the `init` block (before the closing brace of the class), add:

```kotlin
    /**
     * B1 — re-read the recording-default prefs from [RovaSettings] into the
     * flows. Called from SettingsScreen ON_RESUME so values changed by the
     * record sheet while this screen was backgrounded are reflected. Setting
     * a flow to the just-read value re-fires its write-back collector with an
     * identical value — a harmless no-op write.
     */
    fun reloadRecordingDefaults() {
        resolution.value = settings.resolution
        durationSeconds.value = settings.durationSeconds
        intervalMinutes.value = settings.intervalMinutes
        loopCount.value = settings.loopCount
    }
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew.bat :app:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt
git commit -m "feat(settings): SettingsViewModel recording-default flows + reseed (B1 task 2)"
```

---

## Task 3: `RecordViewModel` reseed + `RecordScreen` ON_RESUME hook

No unit test (framework-bound ViewModel + Compose). Verified by compile + Task 7 build.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`

- [ ] **Step 1: Add `reloadRecordingDefaults()` to `RecordViewModel`**

In `RecordViewModel`, immediately after the `init { … }` block closes (after line ~178), add:

```kotlin
    /**
     * B1 — re-read the recording-default prefs from [RovaSettings] into the
     * existing flows. Called from RecordScreen ON_RESUME so a change made in
     * App Settings while the record screen was backgrounded is reflected, and
     * a later stepper nudge does not write back a stale value (clobber fix).
     */
    fun reloadRecordingDefaults() {
        duration.value = settings.durationSeconds
        interval.value = settings.intervalMinutes
        loopCount.value = settings.loopCount
        resolution.value = settings.resolution
    }
```

- [ ] **Step 2: Add the ON_RESUME reseed to `RecordScreen`**

`RecordScreen` already imports `LocalLifecycleOwner`/lifecycle observers in the codebase pattern; if any of these imports are missing, add them to the import block at the top of `RecordScreen.kt`:

```kotlin
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
```

Then, inside the `RecordScreen` composable body, right after `viewModel` is in scope (near the top of the function, before the main layout), add:

```kotlin
    // B1 — resume-reseed recording defaults from prefs so an edit made in App
    // Settings is reflected here and a stepper nudge cannot clobber it.
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
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew.bat :app:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(settings): record-screen resume-reseed of recording defaults (B1 task 3)"
```

---

## Task 4: `SettingsStepperSheet` composable

No unit test (Compose UI; all numeric logic is the already-tested `RecordSettingBounds`). Verified by compile + Task 7 build + lint.

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/SettingsStepperSheet.kt`

- [ ] **Step 1: Write the composable**

Create `app/src/main/java/com/aritr/rova/ui/screens/SettingsStepperSheet.kt`:

```kotlin
package com.aritr.rova.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.focusHighlight

/**
 * B1 — generic `−` / value / `+` bottom sheet for a single Int recording
 * default (clip duration, interval, loops). All clamp/step logic is supplied
 * by the caller from [RecordSettingBounds]; this composable owns presentation
 * + accessibility only.
 *
 * @param title        Sheet heading (e.g. "Clip duration").
 * @param valueLabel   Formatted current value (e.g. recordClipValue(value)).
 * @param atMin        Disable the `−` button (caller: RecordSettingBounds.*AtMin).
 * @param atMax        Disable the `+` button (caller: RecordSettingBounds.*AtMax).
 * @param onStep       Invoked with -1 / +1; caller maps via RecordSettingBounds.step*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsStepperSheet(
    title: String,
    valueLabel: String,
    atMin: Boolean,
    atMax: Boolean,
    onStep: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .semantics { heading() },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StepGlyphButton(
                    glyph = "−",
                    enabled = !atMin,
                    contentDescription = "Decrease $title",
                    onClick = { onStep(-1) },
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(min = 120.dp)
                        // Anti-chant: announces the new discrete value on change,
                        // not a per-tick stream (the value only moves on a tap).
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                StepGlyphButton(
                    glyph = "+",
                    enabled = !atMax,
                    contentDescription = "Increase $title",
                    onClick = { onStep(+1) },
                )
            }
        }
    }
}

@Composable
private fun StepGlyphButton(
    glyph: String,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(
                if (enabled) {
                    Modifier
                        .focusHighlight(shape)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), shape)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
```

> Note: `heading()` is `androidx.compose.ui.semantics.heading`. Add the import
> `import androidx.compose.ui.semantics.heading` to the file (kept out of the block
> above only to call it out — include it).

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew.bat :app:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsStepperSheet.kt
git commit -m "feat(settings): SettingsStepperSheet bottom sheet (B1 task 4)"
```

---

## Task 5: `SettingsOptionSheet` composable

No unit test (Compose UI; resolution option set + canonicalization are the already-tested `QualityPresets`). Verified by compile + Task 7 build + lint.

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/screens/SettingsOptionSheet.kt`

- [ ] **Step 1: Write the composable**

Create `app/src/main/java/com/aritr/rova/ui/screens/SettingsOptionSheet.kt`:

```kotlin
package com.aritr.rova.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.focusHighlight

/**
 * B1 — generic single-select bottom sheet. One row per option with a trailing
 * check on the selected one. Each row is a `selectable(role = RadioButton)`
 * node with a merged content description, and shows a focus ring for D-pad
 * (WCAG 2.2 AA SC 4.1.2 / 2.4.7, ADR-0020).
 *
 * @param optionLabel  Human label for an option (e.g. { it } for resolution).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsOptionSheet(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .semantics { heading() },
            )
            options.forEach { option ->
                val isSelected = option == selected
                val label = optionLabel(option)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusHighlight(androidx.compose.ui.graphics.RectangleShape)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onPick(option) },
                        )
                        .semantics {
                            contentDescription =
                                if (isSelected) "$label, selected" else label
                        }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew.bat :app:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsOptionSheet.kt
git commit -m "feat(settings): SettingsOptionSheet single-select bottom sheet (B1 task 5)"
```

---

## Task 6: Wire the new sections into `SettingsScreen`

No unit test (Compose wiring). Verified by Task 7 build + lint + manual smoke.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt`

- [ ] **Step 1: Add imports**

Add to the import block of `SettingsScreen.kt`:

```kotlin
import com.aritr.rova.data.QualityPresets
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Notifications
```

> If any of these `material.icons.filled.*` names are not present in the bundled
> icon set, substitute the nearest available filled icon (the icon is decorative —
> `contentDescription = null` in `SettingsRow`). `HighQuality`, `Timer`, `Repeat`,
> and `Notifications` are standard; `HourglassEmpty` is the interval glyph.

- [ ] **Step 2: Collect the four flows + track which sheet is open**

Inside `SettingsScreen`, alongside the existing `collectAsStateWithLifecycle()` calls (after `exportFolderName`, line ~123), add:

```kotlin
    val resolution by settingsViewModel.resolution.collectAsStateWithLifecycle()
    val durationSeconds by settingsViewModel.durationSeconds.collectAsStateWithLifecycle()
    val intervalMinutes by settingsViewModel.intervalMinutes.collectAsStateWithLifecycle()
    val loopCount by settingsViewModel.loopCount.collectAsStateWithLifecycle()
```

Alongside the existing `showBatteryDialog` / `showFolderDialog` state (line ~141), add:

```kotlin
    var openSheet by remember { mutableStateOf<RecordingDefaultSheet?>(null) }
```

- [ ] **Step 3: Add the reseed call to the existing ON_RESUME `DisposableEffect`**

In the existing `DisposableEffect(lifecycleOwner) { … }` (line ~131), inside the
`if (event == Lifecycle.Event.ON_RESUME) { … }` block, after the `batteryExempt = …`
line, add:

```kotlin
                settingsViewModel.reloadRecordingDefaults()
```

- [ ] **Step 4: Add the Recording-defaults section**

In the `Column` content, insert this **immediately before** the existing
`SettingsSection(label = "Recording behavior")` block (line ~177):

```kotlin
            SettingsSection(label = "Recording defaults") {
                SettingsRow(
                    icon = Icons.Default.HighQuality,
                    label = "Default resolution",
                    supporting = "Quality new recordings start at.",
                    value = QualityPresets.canonicalizeOrDefault(resolution),
                    onClick = { openSheet = RecordingDefaultSheet.RESOLUTION },
                    trailing = { ChevronTrailing() },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Timer,
                    label = "Clip duration",
                    supporting = "How long each clip records.",
                    value = recordClipValue(durationSeconds),
                    onClick = { openSheet = RecordingDefaultSheet.DURATION },
                    trailing = { ChevronTrailing() },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.HourglassEmpty,
                    label = "Interval between clips",
                    supporting = "Wait time before the next clip.",
                    value = recordWaitValue(intervalMinutes),
                    onClick = { openSheet = RecordingDefaultSheet.INTERVAL },
                    trailing = { ChevronTrailing() },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Repeat,
                    label = "Number of loops",
                    supporting = "How many clips before stopping.",
                    value = recordRepeatsValue(loopCount),
                    onClick = { openSheet = RecordingDefaultSheet.LOOPS },
                    trailing = { ChevronTrailing() },
                )
            }
```

- [ ] **Step 5: Add the Notifications section**

Insert this **immediately before** the existing `SettingsSection(label = "Storage")`
block (line ~213):

```kotlin
            SettingsSection(label = "Notifications") {
                SettingsRow(
                    icon = Icons.Default.Notifications,
                    label = "System notification settings",
                    supporting = "Manage Rova's notification channels in Android settings.",
                    onClick = {
                        try {
                            context.startActivity(buildNotificationSettingsIntent(context))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                "Notification settings not available on this device",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    trailing = { ChevronTrailing() },
                )
            }
```

- [ ] **Step 6: Host the picker sheets + define the enum**

At the end of the composable, after the existing `if (showFolderDialog) { … }` block
(line ~357), add:

```kotlin
    when (openSheet) {
        RecordingDefaultSheet.RESOLUTION -> SettingsOptionSheet(
            title = "Default resolution",
            options = QualityPresets.PICKER_ORDER,
            selected = QualityPresets.canonicalizeOrDefault(resolution),
            optionLabel = { it },
            onPick = { settingsViewModel.resolution.value = it },
            onDismiss = { openSheet = null },
        )
        RecordingDefaultSheet.DURATION -> SettingsStepperSheet(
            title = "Clip duration",
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
            title = "Interval between clips",
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
            title = "Number of loops",
            valueLabel = recordRepeatsValue(loopCount),
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
```

Add the enum at the bottom of the file (top-level, after `ExportFolderDialog`):

```kotlin
/** Which recording-default picker sheet is open in [SettingsScreen]. */
private enum class RecordingDefaultSheet { RESOLUTION, DURATION, INTERVAL, LOOPS }
```

> `RecordSettingBounds`, `recordClipValue`, `recordWaitValue`, `recordRepeatsValue`,
> `SettingsOptionSheet`, `SettingsStepperSheet`, and `buildNotificationSettingsIntent`
> are all in package `com.aritr.rova.ui.screens` — no imports needed. `ActivityNotFoundException`
> and `Toast` are already imported in `SettingsScreen.kt`.

- [ ] **Step 7: Verify it compiles**

Run: `./gradlew.bat :app:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt
git commit -m "feat(settings): recording-defaults + notifications sections (B1 task 6)"
```

---

## Task 7: CHANGELOG + full verification

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add the CHANGELOG entry**

Under `## [Unreleased]` → `### Added` in `CHANGELOG.md`, add:

```markdown
- App Settings — **Recording defaults** section surfaces default resolution / clip duration / interval / number of loops as editable rows (the same prefs the record sheet edits; both screens resume-reseed from SharedPreferences so they never diverge), and a **Notifications** row that deep-links to the system notification-channel settings (honest — the recording foreground-service notification is mandatory). WCAG 2.2 AA by default (ADR-0020). Spec: `docs/superpowers/specs/2026-05-30-b1-settings-expansion-design.md`.
```

- [ ] **Step 2: Run the full gate**

Run: `./gradlew.bat :app:testDebugUnitTest :app:lintDebug --no-daemon`
Expected: BUILD SUCCESSFUL — all unit tests pass (including `NotificationSettingsIntentTest`), lint clean, and all 25 `check*` tasks pass (B1 added no new invariant and touched no `check*`-guarded path).

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs(settings): CHANGELOG for B1 settings expansion (B1 task 7)"
```

- [ ] **Step 4: Manual smoke (real device, per repo policy)**

1. Open App Settings → confirm the new **Recording defaults** rows show the current values.
2. Change clip duration via the stepper sheet; back out to the record screen → its settings card/sheet shows the new value (resume-reseed).
3. Change the duration in the record sheet; return to App Settings → the row reflects it (resume-reseed the other direction).
4. Tap **System notification settings** → the OS notification screen for Rova opens.
5. TalkBack: section labels announce as headings; option rows announce "label, selected"; stepper buttons announce "Increase/Decrease …" and the value re-announces on change.

---

## Self-Review

**Spec coverage:**
- §2.1 IA (Recording defaults + Notifications sections placed) → Task 6 steps 4-5. ✓
- §2.2 four rows, hybrid (resolution list, duration/interval/loops stepper), reused seams → Tasks 4, 5, 6. ✓
- §2.3 Notifications deep-link, API split, ActivityNotFound→Toast → Tasks 1, 6 step 5. ✓
- §3 resume-reseed both ViewModels → Tasks 2, 3, 6 step 3. ✓
- §4 a11y (heading, RadioButton role, focusHighlight, button CDs, value live region) → Tasks 4, 5. ✓
- §5 error handling (intent Toast, stepper clamp via atMin/atMax disabling) → Tasks 1, 6. ✓
- §6 testing (pure decision helper tested; reused logic not duplicated) → Task 1; Tasks 2-6 note framework-bound, no JVM test. ✓
- §7 file list matches Tasks 1-7. ✓

**Placeholder scan:** No TBD/TODO. Every code step shows full code. The two italic "Note" callouts name concrete fallbacks (icon substitution, `heading()` import) rather than deferring decisions. ✓

**Type consistency:** `reloadRecordingDefaults()` identical name in `SettingsViewModel` (Task 2) and `RecordViewModel` (Task 3). `RecordingDefaultSheet` enum values (RESOLUTION/DURATION/INTERVAL/LOOPS) match between Task 6 step 4 (set) and step 6 (consume). `SettingsStepperSheet`/`SettingsOptionSheet` signatures defined in Tasks 4/5 match call sites in Task 6 step 6. `notificationSettingsTarget`/`NotifSettingsTarget`/`buildNotificationSettingsIntent` consistent across Tasks 1 and 6. `RecordSettingBounds` method names (`clipAtMin`/`clipAtMax`/`stepClip`/`waitAtMin`/`waitAtMax`/`stepWait`/`repeatsAtMin`/`repeatsAtMax`/`stepRepeats`) match the source. ✓
