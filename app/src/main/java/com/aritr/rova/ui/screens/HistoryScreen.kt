package com.aritr.rova.ui.screens

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    var videoFiles by remember { mutableStateOf(emptyList<File>()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    
    // Multi-select State
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(setOf<File>()) }

    LaunchedEffect(refreshTrigger) {
        val dir = File(context.getExternalFilesDir("videos"), "")
        if (dir.exists()) {
            videoFiles = dir.listFiles()
                ?.filter { it.extension == "mp4" && !it.name.startsWith("segment_bg_") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }
        // Reset selection on refresh if files gone
        if(isSelectionMode) {
             selectedFiles = selectedFiles.filter { it.exists() }.toSet()
             if(selectedFiles.isEmpty()) isSelectionMode = false
        }
    }

    val groupedFiles = remember(videoFiles) {
        videoFiles.groupBy { file ->
            SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date(file.lastModified()))
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedFiles.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { isSelectionMode = false; selectedFiles = emptySet() }) {
                            Icon(Icons.Default.Close, "Close Selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            // Share Multiple
                             val uris = ArrayList(selectedFiles.map { FileProvider.getUriForFile(context, "${context.packageName}.provider", it) })
                             val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                 type = "video/mp4"
                                 putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                 addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                             }
                             context.startActivity(Intent.createChooser(intent, "Share Videos"))
                        }) {
                            Icon(Icons.Default.Share, "Share")
                        }
                        IconButton(onClick = {
                            // Delete Multiple
                            selectedFiles.forEach { it.delete() }
                            refreshTrigger++
                            isSelectionMode = false
                            selectedFiles = emptySet()
                        }) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = { Text("History") },
                    actions = {
                        IconButton(onClick = { /* Sort/Filter */ }) { Icon(Icons.Default.Sort, "Sort") }
                        IconButton(onClick = { /* Search */ }) { Icon(Icons.Default.Search, "Search") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { innerPadding ->
        if (videoFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No recordings yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                groupedFiles.forEach { (date, files) ->
                    stickyHeader {
                        Text(
                            text = date,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(vertical = 8.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    items(files) { file ->
                        val isSelected = selectedFiles.contains(file)
                        VideoCard(
                            file = file,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onToggleSelection = {
                                if (isSelectionMode) {
                                    selectedFiles = if (isSelected) selectedFiles - file else selectedFiles + file
                                    if (selectedFiles.isEmpty()) isSelectionMode = false
                                } else {
                                    isSelectionMode = true
                                    selectedFiles = setOf(file)
                                }
                            },
                            onPlay = {
                                if (isSelectionMode) {
                                    selectedFiles = if (isSelected) selectedFiles - file else selectedFiles + file
                                    if (selectedFiles.isEmpty()) isSelectionMode = false
                                } else {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "video/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    file: File,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onPlay: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onToggleSelection
            )
            .then(if(isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox for selection mode
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Thumbnail Placeholder
            Box(
                modifier = Modifier
                    .size(80.dp, 60.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = "Play", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatFileSize(file.length()) + " • " + formatTime(file.lastModified()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Resolution badge placeholder if metadata available
                 Surface(
                     shape = MaterialTheme.shapes.extraSmall,
                     color = MaterialTheme.colorScheme.secondaryContainer,
                     modifier = Modifier.padding(top = 4.dp)
                 ) {
                     Text(
                         text = "FHD", // Placeholder, need integration with metadata
                         style = MaterialTheme.typography.labelSmall,
                         modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                         color = MaterialTheme.colorScheme.onSecondaryContainer
                     )
                 }
            }
        }
    }
}

private fun formatFileSize(size: Long): String {
    val kb = size / 1024.0
    val mb = kb / 1024.0
    return if (mb > 1) String.format("%.1f MB", mb) else String.format("%.0f KB", kb)
}

private fun formatTime(millis: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
}
