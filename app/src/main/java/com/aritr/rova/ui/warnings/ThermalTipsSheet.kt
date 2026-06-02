package com.aritr.rova.ui.warnings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aritr.rova.R

/**
 * Phase 4 Slice 3 — bottom sheet opened from the THERMAL_AUTOSTOPPED echo
 * banner's primary CTA ("Tips to cool down"). Static content: 5 bullets +
 * "Got it" dismiss. No live thermal read-out, no nav route — see spec §3
 * non-goals.
 *
 * Hosted from [com.aritr.rova.ui.screens.RecordScreen] alongside the
 * existing SettingsSheet. Visibility is owned by RecordScreen's
 * `rememberSaveable` state; the sheet itself is purely view.
 *
 * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice3-thermal-autostop-design.md §4.10
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThermalTipsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.warning_thermal_tips_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            ThermalTip(stringResource(R.string.warning_thermal_tip_shade))
            ThermalTip(stringResource(R.string.warning_thermal_tip_case))
            ThermalTip(stringResource(R.string.warning_thermal_tip_close_apps))
            ThermalTip(stringResource(R.string.warning_thermal_tip_charging))
            ThermalTip(stringResource(R.string.warning_thermal_tip_rest))
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.warning_thermal_tips_dismiss))
            }
        }
    }
}

@Composable
private fun ThermalTip(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(R.string.warning_thermal_tips_bullet), style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
