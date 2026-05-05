package com.aritr.rova.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.RovaTheme

/**
 * Read-only cell in the Record idle plan grid. Tapping opens the
 * matching focused edit sheet. The visual is calm; the editability is
 * communicated via trailing chevron + the descriptor "{label}: {value}.
 * Tap to change." which TalkBack reads.
 *
 * Min height ≥ 76 dp keeps the entire row above 48 dp and gives older
 * users a generous tap target.
 */
@Composable
fun TappablePlanCell(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescriptionOverride: String? = null
) {
    val description = contentDescriptionOverride ?: "$label: $value. Tap to change."
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                this.contentDescription = description
                this.role = Role.Button
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Preview(name = "TappablePlanCell · light", showBackground = true)
@Composable
private fun TappablePlanCellPreviewLight() {
    RovaTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TappablePlanCell(label = "Clip length", value = "30 s", onClick = {})
                TappablePlanCell(label = "Repeats", value = "Until you stop", onClick = {})
                TappablePlanCell(label = "Wait", value = "None", onClick = {})
                TappablePlanCell(label = "Quality", value = "FHD", onClick = {})
            }
        }
    }
}

@Preview(name = "TappablePlanCell · dark", showBackground = true)
@Composable
private fun TappablePlanCellPreviewDark() {
    RovaTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TappablePlanCell(label = "Clip length", value = "1 m", onClick = {})
                TappablePlanCell(label = "Repeats", value = "10", onClick = {})
            }
        }
    }
}
