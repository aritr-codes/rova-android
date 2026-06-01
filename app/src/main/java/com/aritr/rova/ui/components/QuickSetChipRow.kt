package com.aritr.rova.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aritr.rova.R

/**
 * One chip in the quick-set row. Selected state carries a ✓ prefix in
 * addition to the M3 selected container fill so the signal is never
 * color-only.
 */
data class QuickSetOption<T>(
    val value: T,
    val label: String,
    val contentDescription: String? = null
)

@Composable
fun <T> QuickSetChipRow(
    options: List<QuickSetOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option.value == selected
            val description = option.contentDescription ?: option.label
            val selectedLabel = stringResource(R.string.record_quickset_selected_label, option.label)
            val chipDescription = if (isSelected) {
                stringResource(R.string.record_quickset_selected_cd, description)
            } else {
                stringResource(R.string.record_quickset_not_selected_cd, description)
            }
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option.value) },
                label = { Text(if (isSelected) selectedLabel else option.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.clearAndSetSemantics {
                    this.contentDescription = chipDescription
                    this.role = Role.RadioButton
                    this.selected = isSelected
                }
            )
        }
    }
}

@Preview(name = "QuickSetChipRow · light", showBackground = true)
@Composable
private fun QuickSetChipRowPreviewLight() {
    Surface(color = MaterialTheme.colorScheme.background) {
        val options = listOf(
            QuickSetOption(10, "10s"),
            QuickSetOption(30, "30s"),
            QuickSetOption(60, "1m"),
            QuickSetOption(300, "5m")
        )
        QuickSetChipRow(
            options = options,
            selected = 30,
            onSelect = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(name = "QuickSetChipRow · dark", showBackground = true)
@Composable
private fun QuickSetChipRowPreviewDark() {
    Surface(color = MaterialTheme.colorScheme.background) {
        val options = listOf(
            QuickSetOption(0, "None", contentDescription = "No wait between clips"),
            QuickSetOption(1, "1m"),
            QuickSetOption(5, "5m"),
            QuickSetOption(10, "10m"),
            QuickSetOption(30, "30m"),
            QuickSetOption(60, "1h")
        )
        QuickSetChipRow(
            options = options,
            selected = 1,
            onSelect = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
