package com.aritr.rova.ui.vault

enum class VaultAuthPath { BIOMETRIC_PROMPT, KEYGUARD_INTENT, UNAVAILABLE_NO_CREDENTIAL }

/**
 * B5 / ADR-0025 — auth path selection. BiometricPrompt device-credential
 * support is reliable on API 28+ (BIOMETRIC_STRONG or DEVICE_CREDENTIAL);
 * 24–27 fall back to KeyguardManager.createConfirmDeviceCredentialIntent.
 * No enrolled credential → vault cannot lock (spec §8; gallery-hiding still
 * works regardless).
 */
fun vaultAuthPath(sdkInt: Int, hasEnrolledCredential: Boolean): VaultAuthPath = when {
    !hasEnrolledCredential -> VaultAuthPath.UNAVAILABLE_NO_CREDENTIAL
    sdkInt >= 28 -> VaultAuthPath.BIOMETRIC_PROMPT
    else -> VaultAuthPath.KEYGUARD_INTENT
}
