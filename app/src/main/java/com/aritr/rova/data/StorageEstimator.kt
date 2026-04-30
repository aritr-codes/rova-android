package com.aritr.rova.data

import com.aritr.rova.utils.RovaLog
import java.io.File

/**
 * Phase 1.6 (ROADMAP_v6 §1.6 / ADR 0003 / risk C7) — pure storage-budget math.
 *
 * Two consumers, one source of truth:
 * - **Session-level preflight** (`onStartCommand`, before `createSession`) calls
 *   [estimatePeakBytes] to size the session's reserved disk budget per the
 *   tier's peak: capture + the merge-overhead leg.
 * - **Per-segment storage gate** ([com.aritr.rova.service.RovaRecordingService]'s
 *   `checkSegmentGates`) calls [bytesPerSecondForResolution] for the next-segment
 *   estimate and applies the `(tierMultiplier - 1)` coefficient to the
 *   live-session accumulated bytes.
 *
 * Both consumers must use the **same** numeric constants here. `bytes/sec`
 * is BYTES per second, not bits/sec — the previous draft of the gate
 * divided by 8 (B-fix-3) and was ~8× too permissive; centralizing here
 * makes that class of unit-mismatch impossible.
 */
object StorageEstimator {

    /**
     * Loop horizon used for preflight when `limitLoops == -1` (indefinite).
     * Preflight cannot reserve infinite disk, so we reserve enough for this
     * many loops and rely on the per-segment gate to terminate the session
     * before disk pressure becomes unmergeable.
     *
     * Trade-off: a larger horizon makes preflight stricter (rejecting more
     * sessions up-front); a smaller horizon shifts more of the load onto
     * the per-segment gate. 10 mirrors the prior implicit cap and keeps
     * the gate as the authoritative backstop for unbounded sessions.
     */
    const val INDEFINITE_LOOP_PREFLIGHT_HORIZON: Long = 10L

    /**
     * Per-resolution byte rate (BYTES/sec — NOT bits/sec). Conservative
     * upper bounds from ROADMAP §C7. Used by both preflight and the
     * per-segment gate.
     */
    fun bytesPerSecondForResolution(res: String): Long = when (res.uppercase()) {
        "4K", "UHD", "2160P" -> 10L * 1024 * 1024  // 10 MB/s — 4K HEVC upper bound
        "FHD", "1080P" -> 2L * 1024 * 1024         // 2 MB/s
        "HD", "720P" -> 1L * 1024 * 1024           // 1 MB/s
        "SD", "480P" -> 512L * 1024                // 0.5 MB/s
        else -> 2L * 1024 * 1024                   // default FHD
    }

    /**
     * Phase 1.6 peak-budget estimate. Returns the total disk bytes a session
     * may need across the entire capture+merge+publish pipeline for the
     * given [tier], not including per-segment safety headroom (caller adds
     * 50 MiB).
     *
     * Formula:
     * ```
     * captureBytes  = durationSeconds × loops × bytesPerSec(resolution)
     * peakBytes     = captureBytes × tier.peakBudgetMultiplier
     * ```
     *
     * Where `tier.peakBudgetMultiplier`:
     * - **Tier 1** ⇒ `2` (capture + final mux into the pending row).
     * - **Tier 2 / Tier 3** ⇒ `3` (capture + private merged + transient
     *   public copy `<name>.mp4.part`).
     *
     * Indefinite-loop sessions (`loopCount == -1`) reserve
     * [INDEFINITE_LOOP_PREFLIGHT_HORIZON] loops; the per-segment gate is
     * the authoritative backstop beyond that horizon.
     */
    fun estimatePeakBytes(
        durationSeconds: Long,
        loopCount: Int,
        resolution: String,
        tier: ExportTier
    ): Long {
        val loops = if (loopCount == -1) INDEFINITE_LOOP_PREFLIGHT_HORIZON else loopCount.toLong()
        val captureBytes = durationSeconds * loops * bytesPerSecondForResolution(resolution)
        return captureBytes * tier.peakBudgetMultiplier
    }

    /**
     * Phase 1.6 — live-session disk pressure for the per-segment storage
     * gate. Filesystem-first with `max(actual, estimated)` two-sided
     * conservatism.
     *
     * - **actual** = sum of `segment_*.mp4` lengths in [sessionDir].
     *   Captures finalized AND in-flight bytes the manifest hasn't seen
     *   yet (segment persistence is async on a serial dispatcher;
     *   between segments N and N+1 the manifest can report N records
     *   while disk holds N+1 files).
     * - **estimated** = `segmentCount × bytesPerSec × durationSeconds`.
     *   Bounds the case where actual < estimated (file flushed late) and
     *   where the recorder produced fewer segments than expected so far.
     *
     * Service-local: does NOT load the manifest. Counts only
     * `segment_*.mp4` — not a broad-match. If `listFiles()` returns null
     * or throws ([SecurityException]), falls back to the estimated lower
     * bound; the outer `StatFs` then fails-closed if the volume itself
     * is unstable.
     */
    fun accumulatedSessionBytes(
        sessionDir: File,
        segmentCount: Int,
        durationSeconds: Long,
        resolution: String
    ): Long {
        val segCount = segmentCount.coerceAtLeast(0).toLong()
        val bytesPerSec = bytesPerSecondForResolution(resolution)
        val estimated = segCount * durationSeconds * bytesPerSec
        val actual = try {
            sessionDir.listFiles { f ->
                f.isFile &&
                    f.name.startsWith("segment_") &&
                    f.name.endsWith(".mp4")
            }?.sumOf { it.length() } ?: return estimated
        } catch (e: SecurityException) {
            RovaLog.w("accumulatedSessionBytes: listFiles threw; falling back to estimate", e)
            return estimated
        }
        return maxOf(actual, estimated)
    }
}
