package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BentoRowPlannerTest {

    private fun singles(n: Int) = List(n) { false }

    @Test fun `featured day with 3+ sessions leads with the hero`() {
        val plan = BentoRowPlanner.plan(singles(4), dayAge = 0)
        assertEquals(listOf(6), plan.first().spans)
        assertEquals(208, plan.first().heightDp)
    }

    @Test fun `featured day with 2 sessions renders a pair not a hero`() {
        val plan = BentoRowPlanner.plan(singles(2), dayAge = 0)
        assertEquals(1, plan.size)
        assertEquals(2, plan.first().spans.size) // [3,3] pair — frozen owner ruling
    }

    @Test fun `every plan covers exactly the session count`() {
        for (age in listOf(0, 1, 2, 6, 7, 30)) for (n in 1..9) {
            val plan = BentoRowPlanner.plan(singles(n), age)
            assertEquals("age=$age n=$n", n, plan.sumOf { it.spans.size })
        }
    }

    @Test fun `single leftover takes the tier fill row`() {
        val plan = BentoRowPlanner.plan(singles(1), dayAge = 7)
        assertEquals(listOf(listOf(6)), plan.map { it.spans })
        assertEquals(108, plan.first().heightDp) // archive fill height
    }

    @Test fun `every DualShot session lands on a span of at least 3`() {
        for (age in listOf(0, 2, 7)) for (n in 2..8) for (dualAt in 0 until n) {
            val flags = List(n) { it == dualAt }
            val plan = BentoRowPlanner.plan(flags, age)
            var i = 0
            for (row in plan) for (span in row.spans) {
                if (i < n && flags[i]) assertTrue("age=$age n=$n dualAt=$dualAt", span >= 3)
                i++
            }
        }
    }

    @Test fun `same age same content is deterministic`() {
        assertEquals(BentoRowPlanner.plan(singles(6), 3), BentoRowPlanner.plan(singles(6), 3))
    }

    @Test fun `tier heights match the frozen tables`() {
        assertTrue(BentoRowPlanner.plan(singles(3), 0).drop(1).all { it.heightDp in setOf(152, 164, 192) })
        assertTrue(BentoRowPlanner.plan(singles(6), 3).all { it.heightDp in setOf(148, 128, 104) })
        assertTrue(BentoRowPlanner.plan(singles(6), 9).all { it.heightDp in setOf(92, 108) })
    }
}
