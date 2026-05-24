# Phase 4.1c — Snooze Persistence (Design)

**Date:** 2026-05-24
**Status:** Approved (pending implementation plan)
**Phase parent:** Phase 4 warning surface (ADR-0007, ADR-0013)
**Mockup:** none (no new chrome — additive Settings row only)
**Related ADRs:** ADR-0007 (record warning sheets), ADR-0013 (warning re-skin v3 chrome canon)

---

## 1. Problem

`WarningCenterViewModel._snoozedForever: MutableStateFlow<Set<WarningId>>` is in-memory only. When the process dies (system reclaim, app force-stop, device reboot, or even normal cold start after the VM scope ends), the snooze set resets to empty and every advisory warning the user explicitly tapped "Don't show again" on resurfaces. The current behavior is documented in `docs/WarningCenterContract.md` §5.2 as "the user gets the banner back on cold start, by design" — that doc reflected the Phase 4.1 / 4.1b shipping decision; the design intent for 4.1c is to durably honor the user's choice.

Source: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt:62-66`.

## 2. Goal

Persist `snoozedForever` so a "Don't show again" snooze survives every cold start of the same install. Provide an explicit reset affordance so the choice is reversible without uninstalling.

## 3. Non-goals

- Persist `dismissedWarnings` (transient one-shot "Not now") or `expandedWhy` (transient UI state). Both stay in-memory.
- Survive uninstall + reinstall (snooze deliberately resets on a fresh APK install via the existing backup-excluded `rova_runtime_prefs` file — same reset policy `mode` follows).
- Auto-expire / TTL: snoozes are forever until the user explicitly resets them.
- Per-id confirmation dialog for the reset action.
- Migrate any existing on-disk state — none exists, the current implementation is in-memory.

## 4. Architecture

### 4.1 Storage

| Aspect | Decision |
|---|---|
| Backend | `SharedPreferences` (matches existing `RovaSettings` pattern). |
| File | `rova_runtime_prefs` (existing — added in Phase 4 warning re-skin v3 patch chain for `mode`). |
| Key | `snoozed_warning_ids` |
| Type | `Set<String>` via `getStringSet` / `putStringSet`. |
| Value | Each entry is a `WarningId.name` (`"NOTIFICATIONS_DENIED"`, `"BATTERY_OPTIMIZATION_ON"`, `"POWER_SAVE_MODE"`, etc.). |
| Backup | Excluded by the existing `<exclude domain="sharedpref" path="rova_runtime_prefs.xml" />` rule in `backup_rules.xml` + `data_extraction_rules.xml`. Reinstall resets to empty. |

### 4.2 Surface

`RovaSettings` gains one property:

```kotlin
var snoozedWarningIds: Set<String>
    get() = runtimePrefs.getStringSet("snoozed_warning_ids", emptySet()) ?: emptySet()
    set(value) = runtimePrefs.edit { putStringSet("snoozed_warning_ids", value) }
```

### 4.3 ViewModel

`WarningCenterViewModel` constructor gains two optional parameters with defaults that preserve every existing call site:

```kotlin
class WarningCenterViewModel(
    // ... existing 10 source flows + scope ...
    private val initialSnoozedIds: Set<WarningId> = emptySet(),
    private val onSnoozeChanged: ((Set<WarningId>) -> Unit)? = null,
) : ViewModel() { ... }
```

`init` seeds `_snoozedForever` from `initialSnoozedIds`. Both mutators invoke the callback after updating the flow:

```kotlin
private val _snoozedForever = MutableStateFlow(initialSnoozedIds)
val snoozedForever: StateFlow<Set<WarningId>> = _snoozedForever.asStateFlow()

fun snoozeForever(id: WarningId) {
    _snoozedForever.update { it + id }
    onSnoozeChanged?.invoke(_snoozedForever.value)
}

fun clearSnoozes() {
    if (_snoozedForever.value.isEmpty()) return
    _snoozedForever.value = emptySet()
    onSnoozeChanged?.invoke(emptySet())
}
```

The `clearSnoozes` early-return avoids a redundant disk write when the user taps the reset row on an already-empty set.

### 4.4 Factory wiring

In `WarningCenter.kt`'s `viewModelFactory { initializer { ... } }` block:

```kotlin
val initial = app.settings.snoozedWarningIds
    .mapNotNull { runCatching { WarningId.valueOf(it) }.getOrNull() }
    .toSet()
WarningCenterViewModel(
    // ... existing 10 args ...
    initialSnoozedIds = initial,
    onSnoozeChanged = { set ->
        app.settings.snoozedWarningIds = set.map(WarningId::name).toSet()
    },
)
```

`runCatching` + `mapNotNull` swallows any stale `WarningId.name` left over from a renamed/removed id in a future schema change — the self-heal happens automatically on the next write.

### 4.5 Settings UI

`SettingsSheet.kt` gains one row, gated on the live snooze set:

```kotlin
val snoozedSet by warningVm.snoozedForever.collectAsStateWithLifecycle()
if (snoozedSet.isNotEmpty()) {
    SettingsRow(
        title = "Reset snoozed warnings",
        subtitle = "${snoozedSet.size} warning${if (snoozedSet.size == 1) "" else "s"} hidden",
        onClick = { warningVm.clearSnoozes() },
    )
}
```

The row's subtitle gives the user a count so the affordance is informative even when they don't remember which warnings they snoozed. The exact row composable, its placement within the existing sheet sections, and the access path from `SettingsSheet` to a `WarningCenterViewModel` instance are resolved in the implementation plan (the existing sheet doesn't currently see the warning VM — the plan addresses that wiring).

## 5. Data flow

```
COLD START
  RovaApp.onCreate → RovaSettings instantiated (no I/O)
  RecordScreen mounts → WarningCenter composable
  WarningCenter factory:
    initial = app.settings.snoozedWarningIds.mapNotNull { WarningId.valueOf(it) }.toSet()
    WarningCenterViewModel(... initialSnoozedIds = initial, onSnoozeChanged = ...)
  VM init:
    _snoozedForever = MutableStateFlow(initial)
  activeWarning combines _resolvedWarning + _snoozedForever → filters

SNOOZE A WARNING
  WarningSheetV3 overflow → "Don't show again" → vm.snoozeForever(id)
  VM: _snoozedForever.value = set + id
      onSnoozeChanged.invoke(new set)
  RovaSettings: runtimePrefs.edit { putStringSet(...) }   // async-to-disk

RESET FROM SETTINGS
  Settings row click → vm.clearSnoozes()
  VM: _snoozedForever.value = emptySet
      onSnoozeChanged.invoke(emptySet)
  RovaSettings: runtimePrefs.edit { putStringSet(emptySet) }
  All previously-snoozed warnings reappear at their normal precedence
```

## 6. Error handling

| Scenario | Behavior |
|---|---|
| `getStringSet` returns null | `?: emptySet()` fallback. |
| Stored set contains a `WarningId.name` that no longer exists (renamed/removed) | `runCatching { WarningId.valueOf(it) }.getOrNull()` returns null. `mapNotNull` drops it. On the next write, the stored set self-heals to the trimmed value. |
| `snoozeForever(id)` called twice for same id | `set + id` is idempotent. Second call still triggers a disk write (acceptable — small key). |
| `clearSnoozes()` called on empty set | Early return short-circuits the disk write. |
| Concurrent reads/writes from multiple composable subscriptions | Single source of truth is `_snoozedForever`. SharedPreferences `apply()` is async-to-disk but immediate in-memory. The callback fires from inside the VM mutator under structured concurrency — no race against other VM mutators. |
| Backup restore brings back a stale `snoozed_warning_ids` key from a pre-exclude install snapshot | Cannot happen: `rova_runtime_prefs.xml` was added by the mode-split commits and is `<exclude>`d from the moment it existed. No backed-up snapshot ever contained the key. |

## 7. Testing

All tests pure-JVM JUnit4 (no Robolectric / Compose-UI), matching ADR-0007 precedent for this surface.

`RovaSettingsTest.kt` — 3 new tests:
- `snoozedWarningIds default is empty set`
- `snoozedWarningIds round-trips a 2-id set`
- `snoozedWarningIds setter replaces, does not merge` (write `{A, B}`, then write `{C}`, read returns `{C}`)

`WarningCenterAggregateTest.kt` — 3 new tests:
- `VM seeded with initialSnoozedIds filters those ids from activeWarning at construction` (drive a `NOTIFICATIONS_DENIED` source flow, seed with `setOf(NOTIFICATIONS_DENIED)`, assert `activeWarning.value == null`)
- `clearSnoozes empties the snoozedForever flow and invokes the callback with emptySet` (seed `setOf(NOTIFICATIONS_DENIED)`, call `clearSnoozes`, assert flow + callback observer)
- `snoozeForever invokes the callback with the updated set` (start empty, call `snoozeForever(NOTIFICATIONS_DENIED)`, assert callback receives `setOf(NOTIFICATIONS_DENIED)`)

Settings row interaction is owner-verified on device — matches the existing project convention for `SettingsSheet.kt` (no Compose-UI tests exist for that file).

## 8. Slice plan

Single PR. 5 tasks (TDD per file):

1. **T1:** `RovaSettings.snoozedWarningIds` getter/setter + 3 pure-JVM round-trip tests.
2. **T2:** `WarningCenterViewModel` constructor params + `clearSnoozes()` + 3 aggregate tests.
3. **T3:** `WarningCenter.kt` factory wiring (read initial set + persistence callback).
4. **T4:** `SettingsSheet.kt` "Reset snoozed warnings" row + access path to `WarningCenterViewModel`.
5. **T5:** ADR-0014 documenting the persistence canon + amendment to `WarningCenterContract.md` §5.2 + PR push + open.

## 9. Migration risk

- **In-memory → on-disk transition:** existing users have no persisted snoozes (the field never had a disk backing). First launch after this slice reads an empty set — identical to the current cold-start behavior. Users who tap "Don't show again" after upgrading get the new durable behavior.
- **Constructor signature:** the two new VM params have defaults (`emptySet()` and `null`), so the existing test fixtures + future call sites without persistence (synthetic VMs in tests, the in-progress fakes in `WarningCenterAggregateTest`) compile unchanged.
- **Settings row placement:** depends on the existing `SettingsSheet.kt` access to a `WarningCenterViewModel` instance, which isn't currently present (`SettingsSheet` is owned by the record screen's settings sheet flow, not the WarningCenter mount point). The plan resolves the wiring — most likely by lifting the VM access into the screen's composable and passing the snoozed-set + clear callback down to the sheet, avoiding a new `viewModel(...)` call site inside the sheet.

## 10. Out of scope (parked for future slices)

- **Snooze TTL / auto-expire:** considered and rejected — adds clock dependency + per-id timestamps + restored value would now need a `Map<WarningId, Long>` instead of `Set<WarningId>`. The "explicit reset row" UX is judged sufficient.
- **Confirmation dialog on reset:** low-stakes single-tap action, no destructive consequence — consistent with existing Settings rows.
- **Per-WarningId reset (granular unsnooze):** the reset row clears all. Granular reset is a Settings sub-screen, out of scope for this slice.
- **Backup-included variant:** explicitly chosen against (§3). If a future product call wants device-transfer to carry snoozes, move the key to `rova_settings` and drop the `<exclude>` rule — straightforward later if needed.

## 11. Open questions

None at this time. All design choices locked via brainstorming Q&A.
