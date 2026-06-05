package com.aritr.rova.ui.vault

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class VaultToggleDecisionTest {
    @Test fun turningOff_requiresAuth() = assertTrue(toggleRequiresAuth(current = true, desired = false))
    @Test fun turningOn_isFree() = assertFalse(toggleRequiresAuth(current = false, desired = true))
    @Test fun noChange_isFree() {
        assertFalse(toggleRequiresAuth(current = true, desired = true))
        assertFalse(toggleRequiresAuth(current = false, desired = false))
    }
}
