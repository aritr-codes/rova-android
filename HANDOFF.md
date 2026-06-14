# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-14.**

---

## Where things stand

- **Branch:** `master` — **HEAD `bd6aa0c`** ("a11y: advance checkA11y* suite — TargetSize gate (41st) + contrast gate superseded by TokenContrastTest (#114)").
  Recent line: `bd6aa0c`(#114) → `3287916`(#113) → `23fb7e4`(#112) → `97b6b9c`(#111) → `e799c06`(#110).
- **Open PRs:** zero. (Only an unrelated stale `feat/dualshot-render-threading` branch lingers on origin.)
- **Working tree:** clean.
- **Test baseline:** JVM unit tests only (`:app:testDebugUnitTest`), green on master. Real-device smoke is mandatory — emulators fail CameraX video recording.
- **Static gates:** **41** custom `check*` tasks wired into `preBuild` (measured; `checkA11yTargetSizeToken` was the 41st, #114).

## What this session did (2026-06-14, latest)

1. **`checkA11yTargetSizeToken` — 41st static gate (#114, `bd6aa0c`).** Enforces every interactive-target size token in `ui/theme/*Tokens*.kt` is `>= 24.dp` (WCAG AA SC 2.5.8 — **not** Material's 48dp guideline). **Curated, enumerated set** (`camControlSize`, `stepperButtonSize`, `stepBtnSize`, `primaryActionSize`, `stopActionSize`, `fabSize`, `navIconBoxSize`, `tileMinHeight`) rather than a `*Size` name scan — a pattern scan would false-fail the decorative/glyph/divider tokens (`statusDotSize` 6, `navIconGlyphSize` 20, `stopSquareSize` 18). Block-comment-aware, `// a11y-opt-out: <reason>` hatch, fails safe toward false-pass. `cellSlot` excluded (its 24dp floor is the parent card `heightIn(min=48)`, not the token). Source was already green; negative-tested (`camControlSize`→20.dp fails). codex-reviewed (block-comment false-fail + cellSlot proxy fixed).
2. **`checkA11yNoLowAlphaTextToken` SUPERSEDED by `TokenContrastTest` (#114).** Audit (real WCAG ratios via `ContrastMath` over each token's actual background) found the contrast source **already fully AA-compliant** — every `*Text`/`*Label` token ≥4.5:1 over its surface; only `modeTabDisabledText` (3.79:1) is below and SC 1.4.3 exempts disabled controls. The sketched `alpha<0.55` regex is the **wrong mechanism** (contrast is background-dependent → would false-fail AA-passing tokens). `TokenContrastTest` extended to all 15 text tokens = the authoritative SC 1.4.3 guard. ADR-0020 + CLAUDE.md amended; codex endorsed the gate→test call.
3. **a11y `checkA11y*` suite CLOSED.** ADR-0020's 4 sketched gates resolved: 3 built as `preBuild` gates (`checkA11yAnimationGated` / `checkA11yClickableHasRole` / `checkA11yTargetSizeToken`), 1 enforced by `TokenContrastTest`. No device smoke needed (build-logic + JVM test only).
4. **Backlog gap-check (owner-requested).** Added UI/UX Modernization (Enhanced Library/History, more themes, icon modernization), Video/Player/Editing (player upgrades, expanded editing cross-ref), Modern-app QoL sections to `docs/BACKLOG.md`. Filed a `TokenContrastTest` hardening follow-up (P3, per codex: auto-fail on new tokens / centralize bg refs / theme-variant coverage).

**Earlier 2026-06-14:** #112 (`23fb7e4`) preset **tile-grid** popup + responsive **`ChromeScale`** strip/panel (`cellSlot` 48→44dp; `∞` repeats cell; ADR-0029 §B″3 amended) + #113 (`3287916`) panel **AA touch-padding** (SC 2.5.8=24dp). Both device-smoke GO RZCYA1VBQ2H.

## Build-environment (UPDATED 2026-06-14 — the "recovery dance" is OBSOLETE)

- **Build WARM. Do NOT wipe caches before every build.** The hook that used to corrupt the Kotlin incremental cache — `~/.claude/hooks/kotlin-postedit.ps1`, which spawned `:app:detekt` (not a task here) + `:app:compileDebugKotlin` on every `.kt` save — was **disabled 2026-06-09** (no-op). Just run `./gradlew.bat :app:assembleDebug` (no `--stop`, no `rm`): a small change builds in **~1–3 min** (an UP-TO-DATE warm build ran in **8s** this session) vs ~17 min cold.
- **Clean only on-demand:** if a build actually fails with a weird kotlinc/MD5/incremental error, then once: `gradlew.bat --stop` + `rm -rf app/build/kotlin app/build/intermediates/built_in_kotlinc`.
- **Need only the APK?** Run `:app:assembleDebug` alone; run `:app:testDebugUnitTest` separately/less often.
- `gradle.properties` is well-tuned (parallel, build cache, 4 GB heap). **Config cache is OFF** — the 41 gates capture build-script refs the config cache can't serialize; refactor filed as a P2 build-logic item in `docs/BACKLOG.md`.
- **Subagent EDIT-ONLY** was a workaround for that now-disabled hook; still a fine batching convention (controller runs builds), but no longer mandatory for cache-safety.
- **Windows / PowerShell.** Use `gradlew.bat`. The **adb MCP wrapper is broken on Windows** — drive adb via PowerShell directly.
- **`lintDebug` is RED on pre-existing B5 `VaultAndroidOps` NewApi** (unrelated) — use `:app:assembleDebug` to gate-build.

## Top next candidates (from the backlog)

1. **PR-δ FrontBack PiP (aka DualSight mode)** — P2, **the headline / next up**. The last unbuilt phase of ADR-0029 (Accepted). `FrontBack` is only a `CaptureTopology` enum value + UI refs (surfaced as the **DualSight** mode tab, alongside Auto/DualShot) + the `checkFrontBackCapabilityGated` fence today; **no concurrent-camera source under `service/`**. ADR specifies single-file picture-in-picture via CameraX `CompositionSettings`, hardware-capability-gated. Full cycle: brainstorm → spec → plan → ADR-0029 amendment → subagent-driven build → **device smoke** (mandatory). *(The `checkA11y*` suite, formerly #1, is **CLOSED** this session via #114.)*
2. **PR-ε follow-ups** (all KDoc'd, deferred): warning-sheet visibility hoist out of `WarningCenter`; thermal-tips portrait-under-lock; `FloatingSettingsPanel` BackHandler disarm during exit; per-frame recomposition during the 180ms spin (v2: read spin angle as `State<Float>` inside the `graphicsLayer` lambda); two cosmetic landscape overflow bugs. See `docs/BACKLOG.md`.
3. **`RovaRecordingService.kt` split** — P2 (largest file; the static-gate suite pins the invariants that must survive the split).
4. **`TokenContrastTest` hardening** — P3 (codex follow-up from #114): auto-fail on new uncovered text tokens (reflection), centralize background refs, theme-variant coverage. See `docs/BACKLOG.md`.

## Load-bearing rules (don't violate)

- **Never edit a `check*` task to make it green** — fix the source, or amend the ADR + check with explicit owner sign-off. New invariant = ADR clause → new `check*` → wire into `preBuild`.
- **ADRs are the source of truth.** Touching anything an ADR mentions = amend the ADR clause first, regenerate/extend the matching `check*`, then change code.
- **codex MCP peer review** (`mcp__codex__codex`) mandatory for code changes >5 lines, architecture/design, algorithmic logic, security-sensitive, migrations, perf claims. Skip for conversational/status/trivial.
- **CodeGraph** is initialized — never call `codegraph_explore`/`codegraph_context` from the main session; spawn an Explore agent. Main session may use `codegraph_search`/`callers`/`callees`/`impact`/`node` for targeted lookups only.
- **JVM unit tests only** (`isReturnDefaultValues = true`); framework-touching code gets a pure-Kotlin sibling (the seam/pure-helper pattern). A new feature lands its tests in the same PR.
- **Stacked-PR merge train:** merge base WITHOUT `--delete-branch` → rebase dependent `--onto origin/master <old-base>` → re-target/re-push → THEN delete. `--delete-branch` auto-retarget is unreliable (it has auto-closed #107/#95/#75 historically). See `memory/feedback_stacked_pr_merge_train.md`.
- Untracked `gradle_*.log` in root are ephemeral verification artifacts — leave the new ones, don't commit.

## Key references

- `CLAUDE.md` — project instructions (gate count **41**, ADR count **29** — both current).
- `MEMORY.md` (auto-loaded) — cross-session index; `memory/project_current_state.md` is the running state file; `memory/project_build_env_perf.md` is the corrected build-speed note.
- `docs/BACKLOG.md` — full task list. `docs/adr/` — behavioral invariants. `ROADMAP_v6.md` (reliability), `NEW_UI_BACKEND_REPLAN.md` (UI redesign).

---

## Fresh-session kickoff prompt

Paste this to start the next session:

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.

Orient first: read HANDOFF.md, CLAUDE.md, and the auto-loaded MEMORY.md, then docs/BACKLOG.md +
docs/adr/0029-capture-topology-orientation-policy.md. Don't re-explore what those already establish.

State: master HEAD = bd6aa0c (#114), zero open PRs, tree clean. Tests green on master; 41 check*
gates; device smoke is MANDATORY (emulators fail CameraX video recording; device = RZCYA1VBQ2H).
Last session: #114 closed the checkA11y* suite — built checkA11yTargetSizeToken (41st gate, SC
2.5.8 >=24dp) + superseded checkA11yNoLowAlphaTextToken with TokenContrastTest (contrast is
background-dependent, a static alpha gate would false-fail AA-passing tokens). Also added owner-
requested backlog sections (UI/UX Modernization, Video/Player/Editing, QoL).

TASK: PR-δ FrontBack PiP — aka **DualSight** mode. The last unbuilt phase of the owner-ratified
mode/orientation re-architecture (ADR-0029, Accepted; α/β/γ/ε all merged).

What it is: build CaptureTopology.FrontBack — TWO concurrent camera bindings (front + back) composited
into a SINGLE-FILE picture-in-picture clip via CameraX concurrent-camera + CompositionSettings,
HARDWARE-CAPABILITY-GATED (many devices don't support concurrent front+back). This is the user-facing
"DualSight" capture mode (the third mode tab alongside Auto/Single and DualShot).

Current state (verified): FrontBack exists ONLY as a CaptureTopology enum value (data/
CaptureTopology.kt) + UI refs (ui/screens/CaptureModes.kt — the DualSight tab), fenced by the
checkFrontBackCapabilityGated gate (app/build.gradle.kts ~L1878), which forbids "FrontBack" anywhere
but those two files. There is NO concurrent-camera implementation under service/. NOTE: that gate's
own KDoc says "PR-δ extends the allowlist with the concurrent-camera module it builds" — so adding
your new module to its `allow` set is SANCTIONED, not "editing a gate to pass"; still amend ADR-0029
§5 to record the new allowlisted module.

Distinct from DualShot (service/dualrecord/): DualShot is ONE sensor -> two 27/64 crops via EGL
fan-out (ADR-0009). DualSight/FrontBack is TWO physical sensors concurrent -> one PiP frame. Decide
the compositor: ADR-0029 / replan decision C specifies single-file PiP via CameraX CompositionSettings
(not a hand-rolled EGL compositor) — confirm 1.4.2 supports it; if not, that's a brainstorm decision.

Technical seeds: CameraX 1.4.2 concurrent camera — gate on PackageManager.FEATURE_CAMERA_CONCURRENT +
ProcessCameraProvider.availableConcurrentCameraInfos; bind both lenses; CompositionSettings for the
single-surface PiP layout. RovaRecordingService is the sole CameraX owner (mind the STOP-path /
merge invariants the gates pin). Likely SessionManifest schema bump (DualShot is schema 11). Pause/
resume probably DEFERRED (DualShot precedent). UI must hide/disable DualSight when unsupported, with
a bind-fail fallback (LensFlipPolicy precedent for fallback).

Pipeline (this is a feature, NOT a mechanical add): brainstorm (superpowers:brainstorming) -> spec ->
plan -> ADR-0029 amendment (add the FrontBack BUILD clauses + §5 allowlist update; today §5 only
fences it) -> subagent-driven build -> device smoke. A new feature lands its tests in the same PR
(JVM-only; CameraX/framework-touching code gets a pure-Kotlin sibling per the seam/pure-helper
pattern, isReturnDefaultValues=true).

Build FAST: WARM — just `gradlew.bat :app:assembleDebug` (NO --stop, NO cache wipe; the old "recovery
dance" is obsolete — kotlin-postedit hook disabled 2026-06-09; warm builds seconds-to-minutes). Clean
ONLY on a real kotlinc/MD5 fault. Gate-build with assembleDebug (lintDebug is RED on pre-existing
VaultAndroidOps NewApi).

Key refs: ADR-0029 (capture-topology × orientation-policy); memory/project_mode_orientation_replan.md
(decision C — FrontBack single-file PiP via CompositionSettings); memory/project_pr_gamma_capture_
topology.md (DualSight mode, schema 11, the 4 PR-γ gates); data/CaptureTopology.kt;
ui/screens/CaptureModes.kt; service/dualrecord/ (DualShot dual-encode/muxer/audio-fanout precedent);
checkFrontBackCapabilityGated (build.gradle.kts ~L1878).

Other constraints: never edit a check* gate to pass (the checkFrontBackCapabilityGated allowlist
extension above is the one sanctioned exception — pair it with the ADR-0029 §5 amendment). codex-
review code changes >5 lines / architecture / CameraX concurrency. CodeGraph exploration via Explore
agent only (.codegraph/ exists). Caveman mode + Ultracode if they were on.
```
