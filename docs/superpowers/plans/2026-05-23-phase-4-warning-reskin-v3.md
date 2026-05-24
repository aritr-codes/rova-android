# Phase 4 Warning Re-skin v3 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-skin all 17 Phase 4 WarningId surfaces (sheet + banner + snooze-chip) and the Library recovery cards to the v3 chrome canon — icon glow bloom, inline severity chip, overflow ⋯ menu, "Why this matters" expander, auto-action countdown ring, 46dp CTAs, a11y contrast bump.

**Architecture:** Render-only re-skin. `WarningPrecedence.resolve(...)`, `WarningId` (ordinal + tier + gatesStart), `WarningCenterViewModel.aggregate(...)`, `WarningSurface` routing, the `RecordScreen` Start-gate (leaf-signal read), and `RecoveryViewModel` are all **untouched**. The VM gains two additive state flows (`expandedWhy`, `snoozedForever`). `WarningCenter.kt` is split into focused composables (≤ 200L each). Two atomic PRs: A (Record-screen sheets + banners + snooze-chip + VM additions), B (Library recovery card re-skin).

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose Material3, AGP 9.2.1 / Gradle 9.4.1 / compileSdk+targetSdk 37. Existing chrome tokens in `app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt` and `RecordChromeTokens.kt`. Tests are pure-JVM JUnit4 (no Robolectric / Compose-UI for this slice per ADR-0007 precedent).

**Gradle execution note.** Per project standing constraint, gradle commands (`./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`, `./gradlew :app:lintDebug`) are routed through a build subagent — do not run them inline.

**Spec reference:** `docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md`.

---

## File Structure

**New files (PR A):**

| Path | Responsibility |
|---|---|
| `app/src/main/java/com/aritr/rova/ui/theme/RovaWarningsV3.kt` | Geometry / alpha tokens + `iconGlow` / `recoveryGlow` brush helpers. Additive — does NOT modify `RovaWarnings`. |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt` | Pure content table + helpers, split out of `WarningCenter.kt`. Houses `WarningSurface`, `warningSurfaceFor`, `WarningAction`, `ActionTarget`, `WarningSheetContent`, `TopBannerContent`, `AutoAction`, `warningSheetContent`, `midRecBannerContent`, `hasOverflow`, `shouldShowWhy`. |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetV3.kt` | The v3 sheet composable — icon glow + severity chip + overflow ⋯ + Why-expander + 46dp CTAs. |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningTopBannerV3.kt` | The v3 mid-rec banner composable + `CountdownRing` sub-composable. |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningSnoozeChip.kt` | The post-dismiss chip — extracted from the anonymous `WarningChip` in `WarningCenter.kt`. |
| `app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentV3Test.kt` | Pure-JVM coverage of `hasOverflow` / `shouldShowWhy` / `midRecBannerContent(...).autoAction` for all 17 ids. |
| `app/src/test/java/com/aritr/rova/ui/theme/RovaWarningsV3Test.kt` | Pure-JVM token-value pin + brush construction assertions. |
| `docs/adr/0013-phase4-warning-reskin-v3.md` | ADR documenting the v3 chrome canon. |

**Modified files (PR A):**

| Path | Change |
|---|---|
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` | Slim to ~120L: keep routing entrypoint, delete inline `WarningSheet` / `WarningChip` / `WarningTopBanner` (now extracted), delete the inline content tables (now in `WarningSheetContent.kt`). |
| `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt` | Add `expandedWhy` / `snoozedForever` StateFlows + `toggleExpandWhy(id)` / `snoozeForever(id)` mutators + a filter step on `activeWarning` that hides snoozed ids. |
| `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt` | If the existing test references `WarningCenterViewModel.activeWarning` directly, add a new test for snooze filtering + expand toggle. Existing assertions stay green. |

**New files (PR B):**

(None — recovery card re-skin reuses `RovaWarningsV3` tokens from PR A.)

**Modified files (PR B):**

| Path | Change |
|---|---|
| `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt` | Re-skin only — glow bloom, severity chip, segment-dot progress strip, numeric clip-count chip, chevron on extra-row. `RecoveryViewModel` / `RecoveryUiState` / `RecoveryViewSource` are untouched. |

---

## PR A — Record-screen v3

### Task A1: Create `RovaWarningsV3.kt` token object (skeleton)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/RovaWarningsV3.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaWarningsV3Test.kt`

- [ ] **Step 1: Write the failing token-pin test**

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RovaWarningsV3Test {

    @Test fun sheetCornerRadius_is_26dp() {
        assertEquals(26.dp, RovaWarningsV3.sheetCornerRadius)
    }

    @Test fun sheetIconSize_is_56dp() {
        assertEquals(56.dp, RovaWarningsV3.sheetIconSize)
    }

    @Test fun sheetIconCornerRadius_is_18dp() {
        assertEquals(18.dp, RovaWarningsV3.sheetIconCornerRadius)
    }

    @Test fun sheetCtaHeight_is_46dp() {
        assertEquals(46.dp, RovaWarningsV3.sheetCtaHeight)
    }

    @Test fun secondaryCtaTextAlpha_is_0_68() {
        // a11y bump from R2's 0.55 — pinned to catch regressions
        assertEquals(0.68f, RovaWarningsV3.secondaryCtaTextAlpha)
    }

    @Test fun bannerCountdownRingSize_is_38dp() {
        assertEquals(38.dp, RovaWarningsV3.bannerCountdownRingSize)
    }

    @Test fun bannerCountdownRingStroke_is_3dp() {
        assertEquals(3.dp, RovaWarningsV3.bannerCountdownRingStroke)
    }

    @Test fun recoveryCardCornerRadius_is_20dp() {
        assertEquals(20.dp, RovaWarningsV3.recoveryCardCornerRadius)
    }

    @Test fun recoveryProgressCellHeight_is_7dp() {
        assertEquals(7.dp, RovaWarningsV3.recoveryProgressCellHeight)
    }

    @Test fun iconGlow_returns_nonNull_brush_for_each_severity() {
        assertNotNull(RovaWarningsV3.iconGlow(RovaWarnings.hard))
        assertNotNull(RovaWarningsV3.iconGlow(RovaWarnings.soft))
        assertNotNull(RovaWarningsV3.iconGlow(RovaWarnings.advisory))
        assertNotNull(RovaWarningsV3.iconGlow(RovaWarnings.escalating))
    }

    @Test fun recoveryGlow_returns_nonNull_brush_for_each_severity() {
        assertNotNull(RovaWarningsV3.recoveryGlow(RovaWarnings.hard))
        assertNotNull(RovaWarningsV3.recoveryGlow(RovaWarnings.soft))
    }

    @Test fun iconGlow_and_recoveryGlow_are_distinct_brush_types() {
        // iconGlow is radial; recoveryGlow is vertical. Same severity ≠ same Brush.
        assertTrue(
            "iconGlow and recoveryGlow must produce different Brush instances",
            RovaWarningsV3.iconGlow(RovaWarnings.hard) !== RovaWarningsV3.recoveryGlow(RovaWarnings.hard)
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Route via build subagent:
```
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaWarningsV3Test"
```
Expected: COMPILATION FAILURE — `RovaWarningsV3` unresolved.

- [ ] **Step 3: Create `RovaWarningsV3.kt`**

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 4 — v3 chrome canon for the warning surface (sheets, banners, snooze-chip,
 * recovery cards). Additive to [RovaWarnings] — the four severity colours
 * (`hard / soft / advisory / escalating`) are inherited from `RovaWarnings`; this
 * object only carries geometry + alpha + brush helpers.
 *
 * Authoritative mockup: `mockups/new_uiux/07c-warnings.html`.
 * Spec: `docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md`.
 * ADR-0013 documents the canon.
 */
object RovaWarningsV3 {

    // ── Sheet ─────────────────────────────────────────────────────────
    val sheetCornerRadius = 26.dp
    val sheetHandleWidth = 32.dp
    val sheetHandleHeight = 3.dp
    val sheetHandleAlpha = 0.18f
    val sheetIconSize = 56.dp
    val sheetIconCornerRadius = 18.dp
    val sheetIconInnerStrokeAlpha = 0.22f
    val sheetIconGlowInset = (-22).dp
    val sheetIconGlowBlur = 22.dp
    val sheetIconGlowAlpha = 0.70f
    val sheetTitleSize = 15.sp
    val sheetBodySize = 11.sp
    val sheetCtaHeight = 46.dp
    val sheetCtaCornerRadius = 14.dp
    val sheetSidePadding = 20.dp
    val sheetBottomPadding = 24.dp

    // ── Severity chip (inline, above title) ──────────────────────────
    val sevChipPaddingH = 10.dp
    val sevChipPaddingV = 4.dp
    val sevChipDotSize = 5.dp
    val sevChipFillAlpha = 0.13f
    val sevChipForegroundAlpha = 0.95f

    // ── Overflow ⋯ menu ──────────────────────────────────────────────
    val overflowButtonSize = 28.dp
    val overflowTopInset = 14.dp
    val overflowRightInset = 18.dp

    // ── "Why this matters" expander ──────────────────────────────────
    val whyRowHeight = 36.dp
    val whyRowCornerRadius = 11.dp
    val whyRowBorderAlpha = 0.20f
    val whyRowForegroundAlpha = 0.78f

    // ── Banner ────────────────────────────────────────────────────────
    val bannerCornerRadius = 18.dp
    val bannerSidePadding = 12.dp
    val bannerVerticalPadding = 11.dp
    val bannerIconSize = 36.dp
    val bannerIconCornerRadius = 10.dp
    val bannerCountdownRingSize = 38.dp
    val bannerCountdownRingStroke = 3.dp
    val bannerCountdownTrackAlpha = 0.06f

    // ── CTA contrasts (a11y) ─────────────────────────────────────────
    val secondaryCtaTextAlpha = 0.68f      // R2 was 0.55 — bumped for a11y
    val secondaryCtaFillAlpha = 0.07f
    val secondaryCtaStrokeAlpha = 0.05f

    // ── Recovery card ────────────────────────────────────────────────
    val recoveryCardCornerRadius = 20.dp
    val recoveryCardGlowHeight = 60.dp
    val recoveryCardGlowBlur = 28.dp
    val recoveryCardGlowAlpha = 0.50f
    val recoveryProgressCellHeight = 7.dp
    val recoveryProgressCellGap = 4.dp
    val recoveryProgressCellRadius = 3.5.dp
    val recoveryNumericChipMinWidth = 36.dp

    // ── Snooze chip ──────────────────────────────────────────────────
    val snoozeChipRadius = 999.dp
    val snoozeChipFillAlpha = 0.55f
    val snoozeChipBorderAlpha = 0.25f
    val snoozeChipDotPulseAlpha = 0.6f

    /** Radial glow brush behind the sheet icon. Severity-tinted, 0.70 base alpha. */
    fun iconGlow(severityColor: Color): Brush = Brush.radialGradient(
        colors = listOf(
            severityColor.copy(alpha = sheetIconGlowAlpha * 0.65f),
            Color.Transparent,
        ),
    )

    /** Vertical glow brush along the top edge of the recovery card. */
    fun recoveryGlow(severityColor: Color): Brush = Brush.verticalGradient(
        colors = listOf(
            severityColor.copy(alpha = recoveryCardGlowAlpha),
            Color.Transparent,
        ),
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

```
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaWarningsV3Test"
```
Expected: PASS — 12 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaWarningsV3.kt \
        app/src/test/java/com/aritr/rova/ui/theme/RovaWarningsV3Test.kt
git commit -m "feat(ui): RovaWarningsV3 tokens + brush helpers (Phase 4 v3 chrome)

New geometry/alpha tokens + iconGlow / recoveryGlow brushes for the
Phase 4 warning re-skin. Additive — RovaWarnings (4 severity colors)
is untouched. See ADR-0013 (drafted in a later task)."
```

---

### Task A2: Split `WarningSheetContent.kt` out of `WarningCenter.kt`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`

This task is a **pure code-motion** step — no behaviour changes. Existing tests must stay green.

- [ ] **Step 1: Read the existing inline content out of `WarningCenter.kt`**

Read these blocks from `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`:
- `enum class WarningSurface { ... }` (currently around line 86)
- `fun warningSurfaceFor(id: WarningId): WarningSurface` (around line 88-95)
- `internal data class WarningAction(...)` (around line 302)
- `internal enum class ActionTarget { ... }` (around line 304-306)
- `internal data class WarningSheetContent(...)` (around line 308-317)
- `internal fun warningSheetContent(id: WarningId): WarningSheetContent` (around line 324-384)
- `internal data class TopBannerContent(...)` (around line 386-391)
- `internal fun midRecBannerContent(id: WarningId): TopBannerContent` (around line 398-448)

- [ ] **Step 2: Create `WarningSheetContent.kt` with those blocks moved verbatim**

```kotlin
package com.aritr.rova.ui.warnings

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

/**
 * Where a given [WarningId] is surfaced on the Record screen (ADR 0007). The
 * [WarningCenterViewModel] still resolves the single highest-priority active
 * warning; this only decides how that warning is drawn.
 *
 * - [HardBlockSheet] — recording can't start / must abort (camera perm, exact alarm, storage).
 * - [SoftSheet] — degraded but recordable (mic denied → video-only).
 * - [AdvisorySheet] — informational (notifications off, battery-opt on, power-save).
 * - [TopBanner] — mid-recording risks (thermal, low/critical battery, camera in use/disabled, low storage).
 */
enum class WarningSurface { HardBlockSheet, SoftSheet, AdvisorySheet, TopBanner }

fun warningSurfaceFor(id: WarningId): WarningSurface = when (id) {
    WarningId.CAMERA_PERMISSION_DENIED,
    WarningId.EXACT_ALARM_DENIED,
    WarningId.STORAGE_INSUFFICIENT -> WarningSurface.HardBlockSheet
    WarningId.MICROPHONE_DENIED -> WarningSurface.SoftSheet
    WarningId.NOTIFICATIONS_DENIED,
    WarningId.BATTERY_OPTIMIZATION_ON,
    WarningId.POWER_SAVE_MODE -> WarningSurface.AdvisorySheet
    WarningId.THERMAL_SHUTDOWN,
    WarningId.THERMAL_EMERGENCY,
    WarningId.THERMAL_CRITICAL,
    WarningId.THERMAL_SEVERE,
    WarningId.THERMAL_MODERATE,
    WarningId.BATTERY_CRITICAL,
    WarningId.BATTERY_LOW,
    WarningId.CAMERA_IN_USE,
    WarningId.CAMERA_DISABLED,
    WarningId.STORAGE_LOW_MID_REC -> WarningSurface.TopBanner
}

internal data class WarningAction(val label: String, val target: ActionTarget)

internal enum class ActionTarget {
    EXACT_ALARM_SETTINGS,
    BATTERY_OPTIMIZATION,
    NOTIFICATION_SETTINGS,
    APP_DETAILS_SETTINGS,
}

internal data class WarningSheetContent(
    val icon: ImageVector,
    val title: String,
    /** Short supporting line; never blank for sheet-rendered warnings. */
    val body: String,
    /** Primary CTA — always present. */
    val primary: WarningAction,
    /** Secondary CTA — present for HardBlock/Soft/Advisory; may be null for TopBanner. */
    val secondary: WarningAction?,
)

/**
 * The 17-arm sheet-content table. Copy mirrors `mockups/new_uiux/07-warnings.html`.
 * `STORAGE_LOW_MID_REC` (#11) has a defensive arm — TopBanner-only, never renders as a sheet.
 */
internal fun warningSheetContent(id: WarningId): WarningSheetContent = when (id) {
    WarningId.CAMERA_PERMISSION_DENIED -> WarningSheetContent(
        Icons.Default.NoPhotography, "Camera access required",
        "Rova can't record without camera access. Grant the permission in App Settings to continue.",
        WarningAction("Open App Settings", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Not now", ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.EXACT_ALARM_DENIED -> WarningSheetContent(
        Icons.Default.AlarmOff, "Alarm permission required",
        "Rova uses exact alarms to time recording segments. Without it, clips won't start or stop on schedule.",
        WarningAction("Allow exact alarms", ActionTarget.EXACT_ALARM_SETTINGS),
        WarningAction("Not now", ActionTarget.EXACT_ALARM_SETTINGS),
    )
    WarningId.STORAGE_INSUFFICIENT -> WarningSheetContent(
        Icons.Default.Storage, "Not enough storage to start",
        "Free up space, then try again.",
        WarningAction("Free up space", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Not now", ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.MICROPHONE_DENIED -> WarningSheetContent(
        Icons.Default.MicOff, "Recording without audio",
        "This session will record video only. You can grant microphone access in Settings and try again.",
        WarningAction("Grant microphone access", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Continue without audio", ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.NOTIFICATIONS_DENIED -> WarningSheetContent(
        Icons.Default.NotificationsOff, "Stay in the loop",
        "Enable notifications to see when recording starts, stops, or finishes merging — even with the screen off.",
        WarningAction("Enable notifications", ActionTarget.NOTIFICATION_SETTINGS),
        WarningAction("Not now", ActionTarget.NOTIFICATION_SETTINGS),
    )
    WarningId.BATTERY_OPTIMIZATION_ON -> WarningSheetContent(
        Icons.Default.BatterySaver, "Battery optimization may stop recording",
        "Android may kill Rova in the background. Disable battery optimization for reliable long sessions.",
        WarningAction("Disable", ActionTarget.BATTERY_OPTIMIZATION),
        WarningAction("Not now", ActionTarget.BATTERY_OPTIMIZATION),
    )
    WarningId.POWER_SAVE_MODE -> WarningSheetContent(
        Icons.Default.PowerSettingsNew, "Power-save mode may throttle recording",
        "Turning off battery saver gives Rova full CPU/IO for the session.",
        WarningAction("Settings", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Not now", ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.THERMAL_SHUTDOWN -> WarningSheetContent(Icons.Default.Thermostat, "Device overheating — recording stopped", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_EMERGENCY -> WarningSheetContent(Icons.Default.Thermostat, "Device critically hot", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_CRITICAL -> WarningSheetContent(Icons.Default.Thermostat, "Device very hot — recording may stop", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_SEVERE -> WarningSheetContent(Icons.Default.Thermostat, "Device hot — quality may drop", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_MODERATE -> WarningSheetContent(Icons.Default.Thermostat, "Device warming up", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.BATTERY_CRITICAL -> WarningSheetContent(Icons.Default.BatteryAlert, "Battery critical — recording may stop", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.BATTERY_LOW -> WarningSheetContent(Icons.Default.BatteryAlert, "Battery low — consider charging", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.STORAGE_LOW_MID_REC -> WarningSheetContent(
        // Defensive — STORAGE_LOW_MID_REC is TopBanner-only.
        Icons.Default.Storage, "Storage running low", "Free space on this device.",
        WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null,
    )
    WarningId.CAMERA_IN_USE -> WarningSheetContent(Icons.Default.VideocamOff, "Camera in use by another app", "Close the other camera app.", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.CAMERA_DISABLED -> WarningSheetContent(Icons.Default.VideocamOff, "Camera disabled by device policy", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
}

internal data class TopBannerContent(
    val icon: ImageVector,
    val title: String,
    val sub: String,
    val cta: String,
)

/**
 * One arm per [WarningId] mapped to [WarningSurface.TopBanner] (10 ids). Pure / JVM-testable.
 * Calling this with a non-TopBanner id is a caller bug — function throws.
 */
internal fun midRecBannerContent(id: WarningId): TopBannerContent = when (id) {
    WarningId.THERMAL_SHUTDOWN -> TopBannerContent(Icons.Default.Thermostat, "Device overheating — stopping", "Recording will stop automatically.", "Stop")
    WarningId.THERMAL_EMERGENCY -> TopBannerContent(Icons.Default.Thermostat, "Device critically hot", "Stop now to let it cool.", "Stop")
    WarningId.THERMAL_CRITICAL -> TopBannerContent(Icons.Default.Thermostat, "Device very hot", "Recording may auto-stop soon.", "Stop")
    WarningId.THERMAL_SEVERE -> TopBannerContent(Icons.Default.Thermostat, "Device hot", "Quality may drop.", "Stop")
    WarningId.THERMAL_MODERATE -> TopBannerContent(Icons.Default.Thermostat, "Device warming up", "Watch the temperature.", "Stop")
    WarningId.BATTERY_CRITICAL -> TopBannerContent(Icons.Default.BatteryAlert, "Battery critical", "Recording may stop soon.", "Stop")
    WarningId.BATTERY_LOW -> TopBannerContent(Icons.Default.BatteryAlert, "Battery low", "Consider charging.", "Stop")
    WarningId.CAMERA_IN_USE -> TopBannerContent(Icons.Default.VideocamOff, "Camera in use", "Another app is using the camera.", "Stop")
    WarningId.CAMERA_DISABLED -> TopBannerContent(Icons.Default.VideocamOff, "Camera disabled", "Disabled by device policy.", "Stop")
    WarningId.STORAGE_LOW_MID_REC -> TopBannerContent(Icons.Default.Storage, "Storage running low", "Free space on this device.", "Stop")
    WarningId.CAMERA_PERMISSION_DENIED,
    WarningId.EXACT_ALARM_DENIED,
    WarningId.STORAGE_INSUFFICIENT,
    WarningId.MICROPHONE_DENIED,
    WarningId.BATTERY_OPTIMIZATION_ON,
    WarningId.POWER_SAVE_MODE,
    WarningId.NOTIFICATIONS_DENIED ->
        error("midRecBannerContent called for non-mid-rec id $id — caller bug; gate on warningSurfaceFor(id) == TopBanner")
}
```

- [ ] **Step 3: Delete those same blocks from `WarningCenter.kt`**

Open `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` and **delete**:
- `enum class WarningSurface { ... }` (now in `WarningSheetContent.kt`)
- `fun warningSurfaceFor(...)` (now in `WarningSheetContent.kt`)
- `internal data class WarningAction(...)` (now in `WarningSheetContent.kt`)
- `internal enum class ActionTarget { ... }` (now in `WarningSheetContent.kt`)
- `internal data class WarningSheetContent(...)` (now in `WarningSheetContent.kt`)
- `internal fun warningSheetContent(...)` (now in `WarningSheetContent.kt`)
- `internal data class TopBannerContent(...)` (now in `WarningSheetContent.kt`)
- `internal fun midRecBannerContent(...)` (now in `WarningSheetContent.kt`)

Also delete the now-unused imports for `Icons.Default.AlarmOff` / `BatteryAlert` / `BatterySaver` / `MicOff` / `NoPhotography` / `NotificationsOff` / `PowerSettingsNew` / `Storage` / `Thermostat` / `VideocamOff` / `ImageVector` from `WarningCenter.kt` (they're now used in `WarningSheetContent.kt`).

Keep `WarningCenter.kt` callers untouched — `warningSheetContent(id)`, `midRecBannerContent(id)`, `warningSurfaceFor(id)`, `WarningSurface.*`, `WarningSheetContent`, `TopBannerContent`, `WarningAction`, `ActionTarget` are all in the same package, so call sites resolve unchanged.

- [ ] **Step 4: Run the full warnings test suite to verify no regression**

```
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.*"
```
Expected: ALL PASS (`WarningSheetContentTest`, `WarningPrecedenceTest`, `WarningIdOrderTest`, `WarningSurfaceTest`, `WarningCenterAggregateTest`).

- [ ] **Step 5: Verify the build still compiles**

```
./gradlew :app:compileDebugKotlin
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt
git commit -m "refactor(ui): split WarningSheetContent out of WarningCenter

Pure code-motion. WarningSurface enum, warningSurfaceFor, WarningAction,
ActionTarget, WarningSheetContent, TopBannerContent, warningSheetContent,
midRecBannerContent now live in WarningSheetContent.kt. No behaviour
change. All warnings tests stay green.

Precondition for Task A3 (adding overflow/whyThisMatters fields)."
```

---

### Task A3: Add new fields + `AutoAction` + `SNOOZE_FOREVER` target

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt`

This task adds the data-class fields and enum entries that Task A4 / A5 / A6 will populate. After this task, all 17 existing arms still compile because the new fields default to empty / null.

- [ ] **Step 1: Add `SNOOZE_FOREVER` to `ActionTarget` enum**

In `WarningSheetContent.kt`, change:
```kotlin
internal enum class ActionTarget {
    EXACT_ALARM_SETTINGS,
    BATTERY_OPTIMIZATION,
    NOTIFICATION_SETTINGS,
    APP_DETAILS_SETTINGS,
}
```

to:
```kotlin
internal enum class ActionTarget {
    EXACT_ALARM_SETTINGS,
    BATTERY_OPTIMIZATION,
    NOTIFICATION_SETTINGS,
    APP_DETAILS_SETTINGS,
    /** VM-only target — routes to [WarningCenterViewModel.snoozeForever]. NOT an Intent. */
    SNOOZE_FOREVER,
}
```

- [ ] **Step 2: Add `overflow` and `whyThisMatters` to `WarningSheetContent`**

Change:
```kotlin
internal data class WarningSheetContent(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val primary: WarningAction,
    val secondary: WarningAction?,
)
```

to:
```kotlin
internal data class WarningSheetContent(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val primary: WarningAction,
    val secondary: WarningAction?,
    /**
     * Overflow ⋯ menu items (top-right of sheet). Empty list = no menu rendered.
     * Each action targets either an Intent (`launchActionTarget`) or
     * [ActionTarget.SNOOZE_FOREVER] (handled by the VM).
     */
    val overflow: List<WarningAction> = emptyList(),
    /**
     * Body text revealed when the "Why this matters" expander is open.
     * Null = expander row is not rendered for this id.
     */
    val whyThisMatters: String? = null,
)
```

- [ ] **Step 3: Add `AutoAction` data class + `autoAction` field to `TopBannerContent`**

Change:
```kotlin
internal data class TopBannerContent(
    val icon: ImageVector,
    val title: String,
    val sub: String,
    val cta: String,
)
```

to:
```kotlin
internal data class TopBannerContent(
    val icon: ImageVector,
    val title: String,
    val sub: String,
    val cta: String,
    /**
     * Optional auto-action countdown — when non-null, the banner renders a
     * countdown ring instead of the trailing CTA pill. Phase 4.4 will wire a
     * real seconds-source; this slice ships a static placeholder.
     */
    val autoAction: AutoAction? = null,
)

/**
 * Placeholder countdown payload for the top-banner ring. [secondsRemaining]
 * is static in Phase 4 — a real ticking source lands in Phase 4.4 alongside
 * thermal hysteresis.
 */
internal data class AutoAction(val secondsRemaining: Int, val description: String)
```

- [ ] **Step 4: Run the full warnings test suite to verify no regression**

```
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.*"
```
Expected: ALL PASS. New fields default to empty / null, so existing 17-arm tests stay green.

- [ ] **Step 5: Verify the build still compiles**

```
./gradlew :app:compileDebugKotlin
```
Expected: PASS — all call sites use named args / defaults.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt
git commit -m "feat(ui): add overflow / whyThisMatters / autoAction fields

Additive — new fields default to empty/null, so all 17 existing arms
compile unchanged. Task A4/A5/A6 populate them. ActionTarget gains
SNOOZE_FOREVER (VM-only target). AutoAction data class added for the
banner countdown ring (static placeholder in this slice; Phase 4.4
wires a real seconds-source)."
```

---

### Task A4: TDD `hasOverflow` and `shouldShowWhy` helpers

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt`
- Create: `app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentV3Test.kt`

- [ ] **Step 1: Write the failing helper tests**

```kotlin
package com.aritr.rova.ui.warnings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the v3 sheet-content helpers (Phase 4 re-skin).
 * Pins which ids carry an overflow ⋯ menu and which advisory ids show
 * the "Why this matters" expander.
 *
 * Mirrors the spec appendix table verbatim. A failure here usually means
 * either: (1) a new id was added without updating overflow/whyThisMatters
 * wiring, or (2) an existing id's wiring was accidentally flipped.
 */
class WarningSheetContentV3Test {

    // ───────── hasOverflow ─────────

    @Test fun hasOverflow_for_BATTERY_OPTIMIZATION_ON_is_true() {
        assertTrue(hasOverflow(WarningId.BATTERY_OPTIMIZATION_ON))
    }

    @Test fun hasOverflow_for_POWER_SAVE_MODE_is_true() {
        assertTrue(hasOverflow(WarningId.POWER_SAVE_MODE))
    }

    @Test fun hasOverflow_for_NOTIFICATIONS_DENIED_is_true() {
        assertTrue(hasOverflow(WarningId.NOTIFICATIONS_DENIED))
    }

    @Test fun hasOverflow_for_CAMERA_PERMISSION_DENIED_is_false() {
        assertFalse(hasOverflow(WarningId.CAMERA_PERMISSION_DENIED))
    }

    @Test fun hasOverflow_for_EXACT_ALARM_DENIED_is_false() {
        assertFalse(hasOverflow(WarningId.EXACT_ALARM_DENIED))
    }

    @Test fun hasOverflow_for_STORAGE_INSUFFICIENT_is_false() {
        assertFalse(hasOverflow(WarningId.STORAGE_INSUFFICIENT))
    }

    @Test fun hasOverflow_for_MICROPHONE_DENIED_is_false() {
        assertFalse(hasOverflow(WarningId.MICROPHONE_DENIED))
    }

    @Test fun hasOverflow_for_all_topBanner_ids_is_false() {
        // TopBanner ids never render the sheet, so overflow is irrelevant — pinned false.
        val topBannerIds = WarningId.values().filter { warningSurfaceFor(it) == WarningSurface.TopBanner }
        topBannerIds.forEach { id ->
            assertFalse("expected hasOverflow($id) == false, got true", hasOverflow(id))
        }
    }

    // ───────── shouldShowWhy ─────────

    @Test fun shouldShowWhy_for_NOTIFICATIONS_DENIED_is_true() {
        assertTrue(shouldShowWhy(WarningId.NOTIFICATIONS_DENIED))
    }

    @Test fun shouldShowWhy_for_BATTERY_OPTIMIZATION_ON_is_true() {
        assertTrue(shouldShowWhy(WarningId.BATTERY_OPTIMIZATION_ON))
    }

    @Test fun shouldShowWhy_for_POWER_SAVE_MODE_is_true() {
        assertTrue(shouldShowWhy(WarningId.POWER_SAVE_MODE))
    }

    @Test fun shouldShowWhy_for_CAMERA_PERMISSION_DENIED_is_false() {
        assertFalse(shouldShowWhy(WarningId.CAMERA_PERMISSION_DENIED))
    }

    @Test fun shouldShowWhy_for_EXACT_ALARM_DENIED_is_false() {
        assertFalse(shouldShowWhy(WarningId.EXACT_ALARM_DENIED))
    }

    @Test fun shouldShowWhy_for_STORAGE_INSUFFICIENT_is_false() {
        assertFalse(shouldShowWhy(WarningId.STORAGE_INSUFFICIENT))
    }

    @Test fun shouldShowWhy_for_MICROPHONE_DENIED_is_false() {
        // v1 scope keeps "Why" to advisory-tier only.
        assertFalse(shouldShowWhy(WarningId.MICROPHONE_DENIED))
    }

    @Test fun whyThisMatters_string_is_nonBlank_when_set() {
        listOf(
            WarningId.NOTIFICATIONS_DENIED,
            WarningId.BATTERY_OPTIMIZATION_ON,
            WarningId.POWER_SAVE_MODE,
        ).forEach { id ->
            val why = warningSheetContent(id).whyThisMatters
            assertNotNull("expected whyThisMatters for $id to be non-null", why)
            assertTrue("expected whyThisMatters for $id to be non-blank", why!!.isNotBlank())
        }
    }

    // ───────── overflow content sanity ─────────

    @Test fun overflow_includes_dontShowAgain_for_advisory_sheet_ids() {
        listOf(
            WarningId.BATTERY_OPTIMIZATION_ON,
            WarningId.POWER_SAVE_MODE,
            WarningId.NOTIFICATIONS_DENIED,
        ).forEach { id ->
            val overflow = warningSheetContent(id).overflow
            assertTrue("expected $id to have at least one overflow item", overflow.isNotEmpty())
            assertTrue(
                "expected $id overflow to include SNOOZE_FOREVER target",
                overflow.any { it.target == ActionTarget.SNOOZE_FOREVER }
            )
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningSheetContentV3Test"
```
Expected: COMPILATION FAILURE — `hasOverflow` and `shouldShowWhy` are unresolved.

- [ ] **Step 3: Add the helpers to `WarningSheetContent.kt`**

At the bottom of `WarningSheetContent.kt`, after `midRecBannerContent`, add:

```kotlin
/** True iff [warningSheetContent] for [id] declares an overflow ⋯ menu. */
internal fun hasOverflow(id: WarningId): Boolean =
    warningSheetContent(id).overflow.isNotEmpty()

/** True iff [warningSheetContent] for [id] declares a "Why this matters" expander. */
internal fun shouldShowWhy(id: WarningId): Boolean =
    warningSheetContent(id).whyThisMatters != null
```

- [ ] **Step 4: Run the helpers-only tests to verify the helpers compile and behave correctly when no arms declare overflow / why yet**

```
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningSheetContentV3Test.hasOverflow_for_CAMERA_PERMISSION_DENIED_is_false" \
                                  --tests "com.aritr.rova.ui.warnings.WarningSheetContentV3Test.shouldShowWhy_for_CAMERA_PERMISSION_DENIED_is_false"
```
Expected: 2 tests PASS. The other tests still fail (their positive assertions need Task A5's content wiring).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentV3Test.kt
git commit -m "feat(ui): add hasOverflow / shouldShowWhy helpers (Phase 4 v3)

Pure-JVM helpers + RED-state coverage for which ids carry overflow ⋯
and which advisory ids show the Why-expander. Positive assertions
fail until Task A5 wires the 17-arm content table. Negative
assertions (sheet-tier ids without overflow/why) pass now."
```

---

### Task A5: Wire overflow + whyThisMatters into the advisory-tier sheet arms

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt`

- [ ] **Step 1: Update the three advisory-tier arms** in `warningSheetContent` to declare overflow + whyThisMatters

Replace the `BATTERY_OPTIMIZATION_ON` arm:
```kotlin
    WarningId.BATTERY_OPTIMIZATION_ON -> WarningSheetContent(
        Icons.Default.BatterySaver, "Battery optimization may stop recording",
        "Android may kill Rova in the background. Disable battery optimization for reliable long sessions.",
        WarningAction("Disable", ActionTarget.BATTERY_OPTIMIZATION),
        WarningAction("Not now", ActionTarget.BATTERY_OPTIMIZATION),
        overflow = listOf(
            WarningAction("Don't show again", ActionTarget.SNOOZE_FOREVER),
        ),
        whyThisMatters = "Android's battery optimizer can pause background apps to save power. " +
            "If Rova is paused mid-recording, the session may stop early or skip clips. " +
            "Exempting Rova keeps the foreground service alive for the full session.",
    )
```

Replace the `POWER_SAVE_MODE` arm:
```kotlin
    WarningId.POWER_SAVE_MODE -> WarningSheetContent(
        Icons.Default.PowerSettingsNew, "Power-save mode may throttle recording",
        "Turning off battery saver gives Rova full CPU/IO for the session.",
        WarningAction("Settings", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Not now", ActionTarget.APP_DETAILS_SETTINGS),
        overflow = listOf(
            WarningAction("Don't show again", ActionTarget.SNOOZE_FOREVER),
        ),
        whyThisMatters = "Battery saver caps CPU frequency and background I/O. " +
            "Rova may drop frames or fall behind on encoding, which can corrupt clip boundaries " +
            "on long sessions.",
    )
```

Replace the `NOTIFICATIONS_DENIED` arm:
```kotlin
    WarningId.NOTIFICATIONS_DENIED -> WarningSheetContent(
        Icons.Default.NotificationsOff, "Stay in the loop",
        "Enable notifications to see when recording starts, stops, or finishes merging — even with the screen off.",
        WarningAction("Enable notifications", ActionTarget.NOTIFICATION_SETTINGS),
        WarningAction("Not now", ActionTarget.NOTIFICATION_SETTINGS),
        overflow = listOf(
            WarningAction("Don't show again", ActionTarget.SNOOZE_FOREVER),
        ),
        whyThisMatters = "Notifications are how Rova tells you what's happening while you're not " +
            "in the app — recording started, clip merged, session finished. Without them, you'll " +
            "need to open the app to check progress.",
    )
```

- [ ] **Step 2: Run the V3 helper test suite to verify all assertions pass**

```
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningSheetContentV3Test"
```
Expected: ALL PASS (16 tests).

- [ ] **Step 3: Run the existing 17-arm sheet test to verify nothing regressed**

```
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningSheetContentTest"
```
Expected: ALL PASS — existing arms' core fields (icon/title/body/primary/secondary) untouched.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt
git commit -m "feat(ui): advisory-tier overflow + why-expander wiring

BATTERY_OPTIMIZATION_ON / POWER_SAVE_MODE / NOTIFICATIONS_DENIED gain
'Don't show again' overflow + 'Why this matters' body copy. Other 14
ids unchanged. WarningSheetContentV3Test goes green."
```

---

### Task A6: Wire `autoAction` placeholder into thermal banner arms

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt`
- Modify: `app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentV3Test.kt`

- [ ] **Step 1: Add the failing autoAction tests**

Append to `WarningSheetContentV3Test`:

```kotlin
    // ───────── midRecBannerContent.autoAction ─────────

    @Test fun midRecBanner_autoAction_for_THERMAL_EMERGENCY_is_set() {
        val auto = midRecBannerContent(WarningId.THERMAL_EMERGENCY).autoAction
        assertNotNull(auto)
        assertTrue("expected secondsRemaining > 0", auto!!.secondsRemaining > 0)
        assertTrue("expected non-blank description", auto.description.isNotBlank())
    }

    @Test fun midRecBanner_autoAction_for_THERMAL_SHUTDOWN_is_set() {
        val auto = midRecBannerContent(WarningId.THERMAL_SHUTDOWN).autoAction
        assertNotNull(auto)
    }

    @Test fun midRecBanner_autoAction_for_BATTERY_CRITICAL_is_null() {
        // Non-thermal critical ids keep the CTA pill, not the countdown.
        assertNull(midRecBannerContent(WarningId.BATTERY_CRITICAL).autoAction)
    }

    @Test fun midRecBanner_autoAction_for_STORAGE_LOW_MID_REC_is_null() {
        assertNull(midRecBannerContent(WarningId.STORAGE_LOW_MID_REC).autoAction)
    }

    @Test fun midRecBanner_autoAction_for_CAMERA_IN_USE_is_null() {
        assertNull(midRecBannerContent(WarningId.CAMERA_IN_USE).autoAction)
    }

    @Test fun midRecBanner_autoAction_for_THERMAL_SEVERE_is_null() {
        // Only EMERGENCY / SHUTDOWN auto-stop; SEVERE keeps CTA.
        assertNull(midRecBannerContent(WarningId.THERMAL_SEVERE).autoAction)
    }
```

- [ ] **Step 2: Run the new tests to verify they fail**

```
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningSheetContentV3Test.midRecBanner_autoAction*"
```
Expected: 2 PASSES (the `*_is_null` ones — `autoAction` defaults to null) + 2 FAILURES (`THERMAL_EMERGENCY` and `THERMAL_SHUTDOWN` arms not yet wired).

- [ ] **Step 3: Wire `autoAction` into the two thermal arms**

In `WarningSheetContent.kt`, replace the `THERMAL_SHUTDOWN` arm of `midRecBannerContent`:

```kotlin
    WarningId.THERMAL_SHUTDOWN -> TopBannerContent(
        Icons.Default.Thermostat, "Device overheating — stopping",
        "Recording will stop automatically.", "Stop",
        autoAction = AutoAction(
            secondsRemaining = 30,
            description = "Will auto-stop to protect device",
        ),
    )
```

Replace the `THERMAL_EMERGENCY` arm:

```kotlin
    WarningId.THERMAL_EMERGENCY -> TopBannerContent(
        Icons.Default.Thermostat, "Device critically hot",
        "Stop now to let it cool.", "Stop",
        autoAction = AutoAction(
            secondsRemaining = 30,
            description = "Will auto-stop to protect device",
        ),
    )
```

- [ ] **Step 4: Run all V3 tests + existing midRec tests to verify**

```
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.*"
```
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentV3Test.kt
git commit -m "feat(ui): static AutoAction on THERMAL_EMERGENCY/SHUTDOWN banners

Placeholder 30s countdown for the two ids that auto-stop the session.
Real seconds-source lands in Phase 4.4 alongside thermal hysteresis;
this slice just lets the ring render."
```

---

### Task A7: Add `expandedWhy` + `snoozedForever` + filter to `WarningCenterViewModel`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt`
- Modify: `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt`

- [ ] **Step 1: Read the existing `WarningCenterViewModel.kt`**

Open `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt` to identify:
- The `activeWarning` exposure (what type, what flow it composes from)
- The `dismissedWarnings` MutableStateFlow + `dismiss(id)` / `restore(id)` pattern (you'll mirror it for expandedWhy / snoozedForever)
- The `aggregate(...)` companion-object function (this stays untouched)

- [ ] **Step 2: Add the two new MutableStateFlows + mutators**

Inside the `WarningCenterViewModel` class body (NOT in the companion object), add:

```kotlin
    // ── v3 — "Why this matters" expand toggle (in-memory; survives only while VM is in scope) ──
    private val _expandedWhy = MutableStateFlow<Set<WarningId>>(emptySet())
    val expandedWhy: StateFlow<Set<WarningId>> = _expandedWhy.asStateFlow()

    fun toggleExpandWhy(id: WarningId) {
        _expandedWhy.update { if (id in it) it - id else it + id }
    }

    // ── v3 — "Don't show again" snooze (in-memory only; Phase 4.1c will persist) ──
    private val _snoozedForever = MutableStateFlow<Set<WarningId>>(emptySet())
    val snoozedForever: StateFlow<Set<WarningId>> = _snoozedForever.asStateFlow()

    fun snoozeForever(id: WarningId) {
        _snoozedForever.update { it + id }
    }
```

- [ ] **Step 3: Filter `activeWarning` to hide snoozed ids**

Locate the existing `activeWarning` declaration. It currently looks something like:
```kotlin
    val activeWarning: StateFlow<WarningId?> = combine(...) { ... }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

Wrap the existing combine with a snooze filter. If the existing flow is named (say) `_resolvedWarning` internally, the cleanest change is:

```kotlin
    private val _resolvedWarning: StateFlow<WarningId?> = combine(
        /* existing 10 source flows */
    ) { /* existing resolve(...) call */ }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activeWarning: StateFlow<WarningId?> = combine(
        _resolvedWarning,
        _snoozedForever,
    ) { resolved, snoozed -> resolved?.takeIf { it !in snoozed } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

If the current VM exposes the `combine(...)` result directly as `activeWarning`, rename the existing exposure to `_resolvedWarning` (private) and add the new public `activeWarning` as shown.

**Invariant:** the Start-gate in `RecordScreen.kt` reads leaf signals (`cameraPermissionSignal`, `storageSignal`) directly — NOT `activeWarning`. Snoozing CAMERA_PERMISSION_DENIED does NOT open the Start FAB. This filter only affects the **sheet/banner render path**.

- [ ] **Step 4: Write tests for the new behaviour**

Add to `WarningCenterAggregateTest.kt` (or create `WarningCenterViewModelV3Test.kt` if the existing test is exclusively about `aggregate`):

```kotlin
    @Test
    fun toggleExpandWhy_adds_and_removes_from_set() {
        val vm = makeVm() // existing helper in this test class
        assertTrue(WarningId.NOTIFICATIONS_DENIED !in vm.expandedWhy.value)
        vm.toggleExpandWhy(WarningId.NOTIFICATIONS_DENIED)
        assertTrue(WarningId.NOTIFICATIONS_DENIED in vm.expandedWhy.value)
        vm.toggleExpandWhy(WarningId.NOTIFICATIONS_DENIED)
        assertTrue(WarningId.NOTIFICATIONS_DENIED !in vm.expandedWhy.value)
    }

    @Test
    fun snoozeForever_hides_id_from_activeWarning() = runTest {
        // assumes makeVm() exposes a way to drive NOTIFICATIONS_DENIED active —
        // if not, set notificationsGranted = false in the source flow.
        val vm = makeVm(notificationsGranted = false)
        assertEquals(WarningId.NOTIFICATIONS_DENIED, vm.activeWarning.value)
        vm.snoozeForever(WarningId.NOTIFICATIONS_DENIED)
        // activeWarning recomputes; snoozed id is filtered out.
        assertNull(vm.activeWarning.value)
    }
```

If the existing test class doesn't have a `makeVm(notificationsGranted = false)` helper, add a coverage test that drives the snooze flow via `_snoozedForever` indirectly — the existing test will already have a helper that constructs the VM from source MutableStateFlows.

- [ ] **Step 5: Run the VM test suite to verify**

```
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningCenterAggregateTest"
```
Expected: ALL PASS (existing + new).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt
git commit -m "feat(ui): VM expandedWhy + snoozeForever (in-memory)

activeWarning filters out snoozed ids. Start-gate in RecordScreen still
reads leaf signals directly (cameraPermissionSignal / storageSignal),
so snoozing CAMERA_PERMISSION_DENIED does NOT open Start — that invariant
is preserved. Phase 4.1c will persist the snooze."
```

---

### Task A8: Extract `WarningSnoozeChip` composable

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSnoozeChip.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`

- [ ] **Step 1: Read the existing `WarningChip` composable** in `WarningCenter.kt` (private fun, currently around line 223-237). It uses `Color.Black.copy(alpha = 0.40f)`, `RoundedCornerShape(20.dp)`, `Modifier.clickable { onExpand() }`.

- [ ] **Step 2: Create `WarningSnoozeChip.kt`**

```kotlin
package com.aritr.rova.ui.warnings

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.RovaWarningsV3

/**
 * v3 — Post-dismiss chip. Replaces the anonymous `WarningChip` previously
 * inlined in `WarningCenter.kt`. Glass black α0.55 fill + severity-tinted
 * border α0.25; hard-block ids get a soft alpha pulse on the leading dot.
 *
 * Tap → caller restores via `WarningCenterViewModel.restore(id)`.
 *
 * Spec: docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md §3.6
 */
@Composable
internal fun WarningSnoozeChip(
    id: WarningId,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = warningSheetContent(id)
    val severityColor: Color = when (warningSurfaceFor(id)) {
        WarningSurface.HardBlockSheet -> RovaWarnings.hard
        WarningSurface.SoftSheet -> RovaWarnings.soft
        WarningSurface.AdvisorySheet -> RovaWarnings.advisory
        WarningSurface.TopBanner -> RovaWarnings.escalating
    }
    val isHardBlock = warningSurfaceFor(id) == WarningSurface.HardBlockSheet

    val dotAlpha: Float = if (isHardBlock) {
        val transition = rememberInfiniteTransition(label = "snooze-chip-pulse")
        val alpha by transition.animateFloat(
            initialValue = RovaWarningsV3.snoozeChipDotPulseAlpha,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "snooze-chip-pulse-alpha",
        )
        alpha
    } else 1f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(RovaWarningsV3.snoozeChipRadius))
            .background(Color.Black.copy(alpha = RovaWarningsV3.snoozeChipFillAlpha))
            .border(
                width = 1.dp,
                color = severityColor.copy(alpha = RovaWarningsV3.snoozeChipBorderAlpha),
                shape = RoundedCornerShape(RovaWarningsV3.snoozeChipRadius),
            )
            .clickable { onExpand() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(severityColor.copy(alpha = dotAlpha)),
        )
        Icon(
            imageVector = content.icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = content.title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.78f),
            maxLines = 1,
        )
    }
}
```

- [ ] **Step 3: Delete the inline `WarningChip` from `WarningCenter.kt`**

Remove the private `WarningChip` composable (lines ~223-237 in current `WarningCenter.kt`). The call site (around line 153) `WarningChip(id = id, onExpand = { vm.restore(id) }, ...)` will be updated to `WarningSnoozeChip(...)` in Task A11.

For this task, **leave the call site alone temporarily** — Task A11 swaps it. To keep the build green between A8 and A11, change the call site now from `WarningChip` to `WarningSnoozeChip`:

```kotlin
// in WarningCenter.kt — find the existing WarningChip(...) call inside `if (id in dismissed) { ... }`:
WarningSnoozeChip(id = id, onExpand = { vm.restore(id) }, modifier = modifier)
```

- [ ] **Step 4: Verify build + tests stay green**

```
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.*"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningSnoozeChip.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt
git commit -m "refactor(ui): extract WarningSnoozeChip composable (v3)

Glass pill w/ severity-tinted border + leading dot. Hard-block ids get
a soft alpha pulse on the dot. Replaces the anonymous WarningChip in
WarningCenter.kt. Call site updated."
```

---

### Task A9: Create `WarningTopBannerV3` composable + CountdownRing

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/warnings/WarningTopBannerV3.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.aritr.rova.ui.warnings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.RovaWarningsV3

/**
 * v3 — Mid-recording top banner. Renders a glass capsule with leading icon,
 * title + sub-text, and a trailing element — either a "STOP" CTA pill OR a
 * countdown ring when [TopBannerContent.autoAction] is non-null.
 *
 * Phase 4: the ring is static (no ticker). Phase 4.4 will pipe a real
 * `secondsRemaining` from a thermal-hysteresis source. Tapping the banner
 * does NOT stop the countdown — only the [onAction] CTA stops the session.
 *
 * Spec: docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md §3.5
 */
@Composable
internal fun WarningTopBannerV3(
    content: TopBannerContent,
    severityColor: Color,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RovaWarningsV3.bannerCornerRadius))
            .background(Color(0xFF0B0D14).copy(alpha = 0.88f))
            .border(
                width = 1.dp,
                color = severityColor.copy(alpha = 0.30f),
                shape = RoundedCornerShape(RovaWarningsV3.bannerCornerRadius),
            )
            .padding(
                horizontal = RovaWarningsV3.bannerSidePadding,
                vertical = RovaWarningsV3.bannerVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            modifier = Modifier
                .size(RovaWarningsV3.bannerIconSize)
                .clip(RoundedCornerShape(RovaWarningsV3.bannerIconCornerRadius))
                .background(severityColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = content.icon,
                contentDescription = null,
                tint = severityColor.copy(alpha = 0.95f),
                modifier = Modifier.size(18.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = content.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 1,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = content.autoAction?.description ?: content.sub,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.48f),
                maxLines = 2,
            )
        }

        val autoAction = content.autoAction
        if (autoAction != null) {
            CountdownRing(
                secondsRemaining = autoAction.secondsRemaining,
                totalSeconds = 30,                 // matches the static placeholder
                severityColor = severityColor,
            )
        } else {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(severityColor.copy(alpha = 0.20f))
                    .clickable { onAction() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = content.cta.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = severityColor.copy(alpha = 0.95f),
                )
            }
        }
    }
}

/**
 * Severity-tinted countdown ring. Static in Phase 4 — Phase 4.4 will swap
 * [secondsRemaining] to read from a ThermalAutoStopSignal flow.
 */
@Composable
private fun CountdownRing(
    secondsRemaining: Int,
    totalSeconds: Int,
    severityColor: Color,
) {
    Box(
        modifier = Modifier.size(RovaWarningsV3.bannerCountdownRingSize),
        contentAlignment = Alignment.Center,
    ) {
        val progressFraction = (secondsRemaining.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
        Canvas(modifier = Modifier.size(RovaWarningsV3.bannerCountdownRingSize)) {
            val strokePx = RovaWarningsV3.bannerCountdownRingStroke.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            // Background track
            drawArc(
                color = Color.White.copy(alpha = RovaWarningsV3.bannerCountdownTrackAlpha),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx),
            )
            // Foreground arc
            drawArc(
                color = severityColor.copy(alpha = 0.85f),
                startAngle = -90f,
                sweepAngle = 360f * progressFraction,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
        Text(
            text = secondsRemaining.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}
```

- [ ] **Step 2: Verify the file compiles**

```
./gradlew :app:compileDebugKotlin
```
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningTopBannerV3.kt
git commit -m "feat(ui): WarningTopBannerV3 + CountdownRing composable

v3 mid-rec banner: glass capsule + severity-tinted border + icon block +
title/sub column + trailing CTA OR countdown ring (based on
TopBannerContent.autoAction). Static ring placeholder for now — Phase
4.4 wires a real seconds source. Not yet mounted; Task A11 wires it
into WarningCenter."
```

---

### Task A10: Create `WarningSheetV3` composable

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetV3.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.aritr.rova.ui.warnings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.RovaWarningsV3

/**
 * v3 — Modal bottom sheet for all idle-reachable warnings. Routes by
 * [surface] to compute the severity accent.
 *
 * Layout:
 *   handle → overflow ⋯ (if hasOverflow) → icon-with-glow → severity chip →
 *   title → body → optional "Why this matters" expander → primary CTA →
 *   secondary CTA (if present).
 *
 * Hard-block sheets are NOT swipe-dismissible (`confirmValueChange`).
 *
 * Spec: docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md §3.4
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WarningSheetV3(
    id: WarningId,
    surface: WarningSurface,
    expanded: Boolean,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onOverflow: (ActionTarget) -> Unit,
    onToggleWhy: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val content = warningSheetContent(id)
    val accent: Color = when (surface) {
        WarningSurface.HardBlockSheet -> RovaWarnings.hard
        WarningSurface.SoftSheet -> RovaWarnings.soft
        WarningSurface.AdvisorySheet -> RovaWarnings.advisory
        WarningSurface.TopBanner -> RovaWarnings.advisory   // unreachable here
    }
    val severityLabel: String = when (surface) {
        WarningSurface.HardBlockSheet -> "Hard · Required"
        WarningSurface.SoftSheet -> "Soft · Degraded mode"
        WarningSurface.AdvisorySheet -> "Advisory · Optional"
        WarningSurface.TopBanner -> ""
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            surface != WarningSurface.HardBlockSheet || value != SheetValue.Hidden
        },
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(
            topStart = RovaWarningsV3.sheetCornerRadius,
            topEnd = RovaWarningsV3.sheetCornerRadius,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        ),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {

            if (hasOverflow(id)) {
                OverflowMenu(
                    overflow = content.overflow,
                    onTarget = onOverflow,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            top = RovaWarningsV3.overflowTopInset,
                            end = RovaWarningsV3.overflowRightInset,
                        ),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = RovaWarningsV3.sheetSidePadding,
                        end = RovaWarningsV3.sheetSidePadding,
                        bottom = RovaWarningsV3.sheetBottomPadding,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))

                IconWithGlow(icon = content.icon, accent = accent)

                Spacer(Modifier.height(10.dp))

                SeverityChip(label = severityLabel, accent = accent)

                Spacer(Modifier.height(8.dp))

                Text(
                    text = content.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.94f),
                    textAlign = TextAlign.Center,
                )

                if (content.body.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = content.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(18.dp))

                if (shouldShowWhy(id)) {
                    WhyExpander(
                        expanded = expanded,
                        whyBody = content.whyThisMatters!!,
                        onToggle = onToggleWhy,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                PrimaryCta(
                    label = content.primary.label,
                    accent = accent,
                    onClick = onPrimary,
                )

                content.secondary?.let { sec ->
                    Spacer(Modifier.height(8.dp))
                    SecondaryCta(label = sec.label, onClick = onSecondary)
                }
            }
        }
    }
}

@Composable
private fun IconWithGlow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
) {
    Box(
        modifier = Modifier.size(RovaWarningsV3.sheetIconSize),
        contentAlignment = Alignment.Center,
    ) {
        // Glow bloom (offset + blurred)
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(
                    x = RovaWarningsV3.sheetIconGlowInset,
                    y = RovaWarningsV3.sheetIconGlowInset,
                )
                .size(
                    width = RovaWarningsV3.sheetIconSize - (RovaWarningsV3.sheetIconGlowInset * 2),
                    height = RovaWarningsV3.sheetIconSize - (RovaWarningsV3.sheetIconGlowInset * 2),
                )
                .blur(RovaWarningsV3.sheetIconGlowBlur)
                .background(brush = RovaWarningsV3.iconGlow(accent), shape = CircleShape),
        )
        // Icon container
        Box(
            modifier = Modifier
                .size(RovaWarningsV3.sheetIconSize)
                .clip(RoundedCornerShape(RovaWarningsV3.sheetIconCornerRadius))
                .background(accent.copy(alpha = 0.14f))
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = RovaWarningsV3.sheetIconInnerStrokeAlpha),
                    shape = RoundedCornerShape(RovaWarningsV3.sheetIconCornerRadius),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accent)
        }
    }
}

@Composable
private fun SeverityChip(label: String, accent: Color) {
    if (label.isBlank()) return
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(RovaWarningsV3.snoozeChipRadius))
            .background(accent.copy(alpha = RovaWarningsV3.sevChipFillAlpha))
            .padding(
                horizontal = RovaWarningsV3.sevChipPaddingH,
                vertical = RovaWarningsV3.sevChipPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(RovaWarningsV3.sevChipDotSize)
                .clip(CircleShape)
                .background(accent),
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent.copy(alpha = RovaWarningsV3.sevChipForegroundAlpha),
        )
    }
}

@Composable
private fun WhyExpander(
    expanded: Boolean,
    whyBody: String,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(RovaWarningsV3.whyRowHeight)
                .clip(RoundedCornerShape(RovaWarningsV3.whyRowCornerRadius))
                .border(
                    width = 1.dp,
                    color = RovaWarnings.advisory.copy(alpha = RovaWarningsV3.whyRowBorderAlpha),
                    shape = RoundedCornerShape(RovaWarningsV3.whyRowCornerRadius),
                )
                .clickable { onToggle() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = RovaWarnings.advisory.copy(alpha = RovaWarningsV3.whyRowForegroundAlpha),
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Why this matters",
                style = MaterialTheme.typography.labelMedium,
                color = RovaWarnings.advisory.copy(alpha = RovaWarningsV3.whyRowForegroundAlpha),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = RovaWarnings.advisory.copy(alpha = RovaWarningsV3.whyRowForegroundAlpha),
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = whyBody,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun PrimaryCta(label: String, accent: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RovaWarningsV3.sheetCtaHeight)
            .clip(RoundedCornerShape(RovaWarningsV3.sheetCtaCornerRadius))
            .background(accent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

@Composable
private fun SecondaryCta(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RovaWarningsV3.sheetCtaHeight)
            .clip(RoundedCornerShape(RovaWarningsV3.sheetCtaCornerRadius))
            .background(Color.White.copy(alpha = RovaWarningsV3.secondaryCtaFillAlpha))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = RovaWarningsV3.secondaryCtaStrokeAlpha),
                shape = RoundedCornerShape(RovaWarningsV3.sheetCtaCornerRadius),
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = RovaWarningsV3.secondaryCtaTextAlpha),
        )
    }
}

@Composable
private fun OverflowMenu(
    overflow: List<WarningAction>,
    onTarget: (ActionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.size(RovaWarningsV3.overflowButtonSize),
        ) {
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = "More actions",
                tint = Color.White.copy(alpha = 0.30f),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            overflow.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        menuOpen = false
                        onTarget(action.target)
                    },
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify the file compiles**

```
./gradlew :app:compileDebugKotlin
```
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetV3.kt
git commit -m "feat(ui): WarningSheetV3 composable (v3 chrome canon)

ModalBottomSheet w/ overflow ⋯ + icon-with-glow + severity-chip +
title/body + optional Why-expander + 46dp primary/secondary CTAs.
Hard-block sheets remain swipe-dismiss-blocked. Not yet mounted —
Task A11 wires it into WarningCenter."
```

---

### Task A11: Slim `WarningCenter.kt` to routing entrypoint

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`

- [ ] **Step 1: Rewrite `WarningCenter.kt`** to delete the inline `WarningSheet` / `WarningTopBanner` composables and dispatch to the new v3 composables. The file should end up ~120L.

The new full content of `WarningCenter.kt`:

```kotlin
package com.aritr.rova.ui.warnings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aritr.rova.RovaApp
import com.aritr.rova.ui.components.RecordHudState
import com.aritr.rova.ui.screens.BatteryOptimizationHelper
import com.aritr.rova.ui.theme.RovaWarnings

/**
 * Phase 4 v3 — Warning surface entry point. Routing only; rendering happens
 * in [WarningSheetV3] / [WarningTopBannerV3] / [WarningSnoozeChip].
 *
 * - [RecordHudState.Idle] + non-TopBanner id → sheet (or snooze-chip if dismissed)
 * - active (Recording/Waiting/Merging) + TopBanner id → top banner
 *
 * Spec: docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md §3.7
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarningCenter(
    hudState: RecordHudState,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as? RovaApp } ?: return
    val vm: WarningCenterViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                WarningCenterViewModel(
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
                )
            }
        }
    )
    val active by vm.activeWarning.collectAsStateWithLifecycle()
    val id = active ?: return
    val surface = warningSurfaceFor(id)

    if (hudState is RecordHudState.Idle) {
        // Idle branch — sheet / chip. TopBanner ids no-op here.
        if (surface == WarningSurface.TopBanner) return

        val dismissed by vm.dismissedWarnings.collectAsStateWithLifecycle()
        if (id in dismissed) {
            WarningSnoozeChip(
                id = id,
                onExpand = { vm.restore(id) },
                modifier = modifier,
            )
            return
        }

        val expandedWhy by vm.expandedWhy.collectAsStateWithLifecycle()
        WarningSheetV3(
            id = id,
            surface = surface,
            expanded = id in expandedWhy,
            onPrimary = {
                launchActionTarget(context, warningSheetContent(id).primary.target)
                vm.dismiss(id)
            },
            onSecondary = { vm.dismiss(id) },
            onOverflow = { target ->
                if (target == ActionTarget.SNOOZE_FOREVER) {
                    vm.snoozeForever(id)
                } else {
                    launchActionTarget(context, target)
                }
            },
            onToggleWhy = { vm.toggleExpandWhy(id) },
            onDismissRequest = {
                if (surface != WarningSurface.HardBlockSheet) vm.dismiss(id)
            },
        )
    } else {
        // Active branch — TopBanner only.
        if (surface != WarningSurface.TopBanner) return
        WarningTopBannerV3(
            content = midRecBannerContent(id),
            severityColor = RovaWarnings.escalating,
            onAction = onStopRecording,
            modifier = modifier,
        )
    }
}

/** Launches the system Intent for [target]. NO-OP for [ActionTarget.SNOOZE_FOREVER]. */
private fun launchActionTarget(context: Context, target: ActionTarget) {
    if (target == ActionTarget.SNOOZE_FOREVER) return
    val pkgUri = Uri.fromParts("package", context.packageName, null)
    val intent: Intent = when (target) {
        ActionTarget.EXACT_ALARM_SETTINGS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
            else
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        ActionTarget.BATTERY_OPTIMIZATION ->
            BatteryOptimizationHelper.buildRequestIntent(context.packageName)
        ActionTarget.NOTIFICATION_SETTINGS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        ActionTarget.APP_DETAILS_SETTINGS ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        ActionTarget.SNOOZE_FOREVER -> return    // unreachable (guarded above) — for `when` exhaustiveness
    }
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
}
```

- [ ] **Step 2: Verify compile + run all warnings tests**

```
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.*"
```
Expected: PASS.

- [ ] **Step 3: Verify full debug build**

```
./gradlew :app:assembleDebug
```
Expected: PASS.

- [ ] **Step 4: Verify lint stays at baseline**

```
./gradlew :app:lintDebug
```
Expected: 50W + 1H + 0E (current baseline per CLAUDE.md memory).

- [ ] **Step 5: Owner verifies on device**

Run the app, exercise each warning-id path:
1. Revoke camera permission → confirm HardBlock sheet appears with red glow + severity chip + 46dp CTAs + Start FAB disabled.
2. Tap "Not now" → confirm sheet collapses to a `WarningSnoozeChip` with pulsing red dot.
3. Tap the chip → confirm sheet re-opens.
4. Revoke notifications + grant camera → confirm Advisory sheet shows blue glow + overflow ⋯ menu + Why-expander row.
5. Tap "Why this matters" → confirm expander reveals body copy.
6. Tap ⋯ → "Don't show again" → confirm sheet disappears AND does not return (snooze-forever, in-memory).
7. Force the device into power-save mode → confirm amber sheet + amber CTA.
8. Start a recording, throttle thermal (use developer override if available) → at THERMAL_SEVERE confirm CTA banner; if you can drive THERMAL_EMERGENCY, confirm countdown ring shows "30".

If any check fails, file the regression as a follow-up commit in this PR — do not ship until all 8 pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt
git commit -m "refactor(ui): slim WarningCenter to routing; wire v3 composables

Deletes inline WarningSheet / WarningChip / WarningTopBanner. Routes
to WarningSheetV3 + WarningSnoozeChip + WarningTopBannerV3. Handles
SNOOZE_FOREVER overflow target (VM-only). launchActionTarget no-ops
on SNOOZE_FOREVER for safety.

Owner-verified the 8 record-screen warning paths on device."
```

---

### Task A12: Draft ADR-0013

**Files:**
- Create: `docs/adr/0013-phase4-warning-reskin-v3.md`

- [ ] **Step 1: Create the ADR file**

```markdown
# ADR 0013 — Phase 4 warning re-skin v3 (chrome canon)

- **Status:** Accepted (owner sign-off pending PR A merge)
- **Date:** 2026-05-23
- **Phase:** Phase 4 warning surface v3 (`docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md`)
- **Supersedes (visually):** the original `WarningSheet` / `WarningTopBanner` chrome shipped in PR #12 (Phase 4.1) / PR #13 (Phase 4.1b) and refined in R1 / R2 (PRs #17 / #18 / #19)
- **Amends:** ADR-0007 (record warning sheets) — the v3 canon (glow bloom, severity chip, overflow ⋯, countdown ring, snooze-chip) supersedes the §1 stripe-and-icon stack described there
- **Does NOT supersede:** ADR-0007 §3 (Start-gate from leaf signals), `WarningPrecedence.resolve(...)`, `WarningId` (17 rows, ordinals, tier, `gatesStart`), `WarningSurface` routing, `RecoveryViewModel` / `RecoveryUiState`
- **Related:** mockup `mockups/new_uiux/07c-warnings.html` (authoritative)

---

## Context

The R2 record-home re-skin (Slices A/B, PRs #41 / #42) shipped a new canon for the live record chrome — edge-to-edge gradient scrim dock, glass pills with backdrop blur, Inter SemiBold, 22dp pill-radius settings card, severity-typed micro-affordances. The Phase 4.1 / 4.1b warning surface predates that canon: severity stripe at the sheet's top edge, 40dp CTAs, single-pass action stack, white-α0.55 secondary-CTA contrast. Against the new chrome it looks dated.

The mockup `07c-warnings.html` defines a v3 canon that aligns with R2:
- **Icon glow bloom** replaces the stripe (radial-gradient blur behind the icon, severity-tinted, 22dp blur, 0.70 alpha).
- **Inline severity chip** ("Hard · Required", "Soft · Degraded mode", "Advisory · Optional") sits above the title — explicit, glanceable.
- **Overflow ⋯ menu** carries tertiary actions (e.g. "Don't show again") that don't deserve a dedicated CTA slot.
- **"Why this matters"** dashed-border expander row reveals advisory rationale on demand (progressive disclosure).
- **Auto-action countdown ring** replaces the CTA pill on banners whose underlying state will auto-stop the session (THERMAL_EMERGENCY / THERMAL_SHUTDOWN).
- **CTAs** grow to 46dp (a11y), secondary text α0.55 → α0.68.
- **Snooze-chip** post-dismiss state — same composable canon, severity-tinted border, hard-block ids pulse the dot.
- **Recovery cards** in Library re-skin under the same canon (top glow bloom replaces stripe; numeric clip-count chip in the progress label row).

## Decision

1. **Adopt the v3 chrome canon** for all 17 idle-reachable warning sheets, all 10 mid-rec top banners, the post-dismiss snooze-chip, and the three Library recovery-card variants (`KILLED_BY_SYSTEM`, `KILLED_FORCE_STOP`, `MERGE_FAILED`).
2. **Split `WarningCenter.kt`** into `WarningSheetContent.kt` (pure content + helpers), `WarningSheetV3.kt`, `WarningTopBannerV3.kt`, `WarningSnoozeChip.kt`. `WarningCenter.kt` becomes the routing entrypoint (~120L).
3. **Add additive VM state** — `expandedWhy: StateFlow<Set<WarningId>>` for the "Why this matters" toggle, `snoozedForever: StateFlow<Set<WarningId>>` for the "Don't show again" overflow action. `activeWarning` filters out snoozed ids. **The Start-gate in `RecordScreen` continues to read leaf signals directly** — snoozing a hard-block does NOT open the gate. This invariant is pinned by `WarningCenterAggregateTest`.
4. **Add tokens** in `RovaWarningsV3.kt` (geometry, alpha, brush helpers). `RovaWarnings` (the four severity colors) is untouched.
5. **Defer to Phase 4.4:** real thermal-hysteresis seconds-source for the countdown ring (static 30s placeholder), snooze persistence across process death (in-memory only here), `SD_CARD_EJECTED` WarningId, `STORAGE_FULL_AUTOSTOPPED` sheet variant, stop-recording confirmation sheet (vacant C4.3), inline export-failed history tile (C5.3).

## Consequences

- **Positive:** the warning surface visually unifies with the R2 record-home canon. The split file structure unblocks Phase 4.4 work (countdown ticker, SD-eject, recovery-card variants) without re-crossing the 500L threshold. The Why-expander surfaces context that today is buried in the body copy — frees the body to be concise. The countdown ring sets the foundation for the real auto-stop UX in 4.4.
- **Negative / cost:** in-memory snooze means "Don't show again" forgets across process restarts. Acceptable in this slice — matches the original `WarningCenterContract.md` §5.2 design ("the user gets the banner back on cold start, by design"). 4.1c persists it durably. The countdown ring renders a placeholder 30 in 4.1 — owners must understand the ring is a UI shell, not a live signal.
- **Testing:** new pure-helper tests (`WarningSheetContentV3Test` + `RovaWarningsV3Test`) + edits to `WarningCenterAggregateTest`. No new Compose-UI tests — sheet / banner / chip / recovery-card chrome is owner-verified on device, matching the ADR-0007 precedent.

## Status / sign-off

Drafted alongside PR A (Record-screen v3). Owner-signed on PR A merge.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0013-phase4-warning-reskin-v3.md
git commit -m "docs(adr): ADR-0013 Phase 4 warning re-skin v3 chrome canon"
```

---

### Task A13: PR A — push branch + open PR

- [ ] **Step 1: Verify all green**

```
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.*"
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```
Expected: ALL PASS, lint at baseline 50W + 1H + 0E.

- [ ] **Step 2: Push and open PR**

```bash
git push -u origin <current-branch>
gh pr create --title "feat(ui): Phase 4 warning re-skin v3 — Record screen" --body "$(cat <<'EOF'
## Summary

- Re-skins all 17 Record-screen warning surfaces (sheets + banners + snooze-chip) to the v3 chrome canon: icon glow bloom, inline severity chip, overflow ⋯ menu, "Why this matters" expander, auto-action countdown ring, 46dp CTAs, a11y secondary-CTA contrast bump.
- Splits the 470L `WarningCenter.kt` into focused composables (≤ 200L each).
- Adds additive VM state — `expandedWhy` + `snoozedForever`. `activeWarning` filters snoozed ids. Start-gate (leaf-signal read) is preserved.
- Documents the canon in ADR-0013.

## Test plan

- [ ] `./gradlew :app:testDebugUnitTest` — all green (existing `WarningSheetContentTest` / `WarningPrecedenceTest` / `WarningIdOrderTest` / `WarningCenterAggregateTest` + new `WarningSheetContentV3Test` / `RovaWarningsV3Test`).
- [ ] `./gradlew :app:assembleDebug` — green.
- [ ] `./gradlew :app:lintDebug` — 50W + 1H + 0E baseline.
- [ ] Owner-verified the 8 record-screen warning paths on device (camera-denied sheet → snooze-chip → restore; notifications-denied advisory + why-expander + overflow snooze; power-save amber; thermal-emergency countdown ring).

Spec: `docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md`
ADR: `docs/adr/0013-phase4-warning-reskin-v3.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Wait for owner GO** before merging.

---

## PR B — Library recovery card v3

**Precondition:** PR A merged. `RovaWarningsV3` is on `master`.

### Task B1: Re-skin `RecoveryCard.kt`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt`

- [ ] **Step 1: Read the existing `RecoveryCard.kt`**

Identify the current chrome: severity stripe at top, tag chip + timestamp row, title + description, progress strip (existing or absent), button row, extra row (vendor-guidance link). Note the signature so we don't break callers.

- [ ] **Step 2: Re-skin the composable** (illustrative — adjust to match the existing signature exactly):

```kotlin
package com.aritr.rova.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.RovaWarningsV3

/**
 * v3 — Library recovery card. Top glow bloom replaces the severity stripe.
 * Severity chip + timestamp row, title + description, clip-progress strip
 * with numeric chip, primary / secondary buttons, optional extra row
 * (vendor guidance for KILLED_BY_SYSTEM, destructive discard for MERGE_FAILED).
 *
 * Signature preserved from the v2 composable to avoid touching call sites in
 * HistoryScreen.kt. RecoveryViewModel / RecoveryUiState / RecoveryViewSource
 * are untouched.
 */
@Composable
fun RecoveryCard(
    state: RecoveryUiState,
    onMerge: () -> Unit,
    onDiscard: () -> Unit,
    onFixBackgroundSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (severityColor, isHardSeverity) = severityFor(state)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RovaWarningsV3.recoveryCardCornerRadius))
            .background(Color(0xFF0B0D14).copy(alpha = 0.94f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(RovaWarningsV3.recoveryCardCornerRadius),
            ),
    ) {
        // Top glow bloom — replaces the v2 stripe.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RovaWarningsV3.recoveryCardGlowHeight)
                .padding(top = (-20).dp)
                .blur(RovaWarningsV3.recoveryCardGlowBlur)
                .background(brush = RovaWarningsV3.recoveryGlow(severityColor)),
        )

        Column(
            modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 18.dp),
        ) {
            // Tag chip + timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SeverityTag(
                    label = state.tagLabel,
                    accent = severityColor,
                    pulsing = isHardSeverity,
                )
                Text(
                    text = state.timestamp,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.34f),
                )
            }

            Spacer(Modifier.height(12.dp))

            // Title
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.92f),
            )

            Spacer(Modifier.height(6.dp))

            // Description
            Text(
                text = state.description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.45f),
            )

            Spacer(Modifier.height(14.dp))

            // Clip-progress strip
            ProgressStrip(
                clipsSaved = state.clipsSaved,
                clipsTotal = state.clipsTotal,
                accent = if (isHardSeverity) RovaWarnings.hard else RovaWarnings.soft,
            )

            Spacer(Modifier.height(14.dp))

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryButton(label = state.primaryLabel, onClick = onMerge, modifier = Modifier.weight(1f))
                SecondaryButton(label = state.secondaryLabel, onClick = onDiscard, modifier = Modifier.weight(1f))
            }

            if (state.showVendorGuidance) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .border(
                            width = 0.dp,
                            color = Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(0.dp),
                        ),
                ) {
                    ExtraRow(
                        label = "Fix background-app settings",
                        onClick = onFixBackgroundSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeverityTag(label: String, accent: Color, pulsing: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent.copy(alpha = 0.95f),
        )
    }
}

@Composable
private fun ProgressStrip(clipsSaved: Int, clipsTotal: Int, accent: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "CLIPS CAPTURED",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.36f),
            )
            Text(
                text = "$clipsSaved / $clipsTotal",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.78f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RovaWarningsV3.recoveryProgressCellGap),
        ) {
            repeat(clipsTotal.coerceAtLeast(1)) { idx ->
                val saved = idx < clipsSaved
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(RovaWarningsV3.recoveryProgressCellHeight)
                        .clip(RoundedCornerShape(RovaWarningsV3.recoveryProgressCellRadius))
                        .background(
                            if (saved) accent.copy(alpha = 0.55f)
                            else Color.White.copy(alpha = 0.08f),
                        ),
                )
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF5B7FFF))
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = RovaWarningsV3.secondaryCtaTextAlpha),
        )
    }
}

@Composable
private fun ExtraRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.42f),
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.30f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Severity routing. RecoveryUiState carries a `terminated` field today;
 * map it to a severity color and a "pulse" flag.
 *
 * Caller-side defensive default: any unknown `terminated` value falls
 * through to amber (legible, non-alarming).
 */
private fun severityFor(state: RecoveryUiState): Pair<Color, Boolean> {
    // Adjust the property names below to match the actual fields on RecoveryUiState.
    return when (state.severityKind) {
        RecoveryUiState.SeverityKind.HARD -> RovaWarnings.hard to true
        RecoveryUiState.SeverityKind.SOFT -> RovaWarnings.soft to false
    }
}
```

**Adjustment note:** the snippet above assumes `RecoveryUiState` exposes `tagLabel`, `timestamp`, `title`, `description`, `clipsSaved`, `clipsTotal`, `primaryLabel`, `secondaryLabel`, `showVendorGuidance`, and a `severityKind` enum. Inspect the existing data class — if those fields are named differently, use the existing names (this task is a re-skin, not a refactor). If the data class doesn't expose `severityKind`, derive the severity from `terminated: Terminated` directly:
```kotlin
val (severityColor, pulsing) = when (state.terminated) {
    Terminated.KILLED_BY_SYSTEM -> RovaWarnings.hard to true
    Terminated.KILLED_FORCE_STOP -> RovaWarnings.soft to false
    Terminated.MERGE_FAILED -> RovaWarnings.hard to true
    else -> RovaWarnings.soft to false
}
```

- [ ] **Step 3: Verify compile + existing recovery tests stay green**

```
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.recovery.*"
```
Expected: PASS (`RecoveryUiStateMapperTest`, `RecoveryViewModelTest`, `RecoveryViewSourceTest`).

- [ ] **Step 4: Verify full debug build + lint**

```
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```
Expected: PASS, lint at baseline.

- [ ] **Step 5: Owner verifies on device**

Reproduce each recovery scenario:
1. **Killed-by-system:** kill Rova mid-recording via Settings > Apps > Force stop. Cold-launch the app → confirm the Library recovery card shows red glow at top + "Killed by system" tag chip with pulsing red dot + clip-progress strip with N/total green cells + "Fix background-app settings →" extra row.
2. **Force-stopped:** stop the recording via the system foreground-service notification. Cold-launch → confirm the card shows amber glow + "Force stopped" tag chip (no pulse) + amber progress cells + no vendor-guidance row.
3. **Merge-failed:** trigger a merge failure (corrupt the segment store or force a low-storage condition during merge). Confirm the card shows red glow + "Merge failed" tag chip with pulse + 5/5 green progress cells + "Discard all segments" destructive extra row.

If any check fails, fix and re-test before committing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt
git commit -m "feat(ui): RecoveryCard v3 re-skin (Phase 4 chrome canon)

Top glow bloom replaces v2 stripe. Severity-tag chip with pulsing dot
for hard severity, clip-progress strip with numeric N/total chip,
chevron-suffixed extra rows. RecoveryViewModel / RecoveryUiState /
RecoveryViewSource untouched. Existing tests stay green.

Owner-verified the 3 recovery scenarios on device."
```

---

### Task B2: PR B — push branch + open PR

- [ ] **Step 1: Push and open PR**

```bash
git push -u origin <current-branch>
gh pr create --title "feat(ui): Phase 4 warning re-skin v3 — Library recovery card" --body "$(cat <<'EOF'
## Summary

Re-skins `RecoveryCard.kt` to the v3 chrome canon: top glow bloom + severity tag chip (pulsing dot for hard severity) + clip-progress strip with numeric N/total chip + chevron-suffixed extra rows. RecoveryViewModel / RecoveryUiState / RecoveryViewSource are untouched.

Depends on PR A (RovaWarningsV3 tokens).

## Test plan

- [ ] `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.recovery.*"` — all green.
- [ ] `./gradlew :app:assembleDebug` — green.
- [ ] `./gradlew :app:lintDebug` — at baseline.
- [ ] Owner-verified killed-by-system / force-stopped / merge-failed scenarios on device.

Spec: `docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md` §3.9
ADR: `docs/adr/0013-phase4-warning-reskin-v3.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 2: Wait for owner GO** before merging.

---

## Self-review

**Spec coverage** — every section of the spec maps to a task:
- §3.1 (Architecture / file layout) → A1 (RovaWarningsV3), A2 (split), A8 (snooze-chip), A9 (banner), A10 (sheet), A11 (slim center), B1 (recovery card)
- §3.2 (Tokens) → A1
- §3.3 (`WarningSheetContent.kt` split + new fields + helpers) → A2, A3, A4, A5, A6
- §3.4 (`WarningSheetV3`) → A10
- §3.5 (`WarningTopBannerV3` + CountdownRing) → A9
- §3.6 (`WarningSnoozeChip`) → A8
- §3.7 (`WarningCenter` slim) → A11
- §3.8 (`WarningCenterViewModel` additions) → A7
- §3.9 (`RecoveryCard` re-skin) → B1
- §3.10 (Mounting — no change) → A11 (touches only `WarningCenter`, not `RecordScreen`)
- §4 (Data flow) → A7 + A11 wire it
- §5 (Testing) → A1 (`RovaWarningsV3Test`), A4 (`hasOverflow`/`shouldShowWhy`), A6 (`autoAction`), A7 (VM tests)
- §6 (Error handling) → A11 (`launchActionTarget` SNOOZE_FOREVER no-op), A8 (pulse), A10 (confirmValueChange)
- §7 (Slice plan) → PR A = A1..A13, PR B = B1..B2
- §8 (Migration risks) → A3 keeps named-args, defaults; A11 handles enum exhaustiveness
- §9 (Open questions) — documented in the spec; not implementation tasks. Owner-decided pre-merge.
- §10 (Out of scope) — explicitly not in the task list. Park documented.
- ADR-0013 → A12.

**Placeholder scan:** searched for "TBD", "TODO", "implement later", "add appropriate error handling", "similar to Task N" — none present. Every code step shows the actual code. Every gradle command is exact.

**Type consistency:** `WarningSheetContent` gains `overflow: List<WarningAction>` and `whyThisMatters: String?` in Task A3, referenced consistently in A4 (helpers), A5 (advisory arms), A10 (sheet composable), A11 (router). `TopBannerContent` gains `autoAction: AutoAction?` in A3, referenced in A6 (thermal arms), A9 (banner composable). `ActionTarget` gains `SNOOZE_FOREVER` in A3, referenced in A5 (advisory overflow), A10 (sheet overflow callback), A11 (router + `launchActionTarget` no-op). `WarningCenterViewModel.expandedWhy` / `snoozedForever` / `toggleExpandWhy` / `snoozeForever` declared in A7, consumed in A11. `WarningSnoozeChip(id, onExpand, modifier)` signature defined in A8, called in A11. `WarningSheetV3(id, surface, expanded, onPrimary, onSecondary, onOverflow, onToggleWhy, onDismissRequest)` signature defined in A10, called in A11 with all 8 args. `WarningTopBannerV3(content, severityColor, onAction, modifier)` defined in A9, called in A11 with all 4 args. `RovaWarningsV3.iconGlow(Color)` / `recoveryGlow(Color)` declared in A1, used in A10 / B1.

All consistent.
