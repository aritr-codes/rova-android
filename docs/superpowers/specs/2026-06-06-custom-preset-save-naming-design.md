# Custom Preset Save / Naming — Design

**Date:** 2026-06-06
**Status:** Approved (owner sign-off 2026-06-06)
**Builds on:** PR #93 (presets relocated into Record settings sheet, ADR-0026)

## Goal

Let users save the current recording configuration as a **named custom preset**, apply it with one tap, see it reflected as the active chip, and delete it — all inside the PRESETS section of the Record settings sheet. Orientation/mode stays orthogonal (ADR-0026).

## Background — what already exists

The backend and most of the data layer are already in place:

- `RovaPreset(name, duration, interval, loopCount, resolution, id, isBuiltIn)` — `data/RovaSettings.kt`. Built-ins use `builtin.*` ids; customs use `custom.*` (assigned by `PresetJson.ensureCustomId`). `isBuiltIn` is runtime-only, never persisted.
- `PresetJson.encode/decode` — `data/PresetJson.kt`. v2 envelope `{presetSchemaVersion:2, presets:[...]}`, tolerant of v1 bare arrays, skips individual malformed objects.
- `RovaSettings.customPresetsJson` — SharedPreferences key `custom_presets`, default `"[]"`.
- `BuiltInPresets.all` (exactly 4: Quick Sample / Standard=default / Long Session / Continuous), `BuiltInPresets.default` (`builtin.standard`).
- `PresetMatcher.match(d, i, l, r): String?` — pure value-match over **built-ins only**, canonicalizes resolution, returns id or null ("Custom").
- `RecordViewModel`: `customPresets`, `allPresets` (= `BuiltInPresets.all + customs`), `activePresetId` (= `PresetMatcher.match(...)`, **built-in only today**), `savePreset(name)`, `deletePreset(preset)`, `applyPreset(preset)` (mid-session-guarded per ADR-0026).
- `SettingsSheet.kt`: `PresetSection` (FlowRow of `PresetSheetChip`), `PresetSheetChip` (selected/disabled/48dp/`presetSpokenDescription` contentDescription), threaded `presets`/`activePresetId`/`onApplyPreset`.

This slice is therefore **UI + small VM/matcher wiring + 2 pure helpers + tests** — not new backend.

## Decisions (owner-confirmed)

1. **Save affordance** — a trailing `+ Save` chip in the PresetSection FlowRow, shown **only when `activePresetId == null` (config matches no preset = genuinely Custom) and the sheet is editable**. Disappears once the config matches a preset.
2. **Delete** — long-press a custom chip → confirm dialog. Built-in chips ignore long-press.
3. **Name collision** — block the save with an inline liveRegion error; OK disabled until the name is valid.
4. **Active reflection for customs** — yes. An applied custom shows the selected (✓) state, with built-in value-match taking precedence over custom value-match.

## Architecture

### A. Active-preset reflection (PresetMatcher + RecordViewModel)

Extend matching so customs can be the active chip, keeping built-in precedence.

`PresetMatcher` (pure, `data/PresetMatcher.kt`):

```kotlin
object PresetMatcher {
    // Existing public API — unchanged behavior, now delegates.
    fun match(duration: Int, interval: Int, loopCount: Int, resolution: String?): String? =
        match(BuiltInPresets.all, duration, interval, loopCount, resolution)

    // New: first value-match id within an arbitrary preset list (canonicalized resolution).
    fun match(
        presets: List<RovaPreset>,
        duration: Int, interval: Int, loopCount: Int, resolution: String?,
    ): String? {
        val res = QualityPresets.canonicalize(resolution) ?: return null
        return presets.firstOrNull { p ->
            p.duration == duration && p.interval == interval && p.loopCount == loopCount &&
                QualityPresets.canonicalizeOrDefault(p.resolution) == res
        }?.id
    }

    // New: built-in match takes precedence, else custom value-match.
    fun matchActive(
        customs: List<RovaPreset>,
        duration: Int, interval: Int, loopCount: Int, resolution: String?,
    ): String? =
        match(duration, interval, loopCount, resolution)
            ?: match(customs, duration, interval, loopCount, resolution)
}
```

`RecordViewModel.activePresetId` recomputed to depend on customs:

```kotlin
val activePresetId: StateFlow<String?> =
    combine(duration, interval, loopCount, resolution, _customPresets) { d, i, l, r, customs ->
        PresetMatcher.matchActive(customs, d, i, l, r)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        PresetMatcher.matchActive(_customPresets.value, duration.value, interval.value, loopCount.value, resolution.value),
    )
```

(`combine` 5-arg overload is available; recompute fires when the list flow **emits a new instance** — never mutate the list in place, always assign a fresh list.) Because `activePresetId` now depends on `_customPresets`, saving a custom (whose tuple matches the just-saved config) flips `activePresetId` to that custom's id → the `+ Save` chip auto-hides and the new chip shows selected.

**No stale-initial risk:** `_customPresets` is initialized synchronously in the VM (`MutableStateFlow(loadPresetsFromSettings())`, a SharedPreferences read), so the `stateIn` initial value already sees all customs — there is no transient window where a custom-matching config reports `null` and flashes the `+ Save` chip.

### B. Save validation (new pure helper)

`PresetSaveValidator` (pure, `data/PresetSaveValidator.kt`) — reused by dialog (live) and VM (defensive):

```kotlin
object PresetSaveValidator {
    const val MAX_NAME_LENGTH = 40

    sealed interface Result {
        data object Ok : Result
        data object Blank : Result
        data object DuplicateName : Result
        data object TooLong : Result
    }

    /** [existing] = current custom presets. Built-in names are also reserved. */
    fun validateName(rawName: String, existing: List<RovaPreset>): Result {
        val name = rawName.trim()
        if (name.isEmpty()) return Result.Blank
        if (name.length > MAX_NAME_LENGTH) return Result.TooLong
        val taken = (BuiltInPresets.all + existing).any { it.name.equals(name, ignoreCase = true) }
        if (taken) return Result.DuplicateName
        return Result.Ok
    }
}
```

`RecordViewModel.savePreset(name)` adds defensive guards before persisting:

```kotlin
fun savePreset(name: String) {
    // Mid-session guard — same as applyPreset. UI gates this behind `editable`, but a
    // stale dialog, a future caller, or a test could still reach it. (codex review)
    val s = _serviceState.value
    if (s.isPeriodicActive || s.isMerging) return
    val trimmed = name.trim()
    val d = duration.value; val i = interval.value; val l = loopCount.value; val r = resolution.value
    // Resolution must be canonicalizable, else the saved custom could never match active. (codex review)
    if (QualityPresets.canonicalize(r) == null) return
    val current = _customPresets.value
    // Defensive: dialog already blocks these; guard the contract.
    if (PresetSaveValidator.validateName(trimmed, current) != PresetSaveValidator.Result.Ok) return
    // Tuple-duplicate guard: a config matching any existing preset is unreachable via the
    // conditional + Save chip (only shown when activePresetId == null), but guard anyway.
    if (PresetMatcher.matchActive(current, d, i, l, r) != null) return
    val updated = current + RovaPreset(name = trimmed, duration = d, interval = i, loopCount = l, resolution = r)
    _customPresets.value = updated
    persistPresets(updated)
}
```

`RecordViewModel.deletePreset(preset)` gains the same mid-session guard:

```kotlin
fun deletePreset(preset: RovaPreset) {
    val s = _serviceState.value
    if (s.isPeriodicActive || s.isMerging) return  // (codex review — VM-side guard, not only UI)
    val updated = _customPresets.value - preset
    _customPresets.value = updated
    persistPresets(updated)
}
```

**Race note (codex):** VM mutations are main-thread-confined (Compose UI callbacks), so the read-modify-write on `_customPresets` is safe; the naming dialog dismisses on confirm, preventing double-submit. If we ever move these off the main thread, switch to `_customPresets.update { current -> revalidate against current }`.

### C. UI (`SettingsSheet.kt`)

New params threaded `SettingsSheet` → `SettingsPanel` → `PresetSection`:

```kotlin
onSavePreset: (String) -> Unit,
onDeletePreset: (RovaPreset) -> Unit,
```

`PresetSection`:
- Custom chips: `combinedClickable(onClick = { onApply(preset) }, onClickLabel = ..., onLongClick = { requestDelete(preset) }, onLongClickLabel = <delete label>)`. The `onLongClickLabel` exposes the delete as a TalkBack/Switch/Voice **custom action** — the non-gesture-equivalent required by WCAG SC 2.5.1 / 2.1.1, so no separate ✕ is needed. Built-in chips keep plain `clickable` (no delete path). **Discoverability tradeoff (codex, accepted):** long-press is undiscoverable for *sighted touch* users; accepted because delete is rare, destructive, and confirmed — not a primary action. If usage shows users can't find it, revisit with a visible overflow/✕.
- `+ Save` chip rendered at the end of the FlowRow **only when `activePresetId == null && enabled`**. Styled like an unselected chip with a leading `Icons.Filled.Add`, `contentDescription` = "Save current settings as a preset". Tap → opens naming dialog.
- Save/delete actions are gated behind `editable` (the sheet is read-only mid-session; consistent with `applyPreset`'s mid-session guard).

Two dialogs hosted at `SettingsSheet` level with local `remember` state (`var namingVisible by remember…`, `var pendingDelete by remember<RovaPreset?>…`):

- **`PresetNameDialog`** — mirrors `RovaDialogs.CustomDurationDialog`:
  - `OutlinedTextField` with label, `singleLine`, capitalize-words.
  - Live `PresetSaveValidator.validateName(text, presets.filter { !it.isBuiltIn })`. (Built-in names are reserved inside the validator regardless of the filter.)
  - Error `Text` with `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`, `MaterialTheme.colorScheme.error`, message per result (`Blank` shows no error until OK pressed / `DuplicateName` / `TooLong`).
  - OK enabled only when `Result.Ok`; on confirm calls `onSavePreset(text.trim())`.
- **Delete-confirm** — mirrors `VaultScreen` AlertDialog: title "Delete preset?", body names the preset and states it can't be undone, error-tinted Delete (`colorScheme.error`), neutral Cancel; confirm calls `onDeletePreset(pendingDelete)`.

### D. RecordScreen wiring

Pass `onSavePreset = viewModel::savePreset`, `onDeletePreset = viewModel::deletePreset` into the existing `SettingsSheet(...)` call (it already passes `presets`/`activePresetId`/`onApplyPreset`).

### E. Strings

New keys in `res/values/strings.xml` and `res/values-es/strings.xml`:

| key | en | es |
|---|---|---|
| `preset_save_chip` | Save | Guardar |
| `preset_save_chip_cd` | Save current settings as a preset | Guardar la configuración actual como preajuste |
| `preset_name_dialog_title` | Name this preset | Nombra este preajuste |
| `preset_name_field_label` | Preset name | Nombre del preajuste |
| `preset_name_error_blank` | Enter a name | Escribe un nombre |
| `preset_name_error_duplicate` | You already have a preset with this name | Ya tienes un preajuste con este nombre |
| `preset_name_error_too_long` | Name is too long | El nombre es demasiado largo |
| `preset_delete_title` | Delete preset? | ¿Eliminar preajuste? |
| `preset_delete_body` | Delete "%1$s"? This can't be undone. | ¿Eliminar «%1$s»? No se puede deshacer. |
| `preset_delete_confirm` | Delete | Eliminar |
| `preset_chip_delete_action` | Delete preset | Eliminar preajuste |

(`dialog_ok` / `dialog_cancel` already exist and are reused.)

## ADR / gate

Amend **ADR-0026** with a clause documenting the custom save/naming UX:
- `+ Save` affordance is conditional on `activePresetId == null` (Custom state) and `editable`.
- Custom presets enforce **name uniqueness** (case-insensitive, built-in names reserved) and **tuple uniqueness** (a config matching any existing preset cannot be saved).
- `activePresetId` reflects customs via `matchActive`, **built-in match takes precedence**.
- Custom delete is long-press with an accessible TalkBack custom action (`onLongClickLabel`).

**No new `check*` gate.** The slice introduces no new statically-enforceable invariant beyond orientation-orthogonality, which `checkPresetNoOrientation` already enforces via the unchanged `RovaPreset` / `BuiltInPresets` shape. (New-invariant→gate rule does not trigger: name/tuple uniqueness and active-reflection are runtime behaviors covered by unit tests, not regex-scannable source invariants.)

## Testing (JVM pure only)

- `PresetMatcherTest` additions:
  - list-overload returns first value-match id; null on no match; canonicalizes resolution (`"1080P"` ↔ `FHD`).
  - `matchActive`: built-in precedence when a custom shares a built-in's tuple; custom fallback when only a custom matches; deterministic first-match for duplicate-tuple customs; null when nothing matches.
- `PresetSaveValidatorTest`:
  - `Blank` (empty / whitespace-only), `TooLong` (> 40), `DuplicateName` (case-insensitive vs custom), built-in name reserved, `Ok` for a fresh name.
- Compose dialogs / chips not unit-tested (project test policy: JVM-only).

Baseline expectation: all existing gates + full unit suite green via `assembleDebug` + `testDebugUnitTest`; verify on device (save → apply → reflect → long-press delete → confirm) as the owner step.

## Files

**Create**
- `app/src/main/java/com/aritr/rova/data/PresetSaveValidator.kt`
- `app/src/test/java/com/aritr/rova/data/PresetSaveValidatorTest.kt`

**Modify**
- `app/src/main/java/com/aritr/rova/data/PresetMatcher.kt` — list overload + `matchActive`
- `app/src/test/java/com/aritr/rova/data/PresetMatcherTest.kt` — additions
- `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt` — `activePresetId` rewrite + `savePreset` guard
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` — params, `+ Save` chip, long-press delete, `PresetNameDialog`, delete-confirm dialog
- `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` — wire `onSavePreset` / `onDeletePreset`
- `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml` — new keys
- `docs/adr/0026-preset-config-bundle-orientation-orthogonal.md` — amendment clause

## Sequencing

Recommend merging **PR #93 first** (device-smoke GO) and branching this off master, since it builds on the relocated PresetSection. If #93 is not yet merged, branch off `feat/mode-preset-seed`.
