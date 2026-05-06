package com.aritr.rova.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aritr.rova.ui.screens.HistoryScreen
import com.aritr.rova.ui.screens.RecordScreen
import com.aritr.rova.ui.screens.RecordViewModel
import com.aritr.rova.ui.screens.SettingsScreen
import com.aritr.rova.ui.screens.SettingsViewModel

private data class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    // Slice 3 — RecordViewModel hoisted to MainScreen scope so the
    // bottom NavigationBar can read `serviceState.isPeriodicActive`
    // and gate tab switches without coupling MainScreen directly to
    // the recording service. Activity-scoped (no
    // `viewModelStoreOwner` override): one instance survives every
    // tab switch and binds to `RovaRecordingService` once per
    // activity lifecycle, instead of being re-created on every
    // navigation back to the Record destination.
    val recordViewModel: RecordViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val recordServiceState by recordViewModel.serviceState.collectAsStateWithLifecycle()
    val sessionLocked = recordServiceState.isPeriodicActive
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val destinations = listOf(
        MainDestination("record", "Record", Icons.Default.Videocam),
        MainDestination("history", "History", Icons.Default.History),
        MainDestination("settings", "Settings", Icons.Default.Settings)
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Slice 3 — "Locked while recording" hint pill above
                // the nav. Visible only while a periodic session is
                // active. Carried in the bottomBar slot so it never
                // overlaps the nav itself at any font scale.
                if (sessionLocked) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            tonalElevation = 4.dp,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "Navigation locked while recording"
                            }
                        ) {
                            Text(
                                text = "Locked while recording",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 6.dp
                                )
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(32.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        tonalElevation = 8.dp,
                        shadowElevation = 10.dp,
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        NavigationBar(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            tonalElevation = 0.dp,
                            // Slice 3 — disabled-state semantics surface
                            // through to assistive tech. Visual dim is
                            // a side effect of `enabled = false` on
                            // each NavigationBarItem; the parent
                            // `disabled` semantics is a redundant but
                            // explicit signal to TalkBack.
                            modifier = if (sessionLocked) {
                                Modifier.semantics { disabled() }
                            } else {
                                Modifier
                            }
                        ) {
                            destinations.forEach { destination ->
                                val selected = currentRoute == destination.route
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            destination.icon,
                                            contentDescription = destination.label
                                        )
                                    },
                                    label = { Text(destination.label) },
                                    selected = selected,
                                    enabled = !sessionLocked,
                                    onClick = {
                                        if (sessionLocked) return@NavigationBarItem
                                        if (!selected) {
                                            navController.navigate(destination.route) {
                                                popUpTo(navController.graph.startDestinationId)
                                                launchSingleTop = true
                                            }
                                        }
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        disabledIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                        disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "record",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("record") {
                val toHistory: () -> Unit = {
                    navController.navigate("history") {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                }
                RecordScreen(
                    onMergeFinished = toHistory,
                    onNavigateToHistory = toHistory,
                    viewModel = recordViewModel,
                    settingsViewModel = settingsViewModel
                )
            }
            composable("history") {
                HistoryScreen(onNavigateToRecord = {
                    navController.navigate("record") {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                })
            }
            composable("settings") { SettingsScreen(settingsViewModel = settingsViewModel) }
        }
    }
}
