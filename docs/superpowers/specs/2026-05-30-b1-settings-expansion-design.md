# B1 — Settings Expansion (Recording Defaults + Notifications deep-link)

**Date:** 2026-05-30
**Track:** B (Settings expansion) — slice **B1** of an ordered decomposition.
**Status:** Design — awaiting user review before plan.
**Baseline mockup:** `mockups/new_uiux/06-app-settings.html` (gitignored).
**Standing requirement:** WCAG 2.2 AA by default (ADR-0020).

---

## 1. Why this slice, and why it is small

The owner's Track B is "expand App Settings to the `06-app-settings.html` mockup, with
progressive disclosure / plain language / sensible defaults, subsuming the deferred M4
theme-switcher + i18n + export-sheet polish."

The mockup adds ~15 rows across 7 sections. A pre-design backend audit showed most of
those rows are **independent subsystems** or **no-op placeholders**, not one feature:

| Mockup row | Backend reality | Disposition |
|--|--|--|
| Default resolution / duration / interval / loops | Same persisted prefs the record sheet already edits (EXISTS-SEAM) | **B1 — surface as editable** |
| Notifications (rec / merge / storage) | Recording FGS notification is mandatory; no app-level gate exists | **B1 — single system deep-link row** (honest) |
| Default audio (mic) | Audio always-on; no toggle. Adding one is a recording-pipeline change | Deferred (own cycle) |
| Default camera (front/back) | Front camera unsupported in pipeline | Deferred (own cycle) |
| Auto-copy to gallery (`autoExportEnabled`) | Persisted but **no consumer** — inert toggle | Deferred (wire with export gating) |
| Crash reports & analytics | Backend is `NoopCrashReporter`; Firebase commented out (`RovaApp.kt:308`) — inert toggle | Deferred (wire with Firebase) |
| Low storage warning threshold | Existing 50 MiB constant is *finalize headroom*, a different semantic from "warn below X free" | Deferred (own cycle) |
| Save location (Internal/SD) | Scoped storage + SAF | Deferred (own cycle, B4) |
| Exclude from system gallery | MediaStore `IS_PENDING` / `.nomedia` | Deferred (own cycle, B5) |
| Theme (Dark/Light/System) | Needs a light color scheme built from scratch | Deferred (own cycle, B2) |
| Language | String externalization + per-app locale | Deferred (own cycle, B3) |
| Watermark | Mockup marks "coming soon", disabled | Skipped |

**B1 deliberately ships only rows that do something real.** A toggle that gates nothing
(auto-copy, crash) was rejected for the same no-op reason on every occurrence — the slice
stays honest.

Each deferred row is its own brainstorm → spec → implementation cycle (B2…B6).

---

## 2. Scope of B1

Two new functional additions to `ui/screens/SettingsScreen.kt`. All existing rows keep
their current behavior. **No `RovaSettings` schema change. No service change. No
recording-pipeline change.**

### 2.1 Screen information architecture (top → bottom)

```
Permissions & status        (existing — unchanged)
Recording defaults          (NEW — 4 rows)
Recording behavior          (existing — keep-screen-on, camera guides)
Alerts                      (existing — sound cues, vibrate)
Notifications               (NEW — 1 deep-link row)
Storage                     (existing — auto-delete + chips, export folder, clear cache)
Reliability                 (existing — battery optimization)
About                       (existing — version, privacy policy)
```

### 2.2 Recording defaults section (4 rows)

Each row reads/writes the **same persisted pref** the record `SettingsSheet` already
edits — one source of truth, no new keys, no separate "defaults":

| Row | Pref (`RovaSettings`) | Control | Source of truth reused |
|--|--|--|--|
| Default resolution | `resolution` | option list | `QualityPresets.PICKER_ORDER` + `canonicalizeOrDefault` |
| Clip duration | `durationSeconds` | stepper | `RecordSettingBounds.stepClip/clipAtMin/clipAtMax` + `recordClipValue` |
| Interval between clips | `intervalMinutes` | stepper | `RecordSettingBounds.stepWait/waitAtMin/waitAtMax` + `recordWaitValue` |
| Number of loops | `loopCount` | stepper | `RecordSettingBounds.stepRepeats/repeatsAtMin/repeatsAtMax` + `recordRepeatsStepperValue` |

**Deviation from the approved "C hybrid (resolution+loops list, duration+interval
stepper)":** the record sheet's loop control is already a **stepper** backed by
`RecordSettingBounds.stepRepeats` (Continuous = `loopCount = -1`, formatted by
`recordRepeatsStepperValue`). There is no fixed loop *list* anywhere in the codebase. A
list would re-derive a discrete option set that exists nowhere and reintroduce the cross-
screen divergence the one-source-of-truth choice was meant to prevent. So **loops is a
stepper** here; **resolution stays a list** (it is genuinely enum-bounded). This deviation
is flagged at the review gate — the owner may override and force a fixed list, at the cost
of a B1-local loop-option constant.

#### Presentation
- Each row renders like an existing `SettingsRow`: icon, label, current value (`s-value`
  style), chevron. Tapping opens a bottom sheet.
- **Two new composables** under `ui/screens/` (or `ui/components/`):
  - `SettingsOptionSheet` — single-select list (mockup-faithful: handle, title, option
    rows with trailing check + `selected` styling). Used by resolution.
  - `SettingsStepperSheet` — `−` / value / `+` with min/max clamp. Used by duration,
    interval, loops.
- Both delegate **all numeric/option logic** to the reused seams above; they own only
  presentation. They must not re-implement bounds, steps, Continuous handling, or the
  quality alias map.

### 2.3 Notifications section (1 row)

- Single row "System notification settings" with a chevron, no persisted state. Honest:
  the recording FGS notification is mandatory and per-channel muting is the OS's job.
- Deep-link intent:
  - API ≥ 26: `Settings.ACTION_APP_NOTIFICATION_SETTINGS` with
    `Settings.EXTRA_APP_PACKAGE = context.packageName`.
  - API 24–25 (minSdk 24): fallback `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` with
    `Uri.fromParts("package", packageName, null)`.
- `ActivityNotFoundException` → Toast (same defensive pattern as the existing privacy-
  policy row).

---

## 3. Data flow — two owners, resume-reseed (resolved)

**The problem.** `resolution` / `durationSeconds` / `intervalMinutes` / `loopCount` are
owned in-memory by `RecordViewModel` (RecordViewModel.kt:96-99,147-150) — seeded once at
construction, write-back on change. `RecordViewModel` is **not** hoisted; `RecordScreen`
creates it via `viewModel()` (record-nav-entry scope). `SettingsScreen` receives only the
**shared** `SettingsViewModel` (MainScreen.kt:53,155) and has no handle to
`RecordViewModel`. Under bottom-nav (record entry stays alive), editing these prefs from
app-settings via a parallel `SettingsViewModel` copy leaves `RecordViewModel`'s in-memory
flow stale → record screen shows the old value, and a later stepper nudge writes the stale
value back, **clobbering** the app-settings edit.

**The resolution (owner-approved): resume-reseed both screens.** SharedPreferences
(`RovaSettings`) is the single persistent source of truth; both ViewModels re-read it when
their screen resumes, so they converge.

- `SettingsViewModel` gains four write-through `MutableStateFlow`s — `resolution`,
  `durationSeconds`, `intervalMinutes`, `loopCount` — seeded from `RovaSettings`, each with
  a `viewModelScope.launch { flow.collect { settings.<field> = it } }` collector (identical
  to its existing fields). Plus `fun reloadRecordingDefaults()` that re-reads all four from
  `RovaSettings` into the flows.
- `SettingsScreen` calls `settingsViewModel.reloadRecordingDefaults()` on `ON_RESUME`
  (folded into the existing `DisposableEffect` that already re-reads `batteryExempt` at
  SettingsScreen.kt:131-139) — so app-settings always shows the value the record sheet may
  have changed while away.
- `RecordViewModel` gains a symmetric `fun reloadRecordingDefaults()` re-reading the four
  from `RovaSettings` into its existing `duration`/`interval`/`loopCount`/`resolution`
  flows; `RecordScreen` calls it on `ON_RESUME`. This kills the stale-read and the
  clobber-on-nudge.
- Reseed writes are idempotent: setting `flow.value` to the just-read pref re-fires the
  write-back collector with the identical value — a harmless no-op write.
- The service reads these prefs at session start (unchanged). No schema/version bump, **no
  new persisted key** in B1. Both screens share `RecordSettingBounds` / `QualityPresets` so
  the *step/option logic* cannot diverge; resume-reseed keeps the *in-memory values*
  convergent.

---

## 4. Accessibility (ADR-0020, AA by default)

Reuse the patterns landed in the WCAG remediation (PRs #62–#74):

- Section labels: existing `SettingsSection` already applies `semantics { heading() }`.
- Option-list rows (`SettingsOptionSheet`): `selectable(role = Role.RadioButton)` with a
  merged content description ("Resolution: HD, selected"); the sheet title is a `heading()`;
  `Modifier.focusHighlight(...)` on each option (D-pad focus ring).
- Stepper (`SettingsStepperSheet`): `−`/`+` buttons get explicit content descriptions
  ("Decrease clip duration" / "Increase clip duration"); the value `Text` is a
  `liveRegion = LiveRegionMode.Polite` that announces the **formatted value on change**
  (anti-chant: announces the discrete new value, never a per-tick stream).
- Deep-link row: `role = Role.Button`, `focusHighlight`.
- Contrast: reuse the already-raised tokens. Any new text token is verified ≥ 4.5:1 (or
  ≥ 3:1 non-text) via the existing `ContrastMath` test pattern (`OverlayContrastTest` /
  `TokenContrastTest` style).
- Touch targets ≥ 24 dp (SC 2.5.8 AA) — sheet rows and stepper buttons sized accordingly.

---

## 5. Error handling

- Notifications intent failure → Toast, no crash (mirrors privacy-policy row).
- Stepper clamps at `RecordSettingBounds` min/max — the `−`/`+` buttons disable at the
  bound (`atMin`/`atMax`), so no invalid pref can be written.
- Resolution is a closed set (`QualityPresets`); no free text, no sanitizer needed.
- Off-canonical persisted resolution is folded by `canonicalizeOrDefault` before the list
  marks a selection (defends against legacy/stale values).

---

## 6. Testing (JVM-only, repo policy)

New pure logic is unit-tested; presentation is not (no Robolectric/instrumented per repo
policy):

- A pure label/selection helper if any new formatting is introduced — but the design
  **reuses** `recordClipValue` / `recordWaitValue` / `recordRepeatsStepperValue` and
  `QualityPresets`, all already covered by `RecordSettingBoundsTest` and quality tests, so
  ideally **no new formatter** is needed. If a thin adapter is added, it gets a focused test.
- Stepper clamp behavior is already covered by `RecordSettingBoundsTest`; no duplication.
- Deep-link **decision logic** extracted as a pure function `notificationSettingsTarget(sdkInt): NotifSettingsTarget`
  (an enum: `APP_NOTIFICATION_SETTINGS` for ≥26, `APP_DETAILS` for 24–25) — unit-tested for
  both branches. The thin wrapper that turns the target + package name into an actual
  `android.content.Intent` is **not** unit-tested (android.jar Intent/Settings are no-ops
  under `isReturnDefaultValues = true`); the JVM-testable decision lives in the pure helper,
  per the house pure-helper-extraction pattern.
- Any new contrast token → `ContrastMath` assertion.
- Baseline to preserve: green test suite + `:app:lintDebug` (the 25 `check*` gates). B1
  adds no new invariant and touches no `check*`-guarded source path.

---

## 7. Files (anticipated)

**New**
- `ui/screens/SettingsOptionSheet.kt` — single-select bottom sheet (resolution).
- `ui/screens/SettingsStepperSheet.kt` — `−`/value/`+` bottom sheet (duration/interval/loops).
- `ui/screens/NotificationSettingsIntent.kt` — pure `notificationSettingsTarget(sdkInt)` +
  `NotifSettingsTarget` enum + thin Intent wrapper.
- `test/.../NotificationSettingsIntentTest.kt` — both API branches of the decision helper.

**Modified**
- `ui/screens/SettingsViewModel.kt` — add 4 write-through flows (`resolution`,
  `durationSeconds`, `intervalMinutes`, `loopCount`) + collectors + `reloadRecordingDefaults()`.
- `ui/screens/RecordViewModel.kt` — add `reloadRecordingDefaults()` re-reading the 4 prefs
  into its existing flows.
- `ui/screens/RecordScreen.kt` — call `viewModel.reloadRecordingDefaults()` on `ON_RESUME`.
- `ui/screens/SettingsScreen.kt` — call `settingsViewModel.reloadRecordingDefaults()` on
  `ON_RESUME` (existing `DisposableEffect`); add Recording-defaults section (4 rows + sheet
  hosts) and Notifications section (1 deep-link row). Reuse existing `SettingsRow` /
  `SettingsSection` / `ChevronTrailing`.
- `CHANGELOG.md` — `[Unreleased]` entry.

**Unchanged on purpose:** `RovaSettings.kt` (no new key), service, record `SettingsSheet`
controls, all `check*` tasks.

---

## 8. Out of scope (each its own later cycle)

Mic toggle · default camera · auto-copy/`autoExportEnabled` gating · crash toggle (+Firebase)
· low-storage threshold · save location Internal/SD (B4) · exclude-from-gallery (B5) ·
theme switcher (B2) · language/i18n (B3). Watermark: skipped (mockup "coming soon").
