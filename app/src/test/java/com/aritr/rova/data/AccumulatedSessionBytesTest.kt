package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 1.6 — load-bearing regression suite for
 * [StorageEstimator.accumulatedSessionBytes].
 *
 * Why filesystem-first matters: segment persistence is async on a serial
 * dispatcher. Between segments N and N+1 the manifest can report N
 * records while disk holds N+1 files. The previous draft of the gate
 * used `manifest.segments.sumOf { sizeBytes }` and was therefore fooled
 * into permitting a segment that would push the disk past the merge
 * threshold. These tests prove that bug cannot recur.
 */
class AccumulatedSessionBytesTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var sessionDir: File

    private val FHD = "FHD"
    private val FHD_BPS = 2L * 1024 * 1024
    private val DURATION_S = 30L
    private val EST_PER_SEGMENT = DURATION_S * FHD_BPS  // 60 MB

    @Before
    fun setUp() {
        sessionDir = tmp.newFolder("session_test")
    }

    private fun writeSegment(index: Int, sizeBytes: Long) {
        val name = "segment_${"%04d".format(index)}.mp4"
        val f = File(sessionDir, name)
        // RandomAccessFile.setLength is atomic and avoids actually
        // allocating real bytes — sparse files keep the test fast.
        java.io.RandomAccessFile(f, "rw").use { raf -> raf.setLength(sizeBytes) }
    }

    /**
     * **Load-bearing regression test.** Disk holds N+1 segment files but
     * the manifest (segmentCount) only reports N — the pre-fix gate would
     * have under-counted by a full segment. `accumulatedSessionBytes`
     * MUST return at least the on-disk total.
     */
    @Test
    fun `gate sees full disk pressure even when segmentCount lags by one`() {
        // 5 files on disk, each = 1× estimate. Manifest reports 3.
        repeat(5) { i -> writeSegment(i + 1, EST_PER_SEGMENT) }
        val manifestSegmentCount = 3

        val accumulated = StorageEstimator.accumulatedSessionBytes(
            sessionDir = sessionDir,
            segmentCount = manifestSegmentCount,
            durationSeconds = DURATION_S,
            resolution = FHD
        )

        // Without max(actual, estimated) the result would be 3 × estimate;
        // with it, must be ≥ 5 × estimate.
        assertTrue(
            "accumulated ($accumulated) must reflect 5 on-disk segments, not 3",
            accumulated >= 5 * EST_PER_SEGMENT
        )
        assertEquals(5 * EST_PER_SEGMENT, accumulated)
    }

    /**
     * Bitrate-spike: the recorder produced segments larger than the
     * conservative estimate. `accumulated` must return `actual`, not
     * `estimated`.
     */
    @Test
    fun `bitrate spike returns actual file-length sum, not estimate`() {
        repeat(3) { i -> writeSegment(i + 1, 2 * EST_PER_SEGMENT) }
        val accumulated = StorageEstimator.accumulatedSessionBytes(
            sessionDir = sessionDir,
            segmentCount = 3,
            durationSeconds = DURATION_S,
            resolution = FHD
        )
        assertEquals(3L * 2 * EST_PER_SEGMENT, accumulated)
    }

    /**
     * Segments smaller than estimate (under-bitrate, e.g. low-motion
     * scene at SD): `accumulated` must hold the floor of
     * `segmentCount × estimate` rather than trusting the small files.
     */
    @Test
    fun `under-bitrate segments fall back to estimated lower bound`() {
        repeat(3) { i -> writeSegment(i + 1, EST_PER_SEGMENT / 4) }
        val accumulated = StorageEstimator.accumulatedSessionBytes(
            sessionDir = sessionDir,
            segmentCount = 3,
            durationSeconds = DURATION_S,
            resolution = FHD
        )
        assertEquals(3L * EST_PER_SEGMENT, accumulated)
    }

    /**
     * Empty session (no segments persisted yet, no files on disk):
     * accumulated bytes must be 0.
     */
    @Test
    fun `empty session returns zero`() {
        val accumulated = StorageEstimator.accumulatedSessionBytes(
            sessionDir = sessionDir,
            segmentCount = 0,
            durationSeconds = DURATION_S,
            resolution = FHD
        )
        assertEquals(0L, accumulated)
    }

    /**
     * Stray non-segment files in the session dir (manifest.json, .tmp,
     * stray output) MUST NOT be counted.
     */
    @Test
    fun `non-segment files are not counted`() {
        writeSegment(1, EST_PER_SEGMENT)
        File(sessionDir, "manifest.json").writeText("{}")
        File(sessionDir, "manifest.json.tmp").writeText("{}")
        File(sessionDir, "stray.mp4").writeText("not ours")

        val accumulated = StorageEstimator.accumulatedSessionBytes(
            sessionDir = sessionDir,
            segmentCount = 1,
            durationSeconds = DURATION_S,
            resolution = FHD
        )
        assertEquals(EST_PER_SEGMENT, accumulated)
    }

    /**
     * sessionDir does not exist: `listFiles()` returns null. Must
     * fall back to estimated, not throw.
     */
    @Test
    fun `missing sessionDir falls back to estimated`() {
        val ghost = File(tmp.root, "does_not_exist")
        val accumulated = StorageEstimator.accumulatedSessionBytes(
            sessionDir = ghost,
            segmentCount = 4,
            durationSeconds = DURATION_S,
            resolution = FHD
        )
        assertEquals(4L * EST_PER_SEGMENT, accumulated)
    }

    /**
     * Final-merge output file (merged_*.mp4 or similar) MUST NOT be
     * counted as a segment — segment_*.mp4 prefix discriminator only.
     */
    @Test
    fun `merged output file not counted as segment`() {
        writeSegment(1, EST_PER_SEGMENT)
        File(sessionDir, "merged_output.mp4").apply {
            java.io.RandomAccessFile(this, "rw").use { raf -> raf.setLength(EST_PER_SEGMENT) }
        }
        val accumulated = StorageEstimator.accumulatedSessionBytes(
            sessionDir = sessionDir,
            segmentCount = 1,
            durationSeconds = DURATION_S,
            resolution = FHD
        )
        assertEquals(EST_PER_SEGMENT, accumulated)
    }
}
