package com.aritr.rova.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aritr.rova.data.QualityPresets

/**
 * Quality picker driven solely by [QualityPresets.PICKER_ORDER]. The
 * caller cannot supply free-form chip strings — the picker order is
 * the canonical source of truth for which chips render and in what
 * order. Unrecognized [selected] values are accepted at the boundary
 * but render no chip as selected (callers should canonicalize via
 * [QualityPresets.canonicalize] before this composable).
 */
@Composable
fun QualityOptionSelector(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Pass the base description ("Quality: FHD") only — QuickSetChipRow
    // appends the ", selected" / ", not selected" suffix itself. Pre-
    // formatting with UiCopy.qualityChipDescription would double the
    // suffix and emit "Quality: FHD, selected, selected" via TalkBack.
    val options = QualityPresets.PICKER_ORDER.map { quality ->
        QuickSetOption(
            value = quality,
            label = quality,
            contentDescription = UiCopy.qualityChipBaseDescription(quality)
        )
    }
    QuickSetChipRow(
        options = options,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier
    )
}

@Preview(name = "QualityOptionSelector · light", showBackground = true)
@Composable
private fun QualityOptionSelectorPreviewLight() {
    Surface(color = MaterialTheme.colorScheme.background) {
        QualityOptionSelector(
            selected = QualityPresets.FHD,
            onSelect = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(name = "QualityOptionSelector · dark", showBackground = true)
@Composable
private fun QualityOptionSelectorPreviewDark() {
    Surface(color = MaterialTheme.colorScheme.background) {
        QualityOptionSelector(
            selected = QualityPresets.UHD,
            onSelect = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
