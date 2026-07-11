package com.aritr.rova.ui.warnings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.aritr.rova.ui.theme.ResolveInk
import com.aritr.rova.ui.theme.RovaWarningsV3
import com.aritr.rova.ui.theme.quietTextColor

/**
 * Trust System V1 — the resolved inks for the two THEMED hosts (M7): the History
 * strip card and the Settings "Permissions & status" chip. Both follow the palette
 * (§06: *"flip to Daylight: they follow"*), so — unlike the pinned banner / snooze
 * chip — their backings are derived from the live `MaterialTheme.colorScheme`, and a
 * fixed severity/accent mix goes palette-blind (below AA on Daylight's tinted chips).
 *
 * **Design authority:** `docs/design/warnings-recovery.html` v1.0 (DESIGN FROZEN
 * 2026-07-09) — the `stripchip` (`:1306`) and `set` (`:1310`) `INK_SITES`, and
 * `tintOf` (`:1281`). Pure color math (`Color` only), so it is the house testable
 * seam under `isReturnDefaultValues = true` (mirrors `ResolveInk` / `ContrastMath`),
 * and its outputs are the exact world the 2808-assertion `TrustContrastSweepTest`
 * proves AA on: this object must feed `ResolveInk` the SAME backings the sweep models.
 */
internal object ThemedHostInk {

    /** `.strip` / `.setchip` fill = `color-mix(in srgb, sev 8%, surface)`. HTML `tintOf` (`:1281`). */
    const val TINT_FILL_ALPHA: Float = 0.08f

    /** The severity chip's decorative fill over the tint. HTML `--sev-chip-fill-pct` (`:76`, .20). */
    val SEV_CHIP_FILL_ALPHA: Float = RovaWarningsV3.sevChipFillAlpha

    /** The strip / settings-chip fill AND the resolver's tinted backing. */
    fun tint(severity: Color, surface: Color): Color =
        severity.copy(alpha = TINT_FILL_ALPHA).compositeOver(surface)

    /** Resolved inks for the History-strip severity chip (`stripchip` site: dot + label). */
    data class StripInk(val tint: Color, val chipDot: Color, val chipLabel: Color, val body: Color)

    /** Resolved inks for the Settings chip (`set` site: leading dot + trailing "Fix"). */
    data class SetInk(val tint: Color, val dot: Color, val fix: Color)

    /** Resolved inks for the Library recovery card (`recov` site: leading dot + body). */
    data class RecoveryInk(val dot: Color, val body: Color)

    /**
     * `stripchip` (`:1306`): the sevchip sits on `sev@20%` over the strip [tint]; its label and dot
     * resolve over THAT, lightening toward the host high ink on dark and deepening on Daylight.
     */
    fun forStrip(severity: Color, surface: Color, onSurface: Color, onSurfaceVariant: Color, isDark: Boolean): StripInk {
        val tint = tint(severity, surface)
        val top = onSurface.compositeOver(tint)                       // tHighOf(p, tint)
        val chipFill = severity.copy(alpha = SEV_CHIP_FILL_ALPHA).compositeOver(tint)
        return StripInk(
            tint = tint,
            chipDot = ResolveInk.of(severity, chipFill, ResolveInk.TARGET_MARK, top, ResolveInk.MIX_MARK).color,
            chipLabel = ResolveInk.of(severity, chipFill, ResolveInk.TARGET_TEXT, top, ResolveInk.MIX_LABEL).color,
            body = themedBody(isDark, onSurfaceVariant),
        )
    }

    /**
     * `set` (`:1310`): the leading dot and the "Fix" accent-as-text sit DIRECTLY on the chip [tint]
     * (no inner sevchip). The dot resolves the severity hue as a MARK; "Fix" resolves the PALETTE
     * ACCENT — not the deepened `colorScheme.primary` CTA fill — as text (`MIX_ACCENT`).
     */
    fun forSet(severity: Color, accent: Color, surface: Color, onSurface: Color): SetInk {
        val tint = tint(severity, surface)
        val top = onSurface.compositeOver(tint)
        return SetInk(
            tint = tint,
            dot = ResolveInk.of(severity, tint, ResolveInk.TARGET_MARK, top, ResolveInk.MIX_MARK).color,
            fix = ResolveInk.of(accent, tint, ResolveInk.TARGET_TEXT, top, ResolveInk.MIX_ACCENT).color,
        )
    }

    /**
     * `recov` (`:1312`): unlike the strip/settings chips there is NO inner sev-tint — the leading dot
     * sits DIRECTLY on the elevated [surfaceHi] container (HTML :356, :1313 `dot backing =
     * surfaceHiOf(p)`, `top = tHighOf(p, surfaceHi)`) and resolves the severity hue as a MARK.
     * Body/eyebrow/artifacts all route through [themedBody] (host-themed `--ink-dim == --ink-body ==
     * --tQuiet`, :384). The frozen `recov` site also resolves an accent-as-text `--acc-ink` for the
     * `.vendor` link; that link's styling is caller-owned in M8, so `acc` is not computed here — it
     * returns with the vendor-link consumer.
     */
    fun forRecovery(
        severity: Color,
        surfaceHi: Color,
        onSurface: Color,
        onSurfaceVariant: Color,
        isDark: Boolean,
    ): RecoveryInk {
        val top = onSurface.compositeOver(surfaceHi)                  // tHighOf(p, surfaceHi)
        return RecoveryInk(
            dot = ResolveInk.of(severity, surfaceHi, ResolveInk.TARGET_MARK, top, ResolveInk.MIX_MARK).color,
            body = themedBody(isDark, onSurfaceVariant),
        )
    }

    /**
     * Themed dim/body ink routed through the shipped `quietTextColor` seam (parity plan §2 / :51).
     *
     * The variant's OWN alpha is stripped before the seam and re-supplied as `dimAlpha`, exactly as
     * the frozen `quietOf` (`:1275`) models it: on a DARK scheme that reapplies the dim alpha (a
     * disclosed dim), but on a LIGHT scheme `quietTextColor` DROPS it and returns solid ink — a
     * mathematical requirement, not a preference: `onSurfaceVariant @ its own alpha` reads ~4.25:1
     * over Daylight's tinted chip and fails SC 1.4.3. Calling `rovaQuietText` here would leak that
     * alpha through (its composable wrapper does not strip it) and reintroduce the failure.
     */
    fun themedBody(isDark: Boolean, onSurfaceVariant: Color): Color =
        quietTextColor(
            isDark = isDark,
            onSurfaceVariant = onSurfaceVariant.copy(alpha = 1f),
            dimAlpha = onSurfaceVariant.alpha,
        )
}
