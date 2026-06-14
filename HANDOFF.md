# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-14.**

---

## Where things stand

- **Branch:** `master` — **HEAD `3287916`** ("a11y: panel touch targets to WCAG 2.2 AA (24dp) (#113)").
  Recent line: `3287916`(#113) → `23fb7e4`(#112) → `97b6b9c`(#111) → `e799c06`(#110) → `24cdae7`(#108).
- **Open PRs:** zero. (Only an unrelated stale `feat/dualshot-render-threading` branch lingers on origin.)
- **Working tree:** clean.
- **Test baseline:** JVM unit tests only (`:app:testDebugUnitTest`), green on master. Real-device smoke is mandatory — emulators fail CameraX video recording. Device-smoke GO on RZCYA1VBQ2H this session.
- **Static gates:** **40** custom `check*` tasks wired into `preBuild` (measured; `checkA11yClickableHasRole` was the 40th, #110).

## What this session did (2026-06-14)

1. **Preset UI polish + responsive record-chrome sizing (#112, `23fb7e4`).** Brainstorm → spec → plan → subagent-driven:
   - **Preset popup:** uniform **tile grid** replaces the ragged content-width chip flow on both surfaces (`FloatingSettingsPanel` + `SettingsSheet`); bottom **scroll-fade** discoverability cue; pure `presetTileSummary`.
   - **Config strip:** slimmer + responsive — `cellSlot` 48→44dp, `settingsCardPaddingV` 7→6dp, slot × new **`ChromeScale`** (device-anchored on `smallestScreenWidthDp`; 1.0 at the 411dp reference device with a ±1dp snap, clamped 0.88–1.15). 48dp touch target preserved by the card's `heightIn(min=48)`. Repeats cell now shows **`∞`** for continuous (was "Until you stop") — `recordRepeatsStepperValue` → `recordRepeatsCompactValue`, shared by strip + every stepper; the wider Settings-tab row keeps `recordRepeatsValue`.
   - **Floating panel:** side cap × `ChromeScale` — byte-identical (320dp) on the ref device, scales elsewhere.
   - ADR-0029 §B″3 amended for the 44dp slot + ChromeScale. codex-reviewed + a 4-lens adversarial verify pass. Device-smoke GO.
2. **ADR-0020 AA touch-target nudge (#113, `3287916`).** The compact panel's two sub-24dp controls (the AA bar is **SC 2.5.8 = 24dp**, not the 48dp Material guideline; the 2026-05-30 audit already documented skipping touch targets at that bar) raised via **touch-padding only**: Edit/Done toggle `v 4→7dp` (~26dp), QualityChip `heightIn(min = 24.dp)`. Everything else already passed AA. Documented in `docs/accessibility/remediation-backlog.md`.
3. **Build-speed fix** (see the rewritten build-env section below): the per-build cache-wipe "dance" is **obsolete** — warm builds are ~8s vs ~17 min cold. Filed a config-cache gate-refactor optimization (P2) in `docs/BACKLOG.md`.
4. **Merge train** #112 → rebase a11y `--onto master` (clean) → #113, then deleted the merged `feat/preset-ui-polish` branch.

## Build-environment (UPDATED 2026-06-14 — the "recovery dance" is OBSOLETE)

- **Build WARM. Do NOT wipe caches before every build.** The hook that used to corrupt the Kotlin incremental cache — `~/.claude/hooks/kotlin-postedit.ps1`, which spawned `:app:detekt` (not a task here) + `:app:compileDebugKotlin` on every `.kt` save — was **disabled 2026-06-09** (no-op). Just run `./gradlew.bat :app:assembleDebug` (no `--stop`, no `rm`): a small change builds in **~1–3 min** (an UP-TO-DATE warm build ran in **8s** this session) vs ~17 min cold.
- **Clean only on-demand:** if a build actually fails with a weird kotlinc/MD5/incremental error, then once: `gradlew.bat --stop` + `rm -rf app/build/kotlin app/build/intermediates/built_in_kotlinc`.
- **Need only the APK?** Run `:app:assembleDebug` alone; run `:app:testDebugUnitTest` separately/less often.
- `gradle.properties` is well-tuned (parallel, build cache, 4 GB heap). **Config cache is OFF** — the 40 gates capture build-script refs the config cache can't serialize; refactor filed as a P2 build-logic item in `docs/BACKLOG.md`.
- **Subagent EDIT-ONLY** was a workaround for that now-disabled hook; still a fine batching convention (controller runs builds), but no longer mandatory for cache-safety.
- **Windows / PowerShell.** Use `gradlew.bat`. The **adb MCP wrapper is broken on Windows** — drive adb via PowerShell directly.
- **`lintDebug` is RED on pre-existing B5 `VaultAndroidOps` NewApi** (unrelated) — use `:app:assembleDebug` to gate-build.

## Top next candidates (from the backlog)

1. **Build the `checkA11y*` static-gate suite** — P2 (ADR-0020 Proposed). Only `checkA11yAnimationGated` + `checkA11yClickableHasRole` exist today; add semantics-presence / live-region / reduced-motion / touch-target gates per the invariant→`check*`→`preBuild` convention. *(The preset UI polish that used to be the P1 candidate is **DONE** this session via #112/#113.)*
2. **PR-δ FrontBack PiP** — P2, the last unbuilt phase of ADR-0029 (Accepted). `FrontBack` is only an enum + UI refs + the `checkFrontBackCapabilityGated` fence today; no concurrent-camera source under `service/`. ADR specifies single-file PiP via CameraX `CompositionSettings`, capability-gated.
3. **PR-ε follow-ups** (all KDoc'd, deferred): warning-sheet visibility hoist out of `WarningCenter`; thermal-tips portrait-under-lock; `FloatingSettingsPanel` BackHandler disarm during exit; per-frame recomposition during the 180ms spin (v2: read spin angle as `State<Float>` inside the `graphicsLayer` lambda); two cosmetic landscape overflow bugs. See `docs/BACKLOG.md`.
4. **`RovaRecordingService.kt` split** — P2 (largest file; the static-gate suite pins the invariants that must survive the split).

## Load-bearing rules (don't violate)

- **Never edit a `check*` task to make it green** — fix the source, or amend the ADR + check with explicit owner sign-off. New invariant = ADR clause → new `check*` → wire into `preBuild`.
- **ADRs are the source of truth.** Touching anything an ADR mentions = amend the ADR clause first, regenerate/extend the matching `check*`, then change code.
- **codex MCP peer review** (`mcp__codex__codex`) mandatory for code changes >5 lines, architecture/design, algorithmic logic, security-sensitive, migrations, perf claims. Skip for conversational/status/trivial.
- **CodeGraph** is initialized — never call `codegraph_explore`/`codegraph_context` from the main session; spawn an Explore agent. Main session may use `codegraph_search`/`callers`/`callees`/`impact`/`node` for targeted lookups only.
- **JVM unit tests only** (`isReturnDefaultValues = true`); framework-touching code gets a pure-Kotlin sibling (the seam/pure-helper pattern). A new feature lands its tests in the same PR.
- **Stacked-PR merge train:** merge base WITHOUT `--delete-branch` → rebase dependent `--onto origin/master <old-base>` → re-target/re-push → THEN delete. `--delete-branch` auto-retarget is unreliable (it has auto-closed #107/#95/#75 historically). See `memory/feedback_stacked_pr_merge_train.md`.
- Untracked `gradle_*.log` in root are ephemeral verification artifacts — leave the new ones, don't commit.

## Key references

- `CLAUDE.md` — project instructions (gate count **40**, ADR count **29** — both current).
- `MEMORY.md` (auto-loaded) — cross-session index; `memory/project_current_state.md` is the running state file; `memory/project_build_env_perf.md` is the corrected build-speed note.
- `docs/BACKLOG.md` — full task list. `docs/adr/` — behavioral invariants. `ROADMAP_v6.md` (reliability), `NEW_UI_BACKEND_REPLAN.md` (UI redesign).

---

## Fresh-session kickoff prompt

Paste this to start the next session:

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.

Orient first: read HANDOFF.md, CLAUDE.md, and the auto-loaded MEMORY.md, then docs/BACKLOG.md.
Don't re-explore what those already establish.

State: master HEAD = 3287916 (#113 a11y AA touch targets), zero open PRs, tree clean. Tests green
on master; device smoke is mandatory (emulators fail CameraX). Last session shipped #112 (preset
tile-grid + responsive ChromeScale sizing + ∞ repeats cell, ADR-0029 §B″3 amended) and #113 (panel
AA touch targets, SC 2.5.8 = 24dp).

I want to work on: <PICK ONE>
  1. Build the checkA11y* static-gate suite — P2 (ADR-0020 Proposed): only checkA11yAnimationGated +
     checkA11yClickableHasRole exist; add semantics-presence / live-region / reduced-motion /
     touch-target gates per the invariant->check*->preBuild convention.
  2. PR-δ FrontBack PiP — P2: build CaptureTopology.FrontBack (two concurrent camera bindings ->
     single-file PiP via CameraX CompositionSettings, capability-gated). Brainstorm -> spec -> plan
     -> ADR-0029 amendment -> subagent-driven build. Last unbuilt phase of the mode/orientation arc.
  3. A PR-ε follow-up (warning-sheet hoist / thermal-tips portrait-under-lock / BackHandler disarm /
     per-frame-recomposition v2) — see docs/BACKLOG.md "UI Polish" + "Static-Gate".
  4. RovaRecordingService.kt split — P2 (largest file; gates pin the invariants).

Build FAST: build WARM — just `gradlew.bat :app:assembleDebug` (NO --stop, NO cache wipe). The old
"recovery dance" is obsolete (the kotlin-postedit hook was disabled 2026-06-09); warm builds are
seconds-to-minutes. Clean ONLY if a build errors with a kotlinc/MD5 fault. Use assembleDebug to
gate-build (lintDebug is RED on pre-existing VaultAndroidOps NewApi).

Other constraints: never edit a check* gate to pass — fix source or amend ADR with sign-off.
codex-review code changes >5 lines. CodeGraph exploration via Explore agent only. Caveman mode +
Ultracode if they were on.
```
