package com.aritr.rova.ui.screens

/**
 * Milestone 1 — pure-JVM math seam for [RecordingFrameGuide]. Given a
 * P+L preview zone size and the side's recording aspect ratio (9:16 for
 * portrait, 16:9 for landscape), returns the centred recording rectangle
 * plus the surrounding non-recorded margin regions (scrim targets).
 *
 * Project precedent for pure-helper test seams:
 * [com.aritr.rova.service.dualrecord.internal.AspectFitMath],
 * [com.aritr.rova.ui.screens.cycleModeNext],
 * [com.aritr.rova.ui.warnings.effectiveIdleTopBannerId].
 *
 * Tested by [com.aritr.rova.ui.screens.RecordingFrameLayoutTest] (JVM).
 *
 * Spec:
 * `docs/superpowers/specs/2026-05-26-dualshot-frame-polish-design.md` §6.2.
 */
internal data class FrameRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

internal data class RecordingFrameLayout(
    val recordingRect: FrameRect,
    val scrimRegions: List<FrameRect>,
)

private val EMPTY_RECT = FrameRect(0f, 0f, 0f, 0f)
private val EMPTY_LAYOUT = RecordingFrameLayout(EMPTY_RECT, emptyList())

internal fun recordingFrameLayout(
    zoneWidth: Float,
    zoneHeight: Float,
    recordingAspect: Float,
): RecordingFrameLayout {
    if (zoneWidth <= 0f || zoneHeight <= 0f || recordingAspect <= 0f) {
        return EMPTY_LAYOUT
    }
    val zoneAspect = zoneWidth / zoneHeight
    val (recW, recH) = if (recordingAspect < zoneAspect) {
        // Recording narrower than zone → fit by height, side scrims.
        zoneHeight * recordingAspect to zoneHeight
    } else {
        // Recording wider than (or equal to) zone → fit by width, top/bottom scrims.
        zoneWidth to zoneWidth / recordingAspect
    }
    val recLeft = (zoneWidth - recW) / 2f
    val recTop = (zoneHeight - recH) / 2f
    val recordingRect = FrameRect(recLeft, recTop, recW, recH)

    val scrims = buildList {
        if (recW < zoneWidth) {
            // Side scrims.
            add(FrameRect(0f, 0f, recLeft, zoneHeight))
            add(FrameRect(recLeft + recW, 0f, zoneWidth - recLeft - recW, zoneHeight))
        }
        if (recH < zoneHeight) {
            // Top/bottom scrims.
            add(FrameRect(0f, 0f, zoneWidth, recTop))
            add(FrameRect(0f, recTop + recH, zoneWidth, zoneHeight - recTop - recH))
        }
    }
    return RecordingFrameLayout(recordingRect, scrims)
}
