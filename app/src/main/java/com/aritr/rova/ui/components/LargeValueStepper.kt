package com.aritr.rova.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.ui.theme.RovaTheme

/**
 * Big-value stepper used inside [EditSheetShell] for Clip length /
 * Repeats / Wait. 64 dp circular minus and plus buttons flanking a
 * 44 sp numeric value with a unit caption underneath.
 *
 * Disabled buttons honor M3's 38 % opacity disabled token. Big-value
 * text uses an explicit [TextStyle] so it does not pick up serif
 * headline fonts (typography reconciliation is a later slice).
 */
@Composable
fun LargeValueStepper(
    value: Int,
    unit: String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minValue: Int = 0,
    maxValue: Int = Int.MAX_VALUE,
    step: Int = 1,
    decreaseDescription: String = "Decrease",
    increaseDescription: String = "Increase",
    valueDisplay: String = value.toString()
) {
    val canDecrease = value - step >= minValue
    val canIncrease = value + step <= maxValue

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepperButton(
            icon = Icons.Default.Remove,
            description = decreaseDescription,
            enabled = canDecrease,
            onClick = { if (canDecrease) onValueChange(value - step) }
        )
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = valueDisplay,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 44.sp,
                    lineHeight = 48.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StepperButton(
            icon = Icons.Default.Add,
            description = increaseDescription,
            enabled = canIncrease,
            onClick = { if (canIncrease) onValueChange(value + step) }
        )
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.38f
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
        )
    }
}

@Preview(name = "LargeValueStepper · light", showBackground = true)
@Composable
private fun LargeValueStepperPreviewLight() {
    RovaTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                LargeValueStepper(
                    value = 30,
                    unit = "seconds",
                    onValueChange = {},
                    minValue = 5,
                    maxValue = 600,
                    step = 5,
                    decreaseDescription = "Decrease clip length by 5 seconds",
                    increaseDescription = "Increase clip length by 5 seconds"
                )
                Spacer(Modifier.height(24.dp))
                LargeValueStepper(
                    value = 1,
                    unit = "minute",
                    onValueChange = {},
                    minValue = 0,
                    maxValue = 60,
                    decreaseDescription = "Decrease wait by 1 minute",
                    increaseDescription = "Increase wait by 1 minute"
                )
            }
        }
    }
}

@Preview(name = "LargeValueStepper · dark", showBackground = true)
@Composable
private fun LargeValueStepperPreviewDark() {
    RovaTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                LargeValueStepper(
                    value = 10,
                    unit = "repeats",
                    onValueChange = {},
                    minValue = 1,
                    maxValue = 999,
                    decreaseDescription = "Decrease repeats by 1",
                    increaseDescription = "Increase repeats by 1"
                )
            }
        }
    }
}
