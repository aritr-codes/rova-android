package com.aritr.rova.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5 / ADR-0025 — locks the fail-closed vault-privacy invariant for the History
 * legacy file-system scan: a manifest-backed session dir is owned by the
 * manifest-driven path and must never be re-surfaced by the vaultState-blind
 * legacy fallback.
 */
class LegacyScanPolicyTest {

    @Test
    fun manifestBackedDir_isExcludedFromLegacyScan() {
        // A dir with a manifest (modern session, incl. every vaulted recording)
        // is owned by the manifest-driven path — the legacy scan skips it.
        assertFalse(legacyScanIncludesSessionDir(hasManifest = true))
    }

    @Test
    fun manifestlessDir_isIncludedInLegacyScan() {
        // A truly manifest-less pre-Phase-1.7 dir is the legacy scan's only job.
        assertTrue(legacyScanIncludesSessionDir(hasManifest = false))
    }
}
