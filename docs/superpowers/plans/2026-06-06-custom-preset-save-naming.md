# Custom Preset Save / Naming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users save the current recording config as a named custom preset, apply it with one tap, see the active custom chip reflected, and delete it by long-press — all inside the Record settings sheet PRESETS section.

**Architecture:** UI + small VM/matcher wiring + two pure helpers on top of existing preset backend (`savePreset`/`deletePreset`/`PresetJson`/`customPresetsJson`). Active reflection extends `PresetMatcher` with a list-overload + `matchActive` (built-in precedence). A conditional `+ Save` chip (shown only when `activePresetId == null && editable`) opens a validated naming dialog; custom chips carry a long-press delete with an accessible TalkBack custom action.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), CameraX-era Rova app. JVM-only unit tests (`org.json:json` on testImplementation; `isReturnDefaultValues = true`).

**Spec:** `docs/superpowers/specs/2026-06-06-custom-preset-save-naming-design.md`

**Branch:** `feat/custom-preset-save` (stacked off `feat/mode-preset-seed` / PR #93).

---

## Build-execution model (READ FIRST)

This repo has a post-edit Gradle hook that auto-runs on save; concurrent Gradle daemons cause cache corruption and pin CPU. Therefore:

- **Implementer subagents EDIT ONLY.** They do NOT run `gradlew`. They write code + tests and report.
- **The controller runs builds/tests** in batches between tasks: `gradlew.bat --stop` first (kill the hook's daemon), then the build/test command.
- **`lintDebug` is RED on pre-existing `VaultAndroidOps.kt` NewApi** (B5, unrelated). Verify with `:app:assembleDebug`, NOT `lintDebug`.
- detekt errors in hook notifications are NOISE (`detekt` is not a task here) — ignore them.

Single-test command (controller):
```powershell
./gradlew.bat --stop
./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.PresetSaveValidatorTest"
```
Full verify (controller, end): `./gradlew.bat :app:testDebugUnitTest` then `./gradlew.bat :app:assembleDebug`.

---

## File Structure

**Create**
- `app/src/main/java/com/aritr/rova/data/PresetSaveValidator.kt` — pure name-validation (Blank / TooLong / DuplicateName / Ok).
- `app/src/test/java/com/aritr/rova/data/PresetSaveValidatorTest.kt` — its tests.

**Modify**
- `app/src/main/java/com/aritr/rova/data/PresetMatcher.kt` — add list-overload `match` + `matchActive`.
- `app/src/test/java/com/aritr/rova/data/PresetMatcherTest.kt` — add matchActive/list-overload tests.
- `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt` — `activePresetId` rewrite; `savePreset`/`deletePreset` guards.
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` — params; conditional `+ Save` chip; long-press delete; `PresetNameDialog`; delete-confirm dialog.
- `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` — wire `onSavePreset` / `onDeletePreset`.
- `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml` — new keys.
- `docs/adr/0026-preset-config-bundle-orientation-orthogonal.md` — amendment clause.

---

## Task 1: PresetSaveValidator (pure)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/data/PresetSaveValidator.kt`
- Test: `app/src/test/java/com/aritr/rova/data/PresetSaveValidatorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/data/PresetSaveValidatorTest.kt`:

```kotlin
package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetSaveValidatorTest {

    private fun custom(name: String) =
        RovaPreset(name = name, duration = 15, interval = 3, loopCount = 5, resolution = QualityPresets.FHD, id = "custom.x")

    @Test fun freshNameIsOk() {
        assertEquals(PresetSaveValidator.Result.Ok, PresetSaveValidator.validateName("Beach", emptyList()))
    }

    @Test fun blankIsBlank() {
        assertEquals(PresetSaveValidator.Result.Blank, PresetSaveValidator.validateName("", emptyList()))
    }

    @Test fun whitespaceOnlyIsBlank() {
        assertEquals(PresetSaveValidator.Result.Blank, PresetSaveValidator.validateName("   ", emptyList()))
    }

    @Test fun overMaxIsTooLong() {
        val name = "x".repeat(PresetSaveValidator.MAX_NAME_LENGTH + 1)
        assertEquals(PresetSaveValidator.Result.TooLong, PresetSaveValidator.validateName(name, emptyList()))
    }

    @Test fun atMaxIsOk() {
        val name = "x".repeat(PresetSaveValidator.MAX_NAME_LENGTH)
        assertEquals(PresetSaveValidator.Result.Ok, PresetSaveValidator.validateName(name, emptyList()))
    }

    @Test fun duplicateCustomNameCaseInsensitive() {
        assertEquals(
            PresetSaveValidator.Result.DuplicateName,
            PresetSaveValidator.validateName("night", listOf(custom("Night"))),
        )
    }

    @Test fun builtInNameIsReserved() {
        // "Standard" is a built-in name — cannot be reused for a custom.
        assertEquals(
            PresetSaveValidator.Result.DuplicateName,
            PresetSaveValidator.validateName("Standard", emptyList()),
        )
    }

    @Test fun trimsBeforeChecking() {
        assertEquals(
            PresetSaveValidator.Result.DuplicateName,
            PresetSaveValidator.validateName("  Night  ", listOf(custom("Night"))),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails** (controller)

Run: `./gradlew.bat --stop; ./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.PresetSaveValidatorTest"`
Expected: FAIL — `Unresolved reference: PresetSaveValidator`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/aritr/rova/data/PresetSaveValidator.kt`:

```kotlin
package com.aritr.rova.data

/**
 * ADR-0026 — validates a proposed custom-preset name. Pure so it can drive both
 * the live naming-dialog error state and the [RecordViewModel.savePreset]
 * defensive guard. Built-in names are reserved (a custom may not shadow
 * "Standard" etc.); duplicate detection is case-insensitive and trim-tolerant.
 */
object PresetSaveValidator {
    const val MAX_NAME_LENGTH = 40

    sealed interface Result {
        data object Ok : Result
        data object Blank : Result
        data object TooLong : Result
        data object DuplicateName : Result
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

- [ ] **Step 4: Run test to verify it passes** (controller)

Run: `./gradlew.bat --stop; ./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.PresetSaveValidatorTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/PresetSaveValidator.kt app/src/test/java/com/aritr/rova/data/PresetSaveValidatorTest.kt
git commit -m "feat(presets): PresetSaveValidator — pure custom-name validation (ADR-0026)"
```

---

## Task 2: PresetMatcher list-overload + matchActive (pure)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/PresetMatcher.kt`
- Test: `app/src/test/java/com/aritr/rova/data/PresetMatcherTest.kt`

- [ ] **Step 1: Write the failing tests** — append to `PresetMatcherTest.kt` (inside the class, before the closing brace):

```kotlin
    private fun custom(
        name: String, d: Int, i: Int, l: Int, r: String, id: String,
    ) = RovaPreset(name = name, duration = d, interval = i, loopCount = l, resolution = r, id = id)

    @Test fun listOverloadMatchesFirstByValue() {
        val list = listOf(custom("A", 15, 3, 5, "FHD", "custom.a"))
        assertEquals("custom.a", PresetMatcher.match(list, 15, 3, 5, "FHD"))
    }

    @Test fun listOverloadCanonicalizesResolution() {
        val list = listOf(custom("A", 15, 3, 5, "FHD", "custom.a"))
        assertEquals("custom.a", PresetMatcher.match(list, 15, 3, 5, "1080p"))
    }

    @Test fun listOverloadNoMatchReturnsNull() {
        val list = listOf(custom("A", 15, 3, 5, "FHD", "custom.a"))
        assertNull(PresetMatcher.match(list, 99, 3, 5, "FHD"))
    }

    @Test fun matchActiveBuiltInTakesPrecedence() {
        // A custom whose tuple equals Standard must resolve to the built-in id.
        val customs = listOf(custom("Mine", 30, 2, 20, "FHD", "custom.mine"))
        assertEquals("builtin.standard", PresetMatcher.matchActive(customs, 30, 2, 20, "FHD"))
    }

    @Test fun matchActiveFallsBackToCustom() {
        val customs = listOf(custom("Mine", 15, 3, 5, "FHD", "custom.mine"))
        assertEquals("custom.mine", PresetMatcher.matchActive(customs, 15, 3, 5, "FHD"))
    }

    @Test fun matchActiveDuplicateTupleReturnsFirst() {
        val customs = listOf(
            custom("First", 15, 3, 5, "FHD", "custom.first"),
            custom("Second", 15, 3, 5, "FHD", "custom.second"),
        )
        assertEquals("custom.first", PresetMatcher.matchActive(customs, 15, 3, 5, "FHD"))
    }

    @Test fun matchActiveNoMatchReturnsNull() {
        val customs = listOf(custom("Mine", 15, 3, 5, "FHD", "custom.mine"))
        assertNull(PresetMatcher.matchActive(customs, 99, 9, 9, "FHD"))
    }
```

- [ ] **Step 2: Run tests to verify they fail** (controller)

Run: `./gradlew.bat --stop; ./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.PresetMatcherTest"`
Expected: FAIL — `match(List, ...)` / `matchActive` unresolved.

- [ ] **Step 3: Write the implementation** — replace the body of `PresetMatcher.kt` with:

```kotlin
package com.aritr.rova.data

/**
 * ADR-0026 — classifies the current recording config against the built-in
 * presets (and, via [matchActive], the user's customs). Returns the matching
 * `builtin.*`/`custom.*` id, or null (rendered as "Custom").
 *
 * "Active preset = value match, built-ins first" — selection is by config, not
 * identity. A user custom whose values equal a built-in resolves to the built-in
 * id. Resolution is compared canonically so legacy aliases ("1080p", "UHD")
 * match; loopCount is compared exactly, including the -1 continuous sentinel.
 * A null or unrecognized resolution yields no match (Custom) — we never coerce
 * unknown input to the FHD default, which would be a false positive (review).
 */
object PresetMatcher {
    /** Active built-in id for this config, or null. */
    fun match(duration: Int, interval: Int, loopCount: Int, resolution: String?): String? =
        match(BuiltInPresets.all, duration, interval, loopCount, resolution)

    /** First value-match id within [presets] (canonicalized resolution), or null. */
    fun match(
        presets: List<RovaPreset>,
        duration: Int,
        interval: Int,
        loopCount: Int,
        resolution: String?,
    ): String? {
        val res = QualityPresets.canonicalize(resolution) ?: return null
        return presets.firstOrNull { p ->
            p.duration == duration &&
                p.interval == interval &&
                p.loopCount == loopCount &&
                QualityPresets.canonicalizeOrDefault(p.resolution) == res
        }?.id
    }

    /** Built-in match takes precedence; else first custom value-match; else null. */
    fun matchActive(
        customs: List<RovaPreset>,
        duration: Int,
        interval: Int,
        loopCount: Int,
        resolution: String?,
    ): String? =
        match(duration, interval, loopCount, resolution)
            ?: match(customs, duration, interval, loopCount, resolution)
}
```

- [ ] **Step 4: Run tests to verify they pass** (controller)

Run: `./gradlew.bat --stop; ./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.PresetMatcherTest"`
Expected: PASS (existing 7 + new 7 = 14).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/PresetMatcher.kt app/src/test/java/com/aritr/rova/data/PresetMatcherTest.kt
git commit -m "feat(presets): PresetMatcher list-overload + matchActive (custom active reflection, ADR-0026)"
```

---

## Task 3: RecordViewModel — activePresetId rewrite + save/delete guards

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt:333-380`

No JVM unit test (VM needs Android context; `matchActive`/validator logic is already covered by Tasks 1–2). Verified by compile + device-smoke.

- [ ] **Step 1: Replace `savePreset` and `deletePreset`** (currently lines 333-350) with:

```kotlin
    fun savePreset(name: String) {
        // Mid-session guard — same as applyPreset. UI gates this behind `editable`,
        // but a stale dialog, a future caller, or a test could still reach it (codex review).
        val s = _serviceState.value
        if (s.isPeriodicActive || s.isMerging) return
        val trimmed = name.trim()
        val d = duration.value
        val i = interval.value
        val l = loopCount.value
        val r = resolution.value
        // Resolution must be canonicalizable, else the saved custom could never
        // match active (codex review).
        if (QualityPresets.canonicalize(r) == null) return
        val current = _customPresets.value
        // Defensive: the dialog already blocks these; guard the contract.
        if (PresetSaveValidator.validateName(trimmed, current) != PresetSaveValidator.Result.Ok) return
        // Tuple-duplicate guard: unreachable via the conditional + Save chip (only
        // shown when activePresetId == null), but guard the API anyway.
        if (PresetMatcher.matchActive(current, d, i, l, r) != null) return
        val updated = current + RovaPreset(
            name = trimmed, duration = d, interval = i, loopCount = l, resolution = r,
        )
        _customPresets.value = updated
        persistPresets(updated) // encode() stamps a stable custom.* id
    }

    fun deletePreset(preset: RovaPreset) {
        // Same mid-session guard (codex review — VM-side, not only UI).
        val s = _serviceState.value
        if (s.isPeriodicActive || s.isMerging) return
        val updated = _customPresets.value - preset
        _customPresets.value = updated
        persistPresets(updated)
    }
```

- [ ] **Step 2: Replace `activePresetId`** (currently lines 375-380) with:

```kotlin
    /** The active preset id — built-in value match first, then custom; null = "Custom". */
    val activePresetId: StateFlow<String?> =
        combine(duration, interval, loopCount, resolution, _customPresets) { d, i, l, r, customs ->
            PresetMatcher.matchActive(customs, d, i, l, r)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            PresetMatcher.matchActive(
                _customPresets.value, duration.value, interval.value, loopCount.value, resolution.value,
            ),
        )
```

- [ ] **Step 3: Verify imports** — `QualityPresets`, `PresetSaveValidator`, `PresetMatcher`, `RovaPreset`, `combine`, `stateIn`, `SharingStarted` must be imported. `combine`/`stateIn`/`SharingStarted` are already used (existing `activePresetId`/`allPresets`); `PresetMatcher`/`RovaPreset` already imported. Add `import com.aritr.rova.data.PresetSaveValidator` and confirm `import com.aritr.rova.data.QualityPresets` is present (add if missing).

- [ ] **Step 4: Compile check** (controller)

Run: `./gradlew.bat --stop; ./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (If "5-arg combine ambiguous", confirm the lambda destructures exactly `{ d, i, l, r, customs -> }` — the 5-flow `combine` overload is variance-typed; the explicit lambda params resolve it.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt
git commit -m "feat(presets): reflect active custom + guard save/delete mid-session (ADR-0026)"
```

---

## Task 4: String resources (en + es)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`

- [ ] **Step 1: Add keys to `app/src/main/res/values/strings.xml`** — place after the existing `preset_cd_full` line:

```xml
    <!-- Custom preset save/naming (ADR-0026) -->
    <string name="preset_save_chip">Save</string>
    <string name="preset_save_chip_cd">Save current settings as a preset</string>
    <string name="preset_name_dialog_title">Name this preset</string>
    <string name="preset_name_field_label">Preset name</string>
    <string name="preset_name_error_blank">Enter a name</string>
    <string name="preset_name_error_duplicate">You already have a preset with this name</string>
    <string name="preset_name_error_too_long">Name is too long</string>
    <string name="preset_delete_title">Delete preset?</string>
    <string name="preset_delete_body">Delete \"%1$s\"? This can\'t be undone.</string>
    <string name="preset_delete_confirm">Delete</string>
    <string name="preset_chip_delete_action">Delete preset</string>
```

- [ ] **Step 2: Add the Spanish translations to `app/src/main/res/values-es/strings.xml`** — place after the matching `preset_cd_full` line:

```xml
    <!-- Custom preset save/naming (ADR-0026) -->
    <string name="preset_save_chip">Guardar</string>
    <string name="preset_save_chip_cd">Guardar la configuración actual como preajuste</string>
    <string name="preset_name_dialog_title">Nombra este preajuste</string>
    <string name="preset_name_field_label">Nombre del preajuste</string>
    <string name="preset_name_error_blank">Escribe un nombre</string>
    <string name="preset_name_error_duplicate">Ya tienes un preajuste con este nombre</string>
    <string name="preset_name_error_too_long">El nombre es demasiado largo</string>
    <string name="preset_delete_title">¿Eliminar preajuste?</string>
    <string name="preset_delete_body">¿Eliminar «%1$s»? No se puede deshacer.</string>
    <string name="preset_delete_confirm">Eliminar</string>
    <string name="preset_chip_delete_action">Eliminar preajuste</string>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "i18n(presets): save/naming/delete strings (en + es)"
```

---

## Task 5: SettingsSheet — `+ Save` chip, long-press delete, dialogs

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt`

This is the largest task. Build it in sub-steps; compile only at the end (controller).

- [ ] **Step 1: Add imports** — add to the import block (alphabetical-ish, near existing siblings):

```kotlin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import com.aritr.rova.data.PresetSaveValidator
```

- [ ] **Step 2: Add two params to `SettingsSheet`** — after `onApplyPreset: (RovaPreset) -> Unit,` (line 93):

```kotlin
    onSavePreset: (String) -> Unit,
    onDeletePreset: (RovaPreset) -> Unit,
```

- [ ] **Step 3: Forward them in the `SettingsPanel(...)` call** — after `onApplyPreset = onApplyPreset,` (line 143):

```kotlin
                onSavePreset = onSavePreset,
                onDeletePreset = onDeletePreset,
```

- [ ] **Step 4: Add the same two params to `SettingsPanel`** — after `onApplyPreset: (RovaPreset) -> Unit,` (line 247):

```kotlin
    onSavePreset: (String) -> Unit,
    onDeletePreset: (RovaPreset) -> Unit,
```

- [ ] **Step 5: Host dialog state + dialogs in `SettingsPanel`** — at the very top of the `SettingsPanel` body (right after the `panelShape` `remember`, before the `Column`):

```kotlin
    // Custom preset save/delete dialog state (sheet-scoped). The naming dialog
    // opens from the conditional "+ Save" chip; pendingDelete from a chip long-press.
    var namingVisible by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RovaPreset?>(null) }
    val customNames = presets.filter { !it.isBuiltIn }
```

Then, immediately AFTER the closing brace of the `Column { ... }` (still inside `SettingsPanel`), add:

```kotlin
    if (namingVisible) {
        PresetNameDialog(
            existingCustoms = customNames,
            onDismiss = { namingVisible = false },
            onConfirm = { name ->
                namingVisible = false
                onSavePreset(name)
            },
        )
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.preset_delete_title)) },
            text = { Text(stringResource(R.string.preset_delete_body, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeletePreset(target)
                }) {
                    Text(
                        text = stringResource(R.string.preset_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
```

- [ ] **Step 6: Pass new callbacks into `PresetSection`** — replace the existing `PresetSection(...)` call (lines 316-321) with:

```kotlin
        PresetSection(
            presets = presets,
            activePresetId = activePresetId,
            enabled = editable,
            onApply = onApplyPreset,
            onRequestSave = { namingVisible = true },
            onRequestDelete = { pendingDelete = it },
        )
```

- [ ] **Step 7: Rewrite `PresetSection`** (lines 570-592) to add the `+ Save` chip and route long-press delete:

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSection(
    presets: List<RovaPreset>,
    activePresetId: String?,
    enabled: Boolean,
    onApply: (RovaPreset) -> Unit,
    onRequestSave: () -> Unit,
    onRequestDelete: (RovaPreset) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.chipGroupGap),
        verticalArrangement = Arrangement.spacedBy(SettingsSheetTokens.chipGroupGap),
    ) {
        presets.forEach { preset ->
            PresetSheetChip(
                preset = preset,
                selected = preset.id == activePresetId,
                enabled = enabled,
                onClick = { onApply(preset) },
                // Long-press deletes user customs only; built-ins are read-only.
                onLongClick = if (!preset.isBuiltIn) {
                    { onRequestDelete(preset) }
                } else {
                    null
                },
            )
        }
        // "+ Save" appears only when the current config matches no preset
        // (activePresetId == null = genuinely Custom) and the sheet is editable.
        if (activePresetId == null && enabled) {
            SavePresetChip(onClick = onRequestSave)
        }
    }
}
```

- [ ] **Step 8: Rewrite `PresetSheetChip`** (lines 601-653) to support an optional long-press with an accessible TalkBack custom action:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetSheetChip(
    preset: RovaPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(SettingsSheetTokens.chipRadius)
    val fill = if (selected) SettingsSheetTokens.chipOnFill else Color.Transparent
    val stroke = if (selected) SettingsSheetTokens.chipOnStroke else SettingsSheetTokens.chipOffStroke
    val textColor = if (selected) SettingsSheetTokens.chipOnText else SettingsSheetTokens.chipOffText
    val cd = presetSpokenDescription(preset)
    // The long-press label surfaces delete as a TalkBack/Switch/Voice custom
    // action — the non-gesture equivalent required by WCAG SC 2.5.1 / 2.1.1
    // (codex a11y review). Sighted-touch discoverability is an accepted tradeoff
    // (delete is rare, destructive, and confirmed).
    val deleteLabel = stringResource(R.string.preset_chip_delete_action)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, stroke, shape)
            .then(
                if (enabled) {
                    Modifier
                        .focusHighlight(shape)
                        .combinedClickable(
                            role = Role.Button,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            onLongClickLabel = if (onLongClick != null) deleteLabel else null,
                        )
                } else {
                    Modifier
                },
            )
            .alpha(if (enabled) 1f else 0.5f)
            .semantics {
                this.selected = selected
                contentDescription = cd
                if (!enabled) disabled()
            }
            .padding(
                horizontal = SettingsSheetTokens.chipPaddingH,
                vertical = SettingsSheetTokens.chipPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(preset.name, style = RovaTokens.sheetChip, color = textColor)
    }
}

/**
 * The "+ Save" affordance — styled like an unselected [PresetSheetChip] with a
 * leading Add icon. Shown only when the config matches no preset; tapping opens
 * the naming dialog. (ADR-0026.)
 */
@Composable
private fun SavePresetChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(SettingsSheetTokens.chipRadius)
    val textColor = SettingsSheetTokens.chipOffText
    val cd = stringResource(R.string.preset_save_chip_cd)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(Color.Transparent)
            .border(1.dp, SettingsSheetTokens.chipOffStroke, shape)
            .focusHighlight(shape)
            .clickable(role = Role.Button) { onClick() }
            .semantics { contentDescription = cd }
            .padding(
                horizontal = SettingsSheetTokens.chipPaddingH,
                vertical = SettingsSheetTokens.chipPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(16.dp),
        )
        Text(stringResource(R.string.preset_save_chip), style = RovaTokens.sheetChip, color = textColor)
    }
}
```

- [ ] **Step 9: Add `PresetNameDialog`** — append after `presetSpokenDescription` (after line 674):

```kotlin
/**
 * Naming dialog for saving the current config as a custom preset. Live-validates
 * via [PresetSaveValidator]: OK is disabled until the name is valid, and the
 * error announces through a polite live region (WCAG SC 4.1.3, ADR-0020). The
 * field gets a programmatic label (SC 4.1.2). An empty untouched field shows no
 * error (OK simply stays disabled).
 */
@Composable
private fun PresetNameDialog(
    existingCustoms: List<RovaPreset>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val result = PresetSaveValidator.validateName(text, existingCustoms)
    val isOk = result == PresetSaveValidator.Result.Ok
    val errorRes: Int? = when (result) {
        PresetSaveValidator.Result.DuplicateName -> R.string.preset_name_error_duplicate
        PresetSaveValidator.Result.TooLong -> R.string.preset_name_error_too_long
        // Blank: only nag once the user has typed (e.g. whitespace); not on an
        // untouched empty field.
        PresetSaveValidator.Result.Blank -> if (text.isNotEmpty()) R.string.preset_name_error_blank else null
        PresetSaveValidator.Result.Ok -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_name_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.preset_name_field_label)) },
                    isError = errorRes != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                if (errorRes != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(errorRes),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (isOk) onConfirm(text.trim()) }, enabled = isOk) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}
```

- [ ] **Step 10: Compile check** (controller)

Run: `./gradlew.bat --stop; ./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (RecordScreen still passes the old arg set — it won't compile yet because `SettingsSheet` now requires `onSavePreset`/`onDeletePreset`. That's expected; Task 6 fixes the call site. Run the combined compile at the end of Task 6 instead, OR accept this task fails compile until Task 6. To keep tasks independently committable, do Step 11 commit now and treat Tasks 5+6 as a compile pair verified at Task 6 Step 3.)

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt
git commit -m "feat(presets): + Save chip, long-press delete, naming/confirm dialogs (ADR-0026)"
```

---

## Task 6: RecordScreen — wire save/delete callbacks

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt:867`

- [ ] **Step 1: Add the two callbacks** — in the `SettingsSheet(...)` call, after `onApplyPreset = viewModel::applyPreset,` (line 867):

```kotlin
                    onSavePreset = viewModel::savePreset,
                    onDeletePreset = viewModel::deletePreset,
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(presets): wire onSavePreset/onDeletePreset into the settings sheet"
```

- [ ] **Step 3: Full compile + unit suite** (controller)

Run:
```powershell
./gradlew.bat --stop
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL on both; all gates green (`assembleDebug` runs the 30 `check*` tasks via `preBuild`); unit suite green (existing + 15 new). If a `check*` gate fails, read which ADR clause it cites and fix the source — do NOT edit the gate.

---

## Task 7: ADR-0026 amendment

**Files:**
- Modify: `docs/adr/0026-preset-config-bundle-orientation-orthogonal.md`

- [ ] **Step 1: Append an amendment section** at the end of the file:

```markdown

## Amendment 2026-06-06 — custom preset save/naming

Custom presets are now user-creatable from the Record settings sheet:

- **Save affordance.** A "+ Save" chip appears in the PRESETS FlowRow **only when
  `activePresetId == null`** (the current config matches no preset = "Custom")
  **and the sheet is editable** (no active/merging session). It opens a naming
  dialog.
- **Name + tuple uniqueness.** A custom name must be non-blank, ≤ 40 chars, and
  unique case-insensitively against both built-in and existing custom names
  (`PresetSaveValidator`). A config whose tuple already matches any preset cannot
  be saved (guarded in `savePreset`; unreachable via the conditional chip).
- **Active reflection.** `activePresetId` now reflects customs via
  `PresetMatcher.matchActive` — **built-in value-match takes precedence**, then
  the first custom value-match.
- **Delete.** Custom chips delete via long-press → confirm dialog. The long-press
  is exposed to TalkBack/Switch/Voice as a labelled custom action
  (`onLongClickLabel`), satisfying WCAG SC 2.5.1 / 2.1.1 without a visible ✕.
  Built-in chips are not deletable.
- **Mid-session guards.** `savePreset`/`deletePreset` no-op while a session is
  active or merging (VM-side, mirroring `applyPreset`).

No new `check*` gate: the slice adds no statically-scannable invariant beyond
orientation-orthogonality, which `checkPresetNoOrientation` already enforces via
the unchanged `RovaPreset` / `BuiltInPresets` shape. Name/tuple uniqueness and
active-reflection are runtime behaviors covered by `PresetSaveValidatorTest` and
`PresetMatcherTest`.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0026-preset-config-bundle-orientation-orthogonal.md
git commit -m "docs(adr-0026): amend for custom preset save/naming/delete"
```

---

## Task 8: Final verification

- [ ] **Step 1: Clean full build + suite** (controller)

```powershell
./gradlew.bat --stop
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:testDebugUnitTest
```
Expected: both BUILD SUCCESSFUL; 30 gates green; unit suite green (baseline + 15 new). APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Device-smoke checklist (owner step)**

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Verify:
1. Open settings sheet, change steppers off any preset → `+ Save` chip appears; on a matching preset it is absent.
2. Tap `+ Save` → name dialog; blank/duplicate name disables OK + shows error; valid name saves.
3. Saved custom chip appears, shows ✓ selected (the just-saved config matches it), and `+ Save` is gone.
4. Apply a built-in, then re-apply the custom → custom chip shows ✓.
5. Long-press a custom chip → confirm dialog → Delete removes it; built-in long-press does nothing.
6. Start a session → sheet read-only: no `+ Save`, chips not applyable, long-press inert.
7. TalkBack: custom chip exposes a "Delete preset" action; name-dialog errors announce.

- [ ] **Step 3:** Hand off to `superpowers:finishing-a-development-branch`.

---

## Self-Review

**Spec coverage:**
- §A active reflection → Task 2 (matcher) + Task 3 (VM). ✓
- §B save validation → Task 1 + Task 3 guards. ✓
- §C UI (`+ Save` chip, long-press delete, two dialogs) → Task 5. ✓
- §D RecordScreen wiring → Task 6. ✓
- §E strings → Task 4. ✓
- ADR amendment / no new gate → Task 7. ✓
- Tests (PresetMatcher additions, PresetSaveValidator) → Tasks 1–2. ✓

**Type consistency:** `matchActive(customs, d, i, l, r)` signature identical across Tasks 2, 3, 7. `PresetSaveValidator.Result` (Ok/Blank/TooLong/DuplicateName) consistent across Tasks 1, 3, 5. `onSavePreset: (String) -> Unit` / `onDeletePreset: (RovaPreset) -> Unit` consistent across Tasks 5, 6. `PresetSheetChip` new `onLongClick: (() -> Unit)?` param used by `PresetSection` (Task 5 Steps 7–8) consistently. `SavePresetChip(onClick)` / `PresetNameDialog(existingCustoms, onDismiss, onConfirm)` defined and called within Task 5.

**Placeholder scan:** No TBD/TODO/"handle edge cases"; every code step shows full code. ✓

**Known ordering note:** Tasks 5 and 6 are a compile pair (SettingsSheet gains required params before the call site is updated). Full compile is verified at Task 6 Step 3; per-task commits remain atomic in git history even though Task 5 alone won't link.
