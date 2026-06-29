package com.aritr.rova.service.dualrecord.internal

/**
 * DualShot fps-cadence diagnosis (2026-06-29) — an observer-effect-safe,
 * single-writer sample ring. One instance per hot thread (the [EglRouter]
 * callback thread and each [EncoderRenderThread]); the per-frame [record]
 * is a single masked array store — no allocation, no lock, no I/O — so the
 * probe does not perturb the cadence it measures. Aggregation ([snapshot]
 * → [CadenceStats]) and formatting happen once at segment/record stop, off
 * the hot path. Pure JVM (no Android types) → unit-tested (CadenceProbeTest).
 * See the 2026-06-29 fps-cadence diagnosis spec §5.1. NOT thread-safe by
 * design: each probe has exactly one writer thread.
 */
internal class CadenceProbe(capacityPow2: Int = 512) {

    init {
        require(capacityPow2 > 0 && (capacityPow2 and (capacityPow2 - 1)) == 0) {
            "capacity must be a positive power of two, was $capacityPow2"
        }
    }

    private val mask = capacityPow2 - 1
    private val ring = LongArray(capacityPow2)
    private var writeIdx = 0

    /** Hot path — record one sample. Single masked store + index bump. */
    fun record(value: Long) {
        ring[writeIdx++ and mask] = value
    }

    /** Total samples recorded (may exceed capacity once the ring wrapped). */
    fun recorded(): Int = writeIdx

    /**
     * Valid samples in write order (oldest→newest), capped at capacity.
     * Allocates a fresh array — call OFF the hot path (segment stop).
     */
    fun snapshot(): LongArray {
        val cap = ring.size
        val n = if (writeIdx < cap) writeIdx else cap
        val out = LongArray(n)
        if (writeIdx <= cap) {
            System.arraycopy(ring, 0, out, 0, n)
        } else {
            val start = writeIdx and mask
            for (i in 0 until n) out[i] = ring[(start + i) and mask]
        }
        return out
    }

    /** Discard recorded samples (reset the write index). Call off the hot path. */
    fun reset() {
        writeIdx = 0
    }
}
