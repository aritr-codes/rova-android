package com.aritr.rova.service

/**
 * Phase 1.2 alarm tick kind.
 *
 * Two distinct alarm purposes share one [RovaController.postTick] entry
 * point but drive different behavior in the implementation:
 *
 * - [WAKE]: inter-segment wakeup. Fired by `waitForNextSegment` arming
 *   between segments. The implementation unblocks the channel receive.
 * - [WATCHDOG]: in-recording liveness alarm. Fired by `recordSegment`
 *   arming for `recordingDuration + grace`. If the process is alive when
 *   it fires, the implementation is a no-op (recording is still on track
 *   or already finished — either way nothing to do). If the process is
 *   dead, the receiver writes `KILLED_BY_SYSTEM` because no controller
 *   answers — that is the entire point of the watchdog.
 *
 * Identity in [com.aritr.rova.service.scheduler.AlarmScheduler] uses the
 * URI path `rova://tick/<sessionId>/<kind>` so wake and watchdog occupy
 * separate `PendingIntent` slots and do not clobber each other.
 */
enum class TickKind { WAKE, WATCHDOG }

/**
 * Phase 1.2 in-process control surface for an active recording session.
 *
 * Implemented by [RovaRecordingService]; lives in the [ServiceController]
 * registry while the service is alive. Tick receivers and stop receivers
 * (Phase 1.3) consult the registry instead of starting the service from
 * background — Android 12+ background-start restrictions make
 * receiver-driven service starts unreliable, so all signals are delivered
 * in-process to a service that started itself in the foreground.
 *
 * Contract:
 * - [sessionId] is stable for the lifetime of one registration. Re-arming
 *   with a different sessionId means unregister-then-register.
 * - [postTick] is non-blocking. The service is responsible for ordering
 *   posted ticks against its own state (already-stopping, terminated,
 *   currently-recording) and for switching on [TickKind].
 * - [requestStop] is declared in Phase 1.2 but only invoked starting
 *   Phase 1.3 (RovaStopReceiver). Phase 1.2 callers MUST NOT invoke it.
 */
interface RovaController {
    val sessionId: String

    /**
     * Wake signal from an alarm tick. Non-blocking; returns immediately.
     * Behavior is gated on [kind]:
     * - [TickKind.WAKE] unblocks the inter-segment receive.
     * - [TickKind.WATCHDOG] is a liveness check; if the implementation
     *   is reached, the process is alive and the call is a no-op.
     */
    fun postTick(seq: Int, kind: TickKind)

    /** Phase 1.3: cooperative stop request. Phase 1.2 callers must not invoke. */
    fun requestStop()

    /**
     * ADR-0027: cooperative stop carrying an explicit [com.aritr.rova.data.StopReason]
     * (e.g. [com.aritr.rova.data.StopReason.SCHEDULE_WINDOW] for the daily-window
     * close). First-writer-wins on the reason is preserved by the implementation.
     */
    fun requestStop(reason: com.aritr.rova.data.StopReason)
}
