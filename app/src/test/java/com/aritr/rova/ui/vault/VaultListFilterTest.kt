package com.aritr.rova.ui.vault

import com.aritr.rova.data.VaultState
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultListFilterTest {
    @Test fun libraryShowsOnlyPublic() {
        assertEquals(true, isLibraryVisible(VaultState.PUBLIC))
        assertEquals(false, isLibraryVisible(VaultState.VAULTING))
        assertEquals(false, isLibraryVisible(VaultState.VAULTED))
        assertEquals(false, isLibraryVisible(VaultState.UNVAULTING))
    }
    @Test fun vaultShowsVaultedAndUnvaulting() {
        assertEquals(false, isVaultVisible(VaultState.PUBLIC))
        assertEquals(false, isVaultVisible(VaultState.VAULTING))
        assertEquals(true, isVaultVisible(VaultState.VAULTED))
        assertEquals(true, isVaultVisible(VaultState.UNVAULTING))
    }
}
