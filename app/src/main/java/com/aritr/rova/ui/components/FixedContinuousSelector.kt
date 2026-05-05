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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

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
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        SegmentedButton(
            selected = isFixed,
            onClick = { onDraftChange(RepeatsDraft.Fixed(fixedCountForToggle)) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text(if (isFixed) "✓ Fixed" else "Fixed") },
            modifier = Modifier.clearAndSetSemantics {
                this.contentDescription = if (isFixed) "Fixed, selected" else "Fixed, not selected"
                this.role = Role.RadioButton
                this.selected = isFixed
            }
        )
        SegmentedButton(
            selected = !isFixed,
            onClick = { onDraftChange(RepeatsDraft.Continuous) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text(if (!isFixed) "✓ Continuous" else "Continuous") },
            modifier = Modifier.clearAndSetSemantics {
                this.contentDescription = if (!isFixed) "Records until you stop, selected" else "Records until you stop, not selected"
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
