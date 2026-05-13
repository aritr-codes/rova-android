# Phase 6 — Record-home Mode picker — Implementation Plan

**Spec**: [docs/superpowers/specs/2026-05-13-record-home-mode-picker-design.md](../specs/2026-05-13-record-home-mode-picker-design.md) @ commit `d90f8ea`
**Branch**: `feat/record-home-mode-picker` (base `master @ 8c63b32`)
**Workflow**: superpowers:subagent-driven-development. Each task = one subagent call. Per-task commit. Final gate run + PR.
**Test policy**: Pure-helper JVM-only unit tests. No Robolectric. No Compose UI tests.

## Predicted gates (committed)

- **Tests**: `790 / 66 / 0-0-0` (baseline `774 / 63` + `16` methods / `3` classes from this slice). Single number, not a range — the unfiltered-gate run at the end of Task 8 verifies predicted-vs-actual.
- **Lint**: `53` unchanged (50 W + 3 H + 0 E). No new `InlinedApi` / `NewApi` findings.
- **`assembleDebug`**: OK.

## Optional test class

`ModePickerStateTest` from spec §5 is **NOT** added by this plan. State derivation in the composable is `currentMode == "Portrait"` etc., direct inline — no helper, no test class. If a subagent grows the derivation past ~5 LOC during Task 6, escalate to owner before adding the helper.

## Task ordering rationale

Tasks 1–3 are independent leaf changes (data layer + pure helper). Task 4 depends on Task 3 (the helper). Task 5 depends on Task 4 (service binder method). Task 6 depends on no other task. Task 7 depends on Tasks 5 + 6. Task 8 depends on all. The strict serial order below is the safe path; a sufficiently disciplined subagent could parallelize 1+2+3+6 but the per-task commit discipline is what makes the review-gate readable, so we run serial.

---

## Task 1 — `RovaSettings.mode` field

**File**: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt`

**Spec refs**: §2 (RovaSettings row), §4 ("RovaSettings.mode corrupt value"), §6 D2.

### Steps

1. **(Red)** Add test class `app/src/test/java/com/aritr/rova/data/RovaSettingsModeTest.kt` — Robolectric-free. NOT counted in the +16 because RovaSettings tests don't exist today; **DROP this test step**. RovaSettings is a thin SharedPreferences wrapper with no JVM-only test surface today; the corrupt-value coercion will be covered indirectly by `SessionConfigModeTest` via the constructor default. No new test file for this task.
2. **(Green)** Insert `var mode: String` immediately after the `resolution` block ([RovaSettings.kt:17-19](../../app/src/main/java/com/aritr/rova/data/RovaSettings.kt#L17-L19)):

   ```kotlin
   var mode: String
       get() = (prefs.getString("mode", "Portrait") ?: "Portrait")
           .takeIf { it == "Portrait" || it == "Landscape" } ?: "Portrait"
       set(value) = prefs.edit { putString("mode", value) }
   ```

3. **(Gate)** `./gradlew :app:compileDebugKotlin` — confirms it compiles.

### Commit

`feat(data): RovaSettings.mode persistence (default "Portrait", coerces unknown)`

### Out of scope

Anything else in `RovaSettings`.

---

## Task 2 — `SessionConfig.mode` + manifest `SCHEMA_VERSION 3 → 4`

**Files**:
- `app/src/main/java/com/aritr/rova/data/SessionManifest.kt`
- **NEW** `app/src/test/java/com/aritr/rova/data/SessionConfigModeTest.kt`
- **NEW** `app/src/test/java/com/aritr/rova/data/SessionManifestModeMigrationTest.kt`

**Spec refs**: §2 (SessionManifest row), §4 ("Unknown manifest mode value"), §6 D7, §5 (test classes).

### Steps

1. **(Red)** Write `SessionConfigModeTest.kt` — 3 methods:
   - `defaultMode_isPortrait` — `SessionConfig(durationSeconds=10, intervalMinutes=1, resolution="HD", loopCount=10).mode == "Portrait"`.
   - `toJson_includesMode` — `SessionConfig(..., mode = "Landscape").toJson().getString("mode") == "Landscape"`.
   - `fromJson_roundTripsMode` — for both `"Portrait"` and `"Landscape"`.
2. **(Red)** Write `SessionManifestModeMigrationTest.kt` — 5 methods. Build a minimal v3-shape JSON (no `mode` key in `config`) and assert `SessionManifest.fromJson(json).config.mode == "Portrait"`. Build a v4-shape JSON with each of: `"Portrait"`, `"Landscape"`, `"Diagonal"`, `null` (`JSONObject.NULL`), empty string. Confirm round-trip / coercion.
3. **(Green)** Edit `SessionManifest.kt`:
   - `SessionConfig` data class ([SessionManifest.kt:149-170](../../app/src/main/java/com/aritr/rova/data/SessionManifest.kt#L149-L170)) — add `val mode: String = "Portrait"` field. **Default goes LAST in the constructor** so existing callers (`SessionStore.createSession` etc.) don't need positional updates.
   - `SessionConfig.toJson` — `put("mode", mode)`.
   - `SessionConfig.fromJson` — read with the `audioMode` null-safe pattern:
     ```kotlin
     mode = json.optString("mode", "").ifEmpty { null }
         ?.takeIf { it == "Portrait" || it == "Landscape" }
         ?: "Portrait"
     ```
   - `SessionManifest.SCHEMA_VERSION` ([SessionManifest.kt:92](../../app/src/main/java/com/aritr/rova/data/SessionManifest.kt#L92)) — bump `3` → `4`. Update the leading comment block accordingly: `// v4 (Phase 6): added SessionConfig.mode. v1/v2/v3 manifests read with safe default ("Portrait").`
4. **(Gate)** `./gradlew :app:testDebugUnitTest --tests com.aritr.rova.data.SessionConfigModeTest --tests com.aritr.rova.data.SessionManifestModeMigrationTest`.

### Commit

`feat(data): SessionConfig.mode + manifest SCHEMA_VERSION 3→4 (default "Portrait")`

### Out of scope

Anything else in `SessionManifest` or `SessionConfig`. No `JSONObject.NULL` for the manifest writer — always emit the actual string.

---

## Task 3 — `computeTargetRotation` pure helper + `ModeRotationTest`

**Files**:
- `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` (top-level helper at file scope)
- **NEW** `app/src/test/java/com/aritr/rova/service/ModeRotationTest.kt`

**Spec refs**: §2 (RovaRecordingService row item d), §4 ("Defensive ROTATION_0 fallback"), §6 D1, §5 (test class).

### Steps

1. **(Red)** Write `ModeRotationTest.kt` — 8 methods. Pure JVM. Each method calls `computeTargetRotation(rot, mode)` and asserts the expected output. Use `android.view.Surface.ROTATION_0` etc. (these are integer constants 0/1/2/3 — no Android runtime needed).
   - `portrait_rotation0_returnsRotation0`
   - `portrait_rotation90_returnsRotation90`
   - `portrait_rotation180_returnsRotation180`
   - `portrait_rotation270_returnsRotation270`
   - `landscape_rotation0_returnsRotation90`
   - `landscape_rotation90_returnsRotation180`
   - `landscape_rotation180_returnsRotation270`
   - `landscape_rotation270_returnsRotation0`
2. **(Green)** Add helper at the END of `RovaRecordingService.kt` (after the `RovaRecordingService` class closing brace, or in the existing top-level scope after the `class` body):

   ```kotlin
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
   ```

3. **(Gate)** `./gradlew :app:testDebugUnitTest --tests com.aritr.rova.service.ModeRotationTest` — 8/8 green.

### Commit

`feat(service): computeTargetRotation helper (mode → CameraX rotation)`

### Out of scope

Wiring the helper into `setupCamera` — Task 4.

---

## Task 4 — `RovaRecordingService` currentMode cache + `setMode` binder + `setupCamera` wire

**File**: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`

**Spec refs**: §1 (Architecture #3), §2 (RovaRecordingService row items a-c), §3 step 5, §6 D4.

### Steps

1. **(Edit)** Add private field after the existing `currentCameraSelector` declaration at [RovaRecordingService.kt:294](../../app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt#L294):

   ```kotlin
   private var currentMode: String = "Portrait"
   ```

2. **(Edit)** Seed in `onCreate` at [RovaRecordingService.kt:552-553](../../app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt#L552-L553). Insert after `super.onCreate()`:

   ```kotlin
   currentMode = RovaSettings(this).mode
   ```

3. **(Edit)** Thread rotation into `setupCamera` at [RovaRecordingService.kt:1103-1124](../../app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt#L1103-L1124). Just before the `videoCapture = VideoCapture.withOutput(recorder)` line (1106), insert:

   ```kotlin
   val display = display ?: ContextCompat.getSystemService(this, WindowManager::class.java)?.defaultDisplay
   val displayRotation = display?.rotation ?: android.view.Surface.ROTATION_0
   val targetRot = computeTargetRotation(displayRotation, currentMode)
   ```

   Note: services don't have a `Display` directly. The pattern is `(getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)`. **Subagent: verify the correct Service-context display lookup** (the spec assumed `display.rotation` is reachable; in practice it's `getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation`). Confirm with `ctx_search` / `grep` before writing the final code; if the lookup is awkward, prefer the `DisplayManager` path. The fallback `?: ROTATION_0` matches §4.

   Then swap line 1106:

   ```kotlin
   videoCapture = VideoCapture.Builder(recorder).setTargetRotation(targetRot).build()
   ```

   And add `.setTargetRotation(targetRot)` to the `Preview.Builder()` chain at line 1108:

   ```kotlin
   preview = Preview.Builder().setTargetRotation(targetRot).build()
   ```

4. **(Edit)** Add `setMode` method on the service. Insert immediately after the existing `flipCamera()` block at [RovaRecordingService.kt:1169-1180](../../app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt#L1169-L1180):

   ```kotlin
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
   ```

5. **(Gate)** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`. Confirm no test regressions; the new `setMode` has no unit test (symmetric to `flipCamera`).

### Commit

`feat(service): currentMode cache + setMode binder + setupCamera rotation wire`

### Out of scope

Manifest-side `config.mode` is written downstream by `SessionStore.createSession` consumers (Task 5+); this task changes ONLY the recorder rotation wiring.

### Subagent notes

- **Verify Display lookup pattern** via `Grep` for `getSystemService(DisplayManager` or `Display.DEFAULT_DISPLAY` in the codebase before writing the exact lines. If no precedent exists in this service, the cleanest path is `(getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)`.
- **DO NOT** change any other `setupCamera` logic — preview surface wiring, bindToLifecycle args, error paths.
- **DO NOT** add a new SessionConfig writer call here. The recorder uses `currentMode` for rotation; the manifest write happens through `SessionStore.createSession(config, ...)` which receives the `SessionConfig` from the start-call site — that wiring happens in Task 5 via `RecordViewModel.setMode` setting both the StateFlow AND the prefs, so the next `start()` call reads `RovaSettings.mode` for the `SessionConfig` field.

### Open question that the subagent MUST flag if unresolved

The current `start()` call site in `RecordScreen.onStart` at [RecordScreen.kt:376-382](../../app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt#L376-L382) constructs `SessionConfig` from `viewModel.duration.value`, `viewModel.interval.value`, `viewModel.loopCount.value`, `viewModel.resolution.value`. To include `mode`, `RovaRecordingService.start(...)` needs a new `mode: String` parameter, and `SessionConfig` constructor at the call inside the service needs `mode = mode`. **This is a Task 4 sub-step the spec didn't explicitly itemize.** Subagent: locate the `SessionConfig(` constructor invocation inside `RovaRecordingService` (likely in `onStartCommand` or `startPeriodicRecording`), and add `mode = currentMode` to that constructor call. NO new parameter on the `start()` companion method — `currentMode` is already cached service-side from `RovaSettings.mode` at `onCreate`, and `RecordViewModel.setMode` writes prefs before calling the binder. The prefs-commit ordering in `setMode` (Task 5) ensures `currentMode` is in sync when `start()` fires.

---

## Task 5 — `RecordViewModel.mode` + `setMode`

**File**: `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt`

**Spec refs**: §2 (RecordViewModel row), §3 step 4, §1 (Architecture #2).

### Steps

1. **(Edit)** Add the `MutableStateFlow` after the existing `flashMode` declaration at [RecordViewModel.kt:77](../../app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt#L77):

   ```kotlin
   val mode = MutableStateFlow(settings.mode)
   ```

2. **(Edit)** Do NOT add a `viewModelScope.launch { mode.collect { settings.mode = it } }` collector block — `setMode` writes prefs directly so the prefs-commit ordering matches the binder call (spec §2 RecordViewModel row, explicit).
3. **(Edit)** Add `setMode` after the existing `flipCamera` at [RecordViewModel.kt:196-198](../../app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt#L196-L198):

   ```kotlin
   fun setMode(mode: String) {
       settings.mode = mode                                  // (1) prefs commit
       this.mode.value = mode                                // (2) StateFlow update
       serviceBinder?.getService()?.setMode(mode)            // (3) service rebind
   }
   ```

4. **(Gate)** `./gradlew :app:compileDebugKotlin`. No new unit test (the function is too thin to cover meaningfully without binding to the service — symmetric to `flipCamera`).

### Commit

`feat(viewmodel): RecordViewModel.mode + setMode`

### Out of scope

Anything else in `RecordViewModel`.

---

## Task 6 — `SessionSettingsSheet` `ModeTabsPicker`

**File**: `app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt`

**Spec refs**: §2 (SessionSettingsSheet row), §6 D8 (visual contract), §6 D9 (P+L disabled affordance).

### Steps

1. **(Edit)** Drop the `recordModeValue()` helper at [SessionSettingsSheet.kt:57](../../app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt#L57) (no longer used).
2. **(Edit)** Update the `SessionSettingsSheet` composable signature:

   ```kotlin
   @Composable
   fun SessionSettingsSheet(
       durationSeconds: Int,
       loopCount: Int,
       intervalMinutes: Int,
       quality: String,
       currentMode: String,                  // NEW
       modeEnabled: Boolean,                 // NEW
       onPickRow: (SheetTarget) -> Unit,
       onModePick: (String) -> Unit,         // NEW
       onDismiss: () -> Unit,
   )
   ```

3. **(Edit)** Replace the read-only Mode block at lines 81-94 with:

   ```kotlin
   SectionLabel("Recording mode")
   ModeTabsPicker(
       currentMode = currentMode,
       enabled = modeEnabled,
       onPick = onModePick,
   )
   ```

4. **(Edit)** Add the new `private @Composable fun ModeTabsPicker(...)` at the end of the file. CSS pins from spec §6 D8 (Compose translation):
   - Track: `Modifier.background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(13.dp)).padding(3.dp)`.
   - Row: `Arrangement.spacedBy(2.dp)`.
   - Each tab: `Modifier.weight(1f).clip(RoundedCornerShape(10.dp))` + active branch `.background(Color.White.copy(alpha = 0.11f))`, inactive branch transparent; `.padding(vertical = 8.dp, horizontal = 4.dp)`. Active text color = `Color.White.copy(alpha = 0.90f)`; inactive enabled text = `Color.White.copy(alpha = 0.26f)`; **P+L disabled** text = `Color.White.copy(alpha = 0.16f)` (Variant A pin).
   - Text style: `style = MaterialTheme.typography.labelLarge` (≈ 11.sp), `fontWeight = FontWeight.Medium`, `letterSpacing = 0.1.sp`.
   - Active tab shadow: `.shadow(elevation = 1.dp, shape = RoundedCornerShape(10.dp))` applied BEFORE the background modifier so the shadow draws under the rounded rect.
   - Click handler: `if (enabled && tab != PortraitLandscape) Modifier.clickable { onPick(tabValue) }` else no modifier (so the P+L tap is silent — no ripple, no handler).
   - Tab labels: `"Portrait"`, `"Landscape"`, `"P + L"`.
   - Tab values passed to `onPick`: `"Portrait"`, `"Landscape"` (P+L never reached).

   Three tabs in a `Row` filling width, each a `Box` with the active/inactive/disabled styling.

5. **(Edit)** Drop the `Spacer(Modifier.height(8.dp))` immediately after the old Mode block (line 95) — verify with the rendered sheet that the spacing between the picker and "Session settings" still reads correctly; the picker's internal `padding(3.dp)` track + the Compose default spacing between `Column` children may already account for the gap. If the visual gap collapses, restore the 8.dp Spacer.

6. **(Gate)** `./gradlew :app:compileDebugKotlin`. No unit test (Compose UI; deferred to on-device smoke per spec §5).

### Commit

`feat(ui): SessionSettingsSheet ModeTabsPicker (3-tab segmented, P+L disabled stub)`

### Out of scope

Anything outside `SessionSettingsSheet.kt`. **Do NOT** modify `RecordEditSheets.kt` — Mode is INLINE in the settings sheet, not a drill-down (§6 D8).

### Subagent notes

- **Visual fidelity matters more than literal CSS translation** — translate the pins to the closest idiomatic Compose primitives, using existing `MaterialTheme.typography.*` styles where they match (≈ 11sp = `labelLarge`; ≈ 8.5sp section label = `labelSmall` per existing precedent at line 110-118).
- **P+L "no promise contract"**: no inline copy, no tooltip, no semantic content description that promises a feature. The accessibility content description for the P+L tab should be a neutral `"Portrait + Landscape — disabled"` or omitted.

---

## Task 7 — `RecordScreen` wire-up

**File**: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`

**Spec refs**: §2 (RecordScreen row), §3 step 4.

### Steps

1. **(Edit)** Add the `mode` collection block alongside the existing `resolution` collection at [RecordScreen.kt:60](../../app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt#L60):

   ```kotlin
   val mode by viewModel.mode.collectAsStateWithLifecycle()
   ```

2. **(Edit)** Update the `SessionSettingsSheet` call at [RecordScreen.kt:666-673](../../app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt#L666-L673):

   ```kotlin
   SessionSettingsSheet(
       durationSeconds = duration,
       loopCount = loopCount,
       intervalMinutes = interval,
       quality = resolution,
       currentMode = mode,
       modeEnabled = !isUiLocked,
       onPickRow = { target -> viewModel.openSheet(target) },
       onModePick = { viewModel.setMode(it) },
       onDismiss = { viewModel.closeSettingsSheet() },
   )
   ```

3. **(Gate)** `./gradlew :app:compileDebugKotlin`.

### Commit

`feat(ui): RecordScreen wire viewModel.mode → SessionSettingsSheet`

### Out of scope

The `AndroidView(PreviewView)` wrapper is unchanged — no `Modifier.rotate`, no aspect-ratio letterbox (PreviewView handles server-side per spec §2 / §6 D3). Compose-side letterbox is a Phase 6.1 concern.

---

## Task 8 — Final gate + branch ready

### Steps

1. **(Run)** `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
2. **(Verify)**
   - Tests: `790 / 66 / 0-0-0`. If the count differs by ±1 (e.g., a parameterized test inflated by 1), document the actual number in the done-report. If the diff is wider, STOP and surface to owner.
   - Lint: `53` (50 W + 3 H + 0 E). Zero new findings. If new `InlinedApi` / `NewApi` findings appear (the `setTargetRotation` calls should be clean — both APIs are level 21+, well below `minSdk`), surface to owner.
   - `assembleDebug`: BUILD SUCCESSFUL.
3. **(Verify pinning tests)** Confirm the 7 invariant pinning tests are still green:
   - `WarningIdOrderTest`
   - `WarningPrecedenceTest`
   - `WarningCenterAggregateTest`
   - `WarningSurfaceTest`
   - `WarningSheetContentTest`
   - `MidRecBannerContentTest`
   - `RecordActiveHudFormattersTest`

   ```bash
   ./gradlew :app:testDebugUnitTest --tests com.aritr.rova.ui.warnings.WarningIdOrderTest --tests com.aritr.rova.ui.warnings.WarningPrecedenceTest --tests com.aritr.rova.ui.warnings.WarningCenterAggregateTest --tests com.aritr.rova.ui.warnings.WarningSurfaceTest --tests com.aritr.rova.ui.warnings.WarningSheetContentTest --tests com.aritr.rova.ui.warnings.MidRecBannerContentTest --tests com.aritr.rova.ui.components.RecordActiveHudFormattersTest
   ```

4. **(Verify Start-gate untouched)** Confirm [RecordScreen.kt:107-122](../../app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt#L107-L122) is byte-for-byte unchanged in this branch's diff. `git diff master -- app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt | grep -E '^[+-].{0,3}(startBlocked|cameraPermission|storageInsufficient|cameraPermissionFlow|storageInsufficientFlow)'` should produce zero context-adjacent matches.
5. **(Push)** `git push -u origin feat/record-home-mode-picker`.
6. **(Open PR)** `gh pr create --base master --title "Phase 6 — Record-home Mode picker (Portrait / Landscape)" --body <see template below>`.

### PR body template

```
## Summary
- Phase 6 record-home Mode picker (Portrait / Landscape) — UI-only slice
- 3-tab segmented picker in SessionSettingsSheet; P+L disabled stub (Phase 6.1 owns dual-capture)
- CameraX rotation via derived `computeTargetRotation(display.rotation, mode)`
- SessionConfig.mode + manifest SCHEMA_VERSION 3 → 4 (default "Portrait")
- Mirrors flipCamera() 1:1 — future seamless-rebind slice fixes both in one place

## Gates
- Tests: 790 / 66 / 0-0-0 (baseline 774 / 63; +16 methods / +3 classes)
- Lint: 53 (50 W + 3 H + 0 E) — unchanged
- assembleDebug: OK

## Invariants preserved
- WarningId enum / ordinals / gatesStart / 17 rows — untouched
- WarningPrecedence.resolve(...) signature + walk — untouched
- Start-gate at RecordScreen.kt:107-122 — byte-for-byte unchanged
- 7 pinning tests — green

## Out of scope (LOCKED)
- P+L dual-capture (Phase 6.1)
- service/** beyond Preview/VideoCapture rotation override
- data/** beyond settings + SessionConfig.mode
- app/build.gradle.kts
- Onboarding (08-onboarding.html has zero Mode mentions)

## On-device smoke (DEFERRED-to-owner)
Per R1 / Phase 3.5 / Phase 4.1b precedent — no emulator in build env. See spec §5 for the checklist.

## Spec / plan
- Spec: docs/superpowers/specs/2026-05-13-record-home-mode-picker-design.md @ d90f8ea
- Plan: docs/superpowers/plans/2026-05-13-record-home-mode-picker.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

### Final commit

No new commit. The done-report goes as the first PR comment after `gh pr create` returns the URL.

---

## D-deviation policy

Any deviation from this plan (e.g., subagent finds the `setupCamera` Display lookup is awkward and proposes a different pattern, OR the test count comes in at 789 not 790) is flagged in the per-task done-report and surfaced to owner BEFORE the next task starts. Per Phase 4.1b precedent: deviations are fine if pre-flagged + justified. Silent deviations are NO-GO.

## Rollback

Each task is its own commit; `git revert <sha>` of any single commit unwinds that task cleanly. The data-layer changes (Tasks 1 + 2) are forward-compatible at runtime: a downgraded build reading a v4 manifest ignores the `mode` field; an upgraded build reading a v3 manifest defaults to `"Portrait"`. No data-loss risk.
