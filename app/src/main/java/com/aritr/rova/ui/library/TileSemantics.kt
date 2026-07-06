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
        val autoStoppedWord: String = "",
    )

    fun describe(row: LibraryRow, f: Fragments): String = buildString {
        append(row.title)
        append(", ").append(f.durationWord).append(' ').append(durationSpeech(row.durationMs))
        when (row.badge) {
            LibraryBadge.RECOVERED -> append(", ").append(f.recoveredWord)
            LibraryBadge.INTERRUPTED -> append(", ").append(f.interruptedWord)
            LibraryBadge.AUTO_STOPPED -> if (f.autoStoppedWord.isNotBlank()) append(", ").append(f.autoStoppedWord)
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

    /**
     * Bento single-tile merged label (spec §8 / Task 4). Verb swaps Play/Select with
     * [selecting] (matches the row-list `selectedLabel`/`notSelectedLabel` split); orientation
     * word is only spoken when playing (selection mode reads position/status only, not the
     * decorative frame glyph — same rationale as [describe]'s orientation clause).
     */
    fun bentoLabel(
        selecting: Boolean,
        orientationWord: String?,
        dayAndTime: String,
        duration: String,
        favorite: Boolean,
        latest: Boolean,
        progressPercent: Int? = null,
    ): String = buildString {
        append(if (selecting) "Select" else "Play")
        if (!selecting && orientationWord != null) {
            append(' ').append(orientationWord).append(", ")
        } else {
            append(' ')
        }
        append(dayAndTime)
        append(", ").append(duration)
        if (favorite) append(", favorite")
        if (latest) append(", latest recording")
        appendProgress(selecting, progressPercent)
    }

    /** Bento diptych pane label (spec §8 / Task 4) — names the side ("Portrait"/"Landscape side"). */
    fun bentoPaneLabel(selecting: Boolean, side: String, dayAndTime: String, duration: String, progressPercent: Int? = null): String =
        buildString {
            append("${if (selecting) "Select" else "Play"} $side side, $dayAndTime, $duration")
            appendProgress(selecting, progressPercent)
        }

    /**
     * v3.3 hairline spoken fraction (spec `pgSpeak`) — the WCAG 1.4.11 conforming alternative for
     * the 2dp bar, folded into the tile/pane label so it is not a separate focus stop. Play labels
     * only: selection mode hides the bar, so it speaks nothing.
     */
    private fun StringBuilder.appendProgress(selecting: Boolean, percent: Int?) {
        if (!selecting && percent != null) append(", partially watched ").append(percent).append('%')
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
