# Handoff — Liquid Glass UI Sweep (start in fresh session)

Paste the block below into a new Claude Code session in this repo to resume.

---

I'm resuming the **Liquid Glass UI Sweep** for Rova (the Android periodic-recorder app). The brainstorm + design are DONE; the next action is to write the **PR1 (foundation) implementation plan** via the `superpowers:writing-plans` skill, then execute it.

## Read these first (authoritative)
- **Spec (source of truth):** `docs/superpowers/specs/2026-06-07-liquid-glass-ui-sweep-design.md` — read it fully before planning.
- **Visual reference (gitignored, repo root — open in a browser):** `rova_design_system.html`, `rova_design_system_round2.html`, `rova_design_system_round3.html`, `rova_icon_system.html`, `preset_theme_gallery.html`, `preset_mockup.html`.
- Memory: `memory/project_liquid_glass_sweep.md`.

## What this is
Move Rova's entire UI to a 2027–28 **liquid-glass** language with a **12-theme personality system**, refined icons, defined motion + type scale, WCAG 2.2 AA per theme — presentation layer only, zero reliability/behavior change. Structured **foundation-first**: PR1 = design-system foundation, then one PR per surface.

## Locked decisions (do NOT re-litigate)
1. **Theme model = flat-12 + Follow System.** `ThemeSelection { FOLLOW_SYSTEM, AURORA(default), TIDE, JADE, DUSK, ECLIPSE, DAYLIGHT, BLOSSOM, CORAL, MEADOW, COBALT, ORCHID, GRAPHITE }`. Wave 1 (first 6 after FOLLOW_SYSTEM) shown in picker; all 12 defined in the enum. Follow-system pair = Daylight (OS light) / Aurora (OS dark). Rejected: two-axis palette×mode.
2. **Glass is role-aware** (codex blocker fix): `LocalGlassEnvironment {palette, apiLevel, reduceTransparency}` seeded high in `RovaTheme`; call sites use `GlassSurface(role = GlassRole.X)` → pure `GlassResolver.resolve(env, role)`. NOT a tree-wide `isRecordSurface` flag (sheets mount inside RecordScreen).
3. **Degradation rules** (in the resolver): blur only when `apiLevel>=31 && !reduceTransparency && role!=RecordChrome`; else heavier solid tint, no blur. Reduce-Transparency → fully solid. minSdk is 24 so blur (RenderEffect) is API31+ only.
4. **No backdrop blur over the camera** — Compose has no CSS backdrop-filter; record glass = fill + gradient scrim + edge + opaque capsule behind critical text. Existing `RenderEffect` in `DualPreviewZone.kt` (non-recorded framing margins) is a documented ADR carve-out, NOT record chrome.
5. **Pinned-dark for camera/media routes** (Record/Player/Onboarding) in every theme: shared neutral-dark base + per-palette `accentOnDark`/`accentContainerOnDark` (contrast-checked) on meaningful controls only. App surfaces (Library/Settings/History/warnings/sheets) carry the full palette. Route-aware status-bar polarity.
6. **Locked semantic colors** across all 12 themes: success #34d399, warning #fbbf24, error #ef4444, escalating #f97316, rec #ff4d4d. Dynamic color / Material You OFF.
7. **Theme migration** from shipped `ThemeMode` (#80): SYSTEM→FOLLOW_SYSTEM, DARK→AURORA, LIGHT→DAYLIGHT, else FOLLOW_SYSTEM — pure helper, run in the synchronous SharedPreferences read path (no async DataStore) to avoid first-frame flash; write new key `theme_selection`, leave old key one release.
8. **PR1 proof surface = App Settings** (non-record) flipped to GlassSurface + theme picker (Wave 1). Record route untouched in PR1.
9. **Icons (PR11) and launcher/splash (PR12) are separate LATER PRs** — high blast radius; keep out of PR1. Launcher themed icon = static monochrome (can't follow in-app theme). Splash conservative on API24–30 via core-splashscreen.
10. New gates + **ADR-0028**: `checkRecordSurfaceNoBlur` (scoped to record-chrome files, allowlist DualPreviewZone), `checkGlassSurfaceRoleUsage` (migrated glass goes through GlassSurface/GlassResolver), per-theme contrast tests (12 palettes × semantic roles × fallback modes, NOT every screen×theme; extend `TokenContrastTest.kt` via `ContrastMath`).

## PR sequence
PR1 foundation (tokens/resolver/enum/migration/motion/type/gates/contrast tests + App Settings proof + Wave-1 picker) → PR2 Record home (no-blur/scrim, landscape+P+L) → PR3 Settings/presets sheet (folds in the original preset polish: Built-in/My-presets grouping, contrast fix, Edit-mode delete, selectableGroup+Role.RadioButton+stateDescription a11y) → PR4 Library+empty → PR5 Player → PR6 Warnings (glow-bloom) → PR7 Onboarding → PR8 Notification+merge → PR9 Recovery → PR10 Editor → PR11 icon swap → PR12 launcher+splash → unlock Wave 2 in picker.

## Hard project constraints (carry over)
- **Build-execution model:** the CONTROLLER runs ALL gradle (`gradlew.bat --stop` first, kill java, clear `app/build/kotlin` + `app/build/intermediates/built_in_kotlinc` only if cache corruption). A post-edit hook auto-runs gradle on save → detekt-not-a-task / daemon-stopped / cache-delete IOExceptions are EXPECTED NOISE. Verify with `:app:assembleDebug` (NOT lintDebug — it's RED on pre-existing B5 `VaultAndroidOps` NewApi, unrelated).
- **Never edit a check* gate to make it green** — fix source or amend ADR with explicit owner sign-off. New invariant = ADR clause → new check* task → wire into preBuild.
- **JVM tests only** (no Robolectric); pure-helper extraction pattern + JVM tests for any logic (GlassResolver, ThemeMigration, ContrastMath usage). All UI strings in resources **en + es** (`checkNoHardcodedUiStrings`).
- **codex MCP peer review** for code >5 lines / architecture / a11y / migration (`mcp__codex__codex`). codex already reviewed this foundation architecture — corrections are folded into the spec.
- **CodeGraph** initialized: spawn an Explore agent for exploration; never `codegraph_explore`/`codegraph_context` in the main session.
- **Commit only when asked**; branch off master first (suggested branch `feat/liquid-glass-foundation`). The spec doc + this handoff are currently UNCOMMITTED on master.
- Untracked `gradle_*.log`, `nul`, and the `*.html` mockups + `rova_design_system*.html` in repo root are ephemeral — leave them.

## Repo state at handoff
- master = `db1baa6` (clean; all of #97/#98/#99/#100 merged; both #98 schedule + #100 nav-flash device-smoke GO). Release APK built earlier (`app/build/outputs/apk/release/app-release.apk`, signed).
- Toolchain: AGP 9.2.1, Gradle 9.4.1, Kotlin 2.2.10, minSdk 24, compile/targetSdk 37, Compose BOM 2025.01.01. 31 static gates. ~1500+ JVM tests.

## First action in the new session
1. Read the spec fully.
2. Branch `feat/liquid-glass-foundation` off master (commit the spec + ADR-0028 stub there when ready).
3. Invoke `superpowers:writing-plans` to produce the **PR1 foundation** implementation plan (bite-sized TDD tasks: ThemeSelection+migration → RovaPalette×12 + RovaSemantics → GlassEnvironment/Resolver/Role/Surface → RovaMotion + type scale → new gates + ADR-0028 → per-theme contrast tests → App Settings proof surface + Wave-1 theme picker). PR1 must hold all existing gates and introduce no visual regression outside App Settings.
