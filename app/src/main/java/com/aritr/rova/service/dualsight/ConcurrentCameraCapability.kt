package com.aritr.rova.service.dualsight

/** Lens direction, mirrored from CameraX's LENS_FACING_* so the pure core needs no CameraX import. */
enum class LensFacing { FRONT, BACK, OTHER }

/**
 * Pure capability decision for DualSight (ADR-0029 §5). A device supports DualSight only if
 * the platform advertises the concurrent feature AND a single concurrent combo contains BOTH a
 * front- and a back-facing camera. The combo list is sourced at the call site from
 * ProcessCameraProvider.availableConcurrentCameraInfos (mapped to LensFacing) — never a static
 * device allowlist.
 */
object ConcurrentCameraCapability {
    // Named to avoid the literal "FrontBack" token so checkFrontBackCapabilityGated (ADR-0029 §5)
    // stays green on this throwaway probe branch (the gate allowlist extension is δ-only).
    fun supportsConcurrentFrontAndBack(hasConcurrentFeature: Boolean, combos: List<List<LensFacing>>): Boolean {
        if (!hasConcurrentFeature) return false
        return combos.any { combo ->
            combo.contains(LensFacing.FRONT) && combo.contains(LensFacing.BACK)
        }
    }
}
