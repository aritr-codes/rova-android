package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.Terminated
import java.util.Locale
import java.util.TimeZone

/**
 * ADR-0030 / spec §5.6 — pure join of session primitives + sidecar metadata into
 * a [LibraryRow]. Android-free so it is JVM-testable; the ViewModel supplies the
 * primitives (manifest-derived for finalized rows; legacy rows pass an empty
 * duration list → a 0 duration the card hides). Title resolves to the user's
 * [Input.customTitle] when set, else the derived [SmartTitle].
 */
object LibraryRowMapper {

    data class Input(
        val stableKey: String,
        val startedAtMillis: Long,
        val dateMillis: Long,
        val dateLabel: String,
        val sizeBytes: Long,
        val segmentDurationsMs: List<Long>,
        val topologyPersisted: String,
        val terminated: Terminated?,
        val exportState: ExportState,
        val customTitle: String?,
        val favorite: Boolean,
    )

    fun map(input: Input, locale: Locale, tz: TimeZone): LibraryRow {
        val durationMs = input.segmentDurationsMs.sum()
        val segmentCount = input.segmentDurationsMs.size.coerceAtLeast(1)
        val derived = SmartTitle.derive(input.startedAtMillis, segmentCount, durationMs, locale, tz)
        val title = input.customTitle?.takeIf { it.isNotBlank() } ?: derived
        return LibraryRow(
            stableKey = input.stableKey,
            title = title,
            dateLabel = input.dateLabel,
            dateMillis = input.dateMillis,
            durationMs = durationMs,
            sizeBytes = input.sizeBytes,
            // Raw size (NOT the coerced segmentCount above): legacy rows with no manifest → 0 so the
            // clip-count chip hides; the persisted-segment list = exactly the player's playable clips.
            clipCount = input.segmentDurationsMs.size,
            topology = CaptureTopology.fromPersisted(input.topologyPersisted),
            badge = StatusBadgePolicy.badgeFor(input.terminated, input.exportState),
            favorite = input.favorite,
        )
    }
}
