package com.aritr.rova.ui.warnings

/**
 * Pure geometry of an **invisible** touch-target expansion — the Compose analogue of
 * the frozen spec's absolute `::after` hit overlay (`docs/design/warnings-recovery.html`
 * §05 `.banner .stop::after` :317–:318: `width:100%;min-width:var(--target);height:var(--target)`).
 *
 * The problem it solves: Compose's stock hit helpers (`minimumInteractiveComponentSize()`,
 * `requiredSize`, `sizeIn`) reach a 48dp touch target by **reporting** a ≥48dp measured size
 * to the parent — which grows a wrap-content container. On the banner `Row` that made the
 * whole banner ~12dp taller (Investigation 2), the exact viewfinder-occlusion the frozen §05
 * forbids ("a 48dp-tall pill would grow the banner").
 *
 * The CSS `::after` is out-of-flow: it enlarges the hit region without affecting layout. The
 * Compose transcription measures the clickable child at ≥ [targetPx] (so the child's own bounds
 * — and therefore its pointer hit region — reach 48dp) while this helper decides the size the
 * **parent** sees: the content's NATURAL size, never the target. The expanded bounds overflow,
 * centered and invisible.
 *
 * Pure so the geometry is JVM-provable under `isReturnDefaultValues = true` (house pure-helper
 * seam); the `Modifier.invisibleTouchTarget` in `WarningTopBanner.kt` is the thin Compose wrapper.
 */
internal object TouchTargetExpansion {

    /**
     * @param reportedWidth/reportedHeight the size handed to the PARENT — always the natural
     *   content size, so the touch expansion never reserves layout.
     * @param placeableWidth/placeableHeight the size the child is MEASURED at — the pointer hit
     *   region; ≥ target on each axis.
     * @param offsetX/offsetY placement of the (larger) placeable inside the (natural) reported
     *   box — negative on an axis the target expands, so the hit region overflows symmetrically.
     */
    data class Result(
        val reportedWidth: Int,
        val reportedHeight: Int,
        val placeableWidth: Int,
        val placeableHeight: Int,
        val offsetX: Int,
        val offsetY: Int,
    )

    /**
     * @param naturalWidth/naturalHeight the content's intrinsic size (what the parent must keep seeing)
     * @param targetPx the touch-target floor in px (48dp)
     * @param maxWidthPx the incoming max width for coercion ([Int.MAX_VALUE] when unbounded)
     */
    fun compute(naturalWidth: Int, naturalHeight: Int, targetPx: Int, maxWidthPx: Int): Result {
        val placeableWidth = maxOf(naturalWidth, targetPx)
        val placeableHeight = maxOf(naturalHeight, targetPx)
        // The parent sees the NATURAL size — this is the whole point: no layout reservation.
        val reportedWidth = naturalWidth.coerceAtMost(maxWidthPx)
        val reportedHeight = naturalHeight
        return Result(
            reportedWidth = reportedWidth,
            reportedHeight = reportedHeight,
            placeableWidth = placeableWidth,
            placeableHeight = placeableHeight,
            offsetX = (reportedWidth - placeableWidth) / 2,
            offsetY = (reportedHeight - placeableHeight) / 2,
        )
    }
}
