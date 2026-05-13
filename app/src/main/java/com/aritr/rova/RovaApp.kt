package com.aritr.rova

import android.app.AlarmManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.RequiresApi
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.service.export.AndroidMediaScanWaiter
import com.aritr.rova.service.export.ExportCleanupPredicate
import com.aritr.rova.service.export.ExportRecoveryReport
import com.aritr.rova.service.export.ExportRecoveryRunner
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
import com.aritr.rova.ui.signals.BatteryOptimizationSignal
import com.aritr.rova.ui.signals.CameraPermissionSignal
import com.aritr.rova.ui.signals.CameraStateSignal
import com.aritr.rova.ui.signals.ExactAlarmSignal
import com.aritr.rova.ui.signals.MicrophonePermissionSignal
import com.aritr.rova.ui.signals.NotificationPermissionSignal
import com.aritr.rova.ui.signals.PowerSignal
import com.aritr.rova.ui.signals.StorageLowMidRecSignal
import com.aritr.rova.ui.signals.StorageSignal
import com.aritr.rova.ui.signals.ThermalStatusSignal
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
     * R2 (NEW_UI_BACKEND_REPLAN row 17) — mid-recording free-space advisory.
     * Drives STORAGE_LOW_MID_REC. The host (RecordScreen, T9) calls poll()
     * every ~30 s while in an active HUD state and clear() on transition to Idle.
     */
    val storageLowMidRecSignal: StorageLowMidRecSignal by lazy {
        StorageLowMidRecSignal.forContext(this)
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        // RovaCrashReporter.setBackend(FirebaseCrashlyticsBackend()) — wired Phase 4.
        // NOTE: Phase 1.5 recovery is NOT triggered here. See class KDoc.
        registerExactAlarmStateReceiverIfSupported()
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
            RovaLog.d(
                "RovaApp.runRecoveryScan: export recovery complete " +
                    "(perSession=${exportReport.perSession.size}, " +
                    "lateTerminals=${exportReport.lateTerminals.size}, " +
                    "sweep=${exportReport.sweep.javaClass.simpleName})"
            )

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
                RovaLog.d("RovaApp.runRecoveryScan: cleanup pass discarded ${deleted.size} session(s)")
            }

            _recoveryReport.value = RecoveryReport(
                classifications = classifications,
                scanStartMillis = scanStart,
                scanCompletedMillis = System.currentTimeMillis(),
                deferred = false
            )
            RovaLog.d("RovaApp.runRecoveryScan: classified ${classifications.size} session(s)")
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

        val recoverSession: suspend (SessionManifest) -> RecoveryResult = { m ->
            when (m.exportTier) {
                ExportTier.TIER1_API29_PLUS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        buildTier1Exporter().recover(m)
                    } else {
                        // Defensive only — a TIER1 manifest can only exist on
                        // an API 29+ device, and SDK_INT does not regress for
                        // a device. Defer rather than crash if the impossible
                        // happens (e.g., manifest copied across devices).
                        RecoveryResult.RetryableFailure(
                            phase = "tier1-on-pre-q",
                            cause = IllegalStateException(
                                "TIER1 manifest on API ${Build.VERSION.SDK_INT}"
                            )
                        )
                    }
                }
                ExportTier.TIER2_API26_28 -> tier2Exporter.recover(m)
                ExportTier.TIER3_API24_25 -> tier3Exporter.recover(m)
            }
        }

        // Tier-specific artifact validator. Used by the late-terminal
        // reconciliation pass — only writes `markTerminated(COMPLETED)`
        // when the artifact this manifest claims is intact on disk /
        // MediaStore. Tier 1: validatePending(uri) probes the row via
        // MediaExtractor (works post-finalize too — IS_PENDING=0 doesn't
        // affect MediaExtractor.setDataSource(fd)). Tier 2/3: the public
        // file must exist with non-zero length.
        val validateTierArtifact: suspend (SessionManifest) -> Boolean = { m ->
            when (m.exportTier) {
                ExportTier.TIER1_API29_PLUS -> {
                    val uri = m.pendingUri
                    if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Tier1AndroidOps.validatePending(contentResolver, uri)
                    } else {
                        false
                    }
                }
                ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 -> {
                    val path = m.publicTargetPath
                    if (path != null) {
                        val f = File(path)
                        f.exists() && f.length() > 0L
                    } else {
                        false
                    }
                }
            }
        }

        val orphanSweep: (suspend (Set<String>) -> OrphanSweepResult)? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                { uris -> buildTier1OrphanSweep().sweepTier1OrphanPendingRows(uris) }
            } else {
                null
            }

        return ExportRecoveryRunner(
            sessionStore = sessionStore,
            recoverSession = recoverSession,
            validateTierArtifact = validateTierArtifact,
            orphanSweep = orphanSweep
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
