# Rova — Engineering Backlog

> Single planning artifact enumerating every remaining, deferred, and carried-over task for **Rova** (`com.aritr.rova`).
> **As of 2026-06-13, master at HEAD `24cdae7`** ("PR-ε: fixed-window + counter-rotating record chrome (ADR-0029 §B″) (#108)"), **zero open PRs**.
> This document is a backlog, not a commitment. Every item traces to an ADR, roadmap, accessibility doc, memory file, or a measured code fact. Priority tags: **P1** (next) · **P2** (soon) · **P3** (later) · **Parked**.

---

## Top 3 next candidates

What the memory trail implies is most concrete and ready to pick up:

1. **Gate-count documentation recount sweep** — ✅ DONE 2026-06-13 (Static-Gate / Tech-Debt). CLAUDE.md now corrected; next-most-concrete candidate is **PR-δ FrontBack PiP** (item 2 below). Mechanical, zero-risk, already flagged as a PR-ε follow-up. The true unique `check*` count is **39** (measured), but `CLAUDE.md` says "28" in one place and the PR-ε memory said "36" — docs are stale. *(Source: `memory/project_pr_epsilon_rotation_first_chrome.md` "gate-count docs drift … recount sweep follow-up"; measured below.)*
2. **PR-δ FrontBack PiP** (Capture/Mode). The last unbuilt phase of the owner-ratified mode/orientation re-architecture (ADR-0029, Accepted). α/β/γ/ε are all merged; δ is the only remaining slice and the ADR already specifies its shape. *(Source: ADR-0029; `memory/project_mode_orientation_replan.md`.)*
3. **Preset UI polish** (UI Polish). Owner explicitly called the in-sheet PRESETS section "kinda mess" after device-smoke otherwise passed; not yet specced. *(Source: `memory/project_current_state.md`; `memory/project_pr_epsilon_*` and others repeat the "kinda mess" flag.)*

---

## Capture / Mode

- [ ] **PR-δ FrontBack PiP (2-sensor concurrent capture)** — **P2**
  Build `CaptureTopology.FrontBack`: two concurrent camera bindings (front + back) composed into a single-file picture-in-picture clip via CameraX `CompositionSettings`, hardware-capability-gated. Today `FrontBack` exists only as an enum value referenced by `data/CaptureTopology.kt` / `RovaSettings.kt` / `ui/screens/CaptureModes.kt` and fenced by the `checkFrontBackCapabilityGated` gate — **no concurrent-camera implementation exists** (verified: no FrontBack source under `service/`). PR-δ extends the gate allowlist with the new concurrent-camera module. *(Source: ADR-0029 §"FrontBack" clauses, Accepted; `memory/project_mode_orientation_replan.md` decision C "FrontBack single-file PiP via CompositionSettings".)*

- [ ] **Advanced lock UI (reverse-portrait / 180° / "lock whatever I'm aimed at now")** — **P3**
  Deferred per ADR-0029 decision D ("defer advanced-lock UI, build the 4-rotation model now"). The two clean axes (capture-topology × orientation-policy) were built specifically so future locks are cheap; the UI surface is not yet designed. *(Source: ADR-0029 decision D; `memory/project_mode_orientation_replan.md`.)*

---

## Reliability / Service

- [ ] **`RovaRecordingService.kt` split** — **P2**
  The service file is the largest in the tree and is flagged as a refactor candidate (it is the sole owner of CameraX + MediaMuxer + the segment loop). Decompose into smaller seams without changing behavior; the static-gate suite (`checkStopNoGetService`, `checkUserStoppedBeforeMerge`, `checkCompletedWriteOnlyFromPerformMerge`, etc.) pins the invariants that must survive the split. *(Source: `memory/project_current_state.md` "Deferred / next-option backlog: … `RovaRecordingService.kt` split".)*

- [ ] **Persistent-preview layer for zero camera-black** — **P3** · **VERIFY**
  Noted in memory as a backlog idea: a persistent-preview surface layer so the camera surface never goes black during mode/lens reconfigure or nav transitions. **VERIFY**: this is listed as an aspirational "next-option" in `project_current_state.md`, not as a confirmed open bug with a current repro — earlier black-preview reports were resolved incidentally (`#77`/`#78`) or did not reproduce (scheduled black-preview "didn't repro", deferred). Confirm there is still a reproducible camera-black path before scheduling work. *(Source: `memory/project_current_state.md` "persistent-preview layer for zero camera-black"; `memory/MEMORY.md` bugfix-batch note "Bug 1 scheduled black-preview DEFERRED … didn't repro".)*

---

## Accessibility (ADR-0020)

ADR-0020 ("WCAG 2.2 AA by default") is **Proposed**. All Serious/Blocker findings from the 2026-05-29 audit were remediated and FF-landed on master (`892d551`); the rows below are the **deferred Moderate/Advisory** remainder plus the unbuilt static-gate suite. *(Sources: `docs/adr/0020-wcag-2.2-aa-by-default.md` (Status: Proposed); `docs/accessibility/remediation-backlog.md`; `memory/project_accessibility_wcag_audit.md`.)*

- [ ] **Build the `checkA11y*` static-gate suite** — **P2**
  ADR-0020 sketches a future `checkA11y*` suite (invariant → `check*` → `preBuild`) but it is unbuilt. Only `checkA11yAnimationGated` exists as a true a11y gate (verified; `checkLocaleConfigNoPseudolocale` is locale-config, not a11y proper). The broader suite (semantics presence, live-region presence, reduced-motion gating, touch-target minimums) remains Proposed. *(Source: ADR-0020; CLAUDE.md "checkA11y* suite still Proposed/unbuilt".)*

- [ ] **Touch-target sizing remediation** — **P3**
  Rows 7, 20, 25, 34: camControl 30dp, stepper 27dp, navIconBox 42dp, CTAs/overflow 28–46dp, RecoveryCard CTAs 40dp→48dp, borderline chips/checkboxes/permission chips. Reclassified Advisory-for-AA (≥24dp passes SC 2.5.8) in the 2026-05-30 pass, so deferred. *(Source: `docs/accessibility/remediation-backlog.md` rows 7/20/25/34 + reclassification note.)*

- [ ] **Live-region / status-message announcements (misc)** — **P3**
  Row 22: Snackbar, clip-transition, and permission-sheet announcements missing `liveRegion`. *(Source: `remediation-backlog.md` row 22.)*

- [ ] **Focus order / focus restore / `focusable()` gaps** — **P3**
  Rows 23, 32: focus order, focus restore, and focusable gaps across screens & dialogs; History warning-card overlapping focus + unverified focus visibility. *(Source: `remediation-backlog.md` rows 23/32.)*

- [ ] **Remaining `contentDescription` / label-pairing / decorative-marking gaps** — **P3**
  Row 26 (14 sites) + row 30 (notification action labels). *(Source: `remediation-backlog.md` rows 26/30.)*

- [ ] **Player timeline semantics** — **P3**
  Row 21: SegmentedTimeline missing `progressbar` role + per-cell labels. *(Source: `remediation-backlog.md` row 21.)*

- [ ] **Non-text contrast for decorative borders** — **P3**
  Row 24: camera grid + recovery card decorative borders below non-text contrast (or mark decorative/hide). *(Source: `remediation-backlog.md` row 24.)*

- [ ] **Reflow / resize-text / orientation robustness** — **P3**
  Rows 27, 28: maxLines, font scaling, 320dp, 200% zoom; orientation / wide-screen max-width adaptation. *(Source: `remediation-backlog.md` rows 27/28.)*

- [ ] **Error / empty-state identification + dropdown/menu labels + disabled-reason text** — **P3**
  Row 29. *(Source: `remediation-backlog.md` row 29.)*

- [ ] **Semantics grouping + animated-indicator semantics** — **P3**
  Rows 31, 33: animated indicators lack semantics/liveRegion (static-vs-pulsing announce); Settings `heading()` roles + sheet-slide reduced-motion. *(Source: `remediation-backlog.md` rows 31/33.)*

- [ ] **Advisory polish bundle** — **P3**
  Row 35 (15 sites): disabled-FAB reason, banner pulse gate, why-expander auto-expand, decorative-mark cleanups, KDoc, focus-discoverability, no-op confirmations. *(Source: `remediation-backlog.md` row 35.)*

---

## UI Polish

- [ ] **Preset / custom-modes UI polish** — **P1**
  Owner flagged the in-sheet PRESETS section as "kinda mess" (device-smoke otherwise PASS). A UI-polish slice for the preset chips / "+ Save" / naming dialogs — not yet specced. *(Source: `memory/project_current_state.md`; repeated across `memory/project_pr_epsilon_*` and `MEMORY.md`.)*

- [ ] **PR-ε: warning-sheet visibility hoist out of WarningCenter** — **P2**
  PR-ε v1 deviation: the record-chrome lock currently stays engaged when a warning sheet opens because the warning-sheet visibility flag is not hoisted out of `WarningCenter`. Follow-up: hoist the flag so chrome can unlock for the warning sheet like it does for the tips/settings modals. *(Source: `memory/project_pr_epsilon_rotation_first_chrome.md` "warning-sheet visibility not hoisted → keeps lock when it opens (follow-up: hoist flag out of WarningCenter)".)*

- [ ] **PR-ε: thermal-tips sheet portrait-under-lock conversion** — **P2**
  The thermal tips sheet should render in the upright reading frame while chrome is locked landscape (consistent with the floating settings panel pattern), rather than inheriting locked-landscape chrome. *(Source: PR-ε smoke-watch list / floating-panel pattern, `memory/project_pr_epsilon_rotation_first_chrome.md`.)*

- [ ] **PR-ε: BackHandler disarm during exit animation** — **P3**
  The floating settings panel's `BackHandler` should be disarmed while the panel's exit (close) animation runs, to avoid a second back-press racing the dismissal animation. *(Source: PR-ε floating-panel work, `memory/project_pr_epsilon_rotation_first_chrome.md` — `FloatingSettingsPanel.kt` BackHandler.)*

- [ ] **Liquid Glass landscape re-layout (deferred follow-up)** — **P3**
  The 3rd Liquid Glass Record-home follow-up (landscape re-layout) was deferred to its own PR and folded into the mode/orientation track; revisit after PR-δ. *(Source: `memory/MEMORY.md` liquid-glass entry "3rd follow-up (landscape re-layout) → DEFERRED to own PR, folded into project-mode-orientation-replan".)*

---

## Static-Gate / Tech-Debt

- [x] **Gate-count documentation recount sweep** — ✅ **DONE 2026-06-13**
  **✅ Applied 2026-06-13:** `CLAUDE.md` corrected — count → **39**, inline gate list completed (added the 11 missing names), and "20 ADRs (0001–0020)" → "29 ADRs (0001–0029)" with topics extended. ADR-0020/0022's "25 checks" left **as-is** (point-in-time-at-authoring — "25 before this slice", "the 26th invariant" — rewriting to 39 would corrupt the historical narrative of immutable decision records). Memory (`project_current_state.md`, `MEMORY.md`) updated. Original note retained below for traceability.
  **Measured fact:** there are **39 unique `check*` gate tasks** in `app/build.gradle.kts` — counted as 39 occurrences of `tasks.register("check…` (`grep -oE 'tasks\.register\("check[A-Za-z0-9]+' | sort -u | wc -l` = 39, zero duplicates), and **all 39 are wired into `preBuild`** (39 unique `check*` names appear in `dependsOn(...)`). The docs are stale: `CLAUDE.md` says "**28** custom `check*` tasks" in its Static-check-gate section and "the existing **25** checks" in the ADR-0020 paragraph; the PR-ε memory carried "**36** gates". Update `CLAUDE.md` (both numbers), the full gate enumeration list, and any ADR text that cites a count, to **39**. Full current gate list (alphabetized): checkA11yAnimationGated, checkAtomicTerminalWriteForbiddenPair, checkAudioModeFgsTypeMatch, checkCompletedWriteOnlyFromPerformMerge, checkExportCleanupPredicate, checkExportIsPendingGuarded, checkExportNoCopyToPublicMovies, checkExportPendingVisibilityOnQuery, checkExportPipelineSingleEntry, checkExportQueryArgMatchPendingGuarded, checkExportSetIncludePendingGuarded, checkExportTierReadTolerant, checkExternalRootShared, checkFGSStartGuarded, checkFrontBackCapabilityGated, checkGlassSurfaceRoleUsage, checkLocaleConfigNoPseudolocale, checkNoHardcodedUiStrings, checkNoLegacyModeStrings, checkPendingFdModeIsRW, checkPresetNoOrientation, checkRecordChromeLockSingleSite, checkRecordSurfaceNoBlur, checkRecoveryNoDeletion, checkRecoveryReceiverCounter, checkRecoverySegmentRegex, checkSafTargetCommittedBeforeStream, checkScanFileBoundedWait, checkScanTriggerSingleSite, checkScheduleReceiverNoFgsStart, checkSchedulerNoGetService, checkSetTargetRotationBoundaryOnly, checkStopNoGetService, checkUserCopyVocabulary, checkUserStoppedBeforeMerge, checkVaultExporterNoPublicPublish, checkWakeLockBoundedAcquire, checkWakeLockHeldRefresh, checkWakeLockZeroGapRefresh. *(Source: measured in `app/build.gradle.kts` 2026-06-13; flagged in `memory/project_pr_epsilon_rotation_first_chrome.md` "true unique count 39 not 36 — recount sweep follow-up".)*

- [ ] **PR-ε: per-frame recomposition during 180ms chrome spin (v2)** — **P2**
  PR-ε's counter-rotation triggers a full recomposition per frame during the 180ms spin (minor perf cost, KDoc'd). v2 fix: read the spin angle as a `State<Float>` *inside* the `graphicsLayer` lambda so the rotation updates in the layout/draw phase without recomposing. *(Source: `memory/project_pr_epsilon_rotation_first_chrome.md` smoke-watch "per-frame recomposition during 180ms spin (minor perf, v2: State<Float> read inside layer)".)*

- [ ] **Promote standing-requirement ADRs from Proposed → Accepted (or close)** — **P3** · **VERIFY**
  Four ADRs are still marked **Proposed** while their mechanisms ship in code: ADR-0006 (recording-lifecycle robustness), ADR-0018 (recovery-merge retry classifier preflight), ADR-0019 (thermal hysteresis), ADR-0020 (WCAG 2.2 AA). **VERIFY** each is intentionally Proposed vs. a stale status line before promoting/closing. *(Source: `docs/adr/` status scan 2026-06-13.)*

---

## Known Bugs

- [ ] **PR-ε: cold-entry-in-landscape one-spin (~350ms)** — **P3**
  Entering the record screen while the device is already landscape triggers one chrome spin (~350ms) on first orientation sample. Cosmetic, KDoc'd. *(Source: PR-ε smoke-watch list, `memory/project_pr_epsilon_rotation_first_chrome.md`.)*

- [ ] **PR-ε: LOCKED "Landscape" value overflow grazing separators** — **P3**
  In the LOCKED cell the long "Landscape" value can graze the separators (the `wrapContentSize(unbounded)` inner box overflows rather than truncates by design). Cosmetic tightening. *(Source: PR-ε smoke-watch list.)*

- [ ] **Vault: Tier1 / pre-Q move-out dup-copy window** — **P3**
  A ~millisecond window during Tier1 / pre-Q vault move-out can produce a duplicate copy. KDoc'd as a known non-blocking follow-up; deletion-only path is dynamically ordered in `VaultAndroidOps.kt`, outside `checkRecoveryNoDeletion` scope, JVM-tested. *(Source: `memory/project_b5_private_vault.md` follow-ups; `memory/MEMORY.md` B5 entry.)*

- [ ] **Vault: stale public pointers after move-in** — **P3**
  After moving a recording *into* the vault, stale public MediaStore pointers can linger (cosmetic). *(Source: `memory/project_b5_private_vault.md` follow-ups "stale public pointers post-move-in (cosmetic)".)*

---

## Parked / Future (2027)

- [ ] **Variant D — Ambient Liquid Glass (Slice C)** — **Parked (2027)**
  Parked per ADR-0012 §Future work. Gated on Compose Material 3 Expressive stability + an API-31+ minSdk floor. Piece-1 spike (glass chrome + gradient-scrim fallback) approved as a future experimental branch; pieces 2 (M3 Expressive FAB shape-morph) + 3 (ambient scene-tint sampling) stay parked. *(Source: ADR-0012; `memory/project_current_state.md`; `NEW_UI_BACKEND_REPLAN.md` §Parked.)*

- [ ] **Variant A — mid-segment thermal interrupt** — **Parked**
  Parked per ADR-0016 §Alternatives considered. Explicit unpark trigger: owner reports CRITICAL→OS-kill races in the field. *(Source: ADR-0016; `memory/project_current_state.md`; `NEW_UI_BACKEND_REPLAN.md` §Parked.)*

- [ ] **Variant G — "Continue without saving"** — **Parked**
  A recovery outcome distinct from `Discard`. Parked: owner UX intent unclear, behavioral difference vs `Discard` undefined. Explicit unpark trigger: owner UX spec. *(Source: `memory/project_current_state.md`; `NEW_UI_BACKEND_REPLAN.md` §Parked.)*

- [ ] **Export sheet polish** — **Parked**
  Deferred from the M5 Notification-redesign scope (the originally-bundled `autoExportEnabled` opt-in scope was dropped). *(Source: `memory/project_current_state.md`; `NEW_UI_BACKEND_REPLAN.md` §Parked.)*

- [ ] **M4 follow-on items — tour-replay, full i18n, per-permission JIT split** — **Parked**
  No current owner ask. (Theme switcher already shipped via PR #80; full i18n partially shipped via es locale — the remaining scope is broader-locale + JIT permission split + onboarding tour-replay.) *(Source: `NEW_UI_BACKEND_REPLAN.md` §Parked.)*

- [ ] **Launcher webp legacy fallback for API 24-25** — **Parked**
  Deferred — <0.5% of installs in 2026 per memory. *(Source: `memory/project_current_state.md`; `NEW_UI_BACKEND_REPLAN.md` §Parked.)*

- [ ] **In-app video editor (17 tools)** — **Parked (NO-GO for v1.0)**
  NO-GO on scope/risk grounds: frame-accurate per-clip cuts, audio mixing, OpenGL filter pipeline, text/sticker overlay, multi-source "Add Clips" — a module larger than the current `service/` + `data/` trees combined, with new deps (`androidx.media3.transformer`, OpenGL ES, possibly `MediaCodec` re-encode). The player's Edit button remains a `TODO` snackbar. Today only `MediaMuxer`-concat (`utils/VideoMerger.kt`) exists. *(Source: `NEW_UI_BACKEND_REPLAN.md` §5 row 5 + §7 #2 + §Parked.)*

- [ ] **P+L split-screen capture (true dual-sensor split)** — **Parked**
  Hardware-gated (CameraX 1.x, API-31+ only on supported devices); deferred indefinitely per `NEW_UI_BACKEND_REPLAN.md` §7 #1. (Distinct from today's shipped DualShot, which is one sensor → two crops, and from PR-δ FrontBack PiP above.) *(Source: `NEW_UI_BACKEND_REPLAN.md` §Parked.)*

---

## External (rova-privacy)

- [ ] **rova-privacy PR #1 — privacy-policy refresh (3 commits)** — **P3 (external repo, owner-merged)** · **VERIFY**
  Open on `update/2026-05-28-data-handling-refresh` in the separate **rova-privacy** repo (note: that repo uses `main`, not `master`):
  - Surface 1 — revise the "no network" claim + add the Inter font Google Fonts FontProvider disclosure.
  - Surface 3 — add a DualShot P+L "approximately doubles disk space" note.
  - Surface 4 — add a thermal-status disclosure.
  Owner merges on the rova-privacy side. **VERIFY**: status recorded as open as of the 2026-05-29 cleanup pass; confirm it has not already been merged on the rova-privacy side before actioning. *(Source: `memory/project_current_state.md` lines on rova-privacy PR #1; `memory/MEMORY.md` cleanup-pass entry.)*

---

## Appendix — verification notes

- **Gate count is measured, not estimated:** 39 unique `tasks.register("check…` entries in `app/build.gradle.kts`, all 39 wired into `preBuild` via `dependsOn(...)`. **Corrected in CLAUDE.md 2026-06-13** (count → 39, inline list completed, "20 ADRs" → 29). CLAUDE.md's "28"/"25" and the PR-ε memory's "36" are stale.
- **PR-δ FrontBack is genuinely unbuilt:** `FrontBack` appears only as a `CaptureTopology` enum value + UI references + the `checkFrontBackCapabilityGated` fence; no concurrent-camera source exists under `service/`.
- **Items marked VERIFY** (could not be fully confirmed against current code/state at authoring time): persistent-preview camera-black (no confirmed live repro), Proposed-ADR promotion (status lines may be intentional vs stale), rova-privacy PR #1 (external repo, merge state unknown from this repo).
