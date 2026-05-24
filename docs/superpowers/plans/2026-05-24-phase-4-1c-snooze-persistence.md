# Phase 4.1c — Snooze Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist "Don't show again" snoozes across cold start, with an explicit Settings reset row.

**Architecture:** `RovaSettings` gains a `snoozedWarningIds: Set<String>` property backed by the existing backup-excluded `rova_runtime_prefs` file. `WarningCenterViewModel` accepts two optional ctor params (initial seed + persistence callback) with defaults that preserve every existing call site. `RecordScreen` hoists a single VM instance via factory + shares it across the two `WarningCenter` mounts + the `SettingsSheet` reset-row wiring.

**Tech Stack:** Kotlin 2.2.10 · Jetpack Compose · `androidx.lifecycle.viewmodel.compose.viewModel` · `SharedPreferences` · JUnit4 pure-JVM (no Robolectric / coroutines-test).

**Spec:** [`docs/superpowers/specs/2026-05-24-phase-4-1c-snooze-persistence-design.md`](../specs/2026-05-24-phase-4-1c-snooze-persistence-design.md)

**Branch:** `phase4-1c-snooze-persistence` (already cut from master `e319700`; spec already committed).

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` | Persistent settings store. | + `snoozedWarningIds: Set<String>` property on `runtimePrefs`. |
| `app/src/test/java/com/aritr/rova/data/RovaSettingsTest.kt` | Pure-JVM round-trip coverage. | + 3 tests. |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt` | Warning aggregator + snooze state. | + 2 optional ctor params (`initialSnoozedIds`, `onSnoozeChanged`); seed `_snoozedForever`; mutator callbacks; + `clearSnoozes()`. |
| `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt` | VM behaviour tests. | + 3 tests. |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` | Composable routing + factory. | + Optional `vm: WarningCenterViewModel? = null` param; factory wires persistence (read initial + callback). |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` | Mounts WarningCenter ×2 + SettingsSheet. | Hoist shared VM via factory; pass to both mounts; expose `snoozedForever` + `clearSnoozes` to SettingsSheet. |
| `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` | Settings panel UI. | + 2 params (`snoozedCount`, `onResetSnoozes`); + `ResetSnoozesRow` composable rendered when callback non-null. |
| `docs/adr/0014-snooze-persistence.md` | ADR for the persistence canon. | New file. |
| `docs/WarningCenterContract.md` | Contract doc. | Amend §5 "Snooze contract" paragraph to point at ADR-0014. |

Total: 6 src files modified/created, 2 test files modified, 2 docs touched.

---

## Pre-flight (one time)

- [ ] **Step P1: Verify branch + clean tree**

Run:
```bash
git status
git rev-parse --abbrev-ref HEAD
```

Expected: branch `phase4-1c-snooze-persistence`; spec file already committed; otherwise clean.

- [ ] **Step P2: Confirm baseline tests pass**

Dispatch the gradle subagent (per project convention `gradle subagent-routed`) with:
> "Run `./gradlew :app:testDebugUnitTest --tests 'com.aritr.rova.data.RovaSettingsTest' --tests 'com.aritr.rova.ui.warnings.WarningCenterAggregateTest' --tests 'com.aritr.rova.ui.theme.RovaWarningsV3Test'`. Report total + failure count only."

Expected: all green. Capture baseline counts before changes.

---

## Task 1: `RovaSettings.snoozedWarningIds` getter/setter

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt`
- Test: `app/src/test/java/com/aritr/rova/data/RovaSettingsTest.kt`

The runtime prefs file already exists (added in Phase 4 patch chain for `mode`); the `FakeSharedPreferences` test stub already implements `getStringSet`/`putStringSet`. No fixture changes required.

- [ ] **Step 1.1: Write failing tests (3)**

Append at the end of `RovaSettingsTest.kt`, just before the `// ─── Helpers ──────` section divider on line 266 (after the last existing UI-pending round-trip block and before `private fun settings(...)`):

```kotlin
    // ─── snoozedWarningIds (Phase 4.1c) ───────────────────────────

    @Test fun `snoozedWarningIds default is empty set`() {
        assertEquals(emptySet<String>(), settings().snoozedWarningIds)
    }

    @Test fun `snoozedWarningIds round-trips a 2-id set`() {
        val s = settings()
        s.snoozedWarningIds = setOf("NOTIFICATIONS_DENIED", "BATTERY_OPTIMIZATION_ON")
        assertEquals(
            setOf("NOTIFICATIONS_DENIED", "BATTERY_OPTIMIZATION_ON"),
            s.snoozedWarningIds,
        )
    }

    @Test fun `snoozedWarningIds setter replaces, does not merge`() {
        val s = settings()
        s.snoozedWarningIds = setOf("A", "B")
        s.snoozedWarningIds = setOf("C")
        assertEquals(setOf("C"), s.snoozedWarningIds)
    }
```

- [ ] **Step 1.2: Verify the new tests fail**

Dispatch the gradle subagent:
> "Run `./gradlew :app:testDebugUnitTest --tests 'com.aritr.rova.data.RovaSettingsTest.snoozedWarningIds*'`. Expect 3 failures (`snoozedWarningIds` unresolved reference)."

Expected: 3 FAIL, all with `unresolved reference: snoozedWarningIds` or equivalent compile-time error reported as test build failure.

- [ ] **Step 1.3: Add the property to `RovaSettings.kt`**

In `app/src/main/java/com/aritr/rova/data/RovaSettings.kt`, immediately after the `mode` property (which ends at the line `set(value) = runtimePrefs.edit { putString("mode", value) }` — currently line 66) and before the `loopCount` property, insert:

```kotlin

    /**
     * Phase 4.1c — persistent "Don't show again" set, keyed by [com.aritr.rova.ui.warnings.WarningId.name].
     * Backed by [RUNTIME_PREFS_NAME] so reinstall resets the choice (same policy
     * `mode` follows; see backup_rules.xml + data_extraction_rules.xml).
     * Spec: docs/superpowers/specs/2026-05-24-phase-4-1c-snooze-persistence-design.md §4.1
     */
    var snoozedWarningIds: Set<String>
        get() = runtimePrefs.getStringSet("snoozed_warning_ids", emptySet()) ?: emptySet()
        set(value) = runtimePrefs.edit { putStringSet("snoozed_warning_ids", value) }
```

- [ ] **Step 1.4: Verify tests pass**

Dispatch gradle subagent:
> "Run `./gradlew :app:testDebugUnitTest --tests 'com.aritr.rova.data.RovaSettingsTest'`. Report PASS/FAIL count for whole class."

Expected: all `RovaSettingsTest` tests PASS (existing 30 + 3 new = 33 or whatever the existing total + 3 is). Zero failures.

- [ ] **Step 1.5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/RovaSettings.kt \
        app/src/test/java/com/aritr/rova/data/RovaSettingsTest.kt
git commit -m "$(cat <<'EOF'
feat(settings): persist snoozedWarningIds in runtime prefs (4.1c T1)

Adds Set<String> getter/setter backed by rova_runtime_prefs (same
backup-excluded file as `mode`). Keys are WarningId.name strings.
Spec §4.1 / §4.2.
EOF
)"
```

---

## Task 2: `WarningCenterViewModel` ctor params + `clearSnoozes()`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt`

The existing constructor has 10 source-flow params + `scope: CoroutineScope?`. We add two more optional params with defaults so every existing call site (factory in `WarningCenter.kt`, test `makeVm()`) compiles unchanged.

- [ ] **Step 2.1: Write failing tests (3)**

Append at the end of `WarningCenterAggregateTest.kt` (after the `snoozeForever_hides_id_from_activeWarning` test, before the closing `}` of the class on line 178):

```kotlin

    // ──────────────────────────────────────────────────────────────────
    // Phase 4.1c — initialSnoozedIds seed + onSnoozeChanged callback +
    //              clearSnoozes mutator
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun vm_seeded_with_initialSnoozedIds_filters_those_ids_from_activeWarning() {
        // Drive NOTIFICATIONS_DENIED into _resolvedWarning (notificationsGranted = false),
        // and seed snoozedForever with the same id. activeWarning must be null.
        val s = sources()
        s.nt.value = false
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            scope = CoroutineScope(Dispatchers.Unconfined),
            initialSnoozedIds = setOf(WarningId.NOTIFICATIONS_DENIED),
        )
        assertNull(vm.activeWarning.value)
        assertTrue(WarningId.NOTIFICATIONS_DENIED in vm.snoozedForever.value)
    }

    @Test
    fun clearSnoozes_empties_the_snoozedForever_flow_and_invokes_callback() {
        val s = sources()
        s.nt.value = false
        val received = mutableListOf<Set<WarningId>>()
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            scope = CoroutineScope(Dispatchers.Unconfined),
            initialSnoozedIds = setOf(WarningId.NOTIFICATIONS_DENIED),
            onSnoozeChanged = { received += it },
        )
        vm.clearSnoozes()
        assertEquals(emptySet<WarningId>(), vm.snoozedForever.value)
        assertEquals(listOf(emptySet<WarningId>()), received)
    }

    @Test
    fun snoozeForever_invokes_callback_with_updated_set() {
        val s = sources()
        val received = mutableListOf<Set<WarningId>>()
        val vm = WarningCenterViewModel(
            cameraPermissionGranted = s.cameraPerm,
            exactAlarmGranted = s.ea,
            storageInsufficient = s.storage,
            thermal = s.th,
            power = s.pw,
            camera = s.camState,
            microphonePermissionGranted = s.mic,
            notificationsGranted = s.nt,
            batteryOptimizationExempt = s.bo,
            storageLowMidRec = s.storageLowMidRec,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onSnoozeChanged = { received += it },
        )
        vm.snoozeForever(WarningId.NOTIFICATIONS_DENIED)
        assertEquals(listOf(setOf(WarningId.NOTIFICATIONS_DENIED)), received)
    }
```

- [ ] **Step 2.2: Verify the new tests fail**

Dispatch gradle subagent:
> "Run `./gradlew :app:testDebugUnitTest --tests 'com.aritr.rova.ui.warnings.WarningCenterAggregateTest'`. Expect compile errors on the 3 new tests (`initialSnoozedIds` / `onSnoozeChanged` / `clearSnoozes` unresolved)."

Expected: 3 compile-time test failures naming `initialSnoozedIds`, `onSnoozeChanged`, `clearSnoozes`.

- [ ] **Step 2.3: Edit `WarningCenterViewModel.kt` — add ctor params**

Replace the existing constructor block (currently lines 32-49) with:

```kotlin
class WarningCenterViewModel(
    cameraPermissionGranted: StateFlow<Boolean>,
    exactAlarmGranted: StateFlow<Boolean>,
    storageInsufficient: StateFlow<Boolean>,
    thermal: StateFlow<ThermalStatus>,
    power: StateFlow<PowerState>,
    camera: StateFlow<CameraSignalState>,
    microphonePermissionGranted: StateFlow<Boolean>,
    notificationsGranted: StateFlow<Boolean>,
    batteryOptimizationExempt: StateFlow<Boolean>,
    storageLowMidRec: StateFlow<Boolean>,           // ← NEW (R2 T5)
    // v3 — injectable scope so plain-JVM unit tests can pass
    // `Dispatchers.Unconfined`-backed CoroutineScope and avoid the
    // `Dispatchers.Main` requirement of `viewModelScope`. Production
    // call-sites construct the VM via `viewModel(factory = ...)` and
    // omit this argument so `viewModelScope` is used as before.
    private val scope: CoroutineScope? = null,
    // Phase 4.1c — initial snooze set + on-mutation callback. Defaults
    // preserve every pre-4.1c call site (in-memory only behaviour).
    // The factory in WarningCenter.kt supplies real values that
    // round-trip through RovaSettings.snoozedWarningIds.
    initialSnoozedIds: Set<WarningId> = emptySet(),
    private val onSnoozeChanged: ((Set<WarningId>) -> Unit)? = null,
) : ViewModel() {
```

- [ ] **Step 2.4: Edit `WarningCenterViewModel.kt` — seed snooze flow + add mutators**

Replace the existing `_snoozedForever` block (currently lines 61-67) with:

```kotlin
    // ── v3 + 4.1c — "Don't show again" snooze (persisted via factory callback) ──
    private val _snoozedForever = MutableStateFlow(initialSnoozedIds)
    val snoozedForever: StateFlow<Set<WarningId>> = _snoozedForever.asStateFlow()

    fun snoozeForever(id: WarningId) {
        _snoozedForever.update { it + id }
        onSnoozeChanged?.invoke(_snoozedForever.value)
    }

    /**
     * Phase 4.1c — clear the entire snooze set. Early-returns when already
     * empty so the Settings reset row is idempotent (no redundant disk
     * write when the user taps reset on an already-empty set).
     */
    fun clearSnoozes() {
        if (_snoozedForever.value.isEmpty()) return
        _snoozedForever.value = emptySet()
        onSnoozeChanged?.invoke(emptySet())
    }
```

- [ ] **Step 2.5: Verify tests pass**

Dispatch gradle subagent:
> "Run `./gradlew :app:testDebugUnitTest --tests 'com.aritr.rova.ui.warnings.WarningCenterAggregateTest'`. Report PASS/FAIL count."

Expected: all `WarningCenterAggregateTest` tests PASS (existing 8 + 3 new = 11). Zero failures.

- [ ] **Step 2.6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt
git commit -m "$(cat <<'EOF'
feat(warnings): VM ctor params + clearSnoozes mutator (4.1c T2)

WarningCenterViewModel gains two optional ctor params (initialSnoozedIds
+ onSnoozeChanged callback) with defaults that preserve every existing
call site. clearSnoozes() short-circuits on empty to avoid redundant
disk writes. Spec §4.3.
EOF
)"
```

---

## Task 3: `WarningCenter.kt` — optional VM param + factory wires persistence

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`

We add `vm: WarningCenterViewModel? = null` to the `WarningCenter` composable signature. When the caller passes one, we use it (the `RecordScreen` hoist in Task 4 supplies the shared instance). When null, the existing factory path runs — but now reads/writes through `RovaSettings`. No tests for this task: it is pure composable plumbing, and the `RecordScreen` device verify in Task 4 exercises both code paths.

- [ ] **Step 3.1: Add imports**

In `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`, add to the existing import block (alphabetical order):

```kotlin
import com.aritr.rova.data.RovaSettings
```

- [ ] **Step 3.2: Update the `WarningCenter` composable signature + factory body**

Replace the entire `@Composable fun WarningCenter(...)` block (currently lines 33-60, ending with the `}` that closes the `factory = viewModelFactory { ... }`) with:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarningCenter(
    hudState: RecordHudState,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
    // Phase 4.1c — optional shared VM. RecordScreen hoists one instance and
    // passes it to both WarningCenter mounts (idle + active HUD) so the same
    // snoozedForever flow drives both surfaces + the Settings reset row.
    // When null (legacy callers, previews), the factory below constructs one
    // wired straight to RovaSettings.
    vm: WarningCenterViewModel? = null,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as? RovaApp } ?: return
    val resolvedVm: WarningCenterViewModel = vm ?: viewModel(
        factory = viewModelFactory {
            initializer { buildWarningCenterViewModel(app) }
        }
    )
    val active by resolvedVm.activeWarning.collectAsStateWithLifecycle()
```

Then in the same block, replace every remaining reference to `vm.` (in the rest of the composable body — `vm.dismissedWarnings`, `vm.restore(id)`, `vm.expandedWhy`, `vm.dismiss(id)`, `vm.snoozeForever(id)`, `vm.toggleExpandWhy(id)`) with `resolvedVm.` — all 6 call sites currently between lines 68 and 99 in the unmodified file.

- [ ] **Step 3.3: Add the factory helper at file scope**

Append at the very end of `WarningCenter.kt` (after the closing `}` of `launchActionTarget`, currently line 135):

```kotlin

/**
 * Phase 4.1c — single source of truth for constructing a [WarningCenterViewModel]
 * wired to live signals on [RovaApp] AND to the persistent snooze set on
 * [RovaSettings]. Called by both:
 *   - the factory inside [WarningCenter] (when no `vm` is hoisted), and
 *   - the factory inside `RecordScreen` (when one shared VM drives both
 *     WarningCenter mounts + the SettingsSheet reset row).
 *
 * `runCatching { WarningId.valueOf(it) }.getOrNull()` swallows any stale
 * `WarningId.name` left over from a renamed/removed id; `mapNotNull` drops
 * it. The stored set self-heals to the trimmed value on the next write.
 */
internal fun buildWarningCenterViewModel(app: RovaApp): WarningCenterViewModel {
    val settings = RovaSettings(app)
    val initial: Set<WarningId> = settings.snoozedWarningIds
        .mapNotNull { runCatching { WarningId.valueOf(it) }.getOrNull() }
        .toSet()
    return WarningCenterViewModel(
        cameraPermissionGranted = app.cameraPermissionSignal.state,
        exactAlarmGranted = app.exactAlarmSignal.state,
        storageInsufficient = app.storageSignal.insufficientToStart,
        thermal = app.thermalStatusSignal.state,
        power = app.powerSignal.state,
        camera = app.cameraStateSignal.state,
        microphonePermissionGranted = app.microphonePermissionSignal.state,
        notificationsGranted = app.notificationPermissionSignal.state,
        batteryOptimizationExempt = app.batteryOptimizationSignal.isExempt,
        storageLowMidRec = app.storageLowMidRecSignal.isLow,
        initialSnoozedIds = initial,
        onSnoozeChanged = { set ->
            settings.snoozedWarningIds = set.map(WarningId::name).toSet()
        },
    )
}
```

- [ ] **Step 3.4: Verify it compiles**

Dispatch gradle subagent:
> "Run `./gradlew :app:compileDebugKotlin`. Report errors only."

Expected: BUILD SUCCESSFUL, zero compile errors.

- [ ] **Step 3.5: Verify no test regressions**

Dispatch gradle subagent:
> "Run `./gradlew :app:testDebugUnitTest`. Report total tests + failure count."

Expected: all green; counts ≥ baseline + 6 new tests from T1 + T2.

- [ ] **Step 3.6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt
git commit -m "$(cat <<'EOF'
feat(warnings): WarningCenter factory wires snooze persistence (4.1c T3)

Adds optional `vm` param so RecordScreen can hoist one shared VM
across both mounts. Extracts buildWarningCenterViewModel(app) helper
that reads RovaSettings.snoozedWarningIds + supplies the round-trip
callback. Spec §4.4.
EOF
)"
```

---

## Task 4: `RecordScreen.kt` VM hoist + `SettingsSheet.kt` reset row

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt`

This task touches two files because the row UI in `SettingsSheet` is meaningless without the VM hoist in `RecordScreen`. Bundling avoids a half-shipped intermediate commit.

- [ ] **Step 4.1: Add imports to `RecordScreen.kt`**

In `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`, add to the existing import block (alphabetical order, alongside other `com.aritr.rova.ui.warnings.*` imports if present, otherwise wherever the existing `WarningCenter` import already is):

```kotlin
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aritr.rova.RovaApp
import com.aritr.rova.ui.warnings.WarningCenterViewModel
import com.aritr.rova.ui.warnings.buildWarningCenterViewModel
```

If any of these imports already exist (likely `WarningCenter` already drags in `viewModel` indirectly — but it lives in a separate file, so check), skip the duplicates.

- [ ] **Step 4.2: Hoist the shared `WarningCenterViewModel` in `RecordScreen`**

In `RecordScreen.kt`, find the composable that contains both `WarningCenter(...)` call sites (currently at lines 542 + 584) and the `SettingsSheet(...)` call (currently at line 703) — the surrounding composable function holds them all. Immediately after the existing `val app = ...` line if there is one, OR at the top of the composable body just after `LocalContext.current` is resolved, insert:

```kotlin
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
```

Notes for the implementer:
- `context` is the existing `LocalContext.current` reference already in the composable. If the scope shadows the symbol, alias it.
- The `key = "WarningCenterViewModel"` makes the hoisted VM share identity with the one `WarningCenter` would have created via its own internal factory call (since both resolve through the same `LocalViewModelStoreOwner`). Without an explicit key, multiple `viewModel(...)` calls in the same owner with different `factory` lambdas could create separate instances.
- `collectAsStateWithLifecycle` import is already present in this file (used by other VMs).

- [ ] **Step 4.3: Pass the shared VM to both `WarningCenter` mounts**

In `RecordScreen.kt`, replace the idle-branch `WarningCenter(...)` (currently lines 542-545):

```kotlin
                        WarningCenter(
                            hudState = RecordHudState.Idle,
                            onStopRecording = {},
                        )
```

with:

```kotlin
                        WarningCenter(
                            hudState = RecordHudState.Idle,
                            onStopRecording = {},
                            vm = warningVm,
                        )
```

And replace the active-branch `WarningCenter(...)` (currently lines 584-587):

```kotlin
                            WarningCenter(
                                hudState = hudState,
                                onStopRecording = { viewModel.stopRecording() },
                            )
```

with:

```kotlin
                            WarningCenter(
                                hudState = hudState,
                                onStopRecording = { viewModel.stopRecording() },
                                vm = warningVm,
                            )
```

- [ ] **Step 4.4: Wire `SettingsSheet` to the shared VM**

In `RecordScreen.kt`, replace the existing `SettingsSheet(...)` call (currently lines 703-724) so the last two arguments before `onDismiss` are the new snooze params. The cleanest edit is to insert them between `onModePick = ...` and `onDismiss = ...`:

```kotlin
                SettingsSheet(
                    visible = combinedOpen,
                    durationSeconds = duration,
                    loopCount = loopCount,
                    intervalMinutes = interval,
                    quality = resolution,
                    currentMode = mode,
                    editable = !isUiLocked,
                    statusText = statusText,
                    flashMode = flashMode,
                    flipEnabled = !isUiLocked && mode != "PortraitLandscape",
                    onCycleFlash = { if (!isUiLocked) viewModel.setFlashMode((flashMode + 1) % 3) },
                    onFlip = {
                        if (!isUiLocked && mode != "PortraitLandscape") viewModel.flipCamera()
                    },
                    onDurationChange = { viewModel.duration.value = it },
                    onLoopCountChange = { viewModel.loopCount.value = it },
                    onIntervalChange = { viewModel.interval.value = it },
                    onQualityChange = { viewModel.resolution.value = it },
                    onModePick = { viewModel.setMode(it) },
                    snoozedCount = snoozedSet.size,
                    onResetSnoozes = if (snoozedSet.isNotEmpty()) {
                        { warningVm.clearSnoozes() }
                    } else null,
                    onDismiss = { viewModel.closeSettingsSheet() },
                )
```

- [ ] **Step 4.5: Add the two params + the row composable to `SettingsSheet.kt`**

In `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt`, update the public `SettingsSheet` signature (currently lines 62-81). Insert the two new params between `onModePick` and `onDismiss`:

```kotlin
@Composable
fun SettingsSheet(
    visible: Boolean,
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    currentMode: String,
    editable: Boolean,
    statusText: String,
    flashMode: Int,
    flipEnabled: Boolean,
    onCycleFlash: () -> Unit,
    onFlip: () -> Unit,
    onDurationChange: (Int) -> Unit,
    onLoopCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onQualityChange: (String) -> Unit,
    onModePick: (String) -> Unit,
    // Phase 4.1c — "Reset snoozed warnings" affordance. `onResetSnoozes` is
    // null when the persisted set is empty; the row is suppressed entirely
    // in that state so there is no dead-end "Reset (0)" affordance.
    snoozedCount: Int,
    onResetSnoozes: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
```

Thread the new params into the `SettingsPanel` call inside `SettingsSheet`. The current `SettingsPanel(...)` invocation (currently lines 101-119) passes:

```kotlin
            SettingsPanel(
                durationSeconds = durationSeconds,
                loopCount = loopCount,
                intervalMinutes = intervalMinutes,
                quality = quality,
                currentMode = currentMode,
                editable = editable,
                onDurationChange = onDurationChange,
                onLoopCountChange = onLoopCountChange,
                onIntervalChange = onIntervalChange,
                onQualityChange = onQualityChange,
                onModePick = onModePick,
                onDismiss = onDismiss,
                modifier = ...
            )
```

Replace with (insert `snoozedCount` + `onResetSnoozes` after `onModePick`):

```kotlin
            SettingsPanel(
                durationSeconds = durationSeconds,
                loopCount = loopCount,
                intervalMinutes = intervalMinutes,
                quality = quality,
                currentMode = currentMode,
                editable = editable,
                onDurationChange = onDurationChange,
                onLoopCountChange = onLoopCountChange,
                onIntervalChange = onIntervalChange,
                onQualityChange = onQualityChange,
                onModePick = onModePick,
                snoozedCount = snoozedCount,
                onResetSnoozes = onResetSnoozes,
                onDismiss = onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(top = SettingsSheetTokens.peekHeight),
            )
```

Then update the private `SettingsPanel` signature (currently lines 197-212) to accept them:

```kotlin
@Composable
private fun SettingsPanel(
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    currentMode: String,
    editable: Boolean,
    onDurationChange: (Int) -> Unit,
    onLoopCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onQualityChange: (String) -> Unit,
    onModePick: (String) -> Unit,
    snoozedCount: Int,
    onResetSnoozes: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

- [ ] **Step 4.6: Render the row inside the panel**

In the `SettingsPanel` body, find the `QualityRow(...)` call (currently line 299) and immediately after it, before the `Spacer(Modifier.weight(1f))` that pushes the CTA down (currently line 302), insert:

```kotlin
        if (onResetSnoozes != null) {
            SheetRowDivider()
            ResetSnoozesRow(count = snoozedCount, onClick = onResetSnoozes)
        }
```

- [ ] **Step 4.7: Add the `ResetSnoozesRow` composable**

Append at the very end of `SettingsSheet.kt` (after the closing `}` of `QualityChip`, currently around line 495):

```kotlin

/* ── Reset snoozed warnings (Phase 4.1c) ──────────────────────────────── */

/**
 * One-line clickable row that clears the persisted [RovaSettings.snoozedWarningIds]
 * set via [WarningCenterViewModel.clearSnoozes]. Surfaced only when the
 * caller passes a non-null `onResetSnoozes` (i.e. the set is non-empty), so
 * there is no dead-end "Reset (0)" affordance.
 *
 * Visually mirrors the existing settings rows: title on the left at
 * `sheetRowLabel` style, subtitle directly under the title with a muted
 * alpha, no value/stepper on the right.
 */
@Composable
private fun ResetSnoozesRow(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = SettingsSheetTokens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Reset snoozed warnings",
                style = RovaTokens.sheetRowLabel,
                color = SettingsSheetTokens.rowLabelText,
            )
            Text(
                if (count == 1) "1 warning hidden" else "$count warnings hidden",
                style = RovaTokens.sheetRowLabel,
                color = SettingsSheetTokens.rowLabelText.copy(alpha = 0.55f),
                fontSize = 11.sp,
            )
        }
    }
}
```

Note for the implementer: `Column`, `Text`, `Modifier`, `RovaTokens`, `SettingsSheetTokens`, `clickable`, `Row`, `Alignment`, `dp`, `sp`, `fillMaxWidth`, `padding`, `weight`, `verticalAlignment`, `Arrangement` are all already imported in this file (search the file header to confirm). If `Column` is missing, add `import androidx.compose.foundation.layout.Column`.

- [ ] **Step 4.8: Verify it compiles**

Dispatch gradle subagent:
> "Run `./gradlew :app:assembleDebug`. Report errors + warnings count."

Expected: BUILD SUCCESSFUL. Warnings count ≤ baseline (50W+1H+0E) + 0; if any new W lands, it must be incidental (unused import) and noted but not blocking.

- [ ] **Step 4.9: Verify no test regressions**

Dispatch gradle subagent:
> "Run `./gradlew :app:testDebugUnitTest`. Report total + failures."

Expected: all green; total ≥ baseline + 6 new tests (T1 + T2). Zero failures.

- [ ] **Step 4.10: Device smoke (owner-driven)**

Hand to owner with the smoke script:

```
SMOKE — Phase 4.1c snooze persistence

PRECONDITION
1. Install fresh APK (uninstall first to reset runtime_prefs).
2. Launch Rova → Record screen.

A. Snooze persists across cold start
1. Disable Notifications via system settings (Settings → Apps → Rova → Notifications → off).
2. Return to Rova. The "Stay in the Loop" advisory sheet should appear.
3. Tap overflow → "Don't show again".
4. Sheet collapses. Verify no chip appears (snoozed surfaces clear, not collapse).
5. Force-stop Rova (Settings → Apps → Rova → Force stop).
6. Relaunch Rova. The notifications sheet must NOT appear.

B. Reset row appears with count
1. Open Settings sheet (tap settings card).
2. Scroll under Quality. A "Reset snoozed warnings" row with subtitle "1 warning hidden" must be visible.

C. Reset row clears the snooze
1. Tap "Reset snoozed warnings".
2. The row disappears.
3. Dismiss the Settings sheet.
4. The "Stay in the Loop" sheet reappears immediately at idle.

D. Reset row hidden when set is empty
1. From the post-reset state (no snoozes), open Settings sheet.
2. The "Reset snoozed warnings" row must NOT appear.

E. Reset row survives mode switch
1. Snooze the notifications warning again.
2. Switch mode to Landscape via the mode tabs.
3. Reset row stays "1 warning hidden".

EXPECTED RESULT: all 5 paths pass.
```

Owner reports back. If smoke fails, return to step 4.2 with the failure.

- [ ] **Step 4.11: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt \
        app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt
git commit -m "$(cat <<'EOF'
feat(record): hoist warning VM + Settings reset-snoozes row (4.1c T4)

RecordScreen hoists one WarningCenterViewModel via factory + threads
it into both WarningCenter mounts. SettingsSheet gains snoozedCount +
onResetSnoozes params and a ResetSnoozesRow rendered only when the set
is non-empty. Spec §4.4 / §4.5.
EOF
)"
```

---

## Task 5: ADR-0014 + WarningCenterContract.md §5 amendment

**Files:**
- Create: `docs/adr/0014-snooze-persistence.md`
- Modify: `docs/WarningCenterContract.md`

- [ ] **Step 5.1: Write ADR-0014**

Create `docs/adr/0014-snooze-persistence.md` with the following content:

```markdown
# ADR-0014 — Snooze persistence (Phase 4.1c)

**Status:** Accepted (2026-05-24)
**Phase:** 4.1c
**Supersedes:** WarningCenterContract.md §5 "Snooze contract" paragraph (the
"not persisted across process restarts; the user gets the banner back on cold
start, by design" clause).
**Related ADRs:** ADR-0007 (warning sheet model), ADR-0013 (warning re-skin
v3 chrome canon).

## Context

`WarningCenterViewModel._snoozedForever: MutableStateFlow<Set<WarningId>>`
shipped in Phase 4 v3 as in-memory only. WarningCenterContract.md §5 was
explicit about this being the design intent at the time — silencing a
battery banner that survives a phone reboot would be wrong. After ship, the
"Don't show again" affordance proved confusing for advisory-tier warnings
that the user genuinely wanted to silence durably (NOTIFICATIONS_DENIED,
BATTERY_OPTIMIZATION_ON): every cold start re-presented the sheet, so the
button read as a no-op.

## Decision

Persist `snoozedForever` for the lifetime of an install. Snoozes survive:

- normal cold start
- system reclaim (`onTrimMemory`)
- force-stop via Settings → Apps
- device reboot
- in-place APK update

Snoozes do NOT survive:

- uninstall + reinstall (the `rova_runtime_prefs.xml` file is
  `<exclude>`d from both `cloud-backup` and `device-transfer` in
  `data_extraction_rules.xml` + `backup_rules.xml`)
- explicit reset via the new Settings → "Reset snoozed warnings" row

## Implementation

- Storage: `RovaSettings.snoozedWarningIds: Set<String>` on the existing
  backup-excluded `rova_runtime_prefs` file under key `snoozed_warning_ids`.
  Values are `WarningId.name` strings.
- VM seam: `WarningCenterViewModel` ctor gains `initialSnoozedIds: Set<WarningId>`
  + `onSnoozeChanged: ((Set<WarningId>) -> Unit)?` with defaults that
  preserve in-memory-only behaviour for legacy callers + tests.
- Factory: `buildWarningCenterViewModel(app)` in `WarningCenter.kt` reads
  the persisted set + supplies the round-trip callback. Stale `WarningId.name`
  strings from renamed/removed ids self-heal via `runCatching { valueOf(it) }.getOrNull()`
  on the next write.
- Reset UX: `SettingsSheet` gains a "Reset snoozed warnings" row, gated
  on a non-empty set. Subtitle shows the count.

## Consequences

- **Behavioural:** "Don't show again" now means "until reset or reinstall".
  The Contract amendment in §5 reflects this.
- **No new schema:** the persisted format is a `Set<String>` keyed by
  `WarningId.name`. Future enum renames must keep the old `name` alive
  (or accept a one-time silent self-heal on the next mutator call).
- **No TTL / auto-expire:** considered + rejected (spec §10). Adding
  per-id timestamps would require a `Map<WarningId, Long>` and a clock
  dependency for tests. The explicit reset row is judged sufficient.

## Rejected alternatives

- **Backup-included variant** — would let snoozes carry through
  device-to-device transfer. Rejected: matches the `mode` policy already
  set in ADR-0009 (runtime state stays local; user-config keys back up).
- **Per-id snooze granularity in Settings** — would be a sub-screen with
  one toggle per snoozed id. Out of scope; the reset row clears all in
  one tap, which is the recovery-path UX. Granular unsnooze comes back
  as a future slice if there is demand.
- **Per-id confirmation dialog on reset** — low-stakes single-tap action
  with no destructive consequence (worst case: the user sees a sheet they
  already dismissed once). Consistent with other Settings rows.

## References

- Spec: `docs/superpowers/specs/2026-05-24-phase-4-1c-snooze-persistence-design.md`
- Plan: `docs/superpowers/plans/2026-05-24-phase-4-1c-snooze-persistence.md`
- ADR-0007 (warning sheet model)
- ADR-0013 (warning re-skin v3 chrome canon)
- `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`
```

- [ ] **Step 5.2: Amend `WarningCenterContract.md` §5**

In `docs/WarningCenterContract.md`, find the existing §5 snooze paragraph (currently around line 182):

```
**Snooze contract.** Soft / Advisory dismissals that are 24 h-snoozed write a per-warning timestamp into a new in-memory store (Phase 4.1 owns this — **not** persisted across process restarts; the user gets the banner back on cold start, by design — silencing a battery banner that survives a phone reboot would be wrong).
```

Replace with:

```
**Snooze contract.** *Superseded by ADR-0014 (Phase 4.1c, 2026-05-24).* "Don't
show again" snoozes are now persisted across cold start, system reclaim,
force-stop, device reboot, and in-place APK update. They reset on uninstall
+ reinstall (the backing file `rova_runtime_prefs.xml` is `<exclude>`d from
Android Auto Backup) and on explicit reset via Settings → "Reset snoozed
warnings". The original "in-memory only" intent stated here was the Phase
4.1 / 4.1b shipping decision; the Phase 4.1c implementation durably honors
the user's choice. The 24-h TTL clause is also rescinded: snoozes are
forever until reset.
```

- [ ] **Step 5.3: Commit**

```bash
git add docs/adr/0014-snooze-persistence.md docs/WarningCenterContract.md
git commit -m "$(cat <<'EOF'
docs(adr): ADR-0014 snooze persistence + WarningCenterContract §5 amendment

Records the Phase 4.1c decision to persist snoozedForever via the
backup-excluded rova_runtime_prefs file + the explicit Settings reset
row. Supersedes the original "in-memory only / 24h TTL" snooze contract
from Phases 4.1 / 4.1b.
EOF
)"
```

---

## Task 6: Push + open PR

- [ ] **Step 6.1: Confirm branch state**

```bash
git log --oneline master..HEAD
```

Expected: 5 feature commits (T1, T2, T3, T4, T5) + the pre-existing spec commit. Total 6 commits.

- [ ] **Step 6.2: Push the branch**

```bash
git push -u origin phase4-1c-snooze-persistence
```

- [ ] **Step 6.3: Open the PR**

```bash
gh pr create --title "Phase 4.1c — persist snoozedForever + Settings reset row" --body "$(cat <<'EOF'
## Summary
- `RovaSettings.snoozedWarningIds` backed by the existing backup-excluded `rova_runtime_prefs` file.
- `WarningCenterViewModel` gains optional `initialSnoozedIds` + `onSnoozeChanged` ctor params with defaults that preserve every pre-4.1c call site; adds `clearSnoozes()` mutator with early-return-on-empty.
- `WarningCenter` composable accepts an optional shared VM; factory helper `buildWarningCenterViewModel(app)` reads/writes through `RovaSettings`.
- `RecordScreen` hoists one VM instance + threads it through both `WarningCenter` mounts and `SettingsSheet`'s new "Reset snoozed warnings" row (rendered only when the set is non-empty).
- ADR-0014 documents the persistence canon; `WarningCenterContract.md` §5 amended to point at it.

## Test plan
- [x] `RovaSettingsTest` — 3 new tests (default empty / round-trip / setter replaces).
- [x] `WarningCenterAggregateTest` — 3 new tests (seed filters / clearSnoozes empties + callback / snoozeForever callback).
- [x] Owner device smoke per [docs/superpowers/plans/2026-05-24-phase-4-1c-snooze-persistence.md](docs/superpowers/plans/2026-05-24-phase-4-1c-snooze-persistence.md) Task 4.10 (paths A-E).
- [x] `:app:assembleDebug` clean.

## Notes
- Backup-excluded: snooze resets on uninstall + reinstall (matches the `mode` policy from ADR-0009).
- No TTL / auto-expire: snoozes are forever until reset (spec §10).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 6.4: Return PR URL to owner**

Capture the PR URL from the `gh pr create` output. Hand to owner; do NOT merge (per project constraint `gh pr merge owner-only`).

---

## Rollback

If any task lands a regression that cannot be fixed forward in the same PR:

1. Owner closes the PR.
2. Local: `git checkout master && git branch -D phase4-1c-snooze-persistence`.
3. No production data is touched until Task 4 (the VM hoist). Tasks 1-3 are additive surface-only — reverting only those is a clean op.

The persisted `snoozedWarningIds` key, once written, will be ignored by pre-4.1c code paths (they never read it). A rollback after Task 4 ships leaves the key on disk; it gets garbage-collected on uninstall.

---

## Self-review (run before handing off)

### Spec coverage

| Spec section | Covered by |
|---|---|
| §4.1 Storage | Task 1 (property + 3 tests) |
| §4.2 Surface | Task 1 (`snoozedWarningIds` getter/setter shape) |
| §4.3 ViewModel | Task 2 (ctor + mutators + 3 tests) |
| §4.4 Factory wiring | Task 3 (`buildWarningCenterViewModel` + optional `vm` param) |
| §4.5 Settings UI | Task 4 (`ResetSnoozesRow` + RecordScreen hoist) |
| §5 Data flow | Verified by Task 4.10 smoke paths A + C |
| §6 Error handling — null `getStringSet` | Task 1 (`?: emptySet()` in getter) |
| §6 Error handling — stale `WarningId.name` | Task 3 (`runCatching { valueOf(it) }.getOrNull()` in factory) |
| §6 Error handling — empty `clearSnoozes` | Task 2 (early-return + covered by Task 2 callback test count) |
| §6 Error handling — backup restore | Task 5 (ADR-0014 documents the `<exclude>` rule already in place) |
| §7 Testing — 3 RovaSettings tests | Task 1.1 |
| §7 Testing — 3 aggregate tests | Task 2.1 |
| §8 Slice plan T1-T5 | Tasks 1-5 (1-to-1, plus Task 6 = PR push) |
| §9 Migration risk | Task 4.10 smoke covers fresh-install + upgrade paths; rollback section above |

### Placeholder scan

Searched for: "TBD", "TODO", "implement later", "fill in details", "add appropriate", "similar to Task". None present.

### Type consistency

- `Set<String>` (RovaSettings) ↔ `Set<WarningId>` (VM) — bridge is `WarningId.name` + `WarningId.valueOf` in `buildWarningCenterViewModel` (Task 3).
- `initialSnoozedIds: Set<WarningId>` (Task 2) matches the factory's `initial` local (Task 3).
- `onSnoozeChanged: ((Set<WarningId>) -> Unit)?` (Task 2) matches the factory's lambda signature (Task 3) which calls `set.map(WarningId::name).toSet()` to convert to disk format.
- `snoozedCount: Int` (Task 4 SettingsSheet) derives from `snoozedSet.size` (Task 4 RecordScreen).
- `onResetSnoozes: (() -> Unit)?` (Task 4 SettingsSheet) is the nullable callback; RecordScreen passes `if (snoozedSet.isNotEmpty()) { { warningVm.clearSnoozes() } } else null`.
- `clearSnoozes()` name consistent across Task 2 (definition), Task 4 (call site), Task 5 (ADR reference).

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-24-phase-4-1c-snooze-persistence.md`. Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.

Which approach?
