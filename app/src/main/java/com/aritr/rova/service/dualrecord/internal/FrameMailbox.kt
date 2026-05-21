package com.aritr.rova.service.dualrecord.internal

/**
 * DualShot render threading (2026-05-21) — a single-slot, latest-wins
 * rendezvous between the [EglRouter] callback thread (producer) and one
 * [EncoderRenderThread] (consumer).
 *
 *  - [offer] overwrites the slot. An unread frame is discarded so the
 *    consumer always draws the newest camera frame — this is the
 *    per-side frame-drop, achieved with no explicit drop logic.
 *  - [poison] is the shutdown signal. Once poisoned, [take] returns null
 *    forever and [offer] is a no-op. Poison wins over a pending slot.
 *  - [take] blocks until an item is offered or the mailbox is poisoned.
 *
 * Pure JVM — no Android types — so it is unit-tested directly
 * (FrameMailboxTest), unlike the EGL/GL threads that use it. See the
 * 2026-05-21 render-threading design doc §6.
 */
internal class FrameMailbox<T : Any> {

    private val lock = java.lang.Object()
    private var slot: T? = null
    private var poisoned = false

    /** Overwrite the slot with [item] and wake a waiting [take]. No-op once poisoned. */
    fun offer(item: T) {
        synchronized(lock) {
            if (poisoned) return
            slot = item
            lock.notifyAll()
        }
    }

    /** Shutdown signal — wakes a waiting [take], which then returns null. */
    fun poison() {
        synchronized(lock) {
            poisoned = true
            lock.notifyAll()
        }
    }

    /**
     * Block until an item is offered or the mailbox is poisoned. Returns
     * the latest item, or null iff poisoned (poison wins over a pending
     * slot, so a poisoned mailbox never delivers a stale frame).
     */
    fun take(): T? {
        synchronized(lock) {
            while (slot == null && !poisoned) lock.wait()
            if (poisoned) return null
            val item = slot
            slot = null
            return item
        }
    }
}
