# Accessibility Remediation Backlog

Ranked by severity (Blocker > Serious > Moderate > Advisory), tie-broken by reach weight (sum of host-surface reach weights). Shared-component and token-rooted issues are **deduped** into single rows listing all call-sites. Effort is the merged worst-case (S < M < L). Reach is the combined weight of surfaces a row touches.

> **Reclassification (2026-05-30, remediation cycle).** Touch-target rows
> measured against Material 3's 48dp guideline, but **WCAG 2.2 AA SC 2.5.8
> Target Size (Minimum) requires only 24×24 dp**. Every flagged target
> (camControl 30, stepper 27, navIconBox 42, recovery CTA 40, warning CTA 46,
> expander 36, overflow 28, ring 38, checkbox 24) is **≥24dp → already passes
> AA**. Rows 7, 20, 25, 34 (TOK-04, REC-17, REC-18, NAV-08, WARN-04, HIST-03/05/10,
> SHAR-12/14, SET-04/15, ONB-07) are therefore **M3-polish only, not AA
> failures** — deferred from the AA-severity remediation pass. Re-rank them as
> Advisory for AA purposes.

> **Slice-6 exemptions (2026-05-30, contrast-remainder).** Within rows 14/16
> the disabled-state findings — **PLR-03** (player disabled-icon), **RECOV-07**
> (recovery disabled ghost text), **SHAR-02** (shared disabled button) — are
> **SC 1.4.3-exempt** (inactive UI components). Within row 13, **REC-25**
> (dual-preview divider) and **SET-12** (sheet dividers) are **pure-decoration,
> SC 1.4.11-exempt**. None were changed; the remaining row 13/14/16 items
> (REC-23, TOK-03, PLR-01/02, RECOV-05, SHAR-17) plus WARN-02 (row 12) and
> ONB-01/02/03 (row 15) shipped in `a11y/contrast-remainder`.

> **Row-9 notes (2026-05-30, shared-control labels).** **SET-06**
> (settings chevron `contentDescription`) was **already `null`** in source —
> compliant, no change. **SHAR-10** (SwitchRow leading glyph) is a **decorative
> reinforcement of the adjacent title → `CD = null` is correct**; naming it
> would double-speak. SET-02 / SHAR-11 fixed by making the rows `toggleable`
> (role=Switch) with a presentational Switch; SHAR-04 (error live region) and
> SHAR-05 (text-field label) fixed in `CustomDurationDialog`. Shipped in
> `a11y/shared-control-labels`.

> **Row-10 note (2026-05-30, live-regions).** All five (REC-22, NAV-07,
> RECOV-17, SHAR-07, SHAR-09) ship in `a11y/live-regions`. Key decision:
> announcement strings exclude the per-second countdown / fractional percent
> and re-announce only on discrete boundaries (state change, loop roll,
> merge-segment roll), so a polite live region does not chant every frame.
> `formatMergeAnnouncement` (segment-discrete) is reused for the HUD merge
> state; new `hudActiveAnnouncement` + `formatSessionStatusAnnouncement` are
> unit-tested.

> **Row-19 note (2026-05-30, grouping/headings).** REC-19, SET-01, SET-14
> ship in `a11y/semantics-grouping`: section labels + the permissions header
> become `heading()` nodes; the permission chip and record recovery chip are
> single button nodes with one merged CD (badge + title). No new pure helpers.

> **Row-17 note (2026-05-30, error-display).** RECOV-14 ships in
> `a11y/recovery-error-display`: `state.mergeFailedReason` now renders (error
> colour + assertive live region) above the recovery CTAs. RECOV-15 (map
> reason → suggested user action) is Moderate, deferred to a later cycle.

> **Row-18 note (2026-05-30, focus-visible).** New `Modifier.focusHighlight`
> (a composed 2 dp focus ring; ordering contract documented in-source) ships in
> `a11y/focus-visible` and is applied to the **Serious** sites: NAV-03/05
> (record nav items), RECOV-12 (recovery CTAs), SET-03 (settings rows), SET-16
> (permission chips). **SET-10** (SettingsSheet panel controls — Save CTA, mode
> tabs, steppers, quality chips, reset-snoozes row) shipped in the follow-up
> `a11y/focus-visible-sheet`. REC-16 and WARN-09 are **Moderate** → out of this
> Serious-only pass. Focus
> behaviour is not JVM-unit-testable (no instrumented harness); the modifier was
> codex-reviewed for correct focus-modifier ordering.

| Rank | Finding IDs | Severity | Reach | Title | Effort | Suggested PR slice |
|---|---|---|---|---|---|---|
| 1 | WARN-01 | Blocker | 2 | WarningSheetV3 body text 0.45α (~4.09:1) — gating warning surface unreadable | S | a11y/contrast-warnings |
| 2 | RECOV-06, HIST-08 | Blocker | 4 | RecoveryCard "CLIPS RECOVERED" progress header 0.36α (~2.4–3:1) | S | a11y/contrast-recovery |
| 3 | NOTI-01, NOTI-02, NOTI-03, NOTI-04 | Blocker | 3 | Notification small-text (body/tail/chrono/count) below AA at 8–12sp | S | a11y/contrast-notification |
| 4 | TOK-01, REC-01, REC-02, REC-03, REC-04, REC-05, REC-06, REC-07, NAV-01, NAV-02, TOK-05 | Serious | 6 | RecordChromeTokens text/nav alphas (18–35%) below 4.5:1 — fix at token source | M | a11y/contrast-tokens-record |
| 5 | TOK-02, SET-05, SET-09 | Serious | 3 | SettingsSheetTokens + section-label text alphas below 4.5:1 | M | a11y/contrast-tokens-settings |
| 6 | MOT-01, MOT-02, MOT-03, MOT-04, MOT-05, MOT-06, REC-14, RECOV-18, WARN-07, HIST-13 | Serious | 12 | No reduced-motion gating on any pulse animation — add helper + gate all call-sites | M | a11y/reduced-motion-helper |
| 7 | TOK-04, REC-17, NAV-08, WARN-04 | Serious | 6 | Undersized touch targets (camControl 30, stepper 27, navIconBox 42, CTAs/overflow 28–46dp) | M | a11y/touch-targets-tokens |
| 8 | RECOV-02, RECOV-03, RECOV-04, RECOV-16, HIST-01, HIST-04, HIST-09, NAV-06, WARN-05 | Serious | 9 | Custom clickable Rows/Surfaces missing role=Button + contentDescription | M | a11y/semantics-roles |
| 9 | SHAR-04, SHAR-05, SHAR-10, SHAR-11, SET-02, SET-06 | Serious | 6 | Shared controls (Switch/Icon/TextField) + decorative-icon labels missing | S | a11y/semantics-shared-controls |
| 10 | SHAR-07, SHAR-09, RECOV-17, REC-22, NAV-07 | Serious | 9 | Progress/status/FAB-state changes lack liveRegion announcements | M | a11y/live-regions |
| 11 | NOTI-05, NOTI-06, NOTI-07 | Serious | 3 | Notification RemoteViews missing CD + dot-row group label + live region | L | a11y/notification-semantics |
| 12 | WARN-02 | Serious | 2 | White-on-colored warning CTAs 1.67–3.76:1 — switch to dark text | S | a11y/contrast-warnings |
| 13 | REC-23, REC-25, TOK-03, SET-12 | Serious | 6 | Non-text contrast: focus brackets, dual-preview divider, warning fills, sheet dividers | S | a11y/non-text-contrast |
| 14 | PLR-01, PLR-02, PLR-03 | Serious | 2 | PlayerScreen subtitle/play-button/disabled-icon contrast | S | a11y/contrast-player |
| 15 | ONB-01, ONB-02, ONB-03 | Serious | 1 | Onboarding secondary buttons + body copy contrast | S | a11y/contrast-onboarding |
| 16 | RECOV-05, RECOV-07, SHAR-02, SHAR-17 | Serious | 6 | RecoveryCard body/disabled + shared disabled-button/merge-card subtitle contrast | S | a11y/contrast-recovery |
| 17 | RECOV-14 | Serious | 1 | Merge-failure reason never displayed (3.3.1) | M | a11y/recovery-error-display |
| 18 | NAV-03, NAV-05, RECOV-12, SET-03, SET-10, SET-16, REC-16, WARN-09 | Serious | 6 | No visible keyboard/D-pad focus indicators on interactive elements | L | a11y/focus-visible |
| 19 | REC-19, SET-01, SET-14 | Serious | 6 | Compound chips/sections lack semantic grouping/heading | S | a11y/semantics-grouping |
| 20 | HIST-05 | Serious | 3 | RecoveryCard CTAs at 40dp (raise to 48dp) | S | a11y/touch-targets-recovery |
| 21 | PLR-04, PLR-06 | Moderate | 2 | SegmentedTimeline missing progressbar role + per-cell labels | M | a11y/player-timeline-semantics |
| 22 | PLR-05, HIST-12, SET-17 | Moderate | 5 | Snackbar/clip-transition/permission-sheet announcements missing | M | a11y/live-regions-misc |
| 23 | REC-15, RECOV-09, RECOV-10, NAV-04, SHAR-08, SHAR-16, ONB-06, WARN-08 | Moderate | 6 | Focus order / focus restore / focusable() gaps across screens & dialogs | M | a11y/focus-order |
| 24 | REC-24, RECOV-08 | Moderate | 3 | Camera grid + recovery card decorative borders below non-text contrast (or hide) | S | a11y/non-text-contrast |
| 25 | HIST-03, HIST-10, SHAR-12, SHAR-14, SET-04, SET-15, ONB-07 | Moderate | 6 | Borderline/unverified touch targets (warning Row, checkbox, chips, permission chip) | S | a11y/touch-targets-misc |
| 26 | REC-08, REC-09, REC-10, REC-11, REC-12, REC-13, REC-20, RECOV-11, RECOV-22, ONB-04, SHAR-01, SHAR-03, SET-07, SET-13 | Moderate | 6 | Remaining contentDescription / label-pairing / decorative-marking gaps | M | a11y/semantics-misc |
| 27 | HIST-15, HIST-16, RECOV-19, WARN-12 | Moderate | 6 | Reflow / resize-text robustness (maxLines, font scaling, 320dp, 200% zoom) | M | a11y/reflow-resize |
| 28 | HIST-14, RECOV-20, WARN-11 | Moderate | 6 | Orientation / wide-screen max-width adaptation | M | a11y/orientation |
| 29 | HIST-06, HIST-11, SHAR-06, RECOV-15, REC-21, REC-26 | Moderate | 6 | Error/empty-state identification + dropdown/menu labels + disabled-reason text | M | a11y/error-states |
| 30 | NOTI-08 | Moderate | 3 | Notification action contentDescriptionRes unused — use descriptive labels | S | a11y/notification-semantics |
| 31 | MOT-07 | Moderate | 2 | Animated indicators lack semantics/liveRegion (static-vs-pulsing announce) | M | a11y/reduced-motion-helper |
| 32 | HIST-02, HIST-17 | Moderate | 3 | History warning-card overlapping focus + unverified focus visibility | M | a11y/focus-order |
| 33 | SET-08, SET-11 | Moderate | 3 | Settings heading() roles + sheet slide reduced-motion | M | a11y/semantics-grouping |
| 34 | REC-18 | Moderate | 3 | Camera control glyph affordance (cosmetic, in 48dp box) | S | a11y/touch-targets-misc |
| 35 | NAV-09, NAV-10, REC-27, WARN-10, RECOV-21, RECOV-13, RECOV-01, ONB-08, PLR-07, SHAR-13, SHAR-15, SHAR-18, MOT-08, NOTI-09, NOTI-10 | Advisory | 6 | Advisory polish: disabled-FAB reason, banner pulse gate, why-expander auto-expand, decorative-mark cleanups, KDoc, focus-discoverability, no-op confirmations | S | a11y/advisory-polish |