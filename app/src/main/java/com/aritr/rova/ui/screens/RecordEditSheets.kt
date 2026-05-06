package com.aritr.rova.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aritr.rova.data.QualityPresets
import com.aritr.rova.ui.components.EditSheetShell
import com.aritr.rova.ui.components.FixedContinuousSelector
import com.aritr.rova.ui.components.LargeValueStepper
import com.aritr.rova.ui.components.QualityOptionSelector
import com.aritr.rova.ui.components.QuickSetChipRow
import com.aritr.rova.ui.components.QuickSetOption
import com.aritr.rova.ui.components.RepeatsDraft

/**
 * Slice 2 — focused edit sheets opened from the Record idle dock.
 *
 * Dismiss contract is consistent across all four sheets:
 *   - Done             → commit draft to VM
 *   - Cancel           → discard draft
 *   - Back gesture     → discard draft (via EditSheetShell.onDismissRequest = onCancel)
 *   - Scrim tap        → discard draft (via EditSheetShell.onDismissRequest = onCancel)
 *
 * Sentinels never reach user-facing UI:
 *   - Continuous repeats are represented as [RepeatsDraft.Continuous]; the
 *     `loopCount = -1` sentinel is reconstructed only at the VM-write
 *     boundary inside the Done callback.
 *   - Wait `None` is `intervalMinutes = 0`; the `None` chip and "no wait
 *     between clips" descriptor handle the user-facing rendering.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipLengthEditSheet(
    initialSeconds: Int,
    onCommit: (Int) -> Unit,
    onCancel: () -> Unit
) {
    var draft by remember { mutableStateOf(initialSeconds.coerceIn(CLIP_MIN, CLIP_MAX)) }
    EditSheetShell(
        title = "Clip length",
        subtitle = "How long each loop records.",
        onDone = { onCommit(draft.coerceIn(CLIP_MIN, CLIP_MAX)); onCancel() },
        onCancel = onCancel
    ) {
        Column {
            LargeValueStepper(
                value = draft,
                unit = clipUnitLabel(draft),
                onValueChange = { draft = it.coerceIn(CLIP_MIN, CLIP_MAX) },
                minValue = CLIP_MIN,
                maxValue = CLIP_MAX,
                step = clipStep(draft),
                decreaseDescription = "Decrease clip length",
                increaseDescription = "Increase clip length",
                valueDisplay = clipDisplay(draft)
            )
            Spacer(Modifier.height(20.dp))
            ChipsLabel("Quick set")
            QuickSetChipRow(
                options = clipQuickSetOptions(),
                selected = draft,
                onSelect = { draft = it }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatsEditSheet(
    initialLoopCount: Int,
    onCommit: (Int) -> Unit,
    onCancel: () -> Unit
) {
    var draft by remember {
        mutableStateOf(repeatsDraftFrom(initialLoopCount))
    }
    EditSheetShell(
        title = "Repeats",
        subtitle = "How many clips this session captures.",
        onDone = { onCommit(loopCountFrom(draft)); onCancel() },
        onCancel = onCancel
    ) {
        Column {
            FixedContinuousSelector(
                draft = draft,
                onDraftChange = { draft = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            when (val current = draft) {
                is RepeatsDraft.Fixed -> {
                    LargeValueStepper(
                        value = current.count,
                        unit = if (current.count == 1) "repeat" else "repeats",
                        onValueChange = { draft = RepeatsDraft.Fixed(it.coerceIn(REPEATS_MIN, REPEATS_MAX)) },
                        minValue = REPEATS_MIN,
                        maxValue = REPEATS_MAX,
                        decreaseDescription = "Decrease repeats by 1",
                        increaseDescription = "Increase repeats by 1"
                    )
                    Spacer(Modifier.height(20.dp))
                    ChipsLabel("Quick set")
                    QuickSetChipRow(
                        options = repeatsQuickSetOptions(),
                        selected = current.count,
                        onSelect = { draft = RepeatsDraft.Fixed(it) }
                    )
                }
                RepeatsDraft.Continuous -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Records until you stop",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Rova will keep capturing clips, with the chosen wait between them, until you tap Stop.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaitEditSheet(
    initialMinutes: Int,
    onCommit: (Int) -> Unit,
    onCancel: () -> Unit
) {
    var draft by remember { mutableStateOf(initialMinutes.coerceIn(WAIT_MIN, WAIT_MAX)) }
    EditSheetShell(
        title = "Wait between clips",
        subtitle = "Pause before the next clip starts.",
        onDone = { onCommit(draft.coerceIn(WAIT_MIN, WAIT_MAX)); onCancel() },
        onCancel = onCancel
    ) {
        Column {
            LargeValueStepper(
                value = draft,
                unit = waitUnitLabel(draft),
                onValueChange = { draft = it.coerceIn(WAIT_MIN, WAIT_MAX) },
                minValue = WAIT_MIN,
                maxValue = WAIT_MAX,
                decreaseDescription = "Decrease wait by 1 minute",
                increaseDescription = "Increase wait by 1 minute",
                valueDisplay = waitDisplay(draft)
            )
            Spacer(Modifier.height(20.dp))
            ChipsLabel("Quick set")
            QuickSetChipRow(
                options = waitQuickSetOptions(),
                selected = draft,
                onSelect = { draft = it }
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Wait is stored in whole minutes for this release. " +
                    "Sub-minute waits will land in a later slice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityEditSheet(
    initialQuality: String,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var draft by remember {
        mutableStateOf(QualityPresets.canonicalizeOrDefault(initialQuality))
    }
    EditSheetShell(
        title = "Quality",
        subtitle = "Higher quality uses more storage.",
        onDone = { onCommit(draft); onCancel() },
        onCancel = onCancel
    ) {
        Column {
            ChipsLabel("Choose one")
            QualityOptionSelector(
                selected = draft,
                onSelect = { draft = it },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ChipsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

// ---------------------------------------------------------------------------
// Sentinel mapping at the VM-write boundary
// ---------------------------------------------------------------------------

private fun repeatsDraftFrom(loopCount: Int): RepeatsDraft =
    if (loopCount < 0) RepeatsDraft.Continuous
    else RepeatsDraft.Fixed(loopCount.coerceIn(REPEATS_MIN, REPEATS_MAX))

private fun loopCountFrom(draft: RepeatsDraft): Int = when (draft) {
    is RepeatsDraft.Fixed -> draft.count.coerceIn(REPEATS_MIN, REPEATS_MAX)
    RepeatsDraft.Continuous -> -1
}

// ---------------------------------------------------------------------------
// Range constants
// ---------------------------------------------------------------------------

private const val CLIP_MIN = 1
private const val CLIP_MAX = 300
private const val REPEATS_MIN = 1
private const val REPEATS_MAX = 999
private const val WAIT_MIN = 0
private const val WAIT_MAX = 60

// ---------------------------------------------------------------------------
// Quick-set option builders
// ---------------------------------------------------------------------------

private fun clipQuickSetOptions(): List<QuickSetOption<Int>> = listOf(
    QuickSetOption(10, "10s"),
    QuickSetOption(30, "30s"),
    QuickSetOption(60, "1m"),
    QuickSetOption(300, "5m")
)

private fun repeatsQuickSetOptions(): List<QuickSetOption<Int>> = listOf(
    QuickSetOption(1, "1"),
    QuickSetOption(3, "3"),
    QuickSetOption(5, "5"),
    QuickSetOption(10, "10"),
    QuickSetOption(25, "25")
)

private fun waitQuickSetOptions(): List<QuickSetOption<Int>> = listOf(
    QuickSetOption(0, "None", contentDescription = "No wait between clips"),
    QuickSetOption(1, "1m"),
    QuickSetOption(5, "5m"),
    QuickSetOption(10, "10m"),
    QuickSetOption(30, "30m"),
    QuickSetOption(60, "1h", contentDescription = "1 hour")
)

// ---------------------------------------------------------------------------
// Display helpers — never expose 0/-1 raw
// ---------------------------------------------------------------------------

private fun clipUnitLabel(seconds: Int): String = when {
    seconds == 1 -> "second"
    seconds < 60 -> "seconds"
    seconds == 60 -> "minute"
    seconds % 60 == 0 -> "minutes"
    else -> "seconds"
}

private fun clipDisplay(seconds: Int): String = when {
    seconds < 60 -> seconds.toString()
    seconds % 60 == 0 -> (seconds / 60).toString()
    else -> seconds.toString()
}

private fun clipStep(seconds: Int): Int = if (seconds < 60) 5 else 15

private fun waitUnitLabel(minutes: Int): String = when {
    minutes <= 0 -> ""
    minutes == 1 -> "minute"
    minutes == 60 -> "hour"
    else -> "minutes"
}

private fun waitDisplay(minutes: Int): String = when {
    minutes <= 0 -> "None"
    minutes == 60 -> "1"
    else -> minutes.toString()
}
