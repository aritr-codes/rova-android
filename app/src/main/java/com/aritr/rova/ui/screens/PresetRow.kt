package com.aritr.rova.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.data.RovaPreset

/**
 * ADR-0026 — preset-first idle surface. One tap on a chip applies the whole
 * config bundle via [onApply]. The selected chip reflects [activePresetId]
 * (null = no chip selected -> the "Customize" door shows "Custom").
 *
 * Each chip is a button with a spoken contentDescription (WCAG, ADR-0020) and a
 * >=48dp touch target.
 */
@Composable
fun PresetRow(
    presets: List<RovaPreset>,
    activePresetId: String?,
    onApply: (RovaPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { p ->
            val selected = p.id == activePresetId
            FilterChip(
                selected = selected,
                onClick = { onApply(p) },
                label = { Text(p.name) },
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = presetSpoken(p) },
            )
        }
    }
}

/** "Standard preset, 30 seconds clip, every 2 minutes, 20 times, FHD." */
private fun presetSpoken(p: RovaPreset): String {
    val repeats = if (p.loopCount < 0) "until you stop" else "${p.loopCount} times"
    val wait = if (p.interval <= 0) "no gap" else "every ${p.interval} minutes"
    return "${p.name} preset, ${p.duration} seconds clip, $wait, $repeats, ${p.resolution}"
}
