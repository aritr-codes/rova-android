# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-16 — the Library/History redesign is MERGED to `master 33b4a0b`, and the Icon & Glyph System DESIGN phase is COMPLETE (System D locked; spec + ADR-0031, no code). NEXT SESSION = Icon System P0 build (collision fixes + the `SemanticIcon` tint seam + status-color lock + 2 gates) — this must land BEFORE the Liquid Glass theme engine. Trigger prompt at the bottom.**

---

## Where things stand

- **`master` = `33b4a0b`** — the Library/History Liquid-Glass redesign is **merged** (stacked no-squash train #117→#118→#119: foundation → layout → selection+polish+theme+stabilization). 50 `ui/library/*.kt` on master. ADR-0030. Working tree clean apart from ephemeral `gradle_*.log`.
- **Static gates: 42** custom `check*` tasks (42nd = `checkLibraryNoManifestWrite`). All green on master.
- **Open PRs:** only **#115 — DualSight investigation** (DRAFT, archival, **never merge** — concurrent-camera hardware-blocked; see `memory/project_pr_delta_dualsight_probe.md`).
- **Icon & Glyph System — design DONE (this session):** full audit + Phase-1 competitive research + visual exploration (4 candidate languages → **System D** chosen by owner) + semantic glyph design. Deliverables committed: spec `docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md` + **ADR-0031** (`docs/adr/0031-icon-glyph-system.md`, Status: Proposed). Visual boards live in `.superpowers/brainstorm/…/content/` (gitignored): `board-3-semantic.html` (semantic), `board-v2.html` (language fork). **No code written.**
- **Test baseline:** JVM unit tests only (`:app:testDebugUnitTest`), green on master. Real-device smoke mandatory for any capture path (device = RZCYA1VBQ2H, Android 14).

## Icon System — what's decided (System D)

The locked visual language for every in-app glyph (launcher/mipmap brand mark stays out of scope — ADR-0028 §9):
- **Outlined-default, soft monoline.** 24-grid, 1.9px stroke, round caps/joins, soft corners.
- **Duotone accent layer** (`.ac2`) — one accent detail channel on a light outline; **meaning must survive without it (mono-safe)**.
- **Filled-active** — selected/active or a primary action = filled glyph + accent + a **Liquid-Glass chip** container (FILL 0→1 animatable). Inactive = outlined `onSurfaceVariant`.
- **Theme-retintable accent channel** + **locked semantic status colors** (recovered/interrupted/processing/success/warning/rec bound to `RovaSemantics`, never per-call alpha). Mirrors the existing `LibraryColors`/`RovaSemantics` **identity-vs-locked** split exactly.
- **Over-media contrast at the substrate** (glass chip), not stroke weight.
- **Bespoke only for brand-unique/icon-poor concepts** (DualShot, DualSight, Vault, Recovery, Background-record, Merge), authored to the same grid/stroke; one `RovaGlyphs` home; stock glyphs behind a `RovaIcons` alias map.

Semantic decisions (full table in the spec §7–§10): Settings→**gear** · Sort→**state-free bars** · Warning(status, amber)≠Notifications(bell, setting) · Processing→animated arc + static-dots fallback · Recovered→clip+check · Library→**stacked frames** (un-collides Play) · Record→disc(action)/ring(nav)/square(stop). Plus 8 new product glyphs (Camera Flip, Merge/Stitch, Loop/Interval, Segment Count, Waiting, Merging, Background Recording, DualSight).

## Why P0 is load-bearing

The Liquid Glass theme engine drives surface color from palette tokens. If it is built over today's icon layer it bakes in the 20 raw `Color.White.copy(alpha=…)` tint sites and the ambiguous/colliding glyphs. **P0 fixes the concept→glyph collisions and introduces the single `SemanticIcon` tint seam first**, so the engine inherits a stable icon color contract instead of forcing icon decisions later. ADR-0031 §8 sequences P0 *ahead of* the engine.

## Load-bearing rules (don't violate)

- **Never edit a `check*` task to make it green** — fix the source, or amend the ADR + check with explicit owner sign-off. New invariant = ADR clause → new `check*` task → wire into `preBuild`.
- **ADRs are the source of truth.** Touching anything an ADR mentions = amend the ADR clause first, regenerate/extend the matching `check*`, then change code.
- **JVM unit tests only** (`isReturnDefaultValues = true`); framework-touching code gets a pure-Kotlin sibling (seam/pure-helper pattern). A feature lands its tests in the same PR.
- **New user-facing strings go in resources** (ADR-0022) in **both** `values/strings.xml` and `values-es/strings.xml`; `checkNoHardcodedUiStrings` enforces it.
- **WCAG 2.2 AA by default** (ADR-0020). Live a11y gates: `checkA11yAnimationGated`, `checkA11yClickableHasRole`, `checkA11yTargetSizeToken` (SC 2.5.8 = ≥24dp). Token contrast = `TokenContrastTest` (JVM), not a static gate.
- **codex MCP peer review** (`mcp__codex__codex`) mandatory for code changes >5 lines, architecture/design, algorithmic logic, security-sensitive, migrations, perf claims. **Route it on the P0 implementation plan** (gate regexes + `SemanticIcon` seam API).
- **CodeGraph** is initialized — never call `codegraph_explore`/`codegraph_context` from the main session; spawn an Explore agent. Main session may use `codegraph_search`/`callers`/`callees`/`impact`/`node` only.
- **Commit/push only when the owner asks.** Untracked `gradle_*.log` in root are ephemeral — leave them.
- **Subagents EDIT-ONLY; the controller runs all gradle + commits.**

## Build environment

- **Build WARM. Do NOT wipe caches before every build.** The `kotlin-postedit.ps1` hook that corrupted the incremental cache was disabled 2026-06-09. Just `gradlew.bat :app:assembleDebug` (warm ~1–3 min, UP-TO-DATE ~8s; cold ~17 min). Clean only on a real kotlinc/MD5 fault: `gradlew.bat --stop` + `rm -rf app/build/kotlin app/build/intermediates/built_in_kotlinc`.
- **Config cache is OFF** (the 42 gates capture build-script refs; refactor filed P2 in BACKLOG).
- **Windows / PowerShell.** Use `gradlew.bat`. **adb MCP wrapper broken on Windows — drive adb via PowerShell directly.**
- **`lintDebug` is RED on a pre-existing B5 `VaultAndroidOps` NewApi** (unrelated) — gate-build with `:app:assembleDebug`.

## Key references

- `CLAUDE.md` — project instructions. **NOTE drift:** CLAUDE.md still cites "41 gates / 29 ADRs"; master is **42 gates** (`checkLibraryNoManifestWrite`) and **31 ADRs** (ADR-0030 Library, ADR-0031 icons). Correct CLAUDE.md when convenient.
- Icon spec: `docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md`; ADR-0031. Library record: `memory/project_library_history_redesign.md`. Icon record: `memory/project_icon_glyph_system.md`.
- `docs/adr/` — behavioral invariants. `docs/BACKLOG.md` — full task list. `ROADMAP_v6.md` (reliability), `NEW_UI_BACKEND_REPLAN.md` (UI redesign).

---

## Fresh-session kickoff prompt — Icon System P0 build

Paste this to start the next session. **This IS an implementation session** (the design is locked). Plan first, then build.

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.

Orient first: read HANDOFF.md, CLAUDE.md, the auto-loaded MEMORY.md, the icon spec
docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md, and ADR-0031
(docs/adr/0031-icon-glyph-system.md). Don't re-explore what those establish. Confirm master is
33b4a0b (Library redesign merged; 42 gates) before starting.

State: the Icon & Glyph System DESIGN is COMPLETE and owner-approved. Visual language = System D
(outlined-default soft-monoline + duotone accent channel + filled-active in a Liquid-Glass chip +
LOCKED semantic status colors; mirrors LibraryColors/RovaSemantics identity-vs-locked). No code yet.

TASK: ICON SYSTEM **P0** — the pre-theme-engine slice (ADR-0031 §8). P0 MUST land before the Liquid
Glass theme engine so the engine inherits a stable icon color seam. Scope, exactly:
  1. The `SemanticIcon` tint seam — one content-color contract that ALL glyph color flows through;
     replace the 20 raw `Color.White.copy(alpha=…)` Icon(...) tint sites (RecordChrome ×7, PlayerScreen
     ×8, WarningSheetV3 ×3, SettingsScreen ×2, + LibraryRow/Onboarding/Vault/Warnings). Pure-Kotlin
     resolver + JVM tests (the icon analogue of GlassResolver/LibraryColorSpec).
  2. Lock status colors to RovaSemantics (recovered/interrupted/processing/success/warning/rec) —
     no per-call alpha, always paired with shape (WCAG 1.4.1).
  3. Resolve the chrome-baked concept→glyph collisions: Settings→gear · Sort→state-free bars ·
     Library→stacked-frames (un-collide Play) · Warning-status (amber triangle) vs Notifications-setting
     (bell) split · Record action(disc)/nav(ring)/stop(square) distinction. One concept → one glyph.
  4. Two new gates following invariant→check*→preBuild: `checkSemanticIconNoRawAlpha` (no raw-alpha
     Icon tint outside the seam) + `checkStatusColorLocked` (status glyphs pull only from RovaSemantics).
     Finalize the exact regexes in the plan; codex-review them. NEVER edit a gate to pass.

NOT in P0 (defer): bespoke RovaGlyphs authoring (DualShot/Vault/Recovery/DualSight/Background-record/
Merge) + RecordChromeIcons fold-in + accent/glass-chip palette wiring = **P1, with the theme engine**.
Animated states + secondary concepts (Loop/Interval, Segment count, Waiting) + onboarding illustration
refresh = **P2**. The launcher/mipmap brand icon is out of scope entirely.

Process: run superpowers:writing-plans to produce the P0 implementation plan (TDD/JVM-first; pure-helper
seam pattern; new strings en+es per ADR-0022; WCAG 2.2 AA per ADR-0020). codex-review the plan
(gate regexes + seam API). Then subagent-driven-dev or executing-plans. Subagents EDIT-ONLY; the
controller runs all gradle + commits. Build WARM (no cache wipe). Local-only branch off master; push/
merge only when the owner asks. Reference files: ui/screens/RecordChromeIcons.kt, ui/theme/RovaPalette.kt
(RovaSemantics), ui/theme/GlassResolver.kt, ui/library/LibraryColors.kt + LibraryColorSpec,
ui/theme/RecordChromeTokens.kt, ui/library/components/LibraryDimens.kt, and the 20 tint call-sites in
the spec §3. Caveman mode + Ultracode ON.

First action: confirm master state, then superpowers:writing-plans for the P0 slice.
```
