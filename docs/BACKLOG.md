# Rova — Engineering Backlog

> Single planning artifact enumerating every remaining, deferred, and carried-over task for **Rova** (`com.aritr.rova`).
> **As of 2026-06-17, master at HEAD `086b1a7`** (Icon & Glyph System BUILT + LIVE — P0 + P1a slices 1–3; app-wide premium dialog system + Library/Record-settings polish merged). **45** custom `check*` gates. master is **local-only, ~40 ahead of origin, NOT pushed** (owner gates push). Only open PR = **#115 DualSight** (DRAFT, archival, never-merge). **NEXT = Liquid Glass theme engine (ADR-0028) — NEEDS-SPEC, brainstorm first.**
> This document is a backlog, not a commitment. Every item traces to an ADR, roadmap, accessibility doc, memory file, or a measured code fact. Priority tags: **P1** (next) · **P2** (soon) · **P3** (later) · **Parked**.

---

## Top 3 next candidates

What the memory trail implies is most concrete and ready to pick up:

1. **Liquid Glass theme engine** (UI/UX Modernization) — **NEXT SESSION, NEEDS-SPEC (brainstorm first).** The deferred big one, now unblocked: the icon color seam (`SemanticIcon` → `SemanticIconSpec.tint(palette,role)` reading `LocalGlassEnvironment`) is built, so the engine inherits a stable icon contract. Library is the **reference screen** (`LibraryColors`/`LibraryColorSpec` identity-vs-locked seam, device GO). `RovaPalette.kt` already defines 12 palettes (`rovaPalettes`); Wave-2 Extended-6 is "defined, exposed later". Candidate scope (decide with owner): 12-palette user picker + persistence · generalize the Library seam into app-wide palette propagation · deferred icon P1/P2 glass-chip active containers + animated glyph states · per-route palette overrides. Spec it via brainstorming → writing-plans. Trigger prompt at the bottom of HANDOFF.md. *(Source: owner sequencing 2026-06-17; ADR-0028 / ADR-0031.)*
2. **`RovaRecordingService.kt` split** (Reliability/Service, P2) — the largest file; decompose into seams without changing behavior, with the static-gate suite pinning invariants. Strongest non-UI candidate. *(Source: `memory/project_current_state.md`.)*
3. **Rova video-player upgrades** (Video/Player/Editing, P2, NEEDS-SPEC) / **DualShot performance** (frame jitter + thermal, P2, owner-reported 2026-06-17) — strongest post-theme candidates. *(Source: `memory/project_current_state.md`; BACKLOG Capture/Mode.)*

> **Icon & Glyph System — ✅ BUILT + LIVE 2026-06-17 (P0 + P1a slices 1–3, on local master `086b1a7`).** P0 = `SemanticIcon` tint seam + status-color lock + `checkSemanticIconNoRawAlpha`/`checkStatusColorLocked`. Slice 1 = two-layer `RovaGlyph` pipeline (Library/Settings/Sort/Record). Slice 2 = 6 brand + 2 orientation glyphs authored, `RecordChromeIcons` folded into `RovaGlyphs` + deleted, `checkRovaGlyphHome` (45th gate). Slice 3 = glyphs LIVE (capture/orientation pickers, Vault, Merge, Recovery) + `Single`/`FollowDevice`. System D visual language. `BackgroundRecord` glyph deferred (FGS notification needs a drawable-resource, not ImageVector). Deferred to the theme engine: glass-chip active containers + animated states (P2). *(Source: `memory/project_icon_glyph_system.md`; ADR-0031.)*
> **App-wide premium dialog system + Library/Record-settings polish — ✅ DONE 2026-06-17 (on local master `086b1a7`).** `RovaAlertDialog` routes all 13 dialog sites through one branded scaffold: glass depth (lit rim + lift, 32dp corners, soft shadow), filled accent-gradient CTA with per-theme WCAG-checked label (`DialogActionColors`, pure + JVM-tested, all 12 palettes ≥AA), ghost dismiss, header icon-chip, X-close for informational dialogs. Compact item-sheet, gear→Info(Details), DualShot vault greyed+reason, tap-to-edit stepper values. Rename "View settings"→Details. *(Source: `memory/project_icon_glyph_system.md` polish-follow-up note.)*
> **Enhanced Library & History UI — ✅ COMPLETE + MERGED 2026-06-16.** Liquid-Glass redesign (Slices 1–5 + polish P1–P8 + Theme-Foundation M1–M3 + UX refine + sort-crash fix `e0f359a`) merged via stacked no-squash train #117→#118→#119. ADR-0030 + `checkLibraryNoManifestWrite`. Library = reference screen for the theme engine. Deferred B-items remain (top-bar action reorder, hero "Latest" tag, item-sheet Play emphasis, batch-Delete isolation, scrubber-thumb rest-state). *(Source: `memory/project_library_history_redesign.md`.)*
> **`RovaRecordingService.kt` split** (Reliability/Service, P2) and **Rova video-player upgrades** (Video/Player/Editing, P2, NEEDS-SPEC) remain the strongest non-UI / post-theme candidates. **`TokenContrastTest` hardening** (P3) is the standing a11y tech-debt follow-up.

> **PR-δ FrontBack PiP / DualSight — ⛔ DEFERRED / BLOCKED 2026-06-14** (was the former #1). A hardware probe proved concurrent front+back capture is unsupported on the available device (silicon-level), and no non-concurrent path delivers simultaneous front+back *video*. Blocked pending a verified concurrent-camera device. The **δ0 CameraX 1.5.3 bump** is planned + decoupled (merge-later). See the Capture/Mode section + `docs/superpowers/specs/2026-06-14-dualsight-probe-results.md`.
> **`checkA11y*` static-gate suite — ✅ DONE 2026-06-14** via #114: built `checkA11yTargetSizeToken` (41st gate, SC 2.5.8) + superseded `checkA11yNoLowAlphaTextToken` with `TokenContrastTest`. ADR-0020's 4 sketched gates resolved. See the Accessibility section.
> **Preset UI polish — ✅ DONE 2026-06-14** via #112 + #113. See the UI Polish section.

---

## Capture / Mode

- [ ] **PR-δ FrontBack PiP / DualSight (2-sensor concurrent capture)** — ⛔ **DEFERRED / BLOCKED** (was P2)
  Build `CaptureTopology.FrontBack`: two concurrent camera bindings (front + back) composed into a single-file picture-in-picture clip via CameraX `CompositionSettings`, hardware-capability-gated. **BLOCKED pending a device with verified concurrent front+back support** (Pixel 7+ / Samsung S21+ Director's View class). A hardware probe (2026-06-14) proved the available device (Galaxy A-class, single-ISP) cannot do it at the silicon level: `FEATURE_CAMERA_CONCURRENT=false`, `getConcurrentCameraIds()` empty, `availableConcurrentCameraInfos` empty, `bindToLifecycle` threw `UnsupportedOperationException`; raw Camera2 confirmed. A follow-on analysis found **no non-concurrent path to simultaneous front+back *video*** (rapid-switch = <3fps; sequential/BeReal = photos = a different feature; LOGICAL_MULTI_CAMERA is same-direction-only; no Camera2 bypass). Owner direction: defer, do **not** build a photo fallback. `FrontBack` stays an enum value + UI refs fenced by `checkFrontBackCapabilityGated`; the gate's allowlist extension is **pending** until the feature is unblocked. Capability helper `service/dualsight/ConcurrentCameraCapability.kt` (+ 6 JVM tests) preserved for future use. **Full investigation — design spec, probe evidence + log, δ0 plan, deferred feature plan (resume preconditions), and the preserved keeper — is archived in PR #115 (branch `feat/pr-delta-dualsight`), not on master.** *(Source: ADR-0029 §5 implementation-status note 2026-06-14; PR #115 archival record.)*

- [ ] **δ0 — CameraX 1.4.2 → 1.5.3 bump** — **P3** (planned, NOT executed, **merge-later**)
  Standalone, mergeable dependency bump originally scoped as DualSight's prerequisite, now decoupled and justified on its own reliability merits (1.5.1 SurfaceProcessor-shutdown crash fix — DualShot's path; recording-churn crash fixes; Android-17 DynamicRange crash fix). The probe branch proved 1.5.3 compiles clean against existing Single + DualShot code (zero API breaks). **Low-urgency** (no reported Rova bug ties to the fixes; the rotation-on-recreate fix is largely self-defended by Rova's explicit per-segment `setTargetRotation`). Decision review (2026-06-14) verdict: **merge later** — execute the regression plan (baseline-vs-bumped `ffprobe` diff + device smoke), then merge as standalone reliability work; not merge-now (recording-pipeline bump needs the smoke), not leave-indefinitely. **Plan archived in PR #115 (branch `feat/pr-delta-dualsight`):** `docs/superpowers/plans/2026-06-14-delta0-camerax-1.5.3-bump.md`. *(Source: δ0 plan in PR #115; 2026-06-14 decision review.)*

- [ ] **Advanced lock UI (reverse-portrait / 180° / "lock whatever I'm aimed at now")** — **P3**
  Deferred per ADR-0029 decision D ("defer advanced-lock UI, build the 4-rotation model now"). The two clean axes (capture-topology × orientation-policy) were built specifically so future locks are cheap; the UI surface is not yet designed. *(Source: ADR-0029 decision D; `memory/project_mode_orientation_replan.md`.)*

- [ ] **DualShot performance — frame jitter + thermal buildup** — **P2** · **owner-reported 2026-06-17 (device RZCYA1VBQ2H)**
  Two distinct symptoms from owner DualShot smoke: **(1) frame jitter/stutter** — DualShot capture is visibly less smooth than the native camera; suspect the EGL14/GLES20 fan-out render path (`service/dualrecord/**` — single `CameraEffect(target=PREVIEW)` → FBO-ring → fence-sync → dual MediaMuxer) dropping/uneven frames vs the single-encode path. **(2) Excessive heating** — after ~5+ clips the device heats enough to trip the thermal warning (and DualShot auto-stop, ADR-0016/0019), implying sustained high GPU/encoder load from running two muxes + the GL fan-out. Owner direction: **future optimization cycle — make the app lighter / less resource-intensive.** Investigate: per-frame copy/FBO cost, double-encode bitrate, whether the broadcast `AudioRecord` + dual-muxer cadence stalls the GL thread, and whether δ0 (CameraX 1.5.3, SurfaceProcessor-shutdown fix) helps. Needs a profiling pass (systrace/GPU profiler) on device before any fix. *(Source: owner DualShot smoke 2026-06-17; `memory/project_dualshot_stability_stack.md`; ADR-0009.)*

---

## Reliability / Service

- [ ] **`RovaRecordingService.kt` split** — **P2**
  The service file is the largest in the tree and is flagged as a refactor candidate (it is the sole owner of CameraX + MediaMuxer + the segment loop). Decompose into smaller seams without changing behavior; the static-gate suite (`checkStopNoGetService`, `checkUserStoppedBeforeMerge`, `checkCompletedWriteOnlyFromPerformMerge`, etc.) pins the invariants that must survive the split. *(Source: `memory/project_current_state.md` "Deferred / next-option backlog: … `RovaRecordingService.kt` split".)*

- [ ] **Persistent-preview layer for zero camera-black** — **P3** · **VERIFY**
  Noted in memory as a backlog idea: a persistent-preview surface layer so the camera surface never goes black during mode/lens reconfigure or nav transitions. **VERIFY**: this is listed as an aspirational "next-option" in `project_current_state.md`, not as a confirmed open bug with a current repro — earlier black-preview reports were resolved incidentally (`#77`/`#78`) or did not reproduce (scheduled black-preview "didn't repro", deferred). Confirm there is still a reproducible camera-black path before scheduling work. *(Source: `memory/project_current_state.md` "persistent-preview layer for zero camera-black"; `memory/MEMORY.md` bugfix-batch note "Bug 1 scheduled black-preview DEFERRED … didn't repro".)*

---

## Accessibility (ADR-0020)

ADR-0020 ("WCAG 2.2 AA by default") is **Proposed**. All Serious/Blocker findings from the 2026-05-29 audit were remediated and FF-landed on master (`892d551`); the rows below are the **deferred Moderate/Advisory** remainder plus the unbuilt static-gate suite. *(Sources: `docs/adr/0020-wcag-2.2-aa-by-default.md` (Status: Proposed); `docs/accessibility/remediation-backlog.md`; `memory/project_accessibility_wcag_audit.md`.)*

- [x] **Build the `checkA11y*` static-gate suite** — ✅ **DONE 2026-06-14 (#114, merged `bd6aa0c`) — the 4 ADR-0020-sketched gates resolved**
  ADR-0020 sketched **4** `checkA11y*` gates. Status: `checkA11yAnimationGated` (built #?, 2026-06-03), `checkA11yClickableHasRole` (40th gate, #110), `checkA11yTargetSizeToken` (41st gate, **PR #114**, SC 2.5.8 ≥24dp curated set) — all three **built + wired into `preBuild`**. The 4th, `checkA11yNoLowAlphaTextToken`, was **superseded by `TokenContrastTest`** in PR #114 (audit: contrast source already fully AA-compliant; a static `alpha<0.55` regex is background-blind and would false-fail AA-passing tokens, so contrast is enforced by the JVM test that computes real ratios over each token's surface). `checkLocaleConfigNoPseudolocale` is locale-config, not a11y proper. The original-sketch suite is thus **resolved**; broader *new* invariants (semantics-presence, live-region-presence) remain candidates for future ADR-0020 amendments. *(Source: ADR-0020; CLAUDE.md; PR #114.)*

- [ ] **Harden `TokenContrastTest` (contrast regression-hostility)** — **P3**
  Follow-up from PR #114's codex review of the contrast-test-as-oracle decision. The test currently covers every *current* text token by an explicit map, so a NEW text token isn't auto-checked (rot risk). Harden: (1) reflectively enumerate `*Text`/`*Label` `Color` props on the token objects and fail on any uncovered one (note: `Color` is an inline value class — verify reflection works under `isReturnDefaultValues = true`); (2) centralize the background reference RGBs (currently inline `sheetBg`/`chromeDarkRef`) as a named contract; (3) extend coverage to theme variants (light/dark/system + the 12-theme palettes, elevated sheets, disabled surfaces); (4) keep a `ContrastMath` WCAG-example pin so a helper bug can't silently bless bad tokens. *(Source: PR #114 codex review.)*

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

- [x] **Preset / custom-modes UI polish** — ✅ **DONE 2026-06-14** (#112 + #113)
  Owner had flagged the in-sheet PRESETS section as "kinda mess". Shipped via #112 (`23fb7e4`): uniform **tile-grid** preset popup on both surfaces (`FloatingSettingsPanel` + `SettingsSheet`) with a bottom scroll-fade cue + pure `presetTileSummary`; **responsive `ChromeScale`** config strip (`cellSlot` 48→44dp, slot × device-anchored factor on `smallestScreenWidthDp`) + floating-panel side cap (byte-identical 320dp on the 411dp ref device); `∞` repeats cell for continuous mode. ADR-0029 §B″3 amended. Then #113 (`3287916`) raised the panel's two sub-24dp controls to the AA touch floor (SC 2.5.8 = 24dp) via touch-padding only. Device-smoke GO. *(Custom-modes authoring UI — distinct from preset polish — remains a future Track-A item; not in scope here.)*

- [ ] **PR-ε: warning-sheet visibility hoist out of WarningCenter** — **P2**
  PR-ε v1 deviation: the record-chrome lock currently stays engaged when a warning sheet opens because the warning-sheet visibility flag is not hoisted out of `WarningCenter`. Follow-up: hoist the flag so chrome can unlock for the warning sheet like it does for the tips/settings modals. *(Source: `memory/project_pr_epsilon_rotation_first_chrome.md` "warning-sheet visibility not hoisted → keeps lock when it opens (follow-up: hoist flag out of WarningCenter)".)*

- [ ] **PR-ε: thermal-tips sheet portrait-under-lock conversion** — **P2**
  The thermal tips sheet should render in the upright reading frame while chrome is locked landscape (consistent with the floating settings panel pattern), rather than inheriting locked-landscape chrome. *(Source: PR-ε smoke-watch list / floating-panel pattern, `memory/project_pr_epsilon_rotation_first_chrome.md`.)*

- [ ] **PR-ε: BackHandler disarm during exit animation** — **P3**
  The floating settings panel's `BackHandler` should be disarmed while the panel's exit (close) animation runs, to avoid a second back-press racing the dismissal animation. *(Source: PR-ε floating-panel work, `memory/project_pr_epsilon_rotation_first_chrome.md` — `FloatingSettingsPanel.kt` BackHandler.)*

- [ ] **Liquid Glass landscape re-layout (deferred follow-up)** — **P3**
  The 3rd Liquid Glass Record-home follow-up (landscape re-layout) was deferred to its own PR and folded into the mode/orientation track; revisit after PR-δ. *(Source: `memory/MEMORY.md` liquid-glass entry "3rd follow-up (landscape re-layout) → DEFERRED to own PR, folded into project-mode-orientation-replan".)*

---

## UI/UX Modernization

> **Owner-requested 2026-06-14** — modernization sweep toward a polished, Liquid-Glass-native, modern-video-app feel. These are **new** and not yet ADR-backed; each needs brainstorm → spec (and likely a new ADR or an ADR-0028 amendment) before build. Marked **NEEDS-SPEC**.

- [ ] **Enhanced Library & History UI** — **P2** · **NEEDS-SPEC**
  Redesign/upgrade the Library and History surfaces to the Liquid Glass language — richer thumbnails, date/session grouping, sort + filter, multi-select / batch actions, polished empty + loading states. Today History has only the nav state-retention fix (#100) and deferred a11y focus rows (23/32); no visual modernization pass exists. *(Source: owner request 2026-06-14; History work in `memory/project_settings_expansion_*`; accessibility rows 23/32.)*

- [ ] **More system-wide themes (extend the 12-theme set)** — **P3** · **NEEDS-SPEC**
  Add themes beyond the current 12-theme Liquid Glass system (ADR-0028, shipped #80) — new palettes / accent variants, possibly seasonal or a dedicated high-contrast theme (ties into ADR-0020 AA). *(Source: owner request 2026-06-14; builds on ADR-0028 theme system + `memory/project_settings_expansion_b2.md`.)*

- [ ] **Icon & Glyph System — P0 build** — **P1 (next), design APPROVED 2026-06-16** · ADR-0031
  Design phase ✅ DONE (audit + research + visual exploration → **System D** locked + semantic glyph design + spec + ADR-0031). System D = outlined-default soft-monoline + duotone accent channel + filled-active in a Liquid-Glass chip + **locked semantic status colors** (mirrors `LibraryColors`/`RovaSemantics` identity-vs-locked). **P0 (before the theme engine):** resolve chrome-baked collisions (Settings→gear · Sort→state-free bars · Library→stacked-frames · Warning-status vs Notifications-setting split · Record action/nav/stop distinction); introduce the **`SemanticIcon` tint seam** replacing the 20 raw `Color.White.copy(alpha=…)` sites; lock status colors to `RovaSemantics`; add gates `checkSemanticIconNoRawAlpha` + `checkStatusColorLocked` (invariant→check*→preBuild). **P1 (with engine):** bespoke `RovaGlyphs` (DualShot/Vault/Recovery/DualSight/Background-record/Merge), `RecordChromeIcons` folds in, accent+glass-chip palette wiring. **P2:** animated states (processing/merging) + secondary concepts (Loop/Interval, Segment count, Waiting) + onboarding illustration refresh. Spec: `docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md`. Boards (gitignored): `.superpowers/brainstorm/…/board-3-semantic.html`. *(Source: ADR-0031; this session.)*

---

## Video / Player / Editing

> **Owner-requested 2026-06-14** alongside the modernization sweep. Player + editing upgrades. Marked **NEEDS-SPEC**.

- [ ] **Rova video-player upgrades** — **P2** · **NEEDS-SPEC**
  Enhance the in-app Media3 player (shipped `db25405`, `ui/screens/player/`, Media3 pinned 1.4.1) — playback-speed control, scrub thumbnail preview, frame-step, gesture controls (double-tap seek / vertical brightness+volume), background/PiP playback, share affordance, and the deferred A11y timeline semantics (cross-ref Accessibility row 21 — SegmentedTimeline `progressbar` role + per-cell labels). *(Source: owner request 2026-06-14; player in `ui/screens/player/`; accessibility row 21.)*

- [ ] **Expanded video-editing capabilities** — **P2 / Parked** · **cross-ref / NEEDS-SPEC**
  Owner wants expanded editing. Today only `MediaMuxer`-concat exists (`utils/VideoMerger.kt`); the player's Edit button is a `TODO` snackbar. The full **"In-app video editor (17 tools)" is Parked NO-GO for v1.0** (see Parked section — module larger than `service/` + `data/` combined, needs `media3.transformer`/OpenGL/`MediaCodec`). **Re-scope** as a shippable incremental subset — trim/cut, reorder/delete clips, thumbnail-accurate seek, simple top-level export — rather than the full editor. *(Source: owner request 2026-06-14; see Parked "In-app video editor (17 tools)"; `NEW_UI_BACKEND_REPLAN.md` §5/§7.)*

---

## Modern-app expectations (QoL)

- [ ] **Modern video-app must-haves & QoL bundle** — **P3** · **NEEDS-SPEC**
  Umbrella for features users reasonably expect from a modern recording/video app, to be split into individually-specced items as prioritized: quick-share, in-Library search, storage usage + management view, additional export/backup targets (cloud/SAF — partial SAF shipped via ADR-0024), home-screen widget / quick-settings-tile capture, richer notification controls, and onboarding/first-run polish. *(Source: owner request 2026-06-14; existing SAF export ADR-0024; notification redesign M5.)*

---

## Static-Gate / Tech-Debt

- [x] **Gate-count documentation recount sweep** — ✅ **DONE 2026-06-13**
  **✅ Applied 2026-06-13:** `CLAUDE.md` corrected — count → **39**, inline gate list completed (added the 11 missing names), and "20 ADRs (0001–0020)" → "29 ADRs (0001–0029)" with topics extended. ADR-0020/0022's "25 checks" left **as-is** (point-in-time-at-authoring — "25 before this slice", "the 26th invariant" — rewriting to 39 would corrupt the historical narrative of immutable decision records). Memory (`project_current_state.md`, `MEMORY.md`) updated. Original note retained below for traceability.
  **Measured fact:** there are **39 unique `check*` gate tasks** in `app/build.gradle.kts` — counted as 39 occurrences of `tasks.register("check…` (`grep -oE 'tasks\.register\("check[A-Za-z0-9]+' | sort -u | wc -l` = 39, zero duplicates), and **all 39 are wired into `preBuild`** (39 unique `check*` names appear in `dependsOn(...)`). The docs are stale: `CLAUDE.md` says "**28** custom `check*` tasks" in its Static-check-gate section and "the existing **25** checks" in the ADR-0020 paragraph; the PR-ε memory carried "**36** gates". Update `CLAUDE.md` (both numbers), the full gate enumeration list, and any ADR text that cites a count, to **39**. Full current gate list (alphabetized): checkA11yAnimationGated, checkAtomicTerminalWriteForbiddenPair, checkAudioModeFgsTypeMatch, checkCompletedWriteOnlyFromPerformMerge, checkExportCleanupPredicate, checkExportIsPendingGuarded, checkExportNoCopyToPublicMovies, checkExportPendingVisibilityOnQuery, checkExportPipelineSingleEntry, checkExportQueryArgMatchPendingGuarded, checkExportSetIncludePendingGuarded, checkExportTierReadTolerant, checkExternalRootShared, checkFGSStartGuarded, checkFrontBackCapabilityGated, checkGlassSurfaceRoleUsage, checkLocaleConfigNoPseudolocale, checkNoHardcodedUiStrings, checkNoLegacyModeStrings, checkPendingFdModeIsRW, checkPresetNoOrientation, checkRecordChromeLockSingleSite, checkRecordSurfaceNoBlur, checkRecoveryNoDeletion, checkRecoveryReceiverCounter, checkRecoverySegmentRegex, checkSafTargetCommittedBeforeStream, checkScanFileBoundedWait, checkScanTriggerSingleSite, checkScheduleReceiverNoFgsStart, checkSchedulerNoGetService, checkSetTargetRotationBoundaryOnly, checkStopNoGetService, checkUserCopyVocabulary, checkUserStoppedBeforeMerge, checkVaultExporterNoPublicPublish, checkWakeLockBoundedAcquire, checkWakeLockHeldRefresh, checkWakeLockZeroGapRefresh. *(Source: measured in `app/build.gradle.kts` 2026-06-13; flagged in `memory/project_pr_epsilon_rotation_first_chrome.md` "true unique count 39 not 36 — recount sweep follow-up".)*

- [ ] **Narrow recovery-write allowlist gate (`checkRecoveryManifestWriteAllowlist`)** — **P3**
  After ADR-0030's Slice-3 amendment, recovery manifest writes in `ui/recovery/` are invisible to
  `checkLibraryNoManifestWrite` (by design — ADR-0005 owns recovery's terminal writes; the gate guards
  Library *metadata* UI only). If recovery manifest writes proliferate beyond the single recovery-keep
  `markTerminated`, add a gate scoping `ui/recovery/` to a known allowlist of terminal-repair calls. Not
  needed while the only such write is the recovery-keep in `RecoveryViewModelFactory.kt`. *(Source: codex
  review of the Slice-3 Option-A relocation; `docs/adr/0030-...md` §2 amendment.)*

- [ ] **PR-ε: per-frame recomposition during 180ms chrome spin (v2)** — **P2**
  PR-ε's counter-rotation triggers a full recomposition per frame during the 180ms spin (minor perf cost, KDoc'd). v2 fix: read the spin angle as a `State<Float>` *inside* the `graphicsLayer` lambda so the rotation updates in the layout/draw phase without recomposing. *(Source: `memory/project_pr_epsilon_rotation_first_chrome.md` smoke-watch "per-frame recomposition during 180ms spin (minor perf, v2: State<Float> read inside layer)".)*

- [ ] **Build-speed: unblock the Gradle configuration cache (gate refactor)** — **P2**
  Cold builds run ~17-20 min largely because the **configuration cache is OFF**: the 40 `check*`
  gates capture build-script references in their `doLast` actions, which the configuration cache
  cannot serialize (it **fails at execution** — "CompiledKotlinBuildScript ... this$0 is null" —
  not just warns; see the `gradle.properties` note). Refactor the gates to script-ref-free task
  I/O — move each scan into a `@CacheableTask`/`build-logic` convention plugin with explicit
  `inputs.dir(...)` **and** an `outputs.file(...)` sentinel — then set
  `org.gradle.configuration-cache=true`. Prereq fact: the gates declare `inputs` but **no
  `outputs`** today, so none are UP-TO-DATE/cacheable (they re-run every build; cheap, but the
  cleanup is a config-cache prerequisite). **Independent quick win (zero code):** the old
  per-build cache-wipe "recovery dance" is **obsolete** — the `~/.claude/hooks/kotlin-postedit.ps1`
  hook that corrupted the Kotlin incremental cache was disabled 2026-06-09 (no-op), so **warm
  incremental builds (~1-3 min vs ~17 cold) are safe**; clean only on-demand if a build errors.
  *(Source: `gradle.properties` config-cache note; `memory/project_build_env_perf.md`; measured:
  `app/build.gradle.kts` gates have `inputs`, no `outputs`.)*

- [ ] **Promote standing-requirement ADRs from Proposed → Accepted (or close)** — **P3** · **VERIFY**
  Four ADRs are still marked **Proposed** while their mechanisms ship in code: ADR-0006 (recording-lifecycle robustness), ADR-0018 (recovery-merge retry classifier preflight), ADR-0019 (thermal hysteresis), ADR-0020 (WCAG 2.2 AA). **VERIFY** each is intentionally Proposed vs. a stale status line before promoting/closing. *(Source: `docs/adr/` status scan 2026-06-13.)*

---

## Known Bugs

- [x] **Library sort crash on Largest / Longest** — ✅ **FIXED 2026-06-16 (commit `e0f359a`, local on `feat/library-history-selection`)**
  Selecting the **Largest** or **Longest** sort crashed the Library. Root cause: `LibraryDayGrouping.group` folds same-day rows assuming **date-contiguous** input (true for NEWEST/OLDEST); LONGEST/LARGEST sort by duration/size, scattering a day's rows → the same day label recurs in non-adjacent buckets → the render keys header items `key="hdr-${group.label}"` (LibraryScreen grid:620 / list:664) → **duplicate LazyList keys → `IllegalArgumentException`**. Fix: `LibrarySort.isChronological` gates new pure `LibraryDayGrouping.groupForSort` — chronological → day buckets; size/duration → one header-less bucket (label="" → render suppresses the header). Also the correct UX (flat list under size sort). +5 regression tests (`LibraryDayGroupingTest`) incl the no-duplicate-labels invariant across every sort. Why missed: day-grouping tests fed only pre-date-sorted input; the 42 gates are static (no Compose render); no instrumented test exercises a non-date sort. **Build+42 gates+JVM green; NOT yet device-smoked.** *(Source: this session's stabilization pass; `memory/project_library_history_redesign.md`.)*

- [ ] **Library scrubber thumb reads as a "floating blue dot" at rest** — **P3 (deferred B-item, not a bug)**
  The persistent blue circle on the Library's right edge is the `LibraryScrubber` date-fast-scroll **thumb** ([LibraryScrubber.kt:137-143](../app/src/main/java/com/aritr/rova/ui/library/components/LibraryScrubber.kt#L137-L143)): a 24dp `CircleShape` filled `MaterialTheme.colorScheme.primary` (royal-violet/blue accent), shown at rest when ≥2 day groups exist. It belongs to the app, is intentional UI, is a11y-wired as a slider — **not** a debug artifact or system overlay. The smell is the bare filled primary circle at rest with no track. **Recommendation: KEEP**; defer a rest-state refinement (show-on-scroll / lower-emphasis / faint track) — it's a product+a11y decision, not a fix. *(Source: this session's Issue-B investigation.)*

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
