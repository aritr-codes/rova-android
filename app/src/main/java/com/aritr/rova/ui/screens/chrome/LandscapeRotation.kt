package com.aritr.rova.ui.screens.chrome

import android.view.Surface

/**
 * Pure rotation→placement mapping for "rotate, don't redesign" landscape chrome
 * (spec `docs/superpowers/specs/2026-06-10-landscape-rotate-not-redesign-design.md`
 * §4). Android-type-light — only reads `Surface.ROTATION_*` int constants — so it
 * runs under JVM unit tests (`isReturnDefaultValues = true`). Reuses [NavEdge] for
 * the cluster edge; no new edge type is introduced.
 *
 * The two landscape senses (ROTATION_90 / ROTATION_270) are MIRROR images: the
 * rotated-bottom cluster (config strip + nav) swaps edge AND reverses its
 * cross-axis order, so the layout is always "the portrait bottom bar rotated the
 * natural way." [NATIVE_MATCH_ROTATION] documents the reference handedness taken
 * from the device stock camera (verified on device — plan Task A6); flip it if the
 * probe disagrees.
 */
enum class DeviceLandscape { A, B }

/**
 * Reference: in the stock-camera landscape used for the spec, the control cluster
 * sat on the trailing edge with portrait-LEFT mapping to the BOTTOM. That is
 * [DeviceLandscape.A] == [Surface.ROTATION_90]. If on-device verification shows the
 * stock camera uses the opposite handedness, swap this to [Surface.ROTATION_270].
 */
private const val NATIVE_MATCH_ROTATION = Surface.ROTATION_90

/** `null` for portrait rotations (ROTATION_0 / ROTATION_180). */
fun landscapeSense(displayRotation: Int): DeviceLandscape? = when (displayRotation) {
    Surface.ROTATION_90 ->
        if (NATIVE_MATCH_ROTATION == Surface.ROTATION_90) DeviceLandscape.A else DeviceLandscape.B
    Surface.ROTATION_270 ->
        if (NATIVE_MATCH_ROTATION == Surface.ROTATION_90) DeviceLandscape.B else DeviceLandscape.A
    else -> null
}

/** Which physical edge the rotated-bottom cluster (config strip + nav) hugs. */
fun clusterEdge(sense: DeviceLandscape): NavEdge = when (sense) {
    DeviceLandscape.A -> NavEdge.Trailing
    DeviceLandscape.B -> NavEdge.Leading
}

/**
 * Portrait bottom-bar order (left→right) → landscape rail order (top→bottom).
 * Sense A (native ref): portrait-left → bottom ⇒ top→bottom is the REVERSE.
 * Sense B: the mirror ⇒ original order. Adjacency is preserved either way.
 */
fun <T> railOrder(portraitLeftToRight: List<T>, sense: DeviceLandscape): List<T> =
    when (sense) {
        DeviceLandscape.A -> portraitLeftToRight.reversed()
        DeviceLandscape.B -> portraitLeftToRight
    }
