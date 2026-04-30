package com.aritr.rova.service.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.aritr.rova.service.RovaTickReceiver
import com.aritr.rova.service.TickKind
import com.aritr.rova.utils.RovaLog

/**
 * Phase 1.2 stateless tick scheduler. Owns no in-memory state — every
 * `arm`/`cancel` reconstructs the same [PendingIntent] shape, so behavior
 * is deterministic across process death.
 *
 * **Identity contract** (Codex v3 fix, extended for kinds in v4):
 * `PendingIntent` equality is `(action, component, data, type, categories,
 * requestCode)` — extras EXCLUDED. We carry session identity AND tick kind
 * in `Intent.data = rova://tick/$sessionId/$kind` (kind ∈ {WAKE, WATCHDOG}).
 * `requestCode` is fixed at 0; data does the disambiguation. Two sessions
 * cannot collide because their data URIs differ; within a session, WAKE
 * and WATCHDOG occupy independent slots so a watchdog cancel never wipes
 * a pending inter-segment wake (or vice versa).
 *
 * **Why not `getService`:** Android 12+ forbids starting foreground services
 * from background-only contexts (alarms qualify). This scheduler targets a
 * `BroadcastReceiver` ([RovaTickReceiver]); the receiver consults the
 * in-process [com.aritr.rova.service.ServiceController] registry instead of
 * starting the service. The CI lint rule (ROADMAP_v6.md §"Lint / CI Rules
 * Summary") forbids `PendingIntent.getService` in this file.
 *
 * **Exact-vs-inexact gate** (ADR 0001):
 * Each call re-checks `AlarmManager.canScheduleExactAlarms()` because the
 * permission is user-revocable any time. Manifest-only checks would lie.
 */
object AlarmScheduler {

    /**
     * Arm the next tick for ([sessionId], [kind]). If a prior `arm` for the
     * same (sessionId, kind) is still pending, `FLAG_UPDATE_CURRENT`
     * replaces its extras (seq, etc.) — the OS-side equality match
     * guarantees we update the same slot rather than enqueueing a duplicate.
     * Different [kind] values for the same sessionId are independent slots.
     *
     * **Exact gate (Codex v4 fix):**
     * - API < 31: `setExactAndAllowWhileIdle` is freely available since
     *   API 23 — no permission gate. Use exact unconditionally.
     * - API >= 31: must consult `canScheduleExactAlarms()`. Permission is
     *   user/OS revocable, so we also wrap the exact call in
     *   `try/catch SecurityException` because the check-to-call window is a
     *   TOCTOU race; on SecurityException we downgrade to inexact in-call.
     */
    fun arm(context: Context, sessionId: String, seq: Int, triggerAtMillis: Long, kind: TickKind) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // arm path uses FLAG_UPDATE_CURRENT — always returns non-null. The
        // !! is safe by Android contract (getBroadcast only returns null
        // with FLAG_NO_CREATE when no slot exists).
        val pi = buildPendingIntent(context, sessionId, seq, kind, mutateExisting = true)
            ?: error("AlarmScheduler.arm: getBroadcast returned null for $sessionId/$kind — should be impossible with FLAG_UPDATE_CURRENT")

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            am.canScheduleExactAlarms()

        if (canExact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                RovaLog.d("AlarmScheduler.arm($sessionId/$kind, seq=$seq, t=$triggerAtMillis) [exact]")
                return
            } catch (se: SecurityException) {
                // TOCTOU: permission revoked between canScheduleExactAlarms()
                // and setExactAndAllowWhileIdle. Fall through to inexact.
                RovaLog.w("AlarmScheduler.arm($sessionId/$kind): exact denied at call site, downgrading to inexact", se)
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        RovaLog.d("AlarmScheduler.arm($sessionId/$kind, seq=$seq, t=$triggerAtMillis) [inexact]")
    }

    /**
     * Cancel a specific kind of pending tick for [sessionId]. Reconstructs
     * the same action+component+data shape with `FLAG_NO_CREATE` so cancel
     * finds the existing slot; if no slot exists, returns silently.
     */
    fun cancel(context: Context, sessionId: String, kind: TickKind) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, sessionId, seq = 0, kind = kind, mutateExisting = false)
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
            RovaLog.d("AlarmScheduler.cancel($sessionId/$kind): pending slot cleared")
        } else {
            RovaLog.d("AlarmScheduler.cancel($sessionId/$kind): no pending slot")
        }
    }

    /**
     * Cancel ALL pending ticks for [sessionId] across every [TickKind].
     * Used by stop-path teardown — at stop the service does not know nor
     * care which kinds were armed; it wants the session's alarm footprint
     * to be zero.
     */
    fun cancelForSession(context: Context, sessionId: String) {
        TickKind.values().forEach { cancel(context, sessionId, it) }
    }

    /**
     * Best-effort cleanup on a list of session ids. Caller resolves the list
     * from [com.aritr.rova.data.SessionStore.listSessionIds]; this method
     * itself does no I/O and is safe to call from main, but the caller
     * should batch list-resolution off main.
     */
    fun cancelAll(context: Context, sessionIds: List<String>) {
        sessionIds.forEach { cancelForSession(context, it) }
    }

    /**
     * Build the canonical PendingIntent for ([sessionId], seq).
     *
     * @param mutateExisting true for `arm` (FLAG_UPDATE_CURRENT replaces
     *   extras); false for `cancel` (FLAG_NO_CREATE — returns null if no
     *   matching slot exists, which is the signal to skip cancel).
     */
    private fun buildPendingIntent(
        context: Context,
        sessionId: String,
        seq: Int,
        kind: TickKind,
        mutateExisting: Boolean
    ): PendingIntent? {
        val intent = Intent(context, RovaTickReceiver::class.java).apply {
            action = RovaTickReceiver.ACTION_TICK
            // Identity primitive. PendingIntent equality matches on data,
            // not on extras. (sessionId, kind) uniqueness lives here.
            data = Uri.Builder()
                .scheme(RovaTickReceiver.URI_SCHEME)
                .authority(RovaTickReceiver.URI_HOST_TICK)
                .appendPath(sessionId)
                .appendPath(kind.name)
                .build()
            putExtra(RovaTickReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(RovaTickReceiver.EXTRA_TICK_SEQ, seq)
            putExtra(RovaTickReceiver.EXTRA_TICK_KIND, kind.name)
        }
        val flags = if (mutateExisting) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        }
        // CI lint forbids PendingIntent.getService here — must be getBroadcast.
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /**
     * Constant request code: identity disambiguation lives in `Intent.data`,
     * not in requestCode. Using a constant avoids the
     * `sessionId.hashCode()` collision class that Codex v3 flagged.
     */
    private const val REQUEST_CODE = 0
}
