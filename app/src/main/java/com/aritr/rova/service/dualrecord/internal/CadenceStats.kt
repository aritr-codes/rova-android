package com.aritr.rova.service.dualrecord.internal

/**
 * DualShot fps-cadence diagnosis (2026-06-29) — pure statistics over raw
 * timestamp / duration samples gathered by [CadenceProbe]. Framework-free
 * so it is unit-tested directly (CadenceStatsTest), unlike the GL/encoder
 * threads that feed it. See the 2026-06-29 fps-cadence diagnosis spec §5.4.
 */
internal object CadenceStats {

    data class Summary(
        val count: Int,
        val medianNs: Long,
        val p95Ns: Long,
        val minNs: Long,
        val maxNs: Long,
    )

    private val EMPTY = Summary(0, 0L, 0L, 0L, 0L)

    /**
     * Successive positive deltas of [raw] over [count] entries starting at
     * [from]. A pair where `cur <= prev` (clock reset, duplicate, or ring
     * wrap) is skipped — the delta chain continues from `cur`. Fewer than
     * two entries → empty. Used for the camera HW-timestamp and wall-clock
     * arrival series (cadence). Service/duration series are already deltas
     * and go straight to [summarize].
     */
    fun deltas(raw: LongArray, from: Int, count: Int): LongArray {
        if (count <= 1) return LongArray(0)
        val out = ArrayList<Long>(count - 1)
        var prev = raw[from]
        for (i in (from + 1) until (from + count)) {
            val cur = raw[i]
            if (cur > prev) out.add(cur - prev)
            prev = cur
        }
        return out.toLongArray()
    }

    /**
     * Median / p95 / min / max over [values] (already deltas or durations).
     * Median = upper-middle element (`size/2` after sort). p95 = nearest-rank
     * (`ceil(0.95 * size) - 1`). Empty → all-zero [Summary]. Allocates (sorts
     * a copy) — call OFF the hot path.
     */
    fun summarize(values: LongArray): Summary {
        if (values.isEmpty()) return EMPTY
        val s = values.copyOf()
        s.sort()
        val median = s[s.size / 2]
        val p95Idx = (((95 * s.size + 99) / 100) - 1).coerceIn(0, s.size - 1)
        return Summary(s.size, median, s[p95Idx], s.first(), s.last())
    }
}
