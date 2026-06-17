# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-17 — the Icon & Glyph System is BUILT and LIVE (P0 + P1a slices 1–3), and the app-wide premium dialog system + Library/Record-settings polish are merged. `master` = `086b1a7` (local-only, ~40 ahead of origin, NOT pushed — owner gates push). NEXT SESSION = the Liquid Glass theme engine (ADR-0028) — NEEDS-SPEC, so start with brainstorming. Trigger prompt at the bottom.**

---

## Where things stand

- **`master` = `086b1a7`** (local-only; ~40 commits ahead of `origin/master`; **NOT pushed** — owner gates every push). Working tree clean apart from ephemeral `gradle_*.log` in root.
- **Static gates: 45** custom `check*` tasks, all green on master. (45th = `checkRovaGlyphHome`; the icon seam added `checkSemanticIconNoRawAlpha` + `checkStatusColorLocked`.)
- **Open PRs:** only **#115 — DualSight investigation** (DRAFT, archival, **never merge** — concurrent-camera hardware-blocked; see `memory/project_pr_delta_dualsight_probe.md`).
- **Test baseline:** JVM unit tests only (`:app:testDebugUnitTest`), green on master. Real-device smoke mandatory for any capture path (device = RZCYA1VBQ2H, Android 14).

### Landed since the last handoff (all on local master, owner device-GO)

- **Icon & Glyph System — P0** (`SemanticIcon` tint seam + status-color lock + 2 gates) → **P1a Slice 1** (two-layer `RovaGlyph` pipeline: Library/Settings/Sort/Record) → **Slice 2** (6 brand + 2 orientation glyphs authored; `RecordChromeIcons` folded into `RovaGlyphs` + deleted; `checkRovaGlyphHome` gate) → **Slice 3** (glyphs go LIVE: capture/orientation pickers, Vault, Merge, Recovery; `Single`+`FollowDevice` filler glyphs). System D visual language (outlined soft-monoline + duotone accent channel + locked status colors). `BackgroundRecord` glyph deferred (FGS notification needs a drawable-resource, not an `ImageVector`). Full record: `memory/project_icon_glyph_system.md`.
- **Library item-sheet + Record-settings polish** — compact `LibraryItemSheet`; gear→Info on the per-item details row; DualShot Vault row shown **greyed + reason** (not hidden); **tap a stepper value** (Clip/Repeats/Wait) for direct numeric entry. (Gotcha discovered: phone Record-home renders `FloatingSettingsPanel`, NOT `SettingsSheet` — both reuse the shared `StepperRow`.)
- **Premium app-wide dialog system** — `RovaAlertDialog` (`ui/components/RovaDialogs.kt`) routes **all 13** dialog sites through one branded scaffold: glass depth (lit rim + top lift wash, 32dp corners, soft ambient shadow), a **filled accent-gradient CTA** with a per-theme WCAG-checked label (`DialogActionColors`, pure + JVM-tested, all 12 palettes ≥4.5:1), ghost dismiss, header icon-chip, and a top-right **X-close** for informational dialogs. Rename: "View settings" → **Details**; `history_config_title` → **Recording details**.

## Why the theme engine is next (and why it waited)

The Liquid Glass theme engine drives surface/accent color from palette tokens. It was deliberately sequenced **after** the icon system so the engine inherits a stable icon color contract instead of forcing icon decisions later (ADR-0031 §8). That seam now exists: every glyph colors through `SemanticIcon` → `SemanticIconSpec.tint(palette, role)` / `.statusTint(status)`, reading `LocalGlassEnvironment.current.palette`. The **Library screen is the reference implementation** for the engine — its `LibraryColors` / `LibraryColorSpec` "identity-vs-locked" seam is the pattern to generalize. `RovaPalette.kt` already defines **12 named palettes** (`rovaPalettes`); Wave 2 (Extended 6: Blossom/Coral/Meadow/Cobalt/Orchid/Graphite) is "defined now, exposed later" — exposing them is part of the engine.

**The theme engine is NEEDS-SPEC.** "Engine" could mean: a full 12-palette user picker; glass-chip active-state containers for icons (the deferred icon P1/P2 wiring); animated icon states; per-surface palette propagation; or all of these. **Start the next session with `superpowers:brainstorming` to scope it with the owner before any code.**

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
- **Config cache is OFF** (the 45 gates capture build-script refs; refactor filed P2 in BACKLOG).
- **Windows / PowerShell.** Use `gradlew.bat`. **adb MCP wrapper broken on Windows — drive adb via PowerShell directly.** Device goes `unauthorized` on reconnect — owner must re-accept the USB-debugging prompt.
- **`lintDebug` is RED on a pre-existing B5 `VaultAndroidOps` NewApi** (unrelated) — gate-build with `:app:assembleDebug`.

## Key references

- `CLAUDE.md` — project instructions (now cites **45 gates / 31 ADRs**).
- Theme/glass: `docs/adr/0028-liquid-glass-design-system.md`; `ui/theme/GlassEnvironment.kt`, `GlassResolver.kt`, `GlassSurface.kt`, `RovaPalette.kt` (`rovaPalettes`, `RovaSemantics`). Reference seam: `ui/library/LibraryColors.kt` + `LibraryColorSpec`.
- Icon system: `docs/adr/0031-icon-glyph-system.md`; `ui/theme/RovaGlyphs.kt`, `RovaIcons.kt`; `ui/components/SemanticIcon.kt`; `memory/project_icon_glyph_system.md`.
- `docs/adr/` — behavioral invariants. `docs/BACKLOG.md` — full task list. `ROADMAP_v6.md` (reliability), `NEW_UI_BACKEND_REPLAN.md` (UI redesign), `mockups/new_uiux/` (gitignored).

---

## Fresh-session kickoff prompt — Liquid Glass theme engine

Paste this to start the next session. **This is NEEDS-SPEC → brainstorm to scope it FIRST, then plan, then build.**

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.

Orient first: read HANDOFF.md, CLAUDE.md, the auto-loaded MEMORY.md, ADR-0028
(docs/adr/0028-liquid-glass-design-system.md), and the icon record
memory/project_icon_glyph_system.md. Don't re-explore what those establish. Confirm master is
086b1a7 (icon system live + premium dialogs merged; 45 gates; local-only, NOT pushed) before starting.

State: the Icon & Glyph System is BUILT and LIVE (P0 + P1a slices 1–3 — System D glyphs through the
SemanticIcon tint seam reading LocalGlassEnvironment.current.palette). RovaPalette.kt already defines
12 named palettes (rovaPalettes); the Light/Dark/System switcher exists (B2); the Library screen
(LibraryColors/LibraryColorSpec identity-vs-locked seam) is the REFERENCE implementation for the engine.

TASK: the LIQUID GLASS THEME ENGINE (ADR-0028). This is NEEDS-SPEC — scope is open. Candidate scope to
nail down with the owner: (a) a full 12-palette user theme picker (expose Wave-2 Extended-6:
Blossom/Coral/Meadow/Cobalt/Orchid/Graphite) + persistence; (b) generalize the Library
LibraryColors/LibraryColorSpec "identity-vs-locked" seam into an app-wide palette-propagation engine so
every surface/accent derives from the active palette token; (c) the deferred icon P1/P2 wiring —
glass-chip active-state containers for glyphs (FILL 0→1) + animated states (Processing arc, Merging);
(d) per-route palette overrides (pinned-dark capture/player routes already exist via
PinnedGlassEnvironment). Decide which of these are in-scope for the first engine slice vs deferred.

Process: START with superpowers:brainstorming to turn this into a scoped design + spec (one question at
a time; propose 2–3 approaches; get owner approval; write the spec to docs/superpowers/specs/). THEN
superpowers:writing-plans for the implementation plan (TDD/JVM-first; pure-helper seam pattern like
DialogActionColors/ContrastMath/SemanticIconSpec; new strings en+es per ADR-0022; WCAG 2.2 AA per
ADR-0020 — palette label/accent contrast must clear AA across ALL palettes, the DialogActionColors
pattern). codex-review the design + plan (palette propagation architecture + any new gate regexes).
THEN subagent-driven-dev or executing-plans. Subagents EDIT-ONLY; the controller runs all gradle +
commits. Build WARM (no cache wipe; confirm packageDebug EXECUTED before any device install). Local-only
branch off master; push/merge only when the owner asks. Caveman mode + Ultracode ON.

First action: confirm master state, then superpowers:brainstorming to scope the theme engine.
```
