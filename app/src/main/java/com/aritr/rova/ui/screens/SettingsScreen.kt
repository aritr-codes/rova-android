package com.aritr.rova.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aritr.rova.ui.components.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsViewModel: SettingsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val enableBeeps by settingsViewModel.enableBeeps.collectAsStateWithLifecycle()
    val vibrateAlerts by settingsViewModel.vibrateAlerts.collectAsStateWithLifecycle()
    val keepScreenOn by settingsViewModel.keepScreenOn.collectAsStateWithLifecycle()
    val autoDeleteEnabled by settingsViewModel.autoDeleteEnabled.collectAsStateWithLifecycle()
    val autoDeleteKeepLatest by settingsViewModel.autoDeleteKeepLatest.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Settings", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Shape how Rova behaves during unattended sessions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                SettingsHeroCard(
                    enableBeeps = enableBeeps,
                    vibrateAlerts = vibrateAlerts,
                    keepScreenOn = keepScreenOn
                )

                SettingsSection(
                    title = "Capture behavior",
                    subtitle = "Toggles that affect how the recorder feels while you are filming."
                ) {
                    SwitchRow(
                        Icons.Default.Smartphone,
                        "Keep screen on",
                        "Prevent the display from timing out while you frame a shot.",
                        keepScreenOn
                    ) { settingsViewModel.keepScreenOn.value = it }

                    SwitchRow(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        "Sound cues",
                        "Play start and stop beeps so you can confirm capture hands-free.",
                        enableBeeps
                    ) { settingsViewModel.enableBeeps.value = it }

                    SwitchRow(
                        Icons.Default.Vibration,
                        "Haptic feedback",
                        "Reserve vibration for confirmation and future alerts.",
                        vibrateAlerts
                    ) { settingsViewModel.vibrateAlerts.value = it }
                }

                SettingsSection(
                    title = "Storage",
                    subtitle = "Tools for keeping long recording sessions manageable."
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp
                    ) {
                        Column {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                headlineContent = { Text("Auto-delete old recordings") },
                                supportingContent = {
                                    Text(
                                        if (autoDeleteEnabled) {
                                            "Keeping the latest $autoDeleteKeepLatest finalized recordings."
                                        } else {
                                            "Off — every recording stays until you delete it."
                                        }
                                    )
                                },
                                leadingContent = { androidx.compose.material3.Icon(Icons.Default.DeleteSweep, null) },
                                trailingContent = {
                                    androidx.compose.material3.Switch(
                                        checked = autoDeleteEnabled,
                                        onCheckedChange = { settingsViewModel.autoDeleteEnabled.value = it }
                                    )
                                },
                                modifier = Modifier.clickable {
                                    settingsViewModel.autoDeleteEnabled.value = !autoDeleteEnabled
                                }
                            )
                            if (autoDeleteEnabled) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(5, 10, 25, 50).forEach { option ->
                                        androidx.compose.material3.FilterChip(
                                            selected = autoDeleteKeepLatest == option,
                                            onClick = {
                                                settingsViewModel.autoDeleteKeepLatest.value = option
                                            },
                                            label = { Text("$option") }
                                        )
                                    }
                                }
                            }
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                headlineContent = { Text("Clear cache") },
                                supportingContent = { Text("Removes generated preview and temporary app files.") },
                                leadingContent = { androidx.compose.material3.Icon(Icons.Default.CleaningServices, null) },
                                modifier = Modifier.clickable {
                                    scope.launch(Dispatchers.IO) {
                                        val cacheDir = context.cacheDir
                                        val deleted = cacheDir.walkBottomUp().sumOf { file ->
                                            if (file != cacheDir && file.delete()) file.length() else 0L
                                        }
                                        val mb = deleted / 1024.0 / 1024.0
                                        launch(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                if (mb > 0.1) "Cleared %.1f MB".format(mb) else "Cache is empty",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                SettingsSection(
                    title = "About",
                    subtitle = "Version info and policy links."
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp
                    ) {
                        Column {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                headlineContent = { Text("Version") },
                                supportingContent = { Text(com.aritr.rova.BuildConfig.VERSION_NAME) },
                                leadingContent = { androidx.compose.material3.Icon(Icons.Default.Info, null) }
                            )
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                headlineContent = { Text("Privacy policy") },
                                supportingContent = { Text("Open the hosted policy in your browser.") },
                                leadingContent = { androidx.compose.material3.Icon(Icons.Default.PrivacyTip, null) },
                                modifier = Modifier.clickable {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://aritr-codes.github.io/rova-privacy/"))
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeroCard(
    enableBeeps: Boolean,
    vibrateAlerts: Boolean,
    keepScreenOn: Boolean
) {
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Recorder profile",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "Make Rova feel predictable before you walk away from the phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreferenceBadge("Screen ${if (keepScreenOn) "awake" else "managed"}")
                PreferenceBadge("Beeps ${if (enableBeeps) "on" else "off"}")
                PreferenceBadge("Haptics ${if (vibrateAlerts) "on" else "off"}")
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 2.dp,
            shadowElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

@Composable
private fun PreferenceBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
