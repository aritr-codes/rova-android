## Project

Rova (`com.aritr.rova`) — Android app for automated, hands-free **periodic background video recording**. Single-Activity Compose UI, CameraX capture, foreground service runs the segment loop, MediaMuxer concatenates segments, tiered public export by API level.

Single Gradle module (`:app`). Kotlin only.

## Build, lint, test

All commands run from repo root on Windows (PowerShell). `gradlew.bat` is the wrapper; on POSIX use `./gradlew`.

```powershell
./gradlew :app:lintDebug              # Lint + every custom check* task (preBuild gate)
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:assembleDebug          # Build debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run a single JVM test:

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.RecoveryScannerTest"
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.RecoveryScannerTest.classify_userStopped_offersDiscard"
```

Run a single static-check task:

```powershell
./gradlew :app:checkScanTriggerSingleSite
```

The release build is **signed via `keystore.properties`** (already configured locally). Real-device testing is mandatory — emulators consistently fail CameraX video recording.

### Static-check gate (load-bearing)

`app/build.gradle.kts` registers **46 custom `check*` tasks** wired into `preBuild` via `tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(...) }`. Each is a regex/AST scan that enforces an invariant from a specific ADR clause:

- `checkSchedulerNoGetService`, `checkStopNoGetService`, `checkRecoveryNoDeletion`, `checkRecoverySegmentRegex`, `checkScanTriggerSingleSite`, `checkRecoveryReceiverCounter`, `checkAtomicTerminalWriteForbiddenPair`, `checkExternalRootShared`, `checkAudioModeFgsTypeMatch`, `checkFGSStartGuarded`, `checkUserStoppedBeforeMerge`, `checkExportTierReadTolerant`, `checkScanFileBoundedWait`, `checkPendingFdModeIsRW`, `checkExportIsPendingGuarded`, `checkExportSetIncludePendingGuarded`, `checkExportQueryArgMatchPendingGuarded`, `checkExportPendingVisibilityOnQuery`, `checkExportCleanupPredicate`, `checkExportNoCopyToPublicMovies`, `checkExportPipelineSingleEntry`, `checkCompletedWriteOnlyFromPerformMerge`, `checkWakeLockBoundedAcquire`, `checkWakeLockHeldRefresh`, `checkWakeLockZeroGapRefresh`, `checkNoHardcodedUiStrings`, `checkLocaleConfigNoPseudolocale`, `checkA11yAnimationGated`, `checkScheduleReceiverNoFgsStart`, `checkSafTargetCommittedBeforeStream`, `checkPresetNoOrientation`, `checkNoLegacyModeStrings`, `checkSetTargetRotationBoundaryOnly`, `checkFrontBackCapabilityGated`, `checkUserCopyVocabulary`, `checkVaultExporterNoPublicPublish`, `checkRecordSurfaceNoBlur`, `checkGlassSurfaceRoleUsage`, `checkRecordChromeLockSingleSite`, `checkA11yClickableHasRole`, `checkA11yTargetSizeToken`, `checkLibraryNoManifestWrite`, `checkSemanticIconNoRawAlpha`, `checkStatusColorLocked`, `checkRovaGlyphHome`, `checkSingleColorSchemeSource`.

If a check fails it cites the ADR clause it enforces. **Do not edit a check away to make it green** — fix the source, or amend the ADR (and update the check) only with explicit owner sign-off. Adding a new invariant means: ADR clause → new `check*` task → wire into `preBuild`.

## Test policy

- **JVM unit tests only.** No Robolectric, no instrumented tests in `androidTest/` beyond bundled Compose UI test scaffolding.
- `testOptions.unitTests.isReturnDefaultValues = true` — Android framework calls return defaults under JVM. Anything that touches `android.opengl.Matrix.*` or `android.graphics.Matrix.*` is a no-op in tests; use a pure-Kotlin math seam (see `AspectFitMath.kt`).
- `JSONObject` / `JSONArray` from `android.jar` throw at runtime under JVM tests. The real `org.json:json:20231013` is on `testImplementation` so `SessionManifest` / `SessionConfig` persistence tests work.
- Pattern: extract pure-helper objects (`SegmentGateThermal`, `ThermalHysteresis`, `AspectFitMath`, `effectiveIdleTopBannerId`) so logic is unit-testable; the framework-touching wrapper class stays a thin seam.
- Baseline: **1241 tests / 0-0-0** on master (`e1e121d`). A new feature lands its tests in the same PR.

## Architecture

### Toolchain

| | |
|--|--|
| AGP | 9.2.1 |
| Kotlin | 2.2.10 (Compose plugin only — AGP 9 supplies the JVM Kotlin plugin) |
| Gradle | 9.4.1 |
| compileSdk / targetSdk | 37 |
| minSdk | 24 |
| Java | 11 |
| CameraX | 1.4.2 |
| Media3 (ExoPlayer + ui) | **pinned 1.4.1** — 1.5.x deliberately not adopted |
| Compose BOM | 2025.01.01 |

Version catalog: `gradle/libs.versions.toml`. Dependency lines for `lifecycle-viewmodel-compose`, `material-icons-extended`, `navigation-compose`, `accompanist-permissions`, and `org.json:json` are inline in `app/build.gradle.kts` (not yet in the catalog).

### Module shape

```
app/src/main/java/com/aritr/rova/
├── RovaApp.kt                       # Application — recoveryReport: StateFlow
│                                    #               triggerRecoveryScanIfNeeded()
│                                    #               WarningCenter signal lazies
├── MainActivity.kt                  # Single Activity — sole recovery-scan trigger
├── data/                            # SessionStore (atomic manifest), SessionManifest,
│                                    # SessionConfig, RovaSettings, QualityPresets
├── service/
│   ├── RovaRecordingService.kt      # FGS — CameraX bind, segment loop
│   ├── RovaTickReceiver.kt          # Segment-boundary AlarmManager fire
│   ├── RovaStopReceiver.kt          # Loop-count-exhausted STOP fire
│   ├── dualrecord/                  # P+L dual-encode (CameraEffect + EGL14 fan-out)
│   ├── export/                      # Tier1/2/3 exporters, ExportRecoveryRunner,
│   │                                # ExportCleanupPredicate, MediaScanWaiter
│   ├── recovery/                    # RecoveryScanner.classifyAll, RecoveryReport
│   ├── scheduler/                   # AlarmScheduler — exact alarms only
│   ├── surface/                     # Headless preview surface variants
│   ├── wakelock/                    # WakeLockPolicy — bounded acquire (ADR-0006)
│   ├── audio/                       # BeepPolicy
│   └── notification/                # NotificationCopy
├── ui/
│   ├── MainScreen.kt                # Bottom-nav scaffold
│   ├── screens/                     # RecordScreen, HistoryScreen, SettingsScreen,
│   │                                # onboarding/, player/ (Media3 surface)
│   ├── recovery/                    # RecoveryCard, RecoveryViewModel,
│   │                                # VendorGuidanceIntents (OEM auto-start)
│   ├── warnings/                    # WarningCenter — see contract below
│   ├── signals/                     # CameraPermissionSignal, StorageSignal,
│   │                                # MicrophonePermissionSignal,
│   │                                # ThermalStatusSignal (hysteresis-gated),
│   │                                # BatteryOptimizationSignal, …
│   ├── theme/                       # RovaTokens, RecordChromeTokens
│   ├── components/                  # Shared chrome (re-skin tokens)
│   └── permissions/, share/
└── utils/                           # VideoMerger (MediaMuxer concat), RovaCrashReporter,
                                     # RovaLog, MediaFileValidator
```

### Three control planes

1. **UI / ViewModel** — `RecordViewModel`, `HistoryViewModel`, `SettingsViewModel`, `RecoveryViewModel`, `PlayerViewModel`, `WarningCenterViewModel`. Compose collects `StateFlow`s only.
2. **Service** — `RovaRecordingService` is the only owner of CameraX + MediaMuxer. Segment-boundary timing is driven by exact `AlarmManager` alarms (TICK + STOP receivers), **never** by `Handler.postDelayed` or coroutine delays inside the service. The two STOP paths (user-stop vs loop-exhaust) are kept separate by `checkStopNoGetService` / `checkUserStoppedBeforeMerge`.
3. **Recovery + Export** — runs **once per cold launch** from `MainActivity.onCreate` → `RovaApp.triggerRecoveryScanIfNeeded()` → `RecoveryScanner.classifyAll` → `ExportRecoveryRunner`. The single-site rule is enforced by `checkScanTriggerSingleSite`. Manifests are read-only during scan; `checkRecoveryNoDeletion` forbids file deletion in the scan path.

### Session lifecycle and `Terminated`

`SessionManifest.terminated` is the persisted classifier input. `RecoveryScanner` cross-references on-disk segments against the manifest to pick `OFFER_DISCARD` / `AUTO_DISCARD_ELIGIBLE` / `BLOCKED` / `KILLED_BY_SYSTEM` / `KILLED_FORCE_STOP` / `COMPLETED`. **`COMPLETED` is only written by `performMerge`** — `checkCompletedWriteOnlyFromPerformMerge` and `checkAtomicTerminalWriteForbiddenPair` enforce it.

Atomic manifest writes: `SessionStore` writes to a temp file then renames. Terminal-state writes (`COMPLETED`, `USER_STOPPED`, etc.) must be one atomic call, not a pair.

### Tiered public export

`Tier1Exporter` (API 29+ MediaStore), `Tier2Exporter` (API 26–28 scoped temp + `MediaScannerConnection`), `Tier3Exporter` (API 24–25 direct path with `WRITE_EXTERNAL_STORAGE maxSdkVersion=28`). Tier 1 pending-row visibility rules — per ADR-0003 amendment and ROADMAP v6 — are enforced by `checkExportIsPendingGuarded`, `checkExportSetIncludePendingGuarded`, `checkExportQueryArgMatchPendingGuarded`, `checkExportPendingVisibilityOnQuery`, `checkPendingFdModeIsRW`. Cleanup predicate (`ExportCleanupPredicate`) is the single decision point; `checkExportPipelineSingleEntry` enforces that.

### DualShot (P+L mode)

Native 4:3 source + matrix-driven 27/64 side-crops (ADR-0009). `service/dualrecord/` does EGL14/GLES20 fan-out from a single `CameraEffect(target=PREVIEW)` into a single broadcast `AudioRecord` plus two `MediaMuxer` muxes. Pure JVM tests on `AspectFitMath`, `BitrateTable`, `DualMuxerStateMachine`, `RotationCalculator`, `SegmentPathBuilder`, `AudioFanOutPlanner`. Pause/resume is **deferred**. Mode picker tabs: Portrait / Landscape / P+L.

### WarningCenter

`docs/WarningCenterContract.md` is authoritative. Precedence is a **single flat order over 17 `WarningId` values** (`WarningId` enum declaration order — not category-based). Only `CAMERA_PERMISSION_DENIED` and `STORAGE_INSUFFICIENT` gate Start; `EXACT_ALARM_DENIED` is a flat non-gating banner. Surfacing model is `WarningSheet` / `WarningChip` (per-tier modal sheet collapsing to a chip), not an inline strip (ADR-0007 + 0013). `WarningCenterViewModel` output is `StateFlow<WarningId?>`. The "Idle echo promotion" pattern (`effectiveIdleTopBannerId`) lives in the routing layer, not precedence — see `memory/feedback_idle_echo_promotion.md`.

### Architecture Decision Records

Source of truth for behavioral invariants. **31 ADRs** in `docs/adr/` (0001–0031, with the 0010 slot used for two siblings about canonical UV frame and crop divergence; 0004 historically skipped). Touching anything that an ADR mentions means: amend the ADR clause first, regenerate or extend the matching `check*` task, then change the code. ADRs cover: exact alarms, headless surface, tiered storage, recovery scan, lifecycle robustness, warning sheets, dual-recording, DualShot 4:3 source, edge-to-edge record-home, gradient scrim dock, Phase 4 warning re-skin v3, snooze persistence, storage-full autostopped echo, thermal autostop, recovery merge architecture, recovery merge retry classifier preflight, asymmetric thermal hysteresis, (0020, **Proposed**) WCAG 2.2 AA by default, camera-warm-across-nav, user-facing strings in resources, localization + locale picker, SAF export destination, private vault, preset config-bundle (orientation-orthogonal), daily recording window, Liquid Glass design system (0028 — theme engine slice 1 LIVE: `PaletteColorScheme.from` drives MaterialTheme app-wide; 46th gate `checkSingleColorSchemeSource`), (0029) capture-topology × orientation-policy, (0030) Library/History information architecture, and (0031, **Accepted**; P0 + P1a slices 1–3 + **P2 Track A** + **UI-Phase-2 PR-1/PR-2** LIVE — System D glyphs rendering + branded merge animation + board-exact Record FAB lifecycle) in-app icon & glyph system — `SemanticIcon` tint seam + locked status colors + `RovaIcons` collision map + two-layer `RovaGlyph` home + `MergeMotion`/`ProcessingGlyph` merge spinner (`checkSemanticIconNoRawAlpha`, `checkStatusColorLocked`, `checkRovaGlyphHome`). The Record FAB now renders the board's 5 lifecycle states (`rec_disc`/`rec_morph`/`rec_ring`/`waiting`/`proc_arc`) via the pure `RecordFabVisualSpec` (visual ⟂ action), with `IconRole.OnAccent` = the `DialogActionColors`-AA on-accent tint for the filled disc (light accents stay ≥4.5:1 on the always-white pinned route). The app-wide premium dialog system (`RovaAlertDialog` + `DialogActionColors`, all dialogs glass-branded) rides on the same seam. Gate count unchanged at **46** (PR-1/PR-2 added no `check*`). UI-Phase-2 plan + status: `docs/UI_PHASE2_ICON_THEME_AUDIT.md` §7 — NEXT = PR-3 icon foundation → PR-4 Library → PR-5 remaining surfaces.

### Accessibility (WCAG 2.2 AA)

`docs/accessibility/` is the home for accessibility work. The 2026-05-29 static audit (`2026-05-29-wcag-2.2-aa-audit.md` + `remediation-backlog.md`) found 161 findings across all 12 UI surfaces, root-caused to low-alpha text tokens, missing Compose `semantics`, no reduced-motion gating, and undersized touch targets. **ADR-0020 sets "WCAG 2.2 AA by default" as a standing requirement for all new/changed UI** and sketches a future `checkA11y*` static-gate suite (3 of its 4 sketched gates now built — `checkA11yAnimationGated`, `checkA11yClickableHasRole`, `checkA11yTargetSizeToken`; the 4th, `checkA11yNoLowAlphaTextToken`, was **superseded by `TokenContrastTest`** — contrast is background-dependent so a static alpha-floor gate would false-fail AA-passing tokens) following the same invariant→`check*`→`preBuild` convention as the other gates. The broader source remediation is a later, separate cycle — the original audit touched zero Kotlin.

### Roadmaps — non-overlapping scope

- `ROADMAP_v6.md` — reliability backbone (scheduling, service model, surface lifecycle, STOP path, force-kill detection, drift policy, wakelock discipline, tiered export, pending-row visibility). **v6 is the authoritative reliability roadmap**; v2–v5 are historical.
- `NEW_UI_BACKEND_REPLAN.md` — UI redesign track (mockup-driven, references `mockups/new_uiux/`).
- `UI_ROADMAP.md` — older UI plan, partially superseded by the new direction.

Mockup PNGs / HTML under `mockups/` are **gitignored**. Specs that depend on measurements must inline the values (see `RecordChromeTokens.kt` — the in-source token contract).

## House conventions

- **Pure-helper extraction pattern** — anything framework-touching gets a pure-Kotlin sibling so it can be unit-tested under `isReturnDefaultValues = true`. Examples: `AspectFitMath`, `ThermalHysteresis`, `SegmentGateThermal`, `effectiveIdleTopBannerId`, `PlayerUriResolver`, `SegmentedTimelineMath`, `RecordHudFormatters`.
- **Seam pattern** — UI ViewModels collect a `StateFlow` produced by a signal (`*Signal.kt` under `ui/signals/`). Signals are constructed in `RovaApp` as lazy props and injected. Don't reach into Android system services from a ViewModel.
- **Invariant KDoc** — when a fix is driven by a code review, tag the KDoc on the helper with the review round that caught it (see `memory/feedback_invariant_kdoc_style.md`).
- **No `Modifier.blur` on the record surface** (NO-GO #3 in WarningCenterContract).
- **Recovery scan triggers only from `MainActivity.onCreate`.** Never from `Service.onCreate`, never from a `BroadcastReceiver`.
- Untracked `gradle_*.log` files in repo root are **ephemeral verification artifacts** — leave them, don't commit them, the working tree is intentionally noisy.

## Existing tooling guidance (global)

This project inherits two project-level mechanisms from the user's global `CLAUDE.md`:

- **codex MCP peer review** — call `mcp__codex__codex` for code changes >5 lines, architecture/design decisions, security-sensitive recommendations, migration plans, performance claims. Skip for conversational replies, status updates, trivial edits. See `memory/feedback_codex_consult_policy.md` — consult on contested architecture / novel patterns; not for clear-precedent routine choices.
- **CodeGraph** is initialized (`.codegraph/` present). **Never** call `codegraph_explore` or `codegraph_context` from the main session — spawn an Explore agent instead. Direct main-session use is limited to `codegraph_search`, `codegraph_callers`, `codegraph_callees`, `codegraph_impact`, `codegraph_node` (targeted lookups before edits, not exploration).
