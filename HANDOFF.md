# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-22 — PR-6 player-nav + icon 5b-1 glyph-foundation BOTH MERGED (squash) → `master 55dd894`.** PR-6 (#127): interactive player timeline — continuous duration-proportional bar with clip-boundary ticks, tap-to-seek + throttled drag-scrub, clip prev/next jumps, one seekbar a11y node ("Clip N of M" + prev/next custom actions), visual-only reduced-motion playhead glide; removed the redundant center play overlay (single control-row play); resume-from-position **deferred** (see below). Icon 5b-1 (#128): `Play` board d-string + `Interrupted→escalating`, `FlipCam`+re-authored `FlashBolt`, **24 new System-D glyphs** (board v3 SSOT) + `RovaIcons` map (27 concept + 2 status entries). Built via parallel git worktrees, subagent-driven; 46 gates + full JVM green; final-review + codex caught & fixed 2 scrub state-machine bugs. Open PRs: only #115 (DualSight, archival/never-merge). **NEXT (two parallel tracks — see "Next development task" below):** (a) **icon 5b-2…5b-5** wire the new glyphs into Warnings/Settings/Onboarding/record-chrome+nav (the foundation is unused until consumed); (b) **player resume-persistence PR** (shared canonical-identity seam) then PR-6b wall-clock / PR-7 speed·double-tap·auto-hide.

---

## Where things stand

- **`master` = `55dd894`; `origin/master` CURRENT.** PR-6 player-nav (#127) + icon 5b-1 (#128) squash-merged 2026-06-22, branches deleted. Earlier: UI Phase 2 PR-1…PR-5 (#122–#126), Library+Vault (#125), cue-bleed (#121). Working tree noisy with ephemeral `gradle_*.log` / `.superpowers/sdd/` scratch — not committed. (Orphaned `../rova-player-wt` folder = a removed worktree's leftover build dir; safe to delete when Windows path-length allows.)
- **PR-4 = the first surface to CONSUME the PR-3 glyphs.** `LibraryIconSpec` (pure, JVM-tested) holds the state→glyph/status decisions; 7 Library components + `VaultScreen` route their identity icons through `SemanticIcon`. Delete→`IconStatus.Danger` (item sheet) / neutral (batch bar); Favorite outline→filled-accent (sheet) / media-safe `overlayText` (hero); selected→`RovaIcons.Select` accent. Nav/utility (Back/Close/Clear/Play/Details) stay stock (identity-only fence). No new gate, no new strings. Full record: `memory/project_icon_glyph_system.md`.
- **Static gates: 46** custom `check*` tasks, all green on master. (46th = `checkSingleColorSchemeSource`, theme engine; 45th = `checkRovaGlyphHome`; the icon seam added `checkSemanticIconNoRawAlpha` + `checkStatusColorLocked`.) **UI Phase 2 PR-1/PR-2 added no new gate.**
- **Open PRs:** only **#115 — DualSight investigation** (DRAFT, archival, **never merge** — concurrent-camera hardware-blocked; see `memory/project_pr_delta_dualsight_probe.md`).
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

PR-6 player-nav + icon 5b-1 foundation merged (#127/#128). The two in-flight tracks below are **disjoint domains → parallel-safe** (icon surfaces = warnings/settings/onboarding/chrome; player = `ui/screens/player/` + a new identity seam):

**Track A — FINISH THE ICON SYSTEM: 5b-2…5b-5 surface wiring (P2, effort M, recommended next).** 5b-1 authored 24 glyphs + the `RovaIcons` map but **nothing consumes them yet** — they're dead weight until wired. Migrate each surface's icons through `SemanticIcon`/`RovaIcons`: **5b-2** Warnings (warning-cause glyphs: Thermal/Storage/BatteryLow/CameraOff/MicOff…) · **5b-3** Settings (rows: DarkMode/Language/Quality/Timer/Schedule/Privacy/Info…) · **5b-4** Onboarding (CameraAccess/MicAccess affirmatives) **+ wire the `rec_clipcheck`/`interrupted` status glyphs** (`RovaIcons.Recovered`/`Interrupted`, the last status surfaces) · **5b-5** record-chrome (`FlipCam`/`FlashBolt`) + bottom-nav glass treatment. Surfaces are disjoint → draftable by parallel agents; **shared coordination point = `strings.xml`** (each adds content-descriptions) so land as a short sub-PR sequence, not 4 simultaneous PRs. Closes the icon-system initiative (ADR-0031). *(Plan: `docs/UI_PHASE2_ICON_THEME_AUDIT.md §7`; `memory/project_icon_glyph_system.md`.)*

**Track B — player resume-from-position (P2, NEEDS-SPEC, effort M).** The deferred PR-6 feature. Needs a **shared canonical-identity seam** used by BOTH Library and Player (the player's tier-dependent URI ≠ Library's `stableKey` = `file.absolutePath` on TIER1; `LibraryMetadataStore.prune` drops orphan keys) + a JVM test pinning the TIER1 `content://`→`_DATA` mapping. `ResumePolicy` + `LibraryMetadataEntry.positionMs` already landed (#127) as groundwork. Then **PR-6b** wall-clock playhead (manifest schema bump for per-segment wall-start) and **PR-7** speed · double-tap-seek · auto-hide. *(BACKLOG → Video/Player/Editing; `memory/project_player_roadmap.md`.)*

**Backbone option (anytime): `RovaRecordingService.kt` split (P2, effort L, device-smoke).** Decompose the largest load-bearing file into seams, behavior-preserving — the 46-gate suite + JVM tests make behavior-preservation machine-verifiable. *(BACKLOG → Reliability/Service.)*

**Owner-reported pain — DualShot performance (frame jitter + thermal, P2, device-mandatory).** No root-caused repro yet; needs a device profiling/systrace pass, may interact with the δ0 CameraX 1.4.2→1.5.3 bump (P3). *(ADR-0009; `memory/project_dualshot_stability_stack.md`.)*

**Do NOT pick (stale/blocked):** Library/History redesign (DONE) · `checkA11y*` suite (DONE, #114) · PR-6 interactive timeline (DONE, #127) · icon 5b-1 foundation (DONE, #128) · PR-δ FrontBack/DualSight (silicon-blocked on RZCYA1VBQ2H) · `UI_ROADMAP.md` (superseded).

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

## Fresh-session kickoff prompt — re-prioritize first (PR-5b icons vs PR-6 player)

PR-5 Player (#126) MERGED. A 2026-06-20 project assessment (status review + re-prioritization + icon audit + motion polish + parallelization + audio-cue review) was delivered in chat — **start the next session by reading it / re-confirming the priority**, do NOT assume PR-6 is automatically next. Two live tracks below; both branch off the post-#126 master.

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.
Caveman mode + Ultracode ON.

Orient first: read HANDOFF.md, CLAUDE.md, the auto-loaded MEMORY.md, docs/BACKLOG.md (esp. "Video /
Player / Editing" — the specced PR-6/PR-7 player roadmap), and docs/UI_PHASE2_ICON_THEME_AUDIT.md §7.
Confirm master includes PR-5 #126 and origin/master is current; 46 gates; JVM baseline green.

RE-PRIORITIZE before coding (owner asked, 2026-06-20). Two ready tracks:
- PR-5b — remaining ICON surfaces (last icon-system slice, ADR-0031): Warnings / Settings / Onboarding
  migration + wire the authored rec_clipcheck (Recovered status) / interrupted glyphs into RovaIcons +
  Recovery/History/notification surfaces. SOURCE OF TRUTH for glyphs = board-3-semantic.html (extract
  via ctx_execute_file; do NOT eyeball). Follow PR-4/PR-5 pattern: pure *IconSpec helper for any
  state→glyph/status decision; identity-only fence; IconStatus for status; IconRole.Accent for
  favorite/select. Author in ui/theme/RovaGlyphs.kt (checkRovaGlyphHome).
- PR-6 — player NAVIGATION core (interactive timeline tap+scrub · segment prev/next · wall-clock
  playhead · resume), then PR-7 (speed · double-tap · auto-hide). Full spec + the AVOID list in
  docs/BACKLOG.md "Video / Player / Editing". Player is read-only today; segments = built-in chapters.

Process: brainstorm/plan first; pure-helper + JVM tests in the same PR; codex-review (mcp__codex__codex);
subagent-driven-dev (subagents EDIT-ONLY, controller runs all gradle/commits/smoke). Keep all 46 gates +
JVM green; never edit a check* to pass. New strings en+es (ADR-0022). Build WARM — confirm
:app:packageDebug EXECUTED before adb install -r (debug-signed; uninstall a release build first).
Mandatory device smoke on RZCYA1VBQ2H (adb via PowerShell; device unauthorized on reconnect — owner
re-accepts USB prompt; Record/Player FLAG_SECURE → owner verifies visually; do NOT blind-tap deep nav).
Push + PR + merge ONLY on owner GO.

First action: confirm master has #126 + origin current, then re-confirm priority with the owner (PR-5b
vs PR-6) before planning the chosen slice.
```
