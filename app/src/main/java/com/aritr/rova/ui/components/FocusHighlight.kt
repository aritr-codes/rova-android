package com.aritr.rova.ui.components

import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * WCAG 2.2 AA SC 2.4.7 Focus Visible (ADR-0020) — draws a 2 dp ring while the
 * element holds keyboard / D-pad focus.
 *
 * Custom `Modifier.clickable` surfaces in this app paint their own dark chrome,
 * where the default focus ripple is effectively invisible. This composed
 * modifier observes the focus state of the focus target **below it in the
 * chain** and renders an explicit border when focused.
 *
 * **Ordering contract:** place this *before* the `.clickable(...)` (or
 * `.toggleable(...)`) it is meant to ring, because [onFocusChanged] reports for
 * focus targets that follow it in the modifier chain. Place it after any
 * `.clip(...)` so the ring follows the clipped shape. The border uses a 0 dp /
 * transparent stroke when unfocused, so it never shifts layout.
 *
 * Not JVM-unit-testable (focus is an instrumented concern; this repo runs no
 * instrumented tests) — correctness rests on the ordering contract above.
 */
fun Modifier.focusHighlight(
    shape: Shape,
    color: Color = Color.White,
    width: Dp = 2.dp,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) width else 0.dp,
            color = if (focused) color else Color.Transparent,
            shape = shape,
        )
}
