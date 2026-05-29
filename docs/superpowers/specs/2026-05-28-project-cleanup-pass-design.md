# Project cleanup pass — design

**Date:** 2026-05-28
**Baseline:** master tip `d54e051` (post-M5, 1322 tests / 0-0-0, lint 61W + 1H + 0E, 19 ADRs)
**Type:** Meta-spec — one design doc covering a stack of 6 sequential sub-project PRs
**Execution skill:** `superpowers:subagent-driven-development` (per implementation plan, to be authored after this spec is approved)

---

## 1. Goal

One coordinated cleanup pass that brings repo state, documentation, version metadata, and privacy policy in sync with the post-M5 master tip, shipped as a stack of 6 sequential PRs to `rova-android` plus 1 PR to the sibling `rova-privacy` repository.

The pass does **not** introduce new features, refactor production code, or touch architectural invariants. It is pure hygiene + documentation reconciliation.

---

## 2. Scope

| # | Sub-project | Output | PR count |
|---|---|---|---|
| **F-pre** | Repo hygiene (pre-work that unblocks A) | Delete 6 stale roadmap drafts (gitignored, never tracked); delete `nul` (PowerShell redirect artifact); commit 2 M5 design+plan docs that slipped from PR #54; gitignore tweaks (`.claude/`, `.kotlin/`, `.mcp.json`, `.github/hooks/`) | 1 |
| **A** | Live roadmap refresh | Rewrite `NEW_UI_BACKEND_REPLAN.md` in place; add `Status (live, 2026-05-28)` callout on `ROADMAP_v6.md`; add 1-line preamble on `ROADMAP_v5.md` clarifying its role as v6's content base | 1 |
| **B** | Reference doc audit (triage-first) | Phase 1 drift report; Phase 2 authorized fixes across 9 docs: `readme.md`, `docs/architecture.md`, `docs/WarningCenterContract.md`, `docs/UI_DESIGN_TOKENS.md`, `docs/UI_NAV_GRAPH.md`, `docs/release_checklist.md`, `docs/development_log.md`, `docs/product_vision.md`, `docs/naming.md` | 1 (drift report committed first to feature branch; owner reviews; approved fixes added as per-doc subcommits; one final PR contains both phases) |
| **C** | CHANGELOG creation | New `CHANGELOG.md` in root (Keep a Changelog format) — backfill 0.3.0 / 0.4.0 / 0.5.0 entries from git history, add 0.9.0 current entry | 1 |
| **D** | Version bump + tag | `app/build.gradle.kts`: versionName `0.5.0 → 0.9.0`, versionCode `3 → 4`; annotated git tag `v0.9.0` placed at post-stack HEAD (after D's PR merges) | 1 (+ tag, separate operation) |
| **E** | Privacy policy review (triage-first) | Clone `aritr-codes/rova-privacy` to temp dir; produce drift report against 8 data-handling surfaces (Inter font / Google Fonts is highest priority); authorize fixes; push to rova-privacy repo with explicit per-commit auth | 1 PR to `rova-privacy` repo (push deferred to explicit owner authorization) |

**Total: 5 PRs to `rova-android` + 1 PR to `rova-privacy`.** (F-pre, A, B, C, D = 5 separate PRs. D's PR includes the version bump; the `v0.9.0` tag is applied as a separate operation after D's PR merges.)

---

## 3. Out of scope (with reasons)

| Item | Reason |
|---|---|
| 19 ADRs in `docs/adr/` | CLAUDE.md mandates owner sign-off; `ADR ↔ check* task` chain is load-bearing |
| 60+ files in `docs/superpowers/{specs,plans}/` | Frozen historical artifacts; each was accurate at execution time. Do not retrofit. |
| `gradle_*.log` files in repo root | Per CLAUDE.md "working tree is intentionally noisy" — keep visible as untracked reminders |
| `CLAUDE.md` | Intentionally untracked per git history (committed `3b63465 docs: add CLAUDE.md`, dropped `7593981 chore: drop unused local guidance`); per-developer local file |
| Memory files at `~/.claude/projects/.../memory/` | Owner-only per project memory convention |
| Backfilling `v0.5.0` / `v0.6.0` / `v0.7.0` tags | Would imply releases that didn't actually ship at those times; mildly fictional |
| Promoting `ROADMAP_v6.md` to self-contained (inlining v5 content) | Transcription risk on reliability invariants encoded in v5's checkbox list; defer to dedicated PR if ever needed |
| Renaming `NEW_UI_BACKEND_REPLAN.md` to a less event-y name | Filename is established reference (memory, CLAUDE.md, my prior session answers all point here); rename cost without functional gain |
| Per-doc PR for sub-B fixes | Bundled PR with per-doc subcommits gives the same git-blame clarity with less PR overhead |
| Any production code edits (refactors, new features, dependency bumps beyond version metadata) | Out of cleanup-pass scope |

---

## 4. Sequencing + dependencies

```
F-pre (hygiene that unblocks A) ─→ A ─→ B ─→ C ─→ D ─→ tag v0.9.0 post-merge
                                                  │
                                      E (parallel) ┘
```

| Step | Depends on | Why |
|---|---|---|
| F-pre | (none) | Independent hygiene; deletes stale roadmap files **before** sub-A rewrites NEW_UI_BACKEND_REPLAN so the rewrite doesn't reference now-deleted files |
| A | F-pre | Replan rewrite must not reference files deleted in F-pre |
| B | A | README / architecture.md audit checks need current roadmap state to compare against |
| C | B | CHANGELOG entry can reference any user-visible doc-driven changes B introduces |
| D | C | Version bump commit includes the CHANGELOG addition (the 0.9.0 entry references the version bump itself) |
| E | (none) | Different repo; runs in parallel with A → D |
| Tag `v0.9.0` | D's PR merged to master | Tag fires at the actual release moment, not at draft time |

---

## 5. Sub-project F-pre — Repo hygiene (pre-work)

### Files deleted (6)

```
ROADMAP.md            # gitignored, never tracked — local-only draft
ROADMAP_v2.md         # gitignored, never tracked — local-only draft
ROADMAP_v3.md         # gitignored, never tracked — local-only draft
ROADMAP_v4.md         # gitignored, never tracked — local-only draft
UI_ROADMAP.md         # gitignored, never tracked — local-only draft
ROADMAP_REVIEW.md     # gitignored, never tracked — local-only draft
nul                   # accidental PowerShell redirect artifact
```

**Note:** `ROADMAP_v5.md` is **not** deleted — it stays in-tree as v6's content base per the V2 decision in §6.

These are working-tree deletions only (the 6 roadmap files were never tracked). `git rm` is not needed — plain `rm` (or PowerShell `Remove-Item`) suffices. The existing `.gitignore` entries for the deleted roadmap files (lines 47–51) stay in place; they will match no files after deletion but document the original intent.

### Files committed (2)

```
docs/superpowers/plans/2026-05-27-notification-redesign-v1.md
docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md
```

These are M5 design+plan artifacts that slipped from PR #54. Every other slice in `docs/superpowers/{specs,plans}/` is tracked. Commit message: `docs(notif): backfill M5 design+plan slipped from PR #54`.

### `.gitignore` additions

Append the following section to `.gitignore`:

```
# Per-developer tool state
.claude/
.kotlin/
.mcp.json
.github/hooks/
```

**Do NOT add:** `gradle_*.log` (intentionally visible per CLAUDE.md), `CLAUDE.md` (intentionally untracked, no gitignore needed), `.github/` (workflows directory if added later — only `.github/hooks/` is per-developer).

### Verification

- `git status --porcelain` after F-pre shows: only the 2 newly-committed M5 docs (as already-committed) and the `.gitignore` modification staged for commit; no roadmap drafts, no `nul`, no `.claude/`, `.kotlin/`, `.mcp.json`, `.github/hooks/`
- `ls -la *.md` in root shows only: `NEW_UI_BACKEND_REPLAN.md`, `ROADMAP_v5.md`, `ROADMAP_v6.md`, `readme.md`, `CLAUDE.md`, `CHANGELOG.md` (after sub-C lands)

---

## 6. Sub-project A — Live roadmap refresh

### A.1 `NEW_UI_BACKEND_REPLAN.md` — full rewrite

**Current state:** Dated 2026-05-07. ~3 weeks stale relative to master. Has 2 lines of uncommitted dirty edits (trivial accuracy fixes annotating that `BatteryOptimizationBanner.kt` was removed in PR #12) — these are absorbed by the rewrite.

**Rewrite scope:**

| Section | Action |
|---|---|
| Header | Date `2026-05-07` → `2026-05-28`; commit-of-record `4b32b0c` → `d54e051` |
| §1.1 Git position | Update to current master tip; remove obsolete untracked drift inventory |
| §1.2 What UI slices are already in app code | Promote shipped slices: R1+R2 (#17–#19), Mode picker (#20–#21), DualShot Phase 6.1 (#22–#24), DualShot stability stack (#25–#35), Record screen re-skin (#36–#37, #39, #40), edge-to-edge (#41–#42), Phase 4 v3 warning stack (#43–#49), M1–M5 (#50–#54). Mark each with PR # + commit SHA. |
| §1.3 Old vs new mockup directions | Keep as-is — analysis is correct |
| §1.4 Backend phase status | Verified content still accurate; refresh code refs against current master tip |
| §2–6 (forward-looking roadmap) | Trim completed slices; surface remaining backlog: #4 in-app player, #11 HUD merging states, #1 Mode preset seed; parked items: Variant D (2027 per ADR-0012), Variant A (mid-segment thermal), Variant G ("Continue without saving"), Export sheet polish (deferred from M5), M4 leftovers (theme switcher, tour-replay, full i18n, per-perm JIT split), launcher webp legacy fallback (API 24-25) |

### A.2 `ROADMAP_v6.md` — light-touch status callout

**Insertion point:** Top of file, immediately after the title (line 1) and the surgical-revision preamble (lines 3–4), before the `## What Changed from v5` section.

**New section:**

```markdown
## Status (live, 2026-05-28)

| | |
|---|---|
| Master tip | `d54e051 Milestone 5 — Notification redesign v1.0 (#54)` |
| Test baseline | 1322 / 0-0-0 (failures / errors / skipped) |
| Lint baseline | 61 warnings + 1 hint + 0 errors |
| ADRs | 0001–0019 (with slot 0010 used for two siblings: canonical-uv-frame + crop-divergence) |
| Static checks | 25 `check*` tasks wired into `preBuild` |

### Milestones shipped (post-AGP-9-migration)

| Milestone | PR | SHA | Summary |
|---|---|---|---|
| M5 | #54 | `d54e051` | Notification redesign v1.0 — semantic + a11y + visual skin + clip-dots row + Chronometer + branded launcher |
| M4 | #53 | `12c12a9` | Onboarding redesign — 7 screens → 3 immersive |
| M3 | #52 | `e1e121d` | Asymmetric thermal hysteresis (ADR-0019) |
| M2 | #51 | `0347880` | Merge reliability bundle — retry + preflight + notification + library rows |
| M1 | #50 | `b3fad71` | DualShot frame polish — blur + halved scrim |

### UI/feature roadmap backlog

See `NEW_UI_BACKEND_REPLAN.md` for the active UI/feature roadmap. v6 below is the reliability backbone.

### Authority

Phase-level checkboxes in v5 and v6 below remain authoritative. Their completion signal is: **does the matching `check*` task in `app/build.gradle.kts` exist and pass?** If yes → done. This roadmap does not duplicate that signal as ticked checkboxes.
```

The existing `## What Changed from v5` section and everything below remain untouched.

### A.3 `ROADMAP_v5.md` — 1-line preamble

**Insertion point:** Top of file, before any existing content.

**New text:**

```markdown
> **Note:** v5 is preserved in-tree as the content base for `ROADMAP_v6.md`'s surgical-diff structure. **Do not edit v5 — see v6 for the active diff.** A future PR may promote v6 to self-contained and archive v5; until then, v5 is read-only.

---
```

Mirror reciprocal note in v6's preamble (line 3) is already implicit via "Surgical revision of `ROADMAP_v5.md`" — no edit needed in v6 for this.

---

## 7. Sub-project B — Reference doc audit

### Phase 1 — Drift report

**Output:** `docs/superpowers/specs/2026-05-28-reference-doc-drift-report.md`

**Format:** Per-doc section, each containing:

```
## <doc path>

| Line / Section | Claim in doc | Status | Evidence (current truth) |
|---|---|---|---|
| L10-12  | versionName 0.5.0, targetSdk 36 | STALE | build.gradle.kts:25 (versionName="0.5.0" — bump pending sub-D; targetSdk = 37 per L34) |
| L44     | "in-app player (Phase 2.5)" listed as feature | TBD-VERIFY | Memory says NEXT; code check pending |
| ...     | ...                            | ...    | ... |
```

Status values: `CURRENT`, `STALE`, `MISSING`, `TBD-VERIFY`.

### Scope (9 docs)

| # | Doc | Audit priority | Notes |
|---|---|---|---|
| 1 | `readme.md` | HIGH | versionName + targetSdk + ADR list (only 0001-0006 listed; should be 0001-0019); no mention of M1-M5; **note**: README's "in-app player (Phase 2.5)" claim is actually correct — player shipped in commit `db25405` PR #1. Memory `project_current_state.md` incorrectly lists it as "next" — memory needs a separate owner-authorized correction; this is OUT of sub-B scope (memory is owner-only) but flagged in the drift report for visibility |
| 2 | `docs/architecture.md` | HIGH | Likely pre-M5 / pre-DualShot; cross-reference against `app/src/main/java/com/aritr/rova/` tree |
| 3 | `docs/UI_NAV_GRAPH.md` | HIGH | Onboarding (3-screen), notification taps, recovery routing all changed |
| 4 | `docs/WarningCenterContract.md` | MED | CLAUDE.md says authoritative; verify against Phase 4.2 routing (PR #49) and 4.3 recovery merge (PR #48) |
| 5 | `docs/UI_DESIGN_TOKENS.md` | MED | Inter v4.1 + RecordChromeTokens + notification accent — needs verification |
| 6 | `docs/release_checklist.md` | MED | Sub-D version bump will need this; verify checklist still matches current build/test/lint workflow |
| 7 | `docs/development_log.md` | LOW | Running log; might be archive candidate if abandoned (decide based on last-touched date) |
| 8 | `docs/product_vision.md` | LOW | Likely stable; quick read |
| 9 | `docs/naming.md` | LOW | Likely stable conventions; quick read |

### Off-limits

- 19 ADRs in `docs/adr/` — owner sign-off required per CLAUDE.md
- 60+ files in `docs/superpowers/{specs,plans}/` — frozen historical artifacts
- 3 mockup READMEs in `mockups/` — directory is fully gitignored (`git ls-files mockups/` returned empty); no audit needed

### Phase 2 — Authorized fixes

User reviews the drift report; per-doc go/no-go decision. I execute approved set in one bundled PR with per-doc subcommits (e.g., `docs(readme): refresh ADR table 0001-0019, drop 'in-app player' shipped claim`, `docs(arch): refresh module shape post-DualShot + post-M5`, ...).

### Verification

- All approved doc edits land in one PR
- `./gradlew :app:lintDebug` still green (no code touched, but verify CI)
- Spot-check 2–3 fixed docs for accuracy by re-reading + cross-referencing

---

## 8. Sub-project C — CHANGELOG creation

### File

`CHANGELOG.md` (root of repo, alongside `readme.md`)

### Format

[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) — newest version at top, sections per version: `Added` / `Changed` / `Deprecated` / `Removed` / `Fixed` / `Security`.

### Entries (newest first)

#### `## [0.9.0] — 2026-05-28`

**Sections:**
- **Added** — DualShot P+L simultaneous-recording mode (entire `service/dualrecord/` subsystem, ADR-0008/0009/0010 sibling pair); in-app onboarding (3 immersive screens replacing the previous lazy first-record prompt); semantic + branded notification system (4 notification states, clip-dots row, Chronometer live countdown, Inter font, custom launcher icon); thermal auto-stop system (`PowerManager.OnThermalStatusChangedListener` with asymmetric hysteresis per ADR-0019); storage-full auto-stop with reason-agnostic echo banner (ADR-0015); recovery merge architecture with retry + classifier preflight (ADRs 0017, 0018); WarningCenter v3 re-skin with snooze persistence + 18 warning states (ADRs 0013, 0014, 0016); edge-to-edge immersive record-home (ADRs 0011, 0012); record-home Mode picker (Portrait / Landscape / P+L); record-screen pixel-faithful re-skin with Inter typography system + RecordChromeTokens; camera-guides framing (grid + focus brackets, all modes, toggle).
- **Changed** — Build toolchain: AGP 9.2.1 / Gradle 9.4.1 / Kotlin 2.2.10 / targetSdk 37; UI redesigns across record-home (R1 + R2), settings sheet, library/history surface; warning surface from inline strip → modal sheet + collapse-to-chip (ADR-0007 supersedes pre-PR-#12 `BatteryOptimizationBanner`); launcher icon replaced from stock Android Studio template with branded recording-dot motif.
- **Fixed** — DualShot PORTRAIT-stretch architectural fix (ADR-0009 4:3 source); 10+ DualShot stability fixes (render threading, FBO-ring, fence-sync, EglRouter ANR, aspect-swap, encoder/audio teardown, P+L surface replay on service reconnect); warning dismiss persistence; merge reliability hardening.
- **Removed** — `BatteryOptimizationBanner.kt` (replaced by WarningCenter row `BATTERY_OPTIMIZATION_ON`, PR #12 era).

#### `## [0.5.0]` (untagged — code version, never released as a git tag)

**Note:** `versionName = 0.5.0` was set in commit `53d246b chore(repo): bump readme to 0.5.0 + extend gitignore (#2)`. No git tag was ever created for 0.5.0. Scope = git log between `f57e175` (v0.4.0) and `ae3516a` (Phase 4.1b — the last commit before AGP 9 migration which is 0.9.0 territory).

**Sections** (derived from git log `f57e175..ae3516a`):
- **Added** — Phase 2.5 in-app video player (PR #1, commit `db25405`) with manifest-driven Media3 surface, segmented clip timeline, ±10s seek, auto-pause on background; Phase 2.6 onboarding flow (PR #4, `21842f9`); 6 leaf signals — notifications-permission read (#5), thermal status read (#6), power read (#7), camera-state (#10), exact-alarm revocation (#9); FGS notification copy split into 4 states (PR #8, `2ef33ba`); HUD merging end-states (`32c5cb3`); library empty-state re-skin (`3499e3d`); library recording-settings popup (`e4a5847`); app-settings re-skin (`ed2d4eb`); Phase 2 design tokens (`5d23d18`); UI-pending settings keys (`e5bb225`); WarningCenter Phase 4.1 + 4.1b (PRs #12, #13) — 17 warning states with single-flat precedence ordering (ADR-0007), Start-gating, snooze.
- **Changed** — Recovery: hide finalized sessions from recovery cards (`2b97f8c`); resume after inter-clip wait fix (`00facb7`); bottom nav locked during merge (`014d985`); recording-duration persistence fix (`aa06028`).
- **Fixed** — Post-2.5 player hardening (PR #3, `35558a5`).

#### `## [0.4.0] — 2026-03-07`

From tag `v0.4.0` message: "Library thumbnails, battery optimization, project cleanup."
- **Added** — Real video thumbnails in History library; battery optimization prompt + Doze-mode detection.
- **Changed** — Project cleanup pass.

#### `## [0.3.0] — 2026-03-07`

From tag `v0.3.0` message: "Architecture refactor + reliability hardening. First clean commit. Core periodic recording loop is end-to-end functional. RecordViewModel established, LoomAppLegacy removed, 10+ reliability fixes applied across the service and merge pipeline."
- **Added** — `RecordViewModel`; end-to-end periodic recording loop.
- **Changed** — Architecture refactor; reliability hardening across service + merge pipeline.
- **Removed** — `LoomAppLegacy`.

### Header note

```markdown
# Changelog

All notable changes to Rova are documented here. This project adheres to
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/).

Commit-level detail lives in `git log` and on GitHub PRs (https://github.com/aritr-codes/rova-android/pulls).
ADR-level invariants live in `docs/adr/`. Roadmaps live in `ROADMAP_v6.md` (reliability) and
`NEW_UI_BACKEND_REPLAN.md` (UI / feature).
```

---

## 9. Sub-project D — Version bump

### Files

| File | Change |
|---|---|
| `app/build.gradle.kts:25` | `versionCode = 3` → `versionCode = 4` |
| `app/build.gradle.kts:26` | `versionName = "0.5.0"` → `versionName = "0.9.0"` |

### Tag

Placed **after** D's PR merges to master:

```bash
git tag -a v0.9.0 -m "v0.9.0 — DualShot P+L, M1-M5 milestones, Phase 4 v3 warning stack, AGP 9 migration, edge-to-edge HUD, onboarding 3-screen, notification redesign v1, Inter font, branded launcher" <merge-commit-sha>
git push origin v0.9.0
```

If keystore is configured for tag signing (`git config tag.gpgSign true` or `--sign` flag), use signed tag; otherwise plain annotated tag. **Push step requires explicit owner authorization per memory convention `gh pr merge is owner-only`.**

### Doc cross-references

No other code or config changes. Doc-side version references (`readme.md`, `docs/release_checklist.md`) are handled by sub-B's audit pass.

### Verification

- `./gradlew :app:lintDebug` green
- `./gradlew :app:testDebugUnitTest` green (1322 tests / 0-0-0 baseline preserved)
- `./gradlew :app:assembleDebug` produces `app-debug.apk` reporting versionName 0.9.0 (verify with `aapt dump badging`)

---

## 10. Sub-project E — Privacy policy review

### Steps

1. **Clone** to a temp dir outside the rova-android tree:
   ```bash
   git clone https://github.com/aritr-codes/rova-privacy.git C:\Users\HP\AppData\Local\Temp\rova-privacy-audit\
   ```

2. **Read** the current policy text. Note its structure (sections, headings, claims about data flow).

3. **Produce drift report** at `docs/superpowers/specs/2026-05-28-privacy-policy-drift-report.md` (in rova-android tree, gitignored if needed) against 8 data-handling surfaces:

   | # | Surface | Why it matters | Priority |
   |---|---|---|---|
   | 1 | **Inter font via Google Fonts provider** (commit `c37e081`, M5 Phase 1 follow-on) | NEW network call — uses Android Downloadable Fonts API. Google's privacy practices apply when the system FontProvider fetches the typeface. **Most likely undisclosed.** | HIGHEST |
   | 2 | M5 notifications redesign | FGS apps with persistent notifications are subject to specific disclosure requirements; content + duration tracking |
   | 3 | DualShot dual-encode (PR #22-#35) | Local-only, but doubles storage footprint per session (two MP4s instead of one); disclosure may need updating |
   | 4 | Thermal sensors (M3 ADR-0019, `PowerManager.OnThermalStatusChangedListener`) | Read-only telemetry, never persisted or transmitted, but disclosure may be required as a "sensor data" item |
   | 5 | Recovery scan (filesystem + manifest reads on cold launch) | Pre-existing but wording may not reflect current ADR 0005/0006 surface |
   | 6 | Tiered public export (`Tier1/2/3Exporter`) | Pre-existing but writes to public Movies dir (Tier 1+ MediaStore); wording check |
   | 7 | Foreground service types (`FOREGROUND_SERVICE_CAMERA` + `FOREGROUND_SERVICE_MICROPHONE`) | API 30+ FGS type declarations; disclosure-relevant |
   | 8 | `SCHEDULE_EXACT_ALARM` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | User-facing permissions; should be listed |

4. **User reviews drift report**; per-surface go/no-go decision.

5. **Draft edits** locally in cloned repo (one commit per surface for clean review).

6. **User reviews edits** by reading the drafted commits in the cloned repo.

7. **Push to rova-privacy repo** with explicit per-commit auth from owner. Open PR via `gh pr create` if owner authorizes that flow; otherwise push to a branch and let owner open PR via web UI.

### Verification

- Cloned repo state on disk is clean (`git status` shows only the planned commits)
- Drift report is reviewable as a standalone doc
- No push to `rova-privacy` without explicit per-commit auth

---

## 11. Review gates per sub-project

Per project's iterative review-gate cycle convention (memory `feedback_review_gate_cycle.md` — strict NO-GO/GO per phase, no overlap):

| Sub-project | Pre-execution gate | Post-execution gate |
|---|---|---|
| F-pre | Owner reviews the drafted `rm` + commit + `.gitignore` patch in a planning message | `git status` clean except for staged sub-A work; `git diff` shows expected hygiene changes only |
| A | None (decisions locked in this spec) | Lint + test green (no production code touched, but verify CI still passes against doc-only changes); owner spot-checks rewritten `NEW_UI_BACKEND_REPLAN.md` before commit |
| B | **Drift report review by owner — explicit per-doc go/no-go** | Lint + test green; spot-check 2-3 fixed docs |
| C | CHANGELOG draft review by owner | None (doc-only) |
| D | None (decisions locked) | Lint + test + assembleDebug green; aapt verifies versionName 0.9.0 in APK |
| E | **Drift report review by owner — explicit per-surface go/no-go** | None local; **explicit per-commit auth required before push to rova-privacy** |

Each rova-android PR follows the project's existing squash-merge pattern per #14-#54 history.

---

## 12. Risks + mitigations

| # | Risk | Mitigation |
|---|---|---|
| R1 | Sub-B drift report uncovers an ADR-touching issue (e.g., `WarningCenterContract.md` clause that conflicts with current ADR-0013 wording) | Stop; escalate to owner; do not edit ADR without sign-off; sub-B's PR ships only the doc edits that don't require ADR changes |
| R2 | Sub-A's `NEW_UI_BACKEND_REPLAN.md` rewrite changes interpretation of backlog items in a way that diverges from owner's mental model | Owner reviews rewritten file before commit; rewrite is text-only and reversible |
| R3 | Sub-E privacy edits expose a feature the policy doesn't cover but that needs legal opinion before disclosure (e.g., Google Fonts privacy implications) | Surface explicitly in drift report; owner decides whether to defer that surface's disclosure to a later opinion-gated PR |
| R4 | `v0.9.0` tag placed before final cleanup commit lands → tag history is misleading | Tag explicitly fires **after** D's PR merges to master; tag command is run against the merge commit SHA, not against an intermediate state |
| R5 | F-pre deletion of a roadmap file that turns out to be referenced from a still-live tracked doc | Sub-A's rewrite of NEW_UI_BACKEND_REPLAN happens immediately after F-pre; sub-B's audit catches any remaining dangling references in tracked docs; `rg "ROADMAP_v[2-4]\\.md|UI_ROADMAP\\.md|ROADMAP_REVIEW\\.md"` across the tracked-doc set must return zero hits before D's tag is applied |
| R6 | CHANGELOG backfill of 0.5.0 misrepresents what shipped because the version was never an actual release | CHANGELOG header note clarifies that 0.5.0 was a code-version, not a tag; entry derives from git log between `f57e175` and the AGP 9 commit `e62e948`; owner reviews before commit |
| R7 | Sub-E temp-clone of rova-privacy is left behind on disk after cleanup pass | Cleanup step in sub-E plan: `Remove-Item -Recurse -Force C:\Users\HP\AppData\Local\Temp\rova-privacy-audit\` after PR lands |
| R8 | `.github/hooks/` gitignore entry hides a real CI workflow that someone later puts in `.github/hooks/` | `.github/hooks/` is the per-developer Claude Code hooks convention — GitHub Actions live in `.github/workflows/`, which is not ignored; document the distinction in the gitignore comment |

---

## 13. Definition of done

All of the following true:

- [ ] F-pre, A, B, C, D PRs merged to `rova-android` master (5 PRs)
- [ ] Sub-B drift report committed to `docs/superpowers/specs/2026-05-28-reference-doc-drift-report.md`
- [ ] Sub-E drift report committed to `docs/superpowers/specs/2026-05-28-privacy-policy-drift-report.md` (or owner-decided alternative location)
- [ ] Sub-E PR pushed to `rova-privacy` repo with owner authorization
- [ ] Annotated tag `v0.9.0` exists on `aritr-codes/rova-android` and is pushed to origin
- [ ] Master baseline preserved: 1322+ tests / 0-0-0 / lint 61W+1H+0E (tolerant of small drift if any audit-driven fix touches surrounding code)
- [ ] `git status` on rova-android shows clean working tree except for the convention-allowed noise (`gradle_*.log` files, `CLAUDE.md`)
- [ ] Temp clone of rova-privacy removed from local disk
- [ ] Memory file `project_current_state.md` updated to reflect 0.9.0 tip + completed cleanup pass + corrected "in-app player / HUD merging shipped" facts (subject to explicit owner authorization per memory convention — memory edits are owner-gated)

---

## 14. Implementation notes for the plan author

The implementation plan (to be authored after this spec is approved) should:

1. Use one Task per sub-project (F-pre, A, B, C, D, E) — six tasks total
2. Within each task, use bite-sized steps per `superpowers:writing-plans` convention
3. Sub-B and sub-E are two-phase tasks (drift report → user gate → authorized fixes); the plan must make the gate explicit as a separate step
4. Sub-D's tag step is **after** D's PR merges; the plan must call this out so the tag doesn't fire against an unmerged branch
5. F-pre runs first; all other tasks have dependencies as noted in §4
6. Each task ends in a commit (per existing PR-per-slice cadence) followed by `gh pr create` with the project's standard PR template (per #43-#54 commit history)
7. Do not invoke `gh pr merge` from a subagent — `gh pr merge` is owner-only per memory convention
