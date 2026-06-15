package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology

/**
 * ADR-0030 / spec §8 — builds the single merged tile contentDescription so the
 * duration / status / P+L badges are not separate TalkBack focus stops. Pure: the
 * caller supplies localized word [Fragments] (from string resources) and this only
 * orders them. Duration speech reuses the m/s shape of [SmartTitle].
 */
object TileSemantics {

    data class Fragments(
        val durationWord: String,
        val recoveredWord: String,
        val interruptedWord: String,
        val dualWord: String,
        val portraitWord: String = "",
        val landscapeWord: String = "",
    )

    fun describe(row: LibraryRow, f: Fragments): String = buildString {
        append(row.title)
        append(", ").append(f.durationWord).append(' ').append(durationSpeech(row.durationMs))
        when (row.badge) {
            LibraryBadge.RECOVERED -> append(", ").append(f.recoveredWord)
            LibraryBadge.INTERRUPTED -> append(", ").append(f.interruptedWord)
            null -> {}
        }
        if (row.topology == CaptureTopology.DualShot) append(", ").append(f.dualWord)
        // Orientation glyph is decorative on the tile — speak it here so it is not lost to TalkBack.
        when (row.orientation) {
            LibraryOrientation.PORTRAIT -> if (f.portraitWord.isNotBlank()) append(", ").append(f.portraitWord)
            LibraryOrientation.LANDSCAPE -> if (f.landscapeWord.isNotBlank()) append(", ").append(f.landscapeWord)
            null -> {}
        }
    }

    private fun durationSpeech(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return when {
            m == 0L -> "${s}s"
            s == 0L -> "${m}m"
            else -> "${m}m ${s}s"
        }
    }
}
