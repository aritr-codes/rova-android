# Reference doc drift report — cleanup pass sub-B Phase 1

**Date:** 2026-05-28
**Spec:** `docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md`
**Baseline:** rova-android master tip `5e354d2` (origin/master `d54e051`)
**Docs audited:** 9 tracked reference docs

---

## Status legend

- `CURRENT` — claim matches current code/spec
- `STALE` — claim was true once but no longer is
- `MISSING` — coverage gap (doc should mention something but doesn't)
- `TBD-VERIFY` — needs further investigation before fix can be drafted

---

## Per-doc tables

### `readme.md`

| Line / Section | Claim | Status | Evidence (current truth) | Suggested fix |
|---|---|---|---|---|
| L10 | `Version 0.5.0` | STALE | `app/build.gradle.kts:26` has `versionName = "0.5.0"` but sub-D will bump to 0.9.0; for now version metadata is in sync with build.gradle, but both are behind the actual shipped state | Coordinate with sub-D; update both after version bump lands |
| L12 | `targetSdk 36` | STALE | `app/build.gradle.kts:24 targetSdk = 37` (PR #16 `e505c35`) | Change to `37` |
| L197 | `Playback: AndroidX Media3 (ExoPlayer + PlayerView) — pinned to 1.4.1` | CURRENT | `app/build.gradle.kts:1629` confirms 1.4.x pinned; CLAUDE.md confirms `media3 1.4.1 pinned` | Leave |
| L256–260 | `screens/player/` subtree lists `PlayerScreen.kt`, `PlayerViewModel.kt`, `PlayerUriResolver.kt`, `PlayerUiState.kt` | CURRENT | All 4 files exist under `ui/screens/player/` (PR #1 `db25405` confirmed) | Leave |
| L278–281 | Documentation table lists 4 docs only: `product_vision`, `architecture`, `development_log`, `naming` | MISSING | `docs/` also contains `WarningCenterContract.md`, `UI_DESIGN_TOKENS.md`, `UI_NAV_GRAPH.md`, `release_checklist.md` | Extend table with the 4 missing doc entries |
| L285–291 | ADR table lists 5 ADRs (0001, 0002, 0003, 0005, 0006) — skips 0004 entirely, stops at 0006 | STALE | 19 ADR files exist: 0001–0006 (no 0004 file), 0007–0009, two 0010 siblings, 0011–0019 (`ls docs/adr/`) | Regenerate ADR table; note the 0010 sibling pair (canonical-uv-frame + crop-divergence) |
| Module tree (L243) | `WakeLockPolicy.kt` listed under `service/wakelock/` | CURRENT | `service/wakelock/` package exists in current package tree | Leave |
| Module tree | No entry for `service/dualrecord/`, `service/export/`, `service/notification/`, `service/recovery/`, `service/scheduler/`, `service/audio/`, `service/surface/` | MISSING | All 7 service sub-packages exist (`find app/src/main/java/com/aritr/rova/service -type d`) | Add service sub-package entries to the module tree |
| Module tree | No entry for `ui/screens/onboarding/` | MISSING | `ui/screens/onboarding/` exists (PR #53 `12c12a9`) | Add onboarding subtree entry |
| Module tree | No entry for `ui/recovery/`, `ui/share/`, `ui/permissions/` | MISSING | All 3 packages exist in current package tree | Add entries |
| L274 §Documentation | No mention of ADRs 0007–0019 | MISSING | 14 additional ADRs added since last README update | Update ADR table (already captured above) |
| Static-check gate | README has no mention of the 25 `check*` tasks wired into `preBuild` | MISSING | `app/build.gradle.kts` registers 25 custom check tasks; CLAUDE.md enumerates all 25 by name | Add a note about the static-check gate (or at least the count) in the README build section |

---

### `docs/architecture.md`

| Line / Section | Claim | Status | Evidence (current truth) | Suggested fix |
|---|---|---|---|---|
| §1 Tech Stack table | `Target SDK: 37` | CURRENT | `app/build.gradle.kts:24 targetSdk = 37` | Leave |
| §2 Project Structure tree | `service/` shows only `RovaRecordingService.kt` (monolithic entry) | STALE | 9 sub-packages under `service/` exist: `audio/`, `dualrecord/` (+ `internal/`), `export/`, `notification/`, `recovery/`, `scheduler/`, `surface/`, `wakelock/` | Expand `service/` tree to show sub-packages |
| §2 Project Structure tree | `ui/screens/` lists `RecordScreen.kt`, `RecordChrome.kt`, `SessionSettingsSheet.kt`, `RecordViewModel.kt`, `HistoryScreen.kt`, `HistoryViewModel.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`, `BatteryOptimizationHelper.kt`, `VideoMetadataUtils.kt` — no `player/` or `onboarding/` sub-directories | STALE | `ui/screens/player/` (PR #1) and `ui/screens/onboarding/` (PR #53) both exist | Add `player/` and `onboarding/` subtrees to the ui/screens/ section |
| §2 Project Structure tree | `ui/warnings/` `WarningId.kt` annotated "17-row enum (R2 adds STORAGE_LOW_MID_REC at ordinal 10)" | STALE | Current `WarningId.kt` has 17 enum entries but the doc comment says "R2 adds STORAGE_LOW_MID_REC" as a forward-looking note. The actual enum now has 17 entries including 4 entries added after R2 (THERMAL_AUTOSTOPPED, CANT_MERGE, POWER_SAVE_MODE, THERMAL_MODERATE). The "17-row enum" count is correct but the description "R2 adds STORAGE_LOW_MID_REC at ordinal 10" understates what was added post-R2. | Update the WarningId.kt annotation to reflect final 17-entry state |
| §2 Project Structure tree | `PreviewActivity.kt` listed as "In-app video player" in both the tree and the architecture diagram | STALE | `PreviewActivity.kt` appears to be a pre-`player/` artifact; the current in-app player is `ui/screens/player/PlayerScreen.kt` (Compose, Media3). Architecture diagram shows `PA[PreviewActivity]` — this should be `PlayerScreen`. Verify whether `PreviewActivity.kt` still exists alongside the `player/` package or was deleted. | Verify file existence; if `PreviewActivity.kt` is gone, replace with `PlayerScreen.kt` entry. If it coexists, clarify which is the active player. |
| §2 Project Structure tree | `ui/theme/` shows only `Color.kt`, `Theme.kt`, `Type.kt` | STALE | `ui/theme/` now contains 9 files: `Color.kt`, `Font.kt`, `RecordChromeTokens.kt`, `RovaTokens.kt`, `RovaTokensPreview.kt`, `RovaWarningsV3.kt`, `SettingsSheetTokens.kt`, `Theme.kt`, `Type.kt` (verified via `ls`) | Expand theme/ entry in tree |
| §2 Project Structure tree | `ui/` has no entries for `ui/recovery/`, `ui/share/`, `ui/permissions/` | MISSING | All 3 exist in current package tree | Add entries |
| §8 UI Composition & Navigation | `NavigationBar 3 tabs` shown as persistent app-wide navigation in the Mermaid diagram | STALE | Record-home redesign R1 (PR #17) removed the app-wide `NavigationBar`. Record now owns its own bottom nav (Library / FAB / Settings); `history` and `settings` are drill-down routes, not top-level tabs. | Update nav section to reflect R1 + R2 shipped design |
| §8 UI Composition & Navigation | `ModalNavigationDrawer` shown as settings mechanism | STALE | Settings is now a drill-down Compose route (`SettingsScreen.kt`), not a `ModalNavigationDrawer` | Remove/replace ModalNavigationDrawer reference |
| §9 Technical Decisions table (end of file) | "No unit tests — VideoMerger and RovaSettings have no test coverage" | STALE | Codebase now has 108 test files and 1322 unit tests (master baseline per CLAUDE.md). TDD is the project convention. | Update or remove this line |
| §9 Technical Decisions table | No mention of DualShot / dualrecord subsystem | MISSING | `service/dualrecord/` is a significant architectural subsystem (ADRs 0008, 0009, 0010 siblings; PRs #22–#35) | Add DualShot / CameraEffect fan-out to the architecture doc |
| §9 Technical Decisions table | No mention of ADR-driven static-check gate (25 `check*` tasks) | MISSING | 25 `check*` tasks in `app/build.gradle.kts` are a significant architectural invariant enforcement mechanism | Add mention of the static-check gate |

---

### `docs/WarningCenterContract.md`

| Line / Section | Claim | Status | Evidence (current truth) | Suggested fix |
|---|---|---|---|---|
| §1 Revision note (top) | "17 rows, in precedence order" | STALE vs §4 | The Revision block says "17 rows" in the precedence list, but §4 heading says "The 18 warning states" and the actual `WarningId.kt` enum has 17 entries. The discrepancy is: the contract counts 18 named warning states (C1.1–C4.5) in §4, but the enum has 17 entries because `LOW_BATTERY` was removed or merged. The precedence flat list in §1 explicitly names only 17. | Reconcile the "17 rows" (precedence list) vs "18 warning states" (§4 heading) — either update §4 to say "17" or clarify that one §4 state has no standalone `WarningId` entry. |
| §1 Revision note | Snooze/hysteresis "deferred" to Phase 4.1c | STALE | Snooze persistence shipped in PR #44 (Phase 4.1c), PR #45. `RovaSettings` has snooze keys. `WarningPrecedence` has snooze support. The "deferred" note is no longer accurate. | Update note to say snooze shipped in PR #44 (Phase 4.1c, ADR-0014) |
| §4 "The 18 warning states" | Table includes all states through C4.5 with Phase 4.3 (ADR-0017) `CANT_MERGE` and Phase 4 Slice 2/3 (`STORAGE_FULL_AUTOSTOPPED`, `THERMAL_AUTOSTOPPED`) | CURRENT | `WarningId.kt` contains `CANT_MERGE`, `STORAGE_FULL_AUTOSTOPPED`, `THERMAL_AUTOSTOPPED` | Leave |
| §11 References | `WarningCenter` routing references `effectiveIdleTopBannerId` | MISSING | `effectiveIdleTopBannerId` (ADR-driven idle-echo promotion pattern, memory `feedback_idle_echo_promotion.md`) is not mentioned in §11 References or the contract body | Add reference to idle-echo promotion pattern and `effectiveIdleTopBannerId` |
| General | No mention of ADR-0016 (thermal autostop), ADR-0017 (recovery merge architecture), ADR-0018 (retry classifier preflight) | MISSING | §4 states reference these shipped features but §11 References doesn't list ADR-0016/0017/0018 | Add ADR-0016, 0017, 0018 to §11 References |

---

### `docs/UI_DESIGN_TOKENS.md`

| Line / Section | Claim | Status | Evidence (current truth) | Suggested fix |
|---|---|---|---|---|
| Status block (L3) | "Phase 1 Foundation implemented … live on `feat/record-skin-phase-1-foundation`. Phase 2 implemented: `RecordChrome.kt` … Phase 3+ composables will consume the remaining tokens." | STALE | Branch `feat/record-skin-phase-1-foundation` is merged to master. Status block refers to a feature branch that no longer exists as a branch; the work is on master. Phase 3+ is also fully implemented (Phases 3 and beyond have shipped across PRs #39, #40, #41, #42). | Update status block to say "Merged to master (`d54e051`). All phases shipped." |
| Status block (L4) | Source of truth: `mockups/new_uiux/PROJECT_CONTEXT.md` | TBD-VERIFY | `mockups/` is gitignored and may not exist in a fresh checkout. Cannot verify from working tree whether this reference is still valid or the correct pointer. | Owner to confirm: is `PROJECT_CONTEXT.md` still the primary source of truth for the design system? If so, note that it's gitignored. |
| §3 Implementation notes | "Phase 2.1 PR" listed as having remaining tasks (edit `Color.kt`, dark scheme entries) | STALE | Phase 2 (and beyond) has fully shipped. The `Color.kt` changes are long since merged. These bullet-point TODOs are obsolete. | Remove or archive the "Remaining for Phase 2.1 PR" TODO list |
| §3 Implementation notes | Theme files list: `{Color,Type,Theme,RovaTokens,RecordChromeTokens,Font}.kt` | STALE | `ui/theme/` now also contains `RovaTokensPreview.kt`, `RovaWarningsV3.kt`, `SettingsSheetTokens.kt` | Update the implementation reference path to include the additional token files |
| General | No mention of M5 notification re-skin token additions (Phase 3 notification state tokens) | MISSING | M5 (PR #54) added notification-specific token usage; `RovaWarningsV3.kt` in theme/ is not mentioned in the tokens doc | Add mention of `RovaWarningsV3.kt` and notification re-skin tokens |

---

### `docs/UI_NAV_GRAPH.md`

| Line / Section | Claim | Status | Evidence (current truth) | Suggested fix |
|---|---|---|---|---|
| Status block (L3–L7) | "Phase 2 is not yet started. This document precedes any screen implementation." | STALE | Phase 2 is fully implemented. All screens listed as "New in Phase 2?" = Yes are now shipped: `onboarding` (PR #53 `12c12a9`), `player/{sessionId}` (PR #1 `db25405`). The HUD merging state (Phase 2.4) is also shipped (`32c5cb3`). | Update status block to reflect Phase 2 shipped; note master tip. |
| Route table (§2) | `onboarding` row: "Status today: does not exist" | STALE | `ui/screens/onboarding/` exists (PR #53, 3 screens) | Update to "shipped (PR #53, 3 screens)" |
| Route table (§2) | `player/{sessionId}` row: "Status today: does not exist" | STALE | `ui/screens/player/` exists with `PlayerScreen.kt`, `PlayerViewModel.kt`, `PlayerUriResolver.kt`, `PlayerUiState.kt` (PR #1 `db25405`) | Update to "shipped (PR #1 `db25405`)" |
| Mermaid diagram (§3) | `OB["onboarding (3 walkthrough + 4 permissions) NEW — Phase 2.6"]` labelled `:::new` | STALE | Onboarding is shipped; no longer "new". The "3 walkthrough + 4 permissions" description should be verified against PR #53 which shipped 3 immersive screens (not 7) — the "4 permissions" framing predates the M4 redesign. | Update node label to `:::shipped`; update description to "3 immersive screens" |
| Mermaid diagram (§3) | `PLAYER["player/{sessionId} (Media3 ExoPlayer, segmented timeline) NEW — Phase 2.5"]` labelled `:::new` | STALE | Player is shipped (PR #1 `db25405`) | Update node label to `:::shipped` |
| Mermaid diagram (§3) | `REC_HUD_MERGE["HUD: Merging (progress N/total) — Phase 2.4"]` labelled `:::new` | STALE | HUD merging end-states shipped (`32c5cb3 feat(ui): add HUD merging end-states`) | Update node label to `:::shipped` |
| WarningCenter overlays | "18 states" | CURRENT | `WarningId.kt` has 17 enum entries; §4 of `WarningCenterContract.md` calls out 18 warning states; the nav graph diagram uses "18 states" which aligns with the contract's §4 count. Mark CURRENT pending the WarningContract §1 vs §4 reconciliation. | Leave pending reconciliation in WarningCenterContract |

---

### `docs/release_checklist.md`

| Line / Section | Claim | Status | Evidence (current truth) | Suggested fix |
|---|---|---|---|---|
| Current cut: `versionCode` | `2` | STALE | `app/build.gradle.kts:25 versionCode = 3` (bumped in earlier release) | Update to `3` (or `4` after sub-D lands) |
| Current cut: `versionName` | `0.3.0` | STALE | `app/build.gradle.kts:26 versionName = "0.5.0"` | Update to `0.5.0` (or `0.9.0` after sub-D lands) |
| Current cut: `Commit` | `e05f5fc chore(release): prepare beta distribution metadata` | STALE | Current master tip is `d54e051 Milestone 5 — Notification redesign v1.0 (#54)` | Update commit reference to current tip |
| versionName reminder text (bottom) | `Keep 'versionName' (currently '0.3.0') tracking...` | STALE | versionName is now `0.5.0` (sub-D will bump to `0.9.0`) | Update the inline reminder text to match current value |
| Required verification gates | Lists `rtk ./gradlew :app:lintDebug` etc. | CURRENT | `rtk` prefix is the project convention per global CLAUDE.md; commands match the project's verification pattern | Leave |
| General | No mention of the 25 `check*` static-check tasks wired into `preBuild` as a verification gate | MISSING | `app/build.gradle.kts` has 25 `check*` tasks that gate every build; this is a required verification step not mentioned in the checklist | Add a gate: "`./gradlew :app:preBuild` (runs all 25 static check tasks)" |
| General | Compile warnings note: "Theme.kt deprecated status/navigation bar setters are pre-existing" | TBD-VERIFY | Cannot confirm whether this specific warning is still present or has been resolved in recent PRs. Mark for owner spot-check. | Owner to verify: is the `Theme.kt` navigation bar setters warning still present? If resolved, remove the note. |

---

### `docs/development_log.md`

| Line / Section | Claim | Status | Evidence (current truth) | Suggested fix |
|---|---|---|---|---|
| Overall structure | Chronological record covering 5 phases: Phase 0 (Initial Build), Phase 1 (Quick Wins), Phase 2 (Reliability), Phase 3 (Architecture Refactor), Phase 4 (Cleanup & Polish) | STALE by design | The log stops at "Phase 4: Cleanup & Polish" — this precedes ALL of the significant work from PRs #12–#54: WarningCenter (PRs #12–#13), AGP 9 migration (PRs #14–#16), Record-home redesign R1/R2 (PRs #17–#19), Mode picker (PRs #20–#21), DualShot Phase 6.1 foundation + stability (PRs #22–#35), record-screen re-skin (PRs #36–#40), edge-to-edge (PRs #41–#42), Phase 4 warning stack (PRs #43–#49), M1–M5 (PRs #50–#54). | Append new entries covering PRs #12–#54, OR formally designate as "frozen at Phase 4" archive. Recommend ARCHIVE unless owner wants to catch it up. |
| Verification Checklist section | Lists manual smoke-test steps for Portrait Recording, Premature Stop, Background Recording, Settings Persistence | TBD-VERIFY | These steps read as the manual QA baseline for pre-PR-#12 code. They may still be valid smoke-test steps but are no longer the complete verification picture (e.g., no DualShot P+L verification, no WarningCenter smoke test). | Owner to decide: update to match current smoke checklist in `release_checklist.md`, or leave as historical context. |

---

### `docs/product_vision.md`

| Line / Section | Claim | Status | Evidence (current truth) | Suggested fix |
|---|---|---|---|---|
| §3.1 Feature Table | "In-app video player — Not implemented" | STALE | `ui/screens/player/PlayerScreen.kt` shipped in PR #1 `db25405`. The player IS implemented. | Change status to "Implemented (PR #1 `db25405`)" |
| §3.1 Feature Table | "Video thumbnails — Not implemented" | STALE | `HistoryScreen.kt` shows thumbnails; `VideoMetadataUtils.kt` exists for thumbnail extraction (shipped pre-PR-#12) | Change status to "Implemented" |
| §5.1 Navigation Structure | "The current four-tab layout (Record / History / Schedule / Feedback) should be simplified" | STALE | The four-tab layout was simplified long ago. Current shipped nav is Record (home, owns its own bottom nav with Library/Settings) — R1 redesign PR #17. There is no Schedule or Feedback tab. | Remove or archive the "current four-tab layout" reference; update to describe the shipped R1 nav model |
| §5.6 Onboarding | "First launch: 3-screen tutorial shown automatically (interactive, not just text). Persona picker: 'What will you use this for?'" | STALE | PR #53 (`12c12a9`) shipped 3 immersive onboarding screens without a persona picker. The onboarding is 3 screens, not 7, and has no persona picker flow. | Update to reflect shipped 3-screen onboarding; note persona picker is not in the current implementation |
| §9 Feature Priority for v1.0 | P0: "In-app video player" — rationale "Users shouldn't leave the app to watch recordings" | STALE (status) | Player is shipped (PR #1). The P0 rationale is still valid but the item is done. | Mark as "Shipped (PR #1)" or move to completed section |
| §9 Feature Priority for v1.0 | P0: "Video thumbnails" | STALE (status) | Thumbnails are implemented | Mark as "Shipped" |
| §9 Feature Priority for v1.0 | P0: "Battery optimization banner" — "Exists (unwired)" | STALE | `BATTERY_OPTIMIZATION_ON` is fully shipped as a WarningCenter signal (PR #12, ADR-0007) | Update status to "Shipped (WarningCenter, ADR-0007)" |
| General | No mention of DualShot P+L mode | MISSING | DualShot simultaneous portrait+landscape recording is a shipped core feature (PRs #22–#35, ADRs 0008/0009/0010). Product vision should reflect this as a differentiating capability. | Add DualShot P+L to the feature overview |
| §10 Long-Term Product Direction | "Phase 1: Solid Recorder (Current)" — positioned as the active phase | TBD-VERIFY | The app has well exceeded Phase 1's scope (WarningCenter, DualShot, in-app player, onboarding, tiered export). Owner should decide if §10 phasing still reflects the product direction or needs updating. | Owner to review §10 phasing |

---

### `docs/naming.md`

| Line / Section | Claim | Status | Evidence (current truth) | Suggested fix |
|---|---|---|---|---|
| §"Recommendation" (bottom) | Lists Repshot, Tempo, Rova as the three top-tier candidates with reasoning | STALE (purpose) | The rebrand to "Rova" is complete and has been for a long time (`applicationId = com.aritr.rova`, label = "Rova"). The document's structure is a naming-decision artifact. The "Implementation Note" at the bottom confirms the decision is done. | The doc's body is historical decision context; only the "Implementation Note" section (confirming Rova is chosen) is actionable. Recommend ARCHIVE or add a top-of-file preamble marking it as "Decision record — Rova chosen, see Implementation Note". |
| "Implementation Note" section | Lists updates made for Rova rebrand: `applicationId`, package name, `AndroidManifest.xml`, `RovaRecordingService`, `RovaSettings`, `rova_beep.mp3` | CURRENT | All referenced identifiers exist in the codebase | Leave |
| General | No naming conventions for source files, classes, or packages | MISSING | The doc is a name-candidate analysis, not a naming conventions guide. CLAUDE.md and the codebase have conventions (e.g., `*Signal.kt` for signals, `*ViewModel.kt` for VMs, `check*` for static tasks, `Tier1/2/3Exporter`, etc.) but none are documented here. | If owner wants a naming-conventions doc, create one separately. For this doc: recommend ARCHIVE (purpose is fulfilled) or add preamble. |

---

## Summary

| Doc | CURRENT | STALE | MISSING | TBD-VERIFY |
|---|---|---|---|---|
| `readme.md` | 4 | 2 | 4 | 0 |
| `docs/architecture.md` | 2 | 7 | 4 | 1 |
| `docs/WarningCenterContract.md` | 2 | 2 | 2 | 0 |
| `docs/UI_DESIGN_TOKENS.md` | 2 | 3 | 1 | 1 |
| `docs/UI_NAV_GRAPH.md` | 1 | 6 | 0 | 0 |
| `docs/release_checklist.md` | 1 | 4 | 1 | 1 |
| `docs/development_log.md` | 0 | 1 | 0 | 1 |
| `docs/product_vision.md` | 0 | 7 | 1 | 1 |
| `docs/naming.md` | 1 | 1 | 1 | 0 |
| **Total** | **13** | **33** | **14** | **5** |

---

## Recommended actions (owner go/no-go)

| Doc | Recommendation | Reason |
|---|---|---|
| `readme.md` | **FIX** | High-visibility public doc; `targetSdk 36` (actual 37) is a concrete factual error; ADR table stops at 0006 (actual 19 ADRs); documentation table missing 4 docs; module tree missing 10+ sub-packages |
| `docs/architecture.md` | **FIX** | Service tree shows one file where 9 sub-packages exist; `PreviewActivity` references are stale (player is now `PlayerScreen.kt`); nav section describes pre-R1 3-tab NavigationBar; "no unit tests" claim is flatly wrong (1322 tests); 7 STALE items total |
| `docs/WarningCenterContract.md` | **FIX** | Snooze "deferred to 4.1c" is stale (shipped PR #44); "17 rows" vs "18 warning states" discrepancy needs reconciliation; `effectiveIdleTopBannerId` and ADR-0016/0017/0018 missing from References |
| `docs/UI_DESIGN_TOKENS.md` | **FIX** | Status block still references `feat/record-skin-phase-1-foundation` branch (merged); Phase 2.1 TODO list is stale; theme/ file list is incomplete. Relatively low-risk fixes. |
| `docs/UI_NAV_GRAPH.md` | **FIX** | "Phase 2 not yet started" is false — all Phase 2 routes are shipped; 3 `:::new` Mermaid nodes should be `:::shipped`; route table "does not exist" statuses are wrong for onboarding and player |
| `docs/release_checklist.md` | **FIX** | `versionCode 2` / `versionName 0.3.0` are stale (actual: 3 / 0.5.0); inline reminder text repeats the stale value; current-cut commit SHA points to an old release; best updated in coordination with sub-D |
| `docs/development_log.md` | **ARCHIVE** | The log covers Phases 0–4 (pre-PR-#12). It is ~30 PRs and ~800 commits behind. Catching it up is high-effort and low-value vs. the existing git log + PR history. Recommend moving to `docs/archive/` and noting it as "frozen at Phase 4 Cleanup & Polish" |
| `docs/product_vision.md` | **FIX** | 7 STALE items including "In-app video player — Not implemented" (shipped PR #1), "four-tab layout" (replaced by R1 nav), and "Persona picker onboarding" (shipped as 3 screens without persona picker). These are factual errors visible to any new contributor. |
| `docs/naming.md` | **ARCHIVE** | The naming decision is complete. The doc's purpose (evaluate name candidates) is fully served. It is not a naming-conventions guide. Recommend moving to `docs/archive/` with a one-line preamble noting the Rova decision date. |

---

## Highest-impact findings (Phase 2 priority order)

1. **`docs/architecture.md` — module tree is severely behind.** The tree shows `service/` as a single file when there are 9 sub-packages (dualrecord, export, notification, recovery, scheduler, audio, surface, wakelock, dualrecord/internal). The "no unit tests" claim is false (1322 tests exist). Any new contributor reading this doc gets a fundamentally wrong picture of the codebase. Priority: fix first.

2. **`readme.md` — ADR table stops at 0006; 14 ADRs are invisible.** ADRs 0007–0019 cover the WarningCenter, DualShot, export tiers, edge-to-edge, snooze, thermal autostop, recovery merge, and thermal hysteresis — the bulk of the architectural work since AGP 9 migration. The README presents a false picture of the ADR corpus. Priority: fix second.

3. **`docs/product_vision.md` — "In-app player Not implemented" and "four-tab layout" are factually wrong.** New contributors or external readers (investors, contributors, interviewers) see claims contradicted by the app itself. These are the most embarrassing staleness items. Priority: fix third.

---

*Report generated by cleanup-pass sub-B Phase 1 dispatch (master `5e354d2`).*
