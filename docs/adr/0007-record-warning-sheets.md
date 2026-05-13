# ADR 0007 — Record-screen warnings: modal sheets, not an inline banner

- **Status:** Accepted (owner sign-off 2026-05-12, Record-home redesign R1 spec review)
- **Date:** 2026-05-12
- **Phase:** Record-home redesign R1 (idle + chrome + warning sheets)
- **Supersedes:** the `NEW_UI_BACKEND_REPLAN.md` Phase-4 decision "the WarningCenter surfaces exactly **one Record-screen banner at a time**" (inline `Surface` strip), and the `WarningBanner` presentation shipped in PR #12 (Phase 4.1) / PR #13 (Phase 4.1b)
- **Does NOT supersede:** the `WarningCenterViewModel` precedence model (the single-highest-priority resolution over the leaf signals) — retained verbatim; the hard-block Start-gate (`cameraPermissionSignal` / `storageSignal` read directly in `RecordScreen` → Start disabled) — retained verbatim
- **Related:** `mockups/new_uiux/07-warnings.html` (definitive UX), `docs/WarningCenterContract.md`, `NEW_UI_BACKEND_REPLAN.md` §"Phase 4", ADR 0006 B18 (audio-mode video-only fallback)

---

## Context

The shipped Record screen surfaces the highest-priority active warning as an **inline `WarningBanner`** — a full-width `Surface` strip wedged between the app-bar row and the rest of the screen content (Phase 4.1 / 4.1b). On the *interim* idle layout (the navy "Session plan" dock) that banner is occluded by the dock (a `Box`-overlay sibling collision). More fundamentally, the inline-banner model was a deliberate engineering simplification in the `NEW_UI_BACKEND_REPLAN.md` Phase-4 spec that **diverges from the definitive UX** in `mockups/new_uiux/07-warnings.html`, which renders permission / alarm / storage / battery / power warnings as **modal bottom sheets** over a dimmed viewfinder (with severity-tiered styling and primary + secondary actions), and mid-recording thermal / storage warnings as a **top banner** over the active viewfinder.

The Record-home redesign (R1) converges the Record screen onto `01-record-home.html` (camera-first minimal overlay). There is no inline banner slot in that layout, and the occlusion bug is moot once the navy dock is gone. R1 is the point at which the warning surface is brought onto `07-warnings.html`.

## Decision

1. **Replace `WarningBanner` with `WarningSheet`** — a bottom sheet styled per the warning's tier, per `07-warnings.html`:
   - **Hard block** (`CAMERA_PERMISSION_DENIED`, `EXACT_ALARM_DENIED`, `STORAGE_INSUFFICIENT`): red-accented sheet (handle, tinted icon, title, body, **primary** CTA → `launchActionTarget`, **secondary** "Not now"). Auto-presents when the Record screen is shown with a hard-block active. The Start FAB goes `Disabled` **only for the gating hard-blocks** (`CAMERA_PERMISSION_DENIED` / `STORAGE_INSUFFICIENT`) — `EXACT_ALARM_DENIED` surfaces the same red sheet but does **not** gate Start (the Phase-4.1 decision: exact-alarm denial is advisory, not a hard stop); the Library/Settings nav items stay reachable. "Not now" collapses the sheet to a small glass chip under the status pill (tap → re-opens). This is the *presentation* of the hard-block; the *gating* is still the leaf-signal read in `RecordScreen` (camera-permission / storage), unchanged.
   - **Soft** (`MICROPHONE_DENIED`): amber sheet; primary "Grant microphone access", secondary **"Continue without audio"** (dismisses to a chip; Start stays enabled; the session locks to video-only per ADR 0006 B18).
   - **Advisory** (`NOTIFICATIONS_DENIED`, `BATTERY_OPTIMIZATION_ON`, `POWER_SAVE_MODE`): blue/neutral sheet; primary "Turn on … / Disable …", secondary **"Not now"** (dismisses to a chip); does not block Start.
   - **Critical / mid-recording** (`THERMAL_*`, `BATTERY_CRITICAL`, `BATTERY_LOW`, `CAMERA_IN_USE`, `CAMERA_DISABLED`): a **top banner** over the active viewfinder (these fire while recording). R1 wires the idle-reachable warnings as sheets; the mid-recording top-banner render path lands with R2 (the active-state restyle) unless trivially cheap to add in R1.
2. **Keep `WarningCenterViewModel` and `WarningPrecedence.resolve(...)` exactly as-is** — they still emit the single highest-priority active `WarningId` from a `combine(...)` over the nine leaf signals. Only the *rendering* of that `WarningId` changes (banner → sheet/chip/top-banner per `warningSurfaceFor(id)`).
3. **Keep the hard-block Start-gate exactly as-is** — `RecordScreen` reads `cameraPermissionSignal` / `storageSignal` directly; that boolean now also feeds `recordFabState(...)` (the FAB is `Disabled` under a hard-block). The gate is independent of the sheet (a higher-priority *non-gating* warning never opens the FAB).
4. **Recovery cards remain a Library surface** (per the replan); the Record idle screen keeps only a small low-key **recovery chip** ("N recording(s) interrupted — Review" → History).
5. **Amend the docs:** `NEW_UI_BACKEND_REPLAN.md` §"Phase 4" gains a pointer to this ADR (the "one inline banner" rule is superseded); `docs/WarningCenterContract.md` gains the same pointer (its §C3.1 inline-banner description is superseded by the sheet model).

## Consequences

- **Positive:** the Record screen matches the definitive UX; the occlusion failure mode is structurally impossible (a sheet floats); severity is legible (red / amber / blue, modal vs. dismissable); hard-block warnings carry their fix CTA directly; no new state machine — the precedence VM and the Start-gate are untouched, so the existing `WarningPrecedenceTest` / `WarningCenterAggregateTest` / `*SignalTest` suites stay green.
- **Negative / cost:** new `WarningSheet` composable + per-tier styling + the auto-present / collapse-to-chip behaviour; a hard-block sheet auto-presenting on every screen entry while camera-denied is heavier than a passive banner (mitigated by the collapse-to-chip and only re-presenting on a fresh entry); the mid-recording top-banner variant for thermal/storage is deferred to R2, so those warnings have no on-screen surface while recording in the R1→R2 window (acceptable — R2 is the active-state slice).
- **Testing:** new pure-helper tests for `warningSurfaceFor(WarningId)` and `warningSheetContent(WarningId)` (one assertion per `WarningId`). No new Compose-UI tests; the sheet behaviour is owner-verified on device.

## Status / sign-off

Accepted — owner sign-off recorded 2026-05-12 in the R1 spec review (`docs/superpowers/specs/2026-05-12-record-home-redesign-r1-design.md`). The `NEW_UI_BACKEND_REPLAN.md` §"Phase 4" and `docs/WarningCenterContract.md` pointer-amendments are R1 implementation tasks (see the R1 plan).

---

## Amendment 2026-05-13 — R2: mid-rec top-banner live, +1 row (STORAGE_LOW_MID_REC)

R2 (`docs/superpowers/specs/2026-05-13-record-home-redesign-r2-design.md`) lands the deferred top-banner render path from §1 last sub-bullet. Concretely:

1. **Mid-rec top-banner is live.** `WarningCenter(hudState: RecordHudState)` now renders `WarningTopBanner` when the active warning's surface is `TopBanner` AND `hudState != Idle`. At idle, TopBanner-mapped warnings continue to render nothing (the deliberate "no idle surface for mid-rec ids" choice).
2. **+1 row: `STORAGE_LOW_MID_REC`** (ADVISORY-tier, `gatesStart = false`). Placed at ordinal 10 in `WarningId.kt` — between `BATTERY_LOW` (#10 → 1-indexed) and `THERMAL_SEVERE` (was #11, now #12). Rationale: it's more urgent than the configuration-style advisories (mic / notif / battery-opt / power-save) because storage exhaustion will autonomously halt the session via the per-segment storage gate in `RovaRecordingService`, but it ranks below `BATTERY_LOW` because a 14 %-battery session has less remaining runtime than a session with 3 clips of storage headroom in the common case.
3. **`StorageLowMidRecSignal`** is a new UI-side leaf signal on `RovaApp`, polled by RecordScreen every ~30 s while HUD state ∈ {Recording, Waiting, Merging}. Fires when `freeBytes < 3 × bytesPerSecondForResolution(resolution) × durationSeconds`. No `service/**` diff.
4. **`WarningPrecedence.resolve(...)`** gains `storageLowMidRec: Boolean = false` (last param). **`WarningCenterViewModel.aggregate(...)`** gains a 10th source flow. **`WarningCenter` composable signature** gains `hudState: RecordHudState` and `onStopRecording: () -> Unit`. **`WarningCenterContract.md`** is the 17-row contract document; the Phase-1.D revision block's 16-row table is updated.
5. **Retired:** the five legacy active-state composables (`SessionTimer`, `ClipProgressBand`, `WaitingCountdown`, `MergingProgressBand`, `RecordStatusStrip`) are deleted and replaced by `RecordActiveHud` + `LoopPill` + `StatusPill` in `RecordChrome.kt`.
6. **Out of scope / deferred:** the snooze / dismiss model + hysteresis on the top banner (future 4.1c bundle); service-side cancel for in-progress merges; the dynamic "~N min remaining" estimate in the storage-low banner sub-copy.
7. **Invariants preserved:** the 16 existing rows of `WarningId` keep their original ordinal positions; their tier values and `gatesStart` flags are unchanged. The Start-gate in `RecordScreen` (`startBlocked = !cameraPermissionGranted || storageInsufficient`) is byte-identical.

Owner sign-off recorded 2026-05-13 in the R2 spec review.
