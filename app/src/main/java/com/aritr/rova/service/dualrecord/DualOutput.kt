package com.aritr.rova.service.dualrecord

import java.io.File

/**
 * Phase 6.1a — paired output files for a single P+L segment.
 *
 * The two files MUST live in the same session directory and follow
 * the `segment_NNNN_P.mp4` + `segment_NNNN_L.mp4` paired-suffix
 * convention (`SegmentPathBuilder.build`). 6.1b's RovaRecordingService
 * consumer is responsible for path construction.
 */
data class DualOutput(
    val portraitFile: File,
    val landscapeFile: File,
)
