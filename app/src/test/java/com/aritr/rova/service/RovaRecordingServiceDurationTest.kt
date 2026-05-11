package com.aritr.rova.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bugfix slice — pure-JVM coverage of [recordedSegmentDurationMs], the
 * ns→ms conversion + non-positive fallback extracted from
 * [RovaRecordingService]'s `VideoRecordEvent.Finalize` branch.
 *
 * Why the helper exists: a finalized segment's persisted `durationMs`
 * must be the *actual* recorded length (CameraX
 * `RecordingStats.recordedDurationNanos`), not the *configured* clip
 * length — an early-stopped 60 s clip used to be persisted as 60 s, so
 * the Library/player showed "1:00 total" for a 0:29 file. The fallback
 * to the configured length only fires on a non-positive stat
 * (defensive — should not happen on a successful finalize, but a bogus
 * 0/negative stat must not be persisted as a 0 ms / negative segment).
 *
 * No Android framework here: the helper is a top-level pure fn over
 * plain `Long`s, mirroring the
 * [com.aritr.rova.service.wakelock.WakeLockPolicy] posture (pure logic
 * pulled out so the JVM suite carries the regression without a
 * service-wide DI seam).
 */
class RovaRecordingServiceDurationTest {

    private val configuredFallbackMs = 60_000L // a 1-minute clip config

    @Test fun `converts recorded nanos to millis`() {
        assertEquals(
            1_500L,
            recordedSegmentDurationMs(
                recordedDurationNanos = 1_500_000_000L,
                configuredFallbackMs = configuredFallbackMs
            )
        )
    }

    @Test fun `early-stopped clip reports its actual short length, not the configured length`() {
        // ~29 s actually recorded against a configured 60 s clip — the bug's headline case.
        assertEquals(
            29_000L,
            recordedSegmentDurationMs(
                recordedDurationNanos = 29_000_000_000L,
                configuredFallbackMs = configuredFallbackMs
            )
        )
    }

    @Test fun `floors a sub-millisecond remainder`() {
        // 1500.5 ms of nanos -> 1500 ms (integer ns / 1_000_000 division).
        assertEquals(
            1_500L,
            recordedSegmentDurationMs(
                recordedDurationNanos = 1_500_500_000L,
                configuredFallbackMs = configuredFallbackMs
            )
        )
    }

    @Test fun `zero recorded nanos falls back to the configured length`() {
        assertEquals(
            configuredFallbackMs,
            recordedSegmentDurationMs(
                recordedDurationNanos = 0L,
                configuredFallbackMs = configuredFallbackMs
            )
        )
    }

    @Test fun `negative recorded nanos falls back to the configured length`() {
        assertEquals(
            configuredFallbackMs,
            recordedSegmentDurationMs(
                recordedDurationNanos = -1L,
                configuredFallbackMs = configuredFallbackMs
            )
        )
    }

    @Test fun `recorded nanos under one millisecond floors to zero then falls back`() {
        // 500_000 ns = 0.5 ms -> 0 ms after the floor -> not > 0 -> fallback.
        assertEquals(
            configuredFallbackMs,
            recordedSegmentDurationMs(
                recordedDurationNanos = 500_000L,
                configuredFallbackMs = configuredFallbackMs
            )
        )
    }
}
