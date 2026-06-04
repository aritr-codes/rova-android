package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionManifestVaultTest {

    private fun baseManifest() = SessionManifest(
        sessionId = "s1",
        startedAt = 1000L,
        config = SessionConfig(10, 1, "HD", 10, "Portrait"),
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
    fun schemaVersion_isSeven() {
        assertEquals(7, SessionManifest.SCHEMA_VERSION)
    }
}
