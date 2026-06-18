# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-18 — the P1 recording-cue audio-bleed bug is FIXED (PR #121, device-smoke PASS). `master` = `0a8818e` and `origin/master` is now CURRENT (pushed through `0a8818e`; the prior ~50-commit local-only backlog including theme engine slice 1 / PR #120 + Icon P2 Track A is published). Open PRs: #121 (cue-bleed fix, ready to merge) + #115 (DualSight, archival/never-merge). NEXT = owner pick from the next-task shortlist at the bottom.**

---

## Where things stand

- **`master` = `0a8818e`; `origin/master` CURRENT** (the previously-unpushed ~50-commit backlog was published this session on owner GO). The cue-bleed fix lives on pushed branch `fix/beep-cue-bleed` (PR #121, open against master). Working tree clean apart from ephemeral `gradle_*.log` in root.
- **Static gates: 46** custom `check*` tasks, all green on master. (46th = `checkSingleColorSchemeSource`, theme engine; 45th = `checkRovaGlyphHome`; the icon seam added `checkSemanticIconNoRawAlpha` + `checkStatusColorLocked`.) Icon P2 added **no** new gate.
- **Open PRs:** only **#115 — DualSight investigation** (DRAFT, archival, **never merge** — concurrent-camera hardware-blocked; see `memory/project_pr_delta_dualsight_probe.md`).
- **Test baseline:** JVM unit tests only (`:app:testDebugUnitTest`), green on master. Real-device smoke mandatory for any capture path (device = RZCYA1VBQ2H, Android 14).

### Landed since the last handoff (all on local master, owner device-GO)

- **Recording-cue audio-bleed bug — FIXED, PR #121 (branch `fix/beep-cue-bleed`, commit `9848fab`).** Proven on-device: `rova_beep` is a ~3.5 s/4-pulse cue (not "~300 ms"), so `beepStart`'s fixed 1500 ms await ceiling truncated mid-cue and the mic opened on ~3 pulses still playing → bled into every segment (single + DualShot). Fix derives the await ceiling from the cue's real `MediaPlayer.duration` (pure `BeepTiming.beepPlaybackCeilingMs`, JVM-tested), hardens the player lifecycle (`Main.immediate` + `try/finally` release + `OnErrorListener`), reorders `isRecording`, and trims the asset to a 1 s beep. Device-smoke PASS (objective WAV analysis + owner playback). Full record: `memory/project_beep_cue_bleed_fix.md`.
- **Liquid Glass theme engine — slice 1 (palette propagation), PR #120 → master `8a75849`.** Pure `PaletteColorScheme.from(palette): ColorScheme` drives MaterialTheme app-wide (every `colorScheme.*` reader restyles per palette, not just ~3 surfaces); 12-swatch picker (`ThemeSwatchSheet`, Wave-2 exposed, en+es); **46th gate `checkSingleColorSchemeSource`**; `ThemeContrastTest` asserts WCAG AA across all 12 palettes; pinned routes (Record/Player/Onboarding) stay neutral-dark via `PinnedGlassEnvironment`. Full record: `memory/project_theme_engine.md`.
- **Icon P2 Track A — branded merge animation, merged `c4f9baa` (no-ff).** One shared indeterminate-spin primitive (pure `MergeMotion` + `ProcessingGlyph` on the `SemanticIcon` seam) drives both merge surfaces — Record-home `StatusPill` (orange spinner replaces the retired blue `MergingDotColor`) and recovery-card `ProgressStrip` header. Locked `IconStatus.Processing → RovaSemantics.escalating`; reduced-motion static; no new gate. Device-GO on the pill; recovery surface closed by `ProcessingGlyph` parity. Full record: `memory/project_icon_glyph_system.md` (P2 entry).
- **Icon & Glyph System — P0** (`SemanticIcon` tint seam + status-color lock + 2 gates) → **P1a Slice 1** (two-layer `RovaGlyph` pipeline: Library/Settings/Sort/Record) → **Slice 2** (6 brand + 2 orientation glyphs authored; `RecordChromeIcons` folded into `RovaGlyphs` + deleted; `checkRovaGlyphHome` gate) → **Slice 3** (glyphs go LIVE: capture/orientation pickers, Vault, Merge, Recovery; `Single`+`FollowDevice` filler glyphs). System D visual language (outlined soft-monoline + duotone accent channel + locked status colors). `BackgroundRecord` glyph deferred (FGS notification needs a drawable-resource, not an `ImageVector`). Full record: `memory/project_icon_glyph_system.md`.
- **Library item-sheet + Record-settings polish** — compact `LibraryItemSheet`; gear→Info on the per-item details row; DualShot Vault row shown **greyed + reason** (not hidden); **tap a stepper value** (Clip/Repeats/Wait) for direct numeric entry. (Gotcha discovered: phone Record-home renders `FloatingSettingsPanel`, NOT `SettingsSheet` — both reuse the shared `StepperRow`.)
- **Premium app-wide dialog system** — `RovaAlertDialog` (`ui/components/RovaDialogs.kt`) routes **all 13** dialog sites through one branded scaffold: glass depth (lit rim + top lift wash, 32dp corners, soft ambient shadow), a **filled accent-gradient CTA** with a per-theme WCAG-checked label (`DialogActionColors`, pure + JVM-tested, all 12 palettes ≥4.5:1), ghost dismiss, header icon-chip, and a top-right **X-close** for informational dialogs. Rename: "View settings" → **Details**; `history_config_title` → **Recording details**.

## Next development task — shortlist (owner pick)

The P1 cue-bleed bug is done (PR #121). Two parallel survey agents (reliability track + UI track) ranked what's next; synthesis:

**Backbone / reliability — top pick: `RovaRecordingService.kt` split (P2, effort L, needs end-of-work device smoke).** Decompose the largest, most load-bearing file (sole owner of CameraX + MediaMuxer + the segment loop) into seams, behavior-preserving. Lowest-risk large refactor: the 46-gate static suite + JVM tests already pin the invariants, so behavior-preservation is machine-verifiable before smoke. The cue-bleed fix even noted this split would make the beep/capture seams easier to test. No blocker. *(BACKLOG → Reliability/Service.)*

**User-facing — top pick: Rova video-player upgrades (P2, NEEDS-SPEC, effort M/slice).** The in-app Media3 player (`ui/screens/player/`, Media3 pinned 1.4.1) is the most-used surface without a modernization pass and the natural continuation of the Library redesign + theme engine. Brainstorm→spec a shippable slice 1 (e.g. playback-speed + scrub-thumbnail + double-tap-seek), fold in the deferred A11y debt (timeline `progressbar` role + per-cell labels), explicitly exclude the parked 17-tool editor. *(BACKLOG → Video/Player/Editing; nav `NEW_UI_BACKEND_REPLAN.md §A.1`.)*

**Owner-reported pain — DualShot performance (frame jitter + thermal, P2, effort L, device-mandatory).** Strong "why now" (owner-reported 2026-06-17) but **no root-caused repro yet** — needs a device profiling/systrace pass before it can be scoped, and may interact with the δ0 CameraX 1.4.2→1.5.3 bump (P3, SurfaceProcessor-shutdown fix) which is worth investigating first. *(BACKLOG → Capture/Mode; ADR-0009; `memory/project_dualshot_stability_stack.md`.)*

**Smallest self-contained win:** Icon P2 — glass-chip active-state containers (FILL 0→1) + animated states (S/M), rides the live `SemanticIcon` seam + theme engine slice 1. *(ADR-0031/0028 backlog.)*

**Do NOT pick (stale/blocked):** Library/History redesign (DONE, train #117→#119) · `checkA11y*` suite (DONE, #114) · in-app player + HUD merging end-states (already shipped) · PR-δ FrontBack/DualSight (silicon-blocked on RZCYA1VBQ2H) · `UI_ROADMAP.md` (superseded by `NEW_UI_BACKEND_REPLAN.md`).

## Load-bearing rules (don't violate)

- **Never edit a `check*` task to make it green** — fix the source, or amend the ADR + check with explicit owner sign-off. New invariant = ADR clause → new `check*` task → wire into `preBuild`.
- **ADRs are the source of truth.** Touching anything an ADR mentions = amend the ADR clause first, regenerate/extend the matching `check*`, then change code.
- **JVM unit tests only** (`isReturnDefaultValues = true`); framework-touching code gets a pure-Kotlin sibling (seam/pure-helper pattern — e.g. `DialogActionColors`, `ContrastMath`, `SemanticIconSpec`). A feature lands its tests in the same PR.
- **New user-facing strings go in resources** (ADR-0022) in **both** `values/strings.xml` and `values-es/strings.xml`; `checkNoHardcodedUiStrings` enforces it.
- **WCAG 2.2 AA by default** (ADR-0020). Live a11y gates: `checkA11yAnimationGated`, `checkA11yClickableHasRole`, `checkA11yTargetSizeToken` (SC 2.5.8 = ≥24dp). Token/label contrast = JVM math (`TokenContrastTest`, `DialogActionColorsTest`), not a static gate.
- **codex MCP peer review** (`mcp__codex__codex`) mandatory for code changes >5 lines, architecture/design, algorithmic logic, security-sensitive, migrations, perf claims. Skip for conversational/status/trivial.
- **CodeGraph** is initialized — never call `codegraph_explore`/`codegraph_context` from the main session; spawn an Explore agent. Main session may use `codegraph_search`/`callers`/`callees`/`impact`/`node` only.
- **Commit/push/merge only when the owner asks.** Master is local-only ahead of origin; push only on owner GO. Untracked `gradle_*.log` in root are ephemeral — leave them.
- **Subagents EDIT-ONLY; the controller runs all gradle + commits.**

## Build environment

- **Build WARM. Do NOT wipe caches before every build.** The `kotlin-postedit.ps1` hook that corrupted the incremental cache was disabled 2026-06-09. Just `gradlew.bat :app:assembleDebug` (warm ~1–3 min, UP-TO-DATE ~8s; cold ~12–17 min). Clean only on a real kotlinc/MD5 fault: `gradlew.bat --stop` + `rm -rf app/build/kotlin app/build/intermediates/built_in_kotlinc`. A stale incremental cache that falsely reports `compileDebugKotlin` UP-TO-DATE → `assembleDebug --rerun-tasks`.
- **STALE-APK gotcha:** an `assembleDebug` whose RPC times out mid-run can leave `packageDebug` un-run → a stale APK installs and the device shows "no changes". Always confirm `:app:packageDebug` shows **EXECUTED** (not UP-TO-DATE) + a fresh APK mtime before `adb install -r`. `strings` on a `.dex` is useless for verifying symbols (MUTF-8). Pre-validate per-theme/contrast math in a JS sandbox before the ~12-min Android build to avoid a failed-test rebuild cycle.
- **Config cache is OFF** (the 46 gates capture build-script refs; refactor filed P2 in BACKLOG).
- **Windows / PowerShell.** Use `gradlew.bat`. **adb MCP wrapper broken on Windows — drive adb via PowerShell directly.** Device goes `unauthorized` on reconnect — owner must re-accept the USB-debugging prompt.
- **`lintDebug` is RED on a pre-existing B5 `VaultAndroidOps` NewApi** (unrelated) — gate-build with `:app:assembleDebug`.

## Key references

- `CLAUDE.md` — project instructions (now cites **46 gates / 31 ADRs**).
- Theme/glass: `docs/adr/0028-liquid-glass-design-system.md`; `ui/theme/GlassEnvironment.kt`, `GlassResolver.kt`, `GlassSurface.kt`, `RovaPalette.kt` (`rovaPalettes`, `RovaSemantics`). Reference seam: `ui/library/LibraryColors.kt` + `LibraryColorSpec`.
- Icon system: `docs/adr/0031-icon-glyph-system.md`; `ui/theme/RovaGlyphs.kt`, `RovaIcons.kt`; `ui/components/SemanticIcon.kt`; `memory/project_icon_glyph_system.md`.
- `docs/adr/` — behavioral invariants. `docs/BACKLOG.md` — full task list. `ROADMAP_v6.md` (reliability), `NEW_UI_BACKEND_REPLAN.md` (UI redesign), `mockups/new_uiux/` (gitignored).

---

## Fresh-session kickoff prompt — next dev task (owner pick)

Paste this to start the next session. Pick ONE task from the shortlist above; default to the `RovaRecordingService.kt` split unless the owner says otherwise.

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.

Orient first: read HANDOFF.md, CLAUDE.md, the auto-loaded MEMORY.md, and docs/BACKLOG.md. Don't
re-explore what those establish. Confirm master is 0a8818e and origin/master is current (cue-bleed
fix PR #121 open; theme engine + Icon P2 already merged; 46 gates) before starting.

TASK: the P1 cue-bleed bug is DONE (PR #121). Pick the next item from the "Next development task —
shortlist" in HANDOFF.md. Default = RovaRecordingService.kt split (P2): decompose the largest, most
load-bearing file (sole owner of CameraX + MediaMuxer + the segment loop) into seams, behavior-
preserving. The 46-gate static suite + JVM tests pin the invariants — keep them green at every step;
no `check*` may be edited to pass. A real-device capture smoke on RZCYA1VBQ2H is required at the end
(gates prove invariants, not that CameraX still records). If the owner instead picks video-player
upgrades (NEEDS-SPEC) → brainstorm→spec slice 1 first. If DualShot perf → device profiling pass
before any fix.

Process: brainstorm/plan first for any non-trivial task; pure-helper extraction + JVM tests per house
convention; codex-review architecture/refactor boundaries; subagent-driven-dev or executing-plans with
subagents EDIT-ONLY and the controller running all gradle + commits. Build WARM (no cache wipe; confirm
packageDebug EXECUTED before any device install). New user-facing strings in en+es (ADR-0022).
Local-only branch off master; push/merge only when the owner asks.

First action: confirm master state, then brainstorm/plan the chosen task.
```
