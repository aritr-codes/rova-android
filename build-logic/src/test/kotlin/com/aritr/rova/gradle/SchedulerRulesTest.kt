package com.aritr.rova.gradle

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerRulesTest {

    private fun src(relPath: String, body: String) =
        SourceFile(relPath, body.split("\n"), body)

    @Test
    fun passesOnCleanScheduler() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/scheduler/AlarmScheduler.kt",
            "fun schedule() { PendingIntent.getBroadcast(ctx, 0, i, 0) }"
        ))
        assertNull(RovaGateRules.run("checkSchedulerNoGetService", files))
    }

    @Test
    fun failsOnGetService() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/scheduler/AlarmScheduler.kt",
            "val x = PendingIntent.getService(ctx, 0, i, 0)"
        ))
        val msg = RovaGateRules.run("checkSchedulerNoGetService", files)
        assertTrue(
            msg != null &&
                msg.startsWith("PendingIntent.getService is forbidden in alarm scheduler sources")
        )
    }

    @Test
    fun skipsCommentedGetService() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/scheduler/AlarmScheduler.kt",
            "// PendingIntent.getService(ctx, 0, i, 0)"
        ))
        assertNull(RovaGateRules.run("checkSchedulerNoGetService", files))
    }

    @Test
    fun failsOnGetServiceAfterBlockCommentClose() {
        // `*/ <code>` — raw trimStart begins with `*`, legacy skipped it (false-pass).
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/scheduler/AlarmScheduler.kt",
            "    */ val x = PendingIntent.getService(ctx, 0, i, 0)"
        ))
        val msg = RovaGateRules.run("checkSchedulerNoGetService", files)
        assertTrue(msg != null &&
            msg.startsWith("PendingIntent.getService is forbidden in alarm scheduler sources"))
    }
}
