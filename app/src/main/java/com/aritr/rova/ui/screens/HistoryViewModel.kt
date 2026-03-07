package com.aritr.rova.ui.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class VideoItem(
    val file: File,
    val thumbnail: Bitmap?,
    val resolution: String
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val _items = MutableStateFlow<List<VideoItem>>(emptyList())
    val items: StateFlow<List<VideoItem>> = _items.asStateFlow()

    /**
     * Scans the videos directory and loads thumbnail + resolution for every file on
     * Dispatchers.IO so the main thread is never blocked.
     */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val dir = File(context.getExternalFilesDir("videos"), "")
            val files = if (dir.exists()) {
                dir.listFiles()
                    ?.filter { it.extension == "mp4" && !it.name.startsWith("segment_bg_") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
            } else {
                emptyList()
            }

            val newItems = files.map { file ->
                VideoItem(
                    file = file,
                    thumbnail = VideoMetadataUtils.getThumbnail(file.absolutePath),
                    resolution = VideoMetadataUtils.getResolutionLabel(file.absolutePath)
                )
            }
            _items.value = newItems
        }
    }
}
