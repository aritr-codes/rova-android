# Library Item-Sheet + Record-Settings Polish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`). **Subagents are EDIT-ONLY; the controller runs ALL gradle commands and does ALL commits.**

**Goal:** Four owner-requested refinements: (D) tappable stepper values in the Record-settings sheet (manual numeric entry alongside +/−), (B) the "View settings" gear → Info icon, (C) show the DualShot "Move to Vault" row greyed-with-reason instead of hidden, and (A) make the Library item sheet more compact.

**Architecture:** (D) adds pure clamp/parse helpers to `RecordSettingBounds` + a small `NumericEntryDialog` + an optional tap-to-edit path on `StepperRow`, wired for all three numeric steppers. (B)(C) are `LibraryItemSheet`/`LibraryScreen` edits routed through the icon seam + a new disabled `SheetRow` state. (A) compacts `LibraryItemSheet` layout tokens. No schema/behavior change beyond direct value entry (which clamps to the SAME bounds the steppers enforce).

**Tech Stack:** Kotlin, Jetpack Compose (Material3 `AlertDialog`, `OutlinedTextField`), JVM unit tests.

**Owner-ratified decisions (2026-06-17):** vault → "Show greyed + reason"; sheet → "Compact pass"; entry mechanism → dialog (planner's pick); `∞` repeats stays a `−`-at-min affordance, the dialog edits finite [1,999] only.

---

## File Structure

- `app/.../ui/screens/RecordSettingBounds.kt` — **modify**: `clampClip/clampRepeats/clampWait` + `parseEntry`.
- `app/.../ui/screens/RecordSettingBoundsTest.kt` — **modify/create**: clamp + parse coverage.
- `app/.../ui/components/NumericEntryDialog.kt` — **create**: reusable numeric-entry dialog.
- `app/.../ui/screens/SettingsSheet.kt` — **modify**: `StepperRow` tap-to-edit; wire 3 steppers.
- `app/.../ui/library/components/LibraryItemSheet.kt` — **modify**: Info icon (B), disabled vault row (C), compaction (A).
- `app/.../ui/library/LibraryScreen.kt` — **modify**: pass `movable` so the row always renders + reason label (C).
- `app/src/main/res/values/strings.xml` + `values-es/strings.xml` — **modify**: vault-disabled reason + dialog strings.
- `docs/...` + memory — **modify**: record the polish.

---

## Task 1: RecordSettingBounds clamp + parse helpers (pure, TDD)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordSettingBounds.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/RecordSettingBoundsTest.kt` (create if absent)

- [ ] **Step 1: Write the failing tests**

Create/extend `RecordSettingBoundsTest.kt`:
```kotlin
package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordSettingBoundsClampTest {
    @Test fun clampClip_coerces_to_range() {
        assertEquals(1, RecordSettingBounds.clampClip(0))
        assertEquals(300, RecordSettingBounds.clampClip(5000))
        assertEquals(45, RecordSettingBounds.clampClip(45))
    }
    @Test fun clampRepeats_coerces_to_finite_range() {
        assertEquals(1, RecordSettingBounds.clampRepeats(0))
        assertEquals(1, RecordSettingBounds.clampRepeats(-9))
        assertEquals(999, RecordSettingBounds.clampRepeats(100000))
        assertEquals(12, RecordSettingBounds.clampRepeats(12))
    }
    @Test fun clampWait_coerces_to_range() {
        assertEquals(0, RecordSettingBounds.clampWait(-3))
        assertEquals(60, RecordSettingBounds.clampWait(99))
        assertEquals(15, RecordSettingBounds.clampWait(15))
    }
}
```
(Drop the unused `assertNull` import if the file doesn't otherwise use it.)

- [ ] **Step 2: Run to verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordSettingBoundsClampTest"`
Expected: FAIL — unresolved `clampClip`/`parseEntry`.

- [ ] **Step 3: Add the helpers**

In `RecordSettingBounds.kt`, after `waitAtMax` (line 44), before the closing `}`:
```kotlin

    fun clampClip(v: Int): Int = v.coerceIn(CLIP_MIN, CLIP_MAX)
    fun clampRepeats(v: Int): Int = v.coerceIn(REPEATS_MIN, REPEATS_MAX)
    fun clampWait(v: Int): Int = v.coerceIn(WAIT_MIN, WAIT_MAX)
```

- [ ] **Step 4: Run to verify pass** — `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordSettingBoundsClampTest"` → PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordSettingBounds.kt app/src/test/java/com/aritr/rova/ui/screens/RecordSettingBoundsTest.kt
git commit -m "feat(settings): RecordSettingBounds clamp + parseEntry helpers for direct value entry"
```

---

## Task 2: NumericEntryDialog + strings

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/components/NumericEntryDialog.kt`
- Modify: `app/src/main/res/values/strings.xml`, `values-es/strings.xml`

- [ ] **Step 1: Reuse the existing cancel string**

`R.string.dialog_cancel` ("Cancel" / es present) already exists — use it for the dialog cancel. No new cancel string needed.

- [ ] **Step 2: Add strings (both locales)**

`values/strings.xml`:
```xml
<string name="settings_value_entry_hint">Enter a value</string>
<string name="settings_value_entry_confirm">Set</string>
```
`values-es/strings.xml`:
```xml
<string name="settings_value_entry_hint">Introduce un valor</string>
<string name="settings_value_entry_confirm">Establecer</string>
```

- [ ] **Step 3: Create the dialog**

`NumericEntryDialog.kt`:
```kotlin
package com.aritr.rova.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType

/**
 * Lightweight manual numeric entry for the settings steppers (owner request 2026-06-17).
 * Pre-filled with the current value; the number keyboard is forced; [onConfirm] receives the
 * raw parsed Int (the caller clamps via RecordSettingBounds). Invalid/blank input disables
 * the confirm button. The +/− steppers remain for quick adjustments.
 */
@Composable
fun NumericEntryDialog(
    title: String,
    initialValue: Int?, // null → start empty (e.g. Repeats when currently ∞/continuous)
    hint: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue?.toString() ?: "") }
    val parsed = text.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    label = { Text(hint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onConfirm) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelLabel) } },
    )
}
```

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/aritr/rova/ui/components/NumericEntryDialog.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(settings): NumericEntryDialog for manual stepper value entry (en+es)"
```

---

## Task 3: StepperRow tap-to-edit + wire the three steppers

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt`

UI-only — verified by build + device smoke.

- [ ] **Step 1: Imports** (add any missing)
```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.aritr.rova.ui.components.NumericEntryDialog
```

- [ ] **Step 2: Add optional tap-to-edit to `StepperRow`**

Replace the `StepperRow` signature + the value `Text` (lines 1028-1064). New signature adds `onSetValue`/`editValue`/`editTitle`; when `onSetValue != null`, the value becomes a clickable that opens the dialog:
```kotlin
@Composable
internal fun StepperRow(
    label: String,
    value: String,
    enabled: Boolean,
    atMin: Boolean,
    atMax: Boolean,
    onStep: (Int) -> Unit,
    // Owner 2026-06-17: tap the value to enter it directly. onSetValue != null enables the
    // tap path; editValue is the dialog prefill (null → start empty, e.g. Repeats when ∞).
    editValue: Int? = null,
    onSetValue: ((Int) -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SettingsSheetTokens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = RovaTokens.sheetRowLabel,
            color = SettingsSheetTokens.rowLabelText,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.stepperGap),
        ) {
            StepButton("−", enabled = enabled && !atMin, onClick = { onStep(-1) })
            val canEdit = enabled && onSetValue != null
            Text(
                value,
                style = RovaTokens.sheetStepValue,
                color = SettingsSheetTokens.stepValText,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = SettingsSheetTokens.stepValMinWidth)
                    .then(
                        if (canEdit) Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = label,
                        ) { editing = true } else Modifier
                    ),
            )
            StepButton("+", enabled = enabled && !atMax, onClick = { onStep(+1) })
        }
    }
    if (editing && onSetValue != null) {
        NumericEntryDialog(
            title = label,
            initialValue = editValue,
            hint = stringResource(R.string.settings_value_entry_hint),
            confirmLabel = stringResource(R.string.settings_value_entry_confirm),
            cancelLabel = stringResource(R.string.dialog_cancel),
            onConfirm = { editing = false; onSetValue(it) },
            onDismiss = { editing = false },
        )
    }
}
```

- [ ] **Step 3: Wire the three steppers (lines 676-701)**

Add `editValue` + `onSetValue` to each (clamp through the Task-1 helpers):
```kotlin
        StepperRow(
            label = stringResource(R.string.settings_sheet_clip_duration),
            value = recordClipValue(durationSeconds),
            enabled = editable,
            atMin = RecordSettingBounds.clipAtMin(durationSeconds),
            atMax = RecordSettingBounds.clipAtMax(durationSeconds),
            onStep = { dir -> onDurationChange(RecordSettingBounds.stepClip(durationSeconds, dir)) },
            editValue = durationSeconds,
            onSetValue = { onDurationChange(RecordSettingBounds.clampClip(it)) },
        )
        SheetRowDivider()
        StepperRow(
            label = stringResource(R.string.settings_sheet_repeats),
            value = recordRepeatsCompactValue(loopCount),
            enabled = editable,
            atMin = RecordSettingBounds.repeatsAtMin(loopCount),
            atMax = RecordSettingBounds.repeatsAtMax(loopCount),
            onStep = { dir -> onLoopCountChange(RecordSettingBounds.stepRepeats(loopCount, dir)) },
            // ∞ (continuous = -1): dialog opens EMPTY (editValue=null) so OK can't silently turn
            // ∞→1; the user must type a finite count. ∞ stays reachable via '−' at min. [1,999].
            editValue = loopCount.takeIf { it != RecordSettingBounds.REPEATS_CONTINUOUS },
            onSetValue = { onLoopCountChange(RecordSettingBounds.clampRepeats(it)) },
        )
        SheetRowDivider()
        StepperRow(
            label = stringResource(R.string.settings_sheet_wait_between),
            value = recordWaitValue(intervalMinutes),
            enabled = editable,
            atMin = RecordSettingBounds.waitAtMin(intervalMinutes),
            atMax = RecordSettingBounds.waitAtMax(intervalMinutes),
            onStep = { dir -> onIntervalChange(RecordSettingBounds.stepWait(intervalMinutes, dir)) },
            editValue = intervalMinutes,
            onSetValue = { onIntervalChange(RecordSettingBounds.clampWait(it)) },
        )
```

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt
git commit -m "feat(settings): tap a stepper value to enter it directly (Clip/Repeats/Wait)"
```

---

## Task 4: "View settings" gear → Info icon (B)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt`, `app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt`

- [ ] **Step 1: Add a `Details` concept to RovaIcons** (stock Material Info behind the alias map — bespoke redraw is a later slice)

In `RovaIcons.kt`, add import `import androidx.compose.material.icons.outlined.Info` and in the stock block:
```kotlin
    val Details = RovaIcon(Icons.Outlined.Info)
```
Add to `RovaIconsTest.kt`:
```kotlin
    @Test fun details_is_a_setting_not_a_status() {
        assertNull(RovaIcons.Details.status)
    }
```

- [ ] **Step 2: Swap the row icon** in `LibraryItemSheet.kt` (line ~138)

Replace:
```kotlin
                SheetRow(Icons.Filled.Settings, viewSettingsLabel) { onViewSettings() }
```
with:
```kotlin
                SheetRow(RovaIcons.Details.glyph, viewSettingsLabel) { onViewSettings() }
```
Remove the now-unused `import androidx.compose.material.icons.filled.Settings` (verify no other use in the file first). Add `import com.aritr.rova.ui.theme.RovaIcons` if not already present (Task 6/earlier slice added it).

- [ ] **Step 3: Build check deferred to Task 7. Commit**
```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt
git commit -m "feat(icons): View-settings row uses Info glyph, not a gear (RovaIcons.Details)"
```

---

## Task 5: DualShot vault row — greyed + reason instead of hidden (C)

**Files:**
- Modify: `app/.../ui/library/components/LibraryItemSheet.kt`, `app/.../ui/library/LibraryScreen.kt`, both `strings.xml`

- [ ] **Step 1: Reason strings (both locales)**

`values/strings.xml`:
```xml
<string name="library_action_vault_unavailable_dualshot">DualShot recordings can\'t be moved to the vault</string>
```
`values-es/strings.xml`:
```xml
<string name="library_action_vault_unavailable_dualshot">Las grabaciones DualShot no se pueden mover a la bóveda</string>
```

- [ ] **Step 2: Add a disabled `SheetRow` variant**

In `LibraryItemSheet.kt`, extend the `RovaGlyph` `SheetRow` overload to support a disabled state with a reason subtext:
```kotlin
@Composable
private fun SheetRow(
    glyph: RovaGlyph,
    label: String,
    enabled: Boolean = true,
    reason: String? = null,
    onClick: () -> Unit,
) {
    val baseColor = MaterialTheme.colorScheme.onSurface
    val contentColor = if (enabled) baseColor else baseColor.copy(alpha = 0.38f)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        SemanticIcon(
            glyph = glyph,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            role = if (enabled) IconRole.Default else IconRole.Disabled,
        )
        Spacer(Modifier.width(20.dp))
        Column {
            Text(label, color = contentColor)
            if (!enabled && reason != null) {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = contentColor)
            }
        }
    }
}
```
Add imports: `import androidx.compose.ui.semantics.disabled`, `import com.aritr.rova.ui.theme.IconRole`.

- [ ] **Step 3: Always render the vault row** (line ~139)

Replace:
```kotlin
                if (movable) SheetRow(RovaIcons.Vault, vaultLabel) { onMoveToVault() }
```
with:
```kotlin
                SheetRow(
                    glyph = RovaIcons.Vault,
                    label = vaultLabel,
                    enabled = movable,
                    reason = vaultUnavailableReason,
                ) { onMoveToVault() }
```
Add a `vaultUnavailableReason: String?` param to `LibraryItemSheet(...)` (near `vaultLabel`).

- [ ] **Step 4: Pass the reason from `LibraryScreen.kt`** (the `LibraryItemSheet(...)` call ~line 792)

Add after `vaultLabel = …`:
```kotlin
                vaultUnavailableReason = stringResource(R.string.library_action_vault_unavailable_dualshot),
```
`movable` already correctly resolves to false for DualShot — keep it.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "fix(library): DualShot vault row shows greyed + reason instead of hidden"
```

---

## Task 6: Compact the item sheet (A)

**Files:**
- Modify: `app/.../ui/library/components/LibraryItemSheet.kt`

UI-only — eyeball-tuned, verified on device (Task 7).

- [ ] **Step 1: Drop the drag-handle Box** (lines 113-120) — a floating card needs no attached handle; removing it reclaims ~16dp and reads less heavy.

- [ ] **Step 2: Slim the header** — in `SheetHeader`, reduce the thumbnail from `64×40` to `48×30` and the row padding `vertical = 8.dp` → `6.dp`.

- [ ] **Step 3: Tighten rows** — both `SheetRow` overloads: `heightIn(min = 48.dp)` → `heightIn(min = 44.dp)` (still ≥24dp AA, `checkA11yTargetSizeToken`), `padding(horizontal = 24.dp)` → `20.dp` (already done for the glyph overload in Task 5; apply the same to the `ImageVector` overload). Reduce the outer `Column(Modifier.padding(vertical = 8.dp))` → `vertical = 4.dp`.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt
git commit -m "refactor(library): compact the item sheet (slimmer header, 44dp rows, no drag-handle)"
```

---

## Task 7: Build + gates + JVM suite + docs + device smoke

- [ ] **Step 1: Gated build + tests** — `./gradlew :app:assembleDebug` (45 gates) + `./gradlew :app:testDebugUnitTest`. **Confirm `:app:packageDebug` shows EXECUTED and the APK mtime is fresh** before installing (Slice-3 lesson: a timed-out assemble can leave a stale APK). Use `assembleDebug`, not `lintDebug`.

- [ ] **Step 2: Docs + memory** — note the polish in the icon-system memory follow-up line; if a HANDOFF/BACKLOG entry exists for this, update it.

- [ ] **Step 3: Device smoke** (controller drives adb via PowerShell):
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aritr.rova/.MainActivity
```
Verify: (D) tapping Clip/Repeats/Wait values opens the dialog, entry clamps to bounds, +/− still work; (B) View-settings row shows an Info glyph; (C) on a DualShot item the Vault row is visible but greyed with the reason; (A) the sheet is visibly more compact. Owner GO required before merge.

---

## Self-Review

- **Coverage:** D (Tasks 1-3), B (Task 4), C (Task 5), A (Task 6). All four owner items.
- **Bounds safety:** direct entry clamps via the SAME `RecordSettingBounds` constants the steppers use — no out-of-range value can be set; non-numeric/blank disables confirm. `∞` repeats unaffected (still `−`-at-min).
- **Gates:** new strings in en+es (`checkNoHardcodedUiStrings`); disabled row keeps `Role.Button` + `disabled()` (`checkA11yClickableHasRole`); 44dp ≥ 24dp (`checkA11yTargetSizeToken`); icons via the seam (`checkSemanticIconNoRawAlpha`). No new gate.
- **Type consistency:** `StepperRow(... editValue: Int?, onSetValue: ((Int)->Unit)?)`; `SheetRow(glyph, label, enabled, reason, onClick)`; `LibraryItemSheet(... vaultUnavailableReason: String?)`; `RovaIcons.Details: RovaIcon`.
