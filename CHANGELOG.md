# Changelog

All notable changes to Rova are documented here. This project adheres to
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/).

Commit-level detail lives in `git log` and on GitHub PRs
(https://github.com/aritr-codes/rova-android/pulls).
ADR-level invariants live in `docs/adr/`. Roadmaps live in `ROADMAP_v6.md`
(reliability) and `NEW_UI_BACKEND_REPLAN.md` (UI / feature).

## [Unreleased]

### Added
- Accessibility audit (WCAG 2.2 AA) — static, source-level audit of all 12 UI surfaces in `docs/accessibility/` (full report + prioritized remediation backlog). 161 findings (3 Blocker, ~58 Serious). Docs-only; no Kotlin changed.
- ADR-0020 (Proposed) — "WCAG 2.2 AA by default" standing requirement for all new/changed UI, with a design stub for a future `checkA11y*` static-gate suite.
- `ContrastMath` — pure WCAG 2.1 relative-luminance / contrast-ratio / alpha-composite helper (`ui/theme/`), with unit tests. Seed for the future `checkA11yNoLowAlphaTextToken` gate.

### Fixed
- Accessibility contrast **Blockers** (WCAG 2.2 AA SC 1.4.3, ADR-0020): WarningSheetV3 body text (0.45→0.55α), RecoveryCard progress header (0.36→0.70α), and notification small-text (body/chrono/tail/count colors + count-pill size 8→10sp) raised to meet the 4.5:1 bar. Verified by `ContrastMathTest` against the audit-measured elevated backgrounds.
- Accessibility **text-token contrast** (WCAG 2.2 AA SC 1.4.3, ADR-0020): 8 `RecordChromeTokens` text tokens (loop-unit/status-time/cell-key/settings-arrow/swipe-hint/nav-icon/nav-text/zone-tag, 18–35%→50–70α) and 3 `SettingsSheetTokens` (section-label/idle-tab/chip-off, →55α) raised to meet 4.5:1; disabled-tab text bumped 0.16→0.40α for legibility. These override the mockup's `rgba` alphas (documented in-token). Guarded by `TokenContrastTest`.
- Accessibility **reduced-motion gating** (WCAG 2.2 AA SC 2.3.3 / 2.2.2, ADR-0020): new `ReducedMotion` seam + `rememberReduceMotion()` honors the OS "remove animations" toggle (`Settings.Global` animation scales = 0). All infinite pulse animations now hold static — recording dot, recovery severity tag, warning snooze chip, and `RovaAnimations.pulsingOpacity`/`pulsingBorder` (background-recording banner). Guarded by `ReducedMotionTest`.
- Accessibility **semantic roles & labels** (WCAG 2.2 AA SC 4.1.2 / 1.3.1, ADR-0020): custom clickable Rows/Surfaces now expose `role = Role.Button` (+ action labels) — recovery CTAs, history warning card, library row, record bottom-nav items, warning snooze chip, warning-sheet tertiary link. RecoveryCard progress strip gets a spoken count via `recoveryProgressContentDescription` (guarded by `RecoveryProgressA11yTest`). Touch-target findings reclassified: all ≥24dp, so they pass SC 2.5.8 AA (M3-48dp polish only, deferred).
- Accessibility **merge-failure error display** (WCAG 2.2 AA SC 3.3.1 / 4.1.3, ADR-0020, RECOV-14): the recovery card tracked the last merge-failure reason (it already flipped the CTA to "Retry merge") but never showed it. The reason now renders in the error colour above the CTAs and is announced assertively, so the user understands why a retry is offered.
- Accessibility **semantic grouping & headings** (WCAG 2.2 AA SC 1.3.1 / 4.1.2, ADR-0020): settings section labels (`SettingsSection`, REC-19/SET-01) and the "Permissions & status" header (SET-14) are now `heading()` nodes so TalkBack heading-navigation can jump between groups. Permission chips and the record recovery chip became single button nodes with one merged content description (severity badge + title folded together) instead of stray fragments.
- Accessibility **status live regions** (WCAG 2.2 AA SC 4.1.3, ADR-0020): the active record HUD (REC-22), the Start/Stop FAB (NAV-07), the recovery merge-progress strip (RECOV-17), the merge-progress sheet (SHAR-07), and `SessionStatusCard` (SHAR-09) now publish polite live regions so TalkBack announces state transitions. Announcement strings deliberately omit per-second countdowns / fractional percent — they change only on meaningful boundaries (state, loop roll, merge-segment roll) so the reader does not chant. New pure helpers `hudActiveAnnouncement` and `RecordHudFormatters.formatSessionStatusAnnouncement` are unit-tested.
- Accessibility **shared-control names** (WCAG 2.2 AA SC 4.1.2 / 1.3.1 / 4.1.3, ADR-0020): settings toggle rows (`SettingsRow`) and the shared `SwitchRow` are now single `toggleable` nodes (`role = Role.Switch`) — the row label names the control and on/off state is announced once, replacing the unnamed bare `Switch` + redundant double-focus. The custom-duration dialog's text field gained a `label` (was an unnamed edit box) and its validation error is now a polite live region. Decorative leading glyphs keep `contentDescription = null` (title is adjacent); the settings chevron was already `null` (SET-06 already-compliant).
- Accessibility **remaining contrast** (WCAG 2.2 AA SC 1.4.3 / 1.4.11, ADR-0020): warning-sheet primary CTAs switched to dark text (white-on-accent was 1.67–3.76:1 → ≥4.5:1 on every severity fill); player subtitle (0.45→0.72α) + play-button fill (0.12→0.25α); onboarding skip/not-now (drop 0.45α dimming) + body copy (0.65→0.80α on `onSurface`); RecoveryCard body (0.45→0.65α); merge-complete subtitle (0.78→0.87α); camera focus brackets (0.20→0.50α) and severity-chip fill (0.13→0.20α) for non-text 3:1. Guarded by `OverlayContrastTest`. Disabled-state (PLR-03, RECOV-07, SHAR-02) are SC 1.4.3-exempt; decorative dividers (REC-25, SET-12) are 1.4.11-exempt — both noted in the backlog, not changed.

## [0.9.0] — 2026-05-28

### Added
- DualShot P+L simultaneous-recording mode (entire `service/dualrecord/` subsystem; ADRs 0008, 0009, and the 0010 sibling pair canonical-uv-frame + crop-divergence).
- In-app onboarding — 3 immersive screens replacing the previous lazy first-record prompt (PR #53).
- Semantic + branded notification system — 4 notification states, clip-dots row, Chronometer live countdown, Inter font, custom branded launcher icon replacing the stock Android Studio Android-bot (PR #54).
- Thermal auto-stop system — `PowerManager.OnThermalStatusChangedListener` with asymmetric hysteresis per ADR-0019 (PR #52).
- Storage-full auto-stop with reason-agnostic echo banner (ADR-0015, PR #46).
- Recovery merge architecture with retry + classifier preflight (ADRs 0017, 0018, PRs #48, #51).
- WarningCenter v3 re-skin — snooze persistence + 18 warning states across 5 categories (ADRs 0013, 0014, 0016; PRs #43, #44, #45, #46, #47).
- Edge-to-edge immersive record-home (ADRs 0011, 0012; PRs #41, #42).
- Record-home Mode picker — Portrait / Landscape / P+L (PR #20).
- Record-screen pixel-faithful re-skin with Inter typography system + `RecordChromeTokens` mockup-exact pixel constants (PRs #36, #37).
- Camera-guides framing — grid + focus brackets, all modes, toggle (PR #39).
- Phase 4.2 warning routing — route warnings to History + Settings (PR #49).

### Changed
- Build toolchain — AGP 9.2.1 / Gradle 9.4.1 / Kotlin 2.2.10 / `compileSdk` + `targetSdk` 37 (PRs #14, #15, #16).
- UI redesigns — record-home (R1 + R2, PRs #17, #18, #19), settings sheet (PR #37), library / history surface (`4b32b0c` + chain).
- Warning surface — inline strip → modal sheet + collapse-to-chip (ADR-0007; supersedes pre-PR-#12 `BatteryOptimizationBanner`).
- Launcher icon — replaced stock Android Studio template with branded recording-dot motif (M5 follow-on).

### Fixed
- DualShot PORTRAIT-stretch architectural fix (ADR-0009 4:3 source, PR #25).
- 10+ DualShot stability fixes — render threading (#32), FBO-ring (#33), fence-sync (#35), EglRouter ANR (#30), aspect-swap (#28), encoder/audio teardown (#28), P+L surface replay on service reconnect (#29), DualCameraSizeResolver contract (#26).
- Warning dismiss persistence — stop re-prompting same warning (PR #31).
- Merge reliability — retry + preflight + notification + library rows (PR #51).

### Removed
- `BatteryOptimizationBanner.kt` (replaced by WarningCenter row `BATTERY_OPTIMIZATION_ON`, PR #12 era).

## [0.5.0] — (untagged, code-version only — `53d246b chore(repo): bump readme to 0.5.0 + extend gitignore (#2)`)

### Added
- Phase 2.5 in-app video player — manifest-driven Media3 surface, segmented clip timeline, ±10s seek, auto-pause on background (PR #1).
- Phase 2.6 onboarding flow (PR #4).
- 6 leaf signals — notifications-permission read (#5), thermal status read (#6), power read (#7), camera-state (#10), exact-alarm revocation (#9).
- FGS notification copy split into 4 states (PR #8).
- HUD merging end-states (`32c5cb3`).
- Library empty-state re-skin (`3499e3d`).
- Library recording-settings popup (`e4a5847`).
- App-settings re-skin (`ed2d4eb`).
- Phase 2 design tokens (`5d23d18`).
- UI-pending settings keys (`e5bb225`).
- WarningCenter Phase 4.1 + 4.1b — 17 warning states with single-flat precedence ordering, Start-gating, snooze (ADR-0007; PRs #12, #13).

### Changed
- Recovery — hide finalized sessions from recovery cards (`2b97f8c`); resume after inter-clip wait fix (`00facb7`).
- Bottom nav locked during merge (`014d985`).
- Recording-duration persistence fix (`aa06028`).

### Fixed
- Post-2.5 player hardening (PR #3, `35558a5`).

## [0.4.0] — 2026-03-07

From tag `v0.4.0` message: "v0.4.0 — Library thumbnails, battery optimization, project cleanup"

### Added
- Real video thumbnails in History library.
- Battery optimization prompt + Doze-mode detection.

### Changed
- Project cleanup pass.

## [0.3.0] — 2026-03-07

From tag `v0.3.0` message: "v0.3.0 — Architecture refactor + reliability hardening. First clean commit. Core periodic recording loop is end-to-end functional. RecordViewModel established, LoomAppLegacy removed, 10+ reliability fixes applied across the service and merge pipeline."

### Added
- `RecordViewModel`.
- End-to-end periodic recording loop.

### Changed
- Architecture refactor.
- Reliability hardening across service + merge pipeline.

### Removed
- `LoomAppLegacy`.
