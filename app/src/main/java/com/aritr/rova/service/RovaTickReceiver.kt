package com.aritr.rova.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aritr.rova.RovaApp
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.utils.RovaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 1.2 alarm tick receiver.
 *
 * Lifecycle (Codex v2 §3 fix):
 * 1. [onReceive] calls [goAsync] immediately to extend the broadcast window
 *    beyond the synchronous return.
 * 2. Work runs on [RovaApp.appScope] inside a [withTimeoutOrNull] of 8s
 *    (well under the 10s ANR ceiling for goAsync — leaves headroom for the
 *    `finish()` call itself).
 * 3. `pendingResult.finish()` is in a `finally` block so a thrown exception,
 *    cancelled coroutine, or timeout all release the broadcast slot. Without
 *    this, the OS can ANR-kill the app on the next broadcast.
 *
 * Decision logic:
 * 1. Validate EXTRA_SESSION_ID + EXTRA_TICK_KIND (drop malformed broadcasts).
 * 2. Load manifest. Missing dir = recovery target, drop tick.
 * 3. If `terminated != null || stopRequested`, drop tick (idempotent —
 *    a stale alarm fired after stop must not undo state).
 * 4. ServiceController.current() snapshot:
 *    - same sessionId: forward `postTick(seq, kind)`. Service handles
 *      ordering and the WAKE-vs-WATCHDOG behavior switch.
 *    - null or different sessionId: process is dead; CANNOT start FGS from
 *      background (Android 12+). Write `markTerminated(KILLED_BY_SYSTEM)`
 *      and bail. Recovery (Phase 1.5) will surface the session in the UI.
 *      WATCHDOG is the only path that detects in-recording death — without
 *      it, the kill is invisible until cold-launch recovery.
 *
 * Why not `startForegroundService()` here: Android 12+ throws
 * `ForegroundServiceStartNotAllowedException` for FGS starts from background-
 * only contexts. ADR scope and ROADMAP_v6.md §3 forbid this path.
 */
class RovaTickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TICK) {
            RovaLog.w("RovaTickReceiver: ignoring unknown action=${intent.action}")
            return
        }
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val seq = intent.getIntExtra(EXTRA_TICK_SEQ, -1)
        val kindName = intent.getStringExtra(EXTRA_TICK_KIND)
        if (sessionId.isNullOrEmpty()) {
            RovaLog.w("RovaTickReceiver: missing EXTRA_SESSION_ID; dropping")
            return
        }
        val kind = kindName?.let { runCatching { TickKind.valueOf(it) }.getOrNull() }
        if (kind == null) {
            RovaLog.w("RovaTickReceiver: missing/invalid EXTRA_TICK_KIND='$kindName' for $sessionId; dropping")
            return
        }

        // ADR 0005 Guard B — synchronous increment in onReceive BEFORE
        // goAsync(). The recovery scan blocks on activeReceiverWork == 0
        // before classifying any session; if the increment ran inside the
        // launched coroutine, a UI scan could acquire startupMutex between
        // goAsync() and the launched body's first instruction, racing this
        // receiver's terminal write. Decrement runs in the launched
        // coroutine's finally AFTER pendingResult.finish() so the slot is
        // released first.
        val app = context.applicationContext as RovaApp
        app.activeReceiverWork.incrementAndGet()
        val pendingResult = goAsync()
        app.appScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(WORK_TIMEOUT_MS) {
                    handleTick(app, sessionId, seq, kind)
                } ?: RovaLog.w("RovaTickReceiver: work timed out for $sessionId/$kind")
            } catch (t: Throwable) {
                RovaLog.e("RovaTickReceiver: tick handling failed for $sessionId/$kind", t)
            } finally {
                // MUST run on every exit path. Without finish(), the OS ANR-kills
                // the app on the next broadcast — see goAsync contract.
                pendingResult.finish()
                app.activeReceiverWork.decrementAndGet()
            }
        }
    }

    private suspend fun handleTick(app: RovaApp, sessionId: String, seq: Int, kind: TickKind) {
        val manifest = app.sessionStore.loadManifest(sessionId)
        if (manifest == null) {
            RovaLog.w("RovaTickReceiver: unknown session $sessionId; dropping")
            return
        }
        if (manifest.terminated != null || manifest.stopRequested) {
            RovaLog.d {
                "RovaTickReceiver: $sessionId/$kind already terminated=${manifest.terminated}" +
                    " stopRequested=${manifest.stopRequested}; dropping seq=$seq"
            }
            return
        }

        val controller = ServiceController.current()
        if (controller != null && controller.sessionId == sessionId) {
            RovaLog.d { "RovaTickReceiver: forwarding seq=$seq kind=$kind to controller for $sessionId" }
            controller.postTick(seq, kind)
            return
        }

        // No live controller for this session. Process was killed — either
        // between segments (WAKE) or while a segment was recording (WATCHDOG).
        // Watchdog is the only path that detects in-recording death; without
        // it the kill would not be observed until cold-launch recovery.
        RovaLog.w(
            "RovaTickReceiver: no controller for $sessionId/$kind" +
                " (current=${controller?.sessionId}); writing KILLED_BY_SYSTEM"
        )
        // ADR 0006 §"Migration table" — KILLED_* writers pass StopReason.NONE.
        app.sessionStore.markTerminated(sessionId, Terminated.KILLED_BY_SYSTEM, StopReason.NONE)
    }

    companion object {
        const val ACTION_TICK = "com.aritr.rova.action.TICK"
        const val EXTRA_SESSION_ID = "rova.tick.sessionId"
        const val EXTRA_TICK_SEQ = "rova.tick.seq"
        const val EXTRA_TICK_KIND = "rova.tick.kind"
        const val URI_SCHEME = "rova"
        const val URI_HOST_TICK = "tick"

        /**
         * Bound on goAsync work. 8s leaves 2s margin under the 10s broadcast
         * ANR threshold so `finish()` itself is never the slow path.
         */
        private const val WORK_TIMEOUT_MS = 8_000L
    }
}
