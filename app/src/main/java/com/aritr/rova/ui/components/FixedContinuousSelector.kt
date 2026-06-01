package com.aritr.rova.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aritr.rova.R

/**
 * UI-side draft for the Repeats sheet. The internal `loopCount = -1`
 * sentinel never appears in this layer; sentinel restoration is the
 * VM-write boundary's job in a later slice.
 */
sealed class RepeatsDraft {
    data class Fixed(val count: Int) : RepeatsDraft()
    data object Continuous : RepeatsDraft()
}

/**
 * Two-position segmented control: Fixed | Continuous. Selected option
 * announces a non-color signal (✓ prefix in label). Continuous radio
 * carries the plain-language descriptor "Records until you stop" so
 * TalkBack reads the meaning, not the abstract mode name.
 */
@Composable
fun FixedContinuousSelector(
    draft: RepeatsDraft,
    onDraftChange: (RepeatsDraft) -> Unit,
    modifier: Modifier = Modifier,
    fixedCountForToggle: Int = 10
) {
    val isFixed = draft is RepeatsDraft.Fixed
    val fixedLabel = stringResource(R.string.settings_repeats_fixed)
    val fixedLabelSelected = stringResource(R.string.settings_repeats_fixed_selected)
    val continuousLabel = stringResource(R.string.settings_repeats_continuous)
    val continuousLabelSelected = stringResource(R.string.settings_repeats_continuous_selected)
    val fixedSelectedCd = stringResource(R.string.settings_repeats_fixed_selected_cd)
    val fixedNotSelectedCd = stringResource(R.string.settings_repeats_fixed_not_selected_cd)
    val continuousSelectedCd = stringResource(R.string.settings_repeats_continuous_selected_cd)
    val continuousNotSelectedCd = stringResource(R.string.settings_repeats_continuous_not_selected_cd)
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        SegmentedButton(
            selected = isFixed,
            onClick = { onDraftChange(RepeatsDraft.Fixed(fixedCountForToggle)) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text(if (isFixed) fixedLabelSelected else fixedLabel) },
            modifier = Modifier.clearAndSetSemantics {
                this.contentDescription = if (isFixed) fixedSelectedCd else fixedNotSelectedCd
                this.role = Role.RadioButton
                this.selected = isFixed
            }
        )
        SegmentedButton(
            selected = !isFixed,
            onClick = { onDraftChange(RepeatsDraft.Continuous) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text(if (!isFixed) continuousLabelSelected else continuousLabel) },
            modifier = Modifier.clearAndSetSemantics {
                this.contentDescription = if (!isFixed) continuousSelectedCd else continuousNotSelectedCd
                this.role = Role.RadioButton
                this.selected = !isFixed
            }
        )
    }
}

@Preview(name = "FixedContinuousSelector · light", showBackground = true)
@Composable
private fun FixedContinuousSelectorPreviewLight() {
    Surface(color = MaterialTheme.colorScheme.background) {
        FixedContinuousSelector(
            draft = RepeatsDraft.Fixed(10),
            onDraftChange = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(name = "FixedContinuousSelector · dark", showBackground = true)
@Composable
private fun FixedContinuousSelectorPreviewDark() {
    Surface(color = MaterialTheme.colorScheme.background) {
        FixedContinuousSelector(
            draft = RepeatsDraft.Continuous,
            onDraftChange = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
