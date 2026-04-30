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

    // Cache thumbnails and resolution to avoid re-extracting on every refresh()
    private val metadataCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Bitmap?, String>>()

    /**
     * Scans the videos directory and loads thumbnail + resolution for every file on
     * Dispatchers.IO so the main thread is never blocked.
     */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val dir = File(context.getExternalFilesDir("videos"), "")
            // C18: merged outputs now live under videos/<sessionId>/Rova_*.mp4
            // (one level deep). Top-level Rova_*.mp4 from pre-Phase-1.1 builds
            // is still surfaced so existing recordings remain visible.
            // Per-session segment files (segment_NNNN.mp4) are NOT shown.
            val files = if (dir.exists()) {
                val all = dir.listFiles()?.flatMap { entry ->
                    when {
                        entry.isDirectory ->
                            entry.listFiles()
                                ?.filter { it.extension == "mp4" && it.name.startsWith("Rova_") }
                                ?.toList() ?: emptyList()
                        entry.extension == "mp4" &&
                            !entry.name.startsWith("segment_bg_") &&
                            !entry.name.startsWith("segment_") ->
                            listOf(entry)
                        else -> emptyList()
                    }
                } ?: emptyList()
                all.sortedByDescending { it.lastModified() }
            } else {
                emptyList()
            }

            val newItems = files.map { file ->
                val path = file.absolutePath
                val cached = metadataCache[path]
                if (cached != null) {
                    VideoItem(file = file, thumbnail = cached.first, resolution = cached.second)
                } else {
                    val thumb = VideoMetadataUtils.getThumbnail(path)
                    val res = VideoMetadataUtils.getResolutionLabel(path)
                    metadataCache[path] = Pair(thumb, res)
                    VideoItem(file = file, thumbnail = thumb, resolution = res)
                }
            }

            // Clean stale entries for deleted files
            val currentPaths = files.map { it.absolutePath }.toSet()
            metadataCache.keys.retainAll(currentPaths)

            _items.value = newItems
        }
    }

    /**
     * Deletes the given files on a background thread, then refreshes the list.
     */
    fun deleteFiles(files: Set<File>) {
        viewModelScope.launch(Dispatchers.IO) {
            files.forEach { it.delete() }
            refresh()
        }
    }

    override fun onCleared() {
        metadataCache.values.forEach { (thumbnail, _) ->
            thumbnail?.recycle()
        }
        metadataCache.clear()
        super.onCleared()
    }
}
