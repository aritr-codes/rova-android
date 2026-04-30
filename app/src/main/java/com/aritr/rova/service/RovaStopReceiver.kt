package com.aritr.rova.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aritr.rova.RovaApp
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.scheduler.AlarmScheduler
import com.aritr.rova.utils.RovaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 1.3 cooperative-stop receiver.
 *
 * Lifecycle (mirrors [RovaTickReceiver]; Codex v2 §3 fix):
 * 1. [onReceive] calls [goAsync] before returning so work survives the
 *    synchronous return.
 * 2. Work runs on [RovaApp.appScope] inside [withTimeoutOrNull] of 8s,
 *    well under the 10s broadcast ANR ceiling — `finish()` itself never
 *    becomes the slow path.
 * 3. `pendingResult.finish()` runs in `finally` so an exception, timeout,
 *    or normal completion all release the broadcast slot. Without this,
 *    the OS can ANR-kill the app on the next broadcast.
 *
 * Decision logic (ROADMAP_v4 §1.3 — registry-first, explicit cold-process
 * acceptance):
 *
 * 1. Validate `EXTRA_SESSION_ID`. Empty/missing → drop. Senders MUST
 *    resolve a real sessionId before sending: notification builds the PI
 *    only after [RovaRecordingService.currentSessionId] is non-null; UI
 *    resolves via [ServiceController.current]. The receiver guesses
 *    nothing — pre-session stops are senders' responsibility.
 * 2. Load manifest. Missing dir → drop (no session to stop; recovery
 *    target).
 * 3. If `terminated != null` → drop. Idempotency on
 *    [com.aritr.rova.data.SessionStore.markTerminated] also protects
 *    later writes, but bailing early avoids unnecessary alarm cancels.
 * 4. Persist `stopRequested = true`. Idempotent. Latches a ground-truth
 *    "user asked to stop" so any racing tick that reaches [RovaTickReceiver]
 *    after us drops on the `terminated != null || stopRequested` gate.
 *    This MUST happen before any later step — even if the controller call
 *    or the registry-dead write fails, the latch keeps future ticks no-op.
 * 5. Cancel pending alarms for this session. Idempotent — covers the
 *    in-flight WAKE wait and any armed WATCHDOG.
 * 6. Snapshot [ServiceController.current]:
 *    - matches sessionId → in-process cooperative stop via
 *      [RovaController.requestStop]. The service drives merge and writes
 *      [Terminated.USER_STOPPED] on merge success.
 *    - null or mismatched → process is dead (force-stop case from v4
 *      §1.3 acceptance "STOP from notification while service is dead").
 *      Write [Terminated.USER_STOPPED] so next launch surfaces the right
 *      outcome. [com.aritr.rova.data.SessionStore.markTerminated] is
 *      first-writer-wins, so an earlier KILLED_BY_SYSTEM tick survives —
 *      diagnostic accuracy preserved when the process actually died first.
 *
 * Why not `startService` / `startForegroundService` here: ROADMAP_v4 §1.3
 * forbids it. Android 12+ throws `ForegroundServiceStartNotAllowedException`
 * on FGS starts from broadcast contexts. CI lint
 * (`checkStopNoGetService`, `checkSchedulerNoGetService`) enforces.
 */
class RovaStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) {
            RovaLog.w("RovaStopReceiver: ignoring unknown action=${intent.action}")
            return
        }
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId.isNullOrEmpty()) {
            RovaLog.w("RovaStopReceiver: missing EXTRA_SESSION_ID; dropping")
            return
        }

        // ADR 0005 Guard B — synchronous increment in onReceive BEFORE
        // goAsync(). The recovery scan blocks on activeReceiverWork == 0
        // before classifying any session; if the increment ran inside the
        // launched coroutine, a UI scan could acquire startupMutex between
        // goAsync() and the launched body's first instruction, racing this
        // receiver's USER_STOPPED write on the registry-dead branch.
        // Decrement runs in the launched coroutine's finally AFTER
        // pendingResult.finish() so the slot is released first.
        val app = context.applicationContext as RovaApp
        app.activeReceiverWork.incrementAndGet()
        val pendingResult = goAsync()
        app.appScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(WORK_TIMEOUT_MS) {
                    handleStop(context, app, sessionId)
                } ?: RovaLog.w("RovaStopReceiver: work timed out for $sessionId")
            } catch (t: Throwable) {
                RovaLog.e("RovaStopReceiver: stop handling failed for $sessionId", t)
            } finally {
                // MUST run on every exit path. Without finish(), the OS
                // ANR-kills the app on the next broadcast — see goAsync
                // contract.
                pendingResult.finish()
                app.activeReceiverWork.decrementAndGet()
            }
        }
    }

    private suspend fun handleStop(context: Context, app: RovaApp, sessionId: String) {
        val manifest = app.sessionStore.loadManifest(sessionId)
        if (manifest == null) {
            RovaLog.w("RovaStopReceiver: unknown session $sessionId; dropping")
            return
        }
        if (manifest.terminated != null) {
            RovaLog.d(
                "RovaStopReceiver: $sessionId already terminated=${manifest.terminated};" +
                    " dropping"
            )
            return
        }

        // Step 4 — persist stopRequested=true FIRST. Idempotent. Even if
        // every subsequent step fails, this latch turns later ticks into
        // no-ops via the receiver-side `terminated != null || stopRequested`
        // gate. Ordering matters: this is the only step that survives
        // process death of the receiver itself between the goAsync timeout
        // and finish().
        try {
            app.sessionStore.setStopRequested(sessionId)
        } catch (e: Exception) {
            RovaLog.e("RovaStopReceiver: setStopRequested failed for $sessionId", e)
            // Continue anyway — the in-process controller path can still
            // succeed and the registry-dead branch can still write
            // USER_STOPPED.
        }

        // Step 5 — cancel pending alarms for this session. Idempotent;
        // covers both the in-flight WAKE wait and any armed WATCHDOG.
        // Best-effort: if cancel fails, a later tick will fire and drop on
        // the manifest gate written in step 4.
        try {
            AlarmScheduler.cancelForSession(context, sessionId)
        } catch (e: Exception) {
            RovaLog.w("RovaStopReceiver: cancelForSession failed for $sessionId", e)
        }

        // Step 6 — registry-first delivery.
        val controller = ServiceController.current()
        if (controller != null && controller.sessionId == sessionId) {
            RovaLog.d("RovaStopReceiver: forwarding stop to live controller for $sessionId")
            controller.requestStop()
            return
        }

        // Registry-dead branch (v4 §1.3 force-stop acceptance): no
        // controller for this session. Write USER_STOPPED so the next
        // launch shows the right outcome. markTerminated is
        // first-writer-wins — an earlier KILLED_BY_SYSTEM survives intact.
        RovaLog.w(
            "RovaStopReceiver: no controller for $sessionId" +
                " (current=${controller?.sessionId}); writing USER_STOPPED"
        )
        // ADR 0006 §"Migration table" — RovaStopReceiver writes USER_STOPPED
        // because the user pressed STOP. Both the registry-live and
        // registry-dead branches carry StopReason.USER. The 3-arg form
        // returns a MarkTerminatedResult; we log the path but cannot do
        // much more from a receiver context.
        try {
            when (val result = app.sessionStore.markTerminated(
                sessionId,
                Terminated.USER_STOPPED,
                StopReason.USER
            )) {
                is com.aritr.rova.data.MarkTerminatedResult.Wrote -> {
                    RovaLog.d("RovaStopReceiver: markTerminated wrote USER_STOPPED for $sessionId")
                }
                is com.aritr.rova.data.MarkTerminatedResult.AlreadyTerminal -> {
                    RovaLog.d(
                        "RovaStopReceiver: $sessionId already" +
                            " ${result.existingTerminated}/${result.existingStopReason}; suppressed"
                    )
                }
                is com.aritr.rova.data.MarkTerminatedResult.Failed -> {
                    RovaLog.e(
                        "RovaStopReceiver: markTerminated FAILED for $sessionId" +
                            " (attempts=${result.attempts})", result.cause
                    )
                }
            }
        } catch (e: Exception) {
            RovaLog.e("RovaStopReceiver: markTerminated(USER_STOPPED) threw for $sessionId", e)
        }
    }

    companion object {
        const val ACTION_STOP = "com.aritr.rova.action.STOP"
        const val EXTRA_SESSION_ID = "rova.stop.sessionId"
        const val URI_SCHEME = "rova"
        const val URI_HOST_STOP = "stop"

        /**
         * Bound on goAsync work. 8s leaves 2s margin under the 10s
         * broadcast ANR threshold so `finish()` itself is never the slow
         * path.
         */
        private const val WORK_TIMEOUT_MS = 8_000L
    }
}
