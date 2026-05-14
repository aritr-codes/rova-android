package com.aritr.rova.service.dualrecord

/**
 * Phase 6.1a — small enums co-located in one file (mirrors the
 * `WarningId.kt` precedent: one file, multiple small enums).
 *
 * See `docs/superpowers/specs/2026-05-14-phase-6.1a-dual-recording-foundation-design.md`
 * and `docs/adr/0008-dual-recording-architecture.md`.
 */

/** Which of the two paired outputs a frame, file, or track belongs to. */
enum class VideoSide { PORTRAIT, LANDSCAPE }

/** Video codec selection. HEVC reserved for a future slice. */
enum class VideoCodec { H264 }

/** Camera lens for the active session. */
enum class LensFacing { FRONT, BACK }
