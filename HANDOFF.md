# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-18 — the Liquid Glass theme engine (slice 1, palette propagation, PR #120) and Icon P2 Track A (branded merge animation) are both merged. `master` = `0eb30fd` (local-only, ~50+ ahead of origin, NOT pushed — owner gates push). NEXT = the P1 recording-cue audio-bleed bug (media-integrity, `docs/BACKLOG.md` → Known Bugs) — start with `superpowers:systematic-debugging`. Trigger prompt at the bottom.**

---

## Where things stand

- **`master` = `0eb30fd`** (local-only; ~50+ commits ahead of `origin/master`; **NOT pushed** — owner gates every push). Working tree clean apart from ephemeral `gradle_*.log` in root.
- **Static gates: 46** custom `check*` tasks, all green on master. (46th = `checkSingleColorSchemeSource`, theme engine; 45th = `checkRovaGlyphHome`; the icon seam added `checkSemanticIconNoRawAlpha` + `checkStatusColorLocked`.) Icon P2 added **no** new gate.
- **Open PRs:** only **#115 — DualSight investigation** (DRAFT, archival, **never merge** — concurrent-camera hardware-blocked; see `memory/project_pr_delta_dualsight_probe.md`).
- **Test baseline:** JVM unit tests only (`:app:testDebugUnitTest`), green on master. Real-device smoke mandatory for any capture path (device = RZCYA1VBQ2H, Android 14).

### Landed since the last handoff (all on local master, owner device-GO)

- **Liquid Glass theme engine — slice 1 (palette propagation), PR #120 → master `8a75849`.** Pure `PaletteColorScheme.from(palette): ColorScheme` drives MaterialTheme app-wide (every `colorScheme.*` reader restyles per palette, not just ~3 surfaces); 12-swatch picker (`ThemeSwatchSheet`, Wave-2 exposed, en+es); **46th gate `checkSingleColorSchemeSource`**; `ThemeContrastTest` asserts WCAG AA across all 12 palettes; pinned routes (Record/Player/Onboarding) stay neutral-dark via `PinnedGlassEnvironment`. Full record: `memory/project_theme_engine.md`.
- **Icon P2 Track A — branded merge animation, merged `c4f9baa` (no-ff).** One shared indeterminate-spin primitive (pure `MergeMotion` + `ProcessingGlyph` on the `SemanticIcon` seam) drives both merge surfaces — Record-home `StatusPill` (orange spinner replaces the retired blue `MergingDotColor`) and recovery-card `ProgressStrip` header. Locked `IconStatus.Processing → RovaSemantics.escalating`; reduced-motion static; no new gate. Device-GO on the pill; recovery surface closed by `ProcessingGlyph` parity. Full record: `memory/project_icon_glyph_system.md` (P2 entry).
- **Icon & Glyph System — P0** (`SemanticIcon` tint seam + status-color lock + 2 gates) → **P1a Slice 1** (two-layer `RovaGlyph` pipeline: Library/Settings/Sort/Record) → **Slice 2** (6 brand + 2 orientation glyphs authored; `RecordChromeIcons` folded into `RovaGlyphs` + deleted; `checkRovaGlyphHome` gate) → **Slice 3** (glyphs go LIVE: capture/orientation pickers, Vault, Merge, Recovery; `Single`+`FollowDevice` filler glyphs). System D visual language (outlined soft-monoline + duotone accent channel + locked status colors). `BackgroundRecord` glyph deferred (FGS notification needs a drawable-resource, not an `ImageVector`). Full record: `memory/project_icon_glyph_system.md`.
- **Library item-sheet + Record-settings polish** — compact `LibraryItemSheet`; gear→Info on the per-item details row; DualShot Vault row shown **greyed + reason** (not hidden); **tap a stepper value** (Clip/Repeats/Wait) for direct numeric entry. (Gotcha discovered: phone Record-home renders `FloatingSettingsPanel`, NOT `SettingsSheet` — both reuse the shared `StepperRow`.)
- **Premium app-wide dialog system** — `RovaAlertDialog` (`ui/components/RovaDialogs.kt`) routes **all 13** dialog sites through one branded scaffold: glass depth (lit rim + top lift wash, 32dp corners, soft ambient shadow), a **filled accent-gradient CTA** with a per-theme WCAG-checked label (`DialogActionColors`, pure + JVM-tested, all 12 palettes ≥4.5:1), ghost dismiss, header icon-chip, and a top-right **X-close** for informational dialogs. Rename: "View settings" → **Details**; `history_config_title` → **Recording details**.

## Why the recording-cue audio-bleed bug is next

**P1, media-integrity** (it corrupts saved clips, not just UI) — owner-reported 2026-06-18 on RZCYA1VBQ2H, filed in `docs/BACKLOG.md` → Known Bugs. The `rova_beep` start cue is audible **in the saved clip's audio** (confirmed on playback), and the per-segment intermediate cues show the same overlap. The code *intends* a pre-roll cue that sits outside capture: `beepStart()` ([RovaRecordingService.kt:1469](app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt#L1469)) is a `suspend` that awaits MediaPlayer `onCompletion` + `BEEP_TAIL_MARGIN_MS` **before** `recordSegment()` opens the mic (:1470); `beepEnd()` (:1471) fires only after `recordSegment()` awaits the CameraX `Finalize` callback (:2947). The **same `beepStart` pre-roll path runs at the head of every segment**, so intermediate cues are *meant* to be pre-roll too — the overlap is one bug, not a separate design. On this device the timing gate is evidently **not** keeping the cue out of capture (suspect `MediaPlayer.create().start()` output latency vs `onCompletion`, audio-focus/route coupling, or acoustic echo into a live AGC mic). **Needs a real debugging cycle** (instrument actual speaker-quiet→mic-open delta; DualShot path shares the same ordering — verify the separate `AudioRecord` broadcast in `service/dualrecord/`) before any fix. Secondary: reorder the `isRecording=true` flag (:1468, currently set *before* the pre-roll beep → HUD lights "recording" during the cue) once the audio fix lands.

**Other strong candidates if priorities shift:** `RovaRecordingService.kt` split (P2, largest file, decompose into seams) · Rova video-player upgrades (P2, NEEDS-SPEC) · DualShot performance — frame jitter + thermal (P2, owner-reported). The theme engine slice 1 shipped (PR #120); its deferred follow-ons (icon glass-chip active-state containers; deeper per-surface color seams) remain in ADR-0031/0028 backlog.

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

## Fresh-session kickoff prompt — recording-cue audio-bleed bug (P1)

Paste this to start the next session. **This is a BUG → systematic-debugging FIRST; do not patch blind.**

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.

Orient first: read HANDOFF.md, CLAUDE.md, the auto-loaded MEMORY.md, and the "Recording start cue
bleeds into the recorded audio track" entry in docs/BACKLOG.md (Known Bugs). Don't re-explore what
those establish. Confirm master is 0eb30fd (theme engine slice 1 + Icon P2 merged; 46 gates;
local-only, NOT pushed) before starting.

BUG (P1, media-integrity, owner-confirmed on RZCYA1VBQ2H by playback): the rova_beep start cue is
captured INTO the saved clip's audio, and the per-segment intermediate cues overlap the next segment
the same way. The code intends a pre-roll cue OUTSIDE capture: beepStart() (RovaRecordingService.kt:1469)
is a suspend that awaits MediaPlayer onCompletion + BEEP_TAIL_MARGIN_MS before recordSegment() opens the
mic (:1470); beepEnd() (:1471) fires only after recordSegment() awaits the CameraX Finalize callback
(:2947). The SAME beepStart path runs at the head of every segment, so intermediate cues are meant to be
pre-roll too — it's one bug. On-device the timing gate is NOT keeping the cue out of capture.

TASK: root-cause then fix the bleed. Use superpowers:systematic-debugging — form hypotheses and PROVE
each before fixing. Likely suspects: MediaPlayer.create().start() output latency vs the onCompletion
callback (callback fires on buffer-drain, not acoustic emission); audio-focus / audio-route coupling
between the cue stream and the capture source; acoustic echo into a live AGC mic; or recordSegment()
opening the mic (withAudioEnabled at ~:2759) sooner than the await assumes. Instrument the ACTUAL
speaker-quiet -> mic-open delta on-device. DualShot shares the same beepStart ordering but a separate
AudioRecord broadcast (service/dualrecord/) — verify there too. Pick the fix only after the cause is
proven (measured/larger gap, audio-focus duck, route the cue where the capture source can't pick it up,
or gate capture-start on a verified-silent mic). Secondary follow-on once audio is clean: reorder the
isRecording=true flag (:1468, currently set before the pre-roll beep so the HUD lights "recording"
during the cue).

Process: extract pure-testable seams where possible (e.g. a BeepGate/timing helper, JVM-tested under
isReturnDefaultValues=true) per the house pure-helper pattern; the framework-touching MediaPlayer/mic
ordering stays a thin seam. codex-review the diagnosis + fix (audio/timing + concurrency). Then
subagent-driven-dev or executing-plans. Subagents EDIT-ONLY; the controller runs all gradle + commits.
Build WARM (no cache wipe; confirm packageDebug EXECUTED before any device install — owner device-smoke
is mandatory for this fix: record a clip, play it back, confirm NO cue in the audio on both single and
DualShot). Local-only branch off master; push/merge only when the owner asks. Caveman mode + Ultracode ON.

First action: confirm master state, then superpowers:systematic-debugging on the cue-bleed bug.
```
