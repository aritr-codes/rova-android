package com.aritr.rova.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.ui.PreviewActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val dateGroupFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
private val timeDisplayFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel(), onNavigateToRecord: () -> Unit = {}) {
    val context = LocalContext.current
    val items by viewModel.items.collectAsStateWithLifecycle()

    // Multi-select state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(setOf<File>()) }

    // Load on entry
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    // Clean up stale selections after a refresh
    LaunchedEffect(items) {
        if (isSelectionMode) {
            val existingFiles = items.map { it.file }.toSet()
            selectedFiles = selectedFiles.intersect(existingFiles)
            if (selectedFiles.isEmpty()) isSelectionMode = false
        }
    }

    val groupedItems = remember(items) {
        items.groupBy { item ->
            dateGroupFormat.format(Date(item.file.lastModified()))
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
                            val uris = ArrayList(selectedFiles.map {
                                FileProvider.getUriForFile(context, "${context.packageName}.provider", it)
                            })
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
                            selectedFiles.forEach { it.delete() }
                            isSelectionMode = false
                            selectedFiles = emptySet()
                            viewModel.refresh()
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
                        if (items.isNotEmpty()) {
                            IconButton(onClick = { /* Sort/Filter */ }) { Icon(Icons.Default.Sort, "Sort") }
                            IconButton(onClick = { /* Search */ }) { Icon(Icons.Default.Search, "Search") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { innerPadding ->
        if (items.isEmpty()) {
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
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Your recorded videos will appear here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    FilledTonalButton(onClick = onNavigateToRecord) {
                        Icon(Icons.Default.Videocam, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Start Recording")
                    }
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
                groupedItems.forEach { (date, dateItems) ->
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

                    items(dateItems, key = { it.file.absolutePath }) { item ->
                        val isSelected = selectedFiles.contains(item.file)
                        VideoCard(
                            item = item,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onToggleSelection = {
                                if (isSelectionMode) {
                                    selectedFiles = if (isSelected) selectedFiles - item.file else selectedFiles + item.file
                                    if (selectedFiles.isEmpty()) isSelectionMode = false
                                } else {
                                    isSelectionMode = true
                                    selectedFiles = setOf(item.file)
                                }
                            },
                            onPlay = {
                                if (isSelectionMode) {
                                    selectedFiles = if (isSelected) selectedFiles - item.file else selectedFiles + item.file
                                    if (selectedFiles.isEmpty()) isSelectionMode = false
                                } else {
                                    val intent = Intent(context, PreviewActivity::class.java).apply {
                                        putExtra("VIDEO_PATH", item.file.absolutePath)
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
    item: VideoItem,
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
            .then(
                if (isSelected)
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                else
                    Modifier
            ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            VideoThumbnail(
                thumbnail = item.thumbnail,
                modifier = Modifier
                    .size(80.dp, 60.dp)
                    .clip(MaterialTheme.shapes.small)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatFileSize(item.file.length()) + " • " + formatTime(item.file.lastModified()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = item.resolution,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoThumbnail(thumbnail: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null) {
            val imageBitmap = remember(thumbnail) { thumbnail.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(24.dp)
            )
        } else {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatFileSize(size: Long): String {
    val kb = size / 1024.0
    val mb = kb / 1024.0
    return if (mb > 1) String.format("%.1f MB", mb) else String.format("%.0f KB", kb)
}

private fun formatTime(millis: Long): String {
    return timeDisplayFormat.format(Date(millis))
}
