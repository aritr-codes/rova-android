package com.aritr.loom.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aritr.loom.ui.components.SwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { com.aritr.loom.data.LoomSettings(context) }
    
    // Simple state mapping for now
    var enableBeeps by remember { mutableStateOf(settings.enableBeeps) }
    var vibrateAlerts by remember { mutableStateOf(settings.vibrateAlerts) }
    var keepScreenOn by remember { mutableStateOf(settings.keepScreenOn) }
    
    LaunchedEffect(enableBeeps) { settings.enableBeeps = enableBeeps }
    LaunchedEffect(vibrateAlerts) { settings.vibrateAlerts = vibrateAlerts }
    LaunchedEffect(keepScreenOn) { settings.keepScreenOn = keepScreenOn }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Section: General
            SettingsSection(title = "General Preferences") {
                 SwitchRow(Icons.Default.VolumeUp, "Sound Effects", "Play beeps on start/stop", enableBeeps) { enableBeeps = it }
                 Spacer(Modifier.height(16.dp))
                 SwitchRow(Icons.Default.Vibration, "Haptic Feedback", "Vibrate on interactions", vibrateAlerts) { vibrateAlerts = it }
                 Spacer(Modifier.height(16.dp))
                 SwitchRow(Icons.Default.Smartphone, "Keep Screen On", "Prevent screen timeout", keepScreenOn) { keepScreenOn = it }
            }

            // Section: Storage
            SettingsSection(title = "Storage Management") {
                ListItem(
                    headlineContent = { Text("Auto-Delete Old Recordings") },
                    supportingContent = { Text("Never") },
                    leadingContent = { Icon(Icons.Default.DeleteSweep, null) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable { /* Todo */ }
                )
                ListItem(
                    headlineContent = { Text("Clear Cache") },
                    leadingContent = { Icon(Icons.Default.CleaningServices, null) },
                     modifier = Modifier.clickable { /* Todo */ }
                )
            }

            // Section: About
            SettingsSection(title = "About") {
                ListItem(
                    headlineContent = { Text("Version") },
                    supportingContent = { Text("1.0.0 (Beta)") },
                    leadingContent = { Icon(Icons.Default.Info, null) }
                )
                ListItem(
                    headlineContent = { Text("Privacy Policy") },
                    leadingContent = { Icon(Icons.Default.PrivacyTip, null) },
                     modifier = Modifier.clickable { /* Todo */ }
                )
            }
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}
