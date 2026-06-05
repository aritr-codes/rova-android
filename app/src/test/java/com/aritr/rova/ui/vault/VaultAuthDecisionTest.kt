package com.aritr.rova.ui.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultAuthDecisionTest {
    @Test fun api28Plus_usesBiometricPrompt() =
        assertEquals(VaultAuthPath.BIOMETRIC_PROMPT, vaultAuthPath(sdkInt = 28, hasEnrolledCredential = true))
    @Test fun api24to27_usesKeyguardIntent() =
        assertEquals(VaultAuthPath.KEYGUARD_INTENT, vaultAuthPath(sdkInt = 25, hasEnrolledCredential = true))
    @Test fun noCredential_isUnavailable() =
        assertEquals(VaultAuthPath.UNAVAILABLE_NO_CREDENTIAL, vaultAuthPath(sdkInt = 30, hasEnrolledCredential = false))
    @Test fun api27WithCredential_isKeyguard() =
        assertEquals(VaultAuthPath.KEYGUARD_INTENT, vaultAuthPath(sdkInt = 27, hasEnrolledCredential = true))
}
