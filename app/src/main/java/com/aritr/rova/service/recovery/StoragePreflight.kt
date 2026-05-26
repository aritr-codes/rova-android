package com.aritr.rova.service.recovery

/**
 * Milestone 2 — preflight headroom check for recovery merge. Called from
 * [com.aritr.rova.service.RovaRecordingService.handleRecoveryMergeStart]
 * BEFORE `startForegroundForRecoveryMerge`; on failure, the existing
 * `CANT_MERGE` signal (Phase 4.3, [com.aritr.rova.ui.warnings.WarningId.CANT_MERGE])
 * is raised and the manifest stays non-terminal.
 *
 * `FINALIZE_HEADROOM_BYTES` is the merge-output safety margin — covers
 * filesystem block-aligned overhead, MediaMuxer's own scratch needs,
 * and concurrent-app drift between the check and the muxer open.
 *
 * Spec: `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md` §5 #3.
 */
internal object StoragePreflight {
    /** 50 MiB headroom. */
    const val FINALIZE_HEADROOM_BYTES: Long = 50L * 1024L * 1024L

    fun hasHeadroom(availableBytes: Long, accumulatedSessionBytes: Long): Boolean =
        availableBytes - accumulatedSessionBytes >= FINALIZE_HEADROOM_BYTES
}
