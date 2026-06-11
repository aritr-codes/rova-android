package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionManifestVaultTest {

    private fun baseManifest() = SessionManifest(
        sessionId = "s1",
        startedAt = 1000L,
        config = SessionConfig(10, 1, "HD", 10),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS,
    )

    @Test
    fun defaults_areNonVault() {
        val m = baseManifest()
        assertFalse(m.vaultIntentAtStart)
        assertEquals(VaultState.PUBLIC, m.vaultState)
        assertEquals(null, m.vaultFilePath)
    }

    @Test
    fun roundTrip_preservesVaultFields() {
        val m = baseManifest().copy(
            vaultIntentAtStart = true,
            vaultState = VaultState.VAULTED,
            vaultFilePath = "/data/user/0/com.aritr.rova/files/videos/s1/Rova_x.mp4",
        )
        val back = SessionManifest.fromJson(m.toJson())
        assertEquals(true, back.vaultIntentAtStart)
        assertEquals(VaultState.VAULTED, back.vaultState)
        assertEquals(m.vaultFilePath, back.vaultFilePath)
    }

    @Test
    fun tolerantRead_oldManifestHasNoVaultKeys_defaultsPublic() {
        val json: JSONObject = baseManifest().toJson()
        json.remove("vaultIntentAtStart")
        json.remove("vaultState")
        json.remove("vaultFilePath")
        json.put("schemaVersion", 6)
        val back = SessionManifest.fromJson(json)
        assertFalse(back.vaultIntentAtStart)
        assertEquals(VaultState.PUBLIC, back.vaultState)
        assertEquals(null, back.vaultFilePath)
    }

    @Test
    fun schemaVersion_isTen() {
        // 9 -> 10: ADR-0029 PR-α per-segment effectiveTargetRotation.
        assertEquals(10, SessionManifest.SCHEMA_VERSION)
    }

    // B5 / ADR-0025 commit-before-finalize follow-up: the in-flight public
    // pointer committed on move-OUT before the irreversible Tier1 finalize /
    // pre-Q rename, so a crash-resume can dedup instead of double-publishing.
    @Test
    fun roundTrip_preservesPendingMoveOutFields() {
        val m = baseManifest().copy(
            pendingMoveOutUri = "content://media/external/video/77",
            pendingMoveOutPath = "/storage/emulated/0/Movies/Rova/Rova_x.mp4.part",
        )
        val back = SessionManifest.fromJson(m.toJson())
        assertEquals("content://media/external/video/77", back.pendingMoveOutUri)
        assertEquals("/storage/emulated/0/Movies/Rova/Rova_x.mp4.part", back.pendingMoveOutPath)
    }

    @Test
    fun tolerantRead_oldManifestHasNoPendingMoveOutKeys_defaultsNull() {
        val json: JSONObject = baseManifest().toJson()
        json.remove("pendingMoveOutUri")
        json.remove("pendingMoveOutPath")
        json.put("schemaVersion", 7)
        val back = SessionManifest.fromJson(json)
        assertEquals(null, back.pendingMoveOutUri)
        assertEquals(null, back.pendingMoveOutPath)
    }

    @Test
    fun defaults_pendingMoveOutFieldsNull() {
        val m = baseManifest()
        assertEquals(null, m.pendingMoveOutUri)
        assertEquals(null, m.pendingMoveOutPath)
    }
}
