# Project Cleanup Pass — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute a 6-PR cleanup pass that brings repo state, documentation, version metadata, and privacy policy in sync with master tip `d54e051` (post-M5), with no production-code changes.

**Architecture:** One PR per sub-project (F-pre → A → B → C → D, with E running in parallel against the sibling `rova-privacy` repo). Each PR is small-scope, doc-only or metadata-only. Two of the six tasks (B and E) have a mid-task owner gate (drift report → owner reviews → authorized fixes).

**Tech Stack:** Markdown + git + `.gitignore` regex + `app/build.gradle.kts` Kotlin DSL + GitHub CLI (`gh`). No tests to write (no production code touched). Verification is `lintDebug` / `testDebugUnitTest` / `assembleDebug` baselines remaining green.

**Spec:** [`docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md`](../specs/2026-05-28-project-cleanup-pass-design.md)

---

## File Map

### Files created (during this plan)

| Path | Created in task | Responsibility |
|---|---|---|
| `CHANGELOG.md` (root) | Task 4 (sub-C) | Keep a Changelog release notes; backfilled 0.3.0/0.4.0/0.5.0 + new 0.9.0 |
| `docs/superpowers/specs/2026-05-28-reference-doc-drift-report.md` | Task 3 (sub-B Phase 1) | Per-doc drift table — owner gate before sub-B Phase 2 |
| `docs/superpowers/specs/2026-05-28-privacy-policy-drift-report.md` | Task 6 (sub-E Phase 1) | Per-surface drift table — owner gate before sub-E edits |

### Files modified

| Path | Modified in task | Change scope |
|---|---|---|
| `.gitignore` | Task 1 (sub-F-pre) | Append per-developer tool-state block |
| `NEW_UI_BACKEND_REPLAN.md` | Task 2 (sub-A) | Full rewrite of §1.1, §1.2, §1.4, §2-6; preserve §1.3 |
| `ROADMAP_v6.md` | Task 2 (sub-A) | Insert `## Status (live, 2026-05-28)` section after preamble |
| `ROADMAP_v5.md` | Task 2 (sub-A) | Prepend 1-line preamble block |
| `readme.md` | Task 3 (sub-B Phase 2) | Per-section fixes from drift report (owner-authorized) |
| `docs/architecture.md` | Task 3 (sub-B Phase 2) | Per-section fixes from drift report (owner-authorized) |
| `docs/UI_NAV_GRAPH.md` | Task 3 (sub-B Phase 2) | Per-section fixes from drift report (owner-authorized) |
| `docs/WarningCenterContract.md` | Task 3 (sub-B Phase 2) | Per-section fixes from drift report (owner-authorized) |
| `docs/UI_DESIGN_TOKENS.md` | Task 3 (sub-B Phase 2) | Per-section fixes from drift report (owner-authorized) |
| `docs/release_checklist.md` | Task 3 (sub-B Phase 2) | Per-section fixes from drift report (owner-authorized) |
| `docs/development_log.md` | Task 3 (sub-B Phase 2) | Per-section fixes OR archive recommendation (owner-authorized) |
| `docs/product_vision.md` | Task 3 (sub-B Phase 2) | Per-section fixes from drift report (owner-authorized) |
| `docs/naming.md` | Task 3 (sub-B Phase 2) | Per-section fixes from drift report (owner-authorized) |
| `app/build.gradle.kts:25-26` | Task 5 (sub-D) | `versionCode 3→4`, `versionName "0.5.0"→"0.9.0"` |

### Files deleted (working-tree only — gitignored, never tracked)

| Path | Deleted in task |
|---|---|
| `ROADMAP.md` | Task 1 (sub-F-pre) |
| `ROADMAP_v2.md` | Task 1 (sub-F-pre) |
| `ROADMAP_v3.md` | Task 1 (sub-F-pre) |
| `ROADMAP_v4.md` | Task 1 (sub-F-pre) |
| `UI_ROADMAP.md` | Task 1 (sub-F-pre) |
| `ROADMAP_REVIEW.md` | Task 1 (sub-F-pre) |
| `nul` | Task 1 (sub-F-pre) |

**Note:** `ROADMAP_v5.md` is **NOT** deleted (it stays as v6's content base per V2 decision in spec §6).

### Files committed retroactively (already exist in working tree, untracked)

| Path | Committed in task |
|---|---|
| `docs/superpowers/plans/2026-05-27-notification-redesign-v1.md` | Task 1 (sub-F-pre) |
| `docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md` | Task 1 (sub-F-pre) |

### External repo files (Task 6 / sub-E)

Discovered at clone time — likely `PRIVACY.md` or similar at `aritr-codes/rova-privacy` root. Edited per drift report; pushed only with explicit per-commit owner authorization.

---

## Branch strategy

Each task = one branch off `master` = one PR. Branch names follow project convention (`feat/<scope>` or `chore/<scope>`):

| Task | Branch | Commit prefix |
|---|---|---|
| 1 (F-pre) | `chore/cleanup-pass-f-pre-hygiene` | `chore(repo):` |
| 2 (A) | `docs/cleanup-pass-a-roadmap-refresh` | `docs(roadmap):` |
| 3 (B) | `docs/cleanup-pass-b-reference-audit` | `docs(audit):` and `docs(<doc>):` |
| 4 (C) | `docs/cleanup-pass-c-changelog` | `docs(changelog):` |
| 5 (D) | `chore/cleanup-pass-d-version-0.9.0` | `chore(release):` |
| 6 (E) | branch in `rova-privacy` repo, e.g. `update/2026-05-28-data-handling-refresh` | `docs(privacy):` |

**PR merge is owner-only** per memory convention — subagents must NEVER invoke `gh pr merge`. Each task ends at `gh pr create` (or push-only for E) and waits for owner action.

---

## Task 1: F-pre — Repo hygiene (pre-work that unblocks Task 2)

**Files:**
- Delete (working-tree only, never tracked): 7 files listed in File Map
- Commit (already in working tree, untracked): 2 M5 design+plan docs
- Modify: `.gitignore` (append per-developer tool-state block)

**Branch:** `chore/cleanup-pass-f-pre-hygiene`

- [ ] **Step 1: Pre-flight — confirm starting state is clean**

Run:
```bash
git status --porcelain
git rev-parse HEAD
```

Expected: HEAD is `d54e051` (or a descendant on master). `git status --porcelain` shows the 7 deletable files as untracked (gitignored files don't appear in porcelain output — that's normal; verify their presence with `ls`).

Run:
```bash
ls ROADMAP.md ROADMAP_v2.md ROADMAP_v3.md ROADMAP_v4.md ROADMAP_v5.md UI_ROADMAP.md ROADMAP_REVIEW.md nul 2>&1
```

Expected output: all 7 deletable files + `ROADMAP_v5.md` exist; `nul` exists. (If any deletable file is missing, the cleanup is already partially done — proceed but note which were already absent.)

- [ ] **Step 2: Create the branch**

```bash
git checkout master
git pull
git checkout -b chore/cleanup-pass-f-pre-hygiene
```

Expected: clean checkout on new branch from master tip.

- [ ] **Step 3: Delete the 6 stale roadmap drafts + `nul`**

PowerShell (Windows):
```powershell
Remove-Item ROADMAP.md, ROADMAP_v2.md, ROADMAP_v3.md, ROADMAP_v4.md, UI_ROADMAP.md, ROADMAP_REVIEW.md, nul -Force
```

POSIX equivalent:
```bash
rm ROADMAP.md ROADMAP_v2.md ROADMAP_v3.md ROADMAP_v4.md UI_ROADMAP.md ROADMAP_REVIEW.md nul
```

**Critical:** Do NOT delete `ROADMAP_v5.md` — it stays per V2 decision.

Verify:
```bash
ls ROADMAP*.md UI_ROADMAP.md ROADMAP_REVIEW.md nul 2>&1
```

Expected: only `ROADMAP_v5.md` and `ROADMAP_v6.md` remain; others report "not found" / "No such file".

- [ ] **Step 4: Append per-developer tool-state block to `.gitignore`**

Use Edit on `.gitignore` to append (after the existing last line `.superpowers/`):

```
# Per-developer tool state
.claude/
.kotlin/
.mcp.json
.github/hooks/
```

Verify:
```bash
git diff .gitignore
```

Expected diff: 6 lines added at end (1 comment + 4 patterns + 1 trailing newline preservation). No other changes.

- [ ] **Step 5: Stage the gitignore edit**

```bash
git add .gitignore
git status --porcelain
```

Expected: `M  .gitignore` only (no other staged/unstaged changes from the deletions, since deleted files were never tracked).

- [ ] **Step 6: Commit the gitignore update**

```bash
git commit -m "chore(repo): gitignore per-developer tool state (.claude, .kotlin, .mcp.json, .github/hooks)

Adds the per-developer tool state directories that have been accumulating
as untracked working-tree clutter to .gitignore. None of these are project
artifacts; they're all per-developer Claude Code / Kotlin daemon / MCP
config state that does not belong in git.

Does NOT add: gradle_*.log (intentionally visible per CLAUDE.md), CLAUDE.md
(intentionally untracked since 7593981), .github/workflows (would be tracked
if added later — only .github/hooks/ is ignored)."
```

- [ ] **Step 7: Stage and commit the 2 slipped M5 design+plan docs**

```bash
git add docs/superpowers/plans/2026-05-27-notification-redesign-v1.md \
        docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md
git status --porcelain
```

Expected: 2 lines `A  ...` for the new tracked files.

```bash
git commit -m "docs(notif): backfill M5 design+plan slipped from PR #54

The M5 notification redesign v1.0 design spec and implementation plan
were authored as part of PR #54 (commit d54e051) but the markdown files
themselves were never added to git. Every other slice in
docs/superpowers/{specs,plans}/ is tracked; these two were the gap.

Backfilling them here so the frozen-historical-artifact pattern stays
consistent. Files are unchanged from their state at PR #54."
```

- [ ] **Step 8: Verify branch state is clean**

```bash
git status --porcelain
git log --oneline master..HEAD
```

Expected: `git status` clean (or showing only the convention-allowed noise: `gradle_*.log`, `CLAUDE.md`); 2 new commits on the branch.

- [ ] **Step 9: Push branch and open PR**

```bash
git push -u origin chore/cleanup-pass-f-pre-hygiene
gh pr create --title "chore(repo): F-pre hygiene — gitignore, slipped M5 docs, drop stale roadmap drafts" --body "$(cat <<'EOF'
## Summary

Pre-work for the cleanup pass meta-spec (`docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md`). Three independent hygiene actions bundled into one PR because they unblock subsequent tasks.

- **Deleted (working tree only, never tracked)** — 6 stale roadmap drafts (`ROADMAP.md`, `ROADMAP_v2.md`-`v4.md`, `UI_ROADMAP.md`, `ROADMAP_REVIEW.md`) per the original `.gitignore` intent ("v6 is committed + authoritative — DO NOT add it here"). Also deleted accidental `nul` PowerShell-redirect artifact. **`ROADMAP_v5.md` is preserved** as v6's content base.
- **Gitignored** — `.claude/`, `.kotlin/`, `.mcp.json`, `.github/hooks/` (per-developer tool state, never project artifacts).
- **Backfilled** — 2 M5 design+plan docs that slipped from PR #54.

## Test plan

- [ ] `git status --porcelain` shows only convention-allowed noise (`gradle_*.log`, `CLAUDE.md`)
- [ ] `./gradlew :app:lintDebug` — green (no code touched)
- [ ] `./gradlew :app:testDebugUnitTest` — 1322 / 0-0-0 baseline preserved

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL printed. **Stop here.** Owner reviews + merges; do not invoke `gh pr merge`.

---

## Task 2: A — Live roadmap refresh

**Depends on:** Task 1 (F-pre) PR merged to master.

**Files:**
- Modify: `NEW_UI_BACKEND_REPLAN.md` (full rewrite, ~800 lines)
- Modify: `ROADMAP_v6.md` (insert Status callout after preamble)
- Modify: `ROADMAP_v5.md` (prepend 1-line preamble)

**Branch:** `docs/cleanup-pass-a-roadmap-refresh`

- [ ] **Step 1: Sync master + create branch**

```bash
git checkout master
git pull
git checkout -b docs/cleanup-pass-a-roadmap-refresh
```

Verify Task 1's deletions stuck and the 2 M5 docs are tracked:
```bash
ls ROADMAP*.md UI_ROADMAP.md ROADMAP_REVIEW.md nul 2>&1
git ls-files docs/superpowers/plans/2026-05-27-notification-redesign-v1.md
```

Expected: only `ROADMAP_v5.md` and `ROADMAP_v6.md` exist; the 2 M5 docs return their paths from `git ls-files`.

- [ ] **Step 2: Add `ROADMAP_v5.md` preamble (smallest change first)**

Use Edit on `ROADMAP_v5.md`. Prepend (before line 1 — i.e., the new content becomes lines 1-3, pushing the existing `# Rova — Production Readiness Roadmap (v5)` heading down):

**`old_string`** (line 1 of file, exact):
```
# Rova — Production Readiness Roadmap (v5)
```

**`new_string`**:
```
> **Note:** v5 is preserved in-tree as the content base for `ROADMAP_v6.md`'s surgical-diff structure. **Do not edit v5 — see v6 for the active diff.** A future PR may promote v6 to self-contained and archive v5; until then, v5 is read-only.

---

# Rova — Production Readiness Roadmap (v5)
```

Verify:
```bash
git diff ROADMAP_v5.md | head -10
```

Expected: 4 lines added at top (preamble + blank + `---` + blank), `# Rova` heading unchanged.

- [ ] **Step 3: Commit the v5 preamble**

```bash
git add ROADMAP_v5.md
git commit -m "docs(roadmap): tag v5 as v6's content base — do not edit v5

ROADMAP_v6.md is structured as a surgical diff from v5 ('Tier 2/3 logic
and every other section are unchanged from v5 and remain authoritative
as written there'). Per the cleanup-pass V2 decision, v5 stays in-tree
as v6's content base.

Adds a 1-line preamble making the dependency explicit. A future PR may
promote v6 to self-contained and archive v5; until then, treat v5 as
read-only."
```

- [ ] **Step 4: Add `ROADMAP_v6.md` Status callout**

Use Edit on `ROADMAP_v6.md`. Insert the Status section after line 4 (the surgical-revision preamble) and before line 5 (the `---` separator). The insertion is large; use this Edit:

**`old_string`** (lines 1-5, exact match):
```
# Rova — Production Readiness Roadmap (v6)

> Surgical revision of `ROADMAP_v5.md`. Only the **Tier 1 (API 29+) MediaStore pending-row recovery** logic is changed: pending items are hidden by default in `MediaStore` queries, so v5's orphan sweep would miss them. v5's Tier 2 / Tier 3 logic, the rest of §1.7, and every other section (scheduling, service model, surface lifecycle, STOP path, force-kill detection, drift policy, wakelock discipline, risks C1 / C2 / C3 / C8 / C9 / C10 / C11 / C12 / C13 / C14 / C15 / C17 / C18 / C19) are **unchanged from v5** and remain authoritative as written there.

---
```

**`new_string`**:
```
# Rova — Production Readiness Roadmap (v6)

> Surgical revision of `ROADMAP_v5.md`. Only the **Tier 1 (API 29+) MediaStore pending-row recovery** logic is changed: pending items are hidden by default in `MediaStore` queries, so v5's orphan sweep would miss them. v5's Tier 2 / Tier 3 logic, the rest of §1.7, and every other section (scheduling, service model, surface lifecycle, STOP path, force-kill detection, drift policy, wakelock discipline, risks C1 / C2 / C3 / C8 / C9 / C10 / C11 / C12 / C13 / C14 / C15 / C17 / C18 / C19) are **unchanged from v5** and remain authoritative as written there.

---

## Status (live, 2026-05-28)

| | |
|---|---|
| Master tip | `d54e051 Milestone 5 — Notification redesign v1.0 (#54)` |
| Test baseline | 1322 / 0-0-0 (failures / errors / skipped) |
| Lint baseline | 61 warnings + 1 hint + 0 errors |
| ADRs | 0001–0019 (slot 0010 used for two siblings: canonical-uv-frame + crop-divergence) |
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

---
```

Verify:
```bash
git diff ROADMAP_v6.md | head -50
```

Expected: ~30 lines added after line 4; nothing else changed.

- [ ] **Step 5: Commit the v6 Status callout**

```bash
git add ROADMAP_v6.md
git commit -m "docs(roadmap): v6 — add live Status callout (M1-M5 shipped, baseline)

Inserts a 'Status (live, 2026-05-28)' section near the top of v6
giving fresh readers a 'where are we today' anchor: master tip
d54e051, test baseline 1322/0-0-0, lint 61W+1H+0E, 19 ADRs, and the
M1-M5 milestone summary with PR/SHA references.

Light-touch update — does not touch the existing phase-level
checkboxes. Per the cleanup spec R1 decision, completion signal for
v5/v6 reliability items is 'does the matching check* task exist and
pass'; ticking checkboxes here would duplicate the build gate
signal less reliably."
```

- [ ] **Step 6: Rewrite `NEW_UI_BACKEND_REPLAN.md`**

The rewrite has 5 substantive changes:

(a) **Header date update**: `**Date:** 2026-05-07` → `**Date:** 2026-05-28`. Update commit-of-record reference if any.

(b) **§1.1 Git position table refresh**: replace `4b32b0c polish(ui): simplify history library surface` HEAD with `d54e051 Milestone 5 — Notification redesign v1.0 (#54)`. Replace the 18-entry untracked-drift inventory with current-state truth (use `git status --porcelain | head -30` output as ground truth).

(c) **§1.2 Slice table — promote shipped slices**: extend the existing 6-row Slice 1-6 table with new rows for the post-2026-05-07 work. Each new row: PR #, commit SHA, one-line summary, status (`shipped`). Use this sequence (newest first):

```
| 7  | DualShot P+L stability stack | shipped | PRs #25-#35 (`fda4bc1` → `9ac4269`) — 10 PRs hardening render threading, FBO-ring, fence-sync, EglRouter ANR, aspect-swap, encoder/audio teardown, P+L surface replay |
| 8  | DualShot Phase 6.1 foundation | shipped | PRs #22-#24 (`5224e83`, `1945965`, `ba252c3`) — CameraEffect fan-out, dual-recording consumer, true per-side rendering |
| 9  | Record screen pixel-faithful re-skin | shipped | PRs #36, #37, #39, #40 — Phase 1 Foundation + Phase 2 Shared Chrome + settings-sheet re-skin + camera-guides framing |
| 10 | Edge-to-edge immersive record-home | shipped | PRs #41, #42 (`aa337d2`, `f684d5c`) — Slice A (immersive) + Slice B (gradient scrim dock + Mode tap-cycle) |
| 11 | Phase 4 warning re-skin v3 + slices | shipped | PRs #43-#49 — Record/Library re-skin + 4.1c snooze persistence + Slice 2 storage-full echo + Slice 3 thermal auto-stop + Phase 4.3 recovery merge + Phase 4.2 warning routing |
| 12 | M1 DualShot frame polish | shipped | PR #50 (`b3fad71`) |
| 13 | M2 Merge reliability bundle | shipped | PR #51 (`0347880`) |
| 14 | M3 Asymmetric thermal hysteresis | shipped | PR #52 (`e1e121d`) — ADR-0019 |
| 15 | M4 Onboarding 7→3 immersive | shipped | PR #53 (`12c12a9`) |
| 16 | M5 Notification redesign v1.0 | shipped | PR #54 (`d54e051`) |
```

Replace the old "Slice 5 NOT started" + "Slice 6 NOT started" rows with current state.

(d) **§1.3 (Old vs new mockup directions)**: leave as-is — analysis remains correct.

(e) **§1.4 (Backend phase status)**: verify each row's `Source-of-truth file` line ref against current code. If a line number drifted, update; otherwise leave.

(f) **§2-6 forward-looking sections**: trim completed slices (mark each with `[SHIPPED PR #N]`). Surface remaining backlog:

```
## Remaining backlog (as of 2026-05-28)

| # | Slice | Source |
|---|---|---|
| 1 | Mode preset seed (first-run mode picker default) | §2.x — re-derive from `mockups/new_uiux/01-home-idle.html` mode tabs |
| 4 | (next priority — re-derive from §6) | NEW_UI_BACKEND_REPLAN §6 row to be confirmed by owner |
| 11 | HUD Merging states extension | Verify against shipped `32c5cb3 feat(ui): add HUD merging end-states` — may already be complete; needs owner confirmation |

## Parked (deferred indefinitely or with conditions)

- Variant D — deferred to 2027 per ADR-0012 §Rejected variants A/B/D
- Variant A — mid-segment thermal escalation (deferred — no owner ask)
- Variant G — "Continue without saving" (deferred — no owner ask)
- Export sheet polish (deferred from M5 scope per memory `project_current_state.md`)
- M4 follow-on items: theme switcher, tour-replay, full i18n, per-perm JIT split (deferred — none have current owner ask)
- Launcher webp legacy fallback for API 24-25 (deferred — <0.5% installs in 2026 per memory)
```

**Critical**: The "HUD Merging states" and "in-app player" entries memory lists as "next" need re-verification — git log shows `32c5cb3 feat(ui): add HUD merging end-states` and `db25405 feat(player): add Phase 2.5 in-app video player (#1)` both shipped. The rewrite should reflect git truth, not memory's stale "next" framing. If the owner intended a follow-on slice (e.g., HUD-merging refinement), they should label it as such in review.

Use multiple Edit operations on `NEW_UI_BACKEND_REPLAN.md` — one per logical change. Total ~50-150 line diff depending on §2-6 trim depth.

- [ ] **Step 7: Commit the REPLAN rewrite**

```bash
git add NEW_UI_BACKEND_REPLAN.md
git diff --cached NEW_UI_BACKEND_REPLAN.md | head -20
git commit -m "docs(roadmap): refresh NEW_UI_BACKEND_REPLAN to post-M5 master baseline

Refreshes the planning + reconciliation report to reflect the
current master tip d54e051. Changes:

- §1.1 Git position: 2026-05-07 snapshot → 2026-05-28 (d54e051)
- §1.2 Slice table: extended with 10 newly-shipped slice rows
  (DualShot Phase 6.1 + stability stack, Record screen re-skin,
  edge-to-edge HUD, Phase 4 v3 warning stack, M1-M5)
- §1.3 Old-vs-new analysis: unchanged (still correct)
- §1.4 Backend phase status: line refs re-verified against master
- §2-6 forward-looking sections: completed slices marked SHIPPED;
  remaining backlog re-derived; parked items consolidated

Filename preserved per N1 decision in cleanup-pass spec (rename
defers to a future dedicated PR). 2 lines of pre-rewrite dirty
edits (BatteryOptimizationBanner annotation) are absorbed.

Spec: docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md"
```

- [ ] **Step 8: Verify branch state**

```bash
git log --oneline master..HEAD
./gradlew :app:lintDebug --quiet
```

Expected: 3 commits on branch (v5 preamble, v6 status, REPLAN rewrite). `lintDebug` BUILD SUCCESSFUL (no code touched — should be UP-TO-DATE for most tasks).

- [ ] **Step 9: Push branch and open PR**

```bash
git push -u origin docs/cleanup-pass-a-roadmap-refresh
gh pr create --title "docs(roadmap): cleanup-pass A — refresh live roadmap docs" --body "$(cat <<'EOF'
## Summary

Sub-project A of the cleanup pass meta-spec. Three commits:

- **`docs(roadmap): tag v5 as v6's content base — do not edit v5`** — adds 1-line preamble to `ROADMAP_v5.md` per the V2 decision (v5 stays as v6's surgical-diff content base).
- **`docs(roadmap): v6 — add live Status callout`** — adds `## Status (live, 2026-05-28)` section near the top of `ROADMAP_v6.md` with master tip, test/lint baseline, ADR count, M1-M5 milestone table.
- **`docs(roadmap): refresh NEW_UI_BACKEND_REPLAN to post-M5 master baseline`** — refreshes §1.1, §1.2, §1.4, §2-6 to current state; preserves §1.3 (old-vs-new analysis still correct).

## Test plan

- [ ] `./gradlew :app:lintDebug` — green
- [ ] `./gradlew :app:testDebugUnitTest` — 1322 / 0-0-0 preserved
- [ ] Owner reviews rewritten `NEW_UI_BACKEND_REPLAN.md` for accuracy of the §1.2 slice table and §2-6 backlog re-derivation

Spec: `docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md` §6.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

**Stop here.** Owner reviews + merges; do not invoke `gh pr merge`.

---

## Task 3: B — Reference doc audit (two phases with owner gate)

**Depends on:** Task 2 (A) PR merged to master.

**Files:**
- Create: `docs/superpowers/specs/2026-05-28-reference-doc-drift-report.md` (Phase 1)
- Modify: 9 reference docs per drift report (Phase 2, owner-authorized subset)

**Branch:** `docs/cleanup-pass-b-reference-audit`

### Phase 1 — Drift report

- [ ] **Step 1: Sync master + create branch**

```bash
git checkout master
git pull
git checkout -b docs/cleanup-pass-b-reference-audit
```

- [ ] **Step 2: Read each of 9 reference docs to ground the audit**

For each path below, use Read to ingest the full file content:
- `readme.md`
- `docs/architecture.md`
- `docs/WarningCenterContract.md`
- `docs/UI_DESIGN_TOKENS.md`
- `docs/UI_NAV_GRAPH.md`
- `docs/release_checklist.md`
- `docs/development_log.md`
- `docs/product_vision.md`
- `docs/naming.md`

- [ ] **Step 3: Cross-reference claims against current code**

For each doc, identify specific claims and run targeted Grep / Read operations against `app/src/main/java/com/aritr/rova/` to verify. Examples:
- README claims "in-app player (Phase 2.5)" — verify `ui/screens/player/PlayerScreen.kt` exists (it should — `db25405` PR #1 shipped it).
- README ADR table lists only 0001-0006 — list `docs/adr/*.md` to find all 19 ADRs.
- `architecture.md` may have a module diagram — cross-reference against `Glob("app/src/main/java/com/aritr/rova/**/*.kt")` for new packages (esp. `service/dualrecord/`, `ui/recovery/`, `ui/warnings/`, `ui/signals/`, `ui/screens/player/`, `ui/screens/onboarding/`).
- `WarningCenterContract.md` — verify against Phase 4.2 routing (PR #49 `7f64650`) and Phase 4.3 recovery merge (PR #48 `1485cf5`).
- `UI_DESIGN_TOKENS.md` — verify Inter font + RecordChromeTokens references.

- [ ] **Step 4: Write the drift report file**

Create `docs/superpowers/specs/2026-05-28-reference-doc-drift-report.md` with this structure:

```markdown
# Reference doc drift report — cleanup pass sub-B Phase 1

**Date:** 2026-05-28
**Spec:** `docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md`
**Baseline:** master tip `d54e051`

## Status legend

- `CURRENT` — claim matches current code/spec
- `STALE` — claim was true once but no longer is
- `MISSING` — coverage gap (doc should mention something but doesn't)
- `TBD-VERIFY` — needs further investigation before fix can be drafted

## Per-doc tables

### `readme.md`

| Line / Section | Claim | Status | Evidence (current truth) | Suggested fix |
|---|---|---|---|---|
| L10 | `Version 0.5.0` | STALE | Bumping to 0.9.0 in sub-D | Update to 0.9.0 after sub-D lands; for now flag for sub-D coordination |
| L12 | `targetSdk 36` | STALE | `app/build.gradle.kts:23 targetSdk = 37` (PR #16 `e505c35`) | Update to 37 |
| L274-282 | Documentation table lists 4 docs only (product_vision, architecture, development_log, naming) | MISSING | Should also list: WarningCenterContract, UI_DESIGN_TOKENS, UI_NAV_GRAPH, release_checklist | Extend table |
| L283-293 | ADR table lists 0001-0006 (5 ADRs — missing 0004 slot) | STALE | 19 ADRs total (0001-0019; slot 0010 used for 2 siblings) | Regenerate table from `ls docs/adr/*.md` |
| ... | ... | ... | ... | ... |

### `docs/architecture.md`

| ... | ... | ... | ... | ... |

(repeat per doc)

## Summary

| Doc | CURRENT | STALE | MISSING | TBD-VERIFY |
|---|---|---|---|---|
| `readme.md` | N | N | N | N |
| `docs/architecture.md` | N | N | N | N |
| (...) | | | | |
| **Total** | N | N | N | N |

## Recommended actions (owner go/no-go)

For each doc, the recommendation is one of:
- **FIX** — drift items above are real; sub-B Phase 2 should execute the suggested fixes
- **ARCHIVE** — doc is no longer maintained; move to `docs/archive/` (note: this would require revisiting the A1′-a "delete only" decision for archived items)
- **LEAVE** — doc is current as-is

| Doc | Recommendation | Reason |
|---|---|---|
| `readme.md` | FIX | Highly visible public-facing doc; drift is concrete and fixable |
| (...) | | |
```

Replace each `...` and `N` with the actual content/numbers derived from the cross-reference work.

- [ ] **Step 5: Commit the drift report**

```bash
git add docs/superpowers/specs/2026-05-28-reference-doc-drift-report.md
git commit -m "docs(audit): cleanup-pass B Phase 1 — reference doc drift report

Per-doc drift table for 9 tracked reference docs, comparing claims
against master tip d54e051. Each row: line/section, claim, status
(CURRENT/STALE/MISSING/TBD-VERIFY), evidence, suggested fix.

Phase 2 of sub-B executes the owner-authorized subset of suggested
fixes; this Phase 1 commit is the gate.

Spec: docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md §7."
```

- [ ] **Step 6: Push branch (no PR yet — gate before Phase 2)**

```bash
git push -u origin docs/cleanup-pass-b-reference-audit
```

Output the branch URL to owner for review.

### Owner gate — STOP here until owner approves Phase 2

The implementer subagent surfaces the drift report path + branch URL to the owner via the controller. Owner reviews the drift report and authorizes:
- Per-doc go/no-go (`FIX` / `ARCHIVE` / `LEAVE`)
- Any wording overrides on suggested fixes

Controller relays decisions back to the implementer to proceed.

### Phase 2 — Authorized fixes

- [ ] **Step 7: Execute approved fixes per-doc, one commit per doc**

For each doc with `FIX` approval, apply the owner-authorized suggested fixes from the drift report using Edit. After each doc's fixes:

```bash
git add <doc-path>
git commit -m "docs(<doc-scope>): <one-line summary of fixes>

Per drift report (docs/superpowers/specs/2026-05-28-reference-doc-drift-report.md),
applies owner-authorized fixes to <doc-path>:

- <fix 1>
- <fix 2>
- ..."
```

Scope tags by doc:
- `readme.md` → `docs(readme):`
- `docs/architecture.md` → `docs(arch):`
- `docs/UI_NAV_GRAPH.md` → `docs(nav):`
- `docs/WarningCenterContract.md` → `docs(warnings):`
- `docs/UI_DESIGN_TOKENS.md` → `docs(tokens):`
- `docs/release_checklist.md` → `docs(release):`
- `docs/development_log.md` → `docs(devlog):`
- `docs/product_vision.md` → `docs(vision):`
- `docs/naming.md` → `docs(naming):`

- [ ] **Step 8: Verify the README in-app-player claim is preserved (critical)**

Per the meta-spec §7 explicit note: the README's "in-app player (Phase 2.5)" claim is CORRECT (shipped in PR #1 `db25405`). Verify the README still mentions the in-app player after fixes — if any owner-authorized fix removed this, surface the conflict to the owner before committing.

Run:
```bash
git grep -n "in-app player\|PlayerScreen" readme.md
```

Expected: at least one match (the player IS shipped; the README must continue to advertise it).

- [ ] **Step 9: Verify branch state + run lint/test**

```bash
git log --oneline master..HEAD
./gradlew :app:lintDebug --quiet
./gradlew :app:testDebugUnitTest --quiet
```

Expected: 1 drift report commit + 1-9 per-doc fix commits; lint + test green.

- [ ] **Step 10: Push and open PR**

```bash
git push
gh pr create --title "docs(audit): cleanup-pass B — reference doc audit + authorized fixes" --body "$(cat <<'EOF'
## Summary

Sub-project B of the cleanup pass meta-spec. Two-phase:

**Phase 1** — Drift report committed to `docs/superpowers/specs/2026-05-28-reference-doc-drift-report.md` covering 9 tracked reference docs. Status legend: CURRENT / STALE / MISSING / TBD-VERIFY. Per-doc recommendation: FIX / ARCHIVE / LEAVE.

**Phase 2** — Owner-authorized fixes from the drift report, one commit per doc for git-blame clarity. Commits in this PR:

(list of `docs(<scope>): <summary>` commits from `git log master..HEAD`)

## Notable findings surfaced by the drift report

- README's "in-app player (Phase 2.5)" feature claim is CORRECT (shipped in commit `db25405` PR #1). Preserved after fixes.
- ADR table in README extended from 0001-0006 (5 entries) to 0001-0019 (19 ADRs across 20 files including the 0010 sibling pair).
- Architecture doc module shape refreshed to include `service/dualrecord/`, `ui/screens/player/`, `ui/screens/onboarding/`, `ui/signals/`, `ui/recovery/`, `ui/warnings/`.

## Test plan

- [ ] `./gradlew :app:lintDebug` — green
- [ ] `./gradlew :app:testDebugUnitTest` — 1322 / 0-0-0 preserved
- [ ] Owner spot-checks 2-3 fixed docs for accuracy

Spec: `docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md` §7.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

**Stop here.** Owner reviews + merges.

---

## Task 4: C — CHANGELOG creation

**Depends on:** Task 3 (B) PR merged to master.

**Files:**
- Create: `CHANGELOG.md` (root)

**Branch:** `docs/cleanup-pass-c-changelog`

- [ ] **Step 1: Sync master + create branch**

```bash
git checkout master
git pull
git checkout -b docs/cleanup-pass-c-changelog
```

- [ ] **Step 2: Re-confirm CHANGELOG content from git log**

The 0.5.0 entry in particular should be re-grounded — run:
```bash
git log --oneline 1ed6dbd..f57e175 --no-merges    # 0.3.0 → 0.4.0 range
git log --oneline f57e175..ae3516a --no-merges    # 0.4.0 → 0.5.0 range (pre-AGP-9)
git log --oneline ae3516a..d54e051 --no-merges    # 0.5.0 → 0.9.0 range (post-AGP-9)
```

Map commits to Keep a Changelog sections (Added / Changed / Deprecated / Removed / Fixed / Security).

- [ ] **Step 3: Create `CHANGELOG.md`**

Write the file with this content (using the meta-spec §8 entries as the substantive source — concrete entries already derived during spec authoring):

```markdown
# Changelog

All notable changes to Rova are documented here. This project adheres to
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/).

Commit-level detail lives in `git log` and on GitHub PRs
(https://github.com/aritr-codes/rova-android/pulls).
ADR-level invariants live in `docs/adr/`. Roadmaps live in `ROADMAP_v6.md`
(reliability) and `NEW_UI_BACKEND_REPLAN.md` (UI / feature).

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

From tag `v0.4.0` message: "Library thumbnails, battery optimization, project cleanup."

### Added
- Real video thumbnails in History library.
- Battery optimization prompt + Doze-mode detection.

### Changed
- Project cleanup pass.

## [0.3.0] — 2026-03-07

From tag `v0.3.0` message: "Architecture refactor + reliability hardening. First clean commit. Core periodic recording loop is end-to-end functional. RecordViewModel established, LoomAppLegacy removed, 10+ reliability fixes applied across the service and merge pipeline."

### Added
- `RecordViewModel`.
- End-to-end periodic recording loop.

### Changed
- Architecture refactor.
- Reliability hardening across service + merge pipeline.

### Removed
- `LoomAppLegacy`.
```

- [ ] **Step 4: Commit the CHANGELOG**

```bash
git add CHANGELOG.md
git commit -m "docs(changelog): add CHANGELOG.md — backfill 0.3.0/0.4.0/0.5.0 + add 0.9.0

Per cleanup-pass sub-C. Keep a Changelog 1.1.0 format. Backfilled
entries derived from git log:

- 0.3.0 — from tag v0.3.0 message
- 0.4.0 — from tag v0.4.0 message
- 0.5.0 — derived from f57e175..ae3516a (PRs #1-#13: in-app player,
  onboarding, signals, WarningCenter)
- 0.9.0 — current (DualShot P+L, M1-M5, Phase 4 v3 stack, AGP 9, etc.)

Header references git log + PRs as commit-level detail source and
ADRs as invariant source. Roadmap pointers (v6 reliability,
NEW_UI_BACKEND_REPLAN UI/feature) included for navigation."
```

- [ ] **Step 5: Push and open PR**

```bash
git push -u origin docs/cleanup-pass-c-changelog
gh pr create --title "docs(changelog): cleanup-pass C — add CHANGELOG.md with 0.3.0 → 0.9.0 entries" --body "$(cat <<'EOF'
## Summary

Sub-project C of the cleanup pass meta-spec. Adds a single new file: `CHANGELOG.md` in repo root.

Keep a Changelog 1.1.0 format. Entries:
- **0.9.0** (current — to be tagged in sub-D)
- **0.5.0** (untagged, code-version only — PR #2 `53d246b`)
- **0.4.0** (existing tag, 2026-03-07)
- **0.3.0** (existing tag, 2026-03-07)

Backfill content derived from git log + tag messages. Header notes that PRs + git log + ADRs remain the commit-level / invariant sources.

## Test plan

- [ ] `./gradlew :app:lintDebug` — green
- [ ] Owner reviews entries for accuracy (esp. 0.5.0 backfill from PR #1-#13)

Spec: `docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md` §8.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

**Stop here.** Owner reviews + merges.

---

## Task 5: D — Version bump + tag

**Depends on:** Task 4 (C) PR merged to master.

**Files:**
- Modify: `app/build.gradle.kts:25-26` (2 lines)

**Branch:** `chore/cleanup-pass-d-version-0.9.0`

- [ ] **Step 1: Sync master + create branch**

```bash
git checkout master
git pull
git checkout -b chore/cleanup-pass-d-version-0.9.0
```

Verify CHANGELOG.md from Task 4 is present:
```bash
ls CHANGELOG.md
head -5 CHANGELOG.md
```

Expected: `CHANGELOG.md` exists; first line is `# Changelog`.

- [ ] **Step 2: Edit `app/build.gradle.kts` version fields**

Use Edit on `app/build.gradle.kts`:

**`old_string`** (lines 25-26 verbatim):
```
        versionCode = 3
        versionName = "0.5.0"
```

**`new_string`**:
```
        versionCode = 4
        versionName = "0.9.0"
```

Verify:
```bash
git diff app/build.gradle.kts
```

Expected: 2 lines changed (3→4, "0.5.0"→"0.9.0"); nothing else.

- [ ] **Step 3: Build the APK + verify versionName**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. Then verify the APK manifest reports 0.9.0:

```bash
# Locate aapt — usually at $ANDROID_HOME/build-tools/<version>/aapt or aapt2
$AAPT = (Get-ChildItem -Path "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Filter aapt.exe -Recurse | Select-Object -First 1).FullName
& $AAPT dump badging app/build/outputs/apk/debug/app-debug.apk | Select-String "versionName"
```

POSIX equivalent:
```bash
aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep versionName
```

Expected output line contains: `versionCode='4' versionName='0.9.0'`.

- [ ] **Step 4: Run lint + tests**

```bash
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
```

Expected: both BUILD SUCCESSFUL; test count 1322 / 0-0-0 preserved.

- [ ] **Step 5: Commit the version bump**

```bash
git add app/build.gradle.kts
git commit -m "chore(release): bump to 0.9.0 (versionCode 4)

Bumps versionName 0.5.0 → 0.9.0 and versionCode 3 → 4 per cleanup-pass
sub-D. Captures the 0.4.0 → 0.9.0 work (DualShot P+L, M1-M5, Phase 4 v3
warning stack, AGP 9 migration, edge-to-edge HUD, onboarding 3-screen,
notification redesign v1, Inter font, branded launcher) in a single
named version.

Per cleanup-pass spec D recommendation (CC1 + T1): versionCode
increment-by-1 matches existing pattern; tag v0.9.0 will be applied
after this PR merges (no backfill of intermediate v0.5.0/v0.6.0/v0.7.0
tags — those releases didn't actually ship).

Spec: docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md §9."
```

- [ ] **Step 6: Push and open PR**

```bash
git push -u origin chore/cleanup-pass-d-version-0.9.0
gh pr create --title "chore(release): bump to 0.9.0 (versionCode 4)" --body "$(cat <<'EOF'
## Summary

Sub-project D of the cleanup pass meta-spec. Single 2-line change to `app/build.gradle.kts`:

```diff
-        versionCode = 3
-        versionName = "0.5.0"
+        versionCode = 4
+        versionName = "0.9.0"
```

## Why 0.9.0 (not 0.8.0)

The 0.4.0 → current body of work is large enough to credibly span 4 minor versions: DualShot P+L (major new feature), Phase 4 v3 warning stack (8 PRs), 4 UI redesigns, AGP 9 migration, reliability hardening, M1-M5 milestones. Bumping to 0.9.0 reflects scope honestly and positions 1.0.0 as a meaningful Play-Store-launch anchor for the next slice (in-app player polish + HUD merging confirmation + Mode preset seed).

## Tagging

After this PR merges, an annotated tag `v0.9.0` will be applied to the merge commit (separate operation, owner-authorized — subagent does not invoke `git push origin v0.9.0`).

## Test plan

- [ ] `./gradlew :app:lintDebug` — green
- [ ] `./gradlew :app:testDebugUnitTest` — 1322 / 0-0-0 preserved
- [ ] `./gradlew :app:assembleDebug` — green
- [ ] `aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep versionName` — reports `versionCode='4' versionName='0.9.0'`

Spec: `docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md` §9.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

**Stop here.** Owner reviews + merges.

### Post-merge: tag v0.9.0 (owner-authorized only)

- [ ] **Step 7: After D's PR merges — owner authorizes the tag**

The implementer subagent must surface this step to the controller, which surfaces it to the owner. Subagent does NOT run `git push origin v0.9.0`.

When owner authorizes, the command is:

```bash
git checkout master
git pull
git tag -a v0.9.0 -m "v0.9.0 — DualShot P+L, M1-M5 milestones, Phase 4 v3 warning stack, AGP 9 migration, edge-to-edge HUD, onboarding 3-screen, notification redesign v1, Inter font, branded launcher

See CHANGELOG.md for the full Added/Changed/Fixed/Removed breakdown.
Master tip at tag time: <merge-commit-sha>"
git push origin v0.9.0
```

Verify:
```bash
git tag --list "v0.9.0"
git show v0.9.0 --no-patch --format="%H %ai %s"
```

Expected: tag exists locally; `git show` reports the tagged commit SHA matches the merge of D's PR.

---

## Task 6: E — Privacy policy review (two phases with owner gate; runs in parallel with A→D)

**Depends on:** None within this plan (different repo). Can be started anytime after spec approval.

**Files:**
- Create (in rova-android tree): `docs/superpowers/specs/2026-05-28-privacy-policy-drift-report.md`
- Modify (in cloned rova-privacy tree): `PRIVACY.md` or similar (discovered at clone time)

**Branches:**
- In rova-android: no branch needed (drift report committed via Task 3's PR, or as a standalone commit)
- In rova-privacy: `update/2026-05-28-data-handling-refresh`

### Phase 1 — Drift report

- [ ] **Step 1: Clone the rova-privacy repo to a temp location**

```bash
# Windows
$CLONE_PATH = "$env:LOCALAPPDATA\Temp\rova-privacy-audit"
git clone https://github.com/aritr-codes/rova-privacy.git $CLONE_PATH
```

POSIX equivalent:
```bash
CLONE_PATH=/tmp/rova-privacy-audit
git clone https://github.com/aritr-codes/rova-privacy.git "$CLONE_PATH"
```

Verify clone:
```bash
cd $CLONE_PATH    # PowerShell: Set-Location $CLONE_PATH
ls
```

Expected: at least one `.md` file (likely `PRIVACY.md`, `README.md`, or similar) + `.git/`.

- [ ] **Step 2: Read the current privacy policy**

Identify the policy file (usually `PRIVACY.md`, `privacy-policy.md`, `index.html`, or similar — list `.md` and `.html` files; pick the largest content file).

Use Read on the file. Note: structure (sections), claims about data flow, permissions disclosed, third-party services mentioned, retention statements.

- [ ] **Step 3: Produce drift report**

Create `docs/superpowers/specs/2026-05-28-privacy-policy-drift-report.md` in the **rova-android** tree (so it's reviewed alongside the cleanup pass) with this structure:

```markdown
# Privacy policy drift report — cleanup pass sub-E Phase 1

**Date:** 2026-05-28
**Spec:** `docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md`
**Repo audited:** `aritr-codes/rova-privacy` (cloned at `<CLONE_PATH>`)
**Policy file:** `<discovered file path>`
**Policy version / date in file:** `<as read>`

## Per-surface analysis

### Surface 1 — Inter font via Google Fonts provider (HIGHEST PRIORITY)

**What changed:** Commit `c37e081 feat(ui): add Inter downloadable font (Google Fonts provider)` (M5 Phase 1 follow-on, on master since PR #54 / `d54e051`) adds Android Downloadable Fonts using `androidx.core.provider.FontProvider`. The Google Fonts FontProvider fetches the typeface from Google's servers on first use, then caches it locally.

**Currently in policy:** <quote relevant section or note "not covered">

**Status:** CURRENT / STALE / MISSING / TBD-VERIFY

**Suggested addition:**

> When the app first launches, Rova requests the Inter typeface via Android's Downloadable Fonts API. This request is handled by Google Play Services' FontProvider component, which fetches the typeface file from Google's servers and caches it on your device. Rova does not transmit any user data as part of this request. See Google Play Services' privacy policy for details on this FontProvider's data handling.

(repeat for surfaces 2-8 from spec §10)

## Summary

| # | Surface | Currently in policy | Status | Recommendation |
|---|---|---|---|---|
| 1 | Inter font / Google Fonts | No | MISSING | ADD |
| 2 | M5 notifications | <as read> | <status> | <action> |
| ... | ... | ... | ... | ... |

## Recommended actions (owner go/no-go)

For each surface, the recommendation is one of:
- **ADD** — surface is missing from policy; add suggested text
- **REVISE** — surface is covered but wording is stale; revise
- **NO-CHANGE** — surface is current
- **DEFER** — requires legal opinion before drafting (e.g., wording around Google Fonts data sharing)

| # | Surface | Recommendation | Reason |
|---|---|---|---|
| 1 | Inter font | ADD | Net-new network call; not in policy |
| ... | | | |
```

- [ ] **Step 4: Commit the drift report (in rova-android tree)**

Either fold into Task 3's PR if Task 3 hasn't been pushed yet, OR commit as a standalone:

```bash
# In rova-android tree, on master or a dedicated branch
cd <rova-android>
git checkout -b docs/cleanup-pass-e-privacy-drift-report
git add docs/superpowers/specs/2026-05-28-privacy-policy-drift-report.md
git commit -m "docs(privacy): cleanup-pass E Phase 1 — privacy policy drift report

Per-surface drift table for 8 data-handling surfaces, comparing against
the policy at https://github.com/aritr-codes/rova-privacy (cloned at
<CLONE_PATH>).

Highest-priority gap: Inter font via Google Fonts provider (commit
c37e081, M5 Phase 1 follow-on) — net-new network call not currently
in policy.

Phase 2 of sub-E executes the owner-authorized subset of suggested
edits in the rova-privacy repo; this Phase 1 commit is the gate.

Spec: docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md §10."
git push -u origin docs/cleanup-pass-e-privacy-drift-report
gh pr create --title "docs(privacy): cleanup-pass E Phase 1 — drift report" --body "..."
```

### Owner gate — STOP here until owner approves Phase 2

Owner reviews drift report and authorizes per-surface ADD / REVISE / NO-CHANGE / DEFER.

### Phase 2 — Authorized edits in rova-privacy

- [ ] **Step 5: In cloned rova-privacy, create branch + apply approved edits**

```bash
cd $CLONE_PATH
git checkout -b update/2026-05-28-data-handling-refresh
```

For each surface with ADD or REVISE approval, edit the policy file. One commit per surface:

```bash
git add <policy-file>
git commit -m "docs(privacy): add disclosure for Inter font / Google Fonts provider

Surface 1 of cleanup-pass sub-E. Adds a paragraph covering Android's
Downloadable Fonts API call to Google Play Services' FontProvider
component for fetching the Inter typeface on first launch.

Reference: rova-android commit c37e081 (M5 Phase 1 follow-on).
Drift report: rova-android docs/superpowers/specs/2026-05-28-privacy-policy-drift-report.md."
```

(repeat per approved surface)

- [ ] **Step 6: Owner reviews drafted edits locally**

The subagent surfaces the cloned-repo path + `git log master..HEAD` to the controller. Owner reviews the commits in the cloned repo (e.g., via `git diff master..HEAD` or `gh pr diff` if pushed to a draft PR).

### Owner gate — STOP here until owner authorizes push

- [ ] **Step 7: With explicit per-commit owner authorization, push to rova-privacy**

```bash
cd $CLONE_PATH
git push -u origin update/2026-05-28-data-handling-refresh
gh pr create --title "docs(privacy): refresh data-handling disclosures (Inter font + ...)" --body "..."
```

- [ ] **Step 8: Cleanup — remove temp clone**

After PR is opened (or after owner-merged), clean up:

```powershell
Remove-Item -Recurse -Force $env:LOCALAPPDATA\Temp\rova-privacy-audit
```

POSIX:
```bash
rm -rf /tmp/rova-privacy-audit
```

Verify:
```bash
ls $CLONE_PATH 2>&1
```

Expected: "not found" / "No such file or directory".

---

## Cross-cutting verification (after all 6 tasks merge)

- [ ] **Step F1: Confirm master baseline preserved**

```bash
git checkout master
git pull
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected: all 3 BUILD SUCCESSFUL. Test count 1322 / 0-0-0 baseline preserved (drift tolerable if a B-Phase-2 doc edit incidentally touched code — should not occur in this plan).

- [ ] **Step F2: Confirm version tag exists and is pushed**

```bash
git tag --list "v0.9.0"
git ls-remote --tags origin | grep "v0.9.0"
```

Expected: tag exists locally and on origin.

- [ ] **Step F3: Confirm working tree is clean (except convention-allowed noise)**

```bash
git status --porcelain
```

Expected: empty OR only convention-allowed entries (`gradle_*.log`, `CLAUDE.md`). No stale roadmap drafts, no `nul`, no untracked tool state.

- [ ] **Step F4: Surface follow-on actions to owner**

The implementer subagent surfaces these to the controller:
1. **Memory update** — `project_current_state.md` should reflect 0.9.0 tip + completed cleanup pass + correct the "in-app player / HUD merging shipped" facts. **Owner-authorized only.**
2. **Sub-E push to rova-privacy** — if not yet pushed, surface for owner action.
3. **Temp clone cleanup** — confirm `$LOCALAPPDATA\Temp\rova-privacy-audit` is removed.

---

## Notes for the subagent controller

- **Each task is a separate fresh subagent dispatch** — no shared context between Tasks 1-6.
- **Tasks 1-5 are sequential** (each depends on the previous PR merging). Task 6 is parallel.
- **Owner gates inside Tasks 3 and 6** are explicit STOP points — the subagent must surface the drift report path to the controller, who relays to the owner, who relays decisions back. Subagent does not assume any FIX recommendation has been approved.
- **`gh pr merge` is forbidden** for all 6 tasks — owner-only.
- **Tag push (Step 7 of Task 5)** is owner-only — subagent surfaces the command, owner runs it.
- **Sub-E external push (Step 7 of Task 6)** is owner-only.
- **Memory updates** are owner-only (per project convention).
- If a subagent hits an unexpected drift (e.g., a doc claims something not in the drift report), it should STOP and surface to the controller rather than fabricate an answer.
