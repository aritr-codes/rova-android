# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-13.**

---

## Where things stand

- **Branch:** `master` — **HEAD `24cdae7`** ("PR-ε: fixed-window + counter-rotating record chrome (ADR-0029 §B″) (#108)").
- **Open PRs:** zero.
- **Working tree:** clean except two untracked planning docs created this session — `docs/BACKLOG.md` and `HANDOFF.md` (this file). Neither is committed yet.
- **Test baseline:** JVM unit tests only (`:app:testDebugUnitTest`), green on master. Real-device smoke is mandatory — emulators fail CameraX video recording.
- **Static gates:** **39** custom `check*` tasks wired into `preBuild` (measured — see drift note below).

## What this session did

1. **Landed the β/γ/ε stacked merge train** onto master: `9bbe050` (PR-β #106) → `744a99d` (PR-γ #109) → `24cdae7` (PR-ε #108). This completed the rotation-first record-chrome arc (ADR-0029 §B′/§B″) after owner device-GO. Note the merge-train scar: `gh pr merge --delete-branch` auto-**closed** dependent #107 instead of retargeting — recovered by rebasing the pristine branch and reopening as #109. See `memory/feedback_stacked_pr_merge_train.md` for the safe pattern.
2. **Drafted [`docs/BACKLOG.md`](docs/BACKLOG.md)** — 33 sourced items across 8 themes + a "Top 3 next" callout. Every item traces to an ADR / roadmap / accessibility doc / memory file / measured code fact.
3. **Codebase-hygiene run** — reclaimed **~477.6 MB** of untracked junk: 88 `gradle_*.log` + stray `nul`, 4 `obs_*.mp4` (409 MB), `obs_frames/` (419 files, 55 MB), 6 `landscape_checkpoint_*.png`, 17 untracked root `.html` mockups, 3 stale merged-PR plan docs. **Protected:** the 2 *tracked* root mockups (`config_strip_mode_mockup.html`, `landscape_record_mockup.html`), `keystore.properties`, and all gitignored tool/build dirs (`.codegraph/`, `.idea/`, `app/build/` — never `git clean -x`'d).

## ⚠️ Documentation drift to fix (P1, mechanical)

`CLAUDE.md` says "**28** custom `check*` tasks" (Static-check-gate section) and "the existing **25** checks" (ADR-0020 paragraph); PR-ε memory carried "**36**". **The true count is 39** — measured: 39 unique `tasks.register("check…` in `app/build.gradle.kts`, all 39 wired into `preBuild`. The full alphabetized gate list is inlined in `docs/BACKLOG.md` under "Gate-count documentation recount sweep". Fix `CLAUDE.md` (both numbers) + any ADR text that cites a count + correct the memory files.

## Top 3 next candidates (from the backlog)

1. **Gate-count recount sweep** — P1, zero-risk doc fix (the drift above).
2. **PR-δ FrontBack PiP** — P2, the last unbuilt phase of ADR-0029 (Accepted). `FrontBack` is only an enum + UI refs + the `checkFrontBackCapabilityGated` fence today; no concurrent-camera source under `service/`. ADR already specifies the shape (single-file PiP via CameraX `CompositionSettings`, capability-gated).
3. **Preset UI polish** — P1, owner called the in-sheet PRESETS section "kinda mess" (device-smoke otherwise PASS); not yet specced → start with brainstorming.

## PR-ε non-blocking follow-ups still open (all KDoc'd, deferred)

Warning-sheet visibility hoist out of `WarningCenter`; thermal-tips sheet portrait-under-lock conversion; `FloatingSettingsPanel` BackHandler disarm during exit animation; per-frame recomposition during the 180ms spin (v2: read spin angle as `State<Float>` inside the `graphicsLayer` lambda); two cosmetic landscape overflow bugs (cold-entry one-spin ~350ms, LOCKED "Landscape" value grazing separators). All in `docs/BACKLOG.md`.

## Build-environment gotchas (carry every session)

- **Broken kotlin-postedit hook** runs gradle per file-save → daemon pileup pins CPU and corrupts the `built_in_kotlinc` incremental cache (MD5 mismatch). **Recovery dance before each build:** `gradlew.bat --stop` → kill stray `java` → delete `app/build/kotlin` + `.gradle/kotlin` (and `built_in_kotlinc` cache dirs).
- **Subagents are EDIT-ONLY.** The controller (main session) runs **all** gradle, one build per batch.
- **Windows / PowerShell.** Use `gradlew.bat`. The **adb MCP wrapper is broken on Windows** — drive adb via PowerShell directly. Avoid inline `(... )/1MB` arithmetic piped into `Remove-Item` (the sandbox can misparse `/1MB,2` as a path) — compute sizes into a variable first.
- **`lintDebug` is RED on pre-existing B5 `VaultAndroidOps` NewApi** (unrelated) — use `:app:assembleDebug` to gate-build, not `lintDebug`, unless specifically chasing lint.

## Load-bearing rules (don't violate)

- **Never edit a `check*` task to make it green** — fix the source, or amend the ADR + check with explicit owner sign-off. New invariant = ADR clause → new `check*` → wire into `preBuild`.
- **ADRs are the source of truth.** Touching anything an ADR mentions = amend the ADR clause first, regenerate/extend the matching `check*`, then change code.
- **codex MCP peer review** (`mcp__codex__codex`) is mandatory for code changes >5 lines, architecture/design, algorithmic logic, security-sensitive, migrations, perf claims. Skip for conversational/status/trivial.
- **CodeGraph** is initialized — never call `codegraph_explore`/`codegraph_context` from the main session; spawn an Explore agent. Main session may use `codegraph_search`/`callers`/`callees`/`impact`/`node` for targeted lookups only.
- **JVM unit tests only** (`isReturnDefaultValues = true`); framework-touching code gets a pure-Kotlin sibling (the seam/pure-helper pattern). A new feature lands its tests in the same PR.
- Untracked `gradle_*.log` in root are ephemeral verification artifacts (this session cleaned them; they regenerate on every build — leave the new ones, don't commit).

## Key references

- `CLAUDE.md` — project instructions (note: gate-count numbers are stale, see above).
- `MEMORY.md` (auto-loaded) — cross-session index; `memory/project_current_state.md` is the running state file; `memory/project_pr_epsilon_rotation_first_chrome.md` covers the just-merged work.
- `docs/BACKLOG.md` — full task list (this session's output).
- `docs/adr/` — behavioral invariants. `ROADMAP_v6.md` (reliability), `NEW_UI_BACKEND_REPLAN.md` (UI redesign).

---

## Fresh-session kickoff prompt

Paste this to start the next session:

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.

Orient first: read HANDOFF.md, CLAUDE.md, and the auto-loaded MEMORY.md, then docs/BACKLOG.md.
Don't re-explore what those already establish.

State: master @ 24cdae7, zero open PRs, tree clean (HANDOFF.md + docs/BACKLOG.md untracked,
uncommitted). Tests green on master; device smoke is mandatory (emulators fail CameraX).

I want to work on: <PICK ONE>
  1. Gate-count recount sweep — P1, mechanical: fix CLAUDE.md "28"/"25" → 39 (true measured
     count, full gate list in docs/BACKLOG.md), plus any ADR text citing a count, and correct
     the stale "36" in the memory files. Pure docs, zero code risk.
  2. PR-δ FrontBack PiP — P2: build CaptureTopology.FrontBack (two concurrent camera bindings
     → single-file PiP via CameraX CompositionSettings, capability-gated). Brainstorm → spec →
     plan → ADR-0029 amendment → subagent-driven build. Last unbuilt phase of the mode/
     orientation arc.
  3. Preset UI polish — P1: owner flagged the in-sheet PRESETS section as "kinda mess".
     Start with brainstorming (no spec yet).
  4. A PR-ε follow-up (warning-sheet hoist / thermal-tips portrait-under-lock / BackHandler
     disarm / per-frame-recomposition v2) — see docs/BACKLOG.md "UI Polish" + "Static-Gate".

Constraints: subagents are EDIT-ONLY, controller runs all gradle. Build-env recovery dance
before each build (gradlew --stop → kill java → rm app/build/kotlin + .gradle/kotlin). Use
assembleDebug to gate-build (lintDebug is RED on pre-existing VaultAndroidOps NewApi). Never
edit a check* gate to pass — fix source or amend ADR with sign-off. codex-review code changes
>5 lines. Caveman mode if it was on.
```
