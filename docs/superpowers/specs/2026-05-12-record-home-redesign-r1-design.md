# Record-home redesign — R1: idle screen + chrome + warning sheets

**Status:** Design — awaiting owner review
**Date:** 2026-05-12
**Branch (to be):** `feat/record-home-redesign-r1` off `master` @ `e505c35`
**PR:** one PR, `--base master`, many per-task commits
**Supersedes / amends:** `NEW_UI_BACKEND_REPLAN.md` Phase 4 ("one inline Record-screen banner") → see ADR-0007; `docs/UI_NAV_GRAPH.md` (the 3-tab persistent NavigationBar model)
**Reference UX (definitive):** `mockups/new_uiux/01-record-home.html`, `02-settings-sheet.html`, `07-warnings.html`; chrome cues from `03-history-library.html`, `06-app-settings.html`; `mockups/new_uiux/PROJECT_CONTEXT.md`

---

## 1. Why

`mockups/new_uiux/` is the definitive UI/UX for v1.0.0. The shipped Record screen is the *interim* implementation (a tall opaque navy "Session plan" dock + a full-width Start button + the inline `WarningCenter()` banner from Phase 4.1/4.1b + a 3-tab persistent bottom `NavigationBar`). It diverges wholesale from `01-record-home.html`'s camera-first minimal-overlay design, and the divergence is *also* a live bug: on the idle screen the navy dock paints over the lower portion of the `WarningCenter()` banner (a `Box`-overlay sibling collision — see `screenshots/Screenshot_20260512_114520.png` / `..._114557.png`).

Rather than band-aid the dock (it's getting deleted), R1 converges the Record screen onto the definitive design. R1 is the first of three slices:

- **R1 (this spec)** — idle Record-home + the new chrome (camera viewfinder full-bleed, floating status pill, floating flash/flip controls, the swipe-up settings card → combined settings sheet, the bottom nav with a center Start/Stop FAB) + the `07-warnings.html` warning-sheet treatment (replacing the inline banner) + the navigation restructure (Record owns its bottom nav; History/Settings become drill-down screens). The occlusion bug is moot here — there is no opaque dock, and warnings are sheets, which float.
- **R2 (later)** — active-state restyle (Recording / Waiting / Merging → loop pill + status pill + the new bottom-nav Stop FAB, retiring `SessionTimer` / `ClipProgressBand` / `WaitingCountdown` / `MergingProgressBand` / `RecordStatusStrip` as the layout). R1 only renders the *chrome* in those states and reroutes Stop through the FAB; the active *content* stays as-is in R1.
- **R3 (later)** — folded into R1 (warning sheets done here).

> Onboarding, App Settings (`06-app-settings.html`), Library (`03-history-library.html`), Player (`04-video-player.html`) re-skins are *separate* slices already tracked in the replan; R1 touches History/Settings only enough to add a back affordance.

### Scope decisions locked with the owner (2026-05-12)

| # | Decision |
|---|---|
| D1 | **Full navigation restructure** — kill `MainScreen`'s persistent 3-tab `NavigationBar`; the Record screen owns its own bottom nav (Library / center Start-or-Stop FAB / Settings); `history` and `settings` become drill-down routes with a back arrow, like `player/{sessionId}`. |
| D2 | **Presets dropped for v1.0.0** — no preset chips on idle, no preset section in the settings sheet, no "Save current". `RovaPreset` / `RovaSettings.customPresetsJson` / the VM preset methods go dormant (kept on disk for forward-compat, not surfaced). |
| D3 | **Edit flow = card → combined sheet → per-row picker.** The settings card is a display-only strip; tapping/swiping it opens the combined per-session sheet; tapping a row in that sheet opens that param's *existing* edit-sheet body (`ClipLengthEditSheet`/etc.), which commits on its own "Done". No draft layer — every commit writes through to `RovaSettings` immediately, as today. The combined sheet's bottom button is "Done" (just closes). |
| D4 | **"Mode" cell read-only "Portrait"** for v1.0.0 (the Portrait/Landscape picker ships with Phase 6; P+L deferred). |
| D5 | **Warning sheets per `07-warnings.html` fold into R1** (the old "R3"). Replaces the inline `WarningCenter()` banner. Keeps the `WarningCenterViewModel` precedence logic and the hard-block Start-gate. Needs ADR-0007 + a `NEW_UI_BACKEND_REPLAN.md` Phase-4 amendment. |
| D6 | **Active states: R1 = chrome only.** R1 swaps in the bottom nav + FAB (Stop routed through the FAB, today's big-red-Stop button removed) and repositions today's active-HUD pieces so they don't collide. The loop-pill/status-pill restyle is R2. |
| D7 | **One PR**, per-task commits (Approach A). The two-PR split was rejected because R1a would *release* with the old banner on the new overlay — the half-warning state the owner ruled out. |
| A1 | Hamburger **drawer** ("Quick settings": Keep Screen On / Sounds) — deleted (redundant with `SettingsScreen`). |
| A2 | 3-step **tutorial overlay** + Info button — deleted (Onboarding owns the walkthrough). |
| A3 | **Recovery echo** — kept as a small low-key chip near the status pill on the Record idle screen (not dropped; not a full banner). |
| A4 | `flashMode` stays a session-only (non-persisted) setting, controlled from the floating flash button — unchanged. |
| A5 | `RecordViewModel` un-hoisted from `MainScreen` back to `RecordScreen` scope (it was hoisted only so the old nav bar could read `serviceState`). If the plan finds keeping it hoisted lower-risk, that's acceptable — note it either way. |
| A6 | Mid-recording **thermal/storage top-banner** surface deferred to R2 unless trivially cheap to add the one render path in R1. |

---

## 2. Architecture & component map

### 2.1 Navigation (`ui/MainScreen.kt`, `ui/MainActivity.kt`)

**Today:** outer `Scaffold { bottomBar = NavigationBar(Record | History | Settings) }` + `NavHost(onboarding, record, history, player/{sessionId}, settings)`. `sessionLocked = isPeriodicActive || isMerging` disables all tabs + shows a "Locked during recording" pill. `RecordViewModel` is hoisted to `MainScreen` scope so the nav bar can read `serviceState`. `TOP_LEVEL_ROUTES` guard collapses the nav bar on `player/{sessionId}` and `onboarding`.

**R1:**
- `MainScreen`'s outer `Scaffold` loses its `bottomBar` — it becomes a plain host for the `NavHost`. The onboarding gate (`startDestination` from `RovaSettings.onboardingCompleted`) and `MainActivity`'s recovery-scan trigger are unchanged. `TOP_LEVEL_ROUTES` / nav-bar-collapse machinery is deleted (no nav bar to collapse).
- The `record` route hosts `RecordScreen`, which now renders its own bottom nav (see §2.2). `RecordViewModel` un-hoisted to `viewModel()` inside `RecordScreen` (A5).
- `history` and `settings` become drill-down routes: each gets an `onBack: () -> Unit = { navController.popBackStack() }` passed in. `HistoryScreen`'s `TopAppBar` gains `navigationIcon = { IconButton(onClick = onBack) { Icon(AutoMirrored.Filled.ArrowBack, "Back") } }` (per `03-history-library.html`'s `lib-back`); `SettingsScreen`'s `TopAppBar` gains the same (per `06-app-settings.html`'s `top-back`). Their *content* is untouched in R1.
- Back-stack: `record` stays the post-onboarding start destination; Library/Settings push onto it (`launchSingleTop = true`); system-back from Library/Settings → Record; system-back from Record → exits the app. `sessionLocked` disables the Library/Settings nav items on the Record screen (no other path off the Record screen exists), preserving today's "can't leave Record mid-recording" behaviour.
- `docs/UI_NAV_GRAPH.md` is updated to describe the new model (separate task; doc-only).

### 2.2 Record screen structure (`ui/screens/RecordScreen.kt` + new composables)

`RecordScreen` becomes (conceptually):

```
Scaffold(containerColor = Black, snackbarHost = …) { innerPadding ->
  Box(fillMaxSize, padding(innerPadding)) {
    CameraPreviewLayer()                       // AndroidView(previewView), fillMaxSize — unchanged
    CameraWarmupOrInitOverlay()                // the existing "Initializing Camera…" overlay — unchanged
    when (hudState) {
      Idle    -> RecordIdleBody()              // §2.3 — new
      Recording, Waiting -> RecordActiveBody() // R1: today's pieces, repositioned (no big-red-Stop)
      Merging -> RecordMergingBody()           // R1: today's MergingProgressBand, repositioned
    }
    RecordChrome(hudState, …)                  // §2.4 — top overlay + cam-controls + (idle) settings card + bottom nav
    WarningSurface()                           // §2.5 — the WarningSheet / chip (replaces WarningCenter() banner)
    MergeCompleteCard?                          // unchanged absolute overlay
    CameraDisconnectedAlert?                    // unchanged AlertDialog
  }
  SessionSettingsSheet?                          // §2.3 — the combined sheet (outside the Box so it overlays everything)
  EditSheets(editingField)                       // the existing ClipLength/Repeats/Wait/Quality sheets — unchanged bodies
}
```

New composables (exact file split is the plan's call — single `RecordChrome.kt` or split):
- `RecordTopOverlay(hudState, …)` — the glass `status-pill` (idle: "Ready to record" + idle dot; Recording: "Recording · 0:NN left" + blinking red dot; Waiting: "On break · next in 0:NN" + grey dot) and, in active states, the `loop-pill` ("N/M loops done"). In R1 the active variants reuse today's elapsed/countdown values; visual polish is R2.
- `RecordCameraControls(flashMode, onCycleFlash, onFlip, enabled)` — two small glass circle buttons, top-right. Replaces the flash/flip `IconButton`s in today's app-bar `Row`. `enabled = !isUiLocked`.
- `RecordSettingsCard(duration, loopCount, interval, quality, onOpenSheet)` — idle only; the "swipe to edit" hint + the glass strip of five display cells (Clip / Repeats / Wait / Quality / Mode-readonly-"Portrait") + a chevron; whole-card tap **and** a swipe-up gesture both → `onOpenSheet()`.
- `RecordBottomNav(hudState, sessionLocked, fabState, onLibrary, onSettings, onFabClick)` — the glass bottom bar: Library item (icon + "Library", dimmed+disabled when `sessionLocked`), center FAB (`fabState`: `Start` = play triangle / `Stop` = red square / `Disabled` = dimmed), Settings item (icon + "Settings", dimmed+disabled when `sessionLocked`). `onFabClick` = the existing `onStart` handler when idle / `viewModel.stopRecording()` when Recording|Waiting / no-op when disabled.
- `SessionSettingsSheet(state, onPickRow, onDismiss)` — the combined `ModalBottomSheet` (§2.3).
- `RecordRecoveryChip(count, onReview)` — the small recovery affordance (A3); restyle of `RecoveryEchoBanner`'s content into a chip, or a new small composable. Logic (RovaApp.recoveryReport echo + `RecoveryViewSource.eligibleSessionCount`) unchanged.

**FAB state — pure helper (testable):**
```kotlin
enum class RecordFabState { Start, Stop, Disabled }
fun recordFabState(hudState: RecordHudState, sessionLocked: Boolean, hardBlockActive: Boolean): RecordFabState =
  when {
    hudState == RecordHudState.Idle && hardBlockActive -> RecordFabState.Disabled
    hudState == RecordHudState.Idle                    -> RecordFabState.Start
    hudState is RecordHudState.Recording || hudState is RecordHudState.Waiting -> RecordFabState.Stop
    else /* Merging */                                 -> RecordFabState.Disabled   // merge runs NonCancellable
  }
```
`hardBlockActive` = `!cameraPermissionGranted || storageInsufficient` (today's `startBlocked`, read from the same leaf signals). When `Disabled` for a hard-block, tapping the FAB is a no-op — the warning sheet (auto-presented, §2.5) carries the actionable CTA. (`sessionLocked` is `isPeriodicActive || isMerging` — used for dimming the Library/Settings items; the FAB is `Stop` during an active session, not disabled.)

### 2.3 Combined settings sheet (`ui/screens/SessionSettingsSheet.kt`)

A `ModalBottomSheet` (`02-settings-sheet.html` styling, glass over the dimmed viewfinder):
- handle
- **"Recording mode"** section — a single non-interactive "Portrait" chip with a muted "Landscape coming soon" suffix (D4). No state, no callback. The real selector is a Phase-6 deliverable.
- **"Session settings"** section — four rows: **Clip duration / Repeats / Wait between / Quality** — each `label · currentValue · chevron`, tappable.
- **"Done"** button at the bottom — dismisses the sheet. (Not a transaction boundary; every per-row edit already committed through.)

Tapping a row opens that parameter's existing picker. **The picker body is reused unchanged** — `ClipLengthEditSheet` / `RepeatsEditSheet` / `WaitEditSheet` / `QualityEditSheet` keep their `(initialValue, onCommit, onCancel)` contract, and each commits via `onCommit` → `viewModel.duration.value = …` (etc.) → the existing `RovaSettings` collectors persist it. *How* the picker is presented relative to the combined sheet is the plan's call between two equivalent shapes: **(a)** the picker opens as a second `ModalBottomSheet` stacked on top of the combined sheet (combined sheet stays mounted underneath; closing the picker returns to it) — minimal change, reuses the standalone composables as-is; or **(b)** the combined sheet has an internal "page" state — tapping a row swaps its content for the picker's *body* (the part inside `EditSheetShell`), with a back affordance returning to the row list — one sheet, in-sheet navigation. Either way the four `SheetTarget` branches at the end of `RecordScreen` (the standalone-sheet router) stay for any callers outside the combined sheet, and the per-row commit path is identical.

State: the combined-sheet open/closed flag — either a new `mutableStateOf(false)` in `RecordScreen` or a new `SheetTarget`-sibling on the VM (`combinedSettingsOpen: StateFlow<Boolean>`); plan's call. The four `SheetTarget` branches at the end of `RecordScreen` stay; the combined-sheet branch is added above them. Stepper/chip components (`LargeValueStepper`, `QuickSetChipRow`, `FixedContinuousSelector`, `QualityOptionSelector`) — untouched.

Cell value formatters for the settings card: reuse `UiCopy` / port the relevant `RecordIdleDock` private helpers (`clipLengthCellValue` "10 s", `repeatsCellValue` "Until you stop", `waitCellValue` "None"/"1 m", quality as-is, mode = "Portrait"); if ported, port their tests.

### 2.4 (folded into 2.2)

### 2.5 Warnings (`ui/warnings/WarningCenter.kt`, new `ui/warnings/WarningSheet.kt` or in-file)

**Unchanged:** `WarningCenterViewModel`, `WarningPrecedence.resolve(...)` → the single highest-priority active `WarningId`; the nine leaf-signal flows; the `WarningCenter()` factory wiring; the `WarningId` enum + `tier`/`gatesStart`; `ActionTarget` / `launchActionTarget`. **The hard-block Start-gate is unchanged** — `RecordScreen` still reads `cameraPermissionSignal` / `storageSignal` directly for `hardBlockActive` (now also feeding `recordFabState`); this is orthogonal to the sheet.

**Changed — presentation only:** `WarningBanner` (the inline `Surface` strip) → `WarningSheet`, a bottom sheet styled per the warning's tier (`07-warnings.html`):

| Tier | `WarningId`s | Surface | Behaviour |
|---|---|---|---|
| Hard block | `CAMERA_PERMISSION_DENIED`, `EXACT_ALARM_DENIED`, `STORAGE_INSUFFICIENT` | red-accented `WarningSheet` (handle, tinted icon, title, body, **primary** CTA, **secondary** "Not now") | auto-presents when the Record screen is shown with a hard-block active; Start FAB → `Disabled`, Library/Settings dimmed; "Not now" collapses the sheet to a small glass chip under the status pill (tap → re-opens); primary CTA → `launchActionTarget` (App Settings / exact-alarm settings / App Settings-for-storage) |
| Soft | `MICROPHONE_DENIED` | amber `WarningSheet` | primary "Grant microphone access", secondary **"Continue without audio"** (dismisses to a chip; Start stays enabled — video-only per ADR 0006 B18) |
| Advisory | `NOTIFICATIONS_DENIED`, `BATTERY_OPTIMIZATION_ON`, `POWER_SAVE_MODE` | blue/neutral `WarningSheet` | primary "Turn on … / Disable …", secondary **"Not now"** (dismisses to a chip); does not block Start |
| Critical / mid-recording | `THERMAL_SHUTDOWN/EMERGENCY/CRITICAL/SEVERE/MODERATE`, `BATTERY_CRITICAL`, `BATTERY_LOW`, `CAMERA_IN_USE`, `CAMERA_DISABLED` | `07-warnings.html` shows these as a **top banner** over the active viewfinder, not a sheet | mostly fire while recording (R2 territory); R1 wires the idle-reachable ones (battery-opt, power-save, notifications + the perm/alarm/storage hard-blocks) as sheets; the mid-recording top-banner render path is **deferred to R2** unless trivially cheap (A6). The spec's `warningSurfaceFor` mapping below pins each one. |

**Pure helpers (testable):**
```kotlin
sealed interface WarningSurface { object HardBlockSheet; object SoftSheet; object AdvisorySheet; object TopBanner }
fun warningSurfaceFor(id: WarningId): WarningSurface = when (id) {
  CAMERA_PERMISSION_DENIED, EXACT_ALARM_DENIED, STORAGE_INSUFFICIENT -> HardBlockSheet
  MICROPHONE_DENIED -> SoftSheet
  NOTIFICATIONS_DENIED, BATTERY_OPTIMIZATION_ON, POWER_SAVE_MODE -> AdvisorySheet
  THERMAL_SHUTDOWN, THERMAL_EMERGENCY, THERMAL_CRITICAL, THERMAL_SEVERE, THERMAL_MODERATE,
  BATTERY_CRITICAL, BATTERY_LOW, CAMERA_IN_USE, CAMERA_DISABLED -> TopBanner
}
data class WarningSheetContent(val icon: ImageVector, val title: String, val body: String,
  val primary: WarningAction, val secondary: WarningAction)
fun warningSheetContent(id: WarningId): WarningSheetContent  // the 16-arm map, ex-`bannerContent`, now with primary+secondary
```
Unit tests: one assertion per `WarningId` for both (mirrors `WarningIdOrderTest`). `WarningPrecedenceTest` / `WarningCenterAggregateTest` unchanged (logic untouched).

**`RecoveryEchoBanner`** → `RecordRecoveryChip` (A3): a small glass chip near the status pill — icon + "N recording(s) interrupted" + "Review" → `onNavigateToHistory`. Same eligibility logic. Recovery *cards* remain a Library surface (replan); this is just the landing-screen nudge.

**ADR + replan amendment (R1 deliverable):** `docs/adr/0007-record-warning-sheets.md` — "the `07-warnings.html` modal-sheet treatment supersedes the `NEW_UI_BACKEND_REPLAN.md` Phase-4 'exactly one inline Record-screen banner' decision; the precedence VM is retained; hard-block Start-gating is unchanged." Edit the `NEW_UI_BACKEND_REPLAN.md` Phase-4 section + `docs/WarningCenterContract.md` to point at ADR-0007. **Owner sign-off required in spec review.**

---

## 3. Deletions / dormancy

**Deleted files:** `ui/screens/RecordIdleDock.kt`; `ui/components/PlanSummary.kt`; `ui/components/TappablePlanCell.kt`. Their unit tests (if any) delete with them.

**Deleted within `RecordScreen.kt`:** the navy-dock idle branch; the `ModalNavigationDrawer` "Quick settings" wrapper (A1); the 3-step tutorial overlay + the Info button + `showTutorial`/`tutorialStep` (A2); the "Save preset" `AlertDialog` + `showSavePresetDialog`/`presetNameInput`; the app-bar `Row` (replaced by the floating top overlay + cam-controls); the big-red "Stop recording" `Button` (Stop → FAB).

**Dormant (kept on disk, unsurfaced):** `RovaPreset`; `RovaSettings.customPresetsJson`; `RecordViewModel.{savePreset, deletePreset, loadPresetsFromSettings, persistPresets, customPresets}` (D2).

**Heavily modified:** `ui/MainScreen.kt`, `ui/screens/RecordScreen.kt`, `ui/warnings/WarningCenter.kt`, `ui/screens/HistoryScreen.kt`, `ui/screens/SettingsScreen.kt` — per §2.

**New files:** `ui/screens/RecordChrome.kt` *(or `RecordTopOverlay.kt` / `RecordCameraControls.kt` / `RecordSettingsCard.kt` / `RecordBottomNav.kt`)*; `ui/screens/SessionSettingsSheet.kt`; (optionally) `ui/warnings/WarningSheet.kt`; `ui/screens/RecordRecoveryChip.kt` (or restyle in `RecoveryEchoBanner.kt`); `docs/adr/0007-record-warning-sheets.md`; this spec + the plan.

---

## 4. Testing & gates

- **No new Robolectric / Compose-UI tests.** Compose layout isn't unit-testable in this project; the visual result is owner-verified on device (the Phase-3.5 / Start-gate `DEFERRED-to-owner` pattern). The Start-gate has no UI test by design — that holds.
- **Existing unit tests stay green:** `WarningPrecedenceTest`, `WarningCenterAggregateTest`, `WarningIdOrderTest`, `CameraPermissionSignalTest`, `MicrophonePermissionSignalTest`, `StorageSignalTest`, `RecordHudStateTest`, etc. — precedence/VM/signal/HUD-state logic is untouched.
- **Tests deleted with deleted code:** any `PlanSummaryTest` / `TappablePlanCellTest` / `RecordIdleDock` cell-helper tests. Expect the unit-test count to dip by that many.
- **New pure-helper tests:** `recordFabState` (truth table over `hudState × sessionLocked × hardBlockActive`); `warningSurfaceFor` + `warningSheetContent` (one assertion per `WarningId`); the ported settings-card cell formatters (if not just `UiCopy`).
- **Gates (unfiltered):** `:app:assembleDebug` BUILD SUCCESSFUL, no "Failed to resolve" · `:app:testDebugUnitTest` — report the count (≈ today's 729 − deleted-component-tests + new-helper-tests) · `:app:lintDebug` — report the exact count + every new finding (baseline **53** = 50 W + 3 H + 0 E; deletions may clear some `UseKtx`/`AutoboxingStateCreation`-class findings, new composables may add some — report). No `lint-baseline.xml`.
- **On-device smoke (owner-run, DEFERRED-to-owner):** idle layout (viewfinder full-bleed; status pill; flash cycles OFF→ON→AUTO; flip; settings card → combined sheet → each per-row picker → value persists; recovery chip after an interrupted session); nav (Library/Settings push; back arrow returns; Record system-back exits); FAB Start (incl. the permission-prompt path); recording (FAB → Stop; Stop works; Library/Settings dim); merging (FAB disabled); warnings (revoke camera → hard-block sheet auto-shows + FAB/nav dim + "Not now" → chip → tap chip re-opens + primary CTA → system page; revoke mic → amber sheet + "Continue without audio" → video-only records; notifications off → blue sheet); edge-to-edge/insets at font scale 100/130/150/200, light + dark; predictive back from the combined sheet / a warning sheet / Library / Settings.

---

## 5. Risks / open points

| Risk | Mitigation |
|---|---|
| `RecordScreen.kt` is large and densely commented (recovery echo, anchor timers, warm-up overlay, merge-grace `LaunchedEffect`, lifecycle observers, the `onStart`/`StartResult.Blocked` paths) — easy to regress. | Keep every piece of that logic verbatim where it survives; the change is layout + chrome, not behaviour. Per-task commits keep diffs reviewable. R1 = chrome only for active states (D6) limits the active-path churn. |
| `MainScreen` nav restructure touches History & Settings chrome. | R1 only adds a back arrow + `onBack` to those screens; their content is out of scope. `docs/UI_NAV_GRAPH.md` updated alongside. |
| Auto-presenting a modal hard-block sheet on every screen entry while camera-denied could feel heavy. | "Not now" collapses it to a chip; it only re-presents on a fresh screen entry, not on every recomposition. Final feel = owner smoke. |
| The mockup's "Save" CTA vs. our auto-persist model. | Relabelled "Done"; documented (D3). If the owner wants a true draft/Save model, that's a follow-up — not R1. |
| `WarningSheet` competing with the other sheets (combined settings sheet, the per-row edit sheets, the camera-disconnect `AlertDialog`, the merge-complete card). | Hard-block warning sheets take precedence (you can't be editing settings while camera is denied — Start is blocked anyway, but the screen is usable); the per-row edit sheets and the combined sheet are user-initiated and won't be open under a hard-block in practice; spell out the z-order in the plan. |
| Mid-recording thermal/storage top-banner (A6) — if not done in R1, those warnings have no surface while recording until R2. | Acceptable for R1 (R2 is the active-state slice); flagged. If cheap, add the one render path in R1. |
| ADR-0007 / replan amendment needs owner sign-off. | Part of the spec review gate, before implementation starts. |

---

## 6. Out of scope for R1

R2 (active-state restyle), App Settings / Library / Player / Onboarding re-skins (separate replan slices), Portrait/Landscape Mode (Phase 6), any `service/` / `data/SessionManifest` / recovery / merger change, presets resurrection, a true draft/Save settings model, sub-minute waits, the camera-disconnect timer bug (parked, its own RCA).
