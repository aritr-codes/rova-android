# Phase 4.3 — Recovery Merge + C2.4 "Can't Merge Yet" (Design)

**Date:** 2026-05-25
**Status:** Approved (pending implementation plan)
**Phase parent:** Phase 4 warning surface (ADR-0007, ADR-0013); Phase 1.5 recovery (ADR-0005, ADR-0006)
**Mockup:** `mockups/new_uiux/07c-warnings.html` §2.4 (Can't-merge 3-way sheet) + §4.1/4.2/4.4 (Recovery cards — extends shipped chrome)
**Brainstorm companion (gitignored, local only):** `mockups/new_uiux/phase4.3-brainstorm.html`
**Related ADRs:** ADR-0003 (Tier1 FD mode), ADR-0006 (atomic terminal-write), ADR-0007 (record warning sheets), ADR-0013 (warning re-skin v3 chrome canon), ADR-0015 (storage-full echo — sibling failure-routing pattern), ADR-0016 (thermal autostop — sibling auto-stop pattern)
**Related slices:** Phase 4 Slice 1 (PR #43 — re-skin), Slice 2 (PR #46 — storage-full echo), Slice 3 (PR #47 — thermal autostop), PR #44 (Library recovery card re-skin)
**Related contract sections:** `docs/WarningCenterContract.md` §4.2 C2.4 (Can't merge yet — Phase 4.3 owned), §4.4 C4.4 (Merge failed — Phase 4 UI mapper)
**Memory pointers:** `[[project-current-state]]` parked-backlog "Phase 4.3 — recovery 'Merge what was recorded' via RovaController.recoverAndMerge"; `[[project-phase4-warnings-v3-stack]]`

---

## 1. Problem

Three coupled gaps in the recovery surface today:

1. **No merge action.** `RecoveryCard.kt:161-167` documents the constraint verbatim: *"The existing signature only exposes `onDiscard`; there is no merge / fix-background callback, so the v3 'primary + secondary' two-button row collapses to a single full-width destructive button."* Beta history confirms (`RecoveryUiState.kt:21-24`): an earlier prototype carried placeholder Merge / Discard affordances; the Merge label was **removed** for beta *"because no service-side merge API exists yet"*. Phase 4.3 builds that API.

2. **No "Can't merge yet" surface.** `WarningCenterContract.md` §4.2 C2.4 (`Can't merge yet (3-way)`) is unimplemented (`status=partial`, owner=Phase 4.3). 07c §2.4 shows the design: a 3-way sheet (Free space & retry / Save segments only / Discard session) for the case where recovery merge **can't run** because storage is insufficient.

3. **No "keep as raw clips" path.** `Terminated` enum has 4 values (`USER_STOPPED`, `COMPLETED`, `KILLED_BY_SYSTEM`, `KILLED_FORCE_STOP`); none represent *"user kept the recovered segments as N separate files instead of one merged file"*. Without this terminal, the only way to clear the recovery card after Discard-or-Merge is Discard.

Two contract corrections sit alongside:

- **Wrong host name in contract.** `WarningCenterContract.md` §4.2 C2.4 prescribes `RovaController.recoverAndMerge(sessionId)`. But `RovaController` is the per-live-session in-process interface (`postTick` + `requestStop`, see `RovaController.kt:43-57`); a terminated session has no live `RovaController`. The contract row is architecturally wrong.

- **Single-entry rule on `VideoMerger`.** `VideoMerger.kt:28-30` enforces (via static check `checkExportPipelineSingleEntry`) that callers outside `service/export/` cannot invoke the merge primitives. Recovery merge must therefore route through `ExportPipeline`, not call `VideoMerger` directly. Code comment at `ExportPipeline.kt:139-140` confirms the design intent: the Tier1Exporter `insertPendingRow` seam *"takes a sessionId for symmetry with the recovery path"* — recovery was anticipated, never wired.

Source pointers:
- `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt:161-167` (single-CTA constraint).
- `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryUiState.kt:21-24, 56-64` (beta-history removal of Merge; `RecoveryCardState` shape).
- `app/src/main/java/com/aritr/rova/utils/VideoMerger.kt:28-30` (single-entry rule).
- `app/src/main/java/com/aritr/rova/service/export/ExportPipeline.kt:55-125` (`export()` entry).
- `app/src/main/java/com/aritr/rova/service/RovaController.kt:43-57` (interface scope = live session only).
- `app/src/main/java/com/aritr/rova/data/SessionManifest.kt:313-318` (`Terminated` enum).
- `app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt:25-48` (19-id enum, precedence-ordered).

## 2. Goal

Close all three gaps + apply both contract corrections in a single slice that reuses **shipped v3 chrome** end-to-end (per "hand-in-hand with current UI" constraint):

| Layer | Behavior |
|---|---|
| Library Recovery card | Three CTAs render (per recovery card kind): primary `Merge segments`, secondary `Keep as raw clips`, destructive-link `Discard recording`. Existing chrome (glow bloom, severity tag, clip-progress strip, ADR-0013 tokens) preserved byte-for-byte — only the CTA row changes. |
| `Merge segments` tap | Starts `RovaRecordingService` with `ACTION_RECOVER_MERGE` extra `sessionId`. Service hosts the merge under its existing FGS notification. Progress emits to RecoveryCard's clip-progress strip (fills cell-by-cell). |
| Pre-flight: storage insufficient | `ExportPipeline.exportRecovered(sessionId)` runs a storage pre-flight before `MediaMuxer` open; if insufficient, emits `WarningId.CANT_MERGE` via a new `RecoveryMergeOutcomeSignal`. Sheet auto-presents on next Record screen idle. **No mux attempt is made** — eager failure, per Q5A. |
| C2.4 sheet (CANT_MERGE) | Renders via existing `WarningSheetV3` chrome with severity `ESCALATING`. Primary `Free space & retry` → opens storage settings then restores recovery card with a `Retry merge` button. Secondary `Save segments only` → triggers the same `MULTI_SEGMENT_KEPT` path as `Keep as raw clips`. Destructive-link `Discard session` → existing `discardSession` flow. |
| `Keep as raw clips` tap (and "Save segments only" from C2.4 sheet) | Calls `SessionStore.markTerminated(sessionId, Terminated.MULTI_SEGMENT_KEPT, StopReason.NONE)`. Session re-classifies on next scan; Library renders one row per segment instead of a single merged entry. Recovery card disappears. |
| Mux failure mid-merge | Existing recovery card surfaces as `Merge failed` variant (07c §4.4). User can retry from the card. |
| `Discard recording` tap | No change — existing `RecoveryViewModel.dismiss` → `SessionStore.discardSession` path. |

## 3. Non-goals

- **No new visual chrome.** All UI changes reuse `WarningSheetV3`, `WarningTopBannerV3`, the v3 `RovaWarningsV3` tokens, and the shipped `RecoveryCard` composable body. Only signatures / CTA rows extend. The "hand-in-hand with current UI" constraint forbids inventing chrome variants.
- **No `WorkManager` dependency.** Q2A locked: reuse `RovaRecordingService` FGS lifecycle via start-with-action. (Q2B/Q2C explored in brainstorm; rejected for lifecycle reuse + sticky-notification reuse.)
- **No `RecoveryMergeController` class.** Q2A makes the service the host; the contract correction (§8) renames the symbol referenced in `WarningCenterContract.md` §C2.4 to the actual entry — a service intent-action constant + `companion object` helper `RovaRecordingService.startRecoveryMerge(context, sessionId)` — not a new class.
- **No mid-session "Can't merge" trigger.** C2.4 only fires post-recovery-attempt (eager pre-flight). Mid-active-recording storage shortages stay routed to existing `STORAGE_FULL_AUTOSTOPPED` echo (Slice 2).
- **No retry-queue / backoff.** A failed merge requires user tap on the Retry CTA. No automatic retry. (Backoff would be a separate slice.)
- **No public-gallery insertion for `MULTI_SEGMENT_KEPT`.** Per Q6A: re-classify to `MULTI_SEGMENT_KEPT`, segments stay in the session dir, Library renders them as individual rows. No `MediaStore` inserts (Q6B variant) — keeps the slice contained.
- **No copy-polish to other warning surfaces.** Phase 4.2 was audited and skipped; this slice does not retrofit copy on unrelated ids.
- **No new ADR for the warning surface.** ADR-0013 chrome canon already covers C2.4's sheet rendering. A new ADR (ADR-0017 — see §9) documents the **recovery merge architecture**, which is a fresh contract.
- **No `Phase 1.7` interaction beyond what `ExportPipeline.export` already handles.** Recovery merge reuses the same tier dispatch; Phase 1.7's pending-row visibility guard remains the canonical pending-row owner.

## 4. Architecture

### 4.1 New `Terminated` value

`app/src/main/java/com/aritr/rova/data/SessionManifest.kt` — add one enum value:

```kotlin
enum class Terminated {
    USER_STOPPED,
    COMPLETED,
    KILLED_BY_SYSTEM,
    KILLED_FORCE_STOP,
    MULTI_SEGMENT_KEPT,   // ← new (Phase 4.3)
}
```

`MULTI_SEGMENT_KEPT` semantics:
- User chose to keep recovered segments as N separate files instead of one merged.
- Atomically written by `SessionStore.markTerminated(sessionId, MULTI_SEGMENT_KEPT, StopReason.NONE)` from the `Keep as raw clips` CTA + the C2.4 sheet's `Save segments only` CTA.
- Recovery card mapper (`RecoveryUiStateMapper.isEligible`) treats this terminal the same as `COMPLETED` — no card emitted.
- History list mapper (separate slice's concern — Library row construction) treats `MULTI_SEGMENT_KEPT` as a flag to enumerate the segment files directly (one row per file) instead of looking for a merged artifact.

Compile-time impact: exhaustive `when (terminated)` sites must add an arm. Affected sites by grep:
- `RecoveryUiState.kt:161-166` (`kind` mapping) — new arm returns `null` (card hidden).
- `RecoveryUiState.kt:185-198` (`bodyFor`) — not reached if hidden.
- Any callers of `markTerminated` that switch on the new value (zero today).

### 4.2 New `WarningId.CANT_MERGE`

`app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt` — insert one row after `THERMAL_AUTOSTOPPED` (#13), pushing rows #14..#19 down by one:

```kotlin
enum class WarningId(val tier: WarningTier, val gatesStart: Boolean = false) {
    // Hard block ...
    CAMERA_PERMISSION_DENIED(WarningTier.HARD_BLOCK, gatesStart = true),   // #1
    EXACT_ALARM_DENIED(WarningTier.HARD_BLOCK),                            // #2
    STORAGE_INSUFFICIENT(WarningTier.HARD_BLOCK, gatesStart = true),       // #3
    // Critical ...
    THERMAL_SHUTDOWN(WarningTier.CRITICAL),             // #4
    THERMAL_EMERGENCY(WarningTier.CRITICAL),            // #5
    THERMAL_CRITICAL(WarningTier.CRITICAL),             // #6
    BATTERY_CRITICAL(WarningTier.CRITICAL),             // #7
    CAMERA_IN_USE(WarningTier.CRITICAL),                // #8
    CAMERA_DISABLED(WarningTier.CRITICAL),              // #9
    // Advisory ...
    BATTERY_LOW(WarningTier.ADVISORY),                  // #10
    STORAGE_LOW_MID_REC(WarningTier.ADVISORY),          // #11
    STORAGE_FULL_AUTOSTOPPED(WarningTier.ADVISORY),     // #12
    THERMAL_AUTOSTOPPED(WarningTier.ADVISORY),          // #13
    CANT_MERGE(WarningTier.ADVISORY),                   // #14  ← NEW (Phase 4.3)
    THERMAL_SEVERE(WarningTier.ADVISORY),               // #15  (was #14)
    MICROPHONE_DENIED(WarningTier.ADVISORY),            // #16
    BATTERY_OPTIMIZATION_ON(WarningTier.ADVISORY),      // #17
    POWER_SAVE_MODE(WarningTier.ADVISORY),              // #18
    THERMAL_MODERATE(WarningTier.ADVISORY),             // #19
    NOTIFICATIONS_DENIED(WarningTier.ADVISORY),         // #20
}
```

Tier = ADVISORY (Q7A). Position = directly after `THERMAL_AUTOSTOPPED` because both are echo-style auto-stop-context warnings that fire on Idle. Tier ordinal is the precedence axis; `WarningIdOrderTest` pins the ordering.

`WarningSheetContent.kt`:
- `warningSurfaceFor` adds `WarningId.CANT_MERGE -> WarningSurface.AdvisorySheet` (auto-presents on Idle, snooze-chip on dismiss — matches `BATTERY_OPTIMIZATION_ON` chrome verbatim per "hand-in-hand with current UI" constraint).
- **`WarningSheetContent` data class gains one new field**: `tertiary: WarningAction? = null` (destructive-link CTA, optional, defaults to null = backwards compatible — all other sheet contents continue to compile without the field).
- `warningSheetContent(WarningId.CANT_MERGE)` returns:
  - icon: `Icons.Default.MergeType` (or `Storage` fallback if the former isn't in the icon set)
  - title: `"Can't merge yet"`
  - body: `"Free space and retry, or keep the raw segments for later."`
  - primary: `WarningAction("Free space & retry", ActionTarget.STORAGE_SETTINGS)` (existing Intent target — opens system storage settings)
  - secondary: `WarningAction("Save segments only", ActionTarget.KEEP_SEGMENTS_ONLY)` (NEW VM-only target — see §4.3)
  - tertiary: `WarningAction("Discard session", ActionTarget.DISCARD_RECOVERY_SESSION)` (NEW VM-only target — see §4.3) — renders as `WarningActionStyle.Link` (see §7)
- `midRecBannerContent(WarningId.CANT_MERGE)` is a **defensive arm only** (throws "non-mid-rec id" per the existing pattern), since AdvisorySheet ids never render mid-rec. CANT_MERGE only ever surfaces idle.

### 4.3 New `ActionTarget.KEEP_SEGMENTS_ONLY`

`WarningSheetContent.kt` `ActionTarget` enum — add two values:

```kotlin
internal enum class ActionTarget {
    // existing ...
    /** Phase 4.3 — VM-only target: routes to RecoveryViewModel.keepRaw(sessionId). NOT an Intent. */
    KEEP_SEGMENTS_ONLY,
    /** Phase 4.3 — VM-only target: routes to RecoveryViewModel.dismiss(sessionId) (existing discard path). NOT an Intent. */
    DISCARD_RECOVERY_SESSION,
}
```

Both wired in `WarningCenter.kt`'s action dispatch the same way `DISMISS_AUTOSTOP_ECHO` is — VM-only targets that hit methods on the host VM (not `launchActionTarget`). The C2.4 sheet's three CTAs dispatch:
- primary → `launchActionTarget(STORAGE_SETTINGS)` (existing intent path)
- secondary → `RecoveryViewModel.keepRaw(pendingSessionId)` via a new `onKeepRawFromSheet` callback on `WarningCenter`
- tertiary → `RecoveryViewModel.dismiss(pendingSessionId)` via a new `onDiscardFromSheet` callback on `WarningCenter`

`pendingSessionId` is the sessionId associated with the active CANT_MERGE warning — sourced from `RecoveryMergeOutcomeSignal.State.Outcome.sessionId` and plumbed to the dispatch site (see §4.10).

### 4.4 `ExportPipeline.exportRecovered`

New entry alongside `ExportPipeline.export()`. Same return type (`ExportResult`); differs in:
- Storage pre-flight: query `getAvailableBytes(sessionDir)`; compute required ≈ `sum(segment.length()) * 1.05` (5% headroom for mux overhead). If insufficient, return new `ExportResult.InsufficientStorage(requiredBytes, availableBytes)` without opening MediaMuxer.
- DisplayName derivation: re-uses `Rova_<timestamp>` pattern but timestamp is `Date()` at recovery time, not session start — so files don't collide with any previously-named artifact for the same session (the failed first run never committed a name).
- All else identical: tier dispatch via `currentExportTier()`, mux via `VideoMerger` (allowed — `exportRecovered` lives in `service/export/`).

```kotlin
internal object ExportPipeline {
    suspend fun exportRecovered(
        context: Context,
        sessionStore: SessionStore,
        sessionId: String,
        sessionDir: File,
        segments: List<File>,
        onProgress: (Float) -> Unit,
    ): ExportResult { ... }
}

sealed class ExportResult {
    // existing: Success, UnknownSession, ...
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : ExportResult()
}
```

### 4.5 `RovaRecordingService.startRecoveryMerge`

`RovaRecordingService.kt` — add an action constant + companion factory:

```kotlin
class RovaRecordingService : LifecycleService(), RovaController {

    companion object {
        const val ACTION_RECOVER_MERGE = "com.aritr.rova.service.ACTION_RECOVER_MERGE"
        const val EXTRA_RECOVERY_SESSION_ID = "recovery_session_id"

        fun startRecoveryMerge(context: Context, sessionId: String) {
            val intent = Intent(context, RovaRecordingService::class.java).apply {
                action = ACTION_RECOVER_MERGE
                putExtra(EXTRA_RECOVERY_SESSION_ID, sessionId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Phase 4.3 — recovery merge branch.
        if (intent?.action == ACTION_RECOVER_MERGE) {
            val sessionId = intent.getStringExtra(EXTRA_RECOVERY_SESSION_ID)
                ?: return super.onStartCommand(intent, flags, startId).also { stopSelf(startId) }
            return handleRecoveryMergeStart(sessionId, startId)
        }
        // existing recording branch ...
    }

    private fun handleRecoveryMergeStart(sessionId: String, startId: Int): Int {
        // 1. startForeground with the recovery notification copy
        //    ("Merging recovered clips — <session-id-prefix>").
        // 2. Launch a coroutine on the service scope:
        //      val outcome = recoveryMerger.run(sessionId)
        //      app.recoveryMergeOutcomeSignal.emit(sessionId, outcome)
        //      stopForeground; stopSelf(startId).
        // 3. Reject if a *recording* session is already live (cannot recovery-merge
        //    over an active recording — the service holds the camera). Emit
        //    RecoveryMergeOutcome.ServiceBusy and stopSelf.
    }
}
```

A new `RecoveryMerger` class (lives in `service/recovery/`) wraps the `ExportPipeline.exportRecovered` call, owns segment enumeration via `SessionStore`, and translates the result into a `RecoveryMergeOutcome` sealed type (`Succeeded(uri) | InsufficientStorage(requiredBytes) | MuxFailed(throwable) | ServiceBusy | UnknownSession`).

### 4.6 `RecoveryMergeOutcomeSignal` (new)

`app/src/main/java/com/aritr/rova/ui/signals/RecoveryMergeOutcomeSignal.kt` — new producer file, registered as a lazy prop on `RovaApp` and subscribed by `WarningCenterViewModel`.

State emitted: `Idle | InProgress(sessionId, progress: Float) | Outcome(sessionId, outcome: RecoveryMergeOutcome)`.

Maps to `WarningId` per outcome:
- `InsufficientStorage` → `WarningId.CANT_MERGE` (active on next Idle).
- `MuxFailed` → existing `RecoveryCard` MergeFailed variant (already handled by §4.4 of the contract).
- `Succeeded` → no WarningId; RecoveryCard disappears (session is now COMPLETED).
- `ServiceBusy` → silent (UI prevents tap on Merge when active session running; this is defense-in-depth).

The signal `InProgress(progress)` value flows back to the `RecoveryCard`'s clip-progress strip via the `RecoveryViewModel` — strip cells fill left-to-right as `progress` advances from 0 to 1.

### 4.7 `RecoveryCard` signature extension

`RecoveryCard.kt` — extend with two optional callbacks:

```kotlin
@Composable
fun RecoveryCard(
    state: RecoveryCardState,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    vendorHelpSlot: (@Composable () -> Unit)? = null,
    // NEW — Phase 4.3
    onMerge: (() -> Unit)? = null,
    onKeepRaw: (() -> Unit)? = null,
    mergeInProgress: Float? = null,        // null = idle; 0..1 = filling strip
)
```

Per Q3A: `onMerge` / `onKeepRaw` null = button hidden (backwards compatible — existing test callers don't have to supply them). When both non-null, the existing single-button row replaces with a stacked 3-button column matching 07c §4.x extension:
1. `Merge segments` (primary, ink fill)
2. `Keep as raw clips` (ghost, hairline border)
3. `Discard recording` (destructive, hard-red fill — unchanged)

When `mergeInProgress != null`, the buttons go non-interactive and the clip-progress strip becomes the progress display.

### 4.8 `RecoveryCardState` extension

`RecoveryUiState.kt` — add fields to expose the merge capability:

```kotlin
data class RecoveryCardState(
    val sessionId: String,
    val kind: RecoveryCardKind,
    val title: String,
    val body: String,
    val discardLabel: String,
    val showVendorHelpSlot: Boolean,
    val survivingArtifacts: List<String>,
    // NEW — Phase 4.3
    val mergeLabel: String? = null,            // null = no merge button (back-compat default)
    val keepRawLabel: String? = null,          // null = no keep-raw button
    val mergeInProgress: Float? = null,        // null = idle; 0..1 = strip fill
    val mergeFailedReason: String? = null,     // null = no failure; non-null = flips body+label to retry copy (see §6 failure matrix)
)
```

`RecoveryUiStateMapper.toCard` populates `mergeLabel = "Merge segments"` + `keepRawLabel = "Keep as raw clips"` whenever `survivingArtifacts` is non-empty (i.e., there's something to merge). Mapper logic:
- `survivingArtifacts.isEmpty()` → both labels null (only Discard renders, matches beta).
- `survivingArtifacts.isNotEmpty()` → both labels non-null.

### 4.9 `RecoveryViewModel` extension

`RecoveryViewModel.kt` — add two methods + a progress flow. Constructor seams renamed (`markKeptRaw`, `startRecoveryMergeFn`) so method names (`keepRaw`, `merge`) don't shadow them:

```kotlin
class RecoveryViewModel(
    recoveryReport: StateFlow<RecoveryReport?>,
    loadManifest: (String) -> SessionManifest?,
    private val discardSession: suspend (String) -> Unit = {},
    // NEW
    private val markKeptRaw: suspend (String) -> Unit = {},        // → SessionStore.markTerminated(MULTI_SEGMENT_KEPT)
    private val startRecoveryMergeFn: (String) -> Unit = {},       // → RovaRecordingService.startRecoveryMerge(context, sid)
    private val mergeOutcome: StateFlow<RecoveryMergeOutcomeSignal.State> = MutableStateFlow(...),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val uiState: StateFlow<RecoveryUiState> = combine(recoveryReport, dismissedIds, mergeOutcome) { ... }
        // mapper merges mergeOutcome's InProgress(sessionId, progress) into the matching card's
        // mergeInProgress field so the strip animates without a separate flow plumb.

    fun merge(sessionId: String) {
        startRecoveryMergeFn(sessionId)
    }

    fun keepRaw(sessionId: String) {
        scope.launch {
            try { markKeptRaw(sessionId) }
            catch (t: Throwable) { RovaLog.w("keepRaw failed for $sessionId", t) }
        }
    }
}
```

Production wiring (in `RovaApp.buildRecoveryViewModel` or similar — exact site is implementation-plan territory) passes:
- `markKeptRaw = { sid -> sessionStore.markTerminated(sid, Terminated.MULTI_SEGMENT_KEPT, StopReason.NONE) }`
- `startRecoveryMergeFn = { sid -> RovaRecordingService.startRecoveryMerge(appContext, sid) }`
- `mergeOutcome = app.recoveryMergeOutcomeSignal.state`

### 4.10 `WarningCenter.kt` C2.4 sheet wiring

When `WarningCenterViewModel.activeWarning == WarningId.CANT_MERGE` on Idle, `WarningCenter` opens `WarningSheetV3` via the existing AdvisorySheet path (mirrors `BATTERY_OPTIMIZATION_ON` rendering exactly). The sheet's three actions dispatch:
1. **Primary "Free space & retry"** → `ActionTarget.STORAGE_SETTINGS` (existing) → `launchActionTarget` opens system storage settings. After return, the sheet remains; user taps RecoveryCard's `Retry merge` (= `Merge segments`) again.
2. **Secondary "Save segments only"** → new `ActionTarget.KEEP_SEGMENTS_ONLY` (VM-only) → `WarningCenter` invokes `onKeepRawFromSheet(pendingSessionId)`; the host (`RecordScreen`) wires that callback to `RecoveryViewModel.keepRaw(sessionId)`. Sheet closes.
3. **Destructive-link "Discard session"** → new `ActionTarget.DISCARD_RECOVERY_SESSION` (VM-only) → `WarningCenter` invokes `onDiscardFromSheet(pendingSessionId)`; the host wires it to `RecoveryViewModel.dismiss(sessionId)`. Sheet closes.

`pendingSessionId` source: the `RecoveryMergeOutcomeSignal.State` carries the sessionId in its `InsufficientStorage(sessionId, requiredBytes)` variant. `WarningCenterViewModel` reads the signal alongside the precedence pick; when the active id is CANT_MERGE, the VM exposes the sessionId via a new `pendingCantMergeSessionId: StateFlow<String?>` for `WarningCenter` to pass into the callback at dispatch time.

### 4.11 Library list extension for `MULTI_SEGMENT_KEPT` (out-of-spec, callout only)

When a session ends in `MULTI_SEGMENT_KEPT`, the History/Library list mapper (lives outside Phase 4 scope — `HistoryScreen.kt` + companion mapper, not this spec's territory) must enumerate the segments as individual rows. This spec only writes the terminal; the Library-side rendering is **out of scope** here and is owned by whatever phase touches History list construction next.

**For Phase 4.3 ship-readiness:** the session simply doesn't appear in Library (no merged artifact, recovery card removed). User keeps segments on disk via the path `<session_dir>/seg_*.mp4`. A subsequent slice (presumably a Library-side phase) renders them as rows. The spec acknowledges this as a known limitation — see §10 Open items.

## 5. Contract correction

`docs/WarningCenterContract.md` §4.2 C2.4 row:

| Field | Before | After |
|---|---|---|
| Producer source | "Phase 4.3 — `RovaController.recoverAndMerge(sessionId)` reuses existing merg..." | "Phase 4.3 — `RovaRecordingService.startRecoveryMerge(context, sessionId)` + `ExportPipeline.exportRecovered()` + `RecoveryMergeOutcomeSignal`. The merge runs inside the FGS lifecycle; outcomes drive WarningId via the signal." |
| Phase 3 owner | "Phase 4.3" | "Phase 4.3 (this slice)" |

Also add a new row §4.4 entry for the C2.4 production-flow note (echo-style ADVISORY tier matching STORAGE_FULL_AUTOSTOPPED and THERMAL_AUTOSTOPPED).

Memory update (requires owner authorization per standing constraint): `[[project-current-state]]` parked-backlog "Phase 4.3" entry — replace the `RovaController.recoverAndMerge` host with the corrected name.

## 6. Failure handling matrix

| Trigger | Detection point | Surface | User next action |
|---|---|---|---|
| Storage too low for merge | `ExportPipeline.exportRecovered` pre-flight (`getAvailableBytes` < required) | C2.4 sheet auto-presents on next Idle (AdvisorySheet chrome); RecoveryCard's Merge button becomes "Retry merge" + body text flips to "Last merge needs ~N MB more free space" | "Free space & retry" → settings → retry; or "Save segments only" / "Discard" |
| Mux throws (encoder/IO error) | `ExportPipeline.exportRecovered` catches `Throwable` from `VideoMerger.mergeSegments[ToFd]` | RecoveryCard re-renders with same chrome + a new transient `mergeFailedReason: String?` field on `RecoveryCardState`. When non-null: body text replaced with "Last merge failed: <reason>" + Merge button label = "Retry merge". **No new `RecoveryCardKind`** — same data class, transient flag. | "Retry merge" (re-tap) or Discard |
| Live recording session active when user taps Merge | `RovaRecordingService.handleRecoveryMergeStart` guards on `ServiceController` registry. UI also disables the Merge button when `RecordViewModel.hudState != Idle` (host-side, defense-in-depth — so the ServiceBusy outcome should be unreachable). | Host (HistoryScreen) snackbar surfaces ServiceBusy as a safety net only — should never fire under normal flow | Stop active session via Record screen, then re-tap |
| `MULTI_SEGMENT_KEPT` chosen | `SessionStore.markTerminated` succeeds atomically (ADR-0006 contract) | RecoveryCard removed (mapper hide rule); session no longer flagged for recovery | View segments on disk (Library row construction = future slice) |
| Process killed during merge | OS kills FGS mid-mux; next cold start re-classifies session (still `OFFER_DISCARD` because no terminal write) | RecoveryCard returns as before; user re-taps Merge | Same Merge flow re-runs |

## 7. UI surfaces — fidelity callouts

Per the "hand-in-hand with current UI" constraint:

- **C2.4 sheet** uses `WarningSheetV3` unchanged. Severity tier resolves to ADVISORY (matches the icon-glow + sev-pill chrome). 3-button layout: primary `wbtn-p`, secondary `wbtn-s`, destructive `wbtn-link` per 07c §2.4. The destructive-link styling (red text underlined, transparent background) is **new** to `WarningSheetV3`'s rendering vocabulary — added as one new variant (`WarningActionStyle.Link`) on `WarningAction`. Variant defaults to `Primary`/`Secondary` for existing arms so all current sheets compile without change.
- **RecoveryCard** chrome (glow bloom, severity tag, progress strip, ADR-0013 corner radius + glass alpha) preserved byte-for-byte from PR #44. Only the CTA-row composable changes from one button to up to three. Existing `SeverityTag` + `ProgressStrip` private composables are unchanged; the new logic lives in a new private `CtaRow` composable in the same file.
- **Progress strip during merge** repurposes the existing `ProgressStrip` (per `RecoveryCard.kt:275-322`): `mergeInProgress` ∈ [0,1] determines how many cells fill from left. When idle, the strip shows the static "N / N recovered" view (today's behavior). The reuse is intentional — same visual element, different data source.
- **No new RovaWarnings tokens.** All colors/dimensions come from `RovaWarningsV3` and `RovaWarnings` as shipped. If a new token is needed for the link-style CTA, add it in this slice but keep it under `RovaWarningsV3` (Phase 4 lineage).

## 8. Data shape changes — full list

| File | Change |
|---|---|
| `data/SessionManifest.kt` | `Terminated` enum: add `MULTI_SEGMENT_KEPT` |
| `ui/warnings/WarningId.kt` | Add `CANT_MERGE` at row #14 (ADVISORY) |
| `ui/warnings/WarningSheetContent.kt` | Add `CANT_MERGE` arm in `warningSurfaceFor` (→ AdvisorySheet), `warningSheetContent` (sheet body), `midRecBannerContent` (defensive throw — non-mid-rec id). Add `ActionTarget.KEEP_SEGMENTS_ONLY` + `ActionTarget.DISCARD_RECOVERY_SESSION`. Add `tertiary: WarningAction? = null` field on `WarningSheetContent`. Add `WarningActionStyle` enum (`Primary` / `Secondary` / `Link`) + field on `WarningAction` (defaults preserve all existing sheet renders). |
| `ui/recovery/RecoveryUiState.kt` | `RecoveryCardState`: add `mergeLabel`, `keepRawLabel`, `mergeInProgress`. `RecoveryUiStateMapper.toCard`: populate them when `survivingArtifacts.isNotEmpty()`. |
| `ui/recovery/RecoveryCard.kt` | Composable signature: add `onMerge`, `onKeepRaw`, `mergeInProgress`. New private `CtaRow` for 3-CTA stack. `ProgressStrip` accepts optional `progress: Float?` to drive in-flight fill. |
| `ui/recovery/RecoveryViewModel.kt` | Add `keepRaw`, `merge` methods + `mergeOutcome` flow input. Combine into `uiState`. |
| `service/RovaRecordingService.kt` | Add `ACTION_RECOVER_MERGE`, `EXTRA_RECOVERY_SESSION_ID`, companion `startRecoveryMerge`, `handleRecoveryMergeStart` branch in `onStartCommand`. |
| `service/export/ExportPipeline.kt` | Add `exportRecovered(sessionId, sessionDir, segments)` function. Add `ExportResult.InsufficientStorage` variant. |
| `service/recovery/RecoveryMerger.kt` | **NEW.** Wraps `ExportPipeline.exportRecovered` + emits to `RecoveryMergeOutcomeSignal`. |
| `ui/signals/RecoveryMergeOutcomeSignal.kt` | **NEW.** Holds the in-progress/outcome state flow. |
| `RovaApp.kt` | Add `recoveryMergeOutcomeSignal` lazy prop. Wire it into `buildWarningCenterViewModel` + `buildRecoveryViewModel`. |
| `docs/WarningCenterContract.md` | §C2.4 row correction (see §5). |

## 9. ADR consideration

**New ADR-0017 (Recommended): "Recovery merge architecture — service-hosted, ExportPipeline-routed, signal-driven outcomes."**

Rationale: the architecture introduces 4 contract pieces (start-with-action service entry, eager pre-flight in ExportPipeline, MULTI_SEGMENT_KEPT terminal, signal-driven CANT_MERGE) that future phases will need to reason about. Without an ADR, the "why FGS not WorkManager" + "why MULTI_SEGMENT_KEPT vs ExportState flag" questions resurface every time the recovery path is touched.

The ADR records:
- Decision: host = `RovaRecordingService` via `ACTION_RECOVER_MERGE`, not new `WorkManager` or new controller class.
- Rationale: FGS lifecycle + notification + battery-optimization wiring already present; recovery merge benefits from all three.
- Rejected alternatives: WorkManager (B), caller-scope coroutine (C) — see brainstorm companion.
- Single-entry invariant: all merges (live + recovery) terminate at `ExportPipeline` → `VideoMerger`. `checkExportPipelineSingleEntry` continues to enforce.

## 10. Test policy

Mirror Slice 1-3 scale (~25-30 tests per slice):

| Layer | Tests |
|---|---|
| `Terminated.MULTI_SEGMENT_KEPT` exhaustive `when` | Pin via `MULTI_SEGMENT_KEPT_TerminalTest` — compile-time gate fails any added when-site that omits the arm |
| `WarningId.CANT_MERGE` ordinal | Extend `WarningIdOrderTest` to pin row #14 |
| `WarningPrecedence` resolves CANT_MERGE among echoes | One test per peer (`STORAGE_FULL_AUTOSTOPPED`, `THERMAL_AUTOSTOPPED`) — relative ordering verified |
| `WarningSheetContent.warningSheetContent(CANT_MERGE)` | Title/body/CTA labels match 07c §2.4 verbatim |
| `ExportPipeline.exportRecovered` pre-flight | Mock `getAvailableBytes` low → `InsufficientStorage` returned without MediaMuxer construction |
| `ExportPipeline.exportRecovered` success | Mock available storage high → tier-dispatched mux call observed (seam) |
| `RecoveryMerger.run` outcome translation | Each `ExportResult` variant maps to expected `RecoveryMergeOutcome` |
| `RecoveryMergeOutcomeSignal` → WarningId mapping | InsufficientStorage → CANT_MERGE; MuxFailed → MergeFailed-card-only (no WarningId); Success → no WarningId |
| `RecoveryUiStateMapper.toCard` mergeLabel population | Empty `survivingArtifacts` → labels null; non-empty → "Merge segments" / "Keep as raw clips" populated |
| `RecoveryViewModel.keepRaw` calls SessionStore | Seam invoked with `MULTI_SEGMENT_KEPT` |
| `RecoveryViewModel.merge` triggers service start | Seam invoked with sessionId |
| `RecoveryCard` rendering — 1 CTA path | Backwards compat: when `onMerge == null`, only Discard renders (matches PR #44 baseline) |
| `RecoveryCard` rendering — 3 CTA path | When both labels populated, stack renders in expected order |
| `RecoveryCard` rendering — in-progress | When `mergeInProgress != null`, buttons disabled + ProgressStrip animates |
| `RovaRecordingService.handleRecoveryMergeStart` | When active recording present → `ServiceBusy` outcome emitted, recovery merge does not start |

Total ≈ 15-20 new JVM tests + 2-3 instrumented Compose preview tests. No Espresso (matches Slice 1-3 policy).

## 11. Open items / future

- **Library row enumeration for `MULTI_SEGMENT_KEPT`** is out of this slice. The terminal lands; the Library-side rendering does not. Future slice (likely a Library-phase) renders one row per segment. Until then, kept-raw sessions are invisible in Library — their segments persist on disk at `<sessionDir>/seg_*.mp4`.
- **Retry backoff / auto-retry on transient failures** parked. Phase 4.3 ships a manual-retry-only flow.
- **Mid-recording "Can't merge" prevention** (refuse to start a session if a prior session's recovery is CANT_MERGE-pending) parked. Today the user can record over a CANT_MERGE-pending session — the recovery card stays until acted on.
- **Merge progress in the FGS notification** (not just the in-app card) parked. Notification stays at the existing "Recording in progress" style copy adapted to "Merging recovered clips".

## 12. Memory update (owner-authorized)

After spec is approved, update `[[project-current-state]]` parked-backlog Phase 4.3 entry to reflect:
- Host: `RovaRecordingService.startRecoveryMerge`, not `RovaController.recoverAndMerge`.
- ADR-0017 added.
- New WarningId: `CANT_MERGE` (#14).
- New Terminated: `MULTI_SEGMENT_KEPT`.
- Library row enumeration parked separately.
