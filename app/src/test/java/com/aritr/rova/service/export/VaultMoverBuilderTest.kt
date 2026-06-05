package com.aritr.rova.service.export

import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5 / ADR-0025 (Task 22) — pure decision: only single-mode sessions are
 * movable through [VaultAndroidOps] (P+L carries per-side pointers the
 * single-mode dispatchers can't resolve, so the UI + recovery move paths
 * must gate them out).
 */
class VaultMoverBuilderTest {

    private fun manifest(mode: String) = SessionManifest(
        sessionId = "s1",
        startedAt = 1000L,
        config = SessionConfig(10, 1, "HD", 10, mode),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS,
    )

    @Test fun portrait_isMovable() {
        assertTrue(VaultMoverBuilder.isSingleModeMovable(manifest("Portrait")))
    }

    @Test fun landscape_isMovable() {
        assertTrue(VaultMoverBuilder.isSingleModeMovable(manifest("Landscape")))
    }

    @Test fun portraitLandscape_isNotMovable() {
        assertFalse(VaultMoverBuilder.isSingleModeMovable(manifest("PortraitLandscape")))
    }
}
