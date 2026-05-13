# Record-home redesign R2 — active-state HUD restyle + mid-rec top-banner surface

- **Status:** Draft (owner review pending — section-by-section approval recorded 2026-05-13)
- **Date:** 2026-05-13
- **Slice:** R2 (the active-state half of the Record-home redesign; follows R1 which shipped 2026-05-12 @ master `07fec76`)
- **Branch:** `feat/record-home-redesign-r2` (off `master` @ `07fec76`)
- **Owner:** see git history
- **Supersedes:** nothing (additive over R1)
- **Amends:** ADR 0007 §1 (the deferred-to-R2 top-banner render path) and the 16-row precedence model (now 17)
- **Related:** `mockups/new_uiux/01-record-home.html` (active-state HUD), `mockups/new_uiux/07-warnings.html` row 6 (mid-rec banner contract), `docs/WarningCenterContract.md`, `NEW_UI_BACKEND_REPLAN.md` §6, R1 spec `docs/superpowers/specs/2026-05-12-record-home-redesign-r1-design.md`, R1 plan `docs/superpowers/plans/2026-05-12-record-home-redesign-r1.md`, ADR 0007 `docs/adr/0007-record-warning-sheets.md`

---

## §1 — Scope, invariants, sources of truth

**Scope.** Re-skin the Record screen's three active HUD states (Recording / Waiting / Merging) to the `01-record-home.html` chrome (loop-pill + status-pill + Stop-FAB), retire the five legacy active-state surfaces (`SessionTimer`, `ClipProgressBand`, `WaitingCountdown`, `MergingProgressBand`, `RecordStatusStrip`), and ship the mid-recording top-banner surface from ADR 0007. One enum row is added: `STORAGE_LOW_MID_REC`, with a new leaf signal `StorageLowMidRecSignal`. UI-only — no `service/**` or `data/**` diff (the existing `StorageEstimator.bytesPerSecondForResolution` is read from `data/`, not modified). No `app/build.gradle.kts` change.

**Sources of truth (verify in this order).**

1. `mockups/new_uiux/01-record-home.html` — HUD chrome: loop-pill (`<div class="loop-pill">`, line 124 stylesheet, 454/501/etc. instances) + status-pill (line 133 stylesheet, 455/502/etc. instances) + the active-state Stop FAB layout.
2. `mockups/new_uiux/07-warnings.html` rows 6 / 11 / thermal — mid-rec top-banner contract (`<div class="banner banner-top bn-y">`, line 322 instance; amber, icon + title + sub + secondary "Stop" CTA; pushes HUD chrome down; non-blocking).
3. `docs/adr/0007-record-warning-sheets.md` §1 last sub-bullet — the deferred-to-R2 top-banner list.
4. R1 spec + R1 plan — what R1 already shipped and what it left intact (RecordHudState, RecordHudFormatters, MergeCompleteCard, camera-disconnect AlertDialog, RecordChrome.kt idle surfaces, the WarningCenter precedence VM / 16-row enum / Start-gate).
5. `docs/WarningCenterContract.md` — the 16-row precedence table (this spec amends it to 17).

**Invariants — R2 amends one and preserves the rest.**

- **Amended:** the "16-row precedence" invariant gains one row → 17. `WarningId.kt` gains one entry (`STORAGE_LOW_MID_REC`, placement § 3.2 / D3a), `WarningPrecedence.resolve(...)` gains one Boolean param, `WarningCenterViewModel.aggregate(...)` gains one source flow, `WarningIdOrderTest` pins the new 17-row order. Additive only — no deletions, no reorders of existing rows.
- **Preserved byte-for-byte:**
  - The 16 existing rows of `WarningId` stay in their current ordinal positions; the new row is inserted at ordinal 11 (see § 3.2). Tier values (`HARD_BLOCK`/`CRITICAL`/`ADVISORY`) and `gatesStart` flags for the 16 existing rows are unchanged.
  - The Start-gate logic in `RecordScreen` (`startBlocked = !cameraPermissionGranted || storageInsufficient`, leaf-signal reads, the two `LaunchedEffect`s, the ON_RESUME refresh arm — R1 §3.5 preserve list) is untouched.
  - `WarningSurface` enum (`HardBlockSheet` / `SoftSheet` / `AdvisorySheet` / `TopBanner`) is unchanged — the existing `TopBanner` value, which has been mapped-but-no-op since R1, becomes a live render path in R2.
  - The 14 existing `warningSurfaceFor(id)` arms are unchanged; one arm is added for the new row.
  - `WarningCenterViewModel` factory wiring shape is unchanged — one additional source flow gets passed in.

**Test invariants.** R1's "no Robolectric / no Compose-UI" policy holds for R2. All new tests are JVM helper tests; the active-HUD layout itself is owner-verified on device.

---

## §2 — Locked decisions (D1–D11) + assumptions (A1–A5)

### Decisions

**D1.** Canon mockup = `mockups/new_uiux/01-record-home.html` (active-state HUD shapes) + `mockups/new_uiux/07-warnings.html` row 6 (mid-rec banner contract). No additional mockups consulted.

**D2.** Merging state UI = the same status-pill chrome as Recording/Waiting, with `dot-merging` color, main text `"Merging…"`, time text `· N%` (rendered from `state.progress`). Stop-FAB is `Disabled` while merging (D9). The existing `MergeCompleteCard` continues to render once merging completes (R1-preserved). **No new component is introduced for Merging.** The 1-based-segment-of-total derivation already inside `RecordHudState.Merging` is unused by the pill (the mockup shows only the percent) but kept for any future "Merging 4 of 6 clips" line — out of scope here.

**D3.** Mid-rec top-banner routes the nine ADR-0007 mid-rec `WarningId`s (`THERMAL_SHUTDOWN`, `THERMAL_EMERGENCY`, `THERMAL_CRITICAL`, `THERMAL_SEVERE`, `THERMAL_MODERATE`, `BATTERY_CRITICAL`, `BATTERY_LOW`, `CAMERA_IN_USE`, `CAMERA_DISABLED`) **plus the new `STORAGE_LOW_MID_REC`** = ten ids. All ten map to `WarningSurface.TopBanner` in `warningSurfaceFor(id)`. At idle the TopBanner-tier warnings continue to render nothing (the deliberate ADR-0007 choice — these warnings have no surface at idle). At active (Recording/Waiting/Merging) they render as the top banner.

**D3a.** Placement of `STORAGE_LOW_MID_REC` in the enum is **immediately after `BATTERY_LOW` (ordinal #10) and before `THERMAL_SEVERE`**, giving it ordinal `#11`. Rationale: it is `ADVISORY`-tier (recording continues, user decides), more urgent than the configuration warnings (mic/notif/battery-opt/power-save) because storage exhaustion is the only advisory condition that will autonomously halt the session (the per-segment storage gate in `RovaRecordingService` will stop the session when free bytes actually run out — the banner is the user's "you can act first" window). It ranks **below** `BATTERY_LOW` because a session at 14 % battery has less remaining runtime than a session with `LOW_THRESHOLD_CLIPS` clips of headroom in the common case (with quality-typical clip durations); ordering the two this way matches the mockup's "user decides whether to stop or ignore" framing for storage vs. battery's "consider charging" framing.

**D4.** `StorageLowMidRecSignal` is a new leaf signal under `ui/signals/`. Same architectural shape as `StorageSignal` — `forContext(Context)` factory + a `computeIsLow` lambda seam for tests. UI-side StatFs poll every ~30 s while `hudState !is RecordHudState.Idle`. Fires when `freeBytes < LOW_THRESHOLD_CLIPS × bytesPerClip(durationSeconds, resolution)`, where `bytesPerClip = StorageEstimator.bytesPerSecondForResolution(resolution) * durationSeconds`. `LOW_THRESHOLD_CLIPS = 3` (companion-object constant — file-local, not a public knob). Null external root → returns `false` (don't false-warn — symmetric with `StorageSignal`). StatFs throw → returns `false`. **No hysteresis** in R2 — if `freeBytes` oscillates around the threshold the banner flickers. Acknowledged tradeoff: the threshold is deliberately conservative (3 clips of capture-only bytes), so oscillation is unlikely; if flicker is observed on device the fix is bundled with the future 4.1c snooze/hysteresis work. **No `service/**` diff** — StatFs and `StorageEstimator.bytesPerSecondForResolution(...)` are both read-only from UI.

**D5.** Active-HUD chrome composables are added to the existing `ui/screens/RecordChrome.kt` — same file that already houses the R1 idle chrome. New `internal` symbols: `RecordActiveHud(state: RecordHudState, modifier: Modifier = Modifier)`, `internal fun hudStatusPillContent(state: RecordHudState): StatusPillContent`, plus file-private `LoopPill`, `StatusPill`, `StatusDot` composables and per-state dot colors (`RecordingDotColor`, `WaitingDotColor`, `MergingDotColor`) as file-local `val`s.

> Note (post-implementation): `StatusDotColor.BREAK` was renamed to `WAITING` in T2 code-quality review to align with `RecordHudState.Waiting`. Spec text updated 2026-05-13.

**D6.** Top-banner composable is added to `ui/warnings/WarningCenter.kt`. `WarningCenter` composable gains one parameter: `hudState: RecordHudState`. Routing rule inside `WarningCenter`: TopBanner surfaces render only when `hudState !is RecordHudState.Idle`; Sheet/Chip surfaces render only when `hudState is RecordHudState.Idle`. (Today, TopBanner surfaces always no-op via the `if (surface == WarningSurface.TopBanner) return` at line 130 — that early-return is replaced with the hud-state branch.) New file-private composable `WarningTopBanner(content, onAction)` + new helper `internal fun midRecBannerContent(id: WarningId): TopBannerContent` + new internal data class `TopBannerContent(icon: ImageVector, title: String, sub: String, cta: String)`. **`warningSurfaceFor(id)` does not gain a `hudState` parameter** — the id→surface map stays pure data; the active-vs-idle routing lives in `WarningCenter`. Only the new arm for `STORAGE_LOW_MID_REC` is added.

**D7.** `RecordCameraControls` (flash / flip — R1) is hidden during all three active states. Concretely: the existing `if (hudState is RecordHudState.Idle) RecordCameraControls(...)` branch in `RecordScreen` is kept; for non-idle states no flash/flip controls are rendered. Reason: mid-record `flip` would interrupt the CameraX session; the mockup does not show these controls in active screens.

**D8.** `RecordRecoveryChip` (R1) and the collapsed `WarningChip` (R1) are hidden during all three active states. Mid-rec warnings surface through the top banner instead; the recovery chip is an idle-only affordance.

**D9.** Stop-FAB enabled state per HUD state:

| HudState | FAB state |
|---|---|
| `Idle` | `Start` (unchanged — R1) |
| `Recording` | `Stop` (enabled) |
| `Waiting` | `Stop` (enabled — early-abort during break interval) |
| `Merging` | `Disabled` (no clean cancel path in the service — matches pre-R1 behavior) |

The `recordFabState(...)` helper from R1 already returns `Start` / `Stop` / `Disabled` per these conditions. R2 verifies the existing mapping covers D9 verbatim; the only change is the visual (red stop-icon during active states is a `RecordBottomNav` rendering concern, not a state-machine change — see § 3.5).

**D10.** Top-banner z-order vs. the loop+status pill row = banner pushes the pills **down**. The two are stacked in a `Column(verticalArrangement = Top)` at the top safe-area: when the banner is present it occupies the first slot and the `RecordActiveHud` row sits below it; when absent, the pill row floats at the top alone. Both are simultaneously visible whenever a banner is active.

**D11.** Test policy = R1 verbatim. New JVM-only helper tests; no Robolectric, no Compose-UI test. Layout is owner-verified on device per § 5.3.

### Assumptions (call-outs that may need clarification during implementation)

**A1.** `RecordHudState.Recording` and `RecordHudState.Waiting` are currently `data object`s with no fields — the per-clip countdown and the wait countdown are derived inside `RecordScreen` from the `produceState` timers (R1 §3.5 preserve list). The status-pill content therefore needs the countdown values as additional inputs alongside `RecordHudState`. The contract in § 3.1 takes those as explicit ints rather than mutating `RecordHudState`. If implementation finds the timer values are not cleanly exposable as ints at the call site, the helper signature falls back to `hudStatusPillContent(state, clipSecondsLeft, waitSecondsLeft, mergeProgress)` (no `RecordHudState` shape change). **`RecordHudState.kt` stays unmodified in R2.**

**A2.** Loop counter — `RecordHudState` does not currently expose `loopIndex` / `loopTotal`. RecordScreen owns these via VM state. The `LoopPill` composable takes `loopIndex: Int, loopTotal: Int` as parameters; `RecordActiveHud` accepts both alongside the timer ints and wires them in. No `RecordHudState` shape change required.

**A3.** Settings card (R1) shows only at `Idle` — confirmed in R1 spec. R2 preserves: active states render no `RecordSettingsCard`.

**A4.** The "Merging" status-pill shows `· N%` where `N = (state.progress * 100).toInt().coerceIn(0, 100)`. The 1-based `currentSegment` inside `RecordHudState.Merging` is not rendered in the pill.

**A5.** The mid-rec banner has one secondary CTA: `"Stop"`. It triggers `stopRecording()` (the same path the Stop-FAB takes). The primary "fix this" CTA from the sheet model is **not** present on the top banner — most mid-rec warnings have no in-app action (thermal cools on its own, battery needs a charger, camera-in-use needs the other app to release, storage needs the user to stop). Banner is informational + one secondary "Stop". The R1 launch-action-target machinery is **not** wired through the top-banner path.

---

## §3 — Composable contracts

### 3.1 `RecordActiveHud` — new in `RecordChrome.kt`

```kotlin
/**
 * Top-anchored active-HUD chrome for Recording / Waiting / Merging.
 * Renders [LoopPill] (when loopTotal > 1) above [StatusPill].
 * At [RecordHudState.Idle] the caller MUST NOT mount this composable —
 * idle uses [RecordTopOverlay] (R1) instead.
 */
@Composable
internal fun RecordActiveHud(
    state: RecordHudState,
    loopIndex: Int,
    loopTotal: Int,
    clipSecondsLeft: Int,
    waitSecondsLeft: Int,
    modifier: Modifier = Modifier,
)
```

**Pure helper** `hudStatusPillContent` — unit-testable:

```kotlin
internal data class StatusPillContent(
    val dot: StatusDotColor,           // enum: RECORDING, BREAK, MERGING
    val main: String,                  // "Recording" / "On break" / "Merging…"
    val time: String,                  // "· 0:18 left" / "· next in 0:42" / "· 53%"
)

internal enum class StatusDotColor { RECORDING, BREAK, MERGING }

internal fun hudStatusPillContent(
    state: RecordHudState,
    clipSecondsLeft: Int,
    waitSecondsLeft: Int,
): StatusPillContent = when (state) {
    RecordHudState.Recording -> StatusPillContent(
        dot = StatusDotColor.RECORDING,
        main = "Recording",
        time = "· ${mmss(clipSecondsLeft)} left",
    )
    RecordHudState.Waiting -> StatusPillContent(
        dot = StatusDotColor.WAITING,
        main = "On break",
        time = "· next in ${mmss(waitSecondsLeft)}",
    )
    is RecordHudState.Merging -> StatusPillContent(
        dot = StatusDotColor.MERGING,
        main = "Merging…",
        time = "· ${(state.progress * 100).toInt().coerceIn(0, 100)}%",
    )
    RecordHudState.Idle ->
        error("hudStatusPillContent called with Idle — caller bug; gate on hudState != Idle")
}
```

`mmss(seconds: Int): String` lives in `RecordHudFormatters.kt` (R1) — reused.

`LoopPill` is rendered only when `loopTotal > 1`. Renders `"$loopIndex/$loopTotal loops done"` (clamped: `loopIndex.coerceIn(0, loopTotal)`). Single-clip sessions (`loopTotal == 1` or `loopTotal == -1` for indefinite) **show the loop pill differently** for indefinite: `"$loopIndex / ∞ loops done"`. Final wording per the mockup's `4/10 loops done` — for indefinite, **omit the slash and the total**, render `"$loopIndex loops done"`. Helper:

```kotlin
internal fun loopPillContent(loopIndex: Int, loopTotal: Int): String? = when {
    loopTotal == 1 -> null                          // single-clip — hide pill
    loopTotal < 0  -> "${loopIndex.coerceAtLeast(0)} loops done"   // indefinite
    else           -> "${loopIndex.coerceIn(0, loopTotal)}/$loopTotal loops done"
}
```

Visual: glass-on-camera tokens, same `GlassFill` / `GlassStroke` constants `RecordChrome.kt` already exports. Rounded pill (≥ 16 dp radius). Compact typography (label-medium for the loop-pill, body-medium for the status-pill main + a slightly muted body-medium for the time text).

Dot colors — file-local `val`s in `RecordChrome.kt`:
- `RecordingDotColor = Color(0xFFEF4444)` (red)
- `WaitingDotColor = Color(0xFFFBBF24)` (amber — matches the existing soft-sheet accent)
- `MergingDotColor = Color(0xFF60A5FA)` (blue)

If theme tokens for these dots are added to `RovaTokens.kt` later (out of scope), the constants migrate; for R2 they are file-local.

### 3.2 `WarningId` — 17-row order

```kotlin
enum class WarningId(val tier: WarningTier, val gatesStart: Boolean = false) {
    CAMERA_PERMISSION_DENIED(WarningTier.HARD_BLOCK, gatesStart = true),   // #1
    EXACT_ALARM_DENIED(WarningTier.HARD_BLOCK),                            // #2
    STORAGE_INSUFFICIENT(WarningTier.HARD_BLOCK, gatesStart = true),       // #3
    THERMAL_SHUTDOWN(WarningTier.CRITICAL),                                // #4
    THERMAL_EMERGENCY(WarningTier.CRITICAL),                               // #5
    THERMAL_CRITICAL(WarningTier.CRITICAL),                                // #6
    BATTERY_CRITICAL(WarningTier.CRITICAL),                                // #7
    CAMERA_IN_USE(WarningTier.CRITICAL),                                   // #8
    CAMERA_DISABLED(WarningTier.CRITICAL),                                 // #9
    BATTERY_LOW(WarningTier.ADVISORY),                                     // #10
    STORAGE_LOW_MID_REC(WarningTier.ADVISORY),                             // #11  ← NEW
    THERMAL_SEVERE(WarningTier.ADVISORY),                                  // #12 (was #11)
    MICROPHONE_DENIED(WarningTier.ADVISORY),                               // #13 (was #12)
    BATTERY_OPTIMIZATION_ON(WarningTier.ADVISORY),                         // #14 (was #13)
    POWER_SAVE_MODE(WarningTier.ADVISORY),                                 // #15 (was #14)
    THERMAL_MODERATE(WarningTier.ADVISORY),                                // #16 (was #15)
    NOTIFICATIONS_DENIED(WarningTier.ADVISORY),                            // #17 (was #16)
}
```

`STORAGE_LOW_MID_REC` has `gatesStart = false` (default). It cannot fire at idle by construction (the signal only polls while recording), so a "Start gating" question is moot — but documenting `gatesStart = false` keeps the orthogonality invariant from R1 clean.

### 3.3 `WarningPrecedence.resolve(...)` — 9 → 10 params

```kotlin
internal object WarningPrecedence {
    fun resolve(
        cameraPermissionGranted: Boolean,
        exactAlarmGranted: Boolean,
        storageInsufficient: Boolean,
        thermal: ThermalStatus,
        power: PowerState,
        camera: CameraSignalState,
        microphonePermissionGranted: Boolean,
        notificationsGranted: Boolean,
        batteryOptimizationExempt: Boolean,
        storageLowMidRec: Boolean,                  // ← NEW (last param)
    ): WarningId? { ... }
}
```

The walk gains one branch between `BATTERY_LOW` (#10) and `THERMAL_SEVERE` (now #12):

```kotlin
if (pct != null && pct < 15 && !power.charging) return WarningId.BATTERY_LOW    // #10
if (storageLowMidRec) return WarningId.STORAGE_LOW_MID_REC                      // #11  ← NEW
if (thermal == ThermalStatus.SEVERE) return WarningId.THERMAL_SEVERE             // #12
```

The new param is appended at the end (not slotted to match enum order) to keep the call-site diff at `WarningCenterViewModel.aggregate(...)` minimal and to avoid renumbering call sites in the test file — pure positional addition.

### 3.4 `WarningCenterViewModel.aggregate(...)` — 9 → 10 source flows

R1 folded six booleans into a private `Bools5`-style class (actually 5; verified via code read) and called `combine(bools5, batteryOpt, thermal, power, cameraState)`. R2 extends the bool-pack:

```kotlin
private class Bools6(
    val cameraPerm: Boolean,
    val exactAlarm: Boolean,
    val storageInsufficient: Boolean,
    val microphone: Boolean,
    val notifications: Boolean,
    val storageLowMidRec: Boolean,         // ← NEW
)
```

The `combine(bools6, battery, thermal, power, cameraState)` shape stays 5-arg into Kotlin's typed `combine`. `aggregate(...)` factory gains one `StateFlow<Boolean>` parameter for `storageLowMidRec` (placed at the end of the parameter list, same convention as `resolve`). The R1 9-flow factory wiring in `WarningCenter()` (the file-local `viewModelFactory { initializer { WarningCenterViewModel(...) } }`) gains a 10th `app.storageLowMidRecSignal.isLow` line.

### 3.5 `WarningCenter(hudState: RecordHudState)` — one new param, new render path

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarningCenter(
    hudState: RecordHudState,
    onStopRecording: () -> Unit,             // wired through from RecordScreen — for the banner "Stop" CTA
    modifier: Modifier = Modifier,
) {
    // ... existing factory / activeWarning collect ...

    val surface = warningSurfaceFor(id)

    if (hudState is RecordHudState.Idle) {
        // Existing sheet/chip path — unchanged from R1, except the early-return for TopBanner stays
        if (surface == WarningSurface.TopBanner) return
        // ... R1 sheet/chip block verbatim ...
    } else {
        // Active states — render TopBanner only; sheets/chips suppressed mid-rec
        if (surface != WarningSurface.TopBanner) return
        WarningTopBanner(
            content = midRecBannerContent(id),
            onAction = onStopRecording,
            modifier = modifier,
        )
    }
}
```

**Important.** The `onStopRecording` parameter is wired by RecordScreen — it routes through the same `stopRecording` callback the bottom-nav Stop-FAB uses. No service-layer change.

### 3.6 `WarningTopBanner` + `midRecBannerContent` — new in `WarningCenter.kt`

```kotlin
internal data class TopBannerContent(
    val icon: ImageVector,
    val title: String,
    val sub: String,
    val cta: String,            // "Stop" for all ten ids in R2
)

internal fun midRecBannerContent(id: WarningId): TopBannerContent = when (id) {
    // ... one arm per of the ten TopBanner-mapped WarningIds ...
}

@Composable
private fun WarningTopBanner(
    content: TopBannerContent,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) { /* amber rounded glass surface, top-safe-area inset, icon + title + sub + CTA Row */ }
```

Visual is `mockups/new_uiux/07-warnings.html` row 6 — amber non-blocking banner: rounded ~16 dp glass surface, top inset to safe-area, leading icon (14 dp triangle with `!`), two-line text block (`bn-title` semibold + `bn-sub` muted), trailing CTA pill (`bncta-y` — amber-on-amber pill saying "Stop"). All TopBanner copy uses the existing sheet-content icons where applicable (`Icons.Default.Thermostat`, `BatteryAlert`, `VideocamOff`, `Storage`) to avoid icon duplication.

Banner content table (per-id):

| WarningId | Icon | Title | Sub | CTA |
|---|---|---|---|---|
| `THERMAL_SHUTDOWN` | Thermostat | "Device overheating — stopping" | "Recording will stop automatically." | "Stop" |
| `THERMAL_EMERGENCY` | Thermostat | "Device critically hot" | "Stop now to let it cool." | "Stop" |
| `THERMAL_CRITICAL` | Thermostat | "Device very hot" | "Recording may auto-stop soon." | "Stop" |
| `THERMAL_SEVERE` | Thermostat | "Device hot" | "Quality may drop." | "Stop" |
| `THERMAL_MODERATE` | Thermostat | "Device warming up" | "Watch the temperature." | "Stop" |
| `BATTERY_CRITICAL` | BatteryAlert | "Battery critical" | "Recording may stop soon." | "Stop" |
| `BATTERY_LOW` | BatteryAlert | "Battery low" | "Consider charging." | "Stop" |
| `CAMERA_IN_USE` | VideocamOff | "Camera in use" | "Another app is using the camera." | "Stop" |
| `CAMERA_DISABLED` | VideocamOff | "Camera disabled" | "Disabled by device policy." | "Stop" |
| `STORAGE_LOW_MID_REC` | Storage | "Storage running low" | "Free space on this device." | "Stop" |

Copy is the spec's call. The "~14 min remaining" framing from mockup row 6 is **not** rendered in R2 (would require a clip-rate × free-bytes calc on the UI side); the simpler "Free space on this device" copy ships and the dynamic estimate is deferred.

### 3.7 `StorageLowMidRecSignal` — new in `ui/signals/`

```kotlin
class StorageLowMidRecSignal(
    private val computeIsLow: (durationSeconds: Int, resolution: String) -> Boolean,
) {
    private val _isLow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLow: StateFlow<Boolean> = _isLow.asStateFlow()

    /** Re-run the estimate for the current clip settings. Idempotent. */
    fun poll(durationSeconds: Int, resolution: String) {
        _isLow.value = computeIsLow(durationSeconds, resolution)
    }

    /** Clear when leaving active states. */
    fun clear() { _isLow.value = false }

    companion object {
        const val LOW_THRESHOLD_CLIPS: Int = 3

        fun forContext(context: Context): StorageLowMidRecSignal {
            val app = context.applicationContext
            return StorageLowMidRecSignal(computeIsLow = { dur, res ->
                estimateIsLow(app, dur, res)
            })
        }

        private fun estimateIsLow(app: Context, durationSeconds: Int, resolution: String): Boolean {
            return try {
                val rovaApp = app as? RovaApp ?: return false
                val path = rovaApp.externalRoot?.absolutePath ?: return false
                val stat = StatFs(path)
                val available = stat.availableBlocksLong * stat.blockSizeLong
                val bytesPerClip =
                    StorageEstimator.bytesPerSecondForResolution(resolution) * durationSeconds
                available < LOW_THRESHOLD_CLIPS * bytesPerClip
            } catch (_: Exception) {
                false
            }
        }
    }
}
```

**RovaApp wiring** — `storageLowMidRecSignal` property exposed alongside the other signals; instantiated in `RovaApp.onCreate()` via `StorageLowMidRecSignal.forContext(this)`. No `service/**` touch.

**RecordScreen wiring** — one new `LaunchedEffect(hudState, viewModel.duration.value, viewModel.resolution.value)`:

```kotlin
LaunchedEffect(hudState, duration, resolution) {
    if (hudState is RecordHudState.Idle) {
        rovaApp?.storageLowMidRecSignal?.clear()
        return@LaunchedEffect
    }
    while (true) {
        rovaApp?.storageLowMidRecSignal?.poll(duration, resolution)
        delay(30_000L)
    }
}
```

The `while-delay` pattern auto-cancels when the `LaunchedEffect` key changes (state→Idle or settings change). `clear()` on Idle transitions resets the flow so a fresh-recording session starts with `false`.

---

## §4 — RecordScreen integration + retired components

### 4.1 Per-state mount table

| Slot | Idle (R1) | Recording / Waiting / Merging (R2) |
|---|---|---|
| Top safe-area | `RecordTopOverlay` (recovery chip + WarningChip mount) | `Column { WarningCenter(hudState = active) ; RecordActiveHud(...) }` |
| Top-right (cam controls) | `RecordCameraControls` (flash/flip) | (hidden) |
| Center | (camera preview only) | (camera preview only) |
| Center overlay | (none) | `MergeCompleteCard` after merge completes (existing R1 path); `CameraDisconnectAlertDialog` (existing R1 path) |
| Bottom | `RecordSettingsCard` + `RecordBottomNav(FabState.Start)` | `RecordBottomNav(FabState.Stop or Disabled)` — settings card hidden |
| Sheets | `SessionSettingsSheet` (when open); `WarningCenter` sheets/chips | (none — banner replaces sheets/chips mid-rec) |

The dual-mount pattern from the brainstorm draft collapses to a single `WarningCenter(hudState)` call inside the per-state branch of the existing RecordScreen layout. Two physical mount points are unnecessary — the composable itself branches on `hudState`.

### 4.2 RecordScreen call-site delta (illustrative — final shape per implementer)

```kotlin
Box(Modifier.fillMaxSize()) {
    CameraPreview(...)
    LoadingOverlay(visible = !hasCapturePermissions || !cameraReady)

    when (hudState) {
        RecordHudState.Idle -> {
            RecordTopOverlay(...)                  // R1 — recovery-chip + warning-chip slot
            // Idle-side WarningCenter mount — renders sheet/chip; TopBanner ids no-op (existing R1 behaviour)
            WarningCenter(
                hudState = RecordHudState.Idle,
                onStopRecording = {},              // unused on idle path
                modifier = Modifier.align(Alignment.TopCenter),
            )
            RecordCameraControls(...)              // R1 — flash/flip
            RecordSettingsCard(...)                // R1
        }
        RecordHudState.Recording, RecordHudState.Waiting, is RecordHudState.Merging -> {
            Column(Modifier.align(Alignment.TopCenter)) {
                // Active-side WarningCenter mount — renders TopBanner; sheet/chip ids no-op
                WarningCenter(
                    hudState = hudState,
                    onStopRecording = { viewModel.stopRecording() },
                )
                RecordActiveHud(
                    state = hudState,
                    loopIndex = currentLoopIndex,
                    loopTotal = loopTotalForSession,
                    clipSecondsLeft = displayedCountdownSeconds,
                    waitSecondsLeft = displayedWaitSeconds,
                )
            }
        }
    }

    RecordBottomNav(fabState = recordFabState(...))  // R1 — visual branches on Stop vs. Disabled
    if (hudState is RecordHudState.Merging.GraceWindow) MergeCompleteCard(...)   // R1
    CameraDisconnectAlertDialog(...)                                              // R1
}

if (settingsSheetOpen) SessionSettingsSheet(...)   // R1
```

**Note.** Two `WarningCenter` mount points coexist in the tree; the outer `when (hudState)` ensures only one branch composes at any time, so only one mount actually runs. The composable's internal hud-state guard then routes within that single live mount: idle renders sheet/chip (or no-op for TopBanner ids), active renders TopBanner (or no-op for sheet/chip ids). One VM, one active warning — but two visually-aligned slot positions.

### 4.3 Retired components

| File | Action |
|---|---|
| `app/src/main/java/com/aritr/rova/ui/components/SessionTimer.kt` | DELETE |
| `app/src/main/java/com/aritr/rova/ui/components/ClipProgressBand.kt` | DELETE |
| `app/src/main/java/com/aritr/rova/ui/components/WaitingCountdown.kt` | DELETE |
| `app/src/main/java/com/aritr/rova/ui/components/MergingProgressBand.kt` | DELETE |
| `app/src/main/java/com/aritr/rova/ui/components/RecordStatusStrip.kt` | DELETE |
| `app/src/test/.../components/<above>Test.kt` | DELETE (whichever exist — count during plan task) |

Verification step before the final whole-branch review:

```sh
grep -r "SessionTimer\|ClipProgressBand\|WaitingCountdown\|MergingProgressBand\|RecordStatusStrip" \
    app/src/main app/src/test app/src/androidTest
```

→ must return zero hits.

### 4.4 Untouched (audit, do NOT modify)

- `RecordHudState.kt` — fields/companion are sufficient as-is.
- `RecordHudFormatters.kt` — `mmss` and related helpers — reused.
- `MergeCompleteCard.kt` — post-merge success card — R1-intact.
- `BackgroundRecordingBanner.kt`, `RovaCardComponents.kt`, `RovaComponents.kt`, `RovaDialogs.kt`, `RovaAnimations.kt` — orthogonal.
- All R1 idle composables in `RecordChrome.kt` (`RecordTopOverlay`, `RecordCameraControls`, `RecordSettingsCard`, `RecordBottomNav`, `RecordRecoveryChip`, `RecordFabState`, `RecordChromeMetrics`) — untouched.
- The R1 "preserve verbatim" list in `RecordScreen.kt` (`onStart` / `StartResult.Blocked`, `stopRecording`, `produceState` timers + local-tick, warm-up / loading overlays + `hasCapturePermissions`, the 3 `DisposableEffect`s, `rememberMultiplePermissionsState`, the LaunchedEffects for permission / storage refresh, `startBlocked`, `RecordHudState.from`, merge-grace LaunchedEffect, `editingField` `when` router + the 4 `*EditSheets`, recovery-echo filter, `interruptedSessionCount produceState`) — untouched.
- All `service/**` and `data/**` files.
- `app/build.gradle.kts`.

---

## §5 — Tests, gates, on-device smoke

### 5.1 Unit-test delta (R1 baseline = 735 tests / 60 classes)

| Test class | Status | Methods (approx) | Coverage |
|---|---|---|---|
| `RecordActiveHudFormattersTest` | NEW (`app/src/test/.../ui/screens/`) | 7 | `hudStatusPillContent` × {Recording, Waiting, Merging} dot/main/time; `loopPillContent` × {single-clip→null, indefinite→"N loops done", finite→"N/M loops done", clamp negative→0, clamp over→loopTotal}. |
| `MidRecBannerContentTest` | NEW (`app/src/test/.../ui/warnings/`) | 10 | `midRecBannerContent(id)` × each of the 10 TopBanner-mapped ids (icon non-null + title/sub/cta non-blank + cta == "Stop"). |
| `StorageLowMidRecSignalTest` | NEW (`app/src/test/.../ui/signals/`) | 6 | `freeBytes > 3×bytesPerClip → false`; `freeBytes < 3×bytesPerClip → true`; quality change recomputes; resolution-string canonicalisation matches `StorageEstimator`; null root → false; throw → false; `clear()` resets to false. |
| `WarningSurfaceTest` | +1 (existing) | +1 | `STORAGE_LOW_MID_REC → TopBanner`. (The function is hud-state-agnostic — no other arm changes.) |
| `WarningSheetContentTest` | +1 | +1 | `warningSheetContent(id)` is exhaustive `when` over `WarningId` (no `else`) — adding the row is a compile-time obligation, the new arm returns a non-blank placeholder `WarningSheetContent` with a `// unreachable — TopBanner-only` comment, and a single test asserts that the new arm doesn't crash + has a non-blank title (defensive — never rendered as a sheet, but kept callable). |
| `WarningIdOrderTest` | +1 / mod | +1 (17-row pin) | Pins the new 17-row order; existing 16 ordinals unchanged for the 16 carry-over rows, `STORAGE_LOW_MID_REC.ordinal == 10` (between BATTERY_LOW and THERMAL_SEVERE). |
| `WarningPrecedenceTest` | +6 (existing) | +6 | `storageLowMidRec=true` alone → returns `STORAGE_LOW_MID_REC`; outranks mic / opt / power-save / notifs / thermal-severe / thermal-moderate; ranks below BATTERY_LOW; ranks below all thermal-critical+ / battery-critical / camera-busy; the new last-param position walks correctly with the full-flag matrix; param-positional regression (verifies adding the param at end doesn't shuffle any existing arm). |
| `WarningCenterAggregateTest` | +2 (existing) | +2 | `Bools6` shape; flow combine emits `STORAGE_LOW_MID_REC` when `storageLowMidRec` flips with no higher-priority signals; cleared when the new signal goes back to `false`. |
| `RecordFabStateTest` | 0 | 0 | `recordFabState(...)` mapping unchanged — D9's enabled / disabled mapping = R1 behaviour. |
| `SessionSettingsCardFormattersTest`, `WarningSheetContentTest`, all other R1 tests | 0 | 0 | Untouched. |
| `SessionTimerTest`, `ClipProgressBandTest`, `WaitingCountdownTest`, `MergingProgressBandTest`, `RecordStatusStripTest` | DELETE (whichever exist) | − | Retired with the components. |

**Predicted method delta:** +7 + 10 + 6 + 1 + 1 + 1 + 6 + 2 = **+34 new test methods**; **+3 new test classes**; minus retired test methods/classes (count locked once `Glob app/src/test/**/components/*Test.kt` confirms which of the five retired files have tests).

**Predicted final total:** approximately `735 − R + 34` tests, `60 − R_c + 3` classes, where `R` and `R_c` are retired methods/classes. The plan's Task 1 measures `R` and `R_c` and writes the exact predicted total into the spec gate line before any implementation commits — same convention as R1.

### 5.2 Build / test / lint gates

Run after every per-task commit and before the final whole-branch review. Same order R1 used.

```sh
:app:clean
:app:assembleDebug                              # BUILD SUCCESSFUL — no "Failed to resolve"
:app:testDebugUnitTest                          # exact predicted total, 0 failures, 0 errors, 0 skipped
:app:lintDebug                                  # ≤ 53, 0 Error, no lint-baseline.xml
```

**Lint hard target:** ≤ 53 findings, 0 Error. R2 should reduce findings (retired components were `BackgroundRecordingBanner`-adjacent stylistic flags) — net effect predicted to be ≤ 53. Any new finding above 53 fails the task; any new Error fails the task.

**XML parsing convention:** awk-walk `app/build/test-results/testDebugUnitTest/*.xml` for `tests="N"`, `failures="N"`, `errors="N"`, `skipped="N"`; awk-walk `app/build/reports/lint-results-debug.xml` for `^        id="..."` lines bucketed by severity. (R1 cleanup-pass precedent.)

### 5.3 Owner on-device smoke (DEFERRED-to-owner — no emulator in the build env)

Checklist (pasted into the PR's first comment when the branch opens, the way R1 §4 was):

1. **Happy path — 4 × 30 s / 2 m wait session.** Loop-pill counts 0/4 → 4/4 over the session; status-pill cycles Recording (`· N:NN left`) ↔ On break (`· next in N:NN`); after the last clip, status-pill goes Merging (`· N%`) and the Stop-FAB becomes Disabled; on completion `MergeCompleteCard` appears.
2. **Single-clip session (`Repeats = 1`).** Loop-pill is hidden across all three active states.
3. **Indefinite session (`Repeats = Until you stop`).** Loop-pill shows `"N loops done"` (no slash / no total).
4. **Thermal banner.** Trigger thermal (sauna pocket / video stress) during recording; verify amber top-banner appears, recording continues, banner clears when thermal recovers; thermal-severity escalation behaves (SEVERE → CRITICAL renders the correct copy).
5. **Storage-low banner.** Start a session with disk near full (~200 MB free, 1080p, 5-clip plan); verify the banner fires within ~30 s; tap "Stop" → recording stops cleanly; verify the banner clears once a clip's worth of bytes is freed.
6. **Camera-busy banner.** Open the system camera app mid-record; verify `CAMERA_IN_USE` banner; recording auto-stops (existing service behaviour).
7. **Camera-permission revoked mid-record.** Open App Settings, revoke camera; verify `CAMERA_DISABLED` banner; the camera-disconnect AlertDialog still fires (R1 behaviour).
8. **Banner z-order with status pill.** Force two banners (e.g., thermal + storage-low) — only the highest-priority is shown; loop+status pill row sits below it; both readable.
9. **Stop-FAB during merge.** Verify the FAB is visually Disabled and tapping it is a no-op.
10. **Font scale 100 → 200 %.** Pills + banner + FAB readable, no text clipping, no overlap with bottom-nav.
11. **Predictive back (API 34+).** Back gesture from each active state does not crash, does not stop recording.
12. **Light / dark theme.** Chrome is glass-on-camera so visually the same; eyeball the dot colors + banner amber for legibility in both modes.

### 5.4 Code review pass

- After every task: implementer subagent runs gates, self-reviews, posts a per-task summary; spec-compliance reviewer subagent confirms spec match; code-quality reviewer subagent posts findings; implementer fixes; loops until both reviewers ✅. (R1 precedent.)
- After all tasks: a final whole-branch review (opus subagent) per the R1 pattern.
- PR opens with the done-report comment containing per-task commit list + final gate numbers + on-device smoke checklist (DEFERRED-to-owner). **Do not squash-merge; owner merges.**

---

## §6 — Doc amendments

| Doc | Edit |
|---|---|
| `docs/adr/0007-record-warning-sheets.md` | Append a new top-level section `## Amendment 2026-05-13 — R2: mid-rec top-banner live, +1 row (STORAGE_LOW_MID_REC)`. Body: (a) the deferred-to-R2 top-banner render path lands in this slice; (b) one row added to the 16-row precedence model → now 17, placed at ordinal #11 between BATTERY_LOW and THERMAL_SEVERE (rationale: § 3.2 of the R2 spec); (c) the supersedes clause for `NEW_UI_BACKEND_REPLAN` Phase-4 + `docs/WarningCenterContract.md` §C3.1 carries forward unchanged. |
| `docs/WarningCenterContract.md` | The Phase-1.D revision block's 16-row table → 17-row table. §7 producer notes gain one line for `StorageLowMidRecSignal`. |
| `NEW_UI_BACKEND_REPLAN.md` | §2.1 + the row-count refs in §6 update from 16 to 17 rows. New pointer line: `R2 spec: docs/superpowers/specs/2026-05-13-record-home-redesign-r2-design.md`. |
| `docs/UI_NAV_GRAPH.md` | No change. |
| `docs/architecture.md` | File-tree: add `WarningTopBanner` + `RecordActiveHud` (inside the existing `WarningCenter.kt` / `RecordChrome.kt` entries — note the additional internal symbols) + new file `StorageLowMidRecSignal.kt`. Remove the five retired components. |

---

## §7 — Out of scope (explicit no-go for R2)

- Snooze / dismiss model + hysteresis on the top banner (future 4.1c bundle).
- Service-side cancel for in-progress merges (Stop during Merging stays Disabled).
- Portrait / Landscape "Mode" picker (Phase 6).
- Mid-recording warning routing to History / Settings / Onboarding chrome (old 4.2 — orthogonal).
- Recovery "Merge what was recorded" via `RovaController.recoverAndMerge` (old 4.3 — orthogonal).
- Any `service/**` or `data/**` diff (`StorageEstimator.bytesPerSecondForResolution` is READ from `data/`, not modified).
- Any `app/build.gradle.kts` diff.
- Camera-disconnect timer bug RCA (standalone, owner-tracked).
- Dynamic "~N min remaining" estimate in the storage-low banner (deferred — the static "Free space on this device" sub copy ships).
- Theme-token migration for the dot colors (`RecordingDotColor` / `WaitingDotColor` / `MergingDotColor` stay file-local — `RovaTokens.kt` migration is later if the design system grows).

---

## §8 — References

- R1 spec: `docs/superpowers/specs/2026-05-12-record-home-redesign-r1-design.md`
- R1 plan: `docs/superpowers/plans/2026-05-12-record-home-redesign-r1.md`
- ADR 0007: `docs/adr/0007-record-warning-sheets.md`
- `docs/WarningCenterContract.md`
- `NEW_UI_BACKEND_REPLAN.md`
- Mockups: `mockups/new_uiux/01-record-home.html`, `mockups/new_uiux/07-warnings.html`
- Code anchors: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`, `RecordChrome.kt`, `SessionSettingsSheet.kt`; `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`, `WarningId.kt`, `WarningPrecedence.kt`, `WarningCenterViewModel.kt`; `app/src/main/java/com/aritr/rova/ui/signals/StorageSignal.kt` (template for the new signal); `app/src/main/java/com/aritr/rova/data/StorageEstimator.kt` (consumed read-only); `app/src/main/java/com/aritr/rova/ui/components/RecordHudState.kt`, `RecordHudFormatters.kt`, `MergeCompleteCard.kt` (preserved); the five retired components in `ui/components/`.
- Master baseline at branch-cut: `07fec76` (R1 squash). Expected gate counts: **735 tests / 60 classes / 0-0-0; lint 53 (50 W + 3 H, 0 E)**.
