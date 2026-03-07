package com.aritr.rova.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoPath = intent.getStringExtra("VIDEO_PATH") ?: return
        setContent {
            PreviewScreen(
                videoPath = videoPath,
                onBack = { finish() },
                onSave = { saveVideoLocal(it) },
                onShare = { shareVideoLocal(it) }
            )
        }
    }

    // Local functions to avoid conflicts
    private fun saveVideoLocal(videoPath: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val videoFile = File(videoPath)
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "Rova_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Pictures/Rova")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }

                    val resolver = contentResolver
                    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }

                    val itemUri = resolver.insert(collection, values)

                    itemUri?.let { uri ->
                        resolver.openOutputStream(uri).use { out ->
                            FileInputStream(videoFile).use { input ->
                                input.copyTo(out!!)
                            }
                        }
                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                }
                Toast.makeText(this@PreviewActivity, "✅ Saved to Gallery", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@PreviewActivity, "❌ Save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareVideoLocal(videoPath: String) {
        try {
            val file = File(videoPath)
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Rova Video"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "❌ Share failed", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    videoPath: String, 
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onShare: (String) -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var duration by remember { mutableLongStateOf(0L) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var videoView: VideoView? by remember { mutableStateOf(null) }

    // Update progress loop
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            videoView?.let {
                if (it.isPlaying) {
                    currentPosition = it.currentPosition.toLong()
                }
            }
            delay(100) // Update every 100ms
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rova Preview", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.5f))
            )
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            // Video Player
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(videoPath)
                        setOnPreparedListener { mp ->
                            mp.start()
                            mp.isLooping = true
                            duration = mp.duration.toLong()
                            isPlaying = true
                        }
                        setOnCompletionListener {
                            isPlaying = false
                        }
                        videoView = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        // Toggle play/pause on tap
                        videoView?.let {
                            if (it.isPlaying) {
                                it.pause()
                                isPlaying = false
                            } else {
                                it.start()
                                isPlaying = true
                            }
                        }
                    }
            )

            // Center Play/Pause Button (Overlay)
            if (!isPlaying) {
                FloatingActionButton(
                    onClick = {
                        videoView?.start()
                        isPlaying = true
                    },
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(48.dp))
                }
            }

            // Bottom Controls
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(16.dp)
            ) {
                // Progress Slider
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatDuration(currentPosition),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                        onValueChange = { value ->
                            val newPos = (value * duration).toLong()
                            currentPosition = newPos
                            videoView?.seekTo(newPos.toInt())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.Gray
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    Text(
                        text = formatDuration(duration),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Action Buttons
                videoView?.let { view ->
                    BottomControls(
                        videoView = view,
                        onSave = { onSave(videoPath) },
                        onShare = { onShare(videoPath) }
                    )
                }
            }
        }
    }
}

@Composable
fun BottomControls(
    videoView: VideoView,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // REPLAY
        IconButton(onClick = { videoView.seekTo(0); videoView.start() }) {
            Icon(Icons.Default.Replay, contentDescription = "Replay", tint = Color.White)
        }
        
        // SAVE
        IconButton(onClick = onSave) {
            Icon(Icons.Default.Download, contentDescription = "Save", tint = Color.White)
        }
        
        // SHARE
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
        }
    }
}

fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    return String.format("%d:%02d", minutes, seconds)
}
