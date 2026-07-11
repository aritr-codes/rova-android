package com.aritr.rova.ui.warnings

import androidx.compose.ui.graphics.Color
import com.aritr.rova.ui.theme.ContrastMath
import com.aritr.rova.ui.theme.ResolveInk
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.RovaWarningsV3
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 SnoozeChip ↔ [ResolveInk] integration (Trust System V1).
 *
 * The `snooze` INK_SITE resolves its dot as a MARK over the pinned capsule
 * (`docs/design/warnings-recovery.html` :1300–:1301 `dot:()=>capOf()`). The
 * capsule (`pinSurface` @ `pinContainerAlpha` over any live-media frame) is
 * always near-black, so the resolver takes the LIGHTEN branch and — with the
 * mark mix of 0 — passes the raw locked severity hue straight through. This
 * pins that the M4 migration to `ResolveInk` shifts the dot by ZERO on every
 * severity (byte-identical to the pre-freeze `severityColor` dot), while the
 * raw hue still clears the SC 1.4.11 mark bar over the real composited capsule.
 */
class WarningSnoozeChipInkTest {

    private val severities = listOf(
        "hard" to RovaWarnings.hard,
        "soft" to RovaWarnings.soft,
        "advisory" to RovaWarnings.advisory,
        "escalating" to RovaWarnings.escalating,
    )

    @Test
    fun dotInk_is_the_raw_severity_hue_lightened_passthrough_on_the_pinned_capsule() {
        severities.forEach { (name, sev) ->
            val ink = ResolveInk.of(
                hue = sev,
                backing = RovaWarningsV3.pinSurface,
                target = ResolveInk.TARGET_MARK,
                top = RovaWarningsV3.mediaInk,
                mix = ResolveInk.MIX_MARK,
            )
            assertEquals(
                "$name dot-ink must take the LIGHTEN branch (capsule is near-black)",
                ResolveInk.Direction.LIGHTEN,
                ink.direction,
            )
            assertEquals("$name dot-ink must equal the raw locked severity hue", sev, ink.color)
        }
    }

    @Test
    fun dotInk_clears_the_mark_bar_over_the_capsule_over_the_worst_media_frame() {
        // white (255,255,255) is the worst media frame for a dark capsule.
        val pin = RovaWarningsV3.pinSurface
        val capsule = ContrastMath.compositeAlphaOver(
            (pin.red * 255f).roundToInt(), (pin.green * 255f).roundToInt(), (pin.blue * 255f).roundToInt(),
            RovaWarningsV3.pinContainerAlpha.toDouble(),
            255, 255, 255,
        )
        severities.forEach { (name, sev) ->
            val dot = ResolveInk.of(
                hue = sev,
                backing = RovaWarningsV3.pinSurface,
                target = ResolveInk.TARGET_MARK,
                top = RovaWarningsV3.mediaInk,
                mix = ResolveInk.MIX_MARK,
            ).color
            val ratio = ContrastMath.contrastRatio(
                ContrastMath.relativeLuminance(dot.r8(), dot.g8(), dot.b8()),
                ContrastMath.relativeLuminance(capsule[0], capsule[1], capsule[2]),
            )
            assertTrue(
                "$name dot reads %.2f:1 over the capsule, below the 3.0:1 mark bar".format(ratio),
                ratio >= ResolveInk.TARGET_MARK,
            )
        }
    }

    private fun Color.r8(): Int = (red * 255f).roundToInt()
    private fun Color.g8(): Int = (green * 255f).roundToInt()
    private fun Color.b8(): Int = (blue * 255f).roundToInt()
}
