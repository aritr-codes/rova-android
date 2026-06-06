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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
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
            val clipPhrase = pluralStringResource(R.plurals.preset_cd_clip_seconds, p.duration, p.duration)
            val waitPhrase = if (p.interval <= 0) stringResource(R.string.preset_cd_no_gap)
                else pluralStringResource(R.plurals.preset_cd_every_minutes, p.interval, p.interval)
            val repeatsPhrase = if (p.loopCount < 0) stringResource(R.string.preset_cd_until_stop)
                else pluralStringResource(R.plurals.preset_cd_repeats_times, p.loopCount, p.loopCount)
            val cd = stringResource(R.string.preset_cd_full, p.name, clipPhrase, waitPhrase, repeatsPhrase, p.resolution)
            FilterChip(
                selected = selected,
                onClick = { onApply(p) },
                label = { Text(p.name) },
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = cd },
            )
        }
    }
}

