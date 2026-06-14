package com.aritr.rova.ui.library

import java.util.Locale

/** ADR-0030 — pure human-readable byte sizes for cards + per-day header totals. */
object StorageFormat {

    fun size(bytes: Long, locale: Locale): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.size - 1) {
            value /= 1024.0; unitIndex++
        }
        return String.format(locale, "%.1f %s", value, units[unitIndex])
    }

    fun dayTotal(sizes: List<Long>, locale: Locale): String = size(sizes.sum(), locale)
}
