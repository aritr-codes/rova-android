package com.aritr.rova.service

import com.aritr.rova.utils.RovaLog
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 1.2 process-singleton registry for the live [RovaController].
 *
 * **Why a registry, not a global service-start:** Android 12+ restricts
 * background service starts from receivers. The pattern Phase 1.2 adopts is:
 * the service starts itself foregrounded once (from the user's tap), then
 * registers with this object. Alarm ticks and stop receivers consult the
 * registry — if it's null, the process is dead and a fresh start would
 * violate FGS-from-background rules. Phase 1.5 recovery handles those cases
 * out-of-band.
 *
 * **Why CAS, not just set:** the register/unregister pair must be exclusive.
 * If a stale onDestroy fires unregister against a fresh onCreate's
 * controller, we'd lose the registration. CAS-on-unregister keyed by the
 * exact reference prevents that.
 */
object ServiceController {
    private val ref = AtomicReference<RovaController?>(null)

    /**
     * Register a controller. Returns true on success, false if another
     * controller is already registered (which is a programming error — the
     * service is meant to be a singleton).
     */
    fun register(controller: RovaController): Boolean {
        val ok = ref.compareAndSet(null, controller)
        if (!ok) {
            RovaLog.w(
                "ServiceController.register: already registered with " +
                    "sessionId=${ref.get()?.sessionId}; refusing ${controller.sessionId}"
            )
        } else {
            RovaLog.d("ServiceController.register: ${controller.sessionId}")
        }
        return ok
    }

    /**
     * Unregister a controller. Only succeeds if [controller] is the currently
     * registered reference — protects against a stale onDestroy nulling out
     * a fresh onCreate's registration.
     */
    fun unregister(controller: RovaController) {
        val ok = ref.compareAndSet(controller, null)
        if (ok) {
            RovaLog.d("ServiceController.unregister: ${controller.sessionId}")
        } else {
            RovaLog.w(
                "ServiceController.unregister: ${controller.sessionId} not " +
                    "current (current=${ref.get()?.sessionId}); ignoring"
            )
        }
    }

    /**
     * Snapshot the live controller. Receivers MUST treat null as
     * "process killed — write KILLED_BY_SYSTEM and bail."
     */
    fun current(): RovaController? = ref.get()
}
