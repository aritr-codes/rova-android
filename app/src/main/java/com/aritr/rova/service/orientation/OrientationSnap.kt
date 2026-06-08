package com.aritr.rova.service.orientation

import android.view.Surface

/**
 * PR-α (ADR-0029 §Decision 2) — dwell duration before a new candidate rotation
 * becomes `stable`. First device-tuning guess; spec §7 (Risks) flags this as a
 * one-constant tuning seam. Mirrors THERMAL_FALL_DWELL_MS.
 */
internal const val ORIENTATION_DWELL_MS: Long = 350L

/**
 * PR-α — dead-band (degrees) each side of a bucket boundary. A raw degree within
 * this band of an edge keeps the current stable rotation (absorbs flutter at
 * 45/135/225/315). First guess; tune on device (spec §7).
 */
internal const val ORIENTATION_HYSTERESIS_DEG: Int = 12

/** Sentinel from OrientationEventListener for an undeterminable rotation. */
internal const val ORIENTATION_UNKNOWN: Int = -1

/**
 * PR-α — default orientation behaviour. PR-α only ships "Auto" (device-driven),
 * so the live value is `true`. The OrientationPolicy enum (Auto vs PortraitLock)
 * does NOT exist until PR-γ; this single constant is the seam so the owner's
 * Decision A flips behaviour in ONE place when the enum lands.
 *
 * Ratified 2026-06-08 (Decision A): Auto default, conditional on a visible
 * session-setup orientation control (else PortraitLock). See ADR-0029 §2.
 */
internal const val DEFAULT_ORIENTATION_POLICY_IS_AUTO: Boolean = true

/**
 * PR-α — opaque hysteresis state held inside [RovaRecordingService]. `stable` is
 * the Surface.ROTATION_* the operator sees applied; `candidate`/`candidateSinceMs`
 * are non-null only while a new bucket is under dwell. Mirrors
 * [com.aritr.rova.ui.signals.HysteresisState].
 */
internal data class OrientationSnapState(
    val stable: Int,                 // a Surface.ROTATION_* (0/1/2/3)
    val candidate: Int?,             // pending rotation under dwell, or null
    val candidateSinceMs: Long?,     // non-null iff candidate != null
)

/**
 * Pure bucket map. `degrees` in [0,359]. Android convention, device-natural
 * portrait assumed (sensor-vs-display sign verified empirically — the seam keeps
 * it testable regardless). Exact boundary degrees fall into the HIGHER bucket
 * (e.g. 45 -> ROTATION_270) by the half-open ranges below.
 */
internal fun bucketOf(degrees: Int): Int = when (degrees) {
    in 45 until 135 -> Surface.ROTATION_270
    in 135 until 225 -> Surface.ROTATION_180
    in 225 until 315 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0   // [315..360) u [0..45)
}

/**
 * True when `degrees` is within [deadBandDeg] of a bucket boundary. The buckets
 * split at exactly four edges — 45/135/225/315. Note 0/360 is NOT a boundary: it
 * is the CENTER of the ROTATION_0 bucket ([315..360) u [0..45) wraps through 0),
 * so a phone sitting squarely portrait (near 0°) must NOT be treated as on an edge.
 * Inside the band we refuse to start a new candidate so a phone hovering on a real
 * boundary does not chatter.
 */
internal fun inDeadBand(degrees: Int, deadBandDeg: Int): Boolean {
    val edges = intArrayOf(45, 135, 225, 315)
    return edges.any { edge -> kotlin.math.abs(degrees - edge) < deadBandDeg }
}

/**
 * Pure. degrees in [0,359] or [ORIENTATION_UNKNOWN] (-1). UNKNOWN returns state
 * unchanged. A degree within [deadBandDeg] of a bucket boundary keeps the current
 * stable rotation (dead-band; any in-flight candidate held). A degree cleanly in
 * the CURRENT bucket clears any candidate. A degree cleanly in a NEW bucket
 * starts/continues a dwell; only after [dwellMs] does `stable` flip. Multi-event
 * during dwell does NOT restart the timer (same semantics as applyThermalHysteresis).
 */
internal fun snapOrientation(
    degrees: Int,
    current: OrientationSnapState,
    nowMs: Long,
    dwellMs: Long = ORIENTATION_DWELL_MS,
    deadBandDeg: Int = ORIENTATION_HYSTERESIS_DEG,
): OrientationSnapState {
    if (degrees == ORIENTATION_UNKNOWN) return current

    val normalized = ((degrees % 360) + 360) % 360
    if (inDeadBand(normalized, deadBandDeg)) return current

    val target = bucketOf(normalized)

    // Cleanly inside the current stable bucket: clear any in-flight candidate.
    if (target == current.stable) {
        return if (current.candidate == null) current
        else current.copy(candidate = null, candidateSinceMs = null)
    }

    // New bucket. Start a dwell if none in flight (or in flight for a different
    // candidate); otherwise hold the original timer.
    if (current.candidate != target) {
        return current.copy(candidate = target, candidateSinceMs = nowMs)
    }
    val since = current.candidateSinceMs ?: nowMs
    return if (nowMs - since >= dwellMs) {
        OrientationSnapState(stable = target, candidate = null, candidateSinceMs = null)
    } else {
        current  // dwell not elapsed; do NOT restart the timer
    }
}

/**
 * PR-α (ADR-0029 §Decision 2) — which source provided the first-sample fallback
 * rotation. Recorded/returned for debuggability so a sideways first clip can be
 * root-caused (was it a carried value, a display read, or the hard default?).
 */
internal enum class FirstSampleSource {
    LAST_EFFECTIVE,   // reused the previous segment's persisted effectiveTargetRotation
    DISPLAY_ROTATION, // read the current snapped display rotation
    DEFAULT_PORTRAIT, // nothing available -> Surface.ROTATION_0
}

/**
 * PR-α (ADR-0029 §Decision 2) — result of [firstSampleFallback]: the chosen
 * Surface.ROTATION_* plus the [FirstSampleSource] that fired.
 */
internal data class FallbackResult(
    val rotation: Int,            // a Surface.ROTATION_* (0/1/2/3)
    val source: FirstSampleSource,
)

/**
 * PR-α (ADR-0029 §Decision 2) — DETERMINISTIC first-sample fallback for `Auto`.
 *
 * `Auto` snaps the device rotation via [snapOrientation], but at a segment
 * boundary there may be NO stable snapped sample yet: the
 * OrientationEventListener has not delivered, the raw degree is
 * ORIENTATION_UNKNOWN, or the device is in motion at clip start. This pure
 * function picks the rotation to encode the clip with, in a fixed priority order
 * so the choice is reproducible and testable:
 *
 *   1. [lastEffective] — the previous segment's persisted effectiveTargetRotation
 *      (carry forward; null for the very first segment of a session).
 *   2. [snappedDisplayRotation] — the current snapped display rotation, if known.
 *   3. default portrait — Surface.ROTATION_0.
 *
 * The returned [FallbackResult.source] records which branch fired. Pure / JVM —
 * only Surface.ROTATION_* int constants are touched.
 */
internal fun firstSampleFallback(
    lastEffective: Int?,
    snappedDisplayRotation: Int?,
): FallbackResult = when {
    lastEffective != null ->
        FallbackResult(lastEffective, FirstSampleSource.LAST_EFFECTIVE)
    snappedDisplayRotation != null ->
        FallbackResult(snappedDisplayRotation, FirstSampleSource.DISPLAY_ROTATION)
    else ->
        FallbackResult(Surface.ROTATION_0, FirstSampleSource.DEFAULT_PORTRAIT)
}
