package com.aritr.rova.service.export

import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierArtifactValidatorSafTest {
    private fun m(doc: String?) = SessionManifest("s", 0, SessionConfig(1, 1, "x", 1),
        emptyList(), ExportTier.SAF_DESTINATION, safTargetDocUri = doc)

    @Test fun saf_valid_when_probe_true() {
        assertTrue(TierArtifactValidator.isArtifactValid(m("content://d"), tier1Probe = { false }, safProbe = { true }))
    }
    @Test fun saf_invalid_when_doc_null() {
        assertFalse(TierArtifactValidator.isArtifactValid(m(null), tier1Probe = { false }, safProbe = { true }))
    }
}
