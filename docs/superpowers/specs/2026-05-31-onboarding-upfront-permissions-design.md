# Up-front Onboarding Permissions — Design Spec

**Date:** 2026-05-31
**Status:** Approved (brainstorming) — pending implementation plan
**Track:** Smoke-test remediation, slice 1 of the permission/camera findings (fixes #2/#4 friction; #3 idle-preview and #5 camera-reinit are separate slices)

## Goal

Request the three **runtime-dialog** permissions (Camera, Microphone, Notifications) **up-front during onboarding**, each behind its own rationale card, restoring and adapting the pre-M4 `PermissionSlide`. This reverses the M4 "camera-only, defer the rest to JIT" reduction, which caused users to meet scattered permission asks at unpredictable moments and — once a permission was permanently denied — be routed to App Settings via the WarningCenter sheet.

## Background

The 2026-05-31 on-device smoke test (Samsung A17 5G, Android 14 / API 34) surfaced that camera/mic asks felt like they routed the user to Android **App Settings** three separate times. Root cause analysis (from screenshots + code trace):

- The "App Settings" screens were Rova's **WarningCenter** "permission required" sheets, shown because those permissions were **permanently denied** (carryover from a prior install). Android only allows the Settings path once a permission is permanently denied — the sheet is correct.
- The one permission that had never been asked (Notifications) showed a **normal system dialog** — proving the app's dialog path works.
- The remaining friction is structural: M4 (commit `12c12a9`) cut onboarding from 7 screens (3 walkthrough + 4 permission) to 3 (2 walkthrough + 1 camera), deferring Mic / Notifications / Exact-Alarm to just-in-time prompts on the Record screen. So a fresh user meets permission asks at three different moments instead of once.

The M4 reduction was **product-driven (reduce friction)**, not architecture-driven. No ADR or `check*` static-gate constrains the onboarding flow. `NEW_UI_BACKEND_REPLAN §Phase 2 row 2.6` mandates the permission **order** (camera → mic → notifications → exact-alarm) as a hard NO-GO if reordered; this design preserves that order and simply drops the exact-alarm step.

The pre-M4 implementation (`PermissionSlide` + `permissionCopy()` + per-permission launchers) exists intact at commit `21842f9` and is the restoration source.

## Decisions (from brainstorming)

| # | Decision | Rationale |
|--|--|--|
| Card structure | **Per-permission rationale card** | Android & NN/g: explain in context before each request. Android shows camera/mic as *separate* OS dialogs regardless, so "combining" only removes per-permission rationale. One decision per screen lowers cognitive load (WCAG predictable-input). |
| Exact-alarm | **Stays JIT** (the one departure from "all up-front") | `SCHEDULE_EXACT_ALARM` has **no in-app dialog** — it is a full-screen jump to Settings → Alarms & Reminders. Front-loading that before the user has seen the app contradicts NN/g onboarding guidance and Google's "special-access permissions in context" guidance. It is only needed at session start; WarningCenter already surfaces `EXACT_ALARM` there. |
| Skip / blocking | **No permission traps onboarding** | Every card has Allow + "Skip for now"; skipping advances. Camera remains enforced only at the existing WarningCenter **Start-gate** (`CAMERA_PERMISSION_DENIED.gatesStart`), not by trapping the user. Android/NN/g discourage permission walls; graceful degradation (mic → video-only; notifications → nagged at record time). |
| Scope | **Onboarding permissions only** | #3 (black idle preview) and #5 (camera reinit on nav) are separate slices with their own specs. |
| Auto-skip already-granted cards | **No (v1)** | Onboarding shows once (`onboardingCompleted`); on a true first run nothing is granted. Deterministic flow is simpler; revisit only if re-onboarding becomes a real path. |

## Resulting flow

```
WALKTHROUGH_1 → WALKTHROUGH_2 → PERM_CAMERA → PERM_MIC → [PERM_NOTIFS]
```

- `PERM_NOTIFS` is present **only on API ≥ 33** (Tiramisu). On API 24–32 the flow is 4 steps; `POST_NOTIFICATIONS` does not exist there.
- Dots indicator total and the "Step N of M" eyebrow are derived from the count of **visible permission steps** (3 on API 33+, 2 below), not hardcoded.
- No `PERM_ALARM`, no `WALKTHROUGH_3` (M4 dropped the third walkthrough; not restored).
- Permission order preserved per replan §2.6: camera → mic → notifications.

## Architecture

### Pure seam (the testable core)

The single new pure helper makes SDK-gating and step sequencing unit-testable under `isReturnDefaultValues = true`, following the house pure-helper pattern (`effectiveIdleTopBannerId`, `ThermalHysteresis`):

```kotlin
/**
 * Ordered onboarding steps visible on a device at [sdkInt].
 * PERM_NOTIFS is omitted below API 33 (POST_NOTIFICATIONS did not exist).
 * Order is fixed per NEW_UI_BACKEND_REPLAN §2.6 (camera → mic → notifications).
 */
fun visibleOnboardingSteps(sdkInt: Int): List<OnboardingStep> = buildList {
    add(OnboardingStep.WALKTHROUGH_1)
    add(OnboardingStep.WALKTHROUGH_2)
    add(OnboardingStep.PERM_CAMERA)
    add(OnboardingStep.PERM_MIC)
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add(OnboardingStep.PERM_NOTIFS)
}
```

Why a list, not ordinal + auto-advance: the M4 `HorizontalPager` is built over a page list. If `PERM_NOTIFS` stayed in the enum but auto-advanced on API < 33, the user would see a visible page flash/jump. Driving the pager **and** the ViewModel's `next()/previous()` off `visibleOnboardingSteps(sdkInt)` eliminates the dead page entirely. `Build.VERSION.SDK_INT` is read once at the composable/factory boundary and passed into the pure seam.

### Components

- **`OnboardingStep.kt`** — re-add `PERM_MIC`, `PERM_NOTIFS` to the enum (keep `PERM_CAMERA`; do **not** re-add `PERM_ALARM` or `WALKTHROUGH_3`). `isWalkthrough` boundary stays at `WALKTHROUGH_2`. The enum keeps `next()/previous()` for back-compat, but list-based navigation (below) is the source of truth. Add `visibleOnboardingSteps(sdkInt)` as a top-level function in this file.

- **`OnboardingViewModel.kt`** — operate over an injected `steps: List<OnboardingStep>` (the visible list) instead of raw enum ordinal:
  - `advance()`: index of current in `steps`; if last → `markCompleted()` + `completed = true`; else move to `steps[idx+1]`.
  - `goBack()`: `steps[idx-1]` or no-op at index 0.
  - `skipWalkthroughToFirstPermission()`: jump to the first `isPermission` step in `steps` (rename of `skipWalkthroughToCamera`; still lands on `PERM_CAMERA`).
  - `setStep(step)`: pager swipe-settle write-back; idempotent + completion-guarded (unchanged contract).
  - `complete()`: retained for any early-exit; idempotent.
  - Factory computes `visibleOnboardingSteps(Build.VERSION.SDK_INT)` and injects it; the unit test injects a fixed list + counting `markCompleted` lambda.
  - All transitions remain no-ops after `completed` (existing idempotency contract).

- **`OnboardingSlide.kt`** — restore the pre-M4 generic `PermissionSlide(step, copy, onAllow, onSkip)` and `permissionCopy(step)` from `21842f9`, adapted:
  - Supports `PERM_CAMERA`, `PERM_MIC`, `PERM_NOTIFS` (not `PERM_ALARM`).
  - Eyebrow / severity per card: Camera = "Required", Mic = "Optional", Notifications = "Recommended".
  - "Step N of M" + `DotsIndicator(total = visiblePermStepCount, activeIndex)` derived from the visible permission-step list (passed in), not hardcoded "of 4".
  - Each card: primary `PrimaryCta` ("Allow Camera/Microphone/Notifications") + low-emphasis "Skip for now" `TextButton`. Reuse existing `CalloutCard`, `PrimaryCta`, `DotsIndicator`.
  - Icons: Mic / Notifications via the existing illustration style or `Icons.Filled.Mic` / `Icons.Filled.Notifications`. Keep `CameraRationaleSlide`'s camera glyph or fold it into `PermissionSlide`.
  - **WCAG 2.2 AA (ADR-0020):** every interactive element ≥ 24×24 dp (SC 2.5.8), `semantics` content descriptions / heading roles, AA contrast — reuse the a11y-stack patterns (`focusHighlight`, semantics grouping) already present in the codebase.

- **`OnboardingScreen.kt`** — adapt the M4 pager screen:
  - Build the pager over `visibleOnboardingSteps(...)` (page count = list size); render `WalkthroughSlide` / `PermissionSlide` per step.
  - Restore **three** `ActivityResultContracts.RequestPermission` launchers (camera, mic, notifications). Each `onResult → viewModel.advance()` (grant and deny both advance — denial is observed later by WarningCenter).
  - `onAllow` dispatches by step: `CAMERA → cameraLauncher.launch(CAMERA)`, `PERM_MIC → micLauncher.launch(RECORD_AUDIO)`, `PERM_NOTIFS → notifLauncher.launch(POST_NOTIFICATIONS)`.
  - `onSkip → viewModel.advance()` for every permission card.
  - Keep M4's immersive sticky chrome (`DisposableEffect` hide/show system bars), the two pager↔VM sync `LaunchedEffect`s, and the `BackHandler`.
  - No SDK auto-advance `LaunchedEffect` needed — gating is handled by the visible-steps list.

### Data flow

```
OnboardingScreen launcher (grant/deny)
  → OnboardingViewModel.advance()
  → (last step) markCompleted() → RovaSettings.onboardingCompleted = true
  → OnboardingUiState.completed = true
  → LaunchedEffect(completed) → onCompleted() → MainScreen start-destination = record
  → Record screen: permission Signals reflect the new grants; WarningCenter shows
    only what is still missing (e.g. EXACT_ALARM flat banner, or camera Start-gate if skipped).
```

No new signal wiring: the additional permissions ride the same path the M4 camera-only grant already uses (the Record screen re-reads `CameraPermissionSignal` / `MicrophonePermissionSignal` / `NotificationPermissionSignal` on resume).

## Error / edge handling

- **API 24–32:** no `PERM_NOTIFS` step (handled by `visibleOnboardingSteps`). `RECORD_AUDIO` and `CAMERA` exist on all supported levels.
- **Permanent denial inside onboarding:** not specially handled — on a true first run nothing is permanently denied, and the launcher advancing on deny is acceptable. Post-onboarding, WarningCenter owns the permanent-denial → Settings path (unchanged, correct). Re-onboarding does not occur (`onboardingCompleted` gate).
- **Skip everything:** user reaches Record with no permissions; WarningCenter hard-gates Start on camera and advises on the rest. Existing behavior.
- **Back-gesture / pager swipe:** unchanged M4 contract — back walks steps backward; forward-swipe past the last step is clamped (completion only via tapping the final card's action).

## Testing (JVM-only, per project policy)

1. **`visibleOnboardingSteps(sdkInt)`** — API 33+ returns 5 steps incl. `PERM_NOTIFS`; API 24/32 returns 4 steps without it; order is exactly camera → mic → (notifications).
2. **`OnboardingViewModel`** over an injected list — `advance()` walks the list; `advance()` on the last step calls `markCompleted` exactly once and sets `completed`; `goBack()` clamps at index 0; `skipWalkthroughToFirstPermission()` lands on `PERM_CAMERA`; all transitions no-op after `completed`; `setStep` idempotent + guarded. Use a fixed list + counting `markCompleted` lambda (existing pattern).
3. **Step-indexing helper** — "Step N of M" + dots count derived correctly for the 3-perm (API 33+) and 2-perm (API < 33) cases.
4. Card composables remain UI (Compose-test scaffolding only); no instrumented tests (project policy).

Baseline: a new feature lands its tests in the same PR; full gate (`:app:testDebugUnitTest :app:lintDebug`, all 25 `check*` tasks) must stay green.

## Explicitly out of scope

- #3 idle black preview after grant (separate slice — needs logcat to choose fix).
- #5 camera reinitialize on every nav return (separate slice — touches ADR-0006 service/camera lifecycle).
- Exact-alarm up-front (stays JIT; trivial to add a 4th card later if desired).
- Permanent-denial → Settings routing (works as designed via WarningCenter).
- Auto-skipping already-granted permission cards.

## ADR / gate impact

- **No ADR amendment required** — M4 reduction was product-driven; reversing it changes no behavioral invariant.
- **No `check*` task** touches onboarding.
- **ADR-0020 (WCAG 2.2 AA by default)** applies to the new/changed cards: 24dp+ targets, semantics, AA contrast.
- **`NEW_UI_BACKEND_REPLAN §2.6` permission order** preserved (camera → mic → notifications).
