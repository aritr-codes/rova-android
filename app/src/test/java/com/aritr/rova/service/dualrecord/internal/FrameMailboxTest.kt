package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JVM unit tests for [FrameMailbox]. This is the only unit-tested piece
 * of the DualShot render-threading core — the EGL/GL threads around it
 * are the runtime layer (no unit tests). Covers latest-wins overwrite,
 * the poison-pill shutdown signal, and the blocking [FrameMailbox.take].
 */
class FrameMailboxTest {

    @Test
    fun offer_thenTake_returnsTheItem() {
        val mb = FrameMailbox<String>()
        mb.offer("a")
        assertEquals("a", mb.take())
    }

    @Test
    fun offerTwice_take_returnsLatestOnly() {
        val mb = FrameMailbox<String>()
        mb.offer("stale")
        mb.offer("fresh")
        assertEquals("fresh", mb.take())
    }

    @Test
    fun poison_thenTake_returnsNull() {
        val mb = FrameMailbox<String>()
        mb.poison()
        assertNull(mb.take())
    }

    @Test
    fun poison_winsOverAPendingSlot() {
        val mb = FrameMailbox<String>()
        mb.offer("pending")
        mb.poison()
        assertNull(mb.take())
    }

    @Test
    fun offer_afterPoison_isNoOp() {
        val mb = FrameMailbox<String>()
        mb.poison()
        mb.offer("ignored")
        assertNull(mb.take())
    }

    @Test(timeout = 2000)
    fun take_blocksUntilOffer() {
        val mb = FrameMailbox<String>()
        val started = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        val consumer = Thread {
            started.countDown()
            result[0] = mb.take()
        }
        consumer.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)            // consumer is now parked inside take()
        mb.offer("delivered")
        consumer.join(1000)
        assertEquals("delivered", result[0])
    }

    @Test(timeout = 2000)
    fun take_unblocksOnPoison() {
        val mb = FrameMailbox<String>()
        val result = arrayOfNulls<String>(1)
        val done = CountDownLatch(1)
        val consumer = Thread {
            result[0] = mb.take()
            done.countDown()
        }
        consumer.start()
        Thread.sleep(50)
        mb.poison()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertNull(result[0])
    }
}
