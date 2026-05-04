package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Slice 13B — pin the snackbar copy so a future refactor cannot
 * silently change a user-visible string. Each branch of the formatter
 * has a dedicated case (no-op → null; success-only; mixed; all-fail).
 */
class RetentionCleanupNoticesTest {

    @Test
    fun `no-op notice yields null`() {
        assertNull(RetentionCleanupNotices.message(RetentionCleanupNotice(0, 0)))
    }

    @Test
    fun `success-only notice uses Auto-deleted copy`() {
        assertEquals(
            "Auto-deleted 1 old recording(s)",
            RetentionCleanupNotices.message(RetentionCleanupNotice(deleted = 1, failed = 0))
        )
        assertEquals(
            "Auto-deleted 7 old recording(s)",
            RetentionCleanupNotices.message(RetentionCleanupNotice(deleted = 7, failed = 0))
        )
    }

    @Test
    fun `mixed notice uses partial-failure copy`() {
        assertEquals(
            "Auto-delete removed 3 recording(s); 2 failed",
            RetentionCleanupNotices.message(RetentionCleanupNotice(deleted = 3, failed = 2))
        )
    }

    @Test
    fun `all-fail notice still uses partial-failure copy with deleted zero`() {
        // The partial-failure branch absorbs the all-fail case (d=0, f>0)
        // so the user always sees the failure count when anything failed.
        assertEquals(
            "Auto-delete removed 0 recording(s); 4 failed",
            RetentionCleanupNotices.message(RetentionCleanupNotice(deleted = 0, failed = 4))
        )
    }

    @Test
    fun `single failure still uses parenthesized plural marker`() {
        // Plural via "(s)" is intentional — keeps the formatter free of
        // inflection branching and matches the product copy spec.
        assertEquals(
            "Auto-delete removed 0 recording(s); 1 failed",
            RetentionCleanupNotices.message(RetentionCleanupNotice(deleted = 0, failed = 1))
        )
    }
}
