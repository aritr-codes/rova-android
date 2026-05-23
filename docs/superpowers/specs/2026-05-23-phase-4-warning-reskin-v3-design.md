# Phase 4 Warning Re-skin v3 — Design

- **Status:** Draft (owner review pending)
- **Date:** 2026-05-23
- **Phase:** Phase 4 (warning surface), v3 chrome canon
- **Mockup (authoritative):** `mockups/new_uiux/07c-warnings.html`
- **Supersedes (visually):** the original `WarningSheet` / `WarningTopBanner` render shipped in PRs #12 / #13 + R2 (`f684d5c`); the `RecoveryCard` chrome shipped pre-Phase-4
- **Does NOT supersede:** `WarningPrecedence.resolve(...)`, `WarningId` (ordinal + tier + gatesStart), the Start-gate read in `RecordScreen` against leaf signals, `WarningCenterViewModel.aggregate(...)`, `WarningSurface` routing, `RecoveryViewModel` / `RecoveryUiState` / `RecoveryViewSource`
- **Related ADRs:** ADR-0007 (record warning sheets — amended by ADR-0013), ADR-0005 (recovery scan), ADR-0006 §B18 (video-only fallback)
- **New ADR:** ADR-0013 "Phase 4 warning re-skin v3 chrome canon" (drafted as part of this slice)
- **Backlog parked / deferred to follow-up Phase 4.4:** Phase 4.1c snooze + hysteresis persistence; SD_CARD_EJECTED WarningId + signal; real thermal-hysteresis seconds-until-autostop wiring; stop-recording confirmation sheet (vacant C4.3); export-failed inline history tile (C5.3)

---

## 1 · Context

The current Phase 4 warning surface (`WarningCenter.kt`, `WarningSheet`, `WarningTopBanner`, `RecoveryCard`) renders functional but visually utilitarian chrome that pre-dates the edge-to-edge record-home re-skin (Slices A/B, PRs #41 / #42). After that re-skin, the Record screen reads as a deliberate, glanceable surface — gradient scrim dock, glass pills, Inter SemiBold, severity-typed micro-affordances. The warning surface looks dated against it: flat severity stripe, undersized 40dp CTAs, single-pass action stack, no progressive disclosure, secondary-CTA contrast just over the floor (white α0.55).

The mockup `07c-warnings.html` evolves the canon: icon-glow bloom (instead of stripe), inline severity chip, larger 46dp CTAs, overflow ⋯ menu for tertiary actions, "Why this matters" advisory expander, auto-action countdown ring on escalating banners, and a refined snooze-chip post-dismiss state. The recovery cards in Library / History adopt the same canon — glow bloom in place of the stripe, segment-dot progress strip, numeric chip in the label row.

This slice executes that canon. It is **render-only** for the existing 17 WarningIds; no new ids, no new signals, no new precedence rules.

## 2 · Goals / non-goals

**Goals**

1. Bring all 17 existing WarningIds onto the v3 chrome (sheet + banner + snooze-chip).
2. Re-skin the existing recovery cards in Library / History under the same canon (glow bloom, severity chip, segment-dot progress).
3. Add two minor behaviours: a per-id "Don't show again" hook (in-memory) and a "Why this matters" expander for advisory-tier sheets.
4. Split `WarningCenter.kt` (~470L) into focused composables ≤ 200L each.

**Non-goals**

1. New WarningIds (no `SD_CARD_EJECTED`, no `STORAGE_FULL_AUTOSTOPPED` sheet variant, no `STOP_RECORDING_CONFIRMATION`).
2. New leaf signals (no `SdCardSignal`, no service work).
3. Wiring real thermal-hysteresis seconds-until-autostop to the countdown ring — the ring renders a static `30s` placeholder for `THERMAL_EMERGENCY` / `THERMAL_SHUTDOWN`; the real seconds-source lands in Phase 4.4 alongside hysteresis.
4. Persisting snooze across process death — the "Don't show again" flag is in-memory only; 4.1c covers durable persistence.
5. Stop-recording confirmation sheet (vacant `C4.3`) and the inline export-failed history tile (`C5.3`) — both stay parked.
6. Changes to `WarningPrecedence.resolve(...)`, `WarningId` ordinals, or `WarningSurface` routing.

## 3 · Design

### 3.1 · Architecture

```
app/src/main/java/com/aritr/rova/ui/
├── theme/
│   └── RovaWarningsV3.kt            [NEW]
├── warnings/
│   ├── WarningCenter.kt             [EDIT] — slim to ~120L: routing only
│   ├── WarningSheetContent.kt       [NEW]  — split out the 17-arm content table + warningSurfaceFor + midRecBannerContent + new pure helpers (hasOverflow, shouldShowWhy)
│   ├── WarningSheetV3.kt            [NEW]  — sheet composable: icon-glow + severity-chip + overflow ⋯ + Why-expander + 46dp CTAs
│   ├── WarningTopBannerV3.kt        [NEW]  — banner composable + CountdownRing variant
│   ├── WarningSnoozeChip.kt         [NEW]  — extracted from anonymous WarningChip in WarningCenter.kt
│   └── WarningCenterViewModel.kt    [EDIT] — +expandedWhy, +snoozeForever
└── recovery/
    └── RecoveryCard.kt              [EDIT] — glow bloom + segment-dot progress + numeric chip
```

**File-size discipline.** `WarningCenter.kt` today is 470L. v3 splits it into ≤ 200L composables per concern (sheet, banner, snooze-chip, content table). `WarningCenter.kt` becomes the routing entrypoint only — read leaf state, resolve via VM, dispatch to the right composable based on `warningSurfaceFor(id)` + `hudState`. The split is also a precondition for future Phase 4.4 work (countdown wiring, SD-eject) — adding more cases into the current 470L file would cross 700L.

### 3.2 · Tokens — `RovaWarningsV3.kt`

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object RovaWarningsV3 {
    // Sheet
    val sheetCornerRadius = 26.dp
    val sheetHandleWidth = 32.dp
    val sheetHandleHeight = 3.dp
    val sheetHandleAlpha = 0.18f
    val sheetIconSize = 56.dp
    val sheetIconCornerRadius = 18.dp
    val sheetIconInnerStrokeAlpha = 0.22f
    val sheetIconGlowInset = (-22).dp     // glow extends beyond icon
    val sheetIconGlowBlur = 22.dp
    val sheetIconGlowAlpha = 0.70f
    val sheetTitleSize = 15.sp
    val sheetBodySize = 11.sp
    val sheetCtaHeight = 46.dp
    val sheetCtaCornerRadius = 14.dp
    val sheetSidePadding = 20.dp
    val sheetBottomPadding = 24.dp

    // Severity chip (inline, above title)
    val sevChipPaddingH = 10.dp
    val sevChipPaddingV = 4.dp
    val sevChipDotSize = 5.dp
    val sevChipFillAlpha = 0.13f
    val sevChipForegroundAlpha = 0.95f

    // Overflow ⋯ menu
    val overflowButtonSize = 28.dp
    val overflowTopInset = 14.dp
    val overflowRightInset = 18.dp

    // "Why this matters" expander
    val whyRowHeight = 36.dp
    val whyRowCornerRadius = 11.dp
    val whyRowBorderAlpha = 0.20f         // dashed
    val whyRowForegroundAlpha = 0.78f

    // Banner
    val bannerCornerRadius = 18.dp
    val bannerSidePadding = 12.dp
    val bannerVerticalPadding = 11.dp
    val bannerIconSize = 36.dp
    val bannerIconCornerRadius = 10.dp
    val bannerCountdownRingSize = 38.dp
    val bannerCountdownRingStroke = 3.dp
    val bannerCountdownTrackAlpha = 0.06f

    // CTA contrasts (a11y bump)
    val secondaryCtaTextAlpha = 0.68f     // was 0.55 on R2
    val secondaryCtaFillAlpha = 0.07f
    val secondaryCtaStrokeAlpha = 0.05f

    // Recovery card
    val recoveryCardCornerRadius = 20.dp
    val recoveryCardGlowHeight = 60.dp
    val recoveryCardGlowBlur = 28.dp
    val recoveryCardGlowAlpha = 0.50f
    val recoveryProgressCellHeight = 7.dp
    val recoveryProgressCellGap = 4.dp
    val recoveryProgressCellRadius = 3.5.dp
    val recoveryNumericChipMinWidth = 36.dp

    // Snooze chip
    val snoozeChipRadius = 999.dp
    val snoozeChipFillAlpha = 0.55f
    val snoozeChipBorderAlpha = 0.25f     // severity-tinted
    val snoozeChipDotPulseAlpha = 0.6f    // hard-block only

    /** Radial glow brush behind the sheet icon. */
    fun iconGlow(severityColor: Color): Brush = Brush.radialGradient(
        colors = listOf(
            severityColor.copy(alpha = sheetIconGlowAlpha * 0.65f),
            Color.Transparent,
        ),
    )

    /** Recovery-card top-edge glow brush. */
    fun recoveryGlow(severityColor: Color): Brush = Brush.verticalGradient(
        colors = listOf(
            severityColor.copy(alpha = recoveryCardGlowAlpha),
            Color.Transparent,
        ),
    )
}
```

`RovaWarnings` (R1 — `hard / soft / advisory / escalating`) is **kept** and reused. v3 only adds geometry + alpha tokens; the four severity colors are inherited.

### 3.3 · `WarningSheetContent.kt` — split + new helpers

Move out of `WarningCenter.kt` and into a dedicated file:

- `enum class WarningSurface { HardBlockSheet, SoftSheet, AdvisorySheet, TopBanner }` — unchanged
- `fun warningSurfaceFor(id: WarningId): WarningSurface` — unchanged
- `internal data class WarningAction(val label: String, val target: ActionTarget)` — unchanged
- `internal enum class ActionTarget` — unchanged
- `internal data class WarningSheetContent(...)` — **add** `val overflow: List<WarningAction> = emptyList()` and `val whyThisMatters: String? = null`
- `internal fun warningSheetContent(id: WarningId): WarningSheetContent` — **17 arms re-keyed**: BATTERY_OPTIMIZATION_ON / POWER_SAVE_MODE / NOTIFICATIONS_DENIED gain a `WarningAction("Don't show again", SNOOZE_FOREVER)`; advisory-tier ids gain `whyThisMatters` strings
- `internal data class TopBannerContent(...)` — **add** `val autoAction: AutoAction? = null`
- `internal data class AutoAction(val secondsRemaining: Int, val description: String)` — **NEW**; null = render CTA pill, non-null = render countdown ring
- `internal fun midRecBannerContent(id: WarningId): TopBannerContent` — THERMAL_EMERGENCY / THERMAL_SHUTDOWN gain `autoAction = AutoAction(30, "Will auto-stop to protect device")` placeholder; rest stay CTA
- **NEW pure helpers**:
  - `internal fun hasOverflow(id: WarningId): Boolean = warningSheetContent(id).overflow.isNotEmpty()`
  - `internal fun shouldShowWhy(id: WarningId): Boolean = warningSheetContent(id).whyThisMatters != null`
- **NEW**: `internal enum class ActionTarget` gains `SNOOZE_FOREVER` (no Intent — VM-only target)

### 3.4 · `WarningSheetV3.kt` — composable spec

```
@Composable
internal fun WarningSheetV3(
    id: WarningId,
    surface: WarningSurface,
    expanded: Boolean,                 // "Why this matters" expand state
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onOverflow: (ActionTarget) -> Unit,
    onToggleWhy: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Layout (top → bottom):**

1. `ModalBottomSheet` w/ `confirmValueChange` blocking hidden state for `HardBlockSheet` (unchanged from current).
2. Drag handle.
3. Overflow ⋯ icon button — top-right, alpha α0.30; absent unless `hasOverflow(id)`. Tap → dropdown menu over the sheet, items from `content.overflow`. Each item → `onOverflow(target)`.
4. Icon row (centered):
   - `Box(56.dp)`:
     - Glow layer: `Modifier.fillMaxSize().offset(-22.dp, -22.dp)` → `Box(matchParent)` painted with `RovaWarningsV3.iconGlow(accent)` + `blur(22.dp)`.
     - Icon container: `Surface(shape = RoundedCornerShape(18.dp), color = accent.copy(alpha = 0.14f), border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)))` → `Icon(content.icon, tint = accent)`.
5. Severity chip (centered): `Row` with leading dot + "Hard · Required" / "Soft · Degraded mode" / "Advisory · Optional" (per-tier copy, NOT a free-form string).
6. Title — `style = MaterialTheme.typography.titleMedium`, `fontWeight = SemiBold`, color α0.94.
7. Body — `MaterialTheme.typography.bodySmall`, color α0.45, center-aligned, line-height 1.65.
8. "Why this matters" expander — only when `shouldShowWhy(id)`. Collapsed: 36dp dashed-border row labelled "Why this matters" + chevron. Expanded: row reveals `content.whyThisMatters` body text below it.
9. Primary CTA — 46dp height, severity-tinted shadow.
10. Secondary CTA — α0.07 fill, α0.68 text.

Note: the `ws-meta` storage-figures row (mockup tile 2.1) and the tertiary destructive link (mockup tile 2.4 "Discard session") are **out of scope** — they require new fields on `WarningSheetContent` plus dynamic figures from `StorageSignal`, and the 3-way confirmation sheet (vacant `C4.3`) is parked. Mockup tiles 2.1 and 2.4 are aspirational for this slice; the v3 render for `STORAGE_INSUFFICIENT` keeps the current static body copy ("Free up space, then try again.") until Phase 4.4 adds the dynamic figures.

**Severity → color** (unchanged from current `WarningCenter.kt`):
- `HardBlockSheet` → `RovaWarnings.hard`
- `SoftSheet` → `RovaWarnings.soft`
- `AdvisorySheet` → `RovaWarnings.advisory`

### 3.5 · `WarningTopBannerV3.kt` — composable spec

```
@Composable
internal fun WarningTopBannerV3(
    content: TopBannerContent,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Layout (left → right):**

1. `Surface` — 18dp radius, glass black α0.88, border = severity-tinted α0.24..0.32 by severity, backdrop-filter blur (Compose impl: no-blur fallback — same as `RecordChromeTokens.settingsCardFill` rationale).
2. Icon container — 36dp rounded 10dp, severity-tinted fill α0.16.
3. Text column — title 11.5sp SemiBold α0.88, sub 9.5sp α0.48 with `tnum`.
4. **Branch on `content.autoAction`:**
   - `null` → CTA pill 38dp, severity-tinted fill α0.18..0.22, "STOP" label uppercase tracked.
   - non-null → `CountdownRing(secondsRemaining, severityColor)`:
     - 38dp box; `Canvas` draws background ring α0.06 + foreground arc (severity, stroke α0.85, stroke-width 3dp, `Stroke(cap = Round)`, `startAngle = -90f`, `sweepAngle = 360f * (secondsRemaining / totalSeconds)`).
     - Center: `Text(secondsRemaining.toString())` 11sp SemiBold α0.85 `tnum`.

**Static-placeholder note.** This slice renders `secondsRemaining` from `AutoAction.secondsRemaining` — set to `30` in `midRecBannerContent` for `THERMAL_EMERGENCY` and `THERMAL_SHUTDOWN`. The ring **does not tick** in this slice (no source-of-time wired). Future Phase 4.4 swaps `AutoAction` to read from a `ThermalAutoStopSignal` flow.

### 3.6 · `WarningSnoozeChip.kt` — composable spec

Replaces the anonymous `WarningChip` inside `WarningCenter.kt`.

```
@Composable
internal fun WarningSnoozeChip(
    id: WarningId,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- 999dp pill, glass black α0.55, severity-tinted border α0.25.
- Leading dot — severity-tinted; hard-block ids get a soft pulse animation (`infiniteTransition`, alpha 0.6 → 1.0 → 0.6, period 1500ms).
- Icon — 12dp, white α0.78.
- Label — content title, 10.5sp Medium α0.78, single-line, `TextOverflow.Ellipsis`.
- Trailing hint — "· tap" — 10sp α0.42.

### 3.7 · `WarningCenter.kt` — slimmed routing

Becomes ~120L. Responsibility:

1. Read app signals → instantiate VM (unchanged from current).
2. Collect `activeWarning` + `dismissedWarnings` + `expandedWhy` (NEW).
3. Branch on `hudState`:
   - `Idle` + `surface == TopBanner` → no-op (mid-rec only).
   - `Idle` + `id in dismissed` → `WarningSnoozeChip`.
   - `Idle` + not dismissed → `WarningSheetV3(...)`.
   - Active (Recording / Waiting / Merging) + `surface != TopBanner` → no-op (sheet ids suppress during active).
   - Active + `surface == TopBanner` → `WarningTopBannerV3(...)`.

Action wiring:
- `onPrimary` → `launchActionTarget(...)` + `vm.dismiss(id)` (unchanged).
- `onSecondary` → `vm.dismiss(id)` (unchanged).
- `onOverflow(SNOOZE_FOREVER)` → `vm.snoozeForever(id)`.
- `onOverflow(other)` → `launchActionTarget(other)`.
- `onToggleWhy` → `vm.toggleExpandWhy(id)`.

### 3.8 · `WarningCenterViewModel.kt` — additions

```kotlin
private val _expandedWhy = MutableStateFlow<Set<WarningId>>(emptySet())
val expandedWhy: StateFlow<Set<WarningId>> = _expandedWhy.asStateFlow()

fun toggleExpandWhy(id: WarningId) {
    _expandedWhy.update { if (id in it) it - id else it + id }
}

// Snooze "forever" — in-memory only (Phase 4.1c will persist).
private val _snoozedForever = MutableStateFlow<Set<WarningId>>(emptySet())
val snoozedForever: StateFlow<Set<WarningId>> = _snoozedForever.asStateFlow()

fun snoozeForever(id: WarningId) {
    _snoozedForever.update { it + id }
}
```

**`activeWarning` flow gains a filter step:**

```kotlin
val activeWarning: StateFlow<WarningId?> = combine(
    resolvedWarning,    // from existing aggregate(...)
    _snoozedForever,
) { resolved, snoozed -> resolved?.takeIf { it !in snoozed } }
    .stateIn(viewModelScope, ..., null)
```

**Invariant preserved:** the Start-gate in `RecordScreen` still reads leaf signals directly (`cameraPermissionGranted` / `storageInsufficient`), not `activeWarning`. Snooze does **not** open the gate. The contract bug from R2 ("hard-block dismissed dismisses the *banner*, not the *gate*") stays correct.

### 3.9 · `RecoveryCard.kt` — re-skin

The composable signature stays (Kotlin source compat). Only the rendering changes:

- Outer `Surface` — 20dp radius, glass black α0.94, border α0.08, `blur` backdrop fallback.
- Top glow bloom — `Box(modifier = Modifier.fillMaxWidth().height(60.dp).offset(y = -20.dp).blur(28.dp))` painted with `RovaWarningsV3.recoveryGlow(severityColor)`. Severity color: red for `Terminated.KILLED_BY_SYSTEM`, amber for `KILLED_FORCE_STOP`, red for `MERGE_FAILED`.
- Tag chip — 999dp pill w/ leading dot + uppercase label, matches v3 sheet sev-chip pattern.
- Title — 14sp SemiBold α0.92.
- Description — 10.5sp α0.45, line-height 1.6, `em` highlights α0.80.
- **Progress label row** — `Clips captured` (left) + `4 / 6` numeric chip (right, α0.78, `tnum`). NEW.
- Progress cells — 7dp tall (was 6), 3.5dp radius. Saved cells: `RovaWarnings.suc` (green) when terminated normally vs `RovaWarnings.soft` (amber) when force-stopped — drives via severity. Lost cells: white α0.08.
- Buttons — 40dp height, 12dp radius (matches sheet hierarchy at smaller scale).
- Extra-row — chevron added on the right. Vendor-guidance link only renders for `KILLED_BY_SYSTEM` (existing behaviour); destructive "Discard all segments" only renders for `MERGE_FAILED` (existing behaviour).

`RecoveryViewModel` / `RecoveryUiState` / `RecoveryViewSource` — **untouched**. Tests stay green.

### 3.10 · Mounting — no change

Both mounts in `RecordScreen.kt` stay identical:

- Idle mount: top-anchored Box at `padding(start = 16.dp, top = 110.dp)`, `WarningCenter(hudState = Idle, onStopRecording = {})`.
- Active mount: top-centered Column under status bar, `WarningCenter(hudState = ..., onStopRecording = { vm.stopRecording() })`.

`HistoryScreen.kt` — recovery-card mount point stays. The re-skin happens inside `RecoveryCard.kt`.

## 4 · Data flow

```
RovaApp signals  ─▶  WarningCenterViewModel.aggregate(...)
                              │
                              │  resolved: StateFlow<WarningId?>
                              ▼
                  combine(resolved, snoozedForever)
                              │
                              │  activeWarning: StateFlow<WarningId?>
                              ▼
   WarningCenter (routing)  ◀─┘
       │
       │ branch on hudState + WarningSurface
       ├─▶ WarningSheetV3        (idle, not dismissed)
       │       │
       │       ├─ onPrimary → launchActionTarget + vm.dismiss
       │       ├─ onSecondary → vm.dismiss
       │       ├─ onOverflow(SNOOZE_FOREVER) → vm.snoozeForever
       │       ├─ onOverflow(other) → launchActionTarget
       │       └─ onToggleWhy → vm.toggleExpandWhy
       │
       ├─▶ WarningSnoozeChip     (idle, dismissed)
       │       └─ onExpand → vm.restore
       │
       └─▶ WarningTopBannerV3    (active + TopBanner surface)
               └─ onAction → onStopRecording (unchanged)
```

`expandedWhy` is a Set per WarningId. Sheet binds `expanded = id in expandedWhy.collectAsState()`. Stale entries don't leak: when `activeWarning` becomes null the sheet unmounts; `expandedWhy` keeps the entry but it's only read while that sheet is in composition. Acceptable.

## 5 · Testing

Follows ADR-0007 §"Testing" precedent: pure-helper tests, no compose-UI tests.

**New / kept tests:**

- `WarningSheetContentTest` (existing, kept) — 17 arms, asserts title / body / primary / secondary not blank for each id (except the 10 TopBanner ids where body is `""` per current).
- `WarningSheetContentV3Test` (NEW) — assertions:
  - `hasOverflow(BATTERY_OPTIMIZATION_ON) == true`
  - `hasOverflow(POWER_SAVE_MODE) == true`
  - `hasOverflow(NOTIFICATIONS_DENIED) == true`
  - `hasOverflow(CAMERA_PERMISSION_DENIED) == false`
  - `hasOverflow(...)` for all 17 ids — pinned values
  - `shouldShowWhy(NOTIFICATIONS_DENIED) == true`
  - `shouldShowWhy(BATTERY_OPTIMIZATION_ON) == true`
  - `shouldShowWhy(CAMERA_PERMISSION_DENIED) == false`
  - `shouldShowWhy(...)` for all 17 ids — pinned values
  - `midRecBannerContent(THERMAL_EMERGENCY).autoAction != null`
  - `midRecBannerContent(THERMAL_SHUTDOWN).autoAction != null`
  - `midRecBannerContent(BATTERY_CRITICAL).autoAction == null`
  - `midRecBannerContent(STORAGE_LOW_MID_REC).autoAction == null`
- `RovaWarningsV3Test` (NEW) — pure-JVM:
  - Numeric token values pinned (`sheetIconSize == 56.dp`, `bannerCountdownRingSize == 38.dp`, etc.) — prevents accidental deletions.
  - `iconGlow(...)` and `recoveryGlow(...)` return non-null `Brush`.
- `WarningCenterViewModelTest` (existing, EDITED) — add coverage:
  - `toggleExpandWhy(id)` toggles set membership.
  - `snoozeForever(id)` removes id from `activeWarning` stream until app restart.
  - `snoozeForever(CAMERA_PERMISSION_DENIED)` — assert it works (in-memory only — Start-gate is separately read off leaf signal, so the underlying signal still gates Start; this test pins that contract).
- `WarningPrecedenceTest` (existing) — untouched.
- `WarningIdOrderTest` (existing) — untouched.
- `RecoveryUiStateMapperTest` (existing) — untouched.
- `RecoveryViewModelTest` (existing) — untouched.

**No new Compose-UI / Robolectric tests.** Sheet / banner / chip behaviour is owner-verified on-device, matching the ADR-0007 precedent ("the sheet behaviour is owner-verified on device"). The same applies to the recovery-card re-skin.

## 6 · Error handling

- Snooze + dismiss interaction: a dismissed warning collapses to the snooze-chip (existing behaviour). A `snoozeForever`'d warning **disappears entirely** until app restart (chip is not shown). Distinct from dismiss — UI surface in the overflow ⋯ menu uses the label "Don't show again" so the difference is clear.
- Overflow ⋯ menu opens a dropdown anchored to the icon. Tapping outside dismisses the menu but **not** the sheet.
- "Why this matters" expander state survives sheet recomposition (state in VM). When the sheet auto-presents again on a new active warning, `expandedWhy` is empty — fresh state per sheet open.
- Countdown ring: this slice renders a static placeholder. Tapping the banner does **not** stop the countdown — Phase 4.4 may add a cancel affordance.
- Recovery-card severity-to-color routing: `KILLED_BY_SYSTEM` → red, `KILLED_FORCE_STOP` → amber, `MERGE_FAILED` → red, anything else → amber (defensive default — keeps the card legible even if a new `Terminated` variant ships without explicit re-skin support).

## 7 · Slice / PR plan

**PR A — Record-screen v3** (~8 files, single atomic commit-gated change)

Files:
1. `RovaWarningsV3.kt` (new tokens)
2. `WarningSheetContent.kt` (split + new helpers + AutoAction)
3. `WarningSheetV3.kt` (new composable)
4. `WarningTopBannerV3.kt` (new composable + CountdownRing)
5. `WarningSnoozeChip.kt` (extracted)
6. `WarningCenter.kt` (slim to ~120L; delete inline composables)
7. `WarningCenterViewModel.kt` (+expandedWhy, +snoozeForever, +activeWarning filter)
8. `WarningSheetContentV3Test.kt` + `RovaWarningsV3Test.kt` + `WarningCenterViewModelTest.kt` edits

Compile-gate split: a single PR is fine — the split is mechanical, and the new composables atomically replace the old ones at the routing layer. No "old + new in parallel" period.

**PR B — Library recovery card v3** (~3 files)

Files:
1. `RecoveryCard.kt` (re-skin)
2. `RovaWarningsV3.kt` (no new tokens beyond what PR A added; possibly no-op for this PR)
3. Owner-verifies on device.

Ordering: PR A must merge first (it ships `RovaWarningsV3`). PR B depends on it.

**ADR-0013 lands with PR A** — documents the v3 chrome canon (glow bloom, severity chip, overflow ⋯, countdown ring, snooze-chip) and amends ADR-0007 with a forward pointer.

## 8 · Token / contract migration risks

- `WarningSheetContent` data class gains 2 fields (`overflow`, `whyThisMatters`). Existing call sites use named args — no positional-call breakage. Tests that construct `WarningSheetContent(...)` directly need to add the new fields or use defaults. `WarningSheetContentTest` uses helper-return values, not direct construction, so it stays green.
- `TopBannerContent` gains 1 field (`autoAction`). Same risk profile — defaulted to null.
- `ActionTarget` gains `SNOOZE_FOREVER`. `launchActionTarget` must throw / no-op when called with `SNOOZE_FOREVER` (defensive — `WarningCenter` routes it to the VM, not `launchActionTarget`, but the enum exhaustiveness must be handled).
- `RovaWarnings` is **not modified** — `RovaWarningsV3` is additive. Old code that imports `RovaWarnings.hard` keeps working.

## 9 · Open questions

1. **Severity-chip copy:** the chip text ("Hard · Required", "Soft · Degraded mode", "Advisory · Optional") is per-tier in this design — not per-id. Some ids may want id-specific copy (e.g. "Soft · Audio off" for `MICROPHONE_DENIED`). For v1: per-tier. Per-id can land in 4.4 via `content.severityChipLabel` field if owner wants it.
2. **"Why this matters" copy:** the body text for the expander is a per-id `String?` on `WarningSheetContent`. For v1, advisory-tier only (NOTIFICATIONS_DENIED / BATTERY_OPTIMIZATION_ON / POWER_SAVE_MODE / BATTERY_LOW). Other tiers: `null` → expander not rendered. Open: do MICROPHONE_DENIED and EXACT_ALARM_DENIED also need it? Defer to owner review.
3. **Countdown ring static value (30s):** placeholder pending Phase 4.4 hysteresis wiring. Owner: OK to ship with static 30 for now, or should the ring be hidden entirely (CTA fallback) until 4.4 is ready?
4. **Snooze persistence (4.1c):** the in-memory snooze in this slice resets on process restart. Acceptable trade-off: a user who "Don't show again"s a battery-opt nudge gets it back after a phone reboot — matches the original spec (`WarningCenterContract.md` §5.2 — "the user gets the banner back on cold start, by design"). Owner: confirm this UX matches intent.

## 10 · Out of scope (explicit park list — Phase 4.4 candidate)

- Phase 4.1c snooze persistence + thermal/storage hysteresis
- `SD_CARD_EJECTED` WarningId + `SdCardSignal`
- `STORAGE_FULL_AUTOSTOPPED` distinct sheet variant (today: routes to `STORAGE_INSUFFICIENT` sheet copy when no recording is active)
- Stop-recording confirmation sheet (vacant `C4.3` in `WarningCenterContract.md`)
- Inline export-failed history tile (`C5.3`) — needs `HistoryScreen` item-composable refactor
- Real thermal-hysteresis seconds-source for the countdown ring
- Distinct severity-chip copy per id
- Animated sheet entrance (in / out spring) beyond current `ModalBottomSheet` defaults
- Haptic feedback wiring on sheet present / banner appear

---

## Appendix · WarningId → v3 surface map (no change vs R2)

| Id | Tier | Surface (idle) | Surface (active) | v3 additions |
|---|---|---|---|---|
| `CAMERA_PERMISSION_DENIED` | HARD_BLOCK | HardBlockSheet | — | overflow ⋯ disabled |
| `EXACT_ALARM_DENIED` | HARD_BLOCK | HardBlockSheet | — | overflow ⋯ disabled |
| `STORAGE_INSUFFICIENT` | HARD_BLOCK | HardBlockSheet | — | — (dynamic `ws-meta` row deferred to 4.4) |
| `THERMAL_SHUTDOWN` | CRITICAL | — | TopBanner | **countdown ring** |
| `THERMAL_EMERGENCY` | CRITICAL | — | TopBanner | **countdown ring** |
| `THERMAL_CRITICAL` | CRITICAL | — | TopBanner | CTA |
| `BATTERY_CRITICAL` | CRITICAL | — | TopBanner | CTA |
| `CAMERA_IN_USE` | CRITICAL | — | TopBanner | CTA |
| `CAMERA_DISABLED` | CRITICAL | — | TopBanner | CTA |
| `BATTERY_LOW` | ADVISORY | — | TopBanner | CTA, **Why expander** |
| `STORAGE_LOW_MID_REC` | ADVISORY | — | TopBanner | CTA |
| `THERMAL_SEVERE` | ADVISORY | — | TopBanner | CTA |
| `MICROPHONE_DENIED` | ADVISORY | SoftSheet | — | — |
| `BATTERY_OPTIMIZATION_ON` | ADVISORY | AdvisorySheet | — | **overflow ⋯**, **Why expander** |
| `POWER_SAVE_MODE` | ADVISORY | AdvisorySheet | — | **overflow ⋯**, **Why expander** |
| `THERMAL_MODERATE` | ADVISORY | — | TopBanner | CTA |
| `NOTIFICATIONS_DENIED` | ADVISORY | AdvisorySheet | — | **overflow ⋯**, **Why expander** |

(17 rows. No reorder. No tier change. No `gatesStart` change.)
