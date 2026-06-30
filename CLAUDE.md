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

`app/build.gradle.kts` registers **47 custom `check*` tasks** wired into `preBuild` via `pluginManager.withPlugin("com.android.application") { tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(...) } }`. Each is a regex/AST scan that enforces an invariant from a specific ADR clause:

- `checkSchedulerNoGetService`, `checkStopNoGetService`, `checkRecoveryNoDeletion`, `checkRecoverySegmentRegex`, `checkScanTriggerSingleSite`, `checkRecoveryReceiverCounter`, `checkAtomicTerminalWriteForbiddenPair`, `checkExternalRootShared`, `checkAudioModeFgsTypeMatch`, `checkFGSStartGuarded`, `checkUserStoppedBeforeMerge`, `checkExportTierReadTolerant`, `checkScanFileBoundedWait`, `checkPendingFdModeIsRW`, `checkExportIsPendingGuarded`, `checkExportSetIncludePendingGuarded`, `checkExportQueryArgMatchPendingGuarded`, `checkExportPendingVisibilityOnQuery`, `checkExportCleanupPredicate`, `checkExportNoCopyToPublicMovies`, `checkExportPipelineSingleEntry`, `checkCompletedWriteOnlyFromPerformMerge`, `checkWakeLockBoundedAcquire`, `checkWakeLockHeldRefresh`, `checkWakeLockZeroGapRefresh`, `checkNoHardcodedUiStrings`, `checkLocaleConfigNoPseudolocale`, `checkA11yAnimationGated`, `checkScheduleReceiverNoFgsStart`, `checkSafTargetCommittedBeforeStream`, `checkPresetNoOrientation`, `checkNoLegacyModeStrings`, `checkSetTargetRotationBoundaryOnly`, `checkFrontBackCapabilityGated`, `checkUserCopyVocabulary`, `checkVaultExporterNoPublicPublish`, `checkRecordSurfaceNoBlur`, `checkGlassSurfaceRoleUsage`, `checkRecordChromeLockSingleSite`, `checkA11yClickableHasRole`, `checkA11yTargetSizeToken`, `checkLibraryNoManifestWrite`, `checkSemanticIconNoRawAlpha`, `checkStatusColorLocked`, `checkRovaGlyphHome`, `checkSingleColorSchemeSource`, `checkAeFpsRangeCapabilityGated`.

If a check fails it cites the ADR clause it enforces. **Do not edit a check away to make it green** — fix the source, or amend the ADR (and update the check) only with explicit owner sign-off.

**Gate implementation (config-cache-safe).** Each `check*` is a typed `com.aritr.rova.gradle.SourceCheckTask` (`@CacheableTask`, in the `build-logic/` included build) registered with a `checkId` + declared `sources` (`@InputFiles @PathSensitive(RELATIVE)`) + a `sentinel` (`@OutputFile`, written only on success → UP-TO-DATE/build-cacheable). The task reads each source into a Gradle-free `SourceFile(relPath, lines, text)`, sorts by `relPath`, and calls the pure `RovaGateRules.run(checkId, files)` registry — a `Map<String, (List<SourceFile>) -> String?>` where each rule is the **verbatim-lifted** regex/AST logic, comment-skipping, scope, and failure message, returning the exact message to throw or `null`. No `Project`/script reference is captured at execution, so `org.gradle.configuration-cache=true` is enabled. The rules + golden JVM tests live in `build-logic/src/{main,test}/kotlin/com/aritr/rova/gradle/` (`RovaGateRules_*.kt` by family). **Comment detection (PR #145 + full sweep #146):** comment-aware gates detect on `SourceFile.strippedLines` — the lazy `CommentStripper.strip(text)`, a single-pass, string/char/raw-string-literal-aware, nesting-aware, newline+length-preserving scanner that blanks `//` and `/* */` to spaces while keeping literals verbatim — then read opt-out markers and report offenders from the RAW `lines`, so a `/*…*/`-then-code line or a `/*` inside a literal can neither hide a violation nor disable a gate, while message/line offsets stay byte-stable. #145 migrated 7 gates; the full sweep migrated the remaining ~30 forbid/structural gates (trigger detection on `strippedLines`, co-presence windows + opt-out + report stay RAW — minimal recipe) and consolidated the two non-nesting whole-text strips (`RovaGlyphHome`, `RecordChromeLockSingleSite`) onto `CommentStripper.strip`. **Round-3 (#147, merged `a45cde4`, build-logic only)** migrated the remaining stricter-direction leaves onto `strippedText`/`strippedLines` (false-pass-only, owner-signed-off + codex-reconciled): `WakeLockHeldRefresh` body, `WakeLockZeroGapRefresh` `hasRefresh`, `ExportIsPendingGuarded` `hasFileGuard`, and `ExportPendingVisibilityOnQuery` (TRIGGER + all 3 require-tokens), plus a DRY `SourceFile.strippedLine(idx)` accessor (`strippedLines.getOrElse(idx){""}`) replacing 39 verbatim call sites. **Round-4 (build-logic only, completeness audit + owner-signed-off + codex-reconciled)** swept EVERY remaining raw-comment site across all 46 gates and migrated 7 stricter-only REQUIRE reads onto stripped substrate (literal `.contains`, proven RED-before/GREEN-after with new golden tests): `AudioModeFgsTypeMatch` `hasAudioModeBefore`, `FGSStartGuarded` (file scan guard → `strippedText` + 60-line catch-arm window → `strippedLines`), `ExportSetIncludePendingGuarded` + `ExportQueryArgMatchPendingGuarded` (±30 SDK-branch windows → `strippedLines`), `SafTargetCommittedBeforeStream` `commitsBefore`, `A11yAnimationGated` `hasSeam`, and `ExportCleanupPredicate` (the LAST S2 manual line-prefix filter → `strippedText`; this one is the explicit mixed-direction exception — it closes a real trailing-comment false-PASS AND flips a block-open-then-code false-FAIL to accept, an owner-approved correctness fix, NOT pure stricter-only). **Documented leaves after round-4** (still NOT on the primitive, by design): `SingleColorSchemeSource` (whole-text strip that must BLANK string/char literals — incompatible with CommentStripper, which keeps literals verbatim); `WakeLockZeroGapRefresh`'s `hasElse` sub-check and `A11yClickableHasRole`'s `roleAssign` window (both REQUIRE regexes with `\s*` — blanking a comment to spaces would let `} /*c*/ else {` / `role = /*c*/ Role.X` newly-MATCH = newly-accepts valid code = lenient, so they stay RAW); the forbid/co-presence windows `AtomicTerminalWriteForbiddenPair` / `UserStoppedBeforeMerge` / `CompletedWriteOnlyFromPerformMerge` / `SemanticIconNoRawAlpha` (a RAW window can't HIDE a real violation, so migrating only drops false-FAILs = lenient); and the XML forbid gates `LocaleConfigNoPseudolocale` / `UserCopyVocabulary` (XML, not Kotlin; forbid direction lenient anyway). When authoring a new `RovaGateRules` KDoc, never write a bare `/*`/`*/` in the doc prose — Kotlin nests block comments, so it self-nests into "Unclosed comment". **Adding a new invariant means:** ADR clause → new rule in `RovaGateRules` (+ golden test) → `tasks.register<SourceCheckTask>` in `app/build.gradle.kts` → add to the `preBuild` `dependsOn` block → add its id to `RegistryTest` `EXPECTED_IDS`. The non-negotiable rule for *changing* a gate's mechanism: it changes only HOW the gate runs, never WHAT it enforces (byte-identical message + logic; prove it still RED-fires on a real violation).

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
│   ├── singlerecord/                # Single-mode VideoCapture<Recorder> build +
│   │                                # per-segment Recording start/stop
│   │                                # (SingleVideoRecorder, mirror of dualrecord/)
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

### Single-mode recording (`service/singlerecord/`)

Single-capture (non-DualShot) topology. `SingleVideoRecorder` is the collaborator that owns the CameraX `VideoCapture<Recorder>` build (incl. its build-time `setTargetRotation` — ADR-0029 §3 boundary, this package allowlisted symmetric with `dualrecord/`) and the per-segment `Recording` start/stop, mirroring `service/dualrecord/DualVideoRecorder`. The service binds `recorder.videoCapture` in `setupSingleCamera` and drives one `recorder.start(...)` per segment from `recordSegment`; the active handle is `currentSingleRecording: SingleRecording?`. The segment loop, watchdog, finalize-coordination deferreds, persistence, `performMerge`, terminal writes, wakelock, and recovery stay in the service (exactly as the dual path keeps `recordSegmentDual`/`performMergeDual`). CameraX `VideoRecordEvent` is passed straight through to the service callback (no event remap); the `withAudioEnabled()` `SecurityException` propagates → `PERMISSION_REVOKED`. Pure JVM test on `SingleQualitySelector` (D-deviation — CameraX `Quality` only at the recorder edge). **Invariant (do not "fix"):** `SingleVideoRecorder.start()` overwrites `active` WITHOUT a `check(active==null)` guard — unlike `DualVideoRecorder` which clears its handle on stop via `onStopped`; single REUSES one recorder across all segments and never clears `active`, so a guard would false-fail segment 2. Extracted from inline-in-service in PR #159 (2026-06-30, zero behavior change, device-verified RZCYA1VBQ2H) — closes the one structural target named by the service-split audit (`memory/project_service_decomp_audit.md`).

### DualShot (P+L mode)

Native 4:3 source + matrix-driven 27/64 side-crops (ADR-0009). `service/dualrecord/` does EGL14/GLES20 fan-out from a single `CameraEffect(target=PREVIEW)` into a single broadcast `AudioRecord` plus two `MediaMuxer` muxes. Pure JVM tests on `AspectFitMath`, `BitrateTable`, `DualMuxerStateMachine`, `RotationCalculator`, `SegmentPathBuilder`, `AudioFanOutPlanner`. Pause/resume is **deferred**. Mode picker tabs: Portrait / Landscape / P+L.

### WarningCenter

`docs/WarningCenterContract.md` is authoritative. Precedence is a **single flat order over 17 `WarningId` values** (`WarningId` enum declaration order — not category-based). Only `CAMERA_PERMISSION_DENIED` and `STORAGE_INSUFFICIENT` gate Start; `EXACT_ALARM_DENIED` is a flat non-gating banner. Surfacing model is `WarningSheet` / `WarningChip` (per-tier modal sheet collapsing to a chip), not an inline strip (ADR-0007 + 0013). `WarningCenterViewModel` output is `StateFlow<WarningId?>`. The "Idle echo promotion" pattern (`effectiveIdleTopBannerId`) lives in the routing layer, not precedence — see `memory/feedback_idle_echo_promotion.md`.

### Architecture Decision Records

Source of truth for behavioral invariants. **34 ADRs** in `docs/adr/` (0001–0034, with the 0010 slot used for two siblings about canonical UV frame and crop divergence; 0004 historically skipped). Touching anything that an ADR mentions means: amend the ADR clause first, regenerate or extend the matching `check*` task, then change the code. ADRs cover: exact alarms, headless surface, tiered storage, recovery scan, lifecycle robustness, warning sheets, dual-recording, DualShot 4:3 source, edge-to-edge record-home, gradient scrim dock, Phase 4 warning re-skin v3, snooze persistence, storage-full autostopped echo, thermal autostop, recovery merge architecture, recovery merge retry classifier preflight, asymmetric thermal hysteresis, (0020, **Proposed**) WCAG 2.2 AA by default, camera-warm-across-nav, user-facing strings in resources, localization + locale picker, SAF export destination, private vault, preset config-bundle (orientation-orthogonal), daily recording window, Liquid Glass design system (0028 — theme engine slice 1 LIVE: `PaletteColorScheme.from` drives MaterialTheme app-wide; 46th gate `checkSingleColorSchemeSource`), (0029) capture-topology × orientation-policy, (0030) Library/History information architecture, and (0031, **Accepted**; P0 + P1a slices 1–3 + **P2 Track A** + **UI-Phase-2 PR-1…PR-6 + icon 5b-1…5b-5 + 5b-4 (#136)** LIVE — System D glyphs rendering + branded merge animation + board-exact Record FAB lifecycle + glyphs wired into Warnings/Settings/record-chrome/Onboarding + RecoveryCard + Library status badges; **ADR-0031 CLOSED**) in-app icon & glyph system — `SemanticIcon` tint seam + locked status colors + `RovaIcons` collision map + two-layer `RovaGlyph` home + `MergeMotion`/`ProcessingGlyph` merge spinner (`checkSemanticIconNoRawAlpha`, `checkStatusColorLocked`, `checkRovaGlyphHome`). The Record FAB now renders the board's 5 lifecycle states (`rec_disc`/`rec_morph`/`rec_ring`/`waiting`/`proc_arc`) via the pure `RecordFabVisualSpec` (visual ⟂ action), with `IconRole.OnAccent` = the `DialogActionColors`-AA on-accent tint for the filled disc (light accents stay ≥4.5:1 on the always-white pinned route). The app-wide premium dialog system (`RovaAlertDialog` + `DialogActionColors`, all dialogs glass-branded) rides on the same seam. Gate count after UI Phase 2: **46** (UI Phase 2 added no `check*`); bumped to **47** by ADR-0034 (`checkAeFpsRangeCapabilityGated`). UI-Phase-2 status: PR-1 #122 · PR-2 #123 · PR-3 icon foundation #124 · PR-4 Library+Vault #125 · PR-5 Player #126 · **PR-6 player-nav #127** (interactive timeline: tap/drag seek + clip ticks + clip-jump + seekbar a11y + reduced-motion glide; resume DEFERRED) · **icon 5b-1 glyph-foundation #128** (`Play` d-string + `Interrupted→escalating`; `FlipCam`/`FlashBolt`; **24 new System-D glyphs** in board v3 SSOT; `RovaIcons` 27 concept + 2 status entries) — ALL MERGED (master `55dd894`). **icon 5b-2 Warnings #130 · 5b-3 Settings #131 · 5b-5 record-chrome #132** (route warning/settings glyphs via `WarningIconSpec`/`SemanticIcon`; FlipCam rotation-arrows flip glyph + nav Library/Settings glass-circles + History card glyph-size bump + flash OFF/AUTO theme-accent) — MERGED (master `c493538`, 2026-06-22). NEXT (icon system done): **player resume-persistence** (spec written `docs/superpowers/specs/2026-06-22-player-resume-identity-seam-design.md` — sessionId-canonical identity seam shared by Library + Player; player URI ≠ Library `stableKey`=`file.absolutePath` on TIER1, `prune` drops orphans) → **PR-6b** wall-clock playhead (manifest schema bump) → **PR-7** speed · double-tap · auto-hide. **Differentiated recording cues SHIPPED** (#133 → master `ff783fa`, 2026-06-23): first-segment long `rova_cue_start` / short `rova_beep` reminder / no end cue via `beepStart(isFirstSegment)` + codex CancellationException-rethrow; `memory/project_differentiated_cues.md`. Repo also gained a read-only `scripts/preflight.{ps1,sh}` git-hygiene scan (#134 → `aae66e7`; House convention). Plans: `docs/UI_PHASE2_ICON_THEME_AUDIT.md` §7; player roadmap in `docs/BACKLOG.md` "Video / Player / Editing". Newer ADRs beyond the 0031 block: **(0032)** wall-clock playhead (PR-6b), **(0033)** sub-minute (30s) interval, and **(0034, Accepted)** DualShot AE frame-rate floor — capability-gated `[24,30]` (asymmetric; brightness-preferring fallback that prefers a true ceiling-spanning range, else the lowest pinned fps ≥ floor) lifting the dim-light auto-exposure ceiling (**Limiter 1** of the 2026-06-29 fps-cadence diagnosis). Pure `AeFpsRangePolicy` (D-deviation; `android.util.Range` only at the service edge) + `applyAeFpsFloor` in `setupDualCamera` (intersects available ranges across matched back cameras — `DEFAULT_BACK_CAMERA` resolves to several physical cams; fail-open). 47th gate `checkAeFpsRangeCapabilityGated`. Shipped #155 (master `4325808`), **device-verified** on RZCYA1VBQ2H: dim DualShot cadence held at **24fps** (effective `aeTargetFpsRange=[24,24]`) vs the pre-fix ~16.7fps collapse; the `Preview.Builder` Camera2Interop option propagates through the `CameraEffect` fan-out. **Encoder Limiter 2 — DIAGNOSED & CLOSED, no fix (shipped #157 → master `b1f7850`, 2026-06-30).** Micro-diagnosis (service-split + drain-path + single-side discriminator, all on RZCYA1VBQ2H, codex-reconciled) proved the ~45ms encoder service (~22fps merged output) is **inherent structural dual-HW-encode contention** on this Exynos: a single 540×960 encode sustains 24fps (~37ms `glFinish`), two concurrent encodes add ~5ms/frame → ~22fps + ~10% mailbox drops. `glFinish` is the wall but is a downstream sync stall (codec producer-queue or EGL-context serialization), **not** GPU fill (a quad is microseconds) and **not** muxer write (`onSample`=0.5ms, ruled out). No cheap lever: `KEY_OPERATING_RATE=120`/`KEY_PRIORITY=0` = null. Every remaining fix is structural (stagger/shared-encode, or lower per-side res — a regression vs shipped Quality presets) inside the load-bearing `glFinish`/FBO/fence stack (#25–#35), for only ~2fps — **not worth the risk**; ~22fps accepted for a hands-free background recorder. The **DEBUG fps-cadence probe is REMOVED** this slice (`CadenceProbe`/`CadenceStats`/`EglRouter`+`EglEncoder` taps/`FrameMailbox.overwriteCount` + tests reverted; the Camera2Interop AE-metadata log in `RovaRecordingService` is KEPT — it verifies the shipped Limiter-1 floor). Full close verdict: `docs/superpowers/specs/2026-06-29-dualshot-fps-cadence-findings.md` ("Limiter-2 RESOLUTION"). Handoff `docs/superpowers/handoffs/2026-06-29-NEXT-dualshot-encoder-limiter2.md` is now historical.

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
- **Task pre-flight** — at the start of each task run `pwsh scripts/preflight.ps1` (POSIX: `bash scripts/preflight.sh`). It is **read-only** (reports branch/dirty-tree/sync-vs-origin/stale-branches/worktrees, whitelists the `gradle_*.log` noise, never mutates) and prints suggested fix commands for you to run manually. Resolve every `[FLAG]` before branching for new work.

## Existing tooling guidance (global)

This project inherits two project-level mechanisms from the user's global `CLAUDE.md`:

- **codex MCP peer review** — call `mcp__codex__codex` for code changes >5 lines, architecture/design decisions, security-sensitive recommendations, migration plans, performance claims. Skip for conversational replies, status updates, trivial edits. See `memory/feedback_codex_consult_policy.md` — consult on contested architecture / novel patterns; not for clear-precedent routine choices.
- **CodeGraph** is initialized (`.codegraph/` present). **Never** call `codegraph_explore` or `codegraph_context` from the main session — spawn an Explore agent instead. Direct main-session use is limited to `codegraph_search`, `codegraph_callers`, `codegraph_callees`, `codegraph_impact`, `codegraph_node` (targeted lookups before edits, not exploration).
