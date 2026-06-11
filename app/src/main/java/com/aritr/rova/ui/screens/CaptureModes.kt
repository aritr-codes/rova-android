package com.aritr.rova.ui.screens

import androidx.annotation.StringRes
import com.aritr.rova.R

/**
 * Spec 2026-06-11 §3 — the user-facing capture-mode registry. Mode = capture
 * strategy (CaptureTopology), NEVER orientation. Picker tabs, the record-home
 * cycle chip, and captions all render from this single list, so a future mode
 * is one entry here, no layout work.
 */
enum class CaptureMode(
    val topology: String,
    @StringRes val labelRes: Int,
    @StringRes val captionRes: Int,
) {
    Auto("Single", R.string.capture_mode_auto, R.string.capture_mode_auto_caption),
    DualShot("DualShot", R.string.capture_mode_dualshot, R.string.capture_mode_dualshot_caption),
    DualSight("FrontBack", R.string.capture_mode_dualsight, R.string.capture_mode_dualsight_caption);

    companion object {
        /**
         * PR-δ flips this behind the concurrent-camera capability gate
         * (ADR-0029 §5 — gate on getConcurrentCameraIds combinations, never
         * API level). Until then DualSight is registry-only.
         */
        const val DUALSIGHT_ENABLED: Boolean = false

        fun visible(): List<CaptureMode> = entries.filter { it != DualSight || DUALSIGHT_ENABLED }

        fun forTopology(topology: String): CaptureMode =
            entries.firstOrNull { it.topology == topology } ?: Auto

        /** Spec §5 Variant A (owner-ratified): accent gradient iff mode != Auto. */
        fun isAccented(topology: String): Boolean = forTopology(topology) != Auto

        fun cycleNext(topology: String): String {
            val ring = visible()
            val i = ring.indexOf(forTopology(topology))
            return ring[(i + 1 + ring.size) % ring.size].topology
        }
    }
}

/**
 * Ratified-D "lock whatever I'm aimed at now": Lock Landscape captures the
 * CURRENTLY-snapped landscape rotation when the device is in one (90 or 270),
 * else defaults to ROTATION_90. Lock Portrait is always ROTATION_0.
 */
internal fun lockRotationForLandscapePick(currentDeviceRotation: Int?): Int =
    if (currentDeviceRotation == 1 || currentDeviceRotation == 3) currentDeviceRotation else 1
