package com.aritr.rova

import android.app.Application
import android.content.Context
import com.aritr.rova.data.SessionStore
import com.aritr.rova.service.recovery.RecoveryReport
import com.aritr.rova.service.recovery.RecoveryScanner
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

            val scanner = RecoveryScanner(sessionStore)
            val classifications = scanner.classifyAll(scanStart)
            _recoveryReport.value = RecoveryReport(
                classifications = classifications,
                scanStartMillis = scanStart,
                scanCompletedMillis = System.currentTimeMillis(),
                deferred = false
            )
            RovaLog.d("RovaApp.runRecoveryScan: classified ${classifications.size} session(s)")
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
