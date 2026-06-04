package com.aritr.rova.service.export

import com.aritr.rova.data.VaultState
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportRecoveryVaultTest {
    @Test fun unfinishedVaultIntent_reMergesToVault() {
        assertEquals(VaultRecoveryAction.MERGE_TO_VAULT,
            vaultRecoveryAction(vaultIntentAtStart = true, state = VaultState.PUBLIC, finalized = false))
    }
    @Test fun vaulting_resumesDeletePublicThenVaulted() {
        assertEquals(VaultRecoveryAction.RESUME_VAULTING,
            vaultRecoveryAction(vaultIntentAtStart = true, state = VaultState.VAULTING, finalized = false))
    }
    @Test fun unvaulting_resumesPublish() {
        assertEquals(VaultRecoveryAction.RESUME_UNVAULTING,
            vaultRecoveryAction(vaultIntentAtStart = false, state = VaultState.UNVAULTING, finalized = false))
    }
    @Test fun vaulted_noAction() {
        assertEquals(VaultRecoveryAction.NONE,
            vaultRecoveryAction(vaultIntentAtStart = true, state = VaultState.VAULTED, finalized = true))
    }
    @Test fun normalPublic_noVaultAction() {
        assertEquals(VaultRecoveryAction.NONE,
            vaultRecoveryAction(vaultIntentAtStart = false, state = VaultState.PUBLIC, finalized = false))
    }
}
