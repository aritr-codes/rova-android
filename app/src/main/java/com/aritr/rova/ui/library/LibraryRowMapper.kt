package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.dualrecord.VideoSide
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
        val stopReason: StopReason,
        val exportState: ExportState,
        val customTitle: String?,
        val favorite: Boolean,
        /** DualShot per-side discriminator (null for single-mode/legacy) — authoritative orientation. */
        val side: VideoSide? = null,
        /** Decoded thumbnail pixel size (rotation-corrected) — orientation source for single-mode rows. */
        val thumbWidthPx: Int = 0,
        val thumbHeightPx: Int = 0,
    )

    fun map(input: Input, locale: Locale, tz: TimeZone): LibraryRow {
        val durationMs = input.segmentDurationsMs.sum()
        val derived = SmartTitle.derive(input.startedAtMillis, locale, tz)
        val title = input.customTitle?.takeIf { it.isNotBlank() } ?: derived
        val badge = StatusBadgePolicy.badgeFor(input.terminated, input.stopReason, input.exportState)
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
            badge = badge,
            badgeStopReason = if (badge == LibraryBadge.AUTO_STOPPED) input.stopReason else null,
            favorite = input.favorite,
            orientation = OrientationResolver.resolve(input.side, input.thumbWidthPx, input.thumbHeightPx),
        )
    }
}
