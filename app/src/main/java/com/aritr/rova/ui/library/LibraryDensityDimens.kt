package com.aritr.rova.ui.library

/** Persisted row-density choice (spec §3.7). Stored by name in RovaSettings.libraryDensity. */
enum class LibraryDensity { COMFORTABLE, COMPACT }

/** Session-list dimensions in raw dp Ints (pure — composables convert to Dp). */
data class LibraryDensitySpec(
    val thumbWidthDp: Int,
    val thumbHeightDp: Int,
    val latestThumbWidthDp: Int,
    val latestThumbHeightDp: Int,
    val rowMinHeightDp: Int,
    val rowVerticalPadDp: Int,
)

object LibraryDensityDimens {
    private val COMFORTABLE = LibraryDensitySpec(
        thumbWidthDp = 104, thumbHeightDp = 60,
        latestThumbWidthDp = 128, latestThumbHeightDp = 74,
        rowMinHeightDp = 64, rowVerticalPadDp = 9,
    )
    private val COMPACT = LibraryDensitySpec(
        thumbWidthDp = 84, thumbHeightDp = 50,
        latestThumbWidthDp = 112, latestThumbHeightDp = 64,
        rowMinHeightDp = 56, rowVerticalPadDp = 6,
    )

    fun spec(density: LibraryDensity): LibraryDensitySpec = when (density) {
        LibraryDensity.COMFORTABLE -> COMFORTABLE
        LibraryDensity.COMPACT -> COMPACT
    }
}
