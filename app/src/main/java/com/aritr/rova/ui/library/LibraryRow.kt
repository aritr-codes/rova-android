package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.data.StopReason
import com.aritr.rova.service.dualrecord.VideoSide

/**
 * ADR-0030 — pure row model the Library grid/list/hero render and [LibraryQuery]
 * operates over. Built by the ViewModel from VideoItem + SessionManifest + the
 * sidecar; carries no Android types so it is fully JVM-testable.
 *
 * @property title resolved display title (customTitle ?: SmartTitle).
 * @property dateLabel pre-formatted date string used for search matching.
 */
data class LibraryRow(
    val stableKey: String,
    val title: String,
    val dateLabel: String,
    val dateMillis: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    /**
     * Number of persisted/playable clips (segments) in the session — the same set the player's
     * SegmentedTimeline renders. Polish P3 (session identity). 0 for legacy file-scan rows with no
     * manifest (the clip-count chip then hides; usage aggregation counts such a row as 1 clip).
     */
    val clipCount: Int,
    val topology: CaptureTopology,
    val badge: LibraryBadge?,
    val favorite: Boolean,
    /**
     * Orientation this tile represents — drives the orientation glyph (owner request, 2026-06-15).
     * DualShot rows resolve it from their per-side discriminator; single-mode rows from the video
     * resolution. null = no verdict (square / legacy / SAF) → the card renders no glyph.
     */
    val orientation: LibraryOrientation? = null,
    /** When [badge] == AUTO_STOPPED, the gate reason (THERMAL/LOW_STORAGE) that picks the badge glyph. Else null. */
    val badgeStopReason: StopReason? = null,
    /** [RecordingIdentity.sessionKey] of the owning session; null = sessionless/legacy row. */
    val sessionKey: String? = null,
    /** Per-side row discriminator; null on single-mode + aggregated session rows. */
    val side: VideoSide? = null,
    /** Non-empty ONLY on aggregated DualShot session rows (Task 5 aggregator, PR-A unwired). */
    val sides: List<LibrarySessionSide> = emptyList(),
    /**
     * Saved playback position (ms) for the side this row's tap would play: the row's own side for
     * single/per-side rows, the PORTRAIT-first non-null side on aggregated session rows. Sole
     * consumer is the v3.3 playback-progress hairline (the bento redesign retired the resume pill),
     * so this is the EXACT sidecar slot value — never the legacy ""-slot fallback the player's own
     * resume read keeps (spec v3.3: a legacy position must not paint one side's truth on both
     * panes) — and the player still re-reads its own resume position on open.
     */
    val resumePositionMs: Long? = null,
)

/**
 * One playable side of an aggregated DualShot session row (spec §3.4).
 * [stableKey] is the ORIGINAL per-side row key — PR-B resolves it to the playable file/uri.
 * [resumePositionMs] is that side's exact-slot saved position (v3.3 per-pane hairline).
 */
data class LibrarySessionSide(
    val side: VideoSide,
    val stableKey: String,
    val durationMs: Long,
    val clipCount: Int,
    val resumePositionMs: Long? = null,
)

/** Sort options for the Library (decision C). */
enum class LibrarySort {
    NEWEST, OLDEST, LONGEST, LARGEST;

    /**
     * Date-ordered sorts get day-grouped headers ("Today" / "Yesterday" / date); size- and
     * duration-ordered sorts render as one flat, header-less list. Day headers are a chronological
     * affordance — under LONGEST/LARGEST same-day rows are NOT contiguous, so per-day buckets would
     * both read wrong and (fatally) collide LazyList keys. See [LibraryDayGrouping.groupForSort].
     */
    val isChronological: Boolean get() = this == NEWEST || this == OLDEST
}

/**
 * Filter facets (decision C). [topology] null = any. [search] blank = no search.
 * Vault is a separate destination, not a filter here (ADR-0030).
 */
data class LibraryFilter(
    val favoritesOnly: Boolean = false,
    val topology: CaptureTopology? = null,
    val search: String = "",
)
