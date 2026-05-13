# Phase 6 — Record-home Mode picker (Portrait / Landscape) — Design

**Date**: 2026-05-13
**Branch base**: `master @ 8c63b32` (post-PR-#19 R2 QA-cleanup squash)
**Branch**: `feat/record-home-mode-picker`
**Workflow**: superpowers:brainstorming → superpowers:writing-plans → superpowers:subagent-driven-development → reviewer GO → PR `--base master` → QA NO-GO/GO
**Test policy**: Pure-helper JVM-only unit tests. No Robolectric. No Compose UI tests.
**Predicted gate deltas**: tests `774 → ~789–795` (+15 to +21; plan commits a single number); lint stays `53` (50 W + 3 H + 0 E); `assembleDebug` OK predicted.

---

## 0. Scope (LOCKED)

**In-scope** — UI-only slice:
- 2 functional modes (`Portrait`, `Landscape`) behind a mockup-faithful 3-tab segmented picker with `P+L` disabled stub.
- `RovaSettings.mode: String` (default `"Portrait"`).
- `RecordViewModel.mode: StateFlow<String>` + `setMode(String)`.
- Segmented 3-tab picker rendered inline in `SessionSettingsSheet`, mirroring `mockups/new_uiux/02-settings-sheet.html` lines 339–343 / 419–423 / 499–503 verbatim.
- CameraX rotation override on `Preview.Builder` + `VideoCapture.Builder` in `RovaRecordingService` via a derived `targetRotation`.
- `SessionConfig.mode` field + `SCHEMA_VERSION 3 → 4` migration.

**Out-of-scope** (LOCKED — DO NOT TOUCH):
- P+L dual-capture (Phase 6.1, separate slice).
- Any non-Mode service concern; `service/**` diff is bounded to the Preview/VideoCapture rotation override + `currentMode` cache + `setMode` binder method + recovery hook annotation in §3.
- Any non-Mode `data/**` concern; `data/**` diff is bounded to the settings-persistence row + `SessionConfig.mode` field + manifest reader/writer migration.
- `app/build.gradle.kts` (no SDK / dep changes).
- Onboarding (mockup `08-onboarding.html` has zero Mode/Portrait/Landscape mentions).
- Recovery re-encode of prior-mode clips (rotation is baked in at encode time; merger is rotation-agnostic).
- Landscape UI elsewhere on the home screen or in other surfaces.

**HARD CONSTRAINTS** (preserve verbatim from dispatch, with one owner-approved carve-out below):
- "Do NOT start implementation until spec + plan are locked and owner has GO'd the plan."
- "Do NOT touch the parked 4.1c / 4.2 / 4.3 backlog as part of this slice."
- "Do NOT touch the P+L dual-capture path — that's Phase 6.1, not this slice."
- "out-of-scope: service/** beyond Preview/VideoCapture rotation override — zero diff otherwise" — **OWNER-APPROVED CARVE-OUT (Approach 1, brainstorm 2026-05-13)**: also permits (a) `private var currentMode: String` field + `onCreate` seed, (b) `fun setMode(mode: String)` on the binder mirroring `flipCamera()` 1:1, (c) `mode = currentMode` added to the existing `SessionConfig(...)` constructor at [RovaRecordingService.kt:760](app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt#L760), (d) `internal fun computeTargetRotation(...)` top-level helper. No other `service/**` diff.
- "data/** beyond settings-persistence row + SessionConfig.mode field"
- "app/build.gradle.kts (no SDK / dep changes)"

**Invariants byte-for-byte preserved**: WarningId enum / ordinals / `gatesStart` / all 17 rows · `WarningPrecedence.resolve(...)` signature and walk · `WarningCenterViewModel` aggregate sources + `Bools6` · Start-gate at `RecordScreen.kt:107-122` · `RecordActiveHud` / `LoopPill` / `StatusPill` / `WarningTopBanner` · all R1/R2 leaf signals · pinning tests (`WarningIdOrderTest`, `WarningPrecedenceTest`, `WarningCenterAggregateTest`, `WarningSurfaceTest`, `WarningSheetContentTest`, `MidRecBannerContentTest`, `RecordActiveHudFormattersTest`).

---

## 1. Architecture

Mode is a **bind-time CameraX configuration** that travels with the session. It lives in three places, each owning its own slice of the lifecycle:

1. **`RovaSettings.mode`** — persisted user preference (SharedPreferences). Default `"Portrait"`. Written by `RecordViewModel.setMode`; read at service `onCreate` (seed) and at `RecordViewModel` construction (initial flow value).
2. **`RecordViewModel.mode: StateFlow<String>`** — UI state for the segmented picker. Composable consumes via `collectAsStateWithLifecycle`. `setMode(String)` writes prefs → flow → service binder, in that order, so a future-collected flow value already implies the prefs commit landed.
3. **`RovaRecordingService.currentMode: String`** — service-cached value used at `setupCamera()` to derive `targetRotation` and pass to `Preview.Builder.setTargetRotation` + `VideoCapture.Builder(recorder).setTargetRotation(rot).build()`. Mutated only by `setMode(String)` on the binder, which guards on `isRecording` and re-binds via the existing `forceReconfigureCamera()` path. **Mirrors `flipCamera()` 1:1** — see [RovaRecordingService.kt:1169-1180](app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt#L1169-L1180).

Recovery: no service-side mode hook — see §3 step 8. Manifest mode is metadata-only.

### Future seamless-rebind note

`setMode` and `flipCamera` share structure: `if (isRecording) return` guard → state mutation → `serviceScope.launch { forceReconfigureCamera() }`. A future slice that ships seamless mid-rec camera flip will drop both guards symmetrically and upgrade `forceReconfigureCamera` to preserve the live `Recording` across rebind. **This slice does not ship that.** The note exists to keep the symmetric structure intact so the future migration is one place to fix. **No new abstractions, no feature flags, no signature widening in this slice. The symmetry IS the future-proofing.**

---

## 2. Components / file map

### Modified source files (6)

| File | Change |
|---|---|
| `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` | +`var mode: String` (getter coerces unknown values to `"Portrait"`; setter writes prefs). Mirrors the existing `resolution` shape at [RovaSettings.kt:17-19](app/src/main/java/com/aritr/rova/data/RovaSettings.kt#L17-L19). |
| `app/src/main/java/com/aritr/rova/data/SessionManifest.kt` | +`val mode: String = "Portrait"` on `SessionConfig` data class. Bump `SCHEMA_VERSION 3 → 4`. v3 readers fall back to default `"Portrait"`; v4 readers coerce unknown/null/empty → `"Portrait"` mirroring the existing `audioMode` null-safe pattern at [SessionManifest.kt:119-121](app/src/main/java/com/aritr/rova/data/SessionManifest.kt#L119-L121). |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt` | +`val mode = MutableStateFlow(settings.mode)` (initial = `RovaSettings.mode`). +`fun setMode(mode: String)` that (a) writes `settings.mode = mode`, (b) updates `mode.value`, (c) calls `serviceBinder?.getService()?.setMode(mode)`. Persistence collector pattern at [RecordViewModel.kt:138-141](app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt#L138-L141) is NOT replicated here — `setMode` writes prefs directly so the prefs-commit ordering matches the binder call. |
| `app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt` | Replace the read-only Mode block at [SessionSettingsSheet.kt:81-94](app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt#L81-L94) with `private @Composable fun ModeTabsPicker(currentMode, enabled, onPick)` — segmented 3-tab picker. P+L tab disabled per D9 Variant A. **New params on `SessionSettingsSheet`**: `currentMode: String`, `modeEnabled: Boolean`, `onModePick: (String) -> Unit`. The composable forwards `modeEnabled` into `ModeTabsPicker.enabled`. Drop the old `recordModeValue()` helper and the `· landscape coming soon` Text. |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` | Wire `viewModel.mode` into the `SessionSettingsSheet` call at [RecordScreen.kt:666-673](app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt#L666-L673): collect `val mode by viewModel.mode.collectAsStateWithLifecycle()`, pass `currentMode = mode`, `modeEnabled = !isUiLocked` (using the existing `isUiLocked` declaration at [RecordScreen.kt:231](app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt#L231)), and `onModePick = { viewModel.setMode(it) }`. **`RecordScreen` does NOT call `setMode` directly** — the lambda routes through the VM. The `AndroidView(PreviewView)` wrapper at [RecordScreen.kt:438-441](app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt#L438-L441) is unchanged: PreviewView respects `targetRotation` server-side, so no `Modifier.rotate` (would double-rotate) and no aspect-ratio letterbox in this slice (the Compose-side letterbox box is deferred — the PreviewView's own `ImplementationMode.COMPATIBLE` handles the swap-chain rotation cleanly for both modes). |
| `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | (a) +`private var currentMode: String = "Portrait"`, seeded in `onCreate` via `currentMode = RovaSettings(this).mode`. (b) +`fun setMode(mode: String)` on the service binder mirroring `flipCamera()` at [RovaRecordingService.kt:1169-1180](app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt#L1169-L1180): `if (_serviceState.value.isRecording) return; currentMode = mode; serviceScope.launch { forceReconfigureCamera() }`. (c) `setupCamera()` at [RovaRecordingService.kt:1103-1124](app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt#L1103-L1124): derive `val rot = computeTargetRotation(display.rotation, currentMode)`, then swap `VideoCapture.withOutput(recorder)` → `VideoCapture.Builder(recorder).setTargetRotation(rot).build()` and add `.setTargetRotation(rot)` to `Preview.Builder()`. (d) +`internal fun computeTargetRotation(displayRotation: Int, mode: String): Int` as a top-level helper in the same file (`internal`, not `private`, so `JvmTest` can reach it — Phase 3.5 PR #10 gotcha). |

### New source files (0)

None.

### New test files (3 + 1 optional)

| File | Methods (predicted) | Notes |
|---|---|---|
| `app/src/test/java/com/aritr/rova/data/SessionManifestModeMigrationTest.kt` | ~5 | v3-default (no `mode` key → `"Portrait"`), v4 round-trip (`"Portrait"`/`"Landscape"`), v4 corrupt (`"Diagonal"` → `"Portrait"`), v4 null (`JSONObject.NULL` → `"Portrait"`), v4 empty string (`""` → `"Portrait"`). |
| `app/src/test/java/com/aritr/rova/service/ModeRotationTest.kt` | 8 | `computeTargetRotation` over `Surface.ROTATION_0/_90/_180/_270` × `"Portrait"`/`"Landscape"`. |
| `app/src/test/java/com/aritr/rova/data/SessionConfigModeTest.kt` | ~3 | `SessionConfig` default (`"Portrait"`), `toJson` includes `mode`, `fromJson` round-trips. |
| `app/src/test/java/com/aritr/rova/ui/screens/ModePickerStateTest.kt` *(optional)* | ~3 | Only added if state-derivation grows beyond ~5 LOC. Target shape: `enum class ModeTab { Portrait, Landscape, PortraitLandscape }` + `internal fun activeTab(mode: String): ModeTab` — added only if the picker composable can't read `currentMode == "Portrait"` directly. |

### New docs (2)

- `docs/superpowers/specs/2026-05-13-record-home-mode-picker-design.md` (this file).
- `docs/superpowers/plans/2026-05-13-record-home-mode-picker.md` (the plan — written next).

### Wire diagram

```
RecordScreen
  └─ SessionSettingsSheet(
        currentMode = mode,                              // collected from viewModel.mode
        onModePick = { viewModel.setMode(it) },          // RecordScreen does NOT call setMode directly
        … other unchanged params)
        └─ ModeTabsPicker(currentMode, enabled, onPick)  // NEW private composable
              └─ onPick("Portrait" | "Landscape")        // P+L tab → disabled stub, no handler

RecordViewModel.setMode(mode)
  ├─ settings.mode = mode                                // RovaSettings prefs commit
  ├─ this.mode.value = mode                              // StateFlow update
  └─ serviceBinder?.getService()?.setMode(mode)          // service-side rebind

RovaRecordingService.setMode(mode)
  ├─ if (isRecording) return                             // mirrors flipCamera() guard
  ├─ currentMode = mode
  └─ serviceScope.launch { forceReconfigureCamera() }    // mirrors flipCamera() rebind
```

---

## 3. Data flow

1. **Cold start** — `RovaRecordingService.onCreate` seeds `currentMode = RovaSettings(this).mode` (default `"Portrait"`).
2. **Composable mount** — `RecordViewModel` constructs with `val mode = MutableStateFlow(settings.mode)`. `RecordScreen` collects via `collectAsStateWithLifecycle`.
3. **User opens settings sheet** — taps the idle settings card → `viewModel.openSettingsSheet()` → `combinedSettingsOpen.value = true` → `SessionSettingsSheet` mounts and reads `currentMode`.
4. **User taps a mode tab** (Portrait or Landscape) — `ModeTabsPicker.onPick(newMode)` fires → `onModePick(newMode)` → `viewModel.setMode(newMode)` → (a) `settings.mode = newMode`, (b) `mode.value = newMode`, (c) `serviceBinder?.getService()?.setMode(newMode)`.
5. **Service rebind path** — `RovaRecordingService.setMode(newMode)` → guard `if (isRecording) return` (silent — same shape as `flipCamera()`; the picker's `enabled = !isUiLocked` already prevents this UI-side, the service guard is defense-in-depth). On idle: `currentMode = newMode; serviceScope.launch { forceReconfigureCamera() }`. `forceReconfigureCamera` calls `setupCamera()`, which derives `rot = computeTargetRotation(display.rotation, currentMode)` and rebinds Preview + VideoCapture with that rotation. The PreviewView surface is unchanged (still `Modifier.fillMaxSize()`); rotation lives entirely server-side.
6. **User taps P+L tab** — disabled (`enabled = false`), no handler bound, **silent no-op** per D9 Variant A. `currentMode` does not change.
7. **User starts recording** — `RecordScreen.onStart` calls `RovaRecordingService.start(...)` → `createSession(config, currentAudioMode)` at [RovaRecordingService.kt:758-818](app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt#L758-L818). `SessionConfig` includes `mode = currentMode`; manifest persists it. Recorder uses the rotation already locked in by step 5's rebind.
8. **Recovery / manifest mode**. `SessionConfig` persists `mode` for archive/export metadata only. The service does NOT re-bind CameraX from a recovered manifest: Phase 1.5 recovery merges already-finalized segments (rotation baked in at encode time); any new post-recovery session seeds `currentMode` fresh from `RovaSettings` via `onCreate` → `setupCamera`. Mirrors how `resolution` is handled today. **By design.**
9. **Subsequent cold start (process death)** — same as step 1 (prefs survive process death).

---

## 4. Error handling / edge cases

### `display.rotation` API surface

`getDisplay()` is the pin: API 30+, available unconditionally on `Context` instances bound to a Display (the service's own Context for the Display the FGS attaches to). At `compileSdk = targetSdk = 37`, no SDK gate is needed.

### Unknown manifest mode value

The v4 reader for `SessionConfig.mode` mirrors the existing `audioMode` null-safe pattern at [SessionManifest.kt:119-121](app/src/main/java/com/aritr/rova/data/SessionManifest.kt#L119-L121):

```kotlin
mode = json.optString("mode", "").ifEmpty { null }
    ?.takeIf { it == "Portrait" || it == "Landscape" }
    ?: "Portrait"
```

`P+L` is NOT a valid persisted value in this slice (the picker stub is disabled, the writer never emits it). If it ever appears in a manifest (manual edit / future-version downgrade read), it coerces to `"Portrait"` defensively.

### `RovaSettings.mode` corrupt value

The getter:

```kotlin
var mode: String
    get() = (prefs.getString("mode", "Portrait") ?: "Portrait")
        .takeIf { it == "Portrait" || it == "Landscape" } ?: "Portrait"
    set(value) = prefs.edit { putString("mode", value) }
```

Defensive against future P+L leaked prefs (e.g., a tester flips the bit in a debug build). Setter does not validate — caller (`setMode`) is the trust boundary.

### P+L tap

Variant A: bare disabled. No handler bound (`enabled = false` → `Modifier.clickable` is omitted). Silent no-op. The "no promise contract" framing means we ship NO inline copy ("Soon", caption row, etc.) — the future P+L slice owns its own discoverability.

### Defensive `ROTATION_0` fallback

`computeTargetRotation` never throws. Inputs that don't match `Surface.ROTATION_0/_90/_180/_270` (impossible in practice on a real display, but a `display == null` edge case in tests) return `ROTATION_0`. `mode` values other than `"Portrait"` / `"Landscape"` (impossible if the trust boundary at `setMode` holds) are treated as `"Portrait"`.

### Recording in progress

UI-side: `enabled = !isUiLocked` on the picker (`isUiLocked = isPeriodicActive || isMerging`). Service-side: `if (isRecording) return` guard mirroring `flipCamera()`. Both layers prevent mid-rec rotation change. The future seamless-rebind slice will drop both guards together (see §1).

### Mode change during preview-only

The `forceReconfigureCamera()` rebind is the existing flip-camera path; it tears down + rebuilds Preview + VideoCapture. PreviewView shows a brief gap (the existing 1500 ms `cameraWarmingUp` overlay at [RecordScreen.kt:337-346](app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt#L337-L346) covers it — already proven for flip).

---

## 5. Testing

**Policy** — pure-helper JVM-only unit tests. No Robolectric. No Compose UI tests. No service-side `setMode` binder unit test (symmetric to the existing `flipCamera` precedent — neither has a binder unit test; both are too thin to cover meaningfully without a Robolectric harness).

### New test classes

1. **`SessionManifestModeMigrationTest`** (~5 methods)
   - `v3 manifest with no mode key → SessionConfig.mode == "Portrait"`
   - `v4 manifest with mode="Portrait" → round-trips`
   - `v4 manifest with mode="Landscape" → round-trips`
   - `v4 manifest with corrupt mode="Diagonal" → coerces to "Portrait"`
   - `v4 manifest with null/empty mode → coerces to "Portrait"`

2. **`ModeRotationTest`** (8 methods)
   - 4 rotations × 2 modes:
     - `Portrait + ROTATION_0 → ROTATION_0`
     - `Portrait + ROTATION_90 → ROTATION_90`
     - `Portrait + ROTATION_180 → ROTATION_180`
     - `Portrait + ROTATION_270 → ROTATION_270`
     - `Landscape + ROTATION_0 → ROTATION_90`
     - `Landscape + ROTATION_90 → ROTATION_180`
     - `Landscape + ROTATION_180 → ROTATION_270`
     - `Landscape + ROTATION_270 → ROTATION_0`
   - Formula: `if (mode == "Landscape") (displayRotation + 1) % 4 else displayRotation`. The `% 4` math is the integer-arithmetic shape that derived-rotation pattern uses; `Surface.ROTATION_0/_90/_180/_270` are the integer constants 0/1/2/3.

3. **`SessionConfigModeTest`** (~3 methods)
   - `SessionConfig` default (`mode == "Portrait"`).
   - `toJson` includes the `mode` key.
   - `fromJson` round-trips for `"Portrait"` and `"Landscape"`.

4. **`ModePickerStateTest`** *(optional)* — only if picker state derivation grows beyond ~5 LOC. Skipped by default.

### Predicted gate deltas

- **Tests**: `774 → ~789–795` (+15 to +21). Range here is the spec-stage estimate; **the plan commits a single number** per the R2 precedent (subagent-driven-development uses the plan's number as the unfiltered-gate predicted-vs-actual check).
- **Lint**: stays at `53` (50 W + 3 H + 0 E). The new helpers use only `Surface.ROTATION_*` (API 1), `display.rotation` (API 30, well below `compileSdk = 37`), `org.json.JSONObject` (API 1), `runCatching` (Kotlin stdlib). Zero `InlinedApi` / `NewApi` risk.
- **`assembleDebug`**: OK predicted.
- **No lint-baseline.xml change** — same policy as Phase 4.1b / R1 / R2.

### Pinning tests preserved (verify green at end of slice)

`WarningIdOrderTest`, `WarningPrecedenceTest`, `WarningCenterAggregateTest`, `WarningSurfaceTest`, `WarningSheetContentTest`, `MidRecBannerContentTest`, `RecordActiveHudFormattersTest`. Mode picker does not touch any precedence/Start-gate/HUD-formatter surface; these stay byte-for-byte green.

### On-device smoke (DEFERRED-to-owner)

Per the R1 / Phase 3.5 / Phase 4.1b precedent, no emulator in build env. Owner runs:
- Cold start in Portrait → tap Landscape → preview rotates server-side → tap Start → record clip → confirm output mp4 dimensions match Landscape (1920×1080 or device-class equivalent).
- Cold start in Landscape → preview rotates server-side → tap Start → record clip → confirm output mp4 dimensions match Landscape.
- Settings sheet open during preview → tap P+L tab → silent no-op (no visual feedback, no state change).
- Mid-rec settings sheet open (if mode picker is reachable while `isUiLocked` — should be `enabled = false` and tap is silent).
- Cold start at non-portrait natural orientation device (tablet) → confirm Portrait + Landscape both produce expected rotations via the derived `display.rotation + Mode-offset` math.

---

## 6. Decision table (D1–D9)

| ID | Decision | Pinned value | Source / rationale |
|---|---|---|---|
| **D1** | Mode → CameraX rotation derivation | `internal fun computeTargetRotation(displayRotation: Int, mode: String): Int = if (mode == "Landscape") (displayRotation + 1) % 4 else displayRotation`. Threaded into both `Preview.Builder.setTargetRotation(rot)` and `VideoCapture.Builder(recorder).setTargetRotation(rot).build()`. **Derives `rot` from `display.rotation` as base + Mode-offset, NOT hardcoded `ROTATION_0`/`ROTATION_90`** — handles devices whose natural orientation is non-portrait (tablets) correctly. Owner directive (post-D-table addendum). |
| **D2** | Settings persistence | `RovaSettings.mode: String`, default `"Portrait"`, getter coerces unknown to `"Portrait"`. SharedPreferences key `"mode"`. Mirrors `RovaSettings.resolution` shape. |
| **D3** | Compose-side rotation | **No `Modifier.rotate`** (would double-rotate over PreviewView's server-side rotation). **No aspect-ratio letterbox in this slice** — PreviewView's `ImplementationMode.COMPATIBLE` handles the swap-chain rotation cleanly and the existing `Modifier.fillMaxSize()` works for both modes. The mockup's `01-record-home.html` line 61 letterbox is a Phase 6.1 (P+L) shell concern, not a Mode-picker concern. |
| **D4** | Service binder contract | `fun setMode(mode: String)` on `RovaRecordingService.LocalBinder.getService()`. Guards `if (isRecording) return`. Mirrors `flipCamera()` 1:1. |
| **D5** | UI gating | `enabled = !isUiLocked` on `ModeTabsPicker` (where `isUiLocked = isPeriodicActive \|\| isMerging`, already declared in `RecordScreen` at line 231). |
| **D6** | Onboarding change | **None.** `mockups/new_uiux/08-onboarding.html` has zero Mode/Portrait/Landscape mentions. |
| **D7** | Manifest schema migration | `SCHEMA_VERSION 3 → 4`. v3 readers: missing `mode` key → default `"Portrait"`. v4 readers: present `mode` round-trips; unknown/null/empty coerces to `"Portrait"` per the `audioMode` null-safe pattern. v4 writers: always emit `mode`. |
| **D8** | Picker visual contract | Segmented 3-tab picker mirroring `02-settings-sheet.html` lines 339–343 / 419–423 / 499–503 verbatim. CSS pins (Compose-translated): track `background = rgba(255,255,255,0.05)`, `cornerRadius = 13.dp`, `padding = 3.dp`, `gap = 2.dp`. Tab: `cornerRadius = 10.dp`, `padding = 8.dp vertical / 4.dp horizontal`, `font-size ≈ 11sp` (`MaterialTheme.typography.labelLarge` matches), `font-weight = Medium`, `letter-spacing ≈ 0.1.sp`. Inactive tab: `color = rgba(255,255,255,0.26)`. Active tab: `background = rgba(255,255,255,0.11)`, `color = rgba(255,255,255,0.90)`, `shadow = 0 1.dp 4.dp rgba(0,0,0,0.35)`. Section label: `font-size = 8.5sp` (`MaterialTheme.typography.labelSmall`), `font-weight = Medium`, `letter-spacing = 2.sp`, `text-transform = uppercase`, `color = rgba(255,255,255,0.20)`, `margin-bottom = 8.dp`. **Picker is INLINE in `SessionSettingsSheet`, NOT a drill-down sheet** — no `SheetTarget` enum entry. |
| **D9** | P+L disabled-tab affordance | **Variant A — bare disabled** (owner pick, `AskUserQuestion` 2026-05-13). P+L tab text uses `color = rgba(255,255,255,0.16)` (Compose: `Color.White.copy(alpha = 0.16f)`). No "Soon" label, no caption row, no inline copy. `enabled = false`, no handler bound, silent on tap. The "no promise contract" framing means future P+L slice owns its own discoverability. |

---

## 7. Open questions / non-decisions

None at spec time. All D1–D9 owner-locked.

---

## 8. Source of truth pointers

- **Mockups**:
  - `mockups/new_uiux/02-settings-sheet.html` lines 107-126 (CSS), 339-343 (Portrait active), 419-423 (Landscape active), 499-503 (P+L active — visual reference only; this slice ships P+L disabled).
  - `mockups/new_uiux/01-record-home.html` line 61 (P+L letterbox CSS — Phase 6.1 reference).
  - `mockups/new_uiux/08-onboarding.html` (verified: zero Mode mentions; D6 = no change).
- **Code anchors**:
  - `RovaSettings` shape: [RovaSettings.kt:17-19](app/src/main/java/com/aritr/rova/data/RovaSettings.kt#L17-L19) (resolution mirror).
  - `SessionConfig` data class: [SessionManifest.kt:149-170](app/src/main/java/com/aritr/rova/data/SessionManifest.kt#L149-L170).
  - `audioMode` null-safe coercion: [SessionManifest.kt:119-121](app/src/main/java/com/aritr/rova/data/SessionManifest.kt#L119-L121).
  - `flipCamera` mirror analog: [RovaRecordingService.kt:1169-1180](app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt#L1169-L1180).
  - `setupCamera` rotation insertion site: [RovaRecordingService.kt:1103-1124](app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt#L1103-L1124).
  - `SessionSettingsSheet` Mode block to replace: [SessionSettingsSheet.kt:81-94](app/src/main/java/com/aritr/rova/ui/screens/SessionSettingsSheet.kt#L81-L94).
  - `RecordScreen` SessionSettingsSheet call site: [RecordScreen.kt:666-673](app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt#L666-L673).
  - `RecordViewModel.flipCamera`: [RecordViewModel.kt:196-198](app/src/main/java/com/aritr/rova/ui/screens/RecordViewModel.kt#L196-L198).
  - `isUiLocked` declaration: [RecordScreen.kt:231](app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt#L231).
- **Precedent**:
  - Phase 3.5 PR #10 — `internal` not `private` for JVM-test-reachable helpers.
  - Phase 4.1b — `audioMode` null-safe enum pattern; per-task plan structure.
  - R1 PR #17 / R2 PR #18 / PR #19 — on-device smoke DEFERRED-to-owner precedent.
