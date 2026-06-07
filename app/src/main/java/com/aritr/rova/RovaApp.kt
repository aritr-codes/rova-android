package com.aritr.rova

import android.app.AlarmManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.service.export.AndroidMediaScanWaiter
import com.aritr.rova.service.export.ExportCleanupPredicate
import com.aritr.rova.service.export.ExportRecoveryReport
import com.aritr.rova.service.export.ExportResult
import com.aritr.rova.service.export.ExportRecoveryRunner
import com.aritr.rova.service.export.combineRecoveryResults
import com.aritr.rova.service.export.OrphanSweepResult
import com.aritr.rova.service.export.RecoveryResult
import com.aritr.rova.service.export.Tier1AndroidOps
import com.aritr.rova.service.export.Tier1AndroidSweepOps
import com.aritr.rova.service.export.Tier1Exporter
import com.aritr.rova.service.export.Tier1OrphanSweep
import com.aritr.rova.service.export.Tier2Exporter
import com.aritr.rova.service.export.Tier3Exporter
import com.aritr.rova.service.recovery.RecoveryReport
import com.aritr.rova.service.recovery.RecoveryScanner
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.data.latestTerminalSession
import com.aritr.rova.ui.signals.BatteryOptimizationSignal
import com.aritr.rova.ui.signals.CameraPermissionSignal
import com.aritr.rova.ui.signals.CameraStateSignal
import com.aritr.rova.ui.signals.ExactAlarmSignal
import com.aritr.rova.ui.signals.MicrophonePermissionSignal
import com.aritr.rova.ui.signals.NotificationPermissionSignal
import com.aritr.rova.ui.signals.PowerSignal
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import com.aritr.rova.ui.signals.SessionAutoStopEchoSignal
import com.aritr.rova.ui.signals.SaveFolderSignal
import com.aritr.rova.ui.signals.StorageLowMidRecSignal
import com.aritr.rova.ui.signals.StorageSignal
import com.aritr.rova.ui.signals.ThermalStatusSignal
import com.aritr.rova.ui.vault.VaultLockState
import com.aritr.rova.utils.RovaCrashReporter
import com.aritr.rova.utils.RovaLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Application subclass.
 *
 * Phase 1.2 baseline: process-wide [SessionStore] singleton, long-lived
 * [appScope] for cross-component work, crash backend swap point.
 *
 * Phase 1.5 additions (ADR 0005 — Recovery Scan):
 * - [recoveryReport]: StateFlow consumed by Phase 2 UI for the recovery
 *   dialog and anomaly list.
 * - [recoveryScanRan]: idempotency latch for [triggerRecoveryScanIfNeeded].
 *   Per Guard A, reset to `false` if [runRecoveryScan] throws so a crashed
 *   scan does not permanently mute recovery for the rest of the process.
 * - [activeReceiverWork]: Guard B counter. Receivers increment synchronously
 *   in `onReceive` before `goAsync()` and decrement in their launched
 *   coroutine's `finally`. The recovery scan blocks on this reaching zero
 *   before classifying any session.
 * - [startupMutex]: shared with [RovaRecordingService.startPeriodicRecording]
 *   around `createSession + ServiceController.register`. The scan holds it
 *   for its full duration; the service holds it across the create+register
 *   pair. A session cannot be observed half-registered, and a scan cannot
 *   read a session that the service is in the middle of creating.
 *
 * The recovery scan trigger is **only** invoked from `MainActivity.onCreate`
 * via [triggerRecoveryScanIfNeeded]. It is NOT invoked from this class's
 * [onCreate]: when the process is cold-started for receiver delivery (a
 * pending TICK or STOP alarm), `Application.onCreate` runs WITHOUT a UI
 * surface; running the scan there would race the receiver's terminal write.
 */
class RovaApp : Application() {

    /**
     * Phase 1.4 (ADR 0006 B21). External app-private storage root,
     * resolved ONCE at process boot via [resolveExternalRoot]. May be
     * `null` on cold launch if external storage is mounted read-only or
     * unavailable. Production callers MUST treat null as a hard failure
     * (no recording can start; recovery cannot scan anything).
     *
     * Backed by lazy initialization — the first `videosRoot` /
     * `sessionStore` access triggers resolution. Subsequent accesses
     * return the same `File` so storage preflight and `SessionStore`
     * reason about the same volume.
     */
    val externalRoot: File? by lazy { resolveExternalRoot(this) }

    /**
     * Phase 1.4 (ADR 0006 B21). The `videos/` directory under the
     * resolved external root. `null` iff [externalRoot] is null.
     * `SessionStore` is constructed with this exact `File`; storage
     * preflight checks the same path.
     */
    val videosRoot: File? by lazy { externalRoot?.let { File(it, "videos") } }

    val sessionStore: SessionStore by lazy {
        val root = videosRoot ?: error(
            "RovaApp: external storage root unavailable; cannot construct SessionStore. " +
                "Caller must check RovaApp.videosRoot before accessing sessionStore."
        )
        SessionStore(root)
    }

    /**
     * Phase 3.2 (NEW_UI_BACKEND_REPLAN §5 row 3.2). Process-wide
     * StateFlow of the current `POST_NOTIFICATIONS` grant state.
     * Pre-API-33 the flow is a constant `true` (OS does not gate
     * notifications below TIRAMISU). Lazy-initialized to mirror the
     * [sessionStore] precedent.
     *
     * Refresh contract lives on the signal itself: callers re-call
     * [NotificationPermissionSignal.refresh] from the host Activity's
     * `ON_RESUME` because Android does not broadcast permission grants.
     * The Phase 4 WarningCenterViewModel is the consumer; this slice
     * ships the signal only.
     */
    val notificationPermissionSignal: NotificationPermissionSignal by lazy {
        NotificationPermissionSignal.forContext(this)
    }

    /**
     * Phase 3.4 (NEW_UI_BACKEND_REPLAN §5 row 3.4) — ThermalStatusSignal
     * exposed lazily so cold-start receiver paths pay no cost; first
     * access from a foreground host initializes. Refresh contract lives
     * on the signal itself (host Activity ON_RESUME). Pre-API-29 the
     * flow is a constant [com.aritr.rova.ui.signals.ThermalStatus.NONE]
     * (PowerManager.getCurrentThermalStatus does not exist below Q).
     * Consumer is the Phase 4 WarningCenterViewModel; this slice ships
     * the signal only.
     */
    val thermalStatusSignal: ThermalStatusSignal by lazy {
        ThermalStatusSignal.forContext(this)
    }

    /**
     * Phase 3.3 (NEW_UI_BACKEND_REPLAN §5 row 3.3) — PowerSignal exposed
     * lazily so cold-start receiver paths pay no cost; first access from
     * a foreground host initializes. Refresh contract lives on the signal
     * itself (host Activity ON_RESUME). CAPACITY + isPowerSaveMode gate
     * at API 21; STATUS gates at API 26 and uses an inner SDK guard inside
     * forContext (24/25 → false-charging fallback, legacy sticky-broadcast
     * path out of scope). minSdk = 24. Consumer is the Phase 4 WarningCenterViewModel;
     * this slice ships the signal only.
     */
    val powerSignal: PowerSignal by lazy {
        PowerSignal.forContext(this)
    }

    /**
     * Phase 3.6 (NEW_UI_BACKEND_REPLAN §5 row 3.6) — ExactAlarmSignal
     * exposed lazily so cold-start receiver paths pay no cost; first
     * access from a foreground host initializes. Pre-API-31 the flow
     * is a constant `true` (the OS has no concept of exact-alarm
     * revocation below S). On API 31+ the receiver registered in
     * [onCreate] invokes [ExactAlarmSignal.refresh] on every
     * [AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED]
     * broadcast — the ON_RESUME poll used by other signals is not
     * needed because the OS broadcasts grant changes for this
     * permission. ADR 0001 governs the degradation path in the
     * scheduler tree; this slice ships the signal only. Consumer is
     * the Phase 4 WarningCenterViewModel.
     */
    val exactAlarmSignal: ExactAlarmSignal by lazy {
        ExactAlarmSignal.forContext(this)
    }

    /**
     * Phase 3.5 (NEW_UI_BACKEND_REPLAN §5 row 3.5) — CameraStateSignal,
     * the app-scoped owner of the runtime camera-health StateFlow.
     * Unlike the other signals there is no `forContext` / SDK gate:
     * the signal is purely fed by [com.aritr.rova.service.RovaRecordingService],
     * which observes `Camera.cameraInfo.cameraState` after each
     * `bindToLifecycle` and calls `onCameraState` / `onCameraUnbound`.
     * No broadcast receiver — camera-state has no system broadcast, so
     * (unlike [exactAlarmSignal]) [onCreate] adds nothing for it. Lazy
     * so cold-start receiver paths pay no cost. Consumer is the Phase 4
     * WarningCenterViewModel ("Camera in use" banner).
     */
    val cameraStateSignal: CameraStateSignal by lazy { CameraStateSignal() }

    /**
     * Phase 4.1 (NEW_UI_BACKEND_REPLAN §5 row 13) — battery-optimization
     * exemption signal, consumed by the Phase 4 WarningCenterViewModel
     * (the `BATTERY_OPTIMIZATION_ON` banner). Lazy so cold-start receiver
     * paths pay no cost. Refresh contract lives on the signal itself (host
     * Activity ON_RESUME — grant changes don't broadcast). Replaces the
     * standalone `BatteryOptimizationBanner` polling that RecordScreen did
     * inline.
     */
    val batteryOptimizationSignal: BatteryOptimizationSignal by lazy {
        BatteryOptimizationSignal.forContext(this)
    }

    /**
     * Phase 4.1b (NEW_UI_BACKEND_REPLAN row 1) — CAMERA permission grant as
     * a [StateFlow], consumed by the Phase 4 WarningCenterViewModel (the
     * `CAMERA_PERMISSION_DENIED` banner) and by RecordScreen's Start-gate.
     * Lazy so cold-start paths pay nothing. Refresh contract lives on the
     * signal (host Activity ON_RESUME + on permission-state change).
     */
    val cameraPermissionSignal: CameraPermissionSignal by lazy {
        CameraPermissionSignal.forContext(this)
    }

    /**
     * Phase 4.1b (NEW_UI_BACKEND_REPLAN row 12) — RECORD_AUDIO permission
     * grant as a [StateFlow], consumed by the WarningCenterViewModel (the
     * `MICROPHONE_DENIED` advisory banner). Lazy. Refresh contract on the
     * signal (host Activity ON_RESUME + on permission-state change).
     */
    val microphonePermissionSignal: MicrophonePermissionSignal by lazy {
        MicrophonePermissionSignal.forContext(this)
    }

    /**
     * Phase 4.1b (NEW_UI_BACKEND_REPLAN row 3) — "not enough storage to
     * start" as a [StateFlow], consumed by the WarningCenterViewModel (the
     * `STORAGE_INSUFFICIENT` hard-block banner) and by RecordScreen's
     * Start-gate. Lazy; initial value `false` until the host screen first
     * calls [StorageSignal.recompute] with the current clip settings.
     */
    val storageSignal: StorageSignal by lazy { StorageSignal.forContext(this) }

    /**
     * R2 (NEW_UI_BACKEND_REPLAN row 11) — mid-recording free-space advisory.
     * Drives STORAGE_LOW_MID_REC. The host (RecordScreen, T9) calls poll()
     * every ~30 s while in an active HUD state and clear() on transition to Idle.
     */
    val storageLowMidRecSignal: StorageLowMidRecSignal by lazy {
        StorageLowMidRecSignal.forContext(this)
    }

    /**
     * Phase 4 Slice 2 — auto-stop echo signal. Reads the most-recent terminal
     * session manifest at lazy-init and surfaces it as a [TerminalEcho?],
     * filtered against the persistent dismissed-id set from [RovaSettings].
     * Refresh triggers: RecordScreen ON_RESUME (see RecordScreen.kt T6),
     * + future service terminal-transition observers.
     *
     * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md §4.5
     */
    val autoStopEchoSignal: SessionAutoStopEchoSignal by lazy {
        val settings = RovaSettings(this)
        SessionAutoStopEchoSignal(
            terminalEchoSource = { sessionStore.latestTerminalSession() },
            initialDismissedIds = settings.dismissedAutoStopEchoIds,
        )
    }

    /**
     * Phase 4.3 — push signal for the recovery merge lifecycle. Lives here
     * as a lazy singleton; producer is [com.aritr.rova.service.RovaRecordingService]
     * running [com.aritr.rova.service.recovery.RecoveryMerger] inside the FGS.
     * Consumers (Task 9+): RecoveryViewModel + WarningCenterViewModel.
     */
    val recoveryMergeOutcomeSignal: RecoveryMergeOutcomeSignal by lazy {
        RecoveryMergeOutcomeSignal()
    }

    /**
     * B4b (ADR-0024) — SAF save-folder unavailability signal. `true` once
     * the exporter has permanently flagged the custom save folder as gone
     * or permission-revoked. Drives the SAVE_FOLDER_UNAVAILABLE advisory
     * warning in the WarningCenter. Lazy so cold-start receiver paths pay
     * no cost. Refresh contract: host Activity ON_RESUME.
     */
    val saveFolderSignal: SaveFolderSignal by lazy {
        SaveFolderSignal.forContext(this)
    }

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Shared between [RovaApp.runRecoveryScan] and
     * [com.aritr.rova.service.RovaRecordingService.startPeriodicRecording].
     * The scan holds it for its full duration; the service holds it across
     * `createSession + ServiceController.register`.
     */
    val startupMutex: Mutex = Mutex()

    /**
     * Guard B counter. Receivers ([com.aritr.rova.service.RovaTickReceiver],
     * [com.aritr.rova.service.RovaStopReceiver]) increment synchronously in
     * `onReceive` BEFORE `goAsync()` and decrement in their launched
     * coroutine's `finally`. The scan blocks on this reaching `0` before
     * classifying any session.
     */
    val activeReceiverWork: AtomicInteger = AtomicInteger(0)

    private val _recoveryReport: MutableStateFlow<RecoveryReport?> = MutableStateFlow(null)
    /**
     * The most recent recovery scan report. `null` until the first scan
     * completes (or until a deferred scan retries). Phase 2 UI observes
     * this flow to surface recovery dialogs and anomaly lists.
     */
    val recoveryReport: StateFlow<RecoveryReport?> = _recoveryReport.asStateFlow()

    private val recoveryScanRan: AtomicBoolean = AtomicBoolean(false)

    /**
     * B5 / ADR-0025 (spec R5) — app-scoped, in-memory vault lock holder.
     * Seeded locked at process boot; NEVER persisted. Re-auth on every
     * vault entry and on app foreground. Callers mutate through
     * [onVaultAuthSucceeded] / [onLeaveVault] and the ON_STOP relock
     * observer registered in [onCreate] — never the private backer.
     */
    private val _vaultLock = MutableStateFlow(VaultLockState.initial())
    val vaultLock: StateFlow<VaultLockState> = _vaultLock

    /** B5/ADR-0025 (spec R5) — call after a successful BiometricPrompt/keyguard auth. */
    fun onVaultAuthSucceeded() { _vaultLock.update { it.onAuthSucceeded() } }

    /** B5/ADR-0025 (spec R5) — call when the vault route is popped. */
    fun onLeaveVault() { _vaultLock.update { it.onLeaveVault() } }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // RovaCrashReporter.setBackend(FirebaseCrashlyticsBackend()) — wired Phase 4.
        // NOTE: Phase 1.5 recovery is NOT triggered here. See class KDoc.
        registerExactAlarmStateReceiverIfSupported()
        // Phase 4 Slice 3 — register the thermal push listener for the
        // process lifetime. Idempotent + pre-API-29 no-op. The OS releases
        // the registration on process death (Application.onTerminate is
        // not reliably invoked on production devices).
        thermalStatusSignal.start()
        // B5/ADR-0025 (spec R5) — relock the vault when the whole app
        // backgrounds. Mirrors the ADR-0021 ProcessLifecycleOwner pattern
        // (the action is the vault relock, not camera). No unregister: the
        // Application lives for the whole process.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // B5/ADR-0025 (spec R5) — relock on app background; in-memory only, never persisted.
                _vaultLock.update { it.onAppBackgrounded() }
            }
        })
    }

    /**
     * Phase 3.6. Process-lifetime registration of the
     * [AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED]
     * receiver. No unregister: this is the Application class and the
     * receiver lives until process death. API 31+ only — the broadcast
     * action constant and the OS path that emits it both gate at S.
     * The 3-arg `registerReceiver` overload that takes a flags int is
     * required on API 33+ for dynamic receivers; below 33 the 2-arg
     * overload is used.
     */
    private fun registerExactAlarmStateReceiverIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val filter = IntentFilter(
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action ==
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
                    exactAlarmSignal.refresh()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    /**
     * ADR 0005 §"Scan Trigger Boundary" — the SOLE entry point that
     * launches [runRecoveryScan]. Idempotent per process via [recoveryScanRan].
     *
     * Guard A: if [runRecoveryScan] throws, [recoveryScanRan] is reset to
     * `false` so a subsequent foreground entry can retry. The throwable is
     * recorded via [RovaCrashReporter] (no swallow).
     */
    fun triggerRecoveryScanIfNeeded() {
        if (!recoveryScanRan.compareAndSet(false, true)) {
            return
        }
        appScope.launch {
            try {
                runRecoveryScan()
            } catch (t: Throwable) {
                // Guard A: reset latch + record. NEVER swallow.
                recoveryScanRan.set(false)
                RovaLog.e("RovaApp.runRecoveryScan threw; latch reset for retry", t)
                RovaCrashReporter.recordException(t, "RovaApp.runRecoveryScan threw")
            }
        }
    }

    private suspend fun runRecoveryScan() {
        val scanStart = System.currentTimeMillis()
        startupMutex.withLock {
            // ADR 0005 §"Concurrency Invariants" item 4 — pre-scan drain.
            // Block on activeReceiverWork == 0 with a 10 s cap (8 s receiver
            // budget + 2 s margin). On timeout: reset latch, defer scan,
            // emit a deferred RecoveryReport so the UI can surface "scan
            // deferred — retry from menu" rather than spin indefinitely.
            val drained = withTimeoutOrNull(DRAIN_TIMEOUT_MILLIS) {
                while (activeReceiverWork.get() > 0) {
                    delay(DRAIN_POLL_MILLIS)
                }
                true
            }
            if (drained == null) {
                RovaLog.w("RovaApp.runRecoveryScan: drain timed out (activeReceiverWork=${activeReceiverWork.get()}); deferring")
                recoveryScanRan.set(false)
                _recoveryReport.value = RecoveryReport(
                    classifications = emptyMap(),
                    scanStartMillis = scanStart,
                    scanCompletedMillis = System.currentTimeMillis(),
                    deferred = true
                )
                return@withLock
            }

            // ADR 0006 §"Ownership table" — Phase 1.7 export recovery runs
            // BEFORE the Phase 1.5 classifier so the classifier sees post-
            // recovery manifest state when it builds discard eligibility.
            // The runner snapshots referencedPendingUris BEFORE per-session
            // recovery mutates manifests (a setExportFailed during recovery
            // would clear pendingUri and orphan the row to the sweep).
            val exportReport = buildExportRecoveryRunner().run()
            RovaLog.d {
                "RovaApp.runRecoveryScan: export recovery complete " +
                    "(perSession=${exportReport.perSession.size}, " +
                    "lateTerminals=${exportReport.lateTerminals.size}, " +
                    "sweep=${exportReport.sweep.javaClass.simpleName})"
            }

            val scanner = RecoveryScanner(sessionStore)
            val classifications = scanner.classifyAll(scanStart)

            // Cleanup pass — gated by all four conditions per ADR 0006:
            // (1) AUTO_DISCARD_ELIGIBLE, (2) privateTempPath == null,
            // (3) per-session recovery is terminal-clean (not RetryableFailure
            // / ManifestWriteFailed), (4) sweep returned Swept (not
            // QueryFailed). The deletion call site lives in the export-
            // package helper so RovaApp.kt remains free of deletion APIs
            // (per checkRecoveryNoDeletion).
            val deleted = ExportCleanupPredicate.runCleanupPass(
                sessionStore, classifications, exportReport
            )
            if (deleted.isNotEmpty()) {
                RovaLog.d { "RovaApp.runRecoveryScan: cleanup pass discarded ${deleted.size} session(s)" }
            }

            _recoveryReport.value = RecoveryReport(
                classifications = classifications,
                scanStartMillis = scanStart,
                scanCompletedMillis = System.currentTimeMillis(),
                deferred = false
            )
            RovaLog.d { "RovaApp.runRecoveryScan: classified ${classifications.size} session(s)" }
        }
    }

    /**
     * Phase 1.7 commit-6 — production [ExportRecoveryRunner] wiring. The
     * runner consumes per-tier `recover()` seams; live `export()` wiring
     * is commit 7's territory and intentionally absent here. The
     * recovery seams (`finalizePendingRow`, `validatePending`,
     * `deletePendingRow` for Tier 1; `copyFile` / `renameFile` /
     * `deleteFile` defaults for Tier 2/3) cover the full set of platform
     * calls that `recover()` makes — the `mux` seam is wired to a
     * recovery-only sentinel that never gets called by the recovery code
     * path (see [PreQExportCore.recover] and [Tier1Exporter.recover]
     * shape).
     */
    private fun buildExportRecoveryRunner(): ExportRecoveryRunner {
        val mediaScanWaiter = AndroidMediaScanWaiter(this)

        // Recovery never invokes mux — see PreQExportCore.recover() and
        // Tier1Exporter.recover() shape. Live mux wiring lands in commit 7.
        val recoveryOnlyMux: suspend (List<File>, File) -> Unit = { _, _ ->
            error("RovaApp recovery wiring: live mux is commit-7 territory; recover() never calls this")
        }

        val tier2Exporter = Tier2Exporter(
            sessionStore = sessionStore,
            mediaScanWaiter = mediaScanWaiter,
            mux = recoveryOnlyMux
        )
        val tier3Exporter = Tier3Exporter(
            sessionStore = sessionStore,
            mediaScanWaiter = mediaScanWaiter,
            mux = recoveryOnlyMux
        )

        // Phase 6.1b T14: P+L manifests dispatch recovery PER SIDE
        // (tier × side). Single-mode (`Portrait` / `Landscape` /
        // anything else) falls through to the prior single-call recover
        // path — byte-identical to pre-T14.
        //
        // The per-side path inspects the manifest's per-side pointers
        // (`portraitPendingUri` / `landscapePendingUri` / `portrait*` /
        // `landscape*`) to decide whether each side has anything to
        // recover. If a side's pointers are all null, that side returns
        // `RecoveryResult.NoOp` semantics via [RecoveryResult.Abandoned]
        // (the per-side `abandon` skips the shared `setExportFailed`
        // write per T14's OQ-C lock).
        //
        // Side results are combined via [combineRecoveryResults] so the
        // runner sees one `RecoveryResult` for the session as a whole.
        // See the helper KDoc for the conservative precedence rules.
        fun tierRecover(m: SessionManifest, s: com.aritr.rova.service.dualrecord.VideoSide?):
            suspend () -> RecoveryResult = {
            when (m.exportTier) {
                ExportTier.TIER1_API29_PLUS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        buildTier1Exporter().recover(m, s)
                    } else {
                        RecoveryResult.RetryableFailure(
                            phase = "tier1-on-pre-q",
                            cause = IllegalStateException(
                                "TIER1 manifest on API ${Build.VERSION.SDK_INT}"
                            )
                        )
                    }
                }
                ExportTier.TIER2_API26_28 -> tier2Exporter.recover(m, s)
                ExportTier.TIER3_API24_25 -> tier3Exporter.recover(m, s)
                ExportTier.SAF_DESTINATION -> buildSafRecover(m, s)
            }
        }

        val recoverSession: suspend (SessionManifest) -> RecoveryResult = { m ->
            if (m.config.mode == "PortraitLandscape") {
                // Per-side dispatch. A side with no per-side pointers is
                // treated as a no-op via the per-side abandon's
                // shared-failed skip, surfaced here as Abandoned.
                // ADR-0024 — for a SAF P+L session the pending/private/public
                // pointers are never populated; the per-side SAF doc URI is
                // the work signal (a committed target → finalize/re-publish).
                val portraitHasWork = m.portraitPendingUri != null ||
                    m.portraitPrivateTempPath != null ||
                    m.portraitPublicTargetPath != null ||
                    m.portraitSafTargetDocUri != null
                val landscapeHasWork = m.landscapePendingUri != null ||
                    m.landscapePrivateTempPath != null ||
                    m.landscapePublicTargetPath != null ||
                    m.landscapeSafTargetDocUri != null
                val portraitResult = if (portraitHasWork) {
                    tierRecover(m, com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT)()
                } else {
                    RecoveryResult.Abandoned
                }
                val landscapeResult = if (landscapeHasWork) {
                    tierRecover(m, com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE)()
                } else {
                    RecoveryResult.Abandoned
                }
                combineRecoveryResults(portraitResult, landscapeResult)
            } else {
                tierRecover(m, null)()
            }
        }

        // Tier-specific artifact validator. Used by the late-terminal
        // reconciliation pass — only writes `markTerminated(COMPLETED)`
        // when the artifact this manifest claims is intact on disk /
        // MediaStore. Tier 1: validatePending(uri) probes the row via
        // MediaExtractor (works post-finalize too — IS_PENDING=0 doesn't
        // affect MediaExtractor.setDataSource(fd)). Tier 2/3: the public
        // file must exist with non-zero length.
        //
        // Phase 6.1b T19 final-review remediation: dispatch through
        // [TierArtifactValidator] so the P+L branch consults the
        // per-side pointers (the shared `pendingUri` / `publicTargetPath`
        // are null for P+L sessions per the OQ-C lock — pre-T19 always
        // returned `false` and stranded the manifest at `terminated = null`).
        val tier1Probe: (String) -> Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                { uri -> Tier1AndroidOps.validatePending(contentResolver, uri) }
            } else {
                { false }
            }
        // ADR-0024 — probe a SAF document URI for existence + non-zero
        // length (DocumentsProvider, not MediaStore — no SDK floor).
        val safProbe: (String) -> Boolean = { uri ->
            com.aritr.rova.service.export.SafAndroidOps.validateDocument(applicationContext, uri)
        }
        val validateTierArtifact: suspend (SessionManifest) -> Boolean = { m ->
            com.aritr.rova.service.export.TierArtifactValidator.isArtifactValid(m, tier1Probe = tier1Probe, safProbe = safProbe)
        }

        val orphanSweep: (suspend (Set<String>) -> OrphanSweepResult)? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                { uris -> buildTier1OrphanSweep().sweepTier1OrphanPendingRows(uris) }
            } else {
                null
            }

        // B5 / ADR-0025 — vault re-merge seam. A MERGE_TO_VAULT session
        // (vault-intent recording whose merge never finished) re-merges to
        // the app-private vault via `exportRecovered(vaultIntent = true)`.
        // The vault intent is threaded THROUGH `exportRecovered` (which
        // internally calls `export`); recovery never calls `export(`
        // directly, preserving `checkExportPipelineSingleEntry`. Routing
        // here — never through `recoverSession` (tier-recovery PUBLISHES) —
        // is what guarantees a vault recording is never gallery-published.
        val recoverVaultSession: suspend (SessionManifest) -> RecoveryResult = { m ->
            val dir = sessionStore.sessionDir(m.sessionId)
            val segments = m.segments
                .map { java.io.File(dir, it.filename) }
                .filter { it.exists() && it.length() > 0 }
            val result = com.aritr.rova.service.export.ExportPipeline.exportRecovered(
                context = applicationContext,
                sessionStore = sessionStore,
                sessionId = m.sessionId,
                sessionDir = dir,
                segments = segments,
                vaultIntent = true,
                onProgress = { },
            )
            // The runner consumes a RecoveryResult; map the ExportResult.
            // Success → Resumed (artifact intact); any failure → RetryableFailure
            // so cleanup gating leaves the dir for next-launch retry (a vault
            // merge that failed must not have its segments swept).
            when (result) {
                is ExportResult.Success -> RecoveryResult.Resumed(result)
                else -> RecoveryResult.RetryableFailure(
                    phase = "vault-remerge",
                    cause = IllegalStateException("vault re-merge did not succeed: $result")
                )
            }
        }

        // B5 / ADR-0025 (Task 22) — interrupted-move resume. Builds a
        // session-bound VaultMover via the same [VaultMoverBuilder] the UI
        // uses (DRY), then calls the crash-resume entry for the move's
        // direction. The private copy / intermediate state are already on
        // disk, so finishVaulting / finishUnvaulting re-run only the
        // destructive + terminal-commit half (both idempotent). Unlike the
        // UI move-out path, the resume seam deliberately does NOT delete the
        // now-redundant private vault file: ADR-0005 forbids deletion APIs in
        // the cold-launch recovery sources (checkRecoveryNoDeletion). The
        // orphaned app-private file is harmless (invisible, no longer
        // referenced by the PUBLIC manifest) and is reclaimed by the
        // user-initiated move-out cleanup or a later export-cleanup sweep.
        val resumeVaultMove: suspend (SessionManifest, com.aritr.rova.service.export.VaultRecoveryAction) -> Unit =
            { m, action ->
                if (!com.aritr.rova.service.export.VaultMoverBuilder.isSingleModeMovable(m)) {
                    RovaLog.w("RovaApp.resumeVaultMove: P+L session ${m.sessionId} not movable; skipping")
                } else {
                    val dir = sessionStore.sessionDir(m.sessionId)
                    when (action) {
                        com.aritr.rova.service.export.VaultRecoveryAction.RESUME_VAULTING -> {
                            val mover = com.aritr.rova.service.export.VaultMoverBuilder.buildMoveIn(
                                context = applicationContext,
                                sessionStore = sessionStore,
                                manifest = m,
                                sessionDir = dir,
                            )
                            mover.finishVaulting(m.sessionId)
                        }
                        com.aritr.rova.service.export.VaultRecoveryAction.RESUME_UNVAULTING -> {
                            val outcomeHolder =
                                arrayOfNulls<com.aritr.rova.service.export.VaultAndroidOps.PublishOutcome>(1)
                            val mover = com.aritr.rova.service.export.VaultMoverBuilder.buildMoveOut(
                                context = applicationContext,
                                sessionStore = sessionStore,
                                manifest = m,
                                sessionDir = dir,
                                outcomeHolder = outcomeHolder,
                            )
                            mover.finishUnvaulting(m.sessionId)
                            // No private-file delete here — see the seam KDoc
                            // (ADR-0005 checkRecoveryNoDeletion).
                        }
                        else -> {
                            // Only RESUME_* actions reach this seam.
                        }
                    }
                }
            }

        return ExportRecoveryRunner(
            sessionStore = sessionStore,
            recoverSession = recoverSession,
            validateTierArtifact = validateTierArtifact,
            orphanSweep = orphanSweep,
            recoverVaultSession = recoverVaultSession,
            resumeVaultMove = resumeVaultMove
        )
    }

    /**
     * ADR-0024 — SAF recovery dispatch. Delegates the actual re-mux /
     * re-publish to [com.aritr.rova.service.export.SafRecoverBuilder] (which
     * owns the lone `VideoMerger.mergeSegments` recovery call — kept under
     * `service/export/` per the `checkExportPipelineSingleEntry` invariant).
     * RovaApp only resolves the persisted tree URI + session directory; a
     * cleared/absent grant short-circuits to a retryable failure so the next
     * cold launch can re-attempt once the user re-grants.
     */
    private suspend fun buildSafRecover(
        m: SessionManifest,
        s: com.aritr.rova.service.dualrecord.VideoSide?
    ): RecoveryResult {
        val treeUri = RovaSettings(this).saveLocationTreeUri
            ?: return RecoveryResult.RetryableFailure(
                phase = "saf-no-tree",
                cause = IllegalStateException("SAF manifest but no saved tree uri")
            )
        return com.aritr.rova.service.export.SafRecoverBuilder.recover(
            context = applicationContext,
            sessionStore = sessionStore,
            manifest = m,
            side = s,
            treeUri = treeUri,
            sessionDir = sessionStore.sessionDir(m.sessionId)
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildTier1Exporter(): Tier1Exporter {
        val resolver = contentResolver
        return Tier1Exporter(
            sessionStore = sessionStore,
            insertPendingRow = { _ -> error("recovery wiring: insertPendingRow lands in commit 7") },
            withPendingFd = { _, _, _ -> error("recovery wiring: withPendingFd lands in commit 7") },
            mux = { _, _ -> error("recovery wiring: mux lands in commit 7") },
            finalizePendingRow = { uri -> Tier1AndroidOps.finalizePendingRow(resolver, uri) },
            deletePendingRow = { uri -> Tier1AndroidOps.deletePendingRow(resolver, uri) },
            validatePending = { uri -> Tier1AndroidOps.validatePending(resolver, uri) }
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildTier1OrphanSweep(): Tier1OrphanSweep {
        val resolver = contentResolver
        val pkg = packageName
        return Tier1OrphanSweep(
            ourPackageName = pkg,
            listVisiblePendingRows = {
                Tier1AndroidSweepOps.listVisiblePendingRows(resolver, pkg)
            },
            deletePendingRow = { uri ->
                Tier1AndroidSweepOps.deletePendingRow(resolver, uri)
            }
        )
    }

    /**
     * M5 §5 — notification "Stop" / "Stop Early" actions route through here.
     * Reuses the existing user-stop broadcast pipeline that the in-app Stop
     * FAB takes. If no session is currently recording, the broadcast is a
     * no-op (RovaStopReceiver checks active state).
     */
    fun requestUserStopIfRunning(context: android.content.Context) {
        val intent = android.content.Intent().apply {
            action = com.aritr.rova.service.RovaStopReceiver.ACTION_STOP
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    /**
     * M5 §5 — notification "Share" action routes through here. Resolves
     * the recorded artifact via the same tier-dispatch
     * [com.aritr.rova.ui.screens.HistoryArtifactMapper] uses for the
     * in-app share button, then hands off to the system share chooser.
     *
     * Tier 1: [com.aritr.rova.data.SessionManifest.pendingUri] is the
     * MediaStore content URI; passed directly as the share URI.
     * Tier 2/3: [com.aritr.rova.data.SessionManifest.publicTargetPath]
     * is the on-disk path; a minimal ContentResolver query resolves it
     * to a content URI, falling back to FileProvider if not indexed.
     *
     * No-op on missing manifest, unresolvable artifact, or null share URI.
     */
    fun shareRecording(activity: android.app.Activity, sessionId: String) {
        val manifest = sessionStore.loadManifest(sessionId) ?: return
        val file = com.aritr.rova.ui.screens.HistoryArtifactMapper.resolveArtifactFile(manifest) { uri ->
            // Tier 1 _DATA resolution: query MediaStore for the path owned
            // by this app's export row. Returns null if the row is gone.
            try {
                val contentUri = android.net.Uri.parse(uri)
                @Suppress("DEPRECATION")
                activity.contentResolver.query(
                    contentUri,
                    arrayOf(android.provider.MediaStore.Video.Media.DATA),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val path = c.getString(0)
                        if (path.isNullOrEmpty()) null else java.io.File(path)
                    } else null
                }
            } catch (_: Throwable) { null }
        } ?: return
        val shareUriString = com.aritr.rova.ui.screens.HistoryArtifactMapper.resolveShareUri(manifest)
        val shareUri: android.net.Uri? = shareUriString?.let { android.net.Uri.parse(it) }
            ?: try {
                androidx.core.content.FileProvider.getUriForFile(
                    activity, "${activity.packageName}.provider", file
                )
            } catch (_: IllegalArgumentException) { null }
        shareUri ?: return
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(android.content.Intent.EXTRA_STREAM, shareUri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(android.content.Intent.createChooser(send, null))
    }

    /**
     * ADR-0027 — invoked from [MainActivity] when the user taps the
     * window-open notification. Starts the camera FGS via the normal Start flow
     * (the Activity is foregrounded by the tap, so the one legal camera-start
     * site / isAppVisible gate is satisfied), tagging the session as
     * schedule-started with its window-end so the service self-heals at close.
     * Reads the same recording defaults the manual Start flow uses.
     */
    fun startScheduledRecording(activityContext: Context) {
        val s = RovaSettings(this)
        // Only start if we are genuinely inside an open, enabled window. A late
        // or stale tap (window already closed, schedule disabled/changed) yields
        // null — ignore it, so we never start a session whose self-heal end is
        // unknown (would record with no auto-stop backstop). [Finding 1/codex#3]
        val windowEnd = com.aritr.rova.service.scheduler.ScheduleArmer.currentWindowEnd(
            System.currentTimeMillis(), java.util.TimeZone.getDefault(), s.scheduleSnapshot(),
        )
        if (windowEnd == null) {
            RovaLog.d { "startScheduledRecording: tap outside an open window; ignoring" }
            return
        }
        // Don't clobber a session already recording (e.g. a manual start still
        // running). The service-side register-collision would otherwise abort
        // the new session anyway; bail cleanly here. [Finding 3]
        if (com.aritr.rova.service.ServiceController.current() != null) {
            RovaLog.d { "startScheduledRecording: a session is already live; ignoring" }
            return
        }
        com.aritr.rova.service.RovaRecordingService.start(
            context = activityContext,
            nSeconds = s.durationSeconds.toFloat(),
            mMinutes = s.intervalMinutes.toFloat(),
            limitLoops = s.loopCount,
            resolution = s.resolution,
            startedBySchedule = true,
            scheduleWindowEndMillis = windowEnd,
        )
    }

    /**
     * ADR-0027 — silent window-close. Forwards a cooperative stop with
     * reason=SCHEDULE_WINDOW to the live in-process controller (no getService,
     * no new stop pathway). If the process is dead there is nothing recording
     * to stop; cold-launch recovery classifies from evidence (ADR-0027 §6).
     */
    fun requestScheduleWindowStop() {
        val controller = com.aritr.rova.service.ServiceController.current()
        if (controller != null) {
            controller.requestStop(com.aritr.rova.data.StopReason.SCHEDULE_WINDOW)
        } else {
            RovaLog.d { "requestScheduleWindowStop: no live controller; nothing to stop" }
        }
    }

    companion object {
        @Volatile
        private var instance: RovaApp? = null

        /**
         * Convenience access. Receivers can resolve via `context.applicationContext as RovaApp`,
         * but this is one indirection less for callers that have no context.
         */
        fun get(): RovaApp = instance
            ?: throw IllegalStateException("RovaApp.get() before Application.onCreate()")

        /**
         * Phase 1.4 (ADR 0006 B21). The single canonical resolution of
         * the external app-private storage root. Both the storage
         * preflight (Phase 2) and `SessionStore` derive from this exact
         * call. Returns `null` when external storage is mounted
         * read-only or unavailable.
         *
         * Static check `checkExternalRootShared` enforces that no other
         * production source file references `getExternalFilesDir(null)`.
         */
        fun resolveExternalRoot(context: Context): File? =
            context.getExternalFilesDir(null)

        /**
         * ADR 0005 §"Concurrency Invariants" item 4 — drain timeout
         * (8 s receiver work budget + 2 s margin).
         */
        private const val DRAIN_TIMEOUT_MILLIS = 10_000L
        private const val DRAIN_POLL_MILLIS = 100L
    }
}
