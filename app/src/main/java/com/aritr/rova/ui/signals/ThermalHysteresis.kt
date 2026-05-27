package com.aritr.rova.ui.signals

/**
 * Milestone 3 (ADR-0019) — fall-dwell duration for asymmetric thermal
 * hysteresis. Uniform across all level transitions; multi-level fall
 * = step-down one level per dwell. See ADR-0019 §Decision #2.
 */
internal const val THERMAL_FALL_DWELL_MS: Long = 3_000L

/**
 * Milestone 3 (ADR-0019) — opaque hysteresis state held inside
 * [ThermalStatusSignal]. The `stable` field is what consumers see via
 * `state.value`; `dwellEnteredAtMs` is non-null only while a fall-dwell
 * is in flight.
 */
internal data class HysteresisState(
    val stable: ThermalStatus,
    val dwellEnteredAtMs: Long?,
)

/**
 * Milestone 3 (ADR-0019) — asymmetric thermal hysteresis. Pure
 * function; JVM-testable. Rise (`raw.ordinal > stable.ordinal`):
 * instant transition, clears any in-flight dwell. Equal (`raw == stable`):
 * clears any in-flight dwell (raw bounced back). Fall
 * (`raw.ordinal < stable.ordinal`): starts a [fallDwellMs] timer; on
 * expiry steps stable DOWN exactly ONE level. Multi-event lower-raw
 * during dwell does NOT restart the timer.
 */
internal fun applyThermalHysteresis(
    raw: ThermalStatus,
    current: HysteresisState,
    nowMs: Long,
    fallDwellMs: Long = THERMAL_FALL_DWELL_MS,
): HysteresisState {
    if (raw.ordinal > current.stable.ordinal) {
        return HysteresisState(stable = raw, dwellEnteredAtMs = null)
    }
    if (raw.ordinal == current.stable.ordinal) {
        return HysteresisState(stable = current.stable, dwellEnteredAtMs = null)
    }
    if (current.dwellEnteredAtMs == null) {
        return HysteresisState(stable = current.stable, dwellEnteredAtMs = nowMs)
    }
    if (nowMs - current.dwellEnteredAtMs >= fallDwellMs) {
        return HysteresisState(
            stable = ThermalStatus.entries[current.stable.ordinal - 1],
            dwellEnteredAtMs = null,
        )
    }
    return current
}
