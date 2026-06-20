# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-19 — UI Phase 2 PR-4 (Library + Vault icon migration) is COMPLETE, PR open off `feat/library-icon-pr4` (base `881f8c8`), awaiting owner merge.** 10 source/test files + spec + plan; 46 gates + JVM (+ new `LibraryIconSpecTest`) + codex all green; owner device-smoke GO (Library top-bar glyphs + hero star verified; Vault FLAG_SECURE so visual-only). PR-3 (#124), PR-1 (#122), PR-2 (#123) merged earlier this session. Open PRs: PR-4 + #115 (DualSight, archival/never-merge). NEXT = PR-5 remaining surfaces (Player split → Warnings/Settings/Onboarding; wire `rec_clipcheck`+`interrupted`; plan: `docs/UI_PHASE2_ICON_THEME_AUDIT.md` §7). **Vault screen was pulled forward from PR-5 into PR-4 at owner request.**

---

## Where things stand

- **`master` = `881f8c8`; `origin/master` CURRENT.** UI Phase 2 PR-1 (#122) + PR-2 (#123) + **PR-3 icon foundation (#124, squash, branch deleted)** all merged this session; cue-bleed #121 earlier. **PR-4 (Library + Vault icon migration) is on branch `feat/library-icon-pr4` (base `881f8c8`), PR open, NOT yet merged.** Working tree noisy with ephemeral `gradle_*.log` / `smoke_*.png` / `.superpowers/sdd/` scratch — not committed.
- **PR-4 = the first surface to CONSUME the PR-3 glyphs.** `LibraryIconSpec` (pure, JVM-tested) holds the state→glyph/status decisions; 7 Library components + `VaultScreen` route their identity icons through `SemanticIcon`. Delete→`IconStatus.Danger` (item sheet) / neutral (batch bar); Favorite outline→filled-accent (sheet) / media-safe `overlayText` (hero); selected→`RovaIcons.Select` accent. Nav/utility (Back/Close/Clear/Play/Details) stay stock (identity-only fence). No new gate, no new strings. Full record: `memory/project_icon_glyph_system.md`.
- **Static gates: 46** custom `check*` tasks, all green on master. (46th = `checkSingleColorSchemeSource`, theme engine; 45th = `checkRovaGlyphHome`; the icon seam added `checkSemanticIconNoRawAlpha` + `checkStatusColorLocked`.) **UI Phase 2 PR-1/PR-2 added no new gate.**
- **Open PRs:** **PR-4** (Library + Vault icon migration, `feat/library-icon-pr4`, awaiting owner merge) + **#115 — DualSight investigation** (DRAFT, archival, **never merge** — concurrent-camera hardware-blocked; see `memory/project_pr_delta_dualsight_probe.md`).
- **Test baseline:** JVM unit tests only (`:app:testDebugUnitTest`), green on master. Real-device smoke mandatory for any capture path (device = RZCYA1VBQ2H, Android 14).

### Landed since the last handoff (all on local master, owner device-GO)

- **UI Phase 2 PR-1 — theme hygiene, PR #122 → master `5d8c292`.** New pure `ReducedTransparency` seam: glass→solid collapse now fires on the real OS high-contrast-text signal OR reduce-motion (was reduce-motion only, so high-contrast-text users never got the solid path). `RovaTheme`/`RovaDarkSurface` now `remember(palette) { PaletteColorScheme.from(palette) }`. JVM test; no new gate.
- **UI Phase 2 PR-2 — board-exact Record FAB lifecycle, PR #123 → master `8225c80` (owner device-smoke GO).** Rebuilt the FAB to match `board-3-semantic.html`'s `FB` row exactly (first cut reused primitives → NO-GO; redone). Pure `RecordFabVisualSpec` (visual ⟂ action) drives 5 states: Idle accent-gradient disc + `rec_disc` · Recording red-gradient disc + `rec_morph` (no ring) · Waiting ghost + `waiting` hourglass (cancellable) · Processing ghost + spinning `proc_arc` (inert) · Disabled ghost + `rec_ring`. Authored `RecordRing`/`Waiting`/`ProcArc`/`ProcDots` glyphs (`RovaGlyphs`); `rec_morph` = white Box. **AA (full 4.5:1):** disc = `DialogActionColors`-deepened accent gradient + new `IconRole.OnAccent` (the FAB is on the always-white pinned route, so board-literal white-on-accent would fail light accents). `ThemeContrastTest` pins accent-ghost ≥3:1 + OnAccent ≥4.5:1 × 12 palettes. Open Q (owner GO'd as-is): Disabled FAB shows board-literal faint-ring + **accent core** — revisit if a fully-dim disabled reads better. Plan/status: `docs/UI_PHASE2_ICON_THEME_AUDIT.md` §7. Full record: `memory/project_icon_glyph_system.md`.
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

## Fresh-session kickoff prompt — UI Phase 2 PR-5 (icon system)

Paste this to start the next session. UI Phase 2 PR-3 MERGED (#124); PR-4 (Library + Vault) is PR-open on `feat/library-icon-pr4` awaiting merge; this continues with PR-5 (merge PR-4 first so PR-5 branches off a master that has the consumed-glyph patterns).

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.
Caveman mode + Ultracode ON.

Orient first: read HANDOFF.md, CLAUDE.md, the auto-loaded MEMORY.md, docs/BACKLOG.md, and the
UI Phase 2 plan docs/UI_PHASE2_ICON_THEME_AUDIT.md (esp. §7 — the reconciled PR sequence). Don't
re-explore what those establish. Confirm master is 8225c80 and origin/master is current (UI Phase 2
PR-1 #122 + PR-2 #123 merged; 46 gates) before starting.

SOURCE OF TRUTH for every glyph = .superpowers/brainstorm/1234-1781611237/content/board-3-semantic.html
(extract glyph SVG paths + the E1 semantic map with ctx_execute_file; do NOT eyeball — match the board
exactly, as PR-2's FAB did). Authoring pattern + helpers (glyph/strokePath/fillPath/svgStroke/svgFill/
circle/roundRect/seg) live in ui/theme/RovaGlyphs.kt — the ONLY allowlisted home (checkRovaGlyphHome).
Two-layer duotone = RovaGlyph(outline, accent?); rendered via SemanticIcon (IconRole identity tints,
IconStatus locked colors, IconRole.OnAccent for filled-accent surfaces). RovaIcons = the concept→glyph
collision map.

TASK — PR-3 MERGED (#124). PR-4 (Library + Vault icon migration) is PR-open on feat/library-icon-pr4
(LibraryIconSpec pure helper + 7 Library components + VaultScreen on the SemanticIcon seam; delete→Danger,
favorite outline→filled-accent, selected→Select accent; 46 gates+JVM+codex green; device-GO). Merge PR-4
first, then continue PR-5 (its own branch off master, push + PR + merge only on owner GO):
- PR-5 Remaining surfaces: split Player out FIRST (its own slice); then Warnings/Settings/Onboarding (Vault
  already done in PR-4). Wire the already-authored rec_clipcheck + interrupted glyphs (PR-3) into RovaIcons +
  their surfaces. VISIBLE surfaces → mandatory device smoke (Player is FLAG_SECURE → owner verifies visually).

Process: brainstorm/plan first; pure-helper + JVM tests in the same PR; codex-review the glyph set +
seam wiring (mcp__codex__codex); subagent-driven-dev (subagents EDIT-ONLY, controller runs all gradle/
commits/smoke). Keep all 46 gates + JVM green at every step; never edit a check* to pass. New strings
in en+es (ADR-0022). Build WARM — confirm :app:packageDebug EXECUTED before any adb install -r (debug
APK is debug-signed; if a release build is installed, uninstall first). Mandatory device smoke on
RZCYA1VBQ2H for any visible-surface PR (adb via PowerShell directly; device goes unauthorized on
reconnect — owner re-accepts the USB prompt; Record/Player are FLAG_SECURE so adb screencap can't see
them — owner verifies visually).

First action: confirm PR-4 is merged (or merge on owner GO), then brainstorm/plan PR-5 — start with the
Player slice (enumerate its icon call-sites + target glyphs/roles), then sweep Warnings/Settings/Onboarding,
and wire rec_clipcheck + interrupted into RovaIcons + their surfaces.
```
