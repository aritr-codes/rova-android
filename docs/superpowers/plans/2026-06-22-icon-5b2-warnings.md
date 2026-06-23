# Icon 5b-2 — Warnings glyph wiring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route every warning-cause icon through a System-D `RovaGlyph` via one pure `WarningIconSpec`, preserving the existing per-severity glow/tint.

**Architecture:** A pure `WarningIconSpec.glyphFor(WarningId): RovaGlyph` is the single source of the concept→glyph choice; both `warningSheetContent` and `midRecBannerContent` carry the resolved glyph on their content data class. The two render sites (`IconWithGlow`, banner) draw the glyph's two layers monochrome in the existing severity color (NOT via `SemanticIcon` — warnings keep their severity-tint system, a documented ADR-0031 §4 exception). Zero new strings, zero new glyphs (all concepts already in `RovaIcons`).

**Tech Stack:** Kotlin, Jetpack Compose, JUnit (JVM unit tests under `isReturnDefaultValues = true`).

## Global Constraints

- Branch/worktree off `master` (`1910f8e`). Subagents are EDIT-ONLY; the controller runs all gradle/tests/commits.
- 46 custom `check*` gates + full `:app:testDebugUnitTest` green at every commit. Never edit a `check*` to pass.
- Build WARM: `gradlew.bat :app:assembleDebug` (no cache wipe). Gate-build with `:app:assembleDebug` (lintDebug is RED on a pre-existing unrelated NewApi).
- No new user-facing strings (warning-cause icons are decorative beside their title → `contentDescription = null`).
- Glyph tint at warning render sites stays theme-derived (`accent`, `severityColor.copy(...)`) — gate-legal; never a raw `Color` literal, never `RovaSemantics.*.copy(...)`.
- codex MCP peer review on the data-class/render refactor and the ADR amendment before the final commit.

---

### Task 1: Pure `WarningIconSpec` + JVM test

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/warnings/WarningIconSpec.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/warnings/WarningIconSpecTest.kt`

**Interfaces:**
- Consumes: `com.aritr.rova.ui.theme.RovaGlyph`, `com.aritr.rova.ui.theme.RovaIcons`, `WarningId`.
- Produces: `WarningIconSpec.glyphFor(id: WarningId): RovaGlyph` — used by Task 2.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.warnings

import com.aritr.rova.ui.theme.RovaIcons
import org.junit.Assert.assertSame
import org.junit.Test

class WarningIconSpecTest {

    @Test fun every_warning_id_resolves_without_throwing() {
        // Exhaustiveness guard: a new WarningId with no arm makes glyphFor's `when` fail to compile;
        // this also proves no arm throws at runtime.
        WarningId.values().forEach { WarningIconSpec.glyphFor(it) }
    }

    @Test fun camera_permission_denied_is_the_no_slash_camera() {
        assertSame(RovaIcons.CameraPermission, WarningIconSpec.glyphFor(WarningId.CAMERA_PERMISSION_DENIED))
    }

    @Test fun camera_in_use_and_disabled_are_the_slashed_camera() {
        assertSame(RovaIcons.CameraOff, WarningIconSpec.glyphFor(WarningId.CAMERA_IN_USE))
        assertSame(RovaIcons.CameraOff, WarningIconSpec.glyphFor(WarningId.CAMERA_DISABLED))
    }

    @Test fun all_five_thermal_tiers_and_autostop_share_the_thermal_glyph() {
        listOf(
            WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL,
            WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE, WarningId.THERMAL_AUTOSTOPPED,
        ).forEach { assertSame(RovaIcons.Thermal, WarningIconSpec.glyphFor(it)) }
    }

    @Test fun both_battery_tiers_share_the_battery_low_glyph() {
        assertSame(RovaIcons.BatteryLow, WarningIconSpec.glyphFor(WarningId.BATTERY_CRITICAL))
        assertSame(RovaIcons.BatteryLow, WarningIconSpec.glyphFor(WarningId.BATTERY_LOW))
    }

    @Test fun storage_family_is_storage_but_save_folder_is_folder() {
        listOf(
            WarningId.STORAGE_INSUFFICIENT, WarningId.STORAGE_LOW_MID_REC,
            WarningId.STORAGE_FULL_AUTOSTOPPED, WarningId.CANT_MERGE,
        ).forEach { assertSame(RovaIcons.Storage, WarningIconSpec.glyphFor(it)) }
        assertSame(RovaIcons.Folder, WarningIconSpec.glyphFor(WarningId.SAVE_FOLDER_UNAVAILABLE))
    }

    @Test fun permission_and_power_concepts_map_to_their_glyphs() {
        assertSame(RovaIcons.AlarmOff, WarningIconSpec.glyphFor(WarningId.EXACT_ALARM_DENIED))
        assertSame(RovaIcons.MicOff, WarningIconSpec.glyphFor(WarningId.MICROPHONE_DENIED))
        assertSame(RovaIcons.NotificationsOff, WarningIconSpec.glyphFor(WarningId.NOTIFICATIONS_DENIED))
        assertSame(RovaIcons.BatterySaver, WarningIconSpec.glyphFor(WarningId.BATTERY_OPTIMIZATION_ON))
        assertSame(RovaIcons.PowerMode, WarningIconSpec.glyphFor(WarningId.POWER_SAVE_MODE))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningIconSpecTest"`
Expected: FAIL — `WarningIconSpec` unresolved (compile error).

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.ui.warnings

import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.RovaIcons

/**
 * Pure concept→glyph choice for the Warnings surface (ADR-0031, UI Phase 2 PR-5b-2). One glyph per
 * [WarningId], surface-independent — the sheet and the mid-recording banner agree. Compose/Android-free
 * so it is JVM-unit-testable under `isReturnDefaultValues`, mirroring [com.aritr.rova.ui.library.components.LibraryIconSpec].
 *
 * The glyph is the System-D *identity*; the *tint* stays the warning-severity system (per-tier
 * RovaWarnings + glow) at the render site — the ADR-0031 §4 severity-tint exception.
 */
internal object WarningIconSpec {
    fun glyphFor(id: WarningId): RovaGlyph = when (id) {
        WarningId.CAMERA_PERMISSION_DENIED -> RovaIcons.CameraPermission
        WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED -> RovaIcons.CameraOff
        WarningId.EXACT_ALARM_DENIED -> RovaIcons.AlarmOff
        WarningId.STORAGE_INSUFFICIENT, WarningId.STORAGE_LOW_MID_REC,
        WarningId.STORAGE_FULL_AUTOSTOPPED, WarningId.CANT_MERGE -> RovaIcons.Storage
        WarningId.SAVE_FOLDER_UNAVAILABLE -> RovaIcons.Folder
        WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL,
        WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE, WarningId.THERMAL_AUTOSTOPPED -> RovaIcons.Thermal
        WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW -> RovaIcons.BatteryLow
        WarningId.MICROPHONE_DENIED -> RovaIcons.MicOff
        WarningId.NOTIFICATIONS_DENIED -> RovaIcons.NotificationsOff
        WarningId.BATTERY_OPTIMIZATION_ON -> RovaIcons.BatterySaver
        WarningId.POWER_SAVE_MODE -> RovaIcons.PowerMode
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningIconSpecTest"`
Expected: PASS (all 7 tests).

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningIconSpec.kt app/src/test/java/com/aritr/rova/ui/warnings/WarningIconSpecTest.kt
git commit -m "feat(icon): 5b-2 WarningIconSpec — pure WarningId→RovaGlyph map + JVM test"
```

---

### Task 2: Carry the glyph on the content data classes

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt`

**Interfaces:**
- Consumes: `WarningIconSpec.glyphFor` (Task 1), `com.aritr.rova.ui.theme.RovaGlyph`.
- Produces: `WarningSheetContent.glyph: RovaGlyph`, `TopBannerContent.glyph: RovaGlyph` (consumed by Task 3).

- [ ] **Step 1: Change both data-class fields from `icon: ImageVector` to `glyph: RovaGlyph`**

In `WarningSheetContent` (line ~87): replace `val icon: ImageVector,` with `val glyph: RovaGlyph,`.
In `TopBannerContent` (line ~230): replace `val icon: ImageVector,` with `val glyph: RovaGlyph,`.

- [ ] **Step 2: Replace every arm's first positional icon argument with the spec call**

In `warningSheetContent(id)` (21 arms) AND `midRecBannerContent(id)` (12 mapped arms): each arm's leading `Icons.Default.<X>` becomes `WarningIconSpec.glyphFor(id)`. The `id` is the `when` subject, in scope in every arm. Example — the first sheet arm becomes:

```kotlin
WarningId.CAMERA_PERMISSION_DENIED -> WarningSheetContent(
    WarningIconSpec.glyphFor(id), R.string.warning_camera_perm_title,
    R.string.warning_camera_perm_body,
    WarningAction(R.string.warning_camera_perm_primary, ActionTarget.APP_DETAILS_SETTINGS),
    WarningAction(R.string.warning_action_not_now, ActionTarget.APP_DETAILS_SETTINGS),
)
```

and the first banner arm:

```kotlin
WarningId.THERMAL_SHUTDOWN -> TopBannerContent(
    WarningIconSpec.glyphFor(id), R.string.warning_banner_thermal_shutdown_title,
    R.string.warning_banner_thermal_shutdown_sub, R.string.warning_banner_cta_stop,
    autoAction = AutoAction(
        secondsRemaining = 30,
        description = R.string.warning_banner_auto_stop_protect,
    ),
)
```

Apply the identical `Icons.Default.<X>` → `WarningIconSpec.glyphFor(id)` substitution to ALL arms (the defensive sheet arms for `STORAGE_LOW_MID_REC` / `STORAGE_FULL_AUTOSTOPPED` / `THERMAL_AUTOSTOPPED` keep their comments and `null` secondary). The `error(...)` non-TopBanner arm in `midRecBannerContent` is unchanged.

- [ ] **Step 3: Remove the now-unused Material icon imports**

Delete these import lines (lines ~3–13):
```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.ui.graphics.vector.ImageVector
```
Add: `import com.aritr.rova.ui.theme.RovaGlyph`.

- [ ] **Step 4: Compile (the render sites still reference `.icon` — expected to fail here)**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: FAIL in `WarningSheetV3.kt` / `WarningTopBannerV3.kt` (`content.icon` unresolved) — fixed in Task 3. (If it instead fails inside `WarningSheetContent.kt`, an arm was missed — fix it.)

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt
git commit -m "refactor(icon): 5b-2 warning content carries RovaGlyph from WarningIconSpec"
```

---

### Task 3: Render the glyph (two layers, severity tint) at both sites

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetV3.kt` (`IconWithGlow` + its call)
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningTopBannerV3.kt` (banner icon block)

**Interfaces:**
- Consumes: `WarningSheetContent.glyph`, `TopBannerContent.glyph` (Task 2), `com.aritr.rova.ui.theme.RovaGlyph`.

- [ ] **Step 1: `WarningSheetV3` — change `IconWithGlow` to take a glyph and render two layers**

Update the call (line ~144) from `IconWithGlow(icon = content.icon, accent = accent)` to `IconWithGlow(glyph = content.glyph, accent = accent)`.

Change the `IconWithGlow` signature (line ~233) `icon: ImageVector` → `glyph: RovaGlyph`, and replace the single `Icon(...)` at line ~278 with:

```kotlin
Box(modifier = Modifier.size(24.dp)) {
    Icon(glyph.outline, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = accent)
    glyph.accent?.let { acc ->
        Icon(acc, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = accent)
    }
}
```

Add imports if missing: `androidx.compose.foundation.layout.fillMaxSize`, `androidx.compose.foundation.layout.size`, `androidx.compose.ui.unit.dp`, `com.aritr.rova.ui.theme.RovaGlyph`. Remove the now-unused `androidx.compose.ui.graphics.vector.ImageVector` import only if nothing else in the file uses it.

- [ ] **Step 2: `WarningTopBannerV3` — render the glyph's two layers at the banner severity tint**

Replace the `Icon(...)` block at lines ~90–95 with:

```kotlin
Box(modifier = Modifier.size(18.dp)) {
    Icon(content.glyph.outline, contentDescription = null, modifier = Modifier.fillMaxSize(),
        tint = severityColor.copy(alpha = 0.95f))
    content.glyph.accent?.let { acc ->
        Icon(acc, contentDescription = null, modifier = Modifier.fillMaxSize(),
            tint = severityColor.copy(alpha = 0.95f))
    }
}
```

Add imports if missing: `androidx.compose.foundation.layout.fillMaxSize`. (`size` / `dp` are already used by the file.)

- [ ] **Step 3: Build + full gate/test run**

Run: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all 46 `check*` gates pass (esp. `checkSemanticIconNoRawAlpha`, `checkStatusColorLocked`); all unit tests green.
If `checkSemanticIconNoRawAlpha` fires: confirm each new `Icon` tint is `accent` or `severityColor.copy(...)` — never a literal `Color.*`.

- [ ] **Step 4: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetV3.kt app/src/main/java/com/aritr/rova/ui/warnings/WarningTopBannerV3.kt
git commit -m "feat(icon): 5b-2 render warning glyphs (two-layer, severity tint) on sheet + banner"
```

---

### Task 4: ADR-0031 §4 severity-tint exception note

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/docs/adr/0031-icon-glyph-system.md` → actually `docs/adr/0031-icon-glyph-system.md`

**Interfaces:** none (docs).

- [ ] **Step 1: Add the exception clause under §4**

Append to ADR-0031 §4 (the SemanticIcon-seam section) a clause, verbatim:

```markdown
**Exception — Warnings severity tint (UI Phase 2 5b-2).** The Warnings surface
(`WarningSheetV3` / `WarningTopBannerV3`) takes its glyph *identity* from
`RovaIcons` (via the pure `WarningIconSpec`) but does NOT route color through
`SemanticIcon`. Its glyphs render monochrome in the per-tier warning-severity
color (`RovaWarnings` + glow), which is the warning's primary signal and is
finer-grained (five thermal tiers) than `IconStatus`'s flat Warning/Danger.
These tints are theme-derived (`accent`, `severityColor`), so
`checkSemanticIconNoRawAlpha` permits them. Do not "fix" this into `SemanticIcon`.
```

- [ ] **Step 2: codex review of the full slice**

Run a `mcp__codex__codex` review of the data-class refactor + render change + this ADR note (concise: "review the 5b-2 warning glyph migration for correctness and gate-safety"). Fold in any blocking finding.

- [ ] **Step 3: Commit**

```
git add docs/adr/0031-icon-glyph-system.md
git commit -m "docs(adr): 0031 §4 — Warnings severity-tint exception (5b-2)"
```

---

## Verification (end of slice)

- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest` — BUILD SUCCESSFUL, 46 gates + all tests green.
- Confirm `:app:packageDebug` EXECUTED + fresh APK mtime, then `adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk`.
- Device smoke (Warnings NOT FLAG_SECURE → `screencap` works): trigger a hard-block (deny camera), an advisory (notifications off / battery-opt), and a mid-rec banner (storage/thermal echo) — confirm System-D glyphs render with severity color + glow. Owner visual confirm of the @18dp board glyphs (Thermal/Storage/BatteryLow) is final.

## Self-review notes

- Spec coverage: WarningIconSpec (Task 1), data-class glyph (Task 2), render+tint exception (Task 3), ADR note (Task 4) — all spec items covered. No new strings (decorative). Chrome icons untouched (identity-only fence).
- Type consistency: field name `glyph: RovaGlyph` used identically across data classes + render sites; `WarningIconSpec.glyphFor` signature matches test and call sites.
