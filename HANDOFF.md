# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-14 (DualSight investigation wrap-up).**

---

## Where things stand

- **Branch:** `master` — **HEAD `47f97f7`** ("docs: post-#114 handoff"), whose last feature/gate commit is **`bd6aa0c` (#114)**. **master is unchanged this session** (the wrap-up doc updates live on the branch / PR #115, not yet on master).
- **Open PRs:** the DualSight wrap-up is on **`feat/pr-delta-dualsight`** (pushed; a **DRAFT** PR for the docs + preserved capability helper — *not for merge yet*). A throwaway **`probe/dualsight-concurrent-camera`** branch is pushed as evidence only (never merge it). Plus the long-stale unrelated `feat/dualshot-render-threading`.
- **Working tree:** clean except the ephemeral untracked root `gradle_*.log` (leave them).
- **Test baseline:** JVM unit tests only (`:app:testDebugUnitTest`), green on master. Real-device smoke is mandatory — emulators fail CameraX video recording (device = RZCYA1VBQ2H).
- **Static gates:** **41** custom `check*` tasks wired into `preBuild`.

## What this session did (2026-06-14) — DualSight investigation + decision

**Outcome: DualSight (PR-δ FrontBack PiP) is DEFERRED / BLOCKED. Nothing merged to master.**

1. **Brainstormed → spec'd → planned PR-δ** (DualSight = concurrent front+back single-file PiP via CameraX `CompositionSettings`). Found CameraX 1.4.2 can't do it (needs 1.5.0+), so structured it as δ-probe → δ0 (bump) → δ (feature).
2. **Built + ran a hardware probe** (throwaway branch, CameraX bumped 1.5.3 locally, debug-only `DualSightProbeActivity` with capability queries + real-record attempt A (CameraX) + attempt B (raw Camera2) + rebind-torture). **On-device verdict (RZCYA1VBQ2H, Galaxy A-class): FAIL — hardware truth.** `FEATURE_CAMERA_CONCURRENT=false`, `getConcurrentCameraIds()` empty, `availableConcurrentCameraInfos` empty, `bindToLifecycle` threw `UnsupportedOperationException("Concurrent camera is not supported")`. Three independent layers agree; not a probe artifact.
3. **Analysis: no non-concurrent path to simultaneous front+back *video*** (rapid-switch <3fps; sequential/BeReal = photos = different feature; LOGICAL_MULTI_CAMERA same-direction-only; no Camera2 bypass). codex-concurred. Owner ruled out a photo fallback.
4. **Decision review (DualSight assumed never ships):** the **δ0 CameraX 1.5.3 bump stands on its own** as *low-urgency reliability hygiene* → **merge later** (after regression smoke), not now, not never. Honest recalibration: the rotation-on-recreate fix is largely self-defended; the real pillar is the SurfaceProcessor-shutdown crash fix (DualShot path).
5. **Wrap-up:** DualSight deferred everywhere (ADR-0029 §5 status note, spec banner, BACKLOG, deferred plan); δ0 plan written (codex-hardened smoke checklist); capability helper `ConcurrentCameraCapability` + 6 tests preserved on `feat/pr-delta-dualsight`.

**Key artifacts (all on `feat/pr-delta-dualsight`):**
- `docs/superpowers/specs/2026-06-14-dualsight-frontback-pip-design.md` — design (banner: DEFERRED).
- `docs/superpowers/specs/2026-06-14-dualsight-probe-results.md` — **authoritative evidence** (+ `gradle_dualsight_probe.log`).
- `docs/superpowers/plans/2026-06-14-delta0-camerax-1.5.3-bump.md` — δ0 bump plan (merge-later).
- `docs/superpowers/plans/2026-06-14-dualsight-delta-feature-DEFERRED.md` — δ feature plan (BLOCKED + resume preconditions).
- `app/src/main/java/com/aritr/rova/service/dualsight/ConcurrentCameraCapability.kt` + test — preserved keeper.

## Build-environment (the "recovery dance" is OBSOLETE)

- **Build WARM. Do NOT wipe caches before every build.** The `kotlin-postedit.ps1` hook that corrupted the Kotlin incremental cache was **disabled 2026-06-09**. Just `gradlew.bat :app:assembleDebug` (no `--stop`, no `rm`): warm ~1–3 min (UP-TO-DATE ~8s) vs ~17 min cold. A clean 1.5.3 `assembleDebug` ran ~5 min this session.
- **Clean only on-demand** on a real kotlinc/MD5 fault: `gradlew.bat --stop` + `rm -rf app/build/kotlin app/build/intermediates/built_in_kotlinc`.
- `gradle.properties` well-tuned. **Config cache is OFF** (the 41 gates capture build-script refs; refactor filed P2 in BACKLOG).
- **Windows / PowerShell.** Use `gradlew.bat`. **adb MCP wrapper broken on Windows — drive adb via PowerShell directly** (it works fine that way; install/grant/launch/logcat all ran clean this session).
- **`lintDebug` is RED on pre-existing B5 `VaultAndroidOps` NewApi** (unrelated) — gate-build with `:app:assembleDebug`.

## Top next candidates (from the backlog)

1. **Enhanced Library & History UI** — **P2 · NEEDS-SPEC · the headline / next up** (selected this session as the highest-value DualSight-independent task on current hardware). The surfaces where users browse all recordings have had no visual modernization pass. Liquid-Glass redesign: thumbnails, date/session grouping, sort + filter, multi-select / batch actions, polished empty + loading states; folds in deferred a11y focus rows (23/32). Full pipeline: brainstorm → spec → plan → build → device smoke.
2. **Rova video-player upgrades** (P2, NEEDS-SPEC) **or** **`RovaRecordingService.kt` split** (P2 tech-debt). Player = high-engagement Media3 surface (speed control, scrub thumbnails, gestures, a11y timeline row 21). Service split = highest-leverage refactor (largest file; gates pin invariants), no user value, regression-smoke after.
3. **δ0 CameraX 1.5.3 bump** (P3, merge-later) — standalone reliability bump, plan ready (`docs/superpowers/plans/2026-06-14-delta0-camerax-1.5.3-bump.md`); execute its regression smoke next time there's device time.

## Load-bearing rules (don't violate)

- **Never edit a `check*` task to make it green** — fix the source, or amend the ADR + check with explicit owner sign-off. (The `checkFrontBackCapabilityGated` allowlist extension is sanctioned **only** when DualSight is actually built, paired with an ADR-0029 §5 amendment — that is still pending/blocked.)
- **ADRs are the source of truth.** Touching anything an ADR mentions = amend the ADR clause first, regenerate/extend the matching `check*`, then change code.
- **codex MCP peer review** (`mcp__codex__codex`) mandatory for code changes >5 lines, architecture/design, algorithmic logic, security-sensitive, migrations, perf claims. Skip for conversational/status/trivial.
- **CodeGraph** is initialized — never call `codegraph_explore`/`codegraph_context` from the main session; spawn an Explore agent. Main session may use `codegraph_search`/`callers`/`callees`/`impact`/`node` for targeted lookups only.
- **JVM unit tests only** (`isReturnDefaultValues = true`); framework-touching code gets a pure-Kotlin sibling (seam/pure-helper pattern). A new feature lands its tests in the same PR.
- **Stacked-PR merge train:** merge base WITHOUT `--delete-branch` → rebase dependent `--onto origin/master <old-base>` → re-target/re-push → THEN delete. See `memory/feedback_stacked_pr_merge_train.md`.
- Untracked `gradle_*.log` in root are ephemeral — leave them, don't commit (the **committed** `gradle_dualsight_probe.log` on the DualSight branch is a deliberate evidence exception).

## Key references

- `CLAUDE.md` — project instructions (gate count **41**, ADR count **29**).
- `MEMORY.md` (auto-loaded) — cross-session index; `memory/project_current_state.md` running state; `memory/project_pr_delta_dualsight_probe.md` is the DualSight investigation record; `memory/project_build_env_perf.md` build-speed note.
- `docs/BACKLOG.md` — full task list. `docs/adr/` — behavioral invariants. `ROADMAP_v6.md` (reliability), `NEW_UI_BACKEND_REPLAN.md` (UI redesign).

---

## Fresh-session kickoff prompt

Paste this to start the next session:

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.

Orient first: read HANDOFF.md, CLAUDE.md, and the auto-loaded MEMORY.md, then docs/BACKLOG.md
(see the "UI/UX Modernization" section). Don't re-explore what those already establish.

State: master HEAD = 47f97f7 (post-#114 handoff doc; last feature commit bd6aa0c = #114), unchanged
this session. master has zero open PRs; a DRAFT PR exists on
feat/pr-delta-dualsight (DualSight investigation docs + preserved capability helper — DO NOT merge,
it is wrap-up only) and a throwaway probe/dualsight-concurrent-camera branch (evidence only, never
merge). Tests green on master; 41 check* gates; device smoke MANDATORY (emulators fail CameraX
recording; device = RZCYA1VBQ2H). DualSight (PR-δ FrontBack PiP) is DEFERRED/BLOCKED pending a
concurrent-camera-capable device — do NOT pick it up.

TASK: Enhanced Library & History UI — the highest-value next feature (owner-requested 2026-06-14,
NEEDS-SPEC). The Library and History surfaces (where users browse all their recordings) have had no
visual modernization pass — only the #100 nav state-retention fix and deferred a11y focus rows.

Objective: redesign Library + History to the Liquid Glass design language (ADR-0028, the shipped
12-theme system) — richer thumbnails, date/session grouping, sort + filter, multi-select / batch
actions (delete/share/export/vault), and polished empty + loading states. Fold in the deferred a11y
focus rows (23/32: focus order/restore/focusable gaps, History warning-card overlapping focus) and,
where the player timeline is touched, a11y row 21 (SegmentedTimeline progressbar role + per-cell
labels).

Current state to build on: HistoryScreen + HistoryViewModel (ui/screens/), the Library/History
surfaces; theme system in ui/theme/ (RovaTokens, the 12 Liquid Glass themes, ADR-0028); shared chrome
in ui/components/. Recordings come from SessionStore/SessionManifest (data/); export via the tiered
exporters (service/export/) + SAF (ADR-0024) + vault (ADR-0025, B5). Player is ui/screens/player/
(Media3 1.4.1). This is a feature, NOT a mechanical edit.

Pipeline: superpowers:brainstorming (HARD GATE — present a design, get owner approval before any
code) -> spec (docs/superpowers/specs/) -> writing-plans -> subagent-driven build -> device smoke on
RZCYA1VBQ2H. A new feature lands its tests in the same PR (JVM-only; framework-touching code gets a
pure-Kotlin sibling per the seam/pure-helper pattern, isReturnDefaultValues=true). If the redesign
needs new user-facing strings, they go in resources (ADR-0022, en+es) — checkNoHardcodedUiStrings
will enforce it.

Constraints: never edit a check* gate to pass (fix source or amend ADR+check with owner sign-off).
ADRs are source of truth — amend the relevant ADR (likely an ADR-0028 amendment or a new ADR for the
Library/History redesign) before/with the build. codex-review code changes >5 lines / architecture /
any non-trivial UI-state logic. CodeGraph exploration via Explore agent only (.codegraph/ exists).
Accessibility is WCAG 2.2 AA by default (ADR-0020) for all new/changed UI.

Build FAST: WARM — just `gradlew.bat :app:assembleDebug` (NO --stop, NO cache wipe; recovery dance
obsolete). Gate-build with assembleDebug (lintDebug is RED on pre-existing VaultAndroidOps NewApi).

Success criteria: a Liquid-Glass-native Library/History with grouping + sort/filter + multi-select
batch actions + polished empty/loading states; the deferred a11y focus rows addressed for these
surfaces; all new logic JVM-tested; assembleDebug + all 41 gates green; device-smoke GO on
RZCYA1VBQ2H. Brainstorm + owner design approval BEFORE writing code.

Key refs: docs/BACKLOG.md "UI/UX Modernization" (Enhanced Library & History UI); ADR-0028 (Liquid
Glass + 12-theme system); ADR-0020 (WCAG AA); docs/accessibility/remediation-backlog.md rows 21/23/32;
ui/screens/ (History), ui/screens/player/, ui/theme/, data/ (SessionStore/SessionManifest).
memory/project_settings_expansion_b1.md + _b2.md (History/theme precedent). Caveman mode + Ultracode
if they were on.
```
