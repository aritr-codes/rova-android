package com.aritr.rova.ui.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultLockStateTest {
    @Test fun startsLocked() = assertFalse(VaultLockState.initial().unlocked)
    @Test fun authSuccessUnlocks() =
        assertTrue(VaultLockState.initial().onAuthSucceeded().unlocked)
    @Test fun backgroundRelocks() =
        assertFalse(VaultLockState.initial().onAuthSucceeded().onAppBackgrounded().unlocked)
    @Test fun leaveRouteRelocks() =
        assertFalse(VaultLockState.initial().onAuthSucceeded().onLeaveVault().unlocked)
}
