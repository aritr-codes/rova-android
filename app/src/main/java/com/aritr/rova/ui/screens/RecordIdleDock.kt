package com.aritr.rova.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aritr.rova.data.QualityPresets
import com.aritr.rova.data.RovaPreset
import com.aritr.rova.ui.components.PlanSummary
import com.aritr.rova.ui.components.TappablePlanCell
import com.aritr.rova.ui.components.UiCopy

/**
 * Slice 2 — read-only Record idle dock. Composed inside the existing
 * Record screen below the camera preview. Layout (top-to-bottom inside
 * the dock):
 *
 *   - PlanSummary               (read-only sentence)
 *   - 2x2 TappablePlanCell grid (Clip length / Repeats / Wait / Quality)
 *   - Quick Presets row         (Drill / Vlog + custom; preserved)
 *   - Start button              (full-width inside dock flow; never
 *                               an absolute overlay so it cannot
 *                               collide with the bottom nav at any
 *                               font scale)
 *
 * Editing is delegated to focused modal bottom sheets; the cells just
 * publish a [SheetTarget] via the supplied callback.
 */
@Composable
fun RecordIdleDock(
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    customPresets: List<RovaPreset>,
    onCellTap: (SheetTarget) -> Unit,
    onPresetSelected: (RovaPreset) -> Unit,
    onPresetDeleted: (RovaPreset) -> Unit,
    onSavePreset: () -> Unit,
    onStart: () -> Unit,
    startEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PlanSummary(
                clipSeconds = durationSeconds,
                loopCount = loopCount,
                intervalMinutes = intervalMinutes,
                quality = quality
            )

            // 2x2 cell grid — manual two-row layout to keep things
            // sentinel-blind on each cell.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TappablePlanCell(
                    label = "Clip length",
                    value = clipLengthCellValue(durationSeconds),
                    onClick = { onCellTap(SheetTarget.ClipLength) },
                    contentDescriptionOverride = UiCopy.clipLengthCellDescription(durationSeconds),
                    modifier = Modifier.weight(1f)
                )
                TappablePlanCell(
                    label = "Repeats",
                    value = repeatsCellValue(loopCount),
                    onClick = { onCellTap(SheetTarget.Repeats) },
                    contentDescriptionOverride = repeatsCellDescription(loopCount),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TappablePlanCell(
                    label = "Wait",
                    value = waitCellValue(intervalMinutes),
                    onClick = { onCellTap(SheetTarget.Wait) },
                    contentDescriptionOverride = waitCellDescription(intervalMinutes),
                    modifier = Modifier.weight(1f)
                )
                TappablePlanCell(
                    label = "Quality",
                    value = quality,
                    onClick = { onCellTap(SheetTarget.Quality) },
                    contentDescriptionOverride = UiCopy.qualityCellDescription(quality),
                    modifier = Modifier.weight(1f)
                )
            }

            // Presets row — Drill/Vlog defaults preserved; custom
            // presets follow with a delete affordance.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Presets",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onSavePreset,
                    enabled = startEnabled
                ) {
                    Text("Save current", style = MaterialTheme.typography.labelMedium)
                }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(defaultPresets) { p ->
                    val matches = matchPreset(p, durationSeconds, intervalMinutes, loopCount, quality)
                    FilterChip(
                        selected = matches,
                        onClick = { onPresetSelected(p) },
                        label = { Text(p.name) },
                        leadingIcon = if (matches) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null
                    )
                }
                items(customPresets) { p ->
                    val matches = matchPreset(p, durationSeconds, intervalMinutes, loopCount, quality)
                    // Slice 2 review fix — select and delete are two
                    // separate accessible controls. The previous
                    // `InputChip(trailingIcon = Icon.clickable)` made
                    // the X a 16 dp nested clickable nested inside the
                    // chip's selection action, which TalkBack reported
                    // as a single ambiguous control with a sub-48 dp
                    // touch target. The chip body now selects, and a
                    // standalone IconButton — sized to the M3 default
                    // 40 dp visual with the framework-enforced 48 dp
                    // minimum interactive size — handles delete.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        FilterChip(
                            selected = matches,
                            onClick = { onPresetSelected(p) },
                            label = { Text(p.name) },
                            leadingIcon = if (matches) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                        IconButton(onClick = { onPresetDeleted(p) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete preset ${p.name}"
                            )
                        }
                    }
                }
            }

            // Start — full-width inside the dock flow. A child of this
            // Column, NOT an absolute-positioned overlay, so it cannot
            // collide with the M3 NavigationBar at any font scale.
            Button(
                onClick = onStart,
                enabled = startEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text("Start recording", fontWeight = FontWeight.Medium)
            }
        }
    }
}

private val defaultPresets: List<RovaPreset> = listOf(
    RovaPreset("Drill", 10, 1, 10, QualityPresets.FHD),
    RovaPreset("Vlog", 60, 0, -1, QualityPresets.HD)
)

private fun matchPreset(
    p: RovaPreset,
    duration: Int,
    interval: Int,
    loopCount: Int,
    quality: String
): Boolean = p.duration == duration &&
    p.interval == interval &&
    p.loopCount == loopCount &&
    p.resolution == quality

// ---------------------------------------------------------------------------
// Cell value helpers — sentinel-blind output
// ---------------------------------------------------------------------------

private fun clipLengthCellValue(seconds: Int): String = when {
    seconds <= 0 -> "0 s"
    seconds < 60 -> "$seconds s"
    seconds == 60 -> "1 m"
    seconds % 60 == 0 -> "${seconds / 60} m"
    else -> "$seconds s"
}

private fun repeatsCellValue(loopCount: Int): String =
    if (loopCount < 0) "Until you stop" else loopCount.toString()

private fun repeatsCellDescription(loopCount: Int): String =
    if (loopCount < 0) UiCopy.repeatsContinuousCellDescription()
    else UiCopy.repeatsFixedCellDescription(loopCount)

private fun waitCellValue(intervalMinutes: Int): String = when {
    intervalMinutes <= 0 -> "None"
    intervalMinutes == 60 -> "1 h"
    intervalMinutes % 60 == 0 -> "${intervalMinutes / 60} h"
    else -> "$intervalMinutes m"
}

private fun waitCellDescription(intervalMinutes: Int): String =
    if (intervalMinutes <= 0) UiCopy.waitNoneCellDescription()
    else UiCopy.waitFiniteCellDescription(intervalMinutes)
