package com.aritr.rova.service.export

import com.aritr.rova.data.VaultState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportCleanupPredicateVaultTest {
    @Test fun vaultedWithFile_isKept() = assertTrue(isVaultKeptArtifact(VaultState.VAULTED, "/v/x.mp4"))
    @Test fun vaultingWithFile_isKept() = assertTrue(isVaultKeptArtifact(VaultState.VAULTING, "/v/x.mp4"))
    @Test fun unvaultingWithFile_isKept() = assertTrue(isVaultKeptArtifact(VaultState.UNVAULTING, "/v/x.mp4"))
    @Test fun publicNoFile_isNotKept() = assertFalse(isVaultKeptArtifact(VaultState.PUBLIC, null))
    @Test fun publicWithStrayFile_isNotKept() = assertFalse(isVaultKeptArtifact(VaultState.PUBLIC, "/v/x.mp4"))
    @Test fun vaultedNoFile_isNotKept() = assertFalse(isVaultKeptArtifact(VaultState.VAULTED, null))
}
