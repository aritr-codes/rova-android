package com.aritr.rova.service.orientation

/**
 * ADR-0029 [Ratified D] — (policy, snapped device rotation) -> effectiveTargetRotation.
 * FollowDevice passes the snapped rotation through; a lock resolves to the
 * EXPLICIT Surface.ROTATION_* captured at lock time (four-rotation model, so
 * reverse/180 locks later are additive with no prefs migration). A Lock with
 * an out-of-range stored rotation degrades to FollowDevice rather than
 * pinning garbage.
 *
 * Surface.ROTATION_* values (plain ints, no android.view.Surface import needed):
 *   0 = ROTATION_0   (natural/portrait-up)
 *   1 = ROTATION_90  (landscape-left)
 *   2 = ROTATION_180 (portrait-down)
 *   3 = ROTATION_270 (landscape-right)
 */
object OrientationPolicyResolver {
    fun resolve(policy: String, lockRotation: Int, snappedRotation: Int): Int =
        if (policy == "Lock" && lockRotation in 0..3) lockRotation else snappedRotation
}
